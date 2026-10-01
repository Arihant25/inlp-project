"""
RQ5: Retrieval-augmented code generation.

The study uses gemma4:31b, gpt-oss:20b, and deepseek-v4.1-flash on Ollama Cloud
(greedy decoding, seed 0, at most 65,536 output tokens).
Google AI Studio models are also supported.

For every problem we ask an LLM to implement the function, optionally showing
one retrieved code snippet as context. A context is identified by the document
id of the retrieved snippet (e.g. "12_buggy"), or "none" for no context.
Responses are cached in results/rq5/generations/<llm>/<lang>.jsonl, so each
(problem, context) pair is generated once and reused by every retriever.

Usage:
    python generate.py --llm ollama:gemma4:31b --lang python --contexts core
    python generate.py --llm ollama:gpt-oss:20b --lang java --contexts needed

Requires GEMINI_API_KEY (Google AI Studio) or OLLAMA_API_KEY (Ollama Cloud)
in the repository's .env file.
"""

import argparse
import json
import os
import random
import re
import threading
import time
from concurrent.futures import ThreadPoolExecutor

import requests

from common import PROJECT_ROOT, RESULTS_DIR, build_corpus, load_language

LANG_NAMES = {"python": "Python", "js": "JavaScript", "java": "Java", "classeval": "Python"}
FENCE = {"python": "python", "js": "javascript", "java": "java", "classeval": "python"}
EXCLUDED: set[tuple[str, int]] = set()  # JS/162 needs js-md5, installed via code/rq5/package.json

API_URL = "https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent"


OLLAMA_URL = "https://ollama.com/api/chat"
# Output cap, above every completed generation (max 42,532 tokens), so it only ends
# degenerate reasoning loops. A response that reaches the cap counts as a failure.
MAX_TOKENS = 65536


def api_key(name: str = "GEMINI_API_KEY") -> str:
    with open(os.path.join(PROJECT_ROOT, ".env"), encoding="utf-8") as f:
        for line in f:
            if line.startswith(name + "="):
                return line.strip().split("=", 1)[1]
    raise RuntimeError(f"{name} missing from .env")


def call_ollama(model: str, prompt: str, key: str, max_retries: int = 20) -> dict:
    """Call an Ollama Cloud model (greedy decoding, fixed seed).

    The response is streamed, so long generations (reasoning models can write tens of
    thousands of tokens) complete in one request instead of timing out and being retried.
    """
    body = {"model": model, "stream": True, "options": {"temperature": 0, "seed": 0, "num_predict": MAX_TOKENS},
            "messages": [{"role": "user", "content": prompt}]}
    delay = 5.0
    for _ in range(max_retries):
        try:
            with requests.post(OLLAMA_URL, json=body, stream=True, timeout=(30, 600),
                               headers={"Authorization": f"Bearer {key}"}) as r:
                if r.status_code == 200:
                    parts, last = [], {}
                    for line in r.iter_lines():
                        if not line:
                            continue
                        last = json.loads(line)
                        parts.append(last.get("message", {}).get("content", ""))
                        if last.get("done"):
                            break
                    if not last.get("done"):
                        raise requests.RequestException("stream ended early")
                    return {"text": "".join(parts), "finish": last.get("done_reason"),
                            "usage": {"promptTokenCount": last.get("prompt_eval_count"),
                                      "candidatesTokenCount": last.get("eval_count")}}
                if r.status_code in (429, 500, 502, 503, 504):
                    print(f"  HTTP {r.status_code}, retrying in {delay:.0f}s", flush=True)
                    time.sleep(delay + random.random() * 2)
                    delay = min(delay * 2, 120)
                    continue
                return {"error": f"HTTP {r.status_code}: {r.text[:300]}"}
        except requests.RequestException:
            time.sleep(delay)
    return {"error": "retries exhausted"}


def build_prompt(lang: str, row, context_code: str | None) -> str:
    name = LANG_NAMES[lang]
    parts = [f"You are writing code in a {name} codebase.", "", "Task:", row["instruction"].strip(), ""]
    if context_code is not None:
        parts += [
            "A code search over the codebase returned the following snippet, which may be relevant:",
            f"```{FENCE[lang]}",
            context_code,
            "```",
            "",
        ]
    if lang == "java":
        fmt = "the complete `class Solution` (with any imports) containing the method"
    elif lang == "classeval":
        fmt = f"the complete class `{row['entry_point']}` with all of its methods (and any imports)"
    else:
        fmt = f"the complete function `{row['entry_point']}` and any helper functions it needs"
    parts.append(f"Reply with a single {name} code block with {fmt}. Do not include tests or example usage.")
    return "\n".join(parts)


def extract_code(lang: str, text: str) -> str:
    blocks = re.findall(r"```[a-zA-Z0-9_+-]*\n(.*?)```", text, flags=re.S)
    if not blocks:
        return text.strip()
    return max(blocks, key=len).strip()


def call_llm(model: str, prompt: str, key: str, max_retries: int = 40) -> dict:
    """Call the model; retry transient server errors quickly and back off on rate limits."""
    body = {
        "contents": [{"role": "user", "parts": [{"text": prompt}]}],
        "generationConfig": {"temperature": 0, "maxOutputTokens": 8192},
    }
    rate_delay = 10.0
    for attempt in range(max_retries):
        try:
            r = requests.post(
                API_URL.format(model=model), json=body, timeout=240,
                headers={"x-goog-api-key": key, "Content-Type": "application/json"},
            )
            if r.status_code == 200:
                d = r.json()
                cand = d.get("candidates", [{}])[0]
                parts = cand.get("content", {}).get("parts", [])
                text = "".join(p.get("text", "") for p in parts if not p.get("thought"))
                if not text.strip():
                    time.sleep(1 + random.random())
                    continue
                return {"text": text, "finish": cand.get("finishReason"), "usage": d.get("usageMetadata", {})}
            if r.status_code == 429:
                time.sleep(rate_delay + random.random() * 5)
                rate_delay = min(rate_delay * 2, 120)
                continue
            if r.status_code in (500, 502, 503, 504):
                time.sleep(1 + random.random() * 2)
                continue
            return {"error": f"HTTP {r.status_code}: {r.text[:300]}"}
        except requests.RequestException:
            time.sleep(2 + random.random() * 2)
    return {"error": "retries exhausted"}


def cache_path(llm: str, lang: str) -> str:
    return os.path.join(RESULTS_DIR, "generations", llm, f"{lang}.jsonl")


def load_cache(llm: str, lang: str) -> dict:
    path = cache_path(llm, lang)
    cache = {}
    if os.path.exists(path):
        with open(path, encoding="utf-8") as f:
            for line in f:
                rec = json.loads(line)
                if "error" not in rec:
                    cache[(rec["problem"], rec["context"])] = rec
    return cache


def needed_contexts(lang: str) -> set[tuple[int, str]]:
    """Contexts required by the retrieval results (top-1 of every retriever and filtered picks)."""
    path = os.path.join(RESULTS_DIR, "retrieval", f"{lang}_contexts.json")
    with open(path, encoding="utf-8") as f:
        return {(int(p), c) for p, c in json.load(f)}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--llm", required=True)
    ap.add_argument("--lang", required=True, choices=list(LANG_NAMES))
    ap.add_argument("--contexts", choices=["core", "needed"], default="core")
    ap.add_argument("--workers", type=int, default=8)
    ap.add_argument("--limit", type=int, default=None)
    args = ap.parse_args()

    df = load_language(args.lang)
    rows = {int(r["problem"]): r for _, r in df.iterrows()}
    docs, _ = build_corpus(args.lang)
    code_by_id = dict(zip(docs["doc_id"], docs["code"]))

    if args.contexts == "core":
        todo = {(p, c) for p in rows for c in ("none", f"{p}_correct", f"{p}_buggy")}
    else:
        todo = needed_contexts(args.lang)
    todo = {t for t in todo if (args.lang, t[0]) not in EXCLUDED}
    cache = load_cache(args.llm.replace(":", "_"), args.lang)
    todo = sorted(t for t in todo if t not in cache)
    if args.limit:
        todo = todo[: args.limit]
    print(f"{args.llm} {args.lang}: {len(todo)} generations to run ({len(cache)} cached)")

    # "ollama:<model>" selects Ollama Cloud, anything else Google AI Studio.
    if args.llm.startswith("ollama:"):
        key, model_name = api_key("OLLAMA_API_KEY"), args.llm.split(":", 1)[1]
        caller = lambda prompt: call_ollama(model_name, prompt, key)
    else:
        key = api_key()
        caller = lambda prompt: call_llm(args.llm, prompt, key)
    path = cache_path(args.llm.replace(":", "_"), args.lang)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    lock = threading.Lock()
    done = [0]

    def work(item):
        problem, context = item
        ctx_code = None if context == "none" else code_by_id[context]
        prompt = build_prompt(args.lang, rows[problem], ctx_code)
        res = caller(prompt)
        rec = {"problem": problem, "context": context, "llm": args.llm, **res}
        if "text" in res:
            rec["code"] = extract_code(args.lang, res["text"])
        with lock:
            with open(path, "a", encoding="utf-8") as f:
                f.write(json.dumps(rec) + "\n")
            done[0] += 1
            if done[0] % 50 == 0:
                print(f"  {done[0]}/{len(todo)}", flush=True)

    with ThreadPoolExecutor(max_workers=args.workers) as ex:
        list(ex.map(work, todo))
    print("finished")


if __name__ == "__main__":
    main()

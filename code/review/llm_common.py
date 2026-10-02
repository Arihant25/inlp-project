"""
Shared helpers for the reviewer-requested RQ5/RQ3 experiments (code/review/).

- Reads the Ollama Cloud key OLLAMA_API_KEY_2 from the repository's .env (never printed).
- `usage()` returns the key's weekly and session usage fractions (https://ollama.com/api/usage).
- `call(model, prompt, temperature, seed)` is a copy of code/rq5/generate.py:call_ollama
  that takes temperature and seed arguments (streaming, num_predict 65,536, retries).
- `run_jobs(jobs, out_path)` runs prompts with at most 4 requests in flight and appends
  every response to a jsonl cache, so interrupted runs resume where they stopped.

Every script in this folder that calls the API is named llm_*.py, so the budget guard
can stop it if the weekly usage reaches the cap.
"""

import json
import os
import random
import sys
import threading
import time
from concurrent.futures import ThreadPoolExecutor

import requests

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
sys.path.insert(0, os.path.join(ROOT, "code", "rq5"))
from generate import MAX_TOKENS, OLLAMA_URL, api_key, build_prompt, extract_code  # noqa: E402,F401

OUT_DIR = os.path.join(ROOT, "results", "review")
KEY = api_key("OLLAMA_API_KEY_2")
MODELS = ["gemma4:31b", "gpt-oss:20b", "deepseek-v4.1-flash"]
MAX_IN_FLIGHT = 4
WEEKLY_STOP = 0.47  # jobs stop submitting new requests above this weekly fraction


def usage() -> dict:
    r = requests.get("https://ollama.com/api/usage", headers={"Authorization": f"Bearer {KEY}"}, timeout=30)
    d = r.json()["limits"]
    return {"weekly": d["weekly"]["usage"], "session": d["session"]["usage"]}


def call(model: str, prompt: str, temperature: float = 0.0, seed: int = 0, max_retries: int = 20) -> dict:
    """generate.call_ollama with temperature and seed as arguments."""
    body = {"model": model, "stream": True,
            "options": {"temperature": temperature, "seed": seed, "num_predict": MAX_TOKENS},
            "messages": [{"role": "user", "content": prompt}]}
    delay = 5.0
    for _ in range(max_retries):
        try:
            with requests.post(OLLAMA_URL, json=body, stream=True, timeout=(30, 600),
                               headers={"Authorization": f"Bearer {KEY}"}) as r:
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


def load_jsonl(path: str) -> list[dict]:
    if not os.path.exists(path):
        return []
    with open(path, encoding="utf-8") as f:
        return [json.loads(l) for l in f if l.strip()]


def run_jobs(jobs: list[dict], out_path: str, extract: bool = True) -> None:
    """jobs: dicts with keys id, model, prompt, temperature, seed (+ any metadata).

    Skips ids already in out_path without error; appends results. Stops submitting
    new jobs when the weekly usage exceeds WEEKLY_STOP (checked every 20 calls).
    """
    os.makedirs(os.path.dirname(out_path), exist_ok=True)
    done = {r["id"] for r in load_jsonl(out_path) if "error" not in r}
    todo = [j for j in jobs if j["id"] not in done]
    print(f"{out_path}: {len(todo)} to run ({len(done)} cached)", flush=True)
    lock = threading.Lock()
    state = {"n": 0, "stop": False}

    def work(job):
        if state["stop"]:
            return
        res = call(job["model"], job["prompt"], job.get("temperature", 0.0), job.get("seed", 0))
        rec = {k: v for k, v in job.items() if k != "prompt"} | res
        if extract and "text" in res:
            rec["code"] = extract_code("classeval", res["text"])
        with lock:
            with open(out_path, "a", encoding="utf-8") as f:
                f.write(json.dumps(rec) + "\n")
            state["n"] += 1
            if state["n"] % 20 == 0:
                try:
                    u = usage()
                    print(f"  {state['n']}/{len(todo)} weekly={u['weekly']:.4f}", flush=True)
                    if u["weekly"] >= WEEKLY_STOP:
                        print("  weekly usage above stop threshold; stopping", flush=True)
                        state["stop"] = True
                except Exception as e:  # usage endpoint hiccup: keep going
                    print("  usage check failed", e, flush=True)

    with ThreadPoolExecutor(max_workers=MAX_IN_FLIGHT) as ex:
        list(ex.map(work, todo))
    print("finished", out_path, flush=True)


if __name__ == "__main__":
    print(json.dumps(usage()))

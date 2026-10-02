"""
Reviewer experiments for RQ5 on ClassEval (81 tasks): new generations.

Tasks (--task):
  framing  greedy; correct twin and buggy twin shown with a warning framing
           ("... it may also contain bugs, so do not copy it without checking it").
  top3     greedy; the top-3 Octen documents (rankings.json) in rank order, and an
           oracle variant with both twins (correct and buggy) in a fixed random order.
  samples  temperature 0.8, seeds 1..S; the original prompts with no context,
           the correct twin, and the buggy twin.
  judged   greedy; generations for (problem, context) pairs picked by the LLM judge
           (code/review/llm_judge.py) that are not already in
           results/rq5/generations/<llm>/classeval.jsonl.

Prompts are built with code/rq5/generate.py:build_prompt, so everything except the
studied change is identical to the original experiment.

Usage:
    python code/review/llm_rq5_generate.py --task framing --model gpt-oss:20b [--limit 20]
Output: results/review/<task>/<model>.jsonl (one record per generation, with the code).
"""

import argparse
import json
import os
import random

from llm_common import OUT_DIR, ROOT, build_prompt, load_jsonl, run_jobs
from common import build_corpus, load_language

ORIG_INTRO = "A code search over the codebase returned the following snippet, which may be relevant:"
WARN_INTRO = ("A code search over the codebase returned the following snippet. It may be relevant, but it "
              "may also contain bugs, so do not copy it without checking it against the task.")
TOP3_INTRO = ("A code search over the codebase returned the following {n} snippets, ranked by relevance, "
              "which may be relevant:")


def warned_prompt(row, code: str) -> str:
    p = build_prompt("classeval", row, code)
    assert ORIG_INTRO in p
    return p.replace(ORIG_INTRO, WARN_INTRO)


def multi_prompt(row, codes: list[str]) -> str:
    """Same layout as build_prompt, with several fenced snippets after the intro line."""
    p = build_prompt("classeval", row, "<<SNIPPETS>>")
    block = "```python\n<<SNIPPETS>>\n```"
    assert block in p
    body = "\n\n".join(f"Snippet {i + 1}:\n```python\n{c}\n```" for i, c in enumerate(codes))
    return p.replace(block, body).replace(ORIG_INTRO, TOP3_INTRO.format(n=len(codes)))


def oracle_order(problem: int) -> list[str]:
    pair = [f"{problem}_correct", f"{problem}_buggy"]
    random.Random(problem).shuffle(pair)
    return pair


def make_jobs(task: str, model: str, n_samples: int) -> list[dict]:
    df = load_language("classeval")
    rows = {int(r["problem"]): r for _, r in df.iterrows()}
    docs, _ = build_corpus("classeval")
    code = dict(zip(docs["doc_id"], docs["code"]))
    jobs = []
    if task == "framing":
        for p in rows:
            for v in ("correct", "buggy"):
                jobs.append({"id": f"{p}|warn_{v}", "problem": p, "condition": f"warn_{v}",
                             "context": [f"{p}_{v}"], "prompt": warned_prompt(rows[p], code[f"{p}_{v}"])})
    elif task == "top3":
        with open(os.path.join(ROOT, "results/rq5/retrieval/rankings.json"), encoding="utf-8") as f:
            top = {r["problem"]: r["top"] for r in json.load(f)["classeval"]["octen"]}
        for p in rows:
            ctx = top[p][:3]
            jobs.append({"id": f"{p}|top3", "problem": p, "condition": "top3", "context": ctx,
                         "prompt": multi_prompt(rows[p], [code[c] for c in ctx])})
            ctx = oracle_order(p)
            jobs.append({"id": f"{p}|both_twins", "problem": p, "condition": "both_twins", "context": ctx,
                         "prompt": multi_prompt(rows[p], [code[c] for c in ctx])})
    elif task == "samples":
        for s in range(1, n_samples + 1):
            for p in rows:
                for c in ("none", "correct", "buggy"):
                    ctx = None if c == "none" else f"{p}_{c}"
                    jobs.append({"id": f"{p}|{c}|s{s}", "problem": p, "condition": c, "seed": s,
                                 "temperature": 0.8, "context": [] if ctx is None else [ctx],
                                 "prompt": build_prompt("classeval", rows[p], None if ctx is None else code[ctx])})
    elif task == "judged":
        cached = {(r["problem"], r["context"]) for r in
                  load_jsonl(os.path.join(ROOT, "results/rq5/generations", "ollama_" + model.replace(":", "_"),
                                          "classeval.jsonl")) if "error" not in r}
        with open(os.path.join(OUT_DIR, "judge", "picks.json"), encoding="utf-8") as f:
            picks = json.load(f)["classeval"]
        for p, c in sorted(((int(p), c) for p, c in picks.items())):
            if (p, c) in cached:
                continue
            jobs.append({"id": f"{p}|{c}", "problem": p, "condition": "judged", "context": [] if c == "none" else [c],
                         "prompt": build_prompt("classeval", rows[p], None if c == "none" else code[c])})
    for j in jobs:
        j["model"] = model
        j.setdefault("temperature", 0.0)
        j.setdefault("seed", 0)
    return jobs


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--task", required=True, choices=["framing", "top3", "samples", "judged"])
    ap.add_argument("--model", required=True)
    ap.add_argument("--samples", type=int, default=5)
    ap.add_argument("--limit", type=int, default=None)
    ap.add_argument("--print-prompt", action="store_true")
    args = ap.parse_args()
    jobs = make_jobs(args.task, args.model, args.samples)
    if args.print_prompt:
        print(jobs[0]["prompt"])
        return
    if args.limit:
        jobs = jobs[: args.limit]
    run_jobs(jobs, os.path.join(OUT_DIR, args.task, args.model.replace(":", "_") + ".jsonl"))


if __name__ == "__main__":
    main()

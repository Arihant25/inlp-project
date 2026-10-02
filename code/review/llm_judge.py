"""
LLM correctness-judge defense (reviewer request), as a cheap alternative to
example-test filtering (RQ5.3).

For each query problem, each of the Octen retriever's top-5 documents
(results/rq5/retrieval/rankings.json) is shown to gpt-oss:20b (greedy, seed 0) with
the task, and the model is asked "Does this code correctly implement the task?
Answer YES or NO." The judged pick is the first document (in rank order) judged YES;
if none is, no snippet is used. An answer without YES/NO counts as NO.

Datasets: ClassEval (81 tasks) and HumanEvalFix Python (164 problems).

Outputs:
  results/review/judge/judgments_<lang>.jsonl   one record per (problem, document)
  results/review/judge/picks.json               {lang: {problem: doc_id or "none"}}
  results/review/judge_selection_metrics.json   how often the buggy/correct twin is picked
      by top-1, example-test filtering and the judge, and the judge's YES rates.

Usage: python code/review/llm_judge.py [--limit N]
"""

import argparse
import json
import os
import re

from llm_common import OUT_DIR, ROOT, load_jsonl, run_jobs
from common import build_corpus, load_language

JUDGE = "gpt-oss:20b"
LANGS = ["classeval", "python"]


def judge_prompt(task: str, code: str) -> str:
    return ("You are reviewing a code snippet returned by a code search over a Python codebase.\n\n"
            f"Task:\n{task.strip()}\n\nCode:\n```python\n{code}\n```\n\n"
            "Does this code correctly implement the task? Answer YES or NO.")


def verdict(text: str) -> bool:
    m = re.search(r"\b(YES|NO)\b", text or "", flags=re.I)
    return bool(m) and m.group(1).upper() == "YES"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--limit", type=int, default=None)
    ap.add_argument("--no-run", action="store_true", help="only recompute picks and metrics")
    args = ap.parse_args()
    with open(os.path.join(ROOT, "results/rq5/retrieval/rankings.json"), encoding="utf-8") as f:
        rankings = json.load(f)
    with open(os.path.join(ROOT, "results/rq5/retrieval/metrics.json"), encoding="utf-8") as f:
        filtering = json.load(f)["filtering"]
    picks, metrics = {}, {"judge": JUDGE}
    for lang in LANGS:
        df = load_language(lang)
        rows = {int(r["problem"]): r for _, r in df.iterrows()}
        docs, _ = build_corpus(lang)
        code = dict(zip(docs["doc_id"], docs["code"]))
        top = {r["problem"]: r["top"][:5] for r in rankings[lang]["octen"]}
        jobs = [{"id": f"{p}|{d}", "problem": p, "doc": d, "rank": k, "model": JUDGE,
                 "temperature": 0.0, "seed": 0, "prompt": judge_prompt(rows[p]["instruction"], code[d])}
                for p in sorted(top) for k, d in enumerate(top[p])]
        if args.limit:
            jobs = jobs[: args.limit]
        path = os.path.join(OUT_DIR, "judge", f"judgments_{lang}.jsonl")
        if not args.no_run:
            run_jobs(jobs, path, extract=False)
        recs = {r["id"]: r for r in load_jsonl(path) if "error" not in r}
        yes = {i: verdict(r.get("text", "")) for i, r in recs.items()}
        complete = [p for p in sorted(top) if all(f"{p}|{d}" in yes for d in top[p])]
        pk = {p: next((d for d in top[p] if yes[f"{p}|{d}"]), "none") for p in complete}
        picks[lang] = {str(p): c for p, c in pk.items()}
        fp = filtering[lang]["octen"]["picks"]
        n = len(complete)

        def rates(sel):
            return {"buggy_twin": round(sum(sel[p] == f"{p}_buggy" for p in complete) / n, 4),
                    "correct_twin": round(sum(sel[p] == f"{p}_correct" for p in complete) / n, 4),
                    "none": round(sum(sel[p] == "none" for p in complete) / n, 4)}

        tw = lambda v: [yes[f"{p}|{p}_{v}"] for p in complete if f"{p}_{v}" in top[p]]
        other = [yes[f"{p}|{d}"] for p in complete for d in top[p] if not d.startswith(f"{p}_")]
        both = [p for p in complete if f"{p}_buggy" in top[p] and f"{p}_correct" in top[p]]
        metrics[lang] = {
            "n_problems": n, "n_judgments": sum(len(top[p]) for p in complete),
            "top1": rates({p: top[p][0] for p in complete}),
            "example_test_filtering": rates({p: fp[str(p)] for p in complete}),
            "llm_judge": rates(pk),
            "yes_rate_correct_twin": round(sum(tw("correct")) / len(tw("correct")), 4),
            "yes_rate_buggy_twin": round(sum(tw("buggy")) / len(tw("buggy")), 4),
            "yes_rate_other_problem_docs": round(sum(other) / len(other), 4) if other else None,
            "both_twins_in_top5": len(both),
            "judge_separates_twins": round(sum(yes[f"{p}|{p}_correct"] and not yes[f"{p}|{p}_buggy"]
                                               for p in both) / len(both), 4) if both else None,
            "judge_yes_both_twins": round(sum(yes[f"{p}|{p}_correct"] and yes[f"{p}|{p}_buggy"]
                                              for p in both) / len(both), 4) if both else None,
            "output_cap_hits": sum(r.get("finish") == "length" for r in recs.values()),
        }
    with open(os.path.join(OUT_DIR, "judge", "picks.json"), "w", encoding="utf-8") as f:
        json.dump(picks, f, indent=1)
    with open(os.path.join(OUT_DIR, "judge_selection_metrics.json"), "w", encoding="utf-8") as f:
        json.dump(metrics, f, indent=2)
    print(json.dumps(metrics, indent=1))


if __name__ == "__main__":
    main()

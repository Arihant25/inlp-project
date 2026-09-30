"""
RQ5 Step 3: Execute generated code and evaluate retrieval-augmented generation.

For each cached generation (problem, context) we run the benchmark's hidden
tests. From these results we compute:

  - pass@1 (greedy) with no context, the correct twin, and the buggy twin as context
  - paired exact McNemar tests (correct vs buggy context, none vs buggy context)
  - bug-copy rate: share of generations that contain a line which appears in the
    buggy solution but not in the correct one
  - end-to-end pass@1 of each retriever: the context is the retriever's top-1
    document, or its example-test-filtered pick (RQ5.3)

Usage:
    python code/rq5/3_evaluate.py --llm gemma-4-31b-it

Outputs: results/rq5/execution/<llm>/<lang>.jsonl, results/rq5/generation_metrics_<llm>.json
"""

import argparse
import json
import os
import sys

import numpy as np
from scipy.stats import binomtest

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, SCRIPT_DIR)
from common import GENERATION_LANGUAGES, RESULTS_DIR, build_corpus, load_language
from execute import run_many
from generate import EXCLUDED, load_cache

N_BOOT = 5000


def norm_lines(code: str) -> set[str]:
    return {" ".join(l.split()) for l in code.splitlines() if l.strip()}


def buggy_only_lines(correct: str, buggy: str) -> set[str]:
    return norm_lines(buggy) - norm_lines(correct)


def execute_all(llm: str, lang: str) -> dict:
    """Run hidden tests for every cached generation; cache the execution results."""
    df = load_language(lang)
    tests = dict(zip(df["problem"].astype(int), df["test"]))
    gens = load_cache(llm, lang)
    path = os.path.join(RESULTS_DIR, "execution", llm, f"{lang}.jsonl")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    done = {}
    if os.path.exists(path):
        with open(path, encoding="utf-8") as f:
            for line in f:
                r = json.loads(line)
                done[(r["problem"], r["context"])] = r
    todo = [k for k in gens if k not in done and "code" in gens[k]]
    results = run_many([(lang, gens[k]["code"], tests[k[0]]) for k in todo])
    with open(path, "a", encoding="utf-8") as f:
        for k, res in zip(todo, results):
            rec = {"problem": k[0], "context": k[1], "passed": res["passed"], "status": res["status"]}
            f.write(json.dumps(rec) + "\n")
            done[k] = rec
    return done


def mcnemar(a: list[int], b: list[int]) -> dict:
    """Exact McNemar test on paired binary outcomes a and b."""
    n01 = sum(1 for x, y in zip(a, b) if x == 1 and y == 0)
    n10 = sum(1 for x, y in zip(a, b) if x == 0 and y == 1)
    p = float(binomtest(n01, n01 + n10, 0.5).pvalue) if n01 + n10 else 1.0
    return {"a_only": n01, "b_only": n10, "p": p}


def rate_ci(x: list[int], rng) -> tuple[float, list[float]]:
    x = np.array(x, dtype=float)
    boots = [x[rng.integers(0, len(x), len(x))].mean() for _ in range(N_BOOT)]
    return round(float(x.mean()), 4), [round(float(np.percentile(boots, 2.5)), 4),
                                       round(float(np.percentile(boots, 97.5)), 4)]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--llm", required=True)
    args = ap.parse_args()

    with open(os.path.join(RESULTS_DIR, "retrieval", "rankings.json"), encoding="utf-8") as f:
        rankings = json.load(f)
    with open(os.path.join(RESULTS_DIR, "retrieval", "metrics.json"), encoding="utf-8") as f:
        filtering = json.load(f)["filtering"]

    out = {"llm": args.llm, "per_language": {}, "pooled": {}}
    pooled = {"none": [], "correct": [], "buggy": [], "copy_buggy": [], "copy_correct": [],
              "copy_given_fail": [], "retriever": {}, "filtered": {}}

    for lang in GENERATION_LANGUAGES:
        execs = execute_all(args.llm, lang)
        gens = load_cache(args.llm, lang)
        docs, _ = build_corpus(lang)
        code = dict(zip(docs["doc_id"], docs["code"]))
        problems = sorted(p for p in docs["problem"].unique() if (lang, p) not in EXCLUDED)
        passed = lambda p, c: int(execs[(p, c)]["passed"]) if (p, c) in execs else None

        cond = {c: [passed(p, f"{p}_{c}" if c != "none" else "none") for p in problems]
                for c in ("none", "correct", "buggy")}
        missing = sum(v is None for vals in cond.values() for v in vals)
        keep = [i for i in range(len(problems)) if all(cond[c][i] is not None for c in cond)]
        cond = {c: [cond[c][i] for i in keep] for c in cond}
        probs = [problems[i] for i in keep]

        copy_b, copy_c, copy_fail = [], [], []
        for p, pb in zip(probs, cond["buggy"]):
            marks = buggy_only_lines(code[f"{p}_correct"], code[f"{p}_buggy"])
            gb = norm_lines(gens[(p, f"{p}_buggy")]["code"])
            gc = norm_lines(gens[(p, f"{p}_correct")]["code"])
            cb, cc = int(bool(marks & gb)), int(bool(marks & gc))
            copy_b.append(cb)
            copy_c.append(cc)
            if pb == 0:
                copy_fail.append(cb)

        rng = np.random.default_rng(42)
        res = {"n_problems": len(probs), "missing_generations": missing}
        for c in cond:
            res[f"pass_{c}"], res[f"pass_{c}_ci"] = rate_ci(cond[c], rng)
        res["mcnemar_correct_vs_buggy"] = mcnemar(cond["correct"], cond["buggy"])
        res["mcnemar_none_vs_buggy"] = mcnemar(cond["none"], cond["buggy"])
        res["bug_copy_rate_buggy_context"] = round(float(np.mean(copy_b)), 4)
        res["bug_copy_rate_correct_context"] = round(float(np.mean(copy_c)), 4)
        res["bug_copy_rate_among_failures"] = round(float(np.mean(copy_fail)), 4) if copy_fail else None

        res["retriever_pass"] = {}
        res["filtered_pass"] = {}
        for ret, recs in rankings[lang].items():
            top1 = {r["problem"]: r["top"][0] for r in recs}
            vals = [passed(p, top1[p]) for p in probs]
            ok = [v for v in vals if v is not None]
            res["retriever_pass"][ret] = {"pass": round(float(np.mean(ok)), 4), "n": len(ok)}
            if lang != "classeval":
                pooled["retriever"].setdefault(ret, []).extend(ok)
            picks = filtering[lang][ret]["picks"]
            fvals = [passed(p, picks[str(p)]) for p in probs]
            fok = [v for v in fvals if v is not None]
            res["filtered_pass"][ret] = {"pass": round(float(np.mean(fok)), 4), "n": len(fok)}
            if lang != "classeval":
                pooled["filtered"].setdefault(ret, []).extend(fok)

        out["per_language"][lang] = res
        if lang == "classeval":
            continue  # "pooled" covers the three HumanEvalFix languages; ClassEval is reported separately
        for c in cond:
            pooled[c].extend(cond[c])
        pooled["copy_buggy"].extend(copy_b)
        pooled["copy_correct"].extend(copy_c)
        pooled["copy_given_fail"].extend(copy_fail)

    rng = np.random.default_rng(42)
    P = out["pooled"]
    P["n"] = len(pooled["none"])
    for c in ("none", "correct", "buggy"):
        P[f"pass_{c}"], P[f"pass_{c}_ci"] = rate_ci(pooled[c], rng)
    P["mcnemar_correct_vs_buggy"] = mcnemar(pooled["correct"], pooled["buggy"])
    P["mcnemar_none_vs_buggy"] = mcnemar(pooled["none"], pooled["buggy"])
    P["bug_copy_rate_buggy_context"] = round(float(np.mean(pooled["copy_buggy"])), 4)
    P["bug_copy_rate_correct_context"] = round(float(np.mean(pooled["copy_correct"])), 4)
    P["bug_copy_rate_among_failures"] = round(float(np.mean(pooled["copy_given_fail"])), 4)
    P["retriever_pass"] = {r: rate_ci(v, rng) for r, v in pooled["retriever"].items()}
    P["filtered_pass"] = {r: rate_ci(v, rng) for r, v in pooled["filtered"].items()}

    with open(os.path.join(RESULTS_DIR, f"generation_metrics_{args.llm}.json"), "w", encoding="utf-8") as f:
        json.dump(out, f, indent=2)

    print(json.dumps({k: v for k, v in P.items() if k not in ("retriever_pass", "filtered_pass")}, indent=1))
    print(f"{'retriever':14s} top-1 pass   filtered pass")
    for r in P["retriever_pass"]:
        print(f"{r:14s} {P['retriever_pass'][r][0]:.3f}        {P['filtered_pass'][r][0]:.3f}")


if __name__ == "__main__":
    main()

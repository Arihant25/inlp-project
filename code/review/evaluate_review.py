"""
Execute and evaluate the reviewer experiments (no API calls).

Runs the ClassEval hidden tests on every generation in results/review/<task>/<model>.jsonl
(cached in results/review/execution/<task>/<model>.jsonl) and writes metrics:

  framing -> results/review/framing_metrics.json
      pass@1 of the warned correct/buggy twin conditions, exact McNemar tests against
      the original (unwarned) greedy conditions, bug-copy rate (same definition as
      code/rq5/3_evaluate.py: the generation contains a line that is in the buggy twin
      but not in the correct twin).
  top3    -> results/review/top3_metrics.json
      pass@1 with the top-3 Octen documents and with both twins, vs top-1 Octen,
      correct twin and buggy twin (McNemar).
  samples -> results/review/samples_metrics.json
      mean pass@1 over samples with 95% CIs (two-level bootstrap: tasks, then samples
      within task), correct-minus-buggy (and none-minus-buggy) gap with CI,
      "any of the S samples passes".
  judged  -> results/review/judge_e2e_metrics.json: end-to-end pass@1 with the pick of
      the LLM judge (selection statistics come from code/review/llm_judge.py).

Generations that reached the 65,536-token output cap count as failures.

Usage: python code/review/evaluate_review.py --task framing
"""

import argparse
import json
import os
import sys

import numpy as np
from scipy.stats import binomtest

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
sys.path.insert(0, os.path.join(ROOT, "code", "rq5"))
from common import build_corpus, load_language  # noqa: E402
from execute import run_many  # noqa: E402

OUT = os.path.join(ROOT, "results", "review")
MODELS = ["gemma4:31b", "gpt-oss:20b", "deepseek-v4.1-flash"]
N_BOOT = 5000


def load_jsonl(path):
    if not os.path.exists(path):
        return []
    with open(path, encoding="utf-8") as f:
        return [json.loads(l) for l in f if l.strip()]


def norm_lines(code):
    return {" ".join(l.split()) for l in code.splitlines() if l.strip()}


def mcnemar(a, b):
    """Exact McNemar test; a_only = tasks passing only under a."""
    n01 = sum(1 for x, y in zip(a, b) if x == 1 and y == 0)
    n10 = sum(1 for x, y in zip(a, b) if x == 0 and y == 1)
    p = float(binomtest(n01, n01 + n10, 0.5).pvalue) if n01 + n10 else 1.0
    return {"a_only": n01, "b_only": n10, "p": round(p, 6)}


def boot_ci(x, rng):
    x = np.asarray(x, float)
    b = x[rng.integers(0, len(x), (N_BOOT, len(x)))].mean(1)
    return [round(float(np.percentile(b, 2.5)), 4), round(float(np.percentile(b, 97.5)), 4)]


def r4(x):
    return round(float(x), 4)


def execute(task, model):
    """Execute all generations for (task, model); return {id: record with passed and code}."""
    gens = {g["id"]: g for g in load_jsonl(os.path.join(OUT, task, model.replace(":", "_") + ".jsonl"))
            if "error" not in g}
    path = os.path.join(OUT, "execution", task, model.replace(":", "_") + ".jsonl")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    done = {r["id"]: r for r in load_jsonl(path)}
    df = load_language("classeval")
    tests = dict(zip(df["problem"].astype(int), df["test"]))
    todo = [i for i in gens if i not in done]
    jobs = []
    for i in todo:
        g = gens[i]
        capped = g.get("finish") == "length"  # reached the output cap: a failure
        jobs.append(("classeval", "raise SystemExit(1)" if capped else g.get("code", ""), tests[g["problem"]]))
    res = run_many(jobs) if jobs else []
    with open(path, "a", encoding="utf-8") as f:
        for i, r in zip(todo, res):
            rec = {"id": i, "problem": gens[i]["problem"], "condition": gens[i]["condition"],
                   "passed": bool(r["passed"]), "status": r["status"]}
            f.write(json.dumps(rec) + "\n")
            done[i] = rec
    out = {}
    for i in gens:
        if i in done:
            out[i] = dict(done[i], code=gens[i].get("code", ""))
    return out


def original(model):
    """Original greedy runs: {(problem, context): passed}, {(problem, context): code}."""
    m = "ollama_" + model.replace(":", "_")
    ex = {(r["problem"], r["context"]): int(r["passed"])
          for r in load_jsonl(os.path.join(ROOT, "results/rq5/execution", m, "classeval.jsonl"))}
    gen = {(r["problem"], r["context"]): r.get("code", "")
           for r in load_jsonl(os.path.join(ROOT, "results/rq5/generations", m, "classeval.jsonl"))
           if "error" not in r}
    return ex, gen


def corpus():
    docs, _ = build_corpus("classeval")
    code = dict(zip(docs["doc_id"], docs["code"]))
    probs = sorted(int(p) for p in docs["problem"].unique())
    marks = {p: norm_lines(code[f"{p}_buggy"]) - norm_lines(code[f"{p}_correct"]) for p in probs}
    return probs, code, marks


def copy_rate(codes, probs, marks):
    return r4(np.mean([int(bool(marks[p] & norm_lines(c))) for p, c in zip(probs, codes)]))


def octen_top():
    with open(os.path.join(ROOT, "results/rq5/retrieval/rankings.json"), encoding="utf-8") as f:
        return {r["problem"]: r["top"] for r in json.load(f)["classeval"]["octen"]}


def eval_framing():
    probs, _, marks = corpus()
    out = {}
    for model in MODELS:
        ex = execute("framing", model)
        if not ex:
            continue
        oex, ogen = original(model)
        P = [p for p in probs if f"{p}|warn_correct" in ex and f"{p}|warn_buggy" in ex]
        rng = np.random.default_rng(42)
        wc = [int(ex[f"{p}|warn_correct"]["passed"]) for p in P]
        wb = [int(ex[f"{p}|warn_buggy"]["passed"]) for p in P]
        c = [oex[(p, f"{p}_correct")] for p in P]
        b = [oex[(p, f"{p}_buggy")] for p in P]
        n = [oex[(p, "none")] for p in P]
        out[model] = {
            "n": len(P),
            "pass_none": r4(np.mean(n)),
            "pass_correct": r4(np.mean(c)), "pass_buggy": r4(np.mean(b)),
            "pass_warn_correct": r4(np.mean(wc)), "pass_warn_correct_ci": boot_ci(wc, rng),
            "pass_warn_buggy": r4(np.mean(wb)), "pass_warn_buggy_ci": boot_ci(wb, rng),
            "gap_unwarned": r4(np.mean(c) - np.mean(b)),
            "gap_warned": r4(np.mean(wc) - np.mean(wb)),
            "mcnemar_warn_buggy_vs_buggy": mcnemar(wb, b),
            "mcnemar_warn_correct_vs_correct": mcnemar(wc, c),
            "mcnemar_warn_correct_vs_warn_buggy": mcnemar(wc, wb),
            "mcnemar_none_vs_warn_buggy": mcnemar(n, wb),
            "bug_copy_buggy": copy_rate([ogen[(p, f"{p}_buggy")] for p in P], P, marks),
            "bug_copy_warn_buggy": copy_rate([ex[f"{p}|warn_buggy"]["code"] for p in P], P, marks),
            "bug_copy_warn_correct": copy_rate([ex[f"{p}|warn_correct"]["code"] for p in P], P, marks),
        }
    return out


def eval_top3():
    probs, _, marks = corpus()
    top = octen_top()
    out = {"retrieval": {
        "buggy_twin_in_top3": r4(np.mean([f"{p}_buggy" in top[p][:3] for p in probs])),
        "correct_twin_in_top3": r4(np.mean([f"{p}_correct" in top[p][:3] for p in probs])),
        "both_twins_in_top3": r4(np.mean([f"{p}_buggy" in top[p][:3] and f"{p}_correct" in top[p][:3]
                                          for p in probs])),
        "top1_buggy": r4(np.mean([top[p][0] == f"{p}_buggy" for p in probs]))}}
    for model in MODELS:
        ex = execute("top3", model)
        if not ex:
            continue
        oex, ogen = original(model)
        P = [p for p in probs if f"{p}|top3" in ex and f"{p}|both_twins" in ex]
        rng = np.random.default_rng(42)
        t3 = [int(ex[f"{p}|top3"]["passed"]) for p in P]
        bt = [int(ex[f"{p}|both_twins"]["passed"]) for p in P]
        t1 = [oex[(p, top[p][0])] for p in P]
        c = [oex[(p, f"{p}_correct")] for p in P]
        b = [oex[(p, f"{p}_buggy")] for p in P]
        bfirst = [p for p in P if top[p][0] == f"{p}_buggy"]
        out[model] = {
            "n": len(P),
            "pass_top1_octen": r4(np.mean(t1)),
            "pass_top3_octen": r4(np.mean(t3)), "pass_top3_ci": boot_ci(t3, rng),
            "pass_both_twins": r4(np.mean(bt)), "pass_both_twins_ci": boot_ci(bt, rng),
            "pass_correct": r4(np.mean(c)), "pass_buggy": r4(np.mean(b)),
            "mcnemar_top3_vs_top1": mcnemar(t3, t1),
            "mcnemar_top3_vs_correct": mcnemar(t3, c),
            "mcnemar_top3_vs_buggy": mcnemar(t3, b),
            "mcnemar_both_vs_correct": mcnemar(bt, c),
            "mcnemar_both_vs_buggy": mcnemar(bt, b),
            "subset_top1_is_buggy": {
                "n": len(bfirst),
                "pass_top1": r4(np.mean([oex[(p, top[p][0])] for p in bfirst])) if bfirst else None,
                "pass_top3": r4(np.mean([int(ex[f"{p}|top3"]["passed"]) for p in bfirst])) if bfirst else None,
                "pass_correct": r4(np.mean([oex[(p, f"{p}_correct")] for p in bfirst])) if bfirst else None},
            "bug_copy_top3": copy_rate([ex[f"{p}|top3"]["code"] for p in P], P, marks),
            "bug_copy_both_twins": copy_rate([ex[f"{p}|both_twins"]["code"] for p in P], P, marks),
            "bug_copy_buggy": copy_rate([ogen[(p, f"{p}_buggy")] for p in P], P, marks),
        }
    return out


def eval_samples():
    probs, _, _ = corpus()
    out = {}
    for model in MODELS:
        ex = execute("samples", model)
        if not ex:
            continue
        oex, _ = original(model)
        seeds = sorted({int(i.split("|s")[1]) for i in ex})
        res = {"samples_per_condition": len(seeds), "seeds": seeds, "temperature": 0.8}
        mats = {}
        for c in ("none", "correct", "buggy"):
            M = np.full((len(probs), len(seeds)), np.nan)
            for a, p in enumerate(probs):
                for k, s in enumerate(seeds):
                    r = ex.get(f"{p}|{c}|s{s}")
                    if r is not None:
                        M[a, k] = int(r["passed"])
            mats[c] = M
        res["missing"] = int(sum(np.isnan(M).sum() for M in mats.values()))
        # Shared bootstrap indices so the gaps are paired by task and sample.
        T, S = len(probs), len(seeds)
        rng = np.random.default_rng(42)
        ti = rng.integers(0, T, (N_BOOT, T))
        si = rng.integers(0, S, (N_BOOT, T, S))
        boots = {}
        for c, M in mats.items():
            boots[c] = np.nanmean(M[ti[:, :, None], si].reshape(N_BOOT, -1), 1)
            greedy = np.mean([oex[(p, "none" if c == "none" else f"{p}_{c}")] for p in probs])
            res[c] = {"mean_pass1": r4(np.nanmean(M)),
                      "ci95": [r4(np.percentile(boots[c], 2.5)), r4(np.percentile(boots[c], 97.5))],
                      "per_sample_pass1": [r4(np.nanmean(M[:, k])) for k in range(S)],
                      "any_sample_passes": r4(np.mean(np.nanmax(M, 1))),
                      "all_samples_pass": r4(np.mean(np.nanmin(M, 1))),
                      "greedy_pass1": r4(greedy)}
        for a, b in (("correct", "buggy"), ("none", "buggy"), ("correct", "none")):
            g = boots[a] - boots[b]
            per = [float(np.nanmean(mats[a][:, k]) - np.nanmean(mats[b][:, k])) for k in range(S)]
            res[f"gap_{a}_minus_{b}"] = {
                "mean": r4(np.nanmean(mats[a]) - np.nanmean(mats[b])),
                "ci95": [r4(np.percentile(g, 2.5)), r4(np.percentile(g, 97.5))],
                "per_sample": [r4(x) for x in per],
                "samples_with_positive_gap": int(sum(x > 0 for x in per))}
        out[model] = res
    return out


def eval_judged():
    probs, _, _ = corpus()
    with open(os.path.join(OUT, "judge", "picks.json"), encoding="utf-8") as f:
        picks = {int(p): c for p, c in json.load(f)["classeval"].items()}
    with open(os.path.join(ROOT, "results/rq5/retrieval/metrics.json"), encoding="utf-8") as f:
        fpicks = {int(p): c for p, c in json.load(f)["filtering"]["classeval"]["octen"]["picks"].items()}
    top = octen_top()
    out = {}
    for model in MODELS:
        oex, _ = original(model)
        ex = execute("judged", model)
        new = {(r["problem"], i.split("|", 1)[1]): int(r["passed"]) for i, r in ex.items()}
        allx = {**oex, **new}
        rng = np.random.default_rng(42)
        jv = [allx.get((p, picks[p])) for p in probs]
        if any(v is None for v in jv):
            out[model] = {"missing": sum(v is None for v in jv)}
            continue
        t1 = [oex[(p, top[p][0])] for p in probs]
        fv = [oex[(p, fpicks[p])] for p in probs]
        out[model] = {"n": len(probs),
                      "pass_top1_octen": r4(np.mean(t1)),
                      "pass_test_filtered_octen": r4(np.mean(fv)),
                      "pass_judge_octen": r4(np.mean(jv)), "pass_judge_ci": boot_ci(jv, rng),
                      "pass_correct": r4(np.mean([oex[(p, f"{p}_correct")] for p in probs])),
                      "mcnemar_judge_vs_top1": mcnemar(jv, t1),
                      "mcnemar_judge_vs_filtered": mcnemar(jv, fv),
                      "new_generations": len(new)}
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--task", required=True, choices=["framing", "top3", "samples", "judged"])
    args = ap.parse_args()
    res = {"framing": eval_framing, "top3": eval_top3, "samples": eval_samples, "judged": eval_judged}[args.task]()
    name = "judge_e2e" if args.task == "judged" else args.task
    with open(os.path.join(OUT, f"{name}_metrics.json"), "w", encoding="utf-8") as f:
        json.dump(res, f, indent=2)
    print(json.dumps(res, indent=1))


if __name__ == "__main__":
    main()

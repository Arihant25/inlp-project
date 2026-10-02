"""
Review: one file with all 95% confidence intervals relevant to the paper's claims.

Collected from existing metric files (query/problem-level percentile bootstrap,
5,000 resamples, seed 42, as computed by code/rq5/2_retrieval.py and 3_evaluate.py):
  - pass@1 under none / correct twin / buggy twin, ClassEval and pooled HumanEvalFix
    (python, js, java), per LLM
  - buggy-first and top-1-buggy rate per retriever (pooled HumanEvalFix and ClassEval)
New (5,000 resamples, seed 0):
  - RQ4 relative pair distance R, 1/R, R per category and syntax ratio, bootstrapping
    bug types (the 100 bug_index values; each resampled bug brings its 5 languages).
    Unmatched distance in a resample = mean over ordered pairs of distinct bug ids
    (a != c) weighted by their multiplicities, so a bug is never its own unmatched partner.
    For the 7 embedders and the 4 lexical baselines.
  - HumanEvalFix and ClassEval proximity R, bootstrapping problems the same way
    (six RQ5 embedders).

Output: results/review/confidence_intervals.json
"""

import json
import os
import sys

import numpy as np
import pandas as pd

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from common import (EMBEDDERS, OUT_DIR, ROOT, RQ4Pairs, RQ5_EMBEDDERS, baseline_matrices, cosine_matrix,
                    load_rq5_vectors, r3, rq5_docs)

LLMS = ["ollama_gemma4_31b", "ollama_gpt-oss_20b", "ollama_deepseek-v4.1-flash"]
N_BOOT = 5000
SEED = 0


def ci(x):
    return [float(np.percentile(x, 2.5)), float(np.percentile(x, 97.5))]


def boot_R(S: np.ndarray, cats=None) -> dict:
    """S: (n, n) mean over languages of D[buggy_a, fixed_c] for units a, c (diagonal = matched pairs)."""
    n = len(S)
    rng = np.random.default_rng(SEED)
    W = np.stack([np.bincount(rng.integers(0, n, n), minlength=n) for _ in range(N_BOOT)]).astype(float)
    diag = np.diag(S)
    off = S.copy(); np.fill_diagonal(off, 0)
    pair = W @ diag / W.sum(1)
    um = ((W @ off) * W).sum(1) / (W.sum(1) ** 2 - (W ** 2).sum(1))
    R = pair / um
    out = {"R": float(diag.mean() / off.sum() * (n * (n - 1))), "R_ci": ci(R), "inverse_R_ci": ci(1 / R),
           "pair_mean_ci": ci(pair), "unmatched_mean_ci": ci(um)}
    if cats is not None:
        um0 = off.sum() / (n * (n - 1))
        by, by_b = {}, {}
        for c in ("syntax", "logic", "state", "numeric"):
            m = cats == c
            by[c] = float(diag[m].mean() / um0)
            by_b[c] = (W[:, m] @ diag[m]) / W[:, m].sum(1) / um
            out[f"R_{c}"], out[f"R_{c}_ci"] = by[c], ci(by_b[c])
        m = cats == "syntax"
        syn = (W[:, m] @ diag[m]) / W[:, m].sum(1)
        oth = (W[:, ~m] @ diag[~m]) / W[:, ~m].sum(1)
        out["syntax_ratio"] = float(diag[m].mean() / diag[~m].mean())
        out["syntax_ratio_ci"] = ci(syn / oth)
    return out


def rq4_S(D, P: RQ4Pairs):
    return np.mean([D[np.ix_(P.b[l], P.f[l])] for l in P.languages], axis=0)


def rq4():
    out = {}
    base = None
    for m in EMBEDDERS:
        df = pd.read_parquet(os.path.join(ROOT, f"results/RQ4/{m}/rq4_embeddings.parquet")).reset_index(drop=True)
        P = RQ4Pairs(df)
        base = (df, P) if base is None else base
        for l in P.languages:  # same bug order in every language
            assert (df.loc[P.b[l], "bug_index"].values == df.loc[P.b[P.languages[0]], "bug_index"].values).all()
            assert (P.cat[l] == P.cat[P.languages[0]]).all()
        out[m] = boot_R(rq4_S(cosine_matrix(np.vstack(df["embedding"].values)), P), P.cat[P.languages[0]])
    df, P = base
    for b, D in baseline_matrices(df["code"].tolist()).items():
        out[b] = boot_R(rq4_S(D, P), P.cat[P.languages[0]])
    return out


def rq5_R():
    he, ce = rq5_docs()
    out = {"humanevalfix": {}, "classeval": {}}
    for m in RQ5_EMBEDDERS:
        v = load_rq5_vectors(m)
        for name, docs_by in (("humanevalfix", he), ("classeval", {"classeval": ce})):
            Ss = []
            for l, docs in docs_by.items():
                probs = sorted(docs["problem"].unique())
                C = np.vstack([v[f"{l}|d|{p}_correct"] for p in probs])
                B = np.vstack([v[f"{l}|d|{p}_buggy"] for p in probs])
                C /= np.linalg.norm(C, axis=1, keepdims=True); B /= np.linalg.norm(B, axis=1, keepdims=True)
                Ss.append(1 - C @ B.T)
            out[name][m] = boot_R(np.mean(Ss, axis=0))
    return out


def collected():
    gen = {}
    for llm in LLMS:
        g = json.load(open(os.path.join(ROOT, f"results/rq5/generation_metrics_{llm}.json"), encoding="utf-8"))
        gen[llm] = {}
        for scope, d in (("classeval", g["per_language"]["classeval"]), ("humanevalfix_pooled", g["pooled"])):
            gen[llm][scope] = {c: {"pass": d[f"pass_{c}"], "ci": d[f"pass_{c}_ci"]} for c in ("none", "correct", "buggy")}
            gen[llm][scope]["n"] = d.get("n", d.get("n_problems"))
    ret = {}
    m = json.load(open(os.path.join(ROOT, "results/rq5/retrieval/metrics.json"), encoding="utf-8"))["retrieval"]
    for r, d in m.items():
        ret[r] = {scope: {"buggy_first": d[scope]["buggy_first"], "buggy_first_ci": d[scope]["buggy_first_ci"],
                          "top1_buggy": d[scope]["top1_buggy"], "top1_buggy_ci": d[scope]["top1_buggy_ci"],
                          "n": d[scope]["n"]}
                  for scope in ("pooled", "classeval")}
    return gen, ret


def main():
    gen, ret = collected()
    res = {"generation_pass_at_1": gen, "retrieval": ret}
    clustered = os.path.join(OUT_DIR, "rq5_clustered_tests.json")
    if os.path.exists(clustered):
        c = json.load(open(clustered, encoding="utf-8"))
        for r in ret:
            ret[r]["pooled"]["buggy_first_ci_problem_level"] = c[r]["buggy_first_ci_problem_bootstrap"]
            ret[r]["pooled"]["top1_buggy_ci_problem_level"] = c[r]["top1_buggy_ci_problem_bootstrap"]
    res["rq4_R_bootstrap_bug_types"] = rq4()
    res["rq5_R_bootstrap_problems"] = rq5_R()
    os.makedirs(OUT_DIR, exist_ok=True)
    with open(os.path.join(OUT_DIR, "confidence_intervals.json"), "w", encoding="utf-8") as f:
        json.dump(r3(res), f, indent=2)
    for m, v in res["rq4_R_bootstrap_bug_types"].items():
        print(f"{m:12s} R={v['R']:.3f} {np.round(v['R_ci'],3)} syn={v['R_syntax']:.3f} {np.round(v['R_syntax_ci'],3)} "
              f"ratio={v['syntax_ratio']:.2f} {np.round(v['syntax_ratio_ci'],2)}")
    for s in ("humanevalfix", "classeval"):
        for m, v in res["rq5_R_bootstrap_problems"][s].items():
            print(s, m, round(v["R"], 4), np.round(v["R_ci"], 4))


if __name__ == "__main__":
    main()

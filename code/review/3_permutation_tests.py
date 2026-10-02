"""
Review: snippet-level permutation tests for the cross-vs-intra comparisons of RQ2-RQ4.

The paper's Welch tests treat every pair distance as independent, but each snippet
enters many pairs. Here the group labels are permuted at the snippet level and the
paper's statistic, mean(cross) - mean(intra), is recomputed with exactly the same
pair-selection procedure (including the seed-42 subsampling templates, whose
positions depend only on group sizes, which permutation preserves):

  RQ2  framework labels permuted among the 16 snippets of each (language, pattern)
       cell (pairs are only formed within a cell, so this is the snippet-level
       permutation within language that keeps the pairing structure)
  RQ3  complexity-class labels permuted among the snippets of each language
  RQ4  (a) buggy/fixed labels permuted among the 200 snippets of each language
       (b) stricter: buggy/fixed labels swapped at random within each (bug, language)
           pair, which respects the matched-pair structure

1,000 permutations, seed 0; the same permutations are applied to every embedder.
p = (1 + #{|perm stat| >= |obs|}) / (1 + 1000), two-sided; one-sided (perm >= obs) too.
Compared with the Welch p-values stored in results/rq{2,3}/<m>/rq*_metrics.json and
results/RQ4/<m>/rq4_metrics.json.

Output: results/review/permutation_tests.json
"""

import json
import os
import sys

import numpy as np
import pandas as pd

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from common import EMBEDDERS, OUT_DIR, ROOT, RQ3Pairs, RQ4Pairs, cosine_matrix, r3, rq3_clean

N_PERM = 1000
SEED = 0


def summary(obs, null, welch_p):
    null = np.asarray(null)
    p2 = (1 + int((np.abs(null) >= abs(obs) - 1e-12).sum())) / (1 + len(null))
    p1 = (1 + int((null >= obs - 1e-12).sum())) / (1 + len(null))
    return {"observed_diff": obs, "null_mean": float(null.mean()), "null_sd": float(null.std(ddof=1)),
            "null_q975": float(np.percentile(null, 97.5)), "perm_p_two_sided": p2, "perm_p_one_sided": p1,
            "welch_p": welch_p, "welch_significant": bool(welch_p < 0.05), "perm_significant": bool(p2 < 0.05),
            "conclusion_changes": bool((welch_p < 0.05) != (p2 < 0.05))}


def welch(rq, m):
    path = {"rq2": f"results/rq2/{m}/rq2_metrics.json", "rq3": f"results/rq3/{m}/rq3_metrics.json",
            "rq4": f"results/RQ4/{m}/rq4_metrics.json"}[rq]
    return json.load(open(os.path.join(ROOT, path), encoding="utf-8"))["statistical_tests"]["t_test"]["p_value"]


def rq2():
    rng = np.random.default_rng(SEED)
    dfs = {m: pd.read_parquet(os.path.join(ROOT, f"results/rq2/{m}/rq2_embeddings.parquet")) for m in EMBEDDERS}
    df = dfs[EMBEDDERS[0]]
    assert all((d["code"].values == df["code"].values).all() for d in dfs.values())
    fw = np.asarray(df["language"] + "/" + df["framework"], dtype=str)
    cells = [g.index.values for _, g in df.groupby(["pattern", "language"], sort=True)]
    blocks = {m: np.stack([cosine_matrix(np.vstack(dfs[m]["embedding"].values[c])) for c in cells]) for m in EMBEDDERS}
    iu = np.triu_indices(len(cells[0]), 1)

    def stat(labels_per_cell):
        same = np.stack([(l[:, None] == l[None, :])[iu] for l in labels_per_cell])  # (cells, pairs)
        return {m: float(B[:, iu[0], iu[1]][~same].mean() - B[:, iu[0], iu[1]][same].mean()) for m, B in blocks.items()}

    obs = stat([fw[c] for c in cells])
    null = {m: [] for m in EMBEDDERS}
    for _ in range(N_PERM):
        s = stat([rng.permutation(fw[c]) for c in cells])
        for m in EMBEDDERS:
            null[m].append(s[m])
    return {m: summary(obs[m], null[m], welch("rq2", m)) for m in EMBEDDERS}


def rq3():
    rng = np.random.default_rng(SEED)
    D, dfc = {}, None
    for m in EMBEDDERS:
        df = pd.read_parquet(os.path.join(ROOT, f"results/rq3/{m}/rq3_embeddings.parquet"))
        d = df[rq3_clean(df)].reset_index(drop=True)
        dfc = d if dfc is None else dfc
        assert (d["code"].values == dfc["code"].values).all()
        D[m] = cosine_matrix(np.vstack(d["embedding"].values)).astype(np.float32)
    T = RQ3Pairs(dfc)
    labels = np.asarray(dfc["complexity_class"], dtype=object)

    def stat(lab):
        (ci, cj), (ii, ij) = T.pairs(lab)
        return {m: float(X[ci, cj].mean() - X[ii, ij].mean()) for m, X in D.items()}

    obs = stat(labels)
    null = {m: [] for m in EMBEDDERS}
    for _ in range(N_PERM):
        lab = labels.copy()
        for rows in T.lang_rows.values():
            lab[rows] = rng.permutation(lab[rows])
        s = stat(lab)
        for m in EMBEDDERS:
            null[m].append(s[m])
    return {m: summary(obs[m], null[m], welch("rq3", m)) for m in EMBEDDERS}


def rq4():
    D, df = {}, None
    for m in EMBEDDERS:
        d = pd.read_parquet(os.path.join(ROOT, f"results/RQ4/{m}/rq4_embeddings.parquet")).reset_index(drop=True)
        df = d if df is None else df
        assert (d["code"].values == df["code"].values).all()
        D[m] = cosine_matrix(np.vstack(d["embedding"].values))
    P = RQ4Pairs(df)
    labels = np.asarray(df["code_type"], dtype=object)

    def stat(lab):
        (ci, cj), (ii, ij) = P.cluster_pairs(lab)
        return {m: float(X[ci, cj].mean() - X[ii, ij].mean()) for m, X in D.items()}

    obs = stat(labels)
    out = {}
    for scheme in ("within_language", "within_pair_swap"):
        rng = np.random.default_rng(SEED)
        null = {m: [] for m in EMBEDDERS}
        for _ in range(N_PERM):
            lab = labels.copy()
            if scheme == "within_language":
                for rows in P.lang_rows.values():
                    lab[rows] = rng.permutation(lab[rows])
            else:
                for l in P.languages:
                    swap = rng.random(len(P.b[l])) < 0.5
                    b, f = P.b[l][swap], P.f[l][swap]
                    lab[b], lab[f] = "fixed", "buggy"
            s = stat(lab)
            for m in EMBEDDERS:
                null[m].append(s[m])
        out[scheme] = {m: summary(obs[m], null[m], welch("rq4", m)) for m in EMBEDDERS}
    return out


def main():
    res = {"n_permutations": N_PERM, "seed": SEED}
    res["rq2_cross_vs_intra_framework"] = rq2(); print("rq2 done", flush=True)
    res["rq4_cluster_cross_vs_intra"] = rq4(); print("rq4 done", flush=True)
    res["rq3_cross_vs_intra_complexity"] = rq3(); print("rq3 done", flush=True)
    path = os.path.join(OUT_DIR, "permutation_tests.json")
    os.makedirs(OUT_DIR, exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        json.dump(r3(res), f, indent=2)
    for k in ("rq2_cross_vs_intra_framework", "rq3_cross_vs_intra_complexity"):
        for m, v in res[k].items():
            print(k[:3], m, round(v["observed_diff"], 4), v["perm_p_two_sided"], v["welch_p"], v["conclusion_changes"])
    for s, d in res["rq4_cluster_cross_vs_intra"].items():
        for m, v in d.items():
            print("rq4", s, m, round(v["observed_diff"], 4), v["perm_p_two_sided"], v["welch_p"], v["conclusion_changes"])


if __name__ == "__main__":
    main()

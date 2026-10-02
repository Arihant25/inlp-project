"""
95% bootstrap confidence intervals for the effect sizes in the RQ2 to RQ4 tables.

  - Cohen's d of cross- against intra-group distances (RQ2 frameworks, RQ3 complexity
    classes, RQ4 correctness): percentile bootstrap that resamples the cross pairs and
    the intra pairs separately (5,000 resamples, seed 0). The pairs are exactly those of
    code/rq2/2_analysis.py, code/rq3/2_analysis.py and code/RQ4/2_analysis.py (via common.py),
    so the point estimates equal the paper's values.
  - RQ4 relative pair distance R for the two code retrievers, bootstrapping bug types as
    in 6_confidence_intervals.py (which covers the seven core models and the baselines).

For the nine embedders and the identifier-aware token baselines (tfidf_alnum, edit_alnum).

Output: results/review/effect_size_cis.json
"""

import importlib.util
import json
import os
import sys

import numpy as np
import pandas as pd

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from common import (EMBEDDERS, OUT_DIR, ROOT, RQ3Pairs, RQ4Pairs, baseline_matrices, cohens_d, cosine_matrix, r3,
                    rq2_pairs, rq3_clean)

MODELS = EMBEDDERS + ["jina_code", "codesage"]
BASELINES = ["tfidf_alnum", "edit_alnum"]
N_BOOT = 5000
SEED = 0
CHUNK = 250

_spec = importlib.util.spec_from_file_location(
    "ci6", os.path.join(os.path.dirname(os.path.abspath(__file__)), "6_confidence_intervals.py"))
ci6 = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(ci6)


def d_ci(a: np.ndarray, b: np.ndarray) -> dict:
    """Cohen's d of a against b with a percentile bootstrap over the pairs of each group."""
    a, b = np.asarray(a, float), np.asarray(b, float)
    rng = np.random.default_rng(SEED)
    ds = []
    for start in range(0, N_BOOT, CHUNK):
        k = min(CHUNK, N_BOOT - start)
        x = a[rng.integers(0, len(a), (k, len(a)))]
        y = b[rng.integers(0, len(b), (k, len(b)))]
        n1, n2 = len(a), len(b)
        pooled = np.sqrt(((n1 - 1) * x.var(1, ddof=1) + (n2 - 1) * y.var(1, ddof=1)) / (n1 + n2 - 2))
        ds.append((x.mean(1) - y.mean(1)) / pooled)
    ds = np.concatenate(ds)
    return {"d": cohens_d(a, b), "d_ci": [float(np.percentile(ds, 2.5)), float(np.percentile(ds, 97.5))],
            "n_cross": int(len(a)), "n_intra": int(len(b))}


def emb(df):
    return cosine_matrix(np.vstack(df["embedding"].values))


def load(rq, m):
    folder = {"rq2": "rq2", "rq3": "rq3", "rq4": "RQ4"}[rq]
    return pd.read_parquet(os.path.join(ROOT, f"results/{folder}/{m}/{rq}_embeddings.parquet")).reset_index(drop=True)


def rq2():
    out = {}
    for m in MODELS:
        df = load("rq2", m)
        cross, intra = rq2_pairs(df)
        D = emb(df)
        out[m] = d_ci(D[cross[:, 0], cross[:, 1]], D[intra[:, 0], intra[:, 1]])
    for b, D in baseline_matrices(df["code"].tolist()).items():
        if b in BASELINES:
            out[b] = d_ci(D[cross[:, 0], cross[:, 1]], D[intra[:, 0], intra[:, 1]])
    return out


def rq3():
    out = {}
    for m in MODELS:
        df = load("rq3", m)
        dfc = df[rq3_clean(df)].reset_index(drop=True)
        (ci, cj), (ii, ij) = RQ3Pairs(dfc).pairs(dfc["complexity_class"].values)
        D = emb(dfc)
        out[m] = d_ci(D[ci, cj], D[ii, ij])
    for b, D in baseline_matrices(dfc["code"].tolist()).items():
        if b in BASELINES:
            out[b] = d_ci(D[ci, cj], D[ii, ij])
    return out


def rq4():
    out, r_ci = {}, {}
    for m in MODELS:
        df = load("rq4", m)
        P = RQ4Pairs(df)
        (ci, cj), (ii, ij) = P.cluster_pairs(df["code_type"].values)
        D = emb(df)
        out[m] = d_ci(D[ci, cj], D[ii, ij])
        if m in ("jina_code", "codesage"):
            r_ci[m] = ci6.boot_R(ci6.rq4_S(D, P), P.cat[P.languages[0]])
    for b, D in baseline_matrices(df["code"].tolist()).items():
        if b in BASELINES:
            out[b] = d_ci(D[ci, cj], D[ii, ij])
    return out, r_ci


def main():
    res = {"rq2_cohens_d": rq2(), "rq3_cohens_d": rq3()}
    res["rq4_cohens_d"], res["rq4_R_bootstrap_bug_types_code_retrievers"] = rq4()
    os.makedirs(OUT_DIR, exist_ok=True)
    with open(os.path.join(OUT_DIR, "effect_size_cis.json"), "w", encoding="utf-8") as f:
        json.dump(r3(res), f, indent=2)
    for k in ("rq2_cohens_d", "rq3_cohens_d", "rq4_cohens_d"):
        print(k)
        for m, v in res[k].items():
            print(f"  {m:12s} d={v['d']:.3f}  [{v['d_ci'][0]:.3f}, {v['d_ci'][1]:.3f}]")
    for m, v in res["rq4_R_bootstrap_bug_types_code_retrievers"].items():
        print(f"rq4 R {m}: {v['R']:.3f} {v['R_ci']}")


if __name__ == "__main__":
    main()

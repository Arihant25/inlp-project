"""
Review W1: lexical baselines for RQ2-RQ5.

Each code snippet is represented lexically, with no learned model:
  tfidf_alnum  TF-IDF over identifier-aware tokens (split on non-alphanumerics,
               camelCase, snake_case; lower-cased), cosine distance
  tfidf_punct  same, but every punctuation/operator character is also a token
  edit_alnum   normalized token Levenshtein distance (edit distance on the token
               sequences / max token length)
  edit_punct   same on the punctuation-preserving token sequences
TF-IDF is fitted on the corpus of the RQ (RQ2, RQ3, RQ4: all snippets; RQ5: all
HumanEvalFix documents of the six languages, and ClassEval documents separately).

For every baseline AND for the seven embedders (recomputed through the same code
path, so the comparison is like for like) the script computes the paper's metrics:
  RQ2: framework silhouette (32 language-qualified labels), language silhouette,
       cross/intra-framework means, Cohen's d (pairing of code/rq2/2_analysis.py)
  RQ3: complexity silhouette, cross/intra-complexity Cohen's d (seeded subsampling
       of code/rq3/2_analysis.py), problem-identity Cohen's d
  RQ4: correctness and language silhouette, pair mean, exhaustive unmatched mean,
       R, 1/R, dangerous-neighbourhood rates, R per category, syntax ratio
       (code/RQ4/5_relative_distance.py)
  RQ5: HumanEvalFix and ClassEval proximity R with exhaustive unmatched pairs
       (embedders: the six RQ5 embedders; Ada-002 is not used in RQ5)

The embedder values are checked against the existing metric files (embedder_check).

Output: results/review/lexical_baselines.json
"""

import json
import os
import sys
import time

import numpy as np
import pandas as pd

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from common import (EMBEDDERS, OUT_DIR, ROOT, RQ3Pairs, RQ4Pairs, RQ5_EMBEDDERS, baseline_matrices,
                    cosine_matrix, load_rq5_vectors, r3, rq2_metrics, rq3_clean, rq3_metrics,
                    rq3_problem_identity_pairs, rq4_metrics, rq5_docs, rq5_proximity, tfidf_matrix,
                    edit_matrix, TOKENIZERS)

BASELINES = ["tfidf_alnum", "tfidf_punct", "edit_alnum", "edit_punct"]


def emb(df):
    return cosine_matrix(np.vstack(df["embedding"].values))


def log(msg):
    print(f"[{time.strftime('%H:%M:%S')}] {msg}", flush=True)


def run_rq2():
    out = {}
    for m in EMBEDDERS:
        df = pd.read_parquet(os.path.join(ROOT, f"results/rq2/{m}/rq2_embeddings.parquet"))
        out[m] = rq2_metrics(emb(df), df)
    for b, D in baseline_matrices(df["code"].tolist()).items():
        out[b] = rq2_metrics(D, df)
    return out


def run_rq3():
    out = {}
    df0 = None
    for m in EMBEDDERS:
        df = pd.read_parquet(os.path.join(ROOT, f"results/rq3/{m}/rq3_embeddings.parquet"))
        mask = rq3_clean(df)
        dfc = df[mask].reset_index(drop=True)
        if df0 is None:
            df0, T, ID = dfc, RQ3Pairs(dfc), rq3_problem_identity_pairs(dfc)
        assert (dfc["code"].values == df0["code"].values).all()
        out[m] = rq3_metrics(emb(dfc), dfc, T, ID)
        log(f"rq3 {m} done")
    codes = df0["code"].tolist()
    for tname, tok in TOKENIZERS.items():
        out[f"tfidf_{tname}"] = rq3_metrics(tfidf_matrix(codes, tok), df0, T, ID)
        log(f"rq3 tfidf_{tname} done")
        out[f"edit_{tname}"] = rq3_metrics(edit_matrix(codes, tok), df0, T, ID)
        log(f"rq3 edit_{tname} done")
    return out


def run_rq4():
    out = {}
    for m in EMBEDDERS:
        df = pd.read_parquet(os.path.join(ROOT, f"results/RQ4/{m}/rq4_embeddings.parquet")).reset_index(drop=True)
        out[m] = rq4_metrics(emb(df), df)
    P = RQ4Pairs(df)
    for b, D in baseline_matrices(df["code"].tolist()).items():
        out[b] = rq4_metrics(D, df, P)
    return out


def run_rq5():
    he, ce = rq5_docs()
    out = {"humanevalfix": {}, "classeval": {}}
    for m in RQ5_EMBEDDERS:
        v = load_rq5_vectors(m)
        Dh = {l: cosine_matrix(np.vstack([v[f"{l}|d|{d}"] for d in docs["doc_id"]])) for l, docs in he.items()}
        out["humanevalfix"][m] = rq5_proximity(Dh, he)
        Dc = cosine_matrix(np.vstack([v[f"classeval|d|{d}"] for d in ce["doc_id"]]))
        out["classeval"][m] = rq5_proximity({"classeval": Dc}, {"classeval": ce})
    # HumanEvalFix: fit on all six languages' documents, then slice per language
    all_docs = pd.concat([d.assign(lang=l) for l, d in he.items()], ignore_index=True)
    full = baseline_matrices(all_docs["code"].tolist())
    for b, D in full.items():
        Dh = {}
        for l in he:
            idx = np.where(all_docs["lang"].values == l)[0]
            Dh[l] = D[np.ix_(idx, idx)]
        out["humanevalfix"][b] = rq5_proximity(Dh, he)
    for b, D in baseline_matrices(ce["code"].tolist()).items():
        out["classeval"][b] = rq5_proximity({"classeval": D}, {"classeval": ce})
    return out


def embedder_check(res):
    """Max absolute difference between recomputed embedder metrics and the paper's metric files."""
    diffs = {}
    for m in EMBEDDERS:
        a = json.load(open(os.path.join(ROOT, f"results/rq2/{m}/rq2_metrics.json"), encoding="utf-8"))
        b = json.load(open(os.path.join(ROOT, f"results/rq3/{m}/rq3_metrics.json"), encoding="utf-8"))
        c = json.load(open(os.path.join(ROOT, f"results/RQ4/{m}/rq4_metrics.json"), encoding="utf-8"))
        rd = json.load(open(os.path.join(ROOT, "results/RQ4/relative_distance.json"), encoding="utf-8"))[m]
        pairs = [
            (res["rq2"][m]["framework_silhouette"], a["silhouette"]["framework_silhouette_overall"]),
            (res["rq2"][m]["language_silhouette"], a["silhouette"]["language_silhouette_overall"]),
            (res["rq2"][m]["cohens_d"], a["statistical_tests"]["effect_size"]["cohens_d"]),
            (res["rq3"][m]["complexity_silhouette"], b["silhouette"]["complexity_silhouette_overall"]),
            (res["rq3"][m]["cohens_d"], b["statistical_tests"]["effect_size"]["cohens_d"]),
            (res["rq3"][m]["problem_identity"]["cohens_d"], b["problem_identity"]["cohens_d"]),
            (res["rq4"][m]["correctness_silhouette"], c["silhouette"]["correctness_silhouette_overall"]),
            (res["rq4"][m]["cluster_cohens_d"], c["statistical_tests"]["effect_size"]["cohens_d"]),
            (res["rq4"][m]["R"], rd["R"]),
            (res["rq4"][m]["R_by_category"]["syntax"], rd["R_by_category"]["syntax"]),
        ]
        diffs[m] = max(abs(x - y) for x, y in pairs)
    return diffs


def main():
    os.makedirs(OUT_DIR, exist_ok=True)
    res = {}
    log("RQ2"); res["rq2"] = run_rq2()
    log("RQ4"); res["rq4"] = run_rq4()
    log("RQ5"); res["rq5"] = run_rq5()
    log("RQ3"); res["rq3"] = run_rq3()
    res["embedder_check_max_abs_diff_vs_paper_files"] = embedder_check(res)
    res["notes"] = {
        "baselines": {"tfidf_alnum": "TF-IDF cosine, identifier tokens", "tfidf_punct": "TF-IDF cosine, identifier + punctuation tokens",
                      "edit_alnum": "normalized token Levenshtein, identifier tokens",
                      "edit_punct": "normalized token Levenshtein, identifier + punctuation tokens"},
        "rq5_tfidf_fit": "HumanEvalFix: fitted on all 6 x 328 documents; ClassEval: fitted on its 162 documents",
        "dangerous_thresholds": "Same absolute thresholds (0.05/0.10/0.15) as for embedders; baseline distance scales differ.",
    }
    path = os.path.join(OUT_DIR, "lexical_baselines.json")
    with open(path, "w", encoding="utf-8") as f:
        json.dump(r3(res), f, indent=2)
    log(f"saved {path}")
    print(json.dumps(res["embedder_check_max_abs_diff_vs_paper_files"], indent=1))


if __name__ == "__main__":
    main()

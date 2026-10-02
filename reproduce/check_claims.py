"""
Reproducibility check: re-derive the numbers reported in the paper from the committed
results and compare each with the value printed in the paper.

Runs without network access, API calls or re-embedding, and reads only files that are
tracked by git:

  recomputed  from committed embeddings and per-item records
      results/rq2|rq3|RQ4/<model>/rq*_embeddings.parquet   silhouettes, cross/intra means,
                                                           Cohen's d (same pair selection and
                                                           seeds as the analysis scripts), R,
                                                           dangerous neighbourhoods
      results/clustering/<model>/cosine_distance_matrix.json  Ward dendrograms, CCC, families,
                                                           ARI, overlap scores, Spearman
      results/rq5/embeddings/*.npz                         retrieval metrics, real-bug R
      results/rq5/retrieval/rankings.json                  BM25, top-3 contexts
      results/rq5/execution, results/review/execution      pass@1 and exact McNemar tests
      results/review/pairs.parquet, variants.parquet       behaviour-preserving control
  read        from committed metric files, for numbers whose inputs are not tracked
              (LLM judgements, example-test execution, token baselines, truncation runs,
              the RQ1 snippet bootstrap, permutation and regression tests)

A value printed with k decimals passes when the recomputed value lies within half a unit
of the k-th decimal (the paper's rounding). Counts must match exactly, and verbal
approximations ("about a factor of two") use an explicit tolerance that the check states.

Usage:
    .venv/Scripts/python.exe reproduce/check_claims.py            # full table
    .venv/Scripts/python.exe reproduce/check_claims.py --summary  # failures and counts only

Output: table on stdout, reproduce/claims_report.md (generated); exit code 1 if any check fails.
"""

import argparse
import json
import math
import os
import re
import sys
import time
from dataclasses import dataclass, field
from itertools import combinations

import numpy as np
import pandas as pd
from scipy.cluster.hierarchy import cophenet, fcluster, linkage
from scipy.spatial.distance import squareform
from scipy.stats import binomtest, spearmanr, ttest_ind, wilcoxon

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REPORT = os.path.join(ROOT, "reproduce", "claims_report.md")

CORE = ["octen", "qwen3", "bge_m3", "minilm", "ada002", "unixcoder", "codebert"]
RETRIEVERS_NEW = ["jina_code", "codesage"]
ALL9 = CORE + RETRIEVERS_NEW
LOCAL6 = ["octen", "qwen3", "bge_m3", "minilm", "unixcoder", "codebert"]   # RQ5 / control embedders
FIVE = ["octen", "qwen3", "bge_m3", "minilm", "unixcoder"]                 # LOCAL6 without CodeBERT
YUN = ["ada002", "bge_m3", "unixcoder"]
CODE_MODELS = ["unixcoder", "codebert", "jina_code", "codesage"]
RQ5_DENSE = LOCAL6 + RETRIEVERS_NEW                                         # eight open embedders
CLS = ["codebert_cls", "unixcoder_cls"]
HEF_LANGS = ["python", "js", "java", "go", "cpp", "rust"]
GEN_LANGS = ["python", "js", "java"]
LLMS = {"gemma4:31b": "gemma4_31b", "gpt-oss:20b": "gpt-oss_20b", "deepseek-v4.1-flash": "deepseek-v4.1-flash"}
CATEGORY = {"Easy": "syntax", "Medium": "logic", "Hard": "state", "Super Hard": "numeric"}
THRESHOLDS = [0.05, 0.10, 0.15]
SQ, CU = "O(n" + chr(0xB2) + ")", "O(n" + chr(0xB3) + ")"   # RQ3 class labels O(n^2), O(n^3) as stored


# ── Files ─────────────────────────────────────────────────────────────────────


def path(rel: str) -> str:
    return os.path.join(ROOT, rel)


_json_cache: dict = {}


def load_json(rel: str):
    if rel not in _json_cache:
        with open(path(rel), encoding="utf-8") as f:
            _json_cache[rel] = json.load(f)
    return _json_cache[rel]


def load_jsonl(rel: str) -> list[dict]:
    with open(path(rel), encoding="utf-8") as f:
        return [json.loads(line) for line in f if line.strip()]


# Which script writes each committed file (first matching rule wins).
PRODUCERS = [
    (r"results/clustering/(jina_code|codesage)/", "code/review/rq1_new_embedders.py"),
    (r"results/clustering/[^/]+/(cosine_distance_matrix|language_families)\.json", "code/clustering.py"),
    (r"results/clustering/family_stability\.json", "code/family_stability.py"),
    (r"results/clustering/model_spearman\.json", "code/model_distance.py"),
    (r"results/rq2/(jina_code|codesage)/rq2_embeddings\.parquet", "code/review/embed_resumable.py"),
    (r"results/rq3/(jina_code|codesage)/rq3_embeddings\.parquet", "code/review/embed_resumable.py"),
    (r"results/rq2/[^/]+/rq2_embeddings\.parquet", "code/rq2/1_embedding.py"),
    (r"results/rq3/[^/]+/rq3_embeddings\.parquet", "code/rq3/1_embedding.py"),
    (r"results/rq3/[^/]+/rq3_complexity_stats\.json", "code/rq3/1_embedding.py"),
    (r"results/RQ4/[^/]+/rq4_embeddings\.parquet", "code/RQ4/1_embedding.py"),
    (r"results/rq2/[^/]+/rq2_metrics\.json", "code/rq2/2_analysis.py"),
    (r"results/rq3/[^/]+/rq3_metrics\.json", "code/rq3/2_analysis.py"),
    (r"results/RQ4/[^/]+/rq4_metrics\.json", "code/RQ4/2_analysis.py"),
    (r"results/RQ4/relative_distance\.json", "code/RQ4/5_relative_distance.py"),
    (r"results/review/rq4_relative_distance_new_embedders\.json", "code/RQ4/5_relative_distance.py"),
    (r"results/rq5/embeddings/", "code/rq5/1_embedding.py"),
    (r"results/rq5/retrieval/", "code/rq5/2_retrieval.py"),
    (r"results/rq5/generations/", "code/rq5/generate.py"),
    (r"results/rq5/execution/", "code/rq5/3_evaluate.py"),
    (r"results/rq5/generation_metrics_", "code/rq5/3_evaluate.py"),
    (r"results/review/lexical_baselines\.json", "code/review/1_lexical_baselines.py"),
    (r"results/review/confidence_intervals\.json", "code/review/6_confidence_intervals.py"),
    (r"results/review/effect_size_cis\.json", "code/review/7_effect_size_cis.py"),
    (r"results/review/edit_size_control\.json", "code/review/2_edit_size_control.py"),
    (r"results/review/permutation_tests\.json", "code/review/3_permutation_tests.py"),
    (r"results/review/rq5_clustered_tests\.json", "code/review/4_rq5_clustered_tests.py"),
    (r"results/review/(pairs\.parquet|control_results\.json)", "code/review/analyze.py"),
    (r"results/review/control_kinds\.json", "code/review/analyze_kinds.py"),
    (r"results/review/(variants\.parquet|variant_counts\.json)", "code/review/variants.py"),
    (r"results/review/(framing|top3|samples|judge_e2e)_metrics\.json", "code/review/evaluate_review.py"),
    (r"results/review/execution/", "code/review/evaluate_review.py"),
    (r"results/review/judge", "code/review/llm_judge.py"),
    (r"results/review/rq3_label", "code/review/llm_rq3_labels.py"),
    (r"results/review/rq1_robustness\.json", "code/review/rq1_robustness.py"),
    (r"results/review/rq1_bootstrap\.json", "code/review/rq1_bootstrap.py (snippets from rq1_reembed.py)"),
    (r"results/review/rq1_new_embedders\.json", "code/review/rq1_new_embedders.py"),
    (r"results/review/rq5_new_embedders\.json", "code/review/rq5_new_embedders.py"),
    (r"results/review/rq5_query_instructions\.json", "code/review/rq5_query_instructions.py"),
    (r"results/review/new_embedders_summary\.json", "code/review/new_embedders_summary.py"),
    (r"results/review/truncation_new_embedders\.json", "code/review/truncation_new_embedders.py"),
    (r"results/truncation_stats\.json", "code/truncation_stats.py"),
    (r"results/truncation_sensitivity\.json", "code/truncation_sensitivity.py"),
    (r"datasets/RQ5/classeval/classeval_pairs\.parquet", "code/rq5/0_prepare_classeval.py"),
    (r"datasets/", "(input dataset)"),
]


def producer(rel: str) -> str:
    for pattern, script in PRODUCERS:
        if re.match(pattern, rel):
            return script
    return "(unknown)"


# ── Checks ────────────────────────────────────────────────────────────────────


@dataclass
class Check:
    cid: str
    where: str
    desc: str
    paper: str                 # the value as printed in the paper
    value: object              # the recomputed value
    sources: list
    mode: str = "round"        # round | exact | match | lt | le | gt | ge | range | approx
    tol: float | None = None   # approx: absolute tolerance
    lo: float | None = None    # range: lo <= value < hi
    hi: float | None = None
    method: str = "recomputed"
    status: str = ""
    rq: str = ""


class Registry:
    def __init__(self):
        self.checks: list[Check] = []
        self.rq = ""

    def section(self, rq: str):
        self.rq = rq

    def add(self, cid, where, desc, paper, value, sources, **kw):
        c = Check(cid, where, desc, str(paper), value, list(sources), **kw)
        c.rq = self.rq
        c.status = evaluate(c)
        self.checks.append(c)


def paper_number(s: str) -> float:
    return float(s.replace("$-$", "-").replace("%", "").replace(",", "").strip())


def decimals(s: str) -> int:
    s = s.replace("%", "").strip()
    return len(s.split(".")[1]) if "." in s else 0


def evaluate(c: Check) -> str:
    v = c.value
    if v is None or (isinstance(v, float) and math.isnan(v)):
        return "FAIL"
    if c.mode == "round":
        tol = 0.5 * 10 ** -decimals(c.paper) + 1e-9
        return "PASS" if abs(float(v) - paper_number(c.paper)) <= tol else "FAIL"
    if c.mode == "exact":
        return "PASS" if int(v) == int(paper_number(c.paper)) else "FAIL"
    if c.mode == "match":
        return "PASS" if str(v) == c.paper else "FAIL"
    if c.mode == "lt":
        return "PASS" if float(v) < paper_number(c.paper) else "FAIL"
    if c.mode == "le":
        return "PASS" if float(v) <= paper_number(c.paper) else "FAIL"
    if c.mode == "gt":
        return "PASS" if float(v) > paper_number(c.paper) else "FAIL"
    if c.mode == "ge":
        return "PASS" if float(v) >= paper_number(c.paper) else "FAIL"
    if c.mode == "range":
        return "PASS" if c.lo <= float(v) < c.hi else "FAIL"
    if c.mode == "approx":
        return "PASS" if abs(float(v) - paper_number(c.paper)) <= c.tol else "FAIL"
    raise ValueError(c.mode)


def criterion(c: Check) -> str:
    if c.mode == "round":
        return f"rounds to {c.paper}"
    if c.mode == "exact":
        return "equals"
    if c.mode == "match":
        return "matches"
    if c.mode in ("lt", "le", "gt", "ge"):
        return {"lt": "<", "le": "<=", "gt": ">", "ge": ">="}[c.mode] + f" {c.paper}"
    if c.mode == "range":
        return f"in [{c.lo}, {c.hi})"
    return f"within {c.tol} of {c.paper}"


def fmt(c: Check) -> str:
    v = c.value
    if isinstance(v, (bool, np.bool_)) or isinstance(v, str):
        return str(v)
    if isinstance(v, (int, np.integer)):
        return str(int(v))
    if v is None:
        return "None"
    v = float(v)
    if c.mode == "round":
        return f"{v:.{decimals(c.paper) + 2}f}"
    if v != 0 and abs(v) < 1e-3:
        return f"{v:.3g}"
    return f"{v:.4f}"


# ── Statistics ────────────────────────────────────────────────────────────────


def cosine_matrix(X: np.ndarray) -> np.ndarray:
    """Cosine distances (1 - cos), diagonal 0, clipped to [0, 2] as sklearn; keeps X's float type."""
    X = X / np.linalg.norm(X, axis=1, keepdims=True)
    D = X @ X.T
    np.subtract(1.0, D, out=D)
    np.fill_diagonal(D, 0.0)
    return np.clip(D, 0.0, 2.0, out=D)


def silhouette(D: np.ndarray, labels) -> float:
    """Mean silhouette coefficient on a precomputed distance matrix (as sklearn)."""
    _, inv = np.unique(np.asarray(labels), return_inverse=True)
    n, k = len(inv), inv.max() + 1
    onehot = np.zeros((n, k), dtype=D.dtype)
    onehot[np.arange(n), inv] = 1.0
    sums = D @ onehot
    counts = onehot.sum(0)
    own = counts[inv]
    a = sums[np.arange(n), inv] / np.maximum(own - 1, 1)
    means = sums / counts
    means[np.arange(n), inv] = np.inf
    b = means.min(1)
    with np.errstate(invalid="ignore", divide="ignore"):
        s = (b - a) / np.maximum(a, b)
    s[own == 1] = 0.0
    return float(np.nan_to_num(s).mean())


def cohens_d(a, b) -> float:
    a, b = np.asarray(a, float), np.asarray(b, float)
    n1, n2 = len(a), len(b)
    pooled = np.sqrt(((n1 - 1) * np.var(a, ddof=1) + (n2 - 1) * np.var(b, ddof=1)) / (n1 + n2 - 2))
    return float((a.mean() - b.mean()) / pooled)


def effect_label(d: float) -> str:
    d = abs(d)
    return "Negligible" if d < 0.2 else "Small" if d < 0.5 else "Medium" if d < 0.8 else "Large"


def ari(a, b) -> float:
    """Adjusted Rand index of two flat partitions."""
    _, a = np.unique(np.asarray(a), return_inverse=True)
    _, b = np.unique(np.asarray(b), return_inverse=True)
    C = np.zeros((a.max() + 1, b.max() + 1))
    np.add.at(C, (a, b), 1)
    comb = lambda x: x * (x - 1) / 2.0
    sum_ij, sa, sb = comb(C).sum(), comb(C.sum(1)).sum(), comb(C.sum(0)).sum()
    expected = sa * sb / comb(len(a))
    top = (sa + sb) / 2.0
    return float((sum_ij - expected) / (top - expected)) if top != expected else 1.0


def mcnemar_p(a: list, b: list) -> float:
    n01 = sum(1 for x, y in zip(a, b) if x == 1 and y == 0)
    n10 = sum(1 for x, y in zip(a, b) if x == 0 and y == 1)
    return float(binomtest(n01, n01 + n10, 0.5).pvalue) if n01 + n10 else 1.0


def pair_name(a: str, b: str) -> str:
    return "-".join(sorted([a, b]))


def pairs_text(pairs) -> str:
    return ", ".join(sorted(pair_name(a, b) for a, b in pairs))


# ── Pair selection (ports of code/review/common.py, which reproduces the analysis scripts) ──


def intra_positions(n: int, max_pairs: int, seed: int = 42):
    if n < 2:
        return np.zeros(0, int), np.zeros(0, int)
    i, j = np.triu_indices(n, 1)
    if len(i) > max_pairs:
        c = np.random.default_rng(seed).choice(len(i), max_pairs, replace=False)
        i, j = i[c], j[c]
    return i, j


def cross_positions(n1: int, n2: int, max_pairs: int, seed: int = 42):
    if n1 == 0 or n2 == 0:
        return np.zeros(0, int), np.zeros(0, int)
    total = n1 * n2
    flat = np.arange(total)
    if total > max_pairs:
        flat = np.random.default_rng(seed).choice(total, max_pairs, replace=False)
    return np.divmod(flat, n2)


META = {"rq2": ["language", "framework", "pattern", "variation"],
        "rq3": ["problem_slug", "difficulty", "language", "time_complexity", "complexity_class"],
        "RQ4": ["bug_index", "severity", "language", "code_type"]}


def embeddings(rel: str, dtype=np.float64) -> tuple[pd.DataFrame, np.ndarray]:
    """Metadata columns and the embedding matrix of a committed parquet file (code text not loaded)."""
    df = pd.read_parquet(path(rel), columns=META[rel.split("/")[1]] + ["embedding"]).reset_index(drop=True)
    X = np.vstack(df["embedding"].values).astype(dtype)
    df = df.drop(columns=["embedding"])
    # plain object columns: element access on Arrow-backed strings is slow in the pair loops
    return df.astype({c: object for c in df.columns if df[c].dtype != np.int64}), X


# ── RQ1: language families from the committed distance matrices ──────────────


def rq1_data() -> dict:
    out = {"models": {}}
    for m in ALL9:
        rel = f"results/clustering/{m}/cosine_distance_matrix.json"
        st = load_json(rel)
        M = np.array(st["matrix"], dtype=float)
        d = squareform(M, checks=False)
        Z = linkage(d, method="ward")
        ccc = float(cophenet(Z, d)[0])
        parts = {k: fcluster(Z, t=k, criterion="maxclust") for k in range(3, 9)}
        out["models"][m] = {"ccc": ccc, "parts": parts, "condensed": d, "max_dist": float(M.max())}
        out["languages"] = st["languages"]
    return out


def co_clustered(langs, parts_list) -> set:
    """Language pairs that share a family in every partition of parts_list."""
    keep = set()
    for i, j in combinations(range(len(langs)), 2):
        if all(p[i] == p[j] for p in parts_list):
            keep.add((langs[i], langs[j]))
    return keep


def family_of(langs, part, lang) -> set:
    i = langs.index(lang)
    return {l for l, c in zip(langs, part) if c == part[i]}


def overlap_scores(langs, parts: dict) -> dict:
    out = {}
    for l in langs:
        vals = []
        for a, b in combinations(CORE, 2):
            fa, fb = family_of(langs, parts[a], l), family_of(langs, parts[b], l)
            vals.append(len(fa & fb) / len(fa | fb))
        out[l] = float(np.mean(vals))
    return out


# ── RQ2: framework separation ─────────────────────────────────────────────────


def rq2_data() -> dict:
    out = {}
    for m in ALL9:
        rel = f"results/rq2/{m}/rq2_embeddings.parquet"
        df, X = embeddings(rel)
        D = cosine_matrix(X)
        qual = (df["language"] + "/" + df["framework"]).values
        cross, intra = [], []
        for _, g in df.groupby(["pattern", "language"], sort=True):
            for a, b in combinations(g.index.values, 2):
                (cross if qual[a] != qual[b] else intra).append((a, b))
        cross, intra = np.array(cross), np.array(intra)
        dc, di = D[cross[:, 0], cross[:, 1]], D[intra[:, 0], intra[:, 1]]
        per_lang = {}
        for lang in sorted(df["language"].unique()):
            rows = np.where(df["language"].values == lang)[0]
            per_lang[lang] = silhouette(D[np.ix_(rows, rows)], df["framework"].values[rows])
        iu = np.triu_indices(len(df), 1)
        out[m] = {"fw_sil": silhouette(D, qual), "lang_sil": silhouette(D, df["language"].values),
                  "cross": float(dc.mean()), "intra": float(di.mean()), "d": cohens_d(dc, di),
                  "n_cross": len(dc), "n_intra": len(di), "per_lang": per_lang,
                  "mean_all_pairs": float(D[iu].mean()), "dim": X.shape[1], "n": len(df), "df": df}
    return out


# ── RQ3: complexity class ─────────────────────────────────────────────────────


def rq3_identity_pairs(df: pd.DataFrame, max_pairs: int = 200):
    """Same problem / different class vs different problem / same class (seed 42)."""
    rng = np.random.default_rng(42)
    same, diff = [], []
    for lang in sorted(df["language"].unique()):
        rows = np.where(df["language"].values == lang)[0]
        problems = df["problem_slug"].values[rows]
        classes = df["complexity_class"].values[rows]
        for slug in np.unique(problems):
            idx = np.where(problems == slug)[0]
            same += [(rows[i], rows[j]) for i, j in combinations(idx, 2) if classes[i] != classes[j]]
        for cc in np.unique(classes):
            idx = np.where(classes == cc)[0]
            pairs = [(i, j) for i, j in combinations(idx, 2) if problems[i] != problems[j]]
            if len(pairs) > max_pairs:
                chosen = rng.choice(len(pairs), max_pairs, replace=False)
                pairs = [pairs[c] for c in chosen]
            diff += [(rows[i], rows[j]) for i, j in pairs]
    return np.array(same), np.array(diff)


def rq3_class_pairs(df: pd.DataFrame):
    """Cross- and intra-class pairs: up to 200 per class (pair) and language, seed 42 templates."""
    labels = df["complexity_class"].values
    classes = sorted(np.unique(labels))
    ci, cj, ii, ij = [], [], [], []
    for lang in sorted(df["language"].unique()):
        rows = np.where(df["language"].values == lang)[0]
        members = {cc: rows[labels[rows] == cc] for cc in classes}
        for cc in classes:
            p, q = intra_positions(len(members[cc]), 200)
            ii.append(members[cc][p]); ij.append(members[cc][q])
        for a, b in combinations(classes, 2):
            p, q = cross_positions(len(members[a]), len(members[b]), 200)
            ci.append(members[a][p]); cj.append(members[b][q])
    cat = lambda x: np.concatenate(x).astype(int)
    return (cat(ci), cat(cj)), (cat(ii), cat(ij))


def rq3_data() -> dict:
    out, ref, pairs = {}, None, None
    for m in ALL9:
        rel = f"results/rq3/{m}/rq3_embeddings.parquet"
        df, X = embeddings(rel, np.float32)   # float32, as the analysis script's input; 4,228 x 4,228
        if ref is None:
            ref = df
        else:
            for c in ("problem_slug", "language", "complexity_class", "time_complexity"):
                assert (df[c].values == ref[c].values).all(), f"RQ3 row order differs for {m}"
        keep = (df["complexity_class"] != "Other").values
        dfc = df[keep].reset_index(drop=True)
        D = cosine_matrix(X[keep])
        if pairs is None:
            pairs = (rq3_class_pairs(dfc), rq3_identity_pairs(dfc))
        ((ci, cj), (ii, ij)), (same, diff) = pairs
        dc, di = D[ci, cj].astype(np.float64), D[ii, ij].astype(np.float64)
        ds, dd = D[same[:, 0], same[:, 1]].astype(np.float64), D[diff[:, 0], diff[:, 1]].astype(np.float64)
        out[m] = {"sil": silhouette(D, dfc["complexity_class"].values), "cross": float(dc.mean()),
                  "intra": float(di.mean()), "d": cohens_d(dc, di),
                  "p": float(ttest_ind(dc, di, equal_var=False).pvalue),
                  "pid_same": float(ds.mean()), "pid_diff": float(dd.mean()), "pid_d": cohens_d(dd, ds),
                  "n_same": len(ds), "n_diff": len(dd)}
        del D
    out["_df"] = ref
    return out


# ── RQ4: bug-fix proximity ────────────────────────────────────────────────────


def rq4_data() -> dict:
    out = {}
    for m in ALL9:
        rel = f"results/RQ4/{m}/rq4_embeddings.parquet"
        df, X = embeddings(rel)
        D = cosine_matrix(X)
        langs = sorted(df["language"].unique())
        pair, unmatched, cats = [], [], []
        ci, cj, ii, ij = [], [], [], []
        p_i, q_i = intra_positions(100, 300)
        p_c, q_c = cross_positions(100, 100, 500)
        labels = df["code_type"].values
        for l in langs:
            rows = np.where(df["language"].values == l)[0]
            g = df.iloc[rows]
            b = g[g["code_type"] == "buggy"].sort_values("bug_index")
            f = g[g["code_type"] == "fixed"].sort_values("bug_index")
            assert list(b["bug_index"]) == list(f["bug_index"])
            sub = D[np.ix_(b.index.values, f.index.values)]
            pair.append(np.diag(sub))
            unmatched.append(sub[~np.eye(len(sub), dtype=bool)])
            cats.append(np.array([CATEGORY[s] for s in b["severity"]]))
            bm, fm = rows[labels[rows] == "buggy"], rows[labels[rows] == "fixed"]
            ii += [bm[p_i], fm[p_i]]; ij += [bm[q_i], fm[q_i]]
            ci.append(bm[p_c]); cj.append(fm[q_c])
        pair, unmatched, cats = np.concatenate(pair), np.concatenate(unmatched), np.concatenate(cats)
        cat = lambda x: np.concatenate(x).astype(int)
        dc, di = D[cat(ci), cat(cj)], D[cat(ii), cat(ij)]
        um = float(unmatched.mean())
        r_cat = {c: float(pair[cats == c].mean()) / um for c in CATEGORY.values()}
        closest = int(np.argmin(pair))
        out[m] = {"pair": float(pair.mean()), "unmatched": um, "R": float(pair.mean()) / um,
                  "inv_R": um / float(pair.mean()),
                  "dn": {t: float(100 * (pair < t).mean()) for t in THRESHOLDS},
                  "sil": silhouette(D, labels), "d": cohens_d(dc, di),
                  "p": float(ttest_ind(dc, di, equal_var=False).pvalue), "R_cat": r_cat,
                  "syntax_ratio": float(pair[cats == "syntax"].mean() / pair[cats != "syntax"].mean()),
                  "closest_cat": str(cats[closest]), "df": df}
    return out


# ── RQ5: retrieval from the committed embeddings ──────────────────────────────


def load_vectors(model: str) -> dict:
    """Unit-normalised RQ5 vectors of one model, normalised in the stored precision exactly as
    code/rq5/2_retrieval.py does, so that exactly tied twins break the same way."""
    keys, mats = [], []
    for suffix in ("", "_classeval"):
        z = np.load(path(f"results/rq5/embeddings/{model}{suffix}.npz"))
        keys += [str(k) for k in z["keys"]]
        mats.append(z["vectors"] / np.linalg.norm(z["vectors"], axis=1, keepdims=True))
    V = np.vstack(mats)
    order = {}
    for k in keys:
        lang, kind, item = k.split("|")
        order.setdefault((lang, kind), []).append(item)
    return {"index": {k: i for i, k in enumerate(keys)}, "order": order, "V": V}


def retrieval_records(vs: dict, lang: str) -> list[dict]:
    index, order = vs["index"], vs["order"]
    probs = sorted(int(p) for p in order[(lang, "q")])
    docs = order[(lang, "d")]
    doc_prob = np.array([int(d.split("_")[0]) for d in docs])
    pos = {d: i for i, d in enumerate(docs)}
    S = vs["V"][[index[f"{lang}|q|{p}"] for p in probs]] @ vs["V"][[index[f"{lang}|d|{d}"] for d in docs]].T
    recs = []
    for qi, p in enumerate(probs):
        s = S[qi]
        top = np.argsort(-s, kind="stable")
        sc, sb = s[pos[f"{p}_correct"]], s[pos[f"{p}_buggy"]]
        recs.append({"problem": p, "recall1": int(doc_prob[top[0]] == p),
                     "buggy_first": 0.5 if sb == sc else float(sb > sc), "tie": int(sb == sc),
                     "top1_buggy": int(docs[top[0]] == f"{p}_buggy"), "top1_doc": docs[top[0]]})
    return recs


def summarize(recs: list[dict]) -> dict:
    out = {k: float(np.mean([r[k] for r in recs])) for k in ("recall1", "buggy_first", "tie", "top1_buggy")}
    untied = [r for r in recs if not r["tie"]]
    k = sum(int(r["buggy_first"]) for r in untied)
    out["p"] = float(binomtest(k, len(untied), 0.5).pvalue)
    return out


def proximity(vs: dict, langs: list) -> float:
    """Relative pair distance R with exhaustive unmatched (correct a, buggy b) pairs, a != b."""
    pair, unmatched = [], []
    for lang in langs:
        probs = sorted({int(d.split("_")[0]) for d in vs["order"][(lang, "d")]})
        c = [vs["index"][f"{lang}|d|{p}_correct"] for p in probs]
        b = [vs["index"][f"{lang}|d|{p}_buggy"] for p in probs]
        sub = 1.0 - (vs["V"][c] @ vs["V"][b].T).astype(np.float64)
        pair.append(np.diag(sub))
        unmatched.append(sub[~np.eye(len(sub), dtype=bool)])
    return float(np.concatenate(pair).mean() / np.concatenate(unmatched).mean())


def rq5_retrieval_data() -> dict:
    """Per-query retrieval records and their summaries.

    The committed per-query rankings (rankings.json, written by code/rq5/2_retrieval.py) are the
    records behind the paper's retrieval numbers; they cover the six RQ5 embedders, the two CLS
    variants and BM25. The two code retrievers have no committed rankings, so their records are
    recomputed from the committed embeddings. For every embedder the records are also recomputed
    from the embeddings and compared with the committed rankings (consistency check)."""
    rankings = load_json(RANKINGS)
    out = {"summary": {}, "records": {}, "R_hef": {}, "R_ce": {}, "mismatch": {}, "order": {}}
    langs = HEF_LANGS + ["classeval"]
    for m in RQ5_DENSE + CLS + ["bm25"]:
        stored = {lang: rankings[lang][m] for lang in langs} if m in rankings["python"] else None
        if m != "bm25":
            vs = load_vectors(m)
            out["order"][m] = vs["order"]
            recs = {lang: retrieval_records(vs, lang) for lang in langs}
            if m in RQ5_DENSE:
                out["R_hef"][m] = proximity(vs, HEF_LANGS)
                out["R_ce"][m] = proximity(vs, ["classeval"])
            if stored is not None:
                for bench, ls in (("hef", HEF_LANGS), ("ce", ["classeval"])):
                    n = 0
                    for l in ls:
                        mine = {r["problem"]: r for r in recs[l]}
                        n += sum(a["top"][0] != mine[a["problem"]]["top1_doc"] or
                                 a["buggy_first"] != mine[a["problem"]]["buggy_first"] for a in stored[l])
                    out["mismatch"][(m, bench)] = n
        recs = stored if stored is not None else recs
        out["records"][m] = recs
        out["summary"][m] = {"hef": summarize([r for l in HEF_LANGS for r in recs[l]]), "ce": summarize(recs["classeval"]),
                             **{l: summarize(recs[l]) for l in HEF_LANGS}}
    return out


def ret_src(*models) -> list:
    """Sources of the per-query retrieval records of the given retrievers."""
    out = []
    for m in models:
        out += [RANKINGS] if m in LOCAL6 + CLS + ["bm25"] else npz_files([m])
    return list(dict.fromkeys(out))


def problem_level_wilcoxon(records: dict) -> float:
    by_p = {}
    for lang in HEF_LANGS:
        for r in records[lang]:
            by_p.setdefault(r["problem"], []).append(r["buggy_first"])
    bf = np.array([np.mean(by_p[p]) for p in sorted(by_p)])
    return float(wilcoxon(bf - 0.5, zero_method="wilcox").pvalue)


# ── RQ5: generation from the committed test executions ───────────────────────


def executions(rel: str, key=("problem", "context")) -> dict:
    done = {}
    for r in load_jsonl(rel):
        done[tuple(r[k] for k in key)] = int(r["passed"])
    return done


def generation_data() -> dict:
    rankings = load_json("results/rq5/retrieval/rankings.json")
    filtering = load_json("results/rq5/retrieval/metrics.json")["filtering"]
    out = {}
    for llm, tag in LLMS.items():
        res, pooled = {}, {"none": [], "correct": [], "buggy": [], "ret": {}, "filt": {}}
        for lang in GEN_LANGS + ["classeval"]:
            ex = executions(f"results/rq5/execution/ollama_{tag}/{lang}.jsonl")
            probs = sorted(r["problem"] for r in rankings[lang]["octen"])
            get = lambda p, c: ex.get((p, c))
            cond = {c: [get(p, "none" if c == "none" else f"{p}_{c}") for p in probs] for c in ("none", "correct", "buggy")}
            keep = [i for i in range(len(probs)) if all(cond[c][i] is not None for c in cond)]
            cond = {c: [cond[c][i] for i in keep] for c in cond}
            kprobs = [probs[i] for i in keep]
            r = {"n": len(kprobs), **{c: float(np.mean(cond[c])) for c in cond},
                 "p": mcnemar_p(cond["correct"], cond["buggy"]), "cond": cond, "probs": kprobs, "ex": ex,
                 "ret": {}, "filt": {}}
            for ret, recs in rankings[lang].items():
                top1 = {x["problem"]: x["top"][0] for x in recs}
                vals = [v for v in (get(p, top1[p]) for p in kprobs) if v is not None]
                picks = filtering[lang][ret]["picks"]
                fvals = [v for v in (get(p, picks[str(p)]) for p in kprobs) if v is not None]
                r["ret"][ret], r["filt"][ret] = float(np.mean(vals)), float(np.mean(fvals))
                if lang != "classeval":
                    pooled["ret"].setdefault(ret, []).extend(vals)
                    pooled["filt"].setdefault(ret, []).extend(fvals)
            res[lang] = r
            if lang != "classeval":
                for c in cond:
                    pooled[c].extend(cond[c])
        res["hef"] = {"n": len(pooled["none"]), **{c: float(np.mean(pooled[c])) for c in ("none", "correct", "buggy")},
                      "p": mcnemar_p(pooled["correct"], pooled["buggy"]),
                      "ret": {k: float(np.mean(v)) for k, v in pooled["ret"].items()},
                      "filt": {k: float(np.mean(v)) for k, v in pooled["filt"].items()}}
        out[llm] = res
    return out


def review_data(gen: dict) -> dict:
    """Warnings, top-3 context, temperature sampling and the LLM judge on ClassEval."""
    rankings = load_json("results/rq5/retrieval/rankings.json")
    top1 = {x["problem"]: x["top"][0] for x in rankings["classeval"]["octen"]}
    picks = load_json("results/review/judge/picks.json")["classeval"]
    out = {}
    for llm, tag in LLMS.items():
        ce = gen[llm]["classeval"]
        probs, ex = ce["probs"], ce["ex"]
        fr = executions(f"results/review/execution/framing/{tag}.jsonl", ("problem", "condition"))
        t3 = executions(f"results/review/execution/top3/{tag}.jsonl", ("problem", "condition"))
        sm = executions(f"results/review/execution/samples/{tag}.jsonl", ("id",))
        orig = {c: [ex[(p, f"{p}_{c}")] for p in probs] for c in ("correct", "buggy")}
        warn = {c: [fr[(p, f"warn_{c}")] for p in probs] for c in ("correct", "buggy")}
        top1_pass = [ex[(p, top1[p])] for p in probs]
        top3_pass = [t3[(p, "top3")] for p in probs]
        judge_pass = [ex[(p, "none" if picks[str(p)] == "none" else picks[str(p)])] for p in probs]
        per_sample = {c: [float(np.mean([sm[(f"{p}|{c}|s{s}",)] for p in probs])) for s in range(1, 6)]
                      for c in ("none", "correct", "buggy")}
        gaps = [a - b for a, b in zip(per_sample["correct"], per_sample["buggy"])]
        out[llm] = {"warn_correct": float(np.mean(warn["correct"])), "warn_buggy": float(np.mean(warn["buggy"])),
                    "p_warn_correct": mcnemar_p(warn["correct"], orig["correct"]),
                    "p_warn_buggy": mcnemar_p(warn["buggy"], orig["buggy"]),
                    "top1": float(np.mean(top1_pass)), "top3": float(np.mean(top3_pass)),
                    "p_top3": mcnemar_p(top3_pass, top1_pass), "judge": float(np.mean(judge_pass)),
                    "sample_gap": float(np.mean(gaps)), "sample_gaps": gaps}
    ranks = {x["problem"]: x["top"][:3] for x in rankings["classeval"]["octen"]}
    out["both_in_top3"] = sum(f"{p}_correct" in t and f"{p}_buggy" in t for p, t in ranks.items())
    judge = load_json("results/review/judge/picks.json")
    out["judge_buggy_ce"] = float(np.mean([v.endswith("_buggy") for v in judge["classeval"].values()]))
    out["judge_correct_ce"] = float(np.mean([v.endswith("_correct") for v in judge["classeval"].values()]))
    out["judge_buggy_py"] = float(np.mean([v.endswith("_buggy") for v in judge["python"].values()]))
    return out


# ── Behaviour-preserving control (results/review/pairs.parquet) ───────────────


KINDS = ["rename1", "renameall", "cmpswap", "stmtswap", "for2while"]


def control_data() -> dict:
    pairs = pd.read_parquet(path("results/review/pairs.parquet"))
    var = pd.read_parquet(path("results/review/variants.parquet"))
    var = var[var["passed"]]
    out = {"n_rewrites": len(var), "n_programs_covered": int(var.groupby(["lang", "problem"]).ngroups),
           "median_bug_tokens": float(pairs.loc[pairs.kind == "bug", "edit_tokens"].median()),
           "variants_below_4_tokens": int((pairs.loc[pairs.kind != "bug", "edit_tokens"] < 4).sum()),
           "n_variants": int((pairs.kind != "bug").sum())}
    # closest-size variant per program (ties: closest char size, then kind order, then index)
    bugs = pairs[pairs.kind == "bug"].set_index(["lang", "problem"])
    v = pairs[pairs.kind != "bug"].join(bugs[["edit_tokens", "edit_chars"]], on=["lang", "problem"], rsuffix="_bug", how="inner")
    v = v.assign(dt=(v.edit_tokens - v.edit_tokens_bug).abs(), dc=(v.edit_chars - v.edit_chars_bug).abs(),
                 ko=v.kind.map(KINDS.index))
    best = v.sort_values(["lang", "problem", "dt", "dc", "ko", "idx"]).drop_duplicates(["lang", "problem"])
    best = best.set_index(["lang", "problem"]).sort_index()
    bug_m = bugs.loc[best.index]
    exact = (best["dt"] == 0).values
    out["n_exact"] = int(exact.sum())
    out["n_programs_with_bug_and_variant"] = len(best)
    bug = pairs[pairs.kind == "bug"].set_index(["lang", "problem"])
    for rep in LOCAL6 + ["tfidf"]:
        col = f"d_{rep}"
        db, dv, ex = bug_m[col].values, best[col].values, exact
        diff = db - dv
        r = {"dz": float(diff.mean() / diff.std(ddof=1)),
             "p": float(wilcoxon(db, dv, zero_method="wilcox").pvalue),
             "exact_share_closer": float((db[ex] < dv[ex]).mean()),
             "mean_d_bug": float(db.mean())}
        for kind in ("rename1", "cmpswap"):
            g = pairs[pairs.kind == kind].groupby(["lang", "problem"])[col].mean()
            common = g.index.intersection(bug.index)
            kb, kv = bug.loc[common, col].values, g.loc[common].values
            kd = kb - kv
            r[f"{kind}_dz"] = float(kd.mean() / kd.std(ddof=1))
            r[f"{kind}_share_variant_farther"] = float((kv > kb).mean())
        if rep != "tfidf":
            r["spearman_tfidf"] = float(spearmanr(pairs[col], pairs["d_tfidf"]).statistic)
            r["fe_rel_coef"] = fe_relative_coefficient(pairs, col)
        out[rep] = r
    return out


def fe_relative_coefficient(pairs: pd.DataFrame, col: str) -> float:
    """is_bug coefficient of d ~ log1p(edit) + is_bug + problem fixed effects, over the mean variant d."""
    df = pairs[["lang", "problem", "is_bug", "edit_tokens", col]].rename(columns={col: "d"}).copy()
    df["pid"] = df.lang + "_" + df.problem.astype(str)
    has = df.groupby("pid").is_bug.agg(["sum", "count"])
    df = df[df.pid.isin(has[(has["sum"] == 1) & (has["count"] >= 2)].index)]
    df["log_edit"] = np.log1p(df.edit_tokens)
    cols = ["d", "log_edit", "is_bug"]
    dm = df[cols] - df.groupby("pid")[cols].transform("mean")
    X = dm[["log_edit", "is_bug"]].values
    beta = np.linalg.lstsq(X, dm["d"].values, rcond=None)[0]
    return float(beta[1] / df.loc[df.is_bug == 0, "d"].mean())


# ── Claims ────────────────────────────────────────────────────────────────────


def rq2_file(m):
    return f"results/rq2/{m}/rq2_embeddings.parquet"


def rq3_file(m):
    return f"results/rq3/{m}/rq3_embeddings.parquet"


def rq4_file(m):
    return f"results/RQ4/{m}/rq4_embeddings.parquet"


def mat_file(m):
    return f"results/clustering/{m}/cosine_distance_matrix.json"


def npz_files(models):
    return [f"results/rq5/embeddings/{m}.npz" for m in models] + \
           [f"results/rq5/embeddings/{m}_classeval.npz" for m in models]


EXEC = [f"results/rq5/execution/ollama_{t}/" for t in LLMS.values()]
LEX = "results/review/lexical_baselines.json"
RET_METRICS = "results/rq5/retrieval/metrics.json"
RANKINGS = "results/rq5/retrieval/rankings.json"
ESCI = "results/review/effect_size_cis.json"
RQ4CI = "results/review/confidence_intervals.json"


def add_ci(R: Registry, cid: str, where: str, what: str, paper: str, ci, src: list):
    """Two checks for an interval printed as [lo, hi]."""
    lo, hi = [x.strip() for x in paper.strip("[]").split(",")]
    R.add(f"{cid}a", where, f"{what}: 95% CI lower", lo, ci[0], src, method="read")
    R.add(f"{cid}b", where, f"{what}: 95% CI upper", hi, ci[1], src, method="read")


def r4_ci(m: str):
    """Bootstrap interval of R over bug types (code retrievers live in a separate file)."""
    if m in RETRIEVERS_NEW:
        return load_json(ESCI)["rq4_R_bootstrap_bug_types_code_retrievers"][m]["R_ci"], [ESCI]
    return load_json(RQ4CI)["rq4_R_bootstrap_bug_types"][m]["R_ci"], [RQ4CI]


def claims_design(R: Registry, d1, d2, d3, d4, ret, ctl):
    R.section("Study design")
    dims = [d2[m]["dim"] for m in ALL9]
    R.add("DES-01", "Sec. 3.1", "Smallest embedding dimensionality", "384", min(dims), [rq2_file(m) for m in ALL9], mode="exact")
    R.add("DES-02", "Sec. 3.1", "Largest embedding dimensionality", "1,536", max(dims), [rq2_file(m) for m in ALL9], mode="exact")
    lim = load_json("results/truncation_stats.json")["limits"]
    lim2 = load_json("results/review/truncation_new_embedders.json")["limits"]
    src = ["results/truncation_stats.json", "results/review/truncation_new_embedders.json"]
    R.add("DES-03", "Sec. 3.1", "MiniLM maximum sequence length", "256", lim["minilm"], src, mode="exact", method="read")
    R.add("DES-04", "Sec. 3.1", "CodeSage maximum sequence length", "1,024", lim2["codesage"], src, mode="exact", method="read")
    R.add("DES-05", "Sec. 3.1", "BGE-M3 maximum sequence length", "8,192", lim["bge_m3"], src, mode="exact", method="read")
    R.add("DES-06", "Sec. 3.1", "jina-code maximum sequence length", "8,192", lim2["jina_code"], src, mode="exact", method="read")
    R.add("DES-07", "Sec. 3.1", "CodeBERT and UniXCoder truncation (tokens)", "512",
          lim["codebert"] if lim["codebert"] == lim["unixcoder"] else -1, src, mode="exact", method="read")

    R.add("DES-08", "Sec. 3.2", "RQ1 corpus languages", "19", len(d1["languages"]), [mat_file("octen")], mode="exact")
    df2 = d2["octen"]["df"]
    R.add("DES-09", "Sec. 3.2", "RQ2 snippets", "1,024", len(df2), [rq2_file("octen")], mode="exact")
    fw_per_lang = df2.groupby("language")["framework"].nunique()
    shape = f"{df2.language.nunique()}x{int(fw_per_lang.min()) if fw_per_lang.min() == fw_per_lang.max() else -1}" \
            f"x{df2.pattern.nunique()}x{df2.variation.nunique()}"
    R.add("DES-10", "Sec. 3.2", "RQ2 factorial design languages x frameworks x patterns x variations",
          "8x4x8x4", shape, [rq2_file("octen")], mode="match")
    R.add("DES-11", "Sec. 3.2", "Language-specific frameworks", "32", (df2.language + "/" + df2.framework).nunique(),
          [rq2_file("octen")], mode="exact")
    df3 = d3["_df"]
    R.add("DES-12", "Sec. 3.2", "RQ3 solutions", "4,662", len(df3), [rq3_file("octen")], mode="exact")
    R.add("DES-13", "Sec. 3.2", "RQ3 problems", "150", df3.problem_slug.nunique(), [rq3_file("octen")], mode="exact")
    R.add("DES-14", "Sec. 3.2", "RQ3 languages", "9", df3.language.nunique(), [rq3_file("octen")], mode="exact")
    per_lang = df3.language.value_counts()
    R.add("DES-15", "Sec. 3.2", "Fewest RQ3 solutions per language", "516", per_lang.min(), [rq3_file("octen")], mode="exact")
    R.add("DES-16", "Sec. 3.2", "Most RQ3 solutions per language", "519", per_lang.max(), [rq3_file("octen")], mode="exact")
    diff = df3.drop_duplicates("problem_slug").difficulty.value_counts()
    for i, (lvl, n) in enumerate([("easy", "28"), ("medium", "101"), ("hard", "21")]):
        R.add(f"DES-{17 + i:02d}", "Sec. 3.2", f"NeetCode problems labelled {lvl.capitalize()}", n, diff.get(lvl, 0),
              [rq3_file("octen")], mode="exact")
    lab = load_json("results/review/rq3_label_metrics.json")
    src = ["results/review/rq3_label_metrics.json"]
    R.add("DES-20", "Sec. 3.2", "Label checks sampled", "200", lab["n"], src, mode="exact", method="read")
    R.add("DES-21", "Sec. 3.2", "gpt-oss:20b agrees with the complexity label (%)", "78.5",
          100 * lab["gpt-oss:20b"]["agreement_with_label"], src, method="read")
    R.add("DES-22", "Sec. 3.2", "gemma4:31b agrees with the complexity label (%)", "81.0",
          100 * lab["gemma4:31b"]["agreement_with_label"], src, method="read")
    R.add("DES-23", "Sec. 3.2", "Cohen's kappa, gpt-oss:20b vs label", "0.76", lab["gpt-oss:20b"]["kappa_with_label"], src, method="read")
    R.add("DES-24", "Sec. 3.2", "Cohen's kappa, gemma4:31b vs label", "0.78", lab["gemma4:31b"]["kappa_with_label"], src, method="read")
    worst = set()
    for llm in ("gpt-oss:20b", "gemma4:31b"):
        pc = lab[llm]["per_class_agreement"]
        worst |= set(sorted(pc, key=pc.get)[:2])
    worst = sorted("O(1)" if w == "O(1)" else "O(n^2)" if SQ in w else w for w in worst)
    R.add("DES-25", "Sec. 3.2", "Classes with the lowest label agreement (both LLMs)", "O(1), O(n^2)", ", ".join(worst),
          src, mode="match", method="read")
    counts = df3.complexity_class.value_counts()
    n_clean = int((df3.complexity_class != "Other").sum())
    R.add("DES-26", "Sec. 3.2", "RQ3 solutions that fit no complexity class", "434", counts.get("Other", 0), [rq3_file("octen")], mode="exact")
    R.add("DES-27", "Sec. 3.2", "RQ3 solutions with a complexity class", "4,228", n_clean, [rq3_file("octen")], mode="exact")
    R.add("DES-28", "Sec. 3.2", "Share of classified solutions in O(n) (%)", "40.0", 100 * counts["O(n)"] / n_clean, [rq3_file("octen")])
    R.add("DES-29", "Sec. 3.2", "Share of classified solutions in O(n^2) (%)", "24.2", 100 * counts[SQ] / n_clean, [rq3_file("octen")])

    df4 = d4["octen"]["df"]
    per_type = df4.drop_duplicates("bug_index").severity.value_counts()
    R.add("DES-30", "Sec. 3.2", "RQ4 bug types", "100", df4.bug_index.nunique(), [rq4_file("octen")], mode="exact")
    R.add("DES-31", "Sec. 3.2", "RQ4 languages", "5", df4.language.nunique(), [rq4_file("octen")], mode="exact")
    for i, (sev, n, name) in enumerate([("Easy", "22", "syntax and compile-time"), ("Medium", "28", "logic and control-flow"),
                                        ("Hard", "31", "state and algorithmic"), ("Super Hard", "19", "numeric, memory, concurrency")]):
        R.add(f"DES-{32 + i:02d}", "Sec. 3.2", f"Bug types in category {name}", n, per_type.get(sev, 0), [rq4_file("octen")], mode="exact")
    R.add("DES-36", "Sec. 3.2", "RQ4 bug-fix pairs", "500", len(df4) // 2, [rq4_file("octen")], mode="exact")

    hef_docs = sum(len(ret["records"]["octen"][l]) for l in HEF_LANGS)
    R.add("DES-37", "Sec. 3.2", "HumanEvalFix bug-fix pairs (six languages)", "984", hef_docs, npz_files(["octen"]), mode="exact")
    R.add("DES-38", "Sec. 3.2", "Retrieval corpus documents per HumanEvalFix language", "328",
          len(ret["order"]["octen"][("python", "d")]), npz_files(["octen"]), mode="exact")
    ce = pd.read_parquet(path("datasets/RQ5/classeval/classeval_pairs.parquet"))
    R.add("DES-39", "Sec. 3.2", "ClassEval correct/mutant pairs kept", "81", len(ce), ["datasets/RQ5/classeval/classeval_pairs.parquet"], mode="exact")
    for i, lang in enumerate(["python", "js", "java", "go", "cpp", "rust"]):
        hp = pd.read_parquet(path(f"datasets/RQ5/humanevalpack/{lang}.parquet"))
        n_ex = int((hp["example_test"].fillna("").astype(str).str.strip() != "").sum())
        R.add(f"DES-{40 + i:02d}", "Sec. 3.2", f"HumanEvalFix problems with example tests ({lang})",
              "164" if lang == "rust" else "158", n_ex, [f"datasets/RQ5/humanevalpack/{lang}.parquet"], mode="exact")
    R.add("DES-46", "Sec. 3.4", "ClassEval tasks whose skeleton carries doctest examples", "27",
          int(ce["has_example_test"].sum()), ["datasets/RQ5/classeval/classeval_pairs.parquet"], mode="exact")
    R.add("DES-47", "Sec. 3.4", "ClassEval tasks with example tests used by the filter", "27",
          load_json(RET_METRICS)["filtering"]["classeval"]["n_problems_with_example_tests"], [RET_METRICS], mode="exact", method="read")

    gens = {}
    for r in load_jsonl_dir("results/rq5/generations/ollama_deepseek-v4.1-flash"):
        if "error" not in r:
            gens[(r["file"], r["problem"], r["context"])] = r
    R.add("DES-48", "Sec. 3.4", "deepseek-v4.1-flash generations", "3,286", len(gens),
          ["results/rq5/generations/ollama_deepseek-v4.1-flash/"], mode="exact")
    R.add("DES-49", "Sec. 3.4", "deepseek-v4.1-flash generations that hit the output cap", "34",
          sum(r.get("finish") == "length" for r in gens.values()), ["results/rq5/generations/ollama_deepseek-v4.1-flash/"], mode="exact")
    R.add("DES-50", "Sec. 3.4", "Test-validated behaviour-preserving rewrites", "3,648", ctl["n_rewrites"],
          ["results/review/variants.parquet"], mode="exact")
    R.add("DES-51", "Sec. 3.4", "Programs covered by at least one rewrite", "569", ctl["n_programs_covered"],
          ["results/review/variants.parquet"], mode="exact")
    R.add("DES-52", "Sec. 3.4", "Programs in the control (HumanEvalFix Py/JS/Java + ClassEval)", "573",
          3 * 164 + len(ce), ["datasets/RQ5/classeval/classeval_pairs.parquet"], mode="exact")


def load_jsonl_dir(rel: str) -> list[dict]:
    out = []
    for name in sorted(os.listdir(path(rel))):
        if name.endswith(".jsonl"):
            for r in load_jsonl(f"{rel}/{name}"):
                r["file"] = name
                out.append(r)
    return out


def claims_rq1(R: Registry, d1):
    R.section("RQ1")
    langs, M = d1["languages"], d1["models"]
    src_core = [mat_file(m) for m in CORE]
    ccc = {m: M[m]["ccc"] for m in CORE}
    R.add("RQ1-01", "Sec. 4.1", "Lowest CCC among the core models (CodeBERT)", "0.584", min(ccc.values()), src_core)
    R.add("RQ1-02", "Sec. 4.1", "Model with the lowest CCC", "codebert", min(ccc, key=ccc.get), src_core, mode="match")
    R.add("RQ1-03", "Sec. 4.1", "Highest CCC among the core models (BGE-M3)", "0.799", max(ccc.values()), src_core)
    R.add("RQ1-04", "Sec. 4.1", "Model with the highest CCC", "bge_m3", max(ccc, key=ccc.get), src_core, mode="match")
    R.add("RQ1-05", "Fig. 2a", "UniXCoder CCC", "0.661", ccc["unixcoder"], [mat_file("unixcoder")])
    parts5 = {m: M[m]["parts"][5] for m in ALL9}
    stable7 = co_clustered(langs, [parts5[m] for m in CORE])
    R.add("RQ1-06", "Sec. 4.1", "Pairs sharing a family under all seven core models (k=5)",
          "C-C++, Kotlin-Scala, Python-Ruby", pairs_text(stable7), src_core, mode="match")
    ov = overlap_scores(langs, parts5)
    R.add("RQ1-07", "Sec. 4.1", "Highest mean family overlap of any language", "0.50", max(ov.values()), src_core, mode="lt")
    for i, (lang, val) in enumerate([("Rust", "0.281"), ("Swift", "0.337"), ("Java", "0.358")]):
        R.add(f"RQ1-{8 + i:02d}", "Sec. 4.1", f"Family overlap of {lang}", val, ov[lang], src_core)
    R.add("RQ1-11", "Sec. 4.1", "Three least stable languages", "Rust, Swift, Java",
          ", ".join(sorted(ov, key=ov.get)[:3]), src_core, mode="match")
    fam = lambda m: family_of(langs, parts5[m], "Rust")
    R.add("RQ1-12", "Sec. 4.1", "Rust shares a family with C and C++ under UniXCoder and CodeBERT", "True",
          all({"C", "C++"} <= fam(m) for m in ("unixcoder", "codebert")), [mat_file("unixcoder"), mat_file("codebert")], mode="match")
    R.add("RQ1-13", "Sec. 4.1", "Rust shares a family with Go under Qwen3 and Octen", "True",
          all("Go" in fam(m) for m in ("qwen3", "octen")), [mat_file("qwen3"), mat_file("octen")], mode="match")
    R.add("RQ1-14", "Sec. 4.1", "Rust shares a family with Kotlin and Swift under Ada-002, BGE-M3, MiniLM", "True",
          all({"Kotlin", "Swift"} <= fam(m) for m in ("ada002", "bge_m3", "minilm")),
          [mat_file(m) for m in ("ada002", "bge_m3", "minilm")], mode="match")
    R.add("RQ1-15", "Fig. 2a", "Under UniXCoder Rust joins the C, C++ and Fortran cluster", "True",
          {"C", "C++", "Fortran"} <= fam("unixcoder"), [mat_file("unixcoder")], mode="match")
    R.add("RQ1-16", "Sec. 4.1", "CodeBERT's largest pairwise language distance", "0.056", M["codebert"]["max_dist"], [mat_file("codebert")])
    R.add("RQ1-17", "Sec. 4.1", "UniXCoder's largest pairwise language distance", "0.474", M["unixcoder"]["max_dist"], [mat_file("unixcoder")])
    mx = {m: M[m]["max_dist"] for m in CORE}
    R.add("RQ1-18", "Sec. 4.1", "Largest pairwise language distance of any core model (MiniLM)", "0.844", max(mx.values()), src_core)
    R.add("RQ1-19", "Sec. 4.1", "Model with the largest language distance", "minilm", max(mx, key=mx.get), src_core, mode="match")
    R.add("RQ1-20", "Fig. 2", "ARI between the UniXCoder and CodeBERT partitions (k=5)", "0.42",
          ari(parts5["unixcoder"], parts5["codebert"]), [mat_file("unixcoder"), mat_file("codebert")])

    rho = {(a, b): float(spearmanr(M[a]["condensed"], M[b]["condensed"]).statistic) for a, b in combinations(CORE, 2)}
    R.add("RQ1-21", "Sec. 4.1", "Lowest Spearman rho between core distance matrices", "0.19", min(rho.values()), src_core)
    R.add("RQ1-22", "Sec. 4.1", "Pair with the lowest rho", "codebert-minilm", pair_name(*min(rho, key=rho.get)), src_core, mode="match")
    R.add("RQ1-23", "Sec. 4.1", "Highest Spearman rho (fine-tune and base model)", "0.98", max(rho.values()), src_core)
    R.add("RQ1-24", "Sec. 4.1", "Pair with the highest rho", "octen-qwen3", pair_name(*max(rho, key=rho.get)), src_core, mode="match")
    R.add("RQ1-25", "Sec. 4.1", "Mean Spearman rho over the 21 core model pairs", "0.47", np.mean(list(rho.values())), src_core)
    aris = {k: [ari(M[a]["parts"][k], M[b]["parts"][k]) for a, b in combinations(CORE, 2)] for k in range(3, 9)}
    R.add("RQ1-26", "Sec. 4.1", "Mean ARI between core partitions (k=5)", "0.26", np.mean(aris[5]), src_core)
    R.add("RQ1-27", "Sec. 4.1", "Lowest pairwise ARI (k=5)", "0.03", min(aris[5]), src_core)
    R.add("RQ1-28", "Sec. 4.1", "Highest pairwise ARI (k=5)", "0.84", max(aris[5]), src_core)
    yun = lambda k: np.mean([ari(M[a]["parts"][k], M[b]["parts"][k]) for a, b in combinations(YUN, 2)])
    R.add("RQ1-29", "Sec. 4.1", "Mean ARI among Yun et al.'s three models (k=5)", "0.18", yun(5), [mat_file(m) for m in YUN])
    R.add("RQ1-30", "Sec. 4.1", "Mean ARI among Yun et al.'s three models (k=6)", "0.25", yun(6), [mat_file(m) for m in YUN])
    means = [np.mean(aris[k]) for k in range(3, 9)]
    R.add("RQ1-31", "Sec. 4.1", "Lowest mean ARI over cuts k=3..8", "0.12", min(means), src_core)
    R.add("RQ1-32", "Sec. 4.1", "Highest mean ARI over cuts k=3..8", "0.27", max(means), src_core)
    every = co_clustered(langs, [M[m]["parts"][k] for m in CORE for k in range(4, 9)])
    R.add("RQ1-33", "Sec. 4.1", "Pairs co-clustered by all core models at every cut k=4..8", "C-C++, Kotlin-Scala",
          pairs_text(every), src_core, mode="match")
    R.add("RQ1-34", "Sec. 4.1", "Observed mean family overlap over languages", "0.41", np.mean(list(ov.values())), src_core)
    rob = load_json("results/review/rq1_robustness.json")["4_overlap_null"]
    src = ["results/review/rq1_robustness.json"]
    R.add("RQ1-35", "Sec. 4.1", "Chance (permuted) mean overlap score", "0.24", rob["null_mean"]["mean"], src, method="read")
    R.add("RQ1-36", "Sec. 4.1", "Chance overlap 95% interval, lower", "0.23", rob["null_mean"]["lo95"], src, method="read")
    R.add("RQ1-37", "Sec. 4.1", "Chance overlap 95% interval, upper", "0.26", rob["null_mean"]["hi95"], src, method="read")
    R.add("RQ1-38", "Sec. 4.1", "Chance number of stable pairs", "0.002", rob["null_n_stable_pairs"]["mean"], src, method="read")
    R.add("RQ1-39", "Sec. 4.1", "Observed number of stable pairs", "3", len(stable7), src_core, mode="exact")
    boot = load_json("results/review/rq1_bootstrap.json")["bootstrap"]["feature"]["summary"]
    src = ["results/review/rq1_bootstrap.json"]
    R.add("RQ1-40", "Sec. 4.1", "Feature bootstrap: mean ARI of a model with its full-data partition", "0.86",
          boot["mean_within_ari_vs_full"]["mean"], src, method="read")
    R.add("RQ1-41", "Sec. 4.1", "Feature bootstrap ARI 95% interval, lower", "0.72", boot["mean_within_ari_vs_full"]["lo95"], src, method="read")
    R.add("RQ1-42", "Sec. 4.1", "Feature bootstrap ARI 95% interval, upper", "0.98", boot["mean_within_ari_vs_full"]["hi95"], src, method="read")
    six = [m for m in CORE if m != "ada002"]
    R.add("RQ1-43", "Sec. 4.1", "Mean ARI between the six locally run core models (full data)", "0.30",
          np.mean([ari(parts5[a], parts5[b]) for a, b in combinations(six, 2)]), [mat_file(m) for m in six])
    R.add("RQ1-44", "Sec. 4.1", "Stable pairs co-cluster under all six in at least this share of resamples (%)", "77",
          100 * min(boot["joint_all6_prob_stable_pairs"].values()), src, mode="ge", method="read")
    for i, (target, anchor, val) in enumerate([("Kotlin", "Java", "4"), ("Haskell", "Python", "4"),
                                                ("Swift", "Java or Python", "4"), ("AppleScript", "Python", "3"),
                                                ("AppleScript", "Java", "0")]):
        anchors = anchor.split(" or ")
        n = sum(any(a in family_of(langs, parts5[m], target) for a in anchors) for m in CORE)
        R.add(f"RQ1-{45 + i:02d}", "Sec. 4.1", f"Core models where {target} shares a family with {anchor} (k=5)", val, n,
              src_core, mode="exact")
    stable9 = co_clustered(langs, [parts5[m] for m in ALL9])
    R.add("RQ1-50", "Sec. 4.1", "Pairs sharing a family under all nine models (k=5)", "C-C++, Kotlin-Scala, Python-Ruby",
          pairs_text(stable9), [mat_file(m) for m in ALL9], mode="match")
    for i, (m, val) in enumerate([("jina_code", "0.36"), ("codesage", "0.18")]):
        R.add(f"RQ1-{51 + i:02d}", "Sec. 4.1", f"Mean Spearman rho of {m} with the core models", val,
              np.mean([spearmanr(M[m]["condensed"], M[c]["condensed"]).statistic for c in CORE]),
              [mat_file(m)] + src_core)


def claims_rq2(R: Registry, d2):
    R.section("RQ2")
    lex = load_json(LEX)["rq2"]
    table = {  # model: framework sil, language sil, cross, intra, d, 95% CI of d
        "bge_m3": ("0.146", "0.124", "0.248", "0.122", "2.378", "[2.31, 2.45]"),
        "minilm": ("0.176", "0.181", "0.483", "0.149", "2.302", "[2.24, 2.37]"),
        "ada002": ("0.151", "0.304", "0.112", "0.046", "2.078", "[2.03, 2.13]"),
        "qwen3": ("0.066", "0.175", "0.257", "0.138", "1.747", "[1.69, 1.81]"),
        "octen": ("0.052", "0.136", "0.266", "0.152", "1.711", "[1.65, 1.77]"),
        "unixcoder": ("0.044", "0.174", "0.225", "0.095", "1.251", "[1.20, 1.30]"),
        "codebert": ("0.039", "0.247", "0.012", "0.004", "0.970", "[0.93, 1.01]"),
        "jina_code": ("0.028", "0.015", "0.233", "0.101", "1.884", "[1.83, 1.94]"),
        "codesage": ("0.128", "0.052", "0.568", "0.298", "2.105", "[2.03, 2.18]"),
        "tfidf_alnum": ("0.111", "0.151", "0.547", "0.269", "1.986", "[1.92, 2.06]"),
        "edit_alnum": ("0.031", "0.029", "0.898", "0.706", "2.963", "[2.87, 3.07]"),
    }
    names = ["framework silhouette", "language silhouette", "cross-framework distance", "intra-framework distance", "Cohen's d"]
    for m, row in table.items():
        if m in d2:
            v, src, meth = d2[m], [rq2_file(m)], "recomputed"
            vals = [v["fw_sil"], v["lang_sil"], v["cross"], v["intra"], v["d"]]
        else:
            v, src, meth = lex[m], [LEX], "read"
            vals = [v["framework_silhouette"], v["language_silhouette"], v["cross_mean"], v["intra_mean"], v["cohens_d"]]
        for j, (paper, val) in enumerate(zip(row[:5], vals)):
            R.add(f"T2.{m}.{j + 1}", "Table 2", f"{m}: {names[j]}", paper, val, src, method=meth)
        add_ci(R, f"T2.{m}.6", "Table 2", f"{m}: Cohen's d", row[5], load_json(ESCI)["rq2_cohens_d"][m]["d_ci"], [ESCI])
    R.add("T2.all_large", "Table 2", "Every effect in Table 2 is large (d > 0.8)", "True",
          all(d2[m]["d"] > 0.8 for m in ALL9) and all(lex[b]["cohens_d"] > 0.8 for b in ("tfidf_alnum", "edit_alnum")),
          [rq2_file(m) for m in ALL9] + [LEX], mode="match")
    R.add("RQ2-01", "Sec. 4.2", "Cross/intra pairs per model (same pattern and language)", "6144/1536",
          f"{d2['octen']['n_cross']}/{d2['octen']['n_intra']}", [rq2_file("octen")], mode="match")
    dcore = {m: d2[m]["d"] for m in CORE}
    R.add("RQ2-02", "Sec. 4.2", "Smallest core-model d exceeds the large-effect threshold", "0.8", min(dcore.values()),
          [rq2_file(m) for m in CORE], mode="gt")
    R.add("RQ2-03", "Sec. 4.2", "Core model with the largest d", "bge_m3", max(dcore, key=dcore.get), [rq2_file(m) for m in CORE], mode="match")
    R.add("RQ2-04", "Sec. 4.2", "Core model with the smallest d", "codebert", min(dcore, key=dcore.get), [rq2_file(m) for m in CORE], mode="match")
    lang_gt = [m for m in CORE if d2[m]["lang_sil"] > d2[m]["fw_sil"]]
    R.add("RQ2-05", "Sec. 4.2", "Core models whose language silhouette exceeds the framework silhouette", "6", len(lang_gt),
          [rq2_file(m) for m in CORE], mode="exact")
    R.add("RQ2-06", "Sec. 4.2", "The exception (frameworks separate more cleanly)", "bge_m3",
          ",".join(m for m in CORE if m not in lang_gt), [rq2_file(m) for m in CORE], mode="match")
    R.add("RQ2-07", "Sec. 4.2", "Token edit d exceeds every embedder's d", "True",
          lex["edit_alnum"]["cohens_d"] > max(d2[m]["d"] for m in ALL9), [LEX] + [rq2_file(m) for m in ALL9], mode="match")
    R.add("RQ2-08", "Sec. 4.2", "Both code retrievers separate frameworks more cleanly than languages", "True",
          all(d2[m]["fw_sil"] > d2[m]["lang_sil"] for m in RETRIEVERS_NEW), [rq2_file(m) for m in RETRIEVERS_NEW], mode="match")
    tr = load_json("results/review/truncation_new_embedders.json")["share_truncated"]
    R.add("RQ2-09", "Sec. 4.2", "Share of RQ2 snippets CodeSage truncates (%)", "99", tr["codesage"]["RQ2"],
          ["results/review/truncation_new_embedders.json"], method="read")
    avg = {l: np.mean([d2[m]["per_lang"][l] for m in CORE]) for l in d2["octen"]["per_lang"]}
    R.add("RQ2-10", "Sec. 4.2", "Framework silhouette averaged over core models, Rust", "0.138", avg["Rust"], [rq2_file(m) for m in CORE])
    R.add("RQ2-11", "Sec. 4.2", "Language with the highest averaged framework silhouette", "Rust", max(avg, key=avg.get),
          [rq2_file(m) for m in CORE], mode="match")
    R.add("RQ2-12", "Sec. 4.2", "Framework silhouette averaged over core models, Go", "0.087", avg["Go"], [rq2_file(m) for m in CORE])
    R.add("RQ2-13", "Sec. 4.2", "Language with the lowest averaged framework silhouette", "Go", min(avg, key=avg.get),
          [rq2_file(m) for m in CORE], mode="match")
    bge = d2["bge_m3"]["per_lang"]
    R.add("RQ2-14", "Fig. 3", "Under BGE-M3, clearest / weakest framework sub-structure", "Ruby/PHP",
          f"{max(bge, key=bge.get)}/{min(bge, key=bge.get)}", [rq2_file("bge_m3")], mode="match")
    fw = {m: d2[m]["fw_sil"] for m in CORE}
    R.add("RQ2-15", "Sec. 4.2", "Two lowest framework silhouettes among core models", "codebert, unixcoder",
          ", ".join(sorted(sorted(fw, key=fw.get)[:2])), [rq2_file(m) for m in CORE], mode="match")
    R.add("RQ2-16", "Sec. 4.2", "Two lowest effect sizes among core models", "codebert, unixcoder",
          ", ".join(sorted(sorted(dcore, key=dcore.get)[:2])), [rq2_file(m) for m in CORE], mode="match")
    R.add("RQ2-17", "Sec. 4.2", "Mean distance between all RQ2 embeddings, CodeBERT", "0.023", d2["codebert"]["mean_all_pairs"], [rq2_file("codebert")])
    R.add("RQ2-18", "Sec. 4.2", "Mean distance between all RQ2 embeddings, UniXCoder", "0.343", d2["unixcoder"]["mean_all_pairs"], [rq2_file("unixcoder")])
    ts = load_json("results/truncation_stats.json")["share_truncated"]
    R.add("RQ2-19", "Sec. 4.2", "Share of RQ2 snippets longer than 512 tokens (CodeBERT, UniXCoder) (%)", "100",
          min(ts["codebert"]["RQ2"], ts["unixcoder"]["RQ2"]), ["results/truncation_stats.json"], mode="exact", method="read")
    R.add("RQ2-20", "Sec. 4.2", "Share of RQ2 snippets longer than 256 tokens (MiniLM) (%)", "100", ts["minilm"]["RQ2"],
          ["results/truncation_stats.json"], mode="exact", method="read")
    tsens = load_json("results/truncation_sensitivity.json")
    src = ["results/truncation_sensitivity.json"]
    R.add("RQ2-21", "Sec. 4.2", "UniXCoder d with window-averaged full text", "1.305", tsens["unixcoder"]["rq2"]["full_text"]["cohens_d"], src, method="read")
    R.add("RQ2-22", "Sec. 4.2", "UniXCoder d with truncation", "1.251", tsens["unixcoder"]["rq2"]["truncated"]["cohens_d"], src, method="read")
    R.add("RQ2-23", "Sec. 4.2", "UniXCoder framework silhouette with full text", "0.016",
          tsens["unixcoder"]["rq2"]["full_text"]["framework_silhouette"], src, method="read")
    R.add("RQ2-24", "Sec. 4.2", "CodeBERT d with full text", "0.753", tsens["codebert"]["rq2"]["full_text"]["cohens_d"], src, method="read")
    R.add("RQ2-25", "Sec. 4.2", "CodeBERT full-text effect label", "Medium", effect_label(tsens["codebert"]["rq2"]["full_text"]["cohens_d"]),
          src, mode="match", method="read")
    R.add("RQ2-26", "Sec. 4.2", "MiniLM d with full text", "1.827", tsens["minilm"]["rq2"]["full_text"]["cohens_d"], src, method="read")
    R.add("RQ2-27", "Sec. 4.2", "Full text: language silhouette stays above framework silhouette in all three", "True",
          all(tsens[m]["rq2"]["full_text"]["language_silhouette"] > tsens[m]["rq2"]["full_text"]["framework_silhouette"]
              for m in ("codebert", "unixcoder", "minilm")), src, mode="match", method="read")


def claims_rq3(R: Registry, d3):
    R.section("RQ3")
    lex = load_json(LEX)["rq3"]
    table = {  # model: silhouette, cross, intra, d, 95% CI of d, effect
        "octen": ("-0.019", "0.708", "0.621", "0.638", "[0.61, 0.66]", "Medium"),
        "qwen3": ("-0.023", "0.598", "0.522", "0.593", "[0.57, 0.62]", "Medium"),
        "minilm": ("-0.014", "0.607", "0.538", "0.540", "[0.51, 0.56]", "Medium"),
        "ada002": ("-0.036", "0.217", "0.194", "0.478", "[0.45, 0.50]", "Small"),
        "bge_m3": ("-0.014", "0.386", "0.352", "0.429", "[0.40, 0.45]", "Small"),
        "unixcoder": ("-0.037", "0.496", "0.452", "0.300", "[0.28, 0.32]", "Small"),
        "codebert": ("-0.190", "0.021", "0.020", "0.047", "[0.03, 0.07]", "Negligible"),
        "codesage": ("0.007", "0.935", "0.859", "0.624", "[0.60, 0.65]", "Medium"),
        "jina_code": ("-0.050", "0.630", "0.557", "0.510", "[0.48, 0.54]", "Medium"),
        "edit_alnum": ("0.005", "0.872", "0.827", "0.567", "[0.54, 0.59]", "Medium"),
        "tfidf_alnum": ("-0.000", "0.867", "0.798", "0.520", "[0.49, 0.55]", "Medium"),
    }
    names = ["complexity silhouette", "cross-class distance", "intra-class distance", "Cohen's d"]
    for m, row in table.items():
        if m in d3:
            v, src, meth = d3[m], [rq3_file(m)], "recomputed"
            vals = [v["sil"], v["cross"], v["intra"], v["d"]]
        else:
            v, src, meth = lex[m], [LEX], "read"
            vals = [v["complexity_silhouette"], v["cross_mean"], v["intra_mean"], v["cohens_d"]]
        for j, (paper, val) in enumerate(zip(row[:4], vals)):
            R.add(f"T3.{m}.{j + 1}", "Table 3", f"{m}: {names[j]}", paper, val, src, method=meth)
        add_ci(R, f"T3.{m}.5", "Table 3", f"{m}: Cohen's d", row[4], load_json(ESCI)["rq3_cohens_d"][m]["d_ci"], [ESCI])
        R.add(f"T3.{m}.6", "Table 3", f"{m}: effect size label", row[5], effect_label(vals[3]), src, mode="match", method=meth)
    src_core = [rq3_file(m) for m in CORE]
    R.add("RQ3-01", "Sec. 4.3", "Largest Welch p over the core models", "1e-5", max(d3[m]["p"] for m in CORE), src_core, mode="lt")
    perm = load_json("results/review/permutation_tests.json")["rq3_cross_vs_intra_complexity"]
    R.add("RQ3-02", "Sec. 4.3", "Largest snippet-level permutation p over the core models", "0.05",
          max(perm[m]["perm_p_two_sided"] for m in CORE), ["results/review/permutation_tests.json"], mode="lt", method="read")
    R.add("RQ3-03", "Sec. 4.3", "Largest complexity silhouette among core models", "0", max(d3[m]["sil"] for m in CORE), src_core, mode="lt")
    dcore = {m: d3[m]["d"] for m in CORE}
    R.add("RQ3-04", "Sec. 4.3", "Smallest core-model d (CodeBERT)", "0.047", min(dcore.values()), src_core)
    R.add("RQ3-05", "Sec. 4.3", "Largest core-model d (Octen)", "0.638", max(dcore.values()), src_core)
    R.add("RQ3-06", "Sec. 4.3", "CodeBERT cross minus intra distance", "0.001", d3["codebert"]["cross"] - d3["codebert"]["intra"], [rq3_file("codebert")])
    others = [d3[m]["sil"] for m in ALL9 if m != "codebert"]
    R.add("RQ3-07", "Sec. 4.3", "CodeBERT silhouette over the next most negative model ('about four times')", "4",
          d3["codebert"]["sil"] / min(others), [rq3_file(m) for m in ALL9], mode="approx", tol=0.5)
    gm = {m: load_json(f"results/rq3/{m}/rq3_metrics.json")["global_complexity_distance_matrix"] for m in CORE}
    closest = []
    for m in CORE:
        M = gm[m]
        a, b, _ = min(((a, b, M[a][b]) for a in M for b in M[a] if a < b), key=lambda x: x[2])
        closest.append({a, b} == {"O(2^n)", CU})
    R.add("RQ3-08", "Sec. 4.3", "Core models whose closest class pair is O(2^n) with O(n^3)", "5", sum(closest),
          [f"results/rq3/{m}/rq3_metrics.json" for m in CORE], mode="exact", method="read")
    pid = {m: d3[m]["pid_d"] for m in ALL9}
    R.add("RQ3-09", "Sec. 4.3", "Every model places same-problem pairs closer than same-class pairs", "True",
          all(d3[m]["pid_same"] < d3[m]["pid_diff"] for m in ALL9), [rq3_file(m) for m in ALL9], mode="match")
    big = [m for m in CORE if pid[m] >= 1.765]
    R.add("RQ3-10", "Sec. 4.3", "Core models with problem-identity d >= 1.77", "6", len(big), src_core, mode="exact")
    R.add("RQ3-11", "Sec. 4.3", "Smallest problem-identity d among those six", "1.77", min(pid[m] for m in big), src_core)
    pl_ok = True
    for m in CORE:
        for v in load_json(f"results/rq3/{m}/rq3_metrics.json")["problem_identity"]["per_language"].values():
            pl_ok &= v["same_problem_diff_complexity_mean"] < v["diff_problem_same_complexity_mean"]
    R.add("RQ3-12", "Sec. 4.3", "Same-problem pairs closer in every core model and all nine languages", "True", pl_ok,
          [f"results/rq3/{m}/rq3_metrics.json" for m in CORE], mode="match", method="read")
    R.add("RQ3-13", "Sec. 4.3", "Problem-identity d, CodeBERT", "0.43", pid["codebert"], [rq3_file("codebert")])
    R.add("RQ3-14", "Sec. 4.3", "Problem-identity d, Octen", "4.29", pid["octen"], [rq3_file("octen")])
    R.add("RQ3-15", "Sec. 4.3", "Same-problem, different-class pairs", "2,518", d3["octen"]["n_same"], [rq3_file("octen")], mode="exact")
    R.add("RQ3-16", "Sec. 4.3", "Different-problem, same-class pairs", "10,935", d3["octen"]["n_diff"], [rq3_file("octen")], mode="exact")
    R.add("RQ3-17", "Sec. 4.3", "Problem-identity d, jina-code", "2.88", pid["jina_code"], [rq3_file("jina_code")])
    R.add("RQ3-18", "Sec. 4.3", "Problem-identity d, CodeSage", "5.68", pid["codesage"], [rq3_file("codesage")])
    six = [m for m in CORE if m != "codebert"]
    R.add("RQ3-19", "Answer RQ3", "Smallest d of the six non-CodeBERT core models", "0.30", min(d3[m]["d"] for m in six), src_core)
    R.add("RQ3-20", "Answer RQ3", "Largest d of the six non-CodeBERT core models", "0.64", max(d3[m]["d"] for m in six), src_core)
    R.add("RQ3-21", "Answer RQ3", "CodeBERT d", "0.05", d3["codebert"]["d"], [rq3_file("codebert")])
    sils = [d3[m]["sil"] for m in ALL9] + [lex[b]["complexity_silhouette"] for b in ("tfidf_alnum", "edit_alnum")]
    R.add("RQ3-22", "Answer RQ3", "Largest complexity silhouette of any model or baseline", "0.01", max(sils),
          [rq3_file(m) for m in ALL9] + [LEX], mode="lt")


def claims_rq4(R: Registry, d4, ctl):
    R.section("RQ4")
    lex = load_json(LEX)["rq4"]
    table = {  # pair, unmatched, R, 1/R, DN 0.05, 0.10, 0.15, d
        "unixcoder": ("0.223", "0.792", "0.281", "3.6", "17.6", "29.6", "38.2", "0.105"),
        "bge_m3": ("0.123", "0.463", "0.265", "3.8", "24.8", "45.8", "62.6", "0.136"),
        "codebert": ("0.011", "0.046", "0.245", "4.1", "98.0", "100", "100", "-0.004"),
        "minilm": ("0.140", "0.749", "0.187", "5.3", "24.2", "41.8", "60.8", "0.083"),
        "qwen3": ("0.114", "0.653", "0.175", "5.7", "32.8", "54.0", "69.2", "0.124"),
        "octen": ("0.121", "0.696", "0.174", "5.7", "30.8", "51.8", "68.0", "0.149"),
        "ada002": ("0.039", "0.269", "0.144", "6.9", "72.6", "96.4", "99.2", "0.126"),
        "codesage": ("0.207", "0.940", "0.221", "4.5", "21.4", "39.4", "53.0", "0.057"),
        "jina_code": ("0.120", "0.791", "0.152", "6.6", "27.0", "50.4", "69.4", "0.048"),
        "edit_alnum": ("0.405", "0.941", "0.431", "2.3", "5.0", "8.0", "13.0", "0.021"),
        "tfidf_alnum": ("0.231", "0.937", "0.246", "4.1", "21.2", "31.2", "43.4", "0.044"),
    }
    R_CI = {'unixcoder': '[0.247, 0.317]', 'bge_m3': '[0.234, 0.296]', 'codebert': '[0.208, 0.282]', 'minilm': '[0.163, 0.212]', 'qwen3': '[0.149, 0.201]', 'octen': '[0.148, 0.201]', 'ada002': '[0.125, 0.165]', 'codesage': '[0.186, 0.256]', 'jina_code': '[0.131, 0.175]', 'edit_alnum': '[0.395, 0.467]', 'tfidf_alnum': '[0.212, 0.281]'}
    names = ["pair distance", "unmatched distance", "R", "1/R", "DN% tau=0.05", "DN% tau=0.10", "DN% tau=0.15", "Cohen's d"]
    for m, row in table.items():
        if m in d4:
            v, src, meth = d4[m], [rq4_file(m)], "recomputed"
            vals = [v["pair"], v["unmatched"], v["R"], v["inv_R"], v["dn"][0.05], v["dn"][0.10], v["dn"][0.15], v["d"]]
        else:
            v, src, meth = lex[m], [LEX], "read"
            dn = v["dangerous_pct"]
            vals = [v["pair_mean"], v["unmatched_mean"], v["R"], v["inverse_R"], dn["0.05"], dn["0.1"], dn["0.15"],
                    v["cluster_cohens_d"]]
        for j, (paper, val) in enumerate(zip(row, vals)):
            R.add(f"T4.{m}.{j + 1}", "Table 4", f"{m}: {names[j]}", paper, val, src, method=meth)
        ci, ci_src = r4_ci(m)
        add_ci(R, f"T4.{m}.9", "Table 4", f"{m}: R over bug types", R_CI[m], ci, ci_src)
    src9 = [rq4_file(m) for m in ALL9]
    src_core = [rq4_file(m) for m in CORE]
    sil = [d4[m]["sil"] for m in ALL9]
    R.add("RQ4-01", "Sec. 4.4", "Lowest correctness silhouette", "0.003", min(sil), src9)
    R.add("RQ4-02", "Sec. 4.4", "Highest correctness silhouette", "0.019", max(sil), src9)
    sig = [m for m in CORE if d4[m]["p"] < 0.01]
    R.add("RQ4-03", "Sec. 4.4", "Core models with p < 0.01 (cross vs intra)", "6", len(sig), src_core, mode="exact")
    perm = load_json("results/review/permutation_tests.json")["rq4_cluster_cross_vs_intra"]
    psrc = ["results/review/permutation_tests.json"]
    R.add("RQ4-04", "Sec. 4.4", "Of those, significant (p < 0.01) when labels are permuted within pairs", "6",
          sum(perm["within_pair_swap"][m]["perm_p_two_sided"] < 0.01 for m in sig), psrc, mode="exact", method="read")
    R.add("RQ4-05", "Sec. 4.4", "Of those, significant (p < 0.05) when labels are permuted within language", "4",
          sum(perm["within_language"][m]["perm_p_two_sided"] < 0.05 for m in sig), psrc, mode="exact", method="read")
    R.add("RQ4-06", "Sec. 4.4", "Smallest d of the significant core models", "0.08", min(d4[m]["d"] for m in sig), src_core)
    R.add("RQ4-07", "Sec. 4.4", "Largest d of the significant core models", "0.15", max(d4[m]["d"] for m in sig), src_core)
    R.add("RQ4-08", "Sec. 4.4", "CodeBERT p (cross vs intra)", "0.87", d4["codebert"]["p"], [rq4_file("codebert")])
    R.add("RQ4-09", "Sec. 4.4", "CodeSage p", "0.04", d4["codesage"]["p"], [rq4_file("codesage")])
    R.add("RQ4-10", "Sec. 4.4", "jina-code p", "0.08", d4["jina_code"]["p"], [rq4_file("jina_code")])
    dn10 = {m: d4[m]["dn"][0.10] for m in CORE}
    R.add("RQ4-11", "Sec. 4.4", "Lowest DN rate at tau=0.10 (UniXCoder) (%)", "29.6", min(dn10.values()), src_core)
    R.add("RQ4-12", "Sec. 4.4", "Highest DN rate at tau=0.10 (CodeBERT) (%)", "100", max(dn10.values()), src_core)
    four = ["bge_m3", "minilm", "octen", "qwen3"]
    R.add("RQ4-13", "Sec. 4.4", "Lowest DN rate at tau=0.10, BGE-M3/MiniLM/Octen/Qwen3 (%)", "42", min(dn10[m] for m in four), src_core)
    R.add("RQ4-14", "Sec. 4.4", "Highest DN rate at tau=0.10, BGE-M3/MiniLM/Octen/Qwen3 (%)", "54", max(dn10[m] for m in four), src_core)
    inv = [d4[m]["inv_R"] for m in ALL9]
    R.add("RQ4-15", "Sec. 4.4", "Smallest 1/R over all models ('3.6 times closer')", "3.6", min(inv), src9)
    R.add("RQ4-16", "Sec. 4.4", "Largest 1/R over all models ('6.9 times closer')", "6.9", max(inv), src9)
    rr = {m: d4[m]["R"] for m in ALL9}
    rank_core = sorted(CORE, key=lambda m: -rr[m])
    R.add("RQ4-17", "Sec. 4.4", "Two models with the highest R", "bge_m3, unixcoder", ", ".join(sorted(sorted(rr, key=lambda m: -rr[m])[:2])),
          src9, mode="match")
    raw_rank = sorted(CORE, key=lambda m: dn10[m])   # fewest dangerous neighbourhoods first
    R.add("RQ4-18", "Sec. 4.4", "CodeBERT's rank among core models under raw thresholds (7 = last)", "7",
          raw_rank.index("codebert") + 1, src_core, mode="exact")
    R.add("RQ4-19", "Sec. 4.4", "CodeBERT's rank among core models under R", "3", rank_core.index("codebert") + 1, src_core, mode="exact")
    R.add("RQ4-20", "Sec. 4.4", "Ada-002's rank under raw thresholds (second to last)", "6", raw_rank.index("ada002") + 1, src_core, mode="exact")
    R.add("RQ4-21", "Sec. 4.4", "Ada-002's rank under R (last)", "7", rank_core.index("ada002") + 1, src_core, mode="exact")
    R.add("RQ4-22", "Sec. 4.4", "R of UniXCoder over R of Ada-002 ('about a factor of two')", "2", rr["unixcoder"] / rr["ada002"],
          [rq4_file("unixcoder"), rq4_file("ada002")], mode="approx", tol=0.25)
    R.add("RQ4-23", "Sec. 4.4", "Pair distance of UniXCoder over Ada-002 ('almost six')", "almost 6",
          d4["unixcoder"]["pair"] / d4["ada002"]["pair"], [rq4_file("unixcoder"), rq4_file("ada002")], mode="range", lo=5.5, hi=6.0)
    tf = lex["tfidf_alnum"]["R"]
    R.add("RQ4-24", "Sec. 4.4", "Models whose R exceeds TF-IDF's R", "bge_m3, unixcoder",
          ", ".join(sorted(m for m in ALL9 if rr[m] > tf)), src9 + [LEX], mode="match")
    d_ci = load_json(ESCI)["rq4_cohens_d"]
    R.add("RQ4-07b", "Sec. 4.4", "Upper end of the d interval of the significant core models ('every interval below 0.21')",
          "0.21", max(d_ci[m]["d_ci"][1] for m in sig), [ESCI], mode="lt", method="read")
    tf_ci = r4_ci("tfidf_alnum")[0]
    above = sorted(m for m in ALL9 if r4_ci(m)[0][1] < tf_ci[0])
    R.add("RQ4-24b", "Sec. 4.4", "Models whose R interval lies entirely below TF-IDF's interval", "ada002, jina_code, octen, qwen3",
          ", ".join(above), [RQ4CI, ESCI], mode="match", method="read")
    R.add("RQ4-24c", "Sec. 4.4", "All other models' R intervals overlap TF-IDF's interval", "True",
          all(r4_ci(m)[0][1] >= tf_ci[0] and r4_ci(m)[0][0] <= tf_ci[1] for m in ALL9 if m not in above),
          [RQ4CI, ESCI], mode="match", method="read")
    R.add("RQ4-25", "Sec. 4.4", "Token edit R exceeds every model's R", "True", lex["edit_alnum"]["R"] > max(rr.values()),
          src9 + [LEX], mode="match")
    syn_top = sum(max(d4[m]["R_cat"], key=d4[m]["R_cat"].get) == "syntax" for m in ALL9) + \
        (max(lex["tfidf_alnum"]["R_by_category"], key=lex["tfidf_alnum"]["R_by_category"].get) == "syntax")
    R.add("RQ4-26", "Sec. 4.4", "Models (of 9) plus TF-IDF where syntax pairs have the highest R", "10", syn_top, src9 + [LEX], mode="exact")
    ratio = {m: d4[m]["syntax_ratio"] for m in ALL9}
    R.add("RQ4-27", "Sec. 4.4", "Smallest syntax/other pair-distance ratio (CodeBERT)", "1.5", min(ratio.values()), src9)
    R.add("RQ4-28", "Sec. 4.4", "Model with the smallest ratio", "codebert", min(ratio, key=ratio.get), src9, mode="match")
    R.add("RQ4-29", "Sec. 4.4", "Largest syntax/other pair-distance ratio (Octen)", "2.3", max(ratio.values()), src9)
    R.add("RQ4-30", "Sec. 4.4", "Model with the largest ratio", "octen", max(ratio, key=ratio.get), src9, mode="match")
    R.add("RQ4-31", "Sec. 4.4", "Syntax/other ratio for TF-IDF", "1.8", lex["tfidf_alnum"]["syntax_ratio"], [LEX], method="read")
    R.add("RQ4-32", "Sec. 4.4", "Closest pair is not a syntax error in any core model", "True",
          all(d4[m]["closest_cat"] != "syntax" for m in CORE), src_core, mode="match")
    es = load_json("results/review/edit_size_control.json")["rq4"]
    esrc = ["results/review/edit_size_control.json"]
    dc = es["diff_by_category"]
    R.add("RQ4-33", "Sec. 4.4", "Mean changed identifier tokens, syntax bugs", "7.6", dc["syntax"]["mean_diff_alnum"], esrc, method="read")
    R.add("RQ4-34", "Sec. 4.4", "Mean changed identifier tokens, other categories (lowest)", "7.7",
          min(dc[c]["mean_diff_alnum"] for c in ("logic", "state", "numeric")), esrc, method="read")
    R.add("RQ4-35", "Sec. 4.4", "Mean changed identifier tokens, other categories (highest)", "12.0",
          max(dc[c]["mean_diff_alnum"] for c in ("logic", "state", "numeric")), esrc, method="read")
    R.add("RQ4-36", "Sec. 4.4", "Syntax bugs sit in shorter snippets (mean identifier tokens lowest)", "syntax",
          min(dc, key=lambda c: dc[c]["mean_len_alnum"]), esrc, mode="match", method="read")
    mods = es["models"]
    R.add("RQ4-37", "Sec. 4.4", "Core models where the syntax category stays significant (p < 0.05)", "5",
          sum(mods[m]["M2_logdiff_plus_rel_punct_clustered"]["syntax"]["p"] < 0.05 for m in CORE), esrc, mode="exact", method="read")
    key = "C(category, Treatment('logic'))[T.syntax]"
    share = [1 - mods[m]["M2_logdiff_plus_rel_punct_clustered"]["syntax"]["coef"] /
             mods[m]["diff_punct"]["category_models"]["M1_with_logdiff"][key]["clustered"]["coef"]
             for m in CORE if m != "codebert"]
    R.add("RQ4-38", "Sec. 4.4", "Share of the syntax effect explained by the changed share, lowest ('a fifth')", "0.2",
          min(share), esrc, mode="approx", tol=0.05, method="read")
    R.add("RQ4-39", "Sec. 4.4", "Share of the syntax effect explained by the changed share, highest ('three fifths')", "0.6",
          max(share), esrc, mode="approx", tol=0.05, method="read")

    csrc = ["results/review/pairs.parquet"]
    R.add("RQ4-40", "Sec. 4.4", "Median token edits of a bug", "2", ctl["median_bug_tokens"], csrc, mode="exact")
    R.add("RQ4-41", "Sec. 4.4", "Valid rewrites with fewer than four token edits", "5", ctl["variants_below_4_tokens"], csrc, mode="exact")
    R.add("RQ4-41b", "Sec. 4.4", "Valid behavior-preserving rewrites", "3648", ctl["n_variants"], csrc, mode="exact")
    dz = {m: ctl[m]["dz"] for m in FIVE}
    R.add("RQ4-42", "Sec. 4.4", "Models (of six) where the bug lies closer to the fix than the matched rewrite", "5",
          sum(ctl[m]["dz"] < 0 and ctl[m]["p"] < 0.05 for m in LOCAL6), csrc, mode="exact")
    R.add("RQ4-43", "Sec. 4.4", "Matched d_z, least negative of the five", "-0.39", max(dz.values()), csrc)
    R.add("RQ4-44", "Sec. 4.4", "Matched d_z, most negative of the five", "-0.68", min(dz.values()), csrc)
    R.add("RQ4-45", "Sec. 4.4", "Largest Wilcoxon p of the five", "1e-13", max(ctl[m]["p"] for m in FIVE), csrc, mode="lt")
    R.add("RQ4-46", "Sec. 4.4", "Programs whose rewrite size matches the bug exactly", "112", ctl["n_exact"], csrc, mode="exact")
    ex = [ctl[m]["exact_share_closer"] for m in FIVE]
    R.add("RQ4-47", "Sec. 4.4", "Exact-size programs where the bug lies closer, lowest (%)", "59", 100 * min(ex), csrc)
    R.add("RQ4-48", "Sec. 4.4", "Exact-size programs where the bug lies closer, highest (%)", "82", 100 * max(ex), csrc)
    cmp_dz = [ctl[m]["cmpswap_dz"] for m in FIVE]
    R.add("RQ4-49", "Sec. 4.4", "Comparison swaps lie closer to the fix than the bug in all five models", "True",
          all(v > 0 for v in cmp_dz), csrc, mode="match")
    R.add("RQ4-50", "Sec. 4.4", "Comparison-swap d_z, lowest", "0.31", min(cmp_dz), csrc)
    R.add("RQ4-51", "Sec. 4.4", "Comparison-swap d_z, highest", "0.41", max(cmp_dz), csrc)
    R.add("RQ4-52", "Sec. 4.4", "TF-IDF shows the same pattern (renames farther, swaps closer than the bug)", "True",
          ctl["tfidf"]["rename1_dz"] < 0 and ctl["tfidf"]["cmpswap_dz"] > 0, csrc, mode="match")
    ctrl = load_json("results/review/control_results.json")["results"]
    sig_fe = [m for m in FIVE if ctrl[m]["pooled"]["regression"]["p_is_bug"] < 0.05]
    R.add("RQ4-53", "Sec. 4.4", "Models with a significant bug coefficient in the fixed-effects regression", "4", len(sig_fe),
          ["results/review/control_results.json"], mode="exact", method="read")
    R.add("RQ4-54", "Sec. 4.4", "The model without a significant coefficient", "minilm",
          ",".join(m for m in FIVE if m not in sig_fe), ["results/review/control_results.json"], mode="match", method="read")
    rel = [-ctl[m]["fe_rel_coef"] * 100 for m in sig_fe]
    R.add("RQ4-55", "Sec. 4.4", "Bug-fix distance smaller than same-size rewrite, lowest (%)", "10", min(rel), csrc)
    R.add("RQ4-56", "Sec. 4.4", "Bug-fix distance smaller than same-size rewrite, highest (%)", "29", max(rel), csrc)
    ren = [100 * ctl[m]["rename1_share_variant_farther"] for m in FIVE]
    R.add("RQ4-57", "Sec. 4.4", "Programs where a one-variable rename lies farther than the bug, lowest (%)", "90", min(ren), csrc)
    R.add("RQ4-58", "Sec. 4.4", "Programs where a one-variable rename lies farther than the bug, highest (%)", "99", max(ren), csrc)
    R.add("RQ4-59", "Sec. 4.4", "CodeBERT matched d_z (bug farther than rewrite)", "0.18", ctl["codebert"]["dz"], csrc)
    R.add("RQ4-60", "Sec. 4.4", "CodeBERT bug-fix distance scale (power of ten)", "-3",
          round(math.log10(ctl["codebert"]["mean_d_bug"])), csrc, mode="exact")
    R.add("RQ4-61", "Sec. 4.4", "TF-IDF matched d_z", "-0.52", ctl["tfidf"]["dz"], csrc)
    rho = [ctl[m]["spearman_tfidf"] for m in FIVE]
    R.add("RQ4-62", "Sec. 4.4", "Spearman rho with TF-IDF distances, lowest", "0.71", min(rho), csrc)
    R.add("RQ4-63", "Sec. 4.4", "Spearman rho with TF-IDF distances, highest", "0.89", max(rho), csrc)


def claims_rq5(R: Registry, ret, gen, rev, d4):
    R.section("RQ5")
    S = ret["summary"]
    table = {  # recall@1, buggy-first, star, top-1 buggy | ClassEval same
        "octen": ("97.9", "0.39", "*", "38.6", "98.8", "0.52", "", "51.9"),
        "qwen3": ("95.9", "0.41", "*", "39.4", "98.8", "0.54", "", "53.1"),
        "bge_m3": ("83.7", "0.44", "*", "36.6", "100.0", "0.48", "", "48.1"),
        "unixcoder": ("61.7", "0.46", "*", "26.4", "88.9", "0.52", "", "44.4"),
        "minilm": ("77.0", "0.48", "", "35.3", "100.0", "0.44", "", "33.3"),
        "codebert": ("1.8", "0.59", "*", "1.1", "9.9", "0.76", "*", "4.9"),
        "codesage": ("96.4", "0.32", "*", "30.7", "100.0", "0.49", "", "49.4"),
        "jina_code": ("95.8", "0.41", "*", "39.1", "98.8", "0.36", "*", "35.8"),
        "bm25": ("54.8", "0.48", "", "16.5", "98.8", "0.33", "*", "13.6"),
    }
    for m, row in table.items():
        src = ret_src(m)
        for half, (bench, off) in enumerate([("hef", 0), ("ce", 4)]):
            s = S[m][bench]
            name = "HumanEvalFix" if bench == "hef" else "ClassEval"
            base = f"T5.{m}.{bench}"
            R.add(f"{base}.1", "Table 5", f"{m} {name}: recall@1 (%)", row[off], 100 * s["recall1"], src)
            R.add(f"{base}.2", "Table 5", f"{m} {name}: buggy-first rate", row[off + 1], s["buggy_first"], src)
            R.add(f"{base}.3", "Table 5", f"{m} {name}: binomial p < 0.05 marker", row[off + 2] or "(none)",
                  "*" if s["p"] < 0.05 else "(none)", src, mode="match")
            R.add(f"{base}.4", "Table 5", f"{m} {name}: top-1 buggy (%)", row[off + 3], 100 * s["top1_buggy"], src)
    R.add("RQ5-01", "Table 5", "HumanEvalFix queries pooled over six languages", "984", len([r for l in HEF_LANGS for r in ret["records"]["octen"][l]]),
          npz_files(["octen"]), mode="exact")
    rec = [100 * S[m]["hef"]["recall1"] for m in ("octen", "qwen3")]
    R.add("RQ5-02", "Sec. 4.5", "Octen/Qwen3 recall@1 on HumanEvalFix, lowest (%)", "96", min(rec), ret_src("octen", "qwen3"))
    R.add("RQ5-03", "Sec. 4.5", "Octen/Qwen3 recall@1 on HumanEvalFix, highest (%)", "98", max(rec), ret_src("octen", "qwen3"))
    wil = {m: problem_level_wilcoxon(ret["records"][m]) for m in ("octen", "qwen3", "bge_m3", "unixcoder")}
    R.add("RQ5-04", "Sec. 4.5", "Problem-level Wilcoxon p, largest of Octen/Qwen3/BGE-M3", "1.4e-4",
          max(wil[m] for m in ("octen", "qwen3", "bge_m3")), ret_src("octen", "qwen3", "bge_m3"), mode="le")
    R.add("RQ5-05", "Sec. 4.5", "Problem-level Wilcoxon p, UniXCoder", "0.003", wil["unixcoder"], ret_src("unixcoder"))
    cl = load_json("results/review/rq5_clustered_tests.json")
    R.add("RQ5-06", "Sec. 4.5", "UniXCoder problem-level bootstrap interval, upper bound", "0.502",
          cl["unixcoder"]["buggy_first_ci_problem_bootstrap"][1], ["results/review/rq5_clustered_tests.json"], method="read")
    bf = {m: S[m]["hef"]["buggy_first"] for m in RQ5_DENSE + CLS + ["bm25"]}
    R.add("RQ5-07", "Sec. 4.5", "Retriever best at separating twins on HumanEvalFix", "codesage", min(bf, key=bf.get),
          ret_src(*RQ5_DENSE, *CLS, "bm25"), mode="match")
    qi = load_json("results/review/rq5_query_instructions.json")["models"]
    qsrc = ["results/review/rq5_query_instructions.json"]
    R.add("RQ5-08", "Sec. 4.5", "Qwen3 top-1 buggy with the model-card query instruction (%)", "42.0",
          100 * qi["qwen3"]["variants"]["official"]["pooled"]["top1_buggy"], qsrc, method="read")
    R.add("RQ5-09", "Sec. 4.5", "Octen top-1 buggy with the model-card query instruction (%)", "40.1",
          100 * qi["octen"]["variants"]["official"]["pooled"]["top1_buggy"], qsrc, method="read")
    dense_nb = [m for m in RQ5_DENSE + CLS if not m.startswith("codebert")]
    ce_rec = [100 * S[m]["ce"]["recall1"] for m in dense_nb]
    R.add("RQ5-10", "Sec. 4.5", "ClassEval recall@1 of dense retrievers except CodeBERT, lowest (%)", "89", min(ce_rec), ret_src(*dense_nb))
    R.add("RQ5-11", "Sec. 4.5", "ClassEval recall@1 of dense retrievers except CodeBERT, highest (%)", "100", max(ce_rec), ret_src(*dense_nb))
    below = [m for m in RQ5_DENSE + CLS if S[m]["ce"]["buggy_first"] < 0.5 and S[m]["ce"]["p"] < 0.05]
    R.add("RQ5-12", "Sec. 4.5", "Dense retrievers preferring the reference class significantly on ClassEval", "jina_code",
          ",".join(below), ret_src(*RQ5_DENSE, *CLS), mode="match")
    rest = [S[m]["ce"]["buggy_first"] for m in dense_nb if m not in below]
    R.add("RQ5-13", "Sec. 4.5", "ClassEval buggy-first of the other dense retrievers, lowest", "0.44", min(rest), ret_src(*dense_nb))
    R.add("RQ5-14", "Sec. 4.5", "ClassEval buggy-first of the other dense retrievers, highest", "0.54", max(rest), ret_src(*dense_nb))
    R.add("RQ5-15", "Sec. 4.5", "BM25 ClassEval queries with tied twins (%)", "40", 100 * S["bm25"]["ce"]["tie"], [RANKINGS])

    rh = {m: ret["R_hef"][m] for m in RQ5_DENSE}
    R.add("RQ5-16", "Sec. 4.5", "HumanEvalFix R across the eight open embedders, lowest", "0.010", min(rh.values()), npz_files(RQ5_DENSE))
    R.add("RQ5-17", "Sec. 4.5", "HumanEvalFix R across the eight open embedders, highest", "0.029", max(rh.values()), npz_files(RQ5_DENSE))
    R.add("RQ5-18", "Sec. 4.5", "Fix closer to its bug than to unrelated code, lowest factor", "35", 1 / max(rh.values()), npz_files(RQ5_DENSE))
    R.add("RQ5-19", "Sec. 4.5", "Fix closer to its bug than to unrelated code, highest factor", "97", 1 / min(rh.values()), npz_files(RQ5_DENSE))
    R.add("RQ5-20", "Sec. 4.5", "HumanEvalFix R for TF-IDF", "0.014", load_json(LEX)["rq5"]["humanevalfix"]["tfidf_alnum"]["R"],
          [LEX], method="read")
    R.add("RQ5-21", "Sec. 4.5", "ClassEval R, largest over the eight open embedders", "0.003", max(ret["R_ce"].values()),
          npz_files(RQ5_DENSE), mode="lt")
    prox = load_json(RET_METRICS)["proximity_rq4"]
    R.add("RQ5-22", "Sec. 4.5", "CodeBERT unmatched RQ4 distance with CLS pooling", "0.017", prox["codebert_cls"]["unmatched_mean"],
          [RET_METRICS], method="read")
    R.add("RQ5-23", "Sec. 4.5", "CodeBERT unmatched RQ4 distance with mean pooling", "0.046", d4["codebert"]["unmatched"], [rq4_file("codebert")])
    R.add("RQ5-24", "Sec. 4.5", "CodeBERT RQ4 R with CLS pooling", "0.26", prox["codebert_cls"]["R"], [RET_METRICS], method="read")
    R.add("RQ5-25", "Sec. 4.5", "CodeBERT RQ4 R with mean pooling", "0.24", d4["codebert"]["R"], [rq4_file("codebert")])
    R.add("RQ5-26", "Sec. 4.5", "UniXCoder RQ4 R with mean pooling", "0.28", d4["unixcoder"]["R"], [rq4_file("unixcoder")])
    R.add("RQ5-27", "Sec. 4.5", "UniXCoder RQ4 R with CLS pooling", "0.28", prox["unixcoder_cls"]["R"], [RET_METRICS], method="read")
    R.add("RQ5-28", "Sec. 4.5", "Mean-pooled CodeBERT recall@1 on HumanEvalFix (%)", "1.8", 100 * S["codebert"]["hef"]["recall1"], ret_src("codebert"))

    gtab = {  # benchmark, llm: n, none, correct, buggy, p, copy
        ("hef", "gemma4:31b"): ("492", "93.7", "97.4", "96.7", "0.375", "0.8"),
        ("hef", "gpt-oss:20b"): ("492", "93.3", "96.7", "94.3", "0.004", "0.8"),
        ("hef", "deepseek-v4.1-flash"): ("492", "95.9", "98.0", "96.7", "0.109", "0.8"),
        ("classeval", "gemma4:31b"): ("81", "54.3", "75.3", "74.1", "1.000", "2.5"),
        ("classeval", "gpt-oss:20b"): ("81", "40.7", "84.0", "67.9", "0.001", "9.9"),
        ("classeval", "deepseek-v4.1-flash"): ("81", "48.1", "86.4", "74.1", "0.006", "4.9"),
    }
    for (bench, llm), row in gtab.items():
        g = gen[llm][bench]
        tag = LLMS[llm]
        src = [f"results/rq5/execution/ollama_{tag}/"]
        name = "HumanEvalFix" if bench == "hef" else "ClassEval"
        base = f"T6.{bench}.{tag}"
        R.add(f"{base}.1", "Table 6", f"{llm} {name}: problems", row[0], g["n"], src, mode="exact")
        for j, c in enumerate(("none", "correct", "buggy")):
            R.add(f"{base}.{j + 2}", "Table 6", f"{llm} {name}: pass@1 with {c} (%)", row[j + 1], 100 * g[c], src)
        R.add(f"{base}.5", "Table 6", f"{llm} {name}: McNemar p, correct vs buggy", row[4], g["p"], src)
        gm = load_json(f"results/rq5/generation_metrics_ollama_{tag}.json")
        copy = gm["pooled" if bench == "hef" else "per_language"]
        copy = copy if bench == "hef" else copy["classeval"]
        R.add(f"{base}.6", "Table 6", f"{llm} {name}: bug-copy rate with the buggy twin (%)", row[5],
              100 * copy["bug_copy_rate_buggy_context"], [f"results/rq5/generation_metrics_ollama_{tag}.json"], method="read")

    ce = {llm: gen[llm]["classeval"] for llm in LLMS}
    gsrc = EXEC
    gains = [100 * (ce[l]["correct"] - ce[l]["none"]) for l in LLMS]
    R.add("RQ5-29", "Sec. 4.5", "ClassEval gain of the correct twin over no example, lowest (points)", "21", min(gains), gsrc)
    R.add("RQ5-30", "Sec. 4.5", "ClassEval gain of the correct twin over no example, highest (points)", "43", max(gains), gsrc)
    R.add("RQ5-31", "Sec. 4.5", "A buggy twin still beats no example for every LLM on ClassEval", "True",
          all(ce[l]["buggy"] > ce[l]["none"] for l in LLMS), gsrc, mode="match")
    for i, (llm, pts, p) in enumerate([("gpt-oss:20b", "16.0", "0.001"), ("deepseek-v4.1-flash", "12.3", "0.006"),
                                       ("gemma4:31b", "1.2", "1.0")]):
        R.add(f"RQ5-{32 + 2 * i:02d}", "Sec. 4.5", f"ClassEval cost of the buggy twin, {llm} (points)", pts,
              100 * (ce[llm]["correct"] - ce[llm]["buggy"]), gsrc)
        R.add(f"RQ5-{33 + 2 * i:02d}", "Sec. 4.5", f"ClassEval McNemar p, {llm}", p, ce[llm]["p"], gsrc)
    hef_sig = [l for l in LLMS if gen[l]["hef"]["p"] < 0.05 and gen[l]["hef"]["buggy"] < gen[l]["hef"]["correct"]]
    R.add("RQ5-38", "Sec. 4.5", "LLMs losing significantly with the buggy twin on HumanEvalFix", "gpt-oss:20b", ",".join(hef_sig), gsrc, mode="match")
    R.add("RQ5-39", "Sec. 4.5", "HumanEvalFix cost for gpt-oss:20b (points)", "2.4",
          100 * (gen["gpt-oss:20b"]["hef"]["correct"] - gen["gpt-oss:20b"]["hef"]["buggy"]), gsrc)
    R.add("RQ5-40", "Sec. 4.5", "HumanEvalFix McNemar p for gpt-oss:20b", "0.004", gen["gpt-oss:20b"]["hef"]["p"], gsrc)
    msrc = [f"results/rq5/generation_metrics_ollama_{t}.json" for t in LLMS.values()]
    fails = []
    for t in LLMS.values():
        gm = load_json(f"results/rq5/generation_metrics_ollama_{t}.json")
        fails += [gm["pooled"]["bug_copy_rate_among_failures"], gm["per_language"]["classeval"]["bug_copy_rate_among_failures"]]
    R.add("RQ5-41", "Sec. 4.5", "Failures after a buggy example that copy a buggy line, lowest (%)", "5", 100 * min(fails), msrc, method="read")
    R.add("RQ5-42", "Sec. 4.5", "Failures after a buggy example that copy a buggy line, highest (%)", "31", 100 * max(fails), msrc, method="read")
    gce = load_json("results/rq5/generation_metrics_ollama_gpt-oss_20b.json")["per_language"]["classeval"]
    gsrc1 = ["results/rq5/generation_metrics_ollama_gpt-oss_20b.json"]
    R.add("RQ5-43", "Sec. 4.5", "gpt-oss:20b ClassEval: failures that copy a buggy line (%)", "30.8", 100 * gce["bug_copy_rate_among_failures"], gsrc1, method="read")
    R.add("RQ5-44", "Sec. 4.5", "gpt-oss:20b ClassEval: generations that copy a buggy line (%)", "9.9", 100 * gce["bug_copy_rate_buggy_context"], gsrc1, method="read")
    R.add("RQ5-45", "Sec. 4.5", "gpt-oss:20b ClassEval: buggy-only lines with the correct twin (%)", "1.2", 100 * gce["bug_copy_rate_correct_context"], gsrc1, method="read")

    ssrc = [f"results/review/execution/samples/{t}.jsonl" for t in LLMS.values()]
    sm = load_json("results/review/samples_metrics.json")
    smsrc = ["results/review/samples_metrics.json"]
    for i, (llm, gap, lo, hi) in enumerate([("gpt-oss:20b", "15.6", "9.4", "22.2"), ("deepseek-v4.1-flash", "10.4", "4.9", "16.8"),
                                            ("gemma4:31b", "3.0", "-0.2", "7.2")]):
        R.add(f"RQ5-{46 + 3 * i:02d}", "Sec. 4.5", f"Five-sample correct-minus-buggy gap, {llm} (points)", gap, 100 * rev[llm]["sample_gap"], ssrc)
        ci = sm[llm]["gap_correct_minus_buggy"]["ci95"]
        R.add(f"RQ5-{47 + 3 * i:02d}", "Sec. 4.5", f"Five-sample gap 95% interval, lower, {llm}", lo, 100 * ci[0], smsrc, method="read")
        R.add(f"RQ5-{48 + 3 * i:02d}", "Sec. 4.5", f"Five-sample gap 95% interval, upper, {llm}", hi, 100 * ci[1], smsrc, method="read")
    R.add("RQ5-55", "Sec. 4.5", "Gap positive in all five samples for every LLM", "True",
          all(min(rev[l]["sample_gaps"]) > 0 for l in LLMS), ssrc, mode="match")
    fsrc = [f"results/review/execution/framing/{t}.jsonl" for t in LLMS.values()] + EXEC
    R.add("RQ5-56", "Sec. 4.5", "gpt-oss:20b pass@1, warned, correct twin (%)", "70.4", 100 * rev["gpt-oss:20b"]["warn_correct"], fsrc)
    R.add("RQ5-57", "Sec. 4.5", "gpt-oss:20b McNemar p, warned vs unwarned correct twin", "0.003", rev["gpt-oss:20b"]["p_warn_correct"], fsrc)
    R.add("RQ5-58", "Sec. 4.5", "gpt-oss:20b pass@1, warned, buggy twin (%)", "61.7", 100 * rev["gpt-oss:20b"]["warn_buggy"], fsrc)
    R.add("RQ5-59", "Sec. 4.5", "deepseek-v4.1-flash pass@1, warned, correct twin (%)", "67.9", 100 * rev["deepseek-v4.1-flash"]["warn_correct"], fsrc)
    R.add("RQ5-60", "Sec. 4.5", "deepseek-v4.1-flash pass@1, warned, buggy twin (%)", "67.9", 100 * rev["deepseek-v4.1-flash"]["warn_buggy"], fsrc)
    fm = load_json("results/review/framing_metrics.json")
    R.add("RQ5-61", "Sec. 4.5", "gpt-oss:20b copies no buggy line when warned (%)", "0", 100 * fm["gpt-oss:20b"]["bug_copy_warn_buggy"],
          ["results/review/framing_metrics.json"], mode="exact", method="read")
    tsrc = [RANKINGS] + [f"results/review/execution/top3/{t}.jsonl" for t in LLMS.values()] + EXEC
    R.add("RQ5-62", "Sec. 4.5", "ClassEval tasks whose Octen top-3 contains both twins", "80", rev["both_in_top3"], [RANKINGS], mode="exact")
    for i, (llm, a, b, p) in enumerate([("deepseek-v4.1-flash", "77.8", "86.4", "0.065"), ("gemma4:31b", "71.6", "77.8", "0.063")]):
        R.add(f"RQ5-{63 + 3 * i:02d}", "Sec. 4.5", f"{llm} pass@1 with Octen top-1 (%)", a, 100 * rev[llm]["top1"], tsrc)
        R.add(f"RQ5-{64 + 3 * i:02d}", "Sec. 4.5", f"{llm} pass@1 with Octen top-3 (%)", b, 100 * rev[llm]["top3"], tsrc)
        R.add(f"RQ5-{65 + 3 * i:02d}", "Sec. 4.5", f"{llm} McNemar p, top-3 vs top-1", p, rev[llm]["p_top3"], tsrc)
    R.add("RQ5-69", "Sec. 4.5", "gpt-oss:20b pass@1 with Octen top-1 (%)", "76.5", 100 * rev["gpt-oss:20b"]["top1"], tsrc)
    R.add("RQ5-70", "Sec. 4.5", "gpt-oss:20b pass@1 with Octen top-3 (%)", "76.5", 100 * rev["gpt-oss:20b"]["top3"], tsrc)

    dense_ret = [m for m in LOCAL6 + CLS if not m.startswith("codebert")]
    esrc = EXEC + [RANKINGS]
    for i, (llm, lo, hi) in enumerate([("gpt-oss:20b", "72.8", "76.5"), ("deepseek-v4.1-flash", "77.8", "85.2")]):
        vals = [100 * ce[llm]["ret"][m] for m in dense_ret]
        R.add(f"RQ5-{71 + 2 * i:02d}", "Sec. 4.5", f"{llm} ClassEval pass@1, dense top-1 except CodeBERT, lowest (%)", lo, min(vals), esrc)
        R.add(f"RQ5-{72 + 2 * i:02d}", "Sec. 4.5", f"{llm} ClassEval pass@1, dense top-1 except CodeBERT, highest (%)", hi, max(vals), esrc)
    for i, (llm, val) in enumerate([("gpt-oss:20b", "81.5"), ("deepseek-v4.1-flash", "85.2")]):
        R.add(f"RQ5-{75 + 2 * i:02d}", "Sec. 4.5", f"{llm} ClassEval pass@1 with BM25 top-1 (%)", val, 100 * ce[llm]["ret"]["bm25"], esrc)
        R.add(f"RQ5-{76 + 2 * i:02d}", "Sec. 4.5", f"BM25 beats Octen and Qwen3 for {llm}", "True",
              ce[llm]["ret"]["bm25"] > max(ce[llm]["ret"]["octen"], ce[llm]["ret"]["qwen3"]), esrc, mode="match")
    flt = load_json(RET_METRICS)["filtering"]
    three = ["octen", "qwen3", "bge_m3"]
    before = [100 * S[m][l]["top1_buggy"] for m in three for l in GEN_LANGS]
    after = [100 * flt[l][m]["pick_buggy_twin"] for m in three for l in GEN_LANGS]
    red = [100 * (1 - flt[l][m]["pick_buggy_twin"] / S[m][l]["top1_buggy"]) for m in three for l in GEN_LANGS]
    R.add("RQ5-79", "Sec. 4.5", "Buggy twin reaches the LLM before filtering, lowest (%)", "31.1", min(before), ret_src(*three))
    R.add("RQ5-80", "Sec. 4.5", "Buggy twin reaches the LLM before filtering, highest (%)", "41.5", max(before), ret_src(*three))
    R.add("RQ5-81", "Sec. 4.5", "Buggy twin reaches the LLM after filtering, lowest (%)", "3.7", min(after), [RET_METRICS], method="read")
    R.add("RQ5-82", "Sec. 4.5", "Buggy twin reaches the LLM after filtering, highest (%)", "5.5", max(after), [RET_METRICS], method="read")
    bp = [100 * flt[l]["buggy_twin_passes_example_tests"] for l in GEN_LANGS]
    R.add("RQ5-83", "Sec. 4.5", "Buggy twins passing the docstring examples, lowest (%)", "9", min(bp), [RET_METRICS], method="read")
    R.add("RQ5-84", "Sec. 4.5", "Buggy twins passing the docstring examples, highest (%)", "11", max(bp), [RET_METRICS], method="read")
    R.add("RQ5-85", "Abstract", "Filtering removes buggy retrievals, lowest (%)", "85", min(red), ret_src(*three) + [RET_METRICS])
    R.add("RQ5-86", "Abstract", "Filtering removes buggy retrievals, highest (%)", "90", max(red), ret_src(*three) + [RET_METRICS])
    gap = max(100 * (gen[l]["hef"]["correct"] - gen[l]["hef"]["ret"][m]) for l in LLMS for m in dense_ret)
    R.add("RQ5-87", "Sec. 4.5", "HumanEvalFix: largest gap of dense end-to-end pass@1 below the correct twin (points)", "2.1",
          gap, esrc, mode="le")
    for i, (llm, m) in enumerate([("gpt-oss:20b", "octen"), ("gpt-oss:20b", "qwen3"),
                                  ("deepseek-v4.1-flash", "octen"), ("deepseek-v4.1-flash", "qwen3")]):
        R.add(f"RQ5-{88 + i:02d}", "Sec. 4.5", f"ClassEval points added by filtering, {llm} with {m} ('about 2.5')", "2.5",
              100 * (ce[llm]["filt"][m] - ce[llm]["ret"][m]), esrc + [RET_METRICS], mode="approx", tol=0.25)
    R.add("RQ5-92", "Sec. 4.5", "gpt-oss:20b ClassEval pass@1 with Octen, filtered (%)", "79.0", 100 * ce["gpt-oss:20b"]["filt"]["octen"],
          esrc + [RET_METRICS])
    jsrc = ["results/review/judge/picks.json"]
    R.add("RQ5-93", "Sec. 4.5", "LLM judge passes a buggy ClassEval twin (%)", "0", 100 * rev["judge_buggy_ce"], jsrc, mode="exact")
    R.add("RQ5-94", "Sec. 4.5", "LLM judge passes a buggy HumanEvalFix Python twin (%)", "0.6", 100 * rev["judge_buggy_py"], jsrc)
    R.add("RQ5-95", "Sec. 4.5", "Example-test filtering passes a buggy ClassEval twin, Octen (%)", "34.6",
          100 * flt["classeval"]["octen"]["pick_buggy_twin"], [RET_METRICS], method="read")
    R.add("RQ5-96", "Sec. 4.5", "Example-test filtering passes a buggy Python twin, Octen (%)", "4.3",
          100 * flt["python"]["octen"]["pick_buggy_twin"], [RET_METRICS], method="read")
    R.add("RQ5-97", "Sec. 4.5", "LLM judge rejects the correct ClassEval twin (%)", "45.7", 100 * (1 - rev["judge_correct_ce"]), jsrc)
    jp = [100 * rev[l]["judge"] for l in LLMS]
    tp = [100 * rev[l]["top1"] for l in LLMS]
    R.add("RQ5-98", "Sec. 4.5", "End-to-end pass@1 with the judge, lowest (%)", "67.9", min(jp), jsrc + EXEC)
    R.add("RQ5-99", "Sec. 4.5", "End-to-end pass@1 with the judge, highest (%)", "70.4", max(jp), jsrc + EXEC)
    R.add("RQ5-100", "Sec. 4.5", "End-to-end pass@1 with Octen top-1, lowest (%)", "71.6", min(tp), EXEC + [RANKINGS])
    R.add("RQ5-101", "Sec. 4.5", "End-to-end pass@1 with Octen top-1, highest (%)", "77.8", max(tp), EXEC + [RANKINGS])


def claims_abstract(R: Registry, d1, d2, d3, d4, ret, gen, rev, ctl):
    R.section("Abstract and introduction")
    n = d2["octen"]["n"] + len(d3["_df"]) + len(d4["octen"]["df"]) + \
        sum(len(ret["order"]["octen"][(l, "d")]) for l in HEF_LANGS + ["classeval"])
    R.add("ABS-01", "Abstract", "Code snippets embedded (RQ2 + RQ3 + RQ4 + RQ5 documents)", "8,816", n,
          [rq2_file("octen"), rq3_file("octen"), rq4_file("octen")] + npz_files(["octen"]), mode="exact")
    R.add("ABS-02", "Abstract", "Embedding models with RQ2-RQ4 results", "9",
          sum(os.path.exists(path(rq4_file(m))) for m in ALL9), [rq4_file(m) for m in ALL9], mode="exact")
    stable9 = co_clustered(d1["languages"], [d1["models"][m]["parts"][5] for m in ALL9])
    R.add("ABS-03", "Abstract", "Language pairs co-clustered under all nine models", "3", len(stable9), [mat_file(m) for m in ALL9], mode="exact")
    R.add("ABS-04", "Abstract", "Smallest framework d of the core models", "0.97", min(d2[m]["d"] for m in CORE), [rq2_file(m) for m in CORE])
    R.add("ABS-05", "Abstract", "Largest framework d of the core models", "2.38", max(d2[m]["d"] for m in CORE), [rq2_file(m) for m in CORE])
    R.add("ABS-06", "Abstract", "Largest complexity d of any model", "0.64", max(d3[m]["d"] for m in ALL9), [rq3_file(m) for m in ALL9])
    lex = load_json(LEX)
    order = lex["rq2"]["tfidf_alnum"]["cohens_d"] > lex["rq3"]["tfidf_alnum"]["cohens_d"] > lex["rq4"]["tfidf_alnum"]["cluster_cohens_d"]
    R.add("ABS-07", "Abstract", "TF-IDF reproduces the framework > complexity > correctness order", "True", order, [LEX], mode="match", method="read")
    R.add("ABS-08", "Abstract", "Locally run models where a bug lies closer to its fix than a matched rewrite", "5",
          sum(ctl[m]["dz"] < 0 and ctl[m]["p"] < 0.05 for m in LOCAL6), ["results/review/pairs.parquet"], mode="exact")
    R.add("ABS-09", "Abstract", "Locally run models in the control", "6", len(LOCAL6), ["results/review/pairs.parquet"], mode="exact")
    tf = lex["rq4"]["tfidf_alnum"]["R"]
    R.add("ABS-10", "Abstract", "Models keeping bugs closer to their fixes than TF-IDF (R below TF-IDF)", "7",
          sum(d4[m]["R"] < tf for m in ALL9), [rq4_file(m) for m in ALL9] + [LEX], mode="exact")
    S = ret["summary"]
    strong = [m for m in RQ5_DENSE + CLS if S[m]["hef"]["recall1"] > 0.8]
    hef = [100 * S[m]["hef"]["top1_buggy"] for m in strong]
    ce = [100 * S[m]["ce"]["top1_buggy"] for m in strong]
    src = ret_src(*strong)
    R.add("ABS-11", "Abstract", "Retrievers with recall@1 > 80%: top-1 buggy on HumanEvalFix, lowest (%)", "31", min(hef), src)
    R.add("ABS-12", "Abstract", "Retrievers with recall@1 > 80%: top-1 buggy on HumanEvalFix, highest (%)", "39", max(hef), src)
    R.add("ABS-13", "Abstract", "Retrievers with recall@1 > 80%: top-1 buggy on ClassEval, lowest (%)", "36", min(ce), src)
    R.add("ABS-14", "Abstract", "Retrievers with recall@1 > 80%: top-1 buggy on ClassEval, highest (%)", "53", max(ce), src)
    two = ["gpt-oss:20b", "deepseek-v4.1-flash"]
    costs = [100 * (gen[l]["classeval"]["correct"] - gen[l]["classeval"]["buggy"]) for l in two] + \
            [100 * rev[l]["sample_gap"] for l in two]
    R.add("ABS-15", "Abstract", "ClassEval cost of the buggy example, greedy and sampled, lowest (points)", "10", min(costs), EXEC)
    R.add("ABS-16", "Abstract", "ClassEval cost of the buggy example, greedy and sampled, highest (points)", "16", max(costs), EXEC)
    R.add("ABS-17", "Abstract", "LLMs with a significant ClassEval cost (greedy, p < 0.05)", "2",
          sum(gen[l]["classeval"]["p"] < 0.05 for l in LLMS), EXEC, mode="exact")
    R.add("ABS-18", "Abstract", "Warning lowers pass@1 with the correct example (significant, of three LLMs)", "2",
          sum(rev[l]["p_warn_correct"] < 0.05 and rev[l]["warn_correct"] < gen[l]["classeval"]["correct"] for l in LLMS),
          [f"results/review/execution/framing/{t}.jsonl" for t in LLMS.values()], mode="exact")
    R.add("INT-01", "Sec. 1", "CodeBERT pairs within cosine distance 0.10 (%)", "100", d4["codebert"]["dn"][0.10], [rq4_file("codebert")], mode="exact")
    R.add("INT-02", "Sec. 1", "CodeBERT ranks ahead of Ada-002 after scale normalisation (R)", "True",
          d4["codebert"]["R"] > d4["ada002"]["R"], [rq4_file("codebert"), rq4_file("ada002")], mode="match")
    R.add("INT-03", "Sec. 1", "Models except UniXCoder and BGE-M3 keep bugs closer than TF-IDF", "True",
          sorted(m for m in ALL9 if d4[m]["R"] >= tf) == ["bge_m3", "unixcoder"], [rq4_file(m) for m in ALL9] + [LEX], mode="match")
    R.add("INT-04", "Sec. 1", "CodeBERT's margin to TF-IDF's R is marginal (below 0.005)", "0.005", tf - d4["codebert"]["R"],
          [rq4_file("codebert"), LEX], mode="lt")
    for i, (llm, pts) in enumerate([("gpt-oss:20b", "16"), ("deepseek-v4.1-flash", "12")]):
        R.add(f"INT-{5 + i:02d}", "Sec. 1", f"ClassEval cost for {llm} (points)", pts,
              100 * (gen[llm]["classeval"]["correct"] - gen[llm]["classeval"]["buggy"]), EXEC)


def claims_discussion(R: Registry, d3, d4, ret, gen, rev, ctl):
    R.section("Discussion and threats")
    lex = load_json(LEX)
    R.add("DIS-01", "Sec. 5.1", "Largest complexity d of the core models is below 0.64", "0.64", max(d3[m]["d"] for m in CORE),
          [rq3_file(m) for m in CORE], mode="lt")
    R.add("DIS-02", "Sec. 5.1", "Largest correctness |d| of the core models is below 0.15", "0.15",
          max(abs(d4[m]["d"]) for m in CORE), [rq4_file(m) for m in CORE], mode="lt")
    for i, (rq, key, val) in enumerate([("rq2", "cohens_d", "1.99"), ("rq3", "cohens_d", "0.52"), ("rq4", "cluster_cohens_d", "0.04")]):
        R.add(f"DIS-{3 + i:02d}", "Sec. 5.1", f"TF-IDF d in {rq.upper()}", val, lex[rq]["tfidf_alnum"][key], [LEX], method="read")
    rr = {m: d4[m]["R"] for m in ALL9}
    R.add("DIS-06", "Sec. 5.2", "Lowest R over the nine models", "0.144", min(rr.values()), [rq4_file(m) for m in ALL9])
    R.add("DIS-07", "Sec. 5.2", "Highest R over the nine models", "0.281", max(rr.values()), [rq4_file(m) for m in ALL9])
    cm = {m: rr[m] for m in CODE_MODELS}
    R.add("DIS-08", "Sec. 5.2", "Lowest R among the code models (jina-code)", "0.152", min(cm.values()), [rq4_file(m) for m in CODE_MODELS])
    R.add("DIS-09", "Sec. 5.2", "Highest R among the code models (UniXCoder)", "0.281", max(cm.values()), [rq4_file(m) for m in CODE_MODELS])
    R.add("DIS-10", "Sec. 5.2", "CodeSage top-1 buggy on HumanEvalFix (%)", "30.7", 100 * ret["summary"]["codesage"]["hef"]["top1_buggy"],
          ret_src("codesage"))
    um = [d4[m]["unmatched"] for m in ALL9 if m != "codebert"]
    R.add("DIS-11", "Sec. 5.2", "Unmatched RQ4 distance of the other models, lowest", "0.27", min(um), [rq4_file(m) for m in ALL9])
    R.add("DIS-12", "Sec. 5.2", "Unmatched RQ4 distance of the other models, highest", "0.94", max(um), [rq4_file(m) for m in ALL9])
    R.add("DIS-13", "Sec. 5.2", "Core models a p-value reading would call separated (p < 0.05)", "6",
          sum(d4[m]["p"] < 0.05 for m in CORE), [rq4_file(m) for m in CORE], mode="exact")
    R.add("DIS-14", "Sec. 5.3", "Share of bug-fix pairs within cosine 0.10 under UniXCoder (%)", "29.6", d4["unixcoder"]["dn"][0.10], [rq4_file("unixcoder")])
    R.add("DIS-15", "Sec. 5.3", "LLMs whose pass@1 rises with several candidates (top-3)", "2",
          sum(rev[l]["top3"] > rev[l]["top1"] for l in LLMS), EXEC, mode="exact")
    R.add("DIS-16", "Sec. 5.3", "None of those rises is significant (smallest p)", "0.05",
          min(rev[l]["p_top3"] for l in LLMS if rev[l]["top3"] > rev[l]["top1"]), EXEC, mode="gt")
    R.add("DIS-17", "Sec. 5.3", "CodeBERT recall@1 on HumanEvalFix with CLS pooling (%)", "2.9",
          100 * ret["summary"]["codebert_cls"]["hef"]["recall1"], ret_src("codebert_cls"))
    R.add("DIS-18", "Sec. 5.4", "CodeBERT correctness silhouette", "0.018", d4["codebert"]["sil"], [rq4_file("codebert")])
    others = [d4[m]["sil"] for m in ALL9 if m != "codebert"]
    R.add("DIS-19", "Sec. 5.4", "CodeBERT's silhouette lies inside the other models' range", "True",
          min(others) <= d4["codebert"]["sil"] <= max(others), [rq4_file(m) for m in ALL9], mode="match")
    ts = load_json("results/truncation_sensitivity.json")
    R.add("DIS-20", "Sec. 6", "Stable RQ1 pairs with window-averaged full-text embeddings", "C-C++, Kotlin-Scala, Python-Ruby",
          pairs_text(ts["rq1_stable_pairings_full_text"]), ["results/truncation_sensitivity.json"], mode="match", method="read")
    lab = load_json("results/review/rq3_label_metrics.json")
    R.add("DIS-21", "Sec. 6", "Mean label agreement of the two LLM judges ('about four in five')", "0.8",
          np.mean([lab[l]["agreement_with_label"] for l in ("gpt-oss:20b", "gemma4:31b")]),
          ["results/review/rq3_label_metrics.json"], mode="approx", tol=0.025, method="read")


def claims_consistency(R: Registry, ret):
    R.section("Consistency of committed files")
    for m in LOCAL6 + CLS:
        for bench, name in (("hef", "HumanEvalFix"), ("ce", "ClassEval")):
            R.add(f"CON.{m}.{bench}", "Table 5", f"{m} {name}: queries whose committed ranking the committed embeddings "
                  f"do not reproduce", "0", ret["mismatch"][(m, bench)], npz_files([m]) + [RANKINGS], mode="exact")


# Claims that the committed files cannot settle (see the report).
NOT_CHECKABLE = [
    ("Sec. 3.1", "Batched RQ5 encoding gives the same vectors as one-by-one encoding",
     "needs the models; results/review/embeddings/repro_check.json is not tracked", "code/review/embed_variants.py"),
    ("Sec. 3.1", "Embedding procedure (pooling, no instruction, unchanged Ada-002 vectors)",
     "needs the models and the OpenRouter API", "code/embedding.py, code/rq*/1_embedding.py, code/rq5/1_embedding.py"),
    ("Sec. 3.4", "Every correct solution passes and every buggy one fails the hidden tests",
     "needs Python, Node and JDK 17 to run the harness", "code/rq5/execute.py"),
    ("Sec. 3.4", "ClassEval mutants are the first test-killed mutant in a fixed pseudo-random order",
     "needs a test run over every mutant", "code/rq5/0_prepare_classeval.py"),
    ("Sec. 4.1", "Feature/snippet bootstrap of the RQ1 partitions (0.86, 0.72 to 0.98, 77%) beyond the committed summary",
     "needs the RQ1 snippet embeddings (results/review/rq1_embeddings/*.npz, not tracked)",
     "code/review/rq1_reembed.py, code/review/rq1_bootstrap.py"),
    ("Sec. 4.2", "Window-averaged full-text embeddings (UniXCoder d 1.305, CodeBERT 0.753, MiniLM 1.827) beyond the committed summary",
     "needs re-embedding with the models", "code/truncation_sensitivity.py"),
    ("Sec. 4.5", "Example-test filtering picks and the share of buggy twins that pass the examples",
     "needs executing the top-5 documents on the example tests", "code/rq5/2_retrieval.py"),
    ("Sec. 4.5", "Bug-copy rates and LLM judge verdicts beyond the committed records",
     "copy rates need the HumanEvalPack corpus builder; verdicts need the Ollama API",
     "code/rq5/3_evaluate.py, code/review/llm_judge.py"),
    ("Sec. 4.2 / Fig. 3", "Qualitative t-SNE reading (languages occupy own regions, Java and Kotlin overlap)",
     "visual judgement of a figure", "code/rq2/3_visualize.py, code/make_figures.py"),
    ("Sec. 2.2", "Prior-work numbers of Yun et al. (d 0.42 to 0.71, silhouette 0.41)",
     "reported by the cited paper, not by this artifact", "(external)"),
]


# ── Output ────────────────────────────────────────────────────────────────────


def print_table(checks: list[Check], summary: bool):
    rows = [c for c in checks if c.status != "PASS"] if summary else checks
    if rows:
        w_id = max(len(c.cid) for c in rows)
        w_desc = 64
        print(f"{'ID':<{w_id}}  {'Where':<11}  {'Description':<{w_desc}}  {'Paper':>16}  {'Recomputed':>16}  Status")
        print("-" * (w_id + w_desc + 72))
        rq = None
        for c in rows:
            if c.rq != rq:
                rq = c.rq
                print(f"[{rq}]")
            desc = c.desc if len(c.desc) <= w_desc else c.desc[:w_desc - 3] + "..."
            paper = c.paper if len(c.paper) <= 16 else c.paper[:13] + "..."
            val = fmt(c)
            val = val if len(val) <= 16 else val[:13] + "..."
            print(f"{c.cid:<{w_id}}  {c.where[:11]:<11}  {desc:<{w_desc}}  {paper:>16}  {val:>16}  {c.status}")
    fails = [c for c in checks if c.status != "PASS"]
    print()
    for rq in dict.fromkeys(c.rq for c in checks):
        sub = [c for c in checks if c.rq == rq]
        print(f"  {rq:<28} {sum(c.status == 'PASS' for c in sub):>4} / {len(sub):<4} pass")
    print(f"\n{len(checks)} checks: {len(checks) - len(fails)} PASS, {len(fails)} FAIL "
          f"({sum(c.method == 'recomputed' for c in checks)} recomputed from committed embeddings or records, "
          f"{sum(c.method == 'read' for c in checks)} read from committed metric files)")


def md_escape(s: str) -> str:
    return str(s).replace("|", "\\|")


def write_report(checks: list[Check], secs: float):
    fails = [c for c in checks if c.status != "PASS"]
    lines = ["# Paper claims vs committed results", "",
             "Generated by `reproduce/check_claims.py`; do not edit by hand.", "",
             f"{len(checks)} checks, {len(checks) - len(fails)} pass, {len(fails)} fail "
             f"(runtime {secs:.0f} s). `recomputed` means the value was derived from committed embeddings, "
             "distance matrices or per-item records; `read` means it was taken from a committed metric file "
             "whose inputs are not tracked.", "",
             "A value printed with k decimals passes when the recomputed value lies within half a unit of the "
             "k-th decimal; counts must match exactly.", ""]
    if fails:
        lines += ["## Failures", "", "| ID | Where | Claim | Paper | Recomputed | Source |", "|---|---|---|---|---|---|"]
        for c in fails:
            lines.append(f"| {c.cid} | {md_escape(c.where)} | {md_escape(c.desc)} | {md_escape(c.paper)} | {md_escape(fmt(c))} | "
                         f"{md_escape(', '.join(c.sources))} |")
        lines.append("")
    for rq in dict.fromkeys(c.rq for c in checks):
        lines += [f"## {rq}", "", "| ID | Where | Claim | Paper | Recomputed | Criterion | Status | Method | Source files | Produced by |",
                  "|---|---|---|---|---|---|---|---|---|---|"]
        for c in checks:
            if c.rq != rq:
                continue
            srcs = "<br>".join(f"`{s}`" for s in c.sources)
            prods = "<br>".join(dict.fromkeys(f"`{producer(s)}`" for s in c.sources))
            lines.append(f"| {c.cid} | {md_escape(c.where)} | {md_escape(c.desc)} | {md_escape(c.paper)} | {md_escape(fmt(c))} | "
                         f"{md_escape(criterion(c))} | {c.status} | {c.method} | {srcs} | {prods} |")
        lines.append("")
    lines += ["## Requires full re-run", "",
              "These claims cannot be derived from the tracked files and are not checked here.", "",
              "| Where | Claim | Why | Script |", "|---|---|---|---|"]
    for where, claim, why, script in NOT_CHECKABLE:
        lines.append(f"| {where} | {md_escape(claim)} | {md_escape(why)} | `{script}` |")
    lines.append("")
    with open(REPORT, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(lines))


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--summary", action="store_true", help="print only failures and the counts")
    args = ap.parse_args()
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except AttributeError:
        pass

    t0 = time.time()
    d1 = rq1_data()
    d2 = rq2_data()
    d3 = rq3_data()
    d4 = rq4_data()
    ret = rq5_retrieval_data()
    gen = generation_data()
    rev = review_data(gen)
    ctl = control_data()

    R = Registry()
    claims_abstract(R, d1, d2, d3, d4, ret, gen, rev, ctl)
    claims_design(R, d1, d2, d3, d4, ret, ctl)
    claims_rq1(R, d1)
    claims_rq2(R, d2)
    claims_rq3(R, d3)
    claims_rq4(R, d4, ctl)
    claims_rq5(R, ret, gen, rev, d4)
    claims_discussion(R, d3, d4, ret, gen, rev, ctl)
    claims_consistency(R, ret)

    secs = time.time() - t0
    print_table(R.checks, args.summary)
    write_report(R.checks, secs)
    print(f"Report: {os.path.relpath(REPORT, ROOT)} ({secs:.0f} s)")
    sys.exit(1 if any(c.status != "PASS" for c in R.checks) else 0)


if __name__ == "__main__":
    main()

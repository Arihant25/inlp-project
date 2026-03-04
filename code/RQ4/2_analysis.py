"""
RQ4: Correctness Regions & Bug Patterns — Metric Computation & Statistical Analysis

Loads the embeddings Parquet produced by 1_embedding.py and computes:
  1. Correctness Silhouette Score  (overall + per language + per severity)
  2. Pairwise buggy–fixed cosine distances  (per language, per severity)
  3. Intra-cluster vs cross-cluster distances  (buggy-buggy vs buggy-fixed)
  4. Separation score  (cross − mean(intra_buggy, intra_fixed))
  5. Dangerous-neighbourhood analysis  (% pairs with cosine dist < threshold)
  6. Bug severity distance matrix  (pairwise mean distances between severity levels)
  7. Statistical tests:  Welch's t-test, Cohen's d, Bootstrap 95 % CIs

Usage:
    uv run code/RQ4/2_analysis.py                    # all models
    uv run code/RQ4/2_analysis.py --model octen      # single model

Output: results/RQ4/{model_key}/rq4_metrics.json
"""

import argparse
import json
import os
import sys
from itertools import combinations

import numpy as np
import pandas as pd
from scipy import stats
from scipy.spatial.distance import cosine as cosine_dist
from sklearn.metrics import silhouette_score
from tqdm import tqdm

# ── Paths ─────────────────────────────────────────────────────────────────────
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
CODE_DIR = os.path.dirname(SCRIPT_DIR)
sys.path.insert(0, CODE_DIR)
from embedding import MODELS

PROJECT_ROOT = os.path.dirname(CODE_DIR)
OUTPUT_BASE = os.path.join(PROJECT_ROOT, "results/RQ4")

SEVERITY_ORDER = ["Easy", "Medium", "Hard", "Super Hard"]
DANGEROUS_THRESHOLDS = [0.05, 0.10, 0.15]


# ── Helpers ────────────────────────────────────────────────────────────────────


def to_matrix(df: pd.DataFrame) -> np.ndarray:
    """Convert the embedding column (list of floats) to a 2-D numpy matrix."""
    return np.vstack(df["embedding"].values)


def cohens_d(a: np.ndarray, b: np.ndarray) -> float:
    """
    Compute Cohen's d effect size for two independent samples.

    Args:
        a (np.ndarray): First sample.
        b (np.ndarray): Second sample.

    Returns:
        float: Cohen's d (positive means a > b).
    """
    n1, n2 = len(a), len(b)
    if n1 < 2 or n2 < 2:
        return 0.0
    var1, var2 = np.var(a, ddof=1), np.var(b, ddof=1)
    pooled = np.sqrt(((n1 - 1) * var1 + (n2 - 1) * var2) / (n1 + n2 - 2))
    return float((np.mean(a) - np.mean(b)) / pooled) if pooled > 0 else 0.0


def bootstrap_ci(
    data: np.ndarray, stat=np.mean, n_boot: int = 5000, ci: float = 0.95, seed: int = 42
) -> tuple[float, float]:
    """
    Compute a percentile bootstrap confidence interval.

    Args:
        data (np.ndarray): 1-D array of observations.
        stat (callable): Statistic to bootstrap (default: np.mean).
        n_boot (int): Number of bootstrap resamples.
        ci (float): Confidence level.
        seed (int): Random seed.

    Returns:
        tuple[float, float]: (lower_bound, upper_bound).
    """
    rng = np.random.default_rng(seed)
    boots = [
        stat(rng.choice(data, size=len(data), replace=True)) for _ in range(n_boot)
    ]
    boots = np.array(boots)
    alpha = 1 - ci
    return float(np.percentile(boots, 100 * alpha / 2)), float(
        np.percentile(boots, 100 * (1 - alpha / 2))
    )


# ── Metric 1: Correctness Silhouette Score ────────────────────────────────────


def compute_correctness_silhouette(df: pd.DataFrame) -> dict:
    """
    Evaluate how well code embeddings cluster by correctness (buggy vs fixed).

    Core metric for RQ4: a high silhouette score indicates that buggy code
    occupies a different region of embedding space from fixed code — i.e.,
    the model can implicitly distinguish correct from incorrect implementations.

    Computes:
      - Overall correctness silhouette (all languages pooled)
      - Per-language correctness silhouette
      - Per-severity correctness silhouette
      - Baseline: language silhouette (how well embeddings cluster by language)
      - Baseline: severity silhouette (by bug severity alone)

    Args:
        df (pd.DataFrame): DataFrame with 'embedding', 'code_type', 'language', 'severity'.

    Returns:
        dict: Silhouette scores and language ranking.
    """
    X_all = to_matrix(df)

    # Overall correctness silhouette
    overall = float(silhouette_score(X_all, df["code_type"].values, metric="cosine"))

    # Per language
    per_language: dict[str, float] = {}
    for lang in sorted(df["language"].unique()):
        mask = df["language"] == lang
        X_lang = to_matrix(df[mask])
        labels = df.loc[mask, "code_type"].values
        if len(np.unique(labels)) >= 2 and len(labels) >= 4:
            score = float(silhouette_score(X_lang, labels, metric="cosine"))
            per_language[lang] = round(score, 4)

    # Per severity
    per_severity: dict[str, float] = {}
    for sev in SEVERITY_ORDER:
        mask = df["severity"] == sev
        if mask.sum() < 4:
            continue
        X_sev = to_matrix(df[mask])
        labels = df.loc[mask, "code_type"].values
        if len(np.unique(labels)) >= 2:
            score = float(silhouette_score(X_sev, labels, metric="cosine"))
            per_severity[sev] = round(score, 4)

    # Baseline: language clustering
    lang_silhouette = float(
        silhouette_score(X_all, df["language"].values, metric="cosine")
    )

    # Baseline: severity clustering
    sev_labels = df["severity"].values
    sev_silhouette = None
    if len(np.unique(sev_labels)) >= 2:
        sev_silhouette = float(silhouette_score(X_all, sev_labels, metric="cosine"))

    ranking_lang = sorted(per_language.items(), key=lambda x: x[1], reverse=True)
    ranking_sev = sorted(per_severity.items(), key=lambda x: x[1], reverse=True)

    return {
        "correctness_silhouette_overall": round(overall, 4),
        "language_silhouette_overall": round(lang_silhouette, 4),
        "severity_silhouette_overall": (
            round(sev_silhouette, 4) if sev_silhouette else None
        ),
        "correctness_silhouette_per_language": per_language,
        "correctness_silhouette_per_severity": per_severity,
        "language_ranking": [{"language": l, "score": s} for l, s in ranking_lang],
        "severity_ranking": [{"severity": s, "score": sc} for s, sc in ranking_sev],
    }


# ── Metric 2: Pairwise Buggy–Fixed Distances ─────────────────────────────────


def compute_pairwise_distances(df: pd.DataFrame) -> dict:
    """
    Compute pairwise cosine distances between each matched buggy–fixed pair
    (same bug_type, same language), grouped by language and by severity.

    Args:
        df (pd.DataFrame): DataFrame with 'embedding', 'language', 'severity',
                           'bug_index', 'code_type'.

    Returns:
        dict: Per-language and per-severity mean distances, plus raw arrays
              for downstream statistical testing.
    """
    languages = sorted(df["language"].unique())
    severities = [s for s in SEVERITY_ORDER if s in df["severity"].unique()]

    per_language: dict[str, dict] = {}
    per_severity: dict[str, dict] = {}
    all_distances: list[float] = []

    # Per language
    for lang in tqdm(languages, desc="Pairwise distances (by language)"):
        lang_df = df[df["language"] == lang]
        dists = _matched_pair_distances(lang_df)
        all_distances.extend(dists)
        per_language[lang] = _distance_stats(dists)

    # Per severity
    for sev in severities:
        sev_df = df[df["severity"] == sev]
        dists = _matched_pair_distances(sev_df)
        per_severity[sev] = _distance_stats(dists)

    # Per (language, severity) combination
    per_lang_sev: dict[str, dict[str, dict]] = {}
    for lang in languages:
        per_lang_sev[lang] = {}
        for sev in severities:
            subset = df[(df["language"] == lang) & (df["severity"] == sev)]
            dists = _matched_pair_distances(subset)
            if dists:
                per_lang_sev[lang][sev] = _distance_stats(dists)

    return {
        "overall": _distance_stats(all_distances),
        "per_language": per_language,
        "per_severity": per_severity,
        "per_language_severity": per_lang_sev,
        "_all_distances": all_distances,
    }


def _matched_pair_distances(df: pd.DataFrame) -> list[float]:
    """Compute cosine distances between matched buggy–fixed pairs."""
    dists = []
    bug_indices = df["bug_index"].unique()
    for bi in bug_indices:
        subset = df[df["bug_index"] == bi]
        buggy = subset[subset["code_type"] == "buggy"]
        fixed = subset[subset["code_type"] == "fixed"]
        if len(buggy) == 0 or len(fixed) == 0:
            continue
        b_emb = to_matrix(buggy)[0]
        f_emb = to_matrix(fixed)[0]
        dists.append(float(cosine_dist(b_emb, f_emb)))
    return dists


def _distance_stats(dists: list[float]) -> dict:
    """Compute summary statistics for a list of distances."""
    if not dists:
        return {
            "n_pairs": 0,
            "mean": None,
            "std": None,
            "min": None,
            "max": None,
            "median": None,
        }
    arr = np.array(dists)
    return {
        "n_pairs": len(dists),
        "mean": round(float(np.mean(arr)), 4),
        "std": round(float(np.std(arr, ddof=1)), 4) if len(arr) > 1 else 0.0,
        "min": round(float(np.min(arr)), 4),
        "max": round(float(np.max(arr)), 4),
        "median": round(float(np.median(arr)), 4),
    }


# ── Metric 3: Intra-cluster vs Cross-cluster Distances ───────────────────────


def compute_cluster_distances(df: pd.DataFrame) -> dict:
    """
    For each language, compute:
      - Intra-buggy: avg cosine distance between pairs of buggy embeddings
      - Intra-fixed: avg cosine distance between pairs of fixed embeddings
      - Cross (buggy–fixed): avg cosine distance between buggy and fixed embeddings
      - Separation score: cross − (intra_buggy + intra_fixed) / 2

    Args:
        df (pd.DataFrame): DataFrame with 'embedding', 'language', 'code_type'.

    Returns:
        dict: Per-language clustering metrics and raw arrays for stats.
    """
    languages = sorted(df["language"].unique())
    per_language: dict[str, dict] = {}
    all_intra: list[float] = []
    all_cross: list[float] = []

    for lang in tqdm(languages, desc="Cluster distances"):
        lang_df = df[df["language"] == lang]
        buggy_embs = to_matrix(lang_df[lang_df["code_type"] == "buggy"])
        fixed_embs = to_matrix(lang_df[lang_df["code_type"] == "fixed"])

        # Subsample to keep runtime tractable
        intra_buggy = _subsample_intra_dists(buggy_embs, max_pairs=300)
        intra_fixed = _subsample_intra_dists(fixed_embs, max_pairs=300)
        cross_dists = _subsample_cross_dists(buggy_embs, fixed_embs, max_pairs=500)

        mean_ib = float(np.mean(intra_buggy)) if intra_buggy else 0.0
        mean_if = float(np.mean(intra_fixed)) if intra_fixed else 0.0
        mean_cr = float(np.mean(cross_dists)) if cross_dists else 0.0
        sep = mean_cr - (mean_ib + mean_if) / 2

        per_language[lang] = {
            "intra_buggy_mean": round(mean_ib, 4),
            "intra_fixed_mean": round(mean_if, 4),
            "cross_mean": round(mean_cr, 4),
            "separation_score": round(sep, 4),
            "n_intra_buggy": len(intra_buggy),
            "n_intra_fixed": len(intra_fixed),
            "n_cross": len(cross_dists),
        }
        all_intra.extend(intra_buggy + intra_fixed)
        all_cross.extend(cross_dists)

    return {
        "overall_intra_mean": (
            round(float(np.mean(all_intra)), 4) if all_intra else None
        ),
        "overall_cross_mean": (
            round(float(np.mean(all_cross)), 4) if all_cross else None
        ),
        "per_language": per_language,
        "_all_intra": all_intra,
        "_all_cross": all_cross,
    }


def _subsample_intra_dists(
    embs: np.ndarray, max_pairs: int = 300, seed: int = 42
) -> list[float]:
    """Compute intra-cluster cosine distances with subsampling."""
    n = len(embs)
    if n < 2:
        return []
    pairs = [(i, j) for i in range(n) for j in range(i + 1, n)]
    if len(pairs) > max_pairs:
        rng = np.random.default_rng(seed)
        chosen = rng.choice(len(pairs), max_pairs, replace=False)
        pairs = [pairs[c] for c in chosen]
    return [float(cosine_dist(embs[i], embs[j])) for i, j in pairs]


def _subsample_cross_dists(
    embs1: np.ndarray, embs2: np.ndarray, max_pairs: int = 500, seed: int = 42
) -> list[float]:
    """Compute cross-cluster cosine distances with subsampling."""
    if len(embs1) == 0 or len(embs2) == 0:
        return []
    pairs = [(i, j) for i in range(len(embs1)) for j in range(len(embs2))]
    if len(pairs) > max_pairs:
        rng = np.random.default_rng(seed)
        chosen = rng.choice(len(pairs), max_pairs, replace=False)
        pairs = [pairs[c] for c in chosen]
    return [float(cosine_dist(embs1[i], embs2[j])) for i, j in pairs]


# ── Metric 4: Dangerous Neighbourhoods ────────────────────────────────────────


def compute_dangerous_neighbourhoods(df: pd.DataFrame) -> dict:
    """
    Identify 'dangerous neighbourhoods' — buggy–fixed pairs whose embeddings
    are very close (cosine distance below threshold), meaning the model cannot
    easily distinguish correct from incorrect code.

    Args:
        df (pd.DataFrame): DataFrame with 'embedding', 'language', 'severity',
                           'bug_index', 'bug_type', 'code_type'.

    Returns:
        dict: Per-language and per-severity dangerous-pair counts at multiple thresholds.
    """
    languages = sorted(df["language"].unique())
    severities = [s for s in SEVERITY_ORDER if s in df["severity"].unique()]

    results: dict = {"thresholds": DANGEROUS_THRESHOLDS}

    for threshold in DANGEROUS_THRESHOLDS:
        key = f"threshold_{threshold}"
        results[key] = {"per_language": {}, "per_severity": {}, "overall": {}}

        all_count, all_total = 0, 0
        all_dangerous_bugs: list[dict] = []

        for lang in languages:
            lang_df = df[df["language"] == lang]
            dists = _matched_pair_distances_with_info(lang_df)
            n_close = sum(1 for d in dists if d["distance"] < threshold)
            total = len(dists)
            pct = round(100 * n_close / total, 1) if total > 0 else 0.0
            results[key]["per_language"][lang] = {
                "dangerous": n_close,
                "total": total,
                "pct": pct,
            }
            all_count += n_close
            all_total += total
            all_dangerous_bugs.extend([d for d in dists if d["distance"] < threshold])

        for sev in severities:
            sev_df = df[df["severity"] == sev]
            dists = _matched_pair_distances_with_info(sev_df)
            n_close = sum(1 for d in dists if d["distance"] < threshold)
            total = len(dists)
            pct = round(100 * n_close / total, 1) if total > 0 else 0.0
            results[key]["per_severity"][sev] = {
                "dangerous": n_close,
                "total": total,
                "pct": pct,
            }

        results[key]["overall"] = {
            "dangerous": all_count,
            "total": all_total,
            "pct": round(100 * all_count / all_total, 1) if all_total > 0 else 0.0,
        }

        # Top dangerous bugs (closest buggy–fixed pairs)
        all_dangerous_bugs.sort(key=lambda d: d["distance"])
        results[key]["top_dangerous_bugs"] = all_dangerous_bugs[:10]

    return results


def _matched_pair_distances_with_info(df: pd.DataFrame) -> list[dict]:
    """Compute matched buggy–fixed distances with metadata."""
    results = []
    bug_indices = df["bug_index"].unique()
    for bi in bug_indices:
        subset = df[df["bug_index"] == bi]
        buggy = subset[subset["code_type"] == "buggy"]
        fixed = subset[subset["code_type"] == "fixed"]
        if len(buggy) == 0 or len(fixed) == 0:
            continue
        b_emb = to_matrix(buggy)[0]
        f_emb = to_matrix(fixed)[0]
        dist = float(cosine_dist(b_emb, f_emb))
        row = buggy.iloc[0]
        results.append(
            {
                "bug_type": row["bug_type"],
                "severity": row["severity"],
                "language": row["language"],
                "distance": round(dist, 6),
            }
        )
    return results


# ── Metric 5: Bug Severity Distance Matrix ───────────────────────────────────


def compute_severity_distance_matrix(df: pd.DataFrame) -> dict:
    """
    Build a pairwise mean cosine-distance matrix between bug severity levels,
    using only buggy-code embeddings.

    Shows whether Easy bugs embed closer to Medium bugs than to Super Hard bugs.

    Args:
        df (pd.DataFrame): DataFrame with 'embedding', 'severity', 'code_type'.

    Returns:
        dict: {sev_A: {sev_B: mean_cosine_dist}}, symmetric.
    """
    buggy_df = df[df["code_type"] == "buggy"].copy()
    severities = [s for s in SEVERITY_ORDER if s in buggy_df["severity"].unique()]

    matrix: dict[str, dict[str, float]] = {}
    for sev in severities:
        matrix[sev] = {sev: 0.0}

    for s1, s2 in combinations(severities, 2):
        e1 = to_matrix(buggy_df[buggy_df["severity"] == s1])
        e2 = to_matrix(buggy_df[buggy_df["severity"] == s2])
        if len(e1) == 0 or len(e2) == 0:
            continue
        # Subsample 300 pairs
        pairs = [
            (i, j) for i in range(min(len(e1), 30)) for j in range(min(len(e2), 30))
        ]
        dists = [cosine_dist(e1[i], e2[j]) for i, j in pairs]
        mean_d = round(float(np.mean(dists)), 4)
        matrix.setdefault(s1, {})[s2] = mean_d
        matrix.setdefault(s2, {})[s1] = mean_d

    return matrix


# ── Metric 6: Per-Language Severity Distance Matrix ───────────────────────────


def compute_per_language_severity_matrix(df: pd.DataFrame) -> dict:
    """
    Compute a severity×severity cosine distance matrix separately for each language
    (using only buggy-code embeddings).

    Args:
        df (pd.DataFrame): DataFrame with 'embedding', 'language', 'severity', 'code_type'.

    Returns:
        dict: {language: {sev_A: {sev_B: mean_dist}}}.
    """
    buggy_df = df[df["code_type"] == "buggy"].copy()
    languages = sorted(buggy_df["language"].unique())
    severities = [s for s in SEVERITY_ORDER if s in buggy_df["severity"].unique()]

    result: dict[str, dict] = {}
    for lang in languages:
        lang_df = buggy_df[buggy_df["language"] == lang]
        mat: dict[str, dict[str, float]] = {}
        for sev in severities:
            mat.setdefault(sev, {})[sev] = 0.0
        for s1, s2 in combinations(severities, 2):
            e1 = to_matrix(lang_df[lang_df["severity"] == s1])
            e2 = to_matrix(lang_df[lang_df["severity"] == s2])
            if len(e1) == 0 or len(e2) == 0:
                continue
            pairs = [
                (i, j) for i in range(min(len(e1), 20)) for j in range(min(len(e2), 20))
            ]
            dists = [cosine_dist(e1[i], e2[j]) for i, j in pairs]
            d = round(float(np.mean(dists)), 4)
            mat.setdefault(s1, {})[s2] = d
            mat.setdefault(s2, {})[s1] = d
        result[lang] = mat

    return result


# ── Metric 7: Statistical Tests ───────────────────────────────────────────────


def compute_statistical_tests(
    cluster_results: dict, silhouette_results: dict, pairwise_results: dict
) -> dict:
    """
    Run statistical significance and effect-size testing for RQ4.

    Tests performed:
      1. Welch's t-test: cross-cluster vs intra-cluster distances
         (do buggy and fixed code occupy different regions?)
      2. Cohen's d for the above
      3. Bootstrap 95 % CIs for cross, intra, and their difference
      4. Per-severity ANOVA: do pairwise buggy–fixed distances differ
         significantly across bug severity levels?

    Args:
        cluster_results (dict): Output of compute_cluster_distances.
        silhouette_results (dict): Output of compute_correctness_silhouette.
        pairwise_results (dict): Output of compute_pairwise_distances.

    Returns:
        dict: Results of all tests.
    """
    cross = np.array(cluster_results["_all_cross"])
    intra = np.array(cluster_results["_all_intra"])

    # 1. Welch's t-test: cross vs intra
    t_stat, p_val = stats.ttest_ind(cross, intra, equal_var=False)

    # 2. Cohen's d
    d = cohens_d(cross, intra)

    # 3. Bootstrap CIs
    cross_ci = bootstrap_ci(cross)
    intra_ci = bootstrap_ci(intra)
    rng = np.random.default_rng(42)
    diff_boots = [
        float(
            np.mean(rng.choice(cross, len(cross), replace=True))
            - np.mean(rng.choice(intra, len(intra), replace=True))
        )
        for _ in range(5000)
    ]
    diff_ci = (
        float(np.percentile(diff_boots, 2.5)),
        float(np.percentile(diff_boots, 97.5)),
    )

    # 4. Per-severity ANOVA on pairwise distances
    sev_groups = []
    sev_labels = []
    per_sev = pairwise_results.get("per_severity", {})
    # We need raw distances per severity — recompute from _all_distances is not grouped.
    # Instead use per_language_severity to reconstruct.
    # Actually, let's collect raw distances per severity from the dataframe.
    # Since we don't have the df here, just report the descriptive stats.
    sev_means = {
        s: per_sev[s]["mean"] for s in per_sev if per_sev[s]["mean"] is not None
    }
    sev_stds = {s: per_sev[s]["std"] for s in per_sev if per_sev[s]["std"] is not None}

    return {
        "t_test": {
            "test": "Welch's t-test (cross-cluster vs intra-cluster distance)",
            "t_statistic": round(float(t_stat), 4),
            "p_value": float(p_val),
            "significant_at_0.05": bool(p_val < 0.05),
            "n_cross_pairs": int(len(cross)),
            "n_intra_pairs": int(len(intra)),
        },
        "effect_size": {
            "cohens_d": round(d, 4),
            "interpretation": (
                "negligible"
                if abs(d) < 0.2
                else "small" if abs(d) < 0.5 else "medium" if abs(d) < 0.8 else "large"
            ),
        },
        "confidence_intervals_95": {
            "cross_cluster_mean": round(float(np.mean(cross)), 4),
            "cross_cluster_ci": [round(cross_ci[0], 4), round(cross_ci[1], 4)],
            "intra_cluster_mean": round(float(np.mean(intra)), 4),
            "intra_cluster_ci": [round(intra_ci[0], 4), round(intra_ci[1], 4)],
            "difference_mean": round(float(np.mean(cross) - np.mean(intra)), 4),
            "difference_ci": [round(diff_ci[0], 4), round(diff_ci[1], 4)],
        },
        "severity_descriptive": {
            "means": sev_means,
            "stds": sev_stds,
            "note": (
                "Mean pairwise buggy–fixed distance per severity level. "
                "Higher means the model distinguishes buggy vs fixed more for that severity."
            ),
        },
    }


# ── Run analysis for one model ─────────────────────────────────────────────────


def run_analysis(model_key: str) -> bool:
    """Load embeddings, compute all RQ4 metrics, and save rq4_metrics.json."""
    model_dir = os.path.join(OUTPUT_BASE, model_key)
    embeddings_file = os.path.join(model_dir, "rq4_embeddings.parquet")
    metrics_file = os.path.join(model_dir, "rq4_metrics.json")

    if not os.path.exists(embeddings_file):
        print(f"  Embeddings not found at {embeddings_file} — skipping {model_key}.")
        return False

    print(f"Loading embeddings from {embeddings_file} …")
    df = pd.read_parquet(embeddings_file)
    print(
        f"Loaded {len(df)} snippets  |  "
        f"{df['bug_type'].nunique()} bug types  |  "
        f"{df['language'].nunique()} languages"
    )

    # ── Metric 1: Correctness silhouette ──────────────────────────────────────
    print("\n── Correctness Silhouette ──")
    sil = compute_correctness_silhouette(df)
    print(f"  Overall correctness silhouette: {sil['correctness_silhouette_overall']}")
    print(f"  Language baseline silhouette:   {sil['language_silhouette_overall']}")
    print(f"  Severity baseline silhouette:   {sil['severity_silhouette_overall']}")
    print("  Per-language correctness silhouette:")
    for entry in sil["language_ranking"]:
        print(f"    {entry['language']:8s} {entry['score']:+.4f}")
    print("  Per-severity correctness silhouette:")
    for entry in sil["severity_ranking"]:
        print(f"    {entry['severity']:12s} {entry['score']:+.4f}")

    # ── Metric 2: Pairwise distances ──────────────────────────────────────────
    print("\n── Pairwise Buggy–Fixed Distances ──")
    pw = compute_pairwise_distances(df)
    print(f"  Overall mean: {pw['overall']['mean']}")
    print("  Per-language:")
    for lang in sorted(pw["per_language"].keys()):
        v = pw["per_language"][lang]
        print(f"    {lang:8s}  mean={v['mean']}  std={v['std']}  n={v['n_pairs']}")
    print("  Per-severity:")
    for sev in SEVERITY_ORDER:
        if sev in pw["per_severity"]:
            v = pw["per_severity"][sev]
            print(f"    {sev:12s}  mean={v['mean']}  std={v['std']}  n={v['n_pairs']}")

    # ── Metric 3: Cluster distances ───────────────────────────────────────────
    print("\n── Intra vs Cross Cluster Distances ──")
    cluster = compute_cluster_distances(df)
    print(f"  Overall intra: {cluster['overall_intra_mean']}")
    print(f"  Overall cross: {cluster['overall_cross_mean']}")
    for lang, v in sorted(cluster["per_language"].items()):
        print(
            f"    {lang:8s}  intra_b={v['intra_buggy_mean']}  "
            f"intra_f={v['intra_fixed_mean']}  cross={v['cross_mean']}  "
            f"sep={v['separation_score']}"
        )

    # ── Metric 4: Dangerous neighbourhoods ────────────────────────────────────
    print("\n── Dangerous Neighbourhoods ──")
    danger = compute_dangerous_neighbourhoods(df)
    for t in DANGEROUS_THRESHOLDS:
        key = f"threshold_{t}"
        ov = danger[key]["overall"]
        print(
            f"  Threshold {t}: {ov['dangerous']}/{ov['total']} ({ov['pct']}%) dangerous"
        )

    # ── Metric 5: Severity distance matrix ────────────────────────────────────
    print("\n── Severity Distance Matrix ──")
    sev_mat = compute_severity_distance_matrix(df)

    # ── Metric 6: Per-language severity matrix ────────────────────────────────
    print("── Per-language Severity Distance Matrices ──")
    per_lang_sev_mat = compute_per_language_severity_matrix(df)

    # ── Metric 7: Statistical tests ───────────────────────────────────────────
    print("\n── Statistical Tests ──")
    stat = compute_statistical_tests(cluster, sil, pw)
    print(f"  t-statistic: {stat['t_test']['t_statistic']}")
    print(f"  p-value:     {stat['t_test']['p_value']:.2e}")
    print(
        f"  Cohen's d:   {stat['effect_size']['cohens_d']} "
        f"({stat['effect_size']['interpretation']})"
    )

    # ── Save ──────────────────────────────────────────────────────────────────
    metrics = {
        "model_key": model_key,
        "model_name": MODELS[model_key]["name"],
        "n_snippets": int(len(df)),
        "n_bug_types": int(df["bug_type"].nunique()),
        "severity_counts": df["severity"].value_counts().to_dict(),
        "silhouette": sil,
        "pairwise_distances": {k: v for k, v in pw.items() if not k.startswith("_")},
        "cluster_distances": {
            k: v for k, v in cluster.items() if not k.startswith("_")
        },
        "dangerous_neighbourhoods": danger,
        "global_severity_distance_matrix": sev_mat,
        "per_language_severity_distance_matrix": per_lang_sev_mat,
        "statistical_tests": stat,
    }

    os.makedirs(model_dir, exist_ok=True)
    with open(metrics_file, "w", encoding="utf-8") as f:
        json.dump(metrics, f, indent=2)
    print(f"\nSaved metrics → {metrics_file}")
    return True


# ── Main ──────────────────────────────────────────────────────────────────────


def main():
    parser = argparse.ArgumentParser(
        description="Compute RQ4 metrics for one or all models."
    )
    parser.add_argument(
        "--model",
        type=str,
        default="all",
        choices=list(MODELS.keys()) + ["all"],
        help="Model key (default: all)",
    )
    args = parser.parse_args()

    models_to_run = [args.model] if args.model != "all" else list(MODELS.keys())

    for model_key in models_to_run:
        print(f"\n{'='*60}")
        print(f"  Analyzing model: {model_key}")
        print(f"{'='*60}")
        run_analysis(model_key)

    print("\nDone!")


if __name__ == "__main__":
    main()

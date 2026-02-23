"""
RQ2: Framework-Driven Dialects — Metric Computation & Statistical Analysis

Loads the embeddings Parquet produced by rq2_embedding.py and computes:
  1. Framework Silhouette Score
  2. Cross-Framework Distance (per pattern)
  3. Paired t-tests (intra- vs cross-framework distances)
  4. Cohen's d effect sizes
  5. 95% Confidence Intervals (bootstrap)

Output: results/rq2/rq2_metrics.json
"""

import json
import os
import sys
from itertools import combinations

import numpy as np
import pandas as pd
from scipy import stats
from scipy.spatial.distance import cosine as cosine_dist
from sklearn.metrics import silhouette_score

# ── Paths ─────────────────────────────────────────────────────────────────────
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
PROJECT_ROOT = os.path.dirname(os.path.dirname(SCRIPT_DIR))  # code/rq2 -> code -> project root
OUTPUT_DIR = os.path.join(PROJECT_ROOT, "results/rq2")
EMBEDDINGS_FILE = os.path.join(OUTPUT_DIR, "rq2_embeddings.parquet")
METRICS_FILE = os.path.join(OUTPUT_DIR, "rq2_metrics.json")


# ── Helpers ───────────────────────────────────────────────────────────────────

def embeddings_to_matrix(df: pd.DataFrame) -> np.ndarray:
    """Convert the embedding column (list of floats) to a 2-D numpy matrix."""
    return np.vstack(df["embedding"].values)


def cohens_d(group1: np.ndarray, group2: np.ndarray) -> float:
    """Calculate Cohen's d for two independent samples."""
    n1, n2 = len(group1), len(group2)
    var1, var2 = np.var(group1, ddof=1), np.var(group2, ddof=1)
    pooled_std = np.sqrt(((n1 - 1) * var1 + (n2 - 1) * var2) / (n1 + n2 - 2))
    if pooled_std == 0:
        return 0.0
    return (np.mean(group1) - np.mean(group2)) / pooled_std


def bootstrap_ci(data: np.ndarray, statistic=np.mean, n_bootstrap=5000,
                 ci=0.95, seed=42) -> tuple:
    """Compute bootstrap confidence interval for a statistic."""
    rng = np.random.default_rng(seed)
    boot_stats = []
    for _ in range(n_bootstrap):
        sample = rng.choice(data, size=len(data), replace=True)
        boot_stats.append(statistic(sample))
    boot_stats = np.array(boot_stats)
    alpha = 1 - ci
    lower = np.percentile(boot_stats, 100 * alpha / 2)
    upper = np.percentile(boot_stats, 100 * (1 - alpha / 2))
    return float(lower), float(upper)


# ── Metric 1: Framework Silhouette Score ──────────────────────────────────────

def compute_framework_silhouette(df: pd.DataFrame) -> dict:
    """
    Compute silhouette score when grouping embeddings by framework.
    Also compute per-language silhouette (grouping by framework within each language).
    """
    X = embeddings_to_matrix(df)
    labels = df["framework"].values

    overall_score = silhouette_score(X, labels, metric="cosine")

    # Per-language breakdown
    per_language = {}
    for lang in sorted(df["language"].unique()):
        mask = df["language"] == lang
        X_lang = X[mask]
        labels_lang = labels[mask]
        if len(np.unique(labels_lang)) >= 2:
            score = silhouette_score(X_lang, labels_lang, metric="cosine")
            per_language[lang] = round(score, 4)

    # Silhouette by language label (for comparison)
    lang_silhouette = silhouette_score(X, df["language"].values, metric="cosine")

    return {
        "framework_silhouette_overall": round(overall_score, 4),
        "language_silhouette_overall": round(lang_silhouette, 4),
        "framework_silhouette_per_language": per_language,
    }


# ── Metric 2: Cross-Framework Distance ───────────────────────────────────────

def compute_cross_framework_distances(df: pd.DataFrame) -> dict:
    """
    For each pattern, compute the average cosine distance between
    embeddings of the same pattern implemented with different frameworks.
    Also compute intra-framework distances for comparison.
    """
    patterns = sorted(df["pattern"].unique())
    frameworks = sorted(df["framework"].unique())

    cross_distances_by_pattern = {}
    intra_distances_by_pattern = {}
    all_cross_distances = []
    all_intra_distances = []

    # Per-pattern, per-framework-pair distance matrix
    cross_fw_pair_distances = {}

    for pattern in patterns:
        pat_df = df[df["pattern"] == pattern]
        cross_dists = []
        intra_dists = []

        fw_pair_dists = {}

        for fw1, fw2 in combinations(frameworks, 2):
            embs1 = embeddings_to_matrix(pat_df[pat_df["framework"] == fw1])
            embs2 = embeddings_to_matrix(pat_df[pat_df["framework"] == fw2])

            if len(embs1) == 0 or len(embs2) == 0:
                continue

            pair_dists = []
            for e1 in embs1:
                for e2 in embs2:
                    d = cosine_dist(e1, e2)
                    pair_dists.append(d)

            avg_d = float(np.mean(pair_dists))
            fw_pair_key = f"{fw1} vs {fw2}"
            fw_pair_dists[fw_pair_key] = round(avg_d, 4)
            cross_dists.extend(pair_dists)

        # Intra-framework distances (same framework, same pattern)
        for fw in frameworks:
            fw_embs = embeddings_to_matrix(pat_df[pat_df["framework"] == fw])
            if len(fw_embs) < 2:
                continue
            for i in range(len(fw_embs)):
                for j in range(i + 1, len(fw_embs)):
                    d = cosine_dist(fw_embs[i], fw_embs[j])
                    intra_dists.append(d)

        cross_distances_by_pattern[pattern] = round(float(np.mean(cross_dists)), 4) if cross_dists else None
        intra_distances_by_pattern[pattern] = round(float(np.mean(intra_dists)), 4) if intra_dists else None
        cross_fw_pair_distances[pattern] = fw_pair_dists

        all_cross_distances.extend(cross_dists)
        all_intra_distances.extend(intra_dists)

    return {
        "cross_framework_distance_by_pattern": cross_distances_by_pattern,
        "intra_framework_distance_by_pattern": intra_distances_by_pattern,
        "cross_framework_distance_overall": round(float(np.mean(all_cross_distances)), 4),
        "intra_framework_distance_overall": round(float(np.mean(all_intra_distances)), 4),
        "cross_fw_pair_distances_by_pattern": cross_fw_pair_distances,
        "_all_cross_distances": all_cross_distances,
        "_all_intra_distances": all_intra_distances,
    }


# ── Step 3: Statistical Analysis ─────────────────────────────────────────────

def compute_statistical_tests(distance_results: dict) -> dict:
    """
    Paired t-test comparing intra- vs cross-framework distances,
    Cohen's d effect size, and 95% bootstrap confidence intervals.
    """
    cross = np.array(distance_results["_all_cross_distances"])
    intra = np.array(distance_results["_all_intra_distances"])

    # --- Paired t-test (independent samples, as the groups differ in size) ---
    t_stat, p_value = stats.ttest_ind(cross, intra, equal_var=False)

    # --- Cohen's d ---
    d = cohens_d(cross, intra)

    # --- 95% CIs ---
    cross_ci = bootstrap_ci(cross)
    intra_ci = bootstrap_ci(intra)

    # CI for the difference
    # Bootstrap the difference of means
    rng = np.random.default_rng(42)
    diff_boots = []
    for _ in range(5000):
        s_cross = rng.choice(cross, size=len(cross), replace=True)
        s_intra = rng.choice(intra, size=len(intra), replace=True)
        diff_boots.append(np.mean(s_cross) - np.mean(s_intra))
    diff_boots = np.array(diff_boots)
    diff_ci = (float(np.percentile(diff_boots, 2.5)),
               float(np.percentile(diff_boots, 97.5)))

    return {
        "t_test": {
            "test": "Welch's t-test (cross vs intra framework distance)",
            "t_statistic": round(float(t_stat), 4),
            "p_value": float(p_value),
            "significant_at_0.05": bool(p_value < 0.05),
            "n_cross": int(len(cross)),
            "n_intra": int(len(intra)),
        },
        "effect_size": {
            "cohens_d": round(float(d), 4),
            "interpretation": (
                "negligible" if abs(d) < 0.2 else
                "small" if abs(d) < 0.5 else
                "medium" if abs(d) < 0.8 else
                "large"
            ),
        },
        "confidence_intervals_95": {
            "cross_framework_mean": round(float(np.mean(cross)), 4),
            "cross_framework_ci": [round(cross_ci[0], 4), round(cross_ci[1], 4)],
            "intra_framework_mean": round(float(np.mean(intra)), 4),
            "intra_framework_ci": [round(intra_ci[0], 4), round(intra_ci[1], 4)],
            "difference_mean": round(float(np.mean(cross) - np.mean(intra)), 4),
            "difference_ci": [round(diff_ci[0], 4), round(diff_ci[1], 4)],
        },
    }


# ── Main ──────────────────────────────────────────────────────────────────────

def main():
    # Load embeddings
    if not os.path.exists(EMBEDDINGS_FILE):
        print(f"ERROR: Embeddings file not found: {EMBEDDINGS_FILE}")
        print("Run rq2_embedding.py first.")
        sys.exit(1)

    print(f"Loading embeddings from {EMBEDDINGS_FILE}...")
    df = pd.read_parquet(EMBEDDINGS_FILE)
    print(f"Loaded {len(df)} snippets.")

    # 1. Framework Silhouette Score
    print("\n── Computing Framework Silhouette Score ──")
    silhouette_results = compute_framework_silhouette(df)
    print(f"  Framework Silhouette (overall): {silhouette_results['framework_silhouette_overall']}")
    print(f"  Language  Silhouette (overall): {silhouette_results['language_silhouette_overall']}")
    for lang, score in silhouette_results["framework_silhouette_per_language"].items():
        print(f"    {lang}: {score}")

    # 2. Cross-Framework Distance
    print("\n── Computing Cross-Framework Distances ──")
    distance_results = compute_cross_framework_distances(df)
    print(f"  Cross-framework distance (overall): {distance_results['cross_framework_distance_overall']}")
    print(f"  Intra-framework distance (overall): {distance_results['intra_framework_distance_overall']}")
    for pattern, dist in distance_results["cross_framework_distance_by_pattern"].items():
        intra = distance_results["intra_framework_distance_by_pattern"].get(pattern)
        print(f"    {pattern}: cross={dist}, intra={intra}")

    # 3. Statistical Analysis
    print("\n── Computing Statistical Tests ──")
    stat_results = compute_statistical_tests(distance_results)
    print(f"  t-statistic: {stat_results['t_test']['t_statistic']}")
    print(f"  p-value:     {stat_results['t_test']['p_value']:.2e}")
    print(f"  Significant: {stat_results['t_test']['significant_at_0.05']}")
    print(f"  Cohen's d:   {stat_results['effect_size']['cohens_d']} ({stat_results['effect_size']['interpretation']})")
    ci = stat_results["confidence_intervals_95"]
    print(f"  Cross mean:  {ci['cross_framework_mean']} {ci['cross_framework_ci']}")
    print(f"  Intra mean:  {ci['intra_framework_mean']} {ci['intra_framework_ci']}")
    print(f"  Diff:        {ci['difference_mean']} {ci['difference_ci']}")

    # 4. Assemble and save all metrics
    metrics = {
        "silhouette": silhouette_results,
        "distances": {
            k: v for k, v in distance_results.items()
            if not k.startswith("_")  # exclude raw arrays
        },
        "statistical_tests": stat_results,
    }

    os.makedirs(OUTPUT_DIR, exist_ok=True)
    with open(METRICS_FILE, "w", encoding="utf-8") as f:
        json.dump(metrics, f, indent=2)
    print(f"\nSaved metrics to {METRICS_FILE}")


if __name__ == "__main__":
    main()

"""
RQ2: Framework-Driven Dialects — Metric Computation & Statistical Analysis

Loads the embeddings Parquet produced by 1_embedding.py and computes:
  1. Framework Silhouette Score
  2. Cross-Framework Distance (per pattern)
  3. Paired t-tests (intra- vs cross-framework distances)
  4. Cohen's d effect sizes
  5. 95% Confidence Intervals (bootstrap)

Usage:
    python 2_analysis.py                    # all models
    python 2_analysis.py --model unixcoder  # single model

Output: results/rq2/{model_key}/rq2_metrics.json
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

# ── Paths ─────────────────────────────────────────────────────────────────────
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
CODE_DIR = os.path.dirname(SCRIPT_DIR)
sys.path.insert(0, CODE_DIR)
from embedding import MODELS

PROJECT_ROOT = os.path.dirname(CODE_DIR)
OUTPUT_BASE = os.path.join(PROJECT_ROOT, "results/rq2")


# ── Helpers ───────────────────────────────────────────────────────────────────

def embeddings_to_matrix(df: pd.DataFrame) -> np.ndarray:
    """Convert the embedding column (list of floats) to a 2-D numpy matrix."""
    return np.vstack(df["embedding"].values)


def cohens_d(group1: np.ndarray, group2: np.ndarray) -> float:
    """
    Calculate Cohen's d effect size for two independent samples.
    
    Cohen's d measures the standardized difference between two means.
    Formula: (mean1 - mean2) / pooled_standard_deviation

    Args:
        group1 (np.ndarray): First sample of numeric values.
        group2 (np.ndarray): Second sample of numeric values.

    Returns:
        float: The calculated Cohen's d value.
    """
    n1, n2 = len(group1), len(group2)
    var1, var2 = np.var(group1, ddof=1), np.var(group2, ddof=1)
    pooled_std = np.sqrt(((n1 - 1) * var1 + (n2 - 1) * var2) / (n1 + n2 - 2))
    if pooled_std == 0:
        return 0.0
    return (np.mean(group1) - np.mean(group2)) / pooled_std


def bootstrap_ci(data: np.ndarray, statistic=np.mean, n_bootstrap: int = 5000,
                 ci: float = 0.95, seed: int = 42) -> tuple:
    """
    Compute bootstrap confidence interval for a statistic (default: mean).
    
    Bootstrapping involves taking repeated random samples (with replacement)
    from the data to estimate the sampling distribution of the statistic.

    Args:
        data (np.ndarray): The 1-D array of sample data.
        statistic (callable): Function to compute the statistic (e.g., np.mean).
        n_bootstrap (int): Number of bootstrap resamples to generate.
        ci (float): Confidence interval level (e.g., 0.95 for 95%).
        seed (int): Random seed for reproducibility.

    Returns:
        tuple[float, float]: The lower and upper bounds of the confidence interval.
    """
    rng = np.random.default_rng(seed)
    boot_stats = []
    for _ in range(n_bootstrap):
        # Sample with replacement
        sample = rng.choice(data, size=len(data), replace=True)
        boot_stats.append(statistic(sample))
    
    boot_stats = np.array(boot_stats)
    alpha = 1 - ci
    lower = np.percentile(boot_stats, 100 * (alpha / 2))
    upper = np.percentile(boot_stats, 100 * (1 - (alpha / 2)))
    return float(lower), float(upper)


# ── Metric 1: Framework Silhouette Score ──────────────────────────────────────

def compute_framework_silhouette(df: pd.DataFrame) -> dict:
    """
    Compute the silhouette score evaluating how well embeddings cluster by framework.
    
    The Silhouette Score measures how similar an object is to its own cluster 
    (cohesion) compared to other clusters (separation). A higher score indicates 
    that code snippets from the same framework map to closer embeddings than those 
    from different frameworks.
    
    Computes:
      - Overall framework silhouette score
      - Overall language silhouette score (as a baseline comparison)
      - Framework silhouette score isolated per each language.

    Args:
        df (pd.DataFrame): DataFrame containing 'embedding', 'framework', and 'language' columns.

    Returns:
        dict: A dictionary containing the computed silhouette scores.
    """
    X = embeddings_to_matrix(df)
    labels = df["framework"].values

    # Overall silhouette across all frameworks unconditionally
    overall_score = silhouette_score(X, labels, metric="cosine")

    # Per-language breakdown: silhouette score evaluating how well frameworks cluster *within* a language
    per_language = {}
    for lang in sorted(df["language"].unique()):
        mask = df["language"] == lang
        X_lang = X[mask]
        labels_lang = labels[mask]
        # Silhouette score requires at least 2 distinct clusters
        if len(np.unique(labels_lang)) >= 2:
            score = silhouette_score(X_lang, labels_lang, metric="cosine")
            per_language[lang] = round(score, 4)

    # Baseline comparison: how well embeddings cluster indiscriminately by language
    lang_silhouette = silhouette_score(X, df["language"].values, metric="cosine")

    return {
        "framework_silhouette_overall": round(overall_score, 4),
        "language_silhouette_overall": round(lang_silhouette, 4),
        "framework_silhouette_per_language": per_language,
    }


# ── Metric 2: Cross-Framework Distance ───────────────────────────────────────

def compute_cross_framework_distances(df: pd.DataFrame) -> dict:
    """
    Compute pairwise cosine distances between embeddings of identical software patterns
    implemented in different frameworks (cross-framework) versus the same framework (intra-framework).

    This isolates the semantic separation caused purely by framework choice, independent 
    of the underlying software pattern being implemented.

    Args:
        df (pd.DataFrame): Input dataframe containing 'embedding', 'pattern', and 'framework'.

    Returns:
        dict: A dictionary of aggregated distances and the raw distance distributions
              for downstream statistical testing.
    """
    patterns = sorted(df["pattern"].unique())
    frameworks = sorted(df["framework"].unique())

    cross_distances_by_pattern = {}
    intra_distances_by_pattern = {}
    
    # Store all raw distance floats for eventual grand-mean and stats tests
    all_cross_distances = []
    all_intra_distances = []
    
    # Granular output: tracking distances grouped by pattern AND the specific framework pair
    cross_fw_pair_distances = {}

    for pattern in patterns:
        pat_df = df[df["pattern"] == pattern]
        cross_dists = []
        intra_dists = []
        fw_pair_dists = {}

        # --- CROSS-FRAMEWORK DISTANCES ---
        # Compare implementations of this pattern across all unique pairs of frameworks
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

            # Record average distance for this specific pair of frameworks on this pattern
            avg_d = float(np.mean(pair_dists))
            fw_pair_key = f"{fw1} vs {fw2}"
            fw_pair_dists[fw_pair_key] = round(avg_d, 4)
            cross_dists.extend(pair_dists)

        # --- INTRA-FRAMEWORK DISTANCES ---
        # Compare implementations of this pattern from within the same framework
        for fw in frameworks:
            fw_embs = embeddings_to_matrix(pat_df[pat_df["framework"] == fw])
            if len(fw_embs) < 2:
                continue
            # Pairwise distance of all variations within the same framework
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
        "_all_cross_distances": all_cross_distances,  # Hidden key for stat tests
        "_all_intra_distances": all_intra_distances,  # Hidden key for stat tests
    }


# ── Step 3: Statistical Analysis ─────────────────────────────────────────────

def compute_statistical_tests(distance_results: dict) -> dict:
    """
    Run statistical significance and effect size testing comparing the raw distributions
    of cross-framework distances versus intra-framework distances.

    Includes:
      - Welch's t-test (handles heterogeneous variance and group sizes)
      - Cohen's d (quantises effect size into negligible/small/medium/large)
      - 95% Bootstrap Confidence Intervals for the means and their difference.

    Args:
        distance_results (dict): Dictionary output from compute_cross_framework_distances.

    Returns:
        dict: A dictionary of statistical test results.
    """
    cross = np.array(distance_results["_all_cross_distances"])
    intra = np.array(distance_results["_all_intra_distances"])

    # Welch's t-test (assumes unequal variance, robust for different N)
    t_stat, p_value = stats.ttest_ind(cross, intra, equal_var=False)
    
    # Effect size magnitude
    d = cohens_d(cross, intra)

    # 95% Confidence Intervals mapped over a bootstrap of the underlying distribution
    cross_ci = bootstrap_ci(cross)
    intra_ci = bootstrap_ci(intra)

    # Bootstrap the difference between means
    rng = np.random.default_rng(42)
    diff_boots = []
    for _ in range(5000):
        s_cross = rng.choice(cross, size=len(cross), replace=True)
        s_intra = rng.choice(intra, size=len(intra), replace=True)
        diff_boots.append(np.mean(s_cross) - np.mean(s_intra))
    diff_boots = np.array(diff_boots)
    # 2.5th and 97.5th percentiles yield the 95% CI boundries
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


# ── Run analysis for one model ───────────────────────────────────────────────

def run_analysis(model_key: str):
    """Run all analysis for a single model."""
    model_dir = os.path.join(OUTPUT_BASE, model_key)
    embeddings_file = os.path.join(model_dir, "rq2_embeddings.parquet")
    metrics_file = os.path.join(model_dir, "rq2_metrics.json")

    if not os.path.exists(embeddings_file):
        print(f"  Embeddings not found at {embeddings_file} — skipping {model_key}.")
        return False

    print(f"Loading embeddings from {embeddings_file}...")
    df = pd.read_parquet(embeddings_file)
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

    # 3. Statistical Analysis
    print("\n── Computing Statistical Tests ──")
    stat_results = compute_statistical_tests(distance_results)
    print(f"  t-statistic: {stat_results['t_test']['t_statistic']}")
    print(f"  p-value:     {stat_results['t_test']['p_value']:.2e}")
    print(f"  Cohen's d:   {stat_results['effect_size']['cohens_d']} ({stat_results['effect_size']['interpretation']})")

    # 4. Save
    metrics = {
        "model_key": model_key,
        "model_name": MODELS[model_key]["name"],
        "silhouette": silhouette_results,
        "distances": {
            k: v for k, v in distance_results.items()
            if not k.startswith("_")
        },
        "statistical_tests": stat_results,
    }

    os.makedirs(model_dir, exist_ok=True)
    with open(metrics_file, "w", encoding="utf-8") as f:
        json.dump(metrics, f, indent=2)
    print(f"\nSaved metrics to {metrics_file}")
    return True


# ── Main ──────────────────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(
        description="Compute RQ2 metrics for one or all models."
    )
    parser.add_argument(
        "--model", type=str, default="all",
        choices=list(MODELS.keys()) + ["all"],
        help="Model to analyze (default: all)",
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

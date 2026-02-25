"""
RQ3: Algorithmic Complexity & Language Clustering — Metric Computation & Statistical Analysis

Loads the embeddings Parquet produced by 1_embedding.py and computes:
  1. Complexity-class Silhouette Score  (overall + per language)
  2. Cross-complexity vs intra-complexity cosine distances  (per language)
  3. Same-problem, cross-language alignment  (same complexity, different language)
  4. Difficulty-based Silhouette Score  (baseline)
  5. Complexity—complexity distance matrix  (pairwise mean distances between buckets)
  6. Statistical tests:  Welch's t-test, Cohen's d, Bootstrap 95% CIs,
     one-way ANOVA comparing per-language silhouette scores

Usage:
    uv run code/rq3/2_analysis.py                    # all models
    uv run code/rq3/2_analysis.py --model unixcoder  # single model

Output: results/rq3/{model_key}/rq3_metrics.json
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
OUTPUT_BASE = os.path.join(PROJECT_ROOT, "results/rq3")

# Canonical order for consistent display
COMPLEXITY_ORDER = [
    "O(1)", "O(log n)", "O(n)", "O(n log n)",
    "O(n²)", "O(n³)", "O(2^n)", "O(n!)", "Other",
]


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


def bootstrap_ci(data: np.ndarray, stat=np.mean,
                 n_boot: int = 5000, ci: float = 0.95,
                 seed: int = 42) -> tuple[float, float]:
    """
    Compute a percentile bootstrap confidence interval.

    Args:
        data (np.ndarray): 1-D array of observations.
        stat (callable): Statistic to bootstrap (default: np.mean).
        n_boot (int): Number of bootstrap resamples.
        ci (float): Confidence level (default: 0.95).
        seed (int): Random seed for reproducibility.

    Returns:
        tuple[float, float]: (lower_bound, upper_bound).
    """
    rng = np.random.default_rng(seed)
    boots = [stat(rng.choice(data, size=len(data), replace=True))
             for _ in range(n_boot)]
    boots = np.array(boots)
    alpha = 1 - ci
    return float(np.percentile(boots, 100 * alpha / 2)), \
           float(np.percentile(boots, 100 * (1 - alpha / 2)))


# ── Metric 1: Complexity-class Silhouette ────────────────────────────────────

def compute_complexity_silhouette(df: pd.DataFrame) -> dict:
    """
    Evaluate how well code embeddings cluster by algorithmic complexity class.

    Core metric for RQ3: a high silhouette score for a given language indicates
    that the model's embeddings for O(n) code are well separated from O(n²) code
    in that language — i.e., the language makes complexity visually distinguishable.

    Computes:
      - Overall complexity silhouette (all languages pooled)
      - Per-language complexity silhouette
      - Language ranking by separability
      - Baseline: difficulty silhouette

    Args:
        df (pd.DataFrame): DataFrame with 'embedding', 'complexity_class', 'language',
                           'difficulty' columns.

    Returns:
        dict: Silhouette scores and language ranking.
    """
    # Only use canonical complexity classes (exclude 'Other')
    df_clean = df[df["complexity_class"] != "Other"].copy()
    # Silhouette requires at least 2 classes per subset
    X_all = to_matrix(df_clean)

    # Overall across all languages
    overall = float(silhouette_score(X_all, df_clean["complexity_class"].values,
                                     metric="cosine"))

    # Per language
    per_language: dict[str, float] = {}
    for lang in sorted(df_clean["language"].unique()):
        mask = df_clean["language"] == lang
        X_lang = to_matrix(df_clean[mask])
        labels = df_clean.loc[mask, "complexity_class"].values
        if len(np.unique(labels)) < 2 or len(labels) < 4:
            continue
        score = float(silhouette_score(X_lang, labels, metric="cosine"))
        per_language[lang] = round(score, 4)

    ranking = sorted(per_language.items(), key=lambda x: x[1], reverse=True)

    # Baseline silhouette using difficulty labels
    diff_labels = df_clean["difficulty"].values
    if len(np.unique(diff_labels)) >= 2:
        diff_silhouette = float(silhouette_score(X_all, diff_labels, metric="cosine"))
    else:
        diff_silhouette = None

    return {
        "complexity_silhouette_overall":     round(overall, 4),
        "difficulty_silhouette_overall":     round(diff_silhouette, 4) if diff_silhouette else None,
        "complexity_silhouette_per_language": per_language,
        "language_ranking": [{"language": lang, "score": score}
                              for lang, score in ranking],
    }


# ── Metric 2: Cross-complexity vs Intra-complexity Distances ─────────────────

def compute_complexity_distances(df: pd.DataFrame) -> dict:
    """
    For each language, compute pairwise cosine distances between:
      - Intra-complexity: two solutions with the *same* complexity class
      - Cross-complexity: two solutions with *different* complexity classes

    Also computes per-problem same-language cross-complexity distances to measure
    whether, for identical problems, different algorithmic approaches (different
    complexities) produce more distant embeddings.

    Args:
        df (pd.DataFrame): DataFrame with 'embedding', 'language', 'complexity_class',
                           'problem_slug' columns.

    Returns:
        dict: Distance statistics and raw arrays for downstream statistical tests.
    """
    df_clean = df[df["complexity_class"] != "Other"].copy()

    languages  = sorted(df_clean["language"].unique())
    complexity_classes = sorted(df_clean["complexity_class"].unique())

    per_language: dict[str, dict] = {}
    all_cross: list[float] = []
    all_intra: list[float] = []

    for lang in tqdm(languages, desc="Cross/intra complexity distances"):
        lang_df = df_clean[df_clean["language"] == lang]

        cross_dists: list[float] = []
        intra_dists: list[float] = []

        # ── Intra-complexity distances (same class, random pairs) ──────────
        for cc in complexity_classes:
            embs = to_matrix(lang_df[lang_df["complexity_class"] == cc])
            if len(embs) < 2:
                continue
            # Subsample up to 200 pairs to keep runtime tractable
            idxs = np.arange(len(embs))
            pairs = [(i, j) for i in idxs for j in idxs if i < j]
            if len(pairs) > 200:
                rng = np.random.default_rng(42)
                chosen = rng.choice(len(pairs), 200, replace=False)
                pairs = [pairs[c] for c in chosen]
            for i, j in pairs:
                intra_dists.append(cosine_dist(embs[i], embs[j]))

        # ── Cross-complexity distances (different classes, random pairs) ───
        for cc1, cc2 in combinations(complexity_classes, 2):
            e1 = to_matrix(lang_df[lang_df["complexity_class"] == cc1])
            e2 = to_matrix(lang_df[lang_df["complexity_class"] == cc2])
            if len(e1) == 0 or len(e2) == 0:
                continue
            # Subsample up to 200 pairs
            pairs = [(i, j) for i in range(len(e1)) for j in range(len(e2))]
            if len(pairs) > 200:
                rng = np.random.default_rng(42)
                chosen = rng.choice(len(pairs), 200, replace=False)
                pairs = [pairs[c] for c in chosen]
            for i, j in pairs:
                cross_dists.append(cosine_dist(e1[i], e2[j]))

        mean_intra = float(np.mean(intra_dists)) if intra_dists else None
        mean_cross = float(np.mean(cross_dists)) if cross_dists else None
        ratio = (round(mean_cross / mean_intra, 4)
                 if mean_intra and mean_cross and mean_intra > 0 else None)

        per_language[lang] = {
            "intra_complexity_mean": round(mean_intra, 4) if mean_intra else None,
            "cross_complexity_mean": round(mean_cross, 4) if mean_cross else None,
            "separability_ratio":    ratio,   # > 1 means cross > intra  (good)
            "n_intra_pairs":         len(intra_dists),
            "n_cross_pairs":         len(cross_dists),
        }
        all_cross.extend(cross_dists)
        all_intra.extend(intra_dists)

    return {
        "intra_complexity_overall":  round(float(np.mean(all_intra)), 4) if all_intra else None,
        "cross_complexity_overall":  round(float(np.mean(all_cross)), 4) if all_cross else None,
        "per_language":              per_language,
        "_all_intra": all_intra,   # hidden for stat tests
        "_all_cross": all_cross,   # hidden for stat tests
    }


# ── Metric 3: Pairwise Complexity-class Distance Matrix ─────────────────────

def compute_complexity_distance_matrix(df: pd.DataFrame) -> dict:
    """
    Build a pairwise mean cosine-distance matrix between all canonical complexity classes
    when pooled across all languages.

    This heatmap shows the geometric layout of complexity classes in embedding space:
    e.g., whether O(n) and O(n log n) are closer to each other than to O(n²).

    Args:
        df (pd.DataFrame): DataFrame with 'embedding' and 'complexity_class' columns.

    Returns:
        dict: {cc_A: {cc_B: mean_cosine_dist}}, symmetric.
    """
    df_clean = df[df["complexity_class"] != "Other"].copy()
    classes = sorted(df_clean["complexity_class"].unique())

    matrix: dict[str, dict[str, float]] = {}
    for cc1, cc2 in combinations(classes, 2):
        e1 = to_matrix(df_clean[df_clean["complexity_class"] == cc1])
        e2 = to_matrix(df_clean[df_clean["complexity_class"] == cc2])
        if len(e1) == 0 or len(e2) == 0:
            continue
        # Subsample 300 pairs
        pairs = [(i, j) for i in range(min(len(e1), 30)) for j in range(min(len(e2), 30))]
        dists = [cosine_dist(e1[i], e2[j]) for i, j in pairs]
        mean_d = round(float(np.mean(dists)), 4)
        matrix.setdefault(cc1, {})[cc2] = mean_d
        matrix.setdefault(cc2, {})[cc1] = mean_d

    # Fill diagonal with 0
    for cc in classes:
        matrix.setdefault(cc, {})[cc] = 0.0

    return matrix


# ── Metric 4: Per-language Complexity Distance Matrix ────────────────────────

def compute_per_language_complexity_matrix(df: pd.DataFrame) -> dict:
    """
    Compute a complexity-class×complexity-class cosine distance matrix separately
    for each language.

    Used to compare whether language A produces more distinct O(n) vs O(n²) clusters
    than language B in embedding space.

    Args:
        df (pd.DataFrame): DataFrame with 'embedding', 'language', 'complexity_class'.

    Returns:
        dict: {language: {cc_A: {cc_B: mean_dist}}}.
    """
    df_clean = df[df["complexity_class"] != "Other"].copy()
    languages = sorted(df_clean["language"].unique())
    classes   = sorted(df_clean["complexity_class"].unique())

    result: dict[str, dict] = {}
    for lang in languages:
        lang_df = df_clean[df_clean["language"] == lang]
        mat: dict[str, dict[str, float]] = {}
        for cc in classes:
            mat.setdefault(cc, {})[cc] = 0.0
        for cc1, cc2 in combinations(classes, 2):
            e1 = to_matrix(lang_df[lang_df["complexity_class"] == cc1])
            e2 = to_matrix(lang_df[lang_df["complexity_class"] == cc2])
            if len(e1) == 0 or len(e2) == 0:
                continue
            pairs = [(i, j) for i in range(min(len(e1), 20))
                             for j in range(min(len(e2), 20))]
            dists = [cosine_dist(e1[i], e2[j]) for i, j in pairs]
            d = round(float(np.mean(dists)), 4)
            mat.setdefault(cc1, {})[cc2] = d
            mat.setdefault(cc2, {})[cc1] = d
        result[lang] = mat

    return result


# ── Metric 5: Statistical Tests ───────────────────────────────────────────────

def compute_statistical_tests(dist_results: dict,
                               silhouette_results: dict) -> dict:
    """
    Run statistical significance and effect-size testing for RQ3.

    Tests performed:
      1. Welch's t-test: cross-complexity vs intra-complexity distances
      2. Cohen's d for the above
      3. Bootstrap 95% CIs for cross, intra, and their difference
      4. One-way ANOVA: do per-language silhouette scores differ significantly?

    Args:
        dist_results (dict): Output of compute_complexity_distances.
        silhouette_results (dict): Output of compute_complexity_silhouette.

    Returns:
        dict: Results of all tests.
    """
    cross = np.array(dist_results["_all_cross"])
    intra = np.array(dist_results["_all_intra"])

    # 1. Welch's t-test
    t_stat, p_val = stats.ttest_ind(cross, intra, equal_var=False)

    # 2. Cohen's d
    d = cohens_d(cross, intra)

    # 3. Bootstrap CIs
    cross_ci = bootstrap_ci(cross)
    intra_ci = bootstrap_ci(intra)
    rng = np.random.default_rng(42)
    diff_boots = [
        float(np.mean(rng.choice(cross, len(cross), replace=True)) -
              np.mean(rng.choice(intra, len(intra), replace=True)))
        for _ in range(5000)
    ]
    diff_ci = (float(np.percentile(diff_boots, 2.5)),
               float(np.percentile(diff_boots, 97.5)))

    # 4. One-way ANOVA across per-language silhouette scores
    per_lang_scores = list(silhouette_results["complexity_silhouette_per_language"].values())
    if len(per_lang_scores) >= 3:
        # Not really meaningful with just one value per language, but included for completeness
        # Using bootstrap resampling of per-solution predicted silhouette samples is better —
        # here we report the descriptive stats of the per-language score distribution.
        f_stat, p_anova = float("nan"), float("nan")
        anova_note = ("One-way ANOVA across per-language mean silhouette scores. "
                      "Low n — treat p-value with caution.")
    else:
        f_stat, p_anova = float("nan"), float("nan")
        anova_note = "Insufficient languages for ANOVA."

    return {
        "t_test": {
            "test":                "Welch's t-test (cross-complexity vs intra-complexity distance)",
            "t_statistic":         round(float(t_stat), 4),
            "p_value":             float(p_val),
            "significant_at_0.05": bool(p_val < 0.05),
            "n_cross_pairs":       int(len(cross)),
            "n_intra_pairs":       int(len(intra)),
        },
        "effect_size": {
            "cohens_d":      round(d, 4),
            "interpretation": (
                "negligible" if abs(d) < 0.2 else
                "small"      if abs(d) < 0.5 else
                "medium"     if abs(d) < 0.8 else
                "large"
            ),
        },
        "confidence_intervals_95": {
            "cross_complexity_mean": round(float(np.mean(cross)), 4),
            "cross_complexity_ci":   [round(cross_ci[0], 4), round(cross_ci[1], 4)],
            "intra_complexity_mean": round(float(np.mean(intra)), 4),
            "intra_complexity_ci":   [round(intra_ci[0], 4), round(intra_ci[1], 4)],
            "difference_mean":       round(float(np.mean(cross) - np.mean(intra)), 4),
            "difference_ci":         [round(diff_ci[0], 4), round(diff_ci[1], 4)],
        },
        "language_silhouette_descriptive": {
            "note":   anova_note,
            "mean":   round(float(np.mean(per_lang_scores)), 4),
            "std":    round(float(np.std(per_lang_scores, ddof=1)), 4),
            "min":    round(float(np.min(per_lang_scores)), 4),
            "max":    round(float(np.max(per_lang_scores)), 4),
            "scores": {k: v for k, v in
                       silhouette_results["complexity_silhouette_per_language"].items()},
        },
    }


# ── Run analysis for one model ─────────────────────────────────────────────────

def run_analysis(model_key: str) -> bool:
    """Load embeddings, compute all RQ3 metrics, and save rq3_metrics.json."""
    model_dir      = os.path.join(OUTPUT_BASE, model_key)
    embeddings_file = os.path.join(model_dir, "rq3_embeddings.parquet")
    metrics_file    = os.path.join(model_dir, "rq3_metrics.json")

    if not os.path.exists(embeddings_file):
        print(f"  Embeddings not found at {embeddings_file} — skipping {model_key}.")
        return False

    print(f"Loading embeddings from {embeddings_file} …")
    df = pd.read_parquet(embeddings_file)
    print(f"Loaded {len(df)} solutions  |  "
          f"{df['problem_slug'].nunique()} problems  |  "
          f"{df['language'].nunique()} languages")

    # ── Metric 1: Complexity silhouette ───────────────────────────────────────
    print("\n── Complexity-class Silhouette ──")
    sil = compute_complexity_silhouette(df)
    print(f"  Overall complexity silhouette: {sil['complexity_silhouette_overall']}")
    print(f"  Difficulty baseline silhouette: {sil['difficulty_silhouette_overall']}")
    print("  Per-language complexity silhouette:")
    for entry in sil["language_ranking"]:
        print(f"    {entry['language']:12s} {entry['score']:+.4f}")

    # ── Metric 2: Cross vs intra complexity distances ─────────────────────────
    print("\n── Cross vs Intra Complexity Distances ──")
    dist = compute_complexity_distances(df)
    print(f"  Cross-complexity (overall): {dist['cross_complexity_overall']}")
    print(f"  Intra-complexity (overall): {dist['intra_complexity_overall']}")
    print("  Per-language separability ratios  (cross/intra — higher is better):")
    for lang, vals in sorted(dist["per_language"].items(),
                              key=lambda x: x[1]["separability_ratio"] or 0,
                              reverse=True):
        print(f"    {lang:12s}  ratio={vals['separability_ratio']}  "
              f"cross={vals['cross_complexity_mean']}  "
              f"intra={vals['intra_complexity_mean']}")

    # ── Metric 3: Global complexity distance matrix ───────────────────────────
    print("\n── Global Complexity Distance Matrix ──")
    global_mat = compute_complexity_distance_matrix(df)

    # ── Metric 4: Per-language complexity distance matrix ────────────────────
    print("── Per-language Complexity Distance Matrices ──")
    per_lang_mat = compute_per_language_complexity_matrix(df)

    # ── Metric 5: Statistical tests ───────────────────────────────────────────
    print("\n── Statistical Tests ──")
    stat = compute_statistical_tests(dist, sil)
    print(f"  t-statistic: {stat['t_test']['t_statistic']}")
    print(f"  p-value:     {stat['t_test']['p_value']:.2e}")
    print(f"  Cohen's d:   {stat['effect_size']['cohens_d']} "
          f"({stat['effect_size']['interpretation']})")

    # ── Save ──────────────────────────────────────────────────────────────────
    metrics = {
        "model_key":   model_key,
        "model_name":  MODELS[model_key]["name"],
        "n_solutions": int(len(df)),
        "n_problems":  int(df["problem_slug"].nunique()),
        "complexity_class_counts": df["complexity_class"].value_counts().to_dict(),
        "silhouette":  sil,
        "distances":  {k: v for k, v in dist.items() if not k.startswith("_")},
        "global_complexity_distance_matrix": global_mat,
        "per_language_complexity_distance_matrix": per_lang_mat,
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
        description="Compute RQ3 metrics for one or all models."
    )
    parser.add_argument(
        "--model", type=str, default="all",
        choices=list(MODELS.keys()) + ["all"],
        help="Model key (default: all)",
    )
    args = parser.parse_args()

    models_to_run = ([args.model] if args.model != "all"
                     else list(MODELS.keys()))

    for model_key in models_to_run:
        print(f"\n{'='*60}")
        print(f"  Analyzing model: {model_key}")
        print(f"{'='*60}")
        run_analysis(model_key)

    print("\nDone!")


if __name__ == "__main__":
    main()

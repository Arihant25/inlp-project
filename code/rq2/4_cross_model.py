"""
RQ2: Cross-Model Comparison — Aggregate & Visualize Trends Across Models

Loads per-model rq2_metrics.json files from results/rq2/{model_key}/
and generates cross-model comparison plots:
  1. Silhouette comparison    — grouped bar chart (Language × Model)
  2. Distance comparison      — grouped bar chart (Pattern × Model)
  3. Effect-size comparison   — horizontal bar chart (Model → Cohen's d)
  4. Overall summary table    — saved as cross_model_metrics.json

Usage:
    python 4_cross_model.py

Output: results/rq2/cross_model/
"""

import json
import os
import sys

import matplotlib.pyplot as plt
import numpy as np

# ── Paths ─────────────────────────────────────────────────────────────────────
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
CODE_DIR = os.path.dirname(SCRIPT_DIR)
sys.path.insert(0, CODE_DIR)
from embedding import MODELS

PROJECT_ROOT = os.path.dirname(CODE_DIR)
OUTPUT_BASE = os.path.join(PROJECT_ROOT, "results/rq2")
CROSS_DIR = os.path.join(OUTPUT_BASE, "cross_model")

# Plotting style
plt.rcParams.update({
    "figure.dpi": 150,
    "savefig.dpi": 300,
    "font.size": 10,
    "axes.titlesize": 13,
    "axes.labelsize": 11,
    "legend.fontsize": 8,
    "figure.facecolor": "white",
})

# Palette for models
MODEL_PALETTE = [
    "#264653", "#2a9d8f", "#e9c46a", "#f4a261", "#e76f51", "#6a4c93", "#c77daa",
]


# ── Load all per-model metrics ───────────────────────────────────────────────

def load_all_metrics() -> dict:
    """
    Scan the results directory and load metrics for all available models.
    
    Returns:
        dict: A mapping of {model_key: metrics_dictionary} for all models
              that have successfully completed the analysis step.
    """
    all_metrics = {}
    for model_key in MODELS:
        metrics_file = os.path.join(OUTPUT_BASE, model_key, "rq2_metrics.json")
        if os.path.exists(metrics_file):
            with open(metrics_file, "r") as f:
                all_metrics[model_key] = json.load(f)
            print(f"  Loaded metrics for {model_key}")
        else:
            print(f"  No metrics found for {model_key} — skipping")
    return all_metrics


# ── Plot 1: Silhouette Comparison ────────────────────────────────────────────

def plot_silhouette_comparison(all_metrics: dict, output_dir: str):
    """
    Generate a grouped bar chart comparing per-language silhouette scores across models.
    
    The x-axis represents languages, and each language group contains one bar 
    per model. This visualization shows which models are best at cleanly 
    separating frameworks within specific languages.
    
    Args:
        all_metrics (dict): Loaded metrics dictionary mapping model_key to its metrics.
        output_dir (str): Directory where 'silhouette_comparison.png' is saved.
    """
    model_keys = sorted(all_metrics.keys())
    if len(model_keys) < 2:
        print("  Need ≥2 models for silhouette comparison — skipping.")
        return

    # Collect all languages across models
    all_langs: set = set()
    for m in all_metrics.values():
        all_langs.update(m["silhouette"]["framework_silhouette_per_language"].keys())
    languages = sorted(all_langs)

    n_models = len(model_keys)
    n_langs = len(languages)
    x = np.arange(n_langs)
    bar_width = 0.8 / n_models

    fig, ax = plt.subplots(figsize=(max(10, n_langs * 1.2), 6))

    for i, model_key in enumerate(model_keys):
        per_lang = all_metrics[model_key]["silhouette"]["framework_silhouette_per_language"]
        scores = [per_lang.get(lang, 0) for lang in languages]
        color = MODEL_PALETTE[i % len(MODEL_PALETTE)]
        offset = (i - n_models / 2 + 0.5) * bar_width
        ax.bar(x + offset, scores, bar_width, label=model_key,
               color=color, edgecolor="white", linewidth=0.5, alpha=0.85)

    ax.set_xticks(x)
    ax.set_xticklabels(languages, rotation=30, ha="right")
    ax.set_ylabel("Framework Silhouette Score")
    ax.set_xlabel("Language")
    ax.set_title("RQ2: Framework Silhouette Score per Language — Model Comparison")
    ax.axhline(y=0, color="grey", linewidth=0.5)
    ax.legend(title="Model", fontsize=8, loc="best")
    plt.tight_layout()

    path = os.path.join(output_dir, "silhouette_comparison.png")
    fig.savefig(path, bbox_inches="tight")
    plt.close(fig)
    print(f"  Saved {path}")


# ── Plot 2: Cross-Framework Distance Comparison ─────────────────────────────

def plot_distance_comparison(all_metrics: dict, output_dir: str):
    """
    Generate a grouped bar chart comparing cross-framework distances across models.
    
    The x-axis represents software patterns, and each pattern group contains 
    one bar per model showing the average cosine distance between frameworks 
    implementing that identical pattern. This reveals which patterns are most 
    sensitive to framework choice, and how differently models perceive that gap.
    
    Args:
        all_metrics (dict): Loaded metrics dictionary mapping model_key to its metrics.
        output_dir (str): Directory where 'distance_comparison.png' is saved.
    """
    model_keys = sorted(all_metrics.keys())
    if len(model_keys) < 2:
        print("  Need ≥2 models for distance comparison — skipping.")
        return

    # Collect all patterns
    all_patterns: set = set()
    for m in all_metrics.values():
        all_patterns.update(m["distances"]["cross_framework_distance_by_pattern"].keys())
    patterns = sorted(all_patterns)

    n_models = len(model_keys)
    n_patterns = len(patterns)
    x = np.arange(n_patterns)
    bar_width = 0.8 / n_models

    fig, ax = plt.subplots(figsize=(max(10, n_patterns * 1.5), 6))

    for i, model_key in enumerate(model_keys):
        by_pat = all_metrics[model_key]["distances"]["cross_framework_distance_by_pattern"]
        dists = [by_pat.get(pat, 0) for pat in patterns]
        color = MODEL_PALETTE[i % len(MODEL_PALETTE)]
        offset = (i - n_models / 2 + 0.5) * bar_width
        ax.bar(x + offset, dists, bar_width, label=model_key,
               color=color, edgecolor="white", linewidth=0.5, alpha=0.85)

    ax.set_xticks(x)
    ax.set_xticklabels(patterns, rotation=25, ha="right", fontsize=8)
    ax.set_ylabel("Mean Cross-Framework Cosine Distance")
    ax.set_xlabel("Software Pattern")
    ax.set_title("RQ2: Cross-Framework Distance per Pattern — Model Comparison")
    ax.legend(title="Model", fontsize=8, loc="best")
    plt.tight_layout()

    path = os.path.join(output_dir, "distance_comparison.png")
    fig.savefig(path, bbox_inches="tight")
    plt.close(fig)
    print(f"  Saved {path}")


# ── Plot 3: Effect-Size Comparison ───────────────────────────────────────────

def plot_effect_size_comparison(all_metrics: dict, output_dir: str):
    """
    Generate a horizontal bar chart comparing Cohen's d effect sizes across models.
    
    This visualizes the statistical magnitude of the difference between 
    cross-framework and intra-framework distances. Models with a larger effect 
    size are detecting a stronger "dialect" signal driven by framework choice.
    
    Args:
        all_metrics (dict): Loaded metrics dictionary mapping model_key to its metrics.
        output_dir (str): Directory where 'effect_size_comparison.png' is saved.
    """
    model_keys = sorted(all_metrics.keys())
    if len(model_keys) < 2:
        print("  Need ≥2 models for effect-size comparison — skipping.")
        return

    ds = []
    labels = []
    for mk in model_keys:
        stat = all_metrics[mk].get("statistical_tests", {})
        es = stat.get("effect_size", {})
        d_val = es.get("cohens_d", 0)
        interp = es.get("interpretation", "?")
        ds.append(d_val)
        labels.append(f"{mk}\n(d={d_val:.3f}, {interp})")

    colors = [MODEL_PALETTE[i % len(MODEL_PALETTE)] for i in range(len(model_keys))]

    fig, ax = plt.subplots(figsize=(9, max(4, len(model_keys) * 0.8)))
    y = np.arange(len(model_keys))
    ax.barh(y, ds, color=colors, edgecolor="white", linewidth=0.5, height=0.55)
    ax.set_yticks(y)
    ax.set_yticklabels(labels, fontsize=9)
    ax.set_xlabel("Cohen's d   (cross − intra framework distance)")
    ax.set_title("RQ2: Effect Size of Framework Separation — Model Comparison")

    # Reference lines
    for thresh, lbl in [(0.2, "small"), (0.5, "medium"), (0.8, "large")]:
        ax.axvline(x=thresh, color="grey", linestyle=":", linewidth=0.8)
        ax.text(thresh, len(model_keys) - 0.1, lbl, ha="center", va="bottom",
                fontsize=7, color="grey")

    plt.tight_layout()

    path = os.path.join(output_dir, "effect_size_comparison.png")
    fig.savefig(path, bbox_inches="tight")
    plt.close(fig)
    print(f"  Saved {path}")


# ── Summary JSON ─────────────────────────────────────────────────────────────

def save_summary(all_metrics: dict, output_dir: str):
    """
    Extract and save a concise JSON summary of high-level metrics across all models.
    
    Aggregates overall silhouette scores, distances, and statistical test 
    results into a single file for easy side-by-side programmatic comparison.
    
    Args:
        all_metrics (dict): Loaded metrics dictionary mapping model_key to its metrics.
        output_dir (str): Directory where 'cross_model_metrics.json' is saved.
    """
    summary = {}
    for mk, m in sorted(all_metrics.items()):
        stat = m.get("statistical_tests", {})
        summary[mk] = {
            "model_name": m.get("model_name", MODELS.get(mk, {}).get("name", mk)),
            "framework_silhouette_overall": m["silhouette"]["framework_silhouette_overall"],
            "language_silhouette_overall": m["silhouette"]["language_silhouette_overall"],
            "cross_framework_distance": m["distances"]["cross_framework_distance_overall"],
            "intra_framework_distance": m["distances"]["intra_framework_distance_overall"],
            "distance_gap": round(
                m["distances"]["cross_framework_distance_overall"]
                - m["distances"]["intra_framework_distance_overall"], 4
            ),
            "cohens_d": stat.get("effect_size", {}).get("cohens_d", None),
            "effect_interpretation": stat.get("effect_size", {}).get("interpretation", None),
            "t_statistic": stat.get("t_test", {}).get("t_statistic", None),
            "p_value": stat.get("t_test", {}).get("p_value", None),
        }

    path = os.path.join(output_dir, "cross_model_metrics.json")
    with open(path, "w") as f:
        json.dump(summary, f, indent=2)
    print(f"  Saved {path}")


# ── Main ──────────────────────────────────────────────────────────────────────

def main():
    print("Loading per-model metrics...")
    all_metrics = load_all_metrics()

    if len(all_metrics) == 0:
        print("ERROR: No model metrics found. Run 2_analysis.py first.")
        sys.exit(1)

    os.makedirs(CROSS_DIR, exist_ok=True)

    print(f"\n── Cross-Model Comparison ({len(all_metrics)} models) ──")
    plot_silhouette_comparison(all_metrics, CROSS_DIR)
    plot_distance_comparison(all_metrics, CROSS_DIR)
    plot_effect_size_comparison(all_metrics, CROSS_DIR)
    save_summary(all_metrics, CROSS_DIR)
    print(f"\nDone! Cross-model results saved to {CROSS_DIR}")


if __name__ == "__main__":
    main()

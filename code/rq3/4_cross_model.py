"""
RQ3: Cross-Model Comparison — Aggregate & Visualize Trends Across Models

Loads per-model rq3_metrics.json files from results/rq3/{model_key}/
and generates cross-model comparison plots:
  1. Silhouette comparison     — grouped bar chart (Language × Model)
  2. Separability ratio        — grouped bar chart (Language × Model, cross/intra ratio)
  3. Effect-size comparison    — horizontal bar chart (Model → Cohen's d)
  4. Global distance heatmaps  — side-by-side per model (O(n) vs O(n²) etc.)
  5. Summary table             — saved as cross_model_metrics.json

Usage:
    uv run code/rq3/4_cross_model.py

Output: results/rq3/cross_model/
"""

import json
import os
import sys
import math

import matplotlib.pyplot as plt
import matplotlib.patches as mpatches
import numpy as np

# ── Paths ─────────────────────────────────────────────────────────────────────
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
CODE_DIR = os.path.dirname(SCRIPT_DIR)
sys.path.insert(0, CODE_DIR)
from embedding import MODELS

PROJECT_ROOT = os.path.dirname(CODE_DIR)
OUTPUT_BASE = os.path.join(PROJECT_ROOT, "results/rq3")
CROSS_DIR = os.path.join(OUTPUT_BASE, "cross_model")

# ── Plot style ─────────────────────────────────────────────────────────────────
plt.rcParams.update({
    "figure.dpi": 150,
    "savefig.dpi": 300,
    "font.size": 10,
    "axes.titlesize": 13,
    "axes.labelsize": 11,
    "legend.fontsize": 8,
    "figure.facecolor": "white",
})

MODEL_PALETTE = [
    "#264653", "#2a9d8f", "#e9c46a", "#f4a261", "#e76f51", "#6a4c93",
]

COMPLEXITY_ORDER = [
    "O(1)", "O(log n)", "O(n)", "O(n log n)",
    "O(n²)", "O(n³)", "O(2^n)", "O(n!)",
]


# ── Load all per-model metrics ─────────────────────────────────────────────────

def load_all_metrics() -> dict:
    """
    Scan the RQ3 results directory and load metrics for all available models.

    Returns:
        dict: {model_key: metrics_dict} for every model that has a rq3_metrics.json.
    """
    all_metrics = {}
    for model_key in MODELS:
        path = os.path.join(OUTPUT_BASE, model_key, "rq3_metrics.json")
        if os.path.exists(path):
            with open(path) as f:
                all_metrics[model_key] = json.load(f)
            print(f"  Loaded metrics for {model_key}")
        else:
            print(f"  No metrics for {model_key} — skipping")
    return all_metrics


# ── Plot 1: Complexity Silhouette Comparison ──────────────────────────────────

def plot_silhouette_comparison(all_metrics: dict, output_dir: str):
    """
    Grouped bar chart: per-language complexity-class silhouette score for each model.

    Each language group has one bar per model.  A less-negative (or positive) bar
    means the model forms cleaner complexity clusters for that language.

    Args:
        all_metrics (dict): {model_key: metrics_dict}.
        output_dir (str): Save directory.
    """
    model_keys = sorted(all_metrics.keys())
    if len(model_keys) < 2:
        print("  Need ≥2 models — skipping silhouette comparison.")
        return

    all_langs: set = set()
    for m in all_metrics.values():
        all_langs.update(m["silhouette"]["complexity_silhouette_per_language"].keys())
    languages = sorted(all_langs)

    n_models = len(model_keys)
    bar_width = 0.8 / n_models
    x = np.arange(len(languages))

    fig, ax = plt.subplots(figsize=(max(10, len(languages) * 1.4), 6))

    for i, mk in enumerate(model_keys):
        per_lang = all_metrics[mk]["silhouette"]["complexity_silhouette_per_language"]
        scores = [per_lang.get(lang, 0.0) for lang in languages]
        offset = (i - n_models / 2 + 0.5) * bar_width
        ax.bar(x + offset, scores, bar_width,
               label=mk, color=MODEL_PALETTE[i % len(MODEL_PALETTE)],
               edgecolor="white", linewidth=0.5, alpha=0.85)

    ax.set_xticks(x)
    ax.set_xticklabels(languages, rotation=30, ha="right")
    ax.set_ylabel("Complexity-class Silhouette Score")
    ax.set_xlabel("Language")
    ax.set_title("RQ3: Complexity Silhouette per Language — Model Comparison\n"
                 "(less negative = better complexity cluster separation)")
    ax.axhline(y=0, color="grey", linewidth=0.6)
    ax.legend(title="Model", fontsize=8)
    plt.tight_layout()

    path = os.path.join(output_dir, "silhouette_comparison.png")
    fig.savefig(path, bbox_inches="tight")
    plt.close(fig)
    print(f"  Saved {path}")


# ── Plot 2: Separability Ratio Comparison ────────────────────────────────────

def plot_separability_comparison(all_metrics: dict, output_dir: str):
    """
    Grouped bar chart: per-language cross/intra complexity distance ratio for each model.

    Ratio > 1 means cross-complexity code is further apart than same-complexity code.
    Higher is better for detecting complexity differences.

    Args:
        all_metrics (dict): {model_key: metrics_dict}.
        output_dir (str): Save directory.
    """
    model_keys = sorted(all_metrics.keys())
    if len(model_keys) < 2:
        print("  Need ≥2 models — skipping separability comparison.")
        return

    all_langs: set = set()
    for m in all_metrics.values():
        all_langs.update(m["distances"]["per_language"].keys())
    languages = sorted(all_langs)

    n_models = len(model_keys)
    bar_width = 0.8 / n_models
    x = np.arange(len(languages))

    fig, ax = plt.subplots(figsize=(max(10, len(languages) * 1.4), 6))

    for i, mk in enumerate(model_keys):
        per_lang = all_metrics[mk]["distances"]["per_language"]
        ratios = [per_lang.get(lang, {}).get("separability_ratio") or 1.0
                  for lang in languages]
        offset = (i - n_models / 2 + 0.5) * bar_width
        ax.bar(x + offset, ratios, bar_width,
               label=mk, color=MODEL_PALETTE[i % len(MODEL_PALETTE)],
               edgecolor="white", linewidth=0.5, alpha=0.85)

    ax.set_xticks(x)
    ax.set_xticklabels(languages, rotation=30, ha="right")
    ax.set_ylabel("Cross / Intra Complexity Distance Ratio")
    ax.set_xlabel("Language")
    ax.set_title("RQ3: Complexity Separability Ratio per Language — Model Comparison\n"
                 "(ratio > 1 means complexity classes are detectably separated)")
    ax.axhline(y=1.0, color="black", linestyle="--", linewidth=1.0,
               label="ratio = 1 (no separation)")
    ax.legend(title="Model", fontsize=8)
    plt.tight_layout()

    path = os.path.join(output_dir, "separability_comparison.png")
    fig.savefig(path, bbox_inches="tight")
    plt.close(fig)
    print(f"  Saved {path}")


# ── Plot 3: Effect-Size Comparison ───────────────────────────────────────────

def plot_effect_size_comparison(all_metrics: dict, output_dir: str):
    """
    Horizontal bar chart: Cohen's d (cross vs intra complexity distance) per model.

    Higher d means the model encodes a stronger algorithmic-complexity signal.
    Reference lines mark the small / medium / large thresholds.

    Args:
        all_metrics (dict): {model_key: metrics_dict}.
        output_dir (str): Save directory.
    """
    model_keys = sorted(all_metrics.keys())

    ds, labels = [], []
    for mk in model_keys:
        stat = all_metrics[mk].get("statistical_tests", {})
        d_val = stat.get("effect_size", {}).get("cohens_d", 0.0)
        interp = stat.get("effect_size", {}).get("interpretation", "?")
        ds.append(d_val)
        labels.append(f"{mk}\n(d={d_val:.3f}, {interp})")

    colors = [MODEL_PALETTE[i % len(MODEL_PALETTE)] for i in range(len(model_keys))]

    fig, ax = plt.subplots(figsize=(9, max(4, len(model_keys) * 0.9)))
    y = np.arange(len(model_keys))
    ax.barh(y, ds, color=colors, edgecolor="white", linewidth=0.5, height=0.55)
    ax.set_yticks(y)
    ax.set_yticklabels(labels, fontsize=9)
    ax.set_xlabel("Cohen's d   (cross − intra complexity distance)")
    ax.set_title("RQ3: Effect Size of Complexity Separation — Model Comparison")

    for thresh, lbl in [(0.2, "small"), (0.5, "medium"), (0.8, "large")]:
        ax.axvline(x=thresh, color="grey", linestyle=":", linewidth=0.8)
        ax.text(thresh, len(model_keys) - 0.1, lbl,
                ha="center", va="bottom", fontsize=7, color="grey")

    plt.tight_layout()
    path = os.path.join(output_dir, "effect_size_comparison.png")
    fig.savefig(path, bbox_inches="tight")
    plt.close(fig)
    print(f"  Saved {path}")


# ── Plot 4: Global distance heatmap comparison (small multiples) ─────────────

def plot_global_distance_comparison(all_metrics: dict, output_dir: str):
    """
    A row of small complexity-class distance heatmaps — one per model.

    Allows direct visual comparison of how differently each model represents
    the geometry between complexity classes in embedding space.

    Args:
        all_metrics (dict): {model_key: metrics_dict}.
        output_dir (str): Save directory.
    """
    model_keys = sorted(all_metrics.keys())

    # Collect shared classes
    all_classes: set = set()
    for m in all_metrics.values():
        mat = m.get("global_complexity_distance_matrix", {})
        all_classes.update(mat.keys())
    classes = [c for c in COMPLEXITY_ORDER if c in all_classes]
    if not classes:
        print("  No global distance matrices found — skipping.")
        return

    n = len(classes)
    n_models = len(model_keys)
    n_cols = min(3, n_models)
    n_rows = math.ceil(n_models / n_cols)

    fig, axes = plt.subplots(n_rows, n_cols,
                              figsize=(n * 1.1 * n_cols, n * 0.9 * n_rows))
    axes = np.array(axes).flatten()

    vmax = 0.75
    last_im = None
    for ax_idx, mk in enumerate(model_keys):
        ax = axes[ax_idx]
        mat_raw = all_metrics[mk].get("global_complexity_distance_matrix", {})
        matrix = np.full((n, n), np.nan)
        for i, c1 in enumerate(classes):
            for j, c2 in enumerate(classes):
                v = mat_raw.get(c1, {}).get(c2)
                if v is not None:
                    matrix[i, j] = v

        im = ax.imshow(matrix, aspect="auto", cmap="YlOrRd",
                       vmin=0.0, vmax=vmax)
        last_im = im
        ax.set_xticks(range(n))
        ax.set_xticklabels(classes, rotation=40, ha="right", fontsize=7)
        ax.set_yticks(range(n))
        ax.set_yticklabels(classes, fontsize=7)
        ax.set_title(mk, fontsize=10, fontweight="bold")

        for i in range(n):
            for j in range(n):
                if not np.isnan(matrix[i, j]):
                    ax.text(j, i, f"{matrix[i, j]:.2f}",
                            ha="center", va="center", fontsize=6.0,
                            color="black" if matrix[i, j] < 0.5 else "white")

    for ax_idx in range(len(model_keys), len(axes)):
        axes[ax_idx].set_visible(False)

    if last_im is not None:
        fig.colorbar(last_im, ax=axes[:len(model_keys)],
                     shrink=0.5, label="Mean Cosine Distance")

    fig.suptitle("RQ3: Global Complexity-class Distance Matrix — Model Comparison",
                 y=1.01, fontsize=12)
    plt.tight_layout()

    path = os.path.join(output_dir, "global_distance_comparison.png")
    fig.savefig(path, bbox_inches="tight")
    plt.close(fig)
    print(f"  Saved {path}")


# ── Summary JSON + printed table ──────────────────────────────────────────────

def save_summary(all_metrics: dict, output_dir: str):
    """
    Save a concise cross-model summary JSON and print a Markdown table.

    Args:
        all_metrics (dict): {model_key: metrics_dict}.
        output_dir (str): Save directory for cross_model_metrics.json.
    """
    summary = {}
    for mk in sorted(all_metrics.keys()):
        m = all_metrics[mk]
        stat = m.get("statistical_tests", {})
        per_lang_sil = m["silhouette"]["complexity_silhouette_per_language"]
        per_lang_ratio = m["distances"]["per_language"]
        best_lang_sil  = max(per_lang_sil, key=per_lang_sil.get)
        best_lang_ratio = max(per_lang_ratio,
                              key=lambda l: per_lang_ratio[l].get("separability_ratio") or 0)
        summary[mk] = {
            "model_name": m.get("model_name", mk),
            "complexity_silhouette_overall": m["silhouette"]["complexity_silhouette_overall"],
            "difficulty_silhouette_overall": m["silhouette"].get("difficulty_silhouette_overall"),
            "cross_complexity_distance": m["distances"]["cross_complexity_overall"],
            "intra_complexity_distance": m["distances"]["intra_complexity_overall"],
            "distance_gap": round(
                (m["distances"]["cross_complexity_overall"] or 0)
                - (m["distances"]["intra_complexity_overall"] or 0), 4),
            "cohens_d": stat.get("effect_size", {}).get("cohens_d"),
            "effect_interpretation": stat.get("effect_size", {}).get("interpretation"),
            "t_statistic": stat.get("t_test", {}).get("t_statistic"),
            "p_value": stat.get("t_test", {}).get("p_value"),
            "best_language_silhouette": best_lang_sil,
            "best_language_ratio": best_lang_ratio,
        }

    path = os.path.join(output_dir, "cross_model_metrics.json")
    with open(path, "w") as f:
        json.dump(summary, f, indent=2)
    print(f"  Saved {path}")

    # Print markdown table
    print("\n── Cross-Model Summary ──")
    header = (f"{'Model':12s} | {'Sil(overall)':12s} | {'Cross dist':10s} | "
              f"{'Intra dist':10s} | {'Gap':7s} | {'Cohen d':8s} | {'Effect':8s} | "
              f"{'t-stat':8s} | {'p-value':12s}")
    print(header)
    print("-" * len(header))
    for mk, s in sorted(summary.items()):
        pv = s['p_value']
        pv_str = f"{pv:.2e}" if pv is not None else "N/A"
        print(f"{mk:12s} | {s['complexity_silhouette_overall']:12.4f} | "
              f"{(s['cross_complexity_distance'] or 0):10.4f} | "
              f"{(s['intra_complexity_distance'] or 0):10.4f} | "
              f"{s['distance_gap']:7.4f} | "
              f"{(s['cohens_d'] or 0):8.4f} | "
              f"{(s['effect_interpretation'] or '?'):8s} | "
              f"{(s['t_statistic'] or 0):8.2f} | "
              f"{pv_str:12s}")

    return summary


# ── Main ──────────────────────────────────────────────────────────────────────

def main():
    print("Loading per-model RQ3 metrics …")
    all_metrics = load_all_metrics()

    if not all_metrics:
        print("ERROR: No model metrics found. Run 2_analysis.py first.")
        sys.exit(1)

    os.makedirs(CROSS_DIR, exist_ok=True)

    print(f"\n── Cross-Model Comparison ({len(all_metrics)} models) ──")
    plot_silhouette_comparison(all_metrics, CROSS_DIR)
    plot_separability_comparison(all_metrics, CROSS_DIR)
    plot_effect_size_comparison(all_metrics, CROSS_DIR)
    plot_global_distance_comparison(all_metrics, CROSS_DIR)
    save_summary(all_metrics, CROSS_DIR)

    print(f"\nDone! Cross-model results saved to {CROSS_DIR}")


if __name__ == "__main__":
    main()

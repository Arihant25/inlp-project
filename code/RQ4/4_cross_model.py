"""
RQ4: Cross-Model Comparison — Aggregate & Visualize Trends Across Models

Loads per-model rq4_metrics.json files from results/RQ4/{model_key}/
and generates cross-model comparison plots:
  1. Correctness silhouette comparison     — grouped bar (Language × Model)
  2. Separation score comparison           — grouped bar (Language × Model)
  3. Effect-size comparison                — horizontal bar (Model → Cohen's d)
  4. Dangerous-neighbourhood comparison    — grouped bar (Language × Model)
  5. Severity distance heatmaps            — side-by-side per model
  6. Summary table                         — cross_model_metrics.json

Usage:
    uv run code/RQ4/4_cross_model.py

Output: results/RQ4/cross_model/
"""

import json
import math
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
OUTPUT_BASE = os.path.join(PROJECT_ROOT, "results/RQ4")
CROSS_DIR = os.path.join(OUTPUT_BASE, "cross_model")

# ── Plot style ─────────────────────────────────────────────────────────────────
plt.rcParams.update(
    {
        "figure.dpi": 150,
        "savefig.dpi": 300,
        "font.size": 10,
        "axes.titlesize": 13,
        "axes.labelsize": 11,
        "legend.fontsize": 8,
        "figure.facecolor": "white",
    }
)

MODEL_PALETTE = [
    "#264653",
    "#2a9d8f",
    "#e9c46a",
    "#f4a261",
    "#e76f51",
    "#6a4c93",
]

SEVERITY_ORDER = ["Easy", "Medium", "Hard", "Super Hard"]


# ── Load all per-model metrics ─────────────────────────────────────────────────


def load_all_metrics() -> dict:
    """
    Scan results/RQ4/ and load metrics for every model that has a rq4_metrics.json.

    Returns:
        dict: {model_key: metrics_dict}.
    """
    all_metrics = {}
    for model_key in MODELS:
        path = os.path.join(OUTPUT_BASE, model_key, "rq4_metrics.json")
        if os.path.exists(path):
            with open(path) as f:
                all_metrics[model_key] = json.load(f)
            print(f"  Loaded metrics for {model_key}")
        else:
            print(f"  No metrics for {model_key} — skipping")
    return all_metrics


# ── Plot 1: Correctness Silhouette Comparison ─────────────────────────────────


def plot_silhouette_comparison(all_metrics: dict, output_dir: str):
    """
    Grouped bar chart: per-language correctness silhouette score for each model.
    """
    model_keys = sorted(all_metrics.keys())
    if len(model_keys) < 2:
        print("  Need ≥2 models — skipping silhouette comparison.")
        return

    all_langs: set = set()
    for m in all_metrics.values():
        all_langs.update(m["silhouette"]["correctness_silhouette_per_language"].keys())
    languages = sorted(all_langs)

    n_models = len(model_keys)
    bar_width = 0.8 / n_models
    x = np.arange(len(languages))

    fig, ax = plt.subplots(figsize=(max(10, len(languages) * 1.6), 6))

    for i, mk in enumerate(model_keys):
        per_lang = all_metrics[mk]["silhouette"]["correctness_silhouette_per_language"]
        scores = [per_lang.get(l, 0.0) for l in languages]
        offset = (i - n_models / 2 + 0.5) * bar_width
        ax.bar(
            x + offset,
            scores,
            bar_width,
            label=mk,
            color=MODEL_PALETTE[i % len(MODEL_PALETTE)],
            edgecolor="white",
            linewidth=0.5,
            alpha=0.85,
        )

    ax.set_xticks(x)
    ax.set_xticklabels([l.upper() for l in languages], rotation=30, ha="right")
    ax.set_ylabel("Correctness Silhouette Score")
    ax.set_xlabel("Language")
    ax.set_title("RQ4: Correctness Silhouette per Language — Model Comparison")
    ax.axhline(y=0, color="grey", linewidth=0.6)
    ax.legend(title="Model", fontsize=8)
    plt.tight_layout()

    path = os.path.join(output_dir, "silhouette_comparison.png")
    fig.savefig(path, bbox_inches="tight")
    plt.close(fig)
    print(f"  Saved {path}")


# ── Plot 2: Separation Score Comparison ───────────────────────────────────────


def plot_separation_comparison(all_metrics: dict, output_dir: str):
    """
    Grouped bar chart: per-language separation score for each model.
    """
    model_keys = sorted(all_metrics.keys())
    if len(model_keys) < 2:
        print("  Need ≥2 models — skipping separation comparison.")
        return

    all_langs: set = set()
    for m in all_metrics.values():
        all_langs.update(m["cluster_distances"]["per_language"].keys())
    languages = sorted(all_langs)

    n_models = len(model_keys)
    bar_width = 0.8 / n_models
    x = np.arange(len(languages))

    fig, ax = plt.subplots(figsize=(max(10, len(languages) * 1.6), 6))

    for i, mk in enumerate(model_keys):
        per_lang = all_metrics[mk]["cluster_distances"]["per_language"]
        scores = [per_lang.get(l, {}).get("separation_score", 0.0) for l in languages]
        offset = (i - n_models / 2 + 0.5) * bar_width
        ax.bar(
            x + offset,
            scores,
            bar_width,
            label=mk,
            color=MODEL_PALETTE[i % len(MODEL_PALETTE)],
            edgecolor="white",
            linewidth=0.5,
            alpha=0.85,
        )

    ax.set_xticks(x)
    ax.set_xticklabels([l.upper() for l in languages], rotation=30, ha="right")
    ax.set_ylabel("Separation Score\n(cross − mean(intra))")
    ax.set_xlabel("Language")
    ax.set_title(
        "RQ4: Correctness Separation Score per Language — Model Comparison\n"
        "(positive = buggy/fixed in different regions)"
    )
    ax.axhline(y=0, color="black", linestyle="--", linewidth=1.0)
    ax.legend(title="Model", fontsize=8)
    plt.tight_layout()

    path = os.path.join(output_dir, "separation_comparison.png")
    fig.savefig(path, bbox_inches="tight")
    plt.close(fig)
    print(f"  Saved {path}")


# ── Plot 3: Effect-Size Comparison ────────────────────────────────────────────


def plot_effect_size_comparison(all_metrics: dict, output_dir: str):
    """
    Horizontal bar chart: Cohen's d (cross vs intra cluster distance) per model.
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
    ax.set_xlabel("Cohen's d   (cross − intra cluster distance)")
    ax.set_title("RQ4: Effect Size of Correctness Separation — Model Comparison")

    for thresh, lbl in [(0.2, "small"), (0.5, "medium"), (0.8, "large")]:
        ax.axvline(x=thresh, color="grey", linestyle=":", linewidth=0.8)
        ax.text(
            thresh,
            len(model_keys) - 0.1,
            lbl,
            ha="center",
            va="bottom",
            fontsize=7,
            color="grey",
        )

    plt.tight_layout()
    path = os.path.join(output_dir, "effect_size_comparison.png")
    fig.savefig(path, bbox_inches="tight")
    plt.close(fig)
    print(f"  Saved {path}")


# ── Plot 4: Dangerous-Neighbourhood Comparison ───────────────────────────────


def plot_dangerous_comparison(all_metrics: dict, output_dir: str):
    """
    Grouped bar chart: per-language % dangerous pairs at threshold=0.10 per model.
    """
    model_keys = sorted(all_metrics.keys())
    if len(model_keys) < 2:
        print("  Need ≥2 models — skipping dangerous comparison.")
        return

    all_langs: set = set()
    for m in all_metrics.values():
        dng = m.get("dangerous_neighbourhoods", {}).get("threshold_0.1", {})
        all_langs.update(dng.get("per_language", {}).keys())
    languages = sorted(all_langs)

    n_models = len(model_keys)
    bar_width = 0.8 / n_models
    x = np.arange(len(languages))

    fig, ax = plt.subplots(figsize=(max(10, len(languages) * 1.6), 6))

    for i, mk in enumerate(model_keys):
        dng = (
            all_metrics[mk].get("dangerous_neighbourhoods", {}).get("threshold_0.1", {})
        )
        per_lang = dng.get("per_language", {})
        pcts = [per_lang.get(l, {}).get("pct", 0.0) for l in languages]
        offset = (i - n_models / 2 + 0.5) * bar_width
        ax.bar(
            x + offset,
            pcts,
            bar_width,
            label=mk,
            color=MODEL_PALETTE[i % len(MODEL_PALETTE)],
            edgecolor="white",
            linewidth=0.5,
            alpha=0.85,
        )

    ax.set_xticks(x)
    ax.set_xticklabels([l.upper() for l in languages], rotation=30, ha="right")
    ax.set_ylabel("% Dangerous Pairs\n(cosine dist < 0.10)")
    ax.set_xlabel("Language")
    ax.set_title(
        "RQ4: Dangerous Neighbourhoods per Language — Model Comparison\n"
        "(lower = model better distinguishes buggy from fixed)"
    )
    ax.legend(title="Model", fontsize=8)
    plt.tight_layout()

    path = os.path.join(output_dir, "dangerous_comparison.png")
    fig.savefig(path, bbox_inches="tight")
    plt.close(fig)
    print(f"  Saved {path}")


# ── Plot 5: Severity Distance Heatmap Comparison ─────────────────────────────


def plot_severity_heatmap_comparison(all_metrics: dict, output_dir: str):
    """
    Side-by-side severity distance heatmaps — one per model.
    """
    model_keys = sorted(all_metrics.keys())

    all_sevs: set = set()
    for m in all_metrics.values():
        mat = m.get("global_severity_distance_matrix", {})
        all_sevs.update(mat.keys())
    severities = [s for s in SEVERITY_ORDER if s in all_sevs]
    if not severities:
        print("  No severity matrices — skipping.")
        return

    n = len(severities)
    n_models = len(model_keys)
    n_cols = min(3, n_models)
    n_rows = math.ceil(n_models / n_cols)

    fig, axes = plt.subplots(
        n_rows, n_cols, figsize=(n * 1.3 * n_cols, n * 1.1 * n_rows)
    )
    axes = np.array(axes).flatten() if n_models > 1 else np.array([axes])

    vmax = 0.75
    last_im = None
    for ax_idx, mk in enumerate(model_keys):
        ax = axes[ax_idx]
        mat_raw = all_metrics[mk].get("global_severity_distance_matrix", {})
        matrix = np.full((n, n), np.nan)
        for i, s1 in enumerate(severities):
            for j, s2 in enumerate(severities):
                v = mat_raw.get(s1, {}).get(s2)
                if v is not None:
                    matrix[i, j] = v

        im = ax.imshow(matrix, aspect="auto", cmap="YlOrRd", vmin=0.0, vmax=vmax)
        last_im = im
        ax.set_xticks(range(n))
        ax.set_xticklabels(severities, rotation=40, ha="right", fontsize=8)
        ax.set_yticks(range(n))
        ax.set_yticklabels(severities, fontsize=8)
        ax.set_title(mk, fontsize=10, fontweight="bold")

        for i in range(n):
            for j in range(n):
                if not np.isnan(matrix[i, j]):
                    ax.text(
                        j,
                        i,
                        f"{matrix[i, j]:.2f}",
                        ha="center",
                        va="center",
                        fontsize=7,
                        color="black" if matrix[i, j] < 0.5 else "white",
                    )

    for ax_idx in range(len(model_keys), len(axes)):
        axes[ax_idx].set_visible(False)

    if last_im is not None:
        fig.colorbar(
            last_im,
            ax=axes[: len(model_keys)],
            shrink=0.5,
            label="Mean Cosine Distance",
        )

    fig.suptitle(
        "RQ4: Severity Distance Matrix — Model Comparison", y=1.01, fontsize=12
    )
    plt.tight_layout()

    path = os.path.join(output_dir, "severity_heatmap_comparison.png")
    fig.savefig(path, bbox_inches="tight")
    plt.close(fig)
    print(f"  Saved {path}")


# ── Summary JSON + printed table ──────────────────────────────────────────────


def save_summary(all_metrics: dict, output_dir: str) -> dict:
    """Save a concise cross-model summary JSON and print a Markdown table."""
    summary = {}
    for mk in sorted(all_metrics.keys()):
        m = all_metrics[mk]
        stat = m.get("statistical_tests", {})
        sil = m["silhouette"]
        pw = m["pairwise_distances"]
        cl = m["cluster_distances"]
        dng = m.get("dangerous_neighbourhoods", {}).get("threshold_0.1", {})

        per_lang_sil = sil["correctness_silhouette_per_language"]
        best_lang = max(per_lang_sil, key=per_lang_sil.get) if per_lang_sil else "N/A"

        summary[mk] = {
            "model_name": m.get("model_name", mk),
            "correctness_silhouette_overall": sil["correctness_silhouette_overall"],
            "language_silhouette_overall": sil["language_silhouette_overall"],
            "pairwise_distance_mean": pw["overall"]["mean"],
            "cross_cluster_distance": cl["overall_cross_mean"],
            "intra_cluster_distance": cl["overall_intra_mean"],
            "distance_gap": round(
                (cl["overall_cross_mean"] or 0) - (cl["overall_intra_mean"] or 0), 4
            ),
            "cohens_d": stat.get("effect_size", {}).get("cohens_d"),
            "effect_interpretation": stat.get("effect_size", {}).get("interpretation"),
            "t_statistic": stat.get("t_test", {}).get("t_statistic"),
            "p_value": stat.get("t_test", {}).get("p_value"),
            "dangerous_pct_overall": dng.get("overall", {}).get("pct"),
            "best_language_silhouette": best_lang,
        }

    path = os.path.join(output_dir, "cross_model_metrics.json")
    with open(path, "w") as f:
        json.dump(summary, f, indent=2)
    print(f"  Saved {path}")

    # Print markdown table
    print("\n── Cross-Model Summary ──")
    header = (
        f"{'Model':12s} | {'Sil(corr)':10s} | {'Pair dist':10s} | "
        f"{'Cross':8s} | {'Intra':8s} | {'Gap':7s} | {'Cohen d':8s} | "
        f"{'Effect':8s} | {'Danger%':8s}"
    )
    print(header)
    print("-" * len(header))
    for mk, s in sorted(summary.items()):
        d_pct = (
            f"{s['dangerous_pct_overall']:.1f}"
            if s["dangerous_pct_overall"] is not None
            else "N/A"
        )
        print(
            f"{mk:12s} | "
            f"{s['correctness_silhouette_overall']:10.4f} | "
            f"{(s['pairwise_distance_mean'] or 0):10.4f} | "
            f"{(s['cross_cluster_distance'] or 0):8.4f} | "
            f"{(s['intra_cluster_distance'] or 0):8.4f} | "
            f"{s['distance_gap']:7.4f} | "
            f"{(s['cohens_d'] or 0):8.4f} | "
            f"{(s['effect_interpretation'] or '?'):8s} | "
            f"{d_pct:>8s}"
        )

    return summary


# ── Main ──────────────────────────────────────────────────────────────────────


def main():
    print("Loading per-model RQ4 metrics …")
    all_metrics = load_all_metrics()

    if not all_metrics:
        print("ERROR: No model metrics found. Run 2_analysis.py first.")
        sys.exit(1)

    os.makedirs(CROSS_DIR, exist_ok=True)

    print(f"\n── Cross-Model Comparison ({len(all_metrics)} models) ──")
    plot_silhouette_comparison(all_metrics, CROSS_DIR)
    plot_separation_comparison(all_metrics, CROSS_DIR)
    plot_effect_size_comparison(all_metrics, CROSS_DIR)
    plot_dangerous_comparison(all_metrics, CROSS_DIR)
    plot_severity_heatmap_comparison(all_metrics, CROSS_DIR)
    save_summary(all_metrics, CROSS_DIR)

    print(f"\nDone! Cross-model results saved to {CROSS_DIR}")


if __name__ == "__main__":
    main()

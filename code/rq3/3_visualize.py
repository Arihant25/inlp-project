"""
RQ3: Algorithmic Complexity & Language Clustering — Dimensionality Reduction & Visualization

Generates publication-quality plots from the RQ3 embeddings and metrics:
  1. t-SNE scatter: all embeddings, colour = complexity class
  2. t-SNE faceted: one panel per language, colour = complexity class
  3. Complexity-class silhouette bar chart per language  (key RQ3 result)
  4. Separability ratio bar chart per language  (cross / intra-complexity distance)
  5. Global complexity distance heatmap  (pairwise mean cosine distance between classes)
  6. Per-language complexity distance heatmaps  (9 small multiples in one figure)

Usage:
    uv run code/rq3/3_visualize.py                    # all models
    uv run code/rq3/3_visualize.py --model unixcoder  # single model

Output: results/rq3/{model_key}/*.png
"""

import argparse
import json
import os
import sys
import math

import matplotlib.pyplot as plt
import matplotlib.patches as mpatches
import matplotlib.lines as mlines
import numpy as np
import pandas as pd
from sklearn.manifold import TSNE

# ── Paths ─────────────────────────────────────────────────────────────────────
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
CODE_DIR = os.path.dirname(SCRIPT_DIR)
sys.path.insert(0, CODE_DIR)
from embedding import MODELS

PROJECT_ROOT = os.path.dirname(CODE_DIR)
OUTPUT_BASE = os.path.join(PROJECT_ROOT, "results/rq3")

# ── Plot style ─────────────────────────────────────────────────────────────────
plt.rcParams.update({
    "figure.dpi": 150,
    "savefig.dpi": 300,
    "font.size":   10,
    "axes.titlesize": 13,
    "axes.labelsize": 11,
    "legend.fontsize": 8,
    "figure.facecolor": "white",
})

# Canonical complexity-class order + colours
COMPLEXITY_ORDER = [
    "O(1)", "O(log n)", "O(n)", "O(n log n)",
    "O(n²)", "O(n³)", "O(2^n)", "O(n!)", "Other",
]

# Colourblind-friendly palette (one colour per complexity class)
COMPLEXITY_PALETTE = {
    "O(1)":       "#4dac26",   # green
    "O(log n)":   "#7fbfff",   # light blue
    "O(n)":       "#1f78b4",   # blue
    "O(n log n)": "#fdbf6f",   # orange
    "O(n²)":      "#e31a1c",   # red
    "O(n³)":      "#9e1a9e",   # purple
    "O(2^n)":     "#b15928",   # brown
    "O(n!)":      "#ff7f00",   # dark orange
    "Other":      "#aaaaaa",   # grey
}

# Palette for languages
LANG_PALETTE = [
    "#1f77b4", "#ff7f0e", "#2ca02c", "#d62728", "#9467bd",
    "#8c564b", "#e377c2", "#7f7f7f", "#bcbd22", "#17becf",
]


def to_matrix(df: pd.DataFrame) -> np.ndarray:
    return np.vstack(df["embedding"].values)


# Distinct markers for languages (up to 10)
LANG_MARKER_LIST = ["o", "s", "^", "D", "v", "P", "X", "*", "h", "<"]


def build_lang_marker(languages: list) -> dict:
    """Map each language to a distinct matplotlib marker shape."""
    return {lang: LANG_MARKER_LIST[i % len(LANG_MARKER_LIST)]
            for i, lang in enumerate(sorted(languages))}


# ── Plot 1: t-SNE (all languages, colour = complexity class, shape = language) ─

def plot_tsne_all(df: pd.DataFrame, output_dir: str, model_label: str = ""):
    """
    Generate a combined 2-D t-SNE scatter of all code embeddings.

    Points are coloured by algorithmic complexity class and shaped by language.
    Two legends are shown:
      - Right: complexity class → colour
      - Bottom: language → marker shape (one column per language)

    Args:
        df (pd.DataFrame): DataFrame with 'embedding', 'complexity_class', 'language'.
        output_dir (str): Directory to save 'tsne_all_complexities.png'.
        model_label (str): Optional model name prefix for the plot title.
    """
    print("  Running t-SNE (all languages) …")
    df_plot = df[df["complexity_class"] != "Other"].copy()
    X = to_matrix(df_plot)

    perplexity = min(30, max(5, len(X) // 10))
    tsne = TSNE(n_components=2, random_state=42,
                perplexity=perplexity, max_iter=1000)
    X_2d = tsne.fit_transform(X)

    classes_present = [c for c in COMPLEXITY_ORDER if c in df_plot["complexity_class"].unique()]
    languages = sorted(df_plot["language"].unique())
    lang_marker = build_lang_marker(languages)

    # Reserve space: right for complexity legend, bottom for language legend
    n_langs = len(languages)
    bottom_pad = 0.13
    fig, ax = plt.subplots(figsize=(14, 11))
    fig.subplots_adjust(bottom=bottom_pad, right=0.82)

    for cc in classes_present:
        color = COMPLEXITY_PALETTE.get(cc, "#aaaaaa")
        for lang in languages:
            mask = ((df_plot["complexity_class"] == cc) &
                    (df_plot["language"] == lang)).values
            if mask.sum() == 0:
                continue
            ax.scatter(X_2d[mask, 0], X_2d[mask, 1],
                       c=color,
                       marker=lang_marker[lang],
                       s=25, alpha=0.65,
                       edgecolors="white", linewidths=0.3,
                       zorder=2)

    # Right legend — complexity class → colour
    complexity_handles = [
        mpatches.Patch(facecolor=COMPLEXITY_PALETTE.get(cc, "#aaa"),
                       edgecolor="grey", linewidth=0.5, label=cc)
        for cc in classes_present
    ]
    legend_cc = ax.legend(
        handles=complexity_handles,
        title="Complexity class",
        loc="upper left",
        bbox_to_anchor=(1.01, 1.0),
        bbox_transform=ax.transAxes,
        frameon=True,
        framealpha=0.9,
    )
    ax.add_artist(legend_cc)

    # Bottom legend — language → marker shape, one column per language
    col_width = 1.0 / n_langs
    for k, lang in enumerate(languages):
        marker = lang_marker[lang]
        handle = [mlines.Line2D(
            [], [],
            marker=marker, color="#444444",
            markeredgecolor="white", markeredgewidth=0.3,
            markersize=7, linestyle="None",
            label=lang,
        )]
        x_center = (k + 0.5) * col_width
        fig.legend(
            handles=handle,
            title=lang,
            title_fontproperties={"weight": "bold", "size": 8},
            loc="lower center",
            bbox_to_anchor=(x_center, 0.01),
            bbox_transform=fig.transFigure,
            frameon=True,
            framealpha=0.88,
            fontsize=7.5,
            ncol=1,
            borderpad=0.5,
            handletextpad=0.4,
        )

    title = "RQ3: t-SNE of Code Embeddings\n(colour = Complexity class,  shape = Language)"
    if model_label:
        title = f"[{model_label}] {title}"
    ax.set_title(title)
    ax.set_xlabel("t-SNE 1")
    ax.set_ylabel("t-SNE 2")

    path = os.path.join(output_dir, "tsne_all_complexities.png")
    fig.savefig(path, bbox_inches="tight")
    plt.close(fig)
    print(f"    Saved {path}")


# ── Plot 2: t-SNE faceted by language ────────────────────────────────────────

def plot_tsne_faceted(df: pd.DataFrame, output_dir: str, model_label: str = ""):
    """
    Generate a grid of t-SNE panels — one per language — each coloured by
    complexity class.

    Comparing panels side-by-side reveals which languages produce the cleaner
    separation between complexity clusters, directly addressing RQ3.

    Args:
        df (pd.DataFrame): DataFrame with 'embedding', 'language', 'complexity_class'.
        output_dir (str): Directory to save 'tsne_faceted_by_language.png'.
        model_label (str): Optional model name prefix for the suptitle.
    """
    print("  Running t-SNE (faceted by language) …")
    df_plot = df[df["complexity_class"] != "Other"].copy()
    languages = sorted(df_plot["language"].unique())
    n_langs = len(languages)
    n_cols = min(3, n_langs)
    n_rows = math.ceil(n_langs / n_cols)

    # Compute a single shared 2-D embedding so all panels are comparable
    X_all = to_matrix(df_plot)
    perplexity = min(30, max(5, len(X_all) // 10))
    tsne = TSNE(n_components=2, random_state=42,
                perplexity=perplexity, max_iter=1000)
    X_2d = tsne.fit_transform(X_all)
    df_plot = df_plot.copy()
    df_plot["tsne_1"] = X_2d[:, 0]
    df_plot["tsne_2"] = X_2d[:, 1]

    fig, axes = plt.subplots(n_rows, n_cols,
                              figsize=(6 * n_cols, 5 * n_rows))
    axes = np.array(axes).flatten()

    classes_present = [c for c in COMPLEXITY_ORDER
                        if c in df_plot["complexity_class"].unique()]

    for ax_idx, lang in enumerate(languages):
        ax = axes[ax_idx]
        lang_df = df_plot[df_plot["language"] == lang]
        for cc in classes_present:
            mask = lang_df["complexity_class"] == cc
            sub = lang_df[mask]
            if sub.empty:
                continue
            ax.scatter(sub["tsne_1"], sub["tsne_2"],
                       c=COMPLEXITY_PALETTE.get(cc, "#aaaaaa"),
                       label=cc, s=15, alpha=0.7,
                       edgecolors="none")
        ax.set_title(lang, fontsize=11, fontweight="bold")
        ax.set_xlabel("t-SNE 1", fontsize=9)
        ax.set_ylabel("t-SNE 2", fontsize=9)
        ax.tick_params(labelsize=7)

    # Hide unused panels
    for ax_idx in range(len(languages), len(axes)):
        axes[ax_idx].set_visible(False)

    # Shared legend
    handles = [mpatches.Patch(color=COMPLEXITY_PALETTE.get(cc, "#aaa"), label=cc)
               for cc in classes_present]
    fig.legend(handles=handles, title="Complexity class",
               loc="lower center", ncol=len(classes_present),
               bbox_to_anchor=(0.5, 0.0), fontsize=9, framealpha=0.9)

    sup = "RQ3: t-SNE by Language (colour = Complexity Class)"
    if model_label:
        sup = f"[{model_label}] {sup}"
    fig.suptitle(sup, y=1.01, fontsize=13)
    plt.tight_layout()

    path = os.path.join(output_dir, "tsne_faceted_by_language.png")
    fig.savefig(path, bbox_inches="tight")
    plt.close(fig)
    print(f"    Saved {path}")


# ── Plot 3: Silhouette bar chart per language ─────────────────────────────────

def plot_silhouette_bars(metrics: dict, output_dir: str, model_label: str = ""):
    """
    Bar chart showing per-language complexity-class silhouette scores.

    The key RQ3 result: a higher score for a language means its code embeddings
    separate O(n) from O(n²) etc. more cleanly — the language is more 'expressive'
    of algorithmic complexity in embedding space.

    Args:
        metrics (dict): Loaded rq3_metrics.json.
        output_dir (str): Save directory for 'silhouette_per_language.png'.
        model_label (str): Optional model name prefix.
    """
    per_lang = metrics["silhouette"]["complexity_silhouette_per_language"]
    overall  = metrics["silhouette"]["complexity_silhouette_overall"]
    diff_sil = metrics["silhouette"].get("difficulty_silhouette_overall")

    # Sort by score descending
    langs_sorted = sorted(per_lang.keys(), key=lambda l: per_lang[l], reverse=True)
    scores = [per_lang[l] for l in langs_sorted]

    lang_colors = {l: LANG_PALETTE[i % len(LANG_PALETTE)]
                   for i, l in enumerate(sorted(per_lang.keys()))}
    colors = [lang_colors[l] for l in langs_sorted]

    fig, ax = plt.subplots(figsize=(11, 5))
    bars = ax.bar(langs_sorted, scores, color=colors, edgecolor="white", linewidth=0.8)

    for bar, score in zip(bars, scores):
        yoff = bar.get_height() + (0.005 if score >= 0 else -0.02)
        ax.text(bar.get_x() + bar.get_width() / 2, yoff,
                f"{score:.3f}", ha="center", va="bottom", fontsize=8.5)

    ax.axhline(y=overall, color="crimson", linestyle="--", linewidth=1.5,
               label=f"Overall = {overall:.4f}")
    if diff_sil is not None:
        ax.axhline(y=diff_sil, color="steelblue", linestyle=":", linewidth=1.3,
                   label=f"Difficulty baseline = {diff_sil:.4f}")
    ax.axhline(y=0, color="grey", linewidth=0.6)

    y_min = min(min(scores) - 0.05, -0.02)
    y_max = max(max(scores), overall) + 0.06
    ax.set_ylim(y_min, y_max)

    ax.set_ylabel("Complexity-class Silhouette Score")
    ax.set_xlabel("Language")
    title = ("RQ3: Complexity-class Clustering Quality per Language\n"
             "(higher = complexity classes are more separable in that language)")
    if model_label:
        title = f"[{model_label}] {title}"
    ax.set_title(title)
    ax.legend(fontsize=9)
    plt.xticks(rotation=25, ha="right")
    plt.tight_layout()

    path = os.path.join(output_dir, "silhouette_per_language.png")
    fig.savefig(path, bbox_inches="tight")
    plt.close(fig)
    print(f"    Saved {path}")


# ── Plot 4: Separability ratio bar chart ─────────────────────────────────────

def plot_separability_ratio(metrics: dict, output_dir: str, model_label: str = ""):
    """
    Bar chart of the cross-complexity / intra-complexity distance ratio per language.

    A ratio > 1 means embeddings differ MORE across complexity classes than within
    the same class — i.e., the language's syntax makes algorithmic differences detectable.

    Args:
        metrics (dict): Loaded rq3_metrics.json.
        output_dir (str): Save directory for 'separability_ratio.png'.
        model_label (str): Optional model name prefix.
    """
    per_lang = metrics["distances"]["per_language"]
    data = {l: v["separability_ratio"]
            for l, v in per_lang.items() if v["separability_ratio"] is not None}

    langs_sorted = sorted(data.keys(), key=lambda l: data[l], reverse=True)
    ratios = [data[l] for l in langs_sorted]

    lang_colors = {l: LANG_PALETTE[i % len(LANG_PALETTE)]
                   for i, l in enumerate(sorted(data.keys()))}
    colors = [lang_colors[l] for l in langs_sorted]

    fig, ax = plt.subplots(figsize=(11, 5))
    bars = ax.bar(langs_sorted, ratios, color=colors, edgecolor="white", linewidth=0.8)

    for bar, ratio in zip(bars, ratios):
        ax.text(bar.get_x() + bar.get_width() / 2,
                bar.get_height() + 0.003,
                f"{ratio:.3f}", ha="center", va="bottom", fontsize=8.5)

    ax.axhline(y=1.0, color="black", linestyle="--", linewidth=1.3,
               label="ratio = 1  (cross = intra)")

    ax.set_ylim(0, max(ratios) * 1.12)
    ax.set_ylabel("Cross / Intra Complexity Distance Ratio")
    ax.set_xlabel("Language")
    title = ("RQ3: Complexity Separability Ratio per Language\n"
             "(ratio > 1 means cross-complexity code is more distant than same-complexity)")
    if model_label:
        title = f"[{model_label}] {title}"
    ax.set_title(title)
    ax.legend(fontsize=9)
    plt.xticks(rotation=25, ha="right")
    plt.tight_layout()

    path = os.path.join(output_dir, "separability_ratio.png")
    fig.savefig(path, bbox_inches="tight")
    plt.close(fig)
    print(f"    Saved {path}")


# ── Plot 5: Global complexity distance heatmap ────────────────────────────────

def plot_global_complexity_heatmap(metrics: dict, output_dir: str, model_label: str = ""):
    """
    Heatmap of mean pairwise cosine distances between complexity classes, pooled
    across all languages.

    Shows the geometric layout of complexity classes in embedding space:
    e.g., whether O(n) and O(n log n) are adjacent or whether O(n!) is far from O(1).

    Args:
        metrics (dict): Loaded rq3_metrics.json.
        output_dir (str): Save directory for 'global_complexity_heatmap.png'.
        model_label (str): Optional model name prefix.
    """
    mat_raw = metrics.get("global_complexity_distance_matrix", {})
    if not mat_raw:
        print("    No global complexity matrix — skipping.")
        return

    # Use only complexity classes present in the data
    classes = sorted(set(mat_raw.keys()) | {k for v in mat_raw.values() for k in v},
                     key=lambda c: COMPLEXITY_ORDER.index(c)
                                   if c in COMPLEXITY_ORDER else 99)

    n = len(classes)
    matrix = np.full((n, n), np.nan)
    for i, c1 in enumerate(classes):
        for j, c2 in enumerate(classes):
            val = mat_raw.get(c1, {}).get(c2)
            if val is not None:
                matrix[i, j] = val

    fig, ax = plt.subplots(figsize=(max(7, n * 1.0), max(6, n * 0.9)))
    im = ax.imshow(matrix, aspect="auto", cmap="YlOrRd", vmin=0.0, vmax=0.8)
    cbar = fig.colorbar(im, ax=ax, shrink=0.8)
    cbar.set_label("Mean Cosine Distance")

    ax.set_xticks(range(n))
    ax.set_xticklabels(classes, rotation=35, ha="right", fontsize=9)
    ax.set_yticks(range(n))
    ax.set_yticklabels(classes, fontsize=9)

    for i in range(n):
        for j in range(n):
            if not np.isnan(matrix[i, j]):
                ax.text(j, i, f"{matrix[i, j]:.3f}",
                        ha="center", va="center", fontsize=7.5,
                        color="black" if matrix[i, j] < 0.5 else "white")

    title = ("RQ3: Pairwise Complexity-class Cosine Distance\n"
             "(pooled across all languages)")
    if model_label:
        title = f"[{model_label}] {title}"
    ax.set_title(title)
    plt.tight_layout()

    path = os.path.join(output_dir, "global_complexity_heatmap.png")
    fig.savefig(path, bbox_inches="tight")
    plt.close(fig)
    print(f"    Saved {path}")


# ── Plot 6: Per-language complexity distance heatmaps (small multiples) ───────

def plot_per_language_heatmaps(metrics: dict, output_dir: str, model_label: str = ""):
    """
    A grid of small heatmaps — one per language — each showing the pairwise
    complexity-class cosine distance matrix for that language.

    Enables direct comparison: does Python produce larger intra-class vs cross-class
    distances than Go?  Bright O(n²)-vs-O(1) cells indicate that complexity IS
    detectable; uniform grey means the embeddings cannot distinguish them.

    Args:
        metrics (dict): Loaded rq3_metrics.json.
        output_dir (str): Save directory for 'per_language_complexity_heatmaps.png'.
        model_label (str): Optional model name prefix.
    """
    per_lang_raw = metrics.get("per_language_complexity_distance_matrix", {})
    if not per_lang_raw:
        print("    No per-language complexity matrix — skipping.")
        return

    # Determine shared complexity class list
    all_classes: set[str] = set()
    for mat in per_lang_raw.values():
        all_classes.update(mat.keys())
    classes = sorted(all_classes,
                     key=lambda c: COMPLEXITY_ORDER.index(c)
                                   if c in COMPLEXITY_ORDER else 99)

    languages = sorted(per_lang_raw.keys())
    n_langs = len(languages)
    n_cols = min(3, n_langs)
    n_rows = math.ceil(n_langs / n_cols)
    n = len(classes)

    fig, axes = plt.subplots(n_rows, n_cols,
                              figsize=(max(5, n * 0.8) * n_cols,
                                       max(4, n * 0.7) * n_rows))
    axes = np.array(axes).flatten()

    vmax = 0.8
    for ax_idx, lang in enumerate(languages):
        ax = axes[ax_idx]
        mat_raw = per_lang_raw[lang]
        matrix = np.full((n, n), np.nan)
        for i, c1 in enumerate(classes):
            for j, c2 in enumerate(classes):
                val = mat_raw.get(c1, {}).get(c2)
                if val is not None:
                    matrix[i, j] = val

        im = ax.imshow(matrix, aspect="auto", cmap="Blues",
                       vmin=0.0, vmax=vmax)

        ax.set_xticks(range(n))
        ax.set_xticklabels(classes, rotation=45, ha="right", fontsize=7)
        ax.set_yticks(range(n))
        ax.set_yticklabels(classes, fontsize=7)
        ax.set_title(lang, fontsize=10, fontweight="bold")

        for i in range(n):
            for j in range(n):
                if not np.isnan(matrix[i, j]):
                    ax.text(j, i, f"{matrix[i, j]:.2f}",
                            ha="center", va="center", fontsize=6,
                            color="black" if matrix[i, j] < 0.5 else "white")

    # Shared colourbar and hide empty panels
    for ax_idx in range(len(languages), len(axes)):
        axes[ax_idx].set_visible(False)

    # Add a shared colourbar for the last im
    fig.colorbar(im, ax=axes[:len(languages)], shrink=0.5,
                 label="Mean Cosine Distance")

    sup = ("RQ3: Per-language Complexity-class Distance Matrices\n"
           "(blue intensity = cosine distance between complexity classes)")
    if model_label:
        sup = f"[{model_label}] {sup}"
    fig.suptitle(sup, y=1.01, fontsize=12)
    plt.tight_layout()

    path = os.path.join(output_dir, "per_language_complexity_heatmaps.png")
    fig.savefig(path, bbox_inches="tight")
    plt.close(fig)
    print(f"    Saved {path}")


# ── Run visualisation for one model ──────────────────────────────────────────

def run_visualize(model_key: str) -> bool:
    """Generate all RQ3 plots for a single model."""
    model_dir       = os.path.join(OUTPUT_BASE, model_key)
    embeddings_file = os.path.join(model_dir, "rq3_embeddings.parquet")
    metrics_file    = os.path.join(model_dir, "rq3_metrics.json")

    if not os.path.exists(embeddings_file):
        print(f"  Embeddings not found at {embeddings_file} — skipping {model_key}.")
        return False
    if not os.path.exists(metrics_file):
        print(f"  Metrics not found at {metrics_file} — run 2_analysis.py first.")
        return False

    print(f"\n── Loading data for {model_key} ──")
    df = pd.read_parquet(embeddings_file)
    with open(metrics_file, "r") as f:
        metrics = json.load(f)

    model_label = model_key
    print(f"  {len(df)} solutions  |  {df['problem_slug'].nunique()} problems  |  "
          f"{df['language'].nunique()} languages")

    os.makedirs(model_dir, exist_ok=True)

    print(f"\n── Generating Visualizations ──")
    plot_tsne_all(df, model_dir, model_label)
    plot_tsne_faceted(df, model_dir, model_label)
    plot_silhouette_bars(metrics, model_dir, model_label)
    plot_separability_ratio(metrics, model_dir, model_label)
    plot_global_complexity_heatmap(metrics, model_dir, model_label)
    plot_per_language_heatmaps(metrics, model_dir, model_label)

    print(f"\nAll plots saved to {model_dir}/")
    return True


# ── Main ──────────────────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(
        description="Generate RQ3 visualizations for one or all models."
    )
    parser.add_argument(
        "--model", type=str, default="all",
        choices=list(MODELS.keys()) + ["all"],
        help="Model to visualize (default: all)",
    )
    args = parser.parse_args()

    models_to_run = ([args.model] if args.model != "all"
                     else list(MODELS.keys()))

    for model_key in models_to_run:
        print(f"\n{'='*60}")
        print(f"  Visualizing model: {model_key}")
        print(f"{'='*60}")
        run_visualize(model_key)

    print("\nDone!")


if __name__ == "__main__":
    main()

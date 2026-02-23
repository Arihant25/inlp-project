"""
RQ2: Framework-Driven Dialects — Dimensionality Reduction & Visualization

Generates publication-quality plots from the RQ2 embeddings and metrics:
  1. t-SNE scatter plot  (colour = Language, shape = Framework within that language)
  2. Cross-Framework Distance heatmap — same-language pairs only, grouped by language
  3. Per-language framework silhouette bar chart (bars coloured by language)

Usage:
    python 3_visualize.py                    # all models
    python 3_visualize.py --model unixcoder  # single model

Output: results/rq2/{model_key}/*.png
"""

import argparse
import json
import os
import sys

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
OUTPUT_BASE = os.path.join(PROJECT_ROOT, "results/rq2")

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

# Colourblind-friendly palette for languages (up to 8)
LANG_PALETTE = [
    "#274001",
    "#828a00",
    "#f29f05",
    "#f25c05",
    "#d6568c",
    "#4d8584",
    "#a62f03",
    "#400d01",
]

# Distinct markers for frameworks within each language (up to ~10)
MARKER_LIST = ["o", "s", "^", "D", "v", "P", "X", "*", "h", "<"]


def embeddings_to_matrix(df: pd.DataFrame) -> np.ndarray:
    return np.vstack(df["embedding"].values)


def build_lang_color(languages: list) -> dict:
    """
    Map each language to a consistent colour from the colourblind-friendly palette.
    
    Args:
        languages (list): List of unique language names.
        
    Returns:
        dict: Mapping of {language_name: hex_colour_code}.
    """
    return {lang: LANG_PALETTE[i % len(LANG_PALETTE)] for i, lang in enumerate(sorted(languages))}


def build_fw_marker(frameworks_for_lang: list) -> dict:
    """
    Map frameworks within a specific language to distinct marker shapes.
    
    Args:
        frameworks_for_lang (list): List of framework names for a single language.
        
    Returns:
        dict: Mapping of {framework_name: matplotlib_marker_string}.
    """
    return {fw: MARKER_LIST[i % len(MARKER_LIST)] for i, fw in enumerate(sorted(frameworks_for_lang))}


# ── Plot 1: t-SNE Scatter ────────────────────────────────────────────────────

def plot_tsne(df: pd.DataFrame, output_dir: str, model_label: str = ""):
    """
    Generate and save a 2D t-SNE scatter plot of code embeddings.
    
    Dimensionality reduction is used to visualize high-dimensional embeddings.
    Points are coloured by Language (the primary grouping) and shaped by 
    Framework (to expose dialects variation within each language).
    
    Args:
        df (pd.DataFrame): DataFrame with 'embedding', 'language', and 'framework'.
        output_dir (str): Directory where 'tsne_scatter.png' will be saved.
        model_label (str, optional): Label to prepend to the plot title (e.g. for a specific model).
    """
    print("Running t-SNE ...")
    X = embeddings_to_matrix(df)
    tsne = TSNE(n_components=2, random_state=42, perplexity=30, max_iter=1000)
    X_2d = tsne.fit_transform(X)

    languages = sorted(df["language"].unique())
    lang_color = build_lang_color(languages)

    lang_fw_marker: dict[str, dict[str, str]] = {}
    for lang in languages:
        fws = sorted(df.loc[df["language"] == lang, "framework"].unique())
        lang_fw_marker[lang] = build_fw_marker(fws)

    max_fw = max(len(v) for v in lang_fw_marker.values())
    legend_col_h = (1 + max_fw) * 0.015
    bottom_pad = legend_col_h + 0.10

    fig, ax = plt.subplots(figsize=(14, 11))
    fig.subplots_adjust(bottom=bottom_pad, right=0.82)

    for lang in languages:
        color = lang_color[lang]
        fw_marker = lang_fw_marker[lang]
        for fw, marker in fw_marker.items():
            mask = (df["language"] == lang) & (df["framework"] == fw)
            if mask.sum() == 0:
                continue
            idx = mask.values
            ax.scatter(
                X_2d[idx, 0], X_2d[idx, 1],
                c=[color],
                marker=marker,
                s=45, alpha=0.75,
                edgecolors="white", linewidths=0.3,
                zorder=2,
            )

    # Language legend (right)
    lang_handles = [
        mpatches.Patch(facecolor=lang_color[lang], edgecolor="grey", linewidth=0.5, label=lang)
        for lang in languages
    ]
    legend_lang = ax.legend(
        handles=lang_handles,
        title="Language",
        loc="upper left",
        bbox_to_anchor=(1.01, 1.0),
        bbox_transform=ax.transAxes,
        frameon=True,
        framealpha=0.9,
    )
    ax.add_artist(legend_lang)

    # Framework legend (below, one column per language)
    n_langs = len(languages)
    col_width = 1.0 / n_langs
    legend_y = 0.02

    for k, lang in enumerate(languages):
        color = lang_color[lang]
        handles = [
            mlines.Line2D(
                [], [],
                marker=marker, color=color,
                markeredgecolor="white", markeredgewidth=0.3,
                markersize=7, linestyle="None",
                label=fw,
            )
            for fw, marker in sorted(lang_fw_marker[lang].items())
        ]
        x_center = (k + 0.5) * col_width
        fig.legend(
            handles=handles,
            title=lang,
            title_fontproperties={"weight": "bold", "size": 8},
            loc="lower center",
            bbox_to_anchor=(x_center, legend_y),
            bbox_transform=fig.transFigure,
            frameon=True,
            framealpha=0.88,
            fontsize=7.5,
            ncol=1,
            borderpad=0.5,
            handletextpad=0.4,
            labelcolor=color,
        )

    title = "RQ2: t-SNE of Code Embeddings\n(colour = Language,  shape = Framework)"
    if model_label:
        title = f"[{model_label}] {title}"
    ax.set_title(title)
    ax.set_xlabel("t-SNE 1")
    ax.set_ylabel("t-SNE 2")

    path = os.path.join(output_dir, "tsne_scatter.png")
    fig.savefig(path, bbox_inches="tight")
    plt.close(fig)
    print(f"  Saved {path}")


# ── Plot 2: Cross-Framework Distance Heatmap ─────────────────────────────────

def plot_distance_heatmap(metrics: dict, df_meta: pd.DataFrame, output_dir: str,
                          model_label: str = ""):
    """
    Generate and save a heatmap showing pairwise cosine distances between frameworks.
    
    Only same-language framework pairs are shown (e.g., 'Actix vs Axum' for Rust).
    Columns are grouped by language on the x-axis, and software patterns are listed
    on the y-axis, illustrating which patterns differ most between frameworks.
    
    Args:
        metrics (dict): Dictionary output from compute_cross_framework_distances.
        df_meta (pd.DataFrame): Metadata mapping frameworks to languages.
        output_dir (str): Directory where 'distance_heatmap.png' will be saved.
        model_label (str, optional): Label to prepend to the plot title.
    """
    pair_data = metrics["distances"]["cross_fw_pair_distances_by_pattern"]
    patterns = sorted(pair_data.keys())

    fw_to_lang = (
        df_meta.drop_duplicates(subset=["framework", "language"])
        .set_index("framework")["language"]
        .to_dict()
    )

    def is_same_lang(pair_str: str) -> bool:
        parts = pair_str.split(" vs ", 1)
        if len(parts) != 2:
            return False
        fw_a, fw_b = parts[0].strip(), parts[1].strip()
        lang_a = fw_to_lang.get(fw_a)
        lang_b = fw_to_lang.get(fw_b)
        return lang_a is not None and lang_b is not None and lang_a == lang_b

    all_pairs_raw = set()
    for pat in patterns:
        all_pairs_raw.update(pair_data[pat].keys())

    same_lang_pairs = sorted(p for p in all_pairs_raw if is_same_lang(p))

    if not same_lang_pairs:
        print("  WARNING: No same-language framework pairs found — skipping heatmap.")
        return

    pair_to_lang = {}
    for pair in same_lang_pairs:
        fw_a = pair.split(" vs ", 1)[0].strip()
        pair_to_lang[pair] = fw_to_lang.get(fw_a, "Unknown")

    ordered_langs = sorted(set(pair_to_lang.values()))
    ordered_pairs = []
    lang_boundaries = {}
    for lang in ordered_langs:
        group = sorted(p for p, l in pair_to_lang.items() if l == lang)
        lang_boundaries[lang] = (len(ordered_pairs), len(ordered_pairs) + len(group) - 1)
        ordered_pairs.extend(group)

    matrix = np.full((len(patterns), len(ordered_pairs)), np.nan)
    for i, pat in enumerate(patterns):
        for j, pair in enumerate(ordered_pairs):
            if pair in pair_data[pat]:
                matrix[i, j] = pair_data[pat][pair]

    fig_w = max(14, len(ordered_pairs) * 0.9)
    fig_h = max(5, len(patterns) * 0.8)
    fig, ax = plt.subplots(figsize=(fig_w, fig_h))

    im = ax.imshow(matrix, aspect="auto", cmap="YlOrRd", vmin=0.0, vmax=0.65)
    cbar = fig.colorbar(im, ax=ax, shrink=0.8)
    cbar.set_label("Cosine Distance")

    ax.set_xticks(range(len(ordered_pairs)))

    short_labels = []
    for pair in ordered_pairs:
        fw_a, fw_b = pair.split(" vs ", 1)
        short_labels.append(f"{fw_a.strip()}\nvs\n{fw_b.strip()}")
    ax.set_xticklabels(short_labels, rotation=0, ha="center", fontsize=6.5)

    ax.set_yticks(range(len(patterns)))
    ax.set_yticklabels(patterns, fontsize=9)

    for i in range(len(patterns)):
        for j in range(len(ordered_pairs)):
            if not np.isnan(matrix[i, j]):
                ax.text(j, i, f"{matrix[i, j]:.3f}", ha="center", va="center",
                        fontsize=5.5, color="black" if matrix[i, j] < 0.45 else "white")

    lang_colors_cycle = LANG_PALETTE
    for k, lang in enumerate(ordered_langs):
        col_start, col_end = lang_boundaries[lang]
        col_mid = (col_start + col_end) / 2
        lc = lang_colors_cycle[k % len(lang_colors_cycle)]

        if col_start > 0:
            ax.axvline(x=col_start - 0.5, color="white", linewidth=2.0)

        ax.text(col_mid, -0.11, lang, ha="center", va="top",
                fontsize=8, fontweight="bold", color=lc,
                transform=ax.get_xaxis_transform())

        for j in range(col_start, col_end + 1):
            ax.get_xticklabels()[j].set_color(lc)

    title = ("RQ2: Same-Language Cross-Framework Cosine Distance by Pattern\n"
             "(grouped by language; only intra-language framework pairs shown)")
    if model_label:
        title = f"[{model_label}] {title}"
    ax.set_title(title)
    ax.set_xlabel("Framework Pair  (grouped by Language)", labelpad=28)
    ax.set_ylabel("Software Pattern")
    plt.tight_layout()

    path = os.path.join(output_dir, "distance_heatmap.png")
    fig.savefig(path, bbox_inches="tight")
    plt.close(fig)
    print(f"  Saved {path}")


# ── Plot 3: Per-Language Silhouette Bar Chart ─────────────────────────────────

def plot_silhouette_bars(metrics: dict, output_dir: str, model_label: str = ""):
    """
    Generate and save a bar chart of framework silhouette scores per language.
    
    Bars are coloured by language (matching the t-SNE palette) for cross-referencing.
    A higher score indicates frameworks within that language form distinctly separated 
    clusters, suggesting stronger framework-driven dialects.

    Args:
        metrics (dict): Dictionary output from compute_framework_silhouette.
        output_dir (str): Directory where 'silhouette_per_language.png' will be saved.
        model_label (str, optional): Label to prepend to the plot title.
    """
    per_lang = metrics["silhouette"]["framework_silhouette_per_language"]
    overall = metrics["silhouette"]["framework_silhouette_overall"]

    langs = sorted(per_lang.keys())
    scores = [per_lang[l] for l in langs]
    lang_color = build_lang_color(langs)
    colors = [lang_color[l] for l in langs]

    fig, ax = plt.subplots(figsize=(10, 5))
    bars = ax.bar(langs, scores, color=colors, edgecolor="white", linewidth=0.8)

    for bar, score in zip(bars, scores):
        ypos = bar.get_height() + (0.005 if score >= 0 else -0.015)
        ax.text(bar.get_x() + bar.get_width() / 2, ypos,
                f"{score:.3f}", ha="center", va="bottom", fontsize=8.5)

    ax.axhline(y=overall, color="crimson", linestyle="--", linewidth=1.4,
               label=f"Overall = {overall:.4f}")
    ax.axhline(y=0, color="grey", linewidth=0.6)

    y_min = min(min(scores), 0)
    y_max = max(max(scores), overall)
    ax.set_ylim(y_min - 0.06, y_max + 0.06)

    ax.set_ylabel("Framework Silhouette Score")
    ax.set_xlabel("Language")
    title = ("RQ2: Framework Clustering Quality per Language\n"
             "(higher = frameworks are more separable within that language)")
    if model_label:
        title = f"[{model_label}] {title}"
    ax.set_title(title)
    ax.legend(fontsize=9)
    plt.xticks(rotation=30, ha="right")
    plt.tight_layout()

    path = os.path.join(output_dir, "silhouette_per_language.png")
    fig.savefig(path, bbox_inches="tight")
    plt.close(fig)
    print(f"  Saved {path}")


# ── Run visualization for one model ──────────────────────────────────────────

def run_visualize(model_key: str):
    """Generate all plots for a single model."""
    model_dir = os.path.join(OUTPUT_BASE, model_key)
    embeddings_file = os.path.join(model_dir, "rq2_embeddings.parquet")
    metrics_file = os.path.join(model_dir, "rq2_metrics.json")

    if not os.path.exists(embeddings_file):
        print(f"  Embeddings not found at {embeddings_file} — skipping {model_key}.")
        return False
    if not os.path.exists(metrics_file):
        print(f"  Metrics not found at {metrics_file} — run 2_analysis.py first.")
        return False

    df = pd.read_parquet(embeddings_file)
    with open(metrics_file, "r") as f:
        metrics = json.load(f)

    df_meta = df[["language", "framework"]].drop_duplicates()
    model_label = model_key

    print(f"\n── Generating Visualizations for {model_key} ──")
    plot_tsne(df, model_dir, model_label)
    plot_distance_heatmap(metrics, df_meta, model_dir, model_label)
    plot_silhouette_bars(metrics, model_dir, model_label)
    print(f"Done! All plots saved to {model_dir}")
    return True


# ── Main ──────────────────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(
        description="Generate RQ2 visualizations for one or all models."
    )
    parser.add_argument(
        "--model", type=str, default="all",
        choices=list(MODELS.keys()) + ["all"],
        help="Model to visualize (default: all)",
    )
    args = parser.parse_args()

    models_to_run = [args.model] if args.model != "all" else list(MODELS.keys())

    for model_key in models_to_run:
        print(f"\n{'='*60}")
        print(f"  Visualizing model: {model_key}")
        print(f"{'='*60}")
        run_visualize(model_key)

    print("\nDone!")


if __name__ == "__main__":
    main()

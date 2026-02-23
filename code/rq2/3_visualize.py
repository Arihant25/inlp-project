"""
RQ2: Framework-Driven Dialects — Dimensionality Reduction & Visualization

Generates publication-quality plots from the RQ2 embeddings and metrics:
  1. t-SNE scatter plot  (colour = Language, shape = Framework within that language)
  2. Cross-Framework Distance heatmap — same-language pairs only, grouped by language
  3. Per-language framework silhouette bar chart (bars coloured by language)

Output: results/rq2/*.png
"""

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
PROJECT_ROOT = os.path.dirname(os.path.dirname(SCRIPT_DIR))
OUTPUT_DIR = os.path.join(PROJECT_ROOT, "results/rq2")
EMBEDDINGS_FILE = os.path.join(OUTPUT_DIR, "rq2_embeddings.parquet")
METRICS_FILE = os.path.join(OUTPUT_DIR, "rq2_metrics.json")

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
    "#E69F00",  # orange
    "#56B4E9",  # sky blue
    "#009E73",  # green
    "#F0E442",  # yellow
    "#0072B2",  # blue
    "#D55E00",  # vermillion
    "#CC79A7",  # pink
    "#000000",  # black
]

# Distinct markers for frameworks within each language (up to ~10)
MARKER_LIST = ["o", "s", "^", "D", "v", "P", "X", "*", "h", "<"]


def embeddings_to_matrix(df: pd.DataFrame) -> np.ndarray:
    return np.vstack(df["embedding"].values)


def build_lang_color(languages):
    """Return {language: color} using the fixed palette."""
    return {lang: LANG_PALETTE[i % len(LANG_PALETTE)] for i, lang in enumerate(sorted(languages))}


def build_fw_marker(frameworks_for_lang):
    """Return {framework: marker} for frameworks of a single language."""
    return {fw: MARKER_LIST[i % len(MARKER_LIST)] for i, fw in enumerate(sorted(frameworks_for_lang))}


# ── Plot 1: t-SNE Scatter ────────────────────────────────────────────────────

def plot_tsne(df: pd.DataFrame, output_dir: str):
    """
    Reduce embeddings to 2-D with t-SNE.
    Colour = Language  (the primary grouping for RQ2 context)
    Shape  = Framework (shows dialect variation within each language)

    Legend:
      • Left panel : one colour-filled circle per Language
      • Right panel: one marker shape per Framework, listed per language section
    """
    print("Running t-SNE ...")
    X = embeddings_to_matrix(df)
    tsne = TSNE(n_components=2, random_state=42, perplexity=30, max_iter=1000)
    X_2d = tsne.fit_transform(X)

    languages = sorted(df["language"].unique())
    lang_color = build_lang_color(languages)

    # Per-language marker assignment (frameworks differ per language)
    lang_fw_marker: dict[str, dict[str, str]] = {}
    for lang in languages:
        fws = sorted(df.loc[df["language"] == lang, "framework"].unique())
        lang_fw_marker[lang] = build_fw_marker(fws)

    fig, ax = plt.subplots(figsize=(13, 9))

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

    # ── Legend panel 1: Language colours ──
    lang_handles = [
        mpatches.Patch(facecolor=lang_color[lang], edgecolor="grey", linewidth=0.5, label=lang)
        for lang in languages
    ]
    legend1 = ax.legend(
        handles=lang_handles,
        title="Language",
        loc="upper left",
        bbox_to_anchor=(1.01, 1.0),
        frameon=True,
        framealpha=0.9,
    )
    ax.add_artist(legend1)

    # ── Legend panel 2: Framework markers, grouped per language ──
    fw_handles = []
    for lang in languages:
        # Section header (invisible dummy line used as a bold title entry)
        fw_handles.append(
            mlines.Line2D([], [], color="none", label=f"── {lang} ──")
        )
        fw_marker = lang_fw_marker[lang]
        color = lang_color[lang]
        for fw, marker in sorted(fw_marker.items()):
            fw_handles.append(
                mlines.Line2D(
                    [], [],
                    marker=marker, color=color,
                    markeredgecolor="white", markeredgewidth=0.3,
                    markersize=7, linestyle="None",
                    label=f"  {fw}",
                )
            )

    ax.legend(
        handles=fw_handles,
        title="Framework",
        loc="upper left",
        bbox_to_anchor=(1.01, 0.58),
        frameon=True,
        framealpha=0.9,
        handlelength=1.2,
    )

    ax.set_title(
        "RQ2: t-SNE of Code Embeddings\n"
        "(colour = Language,  shape = Framework)"
    )
    ax.set_xlabel("t-SNE 1")
    ax.set_ylabel("t-SNE 2")
    plt.tight_layout()

    path = os.path.join(output_dir, "tsne_scatter.png")
    fig.savefig(path, bbox_inches="tight")
    plt.close(fig)
    print(f"  Saved {path}")


# ── Plot 2: Cross-Framework Distance Heatmap ─────────────────────────────────

def plot_distance_heatmap(metrics: dict, df_meta: pd.DataFrame, output_dir: str):
    """
    Heatmap of cross-framework pair distances, one row per pattern.

    Only same-language framework pairs are shown (e.g. 'Actix vs Axum' for Rust).
    Columns are grouped by language so cross-language comparisons are not mixed
    with intra-language dialect analysis.
    """
    pair_data = metrics["distances"]["cross_fw_pair_distances_by_pattern"]
    patterns = sorted(pair_data.keys())

    # Build a lookup: framework -> language
    fw_to_lang = (
        df_meta.drop_duplicates(subset=["framework", "language"])
        .set_index("framework")["language"]
        .to_dict()
    )

    # Collect only same-language framework pairs
    def is_same_lang(pair_str: str) -> bool:
        parts = pair_str.split(" vs ", 1)
        if len(parts) != 2:
            return False
        fw_a, fw_b = parts[0].strip(), parts[1].strip()
        lang_a = fw_to_lang.get(fw_a)
        lang_b = fw_to_lang.get(fw_b)
        return lang_a is not None and lang_b is not None and lang_a == lang_b

    # Gather and group pairs by language
    all_pairs_raw = set()
    for pat in patterns:
        all_pairs_raw.update(pair_data[pat].keys())

    same_lang_pairs = sorted(p for p in all_pairs_raw if is_same_lang(p))

    if not same_lang_pairs:
        print("  WARNING: No same-language framework pairs found — skipping heatmap.")
        return

    # Group pairs by language, then sort within group
    pair_to_lang = {}
    for pair in same_lang_pairs:
        fw_a = pair.split(" vs ", 1)[0].strip()
        pair_to_lang[pair] = fw_to_lang.get(fw_a, "Unknown")

    # Ordered languages (sort for consistency)
    ordered_langs = sorted(set(pair_to_lang.values()))
    ordered_pairs = []
    lang_boundaries = {}  # lang -> list of column indices
    for lang in ordered_langs:
        group = sorted(p for p, l in pair_to_lang.items() if l == lang)
        lang_boundaries[lang] = (len(ordered_pairs), len(ordered_pairs) + len(group) - 1)
        ordered_pairs.extend(group)

    # Build matrix
    matrix = np.full((len(patterns), len(ordered_pairs)), np.nan)
    for i, pat in enumerate(patterns):
        for j, pair in enumerate(ordered_pairs):
            if pair in pair_data[pat]:
                matrix[i, j] = pair_data[pat][pair]

    # Figure size scaled to content
    fig_w = max(14, len(ordered_pairs) * 0.9)
    fig_h = max(5, len(patterns) * 0.8)
    fig, ax = plt.subplots(figsize=(fig_w, fig_h))

    im = ax.imshow(matrix, aspect="auto", cmap="YlOrRd", vmin=0.0, vmax=0.65)
    cbar = fig.colorbar(im, ax=ax, shrink=0.8)
    cbar.set_label("Cosine Distance")

    ax.set_xticks(range(len(ordered_pairs)))

    # Strip the language prefix from tick labels (it's shown via column group header)
    short_labels = []
    for pair in ordered_pairs:
        fw_a, fw_b = pair.split(" vs ", 1)
        short_labels.append(f"{fw_a.strip()}\nvs\n{fw_b.strip()}")
    ax.set_xticklabels(short_labels, rotation=0, ha="center", fontsize=6.5)

    ax.set_yticks(range(len(patterns)))
    ax.set_yticklabels(patterns, fontsize=9)

    # Annotate cells
    for i in range(len(patterns)):
        for j in range(len(ordered_pairs)):
            if not np.isnan(matrix[i, j]):
                ax.text(j, i, f"{matrix[i, j]:.3f}", ha="center", va="center",
                        fontsize=5.5, color="black" if matrix[i, j] < 0.45 else "white")

    # Draw vertical separators and language group labels
    lang_colors_cycle = LANG_PALETTE
    for k, lang in enumerate(ordered_langs):
        col_start, col_end = lang_boundaries[lang]
        col_mid = (col_start + col_end) / 2
        lc = lang_colors_cycle[k % len(lang_colors_cycle)]

        # Vertical separator line (before this group, except first)
        if col_start > 0:
            ax.axvline(x=col_start - 0.5, color="white", linewidth=2.0)

        # Language label above columns
        ax.text(col_mid, -0.85, lang, ha="center", va="bottom",
                fontsize=8, fontweight="bold", color=lc,
                transform=ax.get_xaxis_transform())

        # Colour the x-tick labels to match the language
        for j in range(col_start, col_end + 1):
            ax.get_xticklabels()[j].set_color(lc)

    ax.set_title("RQ2: Same-Language Cross-Framework Cosine Distance by Pattern\n"
                 "(grouped by language; only intra-language framework pairs shown)")
    ax.set_xlabel("Framework Pair  (grouped by Language)")
    ax.set_ylabel("Software Pattern")
    plt.tight_layout()

    path = os.path.join(output_dir, "distance_heatmap.png")
    fig.savefig(path, bbox_inches="tight")
    plt.close(fig)
    print(f"  Saved {path}")


# ── Plot 3: Per-Language Silhouette Bar Chart ─────────────────────────────────

def plot_silhouette_bars(metrics: dict, output_dir: str):
    """
    Bar chart of framework silhouette score per language.
    Bars are coloured by language (matching the t-SNE palette) so the reader
    can cross-reference between plots immediately.
    """
    per_lang = metrics["silhouette"]["framework_silhouette_per_language"]
    overall = metrics["silhouette"]["framework_silhouette_overall"]

    langs = sorted(per_lang.keys())
    scores = [per_lang[l] for l in langs]
    lang_color = build_lang_color(langs)
    colors = [lang_color[l] for l in langs]

    fig, ax = plt.subplots(figsize=(10, 5))
    bars = ax.bar(langs, scores, color=colors, edgecolor="white", linewidth=0.8)

    # Value labels on bars
    for bar, score in zip(bars, scores):
        ypos = bar.get_height() + (0.005 if score >= 0 else -0.015)
        ax.text(bar.get_x() + bar.get_width() / 2, ypos,
                f"{score:.3f}", ha="center", va="bottom", fontsize=8.5)

    ax.axhline(y=overall, color="crimson", linestyle="--", linewidth=1.4,
               label=f"Overall = {overall:.4f}")
    ax.axhline(y=0, color="grey", linewidth=0.6)

    ax.set_ylabel("Framework Silhouette Score")
    ax.set_xlabel("Language")
    ax.set_title("RQ2: Framework Clustering Quality per Language\n"
                 "(higher = frameworks are more separable within that language)")
    ax.legend(fontsize=9)
    plt.xticks(rotation=30, ha="right")
    plt.tight_layout()

    path = os.path.join(output_dir, "silhouette_per_language.png")
    fig.savefig(path, bbox_inches="tight")
    plt.close(fig)
    print(f"  Saved {path}")


# ── Main ──────────────────────────────────────────────────────────────────────

def main():
    if not os.path.exists(EMBEDDINGS_FILE):
        print(f"ERROR: {EMBEDDINGS_FILE} not found. Run rq2_embedding.py first.")
        sys.exit(1)
    if not os.path.exists(METRICS_FILE):
        print(f"ERROR: {METRICS_FILE} not found. Run rq2_analysis.py first.")
        sys.exit(1)

    df = pd.read_parquet(EMBEDDINGS_FILE)
    with open(METRICS_FILE, "r") as f:
        metrics = json.load(f)

    # Lightweight metadata-only frame (no embedding column) for heatmap helper
    df_meta = df[["language", "framework"]].drop_duplicates()

    os.makedirs(OUTPUT_DIR, exist_ok=True)

    print("\n── Generating Visualizations ──")
    plot_tsne(df, OUTPUT_DIR)
    plot_distance_heatmap(metrics, df_meta, OUTPUT_DIR)
    plot_silhouette_bars(metrics, OUTPUT_DIR)
    print("\nDone! All plots saved to", OUTPUT_DIR)


if __name__ == "__main__":
    main()

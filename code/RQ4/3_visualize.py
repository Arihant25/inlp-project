"""
RQ4: Correctness Regions & Bug Patterns — Dimensionality Reduction & Visualization

Generates publication-quality plots from the RQ4 embeddings and metrics:
  1. t-SNE scatter: all embeddings, colour = code_type (buggy/fixed), shape = language
  2. t-SNE faceted: one panel per language, colour = code_type
  3. t-SNE by severity: colour = severity, shape = code_type
  4. Correctness silhouette bar chart  (per language + per severity)
  5. Pairwise buggy–fixed distance bar charts  (per language, per severity)
  6. Cluster separation bar chart  (intra vs cross per language)
  7. Dangerous-neighbourhood heatmap  (language × severity × threshold)
  8. Global severity distance heatmap
  9. Per-language severity distance heatmaps  (small multiples)

Usage:
    uv run code/RQ4/3_visualize.py                    # all models
    uv run code/RQ4/3_visualize.py --model octen      # single model

Output: results/RQ4/{model_key}/*.png
"""

import argparse
import json
import math
import os
import sys

import matplotlib.lines as mlines
import matplotlib.patches as mpatches
import matplotlib.pyplot as plt
import numpy as np
import pandas as pd
from sklearn.manifold import TSNE

# ── Paths ─────────────────────────────────────────────────────────────────────
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
CODE_DIR = os.path.dirname(SCRIPT_DIR)
sys.path.insert(0, CODE_DIR)
from embedding import MODELS

PROJECT_ROOT = os.path.dirname(CODE_DIR)
OUTPUT_BASE = os.path.join(PROJECT_ROOT, "results/RQ4")

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

SEVERITY_ORDER = ["Easy", "Medium", "Hard", "Super Hard"]

# Colour palettes
CODE_TYPE_PALETTE = {"buggy": "#e31a1c", "fixed": "#33a02c"}

SEVERITY_PALETTE = {
    "Easy": "#4dac26",
    "Medium": "#fdbf6f",
    "Hard": "#e31a1c",
    "Super Hard": "#6a3d9a",
}

LANG_PALETTE = [
    "#1f77b4",
    "#ff7f0e",
    "#2ca02c",
    "#d62728",
    "#9467bd",
    "#8c564b",
    "#e377c2",
    "#7f7f7f",
    "#bcbd22",
    "#17becf",
]

LANG_MARKER_LIST = ["o", "s", "^", "D", "v", "P", "X", "*", "h", "<"]


def to_matrix(df: pd.DataFrame) -> np.ndarray:
    return np.vstack(df["embedding"].values)


def build_lang_color(languages: list) -> dict:
    return {
        l: LANG_PALETTE[i % len(LANG_PALETTE)] for i, l in enumerate(sorted(languages))
    }


def build_lang_marker(languages: list) -> dict:
    return {
        l: LANG_MARKER_LIST[i % len(LANG_MARKER_LIST)]
        for i, l in enumerate(sorted(languages))
    }


# ── Plot 1: t-SNE All (colour=code_type, shape=language) ─────────────────────


def plot_tsne_all(df: pd.DataFrame, output_dir: str, model_label: str = ""):
    """
    2-D t-SNE scatter coloured by correctness (buggy/fixed), shaped by language.

    The core RQ4 visual: if buggy and fixed form distinct clusters, embeddings
    encode correctness information.
    """
    print("  Running t-SNE (all) …")
    X = to_matrix(df)
    perplexity = min(30, max(5, len(X) // 10))
    tsne = TSNE(n_components=2, random_state=42, perplexity=perplexity, max_iter=1000)
    X_2d = tsne.fit_transform(X)

    languages = sorted(df["language"].unique())
    lang_marker = build_lang_marker(languages)

    fig, ax = plt.subplots(figsize=(14, 10))
    fig.subplots_adjust(bottom=0.13, right=0.82)

    for ct in ["buggy", "fixed"]:
        color = CODE_TYPE_PALETTE[ct]
        for lang in languages:
            mask = ((df["code_type"] == ct) & (df["language"] == lang)).values
            if mask.sum() == 0:
                continue
            ax.scatter(
                X_2d[mask, 0],
                X_2d[mask, 1],
                c=color,
                marker=lang_marker[lang],
                s=30,
                alpha=0.6,
                edgecolors="white",
                linewidths=0.3,
            )

    # Right legend — code type
    ct_handles = [
        mpatches.Patch(
            facecolor=CODE_TYPE_PALETTE[ct],
            edgecolor="grey",
            linewidth=0.5,
            label=ct.capitalize(),
        )
        for ct in ["buggy", "fixed"]
    ]
    leg1 = ax.legend(
        handles=ct_handles,
        title="Code Type",
        loc="upper left",
        bbox_to_anchor=(1.01, 1.0),
        frameon=True,
    )
    ax.add_artist(leg1)

    # Bottom legend — languages
    n_langs = len(languages)
    col_width = 1.0 / n_langs
    for k, lang in enumerate(languages):
        handle = [
            mlines.Line2D(
                [],
                [],
                marker=lang_marker[lang],
                color="#444",
                markeredgecolor="white",
                markeredgewidth=0.3,
                markersize=7,
                linestyle="None",
                label=lang,
            )
        ]
        fig.legend(
            handles=handle,
            title=lang,
            title_fontproperties={"weight": "bold", "size": 8},
            loc="lower center",
            bbox_to_anchor=((k + 0.5) * col_width, 0.01),
            bbox_transform=fig.transFigure,
            frameon=True,
            framealpha=0.88,
            fontsize=7.5,
        )

    title = "RQ4: t-SNE of Code Embeddings\n(colour = Buggy/Fixed,  shape = Language)"
    if model_label:
        title = f"[{model_label}] {title}"
    ax.set_title(title)
    ax.set_xlabel("t-SNE 1")
    ax.set_ylabel("t-SNE 2")

    path = os.path.join(output_dir, "tsne_all_correctness.png")
    fig.savefig(path, bbox_inches="tight")
    plt.close(fig)
    print(f"    Saved {path}")


# ── Plot 2: t-SNE Faceted by Language ─────────────────────────────────────────


def plot_tsne_faceted(df: pd.DataFrame, output_dir: str, model_label: str = ""):
    """
    Grid of t-SNE panels — one per language — coloured by buggy/fixed.

    Reveals which languages show cleaner correctness separation in embedding space.
    """
    print("  Running t-SNE (faceted by language) …")
    languages = sorted(df["language"].unique())
    n_langs = len(languages)
    n_cols = min(3, n_langs)
    n_rows = math.ceil(n_langs / n_cols)

    # Shared t-SNE across all languages
    X_all = to_matrix(df)
    perplexity = min(30, max(5, len(X_all) // 10))
    tsne = TSNE(n_components=2, random_state=42, perplexity=perplexity, max_iter=1000)
    X_2d = tsne.fit_transform(X_all)
    df = df.copy()
    df["tsne_1"] = X_2d[:, 0]
    df["tsne_2"] = X_2d[:, 1]

    fig, axes = plt.subplots(n_rows, n_cols, figsize=(6 * n_cols, 5 * n_rows))
    axes = np.array(axes).flatten()

    for ax_idx, lang in enumerate(languages):
        ax = axes[ax_idx]
        lang_df = df[df["language"] == lang]
        for ct in ["buggy", "fixed"]:
            sub = lang_df[lang_df["code_type"] == ct]
            if sub.empty:
                continue
            ax.scatter(
                sub["tsne_1"],
                sub["tsne_2"],
                c=CODE_TYPE_PALETTE[ct],
                label=ct.capitalize(),
                s=20,
                alpha=0.65,
                edgecolors="none",
            )
        ax.set_title(lang.upper(), fontsize=11, fontweight="bold")
        ax.set_xlabel("t-SNE 1", fontsize=9)
        ax.set_ylabel("t-SNE 2", fontsize=9)
        ax.tick_params(labelsize=7)

    for ax_idx in range(len(languages), len(axes)):
        axes[ax_idx].set_visible(False)

    handles = [
        mpatches.Patch(color=CODE_TYPE_PALETTE[ct], label=ct.capitalize())
        for ct in ["buggy", "fixed"]
    ]
    fig.legend(
        handles=handles,
        title="Code Type",
        loc="lower center",
        ncol=2,
        bbox_to_anchor=(0.5, 0.0),
        fontsize=9,
    )

    sup = "RQ4: t-SNE by Language (colour = Buggy / Fixed)"
    if model_label:
        sup = f"[{model_label}] {sup}"
    fig.suptitle(sup, y=1.01, fontsize=13)
    plt.tight_layout()

    path = os.path.join(output_dir, "tsne_faceted_by_language.png")
    fig.savefig(path, bbox_inches="tight")
    plt.close(fig)
    print(f"    Saved {path}")


# ── Plot 3: t-SNE by Severity ────────────────────────────────────────────────


def plot_tsne_severity(df: pd.DataFrame, output_dir: str, model_label: str = ""):
    """
    t-SNE coloured by bug severity, with x=buggy and o=fixed as markers.

    Shows whether different severity levels occupy distinct regions.
    """
    print("  Running t-SNE (by severity) …")
    X = to_matrix(df)
    perplexity = min(30, max(5, len(X) // 10))
    tsne = TSNE(n_components=2, random_state=42, perplexity=perplexity, max_iter=1000)
    X_2d = tsne.fit_transform(X)

    fig, ax = plt.subplots(figsize=(12, 9))

    ct_markers = {"buggy": "x", "fixed": "o"}
    ct_sizes = {"buggy": 40, "fixed": 25}

    sevs_present = [s for s in SEVERITY_ORDER if s in df["severity"].unique()]
    for sev in sevs_present:
        color = SEVERITY_PALETTE.get(sev, "#aaa")
        for ct in ["buggy", "fixed"]:
            mask = ((df["severity"] == sev) & (df["code_type"] == ct)).values
            if mask.sum() == 0:
                continue
            ax.scatter(
                X_2d[mask, 0],
                X_2d[mask, 1],
                c=color,
                marker=ct_markers[ct],
                s=ct_sizes[ct],
                alpha=0.6,
                label=f"{sev} ({ct})",
                edgecolors="white" if ct == "fixed" else "none",
                linewidths=0.3,
            )

    ax.legend(bbox_to_anchor=(1.01, 1), loc="upper left", fontsize=8, framealpha=0.9)
    title = "RQ4: t-SNE by Bug Severity\n(colour = Severity,  x = Buggy, o = Fixed)"
    if model_label:
        title = f"[{model_label}] {title}"
    ax.set_title(title)
    ax.set_xlabel("t-SNE 1")
    ax.set_ylabel("t-SNE 2")

    path = os.path.join(output_dir, "tsne_by_severity.png")
    fig.savefig(path, bbox_inches="tight")
    plt.close(fig)
    print(f"    Saved {path}")


# ── Plot 4: Correctness Silhouette Bar Charts ────────────────────────────────


def plot_silhouette_bars(metrics: dict, output_dir: str, model_label: str = ""):
    """
    Two-panel bar chart: per-language and per-severity correctness silhouette scores.
    """
    sil = metrics["silhouette"]
    overall = sil["correctness_silhouette_overall"]
    lang_sil = sil["correctness_silhouette_per_language"]
    sev_sil = sil["correctness_silhouette_per_severity"]
    lang_baseline = sil["language_silhouette_overall"]

    fig, (ax1, ax2) = plt.subplots(1, 2, figsize=(16, 6))

    # Panel 1: per language
    langs = sorted(lang_sil.keys(), key=lambda l: lang_sil[l], reverse=True)
    scores = [lang_sil[l] for l in langs]
    lc = build_lang_color(list(lang_sil.keys()))
    colors = [lc[l] for l in langs]

    bars = ax1.bar(langs, scores, color=colors, edgecolor="white", linewidth=0.8)
    for bar, s in zip(bars, scores):
        yoff = bar.get_height() + (0.003 if s >= 0 else -0.015)
        ax1.text(
            bar.get_x() + bar.get_width() / 2,
            yoff,
            f"{s:.3f}",
            ha="center",
            va="bottom",
            fontsize=8.5,
        )

    ax1.axhline(
        y=overall,
        color="crimson",
        linestyle="--",
        linewidth=1.5,
        label=f"Overall = {overall:.4f}",
    )
    ax1.axhline(
        y=lang_baseline,
        color="steelblue",
        linestyle=":",
        linewidth=1.3,
        label=f"Language baseline = {lang_baseline:.4f}",
    )
    ax1.axhline(y=0, color="grey", linewidth=0.6)
    ax1.set_ylabel("Correctness Silhouette Score")
    ax1.set_xlabel("Language")
    ax1.set_title("Per-Language Correctness Clustering")
    ax1.legend(fontsize=8)
    ax1.set_xticks(range(len(langs)))
    ax1.set_xticklabels([l.upper() for l in langs], rotation=25, ha="right")

    # Panel 2: per severity
    sevs = [s for s in SEVERITY_ORDER if s in sev_sil]
    sev_scores = [sev_sil[s] for s in sevs]
    sev_colors = [SEVERITY_PALETTE.get(s, "#aaa") for s in sevs]

    bars2 = ax2.bar(
        sevs, sev_scores, color=sev_colors, edgecolor="white", linewidth=0.8
    )
    for bar, s in zip(bars2, sev_scores):
        yoff = bar.get_height() + (0.003 if s >= 0 else -0.015)
        ax2.text(
            bar.get_x() + bar.get_width() / 2,
            yoff,
            f"{s:.3f}",
            ha="center",
            va="bottom",
            fontsize=8.5,
        )

    ax2.axhline(
        y=overall,
        color="crimson",
        linestyle="--",
        linewidth=1.5,
        label=f"Overall = {overall:.4f}",
    )
    ax2.axhline(y=0, color="grey", linewidth=0.6)
    ax2.set_ylabel("Correctness Silhouette Score")
    ax2.set_xlabel("Bug Severity")
    ax2.set_title("Per-Severity Correctness Clustering")
    ax2.legend(fontsize=8)

    sup = "RQ4: Correctness Silhouette Scores"
    if model_label:
        sup = f"[{model_label}] {sup}"
    fig.suptitle(sup, y=1.02, fontsize=14)
    plt.tight_layout()

    path = os.path.join(output_dir, "silhouette_correctness.png")
    fig.savefig(path, bbox_inches="tight")
    plt.close(fig)
    print(f"    Saved {path}")


# ── Plot 5: Pairwise Distance Bar Charts ─────────────────────────────────────


def plot_pairwise_distances(metrics: dict, output_dir: str, model_label: str = ""):
    """
    Two-panel bar chart: mean buggy–fixed cosine distance per language and per severity.
    """
    pw = metrics["pairwise_distances"]
    per_lang = pw["per_language"]
    per_sev = pw["per_severity"]
    overall = pw["overall"]["mean"]

    fig, (ax1, ax2) = plt.subplots(1, 2, figsize=(16, 6))

    # Panel 1: per language
    langs = sorted(per_lang.keys())
    means = [per_lang[l]["mean"] for l in langs]
    stds = [per_lang[l]["std"] for l in langs]
    lc = build_lang_color(langs)

    bars1 = ax1.bar(
        langs,
        means,
        yerr=stds,
        capsize=4,
        color=[lc[l] for l in langs],
        edgecolor="white",
        alpha=0.85,
    )
    for bar, m in zip(bars1, means):
        ax1.text(
            bar.get_x() + bar.get_width() / 2,
            bar.get_height() + 0.003,
            f"{m:.3f}",
            ha="center",
            va="bottom",
            fontsize=8.5,
        )

    ax1.axhline(
        y=overall,
        color="crimson",
        linestyle="--",
        linewidth=1.5,
        label=f"Overall = {overall:.4f}",
    )
    ax1.set_ylabel("Mean Cosine Distance (Buggy ↔ Fixed)")
    ax1.set_xlabel("Language")
    ax1.set_title("Per-Language Buggy–Fixed Distance")
    ax1.legend(fontsize=8)
    ax1.set_xticks(range(len(langs)))
    ax1.set_xticklabels([l.upper() for l in langs], rotation=25, ha="right")
    ax1.grid(axis="y", alpha=0.3)

    # Panel 2: per severity
    sevs = [s for s in SEVERITY_ORDER if s in per_sev]
    sev_means = [per_sev[s]["mean"] for s in sevs]
    sev_stds = [per_sev[s]["std"] for s in sevs]
    sev_colors = [SEVERITY_PALETTE.get(s, "#aaa") for s in sevs]

    bars2 = ax2.bar(
        sevs,
        sev_means,
        yerr=sev_stds,
        capsize=4,
        color=sev_colors,
        edgecolor="white",
        alpha=0.85,
    )
    for bar, m in zip(bars2, sev_means):
        ax2.text(
            bar.get_x() + bar.get_width() / 2,
            bar.get_height() + 0.003,
            f"{m:.3f}",
            ha="center",
            va="bottom",
            fontsize=8.5,
        )

    ax2.axhline(
        y=overall,
        color="crimson",
        linestyle="--",
        linewidth=1.5,
        label=f"Overall = {overall:.4f}",
    )
    ax2.set_ylabel("Mean Cosine Distance (Buggy ↔ Fixed)")
    ax2.set_xlabel("Bug Severity")
    ax2.set_title("Per-Severity Buggy–Fixed Distance")
    ax2.legend(fontsize=8)
    ax2.grid(axis="y", alpha=0.3)

    sup = "RQ4: Pairwise Buggy–Fixed Cosine Distances"
    if model_label:
        sup = f"[{model_label}] {sup}"
    fig.suptitle(sup, y=1.02, fontsize=14)
    plt.tight_layout()

    path = os.path.join(output_dir, "pairwise_distances.png")
    fig.savefig(path, bbox_inches="tight")
    plt.close(fig)
    print(f"    Saved {path}")


# ── Plot 6: Cluster Separation Bar Chart ─────────────────────────────────────


def plot_cluster_separation(metrics: dict, output_dir: str, model_label: str = ""):
    """
    Two-panel figure:
      Left:  grouped bars (intra-buggy, intra-fixed, cross) per language
      Right: separation score bars per language (green positive, red negative)
    """
    cluster = metrics["cluster_distances"]
    per_lang = cluster["per_language"]
    languages = sorted(per_lang.keys())

    fig, (ax1, ax2) = plt.subplots(1, 2, figsize=(16, 6))

    x = np.arange(len(languages))
    w = 0.25

    ib = [per_lang[l]["intra_buggy_mean"] for l in languages]
    if_ = [per_lang[l]["intra_fixed_mean"] for l in languages]
    cr = [per_lang[l]["cross_mean"] for l in languages]

    ax1.bar(x - w, ib, w, label="Intra-Buggy", color="#e31a1c", alpha=0.8)
    ax1.bar(x, if_, w, label="Intra-Fixed", color="#33a02c", alpha=0.8)
    ax1.bar(x + w, cr, w, label="Cross (Buggy–Fixed)", color="#1f78b4", alpha=0.8)
    ax1.set_xticks(x)
    ax1.set_xticklabels([l.upper() for l in languages], rotation=25, ha="right")
    ax1.set_ylabel("Mean Cosine Distance")
    ax1.set_title("Intra-cluster vs Cross-cluster Distances")
    ax1.legend(fontsize=8)
    ax1.grid(axis="y", alpha=0.3)

    # Separation scores
    sep = [per_lang[l]["separation_score"] for l in languages]
    colors = ["green" if s > 0 else "red" for s in sep]
    bars = ax2.bar(x, sep, color=colors, alpha=0.7, edgecolor="black")
    for bar, s in zip(bars, sep):
        yoff = bar.get_height() + (0.002 if s >= 0 else -0.012)
        ax2.text(
            bar.get_x() + bar.get_width() / 2,
            yoff,
            f"{s:.3f}",
            ha="center",
            va="bottom",
            fontsize=8.5,
        )

    ax2.axhline(y=0, color="black", linestyle="--", linewidth=1.5)
    ax2.set_xticks(x)
    ax2.set_xticklabels([l.upper() for l in languages], rotation=25, ha="right")
    ax2.set_ylabel("Separation Score\n(cross − mean(intra))")
    ax2.set_title("Correctness Separation Score\n(higher = better separation)")
    ax2.grid(axis="y", alpha=0.3)

    sup = "RQ4: Cluster Separation Analysis"
    if model_label:
        sup = f"[{model_label}] {sup}"
    fig.suptitle(sup, y=1.02, fontsize=14)
    plt.tight_layout()

    path = os.path.join(output_dir, "cluster_separation.png")
    fig.savefig(path, bbox_inches="tight")
    plt.close(fig)
    print(f"    Saved {path}")


# ── Plot 7: Dangerous-Neighbourhood Heatmap ──────────────────────────────────


def plot_dangerous_heatmap(metrics: dict, output_dir: str, model_label: str = ""):
    """
    Heatmap showing percentage of dangerous buggy–fixed pairs per
    (language × severity) at the 0.10 threshold.
    """
    danger = metrics["dangerous_neighbourhoods"]
    threshold = 0.10
    key = f"threshold_{threshold}"
    if key not in danger:
        print("    No data for threshold 0.10 — skipping.")
        return

    per_lang = danger[key]["per_language"]
    per_sev = danger[key]["per_severity"]
    languages = sorted(per_lang.keys())
    severities = [s for s in SEVERITY_ORDER if s in per_sev]

    # Build from per_language_severity in the pairwise_distances (we'll approximate
    # by showing per_lang and per_sev side by side)
    fig, (ax1, ax2) = plt.subplots(1, 2, figsize=(14, 5))

    # Panel 1: per language
    pcts = [per_lang[l]["pct"] for l in languages]
    lc = build_lang_color(languages)
    bars = ax1.bar(
        [l.upper() for l in languages],
        pcts,
        color=[lc[l] for l in languages],
        edgecolor="white",
        alpha=0.85,
    )
    for bar, p in zip(bars, pcts):
        ax1.text(
            bar.get_x() + bar.get_width() / 2,
            bar.get_height() + 0.3,
            f"{p:.1f}%",
            ha="center",
            va="bottom",
            fontsize=9,
        )
    ax1.set_ylabel("% Dangerous Pairs\n(cosine dist < 0.10)")
    ax1.set_xlabel("Language")
    ax1.set_title("Dangerous Neighbourhoods by Language")
    ax1.grid(axis="y", alpha=0.3)

    # Panel 2: per severity
    sev_pcts = [per_sev[s]["pct"] for s in severities]
    sev_colors = [SEVERITY_PALETTE.get(s, "#aaa") for s in severities]
    bars2 = ax2.bar(
        severities, sev_pcts, color=sev_colors, edgecolor="white", alpha=0.85
    )
    for bar, p in zip(bars2, sev_pcts):
        ax2.text(
            bar.get_x() + bar.get_width() / 2,
            bar.get_height() + 0.3,
            f"{p:.1f}%",
            ha="center",
            va="bottom",
            fontsize=9,
        )
    ax2.set_ylabel("% Dangerous Pairs\n(cosine dist < 0.10)")
    ax2.set_xlabel("Bug Severity")
    ax2.set_title("Dangerous Neighbourhoods by Severity")
    ax2.grid(axis="y", alpha=0.3)

    sup = "RQ4: Dangerous Neighbourhoods (threshold = 0.10)"
    if model_label:
        sup = f"[{model_label}] {sup}"
    fig.suptitle(sup, y=1.02, fontsize=14)
    plt.tight_layout()

    path = os.path.join(output_dir, "dangerous_neighbourhoods.png")
    fig.savefig(path, bbox_inches="tight")
    plt.close(fig)
    print(f"    Saved {path}")


# ── Plot 8: Global Severity Distance Heatmap ─────────────────────────────────


def plot_severity_heatmap(metrics: dict, output_dir: str, model_label: str = ""):
    """
    Heatmap of pairwise cosine distances between bug severity levels
    (using buggy-code embeddings only).
    """
    mat_raw = metrics.get("global_severity_distance_matrix", {})
    if not mat_raw:
        print("    No global severity matrix — skipping.")
        return

    severities = [s for s in SEVERITY_ORDER if s in mat_raw]
    n = len(severities)
    matrix = np.full((n, n), np.nan)
    for i, s1 in enumerate(severities):
        for j, s2 in enumerate(severities):
            val = mat_raw.get(s1, {}).get(s2)
            if val is not None:
                matrix[i, j] = val

    fig, ax = plt.subplots(figsize=(max(6, n * 1.5), max(5, n * 1.3)))
    im = ax.imshow(matrix, aspect="auto", cmap="YlOrRd", vmin=0.0, vmax=0.8)
    cbar = fig.colorbar(im, ax=ax, shrink=0.8)
    cbar.set_label("Mean Cosine Distance")

    ax.set_xticks(range(n))
    ax.set_xticklabels(severities, rotation=35, ha="right", fontsize=10)
    ax.set_yticks(range(n))
    ax.set_yticklabels(severities, fontsize=10)

    for i in range(n):
        for j in range(n):
            if not np.isnan(matrix[i, j]):
                ax.text(
                    j,
                    i,
                    f"{matrix[i, j]:.3f}",
                    ha="center",
                    va="center",
                    fontsize=9,
                    color="black" if matrix[i, j] < 0.5 else "white",
                )

    title = "RQ4: Pairwise Severity-level Cosine Distance\n(buggy embeddings only)"
    if model_label:
        title = f"[{model_label}] {title}"
    ax.set_title(title)
    plt.tight_layout()

    path = os.path.join(output_dir, "severity_distance_heatmap.png")
    fig.savefig(path, bbox_inches="tight")
    plt.close(fig)
    print(f"    Saved {path}")


# ── Plot 9: Per-language Severity Distance Heatmaps ──────────────────────────


def plot_per_language_severity_heatmaps(
    metrics: dict, output_dir: str, model_label: str = ""
):
    """
    Small-multiples grid: one severity×severity distance heatmap per language.
    """
    per_lang_raw = metrics.get("per_language_severity_distance_matrix", {})
    if not per_lang_raw:
        print("    No per-language severity matrix — skipping.")
        return

    all_sevs: set[str] = set()
    for mat in per_lang_raw.values():
        all_sevs.update(mat.keys())
    severities = [s for s in SEVERITY_ORDER if s in all_sevs]
    n = len(severities)

    languages = sorted(per_lang_raw.keys())
    n_langs = len(languages)
    n_cols = min(3, n_langs)
    n_rows = math.ceil(n_langs / n_cols)

    fig, axes = plt.subplots(
        n_rows, n_cols, figsize=(max(5, n * 1.1) * n_cols, max(4, n * 0.9) * n_rows)
    )
    axes = np.array(axes).flatten()

    vmax = 0.8
    last_im = None
    for ax_idx, lang in enumerate(languages):
        ax = axes[ax_idx]
        mat_raw = per_lang_raw[lang]
        matrix = np.full((n, n), np.nan)
        for i, s1 in enumerate(severities):
            for j, s2 in enumerate(severities):
                val = mat_raw.get(s1, {}).get(s2)
                if val is not None:
                    matrix[i, j] = val

        im = ax.imshow(matrix, aspect="auto", cmap="Blues", vmin=0.0, vmax=vmax)
        last_im = im
        ax.set_xticks(range(n))
        ax.set_xticklabels(severities, rotation=45, ha="right", fontsize=7)
        ax.set_yticks(range(n))
        ax.set_yticklabels(severities, fontsize=7)
        ax.set_title(lang.upper(), fontsize=10, fontweight="bold")

        for i in range(n):
            for j in range(n):
                if not np.isnan(matrix[i, j]):
                    ax.text(
                        j,
                        i,
                        f"{matrix[i, j]:.2f}",
                        ha="center",
                        va="center",
                        fontsize=6.5,
                        color="black" if matrix[i, j] < 0.5 else "white",
                    )

    for ax_idx in range(len(languages), len(axes)):
        axes[ax_idx].set_visible(False)

    if last_im is not None:
        fig.colorbar(
            last_im, ax=axes[: len(languages)], shrink=0.5, label="Mean Cosine Distance"
        )

    sup = "RQ4: Per-language Severity Distance Matrices\n(buggy embeddings only)"
    if model_label:
        sup = f"[{model_label}] {sup}"
    fig.suptitle(sup, y=1.01, fontsize=12)
    plt.tight_layout()

    path = os.path.join(output_dir, "per_language_severity_heatmaps.png")
    fig.savefig(path, bbox_inches="tight")
    plt.close(fig)
    print(f"    Saved {path}")


# ── Plot 10: Language × Severity Distance Grid ───────────────────────────────


def plot_lang_severity_grid(metrics: dict, output_dir: str, model_label: str = ""):
    """
    Heatmap of mean buggy–fixed distance for each (language, severity) cell.
    """
    pw_ls = metrics.get("pairwise_distances", {}).get("per_language_severity", {})
    if not pw_ls:
        print("    No per_language_severity data — skipping.")
        return

    languages = sorted(pw_ls.keys())
    severities = [s for s in SEVERITY_ORDER if any(s in pw_ls[l] for l in languages)]

    matrix = np.full((len(languages), len(severities)), np.nan)
    for i, lang in enumerate(languages):
        for j, sev in enumerate(severities):
            val = pw_ls.get(lang, {}).get(sev, {}).get("mean")
            if val is not None:
                matrix[i, j] = val

    fig, ax = plt.subplots(
        figsize=(max(6, len(severities) * 2), max(4, len(languages) * 1.2))
    )
    im = ax.imshow(
        matrix,
        aspect="auto",
        cmap="RdYlGn",
        vmin=0.0,
        vmax=np.nanmax(matrix) * 1.1 if not np.all(np.isnan(matrix)) else 1.0,
    )
    fig.colorbar(im, ax=ax, shrink=0.7, label="Mean Buggy–Fixed Cosine Distance")

    ax.set_xticks(range(len(severities)))
    ax.set_xticklabels(severities, fontsize=10)
    ax.set_yticks(range(len(languages)))
    ax.set_yticklabels([l.upper() for l in languages], fontsize=10)

    for i in range(len(languages)):
        for j in range(len(severities)):
            if not np.isnan(matrix[i, j]):
                ax.text(
                    j,
                    i,
                    f"{matrix[i, j]:.3f}",
                    ha="center",
                    va="center",
                    fontsize=9,
                    fontweight="bold",
                    color="black" if matrix[i, j] > np.nanmedian(matrix) else "white",
                )

    title = (
        "RQ4: Buggy–Fixed Distance by Language × Severity\n(higher = more separable)"
    )
    if model_label:
        title = f"[{model_label}] {title}"
    ax.set_title(title)
    plt.tight_layout()

    path = os.path.join(output_dir, "language_severity_distance_grid.png")
    fig.savefig(path, bbox_inches="tight")
    plt.close(fig)
    print(f"    Saved {path}")


# ── Run visualization for one model ──────────────────────────────────────────


def run_visualize(model_key: str) -> bool:
    """Generate all RQ4 plots for a single model."""
    model_dir = os.path.join(OUTPUT_BASE, model_key)
    embeddings_file = os.path.join(model_dir, "rq4_embeddings.parquet")
    metrics_file = os.path.join(model_dir, "rq4_metrics.json")

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
    print(
        f"  {len(df)} snippets  |  {df['bug_type'].nunique()} bug types  |  "
        f"{df['language'].nunique()} languages"
    )

    os.makedirs(model_dir, exist_ok=True)

    print(f"\n── Generating Visualizations ──")
    plot_tsne_all(df, model_dir, model_label)
    plot_tsne_faceted(df, model_dir, model_label)
    plot_tsne_severity(df, model_dir, model_label)
    plot_silhouette_bars(metrics, model_dir, model_label)
    plot_pairwise_distances(metrics, model_dir, model_label)
    plot_cluster_separation(metrics, model_dir, model_label)
    plot_dangerous_heatmap(metrics, model_dir, model_label)
    plot_severity_heatmap(metrics, model_dir, model_label)
    plot_per_language_severity_heatmaps(metrics, model_dir, model_label)
    plot_lang_severity_grid(metrics, model_dir, model_label)

    print(f"\nAll plots saved to {model_dir}/")
    return True


# ── Main ──────────────────────────────────────────────────────────────────────


def main():
    parser = argparse.ArgumentParser(
        description="Generate RQ4 visualizations for one or all models."
    )
    parser.add_argument(
        "--model",
        type=str,
        default="all",
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

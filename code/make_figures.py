"""
Regenerate the paper's data figures from saved results with one colour palette.

Figures:
  rq1_dendrogram_<model>.pdf     Ward dendrogram of the 19 language vectors
  rq2_tsne_scatter.pdf           t-SNE of the RQ2 embeddings under BGE-M3
  rq2_silhouette_bge_m3.pdf      per-language framework silhouette under BGE-M3
  rq4_dangerous_comparison.pdf   dangerous-neighbourhood rates per model
  rq5_*.pdf                      RQ5 figures (if RQ5 results exist)

Usage:
    python code/make_figures.py [output_dir]      (default: results/figures)
"""

import json
import os
import sys

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np
import pandas as pd
from scipy.cluster.hierarchy import dendrogram, fcluster, linkage, set_link_color_palette
from scipy.spatial.distance import squareform
from sklearn.manifold import TSNE

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(SCRIPT_DIR)
OUT = sys.argv[1] if len(sys.argv) > 1 else os.path.join(ROOT, "results/figures")

# Palette from the FSE 2027 logo (matches the paper's LaTeX colours).
NAVY, BLUE, SKY = "#1E2A4A", "#2E78B7", "#DEEAF6"
SUN, GOLD, RED = "#E25830", "#F2B233", "#C03030"
TEAL, GREY = "#3A9DAE", "#8A8F99"
CATEGORICAL = [NAVY, SUN, BLUE, GOLD, TEAL, "#8E3B8E", "#5C8A3A", GREY]

plt.rcParams.update({
    "font.family": "DejaVu Sans", "font.size": 9, "axes.edgecolor": NAVY, "axes.labelcolor": NAVY,
    "xtick.color": NAVY, "ytick.color": NAVY, "axes.spines.top": False, "axes.spines.right": False,
    "savefig.bbox": "tight", "figure.dpi": 150,
})

LABEL = {"ada002": "Ada-002", "bge_m3": "BGE-M3", "codebert": "CodeBERT", "minilm": "MiniLM",
         "octen": "Octen", "qwen3": "Qwen3", "unixcoder": "UniXCoder"}


def save(fig, name):
    os.makedirs(OUT, exist_ok=True)
    fig.savefig(os.path.join(OUT, name))
    plt.close(fig)
    print("saved", name)


def rq1_dendrogram(model: str):
    with open(os.path.join(ROOT, f"results/clustering/{model}/cosine_distance_matrix.json"), encoding="utf-8") as f:
        d = json.load(f)
    langs = [l.replace("_", " ") for l in d["languages"]]
    cond = squareform(np.array(d["matrix"]), checks=False)
    Z = linkage(cond, method="ward")
    k = 5
    threshold = Z[-(k - 1), 2] - 1e-12
    set_link_color_palette([NAVY, SUN, BLUE, GOLD, TEAL])
    fig, ax = plt.subplots(figsize=(4.0, 2.3))
    dendrogram(Z, labels=langs, ax=ax, color_threshold=threshold, above_threshold_color=GREY, leaf_rotation=90)
    ax.set_ylabel("Ward linkage distance", fontsize=8)
    ax.tick_params(axis="x", labelsize=7)
    ax.tick_params(axis="y", labelsize=7)
    save(fig, f"rq1_dendrogram_{model}.pdf")


def rq2_tsne():
    df = pd.read_parquet(os.path.join(ROOT, "results/rq2/bge_m3/rq2_embeddings.parquet"))
    X = np.vstack(df["embedding"].values)
    xy = TSNE(n_components=2, random_state=42, perplexity=30, max_iter=1000).fit_transform(X)
    langs = sorted(df["language"].unique())
    markers = ["o", "s", "^", "D"]
    fig, ax = plt.subplots(figsize=(7.2, 3.0))
    for li, lang in enumerate(langs):
        sub = df["language"] == lang
        fws = sorted(df.loc[sub, "framework"].unique())
        for fi, fw in enumerate(fws):
            m = (sub & (df["framework"] == fw)).values
            ax.scatter(xy[m, 0], xy[m, 1], s=11, color=CATEGORICAL[li % len(CATEGORICAL)],
                       marker=markers[fi % 4], alpha=0.8, linewidths=0)
    for li, lang in enumerate(langs):
        ax.scatter([], [], color=CATEGORICAL[li % len(CATEGORICAL)], marker="o", s=20, label=lang)
    ax.legend(title="Language (marker shape = framework)", ncol=4, fontsize=7, title_fontsize=7,
              frameon=False, loc="upper center", bbox_to_anchor=(0.5, -0.02))
    ax.set_xticks([]); ax.set_yticks([])
    ax.spines["left"].set_visible(False); ax.spines["bottom"].set_visible(False)
    save(fig, "rq2_tsne_scatter.pdf")


def rq2_silhouette():
    with open(os.path.join(ROOT, "results/rq2/bge_m3/rq2_metrics.json"), encoding="utf-8") as f:
        per = json.load(f)["silhouette"]["framework_silhouette_per_language"]
    items = sorted(per.items(), key=lambda x: -x[1])
    fig, ax = plt.subplots(figsize=(5.2, 2.2))
    ax.bar([k for k, _ in items], [v for _, v in items], color=BLUE, edgecolor=NAVY, linewidth=0.5)
    for i, (_, v) in enumerate(items):
        ax.text(i, v + 0.004, f"{v:.3f}", ha="center", fontsize=7, color=NAVY)
    ax.set_ylabel("Framework silhouette")
    ax.tick_params(axis="x", labelrotation=30)
    save(fig, "rq2_silhouette_bge_m3.pdf")


def rq4_danger():
    models = ["unixcoder", "minilm", "bge_m3", "octen", "qwen3", "ada002", "codebert"]
    rates = {}
    for m in models:
        with open(os.path.join(ROOT, f"results/RQ4/{m}/rq4_metrics.json"), encoding="utf-8") as f:
            dn = json.load(f)["dangerous_neighbourhoods"]
        rates[m] = [dn[f"threshold_{t}"]["overall"]["pct"] for t in (0.05, 0.1, 0.15)]
    fig, ax = plt.subplots(figsize=(7.2, 1.9))
    w = 0.26
    x = np.arange(len(models))
    for i, (t, c) in enumerate(zip(("0.05", "0.10", "0.15"), (SKY, BLUE, NAVY))):
        ax.bar(x + (i - 1) * w, [rates[m][i] for m in models], w, color=c, edgecolor=NAVY, linewidth=0.5,
               label=f"$\\tau$ = {t}")
    ax.set_xticks(x, [LABEL[m] for m in models])
    ax.set_ylabel("Pairs below $\\tau$ (%)")
    ax.set_ylim(0, 105)
    ax.legend(frameon=False, ncol=3, fontsize=8, loc="upper left")
    save(fig, "rq4_dangerous_comparison.pdf")


def main():
    for m in ("unixcoder", "codebert"):
        rq1_dendrogram(m)
    rq2_tsne()
    rq2_silhouette()
    rq4_danger()


if __name__ == "__main__":
    main()

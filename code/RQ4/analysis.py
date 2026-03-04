import json
import os

import matplotlib.pyplot as plt
import numpy as np
import seaborn as sns
from scipy.spatial.distance import cosine, euclidean
from sklearn.manifold import TSNE
import inspect

# Set style for better-looking plots
sns.set_style("whitegrid")
plt.rcParams["font.size"] = 10

# Load embeddings
script_dir = os.path.dirname(os.path.abspath(__file__))
embeddings_path = os.path.join(
    script_dir, "../../results/embeddings/rq4_octen_embeddings.json"
)
print(f"Loading embeddings from {embeddings_path}...")

with open(embeddings_path, "r", encoding="utf-8") as f:
    embeddings_data = json.load(f)

print(f"Loaded embeddings for {len(embeddings_data)} languages")

# Prepare output directory
output_dir = os.path.join(script_dir, "../../results/RQ4")
os.makedirs(output_dir, exist_ok=True)


def calculate_pairwise_distances(buggy_emb: np.ndarray, fixed_emb: np.ndarray) -> dict:
    """Calculate distance metrics between buggy and fixed embeddings"""
    return {
        "cosine": cosine(buggy_emb, fixed_emb),
        "euclidean": euclidean(buggy_emb, fixed_emb),
    }


def calculate_intra_cluster_distances(embeddings: np.ndarray) -> float:
    """Calculate average pairwise distance within a cluster"""
    n = len(embeddings)
    if n < 2:
        return 0.0

    distances = []
    for i in range(n):
        for j in range(i + 1, n):
            distances.append(cosine(embeddings[i], embeddings[j]))

    return np.mean(distances) if distances else 0.0


def calculate_cross_cluster_distances(
    buggy_embeddings: np.ndarray, fixed_embeddings: np.ndarray
) -> float:
    """Calculate average distance between buggy and fixed clusters"""
    distances = []
    for buggy_emb in buggy_embeddings:
        for fixed_emb in fixed_embeddings:
            distances.append(cosine(buggy_emb, fixed_emb))

    return np.mean(distances) if distances else 0.0


# Analyze each language
print("\n" + "=" * 80)
print("RQ4 ANALYSIS: Do correct implementations cluster separately from buggy ones?")
print("=" * 80)

language_results = {}

for lang in sorted(embeddings_data.keys()):
    print(f"\nAnalyzing {lang}...")

    buggy_embs = np.array(embeddings_data[lang]["buggy_embeddings"])
    fixed_embs = np.array(embeddings_data[lang]["fixed_embeddings"])

    # Calculate pairwise distances between each buggy-fixed pair
    pairwise_distances = []
    for i in range(len(buggy_embs)):
        dist = calculate_pairwise_distances(buggy_embs[i], fixed_embs[i])
        pairwise_distances.append(dist)

    cosine_distances = [d["cosine"] for d in pairwise_distances]
    euclidean_distances = [d["euclidean"] for d in pairwise_distances]

    # Calculate clustering metrics
    intra_buggy = calculate_intra_cluster_distances(buggy_embs)
    intra_fixed = calculate_intra_cluster_distances(fixed_embs)
    cross_distance = calculate_cross_cluster_distances(buggy_embs, fixed_embs)

    # Separation score: positive means good separation
    separation_score = cross_distance - (intra_buggy + intra_fixed) / 2

    language_results[lang] = {
        "n_pairs": len(buggy_embs),
        "pairwise_cosine_mean": np.mean(cosine_distances),
        "pairwise_cosine_std": np.std(cosine_distances),
        "pairwise_cosine_min": np.min(cosine_distances),
        "pairwise_cosine_max": np.max(cosine_distances),
        "pairwise_euclidean_mean": np.mean(euclidean_distances),
        "intra_buggy_distance": intra_buggy,
        "intra_fixed_distance": intra_fixed,
        "cross_distance": cross_distance,
        "separation_score": separation_score,
        "cosine_distances": cosine_distances,
    }

    print(f"  Pairs: {len(buggy_embs)}")
    print(f"  Mean cosine distance (buggy-fixed): {np.mean(cosine_distances):.4f}")
    print(f"  Separation score: {separation_score:.4f}")

# Save results to JSON
results_json = os.path.join(output_dir, "analysis_results.json")
print(f"\nSaving results to {results_json}...")

# Create JSON-serializable version
results_to_save = {}
for lang, result in language_results.items():
    results_to_save[lang] = {k: v for k, v in result.items() if k != "cosine_distances"}
    results_to_save[lang]["cosine_distances"] = result["cosine_distances"]

with open(results_json, "w", encoding="utf-8") as f:
    json.dump(results_to_save, f, indent=2)

# ============================================================================
# VISUALIZATION 1: Distance Distributions per Language
# ============================================================================
print("\nGenerating distance distribution plots...")

n_langs = len(language_results)
fig, axes = plt.subplots(2, 3, figsize=(18, 10))
fig.suptitle(
    "Cosine Distance Distribution: Buggy vs Fixed Code\n(Using Octen-Embedding-0.6B)",
    fontsize=16,
)

for idx, (lang, result) in enumerate(sorted(language_results.items())):
    row, col = idx // 3, idx % 3
    ax = axes[row, col]

    ax.hist(result["cosine_distances"], bins=20, alpha=0.7, edgecolor="black")
    ax.axvline(
        result["pairwise_cosine_mean"],
        color="red",
        linestyle="--",
        linewidth=2,
        label=f'Mean: {result["pairwise_cosine_mean"]:.4f}',
    )
    ax.set_title(f"{lang.upper()}", fontsize=12, fontweight="bold")
    ax.set_xlabel("Cosine Distance")
    ax.set_ylabel("Frequency")
    ax.legend()
    ax.grid(alpha=0.3)

# Hide unused subplots
for idx in range(n_langs, 6):
    row, col = idx // 3, idx % 3
    axes[row, col].axis("off")

plt.tight_layout()
dist_plot_path = os.path.join(output_dir, "distance_distributions.png")
plt.savefig(dist_plot_path, dpi=300, bbox_inches="tight")
print(f"Saved: {dist_plot_path}")
plt.close()

# ============================================================================
# VISUALIZATION 2: Cross-Language Comparison
# ============================================================================
print("Generating cross-language comparison...")

languages = sorted(language_results.keys())
means = [language_results[lang]["pairwise_cosine_mean"] for lang in languages]
stds = [language_results[lang]["pairwise_cosine_std"] for lang in languages]

fig, ax = plt.subplots(figsize=(12, 6))
x = np.arange(len(languages))
bars = ax.bar(x, means, yerr=stds, capsize=5, alpha=0.7, edgecolor="black")

ax.set_xlabel("Programming Language", fontsize=12)
ax.set_ylabel("Mean Cosine Distance (Buggy vs Fixed)", fontsize=12)
ax.set_title(
    "Average Distance Between Buggy and Fixed Code\n(Using Octen-Embedding-0.6B)",
    fontsize=14,
)
ax.set_xticks(x)
ax.set_xticklabels([lang.upper() for lang in languages])
ax.grid(axis="y", alpha=0.3)

# Add value labels
for bar, mean in zip(bars, means):
    height = bar.get_height()
    ax.text(
        bar.get_x() + bar.get_width() / 2.0,
        height,
        f"{mean:.4f}",
        ha="center",
        va="bottom",
        fontsize=9,
    )

plt.tight_layout()
comparison_plot_path = os.path.join(output_dir, "cross_language_comparison.png")
plt.savefig(comparison_plot_path, dpi=300, bbox_inches="tight")
print(f"Saved: {comparison_plot_path}")
plt.close()

# ============================================================================
# VISUALIZATION 3: Clustering Separation Metrics
# ============================================================================
print("Generating clustering metrics plots...")

fig, (ax1, ax2) = plt.subplots(1, 2, figsize=(16, 6))

# Plot 1: Intra vs Cross distances
x = np.arange(len(languages))
width = 0.25

intra_buggy = [language_results[lang]["intra_buggy_distance"] for lang in languages]
intra_fixed = [language_results[lang]["intra_fixed_distance"] for lang in languages]
cross_dists = [language_results[lang]["cross_distance"] for lang in languages]

ax1.bar(x - width, intra_buggy, width, label="Intra-Buggy", alpha=0.8)
ax1.bar(x, intra_fixed, width, label="Intra-Fixed", alpha=0.8)
ax1.bar(x + width, cross_dists, width, label="Cross (Buggy-Fixed)", alpha=0.8)

ax1.set_xlabel("Programming Language", fontsize=12)
ax1.set_ylabel("Mean Cosine Distance", fontsize=12)
ax1.set_title("Clustering Separation: Intra vs Cross Distances", fontsize=13)
ax1.set_xticks(x)
ax1.set_xticklabels([lang.upper() for lang in languages])
ax1.legend()
ax1.grid(axis="y", alpha=0.3)

# Plot 2: Separation scores
separation_scores = [language_results[lang]["separation_score"] for lang in languages]
bars = ax2.bar(x, separation_scores, alpha=0.7, edgecolor="black")

# Color bars based on score (green=good, red=poor)
for bar, score in zip(bars, separation_scores):
    if score > 0:
        bar.set_color("green")
    else:
        bar.set_color("red")

ax2.axhline(y=0, color="black", linestyle="--", linewidth=1.5, label="No Separation")
ax2.set_xlabel("Programming Language", fontsize=12)
ax2.set_ylabel("Separation Score", fontsize=12)
ax2.set_title("Clustering Separation Score\n(Higher = Better Separation)", fontsize=13)
ax2.set_xticks(x)
ax2.set_xticklabels([lang.upper() for lang in languages])
ax2.legend()
ax2.grid(axis="y", alpha=0.3)

plt.tight_layout()
clustering_plot_path = os.path.join(output_dir, "clustering_metrics.png")
plt.savefig(clustering_plot_path, dpi=300, bbox_inches="tight")
print(f"Saved: {clustering_plot_path}")
plt.close()

# ============================================================================
# VISUALIZATION 4: t-SNE Visualization
# ============================================================================
print("\nGenerating t-SNE visualization...")

all_embeddings = []
labels = []  # 'buggy' or 'fixed'
languages_list = []

for lang in sorted(embeddings_data.keys()):
    buggy_embs = np.array(embeddings_data[lang]["buggy_embeddings"])
    fixed_embs = np.array(embeddings_data[lang]["fixed_embeddings"])

    all_embeddings.extend(buggy_embs)
    labels.extend(["buggy"] * len(buggy_embs))
    languages_list.extend([lang] * len(buggy_embs))

    all_embeddings.extend(fixed_embs)
    labels.extend(["fixed"] * len(fixed_embs))
    languages_list.extend([lang] * len(fixed_embs))

embeddings_array = np.array(all_embeddings)
print(f"Running t-SNE on {len(embeddings_array)} samples...")

# runtime-compatible parameter name: older scikit-learn uses `n_iter`,
# newer versions (>=1.5) renamed it to `max_iter`.
_tsne_sig = inspect.signature(TSNE.__init__)
_tsne_kwargs = dict(n_components=2, random_state=42, perplexity=30)
if "max_iter" in _tsne_sig.parameters:
    tsne = TSNE(**_tsne_kwargs, max_iter=1000)
elif "n_iter" in _tsne_sig.parameters:
    tsne = TSNE(**_tsne_kwargs, n_iter=1000)
else:
    tsne = TSNE(**_tsne_kwargs)

embeddings_2d = tsne.fit_transform(embeddings_array)

# Create two-panel plot
fig, axes = plt.subplots(1, 2, figsize=(20, 8))

# Panel 1: Color by buggy/fixed
ax1 = axes[0]
for label_type in ["buggy", "fixed"]:
    mask = np.array(labels) == label_type
    color = "red" if label_type == "buggy" else "green"
    ax1.scatter(
        embeddings_2d[mask, 0],
        embeddings_2d[mask, 1],
        label=label_type,
        alpha=0.6,
        s=50,
        c=color,
    )

ax1.set_title("t-SNE: Buggy vs Fixed Code (All Languages)", fontsize=14)
ax1.legend()
ax1.grid(alpha=0.3)

# Panel 2: Color by language
ax2 = axes[1]
unique_langs = sorted(set(languages_list))
colors = plt.cm.tab10(np.linspace(0, 1, len(unique_langs)))

for lang, color in zip(unique_langs, colors):
    mask = np.array(languages_list) == lang
    buggy_mask = mask & (np.array(labels) == "buggy")
    fixed_mask = mask & (np.array(labels) == "fixed")

    ax2.scatter(
        embeddings_2d[buggy_mask, 0],
        embeddings_2d[buggy_mask, 1],
        color=color,
        marker="x",
        s=100,
        alpha=0.7,
        label=f"{lang} (buggy)",
    )
    ax2.scatter(
        embeddings_2d[fixed_mask, 0],
        embeddings_2d[fixed_mask, 1],
        color=color,
        marker="o",
        s=50,
        alpha=0.7,
        label=f"{lang} (fixed)",
    )

ax2.set_title("t-SNE: By Language (x=buggy, o=fixed)", fontsize=14)
ax2.legend(bbox_to_anchor=(1.05, 1), loc="upper left", fontsize=8)
ax2.grid(alpha=0.3)

plt.tight_layout()
tsne_plot_path = os.path.join(output_dir, "tsne_visualization.png")
plt.savefig(tsne_plot_path, dpi=300, bbox_inches="tight")
print(f"Saved: {tsne_plot_path}")
plt.close()

# ============================================================================
# GENERATE SUMMARY REPORT
# ============================================================================
print("\nGenerating summary report...")

report_path = os.path.join(output_dir, "analysis_summary.txt")
with open(report_path, "w", encoding="utf-8") as f:
    f.write("=" * 80 + "\n")
    f.write("RQ4 ANALYSIS SUMMARY REPORT\n")
    f.write(
        "Research Question: Do correct implementations cluster separately from buggy ones?\n"
    )
    f.write("Model: Octen-Embedding-0.6B (same as RQ1)\n")
    f.write("=" * 80 + "\n\n")

    # Overall statistics
    all_cosine_distances = []
    for lang in language_results:
        all_cosine_distances.extend(language_results[lang]["cosine_distances"])

    f.write("Overall Statistics (All Languages Combined):\n")
    f.write(f"  Total buggy-fixed pairs: {len(all_cosine_distances)}\n")
    f.write(f"  Mean cosine distance: {np.mean(all_cosine_distances):.4f}\n")
    f.write(f"  Std cosine distance: {np.std(all_cosine_distances):.4f}\n")
    f.write(f"  Min cosine distance: {np.min(all_cosine_distances):.4f}\n")
    f.write(f"  Max cosine distance: {np.max(all_cosine_distances):.4f}\n\n")

    # Per-language analysis
    f.write("-" * 80 + "\n")
    f.write("PER-LANGUAGE ANALYSIS\n")
    f.write("-" * 80 + "\n\n")

    for lang in sorted(language_results.keys()):
        result = language_results[lang]
        f.write(f"\n{lang.upper()}:\n")
        f.write(f"  Sample pairs: {result['n_pairs']}\n")
        f.write(f"\n")
        f.write(f"  Distance Statistics (Buggy vs Fixed):\n")
        f.write(f"    Mean cosine distance: {result['pairwise_cosine_mean']:.4f}\n")
        f.write(f"    Std cosine distance: {result['pairwise_cosine_std']:.4f}\n")
        f.write(f"    Min cosine distance: {result['pairwise_cosine_min']:.4f}\n")
        f.write(f"    Max cosine distance: {result['pairwise_cosine_max']:.4f}\n")
        f.write(f"\n")
        f.write(f"  Clustering Metrics:\n")
        f.write(f"    Intra-buggy distance: {result['intra_buggy_distance']:.4f}\n")
        f.write(f"    Intra-fixed distance: {result['intra_fixed_distance']:.4f}\n")
        f.write(f"    Cross distance: {result['cross_distance']:.4f}\n")
        f.write(f"    Separation score: {result['separation_score']:.4f}\n")

        if result["separation_score"] > 0:
            f.write(
                f"    → GOOD separation: Buggy and fixed code form distinct clusters\n"
            )
        else:
            f.write(
                f"    → POOR separation: Buggy and fixed code overlap significantly\n"
            )

    # Key findings
    f.write("\n" + "=" * 80 + "\n")
    f.write("KEY FINDINGS\n")
    f.write("=" * 80 + "\n\n")

    # Best/worst separation
    best_lang = max(
        language_results.keys(), key=lambda x: language_results[x]["separation_score"]
    )
    worst_lang = min(
        language_results.keys(), key=lambda x: language_results[x]["separation_score"]
    )

    f.write(f"1. Best Separation: {best_lang.upper()} ")
    f.write(f"(score: {language_results[best_lang]['separation_score']:.4f})\n")
    f.write(f"   Buggy and fixed code are most distinguishable in this language.\n\n")

    f.write(f"2. Worst Separation: {worst_lang.upper()} ")
    f.write(f"(score: {language_results[worst_lang]['separation_score']:.4f})\n")
    f.write(
        f"   This language shows the most overlap between buggy and fixed code.\n\n"
    )

    # Dangerous neighborhoods
    f.write(f"3. 'Dangerous Neighborhoods' Analysis:\n")
    f.write(
        f"   (Pairs with cosine distance < 0.1 are very similar despite being buggy vs fixed)\n\n"
    )
    for lang in sorted(language_results.keys()):
        close_pairs = sum(
            1 for d in language_results[lang]["cosine_distances"] if d < 0.1
        )
        total_pairs = language_results[lang]["n_pairs"]
        pct = (close_pairs / total_pairs * 100) if total_pairs > 0 else 0
        f.write(f"   {lang.upper()}: {close_pairs}/{total_pairs} ({pct:.1f}%) ")
        f.write(f"pairs are dangerously similar\n")

    f.write("\n" + "=" * 80 + "\n")

print(f"Saved: {report_path}")

# Print summary to console
print("\n" + "=" * 80)
print("ANALYSIS COMPLETE!")
print("=" * 80)
print(f"\nOutput files (in {output_dir}):")
print(f"  • distance_distributions.png")
print(f"  • cross_language_comparison.png")
print(f"  • clustering_metrics.png")
print(f"  • tsne_visualization.png")
print(f"  • analysis_results.json")
print(f"  • analysis_summary.txt")
print("\n")

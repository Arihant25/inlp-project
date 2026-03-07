import json
import numpy as np
import requests
from scipy.spatial.distance import squareform
from scipy.stats import spearmanr
import seaborn as sns
import matplotlib.pyplot as plt


# -------------------------------------------------
# PUT YOUR MATRIX FILE LINKS OR FILE PATHS HERE
# -------------------------------------------------

MODEL_MATRICES = {
    "octen": "../results/clustering/octen/cosine_distance_matrix.json",
    "bge_m3": "../results/clustering/bge_m3/cosine_distance_matrix.json",
    "unixcoder": "../results/clustering/unixcoder/cosine_distance_matrix.json",
    "qwen3": "../results/clustering/qwen3/cosine_distance_matrix.json",
    "minilm": "../results/clustering/minilm/cosine_distance_matrix.json",
    "ada002": "../results/clustering/ada002/cosine_distance_matrix.json"
}


# -------------------------------------------------
# Helper functions
# -------------------------------------------------

def load_matrix(path_or_url):
    """Load matrix JSON from file path or URL."""
    
    if path_or_url.startswith("http"):
        response = requests.get(path_or_url)
        data = response.json()
    else:
        with open(path_or_url, "r") as f:
            data = json.load(f)

    matrix = np.array(data["matrix"])
    languages = data["languages"]

    return matrix, languages


def matrix_to_vector(matrix):
    """
    Convert distance matrix to vector using upper triangle.
    This removes duplicate distances.
    """
    return squareform(matrix)


# -------------------------------------------------
# Load all matrices
# -------------------------------------------------

matrices = {}
languages_reference = None

for model, path in MODEL_MATRICES.items():

    matrix, langs = load_matrix(path)

    if languages_reference is None:
        languages_reference = langs

    matrices[model] = matrix

print("Loaded matrices for models:", list(matrices.keys()))
print("Languages:", languages_reference)


# -------------------------------------------------
# Convert matrices to vectors
# -------------------------------------------------

vectors = {}

for model, matrix in matrices.items():
    vectors[model] = matrix_to_vector(matrix)


# -------------------------------------------------
# Compute model similarity matrix
# -------------------------------------------------

models = list(vectors.keys())
n = len(models)

similarity_matrix = np.zeros((n, n))

for i in range(n):
    for j in range(n):

        v1 = vectors[models[i]]
        v2 = vectors[models[j]]

        corr, _ = spearmanr(v1, v2)

        similarity_matrix[i, j] = corr


# -------------------------------------------------
# Print similarity table
# -------------------------------------------------

print("\nModel Similarity (Spearman correlation):\n")

header = " " * 12 + "".join(f"{m:>12}" for m in models)
print(header)

for i, model in enumerate(models):

    row = f"{model:<12}"

    for j in range(n):
        row += f"{similarity_matrix[i,j]:12.3f}"

    print(row)


# -------------------------------------------------
# Plot heatmap
# -------------------------------------------------

plt.figure(figsize=(8,6))

sns.heatmap(
    similarity_matrix,
    annot=True,
    xticklabels=models,
    yticklabels=models,
    cmap="coolwarm",
    vmin=0,
    vmax=1
)

plt.title("Embedding Model Similarity (Spearman Correlation)")
plt.tight_layout()

plt.savefig("model_similarity_heatmap.png", dpi=300)
plt.show()


# -------------------------------------------------
# Identify most similar models
# -------------------------------------------------

print("\nMost similar model pairs:\n")

pairs = []

for i in range(n):
    for j in range(i+1, n):

        pairs.append(
            (models[i], models[j], similarity_matrix[i,j])
        )

pairs.sort(key=lambda x: x[2], reverse=True)

for m1, m2, score in pairs:
    print(f"{m1:12} ↔ {m2:12} : {score:.3f}")
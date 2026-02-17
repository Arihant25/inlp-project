import json
import numpy as np
from scipy.cluster.hierarchy import linkage, dendrogram
from scipy.spatial.distance import squareform, cosine
import matplotlib.pyplot as plt
import os

# Load embeddings
script_dir = os.path.dirname(os.path.abspath(__file__))
embeddings_path = os.path.join(script_dir, "../../results/embeddings/octen_embeddings.json")
print(f"Loading embeddings from {embeddings_path}...")

with open(embeddings_path, 'r', encoding='utf-8') as f:
    embeddings_data = json.load(f)

print(f"Loaded {len(embeddings_data)} embeddings")

# Group embeddings by language
# Each language has 21 features, we'll average them to get a single embedding per language
languages = [
    "AppleScript", "C", "C++", "Dart", "Fortran", "Go", "Haskell",
    "Java", "JavaScript", "Kotlin", "PHP", "Pascal", "Python",
    "Raku", "Ruby", "Rust", "Scala", "Swift", "Visual_Basic"
]

print("\nAggregating embeddings by language...")
language_embeddings = {}

for lang in languages:
    lang_embs = []
    for key, data in embeddings_data.items():
        if key.startswith(lang + " "):
            lang_embs.append(np.array(data['embedding']))
    
    if lang_embs:
        # Average all feature embeddings for this language
        language_embeddings[lang] = np.mean(lang_embs, axis=0)
        print(f"  {lang}: {len(lang_embs)} features averaged")
    else:
        print(f"  Warning: No embeddings found for {lang}")

# Create ordered list of languages and their embeddings
ordered_langs = sorted(language_embeddings.keys())
embedding_matrix = np.array([language_embeddings[lang] for lang in ordered_langs])

print(f"\nEmbedding matrix shape: {embedding_matrix.shape}")

# Compute pairwise cosine distance matrix
print("Computing pairwise cosine distances...")
n = len(ordered_langs)
distance_matrix = np.zeros((n, n))

for i in range(n):
    for j in range(n):
        if i == j:
            distance_matrix[i, j] = 0
        else:
            # Cosine distance = 1 - cosine similarity
            distance_matrix[i, j] = cosine(embedding_matrix[i], embedding_matrix[j])

# Ensure symmetry
distance_matrix = (distance_matrix + distance_matrix.T) / 2

# Save distance matrix
output_dir = os.path.join(script_dir, "../../results/clustering")
os.makedirs(output_dir, exist_ok=True)

distance_output = os.path.join(output_dir, "cosine_distance_matrix.json")
print(f"\nSaving distance matrix to {distance_output}...")

distance_dict = {}
for i, lang in enumerate(ordered_langs):
    distance_dict[lang] = distance_matrix[i].tolist()

with open(distance_output, 'w', encoding='utf-8') as f:
    json.dump(distance_dict, f, indent=2)

# Also save as a readable table
distance_table = os.path.join(output_dir, "cosine_distance_matrix.txt")
print(f"Saving distance matrix table to {distance_table}...")

with open(distance_table, 'w', encoding='utf-8') as f:
    # Header
    f.write(f"{'Language':<20}")
    for lang in ordered_langs:
        f.write(f"{lang:<15}")
    f.write("\n")
    f.write("-" * (20 + 15 * len(ordered_langs)) + "\n")
    
    # Rows
    for i, lang in enumerate(ordered_langs):
        f.write(f"{lang:<20}")
        for j in range(len(ordered_langs)):
            f.write(f"{distance_matrix[i, j]:<15.4f}")
        f.write("\n")

# Perform hierarchical clustering
print("\nPerforming hierarchical clustering...")
condensed_distances = squareform(distance_matrix)
Z = linkage(condensed_distances, method='ward')
# Create dendrogram visualization
print("Generating dendrogram...")
plt.figure(figsize=(12, 6))

dendrogram(Z, labels=ordered_langs, leaf_rotation=90)
plt.xlabel('Programming Language', fontsize=12)
plt.ylabel('Distance', fontsize=12)
plt.title('Hierarchical Clustering of Programming Languages\n(Based on Octen-Embedding-0.6B)', fontsize=14)
plt.xticks(rotation=90, fontsize=10)
plt.tight_layout()

# Save dendrogram
dendrogram_output = os.path.join(output_dir, "language_families_dendrogram.pdf")
plt.savefig(dendrogram_output, dpi=300, bbox_inches='tight')
print(f"Saved dendrogram to {dendrogram_output}")

# Also save as PNG for easy viewing
dendrogram_png = os.path.join(output_dir, "language_families_dendrogram.png")
plt.savefig(dendrogram_png, dpi=300, bbox_inches='tight')
print(f"Saved dendrogram to {dendrogram_png}")

plt.close()

# Find the most similar language pairs
print("\n" + "="*60)
print("Top 10 Most Similar Language Pairs:")
print("="*60)

similarities = []
for i in range(n):
    for j in range(i+1, n):
        similarities.append((ordered_langs[i], ordered_langs[j], distance_matrix[i, j]))

similarities.sort(key=lambda x: x[2])

for i, (lang1, lang2, dist) in enumerate(similarities[:10], 1):
    print(f"{i:2d}. {lang1:<15} ↔ {lang2:<15} (distance: {dist:.4f})")

# Find the centroid language (minimum average distance to all others)
print("\n" + "="*60)
print("Language Centrality Analysis:")
print("="*60)

avg_distances = []
for i, lang in enumerate(ordered_langs):
    avg_dist = np.mean([distance_matrix[i, j] for j in range(n) if i != j])
    avg_distances.append((lang, avg_dist))

avg_distances.sort(key=lambda x: x[1])

print("\nLanguages by centrality (most central first):")
for i, (lang, avg_dist) in enumerate(avg_distances, 1):
    print(f"{i:2d}. {lang:<15} (avg distance: {avg_dist:.4f})")

print("\n✓ Clustering analysis complete!")
print(f"\nOutput files:")
print(f"  • {dendrogram_output}")
print(f"  • {dendrogram_png}")
print(f"  • {distance_output}")
print(f"  • {distance_table}")

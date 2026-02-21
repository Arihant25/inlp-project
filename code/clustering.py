import argparse
import json
import os
import sys
from typing import Dict, List, Tuple

import matplotlib.pyplot as plt
import numpy as np
from scipy.cluster.hierarchy import dendrogram, linkage, fcluster, cophenet
from scipy.spatial.distance import squareform, cosine, pdist

# --- Configuration ---
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
EMBEDDINGS_DIR = os.path.join(SCRIPT_DIR, "../results/embeddings")
OUTPUT_DIR = os.path.join(SCRIPT_DIR, "../results/clustering")

MODELS = [
    "octen", "bge_m3", "unixcoder", "qwen3", "minilm", "ada002"
]

LANGUAGES = [
    "AppleScript", "C", "C++", "Dart", "Fortran", "Go", "Haskell",
    "Java", "JavaScript", "Kotlin", "PHP", "Pascal", "Python",
    "Raku", "Ruby", "Rust", "Scala", "Swift", "Visual_Basic"
]

# Provide a fixed order for consistency
ORDERED_LANGS = sorted(LANGUAGES)

def load_embeddings(model_key: str) -> Dict[str, np.ndarray]:
    path = os.path.join(EMBEDDINGS_DIR, f"{model_key}_embeddings.json")
    if not os.path.exists(path):
        print(f"Warning: Embeddings file not found: {path}")
        return None
        
    with open(path, 'r', encoding='utf-8') as f:
        data = json.load(f)
        
    embeddings = {}
    for key, val in data.items():
        embeddings[key] = np.array(val["embedding"])
        
    return embeddings


def aggregate_app_embeddings(embeddings_data: Dict[str, np.ndarray]) -> Tuple[List[str], np.ndarray]:
    """Aggregate (mean) embeddings per language."""
    lang_vectors = []
    found_langs = []
    
    for lang in ORDERED_LANGS:
        features = []
        for key, vector in embeddings_data.items():
            if key.startswith(lang + " "):
                features.append(vector)
        
        if features:
            # Mean pooling of all features for this language
            avg_vector = np.mean(features, axis=0)
            lang_vectors.append(avg_vector)
            found_langs.append(lang)
        else:
            print(f"  Warning: No features found for language {lang}")
            
    return found_langs, np.array(lang_vectors)


def perform_clustering(model_key: str):
    print(f"\n=== Clustering for {model_key} ===")
    
    # 1. Load Embeddings
    embeddings_data = load_embeddings(model_key)
    if not embeddings_data:
        return None
        
    # 2. Aggregate per Language
    langs, embedding_matrix = aggregate_app_embeddings(embeddings_data)
    if len(langs) < 2:
        print("Not enough languages to cluster.")
        return None
        
    # 3. Compute Distance Matrix (Cosine)
    # We use pairwise cosine distance
    dists = pdist(embedding_matrix, metric='cosine')
    distance_matrix = squareform(dists)
    
    # 4. Hierarchical Clustering (Ward)
    Z = linkage(dists, method='ward')
    
    # 5. Cophenetic Correlation Coefficient
    ccc, coph_dists = cophenet(Z, dists)
    print(f"  Cophenetic Correlation Coefficient (CCC): {ccc:.4f}")
    
    # 6. Cut Tree to get 5 Families
    # Criterion: 'maxclust' to get exactly 5 clusters
    num_clusters = 5
    cluster_labels = fcluster(Z, t=num_clusters, criterion='maxclust')
    
    families = {}
    for lang, label in zip(langs, cluster_labels):
        label_str = str(label)
        if label_str not in families:
            families[label_str] = []
        families[label_str].append(lang)
        
    # Sort families for consistent output (e.g. by size or first lang)
    sorted_families = {}
    for idx, (fam_id, fam_langs) in enumerate(sorted(families.items(), key=lambda x: x[0])):
        # Rename to Family_1, Family_2,...
        sorted_families[f"Family_{idx+1}"] = sorted(fam_langs)
        
    # --- Saving Results ---
    model_out_dir = os.path.join(OUTPUT_DIR, model_key)
    os.makedirs(model_out_dir, exist_ok=True)
    
    # Save Families JSON
    fam_path = os.path.join(model_out_dir, "language_families.json")
    with open(fam_path, 'w', encoding='utf-8') as f:
        json.dump(sorted_families, f, indent=2)
        
    # Save Distance Matrix
    dist_path = os.path.join(model_out_dir, "cosine_distance_matrix.json")
    dist_list = distance_matrix.tolist()
    # Serialize with language labels for readability
    dist_json = {"languages": langs, "matrix": dist_list}
    with open(dist_path, 'w', encoding='utf-8') as f:
        json.dump(dist_json, f, indent=2)
        
    # Save Dendrogram Plot
    plt.figure(figsize=(10, 6))
    dendrogram(Z, labels=langs, leaf_rotation=90)
    plt.title(f"Hierarchical Clustering - {model_key}\nCCC: {ccc:.4f}")
    plt.xlabel("Language")
    plt.ylabel("Distance")
    plt.tight_layout()
    plt.savefig(os.path.join(model_out_dir, "dendrogram.png"), dpi=300)
    plt.savefig(os.path.join(model_out_dir, "dendrogram.pdf"))
    plt.close()
    
    print(f"  Saved results to {model_out_dir}")
    
    return {
        "model": model_key,
        "ccc": ccc,
        "families": sorted_families
    }


def main():
    parser = argparse.ArgumentParser(description="Perform clustering on generated embeddings.")
    parser.add_argument("--model", type=str, default="all",
                        choices=MODELS + ["all"],
                        help="Model to use for clustering (default: all)")
    args = parser.parse_args()
    
    models_to_run = [args.model] if args.model != "all" else MODELS
    
    results = []
    for model_key in models_to_run:
        res = perform_clustering(model_key)
        if res:
            results.append(res)
            
    # Save comparison summary if running multiple or expected
    if results:
        # Sort by CCC descending (higher is better preservation of distances)
        results.sort(key=lambda x: x["ccc"], reverse=True)
        
        summary_path = os.path.join(OUTPUT_DIR, "family_comparison.json")
        with open(summary_path, 'w', encoding='utf-8') as f:
            json.dump(results, f, indent=2)
            
        print(f"\nSaved comparison summary to {summary_path}")
        print("\n=== Model Ranking by Cophenetic Correlation Coefficient ===")
        print(f"{'Rank':<5} {'Model':<15} {'CCC':<10}")
        print("-" * 35)
        for idx, res in enumerate(results, 1):
            print(f"{idx:<5} {res['model']:<15} {res['ccc']:.4f}")


if __name__ == "__main__":
    main()

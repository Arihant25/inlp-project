"""
RQ1: Cross-model stability of language families.

Reads the families produced by clustering.py for every model and computes:
  1. Pairwise co-clustering counts: for every language pair, the number of
     models that put both languages in the same family.
  2. Stable pairings: pairs that share a family under every model.
  3. Per-language family overlap: for each language, the Jaccard similarity
     |F_a ∩ F_b| / |F_a ∪ F_b| between its families F_a and F_b under two
     models (the family includes the language itself), averaged over all
     model pairs. 1 means the language has the same family under every model.

Usage:
    uv run code/family_stability.py

Input:  results/clustering/<model>/language_families.json
Output: results/clustering/family_stability.json
"""

import json
import os
from itertools import combinations

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
RESULTS_DIR = os.path.join(SCRIPT_DIR, "../results/clustering")
OUTPUT_FILE = os.path.join(RESULTS_DIR, "family_stability.json")

MODEL_KEYS = ["ada002", "bge_m3", "codebert", "minilm", "octen", "qwen3", "unixcoder"]


def load_families(model_key: str) -> dict[str, frozenset]:
    """Map each language to the set of languages in its family for one model."""
    path = os.path.join(RESULTS_DIR, model_key, "language_families.json")
    with open(path, "r", encoding="utf-8") as f:
        families = json.load(f)
    membership = {}
    for members in families.values():
        for lang in members:
            membership[lang] = frozenset(members)
    return membership


def jaccard(a: frozenset, b: frozenset) -> float:
    return len(a & b) / len(a | b)


def main():
    families = {m: load_families(m) for m in MODEL_KEYS}
    languages = sorted(families[MODEL_KEYS[0]])
    model_pairs = list(combinations(MODEL_KEYS, 2))

    # 1. Co-clustering counts
    co_cluster = []
    for l1, l2 in combinations(languages, 2):
        models = [m for m in MODEL_KEYS if l2 in families[m][l1]]
        if models:
            co_cluster.append({"pair": [l1, l2], "n_models": len(models), "models": models})
    co_cluster.sort(key=lambda x: (-x["n_models"], x["pair"]))

    # 2. Stable pairings
    stable = [c["pair"] for c in co_cluster if c["n_models"] == len(MODEL_KEYS)]

    # 3. Per-language family overlap
    overlap = {
        lang: round(
            sum(jaccard(families[a][lang], families[b][lang]) for a, b in model_pairs)
            / len(model_pairs),
            4,
        )
        for lang in languages
    }
    overlap = dict(sorted(overlap.items(), key=lambda x: -x[1]))

    result = {
        "models": MODEL_KEYS,
        "n_families_per_model": {
            m: len(set(families[m].values())) for m in MODEL_KEYS
        },
        "stable_pairings": stable,
        "co_clustering": co_cluster,
        "family_overlap": overlap,
        "family_overlap_definition": (
            "Mean over model pairs of the Jaccard similarity between the families "
            "that contain the language (the language itself included)."
        ),
    }

    with open(OUTPUT_FILE, "w", encoding="utf-8") as f:
        json.dump(result, f, indent=2)

    print(f"Stable pairings across all {len(MODEL_KEYS)} models: {stable}")
    print("Family overlap (highest first):")
    for lang, score in overlap.items():
        print(f"  {lang:14s} {score:.3f}")
    print(f"\nSaved → {OUTPUT_FILE}")


if __name__ == "__main__":
    main()

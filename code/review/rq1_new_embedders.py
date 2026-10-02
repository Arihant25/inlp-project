"""
Review: RQ1 language families for the two added code embedders (jina_code, codesage).

Runs code/clustering.py's perform_clustering (language vectors = mean of the feature
embeddings, cosine distances, Ward linkage, CCC, five families) for each new model, without
calling clustering.py's main (which would rewrite results/clustering/family_comparison.json).
Then compares each new model with the seven existing ones:
  - Spearman correlation of the condensed cosine distance matrices (as code/model_distance.py)
  - language pairs that share a family under all seven / all nine models (as code/family_stability.py)

Input:  results/embeddings/<model>_embeddings.json (code/embedding.py --model <model>)
        results/clustering/<model>/{cosine_distance_matrix,language_families}.json
Output: results/clustering/<new model>/* and results/review/rq1_new_embedders.json
"""

import json
import os
import sys
from itertools import combinations

import numpy as np
from scipy.spatial.distance import squareform
from scipy.stats import spearmanr

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
CODE_DIR = os.path.dirname(SCRIPT_DIR)
sys.path.insert(0, CODE_DIR)
import clustering  # noqa: E402

PROJECT_ROOT = os.path.dirname(CODE_DIR)
CLUSTER_DIR = os.path.join(PROJECT_ROOT, "results/clustering")
OUT_PATH = os.path.join(PROJECT_ROOT, "results/review/rq1_new_embedders.json")
EXISTING = ["octen", "bge_m3", "unixcoder", "codebert", "qwen3", "minilm", "ada002"]
NEW = ["jina_code", "codesage"]


def load_matrix(model):
    with open(os.path.join(CLUSTER_DIR, model, "cosine_distance_matrix.json"), encoding="utf-8") as f:
        d = json.load(f)
    return d["languages"], np.array(d["matrix"])


def load_membership(model):
    with open(os.path.join(CLUSTER_DIR, model, "language_families.json"), encoding="utf-8") as f:
        fams = json.load(f)
    return {lang: frozenset(members) for members in fams.values() for lang in members}


def stable_pairs(models, membership, languages):
    return [[a, b] for a, b in combinations(languages, 2) if all(b in membership[m][a] for m in models)]


def main():
    out = {"models": {}, "spearman": {}}
    for m in NEW:
        res = clustering.perform_clustering(m)
        out["models"][m] = {"ccc": round(float(res["ccc"]), 4), "families": res["families"]}

    mats = {m: load_matrix(m) for m in EXISTING + NEW}
    langs = mats["octen"][0]
    assert all(mats[m][0] == langs for m in mats)
    vec = {m: squareform(mats[m][1], checks=False) for m in mats}
    for m in NEW:
        out["spearman"][m] = {o: round(float(spearmanr(vec[m], vec[o])[0]), 4) for o in EXISTING + NEW if o != m}
        out["models"][m]["mean_spearman_vs_existing"] = round(float(np.mean([out["spearman"][m][o] for o in EXISTING])), 4)

    membership = {m: load_membership(m) for m in EXISTING + NEW}
    s7 = stable_pairs(EXISTING, membership, langs)
    s9 = stable_pairs(EXISTING + NEW, membership, langs)
    out["stable_pairs_all_7_existing"] = s7
    out["stable_pairs_all_9"] = s9
    out["n_stable_pairs_all_7"] = len(s7)
    out["n_stable_pairs_all_9"] = len(s9)
    for m in NEW:
        out["models"][m]["stable_pairs_kept"] = [p for p in s7 if p[1] in membership[m][p[0]]]

    os.makedirs(os.path.dirname(OUT_PATH), exist_ok=True)
    with open(OUT_PATH, "w", encoding="utf-8") as f:
        json.dump(out, f, indent=2)
    for m in NEW:
        print(m, "CCC", out["models"][m]["ccc"], out["models"][m]["families"])
        print("  spearman", out["spearman"][m])
    print("stable pairs (7):", s7)
    print("stable pairs (9):", s9)
    print("Saved", OUT_PATH)


if __name__ == "__main__":
    main()

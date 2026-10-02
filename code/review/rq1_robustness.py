"""
RQ1 robustness analyses requested in review (matrix-level part).

Works from the saved language-level cosine distance matrices
results/clustering/<model>/cosine_distance_matrix.json (all 7 models). Ward
linkage on the condensed form of each matrix reproduces code/clustering.py's
tree (pdist cosine -> linkage(method='ward') -> fcluster(maxclust)).

  1. Reproduction check: CCC per model vs the paper, k=5 families vs
     language_families.json, stable pairs and overlap scores vs
     family_stability.json.
  2. Partition agreement for k = 3..8: ARI / AMI for every model pair
     (mean/min/max), and the number of language pairs co-clustered by all 7
     models. Agreement among Yun et al.'s models (ada002, bge_m3, unixcoder).
  4. Chance baseline for the family overlap score: language labels permuted
     within every model's k=5 partition (family sizes preserved), 1,000
     replicates, seed 0.
  5. Downstream check: do Kotlin, Haskell, Swift and AppleScript share a
     family with Java and/or Python, per model and k.
  6. Spearman agreement between models' distance matrices, recomputed and
     compared with results/clustering/model_spearman.json.
The within-model bootstrap (task 3) is in rq1_bootstrap.py.

Usage:
    PYTHONIOENCODING=utf-8 .venv/Scripts/python.exe code/review/rq1_robustness.py
Output: results/review/rq1_robustness.json
"""

import json
import os
from itertools import combinations

import numpy as np
from scipy.cluster.hierarchy import cophenet, fcluster, linkage
from scipy.spatial.distance import squareform
from scipy.stats import spearmanr
from sklearn.metrics import adjusted_mutual_info_score, adjusted_rand_score

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(SCRIPT_DIR, "..", ".."))
CLU_DIR = os.path.join(ROOT, "results", "clustering")
OUT_DIR = os.path.join(ROOT, "results", "review")
OUT_FILE = os.path.join(OUT_DIR, "rq1_robustness.json")

MODELS = ["ada002", "bge_m3", "codebert", "minilm", "octen", "qwen3", "unixcoder"]
YUN_MODELS = ["ada002", "bge_m3", "unixcoder"]
KS = list(range(3, 9))
K_MAIN = 5
N_NULL = 1000
SEED = 0
PAPER_CCC = {"bge_m3": 0.799, "qwen3": 0.704, "unixcoder": 0.661, "ada002": 0.633,
             "minilm": 0.629, "octen": 0.616, "codebert": 0.584}
TARGETS = ["Kotlin", "Haskell", "Swift", "AppleScript"]
ANCHORS = ["Java", "Python"]
MODEL_PAIRS = list(combinations(MODELS, 2))


def r(x, nd=4):
    return round(float(x), nd)


def summary(arr):
    arr = np.asarray(arr, dtype=float)
    return {"mean": r(arr.mean()), "lo95": r(np.percentile(arr, 2.5)),
            "hi95": r(np.percentile(arr, 97.5))}


def co_matrix(labels):
    return labels[:, None] == labels[None, :]


def overlap_scores(partitions):
    """Per-language mean (over model pairs) Jaccard of the language's family,
    the language itself included (as code/family_stability.py)."""
    co = [co_matrix(p) for p in partitions]
    pairs = list(combinations(range(len(partitions)), 2))
    s = np.zeros(len(partitions[0]))
    for a, b in pairs:
        s += (co[a] & co[b]).sum(1) / (co[a] | co[b]).sum(1)
    return s / len(pairs)


def coclustered_by_all(partitions, langs):
    co = np.logical_and.reduce([co_matrix(p) for p in partitions])
    pairs = [[langs[i], langs[j]] for i, j in combinations(range(len(langs)), 2) if co[i, j]]
    return len(pairs), pairs


def families(labels, langs):
    return sorted(sorted(langs[i] for i in np.where(labels == c)[0]) for c in np.unique(labels))


def load_model(m):
    with open(os.path.join(CLU_DIR, m, "cosine_distance_matrix.json"), encoding="utf-8") as f:
        st = json.load(f)
    d = squareform(np.array(st["matrix"]), checks=False)
    Z = linkage(d, method="ward")
    ccc, _ = cophenet(Z, d)
    return st["languages"], d, Z, ccc


def main():
    os.makedirs(OUT_DIR, exist_ok=True)
    full, langs = {}, None
    for m in MODELS:
        L, d, Z, ccc = load_model(m)
        assert langs is None or L == langs
        langs = L
        full[m] = {"d": d, "Z": Z, "ccc": ccc,
                   "parts": {k: fcluster(Z, t=k, criterion="maxclust") for k in KS}}
    nL = len(langs)
    out = {"config": {"models": MODELS, "languages": langs, "ks": KS, "k_main": K_MAIN,
                      "n_null": N_NULL, "seed": SEED,
                      "source": "results/clustering/<model>/cosine_distance_matrix.json",
                      "ari": "sklearn adjusted_rand_score",
                      "ami": "sklearn adjusted_mutual_info_score (arithmetic normalisation)"}}

    # ---- 1. reproduction
    with open(os.path.join(CLU_DIR, "family_stability.json"), encoding="utf-8") as f:
        fs = json.load(f)
    repro = {}
    for m in MODELS:
        with open(os.path.join(CLU_DIR, m, "language_families.json"), encoding="utf-8") as f:
            stored = sorted(sorted(v) for v in json.load(f).values())
        fam = families(full[m]["parts"][K_MAIN], langs)
        repro[m] = {"ccc": r(full[m]["ccc"]), "paper_ccc": PAPER_CCC[m],
                    "ccc_matches_paper_3dp": bool(round(float(full[m]["ccc"]), 3) == PAPER_CCC[m]),
                    "k5_families_match_stored": fam == stored, "k5_families": fam}
    p5 = [full[m]["parts"][K_MAIN] for m in MODELS]
    n_stable, stable = coclustered_by_all(p5, langs)
    ov = overlap_scores(p5)
    repro["stable_pairs_k5"] = stable
    repro["stable_pairs_match_stored"] = sorted(stable) == sorted(fs["stable_pairings"])
    repro["overlap_scores"] = {l: r(ov[i]) for i, l in enumerate(langs)}
    repro["overlap_max_abs_diff_vs_stored"] = r(
        max(abs(ov[i] - fs["family_overlap"][l]) for i, l in enumerate(langs)), 6)
    repro["overlap_mean_over_languages"] = r(ov.mean())
    out["1_reproduction"] = repro

    # ---- 2. ARI / AMI across k
    agree = {}
    for k in KS:
        per = {}
        for a, b in MODEL_PAIRS:
            pa, pb = full[a]["parts"][k], full[b]["parts"][k]
            per[f"{a}|{b}"] = {"ari": r(adjusted_rand_score(pa, pb)),
                               "ami": r(adjusted_mutual_info_score(pa, pb))}
        aris = [v["ari"] for v in per.values()]
        amis = [v["ami"] for v in per.values()]
        nst, st = coclustered_by_all([full[m]["parts"][k] for m in MODELS], langs)
        yun = {f"{a}|{b}": per[f"{a}|{b}"] for a, b in combinations(YUN_MODELS, 2)}
        ny, sy = coclustered_by_all([full[m]["parts"][k] for m in YUN_MODELS], langs)
        agree[str(k)] = {
            "ari": {"mean": r(np.mean(aris)), "min": r(min(aris)), "max": r(max(aris))},
            "ami": {"mean": r(np.mean(amis)), "min": r(min(amis)), "max": r(max(amis))},
            "n_pairs_coclustered_all7": nst, "pairs_coclustered_all7": st,
            "n_clusters": {m: int(len(np.unique(full[m]["parts"][k]))) for m in MODELS},
            "yun_models": {"pairs": yun,
                           "mean_ari": r(np.mean([v["ari"] for v in yun.values()])),
                           "mean_ami": r(np.mean([v["ami"] for v in yun.values()])),
                           "n_pairs_coclustered_all3": ny, "pairs_coclustered_all3": sy,
                           "partitions": {m: families(full[m]["parts"][k], langs)
                                          for m in YUN_MODELS}},
            "per_pair": per,
        }
    out["2_partition_agreement"] = agree

    # ---- 4. permutation null for the overlap score
    rng = np.random.default_rng(SEED)
    null_scores = np.zeros((N_NULL, nL))
    null_stable = np.zeros(N_NULL, dtype=int)
    for b in range(N_NULL):
        perm = [rng.permutation(p) for p in p5]
        null_scores[b] = overlap_scores(perm)
        null_stable[b] = coclustered_by_all(perm, langs)[0]
    null_mean = null_scores.mean(1)
    out["4_overlap_null"] = {
        "description": ("Labels permuted independently within each model's k=5 partition "
                        "(family sizes preserved); overlap recomputed per replicate."),
        "observed_mean": r(ov.mean()),
        "null_mean": summary(null_mean),
        "p_null_mean_ge_observed": r(np.mean(null_mean >= ov.mean())),
        "observed_mean_over_null_mean": r(ov.mean() / null_mean.mean(), 3),
        "observed_n_stable_pairs": n_stable,
        "null_n_stable_pairs": summary(null_stable),
        "p_null_stable_ge_observed": r(np.mean(null_stable >= n_stable)),
        "null_per_language_pooled": summary(null_scores.ravel()),
        "per_language": {l: {"observed": r(ov[i]), "null": summary(null_scores[:, i]),
                             "p_null_ge_observed": r(np.mean(null_scores[:, i] >= ov[i]))}
                         for i, l in enumerate(langs)},
        "n_languages_above_null_hi95": int(sum(ov[i] > np.percentile(null_scores[:, i], 97.5)
                                               for i in range(nL))),
        "family_sizes_k5": {m: sorted(np.bincount(full[m]["parts"][K_MAIN])[1:].tolist(),
                                      reverse=True) for m in MODELS},
    }

    # ---- 5. downstream check
    ds = {}
    for k in KS:
        rows = {}
        for m in MODELS:
            lab = full[m]["parts"][k]
            rows[m] = {}
            for t in TARGETS:
                fam = [langs[i] for i in np.where(lab == lab[langs.index(t)])[0]]
                rows[m][t] = {"Java": "Java" in fam, "Python": "Python" in fam, "family": fam}
        counts = {t: {"Java": sum(rows[m][t]["Java"] for m in MODELS),
                      "Python": sum(rows[m][t]["Python"] for m in MODELS),
                      "either": sum(rows[m][t]["Java"] or rows[m][t]["Python"] for m in MODELS)}
                  for t in TARGETS}
        ds[str(k)] = {"counts_of_7": counts, "per_model": rows,
                      "java_with_python": {m: bool(full[m]["parts"][k][langs.index("Java")] ==
                                                   full[m]["parts"][k][langs.index("Python")])
                                           for m in MODELS}}
    out["5_downstream"] = ds

    # ---- 6. Spearman
    with open(os.path.join(CLU_DIR, "model_spearman.json"), encoding="utf-8") as f:
        ms = json.load(f)
    order = ms["models"]
    rec = np.array([[spearmanr(full[a]["d"], full[b]["d"])[0] for b in order] for a in order])
    iu = np.triu_indices(len(order), 1)
    out["6_spearman"] = {"models": order,
                         "recomputed": [[r(x) for x in row] for row in rec],
                         "max_abs_diff_vs_stored": r(np.abs(rec - np.array(ms["spearman"])).max(), 6),
                         "offdiag_min": r(rec[iu].min()), "offdiag_max": r(rec[iu].max()),
                         "offdiag_mean": r(rec[iu].mean())}

    with open(OUT_FILE, "w", encoding="utf-8") as f:
        json.dump(out, f, indent=2)
    print(f"saved {OUT_FILE}")


if __name__ == "__main__":
    main()

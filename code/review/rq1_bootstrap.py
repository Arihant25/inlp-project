"""
RQ1 within-model bootstrap (review task 3, plus the within-model Spearman
noise level for task 6).

Uses the snippet embeddings re-computed by rq1_reembed.py
(results/review/rq1_embeddings/<model>.npz) for the six local models; ada-002
cannot be re-embedded without an API key and is reported as not available.

Pipeline per replicate (identical to code/clustering.py): language vector =
(weighted) mean of the language's snippet vectors -> cosine pdist -> Ward
linkage -> fcluster(k=5, 'maxclust').

Two resampling schemes, 1,000 replicates each, numpy default_rng(seed=0):
  - feature: draw the 21 linguistic features with replacement; every snippet
    of a drawn feature gets weight = number of times the feature was drawn.
    The same draw applies to every language and every model.
  - snippet: inside every (language, feature) cell, draw that cell's snippets
    with replacement (multinomial weights). Same draws for every model.
Because draws are shared, replicate b is a paired perturbation across models.

Reported per model: ARI/AMI of bootstrap partitions vs the model's full-data
partition, ARI between two independent bootstrap partitions (b vs b+1),
Spearman rho of full-data vs bootstrap distance matrices, co-clustering
probabilities for every language pair. Reported per model pair: full-data ARI,
bootstrap distribution of between-model ARI (same replicate) and Spearman.

Usage:
    PYTHONIOENCODING=utf-8 .venv/Scripts/python.exe code/review/rq1_bootstrap.py
Output: results/review/rq1_bootstrap.json
"""

import json
import os
from itertools import combinations

import numpy as np
from scipy.cluster.hierarchy import cophenet, fcluster, linkage
from scipy.spatial.distance import pdist, squareform
from scipy.stats import spearmanr
from sklearn.metrics import adjusted_mutual_info_score, adjusted_rand_score

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(SCRIPT_DIR, "..", ".."))
EMB_DIR = os.path.join(ROOT, "results", "review", "rq1_embeddings")
CLU_DIR = os.path.join(ROOT, "results", "clustering")
OUT_FILE = os.environ.get("RQ1_OUT", os.path.join(ROOT, "results", "review", "rq1_bootstrap.json"))

MODELS = os.environ.get("RQ1_MODELS", "bge_m3,codebert,minilm,octen,qwen3,unixcoder").split(",")  # ada002: n/a
K = 5
N_BOOT = int(os.environ.get("RQ1_NBOOT", "1000"))  # env overrides are for smoke tests only
SEED = 0
TARGETS = ["Kotlin", "Haskell", "Swift", "AppleScript"]
ANCHORS = ["Java", "Python"]
STABLE = [("C", "C++"), ("Kotlin", "Scala"), ("Python", "Ruby")]


def r(x, nd=4):
    return round(float(x), nd)


def summary(arr):
    arr = np.asarray(arr, dtype=float)
    return {"mean": r(arr.mean()), "lo95": r(np.percentile(arr, 2.5)),
            "hi95": r(np.percentile(arr, 97.5))}


def co_matrix(labels):
    return labels[:, None] == labels[None, :]


def main():
    # ---- load
    emb, keys = {}, None
    for m in MODELS:
        z = np.load(os.path.join(EMB_DIR, f"{m}.npz"))
        k = z["keys"].tolist()
        assert keys is None or k == keys, f"key order differs for {m}"
        keys = k
        emb[m] = z["emb"].astype(np.float64)
    with open(os.path.join(CLU_DIR, MODELS[0], "cosine_distance_matrix.json"), encoding="utf-8") as f:
        langs = json.load(f)["languages"]
    nL = len(langs)
    lang_of = np.array([langs.index(k.split(" ", 1)[0]) for k in keys])
    feat_names = sorted({k.split(" ", 1)[1].rsplit(" ", 1)[0] for k in keys})
    feat_of = np.array([feat_names.index(k.split(" ", 1)[1].rsplit(" ", 1)[0]) for k in keys])
    nF = len(feat_names)
    blocks = [np.where(lang_of == l)[0] for l in range(nL)]
    pairs_idx = list(combinations(range(nL), 2))
    out = {"config": {"models": MODELS, "ada002": "not available (API-only, no key)",
                      "k": K, "n_boot": N_BOOT, "seed": SEED, "n_snippets": len(keys),
                      "n_features": nF, "languages": langs}}

    def lang_vectors_batch(X, W):
        """W: (B, N) snippet weights -> (B, nL, D) weighted language means."""
        V = np.empty((W.shape[0], nL, X.shape[1]))
        for l, idx in enumerate(blocks):
            Wl = W[:, idx]
            V[:, l] = (Wl @ X[idx]) / Wl.sum(1, keepdims=True)
        return V

    def tree(V):
        d = pdist(V, metric="cosine")
        return d, linkage(d, method="ward")

    # ---- full data + check against saved matrices
    full, check = {}, {}
    for m in MODELS:
        V = lang_vectors_batch(emb[m], np.ones((1, len(keys))))[0]
        d, Z = tree(V)
        with open(os.path.join(CLU_DIR, m, "cosine_distance_matrix.json"), encoding="utf-8") as f:
            st = json.load(f)
        assert st["languages"] == langs
        ds = squareform(np.array(st["matrix"]), checks=False)
        Zs = linkage(ds, method="ward")
        p, ps = fcluster(Z, K, "maxclust"), fcluster(Zs, K, "maxclust")
        full[m] = {"d": d, "p": ps}  # reference partition = paper's (saved-matrix) partition
        check[m] = {"spearman_vs_saved": r(spearmanr(d, ds)[0], 6),
                    "max_abs_diff_vs_saved": float(np.abs(d - ds).max()),
                    "max_saved_distance": r(ds.max()),
                    "ccc_reembedded": r(cophenet(Z, d)[0]),
                    "ccc_saved": r(cophenet(Zs, ds)[0]),
                    "k5_ari_reembedded_vs_saved": r(adjusted_rand_score(p, ps))}
        print(m, check[m], flush=True)
    out["reembedding_check"] = check

    # ---- resampling weights (shared across models)
    schemes = {}
    rng = np.random.default_rng(SEED)
    fw = rng.multinomial(nF, np.full(nF, 1.0 / nF), size=N_BOOT)  # (B, nF)
    schemes["feature"] = fw[:, feat_of].astype(np.float64)
    rng = np.random.default_rng(SEED)
    W = np.zeros((N_BOOT, len(keys)))
    for l in range(nL):
        for f in range(nF):
            idx = np.where((lang_of == l) & (feat_of == f))[0]
            W[:, idx] = rng.multinomial(len(idx), np.full(len(idx), 1.0 / len(idx)), size=N_BOOT)
    schemes["snippet"] = W

    res_all = {}
    for scheme, Wb in schemes.items():
        bp = {m: np.zeros((N_BOOT, nL), dtype=int) for m in MODELS}
        bd = {m: np.zeros((N_BOOT, len(pairs_idx))) for m in MODELS}
        for m in MODELS:
            for s in range(0, N_BOOT, 250):  # chunk to bound memory
                V = lang_vectors_batch(emb[m], Wb[s:s + 250])
                for j in range(V.shape[0]):
                    d, Z = tree(V[j])
                    bd[m][s + j] = d
                    bp[m][s + j] = fcluster(Z, K, "maxclust")
            print(f"{scheme} {m} done", flush=True)

        within, wari = {}, {}
        for m in MODELS:
            fp = full[m]["p"]
            ari = np.array([adjusted_rand_score(fp, bp[m][b]) for b in range(N_BOOT)])
            wari[m] = ari
            ami = [adjusted_mutual_info_score(fp, bp[m][b]) for b in range(N_BOOT)]
            bb = [adjusted_rand_score(bp[m][b], bp[m][(b + 1) % N_BOOT]) for b in range(N_BOOT)]
            rho = [spearmanr(full[m]["d"], bd[m][b])[0] for b in range(N_BOOT)]
            co = np.mean([co_matrix(bp[m][b]) for b in range(N_BOOT)], axis=0)
            within[m] = {
                "ari_vs_full": summary(ari), "ami_vs_full": summary(ami),
                "ari_boot_vs_boot": summary(bb),
                "frac_identical_to_full": r(np.mean(ari == 1.0), 3),
                "spearman_full_vs_boot": summary(rho),
                "stable_pairs_prob": {f"{a}|{b}": r(co[langs.index(a), langs.index(b)], 3)
                                      for a, b in STABLE},
                "target_anchor_prob": {f"{t}|{a}": r(co[langs.index(t), langs.index(a)], 3)
                                       for t in TARGETS for a in ANCHORS},
                "coclustering_prob": {f"{langs[i]}|{langs[j]}": r(co[i, j], 3)
                                      for i, j in pairs_idx},
            }
        between = {}
        mean_between = np.zeros(N_BOOT)
        rho_between_all = []
        for a, b2 in combinations(MODELS, 2):
            ab = np.array([adjusted_rand_score(bp[a][b], bp[b2][b]) for b in range(N_BOOT)])
            rb = [spearmanr(bd[a][b], bd[b2][b])[0] for b in range(N_BOOT)]
            rho_between_all.append(np.mean(rb))
            mean_between += ab / len(list(combinations(MODELS, 2)))
            between[f"{a}|{b2}"] = {
                "ari_full": r(adjusted_rand_score(full[a]["p"], full[b2]["p"])),
                "ari_boot": summary(ab),
                "spearman_full": r(spearmanr(full[a]["d"], full[b2]["d"])[0]),
                "spearman_boot": summary(rb),
                "p_between_lt_both_within": r(np.mean((ab < wari[a]) & (ab < wari[b2])), 3),
            }
        mean_within = np.mean([wari[m] for m in MODELS], axis=0)
        joint = np.array([np.logical_and.reduce([co_matrix(bp[m][b]) for m in MODELS])
                          for b in range(N_BOOT)])
        jp = joint.mean(0)
        n_joint = np.triu(joint, 1).sum(axis=(1, 2))
        full_between = [v["ari_full"] for v in between.values()]
        res_all[scheme] = {
            "summary": {
                "mean_within_ari_vs_full": summary(mean_within),
                "min_model_within_ari_vs_full": r(min(wari[m].mean() for m in MODELS)),
                "mean_between_ari_full_data": r(np.mean(full_between)),
                "max_between_ari_full_data": r(np.max(full_between)),
                "mean_between_ari_boot": summary(mean_between),
                "within_minus_between_boot": summary(mean_within - mean_between),
                "p_between_ge_within": r(np.mean(mean_between >= mean_within), 4),
                "n_model_pairs_between_hi95_below_both_within_lo95": int(sum(
                    np.percentile(np.array([adjusted_rand_score(bp[a][b], bp[b2][b])
                                            for b in range(N_BOOT)]), 97.5)
                    < min(np.percentile(wari[a], 2.5), np.percentile(wari[b2], 2.5))
                    for a, b2 in combinations(MODELS, 2))),
                "mean_within_spearman_full_vs_boot": r(np.mean(
                    [within[m]["spearman_full_vs_boot"]["mean"] for m in MODELS])),
                "mean_between_spearman_boot": r(np.mean(rho_between_all)),
                "n_pairs_coclustered_all6_boot": summary(n_joint),
                "n_pairs_coclustered_all6_full": int(np.triu(np.logical_and.reduce(
                    [co_matrix(full[m]["p"]) for m in MODELS]), 1).sum()),
                "joint_all6_prob_stable_pairs": {f"{a}|{b}": r(jp[langs.index(a), langs.index(b)], 3)
                                                 for a, b in STABLE},
                "joint_all6_prob_pairs_gt_0.05": {f"{langs[i]}|{langs[j]}": r(jp[i, j], 3)
                                                  for i, j in pairs_idx if jp[i, j] > 0.05},
            },
            "within": within,
            "between_pairs": between,
        }
        print(scheme, json.dumps(res_all[scheme]["summary"]), flush=True)
    out["bootstrap"] = res_all
    with open(OUT_FILE, "w", encoding="utf-8") as f:
        json.dump(out, f, indent=2)
    print(f"saved {OUT_FILE}")


if __name__ == "__main__":
    main()

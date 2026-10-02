"""
Review: RQ5 retrieval, proximity and example-test filtering for the two added code embedders.

Uses the functions of code/rq5/2_retrieval.py unchanged (score_matrix, retrieval_records,
summarize, proximity, rq4_proximity, example_test_passes) on the embeddings written by
code/rq5/1_embedding.py --model <jina_code|codesage> [--dataset classeval]. The original
results/rq5/retrieval/metrics.json is not touched; the existing models' pooled numbers are
copied from it for reference.

Output: results/review/rq5_new_embedders.json
"""

import importlib.util
import json
import os
import sys

import numpy as np

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
CODE_DIR = os.path.dirname(SCRIPT_DIR)
RQ5_DIR = os.path.join(CODE_DIR, "rq5")
sys.path.insert(0, RQ5_DIR)
from common import (GENERATION_LANGUAGES, LANGUAGES, NEW_EMBEDDERS, PROJECT_ROOT, RESULTS_DIR,  # noqa: E402
                    build_corpus, load_language)

_spec = importlib.util.spec_from_file_location("rq5_retrieval", os.path.join(RQ5_DIR, "2_retrieval.py"))
R = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(R)

OUT_PATH = os.path.join(PROJECT_ROOT, "results/review/rq5_new_embedders.json")


def main():
    models = NEW_EMBEDDERS
    vec_cache = {m: R.load_vectors(m) for m in models}
    metrics = {"retrieval": {}, "proximity_humanevalfix": {}, "proximity_classeval": {},
               "proximity_rq4": {}, "filtering": {}}
    all_rankings, lang_docs = {}, {}
    for lang in LANGUAGES + ["classeval"]:
        docs, queries = build_corpus(lang)
        lang_docs[lang] = docs
        all_rankings[lang] = {}
        for ret in models:
            S = R.score_matrix(ret, lang, docs, queries, vec_cache)
            recs = R.retrieval_records(S, docs, queries)
            all_rankings[lang][ret] = recs
            metrics["retrieval"].setdefault(ret, {})[lang] = R.summarize(recs, np.random.default_rng(R.RNG_SEED))
    for ret in models:
        pooled = [r for lang in LANGUAGES for r in all_rankings[lang][ret]]
        metrics["retrieval"][ret]["pooled"] = R.summarize(pooled, np.random.default_rng(R.RNG_SEED))

    he_docs = {l: lang_docs[l] for l in LANGUAGES}
    for m in models:
        metrics["proximity_humanevalfix"][m] = R.proximity(vec_cache[m], he_docs, np.random.default_rng(R.RNG_SEED))
        metrics["proximity_classeval"][m] = R.proximity(
            vec_cache[m], {"classeval": lang_docs["classeval"]}, np.random.default_rng(R.RNG_SEED))
        metrics["proximity_rq4"][m] = R.rq4_proximity(m, np.random.default_rng(R.RNG_SEED))

    for lang in GENERATION_LANGUAGES:
        df = load_language(lang)
        docs = lang_docs[lang]
        rankings = {ret: {r["problem"]: r["top"] for r in all_rankings[lang][ret]} for ret in models}
        passes = R.example_test_passes(lang, docs, df, rankings)
        metrics["filtering"][lang] = {}
        for ret in models:
            picks = {}
            for p, top in rankings[ret].items():
                if passes[(p, top[0])] is None:
                    chosen = top[0]
                else:
                    chosen = next((d for d in top if passes[(p, d)]), "none")
                picks[p] = chosen
            n = len(picks)
            metrics["filtering"][lang][ret] = {
                "picks": {str(p): c for p, c in picks.items()},
                "pick_buggy_twin": round(sum(c == f"{p}_buggy" for p, c in picks.items()) / n, 4),
                "pick_correct_twin": round(sum(c == f"{p}_correct" for p, c in picks.items()) / n, 4),
                "pick_none": round(sum(c == "none" for c in picks.values()) / n, 4),
            }

    with open(os.path.join(RESULTS_DIR, "retrieval/metrics.json"), encoding="utf-8") as f:
        old = json.load(f)
    metrics["reference_existing"] = {
        "retrieval_pooled": {k: v["pooled"] for k, v in old["retrieval"].items()},
        "retrieval_classeval": {k: v["classeval"] for k, v in old["retrieval"].items()},
        "proximity_humanevalfix": {k: {x: v[x] for x in ("pair_mean", "unmatched_mean", "R", "dangerous_pct")}
                                   for k, v in old["proximity_humanevalfix"].items()},
        "proximity_classeval": {k: {x: v[x] for x in ("pair_mean", "unmatched_mean", "R", "dangerous_pct")}
                                for k, v in old["proximity_classeval"].items()},
        "filtering": {lang: {k: {x: v[x] for x in ("pick_buggy_twin", "pick_correct_twin", "pick_none")}
                             if isinstance(v, dict) else v for k, v in d.items()}
                      for lang, d in old["filtering"].items()},
    }

    os.makedirs(os.path.dirname(OUT_PATH), exist_ok=True)
    with open(OUT_PATH, "w", encoding="utf-8") as f:
        json.dump(metrics, f, indent=2)

    for m in models:
        for ds in LANGUAGES + ["pooled", "classeval"]:
            x = metrics["retrieval"][m][ds]
            print(f"{m:10s} {ds:9s} R@1={x['recall1']:.3f} bf={x['buggy_first']:.3f} p={x['buggy_first_binom_p']:.3g} "
                  f"top1b={x['top1_buggy']:.3f} tie={x['tie']:.3f}")
        for k in ("proximity_humanevalfix", "proximity_classeval", "proximity_rq4"):
            v = metrics[k][m]
            print(f"  {k}: R={v['R']:.3f} pair={v['pair_mean']} um={v['unmatched_mean']} DN={v['dangerous_pct']}")
        for lang in GENERATION_LANGUAGES:
            v = metrics["filtering"][lang][m]
            print(f"  filter {lang}: buggy={v['pick_buggy_twin']} correct={v['pick_correct_twin']} none={v['pick_none']}")
    print("Saved", OUT_PATH)


if __name__ == "__main__":
    main()

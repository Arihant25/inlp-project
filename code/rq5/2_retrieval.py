"""
RQ5 Step 2: Retrieval evaluation on HumanEvalFix, real-bug proximity, and pooling ablation.

For each retriever (six embedders, two CLS-pooling variants, and BM25) and each
language, every docstring query ranks the 328 documents of that language.

Retrieval metrics (per language and pooled over languages):
  - recall@1:       the top-1 document belongs to the query's problem
  - buggy_first:    the buggy twin ranks above the correct twin (ties count 0.5);
                    0.5 is the rate of a retriever blind to correctness
  - top1_buggy:     the top-1 document is the buggy twin
  - 95% bootstrap CIs over queries and an exact binomial test of buggy_first vs 0.5

Real-bug proximity (replication of RQ4 on human-written bugs):
  relative pair distance R = mean distance(correct, buggy twin) /
  mean distance between unmatched correct and buggy documents of the same language,
  plus dangerous-neighbourhood rates at tau in {0.05, 0.10, 0.15}.

Pooling ablation: the same proximity statistics for CodeBERT and UniXCoder with
mean vs CLS pooling, on HumanEvalFix and on the RQ4 corpus.

Example-test filtering (RQ5.3): among a retriever's top-5 documents, keep the first
one that passes the query problem's docstring example tests; if none passes, use
no context.

Outputs: results/rq5/retrieval/metrics.json, results/rq5/retrieval/rankings.json,
         results/rq5/retrieval/<lang>_contexts.json (contexts to generate for).
"""

import json
import os
import re
import sys

import numpy as np
import pandas as pd
from rank_bm25 import BM25Okapi
from scipy.stats import binomtest

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, SCRIPT_DIR)
from common import (CLS_VARIANTS, EMBEDDERS, GENERATION_LANGUAGES, LANGUAGES, PROJECT_ROOT,
                    RESULTS_DIR, build_corpus, load_language)
from execute import run_many

EMB_DIR = os.path.join(RESULTS_DIR, "embeddings")
OUT_DIR = os.path.join(RESULTS_DIR, "retrieval")
RETRIEVERS = EMBEDDERS + CLS_VARIANTS + ["bm25"]
THRESHOLDS = [0.05, 0.10, 0.15]
TOP_K = 5
N_BOOT = 5000
RNG_SEED = 42


# ── Scoring ──────────────────────────────────────────────────────────────────

def tokenize(text: str) -> list[str]:
    """Identifier-aware tokenizer for BM25: split snake_case and camelCase, lower-case."""
    words = re.findall(r"[A-Za-z]+|\d+", text)
    out = []
    for w in words:
        out += [p.lower() for p in re.findall(r"[A-Z]?[a-z]+|[A-Z]+(?![a-z])|\d+", w) if p]
    return out


def load_vectors(model: str) -> dict[str, np.ndarray]:
    out = {}
    for suffix in ("", "_classeval"):
        path = os.path.join(EMB_DIR, f"{model}{suffix}.npz")
        if os.path.exists(path):
            d = np.load(path)
            vecs = d["vectors"] / np.linalg.norm(d["vectors"], axis=1, keepdims=True)
            out.update(zip(d["keys"], vecs))
    return out


def score_matrix(retriever: str, lang: str, docs: pd.DataFrame, queries: pd.DataFrame, vec_cache: dict):
    """Return a (n_queries, n_docs) similarity matrix."""
    if retriever == "bm25":
        bm25 = BM25Okapi([tokenize(c) for c in docs["code"]])
        return np.array([bm25.get_scores(tokenize(q)) for q in queries["query"]])
    vecs = vec_cache[retriever]
    Q = np.vstack([vecs[f"{lang}|q|{p}"] for p in queries["problem"]])
    D = np.vstack([vecs[f"{lang}|d|{d}"] for d in docs["doc_id"]])
    return Q @ D.T


# ── Metrics ──────────────────────────────────────────────────────────────────

def retrieval_records(S: np.ndarray, docs: pd.DataFrame, queries: pd.DataFrame) -> list[dict]:
    doc_ids = docs["doc_id"].tolist()
    idx = {d: i for i, d in enumerate(doc_ids)}
    recs = []
    for qi, p in enumerate(queries["problem"]):
        s = S[qi]
        order = np.argsort(-s, kind="stable")
        top = [doc_ids[j] for j in order[:TOP_K]]
        sc, sb = s[idx[f"{p}_correct"]], s[idx[f"{p}_buggy"]]
        recs.append({
            "problem": int(p),
            "top": top,
            "recall1": int(docs["problem"].iloc[order[0]] == p),
            "buggy_first": 0.5 if sb == sc else float(sb > sc),
            "tie": int(sb == sc),
            "top1_buggy": int(top[0] == f"{p}_buggy"),
            "top1_correct": int(top[0] == f"{p}_correct"),
        })
    return recs


def summarize(recs: list[dict], rng: np.random.Generator) -> dict:
    out = {"n": len(recs)}
    for key in ("recall1", "buggy_first", "top1_buggy", "top1_correct", "tie"):
        x = np.array([r[key] for r in recs], dtype=float)
        boots = [x[rng.integers(0, len(x), len(x))].mean() for _ in range(N_BOOT)]
        out[key] = round(float(x.mean()), 4)
        out[f"{key}_ci"] = [round(float(np.percentile(boots, 2.5)), 4), round(float(np.percentile(boots, 97.5)), 4)]
    # Exact binomial test on untied pairs: is buggy-first different from 0.5?
    untied = [r for r in recs if not r["tie"]]
    k = sum(int(r["buggy_first"]) for r in untied)
    out["buggy_first_binom_p"] = float(binomtest(k, len(untied), 0.5).pvalue) if untied else None
    return out


def proximity(vecs: dict | None, lang_docs: dict[str, pd.DataFrame], rng: np.random.Generator,
              override: dict | None = None) -> dict:
    """Relative pair distance R and dangerous-neighbourhood rates for bug-fix twins."""
    pair, unmatched, by_type = [], [], {}
    for lang, docs in lang_docs.items():
        get = (lambda d: override[(lang, d)]) if override else (lambda d: vecs[f"{lang}|d|{d}"])
        probs = sorted(docs["problem"].unique())
        btype = dict(zip(docs["problem"], docs["bug_type"]))
        for p in probs:
            dist = 1 - float(get(f"{p}_correct") @ get(f"{p}_buggy"))
            pair.append(dist)
            by_type.setdefault(btype[p], []).append(dist)
        for _ in range(500):
            a, b = rng.choice(probs, 2, replace=False)
            unmatched.append(1 - float(get(f"{a}_correct") @ get(f"{b}_buggy")))
    pair, um = np.array(pair), float(np.mean(unmatched))
    return {
        "n_pairs": len(pair),
        "pair_mean": round(float(pair.mean()), 4),
        "unmatched_mean": round(um, 4),
        "R": round(float(pair.mean()) / um, 4),
        "dangerous_pct": {str(t): round(100 * float((pair < t).mean()), 1) for t in THRESHOLDS},
        "R_by_bug_type": {k: round(float(np.mean(v)) / um, 4) for k, v in sorted(by_type.items())},
    }


def rq4_proximity(model: str, rng: np.random.Generator) -> dict:
    """R and dangerous-neighbourhood rates on the RQ4 corpus (mean vs CLS pooling)."""
    if model in CLS_VARIANTS:
        d = np.load(os.path.join(EMB_DIR, f"{model}.npz"))
        keys, vecs = d["rq4_keys"], d["rq4_vectors"]
    else:
        df = pd.read_parquet(os.path.join(PROJECT_ROOT, f"results/RQ4/{model}/rq4_embeddings.parquet"))
        keys = np.array([f"{r.language}|{r.bug_index}|{r.code_type}" for r in df.itertuples()])
        vecs = np.vstack(df["embedding"].values)
    vecs = vecs / np.linalg.norm(vecs, axis=1, keepdims=True)
    lookup = dict(zip(keys, vecs))
    pair, unmatched = [], []
    for lang in sorted({k.split("|")[0] for k in keys}):
        bugs = sorted({int(k.split("|")[1]) for k in keys if k.startswith(lang + "|")})
        for b in bugs:
            pair.append(1 - float(lookup[f"{lang}|{b}|buggy"] @ lookup[f"{lang}|{b}|fixed"]))
        for _ in range(500):
            a, c = rng.choice(bugs, 2, replace=False)
            unmatched.append(1 - float(lookup[f"{lang}|{a}|buggy"] @ lookup[f"{lang}|{c}|fixed"]))
    pair, um = np.array(pair), float(np.mean(unmatched))
    return {"pair_mean": round(float(pair.mean()), 4), "unmatched_mean": round(um, 4),
            "R": round(float(pair.mean()) / um, 4),
            "dangerous_pct": {str(t): round(100 * float((pair < t).mean()), 1) for t in THRESHOLDS}}


# ── Example-test filtering ────────────────────────────────────────────────────

def has_test(t) -> bool:
    """True when an example test is present (missing values may be None or pandas NA)."""
    return isinstance(t, str) and bool(t.strip())


def example_runner(lang: str) -> str:
    # ClassEval example tests are doctest scripts run as plain Python programs.
    return "python" if lang == "classeval" else lang


def example_test_passes(lang: str, docs: pd.DataFrame, df: pd.DataFrame, rankings: dict) -> dict:
    """Run each (query problem, candidate doc) pair in any top-5 on the problem's example test.
    Pairs whose problem has no usable example test map to None."""
    code = dict(zip(docs["doc_id"], docs["code"]))
    ex_test = dict(zip(df["problem"].astype(int), df["example_test"]))
    pairs = sorted({(p, d) for r in rankings.values() for p, top in r.items() for d in top})
    runnable = [(p, d) for p, d in pairs if has_test(ex_test[p])]
    results = run_many([(example_runner(lang), code[d], ex_test[p]) for p, d in runnable])
    out = {pd_: None for pd_ in pairs}
    out.update({pd_: res["passed"] for pd_, res in zip(runnable, results)})
    return out


def main():
    os.makedirs(OUT_DIR, exist_ok=True)
    vec_cache = {m: load_vectors(m) for m in EMBEDDERS + CLS_VARIANTS}
    metrics = {"retrieval": {}, "proximity_humanevalfix": {}, "proximity_rq4": {}, "filtering": {}}
    all_rankings = {}
    lang_docs = {}

    for lang in LANGUAGES + ["classeval"]:
        docs, queries = build_corpus(lang)
        lang_docs[lang] = docs
        all_rankings[lang] = {}
        for ret in RETRIEVERS:
            S = score_matrix(ret, lang, docs, queries, vec_cache)
            recs = retrieval_records(S, docs, queries)
            all_rankings[lang][ret] = recs
            metrics["retrieval"].setdefault(ret, {})[lang] = summarize(recs, np.random.default_rng(RNG_SEED))

    for ret in RETRIEVERS:
        pooled = [r for lang in LANGUAGES for r in all_rankings[lang][ret]]
        metrics["retrieval"][ret]["pooled"] = summarize(pooled, np.random.default_rng(RNG_SEED))

    he_docs = {l: lang_docs[l] for l in LANGUAGES}
    metrics["proximity_classeval"] = {}
    for model in EMBEDDERS + CLS_VARIANTS:
        metrics["proximity_humanevalfix"][model] = proximity(vec_cache[model], he_docs, np.random.default_rng(RNG_SEED))
        metrics["proximity_classeval"][model] = proximity(
            vec_cache[model], {"classeval": lang_docs["classeval"]}, np.random.default_rng(RNG_SEED))
    for model in ["codebert", "unixcoder"] + CLS_VARIANTS + [m for m in EMBEDDERS if m not in ("codebert", "unixcoder")]:
        metrics["proximity_rq4"][model] = rq4_proximity(model, np.random.default_rng(RNG_SEED))

    # Contexts for generation: top-1 of every retriever and the example-test-filtered pick.
    for lang in GENERATION_LANGUAGES:
        df = load_language(lang)
        docs = lang_docs[lang]
        rankings = {ret: {r["problem"]: r["top"] for r in all_rankings[lang][ret]} for ret in RETRIEVERS}
        passes = example_test_passes(lang, docs, df, rankings)
        contexts = set()
        metrics["filtering"][lang] = {}
        for ret in RETRIEVERS:
            picks = {}
            for p, top in rankings[ret].items():
                contexts.add((p, top[0]))
                if passes[(p, top[0])] is None:
                    chosen = top[0]  # no example tests for this problem: no filtering possible
                else:
                    chosen = next((d for d in top if passes[(p, d)]), "none")
                picks[p] = chosen
                contexts.add((p, chosen))
            n = len(picks)
            metrics["filtering"][lang][ret] = {
                "picks": {str(p): c for p, c in picks.items()},
                "pick_buggy_twin": round(sum(c == f"{p}_buggy" for p, c in picks.items()) / n, 4),
                "pick_correct_twin": round(sum(c == f"{p}_correct" for p, c in picks.items()) / n, 4),
                "pick_none": round(sum(c == "none" for c in picks.values()) / n, 4),
            }
        # Buggy twins that pass the example tests cannot be removed by filtering.
        ex_test = dict(zip(df["problem"].astype(int), df["example_test"]))
        probs = [p for p in sorted(df["problem"].astype(int)) if has_test(ex_test[p])]
        code = dict(zip(docs["doc_id"], docs["code"]))
        buggy_pass = run_many([(example_runner(lang), code[f"{p}_buggy"], ex_test[p]) for p in probs])
        metrics["filtering"][lang]["n_problems_with_example_tests"] = len(probs)
        metrics["filtering"][lang]["buggy_twin_passes_example_tests"] = round(
            float(np.mean([r["passed"] for r in buggy_pass])), 4)
        with open(os.path.join(OUT_DIR, f"{lang}_contexts.json"), "w", encoding="utf-8") as f:
            json.dump(sorted([p, c] for p, c in contexts), f)

    with open(os.path.join(OUT_DIR, "metrics.json"), "w", encoding="utf-8") as f:
        json.dump(metrics, f, indent=2)
    with open(os.path.join(OUT_DIR, "rankings.json"), "w", encoding="utf-8") as f:
        json.dump(all_rankings, f)

    print(f"{'retriever':14s} recall@1  buggy-first [95% CI]      top1-buggy  binom p")
    for ret in RETRIEVERS:
        m = metrics["retrieval"][ret]["pooled"]
        print(f"{ret:14s} {m['recall1']:.3f}     {m['buggy_first']:.3f} {m['buggy_first_ci']}   "
              f"{m['top1_buggy']:.3f}      {m['buggy_first_binom_p']:.3g}")
    print("\nHuman-bug proximity (R, DN@0.10):")
    for model, v in metrics["proximity_humanevalfix"].items():
        print(f"  {model:14s} R={v['R']:.3f}  DN@0.10={v['dangerous_pct']['0.1']}%")
    print("\nRQ4 corpus proximity (pooling ablation):")
    for model, v in metrics["proximity_rq4"].items():
        print(f"  {model:14s} R={v['R']:.3f}  pair={v['pair_mean']}  unmatched={v['unmatched_mean']}  DN@0.10={v['dangerous_pct']['0.1']}%")


if __name__ == "__main__":
    main()

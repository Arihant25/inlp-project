"""
Review: RQ5 retrieval with the query instructions prescribed for Qwen3-Embedding and Octen-Embedding.

Qwen3-Embedding-0.6B and Octen-Embedding-0.6B (a LoRA fine-tune of Qwen3-Embedding-0.6B)
are instruction-aware: their model cards format a query as

    Instruct: {task description}\\nQuery:{query}

and leave documents without an instruction. The RQ5 study embedded queries without the
instruction. This script re-embeds only the RQ5 queries (HumanEvalFix docstrings for the six
languages and ClassEval skeletons, built by code/rq5/common.py build_corpus) with the
instruction, keeps the stored document embeddings (results/rq5/embeddings/<model>.npz and
<model>_classeval.npz), and recomputes the retrieval metrics with the functions of
code/rq5/2_retrieval.py (retrieval_records, summarize).

Instruction variants (task descriptions):
  - official: the instructions Qwen3-Embedding uses for its code-retrieval evaluation
    (QwenLM/Qwen3-Embedding evaluation/task_prompts.json):
      HumanEvalFix (docstring -> code), CodeSearchNetCCRetrieval:
        "Given a code comment, retrieve the code snippet corresponding to that comment."
      ClassEval (class skeleton -> class), tailored as the model card recommends:
        "Given a Python class skeleton with method signatures and docstrings, retrieve
         the complete implementation of the class."
  - generic: "Given a code search query, retrieve relevant code snippets that answer the
    query" for both datasets.
  - none: the stored query embeddings (the paper's setting), recomputed for reference.

Usage:
    python code/review/rq5_query_instructions.py

Output: results/review/rq5_query_instructions.json
"""

import importlib.util
import json
import os
import sys

import numpy as np
import torch
from sentence_transformers import SentenceTransformer

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
CODE_DIR = os.path.dirname(SCRIPT_DIR)
RQ5_DIR = os.path.join(CODE_DIR, "rq5")
sys.path.insert(0, RQ5_DIR)
sys.path.insert(0, CODE_DIR)
from common import LANGUAGES, PROJECT_ROOT, RESULTS_DIR, build_corpus  # noqa: E402
from embedding import MODELS  # noqa: E402

_spec = importlib.util.spec_from_file_location("rq5_retrieval", os.path.join(RQ5_DIR, "2_retrieval.py"))
retrieval = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(retrieval)

EMB_DIR = os.path.join(RESULTS_DIR, "embeddings")
OUT_PATH = os.path.join(PROJECT_ROOT, "results/review/rq5_query_instructions.json")
INSTRUCTED = ["qwen3", "octen"]
DATASETS = LANGUAGES + ["classeval"]

TASKS = {
    "official": {
        "humanevalfix": "Given a code comment, retrieve the code snippet corresponding to that comment.",
        "classeval": ("Given a Python class skeleton with method signatures and docstrings, "
                      "retrieve the complete implementation of the class."),
    },
    "generic": {
        "humanevalfix": "Given a code search query, retrieve relevant code snippets that answer the query",
        "classeval": "Given a code search query, retrieve relevant code snippets that answer the query",
    },
}


def prompt(task: str) -> str:
    """Model-card query format; ST prepends the prompt to the query text."""
    return f"Instruct: {task}\nQuery:"


def normalise(x: np.ndarray) -> np.ndarray:
    return x / np.linalg.norm(x, axis=1, keepdims=True)


def evaluate(qvecs: dict, dvecs: dict, corpora: dict) -> dict:
    """Retrieval metrics per dataset and pooled over the six HumanEvalFix languages."""
    out, recs_by_lang = {}, {}
    for lang in DATASETS:
        docs, queries = corpora[lang]
        Q = np.vstack([qvecs[f"{lang}|q|{p}"] for p in queries["problem"]])
        D = np.vstack([dvecs[f"{lang}|d|{d}"] for d in docs["doc_id"]])
        recs = retrieval.retrieval_records(Q @ D.T, docs, queries)
        recs_by_lang[lang] = recs
        out[lang] = retrieval.summarize(recs, np.random.default_rng(retrieval.RNG_SEED))
    pooled = [r for lang in LANGUAGES for r in recs_by_lang[lang]]
    out["pooled"] = retrieval.summarize(pooled, np.random.default_rng(retrieval.RNG_SEED))
    return out


def main():
    corpora = {lang: build_corpus(lang) for lang in DATASETS}
    with open(os.path.join(RESULTS_DIR, "retrieval/metrics.json"), encoding="utf-8") as f:
        stored_metrics = json.load(f)["retrieval"]
    device = "cuda" if torch.cuda.is_available() else "cpu"
    result = {"tasks": TASKS, "query_format": "Instruct: {task}\\nQuery:{query}",
              "documents": "stored embeddings, no instruction (results/rq5/embeddings)", "models": {}}

    for model_key in INSTRUCTED:
        name = MODELS[model_key]["name"]
        vecs = retrieval.load_vectors(model_key)  # stored, L2-normalised; queries and documents
        model = SentenceTransformer(name, trust_remote_code=True, device=device)
        entry = {"model_name": name, "card_prompts": model.prompts, "variants": {}}

        # Reproducibility check: re-embedding queries without instruction reproduces the stored vectors.
        texts, keys = [], []
        for lang in DATASETS:
            q = corpora[lang][1]
            texts += q["query"].tolist()
            keys += [f"{lang}|q|{p}" for p in q["problem"]]
        plain = normalise(model.encode(texts, batch_size=4, convert_to_numpy=True).astype(np.float32))
        entry["check_min_cosine_plain_vs_stored_query"] = round(
            float(min(plain[i] @ vecs[k] for i, k in enumerate(keys))), 6)

        entry["variants"]["none"] = evaluate(vecs, vecs, corpora)
        entry["check_none_matches_metrics_json_pooled"] = all(
            entry["variants"]["none"]["pooled"][k] == stored_metrics[model_key]["pooled"][k]
            for k in ("recall1", "buggy_first", "top1_buggy", "tie"))

        for variant, tasks in TASKS.items():
            qvecs = {}
            for lang in DATASETS:
                q = corpora[lang][1]
                task = tasks["classeval" if lang == "classeval" else "humanevalfix"]
                E = model.encode(q["query"].tolist(), prompt=prompt(task), batch_size=4, convert_to_numpy=True)
                qvecs.update(zip([f"{lang}|q|{p}" for p in q["problem"]], normalise(E.astype(np.float32))))
                print(f"  encoded {model_key} {variant} {lang}", flush=True)
            entry["variants"][variant] = evaluate(qvecs, vecs, corpora)
        result["models"][model_key] = entry
        del model
        torch.cuda.empty_cache()

        print(f"\n{model_key}: plain-vs-stored min cos {entry['check_min_cosine_plain_vs_stored_query']}, "
              f"none==metrics.json {entry['check_none_matches_metrics_json_pooled']}")
        for variant, v in entry["variants"].items():
            for ds in ("pooled", "classeval"):
                m = v[ds]
                print(f"  {variant:9s} {ds:9s} R@1={m['recall1']:.3f} buggy_first={m['buggy_first']:.3f} "
                      f"p={m['buggy_first_binom_p']:.3g} top1_buggy={m['top1_buggy']:.3f} tie={m['tie']:.3f}")

    os.makedirs(os.path.dirname(OUT_PATH), exist_ok=True)
    with open(OUT_PATH, "w", encoding="utf-8") as f:
        json.dump(result, f, indent=2)
    print(f"\nSaved {OUT_PATH}")


if __name__ == "__main__":
    main()

"""
Truncation check: share of inputs longer than each embedding model's input limit.

Counts tokens with each model's own tokenizer (special tokens included) for every
corpus used in the study, and reports the share of inputs that exceed the limit
the embedding pipeline applies:
  - CodeBERT and UniXCoder: 512 tokens (explicit truncation in embedding.py)
  - Sentence-Transformers models: the model's configured max_seq_length

Usage:
    python code/truncation_stats.py

Output: results/truncation_stats.json
"""

import glob
import json
import os
import sys

import pandas as pd
from huggingface_hub import hf_hub_download
from transformers import AutoTokenizer

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
PROJECT_ROOT = os.path.dirname(SCRIPT_DIR)
sys.path.insert(0, os.path.join(SCRIPT_DIR, "rq5"))
from common import LANGUAGES, build_corpus  # noqa: E402

MODELS = {
    "octen": "Octen/Octen-Embedding-0.6B",
    "bge_m3": "BAAI/bge-m3",
    "unixcoder": "microsoft/unixcoder-base",
    "codebert": "microsoft/codebert-base",
    "qwen3": "Qwen/Qwen3-Embedding-0.6B",
    "minilm": "sentence-transformers/all-MiniLM-L6-v2",
}
HF_MODELS = {"unixcoder", "codebert"}


def max_length(key: str, tokenizer) -> int:
    if key in HF_MODELS:
        return 512
    try:
        cfg = hf_hub_download(MODELS[key], "sentence_bert_config.json")
        with open(cfg, encoding="utf-8") as f:
            return int(json.load(f)["max_seq_length"])
    except Exception:
        return int(tokenizer.model_max_length)


def corpora() -> dict[str, list[str]]:
    texts = {}
    rq1 = []
    for path in sorted(glob.glob(os.path.join(PROJECT_ROOT, "datasets/family_clustering/*/*.json"))):
        with open(path, encoding="utf-8") as f:
            rq1 += [item["code"] for item in json.load(f) if "code" in item]
    texts["RQ1"] = rq1
    for rq, sub in (("RQ2", "rq2"), ("RQ3", "rq3"), ("RQ4", "RQ4")):
        # Any model's embedding file holds the same snippets.
        path = sorted(glob.glob(os.path.join(PROJECT_ROOT, f"results/{sub}/*/{sub.lower()}_embeddings.parquet")))[0]
        texts[rq] = pd.read_parquet(path, columns=["code"])["code"].tolist()
    he = []
    for lang in LANGUAGES:
        docs, queries = build_corpus(lang)
        he += docs["code"].tolist() + queries["query"].tolist()
    texts["RQ5 HumanEvalFix"] = he
    docs, queries = build_corpus("classeval")
    texts["RQ5 ClassEval"] = docs["code"].tolist() + queries["query"].tolist()
    return texts


def main():
    texts = corpora()
    out = {"n_inputs": {k: len(v) for k, v in texts.items()}, "limits": {}, "share_truncated": {}}
    for key, name in MODELS.items():
        tok = AutoTokenizer.from_pretrained(name)
        limit = max_length(key, tok)
        out["limits"][key] = limit
        out["share_truncated"][key] = {}
        for corpus, items in texts.items():
            lengths = [len(tok(t, add_special_tokens=True)["input_ids"]) for t in items]
            out["share_truncated"][key][corpus] = round(100 * sum(l > limit for l in lengths) / len(lengths), 2)
        print(key, limit, out["share_truncated"][key], flush=True)
    with open(os.path.join(PROJECT_ROOT, "results/truncation_stats.json"), "w", encoding="utf-8") as f:
        json.dump(out, f, indent=2)


if __name__ == "__main__":
    main()

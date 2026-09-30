"""
RQ5 Step 1: Embed HumanEvalFix queries and documents, plus the CLS-pooling ablation.

For each embedder and each of the six languages, embeds the 164 docstring
queries and the 328 documents (164 correct + 164 buggy). The six local models
are embedded with the same functions used for RQ1-RQ4 (code/embedding.py).

Pooling ablation: CodeBERT and UniXCoder are also embedded with CLS pooling
(first token, L2-normalised) instead of mean pooling, both for HumanEvalFix and
for the RQ4 bug-fix corpus.

Usage:
    python code/rq5/1_embedding.py                 # all embedders
    python code/rq5/1_embedding.py --model octen   # one embedder
    python code/rq5/1_embedding.py --check         # reproducibility check against RQ4

Output: results/rq5/embeddings/<model>.npz and <model>_classeval.npz (--dataset classeval)
"""

import argparse
import os
import sys

import numpy as np
import pandas as pd
import torch
from tqdm import tqdm
from transformers import AutoModel, AutoTokenizer

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, SCRIPT_DIR)
sys.path.insert(0, os.path.dirname(SCRIPT_DIR))
from common import CLS_VARIANTS, EMBEDDERS, LANGUAGES, PROJECT_ROOT, RESULTS_DIR, build_corpus
from embedding import MODELS, get_unixcoder_embeddings
from sentence_transformers import SentenceTransformer

OUT_DIR = os.path.join(RESULTS_DIR, "embeddings")
RQ4_PARQUET = os.path.join(PROJECT_ROOT, "results/RQ4/{model}/rq4_embeddings.parquet")


def get_cls_embeddings(model_name: str, texts: list[str], batch_size: int = 8) -> list[list[float]]:
    """Same as get_unixcoder_embeddings, but pools the first ([CLS]/<s>) token."""
    tokenizer = AutoTokenizer.from_pretrained(model_name)
    device = "cuda" if torch.cuda.is_available() else "cpu"
    model = AutoModel.from_pretrained(model_name).eval().to(device)
    out = []
    with torch.no_grad():
        for i in tqdm(range(0, len(texts), batch_size), desc=f"Embedding (CLS) {model_name}"):
            batch = tokenizer(texts[i : i + batch_size], padding=True, truncation=True,
                              max_length=512, return_tensors="pt").to(device)
            cls = model(**batch).last_hidden_state[:, 0]
            out.extend(torch.nn.functional.normalize(cls, p=2, dim=1).cpu().tolist())
    return out


def embed(model_key: str, texts: list[str]) -> np.ndarray:
    if model_key in CLS_VARIANTS:
        base = model_key.replace("_cls", "")
        return np.array(get_cls_embeddings(MODELS[base]["name"], texts), dtype=np.float32)
    info = MODELS[model_key]
    if info["type"] == "sentence_transformer":
        # Same model and default configuration as get_sentence_transformer_embeddings,
        # encoded in batches (identical up to floating-point noise; see --check).
        model = SentenceTransformer(info["name"], trust_remote_code=True)
        return model.encode(texts, batch_size=16, show_progress_bar=True, convert_to_numpy=True).astype(np.float32)
    return np.array(get_unixcoder_embeddings(info["name"], texts, batch_size=8), dtype=np.float32)


def rq4_texts() -> pd.DataFrame:
    """RQ4 snippets in the order of the saved RQ4 embeddings (any model's parquet)."""
    df = pd.read_parquet(RQ4_PARQUET.format(model="minilm"))
    return df.drop(columns=["embedding"])


def run_model(model_key: str, dataset: str = "humanevalfix"):
    texts, keys = [], []
    langs = ["classeval"] if dataset == "classeval" else LANGUAGES
    for lang in langs:
        docs, queries = build_corpus(lang)
        texts += queries["query"].tolist() + docs["code"].tolist()
        keys += [f"{lang}|q|{p}" for p in queries["problem"]] + [f"{lang}|d|{d}" for d in docs["doc_id"]]
    arrays = {"keys": np.array(keys)}
    arrays["vectors"] = embed(model_key, texts)
    if model_key in CLS_VARIANTS and dataset == "humanevalfix":
        rq4 = rq4_texts()
        arrays["rq4_vectors"] = embed(model_key, rq4["code"].tolist())
        arrays["rq4_keys"] = np.array(
            [f"{r.language}|{r.bug_index}|{r.code_type}" for r in rq4.itertuples()]
        )
    os.makedirs(OUT_DIR, exist_ok=True)
    suffix = "_classeval" if dataset == "classeval" else ""
    np.savez_compressed(os.path.join(OUT_DIR, f"{model_key}{suffix}.npz"), **arrays)
    print(f"Saved {model_key}: {arrays['vectors'].shape}")


def reproducibility_check(n: int = 20):
    """Re-embed n RQ4 snippets per model and compare with the saved RQ4 embeddings."""
    for model_key in EMBEDDERS:
        df = pd.read_parquet(RQ4_PARQUET.format(model=model_key)).head(n)
        new = embed(model_key, df["code"].tolist())
        old = np.vstack(df["embedding"].values).astype(np.float32)
        cos = (new * old).sum(1) / (np.linalg.norm(new, axis=1) * np.linalg.norm(old, axis=1))
        print(f"{model_key:10s} min cosine to saved RQ4 embeddings: {cos.min():.6f}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default="all", choices=EMBEDDERS + CLS_VARIANTS + ["all"])
    ap.add_argument("--dataset", default="humanevalfix", choices=["humanevalfix", "classeval"])
    ap.add_argument("--check", action="store_true")
    args = ap.parse_args()
    if args.check:
        reproducibility_check()
        return
    for m in EMBEDDERS + CLS_VARIANTS if args.model == "all" else [args.model]:
        run_model(m, args.dataset)


if __name__ == "__main__":
    main()

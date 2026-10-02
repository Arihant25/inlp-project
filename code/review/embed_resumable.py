"""
Review: resumable version of the RQ1-RQ3 embedding step for the added code embedders.

Same inputs, model loading and encoding as the pipeline (code/embedding.py
get_sentence_transformer_embeddings: SentenceTransformer(name, trust_remote_code=True), one
model.encode(text) call per snippet, default max_seq_length, no prompt), but it checkpoints
the vectors every few hundred snippets so a run that is stopped (2-hour job limit, shared GPU)
resumes where it left off. The final files are the ones the pipeline writes:

  rq1: results/embeddings/<model>_embeddings.json            (code/embedding.py)
  rq2: results/rq2/<model>/rq2_embeddings.parquet            (code/rq2/1_embedding.py)
  rq3: results/rq3/<model>/rq3_embeddings.parquet + rq3_complexity_stats.json (code/rq3/1_embedding.py)

Usage:
    python code/review/embed_resumable.py --rq rq2 --model jina_code [--max-minutes 100]
"""

import argparse
import importlib.util
import json
import os
import sys
import time

import numpy as np
import torch
from sentence_transformers import SentenceTransformer

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
CODE_DIR = os.path.dirname(SCRIPT_DIR)
PROJECT_ROOT = os.path.dirname(CODE_DIR)
sys.path.insert(0, CODE_DIR)
import embedding  # noqa: E402

CKPT_DIR = os.path.join(PROJECT_ROOT, "results/review/embed_checkpoints")


def load_module(path, name):
    spec = importlib.util.spec_from_file_location(name, os.path.join(CODE_DIR, path))
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def load_texts(rq):
    if rq == "rq1":
        snippets = embedding.load_code_snippets(embedding.BASE_DIR, embedding.LANGUAGES, embedding.FEATURES)
        return snippets, list(snippets.values())
    if rq == "rq2":
        m = load_module("rq2/1_embedding.py", "rq2_embedding")
        df = m.load_rq2_dataset(m.DATASET_DIR)
    else:
        m = load_module("rq3/1_embedding.py", "rq3_embedding")
        df = m.load_rq3_dataset(m.DATASET_DIR)
    return df, df["code"].tolist()


def save_final(rq, model_key, data, vectors):
    if rq == "rq1":
        embedding.save_results(model_key, list(data.keys()), list(data.values()), [v.tolist() for v in vectors])
        return
    out_dir = os.path.join(PROJECT_ROOT, f"results/{rq}/{model_key}")
    os.makedirs(out_dir, exist_ok=True)
    df = data.copy()
    df["embedding"] = [v.tolist() for v in vectors]
    df.to_parquet(os.path.join(out_dir, f"{rq}_embeddings.parquet"), index=False)
    if rq == "rq3":
        stats = {
            "model_key": model_key,
            "total_solutions": int(len(df)),
            "complexity_class_counts": df["complexity_class"].value_counts().to_dict(),
            "language_counts": df["language"].value_counts().to_dict(),
            "difficulty_counts": df["difficulty"].value_counts().to_dict(),
            "problems": int(df["problem_slug"].nunique()),
        }
        with open(os.path.join(out_dir, "rq3_complexity_stats.json"), "w") as f:
            json.dump(stats, f, indent=2)
    print(f"Saved final {rq} embeddings for {model_key}: {len(df)}", flush=True)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--rq", required=True, choices=["rq1", "rq2", "rq3"])
    ap.add_argument("--model", required=True, choices=["jina_code", "codesage"])
    ap.add_argument("--max-minutes", type=float, default=100)
    ap.add_argument("--every", type=int, default=200)
    ap.add_argument("--batch-size", type=int, default=1,
                    help="1 = one encode call per snippet as in the pipeline; >1 batches (identical up to float noise)")
    args = ap.parse_args()

    final = (os.path.join(embedding.OUTPUT_DIR, f"{args.model}_embeddings.json") if args.rq == "rq1"
             else os.path.join(PROJECT_ROOT, f"results/{args.rq}/{args.model}/{args.rq}_embeddings.parquet"))
    if os.path.exists(final):
        print(f"{final} exists; nothing to do.", flush=True)
        return
    data, texts = load_texts(args.rq)
    os.makedirs(CKPT_DIR, exist_ok=True)
    ckpt = os.path.join(CKPT_DIR, f"{args.rq}_{args.model}.npy")
    done = np.load(ckpt) if os.path.exists(ckpt) else None
    vecs = list(done) if done is not None else []
    print(f"{args.rq} {args.model}: {len(texts)} texts, {len(vecs)} already embedded", flush=True)

    if len(vecs) < len(texts):
        device = "cuda" if torch.cuda.is_available() else "cpu"
        model = SentenceTransformer(embedding.MODELS[args.model]["name"], trust_remote_code=True).to(device)
        start = time.time()
        bs = args.batch_size
        for i in range(len(vecs), len(texts), bs):
            if bs == 1:
                vecs.append(model.encode(texts[i], show_progress_bar=False, convert_to_numpy=True).astype(np.float32))
            else:
                vecs.extend(model.encode(texts[i:i + bs], batch_size=bs, show_progress_bar=False,
                                         convert_to_numpy=True).astype(np.float32))
            i = len(vecs) - 1
            if (i + 1) % args.every < bs or i + 1 == len(texts):
                np.save(ckpt, np.vstack(vecs))
                print(f"  {i + 1}/{len(texts)}  {time.time() - start:.0f}s", flush=True)
                if torch.cuda.is_available():
                    torch.cuda.empty_cache()
                if (time.time() - start) / 60 > args.max_minutes and i + 1 < len(texts):
                    print("Time budget reached; rerun to resume.", flush=True)
                    return
    save_final(args.rq, args.model, data, vecs)


if __name__ == "__main__":
    main()

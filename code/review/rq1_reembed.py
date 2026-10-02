"""
Re-embed the RQ1 snippets (datasets/family_clustering) with the six local
models, for the within-model bootstrap in rq1_robustness.py.

The original per-snippet embeddings (results/embeddings/<model>_embeddings.json)
live in Git LFS and are not available, so we recompute them with the same
procedure as code/embedding.py:
  - octen, qwen3, bge_m3, minilm: SentenceTransformer(name, trust_remote_code=True)
    .encode(...) with default settings (batched here instead of one text at a
    time; padding is masked so outputs agree up to float noise);
  - unixcoder, codebert: AutoModel, truncation to 512 tokens, attention-masked
    mean pooling of last_hidden_state, L2 normalisation (as get_unixcoder_embeddings).
Snippets are loaded with code/embedding.py:load_code_snippets.
Ada-002 is API-only and is not re-embedded.

Usage:
    HF_HUB_OFFLINE=1 PYTHONIOENCODING=utf-8 .venvgpu/Scripts/python.exe \
        code/review/rq1_reembed.py --model all [--batch-size 16] [--fp16]

Output: results/review/rq1_embeddings/<model>.npz with arrays
        keys (str), emb (float16, n_snippets x dim)
"""

import argparse
import gc
import shutil
import os
import sys

import numpy as np
import torch

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
CODE_DIR = os.path.abspath(os.path.join(SCRIPT_DIR, ".."))
sys.path.insert(0, CODE_DIR)
import embedding as E  # noqa: E402  (reuse MODELS, LANGUAGES, FEATURES, loader)

OUT_DIR = os.path.abspath(os.path.join(CODE_DIR, "..", "results", "review", "rq1_embeddings"))
LOCAL_MODELS = ["octen", "qwen3", "bge_m3", "minilm", "unixcoder", "codebert"]


def embed_st(name, texts, bs, fp16=False):
    from sentence_transformers import SentenceTransformer
    kw = {"model_kwargs": {"torch_dtype": torch.float16}} if fp16 else {}
    model = SentenceTransformer(name, trust_remote_code=True, device="cuda", **kw)
    # Resumable: texts are encoded in length-sorted chunks, each saved to
    # <OUT_DIR>/partial_<model>/chunk_XXXX.npy, so an interrupted run resumes.
    # Per-text outputs do not depend on chunking (padding is masked).
    part_dir = os.path.join(OUT_DIR, "partial_" + name.replace("/", "__"))
    os.makedirs(part_dir, exist_ok=True)
    order = np.argsort([-len(t) for t in texts], kind="stable")
    chunk = 1000
    out = []
    try:
        for c, s in enumerate(range(0, len(texts), chunk)):
            f = os.path.join(part_dir, f"chunk_{c:04d}.npy")
            if not os.path.exists(f):
                e = model.encode([texts[j] for j in order[s:s + chunk]], batch_size=bs,
                                 show_progress_bar=False, convert_to_numpy=True)
                np.save(f, e.astype(np.float32))
                print(f"  {name}: chunk {c} ({s + len(e)}/{len(texts)})", flush=True)
            out.append(np.load(f))
    finally:
        del model
    emb = np.empty((len(texts), out[0].shape[1]), dtype=np.float32)
    emb[order] = np.concatenate(out)
    return emb


def embed_hf(name, texts, bs):
    from transformers import AutoModel, AutoTokenizer
    tok = AutoTokenizer.from_pretrained(name)
    model = AutoModel.from_pretrained(name).to("cuda").eval()
    out = []
    # Process in length order to reduce padding (padding is masked, so per-text
    # outputs are unchanged); results are put back in the original order below.
    order = np.argsort([-len(t) for t in texts], kind="stable")
    sorted_texts = [texts[j] for j in order]
    try:
        for i in range(0, len(texts), bs):
            inputs = tok(sorted_texts[i:i + bs], padding=True, truncation=True, max_length=512,
                         return_tensors="pt")
            inputs = {k: v.to("cuda") for k, v in inputs.items()}
            with torch.no_grad():
                h = model(**inputs).last_hidden_state
                m = inputs["attention_mask"].unsqueeze(-1).expand(h.size()).float()
                mean = (h * m).sum(1) / torch.clamp(m.sum(1), min=1e-9)
                out.append(torch.nn.functional.normalize(mean, p=2, dim=1).cpu().numpy())
            if (i // bs) % 200 == 0:
                print(f"  {name}: {i}/{len(texts)}", flush=True)
    finally:
        del model
    emb = np.empty((len(texts), out[0].shape[1]), dtype=np.float32)
    emb[order] = np.concatenate(out)
    return emb


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default="all", choices=LOCAL_MODELS + ["all"])
    ap.add_argument("--batch-size", type=int, default=16)
    ap.add_argument("--fp16", action="store_true",
                    help="load SentenceTransformer weights in float16 (only used when the shared "
                         "GPU lacks memory for float32; fidelity is checked in rq1_bootstrap.py)")
    args = ap.parse_args()
    os.makedirs(OUT_DIR, exist_ok=True)
    snippets = E.load_code_snippets(E.BASE_DIR, E.LANGUAGES, E.FEATURES)
    keys = list(snippets.keys())
    texts = [snippets[k] for k in keys]
    for m in (LOCAL_MODELS if args.model == "all" else [args.model]):
        path = os.path.join(OUT_DIR, f"{m}.npz")
        if os.path.exists(path):
            print(f"skip {m} (exists)")
            continue
        info = E.MODELS[m]
        print(f"=== {m}: {info['name']}", flush=True)
        if info["type"] == "sentence_transformer":
            emb = embed_st(info["name"], texts, args.batch_size, args.fp16)
        else:
            emb = embed_hf(info["name"], texts, args.batch_size)
        np.savez_compressed(path, keys=np.array(keys), emb=emb.astype(np.float16),
                            model_dtype=np.array("float16" if (args.fp16 and info["type"] == "sentence_transformer") else "float32"))
        print(f"saved {path} {emb.shape}", flush=True)
        shutil.rmtree(os.path.join(OUT_DIR, "partial_" + info["name"].replace("/", "__")),
                      ignore_errors=True)
        gc.collect()
        torch.cuda.empty_cache()


if __name__ == "__main__":
    main()

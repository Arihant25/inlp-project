"""
Review control, step 2: embed fix, bug and every VALID semantics-preserving variant.

Uses `embed()` from code/rq5/1_embedding.py, i.e. exactly the procedure that produced
results/rq5/embeddings/*.npz (SentenceTransformer.encode for octen, qwen3, bge_m3,
minilm; mean pooling + L2 norm with 512-token truncation for unixcoder, codebert).

Reproducibility check: the freshly embedded correct solutions are compared with the
stored RQ5 vectors (results/rq5/embeddings/<model>.npz and <model>_classeval.npz);
min / mean cosine are written to results/review/embeddings/repro_check.json.

Usage:
    python code/review/embed_variants.py                # all six embedders
    python code/review/embed_variants.py --model minilm

Output: results/review/embeddings/<model>.npz with arrays `keys` and `vectors`;
keys are "<lang>|<problem>|fix", "<lang>|<problem>|bug", "<lang>|<problem>|<kind>|<idx>".
"""

import argparse
import importlib.util
import json
import os
import sys

import numpy as np
import pandas as pd

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
RQ5_DIR = os.path.join(os.path.dirname(SCRIPT_DIR), "rq5")
sys.path.insert(0, RQ5_DIR)
from common import EMBEDDERS, PROJECT_ROOT, RESULTS_DIR, build_corpus  # noqa: E402

_spec = importlib.util.spec_from_file_location("rq5_embedding", os.path.join(RQ5_DIR, "1_embedding.py"))
rq5_embedding = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(rq5_embedding)

OUT_DIR = os.path.join(PROJECT_ROOT, "results/review/embeddings")
VARIANTS = os.path.join(PROJECT_ROOT, "results/review/variants.parquet")
LANGS = ["python", "js", "java", "classeval"]


def texts_and_keys() -> tuple[list[str], list[str]]:
    var = pd.read_parquet(VARIANTS)
    var = var[var.passed]
    texts, keys = [], []
    for lang in LANGS:
        docs, _ = build_corpus(lang)
        for r in docs.itertuples():
            texts.append(r.code)
            keys.append(f"{lang}|{r.problem}|{'fix' if r.variant == 'correct' else 'bug'}")
        for r in var[var.lang == lang].itertuples():
            texts.append(r.code)
            keys.append(f"{lang}|{r.problem}|{r.kind}|{r.idx}")
    return texts, keys


def repro_check(model_key: str, keys: list[str], vecs: np.ndarray) -> dict:
    out = {}
    for lang in LANGS:
        path = os.path.join(RESULTS_DIR, "embeddings", f"{model_key}{'_classeval' if lang == 'classeval' else ''}.npz")
        z = np.load(path)
        stored = dict(zip(z["keys"], z["vectors"]))
        cos = []
        for k, v in zip(keys, vecs):
            l, p, t = k.split("|")[:3]
            if l != lang or t not in ("fix", "bug") or len(k.split("|")) != 3:
                continue
            s = stored[f"{lang}|d|{p}_{'correct' if t == 'fix' else 'buggy'}"]
            cos.append(float(v @ s / (np.linalg.norm(v) * np.linalg.norm(s))))
        out[lang] = {"n": len(cos), "min_cos": min(cos), "mean_cos": float(np.mean(cos))}
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default="all", choices=EMBEDDERS + ["all"])
    args = ap.parse_args()
    os.makedirs(OUT_DIR, exist_ok=True)
    texts, keys = texts_and_keys()
    print(f"{len(texts)} texts")
    rp = os.path.join(OUT_DIR, "repro_check.json")
    repro = json.load(open(rp)) if os.path.exists(rp) else {}
    for m in EMBEDDERS if args.model == "all" else [args.model]:
        vecs = rq5_embedding.embed(m, texts).astype(np.float32)
        np.savez_compressed(os.path.join(OUT_DIR, f"{m}.npz"), keys=np.array(keys), vectors=vecs)
        repro[m] = repro_check(m, keys, vecs)
        print(m, json.dumps(repro[m]))
        with open(rp, "w") as fh:
            json.dump(repro, fh, indent=2)
        import torch
        torch.cuda.empty_cache()


if __name__ == "__main__":
    main()

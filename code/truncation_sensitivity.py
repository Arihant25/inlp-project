"""
Truncation sensitivity for RQ2 and RQ3.

CodeBERT and UniXCoder truncate inputs at 512 tokens and MiniLM at 256, which
cuts every RQ2 snippet and part of the RQ3 corpus (see truncation_stats.py).
This script re-embeds both corpora with these three models so that the whole
snippet contributes: the text is split into consecutive windows at the model's
limit, each window is mean-pooled as in the original pipeline, and the window
vectors are averaged (weighted by window length) and L2-normalised.

The unchanged RQ2 and RQ3 analysis functions are then applied to the new
embeddings, and the results are compared with the truncated ones.

Usage:
    python code/truncation_sensitivity.py

Output: results/truncation_sensitivity.json
"""

import importlib.util
import json
import os
import sys

import numpy as np
import pandas as pd
import torch
from tqdm import tqdm
from transformers import AutoModel, AutoTokenizer

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
PROJECT_ROOT = os.path.dirname(SCRIPT_DIR)
sys.path.insert(0, SCRIPT_DIR)

MODELS = {
    "codebert": ("microsoft/codebert-base", 512),
    "unixcoder": ("microsoft/unixcoder-base", 512),
    "minilm": ("sentence-transformers/all-MiniLM-L6-v2", 256),
}


def load_module(path: str, name: str):
    spec = importlib.util.spec_from_file_location(name, path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def windowed_embeddings(model_name: str, limit: int, texts: list[str], batch_size: int = 8) -> np.ndarray:
    tok = AutoTokenizer.from_pretrained(model_name)
    device = "cuda" if torch.cuda.is_available() else "cpu"
    model = AutoModel.from_pretrained(model_name).eval().to(device)
    body = limit - 2  # room for the start and end special tokens
    windows, owner = [], []
    for i, t in enumerate(texts):
        ids = tok(t, add_special_tokens=False)["input_ids"]
        for s in range(0, max(len(ids), 1), body):
            windows.append([tok.cls_token_id] + ids[s:s + body] + [tok.sep_token_id])
            owner.append(i)
    vecs = np.zeros((len(windows), model.config.hidden_size), dtype=np.float32)
    lens = np.array([len(w) for w in windows], dtype=np.float32)
    with torch.no_grad():
        for b in tqdm(range(0, len(windows), batch_size), desc=f"windows {model_name}"):
            chunk = windows[b:b + batch_size]
            width = max(len(w) for w in chunk)
            ids = torch.tensor([w + [tok.pad_token_id] * (width - len(w)) for w in chunk])
            mask = torch.tensor([[1] * len(w) + [0] * (width - len(w)) for w in chunk])
            ids, mask = ids.to(device), mask.to(device)
            hidden = model(input_ids=ids, attention_mask=mask).last_hidden_state
            m = mask.unsqueeze(-1).float()
            vecs[b:b + len(chunk)] = ((hidden * m).sum(1) / m.sum(1).clamp(min=1e-9)).cpu().numpy()
    out = np.zeros((len(texts), vecs.shape[1]), dtype=np.float32)
    weight = np.zeros(len(texts), dtype=np.float32)
    for v, o, l in zip(vecs, owner, lens):
        out[o] += l * v
        weight[o] += l
    out /= weight[:, None]
    return out / np.linalg.norm(out, axis=1, keepdims=True)


def main():
    rq2 = load_module(os.path.join(SCRIPT_DIR, "rq2", "2_analysis.py"), "rq2_analysis")
    rq3 = load_module(os.path.join(SCRIPT_DIR, "rq3", "2_analysis.py"), "rq3_analysis")
    results = {}
    for key, (name, limit) in MODELS.items():
        results[key] = {"limit": limit}

        df = pd.read_parquet(os.path.join(PROJECT_ROOT, f"results/rq2/{key}/rq2_embeddings.parquet"))
        df["embedding"] = list(windowed_embeddings(name, limit, df["code"].tolist()))
        sil = rq2.compute_framework_silhouette(df)
        dist = rq2.compute_cross_framework_distances(df)
        stat = rq2.compute_statistical_tests(dist)
        with open(os.path.join(PROJECT_ROOT, f"results/rq2/{key}/rq2_metrics.json"), encoding="utf-8") as f:
            base = json.load(f)
        results[key]["rq2"] = {
            "truncated": {"framework_silhouette": base["silhouette"]["framework_silhouette_overall"],
                          "language_silhouette": base["silhouette"]["language_silhouette_overall"],
                          "cohens_d": base["statistical_tests"]["effect_size"]["cohens_d"]},
            "full_text": {"framework_silhouette": sil["framework_silhouette_overall"],
                          "language_silhouette": sil["language_silhouette_overall"],
                          "cohens_d": stat["effect_size"]["cohens_d"]},
        }

        df = pd.read_parquet(os.path.join(PROJECT_ROOT, f"results/rq3/{key}/rq3_embeddings.parquet"))
        df["embedding"] = list(windowed_embeddings(name, limit, df["code"].tolist()))
        sil = rq3.compute_complexity_silhouette(df)
        dist = rq3.compute_complexity_distances(df)
        stat = rq3.compute_statistical_tests(dist, sil)
        prob = rq3.compute_problem_identity_distances(df)
        with open(os.path.join(PROJECT_ROOT, f"results/rq3/{key}/rq3_metrics.json"), encoding="utf-8") as f:
            base = json.load(f)
        results[key]["rq3"] = {
            "truncated": {"complexity_silhouette": base["silhouette"]["complexity_silhouette_overall"],
                          "cohens_d": base["statistical_tests"]["effect_size"]["cohens_d"],
                          "problem_identity_d": base["problem_identity"]["cohens_d"]},
            "full_text": {"complexity_silhouette": sil["complexity_silhouette_overall"],
                          "cohens_d": stat["effect_size"]["cohens_d"],
                          "problem_identity_d": prob["cohens_d"]},
        }
        print(key, json.dumps(results[key]), flush=True)

    with open(os.path.join(PROJECT_ROOT, "results/truncation_sensitivity.json"), "w", encoding="utf-8") as f:
        json.dump(results, f, indent=2)


if __name__ == "__main__":
    main()

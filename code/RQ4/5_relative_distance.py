"""
RQ4: Relative pair distance R per model and per bug category.

For each model and language, the buggy and fixed snippets are matched by
bug_index. The pair distance is the cosine distance between a buggy snippet and
its own fix. The unmatched distance is the mean over all buggy/fixed pairs of
different bugs in the same language (computed exhaustively, no sampling).

    R = mean pair distance / mean unmatched distance

R per category divides the mean pair distance of that category by the model's
unmatched distance, so categories are compared on one scale.

Usage:
    python code/RQ4/5_relative_distance.py

Output: results/RQ4/relative_distance.json
"""

import json
import os

import numpy as np
import pandas as pd

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
MODELS = ["unixcoder", "bge_m3", "codebert", "minilm", "qwen3", "octen", "ada002"]
CATEGORY = {"Easy": "syntax", "Medium": "logic", "Hard": "state", "Super Hard": "numeric"}


def analyse(model: str) -> dict:
    df = pd.read_parquet(os.path.join(ROOT, f"results/RQ4/{model}/rq4_embeddings.parquet"))
    pair, unmatched, cats, closest = [], [], [], None
    for lang, g in df.groupby("language"):
        b = g[g["code_type"] == "buggy"].set_index("bug_index").sort_index()
        f = g[g["code_type"] == "fixed"].set_index("bug_index").sort_index()
        assert list(b.index) == list(f.index)
        B = np.vstack(b["embedding"].values)
        F = np.vstack(f["embedding"].values)
        B /= np.linalg.norm(B, axis=1, keepdims=True)
        F /= np.linalg.norm(F, axis=1, keepdims=True)
        D = 1 - B @ F.T
        d = np.diag(D)
        pair.extend(d)
        unmatched.extend(D[~np.eye(len(D), dtype=bool)])
        cats.extend(CATEGORY[s] for s in b["severity"])
        i = int(np.argmin(d))
        if closest is None or d[i] < closest["distance"]:
            closest = {"distance": float(d[i]), "language": lang, "bug_type": b["bug_type"].iloc[i],
                       "category": CATEGORY[b["severity"].iloc[i]]}
    pair, cats = np.array(pair), np.array(cats)
    um = float(np.mean(unmatched))
    by_cat = {c: float(pair[cats == c].mean()) for c in CATEGORY.values()}
    other = float(pair[cats != "syntax"].mean())
    return {
        "n_pairs": int(len(pair)),
        "pair_mean": round(float(pair.mean()), 4),
        "unmatched_mean": round(um, 4),
        "R": round(float(pair.mean()) / um, 4),
        "inverse_R": round(um / float(pair.mean()), 2),
        "R_by_category": {c: round(v / um, 4) for c, v in by_cat.items()},
        "n_by_category": {c: int((cats == c).sum()) for c in CATEGORY.values()},
        "syntax_ratio": round(by_cat["syntax"] / other, 2),
        "closest_pair": closest,
    }


def main():
    out = {m: analyse(m) for m in MODELS}
    path = os.path.join(ROOT, "results/RQ4/relative_distance.json")
    with open(path, "w", encoding="utf-8") as f:
        json.dump(out, f, indent=2)
    for m, v in out.items():
        print(f"{m:10s} pair={v['pair_mean']:.3f} unmatched={v['unmatched_mean']:.3f} R={v['R']:.3f} 1/R={v['inverse_R']:.1f} "
              f"cats={ {k: round(x, 3) for k, x in v['R_by_category'].items()} } syn={v['syntax_ratio']} closest={v['closest_pair']['bug_type']}")


if __name__ == "__main__":
    main()

"""
Review: one-file summary of RQ2-RQ4 results for all nine embedders (seven existing + jina_code, codesage).

Reads the per-model metric files written by the unchanged analysis scripts
(results/rq2/<m>/rq2_metrics.json, results/rq3/<m>/rq3_metrics.json, results/RQ4/<m>/rq4_metrics.json),
results/RQ4/relative_distance.json (existing models) and
results/review/rq4_relative_distance_new_embedders.json (new models).

Output: results/review/new_embedders_summary.json
"""

import json
import os

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
MODELS = ["octen", "qwen3", "bge_m3", "minilm", "ada002", "unixcoder", "codebert", "jina_code", "codesage"]


def load(path):
    path = os.path.join(ROOT, path)
    if not os.path.exists(path):
        return None
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def main():
    rel = load("results/RQ4/relative_distance.json") or {}
    rel.update(load("results/review/rq4_relative_distance_new_embedders.json") or {})
    out = {"rq2": {}, "rq3": {}, "rq4": {}}
    for m in MODELS:
        d = load(f"results/rq2/{m}/rq2_metrics.json")
        if d:
            out["rq2"][m] = {
                "framework_silhouette": d["silhouette"]["framework_silhouette_overall"],
                "language_silhouette": d["silhouette"]["language_silhouette_overall"],
                "cross": d["distances"]["cross_framework_distance_overall"],
                "intra": d["distances"]["intra_framework_distance_overall"],
                "cohens_d": d["statistical_tests"]["effect_size"]["cohens_d"],
                "p_value": d["statistical_tests"]["t_test"]["p_value"],
                "per_language_silhouette": d["silhouette"]["framework_silhouette_per_language"],
            }
        d = load(f"results/rq3/{m}/rq3_metrics.json")
        if d:
            pi = d["problem_identity"]
            out["rq3"][m] = {
                "complexity_silhouette": d["silhouette"]["complexity_silhouette_overall"],
                "cross": d["distances"]["cross_complexity_overall"],
                "intra": d["distances"]["intra_complexity_overall"],
                "cohens_d": d["statistical_tests"]["effect_size"]["cohens_d"],
                "p_value": d["statistical_tests"]["t_test"]["p_value"],
                "problem_identity_same_problem": pi["same_problem_diff_complexity_overall"],
                "problem_identity_diff_problem": pi["diff_problem_same_complexity_overall"],
                "problem_identity_d": pi["cohens_d"],
                "problem_identity_p": pi["p_value"],
            }
        d = load(f"results/RQ4/{m}/rq4_metrics.json")
        if d:
            dn = d["dangerous_neighbourhoods"]
            out["rq4"][m] = {
                "correctness_silhouette": d["silhouette"]["correctness_silhouette_overall"],
                "language_silhouette": d["silhouette"]["language_silhouette_overall"],
                "cohens_d": d["statistical_tests"]["effect_size"]["cohens_d"],
                "p_value": d["statistical_tests"]["t_test"]["p_value"],
                "dangerous_pct": {str(t): dn[f"threshold_{t}"]["overall"]["pct"] for t in dn["thresholds"]},
                **({k: rel[m][k] for k in ("pair_mean", "unmatched_mean", "R", "inverse_R", "R_by_category",
                                           "syntax_ratio", "closest_pair")} if m in rel else {}),
            }
    path = os.path.join(ROOT, "results/review/new_embedders_summary.json")
    with open(path, "w", encoding="utf-8") as f:
        json.dump(out, f, indent=2)
    for rq, rows in out.items():
        print(f"== {rq}")
        for m, v in rows.items():
            print(f"  {m:10s} " + " ".join(f"{k}={v[k]:.3f}" if isinstance(v[k], float) else "" for k in v))
    print("Saved", path)


if __name__ == "__main__":
    main()

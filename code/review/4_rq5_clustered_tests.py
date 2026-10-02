"""
Review: problem-clustered significance for the pooled HumanEvalFix retrieval metrics.

The paper's pooled buggy-first test (exact binomial on untied queries) pools
164 problems x 6 languages = 984 queries, but the six language versions of one
problem are not independent. Here the problem is the unit:
  - per retriever and problem, average the buggy_first indicator (ties = 0.5, as in
    code/rq5/2_retrieval.py) and top1_buggy / top1_correct over the six languages
  - Wilcoxon signed-rank test of the 164 per-problem buggy-first means against 0.5
    (zero differences dropped, 'wilcox'; Pratt variant also reported) and a sign test
  - problem-level bootstrap 95% CIs (5,000 resamples of problems, seed 0) for the
    pooled buggy-first rate and the top-1-buggy rate, plus top1_buggy - top1_correct
  - Wilcoxon of per-problem (top1_buggy - top1_correct) against 0
Compared against the pooled query-level binomial p and CIs in results/rq5/retrieval/metrics.json.

Output: results/review/rq5_clustered_tests.json
"""

import json
import os
import sys

import numpy as np
from scipy.stats import binomtest, wilcoxon

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from common import OUT_DIR, ROOT, r3

LANGS = ["python", "js", "java", "go", "cpp", "rust"]
N_BOOT = 5000
SEED = 0


def main():
    rankings = json.load(open(os.path.join(ROOT, "results/rq5/retrieval/rankings.json"), encoding="utf-8"))
    paper = json.load(open(os.path.join(ROOT, "results/rq5/retrieval/metrics.json"), encoding="utf-8"))["retrieval"]
    out = {}
    for ret in rankings["python"]:
        per = {}
        for l in LANGS:
            for r in rankings[l][ret]:
                per.setdefault(r["problem"], []).append((r["buggy_first"], r["top1_buggy"], r["top1_correct"]))
        probs = sorted(per)
        assert all(len(per[p]) == 6 for p in probs)
        A = np.array([np.mean(per[p], axis=0) for p in probs])  # (164, 3)
        bf, tb, tc = A[:, 0], A[:, 1], A[:, 2]
        rng = np.random.default_rng(SEED)
        idx = rng.integers(0, len(probs), (N_BOOT, len(probs)))
        ci = lambda x: [float(np.percentile(x, 2.5)), float(np.percentile(x, 97.5))]
        bf_b, tb_b, gap_b = bf[idx].mean(1), tb[idx].mean(1), (tb - tc)[idx].mean(1)
        w = wilcoxon(bf - 0.5, zero_method="wilcox")
        wp = wilcoxon(bf - 0.5, zero_method="pratt")
        nz = bf != 0.5
        k = int((bf[nz] > 0.5).sum())
        gap = tb - tc
        wg = wilcoxon(gap, zero_method="wilcox") if np.any(gap != 0) else None
        pp = paper[ret]["pooled"]
        sig = lambda p: bool(p < 0.05)
        out[ret] = {
            "n_problems": len(probs),
            "buggy_first_rate": float(bf.mean()), "buggy_first_ci_problem_bootstrap": ci(bf_b),
            "buggy_first_ci_query_bootstrap_paper": pp["buggy_first_ci"],
            "wilcoxon_vs_0.5_p": float(w.pvalue), "wilcoxon_pratt_p": float(wp.pvalue),
            "n_problems_nonzero": int(nz.sum()), "sign_test_p": float(binomtest(k, int(nz.sum()), 0.5).pvalue) if nz.any() else None,
            "n_problems_buggy_first_majority": k, "n_problems_correct_first_majority": int(nz.sum()) - k,
            "query_level_binom_p_paper": pp["buggy_first_binom_p"],
            "significant_query_level": sig(pp["buggy_first_binom_p"]),
            "significant_problem_level_wilcoxon": sig(w.pvalue),
            "problem_ci_excludes_0.5": bool(ci(bf_b)[0] > 0.5 or ci(bf_b)[1] < 0.5),
            "top1_buggy_rate": float(tb.mean()), "top1_buggy_ci_problem_bootstrap": ci(tb_b),
            "top1_buggy_ci_query_bootstrap_paper": pp["top1_buggy_ci"],
            "top1_correct_rate": float(tc.mean()),
            "top1_buggy_minus_correct": float(gap.mean()), "top1_gap_ci_problem_bootstrap": ci(gap_b),
            "top1_gap_wilcoxon_p": float(wg.pvalue) if wg else None,
        }
    os.makedirs(OUT_DIR, exist_ok=True)
    with open(os.path.join(OUT_DIR, "rq5_clustered_tests.json"), "w", encoding="utf-8") as f:
        json.dump(r3(out), f, indent=2)
    for ret, v in out.items():
        print(f"{ret:14s} bf={v['buggy_first_rate']:.3f} CI={np.round(v['buggy_first_ci_problem_bootstrap'],3)} "
              f"W p={v['wilcoxon_vs_0.5_p']:.3g} binom p={v['query_level_binom_p_paper']:.3g} "
              f"top1b={v['top1_buggy_rate']:.3f} CI={np.round(v['top1_buggy_ci_problem_bootstrap'],3)} gap p={v['top1_gap_wilcoxon_p']}")


if __name__ == "__main__":
    main()

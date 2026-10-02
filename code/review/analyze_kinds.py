"""
Review control, step 4 (supplement to analyze.py): per-kind paired contrasts and
lexical tracking, computed from results/review/pairs.parquet.

  * kind_paired: for each variant kind, problems having both the bug and >= 1 valid
    variant of that kind; problem-level mean d(fix, variant) vs d(fix, bug):
    share of problems where the bug moves the embedding MORE, paired Wilcoxon,
    Cohen's dz (bug - variant), and the median token edit sizes of both.
  * lexical_tracking: Spearman correlation, over all (fix, other) pairs, between each
    embedder's distance and the TF-IDF token-cosine distance, separately for bug
    pairs and variant pairs; and within-problem Spearman (mean over problems with
    >= 4 pairs) of embedder vs TF-IDF distance.
  * bug_vs_variant_given_lexical: logistic-free check -- among pairs with similar
    TF-IDF distance (problem fixed effects), does is_bug add anything?
    OLS d_emb ~ d_tfidf + log1p(edit_tokens) + is_bug + C(problem), clustered SE.

Usage:  python code/review/analyze_kinds.py
Output: results/review/control_kinds.json
"""

import json
import os
import sys
import warnings

import numpy as np
import pandas as pd
import statsmodels.formula.api as smf
from scipy.stats import spearmanr, wilcoxon

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(os.path.dirname(SCRIPT_DIR), "rq5"))
from common import EMBEDDERS, PROJECT_ROOT  # noqa: E402

OUT = os.path.join(PROJECT_ROOT, "results/review")
LANGS = ["python", "js", "java", "classeval"]
KINDS = ["rename1", "renameall", "cmpswap", "stmtswap", "for2while"]


def r6(x):
    return None if x is None or (isinstance(x, float) and np.isnan(x)) else round(float(x), 6)


def kind_paired(pairs, rep):
    col = f"d_{rep}"
    bug = pairs[pairs.kind == "bug"].set_index(["lang", "problem"])
    out = {}
    for kind in KINDS:
        g = pairs[pairs.kind == kind].groupby(["lang", "problem"]).agg(d=(col, "mean"), tok=("edit_tokens", "mean"))
        common = g.index.intersection(bug.index)
        if len(common) == 0:
            continue
        db, dv = bug.loc[common, col].values, g.loc[common, "d"].values
        diff = db - dv
        res = {"n": int(len(common)), "mean_d_bug": r6(db.mean()), "mean_d_variant": r6(dv.mean()),
               "share_bug_more": r6((db > dv).mean()),
               "cohens_dz": r6(diff.mean() / diff.std(ddof=1)) if len(diff) > 1 and diff.std(ddof=1) > 0 else None,
               "median_tokens_bug": r6(np.median(bug.loc[common, "edit_tokens"].values)),
               "median_tokens_variant": r6(np.median(g.loc[common, "tok"].values))}
        if len(diff) >= 10 and np.any(diff != 0):
            res["wilcoxon_p"] = float(wilcoxon(db, dv).pvalue)
        out[kind] = res
    return out


def lexical_tracking(pairs, rep):
    col = f"d_{rep}"
    out = {}
    for name, sub in (("bug_pairs", pairs[pairs.kind == "bug"]), ("variant_pairs", pairs[pairs.kind != "bug"]),
                      ("all_pairs", pairs)):
        out[f"spearman_{name}"] = r6(spearmanr(sub[col], sub["d_tfidf"]).statistic)
    w = []
    for _, g in pairs.groupby(["lang", "problem"]):
        if len(g) >= 4 and g[col].nunique() > 1 and g["d_tfidf"].nunique() > 1:
            w.append(spearmanr(g[col], g["d_tfidf"]).statistic)
    out["within_problem_spearman_mean"] = r6(np.nanmean(w))
    out["within_problem_n"] = len(w)
    return out


def given_lexical(pairs, rep):
    col = f"d_{rep}"
    df = pairs.assign(pid=pairs.lang + "_" + pairs.problem.astype(str), log_edit=np.log1p(pairs.edit_tokens),
                      d=pairs[col])
    with warnings.catch_warnings():
        warnings.simplefilter("ignore")
        fit = smf.ols("d ~ d_tfidf + log_edit + is_bug + C(pid)", data=df).fit(
            cov_type="cluster", cov_kwds={"groups": pd.factorize(df.pid)[0]})
    lo, hi = fit.conf_int().loc["is_bug"]
    mv = df[df.is_bug == 0].d.mean()
    return {"coef_is_bug": r6(fit.params["is_bug"]), "ci95": [r6(lo), r6(hi)], "p_is_bug": float(fit.pvalues["is_bug"]),
            "coef_d_tfidf": r6(fit.params["d_tfidf"]), "p_d_tfidf": float(fit.pvalues["d_tfidf"]),
            "coef_is_bug_relative_to_mean_variant_d": r6(fit.params["is_bug"] / mv)}


def main():
    pairs = pd.read_parquet(os.path.join(OUT, "pairs.parquet"))
    res = {}
    for rep in EMBEDDERS + ["tfidf"]:
        res[rep] = {}
        for lang in LANGS + ["pooled"]:
            sub = pairs if lang == "pooled" else pairs[pairs.lang == lang]
            res[rep][lang] = {"kind_paired": kind_paired(sub, rep)}
            if rep != "tfidf":
                res[rep][lang]["lexical_tracking"] = lexical_tracking(sub, rep)
                res[rep][lang]["regression_given_tfidf"] = given_lexical(sub, rep)
    with open(os.path.join(OUT, "control_kinds.json"), "w") as fh:
        json.dump(res, fh, indent=2)
    for rep in EMBEDDERS + ["tfidf"]:
        kp = res[rep]["pooled"]["kind_paired"]
        print(rep, {k: (v["share_bug_more"], v["cohens_dz"]) for k, v in kp.items()},
              res[rep]["pooled"].get("lexical_tracking"), res[rep]["pooled"].get("regression_given_tfidf"))


if __name__ == "__main__":
    main()

"""
Review W1: does edit size explain the RQ4 bug-category effect (syntax pairs farthest apart)?

For every RQ4 buggy/fixed pair (100 bugs x 5 languages) the token diff between the
buggy and the fixed code is measured with difflib.SequenceMatcher (autojunk off) on
the identifier-aware token sequences:
  diff_tokens   = insertions + deletions (a replace of k by l tokens counts k + l)
  diff_relative = diff_tokens / (len_buggy + len_fixed)            (in [0, 1])
Two tokenizations: 'alnum' (identifiers/numbers only; punctuation dropped, as the
task specifies) and 'punct' (punctuation/operator characters kept as tokens; needed
because many syntax bugs only touch punctuation).

Per embedder, OLS of the cosine pair distance:
  M0: dist ~ C(category) + C(language)
  M1: dist ~ log(1 + diff_tokens) + C(category) + C(language)
(category reference = logic), with standard and bug-clustered (cluster = bug_index) SEs.
Also a binary version (is_syntax instead of C(category)), and the partial
correlation of distance with log(1 + diff_tokens) given category and language
(Pearson and Spearman on residuals).

The same is repeated on HumanEvalFix (164 problems x 6 languages; categories =
HumanEvalFix bug_type; no syntax category exists there) for the six RQ5 embedders,
with the joint F/Wald test of bug_type with and without the diff control.

Output: results/review/edit_size_control.json
"""

import difflib
import json
import os
import sys

import numpy as np
import pandas as pd
import statsmodels.formula.api as smf
from scipy import stats

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from common import (CATEGORY, EMBEDDERS, OUT_DIR, ROOT, RQ4Pairs, RQ5_EMBEDDERS, TOKENIZERS, cosine_matrix,
                    load_rq5_vectors, r3, rq5_docs)


def diff_size(a: list, b: list) -> int:
    sm = difflib.SequenceMatcher(None, a, b, autojunk=False)
    return sum((i2 - i1) + (j2 - j1) for op, i1, i2, j1, j2 in sm.get_opcodes() if op != "equal")


def diff_columns(codes_a, codes_b) -> dict:
    out = {}
    for t, tok in TOKENIZERS.items():
        ta, tb = [tok(c) for c in codes_a], [tok(c) for c in codes_b]
        d = np.array([diff_size(x, y) for x, y in zip(ta, tb)])
        out[f"diff_{t}"] = d
        out[f"rel_{t}"] = d / np.array([len(x) + len(y) for x, y in zip(ta, tb)])
        out[f"len_{t}"] = np.array([(len(x) + len(y)) / 2 for x, y in zip(ta, tb)])
    return out


def coef(fit, name):
    return {"coef": float(fit.params[name]), "se": float(fit.bse[name]), "p": float(fit.pvalues[name])}


def fit_models(data: pd.DataFrame, cat_term: str, cluster: str, cat_names: list, diff_col: str) -> dict:
    """OLS with and without the log diff control; returns the category coefficients."""
    data = data.assign(logdiff=np.log1p(data[diff_col]))
    out = {}
    for label, rhs in (("M0_no_diff", f"{cat_term} + C(language)"),
                       ("M1_with_logdiff", f"logdiff + {cat_term} + C(language)")):
        f = f"dist ~ {rhs}"
        ols = smf.ols(f, data).fit()
        cl = smf.ols(f, data).fit(cov_type="cluster", cov_kwds={"groups": data[cluster]})
        res = {"r2": float(ols.rsquared), "n": int(ols.nobs)}
        for n in cat_names + (["logdiff"] if label.startswith("M1") else []):
            res[n] = {"ols": coef(ols, n), "clustered": coef(cl, n)}
        # joint test of all category terms
        terms = [p for p in ols.params.index if p.startswith(cat_term.split(",")[0].replace(")", "")) or p.startswith("is_syntax")]
        if terms:
            R = np.zeros((len(terms), len(ols.params)))
            for i, t in enumerate(terms):
                R[i, list(ols.params.index).index(t)] = 1
            res["category_joint_F_p_ols"] = float(ols.f_test(R).pvalue)
            res["category_joint_wald_p_clustered"] = float(cl.wald_test(R, scalar=True).pvalue)
        out[label] = res
    return out


def partial_corr(data: pd.DataFrame, cat_term: str, diff_col: str) -> dict:
    data = data.assign(logdiff=np.log1p(data[diff_col]))
    rd = smf.ols(f"dist ~ {cat_term} + C(language)", data).fit().resid
    rx = smf.ols(f"logdiff ~ {cat_term} + C(language)", data).fit().resid
    pr, pp = stats.pearsonr(rd, rx)
    sr, sp = stats.spearmanr(rd, rx)
    return {"pearson_r": float(pr), "pearson_p": float(pp), "spearman_rho": float(sr), "spearman_p": float(sp)}


def rq4():
    base = pd.read_parquet(os.path.join(ROOT, "results/RQ4/octen/rq4_embeddings.parquet")).reset_index(drop=True)
    P = RQ4Pairs(base)
    rows = []
    for l in P.languages:
        for k in range(len(P.b[l])):
            b, f = P.b[l][k], P.f[l][k]
            rows.append({"language": l, "bug_index": int(base.at[b, "bug_index"]), "category": P.cat[l][k],
                         "code_b": base.at[b, "code"], "code_f": base.at[f, "code"]})
    pairs = pd.DataFrame(rows)
    for k, v in diff_columns(pairs["code_b"], pairs["code_f"]).items():
        pairs[k] = v
    pairs["is_syntax"] = (pairs["category"] == "syntax").astype(int)

    desc = {}
    for c in CATEGORY.values():
        g = pairs[pairs["category"] == c]
        desc[c] = {"n": int(len(g)),
                   **{f"mean_{k}": float(g[k].mean()) for k in ("diff_alnum", "diff_punct", "rel_alnum", "rel_punct",
                                                                 "len_alnum", "len_punct")},
                   **{f"median_{k}": float(g[k].median()) for k in ("diff_alnum", "diff_punct")},
                   "share_zero_alnum_diff": float((g["diff_alnum"] == 0).mean())}
    kw = {k: float(stats.kruskal(*[pairs.loc[pairs["category"] == c, k] for c in CATEGORY.values()]).pvalue)
          for k in ("diff_alnum", "diff_punct", "rel_alnum", "rel_punct")}

    cat_term = "C(category, Treatment('logic'))"
    cat_names = [f"{cat_term}[T.{c}]" for c in ("numeric", "state", "syntax")]
    models = {}
    for m in EMBEDDERS:
        df = pd.read_parquet(os.path.join(ROOT, f"results/RQ4/{m}/rq4_embeddings.parquet")).reset_index(drop=True)
        assert (df["code"].values == base["code"].values).all()
        D = cosine_matrix(np.vstack(df["embedding"].values))
        data = pairs.assign(dist=np.concatenate([D[P.b[l], P.f[l]] for l in P.languages]))
        r = {"spearman_dist_vs_diff_punct": float(stats.spearmanr(data["dist"], data["diff_punct"])[0]),
             "spearman_dist_vs_diff_alnum": float(stats.spearmanr(data["dist"], data["diff_alnum"])[0])}
        for dc in ("diff_punct", "diff_alnum"):
            r[dc] = {"category_models": fit_models(data, cat_term, "bug_index", cat_names, dc),
                     "binary_models": fit_models(data, "is_syntax", "bug_index", ["is_syntax"], dc),
                     "partial_corr_given_category_language": partial_corr(data, cat_term, dc)}
        # relative diff as an extra control (log diff + relative diff)
        d2 = data.assign(logdiff=np.log1p(data["diff_punct"]))
        f = f"dist ~ logdiff + rel_punct + {cat_term} + C(language)"
        cl = smf.ols(f, d2).fit(cov_type="cluster", cov_kwds={"groups": d2["bug_index"]})
        r["M2_logdiff_plus_rel_punct_clustered"] = {"syntax": coef(cl, cat_names[2]), "logdiff": coef(cl, "logdiff"),
                                                    "rel_punct": coef(cl, "rel_punct")}
        models[m] = r
        print("rq4", m, flush=True)
    return {"diff_by_category": desc, "kruskal_p_diff_by_category": kw, "models": models}


def humanevalfix():
    he, _ = rq5_docs()
    rows = []
    for l, docs in he.items():
        code = dict(zip(docs["doc_id"], docs["code"]))
        bt = dict(zip(docs["problem"], docs["bug_type"]))
        for p in sorted(docs["problem"].unique()):
            rows.append({"language": l, "problem": int(p), "bug_type": bt[p],
                         "code_c": code[f"{p}_correct"], "code_b": code[f"{p}_buggy"]})
    pairs = pd.DataFrame(rows)
    for k, v in diff_columns(pairs["code_b"], pairs["code_c"]).items():
        pairs[k] = v
    desc = {bt: {"n": int(len(g)), "mean_diff_alnum": float(g["diff_alnum"].mean()),
                 "mean_diff_punct": float(g["diff_punct"].mean()), "mean_rel_punct": float(g["rel_punct"].mean())}
            for bt, g in pairs.groupby("bug_type")}
    cat_term = "C(bug_type, Treatment('missing logic'))"
    cat_names = [f"{cat_term}[T.{b}]" for b in sorted(pairs["bug_type"].unique()) if b != "missing logic"]
    models = {}
    for m in RQ5_EMBEDDERS:
        v = load_rq5_vectors(m)
        dist = []
        for r in pairs.itertuples():
            a, b = v[f"{r.language}|d|{r.problem}_correct"], v[f"{r.language}|d|{r.problem}_buggy"]
            dist.append(1 - float(a @ b) / (np.linalg.norm(a) * np.linalg.norm(b)))
        data = pairs.assign(dist=np.array(dist))
        fm = fit_models(data, cat_term, "problem", cat_names, "diff_punct")
        models[m] = {"spearman_dist_vs_diff_punct": float(stats.spearmanr(data["dist"], data["diff_punct"])[0]),
                     "logdiff_coef_clustered": fm["M1_with_logdiff"]["logdiff"]["clustered"],
                     "bug_type_joint_p_clustered_without_diff": fm["M0_no_diff"].get("category_joint_wald_p_clustered"),
                     "bug_type_joint_p_clustered_with_diff": fm["M1_with_logdiff"].get("category_joint_wald_p_clustered"),
                     "r2_without_diff": fm["M0_no_diff"]["r2"], "r2_with_diff": fm["M1_with_logdiff"]["r2"],
                     "partial_corr_given_bugtype_language": partial_corr(data, cat_term, "diff_punct"),
                     "category_models": fm}
        print("hef", m, flush=True)
    return {"diff_by_bug_type": desc, "models": models}


def main():
    res = {"rq4": rq4(), "humanevalfix": humanevalfix()}
    os.makedirs(OUT_DIR, exist_ok=True)
    path = os.path.join(OUT_DIR, "edit_size_control.json")
    with open(path, "w", encoding="utf-8") as f:
        json.dump(r3(res), f, indent=2)
    print(json.dumps(r3(res["rq4"]["diff_by_category"]), indent=1))
    syn = "C(category, Treatment('logic'))[T.syntax]"
    for m, r in res["rq4"]["models"].items():
        for dc in ("diff_punct", "diff_alnum"):
            m0 = r[dc]["category_models"]["M0_no_diff"][syn]["clustered"]
            m1 = r[dc]["category_models"]["M1_with_logdiff"][syn]["clustered"]
            ld = r[dc]["category_models"]["M1_with_logdiff"]["logdiff"]["clustered"]
            pc = r[dc]["partial_corr_given_category_language"]
            print(f"{m:10s} {dc:10s} syn M0 {m0['coef']:.4f} p={m0['p']:.2g} | M1 {m1['coef']:.4f} p={m1['p']:.2g} "
                  f"| logdiff {ld['coef']:.4f} p={ld['p']:.2g} | partial r={pc['pearson_r']:.3f} rho={pc['spearman_rho']:.3f}")


if __name__ == "__main__":
    main()

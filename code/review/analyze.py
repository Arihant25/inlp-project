"""
Review control, step 3: does a behaviour-changing edit (bug) move an embedding more
than a behaviour-preserving edit (validated variant) of the same size?

For every problem p (HumanEvalFix Python/JS/Java, ClassEval) and every representation
(six embedders + a lexical TF-IDF token cosine) we compute
    d(fix, bug)      cosine distance between the correct solution and its buggy twin
    d(fix, variant)  cosine distance to each validated semantics-preserving variant
and the edit size of every pair: changed tokens (difflib opcodes on an
identifier-aware token sequence, insertions + deletions) and changed characters
(same on characters).

Analyses (per representation x language, and pooled over languages):
  * by_kind:   mean / median of problem-level d(fix,bug) and d(fix,variant) per kind,
               and the mean edit sizes
  * matched:   per problem, the variant whose token edit size is closest to the bug's
               (ties: closest char size, then kind order); paired Wilcoxon signed-rank
               test, Cohen's dz, share of problems where the bug moves the embedding
               LESS than the matched variant, plus the size-difference distribution.
               Also a "tight" subset with |size difference| <= 2 tokens and an "exact"
               subset with identical token edit size (most bugs replace ONE token = 2
               edits, while no preserving edit is smaller than 4, so the unrestricted
               match is biased toward larger variant edits).
  * regression: d ~ log(1 + edit_tokens) + is_bug + C(problem)  (OLS, problem fixed
               effects, SE clustered by problem); coefficient of is_bug, its 95% CI,
               and relative to the mean variant distance; repeated on pairs with
               <= 8 changed tokens (the size range where bugs and variants overlap).
  * retrieval: share of problems where the buggy twin is closer to the fix than the
               RENAME-1 / RENAME-ALL variant / every variant (averaged over a
               problem's rename-1 variants).

Usage:  python code/review/analyze.py
Output: results/review/control_results.json, results/review/pairs.parquet
"""

import difflib
import json
import os
import re
import sys
import warnings

import numpy as np
import pandas as pd
import statsmodels.formula.api as smf
from scipy.stats import wilcoxon
from sklearn.feature_extraction.text import TfidfVectorizer

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(os.path.dirname(SCRIPT_DIR), "rq5"))
from common import EMBEDDERS, PROJECT_ROOT, build_corpus  # noqa: E402

OUT = os.path.join(PROJECT_ROOT, "results/review")
LANGS = ["python", "js", "java", "classeval"]
KINDS = ["rename1", "renameall", "cmpswap", "stmtswap", "for2while"]
REPRS = EMBEDDERS + ["tfidf"]

TOKEN_RE = re.compile(r'''"""[\s\S]*?"""|\'\'\'[\s\S]*?\'\'\'|"(?:\\.|[^"\\\n])*"|'(?:\\.|[^'\\\n])*'|`[^`]*`|
    [A-Za-z_$][\w$]*|\d[\w.]*|>>>=|===|!==|>>>|<<=|>>=|\*\*=|//=|\.\.\.|=>|->|==|!=|<=|>=|&&|\|\||\+\+|--|
    \+=|-=|\*=|/=|%=|&=|\|=|\^=|<<|>>|::|\*\*|//|\S''', re.X)


def tokens(code: str) -> list[str]:
    """Identifier-aware tokens: identifiers, numbers, strings and operators are single tokens."""
    return TOKEN_RE.findall(code)


def edit_size(a, b) -> int:
    """Insertions + deletions in difflib's alignment of sequences a and b."""
    n = 0
    for tag, i1, i2, j1, j2 in difflib.SequenceMatcher(None, a, b, autojunk=False).get_opcodes():
        if tag != "equal":
            n += (i2 - i1) + (j2 - j1)
    return n


def load_texts() -> pd.DataFrame:
    var = pd.read_parquet(os.path.join(OUT, "variants.parquet"))
    var = var[var.passed]
    rows = []
    for lang in LANGS:
        docs, _ = build_corpus(lang)
        for r in docs.itertuples():
            t = "fix" if r.variant == "correct" else "bug"
            rows.append({"lang": lang, "problem": r.problem, "kind": t, "idx": 0, "code": r.code,
                         "key": f"{lang}|{r.problem}|{t}"})
        for r in var[var.lang == lang].itertuples():
            rows.append({"lang": lang, "problem": r.problem, "kind": r.kind, "idx": r.idx, "code": r.code,
                         "key": f"{lang}|{r.problem}|{r.kind}|{r.idx}"})
    return pd.DataFrame(rows)


def build_pairs(texts: pd.DataFrame) -> pd.DataFrame:
    """One row per (fix, other) pair with edit sizes and distances for every representation."""
    fix = texts[texts.kind == "fix"].set_index(["lang", "problem"])["code"]
    others = texts[texts.kind != "fix"].copy()
    tok_cache = {}

    def tk(s):
        if s not in tok_cache:
            tok_cache[s] = tokens(s)
        return tok_cache[s]

    others["fix_key"] = others["lang"] + "|" + others["problem"].astype(str) + "|fix"
    others["is_bug"] = (others.kind == "bug").astype(int)
    others["edit_tokens"] = [edit_size(tk(fix[(l, p)]), tk(c)) for l, p, c in
                             zip(others.lang, others.problem, others.code)]
    others["edit_chars"] = [edit_size(fix[(l, p)], c) for l, p, c in zip(others.lang, others.problem, others.code)]
    for m in EMBEDDERS:
        z = np.load(os.path.join(OUT, "embeddings", f"{m}.npz"))
        vec = dict(zip(z["keys"], z["vectors"].astype(np.float64)))
        d = []
        for fk, k in zip(others.fix_key, others.key):
            a, b = vec[fk], vec[k]
            d.append(1.0 - float(a @ b / (np.linalg.norm(a) * np.linalg.norm(b))))
        others[f"d_{m}"] = d
    # Lexical baseline: TF-IDF (sublinear tf, l2) over identifier-aware tokens, fitted per language
    # on all fix / bug / variant texts, cosine distance.
    d = pd.Series(index=others.index, dtype=float)
    for lang in LANGS:
        sub = texts[texts.lang == lang]
        vz = TfidfVectorizer(tokenizer=tokens, lowercase=False, token_pattern=None, sublinear_tf=True)
        X = vz.fit_transform(sub.code)
        row = dict(zip(sub.key, range(len(sub))))
        o = others[others.lang == lang]
        a = X[[row[k] for k in o.fix_key]]
        b = X[[row[k] for k in o.key]]
        d.loc[o.index] = 1.0 - np.asarray(a.multiply(b).sum(1)).ravel()
    others["d_tfidf"] = d.clip(lower=0.0)
    return others.drop(columns=["code"])


def r3(x):
    # 6 decimals in the JSON: CodeBERT distances are O(1e-4); the report rounds to 3 decimals
    # (distances in units of 1e-3 where needed).
    return None if x is None or (isinstance(x, float) and np.isnan(x)) else round(float(x), 6)


def by_kind(pairs: pd.DataFrame, rep: str) -> dict:
    col = f"d_{rep}"
    out = {}
    for kind in ["bug"] + KINDS:
        g = pairs[pairs.kind == kind]
        if g.empty:
            continue
        pl = g.groupby(["lang", "problem"]).agg(d=(col, "mean"), tok=("edit_tokens", "mean"),
                                                ch=("edit_chars", "mean"))
        out[kind] = {"n_problems": int(len(pl)), "n_variants": int(len(g)), "mean_d": r3(pl.d.mean()),
                     "median_d": r3(pl.d.median()), "mean_edit_tokens": r3(pl.tok.mean()),
                     "median_edit_tokens": r3(pl.tok.median()), "mean_edit_chars": r3(pl.ch.mean())}
    # same-problem contrast: problems having both the bug and this kind
    bug = pairs[pairs.kind == "bug"].set_index(["lang", "problem"])[col]
    for kind in KINDS:
        g = pairs[pairs.kind == kind].groupby(["lang", "problem"])[col].mean()
        common = g.index.intersection(bug.index)
        if len(common) and kind in out:
            out[kind]["paired_mean_d_bug"] = r3(bug[common].mean())
            out[kind]["paired_share_bug_gt_variant"] = r3((bug[common] > g[common]).mean())
    return out


def paired_stats(db: np.ndarray, dv: np.ndarray) -> dict:
    diff = db - dv
    res = {"n": int(len(diff)), "mean_d_bug": r3(db.mean()), "mean_d_variant": r3(dv.mean()),
           "median_d_bug": r3(np.median(db)), "median_d_variant": r3(np.median(dv)),
           "mean_diff": r3(diff.mean()), "median_diff": r3(np.median(diff)),
           "cohens_dz": r3(diff.mean() / diff.std(ddof=1)) if len(diff) > 1 and diff.std(ddof=1) > 0 else None,
           "share_bug_less_than_variant": r3((db < dv).mean()),
           "ratio_median_bug_over_variant": r3(np.median(db) / np.median(dv)) if np.median(dv) > 0 else None}
    if len(diff) >= 10 and np.any(diff != 0):
        w = wilcoxon(db, dv, zero_method="wilcox", alternative="two-sided")
        res["wilcoxon_W"] = float(w.statistic)
        res["wilcoxon_p"] = float(w.pvalue)
    return res


def matched(pairs: pd.DataFrame, rep: str, tight: int | None = None) -> dict:
    """tight=None: closest variant for every problem; tight=k: keep problems with |size diff| <= k
    (tight=0: exact token-edit-size match)."""
    col = f"d_{rep}"
    rows = []
    for (lang, p), g in pairs.groupby(["lang", "problem"]):
        b = g[g.kind == "bug"]
        v = g[g.kind != "bug"]
        if b.empty or v.empty:
            continue
        b = b.iloc[0]
        v = v.assign(dt=(v.edit_tokens - b.edit_tokens).abs(), dc=(v.edit_chars - b.edit_chars).abs(),
                     ko=v.kind.map(KINDS.index))
        best = v.sort_values(["dt", "dc", "ko", "idx"]).iloc[0]
        rows.append({"db": b[col], "dv": best[col], "size_diff": int(best.edit_tokens - b.edit_tokens),
                     "bug_tok": int(b.edit_tokens), "var_tok": int(best.edit_tokens), "kind": best.kind})
    m = pd.DataFrame(rows)
    if tight is not None:
        m = m[m.size_diff.abs() <= tight]
    if m.empty:
        return {"n": 0}
    res = paired_stats(m.db.values, m.dv.values)
    sd = m.size_diff
    res["size_diff"] = {"mean_abs": r3(sd.abs().mean()), "median": r3(sd.median()),
                        "q10": r3(sd.quantile(.1)), "q90": r3(sd.quantile(.9)),
                        "share_exact": r3((sd == 0).mean()), "share_within_2": r3((sd.abs() <= 2).mean()),
                        "median_bug_tokens": r3(m.bug_tok.median()), "median_variant_tokens": r3(m.var_tok.median())}
    res["matched_kind_counts"] = m.kind.value_counts().to_dict()
    return res


def regression(pairs: pd.DataFrame, rep: str, max_tokens: int | None = None) -> dict:
    """max_tokens: restrict to pairs with edit_tokens <= max_tokens (local size range, robustness)."""
    col = f"d_{rep}"
    df = pairs[["lang", "problem", "is_bug", "edit_tokens", col]].rename(columns={col: "d"}).copy()
    if max_tokens is not None:
        df = df[df.edit_tokens <= max_tokens]
    df["pid"] = df.lang + "_" + df.problem.astype(str)
    # keep problems that have the bug and at least one variant
    has = df.groupby("pid").is_bug.agg(["sum", "count"])
    keep = has[(has["sum"] == 1) & (has["count"] >= 2)].index
    df = df[df.pid.isin(keep)]
    df["log_edit"] = np.log1p(df.edit_tokens)
    with warnings.catch_warnings():
        warnings.simplefilter("ignore")
        fit = smf.ols("d ~ log_edit + is_bug + C(pid)", data=df).fit(
            cov_type="cluster", cov_kwds={"groups": pd.factorize(df.pid)[0]})
    lo, hi = fit.conf_int().loc["is_bug"]
    mv = df[df.is_bug == 0].d.mean()
    return {"n_obs": int(len(df)), "n_problems": int(df.pid.nunique()),
            "coef_is_bug": r3(fit.params["is_bug"]), "ci95": [r3(lo), r3(hi)],
            "p_is_bug": float(fit.pvalues["is_bug"]), "coef_log_edit": r3(fit.params["log_edit"]),
            "p_log_edit": float(fit.pvalues["log_edit"]), "mean_d_variants": r3(mv),
            "coef_is_bug_relative_to_mean_variant_d": r3(fit.params["is_bug"] / mv) if mv > 0 else None}


def retrieval(pairs: pd.DataFrame, rep: str) -> dict:
    col = f"d_{rep}"
    out = {}
    bug = pairs[pairs.kind == "bug"].set_index(["lang", "problem"])[col]
    for kind in ["rename1", "renameall"]:
        g = pairs[pairs.kind == kind]
        if g.empty:
            continue
        g = g.assign(db=[bug[(l, p)] for l, p in zip(g.lang, g.problem)])
        pl = g.groupby(["lang", "problem"]).apply(lambda x: (x.db < x[col]).mean(), include_groups=False)
        out[f"share_bug_closer_than_{kind}"] = r3(pl.mean())
        out[f"n_{kind}"] = int(len(pl))
    v = pairs[pairs.kind != "bug"].groupby(["lang", "problem"])[col].min()
    common = v.index.intersection(bug.index)
    out["share_bug_closer_than_every_variant"] = r3((bug[common] < v[common]).mean())
    vmax = pairs[pairs.kind != "bug"].groupby(["lang", "problem"])[col].max()
    out["share_bug_farther_than_every_variant"] = r3((bug[common] > vmax[common]).mean())
    out["n_problems"] = int(len(common))
    return out


def main():
    texts = load_texts()
    pairs = build_pairs(texts)
    pairs.to_parquet(os.path.join(OUT, "pairs.parquet"))
    results = {"counts": {}, "results": {}}
    for lang in LANGS + ["pooled"]:
        sub = pairs if lang == "pooled" else pairs[pairs.lang == lang]
        results["counts"][lang] = {"problems_with_bug": int((sub.kind == "bug").sum()),
                                   "valid_variants": sub[sub.kind != "bug"].kind.value_counts().to_dict()}
        for rep in REPRS:
            results["results"].setdefault(rep, {})[lang] = {
                "by_kind": by_kind(sub, rep),
                "matched": matched(sub, rep),
                "matched_tight_le2": matched(sub, rep, tight=2),
                "matched_exact": matched(sub, rep, tight=0),
                "regression": regression(sub, rep),
                "regression_le8_tokens": regression(sub, rep, max_tokens=8),
                "retrieval": retrieval(sub, rep),
            }
        print("done", lang)
    with open(os.path.join(OUT, "control_results.json"), "w") as fh:
        json.dump(results, fh, indent=2)
    # compact console summary
    for rep in REPRS:
        for lang in LANGS + ["pooled"]:
            r = results["results"][rep][lang]
            mt, rg = r["matched"], r["regression"]
            print(f"{rep:9s} {lang:9s} matched n={mt['n']:3d} d_bug={mt['mean_d_bug']} d_var={mt['mean_d_variant']} "
                  f"dz={mt['cohens_dz']} p={mt.get('wilcoxon_p', float('nan')):.2g} "
                  f"bug<var={mt['share_bug_less_than_variant']} | reg is_bug={rg['coef_is_bug']} "
                  f"rel={rg['coef_is_bug_relative_to_mean_variant_d']} p={rg['p_is_bug']:.2g}")


if __name__ == "__main__":
    main()

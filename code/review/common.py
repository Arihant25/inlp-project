"""
Shared helpers for the reviewer-requested control analyses (code/review/*).

Provides
  - identifier-aware tokenizers (alnum-only, as in the RQ5 BM25 tokenizer, and a
    variant that also keeps every punctuation/operator character as a token),
  - pairwise distance matrices: embedding cosine, TF-IDF cosine, normalized
    token Levenshtein (rapidfuzz on token sequences encoded as code points;
    distance / max(len_a, len_b)),
  - re-implementations of the paper's metrics on a precomputed distance matrix
    that reproduce the pair selection (including the seeded subsampling) of
    code/rq2/2_analysis.py, code/rq3/2_analysis.py, code/RQ4/2_analysis.py and
    code/RQ4/5_relative_distance.py exactly.

All functions take D as an (n, n) numpy array aligned with the positional rows of
the dataframe they receive.
"""

import os
import re
from itertools import combinations

import numpy as np
import pandas as pd
from scipy import stats
from sklearn.feature_extraction.text import TfidfVectorizer
from sklearn.metrics import silhouette_score

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
OUT_DIR = os.path.join(ROOT, "results", "review")
EMBEDDERS = ["octen", "qwen3", "bge_m3", "minilm", "ada002", "unixcoder", "codebert"]
RQ5_EMBEDDERS = ["octen", "bge_m3", "unixcoder", "codebert", "qwen3", "minilm"]
CATEGORY = {"Easy": "syntax", "Medium": "logic", "Hard": "state", "Super Hard": "numeric"}
THRESHOLDS = [0.05, 0.10, 0.15]

# ── Tokenizers ────────────────────────────────────────────────────────────────

_CAMEL = re.compile(r"[A-Z]?[a-z]+|[A-Z]+(?![a-z])|\d+")


def _split_word(w: str) -> list[str]:
    return [p.lower() for p in _CAMEL.findall(w) if p]


def tokenize(text: str) -> list[str]:
    """Identifier-aware tokens: split on non-alphanumerics, camelCase and snake_case; lower-case.
    Identical to the BM25 tokenizer in code/rq5/2_retrieval.py (punctuation is dropped)."""
    out = []
    for w in re.findall(r"[A-Za-z]+|\d+", text):
        out += _split_word(w)
    return out


def tokenize_punct(text: str) -> list[str]:
    """As tokenize(), but every non-whitespace non-alphanumeric character is kept as its own token."""
    out = []
    for w in re.findall(r"[A-Za-z]+|\d+|[^\sA-Za-z\d]", text):
        out += _split_word(w) if w[0].isascii() and w[0].isalnum() else [w]
    return out


TOKENIZERS = {"alnum": tokenize, "punct": tokenize_punct}

# ── Distance matrices ────────────────────────────────────────────────────────


def _clean(D: np.ndarray) -> np.ndarray:
    D = np.clip(D.astype(np.float64), 0.0, None)
    D = (D + D.T) / 2
    np.fill_diagonal(D, 0.0)
    return D


def cosine_matrix(X: np.ndarray) -> np.ndarray:
    X = np.asarray(X, dtype=np.float64)
    X = X / np.linalg.norm(X, axis=1, keepdims=True)
    return _clean(1.0 - X @ X.T)


def tfidf_matrix(codes: list[str], tok) -> np.ndarray:
    """TF-IDF (sklearn defaults: smooth idf, l2 norm) fitted on `codes`; cosine distance."""
    vec = TfidfVectorizer(tokenizer=tok, lowercase=False, token_pattern=None)
    X = vec.fit_transform(codes)
    S = (X @ X.T).toarray()
    return _clean(1.0 - S)


def encode_tokens(token_lists: list[list[str]]) -> list[str]:
    """Map each distinct token to one code point so string Levenshtein = token Levenshtein."""
    vocab: dict[str, int] = {}
    out = []
    for toks in token_lists:
        ids = [vocab.setdefault(t, len(vocab)) for t in toks]
        out.append("".join(chr(i + 0x100 if i + 0x100 < 0xD800 else i + 0x100 + 0x800) for i in ids))
    return out


def edit_matrix(codes: list[str], tok) -> np.ndarray:
    """Normalized token Levenshtein distance (distance / max length)."""
    from rapidfuzz.distance import Levenshtein
    from rapidfuzz.process import cdist
    s = encode_tokens([tok(c) for c in codes])
    D = cdist(s, s, scorer=Levenshtein.normalized_distance, workers=-1, dtype=np.float32)
    return _clean(D)


def baseline_matrices(codes: list[str]) -> dict[str, np.ndarray]:
    """All four lexical baselines: tfidf / edit, each with alnum and punct tokenizers."""
    out = {}
    for tname, tok in TOKENIZERS.items():
        out[f"tfidf_{tname}"] = tfidf_matrix(codes, tok)
        out[f"edit_{tname}"] = edit_matrix(codes, tok)
    return out


# ── Statistics ────────────────────────────────────────────────────────────────


def cohens_d(a, b) -> float:
    a, b = np.asarray(a, float), np.asarray(b, float)
    n1, n2 = len(a), len(b)
    if n1 < 2 or n2 < 2:
        return 0.0
    pooled = np.sqrt(((n1 - 1) * np.var(a, ddof=1) + (n2 - 1) * np.var(b, ddof=1)) / (n1 + n2 - 2))
    return float((a.mean() - b.mean()) / pooled) if pooled > 0 else 0.0


def compare(a, b) -> dict:
    """Means, Welch t-test and Cohen's d of a vs b."""
    a, b = np.asarray(a, float), np.asarray(b, float)
    t, p = stats.ttest_ind(a, b, equal_var=False)
    return {"mean_a": float(a.mean()), "mean_b": float(b.mean()), "diff": float(a.mean() - b.mean()),
            "cohens_d": cohens_d(a, b), "welch_t": float(t), "welch_p": float(p),
            "n_a": int(len(a)), "n_b": int(len(b))}


def silhouette(D: np.ndarray, labels) -> float:
    return float(silhouette_score(D, np.asarray(labels), metric="precomputed"))


# ── Pair selection (reproduces the paper scripts) ─────────────────────────────


def _intra_positions(n: int, max_pairs: int, seed: int = 42):
    """Positions (i<j, row-major) within a group of size n, subsampled as in the paper scripts."""
    if n < 2:
        return np.zeros(0, int), np.zeros(0, int)
    i, j = np.triu_indices(n, 1)
    if len(i) > max_pairs:
        c = np.random.default_rng(seed).choice(len(i), max_pairs, replace=False)
        i, j = i[c], j[c]
    return i, j


def _cross_positions(n1: int, n2: int, max_pairs: int, seed: int = 42):
    if n1 == 0 or n2 == 0:
        return np.zeros(0, int), np.zeros(0, int)
    total = n1 * n2
    flat = np.arange(total)
    if total > max_pairs:
        flat = np.random.default_rng(seed).choice(total, max_pairs, replace=False)
    return np.divmod(flat, n2)


def rq2_pairs(df: pd.DataFrame, fw_labels=None):
    """Cross-/intra-framework pairs: same pattern and language, different/same framework (exhaustive)."""
    fw = np.asarray(fw_labels if fw_labels is not None else (df["language"] + "/" + df["framework"]).values)
    cross, intra = [], []
    for (_, _), g in df.reset_index(drop=True).groupby(["pattern", "language"], sort=True):
        idx = g.index.values
        for a, b in combinations(idx, 2):
            (cross if fw[a] != fw[b] else intra).append((a, b))
    return np.array(cross), np.array(intra)


def rq2_metrics(D: np.ndarray, df: pd.DataFrame) -> dict:
    df = df.reset_index(drop=True)
    qual = (df["language"] + "/" + df["framework"]).values
    cross, intra = rq2_pairs(df)
    c = compare(D[cross[:, 0], cross[:, 1]], D[intra[:, 0], intra[:, 1]])
    return {"framework_silhouette": silhouette(D, qual), "language_silhouette": silhouette(D, df["language"].values),
            "cross_mean": c["mean_a"], "intra_mean": c["mean_b"], "cohens_d": c["cohens_d"],
            "welch_p": c["welch_p"], "n_cross": c["n_a"], "n_intra": c["n_b"]}


class RQ3Pairs:
    """Position-pair templates for the RQ3 cross/intra-complexity distances (code/rq3/2_analysis.py).
    The sampled positions depend only on class sizes per language, which label permutation
    within language preserves, so the same templates serve every permutation."""

    def __init__(self, df_clean: pd.DataFrame):
        self.languages = sorted(df_clean["language"].unique())
        self.classes = sorted(df_clean["complexity_class"].unique())
        self.lang_rows = {l: np.where(df_clean["language"].values == l)[0] for l in self.languages}
        self.templates = {}
        labels = df_clean["complexity_class"].values
        for l in self.languages:
            lab = labels[self.lang_rows[l]]
            n = {cc: int((lab == cc).sum()) for cc in self.classes}
            intra = {cc: _intra_positions(n[cc], 200) for cc in self.classes}
            cross = {(a, b): _cross_positions(n[a], n[b], 200) for a, b in combinations(self.classes, 2)}
            self.templates[l] = (intra, cross)

    def pairs(self, labels: np.ndarray):
        """Global (row, row) index arrays of cross and intra pairs for a labelling of df_clean."""
        ci, cj, ii, ij = [], [], [], []
        for l in self.languages:
            rows = self.lang_rows[l]
            lab = labels[rows]
            members = {cc: rows[lab == cc] for cc in self.classes}
            intra, cross = self.templates[l]
            for cc, (p, q) in intra.items():
                ii.append(members[cc][p]); ij.append(members[cc][q])
            for (a, b), (p, q) in cross.items():
                ci.append(members[a][p]); cj.append(members[b][q])
        cat = lambda x: np.concatenate(x).astype(int)
        return (cat(ci), cat(cj)), (cat(ii), cat(ij))


def rq3_problem_identity_pairs(df_clean: pd.DataFrame, max_pairs: int = 200):
    """Same problem/different class vs different problem/same class (code/rq3/2_analysis.py)."""
    rng = np.random.default_rng(42)
    same, diff = [], []
    for lang in sorted(df_clean["language"].unique()):
        rows = np.where(df_clean["language"].values == lang)[0]
        problems = df_clean["problem_slug"].values[rows]
        classes = df_clean["complexity_class"].values[rows]
        for slug in np.unique(problems):
            idx = np.where(problems == slug)[0]
            same += [(rows[i], rows[j]) for i, j in combinations(idx, 2) if classes[i] != classes[j]]
        for cc in np.unique(classes):
            idx = np.where(classes == cc)[0]
            pairs = [(i, j) for i, j in combinations(idx, 2) if problems[i] != problems[j]]
            if len(pairs) > max_pairs:
                chosen = rng.choice(len(pairs), max_pairs, replace=False)
                pairs = [pairs[c] for c in chosen]
            diff += [(rows[i], rows[j]) for i, j in pairs]
    return np.array(same), np.array(diff)


def rq3_clean(df: pd.DataFrame) -> np.ndarray:
    """Boolean mask of the rows used by the RQ3 analysis (complexity class != Other)."""
    return (df["complexity_class"] != "Other").values


def rq3_metrics(D: np.ndarray, df_clean: pd.DataFrame, templates: RQ3Pairs | None = None,
                identity_pairs=None) -> dict:
    df_clean = df_clean.reset_index(drop=True)
    templates = templates or RQ3Pairs(df_clean)
    (ci, cj), (ii, ij) = templates.pairs(df_clean["complexity_class"].values)
    c = compare(D[ci, cj], D[ii, ij])
    same, diff = identity_pairs if identity_pairs is not None else rq3_problem_identity_pairs(df_clean)
    pid = compare(D[diff[:, 0], diff[:, 1]], D[same[:, 0], same[:, 1]])
    return {"complexity_silhouette": silhouette(D, df_clean["complexity_class"].values),
            "cross_mean": c["mean_a"], "intra_mean": c["mean_b"], "cohens_d": c["cohens_d"], "welch_p": c["welch_p"],
            "problem_identity": {"same_problem_diff_complexity_mean": pid["mean_b"],
                                 "diff_problem_same_complexity_mean": pid["mean_a"],
                                 "cohens_d": pid["cohens_d"], "welch_p": pid["welch_p"],
                                 "n_same": pid["n_b"], "n_diff": pid["n_a"]}}


class RQ4Pairs:
    """Buggy/fixed indexing per language for RQ4 (matched pairs, exhaustive unmatched, cluster pairs)."""

    def __init__(self, df: pd.DataFrame):
        df = df.reset_index(drop=True)
        self.df = df
        self.languages = sorted(df["language"].unique())
        self.lang_rows = {l: np.where(df["language"].values == l)[0] for l in self.languages}
        self.b, self.f, self.cat = {}, {}, {}
        for l in self.languages:
            g = df.iloc[self.lang_rows[l]]
            b = g[g["code_type"] == "buggy"].sort_values("bug_index")
            f = g[g["code_type"] == "fixed"].sort_values("bug_index")
            assert list(b["bug_index"]) == list(f["bug_index"])
            self.b[l], self.f[l] = b.index.values, f.index.values
            self.cat[l] = np.array([CATEGORY[s] for s in b["severity"]])
        # cluster-distance templates (code/RQ4/2_analysis.py: 300 intra, 500 cross, seed 42)
        n = len(self.b[self.languages[0]])
        self.t_intra = _intra_positions(n, 300)
        self.t_cross = _cross_positions(n, n, 500)

    def cluster_pairs(self, labels: np.ndarray):
        """Cross (buggy-fixed) and intra (buggy-buggy + fixed-fixed) pairs for a labelling."""
        ci, cj, ii, ij = [], [], [], []
        p, q = self.t_intra
        cp, cq = self.t_cross
        for l in self.languages:
            rows = self.lang_rows[l]
            bm, fm = rows[labels[rows] == "buggy"], rows[labels[rows] == "fixed"]
            ii += [bm[p], fm[p]]; ij += [bm[q], fm[q]]
            ci.append(bm[cp]); cj.append(fm[cq])
        cat = lambda x: np.concatenate(x).astype(int)
        return (cat(ci), cat(cj)), (cat(ii), cat(ij))

    def pair_and_unmatched(self, D: np.ndarray):
        pair, unmatched, cats, langs = [], [], [], []
        for l in self.languages:
            sub = D[np.ix_(self.b[l], self.f[l])]
            pair.append(np.diag(sub))
            unmatched.append(sub[~np.eye(len(sub), dtype=bool)])
            cats.append(self.cat[l]); langs += [l] * len(sub)
        return np.concatenate(pair), np.concatenate(unmatched), np.concatenate(cats), np.array(langs)


def rq4_metrics(D: np.ndarray, df: pd.DataFrame, P: RQ4Pairs | None = None) -> dict:
    df = df.reset_index(drop=True)
    P = P or RQ4Pairs(df)
    pair, unmatched, cats, _ = P.pair_and_unmatched(D)
    um = float(unmatched.mean())
    by_cat = {c: float(pair[cats == c].mean()) for c in CATEGORY.values()}
    (ci, cj), (ii, ij) = P.cluster_pairs(df["code_type"].values)
    cl = compare(D[ci, cj], D[ii, ij])
    return {"correctness_silhouette": silhouette(D, df["code_type"].values),
            "language_silhouette": silhouette(D, df["language"].values),
            "pair_mean": float(pair.mean()), "unmatched_mean": um, "R": float(pair.mean()) / um,
            "inverse_R": um / float(pair.mean()),
            "dangerous_pct": {str(t): float(100 * (pair < t).mean()) for t in THRESHOLDS},
            "R_by_category": {c: v / um for c, v in by_cat.items()},
            "syntax_ratio": by_cat["syntax"] / float(pair[cats != "syntax"].mean()),
            "cluster_cross_mean": cl["mean_a"], "cluster_intra_mean": cl["mean_b"],
            "cluster_cohens_d": cl["cohens_d"], "cluster_welch_p": cl["welch_p"]}


def rq5_proximity(D_by_lang: dict, docs_by_lang: dict) -> dict:
    """R with exhaustive unmatched (correct_a, buggy_b), a != b, within language."""
    pair, unmatched = [], []
    for lang, docs in docs_by_lang.items():
        D = D_by_lang[lang]
        docs = docs.reset_index(drop=True)
        probs = sorted(docs["problem"].unique())
        pos = {d: i for i, d in enumerate(docs["doc_id"])}
        c = np.array([pos[f"{p}_correct"] for p in probs])
        b = np.array([pos[f"{p}_buggy"] for p in probs])
        sub = D[np.ix_(c, b)]
        pair.append(np.diag(sub))
        unmatched.append(sub[~np.eye(len(sub), dtype=bool)])
    pair, unmatched = np.concatenate(pair), np.concatenate(unmatched)
    um = float(unmatched.mean())
    return {"n_pairs": int(len(pair)), "pair_mean": float(pair.mean()), "unmatched_mean": um,
            "R": float(pair.mean()) / um,
            "dangerous_pct": {str(t): float(100 * (pair < t).mean()) for t in THRESHOLDS}}


def load_rq5_vectors(model: str) -> dict:
    out = {}
    for suffix in ("", "_classeval"):
        path = os.path.join(ROOT, "results/rq5/embeddings", f"{model}{suffix}.npz")
        if os.path.exists(path):
            d = np.load(path)
            out.update(zip(d["keys"], d["vectors"]))
    return out


def rq5_docs():
    """HumanEvalFix docs per language and ClassEval docs, from code/rq5/common.build_corpus."""
    import importlib.util
    spec = importlib.util.spec_from_file_location("rq5_common", os.path.join(ROOT, "code", "rq5", "common.py"))
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    he = {l: mod.build_corpus(l)[0] for l in mod.LANGUAGES}
    ce = mod.build_corpus("classeval")[0]
    return he, ce


def r3(x):
    """Recursively round floats to 4 decimals for JSON output (3-decimal reporting + 1 guard digit)."""
    if isinstance(x, dict):
        return {k: r3(v) for k, v in x.items()}
    if isinstance(x, (list, tuple)):
        return [r3(v) for v in x]
    if isinstance(x, (float, np.floating)):
        v = float(x)
        return v if (v != 0 and abs(v) < 1e-3) else round(v, 4)
    if isinstance(x, np.integer):
        return int(x)
    return x

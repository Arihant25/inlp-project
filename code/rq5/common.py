"""
RQ5: Shared helpers for the retrieval-augmented generation experiment on HumanEvalFix.

HumanEvalFix (part of HumanEvalPack, Muennighoff et al., ICLR 2024) pairs each of
164 HumanEval problems with a correct and a human-written buggy solution in six
languages, together with hidden tests and the docstring's example tests.

For every language we build a retrieval corpus of 328 documents
(164 correct + 164 buggy). Following CodeSearchNet, a document is the code
without its docstring (declaration + body) and a query is the natural-language
docstring.
"""

import os

import pandas as pd

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
PROJECT_ROOT = os.path.dirname(os.path.dirname(SCRIPT_DIR))
DATA_DIR = os.path.join(PROJECT_ROOT, "datasets/RQ5/humanevalpack")
RESULTS_DIR = os.path.join(PROJECT_ROOT, "results/rq5")

LANGUAGES = ["python", "js", "java", "go", "cpp", "rust"]
GENERATION_LANGUAGES = ["python", "js", "java", "classeval"]
CLASSEVAL = "classeval"

# The six local embedders from RQ1-RQ4 (Ada-002 is not used in RQ5).
EMBEDDERS = ["octen", "bge_m3", "unixcoder", "codebert", "qwen3", "minilm"]
# Pooling ablation: CLS pooling for the two code models.
CLS_VARIANTS = ["codebert_cls", "unixcoder_cls"]


CLASSEVAL_PAIRS = os.path.join(PROJECT_ROOT, "datasets/RQ5/classeval/classeval_pairs.parquet")


def load_language(lang: str) -> pd.DataFrame:
    """Load one HumanEvalPack language split (or ClassEval), ordered by problem number."""
    if lang == "classeval":
        df = pd.read_parquet(CLASSEVAL_PAIRS).sort_values("problem").reset_index(drop=True)
        df["instruction"] = ("Complete the implementation of the following Python class.\n```python\n"
                             + df["skeleton"].str.strip() + "\n```")
        df["entry_point"] = df["class_name"]
        df["canonical_solution"], df["buggy_solution"] = df["correct"], df["buggy"]
        df["bug_type"] = df["mutation_operator"]
        df["example_test"] = [e if h else None for e, h in zip(df["example_test"], df["has_example_test"])]
        return df
    df = pd.read_parquet(os.path.join(DATA_DIR, f"{lang}.parquet"))
    df["problem"] = df["task_id"].str.split("/").str[1].astype(int)
    return df.sort_values("problem").reset_index(drop=True)


def build_corpus(lang: str) -> tuple[pd.DataFrame, pd.DataFrame]:
    """
    Build the retrieval corpus and the query set for one language.

    Returns:
        docs:    one row per document with columns
                 doc_id, problem, variant ('correct' or 'buggy'), code, bug_type
        queries: one row per problem with columns problem, query
    """
    df = load_language(lang)
    rows = []
    for _, r in df.iterrows():
        for variant, body in (("correct", r["canonical_solution"]), ("buggy", r["buggy_solution"])):
            if lang == "classeval":
                code = body  # full class, docstrings removed
            else:
                code = (r["import"] or "") + r["declaration"] + body
            rows.append(
                {
                    "doc_id": f"{r['problem']}_{variant}",
                    "problem": int(r["problem"]),
                    "variant": variant,
                    "code": code.strip("\n"),
                    "bug_type": r["bug_type"],
                }
            )
    docs = pd.DataFrame(rows)
    # HumanEvalFix: natural-language docstring (CodeSearchNet setting).
    # ClassEval: the class skeleton open in the IDE (signatures and docstrings).
    qcol = "skeleton" if lang == "classeval" else "docstring"
    queries = pd.DataFrame({"problem": df["problem"].astype(int), "query": df[qcol].str.strip()})
    return docs, queries

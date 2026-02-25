"""
RQ3: Algorithmic Complexity & Language Clustering — Data Loading & Embedding Generation

Loads the datasets/RQ3/ JSON files (LeetCode problems with multi-language, multi-complexity
solutions) and generates embeddings for all code snippets using models from the MODELS
registry in embedding.py.

Each JSON file represents one LeetCode problem.  Within each problem there are ≥1
solutions per language, each tagged with a time_complexity string.  The raw complexity
strings are mapped to canonical complexity class buckets (O(1), O(log n), O(n), …).

Usage:
    uv run code/rq3/1_embedding.py                    # all models
    uv run code/rq3/1_embedding.py --model unixcoder  # single model

Output: results/rq3/{model_key}/rq3_embeddings.parquet
        results/rq3/{model_key}/rq3_complexity_stats.json  (bucketing summary)
"""

import argparse
import json
import os
import re
import sys

import numpy as np
import pandas as pd
from tqdm import tqdm

# ── Import shared embedding utilities ─────────────────────────────────────────
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
CODE_DIR = os.path.dirname(SCRIPT_DIR)          # code/
sys.path.insert(0, CODE_DIR)
from embedding import (
    MODELS,
    get_sentence_transformer_embeddings,
    get_unixcoder_embeddings,
    get_openrouter_embeddings,
    estimate_costs,
)

# ── Paths ─────────────────────────────────────────────────────────────────────
PROJECT_ROOT = os.path.dirname(CODE_DIR)
DATASET_DIR  = os.path.join(PROJECT_ROOT, "datasets/RQ3")
OUTPUT_BASE  = os.path.join(PROJECT_ROOT, "results/rq3")


# ── Complexity Bucketing ──────────────────────────────────────────────────────

# Priority-ordered rules: first match wins.
# Each entry is (bucket_label, predicate(raw_string) → bool).
_BUCKET_RULES = [
    # Constant
    ("O(1)",       lambda s: s == "O(1)"),
    # Factorial  — O(n!), O(n! * n)
    ("O(n!)",      lambda s: bool(re.search(r"n\s*!", s))),
    # Exponential — O(2^n), O(4^n), O(n*2^n), O(2^{m+n}), O(m*4^n)
    ("O(2^n)",     lambda s: bool(re.search(r"[234]\^", s))),
    # Cubic       — O(n^3), O(n^2 * m), O(m^2 * n), O(n*n*…)
    ("O(n³)",      lambda s: bool(
        re.search(r"n\^3", s) or
        re.search(r"n\^2\s*\*\s*[a-z]", s, re.I) or
        re.search(r"[a-z]\^2\s*\*\s*n", s, re.I)
    )),
    # n log n     — O(n log n), O(n log k), O(m log n)  (but NOT O(n^2 log n))
    ("O(n log n)", lambda s: bool(
        re.search(r"n\s*log|[a-z]\s*\*\s*log", s, re.I) and
        not re.search(r"n\^2\s*log|n\^3", s, re.I)
    )),
    # Quadratic   — O(n^2), O(n*m), O(m*n)  (but NOT O(n^2 log n))
    ("O(n²)",      lambda s: bool(
        re.search(r"n\^2", s) or
        re.search(r"\bm\s*\*\s*n\b|\bn\s*\*\s*m\b|\bn\s*\*\s*n\b", s, re.I)
    ) and not re.search(r"n\^2\s*log", s, re.I)),
    # Logarithmic — O(log n)
    ("O(log n)",   lambda s: bool(re.fullmatch(r"O\(\s*log\s*[a-zA-Z]+\s*\)", s))),
    # Linear      — O(n), O(N), O(m), O(V+E), O(n+m), O(m+n)
    ("O(n)",       lambda s: bool(
        re.fullmatch(r"O\(\s*[a-zA-Z]\s*\)", s) or
        re.fullmatch(r"O\(\s*[a-zA-Z]\s*[\+\-]\s*[a-zA-Z]\s*\)", s) or
        re.search(r"O\(\s*[VE]\s*\+", s) or
        re.search(r"O\(\s*V\s*\+\s*\(", s)
    )),
]

# Ordered list for display / consistent ordering in graphs
COMPLEXITY_ORDER = [
    "O(1)", "O(log n)", "O(n)", "O(n log n)",
    "O(n²)", "O(n³)", "O(2^n)", "O(n!)", "Other",
]


def bucket_complexity(raw: str) -> str:
    """
    Map a raw time_complexity string to one of 9 canonical buckets.

    The mapping is priority-ordered (first match wins), covering the most common
    complexity patterns found in the LeetCode RQ3 dataset.

    Args:
        raw (str): A time complexity string such as 'O(n^2)', 'O(n log n)', etc.

    Returns:
        str: A canonical bucket label from COMPLEXITY_ORDER.
    """
    if not raw:
        return "Other"
    s = raw.strip()
    for label, predicate in _BUCKET_RULES:
        try:
            if predicate(s):
                return label
        except re.error:
            pass
    return "Other"


# ── Data Loading ──────────────────────────────────────────────────────────────

def load_rq3_dataset(dataset_dir: str) -> pd.DataFrame:
    """
    Walk the RQ3 JSON directory and collect all code solutions with metadata.

    Each JSON file represents a LeetCode problem with the schema:
        {
          "problem_name": str,
          "problem_slug": str,
          "difficulty": str,                   # easy / medium / hard
          "topic_tags": [str, ...],
          "solutions": [
            {
              "language": str,
              "time_complexity": str,
              "space_complexity": str,
              "code": str
            },
            ...
          ]
        }

    Returns a DataFrame with one row per (problem, language, complexity variant).

    Columns:
        problem_name, problem_slug, difficulty, topic_tags (joined),
        language, time_complexity, complexity_class (bucketed),
        space_complexity, code

    Args:
        dataset_dir (str): Path to datasets/RQ3/.

    Returns:
        pd.DataFrame: One row per code solution.
    """
    import glob

    records = []
    json_files = sorted(glob.glob(os.path.join(dataset_dir, "*.json")))

    if not json_files:
        raise FileNotFoundError(f"No JSON files found in {dataset_dir}")

    for fpath in tqdm(json_files, desc="Loading RQ3 problems"):
        with open(fpath, "r", encoding="utf-8") as f:
            problem = json.load(f)

        problem_name = problem.get("problem_name", "")
        problem_slug = problem.get("problem_slug", os.path.basename(fpath).replace(".json", ""))
        difficulty   = problem.get("difficulty", "").lower()
        topic_tags   = "|".join(problem.get("topic_tags", []))

        for sol in problem.get("solutions", []):
            lang      = sol.get("language", "").lower()
            time_c    = sol.get("time_complexity", "").strip()
            space_c   = sol.get("space_complexity", "").strip()
            code      = sol.get("code", "").strip()

            if not code:
                continue

            records.append({
                "problem_name":    problem_name,
                "problem_slug":    problem_slug,
                "difficulty":      difficulty,
                "topic_tags":      topic_tags,
                "language":        lang,
                "time_complexity": time_c,
                "complexity_class": bucket_complexity(time_c),
                "space_complexity": space_c,
                "code":            code,
            })

    df = pd.DataFrame(records)
    print(f"\nLoaded {len(df)} solutions from {len(json_files)} problems.")
    return df


# ── Embedding Generation ──────────────────────────────────────────────────────

def generate_embeddings(df: pd.DataFrame, model_key: str) -> pd.DataFrame | None:
    """
    Generate dense vector embeddings for all code snippets using the given model.

    Routes to the correct embedding function (SentenceTransformer / HuggingFace /
    OpenRouter API) based on the model's type field in MODELS.

    Args:
        df (pd.DataFrame): DataFrame with a 'code' column.
        model_key (str): Key into the MODELS registry.

    Returns:
        pd.DataFrame | None: Original DataFrame with an appended 'embedding' column,
                             or None if the user cancels an API cost prompt.
    """
    model_info = MODELS[model_key]
    model_name = model_info["name"]
    model_type = model_info["type"]
    code_texts = df["code"].tolist()

    print(f"\nGenerating embeddings with {model_key} ({model_name})"
          f" for {len(code_texts)} snippets …")

    if model_type == "api_openrouter":
        if not estimate_costs(code_texts, model_name):
            print("Skipping — user cancelled.")
            return None

    if model_type == "sentence_transformer":
        embeddings = get_sentence_transformer_embeddings(model_name, code_texts)
    elif model_type == "huggingface":
        embeddings = get_unixcoder_embeddings(model_name, code_texts, batch_size=8)
    elif model_type == "api_openrouter":
        embeddings = get_openrouter_embeddings(model_name, code_texts)
    else:
        print(f"Unknown model type: {model_type}")
        return None

    df_out = df.copy()
    df_out["embedding"] = embeddings
    print(f"Generated {len(embeddings)} embeddings, dim = {len(embeddings[0])}")
    return df_out


# ── Main ──────────────────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(
        description="Generate RQ3 embeddings using one or all models."
    )
    parser.add_argument(
        "--model", type=str, default="all",
        choices=list(MODELS.keys()) + ["all"],
        help="Model to use (default: all)",
    )
    args = parser.parse_args()

    # 1. Load dataset once
    df = load_rq3_dataset(DATASET_DIR)

    if df.empty:
        print("ERROR: No solutions found. Check DATASET_DIR:", DATASET_DIR)
        sys.exit(1)

    # Dataset summary
    print(f"\nDataset summary:")
    print(f"  Problems:          {df['problem_slug'].nunique()}")
    print(f"  Languages:         {sorted(df['language'].unique())}")
    print(f"  Difficulties:      {df['difficulty'].value_counts().to_dict()}")
    print(f"  Complexity classes: {df['complexity_class'].value_counts().to_dict()}")
    print(f"  Unique raw complexities: {df['time_complexity'].nunique()}")
    print(f"  Total solutions:   {len(df)}")

    # Verify bucketing on a small sample
    print("\nSample bucketing check:")
    sample = (
        df[["time_complexity", "complexity_class"]]
        .drop_duplicates("time_complexity")
        .sort_values("complexity_class")
        .head(20)
    )
    for _, row in sample.iterrows():
        print(f"  {row['time_complexity']!r:30s} → {row['complexity_class']}")

    # 2. Generate embeddings per model
    models_to_run = [args.model] if args.model != "all" else list(MODELS.keys())

    for model_key in models_to_run:
        print(f"\n{'='*60}")
        print(f"  Processing model: {model_key}")
        print(f"{'='*60}")

        output_dir  = os.path.join(OUTPUT_BASE, model_key)
        output_file = os.path.join(output_dir, "rq3_embeddings.parquet")

        if os.path.exists(output_file):
            print(f"  Embeddings already exist at {output_file} — skipping.")
            print(f"  (Delete the file to regenerate.)")
            continue

        try:
            df_emb = generate_embeddings(df, model_key)
            if df_emb is None:
                continue

            os.makedirs(output_dir, exist_ok=True)
            df_emb.to_parquet(output_file, index=False)
            print(f"\nSaved embeddings → {output_file}")

            # Save bucketing stats
            stats = {
                "model_key": model_key,
                "total_solutions": int(len(df_emb)),
                "complexity_class_counts": df_emb["complexity_class"].value_counts().to_dict(),
                "language_counts": df_emb["language"].value_counts().to_dict(),
                "difficulty_counts": df_emb["difficulty"].value_counts().to_dict(),
                "problems": int(df_emb["problem_slug"].nunique()),
            }
            stats_file = os.path.join(output_dir, "rq3_complexity_stats.json")
            with open(stats_file, "w") as f:
                json.dump(stats, f, indent=2)
            print(f"Saved stats      → {stats_file}")

        except Exception as e:
            print(f"Failed to process {model_key}: {e}")
            import traceback
            traceback.print_exc()

    print("\nDone!")


if __name__ == "__main__":
    main()

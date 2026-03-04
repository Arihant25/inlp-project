"""
RQ4: Correctness Regions & Bug Patterns — Data Loading & Embedding Generation

Loads datasets/RQ4/bugs.json (100 bug types × 5 languages, each with buggy and
fixed code) and generates embeddings for all code snippets using models from the
MODELS registry in embedding.py.

Bug types are classified into four severity levels matching the project proposal:
  Easy       (Syntax errors)              — bugs 1-22
  Medium     (Logic / control-flow)       — bugs 23-50
  Hard       (State / algorithm)          — bugs 51-81
  Super Hard (Numeric / memory / concurrency) — bugs 82-100

Usage:
    uv run code/RQ4/1_embedding.py                    # all models
    uv run code/RQ4/1_embedding.py --model octen      # single model

Output: results/RQ4/{model_key}/rq4_embeddings.parquet
"""

import argparse
import json
import os
import sys

import numpy as np
import pandas as pd
from tqdm import tqdm

# ── Import shared embedding utilities ─────────────────────────────────────────
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
CODE_DIR = os.path.dirname(SCRIPT_DIR)  # code/
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
DATASET_PATH = os.path.join(PROJECT_ROOT, "datasets/RQ4/bugs.json")
OUTPUT_BASE = os.path.join(PROJECT_ROOT, "results/RQ4")


# ── Bug Severity Classification ───────────────────────────────────────────────
# The 100 bug types in the dataset are ordered by severity. We classify them
# into four buckets matching the project proposal's framework.

SEVERITY_ORDER = ["Easy", "Medium", "Hard", "Super Hard"]

# Bug indices are 0-based (matching list position in bugs.json)
_SEVERITY_RANGES = [
    (
        "Easy",
        range(0, 22),
    ),  # Syntax errors: missing delimiters, misspelled keywords, type decl errors
    (
        "Medium",
        range(22, 50),
    ),  # Logic / control-flow: loop issues, condition ordering, collection handling
    (
        "Hard",
        range(50, 81),
    ),  # State / algorithmic: state mgmt, edge cases, DP, greedy, traversal
    (
        "Super Hard",
        range(81, 100),
    ),  # Numeric / memory / concurrency: precision, aliasing, race conditions
]


def classify_severity(bug_index: int) -> str:
    """
    Map a 0-based bug index to its severity bucket.

    Args:
        bug_index (int): Position of the bug in the bugs.json array.

    Returns:
        str: One of 'Easy', 'Medium', 'Hard', 'Super Hard'.
    """
    for label, idx_range in _SEVERITY_RANGES:
        if bug_index in idx_range:
            return label
    return "Super Hard"  # fallback


# ── Data Loading ──────────────────────────────────────────────────────────────


def load_rq4_dataset(dataset_path: str) -> pd.DataFrame:
    """
    Load the RQ4 bugs dataset and produce a flat DataFrame with one row per
    (bug_type, language, code_type) combination.

    Each original JSON entry yields 2 rows per language (buggy + fixed), so
    100 bugs × 5 languages × 2 = 1000 rows total.

    Columns: bug_index, bug_type, description, severity, language, code_type, code

    Args:
        dataset_path (str): Path to datasets/RQ4/bugs.json.

    Returns:
        pd.DataFrame: One row per code snippet.
    """
    with open(dataset_path, "r", encoding="utf-8") as f:
        data = json.load(f)

    records = []
    for idx, entry in enumerate(data):
        bug_type = entry.get("bug_type", f"bug_{idx}")
        description = entry.get("description", "")
        severity = classify_severity(idx)

        for key in entry:
            if key in ("bug_type", "description"):
                continue
            lang = key
            buggy_code = entry[lang].get("buggy_code", "").strip()
            fixed_code = entry[lang].get("fixed_code", "").strip()

            if buggy_code:
                records.append(
                    {
                        "bug_index": idx,
                        "bug_type": bug_type,
                        "description": description,
                        "severity": severity,
                        "language": lang,
                        "code_type": "buggy",
                        "code": buggy_code,
                    }
                )
            if fixed_code:
                records.append(
                    {
                        "bug_index": idx,
                        "bug_type": bug_type,
                        "description": description,
                        "severity": severity,
                        "language": lang,
                        "code_type": "fixed",
                        "code": fixed_code,
                    }
                )

    df = pd.DataFrame(records)
    print(f"\nLoaded {len(df)} code snippets from {len(data)} bug types.")
    return df


# ── Embedding Generation ──────────────────────────────────────────────────────


def generate_embeddings(df: pd.DataFrame, model_key: str) -> pd.DataFrame | None:
    """
    Generate dense vector embeddings for all code snippets using the given model.

    Routes to the correct embedding function based on the model type in MODELS.

    Args:
        df (pd.DataFrame): DataFrame with a 'code' column.
        model_key (str): Key into the MODELS registry.

    Returns:
        pd.DataFrame | None: Original DataFrame with 'embedding' column appended,
                             or None if the user cancels an API cost prompt.
    """
    model_info = MODELS[model_key]
    model_name = model_info["name"]
    model_type = model_info["type"]
    code_texts = df["code"].tolist()

    print(
        f"\nGenerating embeddings with {model_key} ({model_name})"
        f" for {len(code_texts)} snippets …"
    )

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
        description="Generate RQ4 embeddings using one or all models."
    )
    parser.add_argument(
        "--model",
        type=str,
        default="all",
        choices=list(MODELS.keys()) + ["all"],
        help="Model to use (default: all)",
    )
    args = parser.parse_args()

    # 1. Load dataset once
    df = load_rq4_dataset(DATASET_PATH)

    if df.empty:
        print("ERROR: No code snippets found. Check DATASET_PATH:", DATASET_PATH)
        sys.exit(1)

    # Dataset summary
    print(f"\nDataset summary:")
    print(f"  Languages:    {sorted(df['language'].unique())}")
    print(f"  Code types:   {sorted(df['code_type'].unique())}")
    print(f"  Severities:   {df['severity'].value_counts().to_dict()}")
    print(f"  Bug types:    {df['bug_type'].nunique()}")
    print(f"  Total:        {len(df)} snippets")

    # 2. Generate embeddings per model
    models_to_run = [args.model] if args.model != "all" else list(MODELS.keys())

    for model_key in models_to_run:
        print(f"\n{'='*60}")
        print(f"  Processing model: {model_key}")
        print(f"{'='*60}")

        output_dir = os.path.join(OUTPUT_BASE, model_key)
        output_file = os.path.join(output_dir, "rq4_embeddings.parquet")

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
            print(f"\nSaved embeddings to {output_file}")

        except Exception as e:
            print(f"Failed to process {model_key}: {e}")
            import traceback

            traceback.print_exc()

    print("\nDone!")


if __name__ == "__main__":
    main()

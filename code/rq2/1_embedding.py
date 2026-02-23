"""
RQ2: Framework-Driven Dialects — Data Loading & Embedding Generation

Traverses datasets/RQ2/Extracted_Dataset/{Language}/{Framework}/{Pattern}/
and generates UniXCoder embeddings for all code snippets.
Reuses get_unixcoder_embeddings() from embedding.py.

Output: results/rq2/rq2_embeddings.parquet
"""

import os
import sys
import glob

import numpy as np
import pandas as pd
from tqdm import tqdm

# ── Import the existing UniXCoder embedding function ──────────────────────────
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
CODE_DIR = os.path.dirname(SCRIPT_DIR)  # parent code/ directory
sys.path.insert(0, CODE_DIR)
from embedding import get_unixcoder_embeddings  # reuse existing pipeline

# ── Paths ─────────────────────────────────────────────────────────────────────
PROJECT_ROOT = os.path.dirname(CODE_DIR)
DATASET_DIR = os.path.join(PROJECT_ROOT, "datasets/RQ2/Extracted_Dataset")
OUTPUT_DIR = os.path.join(PROJECT_ROOT, "results/rq2")
OUTPUT_FILE = os.path.join(OUTPUT_DIR, "rq2_embeddings.parquet")

UNIXCODER_MODEL = "microsoft/unixcoder-base"


# ── Data Loading ──────────────────────────────────────────────────────────────

def parse_metadata(metadata_path: str) -> dict:
    """Parse a metadata.txt file and return a dict of key-value pairs."""
    meta = {}
    with open(metadata_path, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if ":" in line:
                key, value = line.split(":", 1)
                meta[key.strip()] = value.strip()
    return meta


def load_rq2_dataset(dataset_dir: str) -> pd.DataFrame:
    """
    Walk the Extracted_Dataset directory tree and collect all code snippets
    along with their metadata (Language, Framework, Pattern, Variation).
    """
    records = []

    languages = sorted(
        d for d in os.listdir(dataset_dir)
        if os.path.isdir(os.path.join(dataset_dir, d))
    )

    for lang in tqdm(languages, desc="Loading languages"):
        lang_dir = os.path.join(dataset_dir, lang)
        frameworks = sorted(
            d for d in os.listdir(lang_dir)
            if os.path.isdir(os.path.join(lang_dir, d))
        )

        for framework in frameworks:
            fw_dir = os.path.join(lang_dir, framework)
            patterns = sorted(
                d for d in os.listdir(fw_dir)
                if os.path.isdir(os.path.join(fw_dir, d))
            )

            for pattern in patterns:
                pat_dir = os.path.join(fw_dir, pattern)

                # Parse metadata
                meta_path = os.path.join(pat_dir, "metadata.txt")
                if os.path.exists(meta_path):
                    meta = parse_metadata(meta_path)
                else:
                    meta = {
                        "Language": lang,
                        "Framework": framework,
                        "Pattern": pattern,
                    }

                # Read all variation_*.* files
                variation_files = sorted(glob.glob(os.path.join(pat_dir, "variation_*.*")))
                for vf in variation_files:
                    basename = os.path.basename(vf)
                    # Extract variation number, e.g. variation_1.java -> 1
                    var_num = basename.split("_")[1].split(".")[0]

                    with open(vf, "r", encoding="utf-8", errors="replace") as f:
                        code = f.read()

                    records.append({
                        "language": meta.get("Language", lang),
                        "framework": meta.get("Framework", framework),
                        "pattern": meta.get("Pattern", pattern),
                        "variation": int(var_num),
                        "filepath": os.path.relpath(vf, dataset_dir),
                        "code": code,
                    })

    df = pd.DataFrame(records)
    print(f"Loaded {len(df)} code snippets from {len(languages)} languages.")
    return df


# ── Embedding Generation ─────────────────────────────────────────────────────

def generate_embeddings(df: pd.DataFrame) -> pd.DataFrame:
    """
    Generate UniXCoder embeddings for all code snippets and add them
    as a column in the DataFrame.
    """
    code_texts = df["code"].tolist()
    print(f"\nGenerating UniXCoder embeddings for {len(code_texts)} snippets...")

    embeddings = get_unixcoder_embeddings(UNIXCODER_MODEL, code_texts, batch_size=8)

    # Store embeddings as a list-of-floats column
    df["embedding"] = embeddings
    print(f"Generated {len(embeddings)} embeddings, dimension = {len(embeddings[0])}")
    return df


# ── Main ──────────────────────────────────────────────────────────────────────

def main():
    # 1. Load dataset
    df = load_rq2_dataset(DATASET_DIR)

    if df.empty:
        print("ERROR: No code snippets found. Check DATASET_DIR:", DATASET_DIR)
        sys.exit(1)

    print(f"\nDataset summary:")
    print(f"  Languages:  {sorted(df['language'].unique())}")
    print(f"  Frameworks: {sorted(df['framework'].unique())}")
    print(f"  Patterns:   {sorted(df['pattern'].unique())}")
    print(f"  Total:      {len(df)} snippets")

    # 2. Generate embeddings
    df = generate_embeddings(df)

    # 3. Save to Parquet
    os.makedirs(OUTPUT_DIR, exist_ok=True)
    df.to_parquet(OUTPUT_FILE, index=False)
    print(f"\nSaved embeddings to {OUTPUT_FILE}")


if __name__ == "__main__":
    main()

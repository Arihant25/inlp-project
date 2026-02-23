"""
RQ2: Framework-Driven Dialects — Data Loading & Embedding Generation

Traverses datasets/RQ2/Extracted_Dataset/{Language}/{Framework}/{Pattern}/
and generates embeddings for all code snippets using one or more models
from the MODELS registry in embedding.py.

Usage:
    python 1_embedding.py                  # all models
    python 1_embedding.py --model unixcoder  # single model

Output: results/rq2/{model_key}/rq2_embeddings.parquet
"""

import argparse
import os
import sys
import glob

import numpy as np
import pandas as pd
from tqdm import tqdm

# ── Import from the shared embedding module ──────────────────────────────────
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
CODE_DIR = os.path.dirname(SCRIPT_DIR)  # parent code/ directory
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
DATASET_DIR = os.path.join(PROJECT_ROOT, "datasets/RQ2/Extracted_Dataset")
OUTPUT_BASE = os.path.join(PROJECT_ROOT, "results/rq2")


# ── Data Loading ──────────────────────────────────────────────────────────────

def parse_metadata(metadata_path: str) -> dict:
    """
    Parse a metadata.txt file and return a dictionary of key-value pairs.
    
    The metadata.txt files located in the Extracted_Dataset tree provide 
    overrides for Language, Framework, and Pattern attributes if they differ 
    from the directory names.
    
    Args:
        metadata_path (str): Absolute or relative path to the metadata.txt file.
        
    Returns:
        dict: Parsed key-value pairs (e.g., {"Language": "Python"}).
    """
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
    along with their hierarchical metadata (Language, Framework, Pattern, Variation).

    The expected directory structure is:
        dataset_dir/
            ├── {Language}/
            │   ├── {Framework}/
            │   │   ├── {Pattern}/
            │   │   │   ├── metadata.txt (optional overrides)
            │   │   │   ├── variation_1.ext
            │   │   │   ├── variation_2.ext

    Args:
        dataset_dir (str): Root path to the extracted dataset.

    Returns:
        pd.DataFrame: A DataFrame where each row represents a single code snippet variation,
                      with columns for language, framework, pattern, variation number, 
                      filepath, and raw code text.
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

                # Parse metadata overrides if present
                meta_path = os.path.join(pat_dir, "metadata.txt")
                if os.path.exists(meta_path):
                    meta = parse_metadata(meta_path)
                else:
                    meta = {
                        "Language": lang,
                        "Framework": framework,
                        "Pattern": pattern,
                    }

                # Read all variation_*.* source files
                variation_files = sorted(glob.glob(os.path.join(pat_dir, "variation_*.*")))
                for vf in variation_files:
                    basename = os.path.basename(vf)
                    # Extract variation number, e.g., 'variation_1.java' -> '1'
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

def generate_embeddings(df: pd.DataFrame, model_key: str) -> pd.DataFrame:
    """
    Generate dense vector embeddings for all code snippets using the specified model.
    Routes generation to the correct function based on the model's architecture type 
    (SentenceTransformer vs HuggingFace vs API).
    
    Args:
        df (pd.DataFrame): DataFrame containing at minimum a 'code' column.
        model_key (str): The identifier key matching an entry in MODELS.
        
    Returns:
        pd.DataFrame | None: A new DataFrame with the 'embedding' column appended.
                             Returns None if API cost estimation is rejected by the user
                             or an unknown model type is encountered.
    """
    model_info = MODELS[model_key]
    model_name = model_info["name"]
    model_type = model_info["type"]
    code_texts = df["code"].tolist()

    print(f"\nGenerating embeddings with {model_key} ({model_name}) "
          f"for {len(code_texts)} snippets...")

    # Cost check for API models
    if model_type == "api_openrouter":
        if not estimate_costs(code_texts, model_name):
            print("Skipping due to user cancellation.")
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
    print(f"Generated {len(embeddings)} embeddings, dimension = {len(embeddings[0])}")
    return df_out


# ── Main ──────────────────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(
        description="Generate RQ2 embeddings using one or all models."
    )
    parser.add_argument(
        "--model", type=str, default="all",
        choices=list(MODELS.keys()) + ["all"],
        help="Model to use (default: all)",
    )
    args = parser.parse_args()

    # 1. Load dataset (once)
    df = load_rq2_dataset(DATASET_DIR)

    if df.empty:
        print("ERROR: No code snippets found. Check DATASET_DIR:", DATASET_DIR)
        sys.exit(1)

    print(f"\nDataset summary:")
    print(f"  Languages:  {sorted(df['language'].unique())}")
    print(f"  Frameworks: {sorted(df['framework'].unique())}")
    print(f"  Patterns:   {sorted(df['pattern'].unique())}")
    print(f"  Total:      {len(df)} snippets")

    # 2. Generate embeddings per model
    models_to_run = [args.model] if args.model != "all" else list(MODELS.keys())

    for model_key in models_to_run:
        print(f"\n{'='*60}")
        print(f"  Processing model: {model_key}")
        print(f"{'='*60}")

        output_dir = os.path.join(OUTPUT_BASE, model_key)
        output_file = os.path.join(output_dir, "rq2_embeddings.parquet")

        # Skip if already generated
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

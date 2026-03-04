import json
import os
from typing import Dict, List, Tuple

from sentence_transformers import SentenceTransformer
from tqdm import tqdm

# Initialize the Octen-Embedding-0.6B model (same as RQ1)
print("Loading Octen-Embedding-0.6B model...")
model = SentenceTransformer("Octen/Octen-Embedding-0.6B", device="cuda")
print("Model loaded successfully!")

# Base directory for RQ4 dataset
script_dir = os.path.dirname(os.path.abspath(__file__))
dataset_path = os.path.join(script_dir, "../../datasets/RQ4/bugs.json")


def load_bugs_dataset(filepath: str) -> List[Dict]:
    """Load the bugs dataset from JSON file"""
    with open(filepath, "r", encoding="utf-8") as f:
        return json.load(f)


def extract_code_pairs(data: List[Dict]) -> Dict[str, List[Tuple[str, str, str]]]:
    """
    Extract buggy and fixed code pairs by language.
    Returns: Dict[language] = List[(bug_type, buggy_code, fixed_code)]
    """
    language_pairs = {}

    for entry in data:
        bug_type = entry.get("bug_type", "unknown")
        for key in entry.keys():
            if key not in ["bug_type", "description"]:
                lang = key
                if lang not in language_pairs:
                    language_pairs[lang] = []

                buggy_code = entry[lang]["buggy_code"]
                fixed_code = entry[lang]["fixed_code"]
                language_pairs[lang].append((bug_type, buggy_code, fixed_code))

    return language_pairs


print(f"Loading bugs dataset from {dataset_path}...")
bugs_data = load_bugs_dataset(dataset_path)
print(f"Loaded {len(bugs_data)} bug types")

print("\nExtracting code pairs by language...")
language_pairs = extract_code_pairs(bugs_data)

for lang, pairs in language_pairs.items():
    print(f"  {lang}: {len(pairs)} buggy-fixed pairs")

# Generate embeddings for all buggy and fixed code
print("\nGenerating embeddings...")
embeddings = {}

for lang in tqdm(sorted(language_pairs.keys()), desc="Processing languages"):
    lang_embeddings = {"buggy": [], "fixed": [], "bug_types": []}

    # Collect all buggy and fixed codes for this language
    buggy_codes = [pair[1] for pair in language_pairs[lang]]
    fixed_codes = [pair[2] for pair in language_pairs[lang]]
    bug_types = [pair[0] for pair in language_pairs[lang]]

    # Generate embeddings in batch (more efficient)
    print(f"\n  Embedding {lang} buggy code...")
    buggy_embs = model.encode(
        buggy_codes, show_progress_bar=True, convert_to_numpy=True
    )

    print(f"  Embedding {lang} fixed code...")
    fixed_embs = model.encode(
        fixed_codes, show_progress_bar=True, convert_to_numpy=True
    )

    # Store embeddings
    embeddings[lang] = {
        "buggy_embeddings": buggy_embs.tolist(),
        "fixed_embeddings": fixed_embs.tolist(),
        "buggy_codes": buggy_codes,
        "fixed_codes": fixed_codes,
        "bug_types": bug_types,
    }

# Save embeddings to JSON
output_dir = os.path.join(script_dir, "../../results/embeddings")
os.makedirs(output_dir, exist_ok=True)

output_json = os.path.join(output_dir, "rq4_octen_embeddings.json")
print(f"\nSaving embeddings to {output_json}...")
with open(output_json, "w", encoding="utf-8") as f:
    json.dump(embeddings, f, indent=2)

# Save embeddings to TSV format (for visualization tools)
# Format: one row per code sample with metadata
output_tsv = os.path.join(output_dir, "rq4_octen_embeddings.tsv")
print(f"Saving embeddings to {output_tsv}...")
with open(output_tsv, "w", encoding="utf-8") as f:
    for lang in sorted(embeddings.keys()):
        n_samples = len(embeddings[lang]["buggy_embeddings"])
        for i in range(n_samples):
            # Buggy code embedding
            buggy_emb_str = "\t".join(
                [str(x) for x in embeddings[lang]["buggy_embeddings"][i]]
            )
            f.write(f"{buggy_emb_str}\n")

            # Fixed code embedding
            fixed_emb_str = "\t".join(
                [str(x) for x in embeddings[lang]["fixed_embeddings"][i]]
            )
            f.write(f"{fixed_emb_str}\n")

# Save metadata (for visualization tools)
output_metadata = os.path.join(output_dir, "rq4_octen_embeddings_metadata.tsv")
print(f"Saving metadata to {output_metadata}...")
with open(output_metadata, "w", encoding="utf-8") as f:
    f.write("Name\tLanguage\tType\tBugType\n")
    for lang in sorted(embeddings.keys()):
        n_samples = len(embeddings[lang]["buggy_embeddings"])
        for i in range(n_samples):
            bug_type = embeddings[lang]["bug_types"][i]
            # Buggy code metadata
            f.write(f"{lang}_{i}_buggy\t{lang}\tbuggy\t{bug_type}\n")
            # Fixed code metadata
            f.write(f"{lang}_{i}_fixed\t{lang}\tfixed\t{bug_type}\n")

# Print summary statistics
print("\n✓ Embeddings generated successfully!")
total_pairs = sum(len(embeddings[lang]["buggy_embeddings"]) for lang in embeddings)
print(f"  - Total buggy-fixed pairs: {total_pairs}")
print(f"  - Languages: {len(embeddings)}")
print(
    f"  - Embedding dimension: {len(embeddings[list(embeddings.keys())[0]]['buggy_embeddings'][0])}"
)
print("  - Output files:")
print(f"    • {output_json}")
print(f"    • {output_tsv}")
print(f"    • {output_metadata}")

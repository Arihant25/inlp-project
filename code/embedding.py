import json
import os
import re
from typing import Dict, List

from sentence_transformers import SentenceTransformer
from tqdm import tqdm

# Initialize the Octen-Embedding-0.6B model
print("Loading Octen-Embedding-0.6B model...")
model = SentenceTransformer("Octen/Octen-Embedding-0.6B", device="cuda")
print("Model loaded successfully!")

# Base directory for code snippets
script_dir = os.path.dirname(os.path.abspath(__file__))
base_dir = os.path.join(script_dir, "../../data/code_snippets")

# List of languages (19 total)
languages = [
    "AppleScript",
    "C",
    "C++",
    "Dart",
    "Fortran",
    "Go",
    "Haskell",
    "Java",
    "JavaScript",
    "Kotlin",
    "PHP",
    "Pascal",
    "Python",
    "Raku",
    "Ruby",
    "Rust",
    "Scala",
    "Swift",
    "Visual_Basic",
]

# List of features (21 total)
features = [
    "F1_Variable_Definition",
    "F2_Conditional_Branching",
    "F3_Loop__For",
    "F4_Loop__While",
    "F5_System_I_O",
    "F6_Arithmetic_Operations",
    "F7_Logical_Operations",
    "F8_Comparison_Operations",
    "F9_Library_Integration",
    "F10_Parameter_Passing",
    "F11_Function_Returns",
    "F12_Exception_Handling",
    "F13_Array_Usage",
    "F14_List_Usage",
    "F15_Set_Usage",
    "F16_Map_Dictionary_Usage",
    "F17_Class_Definition",
    "F18_Object_Creation",
    "F19_Inheritance_Mechanism",
    "F20_Functional_Map",
    "F21_Functional_Filter",
]


def extract_code_from_markdown(code_with_fence: str) -> str:
    """Extract code from markdown code fence (```language ... ```)"""
    # Remove markdown code fences
    pattern = r"```[\w\+#]*\n(.*?)\n```"
    match = re.search(pattern, code_with_fence, re.DOTALL)
    if match:
        return match.group(1)
    # If no fence found, return as is
    return code_with_fence


def load_code_snippets(
    base_dir: str, languages: List[str], features: List[str]
) -> Dict:
    """Load all code snippets from JSON files"""
    all_snippets = {}

    for lang in tqdm(languages, desc="Loading snippets"):
        for feature in features:
            json_path = os.path.join(base_dir, lang, f"{feature}.json")

            if not os.path.exists(json_path):
                print(f"Warning: {json_path} not found, skipping...")
                continue

            with open(json_path, "r", encoding="utf-8") as f:
                snippets_list = json.load(f)

            # Combine all code snippets for this language-feature pair
            # We'll concatenate all tasks/code for this feature
            combined_code = ""
            for snippet in snippets_list:
                code = extract_code_from_markdown(snippet["code"])
                combined_code += code + "\n"

            key = f"{lang} {feature}"
            all_snippets[key] = combined_code.strip()

    return all_snippets


print("Loading code snippets...")
snippets = load_code_snippets(base_dir, languages, features)
print(f"Loaded {len(snippets)} code snippets")

# Generate embeddings
print("\nGenerating embeddings...")
embeddings = {}

for key, code in tqdm(snippets.items(), desc="Embedding"):
    embedding = model.encode(code, show_progress_bar=False)
    embeddings[key] = {"code": code, "embedding": embedding.tolist()}

# Save embeddings to JSON
output_dir = os.path.join(script_dir, "../../results/embeddings")
os.makedirs(output_dir, exist_ok=True)

output_json = os.path.join(output_dir, "octen_embeddings.json")
print(f"\nSaving embeddings to {output_json}...")
with open(output_json, "w", encoding="utf-8") as f:
    json.dump(embeddings, f, indent=2)

# Save embeddings to TSV (for visualization tools)
output_tsv = os.path.join(output_dir, "octen_embeddings.tsv")
print(f"Saving embeddings to {output_tsv}...")
with open(output_tsv, "w", encoding="utf-8") as f:
    for key in embeddings:
        embedding_str = "\t".join([str(x) for x in embeddings[key]["embedding"]])
        f.write(f"{embedding_str}\n")

# Save metadata (for visualization tools)
output_metadata = os.path.join(output_dir, "octen_embeddings_metadata.tsv")
print(f"Saving metadata to {output_metadata}...")
with open(output_metadata, "w", encoding="utf-8") as f:
    f.write("Name\tLanguage\tFeature\n")
    for key in embeddings:
        parts = key.split(" ", 1)
        language = parts[0]
        feature = parts[1] if len(parts) > 1 else ""
        name = key.replace(" ", "_")
        f.write(f"{name}\t{language}\t{feature}\n")

print("\n✓ Embeddings generated successfully!")
print(f"  - Total snippets: {len(embeddings)}")
print(
    f"  - Embedding dimension: {len(embeddings[list(embeddings.keys())[0]]['embedding'])}"
)
print("  - Output files:")
print(f"    • {output_json}")
print(f"    • {output_tsv}")
print(f"    • {output_metadata}")

import argparse
import gc
import json
import os

from dotenv import load_dotenv
load_dotenv()

# Set PyTorch memory allocation configuration to avoid fragmentation
os.environ["PYTORCH_ALLOC_CONF"] = "expandable_segments:True"

import re
import sys
import time
from typing import Dict, List, Any

import numpy as np
import requests
import torch
from sentence_transformers import SentenceTransformer
from tqdm import tqdm
from transformers import AutoModel, AutoTokenizer


# --- Configuration ---
MODELS = {
    "octen": {"name": "Octen/Octen-Embedding-0.6B", "type": "sentence_transformer"},
    "bge_m3": {"name": "BAAI/bge-m3", "type": "sentence_transformer"},
    "unixcoder": {"name": "microsoft/unixcoder-base", "type": "huggingface"},
    "qwen3": {"name": "Qwen/Qwen3-Embedding-0.6B", "type": "sentence_transformer"},
    "minilm": {"name": "sentence-transformers/all-MiniLM-L6-v2", "type": "sentence_transformer"},
    "ada002": {"name": "openai/text-embedding-ada-002", "type": "api_openrouter"},
}

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
# Note: JSONs are in datasets/family_clustering/<Lang>/<Feature>.json
BASE_DIR = os.path.join(SCRIPT_DIR, "../datasets/family_clustering")
OUTPUT_DIR = os.path.join(SCRIPT_DIR, "../results/embeddings")

LANGUAGES = [
    "AppleScript", "C", "C++", "Dart", "Fortran", "Go", "Haskell",
    "Java", "JavaScript", "Kotlin", "PHP", "Pascal", "Python",
    "Raku", "Ruby", "Rust", "Scala", "Swift", "Visual_Basic"
]

FEATURES = [
    "F1_Variable_Definition", "F2_Conditional_Branching", "F3_Loop__For",
    "F4_Loop__While", "F5_System_I_O", "F6_Arithmetic_Operations",
    "F7_Logical_Operations", "F8_Comparison_Operations", "F9_Library_Integration",
    "F10_Parameter_Passing", "F11_Function_Returns", "F12_Exception_Handling",
    "F13_Array_Usage", "F14_List_Usage", "F15_Set_Usage",
    "F16_Map_Dictionary_Usage", "F17_Class_Definition", "F18_Object_Creation",
    "F19_Inheritance_Mechanism", "F20_Functional_Map", "F21_Functional_Filter",
]


# --- Helper Functions ---

def load_env_file(filepath):
    """Load environment variables from a file manually."""
    try:
        with open(filepath, 'r') as f:
            for line in f:
                line = line.strip()
                if line and not line.startswith('#') and '=' in line:
                    key, value = line.split('=', 1)
                    os.environ[key.strip()] = value.strip()
    except FileNotFoundError:
        pass  # It's okay if .env doesn't exist, maybe env vars are already set


def extract_code_from_markdown(code_with_fence: str) -> str:
    """Extract code from markdown code fence (```language ... ```)"""
    pattern = r"```[\w\+#]*\n(.*?)\n```"
    match = re.search(pattern, code_with_fence, re.DOTALL)
    if match:
        return match.group(1)
    return code_with_fence


def load_code_snippets(base_dir: str, languages: List[str], features: List[str]) -> Dict[str, str]:
    """Load all code snippets from JSON files and return them as individual entries."""
    all_snippets = {}
    print(f"Loading code snippets from {base_dir}...")
    
    count = 0
    for lang in tqdm(languages, desc="Loading languages"):
        for feature in features:
            json_path = os.path.join(base_dir, lang, f"{feature}.json")
            
            if not os.path.exists(json_path):
                continue
                
            with open(json_path, "r", encoding="utf-8") as f:
                try:
                    data = json.load(f)
                except json.JSONDecodeError:
                    print(f"Error reading {json_path}")
                    continue
            
            # Store each snippet individually
            for idx, item in enumerate(data):
                if "code" in item:
                    code = extract_code_from_markdown(item["code"])
                    key = f"{lang} {feature} {idx}"
                    all_snippets[key] = code.strip()
                    count += 1
                
    print(f"Loaded {count} individual snippets.")
    return all_snippets


def estimate_costs(texts: List[str], model_name: str) -> bool:
    """
    Estimate cost for OpenAI/OpenRouter models using tiktoken.
    Returns True if user confirms.
    """
    import tiktoken
    
    # 1. Calculate tokens
    encoding = tiktoken.get_encoding("cl100k_base")
        
    total_tokens = 0
    for text in tqdm(texts, desc="Calculating tokens"):
        total_tokens += len(encoding.encode(text))
        
    print(f"\n--- Cost Estimation for {model_name} ---")
    print(f"Total Snippets: {len(texts)}")
    print(f"Total Tokens:   {total_tokens:,}")
    
    # 2. Estimate Price (Approximate)
    # Ada-002 price (Azure/OpenAI): ~$0.0001 per 1k tokens
    estimated_price = (total_tokens / 1000) * 0.0001
    
    print(f"Estimated Cost (assuming ~$0.10/1M tokens): ${estimated_price:.4f}")
    print("-------------------------------------------")
    
    # 3. Prompt User
    response = input("Do you want to proceed with API calls? (y/n): ").strip().lower()
    return response == 'y'


# --- Model Handlers ---

def get_sentence_transformer_embeddings(model_name: str, texts: List[str]) -> List[List[float]]:
    print(f"Loading SentenceTransformer: {model_name}")
    model = SentenceTransformer(model_name, trust_remote_code=True)
    
    device = 'cuda' if torch.cuda.is_available() else 'cpu'
    model = model.to(device)

    embeddings = []
    try:
        # Simple iterative processing matching embedding_octen.py
        for i, text in enumerate(tqdm(texts, desc=f"Embedding {model_name}")):
            # Encode single string directly
            emb = model.encode(text, show_progress_bar=False, convert_to_numpy=True)
            
            if hasattr(emb, "tolist"):
                emb = emb.tolist()
            
            embeddings.append(emb)
            
            # Frequent cleanup
            if i % 10 == 0:
                gc.collect()
                if torch.cuda.is_available():
                    torch.cuda.empty_cache()
                    
    finally:
        # Cleanup
        del model
        if torch.cuda.is_available():
            torch.cuda.empty_cache()
            gc.collect()
            
    return embeddings


def get_unixcoder_embeddings(model_name: str, texts: List[str], batch_size: int = 4) -> List[List[float]]:
    print(f"Loading HuggingFace Model: {model_name}")
    tokenizer = AutoTokenizer.from_pretrained(model_name)
    model = AutoModel.from_pretrained(model_name)
    
    if torch.cuda.is_available():
        model = model.to('cuda')
    model.eval()
    
    embeddings = []
    
    try:
        for i in tqdm(range(0, len(texts), batch_size), desc="Embedding (UniXcoder)"):
            batch_texts = texts[i : i + batch_size]
            
            # Tokenize
            inputs = tokenizer(batch_texts, padding=True, truncation=True, max_length=512, return_tensors="pt")
            if torch.cuda.is_available():
                inputs = {k: v.to('cuda') for k, v in inputs.items()}
                
            with torch.no_grad():
                outputs = model(**inputs)
                # Mean pooling of last hidden state
                # Shape: [batch, seq_len, hidden] -> [batch, hidden]
                
                # Mask padding tokens
                attention_mask = inputs['attention_mask']
                token_embeddings = outputs.last_hidden_state
                
                input_mask_expanded = attention_mask.unsqueeze(-1).expand(token_embeddings.size()).float()
                sum_embeddings = torch.sum(token_embeddings * input_mask_expanded, 1)
                sum_mask = torch.clamp(input_mask_expanded.sum(1), min=1e-9)
                
                mean_embeddings = sum_embeddings / sum_mask
                
                # L2 Normalize
                norm_embeddings = torch.nn.functional.normalize(mean_embeddings, p=2, dim=1)
                embeddings.extend(norm_embeddings.cpu().tolist())
    finally:
        # Cleanup
        del model
        del tokenizer
        if torch.cuda.is_available():
            torch.cuda.empty_cache()
            gc.collect()
            
    return embeddings


def get_openrouter_embeddings(model_name: str, texts: List[str]) -> List[List[float]]:
    """Get embeddings via OpenRouter API."""
    api_key = os.environ.get("OPENROUTER_API_KEY")
    if not api_key:
        raise ValueError("OPENROUTER_API_KEY environment variable not set.")
    
    print(f"Calling OpenRouter API for {model_name}...")
    url = "https://openrouter.ai/api/v1/embeddings"
    headers = {
        "Authorization": f"Bearer {api_key}",
        "Content-Type": "application/json",
        "HTTP-Referer": "https://github.com/inlp-project", # Required by OpenRouter
        "X-Title": "INLP Family Clustering",
    }
    
    embeddings = []
    batch_size = 10 
    
    for i in tqdm(range(0, len(texts), batch_size), desc="Embedding (API)"):
        batch_texts = texts[i : i + batch_size]
        
        payload = {
            "model": model_name,
            "input": batch_texts
        }
        
        try:
            response = requests.post(url, headers=headers, json=payload, timeout=60)
            response.raise_for_status()
            data = response.json()
            
            # Sort by index to ensure order is preserved
            batch_embeddings = sorted(data['data'], key=lambda x: x['index'])
            embeddings.extend([x['embedding'] for x in batch_embeddings])
            
        except Exception as e:
            print(f"Error calling API: {e}")
            if hasattr(e, 'response') and e.response:
                print(e.response.text)
            sys.exit(1)
        
    return embeddings


def save_results(model_key: str, keys: List[str], code_list: List[str], embeddings_list: List[List[float]]):
    """Save embeddings to JSON."""
    os.makedirs(OUTPUT_DIR, exist_ok=True)
    output_file = os.path.join(OUTPUT_DIR, f"{model_key}_embeddings.json")
    
    data = {}
    for k, code, emb in zip(keys, code_list, embeddings_list):
        data[k] = {
            "code": code,
            "embedding": emb
        }
        
    with open(output_file, 'w', encoding='utf-8') as f:
        json.dump(data, f, indent=2)
    print(f"Saved {len(data)} embeddings to {output_file}")


# --- Main ---

def main():
    parser = argparse.ArgumentParser(description="Generate embeddings for code snippets using multiple models.")
    parser.add_argument("--model", type=str, default="all", 
                        choices=list(MODELS.keys()) + ["all"],
                        help="Model to use for embeddings (default: all)")
    args = parser.parse_args()
    
    # Load env vars
    load_env_file(os.path.join(SCRIPT_DIR, "../../.env"))
    
    # Load snippets once
    snippets_dict = load_code_snippets(BASE_DIR, LANGUAGES, FEATURES)
    keys = list(snippets_dict.keys())
    texts = list(snippets_dict.values())
    
    if not keys:
        print("No snippets found! Check directory structure.")
        return

    models_to_run = [args.model] if args.model != "all" else list(MODELS.keys())
    
    for model_key in models_to_run:
        print(f"\n=== Processing Model: {model_key} ===")
        model_info = MODELS[model_key]
        model_type = model_info["type"]
        model_name = model_info["name"]
        
        # Check cost if API
        if model_type == "api_openrouter":
            if not estimate_costs(texts, model_name):
                print("Skipping due to user cancellation.")
                continue
        
        embeddings = []
        try:
            if model_type == "sentence_transformer":
                embeddings = get_sentence_transformer_embeddings(model_name, texts)
            elif model_type == "huggingface":
                embeddings = get_unixcoder_embeddings(model_name, texts)
            elif model_type == "api_openrouter":
                embeddings = get_openrouter_embeddings(model_name, texts)
            else:
                print(f"Unknown model type: {model_type}")
                continue
                
            save_results(model_key, keys, texts, embeddings)
            
        except Exception as e:
            print(f"Failed to process {model_key}: {e}")
            import traceback
            traceback.print_exc()


if __name__ == "__main__":
    main()

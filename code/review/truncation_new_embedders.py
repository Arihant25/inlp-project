"""
Review: truncation check (as code/truncation_stats.py) for the two added code embedders.

Counts tokens with each model's own tokenizer (special tokens included; CodeSage's tokenizer
appends EOS as its model card requires) over the corpora of code/truncation_stats.py, and
reports the share of inputs longer than the Sentence-Transformers max_seq_length the
pipeline applies.

Output: results/review/truncation_new_embedders.json
"""

import json
import os
import sys

from huggingface_hub import hf_hub_download
from transformers import AutoTokenizer

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
CODE_DIR = os.path.dirname(SCRIPT_DIR)
sys.path.insert(0, CODE_DIR)
import truncation_stats  # noqa: E402

PROJECT_ROOT = os.path.dirname(CODE_DIR)
MODELS = {"jina_code": "jinaai/jina-embeddings-v2-base-code", "codesage": "codesage/codesage-base-v2"}


def main():
    texts = truncation_stats.corpora()
    out = {"n_inputs": {k: len(v) for k, v in texts.items()}, "limits": {}, "share_truncated": {}}
    for key, name in MODELS.items():
        tok = AutoTokenizer.from_pretrained(name, trust_remote_code=True)
        with open(hf_hub_download(name, "sentence_bert_config.json"), encoding="utf-8") as f:
            limit = int(json.load(f)["max_seq_length"])
        out["limits"][key] = limit
        out["share_truncated"][key] = {}
        for corpus, items in texts.items():
            lengths = [len(tok(t, add_special_tokens=True)["input_ids"]) for t in items]
            out["share_truncated"][key][corpus] = round(100 * sum(l > limit for l in lengths) / len(lengths), 2)
        print(key, limit, out["share_truncated"][key], flush=True)
    path = os.path.join(PROJECT_ROOT, "results/review/truncation_new_embedders.json")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        json.dump(out, f, indent=2)


if __name__ == "__main__":
    main()

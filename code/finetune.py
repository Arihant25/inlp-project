"""
finetune.py  –  Fine-tune bigcode/starcoder on RQ1 code-summary datasets.

One SFT model is trained per *language family* (derived from hierarchical
clustering of Octen-Embedding-8B embeddings at k=5):

  Family      Languages
  ──────────────────────────────────────────────────────────────
  jvm         Dart · Java · Kotlin · Scala
  c           C · C++ · PHP
  legacy      Fortran · Pascal · Visual Basic
  systems     Go · Rust
  dynamic     AppleScript · Haskell · JavaScript · Python · Raku · Ruby · Swift

Dataset format (JSON array, each element):
  { "id": <int>, "code": "<string>", "summary": "<string>" }

Usage:
  # Train all families:
  python finetune.py

  # Train a single family (useful for parallel runs):
  python finetune.py --family jvm

  # Dry-run: validate data loading without training:
  python finetune.py --dry-run
  python finetune.py --family systems --dry-run
"""

import argparse
import json
import os
import re

# Heavy ML imports are deferred into finetune_family() so that
# --dry-run works in environments where torch/transformers are absent.

# ──────────────────────────────────────────────────────────────
# Configuration
# ──────────────────────────────────────────────────────────────

MODEL_NAME = "bigcode/starcoder"

# Paths are relative to the *project root* (one level above this script).
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
PROJECT_ROOT = os.path.dirname(SCRIPT_DIR)
DATASET_DIR = os.path.join(PROJECT_ROOT, "datasets", "RQ1")
CHECKPOINT_BASE = os.path.join(PROJECT_ROOT, "checkpoints")

# ──────────────────────────────────────────────────────────────
# Language family definitions
#   Each value is a list of (human_label, json_filename) tuples.
# ──────────────────────────────────────────────────────────────

FAMILIES: dict[str, list[tuple[str, str]]] = {
    "jvm": [
        ("Dart",   "dart.json"),
        ("Java",   "java.json"),
        ("Kotlin", "kotlin.json"),
        ("Scala",  "scala.json"),
    ],
    "c": [
        ("C",   "c.json"),
        ("C++", "c++.json"),
        ("PHP", "php.json"),
    ],
    "legacy": [
        ("Fortran",       "fortran.json"),
        ("Pascal",        "pascal.json"),
        ("Visual Basic",  "vb.json"),
    ],
    "systems": [
        ("Go",   "go.json"),
        ("Rust", "rust.json"),
    ],
    "dynamic": [
        ("AppleScript", "applescript.json"),
        ("Haskell",     "haskell.json"),
        ("JavaScript",  "js.json"),
        ("Python",      "python.json"),
        ("Raku",        "raku.json"),
        ("Ruby",        "ruby.json"),
        ("Swift",       "swift.json"),
    ],
}


# ──────────────────────────────────────────────────────────────
# Data helpers
# ──────────────────────────────────────────────────────────────

# Characters that are valid JSON string escape sequences.
_VALID_JSON_ESCAPES = set('"\\bfnrtu/')


def _load_json_lenient(filepath: str) -> list:
    """
    Load a JSON file, trying progressively more lenient strategies:

    1. Standard json.load       — strict, fastest path
    2. json.loads(strict=False) — accepts raw control characters
    3. Regex escape-fix         — replaces invalid \\X escapes, then retry
    4. Regex brute-force        — extracts id/code/summary triples from raw text
                                  (handles files with unescaped " inside values)
    """
    with open(filepath, "r", encoding="utf-8") as fh:
        raw = fh.read()

    # Strategy 1: strict
    try:
        return json.loads(raw)
    except json.JSONDecodeError:
        pass

    # Strategy 2: allow raw control characters
    try:
        return json.loads(raw, strict=False)
    except json.JSONDecodeError:
        pass

    # Strategy 3: fix invalid \X escapes by doubling the backslash
    def _fix_escape(m: re.Match) -> str:
        char = m.group(1)
        if char in _VALID_JSON_ESCAPES:
            return m.group(0)
        return "\\\\" + char

    fixed = re.sub(r'\\(.)', _fix_escape, raw)
    try:
        return json.loads(fixed, strict=False)
    except json.JSONDecodeError:
        pass

    # Strategy 4: regex brute-force – extract "summary" fields (short, usually
    # well-formed) paired with surrounding id/code context. Items whose fields
    # are themselves malformed will be silently skipped.
    print(f"  [WARNING] {os.path.basename(filepath)}: JSON is structurally broken; "
          f"using regex fallback (some items may be skipped)")
    pattern = re.compile(
        r'"id"\s*:\s*(\d+).*?"code"\s*:\s*"((?:[^"\\]|\\.)*)"\s*,\s*'
        r'"summary"\s*:\s*"((?:[^"\\]|\\.)*)"',
        re.DOTALL,
    )
    items: list = []
    for m in pattern.finditer(raw):
        try:
            code = json.loads('"' + m.group(2) + '"')
            summary = json.loads('"' + m.group(3) + '"')
            items.append({"id": int(m.group(1)), "code": code, "summary": summary})
        except (json.JSONDecodeError, ValueError):
            continue

    if items:
        return items

    raise RuntimeError(
        f"Could not parse {filepath} with any strategy – please check the file."
    )


def _unwrap_if_needed(data: object, filepath: str) -> list:
    """
    Normalise the top-level structure returned by json.load:

    - list  → returned as-is
    - dict  → if it has exactly one key and the value is a list, return that list
              (handles files like php.json: {"php": [...]})
    - other → raise ValueError
    """
    if isinstance(data, list):
        return data
    if isinstance(data, dict):
        values = list(data.values())
        if len(values) == 1 and isinstance(values[0], list):
            return values[0]
        # Multiple keys – collect all list values
        combined: list = []
        for v in values:
            if isinstance(v, list):
                combined.extend(v)
        if combined:
            return combined
    raise ValueError(
        f"Unexpected top-level type in {filepath}: {type(data).__name__}"
    )


def _strip_code_fences(code: str) -> str:
    """Remove optional ```lang … ``` fences that appear in some entries."""
    code = code.strip()
    # Remove opening fence (e.g. ```java or just ```)
    code = re.sub(r"^```[a-zA-Z_+#]*\n?", "", code)
    # Remove closing fence
    code = re.sub(r"\n?```\s*$", "", code)
    return code.strip()


def load_family_data(family_name: str) -> list:
    """
    Load and concatenate JSON datasets for all languages in a family.

    Each dataset is a JSON *array* of objects with "code" and "summary" fields.
    Items are formatted as:

        ### Code:
        <code>
        ### Summary:
        <summary>

    Returns a plain list[dict] with a single "text" key so that the function
    can be called without any ML packages installed (e.g. during --dry-run).
    """
    records: list = []

    for lang_label, filename in FAMILIES[family_name]:
        filepath = os.path.join(DATASET_DIR, filename)
        if not os.path.isfile(filepath):
            print(f"  [WARNING] Dataset not found for {lang_label}: {filepath}")
            continue

        raw_data = _load_json_lenient(filepath)
        items = _unwrap_if_needed(raw_data, filepath)

        skipped = 0
        loaded = 0
        for item in items:
            if not isinstance(item, dict):
                skipped += 1
                continue
            code = _strip_code_fences(item["code"])
            summary = item["summary"].strip()
            text = f"### Code:\n{code}\n### Summary:\n{summary}"
            records.append({"text": text})
            loaded += 1

        if skipped:
            print(f"  [WARNING] Skipped {skipped} non-dict item(s) in {filename}")
        print(f"  Loaded {loaded:>5} samples from {lang_label} ({filename})")

    print(f"  Total for family '{family_name}': {len(records)} samples")
    return records


# ──────────────────────────────────────────────────────────────
# Fine-tuning
# ──────────────────────────────────────────────────────────────

def finetune_family(family_name: str, dry_run: bool = False) -> None:
    """Load data and fine-tune StarCoder for a single language family."""
    print(f"\n{'='*60}")
    print(f"Family: {family_name.upper()}")
    print(f"Languages: {', '.join(l for l, _ in FAMILIES[family_name])}")
    print(f"{'='*60}")

    # ── Data ──────────────────────────────────────────────────
    records = load_family_data(family_name)

    if dry_run:
        print(f"\n[DRY-RUN] Skipping tokenization and training for '{family_name}'.")
        print(f"  Sample text (first record, first 200 chars):\n"
              f"  {records[0]['text'][:200]!r}\n")
        return

    # ── Deferred heavy imports (only needed for actual training) ───────
    import torch  # noqa: PLC0415
    from datasets import Dataset  # noqa: PLC0415
    from transformers import (  # noqa: PLC0415
        AutoModelForCausalLM,
        AutoTokenizer,
        DataCollatorForLanguageModeling,
        Trainer,
        TrainingArguments,
    )

    # Wrap records list into a HuggingFace Dataset for the Trainer
    dataset = Dataset.from_list(records)

    # ── Tokenizer ─────────────────────────────────────────────
    print("\nLoading tokenizer …")
    tokenizer = AutoTokenizer.from_pretrained(MODEL_NAME, trust_remote_code=True)
    # StarCoder's tokenizer has no pad token by default.
    if tokenizer.pad_token is None:
        tokenizer.pad_token = tokenizer.eos_token

    def tokenize(example):
        return tokenizer(example["text"], truncation=True, max_length=1024)

    tokenized = dataset.map(tokenize, batched=True, remove_columns=["text"])

    # ── Model ─────────────────────────────────────────────────
    print("Loading model …")
    model = AutoModelForCausalLM.from_pretrained(MODEL_NAME, trust_remote_code=True)

    # ── Training args ─────────────────────────────────────────
    output_dir = os.path.join(CHECKPOINT_BASE, f"starcoder_sft_{family_name}")
    os.makedirs(output_dir, exist_ok=True)

    training_args = TrainingArguments(
        output_dir=output_dir,
        per_device_train_batch_size=4,
        gradient_accumulation_steps=4,
        num_train_epochs=3,
        save_total_limit=2,
        save_strategy="epoch",
        logging_dir=os.path.join(output_dir, "logs"),
        fp16=torch.cuda.is_available(),
        report_to="none",
    )

    # ── Trainer ───────────────────────────────────────────────
    trainer = Trainer(
        model=model,
        args=training_args,
        train_dataset=tokenized,
        tokenizer=tokenizer,
        data_collator=DataCollatorForLanguageModeling(tokenizer=tokenizer, mlm=False),
    )

    print(f"\nStarting training for '{family_name}' …")
    trainer.train()

    print(f"\nSaving model to {output_dir} …")
    trainer.save_model(output_dir)
    tokenizer.save_pretrained(output_dir)
    print(f"Done! Model saved to: {output_dir}")


# ──────────────────────────────────────────────────────────────
# Entry point
# ──────────────────────────────────────────────────────────────

def main() -> None:
    parser = argparse.ArgumentParser(
        description="Fine-tune StarCoder on RQ1 datasets by language family."
    )
    parser.add_argument(
        "--family",
        choices=list(FAMILIES.keys()),
        default=None,
        help="Train only this family (default: train all families sequentially).",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Load and validate datasets without running training.",
    )
    args = parser.parse_args()

    families_to_run = [args.family] if args.family else list(FAMILIES.keys())

    for family in families_to_run:
        finetune_family(family, dry_run=args.dry_run)

    print("\n✓ All requested families processed.")


if __name__ == "__main__":
    main()

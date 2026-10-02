"""
RQ3 label validation (reviewer request).

Draws a stratified random sample of 200 labelled RQ3 solutions (seed 0; the 8
complexity classes used in the RQ3 analysis, 25 per class, spread round-robin over
the 9 languages; "Other" is excluded as in code/rq3/2_analysis.py), asks two LLMs
(gpt-oss:20b and gemma4:31b, greedy, seed 0) for the worst-case time complexity of
the code in big-O, maps each answer to a class with the same rules as RQ3
(code/rq3/1_embedding.py:bucket_complexity, after the same LaTeX clean-up as the
scraper), and reports agreement with the label, inter-LLM agreement and Cohen's kappa.

The problem name is not shown, so the models judge the code itself rather than
recalling a known solution.

Outputs:
  results/review/rq3_labels/sample.json            the 200 sampled solutions
  results/review/rq3_labels/<model>.jsonl          raw LLM answers
  results/review/rq3_label_metrics.json            agreement, kappa, confusions

Usage: python code/review/llm_rq3_labels.py [--limit N] [--no-run]
"""

import argparse
import importlib.util
import json
import os
import re
import sys
from collections import Counter

import numpy as np
from sklearn.metrics import cohen_kappa_score

from llm_common import OUT_DIR, ROOT, load_jsonl, run_jobs

import types  # noqa: E402

# code/rq3/1_embedding.py imports the embedding helpers (torch etc.) at module level; only its
# data loading and bucketing functions are needed here, so a stub stands in for code/embedding.py.
_stub = types.ModuleType("embedding")
for _n in ("MODELS", "get_sentence_transformer_embeddings", "get_unixcoder_embeddings",
           "get_openrouter_embeddings", "estimate_costs"):
    setattr(_stub, _n, None)
sys.modules.setdefault("embedding", _stub)
_spec = importlib.util.spec_from_file_location("rq3_embedding", os.path.join(ROOT, "code/rq3/1_embedding.py"))
rq3 = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(rq3)

MODELS = ["gpt-oss:20b", "gemma4:31b"]
PER_CLASS = 25
LANG_NAME = {"python": "Python", "cpp": "C++", "java": "Java", "javascript": "JavaScript", "csharp": "C#",
             "go": "Go", "kotlin": "Kotlin", "rust": "Rust", "swift": "Swift"}
SUPERSCRIPT = str.maketrans({"²": "^2", "³": "^3", "⁴": "^4", "ⁿ": "^n", "ᵏ": "^k", "·": "*", "×": "*", "⋅": "*"})


def prompt(lang: str, code: str) -> str:
    name = LANG_NAME.get(lang, lang)
    return (f"What is the worst-case time complexity of the following {name} code?\n\n"
            f"```{lang}\n{code}\n```\n\n"
            "Use n for the main input size and further variables (m, k, ...) only when needed. "
            "Reply with your answer on the last line in the form: Time: O(...)")


def clean(s: str) -> str:
    """Same clean-up as datasets/RQ3/scrape_neetcode150.py:clean_latex, plus unicode operators."""
    s = s.translate(SUPERSCRIPT).replace("$", "").replace("`", "").replace("**", "")
    s = re.sub(r"\\log", "log", s)
    s = re.sub(r"\\ln", "ln", s)
    s = re.sub(r"\\cdot", "*", s)
    s = re.sub(r"\\times", "*", s)
    s = re.sub(r"\\sqrt\{([^}]+)\}", r"sqrt(\1)", s)
    s = re.sub(r"\\left|\\right", "", s)
    s = re.sub(r"\^\{([^}]+)\}", r"^\1", s)
    s = re.sub(r"\s*\^\s*", "^", s)
    s = re.sub(r"\\", "", s)
    s = re.sub(r"\s+", " ", s)
    return s.strip()


def extract_bigo(text: str) -> str:
    """The big-O expression on the 'Time:' line (or the last O(...) in the reply), balanced parentheses."""
    text = text or ""
    m = list(re.finditer(r"Time\s*[:：]", text, flags=re.I))
    seg = text[m[-1].end():] if m else text
    starts = [k.start() for k in re.finditer(r"O\s*\(", seg)]
    if not starts:
        return ""
    i = starts[0] if m else starts[-1]
    j, depth = seg.index("(", i), 0
    for k in range(j, len(seg)):
        depth += seg[k] == "("
        depth -= seg[k] == ")"
        if depth == 0:
            return clean("O(" + seg[j + 1:k].strip() + ")")
    return clean(seg[i:])


def sample(df):
    df = df[df["complexity_class"] != "Other"].reset_index(drop=True)
    rng = np.random.default_rng(0)
    rows = []
    for cls in rq3.COMPLEXITY_ORDER[:-1]:
        sub = df[df["complexity_class"] == cls]
        by_lang = {l: list(rng.permutation(g.index.values)) for l, g in sub.groupby("language")}
        order = list(rng.permutation(sorted(by_lang)))
        taken = []
        while len(taken) < PER_CLASS and any(by_lang.values()):
            for l in order:
                if by_lang[l] and len(taken) < PER_CLASS:
                    taken.append(by_lang[l].pop(0))
        rows += taken
    s = df.loc[rows]
    return [{"id": f"{r.problem_slug}|{r.language}|{i}", "problem_slug": r.problem_slug, "language": r.language,
             "time_complexity": r.time_complexity, "label": r.complexity_class, "code": r.code}
            for i, r in zip(rows, s.itertuples())]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--limit", type=int, default=None)
    ap.add_argument("--no-run", action="store_true")
    args = ap.parse_args()
    df = rq3.load_rq3_dataset(rq3.DATASET_DIR)
    items = sample(df)
    os.makedirs(os.path.join(OUT_DIR, "rq3_labels"), exist_ok=True)
    with open(os.path.join(OUT_DIR, "rq3_labels", "sample.json"), "w", encoding="utf-8") as f:
        json.dump(items, f, indent=1)
    answers = {}
    for model in MODELS:
        path = os.path.join(OUT_DIR, "rq3_labels", model.replace(":", "_") + ".jsonl")
        jobs = [{"id": it["id"], "model": model, "temperature": 0.0, "seed": 0,
                 "prompt": prompt(it["language"], it["code"])} for it in items]
        if args.limit:
            jobs = jobs[: args.limit]
        if not args.no_run:
            run_jobs(jobs, path, extract=False)
        answers[model] = {r["id"]: r for r in load_jsonl(path) if "error" not in r}

    ids = [it["id"] for it in items if all(it["id"] in answers[m] for m in MODELS)]
    lab = {it["id"]: it["label"] for it in items}
    raw_lab = {it["id"]: it["time_complexity"] for it in items}
    lang = {it["id"]: it["language"] for it in items}
    pred = {m: {i: rq3.bucket_complexity(extract_bigo(answers[m][i].get("text", ""))) for i in ids} for m in MODELS}
    expr = {m: {i: extract_bigo(answers[m][i].get("text", "")) for i in ids} for m in MODELS}
    classes = rq3.COMPLEXITY_ORDER
    out = {"n": len(ids), "per_class_sampled": PER_CLASS,
           "label_distribution": dict(Counter(lab[i] for i in ids)),
           "language_distribution": dict(Counter(lang[i] for i in ids))}
    for m in MODELS:
        y, p = [lab[i] for i in ids], [pred[m][i] for i in ids]
        conf = Counter((a, b) for a, b in zip(y, p) if a != b)
        out[m] = {
            "agreement_with_label": round(float(np.mean([a == b for a, b in zip(y, p)])), 4),
            "kappa_with_label": round(float(cohen_kappa_score(y, p, labels=classes)), 4),
            "unmapped_answers_Other": sum(x == "Other" for x in p),
            "agreement_excluding_Other_answers": round(float(np.mean([a == b for a, b in zip(y, p) if b != "Other"])), 4),
            "per_class_agreement": {c: round(float(np.mean([b == c for a, b in zip(y, p) if a == c])), 4)
                                    for c in classes[:-1]},
            "per_language_agreement": {l: round(float(np.mean([pred[m][i] == lab[i] for i in ids if lang[i] == l])), 4)
                                       for l in sorted(set(lang.values()))},
            "top_confusions_label_to_llm": [[a, b, n] for (a, b), n in conf.most_common(10)],
            "llm_higher_than_label": sum(classes.index(b) > classes.index(a) for a, b in zip(y, p) if b != "Other"),
            "llm_lower_than_label": sum(classes.index(b) < classes.index(a) for a, b in zip(y, p) if b != "Other"),
            "output_cap_hits": sum(answers[m][i].get("finish") == "length" for i in ids),
        }
    a, b = [pred[MODELS[0]][i] for i in ids], [pred[MODELS[1]][i] for i in ids]
    both = [i for i in ids if pred[MODELS[0]][i] == pred[MODELS[1]][i]]
    out["inter_llm"] = {
        "agreement": round(float(np.mean([x == y for x, y in zip(a, b)])), 4),
        "kappa": round(float(cohen_kappa_score(a, b, labels=classes)), 4),
        "n_llms_agree": len(both),
        "label_agreement_when_llms_agree": round(float(np.mean([pred[MODELS[0]][i] == lab[i] for i in both])), 4) if both else None,
        "both_llms_disagree_with_label": sum(pred[MODELS[0]][i] != lab[i] and pred[MODELS[1]][i] != lab[i] for i in ids),
        "both_llms_agree_with_each_other_but_not_label": sum(pred[MODELS[0]][i] != lab[i] for i in both),
    }
    out["examples_llms_agree_against_label"] = [
        {"id": i, "label_raw": raw_lab[i], "label": lab[i], MODELS[0]: expr[MODELS[0]][i], MODELS[1]: expr[MODELS[1]][i]}
        for i in both if pred[MODELS[0]][i] != lab[i]][:40]
    with open(os.path.join(OUT_DIR, "rq3_label_metrics.json"), "w", encoding="utf-8") as f:
        json.dump(out, f, indent=2, ensure_ascii=False)
    print(json.dumps(out, indent=1, ensure_ascii=False))


if __name__ == "__main__":
    main()

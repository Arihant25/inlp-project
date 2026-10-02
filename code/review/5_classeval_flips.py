"""
Review: concentration of ClassEval pass/fail flips between the correct and buggy twin as context.

For each LLM (results/rq5/execution/<llm>/classeval.jsonl), restricted as in
code/rq5/3_evaluate.py to tasks with all three conditions (none, correct, buggy):
  loss = passes with the correct twin as context, fails with the buggy twin
  gain = fails with the correct twin, passes with the buggy twin
Reports the task lists, the number of distinct tasks that account for losses/gains
across LLMs, tasks that flip for >= 2 and all 3 LLMs, and the mutation-operator
mix of flipping tasks against the operator mix of all 81 tasks
(datasets/RQ5/classeval/classeval_pairs.parquet), with a Fisher exact test per
operator (loss tasks vs non-loss tasks).

Output: results/review/classeval_flips.json
"""

import json
import os
import sys
from collections import Counter

import pandas as pd
from scipy.stats import fisher_exact

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from common import OUT_DIR, ROOT, r3

LLMS = ["ollama_gemma4_31b", "ollama_gpt-oss_20b", "ollama_deepseek-v4.1-flash"]


def main():
    pairs = pd.read_parquet(os.path.join(ROOT, "datasets/RQ5/classeval/classeval_pairs.parquet"))
    op = dict(zip(pairs["problem"].astype(int), pairs["mutation_operator"]))
    name = dict(zip(pairs["problem"].astype(int), pairs["class_name"]))
    out = {"per_llm": {}}
    losses, gains = {}, {}
    for llm in LLMS:
        ex = {}
        with open(os.path.join(ROOT, f"results/rq5/execution/{llm}/classeval.jsonl"), encoding="utf-8") as f:
            for line in f:
                r = json.loads(line)
                ex[(r["problem"], r["context"])] = int(r["passed"])
        probs = sorted(p for p in op if all(k in ex for k in [(p, "none"), (p, f"{p}_correct"), (p, f"{p}_buggy")]))
        L = [p for p in probs if ex[(p, f"{p}_correct")] == 1 and ex[(p, f"{p}_buggy")] == 0]
        G = [p for p in probs if ex[(p, f"{p}_correct")] == 0 and ex[(p, f"{p}_buggy")] == 1]
        gm = json.load(open(os.path.join(ROOT, f"results/rq5/generation_metrics_{llm}.json"), encoding="utf-8"))
        mc = gm["per_language"]["classeval"]["mcnemar_correct_vs_buggy"]
        assert (len(L), len(G)) == (mc["a_only"], mc["b_only"]), (llm, len(L), len(G), mc)
        losses[llm], gains[llm] = L, G
        out["per_llm"][llm] = {"n_tasks": len(probs),
                               "losses": [{"problem": p, "class": name[p], "operator": op[p]} for p in L],
                               "gains": [{"problem": p, "class": name[p], "operator": op[p]} for p in G],
                               "n_losses": len(L), "n_gains": len(G),
                               "net_loss": len(L) - len(G), "mcnemar_p_paper": mc["p"]}
    for kind, d in (("losses", losses), ("gains", gains)):
        cnt = Counter(p for v in d.values() for p in v)
        ops = Counter(op[p] for p in cnt)
        base = Counter(op.values())
        enrich = {}
        for o in sorted(base):
            a = sum(1 for p in cnt if op[p] == o); b = len(cnt) - a
            c = base[o] - a; e = (len(op) - len(cnt)) - c
            enrich[o] = {"flip_tasks": a, "all_tasks": base[o], "share_flip": a / len(cnt) if cnt else None,
                         "share_all": base[o] / len(op), "fisher_p": float(fisher_exact([[a, b], [c, e]])[1])}
        out[kind] = {"total_events": sum(len(v) for v in d.values()), "distinct_tasks": len(cnt),
                     "tasks_flipping_for_>=2_llms": sorted(p for p, c in cnt.items() if c >= 2),
                     "tasks_flipping_for_3_llms": sorted(p for p, c in cnt.items() if c == 3),
                     "count_per_task": {str(p): c for p, c in sorted(cnt.items())},
                     "operator_of_distinct_tasks": dict(ops), "operator_enrichment": enrich}
    both = sorted(set(p for v in losses.values() for p in v) & set(p for v in gains.values() for p in v))
    out["tasks_both_loss_and_gain_across_llms"] = both
    out["operator_mix_all_tasks"] = dict(Counter(op.values()))
    os.makedirs(OUT_DIR, exist_ok=True)
    with open(os.path.join(OUT_DIR, "classeval_flips.json"), "w", encoding="utf-8") as f:
        json.dump(r3(out), f, indent=2)
    print(json.dumps({k: v for k, v in out.items() if k != "per_llm"}, indent=1))
    for llm, v in out["per_llm"].items():
        print(llm, "losses", [(x["problem"], x["operator"]) for x in v["losses"]], "gains", [(x["problem"], x["operator"]) for x in v["gains"]])


if __name__ == "__main__":
    main()

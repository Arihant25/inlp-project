"""
RQ5 Step 0: Build correct/buggy class pairs from ClassEval (Du et al., ICSE 2024).

ClassEval provides 100 class-level Python tasks with a skeleton (signatures and
docstrings), a reference solution, and unit tests. For each task we derive one
buggy variant of the reference solution by mutation analysis:

  AOR  arithmetic operator replacement     (+ <-> -, * <-> //, % -> //)
  ROR  relational operator replacement     (< <-> <=, > <-> >=, == <-> !=)
  LCR  logical connector replacement       (and <-> or)
  CRP  integer constant +1                 (k -> k + 1)
  COI  condition negation                  (if c -> if not c)

Mutation sites are visited in a fixed pseudo-random order (seed = task number)
and the first mutant that the task's unit tests kill is kept. Tasks whose
reference solution fails its own tests in our environment are excluded.

Example tests: the doctest examples in the skeleton's docstrings. They are kept
for a task only if the reference solution passes them.

Output: datasets/RQ5/classeval/classeval_pairs.parquet
"""

import ast
import copy
import os
import random
import sys

import pandas as pd

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, SCRIPT_DIR)
from common import PROJECT_ROOT
from execute import run_many

SRC = os.path.join(PROJECT_ROOT, "datasets/RQ5/classeval/classeval.parquet")
OUT = os.path.join(PROJECT_ROOT, "datasets/RQ5/classeval/classeval_pairs.parquet")

SWAPS = {
    ast.Add: ("AOR", ast.Sub), ast.Sub: ("AOR", ast.Add), ast.Mult: ("AOR", ast.FloorDiv),
    ast.FloorDiv: ("AOR", ast.Mult), ast.Mod: ("AOR", ast.FloorDiv),
    ast.Lt: ("ROR", ast.LtE), ast.LtE: ("ROR", ast.Lt), ast.Gt: ("ROR", ast.GtE),
    ast.GtE: ("ROR", ast.Gt), ast.Eq: ("ROR", ast.NotEq), ast.NotEq: ("ROR", ast.Eq),
    ast.And: ("LCR", ast.Or), ast.Or: ("LCR", ast.And),
}


def mutation_sites(tree: ast.AST) -> list[tuple]:
    """List (node_index, kind, operator) for every mutable site in method bodies."""
    sites = []
    for i, node in enumerate(ast.walk(tree)):
        if isinstance(node, (ast.BinOp, ast.AugAssign)) and type(node.op) in SWAPS:
            sites.append((i, "op", SWAPS[type(node.op)][0]))
        elif isinstance(node, ast.BoolOp) and type(node.op) in SWAPS:
            sites.append((i, "boolop", "LCR"))
        elif isinstance(node, ast.Compare) and len(node.ops) == 1 and type(node.ops[0]) in SWAPS:
            sites.append((i, "cmp", "ROR"))
        elif isinstance(node, ast.Constant) and type(node.value) is int:
            sites.append((i, "const", "CRP"))
        elif isinstance(node, (ast.If, ast.While)):
            sites.append((i, "cond", "COI"))
    return sites


def apply_mutation(tree: ast.AST, site: tuple) -> str:
    t = copy.deepcopy(tree)
    node = list(ast.walk(t))[site[0]]
    kind = site[1]
    if kind in ("op", "boolop"):
        node.op = SWAPS[type(node.op)][1]()
    elif kind == "cmp":
        node.ops = [SWAPS[type(node.ops[0])][1]()]
    elif kind == "const":
        node.value = node.value + 1
    elif kind == "cond":
        node.test = ast.UnaryOp(op=ast.Not(), operand=node.test)
    return ast.unparse(ast.fix_missing_locations(t))


def doctest_script(skeleton: str, class_name: str) -> str:
    """A test program that runs the skeleton's docstring examples against the class."""
    return (
        "import doctest, sys\n"
        f"_skeleton = {skeleton!r}\n"
        "_globs = dict(globals())\n"
        "_parser = doctest.DocTestParser()\n"
        "_runner = doctest.DocTestRunner(optionflags=doctest.NORMALIZE_WHITESPACE | doctest.ELLIPSIS)\n"
        "import ast as _ast\n"
        "for _n in _ast.walk(_ast.parse(_skeleton)):\n"
        "    if isinstance(_n, (_ast.FunctionDef, _ast.ClassDef)):\n"
        "        _doc = _ast.get_docstring(_n)\n"
        "        if _doc and '>>>' in _doc:\n"
        "            _runner.run(_parser.get_doctest(_doc, dict(_globs), _n.name, None, 0), out=lambda s: None)\n"
        "_r = _runner.summarize(verbose=False)\n"
        "sys.exit(1 if _r.failed or _r.attempted == 0 else 0)\n"
    )


def main():
    df = pd.read_parquet(SRC)
    df["problem"] = df["task_id"].str.split("_").str[1].astype(int)
    df = df.sort_values("problem").reset_index(drop=True)

    # 1. Reference solutions must pass their own tests.
    ok = run_many([("classeval", r.solution_code, r.test) for r in df.itertuples()])
    df = df[[o["passed"] for o in ok]].reset_index(drop=True)
    print(f"{len(df)} tasks whose reference solution passes its tests")

    rows = []
    for r in df.itertuples():
        tree = ast.parse(r.solution_code)
        sites = mutation_sites(tree)
        random.Random(int(r.problem)).shuffle(sites)
        mutant, op = None, None
        for start in range(0, len(sites), 8):
            batch = sites[start:start + 8]
            codes = []
            for s in batch:
                try:
                    codes.append(apply_mutation(tree, s))
                except Exception:
                    codes.append(None)
            results = run_many([("classeval", c, r.test) for c in codes if c is not None])
            it = iter(results)
            for s, c in zip(batch, codes):
                if c is None:
                    continue
                res = next(it)
                if not res["passed"] and c != ast.unparse(tree):
                    mutant, op = c, s[2]
                    break
            if mutant:
                break
        if mutant is None:
            continue
        rows.append({
            "problem": int(r.problem),
            "class_name": r.class_name,
            "skeleton": r.skeleton,
            "correct": ast.unparse(tree),
            "buggy": mutant,
            "mutation_operator": op,
            "test": r.test,
            "example_test": doctest_script(r.skeleton, r.class_name),
        })
    out = pd.DataFrame(rows)

    # 2. Keep example tests only where the reference solution passes them.
    ex = run_many([("python", c, t) for c, t in zip(out["correct"], out["example_test"])])
    out["has_example_test"] = [e["passed"] for e in ex]
    out.to_parquet(OUT, index=False)
    print(f"{len(out)} tasks with a killed mutant; operators: {out['mutation_operator'].value_counts().to_dict()}")
    print(f"{int(out['has_example_test'].sum())} tasks with usable doctest examples")


if __name__ == "__main__":
    main()

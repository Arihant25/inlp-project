"""Smoke test for the replication package (about 7 minutes on a laptop CPU).

Re-runs the deterministic analysis scripts on the committed embeddings and checks
that every output they write is identical to the committed file. The committed
outputs are restored afterwards, so the test leaves the repository unchanged.
It then runs reproduce/check_claims.py, which compares the numbers in the paper
with the committed results.

No GPU, network access, or API key is needed.

Usage:
    python reproduce/smoke_test.py            # all steps
    python reproduce/smoke_test.py --quick    # skip the three slowest steps
"""

import argparse
import json
import math
import os
import shutil
import subprocess
import sys
import tempfile
import time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# (description, command, outputs the command rewrites, slow)
STEPS = [
    ("RQ2 framework separation (Octen)", ["code/rq2/2_analysis.py", "--model", "octen"],
     ["results/rq2/octen"], False),
    ("RQ3 complexity separation (Octen)", ["code/rq3/2_analysis.py", "--model", "octen"],
     ["results/rq3/octen"], False),
    ("RQ4 bug-fix separation (Octen)", ["code/RQ4/2_analysis.py", "--model", "octen"],
     ["results/RQ4/octen"], False),
    ("RQ4 relative pair distance R, all nine models", ["code/RQ4/5_relative_distance.py"],
     ["results/RQ4/relative_distance.json"], False),
    ("RQ1 cross-model family stability", ["code/family_stability.py"],
     ["results/clustering"], False),
    ("RQ5 ClassEval generation flips", ["code/review/5_classeval_flips.py"],
     ["results/review/classeval_flips.json"], False),
    ("Problem-level confidence intervals", ["code/review/6_confidence_intervals.py"],
     ["results/review/confidence_intervals.json"], False),
    ("Permutation tests", ["code/review/3_permutation_tests.py"],
     ["results/review/permutation_tests.json"], True),
    ("Bootstrap intervals of Cohen's d and R (Tables 2 to 4)", ["code/review/7_effect_size_cis.py"],
     ["results/review/effect_size_cis.json"], True),
    ("Token baselines (TF-IDF, token edit distance)", ["code/review/1_lexical_baselines.py"],
     ["results/review/lexical_baselines.json"], True),
]


def files_under(path):
    full = os.path.join(ROOT, path)
    if os.path.isfile(full):
        return [path]
    out = []
    for d, _, names in os.walk(full):
        for n in names:
            if n.endswith((".json", ".csv", ".txt")):
                out.append(os.path.relpath(os.path.join(d, n), ROOT).replace(os.sep, "/"))
    return sorted(out)


def same_json(a, b, tol=1e-9):
    if isinstance(a, dict) and isinstance(b, dict):
        return a.keys() == b.keys() and all(same_json(a[k], b[k], tol) for k in a)
    if isinstance(a, list) and isinstance(b, list):
        return len(a) == len(b) and all(same_json(x, y, tol) for x, y in zip(a, b))
    if isinstance(a, (int, float)) and isinstance(b, (int, float)):
        if isinstance(a, float) and isinstance(b, float) and math.isnan(a) and math.isnan(b):
            return True
        return abs(a - b) <= tol * max(1.0, abs(a), abs(b))
    return a == b


def same_file(old, new):
    with open(old, "rb") as f:
        a = f.read()
    with open(new, "rb") as f:
        b = f.read()
    if a == b:
        return True
    if old.endswith(".json"):
        return same_json(json.loads(a), json.loads(b))
    return a.replace(b"\r\n", b"\n") == b.replace(b"\r\n", b"\n")


def run_step(desc, cmd, outputs, env):
    tracked = [f for p in outputs for f in files_under(p)]
    backup = tempfile.mkdtemp(prefix="smoke_")
    for f in tracked:
        dst = os.path.join(backup, f)
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        shutil.copy2(os.path.join(ROOT, f), dst)
    start = time.time()
    proc = subprocess.run([sys.executable] + cmd, cwd=ROOT, env=env,
                          stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, text=True,
                          encoding="utf-8", errors="replace")
    secs = time.time() - start
    try:
        if proc.returncode != 0:
            return False, secs, "script failed:\n" + proc.stderr[-2000:]
        changed = [f for f in tracked if not same_file(os.path.join(backup, f), os.path.join(ROOT, f))]
        if changed:
            return False, secs, "outputs differ from the committed files: " + ", ".join(changed)
        return True, secs, f"{len(tracked)} output files identical"
    finally:
        for f in tracked:
            shutil.copy2(os.path.join(backup, f), os.path.join(ROOT, f))
        shutil.rmtree(backup, ignore_errors=True)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--quick", action="store_true", help="skip the three slowest steps (about 1 minute)")
    args = ap.parse_args()

    env = dict(os.environ, PYTHONIOENCODING="utf-8", PYTHONUTF8="1", MPLBACKEND="Agg")
    failures = 0
    for desc, cmd, outputs, slow in STEPS:
        if slow and args.quick:
            print(f"[SKIP] {desc}")
            continue
        ok, secs, msg = run_step(desc, cmd, outputs, env)
        failures += not ok
        print(f"[{'PASS' if ok else 'FAIL'}] {desc} ({secs:.0f}s): {msg}", flush=True)

    checker = os.path.join(ROOT, "reproduce", "check_claims.py")
    if os.path.exists(checker):
        print("\nComparing the paper's numbers with the committed results ...", flush=True)
        proc = subprocess.run([sys.executable, checker, "--summary"], cwd=ROOT, env=env)
        failures += proc.returncode != 0

    print("\nSmoke test " + ("passed." if failures == 0 else f"FAILED ({failures} step(s))."))
    sys.exit(1 if failures else 0)


if __name__ == "__main__":
    main()

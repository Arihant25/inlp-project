"""
RQ5: Test execution for Python, JavaScript, and Java programs from HumanEvalPack.

Each program runs in its own temporary directory with a timeout. The harness is
validated by running every canonical solution (must pass) and every buggy
solution (should fail) against the hidden tests (see validate_harness).

Note on JavaScript: HumanEvalPack tests use console.assert, which only prints a
message on failure and exits with status 0. We replace console.assert with a
version that throws, so failures are detected.
"""

import os
import re
import shutil
import subprocess
import sys
import tempfile
from concurrent.futures import ThreadPoolExecutor

JS_ASSERT_PATCH = (
    "console.assert = (cond, ...msg) => { if (!cond) { "
    "throw new Error('Assertion failed ' + msg.join(' ')); } };\n"
)
PYTHON_PRELUDE = "import math\nimport re\nimport sys\nimport string\nimport hashlib\nimport heapq\nimport collections\nfrom typing import *\n"
JAVA_PRELUDE = "import java.util.*;\nimport java.lang.*;\nimport java.util.stream.*;\nimport java.math.*;\nimport java.security.*;\n"

# Node resolves test dependencies (js-md5) from code/rq5/node_modules (npm install --prefix code/rq5).
RUN_ENV = dict(os.environ, NODE_PATH=os.path.join(os.path.dirname(os.path.abspath(__file__)), "node_modules"))

TIMEOUT_S = {"python": 15, "classeval": 30, "js": 15, "java": 60}


def _java_program(code: str, test: str) -> str:
    # The test defines `public class Main`, so Solution must not be public.
    code = re.sub(r"\bpublic\s+class\s+Solution\b", "class Solution", code)
    imports = [l for l in code.splitlines() if l.strip().startswith("import ")]
    body = "\n".join(l for l in code.splitlines() if not l.strip().startswith("import "))
    return JAVA_PRELUDE + "\n".join(imports) + "\n" + body + "\n\n" + test


def run_program(lang: str, code: str, test: str) -> dict:
    """Run `code` followed by `test`. Returns {'passed': bool, 'status': str}."""
    tmp = tempfile.mkdtemp(prefix=f"rq5_{lang}_")
    try:
        if lang == "python":
            path = os.path.join(tmp, "prog.py")
            src = PYTHON_PRELUDE + code + "\n\n" + test + "\n"
            cmds = [[sys.executable, path]]
        elif lang == "classeval":
            # ClassEval tests are unittest.TestCase classes; exit status reflects the result.
            path = os.path.join(tmp, "prog.py")
            src = (PYTHON_PRELUDE + code + "\n\n" + test + "\n\nif __name__ == '__main__':\n"
                   "    import unittest\n    unittest.main(verbosity=0)\n")
            cmds = [[sys.executable, path]]
        elif lang == "js":
            path = os.path.join(tmp, "prog.js")
            src = JS_ASSERT_PATCH + code + "\n\n" + test + "\n"
            cmds = [["node", path]]
        elif lang == "java":
            path = os.path.join(tmp, "Main.java")
            src = _java_program(code, test)
            cmds = [["javac", "-nowarn", "Main.java"], ["java", "-cp", ".", "Main"]]
        else:
            raise ValueError(lang)
        with open(path, "w", encoding="utf-8") as f:
            f.write(src)
        for cmd in cmds:
            try:
                proc = subprocess.run(
                    cmd, cwd=tmp, capture_output=True, text=True, env=RUN_ENV,
                    timeout=TIMEOUT_S[lang], encoding="utf-8", errors="replace",
                )
            except subprocess.TimeoutExpired:
                return {"passed": False, "status": "timeout"}
            if proc.returncode != 0:
                stage = "compile_error" if cmd[0] == "javac" else "failed"
                return {"passed": False, "status": stage, "stderr": proc.stderr[-500:]}
        return {"passed": True, "status": "passed"}
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def run_many(jobs: list[tuple[str, str, str]], workers: int = 8) -> list[dict]:
    """Run many (lang, code, test) jobs in parallel, preserving order."""
    with ThreadPoolExecutor(max_workers=workers) as ex:
        return list(ex.map(lambda j: run_program(*j), jobs))


def validate_harness(lang: str) -> dict:
    """Canonical solutions must pass the hidden tests; buggy ones should fail."""
    from common import build_corpus, load_language

    df = load_language(lang)
    docs, _ = build_corpus(lang)
    tests = dict(zip(df["problem"], df["test"]))
    jobs = [(lang, r.code, tests[r.problem]) for r in docs.itertuples()]
    results = run_many(jobs)
    out = {"correct_pass": 0, "correct_total": 0, "buggy_pass": 0, "buggy_total": 0, "failures": []}
    for r, res in zip(docs.itertuples(), results):
        out[f"{r.variant}_total"] += 1
        out[f"{r.variant}_pass"] += int(res["passed"])
        if r.variant == "correct" and not res["passed"]:
            out["failures"].append({"problem": r.problem, **res})
    return out


if __name__ == "__main__":
    import json

    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    for lang in sys.argv[1:] or ["python", "js", "java"]:
        v = validate_harness(lang)
        print(lang, json.dumps({k: v[k] for k in v if k != "failures"}), v["failures"][:3])

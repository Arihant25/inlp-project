# Installation and Smoke Test

Installation takes about 5 minutes and the smoke test about 7 minutes. Neither needs a GPU, network access after installation, or an API key.

## Option A: Docker

```bash
docker build -t code-embeddings .
docker run --rm code-embeddings
```

The image contains Python 3.12 with the pinned analysis packages, Node.js, and JDK 17.

## Option B: Python

Python 3.11 or 3.12:

```bash
python -m venv .venv
source .venv/bin/activate            # Windows: .venv\Scripts\activate
pip install -r env/requirements-analysis.txt
python reproduce/smoke_test.py
```

With [uv](https://docs.astral.sh/uv/), `uv sync` followed by `uv run reproduce/smoke_test.py` gives the same result.

## What the Smoke Test Does

1. It re-runs the analysis scripts of RQ1 to RQ5 on the committed embeddings, per-item results, and test results, and it checks that every output file is identical to the committed one. The committed files are restored afterwards.
2. It runs `reproduce/check_claims.py`, which recomputes the numbers in the paper from the committed results and compares them with the printed values.

Expected output (times from a laptop CPU):

```text
[PASS] RQ2 framework separation (Octen) (4s): 1 output files identical
[PASS] RQ3 complexity separation (Octen) (11s): 2 output files identical
[PASS] RQ4 bug-fix separation (Octen) (9s): 1 output files identical
[PASS] RQ4 relative pair distance R, all nine models (1s): 1 output files identical
[PASS] RQ1 cross-model family stability (0s): 21 output files identical
[PASS] RQ5 ClassEval generation flips (2s): 1 output files identical
[PASS] Problem-level confidence intervals (4s): 1 output files identical
[PASS] Permutation tests (21s): 1 output files identical
[PASS] Bootstrap intervals of Cohen's d and R (Tables 2 to 4) (165s): 1 output files identical
[PASS] Token baselines (TF-IDF, token edit distance) (112s): 1 output files identical
...
Smoke test passed.
```

`python reproduce/smoke_test.py --quick` skips the three slowest steps.

## Further Checks

- `python reproduce/check_claims.py` prints the full table of paper claims with the recomputed value and its source file.
- `python code/rq5/execute.py` checks the RQ5 test harness in about 3 minutes: all 164 correct HumanEvalFix solutions pass their hidden tests and all 164 buggy ones fail, in Python, JavaScript, and Java. It needs Node.js, JDK 17, and `npm install --prefix code/rq5`, all included in the Docker image.
- `python code/make_figures.py` regenerates the paper's figures into `results/figures/`.

For a full re-run that re-embeds every corpus and repeats the LLM generations, see the README and [`REQUIREMENTS.md`](REQUIREMENTS.md).

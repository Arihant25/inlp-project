# Status

We apply for the **Available**, **Functional**, and **Reusable** badges.

## Available

The package will be archived on Zenodo with a DOI and an open license (MIT, see [`LICENSE`](LICENSE)) after acceptance. During review it is available at the anonymized mirror cited in the paper.

## Functional

- **Documented:** the README maps every RQ, table, and figure of the paper to the script that computes it and the result file it writes. [`reproduce/claims_report.md`](reproduce/claims_report.md) does the same for each individual number.
- **Consistent and complete:** the package contains every corpus, every LLM generation and judgment, every test result, and every metric file behind the paper. The embeddings that the RQ1 to RQ5 analyses read are committed (the RQ1 files through Git LFS). Only the embeddings of the behavior-preserving rewrites and of the RQ1 bootstrap are left out for size, and the released scripts regenerate them.
- **Exercisable:** `reproduce/check_claims.py` recomputes the paper's numbers from the committed results in about a minute. `reproduce/smoke_test.py` re-runs the analyses and verifies that their outputs are identical to the committed ones. Neither needs a GPU, network access, or an API key.

## Reusable

- **Pinned environments:** `env/requirements-*.txt`, `uv.lock`, and a Dockerfile.
- **New models:** an embedding model is added with one entry in the `MODELS` registry of `code/embedding.py`. Every RQ then accepts it through `--model`.
- **New corpora:** the behavior-preserving rewrite generator (`code/review/variants.py`) writes renames, operand swaps, statement swaps, and loop rewrites for Python, JavaScript, and Java, and keeps only rewrites that pass the hidden tests. It reads programs in the HumanEvalPack and ClassEval format, and the RQ4 pairs follow a documented [format](datasets/RQ4/README.md).
- **Retrieval risk check:** `code/rq5/2_retrieval.py` measures, for any embedder, how often a buggy near-duplicate outranks its fix, and `code/rq5/execute.py` is a standalone test harness for Python, JavaScript, and Java with a self-check.
- **Datasets for other studies:** the 1,024 framework snippets (RQ2), the 4,662 solutions with complexity labels (RQ3), the 500 bug-fix pairs (RQ4), the 81 test-killed ClassEval mutants, and the 3,648 validated behavior-preserving rewrites.

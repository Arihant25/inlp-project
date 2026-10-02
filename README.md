# What Do Code Embedding Models Encode? Replication Package

Replication package for the paper *What Do Code Embedding Models Encode? An Empirical Study of Nine Models Against Token Baselines*.

The study measures which properties of code shape the cosine similarity of nine embedding models: language family (RQ1), framework (RQ2), complexity class (RQ3), and correctness (RQ4). It compares the models with token-level baselines and measures the cost of correctness-blind retrieval in retrieval-augmented generation with three LLMs (RQ5).

The package contains every corpus, script, LLM generation, test result, and metric file behind the paper. **Every number in the paper can be checked against the committed results in about a minute, without a GPU, network access, or an API key.**

| Document | Content |
| --- | --- |
| [`INSTALL.md`](INSTALL.md) | Installation and a smoke test (under 10 minutes) |
| [`REQUIREMENTS.md`](REQUIREMENTS.md) | Hardware and software requirements, pinned versions |
| [`STATUS.md`](STATUS.md) | Badges we apply for and why |
| [`LICENSE`](LICENSE) | MIT for our code and data, third-party licenses listed below |
| [`reproduce/claims_report.md`](reproduce/claims_report.md) | Every checked claim of the paper, with its result file and the script that writes it |

## Quick Start

```bash
pip install -r env/requirements-analysis.txt   # Python 3.11 or 3.12, CPU only
python reproduce/check_claims.py               # compare the paper's numbers with the committed results (~1 minute)
python reproduce/smoke_test.py                 # re-run the analyses on the committed embeddings (~7 minutes)
```

Or with Docker:

```bash
docker build -t code-embeddings .
docker run --rm code-embeddings                # runs reproduce/smoke_test.py
```

## Three Levels of Reproduction

| Level | What it does | Needs | Time |
| --- | --- | --- | --- |
| 1. Check claims | `reproduce/check_claims.py` recomputes the paper's numbers from the committed metric files and per-item results | CPU | ~1 minute |
| 2. Re-run analyses | `reproduce/smoke_test.py` re-runs the analysis scripts on the committed embeddings and verifies that every output is identical to the committed one | CPU | ~7 minutes |
| 3. Full re-run | Re-embed every corpus and re-run generation with the commands below | GPU with 8 GB, Ollama Cloud and OpenRouter accounts | hours |

Level 3 regenerates every embedding, including those of the behavior-preserving rewrites and the RQ1 bootstrap, which are not committed for size. Ada-002 embeddings (OpenRouter) and the RQ5 generations (Ollama Cloud) are the only steps that call an external service. All their outputs are committed, so levels 1 and 2 never call them.

## Models

All embedding scripts accept `--model KEY`, or `--model all` (the default).

| Key | Model | Group |
| --- | --- | --- |
| `octen` | Octen/Octen-Embedding-0.6B | core |
| `qwen3` | Qwen/Qwen3-Embedding-0.6B | core |
| `bge_m3` | BAAI/bge-m3 | core |
| `minilm` | sentence-transformers/all-MiniLM-L6-v2 | core |
| `unixcoder` | microsoft/unixcoder-base | core |
| `codebert` | microsoft/codebert-base | core |
| `ada002` | openai/text-embedding-ada-002 (via OpenRouter) | core, RQ1 to RQ4 only |
| `jina_code` | jinaai/jina-embeddings-v2-base-code | code retriever |
| `codesage` | codesage/codesage-base-v2 | code retriever |

`jina_code` needs `env/requirements-jina.txt` (transformers 4.46), because its remote code does not run under transformers 5. All other models use `env/requirements-gpu.txt`.

The token baselines (TF-IDF on identifier-aware tokens, normalized token edit distance, and BM25 for RQ5) are computed in `code/review/1_lexical_baselines.py` and `code/rq5/2_retrieval.py`.

## Where Each Result Comes From

| Paper element | Script | Result file |
| --- | --- | --- |
| RQ1 dendrograms, CCC, Spearman, family overlap | `code/clustering.py`, `code/family_stability.py`, `code/model_distance.py` | `results/clustering/` |
| RQ1 ARI across k, chance null, within-model bootstrap | `code/review/rq1_robustness.py`, `code/review/rq1_bootstrap.py` | `results/review/rq1_*.json` |
| RQ2 table (silhouette, within-language framework split) | `code/rq2/2_analysis.py` | `results/rq2/<model>/` |
| RQ3 table (complexity classes) | `code/rq3/2_analysis.py` | `results/rq3/<model>/` |
| RQ3 label agreement with LLM judges | `code/review/llm_rq3_labels.py` | `results/review/rq3_labels/` |
| RQ4 table (relative pair distance R, bug categories) | `code/RQ4/2_analysis.py`, `code/RQ4/5_relative_distance.py` | `results/RQ4/` |
| RQ4 behavior-preserving rewrites | `code/review/variants.py`, `embed_variants.py`, `analyze.py`, `analyze_kinds.py`, `2_edit_size_control.py` | `results/review/control_*.json`, `edit_size_control.json`, `pairs.parquet`, `variants.parquet` |
| Token baselines in RQ2 to RQ5 | `code/review/1_lexical_baselines.py` | `results/review/lexical_baselines.json` |
| Code retrievers (jina-code, CodeSage) | `code/review/rq1_new_embedders.py`, `rq5_new_embedders.py`, `new_embedders_summary.py` | `results/review/*new_embedders*.json` |
| Permutation tests, confidence intervals (Tables 2 to 4 and RQ5) | `code/review/3_permutation_tests.py`, `6_confidence_intervals.py`, `7_effect_size_cis.py` | `results/review/permutation_tests.json`, `confidence_intervals.json`, `effect_size_cis.json` |
| RQ5 retrieval table, example-test filtering | `code/rq5/2_retrieval.py`, `code/review/4_rq5_clustered_tests.py` | `results/rq5/` |
| RQ5 generation table | `code/rq5/generate.py`, `code/rq5/3_evaluate.py` | `results/rq5/` |
| RQ5 five samples, warning framing, top-3 context, LLM judge | `code/review/llm_rq5_generate.py`, `llm_judge.py`, `evaluate_review.py`, `5_classeval_flips.py` | `results/review/framing*`, `judge*`, `classeval_flips.json` |
| Truncation | `code/truncation_stats.py`, `code/truncation_sensitivity.py`, `code/review/truncation_new_embedders.py` | `results/truncation_*.json`, `results/review/truncation_new_embedders.json` |
| Figures | `code/make_figures.py` | `results/figures/` |

[`reproduce/claims_report.md`](reproduce/claims_report.md) lists each checked number individually.

## Datasets

| Directory | Content | Origin |
| --- | --- | --- |
| `datasets/family_clustering/` | RQ1: 21 linguistic features in 19 languages (399 files) | Released by Yun et al. (FSE 2026), used unchanged |
| `datasets/RQ2/` | RQ2: 1,024 snippets, 8 languages, 32 frameworks, 8 patterns, 4 variations, with the prompts and generation scripts | Generated by us with Gemini 2.5 Pro, validated by two authors |
| `datasets/RQ3/` | RQ3: 4,662 NeetCode 150 solutions in 9 languages with complexity labels, and the scraper | Problem list from the MIT-licensed `neetcode-gh/leetcode` repository |
| `datasets/RQ4/` | RQ4: 500 bug-fix pairs, 100 bug types, 5 languages ([README](datasets/RQ4/README.md)) | Generated by us, validated by two authors |
| `datasets/RQ5/humanevalpack/` | HumanEvalFix in 6 languages | HumanEvalPack (Muennighoff et al., ICLR 2024), MIT, unchanged |
| `datasets/RQ5/classeval/` | ClassEval tasks and 81 test-killed mutants | ClassEval (Du et al., ICSE 2024), MIT, plus our mutants |

The RQ4 bug categories appear in the files as severity levels: Easy (syntax), Medium (logic and control flow), Hard (state and algorithmic), and Super Hard (numeric, memory, concurrency).

## Full Re-run

Install the embedding environment (`env/requirements-gpu.txt`). For Ada-002 and RQ5 generation, copy `.env.example` to `.env` and set `OPENROUTER_API_KEY` and `OLLAMA_API_KEY`. Each RQ runs embed, analyze, visualize, and compare in order.

```bash
# RQ1
python code/embedding.py --model all
python code/clustering.py --model all
python code/family_stability.py
python code/model_distance.py
python code/review/rq1_reembed.py && python code/review/rq1_robustness.py && python code/review/rq1_bootstrap.py

# RQ2, RQ3, RQ4 (code/rq2, code/rq3, code/RQ4)
python code/rq2/1_embedding.py --model all
python code/rq2/2_analysis.py --model all
python code/rq2/3_visualize.py --model all
python code/rq2/4_cross_model.py
#   ... the same four steps for code/rq3 and code/RQ4, then
python code/RQ4/5_relative_distance.py

# Code retrievers (jina_code with env/requirements-jina.txt)
for m in jina_code codesage; do
  for rq in rq1 rq2 rq3; do python code/review/embed_resumable.py --rq $rq --model $m; done
  python code/RQ4/1_embedding.py --model $m
done
python code/review/rq1_new_embedders.py
python code/review/rq5_new_embedders.py
python code/review/new_embedders_summary.py

# Token baselines, behavior-preserving rewrites, and statistics
python code/review/1_lexical_baselines.py
python code/review/variants.py && python code/review/embed_variants.py
python code/review/analyze.py && python code/review/analyze_kinds.py
python code/review/2_edit_size_control.py
python code/review/3_permutation_tests.py
python code/review/6_confidence_intervals.py
python code/review/7_effect_size_cis.py

# RQ5 retrieval
python code/rq5/0_prepare_classeval.py
python code/rq5/1_embedding.py
python code/rq5/1_embedding.py --dataset classeval
python code/rq5/2_retrieval.py
python code/review/4_rq5_clustered_tests.py

# RQ5 generation (Ollama Cloud) and test execution
python code/rq5/generate.py --llm ollama:gemma4:31b --lang python --contexts core
python code/rq5/generate.py --llm ollama:gemma4:31b --lang python --contexts needed
python code/rq5/3_evaluate.py --llm ollama_gemma4_31b
#   ... the same for gpt-oss:20b and deepseek-v4.1-flash and every language, then
for llm in gemma4:31b gpt-oss:20b deepseek-v4.1-flash; do
  for task in samples framing top3; do python code/review/llm_rq5_generate.py --task $task --model $llm; done
done
python code/review/llm_judge.py
for llm in gemma4:31b gpt-oss:20b deepseek-v4.1-flash; do python code/review/llm_rq5_generate.py --task judged --model $llm; done
for task in samples framing top3 judged; do python code/review/evaluate_review.py --task $task; done
python code/review/5_classeval_flips.py

# Figures
python code/make_figures.py
```

The RQ5 LLMs run on Ollama Cloud with temperature 0, seed 0, and a 65,536-token output cap (34 DeepSeek responses reach it and count as failures). The five-sample runs use temperature 0.8. `code/rq5/execute.py` runs Python, JavaScript (Node.js), and Java (JDK 17) programs, and `python code/rq5/execute.py` validates the harness (every correct solution passes, every buggy one fails). One JavaScript test needs `js-md5` (`npm install --prefix code/rq5`).

Hosted LLMs can change behind a fixed name, so a re-run of generation may differ slightly from the committed generations. The committed generations and test results are the ones the paper reports.

## Repository Layout

```text
code/                 embedding, analysis, and figure scripts (one folder per RQ)
code/review/          token baselines, behavior-preserving rewrites, code retrievers, statistics, RQ5 variants
datasets/             all corpora (see Datasets)
env/                  pinned environments (analysis, GPU, jina)
reproduce/            check_claims.py, smoke_test.py, claims_report.md
results/              every metric file, per-item result, LLM generation, and test result
```

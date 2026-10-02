# Requirements

## Levels 1 and 2 (check claims, re-run analyses)

- **Hardware:** any x86-64 or ARM machine with 4 GB of RAM and 4 GB of free disk. No GPU.
- **OS:** Linux, macOS, or Windows. The package was run on Windows 11 and in the Docker image (Debian 12).
- **Software:** Python 3.11 or 3.12 with the packages pinned in [`env/requirements-analysis.txt`](env/requirements-analysis.txt) (NumPy 2.4.2, pandas 3.0.1, SciPy 1.17.1, scikit-learn 1.8.0, statsmodels 0.15.0, rank-bm25 0.2.2), or Docker.
- **Network:** none after installation.

## Level 3 (full re-run)

- **Hardware:** an NVIDIA GPU with 8 GB of memory (the study used a GeForce RTX 2070 Max-Q with CUDA 12.6), 16 GB of RAM, and 30 GB of free disk for model weights and intermediate embeddings.
- **Software:**
  - [`env/requirements-gpu.txt`](env/requirements-gpu.txt): PyTorch 2.10.0 (CUDA 12.6), transformers 5.2.0, sentence-transformers 5.2.3. Used for every model except jina-code.
  - [`env/requirements-jina.txt`](env/requirements-jina.txt): transformers 4.46.3 and sentence-transformers 3.3.1, for jina-embeddings-v2-base-code, whose remote code does not run under transformers 5.
  - Node.js and JDK 17 for executing the RQ5 JavaScript and Java tests.
- **Services:** an OpenRouter account for Ada-002 embeddings and an Ollama Cloud account for the RQ5 generations (`gemma4:31b`, `gpt-oss:20b`, `deepseek-v4.1-flash`). Keys go in `.env` (see `.env.example`) and are read only by `code/embedding.py`, `code/rq5/generate.py`, and `code/review/llm_*.py`.

`pyproject.toml` and `uv.lock` describe the same dependencies for [uv](https://docs.astral.sh/uv/) users (`uv sync`, or `uv sync --extra embed` for level 3).

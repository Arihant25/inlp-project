# Analysis image for levels 1 and 2 of the replication package (no GPU, no network at run time).
#   docker build -t code-embeddings .
#   docker run --rm code-embeddings                                       # smoke test
#   docker run --rm code-embeddings python reproduce/check_claims.py      # full claim table
#   docker run --rm code-embeddings python code/rq5/execute.py            # RQ5 test-harness self-check
FROM python:3.12-slim-bookworm

RUN apt-get update \
 && apt-get install -y --no-install-recommends nodejs npm openjdk-17-jdk-headless \
 && rm -rf /var/lib/apt/lists/*

WORKDIR /artifact
COPY env/requirements-analysis.txt env/
RUN pip install --no-cache-dir -r env/requirements-analysis.txt

COPY . .
RUN npm install --prefix code/rq5 --no-audit --no-fund

ENV PYTHONUTF8=1 PYTHONIOENCODING=utf-8 MPLBACKEND=Agg
CMD ["python", "reproduce/smoke_test.py"]

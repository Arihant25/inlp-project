# RQ4: Correctness Regions and Bug Patterns — Analysis Report

## 1. Research Question

> *Do correct implementations cluster separately from buggy ones within language families? Can we identify "dangerous neighbourhoods" in embedding space where semantically similar code exhibits mixed correctness?*

This question probes whether code-embedding models encode **correctness** as a meaningful dimension, or whether buggy and fixed code are representationally indistinguishable. A practical consequence is that AI-assisted code review tools relying on embedding similarity may fail to flag incorrect code if it lives too close to correct code in the latent space.

---

## 2. Experimental Setup

### 2.1 Dataset

| Property | Value |
|----------|-------|
| Source | `datasets/RQ4/bugs.json` (Gemini-generated correct–buggy pairs) |
| Bug types | 100 (spanning syntax, logic, semantic, and language-specific bugs) |
| Languages | C, Go, Java, Python, Swift |
| Code types per bug | `buggy` and `fixed` |
| Total snippets | **1,000** (100 bugs × 5 languages × 2 code types) |

### 2.2 Bug Severity Classification

Bugs are grouped into four severity tiers based on their index position in the dataset, following the proposal's classification framework:

| Severity | Indices | Count | Description |
|----------|---------|-------|-------------|
| **Easy** | 0–21 | 220 | Syntax errors — missing semicolons, misspelled keywords, bracket mismatches |
| **Medium** | 22–49 | 280 | Logic/control-flow errors — wrong loop conditions, missing base cases, data-structure misuse |
| **Hard** | 50–80 | 310 | Subtle semantic bugs — off-by-one, null handling, shallow vs deep copy, numeric precision |
| **Super Hard** | 81–99 | 190 | Language-specific footguns — mutable default args (Python), integer overflow (C), closure capture (Swift) |

### 2.3 Embedding Models

Three models were evaluated to establish cross-model robustness:

| Model Key | Model Name | Dim | Type |
|-----------|-----------|-----|------|
| `octen` | Octen/Octen-Embedding-0.6B | 1,024 | Code-specialised |
| `minilm` | sentence-transformers/all-MiniLM-L6-v2 | 384 | General-purpose (lightweight) |
| `bge_m3` | BAAI/bge-m3 | 1,024 | Multilingual general-purpose |

### 2.4 Metrics

1. **Correctness Silhouette Score** — clustering quality when labels are `buggy` vs `fixed` (within each language/severity). Baseline comparisons against language-label and severity-label silhouettes.
2. **Pairwise Buggy–Fixed Cosine Distance** — for each bug type and language, the cosine distance between the buggy and fixed embedding of the same algorithm.
3. **Intra- vs Cross-Cluster Distance** — mean pairwise cosine distance within buggy/fixed groups vs across them, per language, with a separation score.
4. **Dangerous Neighbourhoods** — percentage of (bug, language) pairs whose buggy–fixed distance falls below a threshold (0.05, 0.10, 0.15), indicating the model cannot distinguish them.
5. **Statistical Tests** — Welch's t-test on cross-cluster vs intra-cluster distances, Cohen's *d* effect size, and bootstrap 95% confidence intervals (5,000 resamples).

---

## 3. Results

### 3.1 Overall Correctness Clustering Is Near-Zero

| Metric | Octen | MiniLM | BGE-M3 |
|--------|:-----:|:------:|:------:|
| Correctness silhouette (overall) | **0.0095** | **0.0129** | **0.0194** |
| Language baseline silhouette | 0.0391 | 0.0535 | 0.0068 |
| Severity baseline silhouette | 0.0253 | 0.0220 | 0.0201 |

**Key finding:** Across all three models the correctness silhouette is essentially zero (range 0.01–0.02). For Octen and MiniLM, embeddings cluster more strongly by *language* (4× higher silhouette) and by *severity* (2× higher) than by correctness. BGE-M3 is notable for having a near-zero language baseline (0.007), suggesting it produces more language-agnostic representations — yet even there, correctness separation is negligible.

**Interpretation:** None of the tested models encode a meaningful "correctness axis" in their embedding spaces. Buggy and fixed code of the same algorithm are representationally overlapping.

### 3.2 Per-Severity Correctness Silhouette

| Severity | Octen | MiniLM | BGE-M3 |
|----------|:-----:|:------:|:------:|
| Easy | 0.0518 | 0.0445 | **0.0629** |
| Medium | 0.0041 | 0.0051 | 0.0134 |
| Hard | 0.0025 | 0.0047 | 0.0097 |
| Super Hard | **−0.0019** | 0.0073 | 0.0140 |

**Key finding:** Easy (syntax) bugs consistently produce the highest correctness silhouette across all models — roughly 3–5× higher than other severity tiers. This is intuitive: a missing semicolon or misspelled keyword creates a visually (and tokenically) distinct string.

For Octen, Super Hard bugs yield a **negative** silhouette (−0.0019), meaning buggy code is *more similar to the wrong cluster* than to its own — the worst possible outcome for a correctness detector. MiniLM and BGE-M3 keep Super Hard slightly positive but still negligible.

### 3.3 Pairwise Buggy–Fixed Distances

| Metric | Octen | MiniLM | BGE-M3 |
|--------|:-----:|:------:|:------:|
| Overall mean distance | 0.1212 | 0.1404 | 0.1228 |
| Overall std | 0.0999 | 0.1128 | 0.0879 |

#### By severity:

| Severity | Octen | MiniLM | BGE-M3 |
|----------|:-----:|:------:|:------:|
| Easy | **0.2365** | **0.2085** | **0.2027** |
| Medium | 0.1082 | 0.1064 | 0.1183 |
| Hard | 0.0963 | 0.1056 | 0.1121 |
| Super Hard | 0.0886 | 0.1276 | 0.1288 |

**Key finding:** The overall pairwise distance between a buggy snippet and its fixed counterpart is remarkably small — only 0.12–0.14 on the cosine scale \[0, 2\]. Easy bugs are most separable (≈0.20–0.24), while Medium/Hard/Super Hard bugs hover around 0.09–0.13.

The proposal's expected-behaviour thresholds predicted:
- Easy: distance > 0.5
- Medium: distance 0.2–0.5
- Hard: distance < 0.2

**Observed reality is far below these expectations.** Even Easy bugs only reach ≈0.2 (the lower bound of the "Medium" expectation), and harder bugs are well under 0.1.

#### By language:

| Language | Octen | MiniLM | BGE-M3 |
|----------|:-----:|:------:|:------:|
| C | 0.1224 | 0.1388 | 0.1190 |
| Go | 0.1148 | 0.1472 | 0.1225 |
| Java | 0.1169 | 0.1336 | 0.1088 |
| Python | 0.1290 | 0.1326 | 0.1369 |
| Swift | 0.1229 | 0.1496 | 0.1267 |

Distances are remarkably uniform across languages (range ≈0.11–0.15), indicating that the poor separability is not a language-specific artefact but a fundamental property of how these models represent code.

### 3.4 Cluster Separation Analysis

| Metric | Octen | MiniLM | BGE-M3 |
|--------|:-----:|:------:|:------:|
| Intra-cluster mean distance | 0.6806 | 0.7366 | 0.4511 |
| Cross-cluster mean distance | 0.6992 | 0.7492 | 0.4626 |
| Raw difference (cross − intra) | **0.0187** | **0.0126** | **0.0115** |

The gap between "how far apart buggy snippets are from each other" and "how far apart buggy snippets are from fixed snippets" is only 0.01–0.02 — a trivially small margin relative to the absolute distance scale.

#### Per-language separation scores:

| Language | Octen | MiniLM | BGE-M3 |
|----------|:-----:|:------:|:------:|
| C | 0.0179 | 0.0133 | 0.0110 |
| Go | 0.0129 | 0.0129 | 0.0112 |
| Java | 0.0218 | 0.0133 | 0.0123 |
| Python | 0.0222 | 0.0111 | 0.0133 |
| Swift | 0.0185 | 0.0124 | 0.0098 |

No language achieves even a separation score of 0.03.

### 3.5 Dangerous Neighbourhoods

A pair (bug type, language) is "dangerous" when the cosine distance between its buggy and fixed embeddings falls below a threshold — meaning the model sees them as nearly identical.

| Threshold | Octen | MiniLM | BGE-M3 |
|-----------|:-----:|:------:|:------:|
| 0.05 | 30.8 % | 24.2 % | 24.8 % |
| **0.10** | **51.8 %** | **41.8 %** | **45.8 %** |
| 0.15 | 68.0 % | 60.8 % | 62.6 % |

**Key finding:** At a threshold of 0.10, between 42–52 % of all bug–fix pairs are **indistinguishable** to the model. At 0.15 the figure rises to 61–68 %. This means that for the majority of bugs, a nearest-neighbour classifier in embedding space would be no better than a coin flip at distinguishing correct from incorrect code.

#### Dangerous neighbourhoods by severity (at threshold 0.10):

| Severity | Octen | MiniLM | BGE-M3 |
|----------|:-----:|:------:|:------:|
| Easy | **13.6 %** | 18.2 % | **9.1 %** |
| Medium | 60.7 % | 53.6 % | 46.4 % |
| Hard | 48.4 % | 64.5 % | 54.8 % |
| Super Hard | **68.4 %** | 52.6 % | 52.6 % |

Easy bugs have the lowest dangerous-neighbourhood rate (9–18 %), consistent with their higher pairwise distance. Super Hard and Medium bugs are the most dangerous (up to 68 %).

#### Dangerous neighbourhoods by language (at threshold 0.10):

| Language | Octen | MiniLM | BGE-M3 |
|----------|:-----:|:------:|:------:|
| C | 53.0 % | 43.0 % | 45.0 % |
| Go | 52.0 % | 39.0 % | 47.0 % |
| Java | 55.0 % | 44.0 % | 52.0 % |
| Python | 48.0 % | 49.0 % | 42.0 % |
| Swift | 51.0 % | 34.0 % | 43.0 % |

Java tends to have the highest dangerous-neighbourhood rate, and Swift/Python the lowest, though differences are moderate.

### 3.6 Statistical Tests

| Statistic | Octen | MiniLM | BGE-M3 |
|-----------|:-----:|:------:|:------:|
| Welch's *t* | 5.473 | 3.058 | 5.014 |
| *p*-value | 4.64 × 10⁻⁸ | 2.24 × 10⁻³ | 5.51 × 10⁻⁷ |
| Cohen's *d* | 0.149 | 0.083 | 0.136 |
| Effect size | **negligible** | **negligible** | **negligible** |

**Key finding:** The *t*-test is statistically significant (*p* < 0.01) for all three models — there *is* a measurable difference between cross-cluster and intra-cluster distances. However, Cohen's *d* is **negligible** (< 0.2) in every case, meaning the difference has no practical significance. The statistical significance is driven by large sample sizes (2,500–3,000 distance pairs), not by a meaningful separation effect.

#### Bootstrap 95 % confidence intervals for the cross−intra difference:

| Model | Mean Δ | 95 % CI |
|-------|:------:|:-------:|
| Octen | 0.0187 | \[0.0118, 0.0255\] |
| MiniLM | 0.0126 | \[0.0043, 0.0208\] |
| BGE-M3 | 0.0115 | \[0.0070, 0.0161\] |

The CI for the mean difference excludes zero, confirming a real but tiny signal.

---

## 4. Cross-Model Consistency

The three models vary considerably in architecture (code-specialised vs general-purpose, 384-d vs 1024-d), yet the patterns are strikingly consistent:

1. **All models produce near-zero correctness silhouettes** (0.01–0.02).
2. **Easy bugs are the most distinguishable** in every model.
3. **Dangerous neighbourhoods affect 42–52 % of pairs** at the 0.10 threshold.
4. **Effect sizes are negligible** despite statistically significant *t*-tests.
5. **No single language is dramatically better or worse** — the phenomenon is language-agnostic.

This cross-model agreement strengthens the conclusion: the inability to separate buggy from fixed code is not a model-specific weakness but a fundamental limitation of how current embedding models represent code.

---

## 5. Discussion

### 5.1 Why Models Fail to Encode Correctness

Embedding models learn to represent **distributional similarity** — tokens that appear in similar contexts receive similar representations. A subtle bug (e.g., `<` vs `<=`, `shallow_copy` vs `deep_copy`) changes one or two tokens in an otherwise identical code block, producing a near-identical embedding. The embedding space is optimised for semantic similarity at the level of *what the code does* (algorithmically), not *whether it does it correctly*.

### 5.2 Severity Gradient Is Real but Weak

The severity-based gradient (Easy > Medium > Hard ≈ Super Hard) is the most interpretable pattern in the data. Syntax errors break token-level patterns enough for the model to notice; subtle semantic bugs preserve the surface form almost perfectly. This aligns with the intuition that:

- **Easy bugs** alter the code's *surface syntax* → detectable in embedding space.
- **Hard/Super Hard bugs** alter the code's *runtime semantics* → invisible to surface-level representations.

### 5.3 Implications for AI-Assisted Code Review

The dangerous-neighbourhood finding has direct practical implications:

- **Embedding-based code search** may retrieve buggy code as a "match" for correct queries ≈50 % of the time.
- **Nearest-neighbour bug detectors** operating in embedding space are fundamentally limited for non-trivial bugs.
- **RAG pipelines** for code generation may inject buggy exemplars that are indistinguishable from correct ones.

Reliable correctness detection likely requires **execution-aware representations** (e.g., incorporating test outcomes, runtime traces, or formal verification signals) rather than purely textual embeddings.

### 5.4 Comparison to Proposal Expectations

The project proposal predicted:
- Easy bugs: distance > 0.5 → **Observed: ≈0.2** (2.5× lower than expected)
- Medium bugs: distance 0.2–0.5 → **Observed: ≈0.11** (below the predicted lower bound)
- Hard bugs: distance < 0.2 → **Observed: ≈0.10** (confirmed, within range)

The proposal's expectations were calibrated roughly correctly for relative ordering but significantly overestimated absolute distances.

---

## 6. Summary of Findings

| # | Finding | Evidence |
|:-:|---------|----------|
| 1 | **Buggy and fixed code do not form separate clusters.** | Correctness silhouette ≈ 0.01 across all models. |
| 2 | **Embeddings cluster more by language than by correctness.** | Language silhouette 2–4× higher than correctness silhouette (Octen, MiniLM). |
| 3 | **Easy (syntax) bugs are the most distinguishable.** | Per-severity silhouette 3–5× higher for Easy; pairwise distance ≈0.20 vs ≈0.10 for other tiers. |
| 4 | **42–52 % of bug–fix pairs are "dangerous" at threshold 0.10.** | Consistent across all three models. |
| 5 | **Super Hard bugs are the most dangerous.** | Up to 68.4 % dangerous-neighbourhood rate (Octen), negative silhouette (−0.002). |
| 6 | **Statistical significance ≠ practical significance.** | *p* < 0.01 but Cohen's *d* < 0.15 (negligible) for all models. |
| 7 | **Results are model-agnostic.** | Three architecturally different models produce consistent patterns. |
| 8 | **Results are language-agnostic.** | Per-language metrics vary by < 0.03 across C, Go, Java, Python, Swift. |

---

## 7. Answering RQ4

> **Do correct implementations cluster separately from buggy ones within language families?**

**No.** Across three embedding models, five languages, and four severity tiers, buggy and fixed implementations of the same algorithms are nearly indistinguishable in embedding space. Correctness silhouette scores hover around 0.01 (where 1.0 would indicate perfect separation), and the practical effect size (Cohen's *d*) is negligible.

> **Can we identify "dangerous neighbourhoods" in embedding space where semantically similar code exhibits mixed correctness?**

**Yes — and they are pervasive.** At a cosine-distance threshold of 0.10, between 42 % and 52 % of all bug–fix pairs fall into dangerous neighbourhoods. The problem is most severe for Hard and Super Hard bugs (subtle semantic and language-specific issues), where up to 68 % of pairs are indistinguishable. Easy (syntax) bugs are the only category with meaningfully lower rates (9–18 %).

These findings suggest that current code-embedding models fundamentally lack the ability to encode correctness, and that embedding-based tools for code quality assurance should be supplemented with execution-aware or verification-based signals.

---

*Report generated from analysis of 3 models × 1,000 snippets each. Full metrics available in per-model `rq4_metrics.json` files. Visualisations (t-SNE plots, silhouette bars, heatmaps, etc.) available in per-model result directories.*

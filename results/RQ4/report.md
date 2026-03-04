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

Five models spanning different architectures and training regimes were evaluated to establish cross-model robustness:

| Model Key | Model Name | Dim | Type |
|-----------|-----------|-----|------|
| `octen` | Octen/Octen-Embedding-0.6B | 1,024 | Code-specialised |
| `minilm` | sentence-transformers/all-MiniLM-L6-v2 | 384 | General-purpose (lightweight) |
| `bge_m3` | BAAI/bge-m3 | 1,024 | Multilingual general-purpose |
| `unixcoder` | microsoft/unixcoder-base | 768 | Code-specialised (pre-trained on code) |
| `qwen3` | Qwen/Qwen3-Embedding-0.6B | 1,024 | General-purpose (multilingual) |

### 2.4 Metrics

1. **Correctness Silhouette Score** — clustering quality when labels are `buggy` vs `fixed` (within each language/severity). Baseline comparisons against language-label and severity-label silhouettes.
2. **Pairwise Buggy–Fixed Cosine Distance** — for each bug type and language, the cosine distance between the buggy and fixed embedding of the same algorithm.
3. **Intra- vs Cross-Cluster Distance** — mean pairwise cosine distance within buggy/fixed groups vs across them, per language, with a separation score.
4. **Dangerous Neighbourhoods** — percentage of (bug, language) pairs whose buggy–fixed distance falls below a threshold (0.05, 0.10, 0.15), indicating the model cannot distinguish them.
5. **Statistical Tests** — Welch's t-test on cross-cluster vs intra-cluster distances, Cohen's *d* effect size, and bootstrap 95% confidence intervals (5,000 resamples).

---

## 3. Results

### 3.1 Overall Correctness Clustering Is Near-Zero

| Metric | Octen | MiniLM | BGE-M3 | UniXcoder | Qwen3 |
|--------|:-----:|:------:|:------:|:---------:|:-----:|
| Correctness silhouette (overall) | 0.0095 | 0.0129 | **0.0194** | 0.0133 | 0.0102 |
| Language baseline silhouette | 0.0391 | 0.0535 | 0.0068 | 0.0006 | **0.0602** |
| Severity baseline silhouette | 0.0253 | 0.0220 | 0.0201 | 0.0226 | 0.0239 |

**Key finding:** Across all five models the correctness silhouette is essentially zero (range 0.0095–0.0194). For Octen, MiniLM, and Qwen3, embeddings cluster more strongly by *language* (4–6× higher silhouette) than by correctness. Qwen3 shows the strongest language clustering (0.0602) while having one of the weakest correctness signals (0.0102). BGE-M3 and UniXcoder are notable for having near-zero language baselines (0.007 and 0.001 respectively), suggesting they produce more language-agnostic representations — yet even they do not achieve meaningful correctness separation.

**Interpretation:** None of the five tested models encode a meaningful "correctness axis" in their embedding spaces. Buggy and fixed code of the same algorithm are representationally overlapping.

### 3.2 Per-Severity Correctness Silhouette

| Severity | Octen | MiniLM | BGE-M3 | UniXcoder | Qwen3 |
|----------|:-----:|:------:|:------:|:---------:|:-----:|
| Easy | 0.0518 | 0.0445 | 0.0629 | **0.0672** | 0.0542 |
| Medium | 0.0041 | 0.0051 | 0.0134 | 0.0075 | 0.0043 |
| Hard | 0.0025 | 0.0047 | 0.0097 | 0.0043 | 0.0025 |
| Super Hard | **−0.0019** | 0.0073 | 0.0140 | 0.0065 | **−0.0016** |

**Key finding:** Easy (syntax) bugs consistently produce the highest correctness silhouette across all five models — roughly 3–8× higher than other severity tiers. UniXcoder achieves the best Easy-bug separation (0.0672), while BGE-M3 is a close second (0.0629). This is intuitive: a missing semicolon or misspelled keyword creates a visually (and tokenically) distinct string.

Both Octen and Qwen3 produce **negative** Super Hard silhouettes (−0.0019 and −0.0016), meaning for these models buggy code is *more similar to the wrong cluster* than to its own — the worst possible outcome for a correctness detector. The remaining models keep Super Hard slightly positive but still negligible.

### 3.3 Pairwise Buggy–Fixed Distances

| Metric | Octen | MiniLM | BGE-M3 | UniXcoder | Qwen3 |
|--------|:-----:|:------:|:------:|:---------:|:-----:|
| Overall mean distance | 0.1212 | 0.1404 | 0.1228 | **0.2230** | 0.1139 |
| Overall std | 0.0999 | 0.1128 | 0.0879 | 0.1639 | 0.0968 |

**UniXcoder** stands out with the highest pairwise distance (0.223) — nearly double that of Qwen3 (0.114). As the only model pre-trained explicitly on source code (via code-specific objectives), UniXcoder appears to encode more fine-grained token-level differences, even if the overall separation remains insufficient for reliable correctness detection.

#### By severity:

| Severity | Octen | MiniLM | BGE-M3 | UniXcoder | Qwen3 |
|----------|:-----:|:------:|:------:|:---------:|:-----:|
| Easy | 0.2365 | 0.2085 | 0.2027 | **0.3917** | 0.2159 |
| Medium | 0.1082 | 0.1064 | 0.1183 | **0.2177** | 0.1026 |
| Hard | 0.0963 | 0.1056 | 0.1121 | **0.1997** | 0.0879 |
| Super Hard | 0.0886 | 0.1276 | 0.1288 | **0.2072** | 0.0886 |

**Key finding:** The overall pairwise distance between a buggy snippet and its fixed counterpart is remarkably small for most models — only 0.11–0.14 on the cosine scale \[0, 2\]. UniXcoder is the outlier, achieving roughly 2× higher distances across all severity tiers, with Easy bugs reaching 0.39. However, even UniXcoder's distances remain far below the proposal's expectations.

The proposal's expected-behaviour thresholds predicted:
- Easy: distance > 0.5
- Medium: distance 0.2–0.5
- Hard: distance < 0.2

**Observed reality is far below these expectations.** Even UniXcoder's Easy bugs (0.39) fall short of the 0.5 target. For the other four models, Easy bugs only reach ≈0.2 (the lower bound of the "Medium" expectation), and harder bugs are well under 0.15.

#### By language:

| Language | Octen | MiniLM | BGE-M3 | UniXcoder | Qwen3 |
|----------|:-----:|:------:|:------:|:---------:|:-----:|
| C | 0.1224 | 0.1388 | 0.1190 | 0.2176 | 0.1184 |
| Go | 0.1148 | 0.1472 | 0.1225 | 0.2266 | 0.1055 |
| Java | 0.1169 | 0.1336 | 0.1088 | 0.1981 | 0.1083 |
| Python | 0.1290 | 0.1326 | 0.1369 | **0.2484** | 0.1203 |
| Swift | 0.1229 | 0.1496 | 0.1267 | 0.2245 | 0.1171 |

Distances are remarkably uniform across languages within each model (range ≈0.02–0.05 per model), indicating that the poor separability is not a language-specific artefact but a fundamental property of how these models represent code. UniXcoder consistently produces higher distances, with Python showing the widest buggy–fixed gap (0.248).

### 3.4 Cluster Separation Analysis

| Metric | Octen | MiniLM | BGE-M3 | UniXcoder | Qwen3 |
|--------|:-----:|:------:|:------:|:---------:|:-----:|
| Intra-cluster mean distance | 0.6806 | 0.7366 | 0.4511 | 0.7740 | 0.6385 |
| Cross-cluster mean distance | 0.6992 | 0.7492 | 0.4626 | 0.7879 | 0.6543 |
| Raw difference (cross − intra) | **0.0187** | 0.0126 | 0.0115 | 0.0140 | **0.0158** |

The gap between "how far apart buggy snippets are from each other" and "how far apart buggy snippets are from fixed snippets" is only 0.01–0.02 across all five models — a trivially small margin relative to the absolute distance scale.

#### Per-language separation scores:

| Language | Octen | MiniLM | BGE-M3 | UniXcoder | Qwen3 |
|----------|:-----:|:------:|:------:|:---------:|:-----:|
| C | 0.0179 | 0.0133 | 0.0110 | 0.0099 | 0.0152 |
| Go | 0.0129 | 0.0129 | 0.0112 | 0.0143 | 0.0092 |
| Java | 0.0218 | 0.0133 | 0.0123 | 0.0128 | 0.0188 |
| Python | 0.0222 | 0.0111 | 0.0133 | 0.0185 | 0.0189 |
| Swift | 0.0185 | 0.0124 | 0.0098 | 0.0145 | 0.0167 |

No language achieves even a separation score of 0.03 on any model.

### 3.5 Dangerous Neighbourhoods

A pair (bug type, language) is "dangerous" when the cosine distance between its buggy and fixed embeddings falls below a threshold — meaning the model sees them as nearly identical.

| Threshold | Octen | MiniLM | BGE-M3 | UniXcoder | Qwen3 |
|-----------|:-----:|:------:|:------:|:---------:|:-----:|
| 0.05 | 30.8 % | 24.2 % | 24.8 % | **17.6 %** | 32.8 % |
| **0.10** | 51.8 % | 41.8 % | 45.8 % | **29.6 %** | **54.0 %** |
| 0.15 | 68.0 % | 60.8 % | 62.6 % | **38.2 %** | **69.2 %** |

**Key finding:** At a threshold of 0.10, dangerous-neighbourhood rates range from **29.6 %** (UniXcoder) to **54.0 %** (Qwen3). UniXcoder is the clear best performer — its code-specific pre-training yields roughly half the dangerous-neighbourhood rate of the worst performer. Qwen3 and Octen are the worst, with over half of all pairs indistinguishable. At 0.15 the rates range from 38 % (UniXcoder) to 69 % (Qwen3).

#### Dangerous neighbourhoods by severity (at threshold 0.10):

| Severity | Octen | MiniLM | BGE-M3 | UniXcoder | Qwen3 |
|----------|:-----:|:------:|:------:|:---------:|:-----:|
| Easy | 13.6 % | 18.2 % | 9.1 % | **4.5 %** | 18.2 % |
| Medium | 60.7 % | 53.6 % | 46.4 % | **32.1 %** | **64.3 %** |
| Hard | 48.4 % | 64.5 % | 54.8 % | **22.6 %** | 51.6 % |
| Super Hard | **68.4 %** | 52.6 % | 52.6 % | 31.6 % | **73.7 %** |

Easy bugs have the lowest dangerous-neighbourhood rate across all models (4.5–18 %), consistent with their higher pairwise distance. UniXcoder keeps even Super Hard bugs at 31.6 %, while Qwen3 sees 73.7 % of Super Hard pairs as indistinguishable — the highest rate observed.

#### Dangerous neighbourhoods by language (at threshold 0.10):

| Language | Octen | MiniLM | BGE-M3 | UniXcoder | Qwen3 |
|----------|:-----:|:------:|:------:|:---------:|:-----:|
| C | 53.0 % | 43.0 % | 45.0 % | 32.0 % | 51.0 % |
| Go | 52.0 % | 39.0 % | 47.0 % | 30.0 % | 56.0 % |
| Java | 55.0 % | 44.0 % | 52.0 % | 37.0 % | **58.0 %** |
| Python | 48.0 % | 49.0 % | 42.0 % | **23.0 %** | 52.0 % |
| Swift | 51.0 % | 34.0 % | 43.0 % | 26.0 % | 53.0 % |

Java tends to have the highest dangerous-neighbourhood rate, and Python the lowest (especially for UniXcoder at 23 %). UniXcoder consistently outperforms all other models across every language.

### 3.6 Statistical Tests

| Statistic | Octen | MiniLM | BGE-M3 | UniXcoder | Qwen3 |
|-----------|:-----:|:------:|:------:|:---------:|:-----:|
| Welch's *t* | 5.473 | 3.058 | 5.014 | 3.876 | 4.565 |
| *p*-value | 4.64 × 10⁻⁸ | 2.24 × 10⁻³ | 5.51 × 10⁻⁷ | 1.07 × 10⁻⁴ | 5.10 × 10⁻⁶ |
| Cohen's *d* | 0.149 | 0.083 | 0.136 | 0.105 | 0.124 |
| Effect size | **negligible** | **negligible** | **negligible** | **negligible** | **negligible** |

**Key finding:** The *t*-test is statistically significant (*p* < 0.01) for all five models — there *is* a measurable difference between cross-cluster and intra-cluster distances. However, Cohen's *d* is **negligible** (< 0.2) in every case, meaning the difference has no practical significance. The statistical significance is driven by large sample sizes (2,500–3,000 distance pairs), not by a meaningful separation effect.

#### Bootstrap 95 % confidence intervals for the cross−intra difference:

| Model | Mean Δ | 95 % CI |
|-------|:------:|:-------:|
| Octen | 0.0187 | \[0.0118, 0.0255\] |
| MiniLM | 0.0126 | \[0.0043, 0.0208\] |
| BGE-M3 | 0.0115 | \[0.0070, 0.0161\] |
| UniXcoder | 0.0140 | \[0.0067, 0.0210\] |
| Qwen3 | 0.0158 | \[0.0088, 0.0228\] |

All five CIs exclude zero, confirming a real but tiny signal across every model.

---

## 4. Cross-Model Consistency

The five models span a wide range of architectures — code-specialised (Octen, UniXcoder), general-purpose (MiniLM, Qwen3), and multilingual (BGE-M3) — with dimensionalities from 384 to 1,024. Yet the core patterns are strikingly consistent:

1. **All models produce near-zero correctness silhouettes** (0.0095–0.0194).
2. **Easy bugs are the most distinguishable** in every model.
3. **Effect sizes are negligible** (Cohen's *d* = 0.083–0.149) despite statistically significant *t*-tests.
4. **No single language is dramatically better or worse** — the phenomenon is language-agnostic.

### 4.1 UniXcoder: The Relative Winner

UniXcoder stands out as the best-performing model on several key metrics:
- **Highest pairwise distance** (0.223 — nearly 2× the next best).
- **Lowest dangerous-neighbourhood rate** (29.6 % at threshold 0.10 — vs 42–54 % for others).
- **Best Easy-bug silhouette** (0.0672).
- **Only 4.5 % of Easy bugs are dangerous** (vs 9–18 % for others).

This is likely attributable to UniXcoder's code-specific pre-training objectives (masked token prediction, code-NL alignment), which encode finer-grained token-level differences. However, even UniXcoder's correctness silhouette (0.0133) remains negligible in absolute terms.

### 4.2 Qwen3: The Worst Performer

Qwen3 shows the highest dangerous-neighbourhood rates (54.0 % overall, 73.7 % for Super Hard) and is one of two models with a negative Super Hard silhouette. Its strong language-clustering signal (0.0602 — highest) suggests it prioritises language-level features over correctness-related features, making it the least suitable for correctness-aware tasks.

### 4.3 Cross-Model Summary Table

| Model | Sil (corr) | Pair dist | Cohen's *d* | Danger % @ 0.10 |
|-------|:----------:|:---------:|:-----------:|:---------------:|
| BGE-M3 | **0.0194** | 0.1228 | 0.136 | 45.8 |
| UniXcoder | 0.0133 | **0.2230** | 0.105 | **29.6** |
| MiniLM | 0.0129 | 0.1404 | 0.083 | 41.8 |
| Qwen3 | 0.0102 | 0.1139 | 0.124 | 54.0 |
| Octen | 0.0095 | 0.1212 | 0.149 | 51.8 |

This cross-model agreement — despite architectural diversity — strengthens the conclusion: the inability to separate buggy from fixed code is not a model-specific weakness but a fundamental limitation of how current embedding models represent code.

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
- Easy bugs: distance > 0.5 → **Observed: 0.20–0.39** (UniXcoder closest at 0.39, others ≈0.2)
- Medium bugs: distance 0.2–0.5 → **Observed: 0.10–0.22** (only UniXcoder reaches the lower bound)
- Hard bugs: distance < 0.2 → **Observed: 0.09–0.20** (confirmed across all models)

The proposal's expectations were calibrated roughly correctly for relative ordering but significantly overestimated absolute distances for most models. UniXcoder comes closest to the predicted values but still falls short for Easy bugs.

---

## 6. Summary of Findings

| # | Finding | Evidence |
|:-:|---------|----------|
| 1 | **Buggy and fixed code do not form separate clusters.** | Correctness silhouette ≈ 0.01–0.02 across all five models. |
| 2 | **Embeddings cluster more by language than by correctness.** | Language silhouette 2–6× higher than correctness silhouette (Octen, MiniLM, Qwen3). |
| 3 | **Easy (syntax) bugs are the most distinguishable.** | Per-severity silhouette 3–8× higher for Easy; pairwise distance 0.20–0.39 vs 0.09–0.22 for other tiers. |
| 4 | **30–54 % of bug–fix pairs are "dangerous" at threshold 0.10.** | Range spans from 29.6 % (UniXcoder) to 54.0 % (Qwen3). |
| 5 | **Super Hard bugs are the most dangerous.** | Up to 73.7 % dangerous-neighbourhood rate (Qwen3), negative silhouette on two models. |
| 6 | **Statistical significance ≠ practical significance.** | *p* < 0.01 but Cohen's *d* < 0.15 (negligible) for all models. |
| 7 | **Code-specific pre-training helps but does not solve the problem.** | UniXcoder achieves 2× higher pairwise distances and half the danger rate, yet correctness silhouette remains ≈ 0.01. |
| 8 | **Results are language-agnostic.** | Per-language metrics vary by < 0.05 across C, Go, Java, Python, Swift within each model. |
| 9 | **Results are model-agnostic.** | Five architecturally different models produce consistent patterns despite spanning 384–1024 dimensions. |

---

## 7. Answering RQ4

> **Do correct implementations cluster separately from buggy ones within language families?**

**No.** Across five embedding models, five languages, and four severity tiers, buggy and fixed implementations of the same algorithms are nearly indistinguishable in embedding space. Correctness silhouette scores range from 0.0095 to 0.0194 (where 1.0 would indicate perfect separation), and the practical effect size (Cohen's *d*) is negligible for every model. Even UniXcoder — the best performer — achieves only a 0.0133 correctness silhouette.

> **Can we identify "dangerous neighbourhoods" in embedding space where semantically similar code exhibits mixed correctness?**

**Yes — and they are pervasive.** At a cosine-distance threshold of 0.10, between 30 % (UniXcoder) and 54 % (Qwen3) of all bug–fix pairs fall into dangerous neighbourhoods. The problem is most severe for Hard and Super Hard bugs (subtle semantic and language-specific issues), where up to 74 % of pairs are indistinguishable. Easy (syntax) bugs are the only category with meaningfully lower rates (4.5–18 %).

Code-specific pre-training (UniXcoder) provides a meaningful but insufficient advantage: it halves the dangerous-neighbourhood rate compared to the worst performers but still leaves nearly a third of bug–fix pairs indistinguishable. These findings suggest that current code-embedding models fundamentally lack the ability to encode correctness, and that embedding-based tools for code quality assurance should be supplemented with execution-aware or verification-based signals.

---

*Report generated from analysis of 5 models × 1,000 snippets each. Full metrics available in per-model `rq4_metrics.json` files. Visualisations (t-SNE plots, silhouette bars, heatmaps, etc.) available in per-model result directories. Cross-model comparison plots in `results/RQ4/cross_model/`.*

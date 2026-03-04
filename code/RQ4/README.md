# RQ4 Analysis: Buggy vs Fixed Code Clustering

This directory contains code for analyzing Research Question 4: **Do correct implementations cluster separately from buggy ones within language families?**

## Research Question

Can we identify "dangerous neighborhoods" in embedding space where semantically similar code exhibits mixed correctness? This explores whether interference manifests as increased proximity between correct and incorrect code patterns.

## Files

- **`embedding.py`**: Generates embeddings for buggy and fixed code pairs using Octen-Embedding-0.6B model
- **`analysis.py`**: Analyzes clustering patterns, separation metrics, and generates visualizations

## Dataset

The analysis uses `datasets/RQ4/bugs.json` which contains 100 bug types across 5 programming languages:
- C
- Go
- Java
- Python
- Swift

Each bug type includes both buggy and fixed versions of code snippets.

## Usage

### Step 1: Generate Embeddings

```bash
python code/RQ4/embedding.py
```

This will:
- Load the bugs dataset
- Extract buggy and fixed code pairs for each language
- Generate embeddings using Octen-Embedding-0.6B (same model as RQ1)
- Save embeddings to `results/embeddings/rq4_octen_embeddings.json`
- Also save TSV format for visualization tools

### Step 2: Run Analysis

```bash
python code/RQ4/analysis.py
```

This will:
- Load the generated embeddings
- Calculate distance metrics (pairwise, intra-cluster, cross-cluster)
- Compute separation scores
- Generate visualizations
- Create summary report

## Output

All results are saved to `results/RQ4/`:

### Visualizations
- **`distance_distributions.png`**: Histograms showing buggy-fixed distance distributions per language
- **`cross_language_comparison.png`**: Bar chart comparing mean distances across languages
- **`clustering_metrics.png`**: Clustering separation metrics (intra vs cross distances + separation scores)
- **`tsne_visualization.png`**: 2D t-SNE visualization of embedding space

### Data Files
- **`analysis_results.json`**: Detailed numerical results for all metrics
- **`analysis_summary.txt`**: Human-readable summary report with key findings

## Metrics

### Distance Metrics
- **Pairwise Distance**: Distance between each buggy-fixed pair
- **Intra-Cluster Distance**: Average distance within buggy or fixed clusters
- **Cross-Cluster Distance**: Average distance between buggy and fixed clusters

### Separation Score
```
separation_score = cross_distance - (intra_buggy + intra_fixed) / 2
```

- **Positive score**: Good separation - buggy and fixed code form distinct clusters
- **Negative score**: Poor separation - significant overlap between buggy and fixed code

### Dangerous Neighborhoods
Percentage of buggy-fixed pairs with cosine distance < 0.1, indicating very similar code despite different correctness.

## Model Configuration

Following RQ1 methodology:
- **Model**: Octen-Embedding-0.6B
- **Device**: CUDA (GPU)
- **Distance Metric**: Cosine distance (primary), Euclidean distance (secondary)
- **Dimensionality Reduction**: t-SNE (perplexity=30, n_iter=1000)

## Dependencies

Same as RQ1:
- sentence-transformers
- numpy
- matplotlib
- seaborn
- scikit-learn
- scipy
- tqdm

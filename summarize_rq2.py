import json

with open('results/rq2/rq2_metrics.json', 'r') as f:
    data = json.load(f)

print("=== SILHOUETTE ===")
print(f"Framework Overall: {data['silhouette']['framework_silhouette_overall']}")
print(f"Language Overall: {data['silhouette']['language_silhouette_overall']}")
print("Per Language Framework Silhouette:")
for lang, score in data['silhouette']['framework_silhouette_per_language'].items():
    print(f"  {lang}: {score}")

print("\n=== DISTANCES ===")
print(f"Cross-FW Overall: {data['distances']['cross_framework_distance_overall']}")
print(f"Intra-FW Overall: {data['distances']['intra_framework_distance_overall']}")

print("\n=== DISTANCES BY PATTERN ===")
print("Cross-FW:")
for p, v in data['distances']['cross_framework_distance_by_pattern'].items():
    print(f"  {p}: {v}")
print("Intra-FW:")
for p, v in data['distances']['intra_framework_distance_by_pattern'].items():
    print(f"  {p}: {v}")


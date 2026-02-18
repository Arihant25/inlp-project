import yaml
import json
import re

# Load YAML
with open("template.yaml", "r") as f:
    data = yaml.safe_load(f)

# Extract problem name
problem_name = data.get("problem_name", "output")

# Sanitize filename (lowercase, replace spaces with hyphen)
safe_filename = re.sub(r'[^a-zA-Z0-9_-]', '', problem_name.replace(" ", "-")).lower()

# Create filename
output_file = f"{safe_filename}.json"

# Write JSON
with open(output_file, "w") as f:
    json.dump(data, f, indent=4)

print(f"Saved as {output_file}")

import json
import os
import re

def get_file_extension(language: str) -> str:
    """Maps the programming language to its standard file extension."""
    mapping = {
        "Java": ".java",
        "Python": ".py",
        "JavaScript": ".js",
        "Kotlin": ".kt",
        "Go": ".go",
        "Rust": ".rs",
        "PHP": ".php",
        "Ruby": ".rb"
    }
    return mapping.get(language, ".txt")

def clean_code_string(raw_code: str, language: str) -> str:
    """
    Strips out the markdown code block formatting that LLMs often add,
    even when instructed not to.
    """
    # Remove the opening markdown tag (e.g., ```python)
    # The regex ^```[a-zA-Z]*\n matches ``` followed by optional language name and a newline
    cleaned = re.sub(r'^```[a-zA-Z]*\n', '', raw_code.strip())
    
    # Remove the closing markdown tag (```)
    if cleaned.endswith("```"):
        cleaned = cleaned[:-3].strip()
        
    return cleaned

def process_dataset(input_file: str, output_dir: str):
    """Reads the JSONL file and creates the hierarchical directory structure."""
    
    if not os.path.exists(output_dir):
        os.makedirs(output_dir)
        
    success_count = 0
    failure_count = 0

    print(f"Reading dataset from {input_file}...")
    
    with open(input_file, 'r', encoding='utf-8') as f:
        for line_number, line in enumerate(f, 1):
            if not line.strip():
                continue
                
            try:
                record = json.loads(line)
            except json.JSONDecodeError:
                print(f"Warning: Could not parse JSON on line {line_number}")
                continue

            # Only process successful generations
            if record.get("status") != "success" or not record.get("variations"):
                failure_count += 1
                continue

            lang = record["language"]
            framework = record["framework"]
            pattern = record["pattern"].replace(" & ", "_and_").replace("/", "_").replace(" ", "_").lower()
            
            # Create the nested directory path: Language/Framework/Pattern
            target_dir = os.path.join(output_dir, lang, framework, pattern)
            os.makedirs(target_dir, exist_ok=True)
            
            file_ext = get_file_extension(lang)
            
            # Write the metadata file once per pattern
            metadata_path = os.path.join(target_dir, "metadata.txt")
            with open(metadata_path, 'w', encoding='utf-8') as meta_file:
                meta_file.write(f"ID: {record['id']}\n")
                meta_file.write(f"Language: {lang}\n")
                meta_file.write(f"Framework: {framework}\n")
                meta_file.write(f"Pattern: {record['pattern']}\n")
                meta_file.write(f"Status: {record['status']}\n")
                meta_file.write(f"Variations Generated: {len(record['variations'])}\n")
            
            # Write each code variation to its own file
            for i, raw_variation in enumerate(record["variations"], 1):
                clean_code = clean_code_string(raw_variation, lang)
                
                # Naming convention: variation_1.py, variation_2.py, etc.
                file_name = f"variation_{i}{file_ext}"
                file_path = os.path.join(target_dir, file_name)
                
                with open(file_path, 'w', encoding='utf-8') as code_file:
                    code_file.write(clean_code)
                    
            success_count += 1

    print("\nExtraction Complete!")
    print(f"Successfully processed {success_count} pattern implementations.")
    print(f"Skipped {failure_count} failed generations.")
    print(f"Dataset extracted to: {os.path.abspath(output_dir)}")

if __name__ == "__main__":
    # Ensure this matches the name of your generated JSONL file
    INPUT_JSONL = "rq2_synthetic_dataset.jsonl"
    OUTPUT_DIRECTORY = "Extracted_Dataset"
    
    process_dataset(INPUT_JSONL, OUTPUT_DIRECTORY)
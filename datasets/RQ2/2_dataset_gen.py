"""
Multilingual Code Learning Dynamics in LLMs - RQ2 Async Execution Pipeline
Optimized for Google AI Studio Tier 1 Paid & Live Data Streaming

Target Model: Gemini 2.5 Pro
"""

import json
import asyncio
import re
import os
import logging
from typing import List, Dict, Any
from dotenv import load_dotenv
from google import genai
from google.genai import types
from tenacity import retry, wait_exponential, stop_after_attempt, before_sleep_log
from tqdm.asyncio import tqdm

# Logging setup for monitoring
logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(levelname)s - %(message)s')
logger = logging.getLogger(__name__)

load_dotenv()
API_KEY = os.environ.get("GEMINI_API_KEY")
if not API_KEY:
    raise ValueError("GEMINI_API_KEY not found. Please ensure it is set in your .env file.")

client = genai.Client(api_key=API_KEY)

# UPDATED: Targeting the Pro model for advanced reasoning and code generation
MODEL_NAME = "gemini-2.5-pro"

# Tier 1 Concurrency Limit
MAX_CONCURRENT_REQUESTS = 10 

def extract_code_variations(llm_response: str) -> List[str]:
    pattern = r'<variation id="\d+">\s*(.*?)\s*</variation>'
    extracted_variations = re.findall(pattern, llm_response, re.DOTALL)
    return [variation.strip() for variation in extracted_variations]

# UPDATED: Increased max wait time to 180s to account for potentially longer Pro generation times
@retry(
    wait=wait_exponential(multiplier=2, min=2, max=180),
    stop=stop_after_attempt(5),
    before_sleep=before_sleep_log(logger, logging.WARNING),
    reraise=True
)
async def generate_with_retry(prompt: str) -> str:
    response = await client.aio.models.generate_content(
        model=MODEL_NAME,
        contents=prompt,
        config=types.GenerateContentConfig(
            temperature=0.2, 
        )
    )
    return response.text

async def process_prompt(
    job: Dict[str, Any], 
    semaphore: asyncio.Semaphore,
    file_lock: asyncio.Lock,
    output_file: str
) -> Dict[str, Any]:
    
    async with semaphore:
        try:
            raw_text = await generate_with_retry(job["prompt"])
            variations = extract_code_variations(raw_text)
            status = "success" if len(variations) == 4 else "partial_failure"
            
            result = {
                "id": job["id"],
                "language": job["language"],
                "framework": job["framework"],
                "pattern": job["pattern"],
                "status": status,
                "raw_response": raw_text if status == "partial_failure" else None,
                "variations": variations
            }
        except Exception as e:
            logger.error(f"Catastrophic failure on job {job['id']}: {str(e)}")
            result = {
                "id": job["id"],
                "language": job["language"],
                "framework": job["framework"],
                "pattern": job["pattern"],
                "status": "failed",
                "error": str(e),
                "variations": []
            }
            
        # LIVE WRITE LOGIC: Safely acquire lock and append the result immediately
        async with file_lock:
            with open(output_file, "a", encoding="utf-8") as f:
                f.write(json.dumps(result) + "\n")
                
        return result

async def main():
    input_file = "rq2_gemini_generation_prompts.json"
    output_file = "rq2_synthetic_dataset.jsonl" 
    
    print(f"📥 Loading {input_file}...")
    with open(input_file, "r", encoding="utf-8") as f:
        prompts = json.load(f)
        
    print(f"🚀 Initializing new Google GenAI SDK targeting {MODEL_NAME}...")
    
    # Initialize Concurrency Control and File Lock
    semaphore = asyncio.Semaphore(MAX_CONCURRENT_REQUESTS)
    file_lock = asyncio.Lock()
    
    # Clear out any old data in the output file before starting a fresh run
    with open(output_file, "w", encoding="utf-8") as f:
        pass
    
    tasks = [process_prompt(job, semaphore, file_lock, output_file) for job in prompts]
    results = await tqdm.gather(*tasks, desc="Generating Snippets")
    
    successful = sum(1 for r in results if r["status"] == "success")
    total_snippets = sum(len(r["variations"]) for r in results)
    
    print("\n" + "="*40)
    print("📊 GENERATION COMPLETE")
    print(f"Successful Base Runs: {successful} / {len(results)}")
    print(f"Total Code Snippets Extracted: {total_snippets} / 1024")
    print(f"📄 Output live-streamed to: {output_file}")
    print("="*40 + "\n")

if __name__ == "__main__":
    asyncio.run(main())
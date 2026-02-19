"""
Multilingual Code Learning Dynamics in LLMs - RQ2 Prompt Generation Pipeline

This script generates the exact system prompts required to synthesize the dataset for 
Research Question 2 (RQ2). The goal is to evaluate if popular frameworks create 
detectable sub-clusters within language families in LLM embedding spaces.

Dataset Dimensions:
- 8 Software Patterns
- 8 Programming Languages
- 4 Frameworks per Language (including 'Vanilla' standard libraries)
- Total Base Implementations: 256
- Target Variations per Base: 4 (Yielding 1024 total code snippets)
"""

import json
from typing import List, Dict, Any

# =============================================================================
# 1. MATRIX DEFINITIONS
# =============================================================================

TARGET_MATRIX: Dict[str, List[str]] = {
    "Java": ["Spring Boot", "Micronaut", "Quarkus", "Vanilla"],
    "Python": ["Django", "Flask", "FastAPI", "Vanilla"],
    "JavaScript": ["Express", "NestJS", "Fastify", "Vanilla"],
    "Kotlin": ["Ktor", "Spring Boot", "Javalin", "Vanilla"],
    "Go": ["Gin", "Echo", "Fiber", "Vanilla"],
    "Rust": ["Actix", "Rocket", "Axum", "Vanilla"],
    "PHP": ["Laravel", "Symfony", "Slim", "Vanilla"],
    "Ruby": ["Rails", "Sinatra", "Hanami", "Vanilla"]
}

PATTERN_DETAILS: Dict[str, str] = {
    "Authentication & Authorization": "User login with password validation, JWT token generation/validation, OAuth2 client implementation, Session management, Password hashing, and Role-based access control (RBAC).",
    "REST API Endpoints": "Create user (POST), Get user by ID (GET), Update user (PUT/PATCH), Delete user (DELETE), List users with pagination, and Search/filter users with query parameters.",
    "Database Operations": "CRUD operations on entities, One-to-many relationships (User -> Posts), Many-to-many relationships (Users <-> Roles), Transactions and rollbacks, Query building with filters, and Database migrations.",
    "Data Validation & Serialization": "Input validation (email, phone, required fields), JSON serialization/deserialization, XML parsing and generation, Custom validators, Error message formatting, and Type conversion/coercion.",
    "Caching Layers": "In-memory cache (get/set/delete), Cache invalidation strategies, Cache-aside pattern, Time-based expiration, and LRU cache implementation.",
    "Background Jobs & Task Queues": "Schedule periodic tasks, Async email sending, Image processing pipeline, Retry logic with exponential backoff, and Job status tracking.",
    "Middleware/Interceptors": "Request logging, CORS handling, Rate limiting, Request/response transformation, and Error handling middleware.",
    "File Operations": "File upload handling, CSV/Excel parsing, Image resizing/processing, File download with streaming, and Temporary file management."
}

# =============================================================================
# 2. UNIFIED DOMAIN SCHEMA
# =============================================================================

DOMAIN_SCHEMA = """
Assume the following unified domain model across all implementations:
- Entity 1: `User` (Fields: id (UUID), email (String), password_hash (String), role (Enum: ADMIN, USER), is_active (Boolean), created_at (Timestamp))
- Entity 2: `Post` (Fields: id (UUID), user_id (UUID), title (String), content (Text), status (Enum: DRAFT, PUBLISHED))
"""

# =============================================================================
# 3. CONSTRAINT DICTIONARIES
# =============================================================================


def get_base_framework_constraint(framework: str, language: str) -> str:
    if framework == "Vanilla":
        return f"You are strictly restricted to the standard library of {language}. You MUST NOT import or hallucinate any external frameworks, third-party libraries, or ORMs."
    return f"Strictly utilize the built-in, idiomatic features, annotations, and conventions native to the {framework} framework. Assume the framework's standard request/response lifecycle."


PATTERN_OVERRIDES: Dict[str, Dict[str, str]] = {
    "Authentication & Authorization": {
        "Vanilla": "Implement raw cryptographic signing logic (like JWT token generation) and manual role-based access control middleware from scratch using standard crypto libraries.",
        "Framework": "Use the framework's native security plugins and session management abstractions."
    },
    "REST API Endpoints": {
        "Vanilla": "Use the language's core HTTP daemon to manually parse incoming URL paths and HTTP methods for CRUD operations.",
        "Framework": "Use the framework's native routing mechanism."
    },
    "Database Operations": {
        "Vanilla": "Generate a pure SQL execution script utilizing standard database drivers. Include manual checks for table existence before creating relationships.",
        "Framework": "Utilize the native ORM/ActiveRecord pattern associated with the framework."
    },
    "Data Validation & Serialization": {
        "Vanilla": "Write custom regular expressions and manual conditional checks for all input validation and type coercion.",
        "Framework": "Leverage the framework's built-in DTOs and validation decorators/rules."
    },
    "Caching Layers": {
        "Vanilla": "Build an in-memory LRU cache from scratch using a doubly-linked list combined with a hash map. Do not assume an external Redis connection.",
        "Framework": "Implement cache-aside logic assuming standard framework caching abstractions."
    },
    "Background Jobs & Task Queues": {
        "Vanilla": "Utilize language-level in-memory constructs (e.g., async task threads, setInterval, or Goroutines) to handle retry logic and scheduling. Do not rely on OS-level cron or external queue workers.",
        "Framework": "Implement async tasks using the framework's standard queue integration."
    },
    "Middleware/Interceptors": {
        "Vanilla": "Implement the 'Decorator' or 'Wrapper' design pattern around the base HTTP handler functions to achieve request/response transformation.",
        "Framework": "Hook directly into the framework's native middleware pipeline."
    },
    "File Operations": {
        "Vanilla": "Write manual multipart-form boundary parsing and byte-stream reading logic using standard library file handlers.",
        "Framework": "Use the framework's high-level file upload request wrappers."
    }
}

# =============================================================================
# 4. MASTER TEMPLATE
# =============================================================================

MASTER_TEMPLATE: str = """You are an expert software engineer. Your task is to generate a realistic, production-grade code snippet in {language} using {framework} that implements the following pattern: {pattern}.

Specific Functionality Required:
{pattern_details}

Domain Schema Requirement:
{domain_schema}

Implementation Constraints:
- {framework_constraint}
- {pattern_constraint}

Strict Output Requirements:
1. Provide exactly 4 distinct variations of this implementation to simulate 4 different developers. Vary the structural organization (e.g., functional vs OOP, different valid design patterns, or differing variable naming conventions).
2. COMPILABILITY GUARANTEE: All implementations must be syntactically valid, self-contained, and capable of passing compiler/interpreter checks. Include all necessary package declarations, imports, and mock data structures required for the code to run in isolation.
3. EXACT OUTPUT FORMAT: You MUST wrap each generated variation in specific XML tags. Do not use standard markdown code blocks (like ```) outside or inside these tags. Do not include any natural language text before or after the tags.

Follow this exact structure:
<variation id="1">
[Insert complete code for variation 1 here]
</variation>
<variation id="2">
[Insert complete code for variation 2 here]
</variation>
<variation id="3">
[Insert complete code for variation 3 here]
</variation>
<variation id="4">
[Insert complete code for variation 4 here]
</variation>"""

# =============================================================================
# 5. GENERATION ENGINE
# =============================================================================


def generate_prompts() -> List[Dict[str, Any]]:
    generated_prompts = []

    for language, frameworks in TARGET_MATRIX.items():
        for framework in frameworks:
            for pattern, pattern_details in PATTERN_DETAILS.items():

                constraint_type = "Vanilla" if framework == "Vanilla" else "Framework"
                fw_constraint = get_base_framework_constraint(
                    framework, language)
                pt_constraint = PATTERN_OVERRIDES[pattern][constraint_type]

                final_prompt = MASTER_TEMPLATE.format(
                    language=language,
                    framework=framework,
                    pattern=pattern,
                    pattern_details=pattern_details,
                    domain_schema=DOMAIN_SCHEMA,
                    framework_constraint=fw_constraint,
                    pattern_constraint=pt_constraint
                )

                safe_lang = language.lower()
                safe_fw = framework.lower().replace(" ", "")
                safe_pat = pattern.lower().replace(" ", "_").replace("&", "and").replace("/", "_")
                job_id = f"{safe_lang}_{safe_fw}_{safe_pat}"

                generated_prompts.append({
                    "id": job_id,
                    "language": language,
                    "framework": framework,
                    "pattern": pattern,
                    "prompt": final_prompt
                })

    return generated_prompts

# =============================================================================
# 6. EXECUTION SCRIPT
# =============================================================================


if __name__ == "__main__":
    prompts_payload = generate_prompts()
    output_filename = "rq2_gemini_generation_prompts.json"

    with open(output_filename, "w", encoding="utf-8") as f:
        json.dump(prompts_payload, f, indent=4)

    print(f"✅ Successfully generated {len(prompts_payload)} final prompts.")
    print(f"📄 Saved pipeline payload to: {output_filename}")

    assert len(
        prompts_payload) == 256, f"Expected 256 prompts, got {len(prompts_payload)}"

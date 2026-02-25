#!/usr/bin/env python3
"""
NeetCode 150 Solution Scraper
==============================
Fetches all NeetCode 150 problems from https://neetcode.io/solutions/{slug},
scrapes every approach × language combination with time/space complexities,
writes each problem to template.yaml, and converts it to JSON by calling
yamlToJsonConverter.py.

Usage:
    uv run python3 scrape_neetcode150.py
    # or to scrape only specific slugs:
    uv run python3 scrape_neetcode150.py two-sum group-anagrams
"""

import json
import re
import subprocess
import sys
import time
from pathlib import Path

import requests
import yaml
from playwright.sync_api import sync_playwright
from playwright.sync_api import TimeoutError as PWTimeout

# ── Paths ────────────────────────────────────────────────────────────────────
OUTPUT_DIR = Path(__file__).parent          # datasets/RQ3/
CONVERTER  = OUTPUT_DIR / "yamlToJsonConverter.py"

# ── NeetCode GitHub problem list ──────────────────────────────────────────────
PROBLEM_LIST_URL = (
    "https://raw.githubusercontent.com/neetcode-gh/leetcode/main/"
    ".problemSiteData.json"
)

# ── Helpers ───────────────────────────────────────────────────────────────────

def clean_latex(s: str) -> str:
    """Convert a LaTeX complexity string to a readable plain-text form."""
    s = s.strip()
    s = re.sub(r"\\log",  "log",  s)
    s = re.sub(r"\\ln",   "ln",   s)
    s = re.sub(r"\\cdot", "*",    s)
    s = re.sub(r"\\times","*",    s)
    s = re.sub(r"\\sqrt\{([^}]+)\}", r"sqrt(\1)", s)
    s = re.sub(r"\s*\^\s*", "^", s)
    s = re.sub(r"\\", "",  s)   # strip any remaining backslashes
    s = re.sub(r"\s+", " ", s)
    return s.strip()


# Custom YAML representer: force block-literal ('|') for multiline strings.
class _BlockDumper(yaml.Dumper):
    pass

def _str_presenter(dumper, data):
    if "\n" in data:
        return dumper.represent_scalar("tag:yaml.org,2002:str", data, style="|")
    return dumper.represent_scalar("tag:yaml.org,2002:str", data)

_BlockDumper.add_representer(str, _str_presenter)


# ── Step 1: Fetch problem list ────────────────────────────────────────────────

def get_neetcode150_list() -> list[dict]:
    """Return list of {name, slug, difficulty} for all NeetCode 150 problems."""
    print("Fetching NeetCode 150 problem list from GitHub…")
    resp = requests.get(PROBLEM_LIST_URL, timeout=30)
    resp.raise_for_status()
    raw = resp.json()

    problems = []
    for item in raw:
        if not item.get("neetcode150"):
            continue
        slug = item["link"].rstrip("/")
        problems.append(
            {
                "name":       item["problem"],
                "slug":       slug,
                "difficulty": item["difficulty"].lower(),
            }
        )
    return problems


# ── Step 2: Scrape one solutions page ─────────────────────────────────────────

# JavaScript that runs inside the browser page and returns all the data we need.
_EXTRACT_JS = """
() => {
    // ── Approach headings (numbered h2s) ─────────────────────────────────────
    const h2s    = Array.from(document.querySelectorAll('h2'));
    const approaches = h2s
        .map(h => h.innerText.trim())
        .filter(t => /^\\d+\\./.test(t));

    // ── Topic tags (inside the collapsed <details> accordion) ───────────────
    const topicsEl = Array.from(
        document.querySelectorAll('details.hint-accordion')
    ).find(d => d.querySelector('summary')?.innerText.trim() === 'Topics');

    let topics = [];
    if (topicsEl) {
        topicsEl.open = true;
        topics = Array.from(topicsEl.querySelectorAll('a'))
            .map(a => a.innerText.trim().toLowerCase())
            .filter(Boolean);
    }

    // ── Code tabs and complexity divs ────────────────────────────────────────
    // The inner .my-div has this child layout:
    //   [0]  DIV (description / approach text)
    //   [1]  APP-CODE-TABS  ← approach 1 code
    //   [2]  DIV            ← approach 1 complexity
    //   [3]  APP-CODE-TABS  ← approach 2 code
    //   [4]  DIV            ← approach 2 complexity
    //   …
    const innerDiv     = document.querySelector('.my-div .my-div');
    const innerKids    = innerDiv ? Array.from(innerDiv.children) : [];
    const complexDivs  = [];
    for (let i = 2; i < innerKids.length; i += 2) {
        complexDivs.push(innerKids[i]);
    }

    const codeTabs = Array.from(document.querySelectorAll('app-code-tabs'));

    // ── Extract per-approach data ────────────────────────────────────────────
    const solutionGroups = codeTabs.map((tabEl, idx) => {
        // --- Code for every language (already in the DOM as <pre> elements) --
        const langs = [];
        tabEl.querySelectorAll('pre').forEach(pre => {
            const lang = pre.className.replace('language-', '');
            const code = pre.textContent;
            if (lang && code && code.trim()) {
                langs.push({ lang, code });
            }
        });

        // --- Complexity from KaTeX <annotation encoding="application/x-tex"> -
        let timeC = 'O(?)', spaceC = 'O(?)';
        if (complexDivs[idx]) {
            const anns = complexDivs[idx]
                .querySelectorAll('annotation[encoding="application/x-tex"]');
            const vals = Array.from(anns).map(a => a.textContent.trim());
            if (vals.length >= 1) timeC  = vals[0];
            if (vals.length >= 2) spaceC = vals[1];
        }

        return { langs, timeC, spaceC };
    });

    return { approaches, topics, solutionGroups };
}
"""


def scrape_solutions_page(page, slug: str) -> dict | None:
    """Load the solutions page for *slug* and return extracted data, or None."""
    url = f"https://neetcode.io/solutions/{slug}"
    try:
        page.goto(url, wait_until="domcontentloaded", timeout=30_000)
        page.wait_for_selector("app-code-tabs", timeout=15_000)
        time.sleep(3)          # let Angular finish rendering
    except PWTimeout:
        print(f"    ⚠  Timeout loading {url}")
        return None
    except Exception as exc:
        print(f"    ⚠  Error loading {url}: {exc}")
        return None

    try:
        return page.evaluate(_EXTRACT_JS)
    except Exception as exc:
        print(f"    ⚠  JS evaluation failed for {slug}: {exc}")
        return None


# ── Step 3: Assemble data dict ────────────────────────────────────────────────

def build_data(name: str, slug: str, difficulty: str, scraped: dict) -> dict:
    """Combine scraped data into the template.yaml-compatible structure."""
    solutions = []
    for group in scraped.get("solutionGroups", []):
        time_c  = clean_latex(group.get("timeC",  "O(?)"))
        space_c = clean_latex(group.get("spaceC", "O(?)"))
        for entry in group.get("langs", []):
            code = entry["code"]
            if not code.strip():
                continue
            solutions.append(
                {
                    "language":         entry["lang"],
                    "time_complexity":  time_c,
                    "space_complexity": space_c,
                    "code":             code,
                }
            )

    return {
        "problem_name": name,
        "problem_slug": slug,
        "difficulty":   difficulty,
        "topic_tags":   scraped.get("topics", []),
        "solutions":    solutions,
    }


# ── Step 4: Write YAML + run converter ───────────────────────────────────────

def save_and_convert(data: dict) -> bool:
    """Write template.yaml and invoke yamlToJsonConverter.py."""
    yaml_path = OUTPUT_DIR / "template.yaml"
    with open(yaml_path, "w", encoding="utf-8") as fh:
        yaml.dump(
            data,
            fh,
            Dumper=_BlockDumper,
            default_flow_style=False,
            allow_unicode=True,
            width=120,
            sort_keys=False,
        )

    result = subprocess.run(
        [sys.executable, str(CONVERTER)],
        cwd=OUTPUT_DIR,
        capture_output=True,
        text=True,
        timeout=30,
    )
    if result.returncode != 0:
        print(f"    ✗ Converter error: {result.stderr.strip()}")
        return False
    print(f"    ✓ {result.stdout.strip()}")
    return True


# ── Main ──────────────────────────────────────────────────────────────────────

def main():
    # Allow running on specific slugs from the command line.
    force_slugs = set(sys.argv[1:])

    problems = get_neetcode150_list()
    if force_slugs:
        problems = [p for p in problems if p["slug"] in force_slugs]
    print(f"Found {len(problems)} problem(s) to process.\n")

    # Slugs for which a JSON file already exists → skip unless forced.
    existing = {f.stem for f in OUTPUT_DIR.glob("*.json")}

    with sync_playwright() as pw:
        browser = pw.chromium.launch(headless=True)
        page    = browser.new_page()

        for i, prob in enumerate(problems, 1):
            slug       = prob["slug"]
            name       = prob["name"]
            difficulty = prob["difficulty"]

            if slug in existing and not force_slugs:
                print(f"[{i:3}/{len(problems)}] Skipping  {slug}  (already exists)")
                continue

            print(f"[{i:3}/{len(problems)}] Scraping  {slug}")

            scraped = scrape_solutions_page(page, slug)
            if not scraped:
                print(f"    ✗ No data returned for {slug}")
                continue

            approaches = scraped.get("approaches", [])
            n_groups   = len(scraped.get("solutionGroups", []))
            print(f"    {n_groups} approach(es): {approaches}")

            data = build_data(name, slug, difficulty, scraped)
            if not data["solutions"]:
                print(f"    ✗ No solutions scraped for {slug}")
                continue

            save_and_convert(data)

            time.sleep(1.5)   # be polite to the server

        browser.close()

    print("\nAll done.")


if __name__ == "__main__":
    main()

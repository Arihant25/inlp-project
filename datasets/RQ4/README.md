# RQ4: Bug-fix pairs

`bugs.json` holds 100 bug types. For each type it gives a short description and, for each of C, Go, Java, Python, and Swift, a buggy version and its fix (`buggy_code`, `fixed_code`), so the corpus has 500 bug-fix pairs.

The bug types are ordered by category, which the analysis scripts (`code/RQ4/`) record as a severity level:

| Bug index | Severity label | Category |
|---|---|---|
| 1-22 | Easy | syntax and compile-time errors |
| 23-50 | Medium | logic and control-flow bugs |
| 51-81 | Hard | state and algorithmic bugs |
| 82-100 | Super Hard | numeric, memory, and concurrency bugs |

Two authors manually validated every pair.

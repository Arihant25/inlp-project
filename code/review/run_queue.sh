#!/bin/bash
# Runs reviewer generation steps sequentially (one process, <=4 requests in flight),
# logging the weekly usage before/after each step to results/review/usage_log.txt.
# Usage: bash code/review/run_queue.sh "task:model" ["task:model" ...]
cd "$(dirname "$0")"
export PYTHONIOENCODING=utf-8
P=../../.venv/Scripts/python.exe
LOG=../../results/review/usage_log.txt
for step in "$@"; do
  task=${step%%:*}; model=${step#*:}
  echo "$(date -Is) start $task $model $($P llm_common.py)" >> $LOG
  if [ "$task" = judge ]; then $P llm_judge.py; elif [ "$task" = rq3 ]; then $P llm_rq3_labels.py;
  elif [ "$task" = samples3 ]; then $P llm_rq5_generate.py --task samples --samples 3 --model "$model";
  else $P llm_rq5_generate.py --task "$task" --model "$model"; fi
  echo "$(date -Is) end   $task $model $($P llm_common.py)" >> $LOG
  w=$($P -c "import llm_common as c; print(c.usage()['weekly'])")
  $P -c "import sys; sys.exit(0 if $w < 0.47 else 1)" || { echo "stop: weekly $w" >> $LOG; exit 1; }
done

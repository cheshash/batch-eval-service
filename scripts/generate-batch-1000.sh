#!/usr/bin/env bash
# Generate a 1000-row JSONL batch file for load testing.
set -euo pipefail

OUT="${1:-batch-1000.jsonl}"
MODEL="${2:-deepseek-4-flash}"

: > "$OUT"
for i in $(seq 0 999); do
  printf '{"request_id":"batch-%04d","model":"%s","prompt":"Summarize batch row %d.","metadata":{"index":%d}}\n' \
    "$i" "$MODEL" "$i" "$i" >> "$OUT"
done

echo "Wrote 1000 rows to $OUT"

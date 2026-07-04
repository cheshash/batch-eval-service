#!/usr/bin/env bash
# End-to-end smoke test — requires docker compose stack running.
set -euo pipefail

API="${API_URL:-http://localhost:8000}"
MAX_WAIT="${MAX_WAIT_SECONDS:-120}"
POLL="${POLL_SECONDS:-2}"

echo "==> Health check"
curl -sf "$API/health" | grep -q ok

echo "==> Submit batch"
RESP=$(curl -sf -X POST "$API/v1/batches" -F "file=@sample_batch.jsonl")
JOB_ID=$(echo "$RESP" | python3 -c "import sys,json; print(json.load(sys.stdin)['job_id'])")
echo "    job_id=$JOB_ID"

echo "==> Poll until completed (max ${MAX_WAIT}s)"
elapsed=0
while [ "$elapsed" -lt "$MAX_WAIT" ]; do
  STATUS=$(curl -sf "$API/v1/batches/$JOB_ID" | python3 -c "import sys,json; print(json.load(sys.stdin)['status'])")
  echo "    status=$STATUS (${elapsed}s)"
  if [ "$STATUS" = "completed" ]; then
    break
  fi
  if [ "$STATUS" = "failed" ]; then
    curl -sf "$API/v1/batches/$JOB_ID"
    exit 1
  fi
  sleep "$POLL"
  elapsed=$((elapsed + POLL))
done

if [ "$STATUS" != "completed" ]; then
  echo "ERROR: job did not complete in time"
  exit 1
fi

echo "==> Download results URL"
RESULTS=$(curl -sf "$API/v1/batches/$JOB_ID/results")
URL=$(echo "$RESULTS" | python3 -c "import sys,json; print(json.load(sys.stdin)['download_url'])")
LINES=$(curl -sf "$URL" | wc -l | tr -d ' ')
echo "    result lines=$LINES"
if [ "$LINES" -lt 3 ]; then
  echo "ERROR: expected at least 3 result lines"
  exit 1
fi

echo "==> Smoke test passed"

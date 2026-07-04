# Batch Evaluation Service — Architecture

Async batch LLM evaluation (DigitalOcean-style): upload JSONL → validate → SQL + S3 → S3 notifies SQS → worker processes rows → poll status → download results.

---

## High-Level Diagram

```
┌─────────────┐     POST /v1/batches      ┌──────────────┐
│   Client    │ ────────────────────────► │  API (Javalin)│
└─────────────┘                           └──────┬───────┘
                                                 │
                    validate JSONL, detect priority model
                                                 │
                    ┌────────────────────────────┼────────────────────────────┐
                    ▼                            ▼                            │
              ┌──────────┐              ┌──────────────┐                      │
              │ SQLite   │              │ S3 (inputs/) │                      │
              │ batch_*  │              │ priority/    │                      │
              │ tables   │              │ standard/    │                      │
              └──────────┘              └──────┬───────┘                      │
                    ▲                          │ object-created               │
                    │                          ▼                              │
                    │                   ┌──────────┐                          │
                    │                   │   SQS    │                          │
                    │                   └────┬─────┘                          │
                    │                        │                                │
                    │                        ▼                                │
                    │              ┌──────────────────┐                       │
                    └──────────────│ Worker           │                       │
                                   │ (poll SQS)       │                       │
                                   └────────┬─────────┘                       │
                                            │                                   │
                         sort rows by PRIORITY_MODEL first                     │
                                            │                                   │
                                            ▼                                   │
                              ┌─────────────────────────┐                     │
                              │ Prompt endpoint         │                     │
                              │ • mock-prompt (dev)       │                     │
                              │ • DO Gradient (prod)      │                     │
                              └────────────┬────────────┘                     │
                                            │                                   │
                                            ▼                                   │
                              ┌─────────────────────────┐                     │
                              │ S3 results/{job_id}.jsonl│◄────────────────────┘
                              └─────────────────────────┘

GET /v1/batches/{id}           → job status + metrics (SQLite)
GET /v1/batches/{id}/results   → one-time presigned S3 download URL
```

---

## Modules (Maven / ACBDA)

| Layer | Package | Module | Responsibility |
|-------|---------|--------|----------------|
| **A**ctivity | `com.batcheval.activity` | `api`, `worker` | HTTP routes, SQS poll loop |
| **B**usiness | `com.batcheval.business` | `api`, `worker` | Submit, process rows, prompt HTTP |
| **D** | `com.batcheval.builder` | `api` | API response shapes |
| **A**ccessor | `com.batcheval.accessor` | `common` | S3, SQS |
| **A** (DAO) | `com.batcheval.dao` | `common` | SQLite jobs/files |
| Models | `com.batcheval.model` | `common` | JSONL schema, job status, errors |

Entry points: `ApiActivity`, `WorkerActivity`

---

## API Endpoints

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/health` | Liveness |
| `POST` | `/v1/batches` | Multipart upload `.jsonl` → job queued |
| `GET` | `/v1/batches/{job_id}` | Status + metrics |
| `GET` | `/v1/batches/{job_id}/results` | One-time presigned download URL |

---

## Input JSONL (one row per line)

```json
{
  "request_id": "eval-001",
  "model": "meta-llama-3-8b-instruct",
  "prompt": "Your prompt here.",
  "metadata": { "source": "optional" }
}
```

Limits: `MAX_REQUESTS_PER_FILE=1000`, `MAX_FILE_SIZE_BYTES=200MB`

---

## Submit Flow (API)

1. Validate filename (`.jsonl`), size, row count, schema
2. Detect **priority job** if any row uses `PRIORITY_MODEL`
3. **SQL first**: insert `batch_files` + `batch_jobs` (`high_priority` flag)
4. **S3 upload**: `inputs/priority/{file_id}.jsonl` or `inputs/standard/{file_id}.jsonl`
5. Return `{ job_id, status: queued }` — API does **not** enqueue SQS directly
6. On S3 failure → rollback SQL row

---

## Worker Flow

1. Long-poll SQS (S3 object-created events on `inputs/`)
2. Parse `file_id` from S3 key via `S3EventParser`
3. Lookup job in SQLite by `file_id`
4. Skip if job already `completed` / `failed`
5. Load JSONL from S3, set `in_progress`
6. **Sort rows**: `PRIORITY_MODEL` rows scheduled first (output order unchanged)
7. Thread pool (`WORKER_MAX_CONCURRENCY=4`) + per-row HTTP to prompt endpoint
8. **429 only**: exponential backoff + jitter, max 5 attempts
9. Upload `results/{job_id}.jsonl`, mark job `completed` with metrics

---

## Model Priority (3 layers)

| Layer | Trigger | Effect |
|-------|---------|--------|
| **Job** | Any row uses priority model | S3 prefix `inputs/priority/`, SQL `high_priority=1` |
| **Row** | Per-line `model` field | Priority model rows get thread slots first |
| **Future** | Separate SQS queues | Cross-job ordering (not implemented) |

Config: `PRIORITY_MODEL=meta-llama-3-8b-instruct`  
DO mapping: `meta-llama-3-8b-instruct` → `llama3-8b-instruct`

---

## Prompt / LLM Integration

| Mode | `PROMPT_API_KEY` | Endpoint |
|------|------------------|----------|
| **Mock** (default) | empty | `http://mock-prompt:9000/v1/evaluate` |
| **Real AI** | set (`sk-do-...` or scoped token) | `https://inference.do-ai.run/v1/chat/completions` |

Worker sends OpenAI chat format + `Authorization: Bearer` when key is set.

---

## Storage

| Store | Path / table | Contents |
|-------|--------------|----------|
| SQLite | `/data/batch_eval.db` | Job state, metrics, download consumed flag |
| S3 inputs | `inputs/priority\|standard/{file_id}.jsonl` | Uploaded JSONL |
| S3 results | `results/{job_id}.jsonl` | One output line per input row |

API and worker share SQLite volume + S3 bucket.

---

## Deployment (DigitalOcean Droplet)

```bash
git clone <repo> ~/batch-eval-service
cd ~/batch-eval-service
# optional .env for Gradient AI
docker compose up --build -d
```

Services: `localstack` (S3+SQS), `mock-prompt`, `api` (:8000), `worker`

**CI/CD**: GitHub Actions — `ci.yml` (mvn verify), `deploy.yml` (SSH + docker compose on push to main)

Secrets: `DROPLET_HOST`, `DROPLET_USER`, `DROPLET_SSH_KEY`

---

## Config (key env vars)

| Variable | Purpose |
|----------|---------|
| `DATABASE_URL` | `jdbc:sqlite:/data/batch_eval.db` |
| `AWS_ENDPOINT_URL` | LocalStack URL (empty for real AWS) |
| `S3_BUCKET`, `SQS_QUEUE_URL` | Object + queue |
| `PROMPT_ENDPOINT_URL` | Mock or Gradient |
| `GRADIENT_MODEL_ACCESS_KEY` / `PROMPT_API_KEY` | Real AI auth |
| `WORKER_MAX_CONCURRENCY` | Parallel row HTTP calls (default 4) |
| `MAX_REQUESTS_PER_FILE` | Max rows per batch (1000) |
| `PRIORITY_MODEL` | Model name for priority tier |

---

## Output JSONL (per row)

Success:
```json
{"request_id":"eval-001","status":"success","response":{"output":"..."},"latency_ms":42}
```

Failed:
```json
{"request_id":"eval-001","status":"failed","error":{"code":"client_error","message":"...","http_status":401,"attempts":1}}
```

---

## Known Limits / Future Work

- SQLite: single-node, not horizontally scalable
- No DLQ, no SQS visibility extension for long jobs
- Presigned URLs use LocalStack hostname (download from inside Docker on dev)
- Cross-job SQS priority queues: Phase 2
- Production: real AWS S3/SQS, Postgres, `sk-do-` Gradient keys, HTTPS reverse proxy

---

## Quick Test Commands

```bash
curl http://localhost:8000/health
curl -X POST http://localhost:8000/v1/batches -F "file=@sample_batch.jsonl"
curl http://localhost:8000/v1/batches/<job_id>
curl http://localhost:8000/v1/batches/<job_id>/results
# results file (on droplet):
docker compose exec api curl -s "http://localstack:4566/batch-eval-files/results/<job_id>.jsonl"
```

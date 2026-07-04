# Batch Evaluation Service

Async batch evaluation: **upload → SQL + S3 → S3 notifies SQS → worker**.

```
API:     validate → SQL (file + job) → S3 upload → return job_id
S3:      object-created event on inputs/ → SQS
Worker:  SQS message → lookup job by file_id → process rows from S3
```

## 3 APIs (+ health)

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/v1/batches` | Upload file → S3 + SQL → start job → `{ job_id, status: queued, ... }` |
| `GET` | `/v1/batches/{job_id}` | Poll status + metrics |
| `GET` | `/v1/batches/{job_id}/results` | One-time S3 download URL |
| `GET` | `/health` | Health check |

## Submit a batch

```bash
curl -X POST http://localhost:8000/v1/batches \
  -F "file=@sample_batch.jsonl"
```

Response:

```json
{
  "job_id": "...",
  "file_id": "...",
  "status": "queued",
  "created_at": "...",
  "metrics": { "total": 0, "completed": 0, "failed": 0 }
}
```

## Poll status

```bash
curl http://localhost:8000/v1/batches/<job_id>
```

## Download results (when completed)

```bash
curl http://localhost:8000/v1/batches/<job_id>/results
```

## Run locally

```bash
mvn verify
docker compose up --build
```

## Config

See `config/batch-eval.cfg` — `S3_BUCKET`, `SQS_QUEUE_URL`, `DATABASE_URL`, `PROMPT_ENDPOINT_URL`, `PRIORITY_MODEL`.

## Input JSONL schema

Each line is one evaluation request:

```json
{
  "request_id": "eval-001",
  "model": "meta-llama-3-8b-instruct",
  "prompt": "Summarize async batch processing.",
  "metadata": { "source": "sample" }
}
```

`model` is required and forwarded to the prompt HTTP endpoint.

## Model priority (S3 + worker)

Priority is layered at **job**, **S3**, and **row** level:

```
Submit (API)
  ├─ validate JSONL (model required)
  ├─ if any row uses PRIORITY_MODEL → job.high_priority = true
  ├─ SQL: batch_jobs.high_priority
  └─ S3 key: inputs/priority/{file_id}.jsonl  OR  inputs/standard/{file_id}.jsonl
       │
       ▼
S3 object-created (inputs/) → SQS → Worker
  ├─ parse file_id from inputs/{priority|standard}/{file_id}.jsonl
  ├─ within job: sort rows — PRIORITY_MODEL rows scheduled first
  └─ HTTP payload: { model, prompt, metadata }
```

| Tier | When | S3 prefix | Worker behavior |
|------|------|-----------|-----------------|
| **Job** | Any row uses `PRIORITY_MODEL` | `inputs/priority/` | `high_priority` flag in SQL (for future cross-job ordering) |
| **Row** | Per-line `model` field | (same file) | Priority-model rows get thread-pool slots first under contention |

**Phase 2 (scale):** wire separate SQS queues with S3 prefix filters — `inputs/priority/` → priority queue, `inputs/standard/` → standard queue — and poll the priority queue first. Single-queue setup works today because both prefixes match `inputs/`.

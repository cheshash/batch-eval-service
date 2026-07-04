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
./scripts/e2e-smoke.sh
```

### 1000-prompt batch file

```bash
chmod +x scripts/generate-batch-1000.sh
./scripts/generate-batch-1000.sh batch-1000.jsonl

curl -X POST http://localhost:8000/v1/batches -F "file=@batch-1000.jsonl"
```

Max rows per file: `MAX_REQUESTS_PER_FILE=1000` in `config/batch-eval.cfg`.

## CI/CD (GitHub Actions)

| Workflow | Trigger | What it does |
|----------|---------|--------------|
| `ci.yml` | PR + push | `mvn verify` |
| `deploy.yml` | push to `main`/`master` | test → SSH deploy to droplet |

### One-time setup: GitHub secrets

Repo → **Settings → Secrets and variables → Actions → New repository secret**

| Secret | Value |
|--------|--------|
| `DROPLET_HOST` | Droplet IP (e.g. `137.184.202.86`) |
| `DROPLET_USER` | SSH user (e.g. `root`) |
| `DROPLET_SSH_KEY` | Private SSH key (full PEM contents) |

On the droplet, clone the repo once:

```bash
git clone https://github.com/YOUR_USERNAME/batch-eval-service.git ~/batch-eval-service
cd ~/batch-eval-service && docker compose up --build -d
```

Every push to `main` runs tests then `git pull` + `docker compose up --build -d` on the droplet.


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

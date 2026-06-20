# Transaction Simulator

A long-running streaming service that replays real transaction history against the Account Service, producing a realistic and continuously growing event dataset for downstream analytics. Part of the [Scala Banking Platform](https://github.com/ovhfmn/scala-banking-platform).

---

## What it does

Discovers CSV transaction files in a mounted directory and opens each as an independent FS2 stream. Events are fired against the HTTP Service at the exact inter-event intervals from the original data — a transaction that occurred 30 seconds after the previous one in the CSV fires 30 seconds later in the simulation. Deterministic salary credits are injected per account on a derived monthly schedule. Each stream maintains its own checkpoint, so the service resumes precisely from where it stopped after a restart.

---

## Architecture

```
transactions_*.csv  (mounted directory)
        |
        | discovered at startup + watched for new files
        v
+-----------------------------------------------+
|              Event Driver                      |
|                                                |
|  FS2 file watcher                              |
|  per-file streams (parJoin)                    |
|  real-timestamp replay (IO.sleep)              |
|  salary injection (deterministic)              |
|  per-file checkpointing                        |
|  file archiving after exhaustion               |
+-----------------------------------------------+
        |
        | POST /accounts/{id}/debit
        | POST /accounts/{id}/credit
        v
   [HTTP Service]
        |
        v
  [Redpanda] --> rest of platform
```

## Control API

```bash
# Pause all streams (takes effect after current row completes, within ~500ms)
curl -X POST http://localhost:9090/control/pause

# Resume
curl -X POST http://localhost:9090/control/resume

# Status
curl http://localhost:9090/control/status
```

Status response:
```json
{
  "status": "running",
  "debits": 14823,
  "credits": 841,
  "salaries": 312,
  "total": 15976
}
```

---

## Tech stack

| | |
|---|---|
| Language | Scala 3 |
| Effects | Cats Effect 3 |
| Streaming | FS2 |
| HTTP client | http4s Ember |
| HTTP server | http4s Ember (control API) |
| JSON | Circe |
| Config | Ciris (env vars) |
| Logging | Log4Cats + Logback (JSON + rolling file) |
| Build | sbt + sbt-assembly |

---

## Configuration

All values read from environment variables at startup. Missing required variables cause a fast failure with a clear error message.

| Variable             | Default                    | Description                                |
|----------------------|----------------------------|--------------------------------------------|
| `ACCOUNT_SERVICE_URL` | `http://http-service:8080` | Account Service base URL                   |
| `DATA_DIR`           | `/app/data`                | Directory containing transaction CSV files |
| `CHECKPOINT_DIR`     | `/app/checkpoints`         | Per-file checkpoint storage                |
| `DATA_DIR`           | `/app/archive`             | Directory containing exhausted CSV files   |
| `CONTROL_PORT`       | `9090`                     | Control API port                           |
| `PARALLELISM`        | `4`                        | Max concurrent file streams                |

---

## CSV format

Required columns (resolved by header name):

| Column              | Used as |
|---------------------|---|
| `datetime`          | Event timestamp — drives replay timing |
| `client_id`         | Account ID in the HTTP Service |
| `amount`            | Signed decimal: positive = debit, negative = credit |

All other columns are ignored.

---

## Key design decisions

**Real-timestamp replay** — the service captures a wall-clock anchor at stream start and maps each row's `dateTime` to a relative offset. `IO.sleep` fires each event at the correct interval, preserving the original inter-event gaps without blocking any OS thread.

**Per-file independent checkpoints** — each CSV file gets its own checkpoint file (`filename.csv.checkpoint`). On restart, each stream resumes from its own last processed position independently. A slow file does not drag faster files back.

**Deterministic salary injection** — salary day is derived from `clientId` via hash (`clientId.hashCode.abs % 28 + 1`). Salary amount is 10% of `overdraftLimit` if the limit is ≥ 20,000, otherwise a seeded random value in [1500, 5500]. Both are fully reproducible — restarting never double-pays.

**Checkpoint tracks salary month** — the checkpoint file records `lastSalaryMonth` alongside `lastProcessedAt`. On restart, the service knows which accounts have already received salary for the current month without per-account state storage.

**Directory watching** — after exhausting all files, the service watches `DATA_DIR` for new CSV files via `Files[IO].watch`. When a new file appears, a new stream is spawned automatically. The service never exits unless stopped.

**File archiving** — exhausted files are moved to `DATA_DIR/archive/` after the last row is checkpointed. The watcher ignores archived files. Checkpoints for archived files are removed.

**`IO.race` for concurrent fibers** — the simulator and control API server run as two concurrent fibers under `IO.race`. If the simulator terminates, the control server is cancelled cleanly via its `Resource` finalizer. No connection leaks.

**Amount sign convention** — positive CSV amount = customer spending = debit (balance decreases). Negative CSV amount = income or refund = credit (balance increases). This matches standard retail banking dataset conventions.

---

## Features

- Failed rows are logged and skipped.
- Pause granularity is per-row (~500ms response time).
- `overdraftLimit` uses a safe default of 0
- Linear CSV scan on resume — rows before the checkpoint are skipped by reading and discarding.
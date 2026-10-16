# Design

EvalOps is a Spring Boot service with embedded H2 storage, accessed through Spring JDBC. It evaluates model endpoints that speak the OpenAI `/v1/completions` API, compares runs statistically, and gates CI on regressions.

## Concepts

* **Dataset.** A named, *immutable*, versioned list of items (`input`, optional `reference`, optional `meta`).
  * Uploading content identical to the latest version returns that version; any change creates version n + 1.
  * Each version stores the SHA-256 of its canonical JSON.
* **Target.** An endpoint URL, a model name and request parameters (`max_tokens`, `temperature`, `stop`, …).
  * A run stores a *snapshot* of its target's configuration. Editing a target later never changes what an old run means.
* **Run.** A dataset version × a target snapshot × a list of metrics.
  * Every item is sent and its output scored; one row per item holds the output, latency, attempt count, error and scores.
* **Metrics** (`metrics/Metrics.java`; each returns a score in [0, 1]):

  | Metric | Definition |
  |---|---|
  | `exact_match` | after whitespace normalisation |
  | `contains` | the output contains the reference |
  | `regex_match` | the reference is a regular expression |
  | `first_line_exact` | the first lines match (for code completion) |
  | `edit_similarity` | 1 − Levenshtein / max length |
  | `rouge_l_f1` | ROUGE-L F1 over whitespace tokens |
  | `bleu` | sentence BLEU-4, smoothed |
  | `json_valid` | the output parses as JSON |

## Execution

* **Concurrency.** Each run is bounded by a semaphore and runs on virtual threads.
* **Retries.** Transient failures (connection errors, 429, 5xx) are retried up to `max-attempts` times with exponential backoff, starting at 200 ms. Other errors fail the item.
  * A failed item scores 0 on every metric, so failures count against a run instead of disappearing.
  * A run in which every item failed is marked `failed`.
* **Response cache.** Responses are cached under SHA-256(target snapshot, input).
  * Re-running an identical configuration sends nothing and reproduces the same outputs.
  * `"cache": false` forces new requests, for example for sampling targets.
* **Resume.** Results are written per item. On start-up, runs that were `queued` or `running` resume with only their unfinished items.

## Reliability

### Transactional outbox (`outbox/`)

* **Events are written with the state change.** `run.queued` is inserted in the transaction that creates the run (and its tasks and idempotency key). `run.running` and `run.finished` are inserted in the transaction that changes the status. `run.finished` carries the item and error counts and the metric means. If a transaction rolls back, its event does not exist.
* **Relay.** `OutboxRelay` polls the `outbox` table every 200 ms and delivers unpublished events, oldest first, to every `EventConsumer`. An event is marked published only after all consumers accepted it. A crash or failure at any point before that leads to redelivery after restart, never to loss: delivery is **at least once**.
* **Ordering.** When an event fails, later events of the same run wait for the next pass. Other runs continue.
* **Idempotent consumers.** Each consumer records the event ids it has applied (`processed_event`). For database effects (`IdempotentConsumer`), the record and the effect commit in one transaction, so a redelivered event has no second effect. External effects (webhooks, GitHub) are recorded after they succeed, so they can repeat after a crash; receivers deduplicate by event id (`X-EvalOps-Event-Id`), and the GitHub publisher is idempotent by design.
* **Consumers.** `WebhookSink` POSTs each event to `evalops.outbox.webhooks`. `RunCounters` keeps counts of runs. `GitHubChecks` publishes check runs.

### Idempotency keys

`POST /runs` accepts an `Idempotency-Key` header. The key, a SHA-256 of the request body and the run id are stored in the run's creation transaction (primary key on the key).

* Same key, same body: 200 with the original run and `Idempotent-Replayed: true`. Nothing new is created.
* Same key, different body: 422.
* Concurrent first attempts: one insert wins; the others hit the key constraint, roll back completely, and return the winner's run (tested with 8 concurrent requests).

### Out-of-process workers (`api/WorkerApi.java`, `worker/worker.py`)

A run created with `"executor": "worker"` is not executed in the service. Its items become rows of the `task` table, and external workers lease them.

* **Lease.** `POST /worker/lease` atomically takes one task that is queued, or whose lease has expired. It is a conditional `UPDATE` on the task's current token, so of two racing workers only one wins. Every lease **increments the task's fencing token**.
* **Heartbeat.** The worker renews its lease every `lease_ms / 3`. A worker that dies or is paused stops renewing, and the task can be leased again with a higher token.
* **Fenced completion.** `POST /worker/complete` stores the result only if the token is still current. The check and the result write are one transaction. A late result from a worker whose lease was taken over gets **409** and is discarded. The service scores the output itself, so metrics are the same as for local runs.
* **The worker** is Python with only the standard library. It runs N threads; each leases, calls the target with retries, and posts the result.

### GitHub check runs (`outbox/GitHubChecks.java`)

A run created with `"github": {"owner", "repo", "head_sha", "gate"?}` gets one check run on that commit.

* `run.queued` creates it (`status: queued`, `external_id: evalops-run-<id>`). `run.running` sets `in_progress`. `run.finished` completes it with a conclusion and a Markdown summary of the metrics.
* **Conclusion.** With a `gate` (`{"baseline": run, "rules": [...]}`), the gate's verdict. Without one, `success` if the run finished.
* **Idempotency.** The check-run id is stored per run. Before creating, the publisher lists the commit's check runs and reuses one with the same `external_id`. A redelivered event, or a crash between GitHub's answer and storing the id, updates the existing check run instead of creating a second one.
* **Requests** carry `Authorization: Bearer <token>`, `Accept: application/vnd.github+json` and `X-GitHub-Api-Version: 2022-11-28`.
* **Auth.** A token (`evalops.github.token`), or a GitHub App (`app-id`, `installation-id`, `private-key`): the service signs an RS256 JWT and exchanges it for an installation token, cached until 5 minutes before it expires.
* **Errors.** 401, 403, 408, 429, 5xx and connection errors are retried by the relay. Other 4xx answers are permanent: the update is dropped with a warning.

### LLM-as-judge

See [judge.md](judge.md).

## Statistics (`stats/Stats.java`)

Resampling is seeded from the run ids, so a report is reproducible.

* **Run summary.** For each metric, the mean and a 95 % percentile bootstrap interval. Also the latency p50 and p95, error and cache counts.
* **Comparison.** Two runs on the **same dataset version** are paired item by item. Runs on different versions are refused.
  * For each metric: the mean difference, a 95 % bootstrap interval of it, and a two-sided **sign-flip permutation** p-value. That p-value is exact under the null that each item's difference is symmetric about zero.
  * Pairing removes the variance from item difficulty, which both runs share.
  * The comparison also lists the items that regressed most on the first metric, with both outputs.
* **Gates.** Each rule may set two independent conditions:
  * `maxDrop`: fail if the metric drops by more than this **and** the drop is significant (p < `alpha`, default 0.05);
  * `minValue`: fail if the candidate's mean is below this.

  `scripts/gate.sh` turns the result into an exit code for CI.

## API

| Method | Path | Purpose |
|---|---|---|
| POST | `/datasets` | `{name, items: [{input, reference?, meta?}]}` → version |
| GET | `/datasets`, `/datasets/{name}/{version}` | list, fetch with items |
| PUT | `/targets/{name}` | `{url, model?, params?}` |
| POST | `/runs` | `{dataset, version?, target, metrics: [...], concurrency?, cache?, executor?, judge?, github?}` → 202 `{run}`; optional `Idempotency-Key` header |
| POST | `/worker/lease`, `/worker/heartbeat`, `/worker/complete` | worker task leasing with fencing tokens (409 for a stale token) |
| GET | `/runs/{id}`, `/runs/{id}/results` | summary with intervals; per-item rows |
| GET | `/compare?baseline=&candidate=` | paired comparison and regressions |
| POST | `/gates/check` | `{baseline, candidate, rules: [{metric, maxDrop?, alpha?, minValue?}]}` → `{pass, checks}` |
| GET | `/actuator/prometheus` | item counters by outcome (ok, cached, error) |

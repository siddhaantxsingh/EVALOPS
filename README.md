# EvalOps (project 19)

An evaluation service for LLM endpoints, built with Java 21, Spring Boot 3 and H2. It:

* keeps **versioned, immutable datasets**;
* runs them against any OpenAI-style `/v1/completions` endpoint, with bounded concurrency, retries and a response cache, and resumes after a crash;
* scores outputs with standard metrics;
* compares runs with **paired bootstrap statistics**;
* turns those comparisons into **CI gates**;
* writes job events through a **transactional outbox** (at-least-once relay, idempotent consumers) and accepts **idempotency keys** on run creation;
* can hand items to a pool of out-of-process **Python workers** that lease them, with lease expiry and fencing tokens;
* scores with an **LLM judge** (fixed rubric, greedy decoding) and **validates the judge** against labels;
* publishes runs as **GitHub check runs**, idempotently, with token or GitHub App auth.

**Status.** All 16 tests pass locally (`mvn verify`). The worker tests kill and pause real Python worker processes. The judge was validated with the real SmolLM2-135M model, and that judge is **not usable**: its agreement with labels is at chance level (κ 0.00 and 0.08, [docs/judge.md](docs/judge.md)). GitHub checks are verified only against a local fake GitHub API; a real App is an owner step ([docs/OWNER_ACTIONS.md](docs/OWNER_ACTIONS.md)). The demo evaluates this repository's own `llm-inference-engine` ([docs/demo.md](docs/demo.md)).

## Demo result

300 next-line completions of held-out Python code, scored against the f32 model:

| candidate | first-line exact Δ | edit similarity Δ | gate |
|---|---|---|---|
| int8 weights | +0.007 (p = 0.49) | −0.002 (p = 0.66) | **pass** |
| 1-layer draft model | −0.137 (p < 0.001) | −0.085 (p < 0.001) | **fail** |

## Quick start

```sh
mvn -B package
java -jar target/evalops-0.1.0.jar                                   # :8095, H2 file in ./data
curl -X POST :8095/datasets -H 'Content-Type: application/json' -d '{"name":"qa","items":[{"input":"2+2=","reference":"4"}]}'
curl -X PUT  :8095/targets/my-model -H 'Content-Type: application/json' -d '{"url":"http://127.0.0.1:8080/v1/completions","params":{"max_tokens":8}}'
curl -X POST :8095/runs -H 'Content-Type: application/json' -d '{"dataset":"qa","target":"my-model","metrics":["exact_match"]}'
curl :8095/runs/1                                                    # means with 95 % intervals
scripts/gate.sh http://127.0.0.1:8095 1 2 '[{"metric":"exact_match","maxDrop":0.01}]'   # exit 0 / 1 / 2
demo/run_demo.sh demo/results                                        # the llm-inference-engine evaluation

# retry-safe creation, worker execution, judge and GitHub check
curl -X POST :8095/runs -H 'Idempotency-Key: ci-123' -H 'Content-Type: application/json' -d '{"dataset":"qa","target":"my-model","metrics":["exact_match"],"executor":"worker"}'
python3 worker/worker.py --evalops http://127.0.0.1:8095 --threads 4          # stdlib only
judge/run_validation.sh docs/evidence/judge                                    # judge vs labels, real SmolLM2 model
```

## Documentation

* [Design](docs/design.md): data model, execution, statistics and API.
* [Testing](docs/testing.md): metrics on known values, the bootstrap's coverage, false-positive rate and power, and the reliability tests (outbox crashes, duplicate delivery, killed workers).
* [LLM judge and its validation](docs/judge.md).
* [Owner actions](docs/OWNER_ACTIONS.md): real GitHub App, stronger judge.
* [Demo](docs/demo.md).
* [Limitations](docs/limitations.md).
* [Requirements matrix](docs/requirements.md).

## Scorecard

| Score (0–10) | Value | Why not higher |
|---|---|---|
| Fresher-portfolio strength | 8 | Covers outbox, idempotency, fencing tokens and judge validation with real tests, but it is one service with no real users or real GitHub integration run. |
| Engineering maturity | 7 | Crash tests for the outbox are simulated in-process (no JVM kill), there is no dead-letter handling, and no auth on any endpoint. |
| Deployment readiness | 4 | Single instance on embedded H2, no auth, GitHub App never run against real GitHub, no container image or deployment manifests. |
| Completion of intended scope | 7 | Outbox, idempotency, workers and checks are done; the judge works but the only judge model available here is at chance, and real GitHub use is owner-blocked. |

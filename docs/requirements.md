# Requirements matrix — Project 19 (EvalOps)

| Requirement | Status | Evidence |
|---|---|---|
| Versioned, immutable datasets | VERIFIED | `datasetsAreVersionedByContent` |
| Evaluation runs against model endpoints (concurrency, retries, errors) | VERIFIED | `runsRetryCacheAndReport` |
| Reproducibility: target snapshots, response cache, seeded statistics | VERIFIED | cache test (zero requests), reproducibility test |
| Metrics library | VERIFIED | known-value tests |
| Confidence intervals and paired significance tests | VERIFIED | coverage, false-positive-rate and power simulations |
| Regression gates for CI | VERIFIED | gate tests; `scripts/gate.sh` |
| Resume after interruption | VERIFIED | `interruptedRunsResumeWithoutRedoingFinishedItems` |
| Evaluation of a real model | VERIFIED | `docs/demo.md` (llm-inference-engine f32 vs int8) |
| Transactional outbox: events in the state-change transaction, at-least-once relay, consumers idempotent by event id | VERIFIED | `eventsCommitWithStateAndAreNotLostWhenPublishingFails`, `crashAfterDeliveryRedeliversAndConsumersApplyEachEventOnce` (crashes simulated in-process) |
| Idempotency keys on run creation (incl. concurrent retries) | VERIFIED | `idempotencyKeysMakeRunCreationSafeToRetry` |
| Out-of-process Python worker pool with leases, heartbeats and fencing tokens; stale results rejected | VERIFIED | `workersLeaseTasksAndAKilledWorkersTaskIsTakenOver` (SIGKILL), `aPausedWorkersLateResultIsRejectedByItsFencingToken` (SIGSTOP/SIGCONT) |
| LLM-as-judge: fixed rubric, deterministic decoding, cached verdicts | VERIFIED | `judgeScoresWithAFixedRubricAndGreedyDecoding` (fake chat endpoint) |
| Judge validation with the real local model (SmolLM2-135M) | VERIFIED (the judge itself performs at chance: κ 0.00 / 0.08) | [judge.md](judge.md); `docs/evidence/2026-10-15/judge/` |
| GitHub check runs: request shape, auth headers, idempotent create/update, App JWT auth | VERIFIED against a local fake API | `checkRunsArePublishedOnceWithTheRightShape`, `gitHubAppAuthMintsAnInstallationToken` |
| GitHub checks with a real GitHub App | BLOCKED (owner credentials) | [OWNER_ACTIONS.md](OWNER_ACTIONS.md) |
| Chat-format targets, multi-turn, sandboxed code execution | NOT IMPLEMENTED | limitations |
| Auth, multi-tenancy (including worker endpoints) | NOT IMPLEMENTED | limitations |
| CI | VERIFIED | `.github/workflows/evalops.yml` (run 38562292362 green; after the outbox/worker/judge/checks change: runs 38781917424 and 38782978243 green) |

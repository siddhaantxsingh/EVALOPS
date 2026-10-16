# Testing

`mvn verify` runs 16 tests in about a minute. The worker tests need `python3` on the PATH. Output of the last local run: `docs/evidence/2026-10-15/mvn_verify_reliability.log`.

## `MetricsStatsTest`

* **Metrics on hand-computed cases.**
  * Edit similarity of kitten/sitting is 1 − 3/7.
  * The ROUGE-L of a sentence with one token deleted is checked against its hand-computed value.
  * Identical sentences get BLEU 1; disjoint ones get 0.
  * An invalid regular expression scores 0 instead of throwing.
* **Bootstrap coverage.** On 300 simulated Bernoulli(0.6) samples of size 200, the 95 % interval contains the true mean 91–98 % of the time (the assertion's bounds).
* **Paired test calibration.** Each simulated item has a difficulty that affects both runs. Over 300 trials at n = 300:
  * between two runs of equal quality, the false-positive rate at α = 0.05 is within 2–9 %;
  * a +0.15 effect is detected in more than 90 % of trials.

  The analytic power is about 0.98. A first version used n = 150, where the power is only about 0.82; the test's expectation was wrong, not the code, and the test was corrected.
* **Reproducibility.** The same inputs and seed give the same report.

## `EvalOpsIntegrationTest`

These run the real service with an in-memory H2 database, against a fake OpenAI-compatible endpoint (a JDK HTTP server). The fake's answer accuracy is a request parameter, and which items it answers correctly is a hash of the prompt, so runs are deterministic. It also injects one 503 per "FLAKY" item and a 400 for every "BROKEN" item.

| Test | What it proves |
|---|---|
| `datasetsAreVersionedByContent` | Identical uploads reuse a version; any change creates the next one; invalid names and empty datasets are rejected. |
| `runsRetryCacheAndReport` | **Retries.** Every flaky item takes exactly 2 attempts (120 requests for 60 items), and the run scores 1.0. **Caching.** An identical second run sends **zero** requests (60 cached). `cache: false` sends 60. **Permanent errors.** A 400 is not retried: the items are recorded as errors and the run is `failed`. **Validation.** Unknown metrics, targets and datasets are rejected. |
| `comparisonsAndGatesSeparateRealRegressionsFromNoise` | 300 items; targets of accuracy 0.85, 0.85 (a different configuration) and 0.65. **Real regression.** The 0.85 → 0.65 drop has a negative interval and p < 0.01, its regressions list holds real worse items, and the gate fails. **No regression.** Reversing the direction passes the gate. **Equal quality** passes too: no false alarm. **Floors.** A `minValue` rule fails with its own reason. **Pairing.** Runs on different dataset versions are refused. |
| `interruptedRunsResumeWithoutRedoingFinishedItems` | A run "interrupted" after 7 of 20 items resumes on start-up and sends exactly 13 requests. |

The integration tests found a design flaw. A rule with only `minValue` also applied a drop check, because `maxDrop` silently defaulted to 0. Each condition now applies only when given, and a rule with neither is rejected.

## `ReliabilityIntegrationTest`

The real service, with the outbox relay's schedule off (tests drain it explicitly), against one fake HTTP server that plays a completions target, a chat judge, a webhook receiver and the GitHub REST API.

| Test | What it proves |
|---|---|
| `eventsCommitWithStateAndAreNotLostWhenPublishingFails` | A finished run has exactly `run.queued`, `run.running`, `run.finished` in the outbox, unpublished, before any relay ran (the "crash between commit and publish"). With the webhook down, nothing is published or lost, and the run's later events are not attempted out of order. A **new relay instance** then publishes all three, once each. |
| `crashAfterDeliveryRedeliversAndConsumersApplyEachEventOnce` | A fault hook throws right after the webhook accepted `run.finished`. The next pass redelivers it: the webhook sees the same event id **twice** (at least once), the run counter moves **once**. Two more direct deliveries to the counter change nothing. |
| `idempotencyKeysMakeRunCreationSafeToRetry` | A replay returns the same run with `Idempotent-Replayed: true`; a different body with the same key is 422; **8 concurrent first attempts** create exactly one run and one `run.queued` event. |
| `checkRunsArePublishedOnceWithTheRightShape` | The exact call sequence (list, create, in_progress, completed), headers (`Bearer` token, `application/vnd.github+json`, API version) and bodies (name, head_sha, external_id, conclusion, output title and summary with the gate result). A redelivered `run.finished`, and a redelivery after the stored check-run id was lost, create **no second check run**. A request without `head_sha` is 400. |
| `gitHubAppAuthMintsAnInstallationToken` | With App settings, the service signs a JWT that the fake verifies (RS256 signature with a generated key, issuer, lifetime ≤ 10 min), gets an installation token, uses it, and caches it. |
| `judgeScoresWithAFixedRubricAndGreedyDecoding` | `llm_judge` without a judge is 400. Verdicts YES/NO/unparsable score 1/0/0 with the parse flag; requests carry temperature 0, seed 0 and the fixed rubric; an identical run sends **zero** judge requests. |
| `workersLeaseTasksAndAKilledWorkersTaskIsTakenOver` | Three Python worker processes finish a 6-item run. Then a worker is **killed with SIGKILL** in the middle of a 2-second item; after its 1-second lease expires a second worker takes the item with token 2 and finishes the run. A completion with the dead worker's token 1 is 409. |
| `aPausedWorkersLateResultIsRejectedByItsFencingToken` | A worker is **paused with SIGSTOP** mid-item, so its lease expires; another worker takes over and finishes. After **SIGCONT**, the first worker posts its result with the old token, gets 409 and logs "result discarded (stale lease)". The stored result is the second worker's. |

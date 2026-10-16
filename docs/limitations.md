# Limitations

* **No security layer.** The service has no authentication or multi-tenancy: deploy it behind an authenticating proxy. The worker endpoints are unauthenticated too.
* **One target protocol.** Only OpenAI-style `/v1/completions` is supported: one prompt in, `choices[0].text` out. There is no chat format, tool calls or multi-turn conversations.
* **The LLM judge was validated only with a 135M model, and that judge is unusable** (κ ≈ 0 against labels; [judge.md](judge.md)). A larger judge model was not run here. The hand-labelled set has 50 items and one annotator.
* **No execution-based metrics.** There is no pass@k, because running model-generated code needs a sandbox.
* **Embedded H2 and a single instance.** Local runs resume after a restart, but only on the same instance. Worker runs can be served by any number of worker processes, but there is still one EvalOps instance and one database.
* **The outbox relay is single-instance and polls.** It delivers at least once, not exactly once. External receivers must deduplicate by event id. A permanently failing event blocks later events of the same run (they are retried every pass). There is no dead-letter table.
* **Crash tests are simulated in-process.** "Crash between commit and publish" is tested by committing and not running the relay, then running a fresh relay; "crash after delivery" by a fault hook that throws. The JVM itself is not killed in the tests. Worker crashes are real: the Python process is killed with SIGKILL, or paused with SIGSTOP.
* **Worker runs have no response cache**, and the worker supports only the `/v1/completions` target protocol.
* **GitHub checks were tested only against a local fake API.** No real GitHub App was used (owner step: [OWNER_ACTIONS.md](OWNER_ACTIONS.md)). The App private key must be PKCS#8 (`openssl pkcs8 -topk8`).
* **A failed item scores 0.** This is deliberate: errors count against a run. It does mean that infrastructure failures and model failures are mixed in the means; error counts are reported separately.
* **The gate's significance is per metric.** There is no correction for multiple comparisons across rules.

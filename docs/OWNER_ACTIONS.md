# Owner actions

Steps only the repository owner can do. Each lists the exact commands, the expected output and what to save.

## 1. Publish EvalOps results as real GitHub check runs

The publisher is tested against a fake GitHub API only. To use it for real:

1. **Create a GitHub App** (GitHub → Settings → Developer settings → GitHub Apps → New GitHub App).
   * Homepage URL: anything. Webhook: off.
   * Repository permissions: **Checks: Read and write**, Metadata: Read-only.
   * Create it, note the **App ID**, and generate a **private key** (downloads `app.pem`).
2. **Install the App** on the repository (App page → Install App). The installation id is the number at the end of the installation's URL (`.../settings/installations/<id>`).
3. **Convert the key to PKCS#8** (Java reads only that format):
   ```sh
   openssl pkcs8 -topk8 -nocrypt -in app.pem -out app-pkcs8.pem
   ```
4. **Start EvalOps** with the App settings:
   ```sh
   java -jar target/evalops-0.1.0.jar --evalops.github.app-id=<APP_ID> \
     --evalops.github.installation-id=<INSTALLATION_ID> --evalops.github.private-key=$PWD/app-pkcs8.pem
   ```
   (Or, for a quick test, a fine-grained personal token with Checks write access: `--evalops.github.token=<TOKEN>`.)
5. **Create a run for a real commit** of that repository:
   ```sh
   curl -X POST :8095/runs -H 'Content-Type: application/json' -H 'Idempotency-Key: try-1' -d '{"dataset":"qa","target":"my-model",
     "metrics":["exact_match"],"github":{"owner":"<OWNER>","repo":"<REPO>","head_sha":"<40-char SHA>"}}'
   ```
6. **Expected:** within a second of the run finishing, the commit shows a check named **EvalOps** with conclusion success or failure and a metrics table. The EvalOps log has no `GitHub check for run ... dropped` warning.
7. **Save:** a screenshot of the check, and `curl -s -H "Authorization: Bearer <TOKEN>" https://api.github.com/repos/<OWNER>/<REPO>/commits/<SHA>/check-runs` output, into `docs/evidence/<date>/github_check/`. Then mark the "real GitHub App" row in `docs/requirements.md` VERIFIED.

Never commit `app.pem`, `app-pkcs8.pem` or tokens.

## 2. Validate a stronger judge model

The 135M judge is at chance level ([judge.md](judge.md)). To test a larger model, run any OpenAI-compatible chat server on a machine with enough memory (for example llama.cpp's `llama-server` with a 7B+ instruct GGUF), then:

```sh
mvn -B package -DskipTests
# start EvalOps on :18095 (see judge/run_validation.sh), then:
python3 judge/validate.py --evalops http://127.0.0.1:18095 --judge-url http://<HOST>:<PORT>/v1/chat/completions \
  --out docs/evidence/<date>/judge-<model> judge/labelled.jsonl judge/arithmetic.jsonl
```

Expected: a printed report per set with `llm_judge.kappa`. Save the output directory. A judge is fit for gating only if κ is high (for example above 0.6) on both sets; also add more hand labels from a second annotator.

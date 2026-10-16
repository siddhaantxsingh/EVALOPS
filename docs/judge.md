# LLM-as-judge and its validation

## How the judge works

`llm_judge` is a metric. A run that uses it names a **judge target**: an OpenAI-style `/v1/chat/completions` endpoint.

```sh
curl -X PUT :8095/targets/judge -H 'Content-Type: application/json' -d '{"url":"http://127.0.0.1:8081/v1/chat/completions"}'
curl -X POST :8095/runs -H 'Content-Type: application/json' \
  -d '{"dataset":"qa","target":"my-model","judge":"judge","metrics":["exact_match","llm_judge"]}'
```

* **Fixed rubric** (`Judge.SYSTEM`, version `correctness-v1`). The judge sees the question, the reference answer and the candidate answer, and must reply with one word, YES or NO.
* **Deterministic decoding.** `temperature` 0, `seed` 0, `max_tokens` 3. The judge's configuration is stored with the run (`judge_snapshot`), like the target's.
* **Parsing.** The first word decides: YES scores 1, NO scores 0. Anything else scores 0 and is recorded as unparsed (`llm_judge_parsed` = 0 in the item's scores).
* **Cache.** Verdicts are cached under the judge configuration, the rubric and the full prompt, so a re-run gives the same verdicts without new requests (tested).

## Validation with the real local model

The judge was run with **SmolLM2-135M-Instruct** (F16 GGUF, served by this repository's `llm-inference-engine`) on two labelled sets.

* `judge/labelled.jsonl`: **50 short factual questions**, each with a reference answer, a candidate answer and a label (25 correct, 25 wrong). Correct candidates are often paraphrases ("Eight" for "8", "CO2" for "Carbon dioxide"), so string metrics miss them. The labels were written by hand during development, by the coding agent that built this feature, not by an independent annotator. They are simple factual judgements, but they are one person's labels.
* `judge/arithmetic.jsonl`: **60 additions** ("What is 17 + 25?"). The candidate is the right sum or is off by a small amount. The label is computed, not judged (34 correct, 26 wrong).

`judge/run_validation.sh` starts the engine and EvalOps, and `judge/validate.py` runs every set through EvalOps. A small replay server plays the "model under test" and returns each stored candidate, so the whole path is exercised: datasets, runs, metrics, judge and cache. Each set is run twice without the cache, to check determinism.

Results (`docs/evidence/2026-10-15/judge/judge_validation.json`). The machine was shared with other agents' jobs; load average 111–142 on 4 vCPUs during the run. That affects speed, not verdicts.

| set | scorer | accuracy vs labels | Cohen's κ | says "correct" | TP / TN / FP / FN |
|---|---|---|---|---|---|
| hand-labelled (50) | **llm_judge** | 0.50 | **0.00** | 16 % | 4 / 21 / 4 / 21 |
| hand-labelled (50) | exact_match | 0.62 | 0.24 | 12 % | 6 / 25 / 0 / 19 |
| hand-labelled (50) | contains | 0.72 | 0.44 | 22 % | 11 / 25 / 0 / 14 |
| arithmetic (60) | **llm_judge** | 0.57 | **0.08** | 73 % | 26 / 8 / 18 / 8 |
| arithmetic (60) | exact_match | 1.00 | 1.00 | 57 % | 34 / 26 / 0 / 0 |

* **Parse rate.** The judge's reply started with YES or NO for 46 % of the hand-labelled items and 73 % of the arithmetic items. On arithmetic, every parsed reply was YES: the 73 % "correct" rate is exactly the parse rate.
* **Determinism.** The second, uncached run gave identical verdicts on 50 / 50 and 60 / 60 items.
* **Judge vs exact match** on the hand-labelled set: 0.72 agreement, κ = −0.16. The agreement is no better than chance.

## What this means

**A 135M-parameter judge is not usable.** Its agreement with the labels is at chance level (κ ≈ 0), it often does not follow the one-word format, and on arithmetic it says YES whenever it answers. It is worse than `contains` on the hand-labelled set and far worse than `exact_match` on arithmetic. This was expected for a model this small, and the validation shows it with numbers instead of assuming it.

What the feature does show:

* The plumbing is correct and tested: rubric, deterministic decoding, caching, parse tracking, judge configuration snapshots, and agreement measurement against labels.
* The validation harness is the important part. Point `judge` at a larger model (an owner step; see [OWNER_ACTIONS.md](OWNER_ACTIONS.md)) and re-run `judge/run_validation.sh` before trusting its scores. A judge should be used for gating only after its κ against labels is high (for example above 0.6).
* The hand-labelled set is small (50 items) and was labelled by one annotator. The confidence in any κ from it is wide.

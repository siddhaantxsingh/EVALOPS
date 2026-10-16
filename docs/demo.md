# Demo: evaluating llm-inference-engine

`demo/run_demo.sh` starts three `llm serve` processes from `../llm-inference-engine`:

* the 3.0 M-parameter model with f32 weights;
* the same model with int8 weights;
* the 1-layer draft model.

It then evaluates each through EvalOps on **300 next-line completions of held-out Python standard-library code** (`demo/make_dataset.py`). Each item is up to 600 bytes of code, and the reference is the next non-blank line. Generation is greedy, up to 32 tokens, stopping at a newline. Raw responses are in `demo/results/`.

## Runs

| model | first line exact | edit similarity | BLEU | latency p50 | cached |
|---|---|---|---|---|---|
| f32 | 0.160 [0.120, 0.200] | 0.499 [0.467, 0.531] | 0.314 | 592 ms | 35 |
| int8 | 0.167 [0.127, 0.210] | 0.497 [0.464, 0.532] | 0.315 | 657 ms | 35 |
| draft | 0.023 [0.007, 0.040] | 0.414 [0.385, 0.445] | 0.173 | 91 ms | 36 |

Brackets are 95 % bootstrap intervals. The dataset has 36 duplicate inputs, because the generator drew some positions twice. *Cached* counts those answered from the response cache; where both copies of a duplicate were in flight at the same moment, both were sent.

## Comparisons against f32 (paired over the same 300 items)

| candidate | metric | delta | 95 % interval | p |
|---|---|---|---|---|
| int8 | first_line_exact | +0.0067 | [+0.0000, +0.0167] | 0.4893 |
| int8 | edit_similarity | -0.0016 | [-0.0086, +0.0052] | 0.6552 |
| int8 | bleu | +0.0011 | [-0.0054, +0.0067] | 0.7326 |
| draft | first_line_exact | -0.1367 | [-0.1800, -0.0933] | 0.0005 |
| draft | edit_similarity | -0.0845 | [-0.1099, -0.0596] | 0.0005 |
| draft | bleu | -0.1410 | [-0.1751, -0.1088] | 0.0005 |

## CI gate

Rules: `first_line_exact` and `edit_similarity` may not drop by more than 0.02 with p < 0.05.

```
PASS first_line_exact: ok (baseline 0.1600, candidate 0.1667, p = 0.4893)
PASS edit_similarity: ok (baseline 0.4989, candidate 0.4973, p = 0.6552)
→ int8: exit 0 (pass)

FAIL first_line_exact: dropped 0.1367 (p = 0.0005) beyond the allowed 0.0200 (baseline 0.1600, candidate 0.0233, p = 0.0005)
FAIL edit_similarity: dropped 0.0845 (p = 0.0005) beyond the allowed 0.0200 (baseline 0.4989, candidate 0.4144, p = 0.0005)
→ draft: exit 1 (fail)
```

## What it shows

* **int8 weights are indistinguishable from f32 on this task.** Every delta is within ±0.007 and every interval contains 0, so the gate passes. This agrees with the engine's own perplexity measurement (+0.01 %).
* **The draft model is clearly worse,** and the regression is significant (p < 0.001 on both gated metrics). The gate fails. `compare_f32_draft.json` lists the worst regressions with both outputs.
* **Writing this demo found a bug in `scripts/gate.sh`.** The first version's Python one-liner did not parse. It exited with status 1, which a CI job would read as a failed gate. Response-parsing problems now exit 2, distinct from a real failure (checked: an unknown metric gives exit 2).
* **It also found a bug in the engine.** `llm serve` did not handle SIGTERM; it now shuts down gracefully on it.

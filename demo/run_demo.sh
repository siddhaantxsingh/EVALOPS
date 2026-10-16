#!/bin/sh
# EvalOps evaluating llm-inference-engine: the f32 model vs its int8-weight
# copy vs the 1-layer draft model, on next-line completion of held-out code.
# usage: demo/run_demo.sh OUT_DIR   (needs ../llm-inference-engine built)
set -eu
OUT=${1:-demo/results}
mkdir -p "$OUT"
LLM=../llm-inference-engine/target/release/llm
M=../llm-inference-engine/models
TMP=$(mktemp -d)
trap 'kill $(jobs -p) 2>/dev/null; rm -rf "$TMP"' EXIT
$LLM serve --listen 127.0.0.1:18101 --model $M/small.llmw --tokenizer $M/tokenizer.json 2>/dev/null &
$LLM serve --listen 127.0.0.1:18102 --model $M/small.llmw --tokenizer $M/tokenizer.json --q8 2>/dev/null &
$LLM serve --listen 127.0.0.1:18103 --model $M/draft.llmw --tokenizer $M/tokenizer.json 2>/dev/null &
java -jar target/evalops-0.1.0.jar --server.port=18100 --spring.datasource.url="jdbc:h2:file:$TMP/db" >/dev/null 2>&1 &
for p in 18101 18102 18103 18100; do
  until curl -sf "http://127.0.0.1:$p/$( [ $p = 18100 ] && echo actuator/health || echo healthz )" >/dev/null; do sleep 0.5; done
done
E=http://127.0.0.1:18100
python3 demo/make_dataset.py "$TMP/ds.json" 300 >/dev/null
curl -sf -X POST $E/datasets -H 'Content-Type: application/json' -d @"$TMP/ds.json" > "$OUT/dataset.json"
for t in f32:18101 int8:18102 draft:18103; do
  name=${t%%:*}; port=${t##*:}
  curl -sf -X PUT $E/targets/$name -H 'Content-Type: application/json' \
    -d "{\"url\":\"http://127.0.0.1:$port/v1/completions\",\"model\":\"$name\",\"params\":{\"max_tokens\":32,\"temperature\":0,\"stop\":[\"\\n\"]}}" >/dev/null
done
run() {
  id=$(curl -sf -X POST $E/runs -H 'Content-Type: application/json' \
    -d "{\"dataset\":\"stdlib-next-line\",\"target\":\"$1\",\"metrics\":[\"first_line_exact\",\"edit_similarity\",\"bleu\"],\"concurrency\":8}" \
    | python3 -c 'import json,sys; print(json.load(sys.stdin)["run"])')
  until curl -sf $E/runs/$id | grep -q '"status":"done"'; do sleep 1; done
  curl -sf $E/runs/$id > "$OUT/run_$1.json"
  echo $id
}
F=$(run f32); Q=$(run int8); D=$(run draft)
curl -sf "$E/compare?baseline=$F&candidate=$Q&regressions=3" > "$OUT/compare_f32_int8.json"
curl -sf "$E/compare?baseline=$F&candidate=$D&regressions=3" > "$OUT/compare_f32_draft.json"
RULES='[{"metric":"first_line_exact","maxDrop":0.02},{"metric":"edit_similarity","maxDrop":0.02}]'
scripts/gate.sh $E $F $Q "$RULES" > "$OUT/gate_int8.txt" && echo "int8 gate: PASS" || echo "int8 gate: FAIL"
scripts/gate.sh $E $F $D "$RULES" > "$OUT/gate_draft.txt" && echo "draft gate: PASS" || echo "draft gate: FAIL"
cat "$OUT/gate_int8.txt" "$OUT/gate_draft.txt"

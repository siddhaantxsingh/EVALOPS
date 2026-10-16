#!/bin/sh
# Judge validation with the real model: starts llm-inference-engine serving
# SmolLM2-135M-Instruct, EvalOps on a scratch database, then judge/validate.py.
# usage: judge/run_validation.sh OUT_DIR [MODEL.gguf]
set -eu
cd "$(dirname "$0")/.."
OUT=${1:-docs/evidence/judge}
MODEL=${2:-/home/user/models/smollm2-135m-instruct-f16.gguf}
ENGINE=../llm-inference-engine/target/release/llm
mkdir -p "$OUT"
DATA=$(mktemp -d)
$ENGINE serve --model "$MODEL" --ctx 2048 --listen 127.0.0.1:18081 > "$OUT/engine.log" 2>&1 &
EP=$!
java -jar target/evalops-0.1.0.jar --server.port=18095 --spring.datasource.url="jdbc:h2:file:$DATA/db" > "$OUT/evalops.log" 2>&1 &
JP=$!
trap 'kill $EP $JP 2>/dev/null; rm -rf "$DATA"' EXIT
for i in $(seq 1 120); do
  curl -sf 127.0.0.1:18095/datasets >/dev/null 2>&1 && curl -sf -X POST 127.0.0.1:18081/v1/chat/completions -d '{"messages":[{"role":"user","content":"hi"}],"max_tokens":1}' >/dev/null 2>&1 && break
  sleep 1
done
uptime > "$OUT/uptime_before.txt"
python3 judge/validate.py --evalops http://127.0.0.1:18095 --judge-url http://127.0.0.1:18081/v1/chat/completions --out "$OUT" \
  judge/labelled.jsonl judge/arithmetic.jsonl | tee "$OUT/validate.txt"
uptime > "$OUT/uptime_after.txt"

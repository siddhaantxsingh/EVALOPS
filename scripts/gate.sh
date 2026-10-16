#!/bin/sh
# CI gate: exits 0 if the candidate run passes the rules, 1 if not, 2 on errors.
# usage: scripts/gate.sh EVALOPS_URL BASELINE_RUN CANDIDATE_RUN RULES_JSON
#   RULES_JSON e.g. '[{"metric":"exact_match","maxDrop":0.01,"alpha":0.05}]'
set -u
URL=$1 BASE=$2 CAND=$3 RULES=$4
OUT=$(curl -sS -f -X POST "$URL/gates/check" -H 'Content-Type: application/json' \
  -d "{\"baseline\": $BASE, \"candidate\": $CAND, \"rules\": $RULES}") || exit 2
printf '%s' "$OUT" | python3 -c '
import json, sys
try:
    r = json.load(sys.stdin)
    for c in r["checks"]:
        print("%s %s: %s (baseline %.4f, candidate %.4f, p = %.4f)" % ("PASS" if c["pass"] else "FAIL", c["metric"], c["reason"],
              c["baseline"], c["candidate"], c["p_value"]))
except Exception as e:
    print("gate: cannot read the response:", e, file=sys.stderr)
    sys.exit(2)
sys.exit(0 if r["pass"] else 1)'

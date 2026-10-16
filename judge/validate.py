#!/usr/bin/env python3
"""Judge validation: how well does the LLM judge agree with labels?

Needs a running EvalOps (--evalops) and a chat endpoint for the judge
(--judge-url, e.g. llm-inference-engine serving SmolLM2-135M-Instruct).
The candidate answers are fixed: a small replay server, started here, plays
the "model under evaluation" and returns each item's stored candidate. So
the full EvalOps path is exercised (dataset, run, metrics, judge, cache).

For each labelled set it reports, against the labels: accuracy, Cohen's
kappa and the confusion matrix of llm_judge, exact_match and contains; the
judge's YES rate and parse rate; and whether a second, uncached run gives
identical verdicts.

usage: validate.py --evalops URL --judge-url URL --out DIR [files...]
"""
import argparse
import json
import os
import threading
import time
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

REPLAY = {}  # (set, prompt) -> candidate


class Replay(BaseHTTPRequestHandler):
    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        text = REPLAY[(body["replay_set"], body["prompt"])]
        out = json.dumps({"choices": [{"text": text, "index": 0}]}).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(out)))
        self.end_headers()
        self.wfile.write(out)

    def log_message(self, *a):
        pass


def call(method, url, body=None):
    req = urllib.request.Request(url, data=None if body is None else json.dumps(body).encode(), method=method,
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=600) as r:
        return json.loads(r.read())


def wait_run(base, rid):
    while True:
        s = call("GET", "%s/runs/%d" % (base, rid))
        if s["status"] in ("done", "failed"):
            return s
        time.sleep(0.5)


def agreement(pred, gold):
    n = len(gold)
    tp = sum(1 for p, g in zip(pred, gold) if p and g)
    tn = sum(1 for p, g in zip(pred, gold) if not p and not g)
    fp = sum(1 for p, g in zip(pred, gold) if p and not g)
    fn = n - tp - tn - fp
    acc = (tp + tn) / n
    pe = ((tp + fp) / n) * ((tp + fn) / n) + ((tn + fn) / n) * ((tn + fp) / n)
    kappa = (acc - pe) / (1 - pe) if pe < 1 else 0.0
    return {"accuracy": round(acc, 4), "kappa": round(kappa, 4), "tp": tp, "tn": tn, "fp": fp, "fn": fn, "positive_rate": round((tp + fp) / n, 4)}


def partition(rows):
    """Split rows into groups with unique questions (EvalOps items are prompts; the replay needs one answer per prompt)."""
    groups = []
    for r in rows:
        for g in groups:
            if all(x["question"] != r["question"] for x in g):
                g.append(r)
                break
        else:
            groups.append([r])
    return groups


def evaluate(base, judge_target, name, rows, cache):
    ordered, outs = [], []
    for gi, group in enumerate(partition(rows)):
        ds = "%s-%d" % (name, gi)
        call("POST", base + "/datasets", {"name": ds, "items": [{"input": r["question"], "reference": r["reference"]} for r in group]})
        for r in group:
            REPLAY[(ds, r["question"])] = r["candidate"]
        call("PUT", base + "/targets/replay-" + ds, {"url": REPLAY_URL, "model": "replay", "params": {"replay_set": ds}})
        t0 = time.time()
        rid = call("POST", base + "/runs", {"dataset": ds, "target": "replay-" + ds, "judge": judge_target, "metrics": ["exact_match", "contains", "llm_judge"],
                                            "concurrency": 4, "cache": cache})["run"]
        s = wait_run(base, rid)
        assert s["status"] == "done", s
        res = sorted(call("GET", "%s/runs/%d/results" % (base, rid)), key=lambda x: x["idx"])
        outs.append({"run": rid, "items": len(group), "seconds": round(time.time() - t0, 2)})
        for r, x in zip(group, res):
            ordered.append((r, x["scores"]))
    return ordered, outs


def main():
    global REPLAY_URL
    ap = argparse.ArgumentParser()
    ap.add_argument("--evalops", required=True)
    ap.add_argument("--judge-url", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("files", nargs="+")
    a = ap.parse_args()
    srv = ThreadingHTTPServer(("127.0.0.1", 0), Replay)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    REPLAY_URL = "http://127.0.0.1:%d/v1/completions" % srv.server_address[1]
    call("PUT", a.evalops + "/targets/judge", {"url": a.judge_url, "model": "smollm2-135m-instruct"})
    report = {"judge_url": a.judge_url, "sets": {}}
    for path in a.files:
        name = os.path.splitext(os.path.basename(path))[0]
        rows = [json.loads(l) for l in open(path) if l.strip()]
        first, runs1 = evaluate(a.evalops, "judge", name, rows, cache=False)
        second, runs2 = evaluate(a.evalops, "judge", name + "-repeat", rows, cache=False)
        gold = [r["label"] for r, _ in first]
        rep = {"items": len(rows), "positives": sum(gold), "runs": runs1, "repeat_runs": runs2}
        for m in ("llm_judge", "exact_match", "contains"):
            rep[m] = agreement([s[m] >= 0.5 for _, s in first], gold)
        rep["judge_vs_exact_match"] = agreement([s["llm_judge"] >= 0.5 for _, s in first], [s["exact_match"] >= 0.5 for _, s in first])
        rep["judge_parse_rate"] = round(sum(s.get("llm_judge_parsed", 0) for _, s in first) / len(first), 4)
        rep["repeat_identical_verdicts"] = sum(1 for (_, s1), (_, s2) in zip(first, second) if s1["llm_judge"] == s2["llm_judge"])
        rep["examples"] = [{"q": r["question"], "ref": r["reference"], "cand": r["candidate"], "label": r["label"], "judge": s["llm_judge"],
                            "exact": s["exact_match"]} for r, s in first]
        report["sets"][name] = rep
        print(name, json.dumps({k: v for k, v in rep.items() if k != "examples"}, indent=1))
    os.makedirs(a.out, exist_ok=True)
    with open(os.path.join(a.out, "judge_validation.json"), "w") as f:
        json.dump(report, f, indent=1)


if __name__ == "__main__":
    main()

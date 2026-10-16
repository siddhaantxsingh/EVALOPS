#!/usr/bin/env python3
"""EvalOps worker: leases evaluation tasks over HTTP and runs them.

Standard library only. Each worker thread loops:

  1. POST /worker/lease            -> a task (run, idx, token, input, target) or 204
  2. call the target (OpenAI-style /v1/completions), retrying transient errors,
     while a heartbeat thread renews the lease every lease_ms / 3
  3. POST /worker/complete with the fencing token

If the lease was lost (the worker was paused past its lease, and another
worker took the task), the service answers 409 and the result is discarded.

usage: worker.py --evalops http://127.0.0.1:8095 [--threads 4] [--lease-ms 30000]
                 [--name w1] [--once]
"""
import argparse
import json
import os
import socket
import sys
import threading
import time
import urllib.error
import urllib.request


def post(url, body, timeout=60):
    """POST JSON; returns (status, parsed body or None)."""
    req = urllib.request.Request(url, data=json.dumps(body).encode(), headers={"Content-Type": "application/json"}, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            data = r.read()
            return r.status, (json.loads(data) if data else None)
    except urllib.error.HTTPError as e:
        data = e.read()
        try:
            return e.code, json.loads(data) if data else None
        except ValueError:
            return e.code, None


class Transient(Exception):
    pass


def complete(target, prompt, timeout):
    body = dict(target.get("params") or {})
    if target.get("model"):
        body["model"] = target["model"]
    body["prompt"] = prompt
    body["stream"] = False
    try:
        status, resp = post(target["url"], body, timeout)
    except (urllib.error.URLError, OSError) as e:
        raise Transient("connection error: %s" % e)
    if status == 429 or status >= 500:
        raise Transient("HTTP %d" % status)
    if status != 200:
        raise ValueError("HTTP %d: %s" % (status, resp))
    text = (resp.get("choices") or [{}])[0].get("text")
    if not isinstance(text, str):
        raise ValueError("response has no choices[0].text")
    return text


def log(*a):
    print(time.strftime("%H:%M:%S"), *a, file=sys.stderr, flush=True)


class Worker:
    def __init__(self, base, name, lease_ms, max_attempts=3, timeout=60):
        self.base = base.rstrip("/")
        self.name = name
        self.lease_ms = lease_ms
        self.max_attempts = max_attempts
        self.timeout = timeout
        self.stats = {"completed": 0, "stale": 0, "errors": 0}

    def heartbeat(self, task, stop):
        while not stop.wait(self.lease_ms / 3000.0):
            try:
                status, _ = post(self.base + "/worker/heartbeat",
                                 {"run": task["run"], "idx": task["idx"], "token": task["token"], "lease_ms": self.lease_ms}, 10)
            except OSError:
                continue  # the service may be briefly unreachable; the lease may expire
            if status == 409:
                log(self.name, "lost lease on run", task["run"], "item", task["idx"])
                return

    def run_task(self, task):
        stop = threading.Event()
        hb = threading.Thread(target=self.heartbeat, args=(task, stop), daemon=True)
        hb.start()
        output, error, attempts = None, None, 0
        t0 = time.monotonic()
        try:
            while output is None and attempts < self.max_attempts:
                attempts += 1
                try:
                    output = complete(task["target"], task["input"], self.timeout)
                except Transient as e:
                    error = str(e)
                    time.sleep(0.2 * 2 ** (attempts - 1))
                except Exception as e:  # permanent: report it, scored 0
                    error = str(e)
                    break
        finally:
            stop.set()
        body = {"run": task["run"], "idx": task["idx"], "token": task["token"], "attempts": attempts,
                "latency_ms": (time.monotonic() - t0) * 1000.0}
        if output is not None:
            body["output"] = output
        else:
            body["error"] = error
        status, resp = post(self.base + "/worker/complete", body)
        if status == 200:
            self.stats["completed"] += 1
        elif status == 409:
            self.stats["stale"] += 1
            log(self.name, "result discarded (stale lease) for run", task["run"], "item", task["idx"], "token", task["token"])
        else:
            self.stats["errors"] += 1
            log(self.name, "complete failed: HTTP", status, resp)

    def loop(self, once=False, idle_s=0.2):
        while True:
            try:
                status, task = post(self.base + "/worker/lease", {"worker": self.name, "lease_ms": self.lease_ms}, 10)
            except OSError as e:
                log(self.name, "evalops unreachable:", e)
                time.sleep(1)
                continue
            if status == 204:
                if once:
                    return
                time.sleep(idle_s)
                continue
            if status != 200:
                log(self.name, "lease failed: HTTP", status, task)
                time.sleep(1)
                continue
            log(self.name, "leased run", task["run"], "item", task["idx"], "token", task["token"])
            self.run_task(task)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--evalops", default="http://127.0.0.1:8095")
    ap.add_argument("--threads", type=int, default=4)
    ap.add_argument("--lease-ms", type=int, default=30000)
    ap.add_argument("--name", default="%s-%d" % (socket.gethostname(), os.getpid()))
    ap.add_argument("--once", action="store_true", help="exit when no task is available")
    a = ap.parse_args()
    threads = []
    for i in range(a.threads):
        w = Worker(a.evalops, "%s/%d" % (a.name, i), a.lease_ms)
        t = threading.Thread(target=w.loop, kwargs={"once": a.once}, daemon=True)
        t.start()
        threads.append(t)
    for t in threads:
        t.join()


if __name__ == "__main__":
    main()

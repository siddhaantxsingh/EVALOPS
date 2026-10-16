"""Build a next-line code completion dataset from the held-out Python
standard-library files (the files llm-inference-engine's model never saw).

Each item: input = up to ~600 bytes of code ending at a line boundary,
reference = the next non-blank line. Writes the POST /datasets body.
usage: python3 demo/make_dataset.py OUT.json [N]
"""
import hashlib
import glob
import json
import os
import random
import sys

ROOT = "/usr/lib/python3.12"


def held_out():
    # same split rule as llm-inference-engine/train/data.py
    out = []
    for p in sorted(glob.glob(f"{ROOT}/**/*.py", recursive=True)):
        rel = os.path.relpath(p, ROOT)
        if any(s in rel.split("/") for s in ("test", "tests", "idlelib", "site-packages", "dist-packages")):
            continue
        if int(hashlib.sha256(rel.encode()).hexdigest(), 16) % 50 == 0:
            out.append(p)
    return out


def main():
    out, n = sys.argv[1], int(sys.argv[2]) if len(sys.argv) > 2 else 300
    rng = random.Random(0)
    items = []
    lines = [(f, l) for f in held_out() for l in [open(f, encoding="utf-8").read().split("\n")] if len(l) > 20]
    while len(items) < n:
        f, ls = rng.choice(lines)
        i = rng.randrange(5, len(ls) - 1)
        if not ls[i].strip() or len(ls[i]) > 100:
            continue
        prefix = "\n".join(ls[:i]) + "\n"
        if len(prefix) > 600:
            cut = prefix.find("\n", len(prefix) - 600)
            prefix = prefix[cut + 1:]
        items.append({"input": prefix, "reference": ls[i], "meta": {"file": os.path.relpath(f, ROOT), "line": i + 1}})
    json.dump({"name": "stdlib-next-line", "items": items}, open(out, "w"))
    print(len(items), "items from", len(lines), "held-out files")


main()

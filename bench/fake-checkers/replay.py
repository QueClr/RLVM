#!/usr/bin/env python3
"""Fake checker that replays recorded verdicts.

    replay.py MAP.json        (export path in $IN)

MAP.json maps arena test names ("perf/app-lam") to an exit status, or to the
string "timeout" (then it sleeps until it is killed). Used by bench/selftest.py
to feed the published results of real checkers through run-suite.py and check
that its scoring agrees with the arena's.
"""
import json
import os
import re
import sys
import time

with open(sys.argv[1]) as handle:
    verdicts = json.load(handle)
path = os.environ["IN"]
match = re.search(r"/(?:good|bad|either)/(.+)\.ndjson$", path)
if not match or match.group(1) not in verdicts:
    print(f"replay: no recorded verdict for {path}", file=sys.stderr)
    sys.exit(99)
verdict = verdicts[match.group(1)]
if verdict == "timeout":
    time.sleep(3600)
sys.exit(int(verdict))

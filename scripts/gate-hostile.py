#!/usr/bin/env python3
"""scripts/gate-hostile.py - the hostile exports through the launcher, judged by the OFFICIAL verdicts
under the contract of the default configuration (OWNER-NOTES 13).

    scripts/gate-hostile.py [--manifest FILE] [--dir DIR] [-j LANES] [--kind-mark REGEX] [--report FILE]
                            [--wrong-reject-mark REGEX] [--max-known-wrong-rejects N]

A step of scripts/gate.sh (`--hostile [MANIFEST]`); the checker is scripts/check-light.sh, configured
by the same environment as the mutant step (RLVM_DIST, RLVM_THREADS, RLVM_ENGINE, RLVM_LOAD,
RLVM_EXTRA_CP, RLVM_CHECK_ARGS), so the rows are run in the gate's configuration - with `--defaults`
the distribution exactly as a user runs it, whatever its default engine is.

Manifest: a TSV, `#` lines are comments,

    name <TAB> official verdict (accept | reject) <TAB> marks <TAB> free text

(the format of rlvm-machine/src/test/resources/s/manifest.tsv). The export of a row is NAME.ndjson or
NAME.ndjson.gz in --dir (default: the manifest's directory). The oracle is column 2, the verdict of
the official checker stored with the row - never the opinion of one of our engines.

Expectation per row (note 13):
  - a row of KIND T (a derivable statement that the official checker rejects only through its own
    incompleteness, non-transitivity, cache order or object identity): the official verdict OR accept;
  - every other row: the official verdict.
A row is of kind T when one of its marks (column 3, a comma list) matches --kind-mark (default: the
marks `T`, `kind-T`, `kindT`, `kind=T`). The owner of the suite decides the marks; a row without the
mark is strict. Declines are counted and listed, and are not wrong verdicts (contract (c) is the
gate's 230 tests and the mutant corpora; a hostile export may be something RLVM cannot represent).

KNOWN WRONG REJECTS (*agentic-note*: rep-MO-1). Since the re-check of a rejection by the reference engine
is off by default (the maintainer's instruction of 2026-11-25), the default configuration rejects some
hostile exports that the official checker accepts: the machine is not aligned with the official
behaviour there yet. Such a row carries the mark `wrong-reject` (--wrong-reject-mark) in the manifest,
with its cause in the text. A rejection of an official accept on a row WITH the mark is counted and
listed as KNOWN WRONG REJECT - every one, by name, on every run: the mark records a defect, it does not
hide it; on a row WITHOUT the mark it is a WRONG REJECT and fails the gate as before. The number of
known wrong rejects may only go down: --max-known-wrong-rejects N (scripts/gate.sh passes its constant)
fails the gate when more than N rows are rejected under the mark, so a new wrong reject cannot be
admitted by a mark in the manifest alone. A marked row that is not rejected (the machine was aligned,
or the run verifies: -X verify=reference) is listed as "no longer rejected": its mark can go, and the
constant goes down with it.

Summary line (the last line printed):
  hostile: N rows: agree A, kind-T accepts tolerated K, KNOWN WRONG REJECT w (m marked, at most c),
           WRONG ACCEPT x, WRONG REJECT y, declined d, errors e
Exit status: 0 if x = y = e = 0 and w <= c, 1 otherwise, 2 usage.
"""
import argparse
import concurrent.futures
import gzip
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
VERDICT = {0: "accept", 1: "reject", 2: "decline"}


def rows_of(manifest):
    out = []
    with open(manifest) as handle:
        for n, line in enumerate(handle, 1):
            line = line.rstrip("\n")
            if not line.strip() or line.startswith("#"):
                continue
            fields = line.split("\t")
            if len(fields) < 2 or fields[1] not in ("accept", "reject"):
                sys.exit(f"gate-hostile: {manifest}:{n}: expected 'name<TAB>accept|reject<TAB>marks...', got: {line[:80]}")
            marks = [m.strip() for m in (fields[2] if len(fields) > 2 else "-").split(",") if m.strip() and m.strip() != "-"]
            out.append({"name": fields[0], "official": fields[1], "marks": marks})
    return out


def judge(official, kind_t, got, known_wrong_reject=False):
    """-> agree | tolerated | known-wrong-reject | wrong-accept | wrong-reject | declined | error"""
    if got not in ("accept", "reject", "decline"):
        return "error"
    if got == "decline":
        return "declined"
    if got == official:
        return "agree"
    if got == "accept":
        return "tolerated" if kind_t else "wrong-accept"
    return "known-wrong-reject" if known_wrong_reject else "wrong-reject"


def main():
    ap = argparse.ArgumentParser(add_help=False)
    ap.add_argument("--manifest", default=os.path.join(ROOT, "rlvm-machine", "src", "test", "resources", "s", "manifest.tsv"))
    ap.add_argument("--dir")
    ap.add_argument("-j", "--jobs", type=int, default=4)
    ap.add_argument("--kind-mark", default=r"^(T|kind-T|kindT|kind=T)$")
    ap.add_argument("--wrong-reject-mark", default=r"^(wrong-reject)$")
    ap.add_argument("--max-known-wrong-rejects", type=int, default=None)
    ap.add_argument("--report")
    ap.add_argument("--selftest", action="store_true")
    ap.add_argument("-h", "--help", action="store_true")
    args = ap.parse_args()
    if args.help:
        print(__doc__)
        return 0
    if args.selftest:
        table = [("accept", False, "accept", "agree"), ("reject", False, "reject", "agree"), ("reject", False, "accept", "wrong-accept"),
                 ("reject", True, "accept", "tolerated"), ("reject", True, "reject", "agree"), ("accept", True, "reject", "wrong-reject"),
                 ("accept", False, "reject", "wrong-reject"), ("accept", False, "decline", "declined"), ("reject", True, "exit 3", "error")]
        bad = [t for t in table if judge(t[0], t[1], t[2]) != t[3]]
        # the mark of a known wrong reject: only a rejection of an official accept is counted under it
        marked = [("accept", "reject", "known-wrong-reject"), ("accept", "accept", "agree"), ("accept", "decline", "declined"),
                  ("reject", "reject", "agree"), ("reject", "accept", "wrong-accept"), ("accept", "exit 3", "error")]
        bad += [t for t in marked if judge(t[0], False, t[1], True) != t[2]]
        print("gate-hostile: self-test " + ("passed (15 cases)" if not bad else f"FAILED: {bad}"))
        return 1 if bad else 0
    if not os.path.isfile(args.manifest):
        sys.exit(f"gate-hostile: no manifest {args.manifest}")
    directory = args.dir or os.path.dirname(os.path.abspath(args.manifest))
    rows = rows_of(args.manifest)
    kind = re.compile(args.kind_mark)
    known = re.compile(args.wrong_reject_mark)
    tmp = tempfile.mkdtemp(prefix="gate-hostile-")
    checker = os.path.join(ROOT, "scripts", "check-light.sh")

    def run(row):
        plain = os.path.join(directory, row["name"] + ".ndjson")
        packed = plain + ".gz"
        if os.path.isfile(plain):
            path = plain
        elif os.path.isfile(packed):
            path = os.path.join(tmp, row["name"] + ".ndjson")
            with gzip.open(packed, "rb") as src, open(path, "wb") as dst:
                shutil.copyfileobj(src, dst)
        else:
            row.update(got="no export", result="error")
            return row
        try:
            p = subprocess.run([checker, path], stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, timeout=1800)
            row["got"] = VERDICT.get(p.returncode, f"exit {p.returncode}")
            if p.returncode not in VERDICT:
                row["stderr"] = p.stderr.decode(errors="replace")[-400:]
        except subprocess.TimeoutExpired:
            row["got"] = "timeout"
        row["kind_t"] = any(kind.match(m) for m in row["marks"])
        row["known_wrong_reject"] = any(known.match(m) for m in row["marks"])
        row["result"] = judge(row["official"], row["kind_t"], row["got"], row["known_wrong_reject"])
        if path.startswith(tmp):
            os.remove(path)
        return row

    try:
        with concurrent.futures.ThreadPoolExecutor(max_workers=max(1, args.jobs)) as pool:
            done = list(pool.map(run, rows))
    finally:
        shutil.rmtree(tmp, ignore_errors=True)
    count = {k: 0 for k in ("agree", "tolerated", "known-wrong-reject", "wrong-accept", "wrong-reject", "declined", "error")}
    marked = 0
    for row in done:
        count[row["result"]] += 1
        marked += 1 if row.get("known_wrong_reject") else 0
        if row["result"] not in ("agree",):
            print(f"  {row['result'].upper().replace('-', ' '):<18} {row['name']}: official {row['official']}, got {row.get('got')}"
                  + (f", marks {','.join(row['marks'])}" if row["marks"] else "")
                  + (f" | {row['stderr'].strip().splitlines()[-1]}" if row.get("stderr", "").strip() else ""))
        elif row.get("known_wrong_reject"):
            # A row under the mark that got the official verdict: the mark can go (and the ceiling with it).
            print(f"  NO LONGER REJECTED {row['name']}: official {row['official']}, got {row.get('got')}, marks {','.join(row['marks'])}")
    ceiling = args.max_known_wrong_rejects
    over = ceiling is not None and count["known-wrong-reject"] > ceiling
    if over:
        print(f"  TOO MANY KNOWN WRONG REJECTS: {count['known-wrong-reject']} rows are rejected under the mark, at most {ceiling} "
              f"are allowed (the number may only go down)")
    if args.report:
        with open(args.report, "w") as handle:
            json.dump({"manifest": os.path.relpath(args.manifest, ROOT), "dist": os.environ.get("RLVM_DIST"),
                       "engine": os.environ.get("RLVM_ENGINE"), "threads": os.environ.get("RLVM_THREADS"),
                       "load": os.environ.get("RLVM_LOAD"), "check_args": os.environ.get("RLVM_CHECK_ARGS"),
                       "known_wrong_rejects_marked": marked, "known_wrong_rejects_max": ceiling,
                       "counts": count, "rows": done}, handle, indent=1)
    print(f"hostile: {len(done)} rows: agree {count['agree']}, kind-T accepts tolerated {count['tolerated']}, "
          f"KNOWN WRONG REJECT {count['known-wrong-reject']} ({marked} marked, at most {'any' if ceiling is None else ceiling}), "
          f"WRONG ACCEPT {count['wrong-accept']}, WRONG REJECT {count['wrong-reject']}, declined {count['declined']}, "
          f"errors {count['error']}")
    return 0 if count["wrong-accept"] == 0 and count["wrong-reject"] == 0 and count["error"] == 0 and not over else 1


if __name__ == "__main__":
    sys.exit(main())

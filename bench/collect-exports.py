#!/usr/bin/env python3
"""Collect the exports built by the arena's `lka.py build-test` under work/exports/.

    bench/collect-exports.py [--build-dir DIR] [--out DIR] [--published results.json]

lka.py writes every test it builds to <arena>/_build/tests/NAME.ndjson with
NAME.stats.json next to it (the sub-tests of a `multiple` test, i.e. the
tutorial, to <arena>/_build/tests/tutorial/{good,bad}/SUB.ndjson). This script
lays them out the way the arena's test tarball does, for bench/run-suite.py:

    work/exports/NAME.ndjson                 the top-level corpora (init, std, mathlib, ...)
    work/exports/all/good/NAME.ndjson        outcome accept
    work/exports/all/bad/NAME.ndjson         outcome reject
    work/exports/all/either/NAME.ndjson      outcome either (not in the arena's tarball)
    work/exports/all/manifest.json           one entry per test, see below

Everything is a relative symbolic link to the file lka.py built; nothing is
copied. The manifest has the shape of the arena's results.json as far as
run-suite.py reads it ({"tests": [{"name", "outcome", "timeout", ...}]}), so

    bench/run-suite.py --tests work/exports/all ...

picks it up by itself (expected outcomes, `either`, --arena-timeouts). Per
test: name, outcome (accept | reject | either), bucket, path (below all/),
source (below the project root), size, lines, sha256, timeout (seconds, the
arena's; null = the arena sets none), lean_version, lean4export_version,
yaml_file, compare_perf, skip_on_ci, in_arena_tarball (the arena publishes it
in lean-arena-tests.tar.gz: at most 10 MiB and not `either`), and
sha256_matches_arena (true | false | null if the published results do not
list the test), description.

Exit status: 0 if every test of the published results was found (or no
published results were given), 1 if some are missing, or if a hash differs
and --strict was given.
"""
import argparse
import hashlib
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
TEST_SIZE_LIMIT = 10 * 1024 * 1024          # lka.py: larger tests are not in the tarball
BUCKET = {"accept": "good", "reject": "bad", "either": "either"}


def sha256_of(path: str) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        while chunk := handle.read(1 << 20):
            digest.update(chunk)
    return digest.hexdigest()


def link(target: str, link_path: str) -> None:
    os.makedirs(os.path.dirname(link_path), exist_ok=True)
    relative = os.path.relpath(target, os.path.dirname(link_path))
    if os.path.islink(link_path):
        if os.readlink(link_path) == relative:
            return
        os.unlink(link_path)
    elif os.path.exists(link_path):
        raise SystemExit(f"collect-exports: {link_path} exists and is not a symbolic link; "
                         "remove it first")
    os.symlink(relative, link_path)


def remove_stale_links(directory: str, keep: set[str]) -> int:
    removed = 0
    for dirpath, dirnames, filenames in os.walk(directory, topdown=False):
        for filename in filenames:
            path = os.path.join(dirpath, filename)
            if os.path.islink(path) and path not in keep:
                os.unlink(path)
                removed += 1
        for dirname in dirnames:
            path = os.path.join(dirpath, dirname)
            if not os.path.islink(path) and not os.listdir(path):
                os.rmdir(path)
    return removed


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--build-dir", default=os.path.join(ROOT, "work", "arena-run", "_build", "tests"),
                        help="lka.py's _build/tests (default: work/arena-run/_build/tests)")
    parser.add_argument("--out", default=os.path.join(ROOT, "work", "exports"),
                        help="output directory (default: work/exports)")
    parser.add_argument("--published", default=os.path.join(ROOT, "work", "arena-site", "results.json"),
                        help="the arena's published results.json, to compare sizes and hashes "
                             "with (default: work/arena-site/results.json; '' = none)")
    parser.add_argument("--verify-hashes", action="store_true",
                        help="recompute the sha256 of every export instead of trusting the one "
                             "lka.py recorded in NAME.stats.json when it built the test")
    parser.add_argument("--strict", action="store_true",
                        help="exit 1 if an export's sha256 differs from the published one")
    args = parser.parse_args()

    build_dir = os.path.abspath(args.build_dir)
    out_dir = os.path.abspath(args.out)
    all_dir = os.path.join(out_dir, "all")
    if not os.path.isdir(build_dir):
        print(f"collect-exports: {build_dir} not found; build the tests first", file=sys.stderr)
        return 2

    published: dict[str, dict] = {}
    published_meta: dict = {}
    if args.published and os.path.isfile(args.published):
        with open(args.published) as handle:
            data = json.load(handle)
        published = {t["name"]: t for t in data.get("tests", [])}
        published_meta = data.get("meta", {})

    arena_revision = None
    head = os.path.join(os.path.dirname(os.path.dirname(build_dir)), ".git", "HEAD")
    if os.path.isfile(head):
        with open(head) as handle:
            arena_revision = handle.read().strip()
        if arena_revision.startswith("ref: "):
            ref = os.path.join(os.path.dirname(head), arena_revision[5:])
            arena_revision = open(ref).read().strip() if os.path.isfile(ref) else arena_revision

    tests = []
    problems = []
    for dirpath, dirnames, filenames in os.walk(build_dir):
        if os.path.basename(dirpath) == "work" and os.path.dirname(dirpath) == build_dir:
            dirnames[:] = []            # lka.py's scratch directories (sources, .lake)
            continue
        dirnames.sort()
        for filename in sorted(filenames):
            if not filename.endswith(".stats.json"):
                continue
            stats_path = os.path.join(dirpath, filename)
            with open(stats_path) as handle:
                stats = json.load(handle)
            source = stats_path[:-len(".stats.json")] + ".ndjson"
            name = stats["name"]
            if not os.path.isfile(source):
                problems.append(f"{name}: {source} is missing")
                continue
            outcome = stats.get("outcome")
            if outcome not in BUCKET:
                problems.append(f"{name}: unknown outcome {outcome!r}")
                continue
            size = os.path.getsize(source)
            if size != stats.get("size"):
                problems.append(f"{name}: size {size} differs from the {stats.get('size')} in {stats_path}")
            sha256 = sha256_of(source) if args.verify_hashes else stats.get("sha256")
            if args.verify_hashes and sha256 != stats.get("sha256"):
                problems.append(f"{name}: sha256 differs from the one in {stats_path}")
            bucket = BUCKET[outcome]
            arena = published.get(name)
            tests.append({
                "name": name,
                "outcome": outcome,
                "bucket": bucket,
                "path": f"{bucket}/{name}.ndjson",
                "source": os.path.relpath(source, ROOT),
                "size": size,
                "lines": stats.get("lines"),
                "sha256": sha256,
                "timeout": stats.get("timeout"),
                "lean_version": stats.get("lean_version"),
                "lean4export_version": stats.get("lean4export_version"),
                "yaml_file": stats.get("yaml_file"),
                "compare_perf": bool(stats.get("compare-perf")),
                "skip_on_ci": bool(stats.get("skip-on-ci")),
                "in_arena_tarball": size <= TEST_SIZE_LIMIT and outcome != "either",
                "sha256_matches_arena": (sha256 == arena.get("sha256")) if arena else None,
                "arena_size": arena.get("size") if arena else None,
                "description": stats.get("description"),
            })
    tests.sort(key=lambda t: t["name"])

    keep = set()
    for test in tests:
        source = os.path.join(ROOT, test["source"])
        path = os.path.join(all_dir, test["path"])
        link(source, path)
        keep.add(path)
        if "/" not in test["name"]:
            top = os.path.join(out_dir, test["name"] + ".ndjson")
            link(source, top)
    stale = remove_stale_links(all_dir, keep) if os.path.isdir(all_dir) else 0

    by_bucket = {b: sum(1 for t in tests if t["bucket"] == b) for b in ("good", "bad", "either")}
    missing = sorted(set(published) - {t["name"] for t in tests})
    extra = sorted({t["name"] for t in tests} - set(published)) if published else []
    differing = [t["name"] for t in tests if t["sha256_matches_arena"] is False]
    manifest = {
        "meta": {
            "generated_by": "bench/collect-exports.py",
            "build_dir": os.path.relpath(build_dir, ROOT),
            "arena_revision": arena_revision,
            "published_revision": published_meta.get("git_revision"),
            "published_timestamp": published_meta.get("timestamp"),
            "tests": len(tests),
            "good": by_bucket["good"], "bad": by_bucket["bad"], "either": by_bucket["either"],
            "total_bytes": sum(t["size"] for t in tests),
            "identical_to_arena": sum(1 for t in tests if t["sha256_matches_arena"]),
            "differing_from_arena": differing,
            "missing_from_build": missing,
            "not_in_published_results": extra,
        },
        "tests": tests,
    }
    os.makedirs(all_dir, exist_ok=True)
    manifest_path = os.path.join(all_dir, "manifest.json")
    with open(manifest_path + ".tmp", "w") as handle:
        json.dump(manifest, handle, indent=1)
    os.replace(manifest_path + ".tmp", manifest_path)

    print(f"collect-exports: {len(tests)} tests in {all_dir}: good {by_bucket['good']}, "
          f"bad {by_bucket['bad']}, either {by_bucket['either']}; "
          f"{sum(t['size'] for t in tests):,} bytes; {stale} stale link(s) removed")
    if published:
        print(f"collect-exports: byte-identical to the arena's exports (sha256): "
              f"{manifest['meta']['identical_to_arena']} of {len(published)} published tests")
        for name in differing:
            test = next(t for t in tests if t["name"] == name)
            print(f"  DIFFERENT: {name}: {test['size']:,} bytes here, {test['arena_size']:,} on the arena")
        for name in missing:
            print(f"  MISSING:   {name} (published by the arena, not built here)")
        for name in extra:
            print(f"  EXTRA:     {name} (built here, not in the published results)")
    for problem in problems:
        print(f"  PROBLEM:   {problem}")
    print(f"collect-exports: wrote {manifest_path}")
    if missing or problems or (args.strict and differing):
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())

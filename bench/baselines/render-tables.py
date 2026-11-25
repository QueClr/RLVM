#!/usr/bin/env python3
"""bench/baselines/render-tables.py - refresh the generated tables of a document.

    bench/baselines/render-tables.py bench/README.md [FILE.md ...]

Replaces what stands between `<!-- BEGIN name -->` and `<!-- END name -->` by
the output of the command that makes that block:

    big:SECTION       bench/baselines/big-summary.py --no-json --markdown --section SECTION
                      (corpora, arch, threads)
    profile:SECTION   bench/baselines/profile-summary.py --markdown --section SECTION
                      (split, distribution, classes, kinds, names, top, workers, agreement)

so that the numbers in the documents are the ones in work/baselines/big/runs.jsonl
and work/baselines/mathlib-profile.json and not copies made by hand.
"""
import os
import re
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
TOOLS = {"big": ["big-summary.py", "--no-json", "--markdown"], "profile": ["profile-summary.py", "--markdown"]}
BLOCK = re.compile(r"(<!-- BEGIN (big|profile):([a-z]+) -->\n).*?(<!-- END \2:\3 -->)", re.S)


def main() -> int:
    if len(sys.argv) < 2:
        print(__doc__, file=sys.stderr)
        return 2
    cache = {}

    def render(match):
        tool, section = match.group(2), match.group(3)
        if (tool, section) not in cache:
            cmd = [sys.executable, "-I", os.path.join(HERE, TOOLS[tool][0]), *TOOLS[tool][1:], "--section", section]
            cache[(tool, section)] = subprocess.run(cmd, check=True, capture_output=True, text=True).stdout.strip("\n")
        return match.group(1) + cache[(tool, section)] + "\n" + match.group(4)

    for path in sys.argv[1:]:
        text = open(path).read()
        new, count = BLOCK.subn(render, text)
        with open(path, "w") as handle:
            handle.write(new)
        print(f"{path}: refreshed {count} block(s)", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())

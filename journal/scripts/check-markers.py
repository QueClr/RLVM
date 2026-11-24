"""Check that every `*agentic-note*: <address>` marker in the RLVM repository
names a tree or section that exists in this forest.

    python3 scripts/check-markers.py [path of the RLVM checkout]

Without an argument the checkout is the directory that contains this forest.
The forest itself is not searched: AGENTS.md describes the marker.
"""

import pathlib
import re
import subprocess
import sys

journal = pathlib.Path(__file__).resolve().parent.parent
addresses = set()
for tree in (journal / "trees").rglob("*.tree"):
    addresses.add(tree.stem)
    addresses.update(re.findall(r"\\subtree\[([^\]]+)\]", tree.read_text()))

repo = sys.argv[1] if len(sys.argv) > 1 else str(journal.parent)
found = subprocess.run(
    ["git", "-C", repo, "grep", "-n", "-o", "-E", r"\*agentic-note\*: [A-Za-z0-9-]+",
     "--", ".", ":!journal", ":!AGENTS.md"],
    capture_output=True,
    text=True,
)
if found.returncode not in (0, 1):
    sys.exit(f"git grep failed in {repo}: {found.stderr.strip()}")
missing = 0
lines = [line for line in found.stdout.splitlines() if line]
for line in lines:
    where, addr = line.rsplit(": ", 1)
    if addr not in addresses:
        missing += 1
        print(f"{where.rsplit(':', 1)[0]}: no tree or section named {addr}")
print(f"{len(lines)} markers checked, {missing} unresolved")
sys.exit(1 if missing else 0)

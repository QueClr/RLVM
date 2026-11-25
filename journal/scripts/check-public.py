"""Check the trees against the rules of AGENTS.md that a build cannot see.

    python3 scripts/check-public.py

The rendered forest is a public site. An ERROR is something that must not be
published or that breaks a writing rule (a host path, a credential, a link to
a working file, a reference to a conversation). A NOTE is a place worth a
second look. The exit status is 1 if there is an error.
"""

import pathlib
import re
import sys

journal = pathlib.Path(__file__).resolve().parent.parent

ERRORS = [
    (r"/home/|/Users/|(?<![\w.-])/root/|/tmp/claude|/nix/store/", "absolute path of a machine"),
    (r"\b[\w.+-]+@[\w-]+\.[\w.-]+\b", "e-mail address"),
    (r"ghp_[A-Za-z0-9]{20,}|github_pat_|sk-[A-Za-z0-9-]{20,}|AKIA[0-9A-Z]{16}|BEGIN [A-Z ]*PRIVATE KEY",
     "credential"),
    (r"\\src\{work/|\\src\{refs/|\\src\{\.toolchain/|\\src\{\.claude/", "link to a file that is not in the repository"),
    (r"\bthe user\b|\bthe user's\b", 'refers to "the user"'),
    (r"\b(in|from) (the|this|our) (chat|conversation|transcript)\b|\bscratchpad\b",
     "refers to a conversation or to private scratch"),
]

NOTES = [
    (r"(?<![\w/{])work/[\w.-]+", "names a working file under work/ (not in the repository)"),
    (r"(?<![\d:T])\d{1,2}:\d{2}Z\b", "a bare UTC time; say the date or drop it if it carries nothing"),
    (r"\\date\{\d{4}-\d{2}-\d{2}T", "the \\date line journal-new wrote was not replaced"),
    (r"Being written\.", "stub text left in"),
]

REQUIRED = {
    "Design": ["\\date{", "\\author{", "\\meta{status}{"],
    "Decision": ["\\date{", "\\author{", "\\meta{status}{"],
    "Problem": ["\\date{", "\\author{", "\\meta{status}{"],
    "Change": ["\\date{", "\\author{", "\\tag{changelog}"],
    "Insight": ["\\date{", "\\author{"],
    "Hypothesis": ["\\date{", "\\author{"],
    "Example": ["\\date{", "\\author{"],
    "Motivation": ["\\date{", "\\author{"],
    "Session": ["\\date{", "\\author{", "\\meta{harness}{", "\\meta{model-id}{"],
    "Study": ["\\date{", "\\author{", "\\meta{origin}{"],
    "Report": ["\\date{", "\\author{", "\\meta{origin}{"],
}

# Moved documents (studies, reports, and design documents that carry
# \meta{moved}) keep the working-file names and the times of day they were
# written with: the notes are not printed for them, the errors are.
MOVED = ("trees/study/", "trees/report/")

errors = notes = 0
for tree in sorted((journal / "trees").rglob("*.tree")):
    text = tree.read_text()
    name = tree.relative_to(journal)
    for number, line in enumerate(text.splitlines(), 1):
        for pattern, what in ERRORS:
            if re.search(pattern, line):
                errors += 1
                print(f"ERROR {name}:{number}: {what}")
        for pattern, what in NOTES:
            if str(name).startswith(MOVED) or "\\meta{moved}{" in text:
                continue
            if re.search(pattern, line):
                notes += 1
                print(f"note  {name}:{number}: {what}")
    taxon = re.search(r"\\taxon\{([^}]+)\}", text)
    if taxon:
        for needed in REQUIRED.get(taxon.group(1), []):
            if needed not in text:
                errors += 1
                print(f"ERROR {name}: a {taxon.group(1)} tree without {needed}...}}")

print(f"{errors} errors, {notes} notes")
sys.exit(1 if errors else 0)

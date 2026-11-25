# Agent instructions

RLVM (Rabdos Lean VM) is a checker for the Lean 4 kernel on GraalVM. `README.md` says what it is and how
to build, run and test it. This file says how to work in the repository.

## Where notes go: the journal, and only the journal

Rule of the maintainer (2026-11-24): **every note lives in the journal** - `journal/`, a
[Forester](https://www.forester-notes.org/) forest, published at <https://queclr.github.io/RLVM/>. That
covers design documents, decisions, research, measurements and profiles, reviews, working notes of a
module, change logs and the maintainer's standing policies.

- Outside `journal/` the only prose files are `README.md` files (the root one and one per module or tool
  directory: what the thing is and how to build, run and test it), `LICENSE`, this file and `CLAUDE.md`.
- Do not create `NOTES-*.md`, files under `docs/`, status reports or any other Markdown note elsewhere in
  the repository. Write a tree instead; `journal/AGENTS.md` has the kinds of trees, the frontmatter, the
  markup and the tools (`journal-new`, `journal-check`).
- A README stays short: no history, no measurement tables beyond a headline, no design discussion. It
  links to the journal for all of that.
- Tables that a tool generates (benchmarks) are emitted as trees, not as Markdown files.
- Code and READMEs cite a note by its address with the marker `*agentic-note*: <address>`; resolve one
  with `rg -F '<address>' journal/trees/`. An address that code cites is permanent.
- Scratch belongs under `work/` (untracked) and is never cited from a tracked file.

Transition: the documents written before this rule (`docs/`, `rlvm-machine/NOTES-*.md`,
`rlvm-machine/SWITCHES.md`, `rlvm-machine/OWNERSHIP.md`, `rlvm-machine/WORK-PACKAGES.md`,
`rlvm-truffle/NOTES-*.md`, `bench/track/*.md` other than its README, the result notes under `spikes/`)
are being moved into the journal. Until a document has been moved it may be corrected in place; nothing
new is added next to it.

## Standing policies

The maintainer's lasting instructions are the journal's `policy/standing-policies` tree (until the move:
`docs/design/OWNER-NOTES.md`). They are the highest authority. In short:

- The metric is warm wall clock: 8 parallel workers, steady state, loading pipelined with checking.
- The default configuration never accepts a statement that is not derivable, gives the official verdict
  on every real input, and accepts everything the official checker accepts; `-X policy=strict` is the
  reference engine. No switch enters a default set without an adversarial pass judged by the official
  checker.
- Oracle GraalVM is never downloaded or used by anything default; GraalVM Community Edition only.
- The code is Apache-2.0 OR MIT; the journal is GFDL 1.3 or later.
- The repository is public: no absolute host paths, no personal data, no credentials in any tracked file.

## Working rules

- Every timing that is reported was taken through `bench/quiet.py`; heavy jobs run through
  `bench/slot.sh`.
- A wrong accept or a wrong reject against the official checker is reported at once, before anything
  else.
- Commit each step with a message that says what the step was.

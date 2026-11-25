# Agent instructions

This directory is the working journal of RLVM (Rabdos Lean VM), a checker for
the Lean 4 kernel on GraalVM. It is a
[Forester](https://www.forester-notes.org/) forest: many small notes ("trees")
with permanent addresses that link to and include each other. Design notes,
decisions, measured findings, open problems, examples and dated changelog
entries for RLVM are written here - and only here: by the maintainer's rule of
2026-11-24 (the repository's root `AGENTS.md`) every note of the project lives
in this forest, and outside it only `README.md` files remain. The long
documents written before that rule (under `docs/` and next to the modules) are
being moved in; until one has been moved, a tree states the point and links to
the document that still carries the detail.

The forest is self-contained: everything it needs is in this directory, so it
can be moved into a repository of its own unchanged. All commands below are run
in this directory.

Write here freely as part of your work. You do not need to ask first. The
rendered forest is meant to be a public web site (see "Publication"), so read
"What must not be written" before you add a tree.

## Tools

Everything comes from the flake. Do not rely on tools installed on the host.

```sh
nix develop                 # shell with forester, git, python3, rg
journal-new <kind>          # allocate an address and create the tree file
journal-build               # forester build forest.toml, into output/
journal-check               # build a scratch copy; fails on any diagnostic
journal-serve               # build, then serve at http://localhost:1313/RLVM/
nix flake check             # the build must pass with no diagnostics
```

Outside the shell the same commands are `nix run .#new -- <kind>`,
`nix run .#build`, `nix run .#check` and `nix run .#serve`; `nix build .#site`
renders the site into `result/`.

Two things to know while the journal is a directory of the RLVM repository:

- A flake inside a git repository sees only files that git tracks. A tree that
  is not added yet is silently missing from `nix build .#site`. Write the flake
  as a path to include every file of the directory: `nix build path:.#site`,
  `nix develop path:.`.
- When several agents work in one copy, build the commands once with
  `nix build path:.#tools -o result-tools` and call
  `result-tools/bin/journal-new`, `result-tools/bin/journal-check` and so on.
  `journal-check` builds in a scratch directory and does not touch `output/`,
  so agents do not disturb each other; `journal-build` and `journal-serve`
  write `output/` and are for one agent at a time.

The first build on a machine compiles Forester from source and takes several
minutes; later builds reuse the Nix store.

## Kinds of trees

| Kind | `journal-new` argument | Address | Directory | `\taxon` |
|---|---|---|---|---|
| Design note | `design` | `design-XXXX` or `design-<slug>` | `trees/design/` | `Design` |
| Decision | `decision` | `dec-XXXX` | `trees/decision/` | `Decision` |
| Open problem | `problem` | `prob-XXXX` | `trees/problem/` | `Problem` |
| Changelog entry | `log` | `log-XXXX` | `trees/log/` | `Change` |
| Example | `example` | `ex-XXXX` | `trees/example/` | `Example` |
| Motivation | `motivation` | `mot-XXXX` | `trees/motivation/` | `Motivation` |
| Insight | `insight` | `ins-XXXX` | `trees/insight/` | `Insight` or `Hypothesis` |
| Session | `session` | `ses-XXXX` | `trees/session/` | `Session` |
| Identity | (by hand) | `claude-fable-5-2`, ... | `trees/people/` | `Person` |

- Addresses are global and permanent. Directories are only for tidiness; they
  are not part of the address. Never rename an address: other trees point at
  it.
- A design note may use a readable address (`design-<slug>`): create the file
  `trees/design/design-<slug>.tree` by hand. Everything else uses the random
  address `journal-new` allocates.
- Keep a tree to one idea. Put an example, a motivating case or an unresolved
  question in its own tree and `\transclude` it from every note it belongs to,
  rather than growing one long document.
- Which kind for what. A design or a review document of `docs/design/` becomes
  a `Design` note (one per subject, not one per document: a long document
  yields several). A choice between alternatives is a `Decision`. A measured
  finding (a profile, an experiment, a prototype's verdict) is an `Insight`
  with its numbers and their conditions; a claim nobody measured is a
  `Hypothesis`. A wrong verdict or a hostile input that shows a rule is an
  `Example`. Something unsettled is a `Problem`. There is no separate kind for
  profiles or reviews.
- The hub trees (`decisions`, `open-problems`, `examples`, `insights`,
  `sessions`) list trees by taxon with a query, so a new tree with the right
  `\taxon` shows up without further edits. Two hubs are maintained by hand:
  add a new design note to `trees/designs.tree`, and see "Changelog entries"
  below.
- The index links to the hubs and does not include them. A page carries in
  full every tree it transcludes, collapsed or not, and every tree that
  transcludes it, so a hub inside another tree, or a query list in a tree
  that many trees cite, multiplies the size of the site. Transclude a tree
  where it is read as part of the text (an example inside an insight); link
  to it otherwise.

## Frontmatter

Every tree starts with:

```
\title{Short title, sentence case}
\taxon{Design}
\date{2026-11-24}
\author{claude-fable-5-2}
\contributor{queclr}
\tag{...}
\import{base-macros}
```

- `\date` is the creation date. When you substantially revise a tree, do not
  change it; set `\meta{updated}{YYYY-MM-DD}`. A tree that records earlier
  work carries the date of the work, not the date it was written up.
- Dates are calendar dates in the time zone of the repository's commit
  history, which is the maintainer's (UTC-5 when the journal was started):
  the date `git log` shows for a commit and the date your environment gives
  as today. Do not use the UTC date. The status logs and several documents of
  the project give times in UTC; a time of day quoted from them is marked
  `UTC`, and a UTC time before 05:00 belongs to the previous date here. The
  line `journal-new` writes is a UTC time stamp: replace it.
- Design notes, decisions and problems also carry `\meta{status}{...}`,
  `\meta{created}{...}` and, once finished, `\meta{completed}{...}`, and repeat
  the status as the first paragraph (`\p{\strong{Status:} open.}`) so that it
  is visible on the page. Statuses: `draft`, `accepted`, `implemented`,
  `superseded`, `open`, `closed`.
- A tree that summarises a document names it: `\meta{origin}{docs/design/ISA.md}`.
- Changelog entries carry `\tag{changelog}`, one area tag (see "Changelog
  entries"), the release tag `unreleased`, `\meta{release}{Unreleased}` and
  `\meta{commit}{<short hash>}`, the commit on branch `dev` that recorded the
  change.
- Free tags name the subject: `machine`, `reference-engine`, `loader`, `store`,
  `driver`, `platform`, `gc`, `truffle`, `soundness`, `levels`, `nat`,
  `benchmark`, `arena`, `performance`, `journal`.

## Identity

- `\author` is the agent that wrote the tree: the identity tree of your exact
  model version, for example `claude-fable-5-2`. `\contributor` is the person
  who directed the session, and any agent or person who later revised the
  tree. Both take the address of a tree in `trees/people/`.
- If your model version has no identity tree yet, create one by copying an
  existing agent tree, and add it to `trees/agents.tree`. Do not write under
  another version's identity.
- Each working session leaves one session tree (`journal-new session`): date,
  `\meta{harness}{...}`, `\meta{model-id}{...}`, the RLVM branch and commits,
  and links to the trees it created or changed.
- When you revise a tree written by someone else, add yourself as
  `\contributor`; do not replace the `\author`.

## Changelog entries

One tree per change, written when the change is made, with the date it was
made. Then add it by hand in two places, newest first:

1. the month tree `trees/log/log-YYYY-MM.tree` (create it, and add it at the
   top of `trees/changelog.tree`, if the month is new);
2. the release tree `trees/log/release-unreleased.tree`, under its area.

The areas, one tag per entry:

| Area tag | What it covers |
|---|---|
| `reference-engine` | `rlvm-ref`, `rlvm-level`, `rlvm-nat`, `rlvm-inductive`, `rlvm-admit` |
| `machine` | `rlvm-machine`: instructions, values, memo tables, switches |
| `loader-store` | `rlvm-export`, `rlvm-load`, `rlvm-store` |
| `driver` | `rlvm-driver`: scheduling, sessions, passes, verdict protocol |
| `launcher-platform` | `rlvm-cli`, `rlvm-main`, `scripts/`, JVM and native-image settings |
| `bench-tooling` | `bench/`, `rlvm-testkit`, gates, mutant and hostile corpora |
| `truffle-tier` | `rlvm-truffle` |
| `soundness` | a fix of a wrong verdict, in whichever module |

Link the entry to the design note, decision or problem it relates to.

`\meta{commit}` is the commit whose message names the change. The history of
branch `dev` starts with an import on 2026-11-24, and the commits after it
are snapshots of a shared tree, so the source of a change can be in an
earlier commit than the one that records it: where no message names the
change, give the first commit that contains it, and for work older than the
history the import commit of its module. Name further commits in the text
with `\commit{}`. The order of entries within one day exists only in the
month tree and the release tree, so keep them newest first.

## Insights

Record a lesson only if it would help a later session avoid the same dead end
or reach the fix faster. Keep it to a few sentences and name its source (a
document under `docs/`, a commit or a session tree). Use `\taxon{Hypothesis}`
if it was not verified, and change it to `\taxon{Insight}` once it is.

## Numbers

RLVM's goal is warm wall-clock time with parallel workers (see
`trees/policy/standing-policies.tree`). A number without its conditions is
not usable.

- State a number exactly as its source does, with what it was measured on:
  the engine (reference engine or machine, and the switch set), the launcher
  (JVM or native image), the number of workers, whether the pass was warm, the
  corpus, and the machine class (the development host is aarch64; the arena
  runner is x86-64).
- Where a number was later corrected, give the corrected one and say so.
- Say what was not measured.

## Writing rules

- Write for a reader who has no access to any conversation. Trees may cite
  commits (`\commit{abc1234}`), documents of the repository (`\src{docs/...}`)
  and session trees. They must never refer to a chat, to "the user" or to an
  agent's private scratch files.
- The person who directs the work is "the maintainer". The documents of the
  repository say "the owner"; "owner note 12" names note 12 of
  `docs/design/OWNER-NOTES.md`, and "the owner of" a module is the agent
  responsible for it.
- Agents of the project are named by their role when it matters ("the
  integrator of the machine", "the adversarial reviewer"). The short labels
  that findings carry in the documents (`S3-F1`, `E1-F7`, `P8`) may be used as
  names of findings and reports.
- Record lasting instructions from the maintainer in
  `trees/policy/standing-policies.tree` in the same session, with the date.
  Read that tree and `trees/policy/accepted-risks.tree` before reviewing or
  restructuring the journal; do not re-raise an accepted risk.
- Do not delete a decision or a closed problem. Mark it superseded or closed
  and link what replaced it.

## What must not be written

The repository and the rendered site are public.

- No credentials, tokens or keys, and no host names or absolute paths of a
  machine. Paths are relative to the root of the RLVM repository.
- No personal data beyond the identity trees in `trees/people/`.
- Files under `work/` are not part of the repository. Do not link to them;
  cite the document under `docs/` that reports the result, or state the
  result in the tree.
- Source code of other projects is not copied here beyond a short quotation
  that a finding needs.

`python3 scripts/check-public.py` searches the trees for these and for
missing frontmatter. Run it with `journal-check` before finishing.

## Markup

Forester markup looks like LaTeX with markdown links. The pitfalls:

- Every paragraph must be wrapped: `\p{...}`. Bare text is not a paragraph.
- Do not hard-wrap prose. One paragraph is one line.
- `%` starts a comment that swallows the rest of the line. Write `\%`.
- `{ } [ ] \ #` are syntax. In prose, put them in inline code or a verbatim
  span. Parentheses must be balanced within a paragraph.
- Inline code: `\code{\verb~|is_def_eq~}`. The verbatim span is
  `\verb<mark>|...<mark>`, where `<mark>` is any text that does not occur in
  the content (`~` usually works; use `!!` when the content has a `~`). Plain
  words without syntax characters may be written `\code{skipargs}`.
- Code block:

  ```
  \pre{\verb~|
  def d (x : A) : Nat := And.rec (fun _ _ => 5) x
  ~}
  ```

- Links: `[text](address)` for a tree. For a URL use
  `\<html:a>[href]{\verb~|https://example.org/x#y~}{text}`.
- A document or source file of the RLVM repository: `\src{docs/design/ISA.md}`
  (no verbatim span inside; the path is the argument). The link shows the file
  on branch `dev` as it is today; for a document that is regenerated, such as
  `docs/BENCHMARKS.md`, cite the state you quote:
  `\srcat{0123abc}{docs/BENCHMARKS.md}`. A commit: `\commit{0123abc}`. All
  three come from `base-macros`.
- Lists: `\ul{\li{...}\li{...}}`, `\ol{...}`. Emphasis: `\em{}`, `\strong{}`.
- Tables: `\table{\tr{\th{A}\th{B}}\tr{\td{1}\td{2}}}` (from `base-macros`).
- Include another tree as a section: `\transclude{address}`. Declare a section
  with its own address inline: `\subtree[address]{\title{...} ...}`.
- A link or a transclusion to an address that does not exist is a diagnostic,
  and a diagnostic fails the build.
- A markdown draft can be converted with
  `python3 scripts/md2tree.py draft.md <address>`.

## Addresses cited from outside

RLVM source and the documents under `docs/` do not cite journal addresses yet.
The direction today is from the journal to the repository: a tree links to the
document that carries the detail by its path relative to the repository root.

When code or a document does cite a tree, it writes the marker
`*agentic-note*: <address>`, and from then on that address is permanent: the
tree may be revised or marked superseded, never renamed or deleted. To resolve
a marker, search here: `rg -F '<address>' trees/`. A whole tree is the file of
that name; a section is declared as `\subtree[<address>]`.
`python3 scripts/check-markers.py` checks that every marker in the repository
names an existing tree or section.

## Publication

`.github/workflows/journal-pages.yml` at the root of the RLVM repository
builds the forest with `nix build .#site` and publishes it as GitHub Pages on
every push to branch `dev` that touches this directory. The site's address is
the `url` of `forest.toml`. A pull request builds and does not publish. Links
in the rendered forest are absolute and start with the path of `url`, so the
site root is the directory `RLVM` of the build output. If the journal moves to
another repository, change `url`, `sitePath` in `flake.nix` and the workflow
together. Pushing is the maintainer's decision: do not push, and do not start
the workflow by hand, unless asked.

## Licence

The trees are licensed under the GNU Free Documentation License, Version 1.3
or any later version, with no Invariant Sections, no Front-Cover Texts and no
Back-Cover Texts. The text is `LICENSE` in this directory, and
`assets/GFDL-1.3.txt` is the same text for the site, which shows the notice
in `trees/licence.tree`. Code quoted in trees is from the RLVM repository and
is under its licence, Apache-2.0 OR MIT. A tree needs no licence header.

## Version control

The journal is committed with the RLVM repository, on branch `dev`.

- In the RLVM project, agents do not run git; the orchestrator commits each
  step. See `trees/policy/standing-policies.tree`.
- Commit messages for journal-only changes start with `journal:` and the kind
  of tree touched, for example `journal: design note on the pair table`.
- Before finishing, run `journal-check` (or `nix flake check path:.`) and fix
  every diagnostic.

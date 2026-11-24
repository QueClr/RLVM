# RLVM agentic journal

Working notes for RLVM (Rabdos Lean VM), a checker for the Lean 4 kernel on
GraalVM: design notes, decisions, measured findings, open problems, examples
and a dated changelog, written mostly by coding agents and tracked with the
identity of the agent that wrote them.

The notes are a [Forester](https://www.forester-notes.org/) forest under
`trees/`. Each note states one point and links to the document under `docs/`
of the RLVM repository that carries the detail.

```sh
nix develop path:.   # forester and helpers
journal-serve        # build and browse at http://localhost:1313/RLVM/
journal-check        # build a scratch copy; fails on any diagnostic
```

The rendered forest is published at <https://queclr.github.io/RLVM/> by
`.github/workflows/journal-pages.yml` of the RLVM repository.

See [AGENTS.md](AGENTS.md) for the layout and the writing rules.

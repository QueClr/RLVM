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

`.github/workflows/journal-pages.yml` of the RLVM repository renders the forest
and publishes it as GitHub Pages when branch `dev` is pushed; the site's
address will be the `url` of `forest.toml`.

See [AGENTS.md](AGENTS.md) for the layout and the writing rules.

## Licence

Copyright 2026 The RLVM Authors. The trees of this journal are licensed under
the GNU Free Documentation License, Version 1.3 or any later version published
by the Free Software Foundation; with no Invariant Sections, no Front-Cover
Texts, and no Back-Cover Texts. The text of the licence is in
[LICENSE](LICENSE). Code quoted in trees is from the RLVM repository and is
under its licence, Apache-2.0 OR MIT.

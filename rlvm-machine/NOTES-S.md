# NOTES-S - the independent soundness review (package S)

The review, its findings, the table of shortcuts and the verdict on the switch sets are in
`docs/design/review/soundness-machine.md`. This file says what package S keeps in the module.

| What | Where |
|---|---|
| hostile exports, gzip (329; each attacks one shortcut or memo; plain files in `work/soundness/exports/`) | `src/test/resources/s/*.ndjson.gz` |
| manifest: name, verdict of the OFFICIAL checker (leanchecker 4.36.0), known findings, what the export attacks | `src/test/resources/s/manifest.tsv` |
| generators (`lk.py`: export builder on `init-prelude` with a recursor generator; `batch1..8.py`), the judge (`judge.py`: official checker, reference engine and the machine through the launcher under any switch sets; `check-machine.sh` for `testkit judge`), the targeted mutator (`mutate.py`) | `src/test/resources/s/gen/` (working copies in `work/soundness/`) |
| `SHostile` | in-process judgment of one export by M8's `Diff.compare` under a switch set; the rule of a row |
| `SHostileTest` (group `fast`) | v0 and `warm,gated,expect` on every export; `probes`, `facts`, the K1 roles on the exports that attack them. 6 tests, about 5 s on a quiet host |
| `SHostileSlowTest` (group `slow`) | every export under 17 switch sets (about 4 minutes on a loaded host); `noKnownFindingIsLeft` (strict) |

Rules of the tests: a WRONG ACCEPT or WRONG REJECT against the official verdict fails, unless the manifest lists
it as a KNOWN FINDING of a switch (`warm`; `probes` and `facts` had rows until M4's fix of 14:30Z) or of every
configuration, v0 included (`eager` = S-F5, `levels` = S-F3): those are printed.

State on 2026-11-24, 15:15Z (main tree): open is only `levels` (2 rows: S-F3, both engines). The 16 rows of
S-F1 / S-F2 (fixed by M4 at 14:30Z), the 3 rows of S-F4 and the 2 rows of S-F5 (closed by M6's rule M6-F26, armed
by M5 and M7 by 15:10Z: a declaration that enters eager reduction is DECLINED by the machine and checked by the
reference engine) are strict and pass under every switch set. The 11 exports that enter eager reduction carry the
note `eager-decline`: a decline of the machine on them is by design and is not printed.

Adding an export: write it with `gen/lk.py` (see `gen/batch2.py` for a short example), judge it with
`gen/judge.py FILE` (prints the official verdict and every switch set), gzip it into `src/test/resources/s/`
and add its manifest row.

Deviation: the exports are stored compressed (2.3 MB instead of 8.5 MB) and unpacked into a temporary directory
by the tests.

## The whole suite (INT7, 2026-11-25; OWNER-NOTES 13, the "practical" policy)

`manifest.tsv` has 1,189 rows: package S's and E1's 421, reviewer S3's 423 (`s3-*`; `work/s3/manifest.tsv`, the six rows
that caught seeded faults first), reviewer S2's 279 (`s2-*` = `work/s2/attacks` 134 + `attacks2` 11, `s2d-*` =
`attacks-dat` 134), reviewer C1's 41 (`c1-*`; `work/c1/hostile`) and R2's 25 (`r2-*`; `work/r2/exports/batch1..3.tsv`).
The official verdict of every new row was taken again from leanchecker 4.36.0 in Docker (`work/int7/official.sh`,
`work/int7/hostile/official.tsv`: 325 accept, 443 reject; every reviewer's verdict confirmed, S2's `attacks-dat` had
none of its own). Built by `work/int7/hostile/collect.py` + `mkmanifest.py` (+ `marks.tsv`).

**Every row = official verdict + kind.** The kind is the mark `T` (81 rows, all official REJECT): the official verdict
OR an accept. Every other row is strict. A `T` is given only where a reviewer gave it; the text of the row says who:

| Rows | Kind T by |
|---|---|
| 31 `s3-*` | S3, `soundness-default-set.md` section 8 (derivable as written; certificates and twins in sections 3, 7, 10) |
| 15: `probe-b1-*`, `probe-b12-*`, `facts-*-noncongruent-*`, `open-flag-closed-bad` (14) and `s2-acc-rec-intro-vs-axiom` | S2, `soundness-shortcuts.md` section 4: 15 certificates accepted by the official checker |
| 19 rows of the family `X = Y p` under eager reduction (`*-eager-*`, `krule-*-eager-*`, `e1-site-*`, ..) | S3, section 10.1 (family argument) |
| 7 `e1-lam-fvar-type-in-conv-*`, `e1-sib-*` | E1 (`official-divergence.md` section 1, certificates `e1-cert-*`); the seventh, `e1-sib-proj-let-pi-bad`, by S3 10.1 |
| 5 `r2-*` | R2 (findings R2-F1 nondep, R2-F2 object identity, R2-F3 admission cache) |
| `c1-fuse-abbrev-samehead-proofarg-bad`, `c1-fuse-opaque-samehead-proofarg-bad` | INT7 by S2's certified family: the statement `G I = G h` of `probe-b1-abbrev-proof-arg` / `probe-b1-theorem-head` |
| `r2-id-beta-control-bad`, `r2-id-literal-vs-offset-bad` | INT7 by S3's family S3-F1 (R2 lists them as controls of S3-F1): the statement of an officially accepted twin up to a beta step / `Nat.succ 6 = 7` |

Notes of single rows (the rule is in `SHostile`): `recheck` (11 official ACCEPT rows that the machine's primary lane
may REJECT through its cache history - S3-F1, S3-F3, S3-F6 and R2's `r2-id-binder-*`, `r2-ptr-*-ok` - and the
reference engine accepts: the pipeline's verdict is the official one; a rejection of the machine on an official ACCEPT
without this note fails the suite); `mdata` (`s3-mdata-eager-hidden-ok`: official ACCEPT because an `mdata` node hides
`eagerReduce`; with transparent `mdata` - the practical policy - `default,eager` and the reference engine REJECT it;
with the two shortcuts the machine accepts it. It is the one known input on which the pipeline rejects what the
official checker accepts; it belongs to the strict mode's suite); `decline` (`s2-prop-mdata-domain-bad`, `s2d-`: the
reference engine declines).

`SHostileSlowTest`: 1,189 rows under every switch set. `SHostileTest` (fast): v0 and `warm,gated,expect` on every row.

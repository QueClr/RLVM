# NOTES-S - the independent soundness review (package S)

The review, its findings, the table of shortcuts and the verdict on the switch sets are in
`docs/design/review/soundness-machine.md`. This file says what package S keeps in the module.

| What | Where |
|---|---|
| hostile exports, gzip (312; each attacks one shortcut or memo; plain files in `work/soundness/exports/`) | `src/test/resources/s/*.ndjson.gz` |
| manifest: name, verdict of the OFFICIAL checker (leanchecker 4.36.0), known findings, what the export attacks | `src/test/resources/s/manifest.tsv` |
| generators (`lk.py`: export builder on `init-prelude` with a recursor generator; `batch1..6.py`), the judge (`judge.py`: official checker, reference engine and the machine through the launcher under any switch sets; `check-machine.sh` for `testkit judge`), the targeted mutator (`mutate.py`) | `src/test/resources/s/gen/` (working copies in `work/soundness/`) |
| `SHostile` | in-process judgment of one export by M8's `Diff.compare` under a switch set; the rule of a row |
| `SHostileTest` (group `fast`) | v0 and `warm,gated,expect` on every export; `probes`, `facts`, the K1 roles on the exports that attack them. 6 tests, about 5 s on a quiet host |
| `SHostileSlowTest` (group `slow`) | every export under 15 switch sets; `noKnownFindingIsLeft` (strict) |

Rules of the tests: a WRONG ACCEPT or WRONG REJECT against the official verdict fails, unless the manifest lists
it as a KNOWN FINDING of that switch (`probes`, `facts`) or of every configuration (`levels`): those are printed.
A decline of the machine is printed and never fails. When an owner has fixed a finding, `SHostileSlowTest#
noKnownFindingIsLeft` passes for it and the mark is removed from the manifest (ask S, or edit the manifest in
the same publication as the fix: the row is then strict for everybody).

Adding an export: write it with `gen/lk.py` (see `gen/batch2.py` for a short example), judge it with
`gen/judge.py FILE` (prints the official verdict and every switch set), gzip it into `src/test/resources/s/`
and add its manifest row.

Deviation: the exports are stored compressed (2.3 MB instead of 8.5 MB) and unpacked into a temporary directory
by the tests.

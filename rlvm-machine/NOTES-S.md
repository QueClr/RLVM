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

# Where the machine's verdict can differ from the official checker's

2026-11-24, agent E1 (the machine's integrator), with the findings of S3 and R2 as they stand on the board
(`work/status/requests.log`). This is the record of what the owner's "practical" policy
(`docs/design/OWNER-NOTES.md` note 13) tolerates: every class below is of KIND T - the accepted statement is
derivable in Lean's type theory and the official checker rejects it only through its own non-transitivity, cache
order or object identity. No class of KIND F (an accepted statement that is not derivable) is known. Ground truth
for every verdict: `leanchecker --from-export`, Lean v4.36.0, in the arena Docker image.

Sections 1 and 5 are E1's own work and measured. Sections 2 - 4 summarise S3's and R2's board lines; their exports
and certificates are theirs and S3 / R2 complete the rows (marked "S3" / "R2").

## The common root

The official `is_def_eq` is not transitive and not a function of the pair. Package S's triple: with
`A = True /\ True`, `h : A` an axiom and `d x := And.rec (fun _ _ => 5) x`, the kernel accepts `d intro = d h` and
`d intro = 5` and rejects `d h = 5`. S3: the cache `m_whnf_core` is read by cheap calls and written by full ones, so
the answer for a pair depends on what was reduced before. R2: one test in `is_def_eq_core` is pointer equality, so
the answer depends on whether a subterm is one export record or two. An engine that computes on VALUES and keys its
memo tables by values answers the question of the theory, which is transitive.

## 1. Stand-in types of terms the kernel has not reduced (E1-F1 .. E1-F7)

The official kernel types a bound variable by its binder's DECLARED type, and it infers the type of terms it has not
reduced. The machine binds a variable to a value and types values, so it can use a type that is only convertible.

| Shape | Export (S manifest) | Official | Machine now | Kind, certificate |
|---|---|---|---|---|
| F1 let variable typed by its value | `e1-app-type-is-domain-let-bad` | reject | reject (fixed) | T: `e1-cert-dh-eq-5-ok` |
| F2 redex re-inferred, variable typed by the argument | `e1-redex-function-applied-open-bad` | reject | reject (fixed) | T: the same two steps (no export of its own) |
| F3 lambda against Pi, variable of the other side | `e1-lam-fvar-type-in-conv-bad` in v0 | reject | reject (fixed) | T: `e1-cert-lam-fvar-ok` |
| F4 B9 (`expect`) with convertible domains | `e1-lam-fvar-type-in-conv-bad`, `-second-binder-bad`, `-argument-bad` | reject | reject; ACCEPT with the switch `convexpect` (off by default) | T: `e1-cert-lam-fvar-ok` |
| F5 expected codomain stored as a memo | `e1-expect-cod-memo-bad` | reject | reject (fixed) | T: the same two steps (no export of its own) |
| F6 K rule on an applied let variable | `e1-sys-let-krule-major-applied-bad` | reject | declined (rule) | T: statement `True` |
| F7 type of a stuck projection's struct / raw K major | `e1-sib-proj-applied-let-pi-bad`, `e1-sib-proj-redex-pi-bad`, `e1-sib-krule-major-redex-bad` | reject | ACCEPT (the third: `gated` sets only) | T: statement `True` |

Certificates (official ACCEPT): `e1-cert-dh-eq-5-ok` proves `d h = 5` by `Eq.rec` from the two steps the kernel
accepts; `e1-cert-lam-fvar-ok` proves the statement of the F4 rows from its accepted twin. Why the class cannot
produce kind F: every step the machine takes is a conversion that holds in the theory (delta, iota, proof
irrelevance), the theory's definitional equality is transitive and typing is closed under it, so a term the machine
types through a convertible stand-in has that type in the theory.

What is exact now (machine jar `5945b6568177` and later; 92 rows `e1-*`, 6 of them marked kind T: the three of F4 are rejected by
the default set, the three of F7 are accepted): the let rule, the type of
a lambda instantiated with an argument of another type (typed binding), CBIND's comparison variable, the expected-type
memo. The typed bindings cost nothing measurable (ticks -0.35 % / -0.01 % on the Cedar slice / Std without them).
B9's restriction to ONE domain value costs 2.4 % of the ticks of the Cedar slice, 0.3 % of Std's, Init's and the 16
Mathlib slices' (268,665,151 against 269,542,201) and nothing measurable in warm wall clock (native image, 8 workers: cedar 1.59 / 1.62 s before the fix, 1.59 / 1.58 s after; Std
1.19 / 1.20 s before, 1.19 / 1.22 s after), so it stays; the switch `convexpect` (off) gives the old B9 back.

Not exact, and intrinsic to a machine on values (F7): an exact treatment needs the official type of the unreduced
term - `VTYPE` of a suspension = `INFER` of its term, a suspension never replaced by its known value where it stands
for an argument, a stuck projection that keeps the suspension of its struct, and the same in the compiled paths
(`rlvm-machine/NOTES-M5.md` "E1-F7"). Not built (orchestrator 21:58Z; owner's note 13). Decline rules instead would
cost 322 of 425,620 units of the Mathlib slices and 12 of 100,096 of Std (after the application of an inexact let
variable) and do not cover the let-free shapes.

## 2. Value-keyed pair table against the official term-keyed caches (S3-F1; S3)

`work/s3/exports/s3-wc-pos-beta-bad.ndjson`, `s3-wc-canon-fvar-bad`: official reject, machine accept in v0 and every
set (checked with jar `5945b6568177`). The machine's POS pair, keyed by values, answers a comparison that the
official kernel asks for a new term pair after a full `whnf_core` changed what the cheap call returns. Kind T (S3:
the twin with the two sub-proofs in the other order is accepted officially). Exact treatment: the official
per-declaration caches mirrored, keyed by terms - a machine on values has no term keys; cost not measured (S3).

## 3. Memo that survives a declaration: `warm`, `pchk` (S3-F3, S3-F5; S3)

`s3-wc-warm-term-reused-bad`, `s3-wc-warm-pair-reused-bad` (accept under `warm` and the default set; v0 rejects),
`s3-pchk-def-reused-*`. Officially every cache lives for one declaration. Kind T (S3). Also a wrong REJECT of the
warm machine alone (`s3-wc-proj-two-decls-ok`), which the pipeline's strict re-check turns into the official accept.
Exact treatment: no memo across declarations that answers a comparison or a cheap CORE; its cost is the gain of
`warm` (`rlvm-machine/SWITCHES.md`, the table of the default configuration: v0 against the default set).

## 4. Switch-specific (S3) and the reference engine (R2)

`lazyeval`: `s3-wc-inner-caseson-lazy-bad` (S3-F4, kind T). `k2`: S3-F6, a wrong accept with its default thresholds;
the switch stays off (decided 21:58Z). Reference engine: `s3-wc-pos-mdata-bad` (S3-F2: the loader drops mdata
records; under the practical policy mdata stays transparent, the decline belongs to the strict mode) and
`work/r2/exports/r2-ptr-other-record-bad.ndjson` (R2-F2: pointer identity of export records; any engine over a
hash-consed store).

## 5. Eager reduction

Without the switch `eager` a declaration that enters eager reduction is declined (M6-F26) and the reference engine
decides it. With the switch (published 21:57Z, off by default) the machine decides it with the official verdict or
declines; 18 hostile rows are declined by design (`docs/design/review/eager-reduction.md`). No divergence is known.

## 6. What the gates show for real inputs (contract items (b) and (c) of note 13)

Machine jar `5945b6568177` (the fix), main tree: M8's full gate for v0 and the default set, no wrong verdict and no
difference; mutants 17,775 x (v0, default): SOUNDNESS 0, COMPLETENESS 0; DiffEngine through the launcher on the 230
arena tests, Init, Std, cedar, cslib, reachability, the Cedar slice and 16 Mathlib slices (1,153,493 engine
checks): 0 verdict differences from the reference engine; seven corpora accepted by the native image with 8 workers.
The hostile suite has 421 rows; a row with the mark `T` demands the official verdict or an accept, every other row
the official verdict.

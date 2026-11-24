# 01 — Arena contract and the lean4export NDJSON format

Research snapshot: arena commit `99195b6b` (2026-11-23, the commit the live site was built from), `results.json`
of the same commit (37 checkers, 270 tests, 9990 results, runs dated 2026-11-14 … 2026-11-23).
Paths are relative to `/home/queclr/Documents/rabdos-lean-vm/refs/` unless absolute.

Sources read (all read-only; nothing from the cloned repositories was built or executed — the only programs run were the host's official Lean 4.36.0 toolchain binaries `leanexport`, `lean`, `leanchecker`, and analysis scripts written for this report):
- `lean-kernel-arena/` (README.md, lka.py, set-timeouts.py, schemas/, checkers/*.yaml, tests/**, tutorial/**, .github/workflows/build-and-deploy.yml, flake.nix)
- `lean-kernel-arena-history/` — blob-less clone made for this report, only to read commit messages (7e43d5c, 8253be0)
- `lean4export/` — standalone exporter, HEAD df81913 (toolchain v4.36.0-rc1)
- `lean4-leanexport/` — sparse clone of leanprover/lean4 at tag v4.36.0 (611c117b…) made for this report: `src/LeanExport/{Basic,Parse}.lean`, `src/Lean/Data/Json/*`; other files read with `git show HEAD:<path>` (`src/Lean/Replay.lean`, `src/Lean/Declaration.lean`, `src/Lean/Expr.lean`)
- `lean4/` (another agent's sparse clone, v4.36.0): `src/LeanChecker.lean`, `src/kernel/type_checker.cpp`, `src/kernel/inductive.cpp`
- Downloaded data in `/home/queclr/Documents/rabdos-lean-vm/work/arena-site/`: `index.html`, `results.json`, `lean-arena-tests.tar.gz`, extracted `tests/{good,bad}/…` (230 files, 52 MB)
- Generated locally with the host's official Lean 4.36.0 toolchain (`~/.elan/toolchains/leanprover--lean4---v4.36.0/bin/leanexport`): `work/arena-site/generated/init.ndjson` and `std.ndjson`. Both are **byte-identical to the arena tests `init` and `std`** (sha256 equal to the values in results.json), see §5.

---

## 0. Decision-relevant facts first

1. **Only instructions on `mathlib` rank speed.** Sort key = (bad tests accepted, good tests rejected, mathlib instructions [only if mathlib was *accepted*], declined count) — `lean-kernel-arena/lka.py:2728-2738`. Declines, crashes and timeouts never count as a wrong answer, only in the last tie-breaker.
2. **mathlib export = 6,326,141,213 bytes, 112,081,718 lines**: 107,037,712 expression records, 4,273,914 name records, 48,246 level records, 721,845 declaration records (739,167 constants). Leader nano-lean spends 394.9 G instructions on it = **62 instr/byte = 3.5 k instr/line**, parsing included. Every instruction per input byte costs 6.3 G.
3. **Exporter output is byte-canonical**: one compact JSON object per line, keys in ASCII-sorted order, no whitespace, `\n` line ends, dense ids in emission order, children before parents. Verified: 100 % of the 17,106,803 lines of `init`+`std` and 99.97 % of the lines of the 230 tarball files match fixed byte templates (§2.3). The 271 exceptions are all in 8 hand-crafted *bad* tests (other key order, spaces after `:`/`,`, `\u03b1` escapes) → **template fast path + generic JSON fallback per line**.
4. **`app` records are ~80 % of all lines** (init 80.5 %, std 76.2 %), 48 bytes each; `lam` 8–10 %, `forallE` 5–6 %, names 4.6–5.5 %, everything else < 2.5 %. Declaration lines are < 1 % of lines.
5. **The expression table is already hash-consed** by the exporter modulo binder names/binder info (`HashMap Expr Nat` with `BEq Expr = Expr.eqv`): 0 structural duplicates among 16.07 M expr records of init+std and in every tarball file. No hash-consing is needed at load time for exporter output; id equality ⇒ structural equality (the converse must not be relied on for hand-crafted files).
6. **~80 % of expression records are reachable only from theorem *values* (proofs)**; ~94 % are never referenced after the declaration that introduced them; 83–84 % have exactly one referrer (§2.12).
7. **Ids must not be assumed dense/ordered**: two *accept* tests use sparse and out-of-order ids (`other/sparse-name-index`, `other/level-index-out-of-order`). Ids are arbitrary naturals; "defined before use, bound once" is the only rule the official reader enforces.
8. **Nothing derived may be trusted**: constructor records, recursor records (type, rules, `nfields`, `k`, counts), `numFields`, `isRec`, quot types, level-param lists. 16 of the 32 `bugs/` rejects are forged derived data (§3.3).
9. **File order is dependency order** for exporter output (every constant declared before any record of another declaration uses it; verified on init, std, all good tests). The official checker itself replays by dependency, not file order, and accepts a file whose declaration lines are out of order (verified by experiment, §2.11).
10. **Axioms**: accept every well-typed axiom. `cedar` contains 9 `…_native.native_decide.ax_N_M` axioms plus `sorryAx`; all top checkers and the official one accept it; only the con-leche family declines. `Lean.trustCompiler`/`Lean.ofReduceBool`/`Lean.reduceBool`/`Lean.reduceNat` **do not occur at all** in the 4.36.0 `init`/`std` exports and the 4.36.0 C++ kernel has no `reduce_native` any more (only `reduce_nat`).
11. **unsafe/partial**: never present in the real corpora; only `tutorial/140`, `141` (exported with `--export-unsafe`). Official behaviour: skip such constants, so anything using them fails with "unknown constant" → reject.
12. **Timeouts** are wall-clock per test (mathlib 4120 s, perf tests 40–360 s, small tests none); a timeout is scored like a decline. Runner: 8 vCPU / 16 GB x86-64, one checker per VM, tests run sequentially.
13. **JVM baseline measured in the arena** (tenet-jvm, plain `java -XX:+UseParallelGC -XX:MaxRAMPercentage=75`): 0.546 G instructions / 64 MB / 75 ms for a trivial test; mathlib 9798 G instructions, 11.8 GB RSS, 1060 s wall.
14. Local ground truth exists: `~/.elan/toolchains/leanprover--lean4---v4.36.0/bin/leanchecker --from-export FILE` (exit 0/1) runs on this aarch64 host; its messages for all 92 bad tests are in §3.

---

## 1. The arena contract

### 1.1 Checker definition, build, run

`checkers/<name>.yaml` (schema `lean-kernel-arena/schemas/checker.json`): `language` (enum incl. `java`), `url`+`ref`+`rev` (git, fetched with `git fetch --depth 1 origin <rev>`), `build` (shell, optional), `run` (shell, required), optional `version`, `timeout` (s, min with the test's), `declines` (test names recorded as declined without running), `threads` (declared worker count; **not read by lka.py or the templates at this commit** — informational), `disable`.

- Build: `build` runs via shell in the repo checkout `_build/checkers/<name>/src` (`lka.py:1612-1652`). Build time is not measured. Precedents for heavy builds: nano-lean `scripts/pgo.sh`, gruszka/pestka PGO builds trained on a downloaded Init export with `sha256sum -c`, kefir-wasm installs a rustup toolchain with curl, tenet-jvm `make` with the flake's `jdk`.
- Tools on the runner come from `lean-kernel-arena/flake.nix` (nixpkgs `nixos-26.05`): python3, elan, rustc, cargo, llvm 21, perf, cmake, jq, just, pypy, nodejs, binaryen, ocaml, opam, gmp, zig, ghc, texlive, dotnet-sdk_10, **`jdk`** (version = nixpkgs default; not verified). No GraalVM: RLVM's build step has to fetch a pinned GraalVM itself (or the flake must be extended in the PR).
- Run: `sh -c "<run>"` (not bash), cwd = `_build/checkers/<name>/src`, environment = runner environment + `IN=<absolute path of the .ndjson>` (`lka.py:1750-1769`). For cached tests `$IN` is a symlink into a cache volume (`lka.py:1254-1257`).
- stdout/stderr are piped and drained; only the first and last 100 lines / 8 KiB of each are kept (`lka.py:284-285`, `BoundedOutput`). Output never blocks, but printing costs instructions.
- **Exit codes** (`lka.py:1771-1781`): 0 → `accepted`, 1 → `rejected`, 2 → `declined`, anything else (incl. signals, e.g. 137 = OOM kill) → `error`; killed by the arena on timeout → `timeout`.
- Correctness (`lka.py:1784-1798`): declined → `declined`; error/timeout → `error`; test outcome `either` → `either`; match → `correct`; else `incorrect`.
- Timeout handling: the whole wrapped command runs in a new session and the **process group gets SIGKILL** when `min(test.timeout, checker.timeout)` wall seconds elapse (`lka.py:365-441`, `1765-1766`). No grace period, no partial credit.

### 1.2 Measurement

Command actually executed (`lka.py:496-514`):

```
perf stat -j -o <tmp.perf> -e duration_time -e task-clock -e instructions -- \
  time -f "real_seconds=%e\nuser_seconds=%U\nsys_seconds=%S\nmax_rss_kb=%M" -o <tmp.time> -- \
  sh -c "<run command>"
```
with `LC_ALL=C`.

- `instructions` → result field `instructions`; `task-clock` → `cpu_time`; `duration_time` → `wall_time`; GNU time `%M` × 1024 → `max_rss` (`lka.py:532-593`). `user_seconds`/`sys_seconds` are written by `time` but not stored.
- `perf stat` counts the command **and everything it forks and all their threads** (default inherit; no `--no-inherit`). A wrapper script, a converter subprocess (gruszka/pestka run `susz pack` first), GC threads and JIT compiler threads are all inside the count.
- **User vs kernel instructions.** Documented intent: user-space only. The parser strips the `:u` suffix that perf appends when `perf_event_paranoid=2` forces user-only counting (`lka.py:541-543`), the workflow comment says "user-space-only at paranoid=2" (`.github/workflows/build-and-deploy.yml:84-85`), and commit 7e43d5c states "per-process user-space counting works at the default paranoid = 2 with no sysctl needed". **Not verified:** the same job first runs a probe step that executes `sudo sysctl -w kernel.perf_event_paranoid=-1` (`build-and-deploy.yml:146`, `continue-on-error`); if sudo is permitted on the Namespace runner, the later `lka.py run` step counts user+kernel. The CI logs are not publicly downloadable (HTTP 403). Treat kernel-mode work (page faults of a multi-GB heap, `read`, `madvise`) as possibly charged.
- `max_rss` is `ru_maxrss` of the child tree as reported by GNU time: the largest single process, not a sum (Linux `getrusage` semantics; not re-verified on the runner). Memory is displayed, not ranked. The hard limit is the 16 GB VM: rpylean was killed with exit 137 at 14.65 GB on `perf/magma-string-pair-n9` → `error`.
- **Virtual time** = `instructions / 6e9` (`lka.py:2339-2345`, constant at `2687`). The leaderboard's ⏱️ column and the third sort key use the instruction count only. Cache misses, waiting and I/O time are free; instructions are everything (nano-lean retires 3.2 G instr per CPU-second on mathlib, tenet-jvm 8.6 G, official 5.4 G).
- Fallback if perf is missing: wall time only, `instructions = 0` (`lka.py:599-613`).
- Each (checker, test) pair is measured **once**; results are reused across site builds while the source hashes of checker and test are unchanged (`ci-select`, `lka.py:3086-3152`). No averaging.

### 1.3 Scoring and ranking

`compute_checker_stats` (`lka.py:2038-2141`) and `sort_key` (`lka.py:2728-2738`):

```
for each test result of the checker:
    if status in (declined, error, timeout): declined_count += 1; continue      # never counts as wrong
    if outcome == either: continue                                              # never counts
    if outcome == accept: accept_total += 1; accept_correct += (status == accepted)
    if outcome == reject: reject_total += 1; reject_correct += (status == rejected)
    if test == "mathlib" and status == accepted: mathlib_instructions = instructions
key = (reject_total - reject_correct,          # bad proofs accepted
       accept_total - accept_correct,          # good proofs rejected
       mathlib_instructions or +inf,           # 0/missing sorts last
       declined_count)
```
Consequences: (a) when unsure, exit 2 (or crash) rather than guess — a decline costs only the last tie-breaker; (b) rejecting mathlib, or timing out on it, removes the checker from the speed ranking; (c) `either` tests are free.

Leaderboard at this snapshot (columns: wrongly accepted / wrongly rejected / declined(decl,err,timeout) / mathlib virtual s / G instr / wall s / cpu s / RSS GB; `thr` = declared threads):

| # | checker | lang | thr | badAcc | goodRej | declined | virt s | G instr | wall | cpu | RSS |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | nano-lean | rust | 4 | 0 | 0 | 3 (3/0/0) | 65.8 | 394.9 | 36.8 | 121.6 | 5.00 |
| 2 | gruszka | rust | 4 | 0 | 0 | 0 | 77.0 | 462.0 | 32.5 | 109.7 | 2.31 |
| 3 | sokonanoda | rust | (4 used) | 0 | 0 | 6 (2/2/2) | 87.9 | 527.4 | 46.3 | 154.8 | 4.95 |
| 4 | rpylean | python (RPython) | 1 | 0 | 0 | 4 (0/1/3) | 96.3 | 578.1 | 130.2 | 127.3 | 4.44 |
| 5 | pestka | aver | 4 | 0 | 0 | 0 | 245.9 | 1475.3 | 100.6 | 341.2 | 3.50 |
| 6 | kefir | rust | 1 | 0 | 0 | 0 | 429.5 | 2577.3 | 442.5 | 442.4 | 2.48 |
| 7 | palladium | rust | 4 | 0 | 0 | 6 (0/6/0) | 472.2 | 2833.0 | 148.0 | 592.9 | 3.82 |
| 8 | con-ron | rust | 4 | 0 | 0 | 10 (10/0/0) | 579.0 | 3473.9 | 149.8 | 440.2 | 4.41 |
| 9 | kefir-wasm | rust/wasm | 1 | 0 | 0 | 0 | 582.7 | 3496.0 | 411.9 | 412.7 | 2.73 |
| 10 | con-agua | x86 | 4 | 0 | 0 | 10 (10/0/0) | 654.3 | 3925.9 | 210.3 | 675.0 | 11.82 |
| 11 | nanobruijn | rust | (4 used) | 0 | 0 | 2 (0/0/2) | 784.1 | 4704.7 | 311.8 | 973.2 | 6.85 |
| 12 | nanoda | rust | (4 used) | 0 | 0 | 4 (2/0/2) | 962.2 | 5773.3 | 377.2 | 1227.3 | 5.94 |
| 15 | tenet-jvm | java | 1 | 0 | 0 | 3 (0/1/2) | 1633.1 | 9798.4 | 1060.0 | 1143.9 | 11.77 |
| 16 | official-nightly (2026-11-14) | c++ | 1 | 0 | 0 | 0 | 1635.9 | 9815.1 | 1891.7 | 1891.4 | 9.91 |
| 18 | official (v4.35.0) | c++ | 1 | 0 | 0 | 2 (0/0/2) | 2015.9 | 12095.3 | 2234.4 | 2233.9 | 9.90 |
| 31 | nanoclo | rust | 4 | 2 | 1 | 0 | 501.9 | 3011.4 | 200.0 | 658.1 | 7.40 |
| 37 | mathgraph | rust | 2 | 8 | 1 | 2 | 152.0 | 911.7 | 104.5 | 198.9 | 6.16 |

(13 con-leche 1022 s, 14 pestka-wasm 1169 s, 17 eink0rn 1769 s, 19 ind-models, 20 tenet 2842 s, 21 tenet-hs 3212 s; ranks 22–27 have no mathlib result; 28–37 have wrong answers.) To take first place RLVM needs **< 394.9 G instructions on mathlib with zero wrong answers**; < 578 G is 4th.

### 1.4 Timeouts (seconds, from tests/**/*.yaml)

| test | s | test | s | test | s |
|---|---|---|---|---|---|
| mathlib | 4120 | navier-stokes-euler | 1840 | cslib | 1600 |
| con-leche | 920 | cedar | 640 | std | 600 |
| init | 370 | reachability | 220 | init-prelude | 40 |
| perf/magma-list-deep-n36 | 360 | perf/unfold-before-literal-fallback | 270 | perf/unfold-before-literal | 240 |
| perf/magma-list-pair-n21 | 240 | perf/ite-taken-branch | 230 | perf/proj-applied-struct | 230 |
| perf/short-circuit-and | 220 | perf/proj-applied-struct-bad | 130 | perf/unfold-before-literal-bad | 130 |
| perf/loop-unfolded-once-bad, proj-applied-struct-arg-bad, short-circuit-and-bad, magma-string-pair-n9 | 120 | perf/ite-taken-branch-bad | 110 | perf/app-lam, gf3-matrix-product, magma-list-deep-n21, magma-string-n4 | 70 |
| perf/magma-list-pair-n7, proj-app-lazy-delta | 60 | perf/grind-ring-5 | 50 | all other perf/ (24 tests) | 40 |
| corner-cases/eta-struct-major-no-whnf, -and-no-whnf | 60 | tutorial/*, bugs/*, other/*, other corner-cases | none | CI job limit | 360 min |

Derivation (`set-timeouts.py`): budget = 3 × Σ official wall time, distributed as `c·w^0.6` over `compare-perf` tests (`w` ≥ 1 s), rounded up to 10 s; ≈ 2× official for mathlib. They are re-derived from time to time; expect them to shrink when the official kernel gets faster.

### 1.5 Real-world corpora (all `outcome: accept`, `compare-perf`, `skip-on-ci`)

Record counts are exact: they come from checker output in results.json (pestka: "constants, declarations, names, levels, expressions"; tenet-jvm: per-kind counts; lazylean; kefir) and satisfy `lines = 1 + names + levels + exprs + decl records` for every row.

| test | bytes | lines | name recs | level recs | expr recs | decl recs | constants | axiom/def/thm/opaque/quot/ind-blocks | Lean | source |
|---|---|---|---|---|---|---|---|---|---|---|
| mathlib | 6,326,141,213 | 112,081,718 | 4,273,914 | 48,246 | 107,037,712 | 721,845 | 739,167 | 4/187,271/524,582/2,855/4/7,129 | 4.36.0 | mathlib4 `v4.36.0` fc4f59e5, module `Mathlib` |
| cslib | 2,500,956,112 | 44,051,112 | 2,349,377 | 12,713 | 41,287,036 | 401,985 | 413,186 | 4/119,982/274,986/2,636/4/4,373 | 4.36.0 | cslib e60b7760, module `Cslib` |
| navier-stokes-euler | 1,559,685,706 | 28,569,038 | 691,465 | 3,907 | 27,747,197 | 126,468 | 129,845 | 3/24,370/100,424/53/4/1,614 | 4.34.0-rc2 | 4 named theorems + deps |
| cedar | 904,722,064 | 15,913,292 | 1,037,701 | 1,029 | 14,728,503 | 146,058 | 151,772 | 13/60,089/82,625/1,371/4/1,956 | 4.36.0 | module `Cedar` |
| con-leche | 701,947,717 | 12,734,575 | 476,319 | 256 | 12,221,252 | 36,747 | 38,246 | 3/14,599/21,663/9/4/469 | 4.36.0 | 3 named theorems + deps |
| std | 584,315,500 | 10,611,507 | 580,893 | 953 | 9,928,514 | 101,146 | 103,885 | 4/27,944/71,751/397/4/1,046 | 4.36.0 | `import Std` |
| init | 347,772,063 | 6,495,296 | 299,085 | 571 | 6,137,092 | 58,547 | 59,979 | 4/15,239/42,451/244/4/605 | 4.36.0 | `import Init` |
| reachability | 19,031,110 | 361,766 | 30,662 | 73 | 326,617 | 4,413 | 4,846 | 3/1,687/2,532/2/4/185 | 4.34.1 | 6 `decide +kernel` theorems + deps |
| init-prelude | 3,811,816 | 65,406 | 7,491 | 113 | 55,961 | 1,840 | 2,121 | 3/1,536/161/10/4/126 | 4.36.0 | `prelude` + `import Init.Prelude` |

Axioms declared: mathlib, cslib, std, init: `propext`, `Quot.sound`, `Classical.choice`, `sorryAx`; init-prelude: `Quot.sound`, `Classical.choice`, `sorryAx`; navier/con-leche/reachability: the three standard ones; cedar: the three standard ones, `sorryAx`, and 9 `_private.Cedar.….<thm>._native.native_decide.ax_N_M`.
Average 53–57 bytes/line everywhere. Virtual seconds (instr/6e9) of leaders, same order as the table rows:

| test | nano-lean | gruszka | sokonanoda | rpylean | pestka | kefir | tenet-jvm | official |
|---|---|---|---|---|---|---|---|---|
| mathlib | 65.8 | 77.0 | 87.9 | 96.3 | 245.9 | 429.5 | 1633.1 | 2015.9 |
| cslib | 23.6 | 27.4 | 32.0 | 35.4 | 79.0 | 141.1 | 396.6 | 566.1 |
| navier-stokes-euler | 17.2 | 21.8 | 40.3 | 24.1 | 57.4 | 130.7 | 493.2 | 671.9 |
| cedar | 10.5 | 10.3 | 12.0 | 13.3 | 26.1 | 46.2 | 79.2 | 147.2 |
| con-leche | 10.3 | 9.2 | 11.5 | 11.9 | 20.3 | 47.2 | 120.6 | 216.2 |
| std | 6.7 | 6.7 | 8.1 | 9.2 | 18.0 | 32.4 | 60.2 | 110.9 |
| init | 4.1 | 4.1 | 4.8 | 5.4 | 10.9 | 18.2 | 37.3 | 66.9 |
| reachability | 1.9 | 2.4 | 4.3 | 4.3 | 4.4 | 3.4 | 21.4 | 23.5 |

nano-lean is at 3.2–4.9 k instructions per export line on the seven large corpora (mathlib 3.5 k, cslib 3.2 k, init/std 3.8 k, con-leche 4.9 k). Other parse-time evidence: lazylean (C++) loads mathlib in 12.8 s wall; con-leche (Lean) "parse 19.8 s, check 153.0 s (4 workers)"; nanobruijn reports "107,037,712 parser entries → 97,515,217 DAG entries" after its own shift-normalising interning. Slowest single mathlib declaration for con-leche: `_private.Mathlib.AlgebraicGeometry.EllipticCurve.Affine.AddSubMap.0.WeierstrassCurve.Affine.Point.sym2x_add_sub_eq_addSubMap_sym2x._proof_1_8` (3.05 s); lean4lean needs 48.8 s for `IntermediateField.algHomAdjoinIntegralEquiv_symm_apply_gen`.

---

## 2. The export format (format version 3.1.0) as actually produced

### 2.1 Which exporter

- `tests/lean-toolchain` = `leanprover/lean4:v4.36.0`. Since Lean v4.35.0 the toolchain ships a `leanexport` binary and the arena uses it (`lka.py:752-758`, `1390-1403`); only older toolchains build `leanprover/lean4export` master.
- Bundled source: `lean4-leanexport/src/LeanExport/Basic.lean` (writer), `…/Parse.lean` (reader used by `leanchecker --from-export`), `lean4/src/LeanExport.lean` (main). It is the standalone `lean4export/Export.lean` with one semantic difference: standalone HEAD skips `partial` definitions unless `--export-unsafe` (`lean4export/Export.lean:238`), bundled 4.36.0 skips only `isUnsafe` ones (`Basic.lean:255`). No visible effect on the corpora (§2.7).
- Invocation: `lake env leanexport <Module> [-- <decl>…] > out.ndjson` (`lka.py:776-781`); no options, so `Expr.mdata` is stripped and unsafe constants are skipped. `leanfile` tests compile the file as module `Test` in a throw-away lake project and export `Test`. Tutorial tests use their own driver around the same writer with `--export-unsafe` and optional renaming (`lean-kernel-arena/tutorial/Tutorial/Export.lean:127-176`).
- Header metadata of every test: exporter `lean4export` 3.1.0 (2 files: `handcrafted` 0.1.0), format 3.1.0. Lean versions seen: 4.36.0 (most), 4.35.0, 4.34.1, 4.34.0-rc2, 4.29.1; githash is a real hash, forty zeros, or the string `generated`.
- The output is deterministic and platform independent: generated on this aarch64 host it is byte-identical to the arena's x86-64 exports (§5).

### 2.2 File structure

```
line 1      {"meta":{"exporter":{"name":S,"version":S},"format":{"version":S},"lean":{"githash":S,"version":S}}}
lines 2..n  one record per line: name | level | expr | declaration
```
- Encoding UTF-8, every line ends with a single `\n` (no CRLF, no blank lines, final newline present) — verified on all files.
- Three independent id spaces: names (`in`), levels (`il`), expressions (`ie`). Implicit entries: **name 0 = anonymous**, **level 0 = zero**; neither is ever emitted. Expression ids start at 0.
- Written by `IO.println (json.compress)`; `Json.mkObj` builds a `Std.TreeMap String Json`, so **keys of every object are emitted in ascending code-point order** (`lean4-leanexport/src/Lean/Data/Json/Basic.lean:230-231`, `Printer.lean:167-232`). That is why the id key is not in a fixed position: `{"app":…,"ie":N}` but `{"ie":N,"lam":…}`.

### 2.3 Canonical byte templates (the fast path)

`N` = decimal natural without leading zeros; `L` = `[]` or `[N,N,…]`; `S` = JSON string as in §2.8; `B` = `true|false`. These regular shapes matched every line of init+std and of all exporter-generated test files.

```
names   {"in":N,"num":{"i":N,"pre":N}}                                          Name.num pre i
        {"in":N,"str":{"pre":N,"str":S}}                                        Name.str pre s
levels  {"il":N,"imax":[N,N]}   {"il":N,"max":[N,N]}   {"il":N,"param":N}   {"il":N,"succ":N}
                                                       (param → NAME id; others → LEVEL ids)
exprs   {"app":{"arg":N,"fn":N},"ie":N}                                         note: arg before fn
        {"bvar":N,"ie":N}                                                       de Bruijn index
        {"const":{"name":N,"us":L},"ie":N}                                      name id, LEVEL ids
        {"forallE":{"binderInfo":BI,"body":N,"name":N,"type":N},"ie":N}
        {"ie":N,"lam":{"binderInfo":BI,"body":N,"name":N,"type":N}}
        {"ie":N,"letE":{"body":N,"name":N,"nondep":B,"type":N,"value":N}}
        {"ie":N,"natVal":"<decimal digits>"}                                    string, any length
        {"ie":N,"proj":{"idx":N,"struct":N,"typeName":N}}                       field index, expr id, name id
        {"ie":N,"sort":N}                                                       level id
        {"ie":N,"strVal":S}
        {"ie":N,"mdata":{"data":{…},"expr":N}}                                  only with --export-mdata (never in the arena)
        BI = "default" | "implicit" | "strictImplicit" | "instImplicit"
decls   {"axiom":{"isUnsafe":B,"levelParams":L,"name":N,"type":N}}
        {"def":{"all":L,"hints":H,"levelParams":L,"name":N,"safety":"safe"|"unsafe"|"partial","type":N,"value":N}}
              H = "abbrev" | "opaque" | {"regular":N}
        {"opaque":{"all":L,"isUnsafe":B,"levelParams":L,"name":N,"type":N,"value":N}}
        {"quot":{"kind":"type"|"ctor"|"lift"|"ind","levelParams":L,"name":N,"type":N}}
        {"thm":{"all":L,"levelParams":L,"name":N,"type":N,"value":N}}
        {"inductive":{"ctors":[C,…],"recs":[R,…],"types":[T,…]}}                 note: ctors, recs BEFORE types
          C = {"cidx":N,"induct":N,"isUnsafe":B,"levelParams":L,"name":N,"numFields":N,"numParams":N,"type":N}
          R = {"all":L,"isUnsafe":B,"k":B,"levelParams":L,"name":N,"numIndices":N,"numMinors":N,
               "numMotives":N,"numParams":N,"rules":[{"ctor":N,"nfields":N,"rhs":N},…],"type":N}
          T = {"all":L,"ctors":L,"isRec":B,"isReflexive":B,"isUnsafe":B,"levelParams":L,"name":N,
               "numIndices":N,"numNested":N,"numParams":N,"type":N}
```
`levelParams`, `all`, `ctors` (inside T), `induct`, rule `ctor`, `typeName`, binder `name` are **name ids**; `us`, `sort`, `succ`, `max`, `imax` operands are **level ids**; `type`, `value`, `body`, `fn`, `arg`, `struct`, `rhs`, mdata `expr` are **expression ids**.

First-bytes dispatch (after `{"`): `ap`→app, `ax`→axiom, `b`→bvar, `c`→const, `d`→def, `f`→forallE, `ie`→ie-first expr (then the second key: `lam`,`letE`,`natVal`,`proj`,`sort`,`strVal`,`mdata`), `il`→level, `in"`→name, `ind`→inductive, `m`→meta, `o`→opaque, `q`→quot, `t`→thm.

### 2.4 Id spaces: what holds and what may be assumed

Writer logic (`Basic.lean:100-110`): look the object up in a `HashMap`; if absent, first emit its children (recursively), then assign `idx := map.size` and print. Hence for exporter output:

| property | exporter output | hand-crafted tests | official reader (`Parse.lean`) |
|---|---|---|---|
| ids dense, starting at 1 (names, levels) / 0 (exprs), strictly +1 in file order | yes (verified: init, std, 228 of 230 tarball files) | **no** — `other/sparse-name-index` (name id 2 without 1, expr id 4 without 0–3), `other/level-index-out-of-order` (level 2 defined before level 1); both are *accept* tests | ids are keys of `HashMap Nat _`; any natural |
| every referenced id is defined on an earlier line | yes, 0 forward references anywhere | yes in all tests | required: "Name/Level/Expr not found" otherwise |
| an id is bound once | yes | yes | required: "index N bound twice" (`Parse.lean:61-86`) |
| no two ids denote the same object | names: unique by structure; levels: unique by syntax (`max u v` ≠ `max v u`); exprs: unique modulo binder names and binderInfo | not guaranteed | not required |
| largest ids | mathlib: expr 107,037,711; name 4,273,914; level 48,246 → all fit in int32 | tiny | unbounded `Nat` |

Recommended contract: fast path asserts `id == next++` per space; on the first violation switch that space to a sparse table (id → slot; grow an array while the id is small, else a hash map); a reference to an undefined id or a rebinding = malformed input (official: uncaught exception, exit 1 = *rejected*).

Because expression lookup is by `BEq Expr = Expr.eqv` ("alpha equivalent, binder annotations ignored", `Expr.lean:803-811`), the record of a binder shows the binder name/info of its *first* occurrence only. `letE.nondep` is normalised to `false` before lookup (`Basic.lean:158-164`); all 5072+33,496 letE records seen have `nondep:false`. Binder names, binderInfo and `nondep` are irrelevant to checking and need not be stored.

### 2.5 Names, levels, expressions

- **Names** are hierarchical: `str(pre, s)` or `num(pre, i)`. A dot inside `s` is part of the component (`str(0,"a.b")` ≠ `a` then `b`); never identify names by a dotted rendering. `i` is a natural: observed up to 4,283,319,217 (> 2³¹; 9–10-digit values are common: 9687 ten-digit values in the tarball) → parse as `long`, with an overflow path. Longest component seen: 230 chars. In hand-crafted files two ids may denote the same name, so constants, level parameters and special names must be resolved by structural (interned) name, not by raw id.
- **Levels**: `succ`, `max`, `imax`, `param(name)`; `zero` only as id 0; `mvar` never. Level ids are deduplicated syntactically only. `const.us` lists level ids, one per universe parameter (must equal the constant's arity — `bugs/constlevels`, `large-elim-*`).
- **Expressions**: `bvar` (loose indices can legally appear in records, since subterms under binders are separate records; a declaration root must be closed — `bugs/loose-bvar-value`), `sort`, `const`, `app` (binary), `lam`, `forallE`, `letE`, `proj(typeName, idx, struct)`, `natVal`, `strVal`, `mdata` (never emitted without the flag; the official reader maps it to an empty-metadata `mdata` node). `fvar`/`mvar` cannot be exported (`panic!`).
- Before the first `natVal` the writer exports constant `Nat` (if not yet exported); before the first `strVal` it exports `Char.ofNat` and `String.ofList` (`Basic.lean:234-244`). So a declaration block can be emitted *in the middle of* the expression records of the declaration being written; "the records between two declaration lines belong to the second" is not exact.
- **Nat literals**: decimal string, arbitrary length; the official reader uses `String.toNat?` (`Parse.lean:237-241`). Longest in the available files: 20 digits (2⁶⁴ in `bugs/nat-shift-huge`); mathlib not inspected (must go through a bignum path beyond 18 digits). The 4.36 kernel refuses numerals above 128 MB (`LEAN_NAT_MAX_SIZE`, `lean4/src/kernel/type_checker.cpp:30-36`, checked in `infer_lit` and after `Nat.succ/add/sub/mul/pow/shiftLeft`), and requires the second argument of `Nat.pow`/`Nat.shiftLeft` to fit in 32 bits unless the result is trivially 0 (`type_checker.cpp:610-660`).
- **String literals** denote lists of Unicode scalar values: the kernel expands `"…"` to `String.ofList (List.cons.{0} Char (Char.ofNat n₁) … (List.nil.{0} Char))` with one `Char.ofNat` per **code point** (`lean4/src/kernel/inductive.cpp:1342-1390`, `type_checker.cpp:1100-1103`). `bugs/str-lit-utf8` (accept) and `bugs/str-lit-utf8-bad` (reject) test 2-, 3- and 4-byte characters. In Java: iterate code points, never `char`s or bytes.

### 2.6 Declarations

Field meaning = the fields of `Lean.ConstantInfo` (`Declaration.lean`); who must believe what is in §2.9.

- `axiom`: name, levelParams, type, isUnsafe.
- `def`: name, levelParams, type, value, `hints` (`"opaque"`, `"abbrev"`, `{"regular":h}` with `h` a UInt32 height; max seen 36), `safety`, `all` (names of the mutual block as elaborated; informational; length ≠ 1 for 4 defs in init, 6 in std).
- `thm`: name, levelParams, type, value, all.
- `opaque`: name, levelParams, type, value, isUnsafe, all (the official reader tolerates a missing `isUnsafe`, default false).
- `quot`: always the four records `Quot` (kind `type`), `Quot.mk` (`ctor`), `Quot.lift` (`lift`), `Quot.ind` (`ind`) in this order, preceded by the block of `Eq` (`Basic.lean:308-322`).
- `inductive`: one line per *mutual block*: all `types` in the order of `all`; all `ctors` grouped by type in that order, each type's constructors in `cidx` order; all recursors whose `all` contains the first type, **in arbitrary order** (iteration of a `NameSet`: e.g. `Lean.Syntax.rec_2, Lean.Syntax.rec, Lean.Syntax.rec_1`; `ImaxProp.rec` before `ImaxPropDummy.rec`). Auxiliary recursors of nested inductives (`X.rec_1`, …) are in the block of `X`; their rules name constructors of other types (`List.cons`, `Array.mk`). Recursors have their own `levelParams` (motive universe first when large elimination is allowed). Match recursors and constructors by name, never by position.

Observed flag frequencies (init / std): k=true recursors 6 / 6; reflexive types 4 / 4; nested (`numNested>0`) 1 / 3; mutual blocks 0 / 0 (one in the tests: `bugs/proj-of-imax-prop`); `isUnsafe` always false; `safety` always `safe`; hints init: 7203 regular, 7884 abbrev, 152 opaque.

### 2.7 Ordering guarantees

`dumpConstant` (`Basic.lean:252-429`) marks the constant visited, exports every constant used by its type and value (recursively), then writes its own names/levels/expressions and finally its declaration line. For an inductive block the dependencies of all types, constructors, recursor types and rule right-hand sides are exported before any record of the block.

- Verified with a script on init, std and every *good* tarball file: each constant referenced by a declaration is declared on an earlier line, except references inside one `inductive` line to members of the same block (types ↔ constructors ↔ recursors ↔ rules).
- The only violations in the whole tarball are four *bad* tests: `tutorial/014_selfProof` (self reference), `tutorial/140`, `141` (unsafe/partial self-recursive definitions) and `bugs/nested-name-capture` (a recursor rule naming a constant that is declared later).
- Top-level iteration order is that of `env.constants.toList` filtered by `!isInternal` (`Basic.lean:466`) — a hash-map order, not source order, but dependency-closed. With `-- decls` only those and their dependency cone are exported.
- Partial definitions (`safety: partial`) would be exported by the bundled 4.36.0 writer if reachable, but they are only the `_unsafe_rec` auxiliaries (internal names, referenced only by each other). Evidence that the corpora contain none: 0 occurrences of `"unsafe"`/`"partial"`/`_unsafe_rec` in init and std; con-ron/con-agua/con-leche decline any such definition at once and accept all corpora but cedar; sokonanoda declines them and accepts cedar.

Checking strictly in file order with "unknown constant ⇒ reject" is therefore complete for all current accept tests; the official checker is more liberal (§2.11).

### 2.8 Strings, escapes, numbers

Writer (`lean4-leanexport/src/Lean/Data/Json/Printer.lean:36-93`):
- `"` → `\"`, `\` → `\\`, U+000A → `\n`, U+000D → `\r`; any other code point < U+0020 → `\u00XX` (four lowercase hex digits); **everything ≥ U+0020 is written raw as UTF-8**, including U+007F and all non-ASCII. No `\t`, `\b`, `\f`, `\/`, no surrogate escapes.
- Observed in std (a superset of init): only `\"` (46), `\\` (45), `\n` (88), `\r` (42); raw non-ASCII on 1225 lines.
- Numbers are plain decimal naturals (no sign, fraction or exponent). Booleans are `true`/`false`. `null` never occurs.

Hand-crafted files use ordinary JSON: `\u03b1` occurs in `bugs/nested-name-capture` (name components `α`, `β` written by an ASCII-escaping JSON library). The fallback parser must implement all JSON escapes including surrogate pairs; a lone surrogate cannot be a Lean string (reject).

### 2.9 What a checker must not trust

| record / field | status | evidence |
|---|---|---|
| constructor records (`type`, `induct`, `cidx`, `numParams`, `numFields`, levelParams) | re-derive from the types' `ctors` lists and compare; reject any constructor record of a block that is not derived, or whose `induct` is not in the block | `bugs/ctor-num-fields`, `other/orphan-ctor`, tutorial 134, 138, 139 |
| recursor records (name, levelParams, `type`, `all`, `numParams/Indices/Motives/Minors`, `k`, `rules[].ctor/nfields/rhs`) | re-derive completely; compare structurally (official: `BEq`, i.e. alpha-equivalence ignoring binder names/info), **not** up to definitional equality; reject extra, misnamed or orphan recursors | `bugs/nat-rec-rules`, `unit-rec-rule`, `rec-rule-wrong-arg`, `rec-rule-proof-irrel`, `rec-rule-proof-irrel-nested`, `rec-type-proof-irrel`, `nested-rec-rule-nfields`, `rec-k-lie`, `nat-rec-k-lie`, `large-elim-param`, `large-elim-prop-bool`, `rec-missing-ih`, `orphan-rec`, `other/extra-rec`, tutorial 073, 135–138 |
| inductive `numIndices`, `isRec`, `isReflexive`, `numNested`, `isUnsafe` | recompute; the official checker never reads them from the export (Replay builds an `inductDecl` from names, types, constructor names/types, `levelParams` and `numParams` of the first type only) | `Replay.lean` (`.inductInfo` case); `corner-cases/vanishing-occurrence` notes exported `isRec = true` although the kernel finds no occurrence |
| inductive `numParams`, `levelParams`, `all`, `ctors` | *inputs* of the declaration: validate (binder count ≥ numParams, no duplicate/undeclared universe names, constructors exist) | tutorial 048–052, `other/inductive-undeclared-universe` |
| `quot` records | check against the four canonical signatures, or generate them and compare; `Eq` must exist with the right shape | `bugs/quot-bad-type` |
| `levelParams` of any declaration | no duplicates; every `param` used in type/value must be listed | tutorial 019, 048, `other/inductive-undeclared-universe` |
| `hints` | untrusted performance hint; influences only the unfolding order (the official kernel takes it as given) | — |
| `all` of def/thm/opaque, `isUnsafe`, binder names, binderInfo, `nondep` | ignore | — |
| `safety` | `unsafe`/`partial` definitions must not be usable by safe declarations | tutorial 140, 141 |
| name uniqueness across all declared constants, including generated `X.rec`, constructors, and any internal auxiliary names the checker invents | reject duplicates; never let a later declaration capture an auxiliary name | tutorial 132–139, `bugs/nested-name-capture` |
| header `meta` | the official reader discards line 1 unread (`Parse.lean:511-512`); use it only to decline unknown format versions | — |

### 2.10 Deviations in hand-crafted test files (complete list for the tarball)

| file | deviation |
|---|---|
| bad/bugs/large-elim-param | `": "` and `", "` separators on all 88 lines; id key first |
| bad/bugs/closure-level-subst, pi-closure-context (2 lines each), level-imax-leq (43), level-imax-normalization (44), nat-rec-rules (67), bad/other/inductive-undeclared-universe (23) | keys not sorted (`{"ie":3,"bvar":0}`, `{"ie":2,"const":{…}}`) |
| bad/bugs/nested-name-capture | `\u03b1`, `\u03b2` escapes (the names α, β) in two name strings |
| good/other/sparse-name-index, good/other/level-index-out-of-order | sparse / out-of-order ids; exporter name `handcrafted` |

All good tests except the last two are template-exact. The 30 `either` tests are not in the tarball; the four static ones in `lean-kernel-arena/tests/corner-cases/*.ndjson` are template-exact.

### 2.11 The official reader and checker (`leanchecker --from-export`)

`lean4/src/LeanChecker.lean` (`checkExport`), `Parse.lean`, `Replay.lean` (v4.36.0):

```
parse:   skip line 1 without parsing it;
         for every further line: Json.parse (blank line = error); dispatch on the key set, any key order;
         unknown key set / wrong field type / undefined or rebound id / natVal not a digit string → exception;
         every constant (also each type, ctor, rec of a block) goes into constMap; a name seen twice →
         "Duplicate declaration"                                   -- uncaught exception ⇒ exit code 1
replay:  remaining := all constants that are neither unsafe nor partial; Quot.mk/lift/ind are removed
         replayConstant(c): if c ∈ remaining: move to pending; first replayConstant every constant used
                            by c's type/value (dependency order, NOT file order); then
           def / thm / axiom / opaque → kernel addDecl
           inductive → addDecl(inductDecl lparams nparams [{name,type,ctors:[{name,type}]} for all types in `all`])
                       (lparams, nparams from the first type reached; the kernel generates ctors and recursors)
           ctor, rec  → only remembered
           quot       → replayConstant `Eq; addDecl quotDecl
         at the end: every remembered ctor/rec must equal (BEq) the kernel-generated constant of that name,
                     else "Invalid constructor/recursor" or "No such constructor/recursor"
post:    Quot, Quot.mk, Quot.lift, Quot.ind, if present in the export, must equal the kernel's
exit 0 "Lean default kernel accepts the solution"; exit 1 on any kernel error or exception
```
Notes: unsafe/partial constants are simply absent, so their users fail with "unknown constant". Forged recursors are often rejected at their first *use* (wrong universe arity or type), because the comparison runs last.

Behaviour confirmed by experiment with the local 4.36.0 `leanchecker` on edited copies of tutorial tests (files in /tmp, not kept):

| input change | official result |
|---|---|
| a theorem's declaration line moved to the end of the file, after the theorem that uses it | **accepted** (dependency replay; file order of declarations is irrelevant as long as ids are defined before use) |
| `"recs":[]` in the block of `Empty` while a later definition uses `Empty.rec` | **accepted** (the kernel-generated recursor exists whether or not the export lists it) |
| garbage text as line 1 | accepted (line 1 is never parsed) |
| no meta line (line 1 is the first record) | exit 1, "Name not found 1" (the first record is swallowed) |
| blank line in the middle | exit 1, "Invalid JSON" |
| missing final newline; CRLF line ends | accepted |
| the same name record twice (id rebound) | exit 1, "Name index 1 bound twice" |

No arena test exercises the first two rows today (every good test is in dependency order and lists all recursors), but an exact emulation needs: (1) a constant table of the whole file before deciding "unknown constant" — on a reference to a constant declared later, check that one first (cycle ⇒ reject); a purely streaming checker should decline rather than reject in that situation; (2) generated constructors/recursors must be usable even when the export omits their records.

### 2.12 Measured statistics

Scripts were written for this report (JSON-parsing every line; kept outside the repository). "tarball" = all 230 files.

| quantity | init (347.8 MB) | std (584.3 MB) | tarball max / remark |
|---|---|---|---|
| lines | 6,495,296 | 10,611,507 | 1,034,955 total |
| `app` | 5,228,615 (80.50 % of lines, 72.1 % of bytes, 48.0 B avg, max 50 B) | 8,090,565 (76.24 %, 66.5 %) | 62.2 % of lines |
| `lam` | 531,743 (8.19 %, 13.1 % of bytes, 85.8 B avg) | 1,062,503 (10.01 %, 15.7 %) | 11.9 % |
| `forallE` | 302,481 (4.66 %, 7.8 %, 89.3 B) | 639,125 (6.02 %, 9.9 %) | 8.5 % |
| `const` | 57,535 (0.89 %) | 100,998 (0.95 %) | 2.5 % |
| `letE` / `proj` / `strVal` / `natVal` / `sort` / `bvar` | 11,380 / 2,948 / 1,837 / 285 / 121 / 147 | 22,116 / 9,329 / 3,055 / 453 / 223 / 147 | bvar records = max index + 1 |
| name.str / name.num | 174,019 / 125,066 (4.6 % of lines, 4.1 % of bytes) | 322,169 / 258,724 (5.5 %) | 11.0 % |
| levels (succ/max/imax/param) | 90 / 269 / 150 / 62 | 146 / 493 / 181 / 133 | — |
| thm / def / inductive / opaque / axiom / quot lines | 42,451 / 15,239 / 605 / 244 / 4 / 4 | 71,751 / 27,944 / 1,046 / 397 / 4 / 4 | declaration lines: 0.9 % of lines, 1.7 % of bytes |
| longest line | 3,272 B (an `inductive`) | 10,847 B (an `inductive`) | 2,372 B; longest non-inductive line in init/std: 262 B (a `strVal`); names ≤ 182 B |
| line length | 85.7 % ≤ 55 B, 99.35 % ≤ 95 B | 82.3 % ≤ 55 B, 98.95 % ≤ 95 B | — |
| max DAG depth of an expression (children + 1) | 325 | 325 | **19,999** (`perf/app-lam`), 6,004 (`beta-ladder`, `let-ladder`), 1,325 (`magma-list-deep-n36`), 1,129 (`grind-ring-5`) |
| max binder nesting / max app spine length | 147 / 99 | 147 / 99 | max bvar index 3,999 (`app-lam`) |
| app nodes by spine position 1/2/3/4/5/6/≥7 | 11.8/22.2/21.8/15.5/11.8/11.4/5.6 % | 11.8/21.3/20.8/15.3/11.9/11.0/8.0 % | — |
| app: `fn` is the previous record / `arg` is the previous record | 37.6 % / 36.8 % | 40.9 % / 35.1 % | distance ≥ 9: 48.6 % / 58.5 % |
| expr in-degree 1 / 2 / 3 / 4 / ≥5 | 83.2 / 8.8 / 3.2 / 1.7 / 3.1 % (max 191,358) | 83.9 / 8.6 / 3.0 / 1.6 / 2.9 % (max 322,960) | references from records and declaration roots |
| expr records introduced by a theorem and reachable only from its **value** | 82.6 % | 78.6 % | "segment" = records since the previous declaration line |
| … by a theorem, reachable from its type | 7.5 % | 10.3 % | |
| … by a definition: value-only / type | 7.2 % / 2.0 % | 8.0 % / 2.2 % | |
| expr records referenced by any later declaration | 6.17 % | 6.33 % | i.e. ~94 % die with their declaration |
| proof-only records that a later declaration nevertheless references | 250,630 (4.9 % of them) | 393,456 (5.0 %) | so proof subterms cannot be dropped blindly |
| new expr records per declaration: median / p90 / p99 / p99.9 / max | 27 / 211 / 1,241 / 5,451 / 26,418 | 31 / 181 / 1,140 / 5,444 / 66,890 | — |
| duplicates modulo binder names (exprs) / levels / names | 0 / 0 / 0 | 0 / 0 / 0 | 0 in every file |
| `us` list length max; levelParams max; `regular` hint max | 7; 7; 31 | 7; 7; 36 | — |

Not measured (file not available locally): the same breakdown for mathlib. Extrapolating the init/std shares to mathlib's exact totals gives roughly 82–86 M `app`, 9–11 M `lam`, 5–6.5 M `forallE` records; treat as an estimate.

### 2.13 Byte-level parsing advice

What a single-pass hand-written parser may assume **on the fast path only** (and must verify byte-by-byte, falling back otherwise): fixed key order, no whitespace, `\n` terminators, decimal ids without leading zeros, `id == next expected id`, referenced ids `< next`. What it may never assume: density/order of ids, absence of escapes, key order, line length bounds (an `inductive` line grows with the block; allow arbitrarily long lines), 32-bit `i`/`natVal`/`idx`.

```
buf: one reusable byte[] of a few MiB filled by read(); carry an incomplete tail line to the front.
     (A mapped 6.3 GB file would count towards max RSS while resident, as Linux counts file-backed pages; plain reads do not.)
per line, p at '{':
  switch on buf[p+2], buf[p+3]:
    "ap": match `{"app":{"arg":` A `,"fn":` F `},"ie":` N `}\n`      → 2 ints + check N == nextExpr
    "ie": match `{"ie":` N `,"`; then
            `lam":{"binderInfo":"` → skip to the closing quote; `,"body":` B `,"name":` (skip digits) `,"type":` T `}}\n`
            `letE":{"body":` B `,"name":` skip `,"nondep":` skip `,"type":` T `,"value":` V `}}\n`
            `proj":{"idx":` I `,"struct":` E `,"typeName":` P `}}\n`
            `sort":` L `}\n`      `natVal":"` digits `"}\n`      `strVal":` string `}\n`
    "fo": `{"forallE":{"binderInfo":"` skip `","body":` B `,"name":` skip `,"type":` T `},"ie":` N `}\n`
    "co": `{"const":{"name":` P `,"us":[` ints `]},"ie":` N `}\n`
    "bv": `{"bvar":` I `,"ie":` N `}\n`
    "in" + '"': `{"in":` N `,"num":{"i":` I `,"pre":` P `}}\n`  |  `{"in":` N `,"str":{"pre":` P `,"str":"` … `"}}\n`
    "il": the four level shapes
    anything else (thm, def, inductive, opaque, axiom, quot, meta) or ANY mismatch above:
          hand the whole line to a generic JSON object parser (any key order, whitespace, all escapes)
          and build the same record from the parsed object. That path sees ≤ 1 % of the lines.
```
- app + lam + forallE + names + const + letE + proj cover 99.1 % of the lines of init and std.
- Strings on the fast path: scan to the closing `"`; if a `\` is met, either decode the four writer escapes or fall back. Keep name components and string literals as UTF-8 byte ranges in a side pool; a `java.lang.String` is needed only for diagnostics. Kernel-special names (`Nat`, `Nat.add`, …, `String.ofList`, `Char.ofNat`, `Quot`, `Eq`, `eagerReduce`, …) are found by comparing interned name structure once.
- Do not store binder names, binderInfo, `nondep`, or `all`/`isUnsafe` of non-inductive declarations.
- Integer parsing: ids have ≤ 9 digits on mathlib; nano-lean parses 8 digits per 64-bit word (SWAR) and matches literal key fragments by slice comparison, with a JSON fallback (`nano-lean-v0.4.2/src/import/scan.rs:57-140`, `…/json.rs`).
- Since ids are sequential, the id field of a record carries no information on the fast path; it only has to be compared with the counter.
- Budget: 6.33 GB at k instructions/byte = 6.3·k G instructions; at the leader's total of 62 instr/byte, parsing should stay near or below 10 instr/byte (≈ 500 instructions per `app` line).
- Storage options given §2.12: (a) keep all expression records in flat `int` arrays (app = 2 ints; roughly 1–1.5 GB for mathlib) and never free; (b) check each declaration when its line arrives and recycle the records introduced since the previous declaration — only sound with last-use information, because ~5 % of proof-only records are referenced by later declarations (a pre-pass over the file doubles the scan). What the leaders say about themselves (checker descriptions in `lean-kernel-arena/checkers/*.yaml`; their code was not examined for this report): kefir "Declarations are checked as they stream in and their terms are freed once checked"; gruszka/pestka convert the export to the binary Susz form first (`susz pack`, inside the measured command) and read a theorem's proof block only when the theorem is checked; palladium "The export is parsed in parallel as it is read, in bounded pieces, so the file is never in memory as a whole".
- Expression depth: recursion over records must not use the Java stack naively (`app-lam` nests 19,999 deep; nanoda needed its thread stacks raised to 256 MiB for some tests; palladium fails six perf tests with "recursion depth exceeded", logos-kernel declines mathlib for the same reason).

---

## 3. Test catalogue (270 tests)

| group | accept | reject | either | in tarball | what it is |
|---|---|---|---|---|---|
| top level | 9 | – | – | init-prelude only (others > 10 MB) | real corpora, §1.5 |
| tutorial/ | 94 | 47 | – | all | one feature per test, generated from `tutorial/Tutorial.lean` |
| bugs/ | 2 | 32 | – | all | soundness bugs/crashes actually found in some checker |
| other/ | 5 | 5 | – | all | synthetic single-check tests |
| corner-cases/ | 1 | – | 30 | 1 (either tests are excluded, `lka.py:2287-2290`) | debatable outcomes |
| perf/ | 37 | 8 | – | 43 (two string-magma tests are 17 MB) | small inputs with algorithmic traps |

"Official 4.36.0" below = message of the local `leanchecker --from-export` run for this report (all 92 bad tests: exit 1).

### 3.1 tutorial/ — accept tests (94)

001–008 definitions, arrows, Π, λ, β (also under binders), binder domain needing whnf to be a sort; 013 theorem using a theorem; 015–027 universe arithmetic: `imax 1 0`, `imax 0 1`, `imax 2 1`, level parameters, `imax u 0`, `imax u u`, typing of Π via imax, `max` commutative/associative/idempotent/absorbing; 028–029 local variables, defeq of λs; 030–032 Church/Peano arithmetic by conversion; 033–035 `let` typing (dependent) and ζ; 036–045 inductive types: `Empty`, `Bool`, structures, `And`, `Prod`, `PProd`, `PUnit`, `Eq`, `N`, indexed `RBTree`; 055 constructor field types are reduced (whnf) when checking; 058–060 universe of fields (Prop may hold anything; `Type u` field in `Type (u+1)`); 062–072, 074 recursor *types* (Empty, Bool, structure, And, Prod, PProd, PUnit, Eq, N, RBTree, 2-constructor Prop → Prop-only elimination, `Exists`); 075 recursor of a one-constructor field-less type reduces on a variable major (structure eta on the major); 076–077 subsingleton elimination when data fields occur in the result type syntactically (and not after reduction); 078–082 ι-reduction (Bool, Prod, N, List, RBTree); 083–086 projection functions (`And`, `Prod`, `PProd`, `PSigma`); 089, 091 projections of proof fields out of a `Prop` structure before any dependent data field; 095 recursor of an indexed `Prop` family eliminating into `Sort`; 098 projection reduction; 099 rule K for `Eq`; 102–103 Nat literal typing and `3 ≡ succ (succ (succ zero))`; 104, 106, 107 proof irrelevance (also when the type only reduces to `Prop`, and between applications); 108–110 unit eta; 112 structure eta; 114–115 function eta; 119–122 reflexive inductives (positivity after reduction, recursor type, ι); 123–124 `Acc.rec` type and ι; 126–131 `Quot` signatures and reductions.

Three of them contain user axioms (034, 035, 107; and reject test 047): the con-leche family declines those.

### 3.2 tutorial/ — reject tests (47)

| test | why it is invalid | check that catches it (official 4.36.0 message) |
|---|---|---|
| 002_badDef | `def badDef : Prop := Type` | value type vs declared type ("declaration type mismatch … has type Type 1 … expected Prop") |
| 009_forallSortBad | Π whose third binder domain is a proof variable | domain's type must whnf to a sort ("type expected x") |
| 010_nonTypeType | definition whose "type" is the function `constType` | declared type must be a type ("type expected constType") |
| 011_nonTypeAxiom | same for an axiom | axioms need the same check |
| 012_nonPropThm | theorem whose statement `Prop` has sort `Type` | theorem type must be a proposition ("type of theorem … is not a proposition") |
| 014_selfProof | theorem's value is the theorem itself | constant not in the environment while being checked ("unknown constant") |
| 019_tut06_bad01 | `levelParams = [u, u]` | duplicate universe parameter |
| 046_inductBadNonSort | inductive whose type is a λ | "type expected" |
| 047_inductBadNonSort2 | inductive whose type is the axiom `aType : Type` | type must end in a `Sort` ("type expected aType") |
| 048_inductLevelParam | inductive with `[u, u]` | duplicate universe parameter |
| 049_inductTooFewParams | `numParams = 2`, type has one binder | "incorrect number of parameters" |
| 050_inductWrongCtorParams | constructor parameter of type `Type` where the inductive's is `Prop`; result `I aProp` | constructor telescope must start with the block's parameters and return `I params…` ("invalid occurrence of datatype … must be applied to the parameters and universe levels") |
| 051_inductWrongCtorResParams | result `I y x` (parameters swapped) | same |
| 052_inductWrongCtorResLevel | result `I.{u2,u1} x y` | same (universe arguments must be the declared ones in order) |
| 053_inductInIndex | result `I (I aProp)`: the type inside an index | "invalid return type" |
| 054_indNeg | `mk : (I → I) → I` | strict positivity ("non positive occurrence") |
| 056_reduceCtorType.mk | constructor type `id Type I` (not a manifest Π/application) | constructor type as a whole is *not* reduced ("invalid return type") |
| 057_indNegReducible | field `(constType aType I → I)`; the occurrence would vanish by reducing inside the domain | only whnf of the field type; inner occurrence is negative |
| 061_typeWithTooHighTypeField.mk | `T : Type` with a field `Type` | field universe ≤ inductive universe ("universe level … is too big") |
| 073_BogusRecursor | recursor record claims type `False`, used to prove `False` | regenerate recursors ("incorrect number of universe levels parameters for 'BogusRecursor.rec', #1 expected, #0 provided") |
| 087_projOutOfRange | `z.3` on `And` | field index range ("invalid projection") |
| 088_projNotStruct | projection out of `N` (two constructors) | structure-likeness ("invalid projection") |
| 090, 092_projProp2/4 | data field out of a `Prop` structure | projected field of a proposition must be a proof |
| 093_projProp5 | proof field whose type depends on an earlier data field | walking past a data field that later fields depend on is forbidden |
| 094_projProp6 | proof field located after such a dependent data field | same |
| 096, 097_projIndexData(2) | `x.[PropStructure#i]` where `x : ProjDataIndex n` (and the intended point: data stored in a `Prop` family) | projection's type name must be the head of the struct's type; Prop rule ("invalid projection") |
| 100_ruleKbad | `Eq.rec a (h : true = false) = a` by `rfl` | K only when the major's type matches the constructor's ("declaration type mismatch") |
| 101_ruleKAcc | `Acc.rec … h = a` by `rfl` | `Acc` is not K-like (its constructor has fields) |
| 105_proofIrrelevanceBad | `h1 = h2` for `h1 h2 : p`, `p : Type` | proof irrelevance only in `Prop` |
| 111_indexedUnitEta | `x = y` for `x y : IndexedUnit true` | unit-like eta not for indexed families |
| 113_indexedStructEta | `IndexedSingleton.mk True.intro = x` | structure eta not for indexed families |
| 116_funEtaBad | `(fun x => f (g x)) = f` | eta must compare bodies |
| 117_reflOccLeft | field `Nat → (I → Nat)` | positivity behind arrows |
| 118_reflOccInIndex | field `Nat → I (I α)`; as exported the constructor type is also ill-typed | positivity / type check of the constructor ("application type mismatch") |
| 125_accRecNoEta | `Acc.rec … (h : Acc r a) = p` by `rfl` | no structure eta for `Acc` majors |
| 132_dup_defs | two definitions named `dup_defs` | duplicate constant name ("Duplicate declaration", raised by the reader) |
| 133_dup_ind_def | definition named like an inductive type | same |
| 134_dup_ctor_def | definition named like a constructor | same |
| 135_dup_rec_def | definition named like a recursor | same |
| 136_misnamed_rec_user | recursor exported as `misnamed_rec.not_rec`, used by a definition | recursors are generated, named `<type>.rec` ("unknown constant 'misnamed_rec.not_rec'") |
| 137_dup_rec_def2 | misnamed recursor and a definition taking `dup_rec_def2.rec` | generated name collides ("constant has already been declared") |
| 138_dup_ctor_rec | constructor named `X.rec` | duplicate name |
| 139_DupConCon | two constructors with one name | duplicate name |
| 140_falseFromUnsafe | theorem `False := unsafeLoop` (`safety: unsafe`) | unsafe constants are not visible to safe ones ("unknown constant 'unsafeLoop'") |
| 141_falseFromPartial | theorem `False := partialLoop` (`safety: partial`) | same for partial |

Top-checker policy on 140/141: nano-lean, gruszka, rpylean, pestka, kefir, palladium, nanoda, nanobruijn, tenet-jvm, official reject; sokonanoda and the con-leche family decline (allowed, but one more decline).

### 3.3 bugs/ (2 accept, 32 reject)

| test | forged / exploited | required check; where official 4.36.0 stops | accepted this bug |
|---|---|---|---|
| closure-level-subst | `lower : Family.{0} := good` with `good : Family.{1}`; two closures over the same body differing only in the universe substitution | closure/cache keys must include universe instantiation ("declaration type mismatch 'lower'") | sokonanoda, mathgraph |
| constlevels | constant used with the wrong number of universe arguments | arity check at every `const` ("let-declaration type mismatch") | UB in official `lazy_delta_reduction_step` (lean4#10577) |
| ctor-num-fields | constructor `numFields = 0` for a 1-field structure → fake unit-like eta | recompute numFields | nanoda family |
| eta-ctor-alias | `f = Box.mk` for `f : BoxFn` (alias of `Bool → Box`) | if eta-expanding partially applied constructors, take arity from the constructor's type, not from the un-unfolded other side | draft of lean4#15373 |
| k-rec-conv | `fun x => x` vs `fun _ => y` under K | K-reduction must not make unrelated minors convertible | sokonanoda |
| large-elim-param | `MyBool.{u} : Sort u` with a large-eliminating recursor | "level is never zero" must be false for a bare parameter; regenerate recursor ("incorrect number of universe levels parameters for 'MyBool.rec'") | mini |
| large-elim-prop-bool | two-constructor `Prop` with a `Type`-style recursor | regenerate recursor | kiota |
| level-imax-leq | `imax u v + 1 ≤ imax u v` | level `≤` must carry the successor offset through `imax` | nanoda |
| level-imax-normalization | `imax 0 v` vs `succ (imax 0 v)` | normal form must keep the offset | — |
| loose-bvar-value | definition value `#0` (loose), reached through an inductive that is checked first | declaration roots must be closed; reject before unfolding ("type checker does not support loose bound variables") | pestka hung; sokonanoda errors (exit 3) today |
| nat-rec-k-lie | `Nat.rec` marked `k = true` | recompute `k` | nanoda family (variant) |
| nat-rec-rules | wrong `Nat.rec` succ rule | compare rules with derived ones, not with themselves | nanoda |
| **nat-shift-huge (accept)** | `Nat.shiftRight 42 (2^64) = 0`, `Nat.shiftLeft 0 (2^64) = 0`, closed and under `Nat.rec` | shift counts ≥ 2⁶⁴ without overflow/OOM | rpylean crashed; today: nano-lean declines (11 GB, budget), sokonanoda panics, tenet-jvm OOM after 366 s, 5 checkers reject |
| nested-name-capture | rule of `Shadow.rec` calls `_nested.Cov_1.rec`; a later definition takes that name | auxiliary names of nested-inductive elaboration must be uncapturable; rules compared with derived ones | nanoda, nanoclo, nanobruijn, sokonanoda (earlier); mathgraph, metalean, nanoclo-fortran today |
| nested-rec-rule-nfields | `nfields = 1` instead of 2 in a rule of a nested inductive's recursor | recompute nfields, also for nested | nanoda (earlier); 4 checkers today |
| nested-unused-param | ill-typed argument `b` in a nested occurrence `L (E w) b` that disappears in the auxiliary type | type-check nested applications' parameters ("invalid projection w.1") | official (lean4#14576) |
| open-term-cache-context | shared open subterm `#1 #0` under binders of different types | infer-cache for open terms must depend on the binder types ("application type mismatch f y") | ocaml4lean branch |
| orphan-rec | extra recursor `rogue : False` with `all = []` | reject every recursor not derived ("unknown constant 'rogue'") | nanoda |
| pi-closure-context | `bad : TrueTy → FalseTy := fun _ => FalseTy` | do not confuse "closure that evaluates the body" with "closure that infers its type" | sokonanoda, mathgraph |
| proj-non-structure | projection out of a two-constructor type | structure check | nanoda family |
| proj-of-imax-prop | data projection out of `Sort (imax 1 0)` | Prop test after level normalisation ("invalid projection p.1") | official (lean4#14613) |
| proj-of-stuck-prop | family whose sort is `Gate.rec … Prop h`, Prop only via a hash-dependent defeq cache | result sort must reduce to a sort ("type expected"); no order-dependent transitivity in caches | official ≤ nightly-2026-08-01 |
| proj-of-subst-prop | same projection reached by substituting a proof of a defeq proposition | `is_prop` must fail hard on a stuck sort (lean4#14807) | official |
| quot-bad-type | `Quot` record with type `False` | validate/generate quot signatures | rpylean; con-ron/con-agua decline |
| rec-k-lie | two-constructor type with `k = true` | recompute `k` | nanoda family |
| rec-missing-ih | recursor whose `step` rule drops the IH (hash-collision dependent) | derive independently; positivity error on `step` | official ≤ nightly-2026-08-01 |
| rec-of-subst-prop | `Prop` family with a `Type` field, eliminated by its own recursor | reject the stuck result sort ("type expected Issue.resultSort Issue.gateWitness") | official |
| rec-rule-proof-irrel | rule rhs replaced by an ill-typed proof | structural comparison of rules (not defeq) | lazylean, mathgraph, nanoclo, nanoclo-fortran today |
| rec-rule-proof-irrel-nested | same in an auxiliary recursor (`N.rec_1`) | same, also for auxiliary recursors | **7 checkers today — the most-failed test** |
| rec-rule-wrong-arg | rule `mk true` instead of `mk b` | derive rules | — |
| rec-type-proof-irrel | recursor *type* with an ill-typed proof inside | structural comparison of recursor types | lazylean today |
| **str-lit-utf8 (accept)** | `"é€😀" = String.ofList [Char.ofNat 0xE9, 0x20AC, 0x1F600]` | code points, not bytes | rpylean rejected; 3 checkers reject today |
| str-lit-utf8-bad | `"é" = String.ofList [0xC3, 0xA9]` | same | rpylean |
| unit-rec-rule | `PUnit.rec` rule returns `PUnit.unit` instead of the minor | derive rules also for field-less constructors | sokonanoda (earlier); mathgraph, nanoclo-fortran today |

### 3.4 other/ (5 accept, 5 reject)

| test | outcome | content |
|---|---|---|
| sparse-name-index | accept | `axiom foo : Prop` with name id 2 and expr id 4 (nanoda and the con-leche family decline) |
| level-index-out-of-order | accept | `axiom foo : Sort 2` with level 2 defined before level 1 |
| nat-lit-add / -sub / -ble | accept | `Nat.add 123456789 987654321 = 1111111110`, `Nat.sub 1000000 1000001 = 0`, `Nat.ble 1000000 1000001 = true` by `rfl`: literal arithmetic is mandatory (applications of `Nat.add` to literals, not `HAdd`) |
| nat-lit-add-bad | reject | `… = 1111111111` with proof `Eq.refl` |
| extra-rec | reject | block of `False` plus a second recursor `rogue : False` |
| orphan-ctor | reject | block of `False` plus a constructor `rogue : False` with `induct = Orphan` (undeclared) |
| inductive-undeclared-universe | reject | `inductive U : Sort u` with `levelParams = []` ("invalid reference to undefined universe level parameter 'u'"); accepted today by mathgraph, nanoclo-fortran, vow |
| proj-of-prop | reject | `(Wrapper.mk True.intro).p : False` — the structure argument of a projection must be type-*checked*, not just inferred |

### 3.5 corner-cases/ (1 accept, 30 either)

`proof-param-ok` (accept): inductive with two proof parameters used in declared order.
Answers: O35 = official v4.35.0 (the ranked "official"), ON = official-nightly 2026-11-14; then nano-lean, gruszka, sokonanoda, rpylean, pestka, kefir; last column = accepted/rejected among all 37. A accept, R reject, T timeout, D decline.

| test | O35 | ON | nl | gr | so | rp | pe | ke | A/R | what decides |
|---|---|---|---|---|---|---|---|---|---|---|
| alg-conv-trans-acc | R | R | R | R | R | R | R | R | 0/35 | needs inventing a proof of `Acc` (conversion not transitive) |
| alg-conv-trans-acc-left / -right | A | A | A | A | A | A | A | A | 32/3, 34/1 | each half is checkable |
| alg-conv-trans-quot | R | R | R | R | R | R | R | R | 0/37 | same for `Quot` of propositions |
| alg-conv-trans-quot-left | R | R | R | R | A | R | R | R | 5/32 | whnf first reduces one side; comparing arguments first would accept |
| alg-conv-trans-quot-left-def / -right | A | A | A | A | A | A | A | A | 33/4, 37/0 | — |
| eta-ctor | R | A | R | R | R | R | R | R | 7/29 | η for partially applied constructors (lean4#15373) |
| eta-rec-struct, eta-rule-k | R | R | R | R | R | R | R | R | 2/35 | η for recursors missing the major (lean4#15374) |
| eta-struct-major-and | R | A | R | A | A | A | A | A | 19/18 | structure eta on a `Prop` major (`And`) |
| eta-struct-major-no-whnf, -and-no-whnf (60 s) | T | A | D | A | T | T | A | A | 14/4, 19 other | eta-expand the major *before* reducing it; otherwise non-termination |
| eta-unit-like-nested, -pi, -proof-field, -struct | R | A | R | R | R | R | R | R | 5/31–32 | wider unit-like eta (lean4#15372) |
| imax-nested-absorption | R | R | A | R | A | A | R | R | 21/16 | complete level equality vs official normalisation |
| imax-right-successor | R | R | A | R | A | A | A | R | 24/13 | `imax u (v+1) = max u (v+1)` |
| large-elim-maybe-prop | R | R | R | R | R | R | R | R | 3/33 | large elimination for `Sort u` box (official: recursor arity mismatch) |
| let-value-type-mismatch | R | R | R | R | R | R | R | R | 1/36 | check the `let` annotation ("let-declaration type mismatch") |
| nested-nonuniform-param | R | R | A | A | R | R | R | R | 18/17 | parameter of a nested occurrence must be the block's parameter |
| nested-redex-aux | A | A | A | A | R | A | A | A | 32/2 | nested occurrences are collected syntactically (two auxiliaries) |
| positivity-whnf | R | R | R | R | R | R | R | R | 1/35 | occurrence hidden in an unused argument inside an arrow domain |
| proj-maybe-prop, -past | A | A | A | A | A | A | A | A | 26/10 | projection out of `MaybeProp.{u} : Sort u` |
| proof-param-swap | R | R | R | R | R | R | R | R | 0/36 | swapped proof parameters in a constructor result |
| subject-reduction-redex | A | R | A | A | A | R | A | A | 25/10 | annotation supplies the middle term |
| subject-reduction-reduct | R | R | R | R | R | R | R | R | 0/35 | — |
| vanishing-occurrence | A | A | A | A | A | A | A | R | 31/5 | occurrence removed by whnf of the field type |

The nightly already accepts the eta family (`eta-ctor`, `eta-struct-major-*`, `eta-unit-like-*`) and rejects `subject-reduction-redex`; these tests may become `accept` when the arena's official checker and toolchain move on. Imitating O35 is safe today; the algorithm should leave room for the nightly's eta rules, in particular "eta-expand a structure major before whnf", which is the only way not to loop on the two `no-whnf` tests.

### 3.6 perf/ (37 accept, 8 reject)

Virtual seconds (instr/6e9); E = error, T = timeout. tenet-jvm's floor is ≈ 0.11 (JVM start-up).

| test | to | official | nano-lean | gruszka | tenet-jvm | the algorithmic question |
|---|---|---|---|---|---|---|
| app-lam | 70 | 4.87 | 0.01 | 0.08 | 11.70 | n levels of `c (λ…) (λ…)` with shared arguments, depth 19,999: infer cache on shared subterms (else 2ⁿ) and no O(n) substitution per binder (else n²) |
| args-before-unfold | 40 | 0.04 | 0.00 | 0.00 | 0.26 | `count #n` vs `count (N.add #(n-1) #1)`: compare arguments of an equal head before unfolding (Θ(n) vs Θ(n²)) |
| beta-ladder | 40 | 1.67 | 0.00 | 0.04 | 2.23 | 2000 nested β-redexes over one body: environment machine Θ(n) vs substitution Θ(n²) |
| church-numerals | 40 | 0.05 | 0.00 | 0.01 | 0.33 | `cmul (cnum n) (cnum (n+1))` both ways, 14,520 β steps under binders |
| discarded-argument | 40 | 0.17 | 0.00 | 0.00 | 0.46 | `dropArg (count #n)` vs `dropArg (count #(n+1))`: unfold a head that ignores its argument before comparing arguments |
| discarded-argument-match | 40 | 0.15 | 0.00 | 0.00 | 0.75 | same through `brecOn`/`below` tables that are built and never read |
| folded-constant-first / -last | 40 | 0.05 / 0.04 | 0.00 | 0.00 | 0.28 | `tagged (count #n)` vs a pair: a constant unfolded for one component should not have to be re-evaluated for the other |
| fueled-chain | 40 | 0.13 | 0.02 | 0.01 | 0.77 | monadic programs differing in the instance; proof irrelevance on `Subtype` proofs doubles the work per guard unless equal terms are recognised early (nanobruijn: 256 s at N = 12) |
| gf3-matrix-product | 70 | 1.82 | 0.83 | 2.44 | 9.94 | 781×781 GF(3) matrices as 609,961-bit `Nat` literals: bignum shift/and/mul/xor speed; caches must not retain every intermediate (official 3.5 GiB); hash of big literals |
| grind-ring-5 | 50 | 2.18 | 0.22 | 0.25 | 4.13 | one large `grind` proof term (203 k lines) |
| identical-nesting | 40 | 0.03 | 0.00 | 0.00 | 0.11 | identical terms `f4 (f4 (…))`: test syntactic/pointer equality before unfolding (else exponential) |
| irrelevance-before-evaluation | 40 | 0.03 | 0.00 | 0.00 | 0.12 | `slowTriv (count #n) = True.intro`: proof irrelevance before reducing proofs |
| irrelevance-refutes | 40 | 0.03 | 0.00 | 0.00 | 0.12 | proofs of non-defeq propositions are not defeq: take irrelevance's negative answer too instead of unfolding `Nat.rec` proofs |
| ite-taken-branch | 230 | 19.41 | 0.04 | 0.05 | 17.88 | `(if true = true then piA else tA)` vs `piA`: unfold the cheap `ite`/`Decidable` side although the other constant is "higher" |
| ite-taken-branch-bad (reject) | 110 | 6.77 | 0.27 | 0.62 | 7.86 | `if false = true …`: must decide the condition; refuting may evaluate `piA` (rpylean 27.6) |
| let-fold | 40 | 0.06 | 0.00 | 0.01 | 2.25 | `let d := bits #16 0; d + 0 = Nat.add d 0`: do not substitute the let value and then fold closed Nat arithmetic (2ⁿ) |
| let-ladder | 40 | 1.01 | 0.00 | 0.00 | 1.36 | 2000 `let`s separated by additions: O(1) per binding |
| loop-unfolded-once | 40 | 0.52 | 0.04 | 0.04 | 1.92 | `allInK 0 N p` vs its one-step unfolding, N = 10⁶: unfold once, do not run the loop |
| loop-unfolded-once-bad (reject) | 120 | 14.24 | 0.04 | 0.05 | 2.79 | bodies differ under a binder; official refutes in ~N³ |
| magma-list-deep-n21 / n36 | 70 / 360 | 2.70 / 35.45 | 0.05 / 0.42 | 0.28 / 9.43 | 4.99 / 57.76 | `decide` over finite magmas with `List` tables: raw evaluation throughput on deep closed computations (rpylean 0.05 / 0.20) |
| magma-list-pair-n21 / n7 | 240 / 60 | 18.55 / 1.71 | 2.23 / 0.20 | 3.12 / 0.25 | 19.07 / 2.58 | 21⁴ ≈ 195 k tuples, list lookups (rpylean best: 1.11) |
| magma-string-n4 / pair-n9 | 70 / 120 | 3.58 / 8.21 | 0.23 / 0.49 | 0.26 / 0.72 | 4.89 / 14.64 | table stored in a `String`: literal decoding on every lookup (rpylean OOM at 14.65 GB on n9) |
| proj-app-lazy-delta | 60 | 2.08 | 0.00 | 0.00 | 3.04 | `small.1 a` vs `(big N).1 a`: lazy δ on *applied* projections (lean4#16613) |
| proj-applied-struct | 230 | 14.03 | 0.00 | 0.00 | 14.82 | `(gC slowA c).fn x` vs `(gC' slowA c).fn x`: unfold `gC'` instead of reducing the structure (2¹⁹ steps); palladium E |
| proj-applied-struct-bad / -arg-bad (reject) | 130 / 120 | 4.88 / 4.69 | 0.88 / 2.65 | 1.06 | 12.93 / 5.34 | equal heads with different arguments / propositional-only equality must be refuted |
| proj-cheap-struct | 40 | 0.04 | 0.00 | 0.00 | 0.22 | `(S u).re` vs `(S ()).re`: compare stuck projections by their structures' arguments; do not evaluate `S` (2¹⁷) |
| proj-lazy-struct | 40 | 0.04 | 0.00 | 0.00 | 0.17 | `(P a).1` vs `(Q b).1`: project as soon as both are constructors; do not compare whole structures |
| proj-stuck-struct | 40 | 0.04 | 0.00 | 0.00 | 0.17 | keep the unreduced structure argument of a stuck projection (else exponential unrolling) |
| prop-struct-projections | 40 | 0.10 | 0.01 | 0.01 | 0.63 | 200-field `Prop` structure: "is field j used later" must be O(1) per field (N² not N³) |
| refute-cheap-first / -last (reject) | 40 | 0.04 / 0.18 | 0.00 | 0.00 | 0.13 / 0.63 | `(false, count #n) = (true, count #(n+1))` and swapped: order of comparing constructor arguments |
| repeated-subproblem | 40 | 0.03 | 0.00 | 0.00 | 0.12 | perfect trees: memoise proved-equal pairs (n vs 2ⁿ) |
| shared-subterm | 40 | 0.06 | 0.00 | 0.01 | 0.37 | consume a shared tree through its DAG, never expand 2ⁿ nodes |
| shift-cascade | 40 | 0.05 | 0.00 | 0.00 | 0.28 | 1000 nested `let`s capturing a λ parameter: no accumulation of deferred shifts |
| short-circuit-and | 220 | 17.50 | 0.04 | 0.04 | 16.71 | `false && A` vs `false && B` with open, unequal A, B: unfold `&&` before comparing arguments (pestka 3.06) |
| short-circuit-and-bad (reject) | 120 | 6.18 | 0.18 | 0.65 | 6.69 | `true && …`: must compare the arguments |
| unfold-before-literal | 240 | 14.47 | 2.65 | 0.01 | 22.49 | `[slowA, 1].sum` vs `slowA + (1 + 0)`: δ-unfold before Nat-literal folding forces `slowA` (2¹⁹) |
| unfold-before-literal-bad (reject) | 130 | 4.87 | 0.88 | 1.06 | 12.91 | `… = slowA + (2 + 0)`: failure of the shortcut must not be a "no" by itself, nor a "yes" |
| unfold-before-literal-fallback | 270 | 14.52 | 2.65 | 3.60 | 22.31 | `[slowA + 1, 1].sum = slowA + (2 + 0)`: after the argument comparison fails, continue and accept (rpylean T, kefir 0.00) |
| unroll-versus-evaluate | 40 | 0.04 | 0.00 | 0.00 | 0.16 | `count #(n+1)` vs `N.add (count #n) #1`: unfold the right one of two different heads |

Sources named in the descriptions: Courant & Leroy (POPL 2026) §2/§10, smalltt's `conv_eval`, AxiomMath/CFSG certificates, con-leche's own proofs, the SAIR equational-theories certificates.

---

## 4. results.json: failures, traps, policies

### 4.1 Everything the top 12 do not get right

| checker | test | expected | status | reason (from stderr/stdout) |
|---|---|---|---|---|
| nano-lean | bugs/nat-shift-huge | accept | declined | "declaration stack/arena budget exhausted" on 3 theorems; 11.0 GB, 72.9 G instr |
| nano-lean | corner-cases/eta-struct-major-no-whnf, -and-no-whnf | either | declined | "declaration work budget exhausted" after 21 G instr |
| gruszka, pestka, kefir, kefir-wasm | — | | | no failure, no decline |
| sokonanoda | bugs/loose-bvar-value | reject | error (3) | panic "eval: loose bvar" |
| sokonanoda | bugs/nat-shift-huge | accept | error (3) | panic "Nat.shiftLeft: shift does not fit in a machine word" |
| sokonanoda | the two no-whnf corner cases | either | timeout 60 s | reduces the major first |
| sokonanoda | tutorial/140, 141 | reject | declined | "unsafe and partial definitions are not supported" |
| rpylean | the two no-whnf corner cases | either | timeout | — |
| rpylean | perf/magma-string-pair-n9 | accept | error (137) | killed at 14.65 GB after 722 G instr |
| rpylean | perf/unfold-before-literal-fallback | accept | timeout 270 s | — |
| palladium | perf/proj-applied-struct, -bad, -arg-bad, unfold-before-literal, -bad, -fallback | 3 accept, 3 reject | error (3) | "fault: … recursion depth exceeded" |
| con-ron, con-agua | bugs/quot-bad-type | reject | declined | "quotient declaration mismatch" (verified mode declines instead of rejecting) |
| con-ron, con-agua | cedar; other/sparse-name-index, level-index-out-of-order; tutorial/034, 035, 107 (accept), 047 (reject) | | declined | "non-standard axiom (…)" — any axiom outside its pinned set |
| con-ron, con-agua | tutorial/140, 141 | reject | declined | "definition with safety unsafe/partial" |
| nanobruijn | the two no-whnf corner cases | either | timeout | — |
| nanoda | the two no-whnf corner cases | either | timeout | — |
| nanoda | other/sparse-name-index, level-index-out-of-order | accept | declined | "back-reference mismatch … Back-refs must be continuous" |

For reference — tenet-jvm: `bugs/nat-shift-huge` → `java.lang.OutOfMemoryError: GC overhead limit exceeded` after 366 s and 10,892 G instructions (error); the two no-whnf tests time out. official v4.35.0: only the two no-whnf timeouts. lean4lean (rank 28) wrongly rejects 7 accept tests with "deterministic timeout", "deep recursion detected", "unknown constant 'Bool.Reflects'".

### 4.2 Tests most often answered wrongly (all 37 checkers)

| wrong | test | expected | who |
|---|---|---|---|
| 7 | bugs/rec-rule-proof-irrel-nested | reject | lazylean, mathgraph, metalean-fast, metalean-slow, nanoclo, nanoclo-fortran, vow |
| 5 | bugs/nat-shift-huge | accept | canonical-min, lazylean, lean4cobol, mathgraph, nanoclo (+ 11 declines/errors/timeouts: the most-missed test overall) |
| 4 | bugs/rec-rule-proof-irrel | reject | lazylean, mathgraph, nanoclo, nanoclo-fortran |
| 4 | bugs/nested-name-capture | reject | mathgraph, metalean-fast, metalean-slow, nanoclo-fortran |
| 4 | bugs/nested-rec-rule-nfields | reject | same four |
| 3 | bugs/str-lit-utf8 | accept | canonical-min, lean4cobol, vow |
| 3 | perf/ite-taken-branch | accept | canonical-min, lean4cobol, lean4lean |
| 3 | other/inductive-undeclared-universe | reject | mathgraph, nanoclo-fortran, vow |
| 2 | bugs/unit-rec-rule | reject | mathgraph, nanoclo-fortran |
| 1 each | bugs/rec-type-proof-irrel (lazylean), closure-level-subst, pi-closure-context (mathgraph), nested-unused-param (metalean-fast) | reject | |

Common theme: recursors (especially auxiliary recursors of nested inductives) compared up to defeq or not at all. Most declined/errored/timed-out non-either tests: `bugs/nat-shift-huge` (11), `mathlib` (11), `perf/proj-applied-struct-bad` (10), `cedar` (10), `perf/unfold-before-literal-fallback` (10), `perf/proj-applied-struct-arg-bad`, `unfold-before-literal-bad`, `tutorial/140`, `141` (9 each), `perf/magma-string-pair-n9`, `proj-app-lazy-delta`, `proj-applied-struct`, `gf3-matrix-product`, `unfold-before-literal`, `con-leche` (8 each).

### 4.3 Policies of the leaders

- **Axioms.** nano-lean, gruszka, pestka, rpylean, palladium, official: no axiom filter in the run command, cedar accepted. sokonanoda `--axiom-allow-all`; kefir `--axiom-policy=allow-all-declared-axioms` (prints the assumed axioms; mathlib: propext, Quot.sound, Classical.choice, sorryAx); nanoda/nanobruijn/nanoclo/mathgraph config `"unsafe_permit_all_axioms": true`. Only con-leche/con-ron/con-agua decline unknown axioms. `sorryAx` is an ordinary axiom in init, std, cslib, mathlib, cedar.
- **native_decide / reduceBool.** At Lean 4.36 `native_decide` leaves a plain axiom per use (cedar). The strings `trustCompiler`, `ofReduceBool`, `reduceBool`, `reduceNat` have 0 occurrences in init.ndjson and std.ndjson, and `lean4/src/kernel/` contains only `reduce_nat` (literal arithmetic). tenet-jvm states it does not evaluate `Lean.reduceBool`/`reduceNat` and rejects proofs needing them; tenet (.NET) declines "a file that asks it to trust compiled code". For exports of older Lean versions these names may exist as ordinary constants/axioms; treating them as opaque is sound. Not verified: whether any current arena test contains them (the three tests exported with 4.34.x declare only the three standard axioms).
- **Nat literal extension.** Mandatory (`other/nat-lit-*`, gf3, magma, reachability). 4.36 kernel list (`type_checker.cpp:668-700`): `Nat.succ`, `add`, `sub`, `mul`, `pow`, `gcd`, `mod`, `div`, `beq`, `ble`, `land`, `lor`, `xor`, `shiftLeft`, `shiftRight`; arguments are whnf'd and must both be literals (or `Nat.zero`). nanoda-family checkers enable it by flag (`nat_extension`, `string_extension`).
- **Malformed input.** official: any reader error is an uncaught exception → exit 1 (rejected). gruszka/pestka wrapper: converter says invalid → reject; cannot convert (limit, unknown record or key) → decline; anything else incl. signals → decline (`gruszka/scripts/arena-run`). nano-lean: rejected → 1, budget exhaustion/unsupported/internal → 2 (`nano-lean-v0.4.2/src/term/outcome.rs:55-60`); an unsafe/partial definition is "not permitted in a safe proof export" → reject at import (`src/import/json.rs:270-279`). palladium: unsupported format → decline, internal fault → 3, I/O → 4. tenet-jvm: verdict starts as error; exit 3 for internal error/unreadable input; never declines. nanoda's arena wrapper maps every failure except 2 to 1. No arena test contains syntactically invalid JSON.
- **Init.Prelude.** README: declarations of `Init.Prelude` in a test are either absent or those of an official release; checkers may check more but are not expected to handle foreign preludes; the Lean version in the header may be used. Leaders: gruszka "treats the whole file, including the prelude, as untrusted … builds the quotient package and every recursor itself"; con-ron/con-agua embed con-leche's prelude pins and `natOpPinSets`; nano-lean has a check of the primitives it reduces by name (`nano-lean-v0.4.2/src/import/primitives.rs`, only skimmed) and can compare the prelude with a trusted export (`--prelude` option, `src/import/prelude.rs`; not used in the arena command). Tests that redefine prelude names with forged data exist (`bugs/nat-rec-rules`, `nat-rec-k-lie`, `unit-rec-rule`, `quot-bad-type`) and are all caught by re-deriving recursors/quot.
- **Resource exhaustion** is reported as decline or error by every leader, never as reject (a reject on a good test costs rank).

---

## 5. Local reproduction and ground truth

- Arena data: `/home/queclr/Documents/rabdos-lean-vm/work/arena-site/{index.html,results.json,lean-arena-tests.tar.gz}`; tests extracted to `work/arena-site/tests/{good,bad}/<group>/<name>.ndjson` (138 good, 92 bad). Links on the site: `https://arena.lean-lang.org/lean-arena-tests.tar.gz`, `https://arena.lean-lang.org/results.json`.
- Real corpora reproduced locally (Lean 4.36.0 toolchain already installed under `~/.elan`):
  ```
  TC=$HOME/.elan/toolchains/leanprover--lean4---v4.36.0
  LEAN_SYSROOT=$TC PATH=$TC/bin:$PATH $TC/bin/leanexport Init > init.ndjson         # 12 s, sha256 93b61a43…30ba = arena `init`
  # std: needs a non-`module` file that imports Std, exactly like the arena's leanfile flow
  printf -- '-- Import Std module to make all its declarations available for export\nimport Std\n' > Test.lean
  LEAN_SYSROOT=$TC PATH=$TC/bin:$PATH $TC/bin/lean -o Test.olean Test.lean
  LEAN_SYSROOT=$TC PATH=$TC/bin:$PATH LEAN_PATH=$PWD $TC/bin/leanexport Test > std.ndjson   # 17 s, sha256 15b659c1…2f7d = arena `std`
  ```
  Results are in `work/arena-site/generated/{init,std}.ndjson`. `leanexport Std` directly gives a different, smaller file (557,816,663 bytes); without `LEAN_SYSROOT` the binary picks up the default toolchain and fails with "incompatible header".
- Official verdicts: `$TC/bin/leanchecker --from-export FILE` → exit 0 "Lean default kernel accepts the solution" / exit 1 "… rejects the solution: while replaying declaration 'X': (kernel) …". Run for this report on all 92 bad tests (all exit 1) and 5 static corner cases. The arena's ranked "official" is v4.35.0; the tests are exported by v4.36.0.
- mathlib, cslib, cedar, con-leche, navier-stokes-euler, reachability are not downloadable; they need the respective repositories at the pinned revisions plus `lake exe cache get` (YAMLs in `lean-kernel-arena/tests/`).

---

## 6. Not verified / open

1. Whether the arena's instruction count is user-only or user+kernel (§1.2).
2. Version of `jdk` in nixpkgs `nixos-26.05`; whether a downloaded GraalVM (or a native image built at build time) is acceptable to the arena maintainers — nothing in the README forbids it; other checkers download toolchains and data in `build`.
3. Record-kind distribution, longest line, longest `natVal`, deepest expression of the **mathlib** export (only totals are known); the estimates in §2.12 are extrapolated from init/std.
4. Absence of `unsafe`/`partial` definitions and of `mdata` in the six corpora that could not be inspected rests on indirect evidence (§2.7) and on the writer's code.
5. `String.ofList`/`Char.ofNat` as the literal-expansion constants was read from the 4.36.0 kernel. All 12 tarball files that contain a `strVal` are 4.36.0 exports, so the hand-crafted 4.29.1-labelled tests do not depend on it; whether the two corpora exported with Lean 4.34.x (navier-stokes-euler, reachability) contain string literals, and under which constant name their Lean version expands them, was not checked (all leaders accept both).
6. `max_rss` semantics on the runner (largest process vs. sum) follow from GNU time/Linux documentation, not from an experiment.
7. The exact kernel code path behind each official error message in §3 was not traced; the messages are quoted as printed.
8. How noisy instruction counts are between runs of a JIT-compiled checker is unknown; the arena measures once per (checker, test) source-hash pair.

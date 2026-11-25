# rlvm-export — the lean4export NDJSON reader

Java package `rlvm.export`. Turns a lean4export file (format 3.1.0) into calls on a primitive-typed
sink, in file order. No object per record, no JSON tree for 99.9 % of the lines, no dependency
besides the JDK. Works on the JVM and as a GraalVM native image (built and measured, see below).

Status: complete for the task; `$MVN -o -q -pl rlvm-export test` runs 72 tests in 85 s, all pass; a
73rd (the full mathlib export) is opt-in and passes. The full mathlib export (6.3 GB, 112 M lines) reads in 3.4 s / 40 G instructions (parse
only) or 5.9 s / 49 G instructions (parse + `ColumnarStore`) on one thread with C2 as the JIT.
**With the default JIT of GraalVM (libgraal) the same runs cost 71 G / 88 G**: 30 G of that is the
JIT compiler itself. See "What limits us".

## Public API

| class | role |
|---|---|
| `ExportReader` | `read(file, sink [, options])` with `file` a `Path`, a `byte[]` or a `ReadableByteChannel` → `Result` (lines, bytes, counts and max ids per id space, dense flags, general-path lines). Throws `ExportException` or `IOException`. `Options`: `io` (AUTO / MAPPED / BUFFERED), `threads`, `bufferBytes`, `parallelChunkBytes`, `unloadMapped`, `generalPathOnly` (testing). |
| `ExportSink` | interface: one callback per record kind. The term store of the checker implements this. |
| `ExportException` | checked; `kind()` = `MALFORMED` (the official reader rejects too → **reject**) or `UNSUPPORTED` (outside what we represent, official may accept → **decline**); `line()` (1-based), `reason()`. |
| `ColumnarStore` | reference sink: everything in flat arrays, plus per-expression metadata. Layout in its class comment. |
| `Ownership` | analysis over a `ColumnarStore`: proof-private split and last use per expression. |
| `tools.ExportStats` | `java -cp rlvm-export/target/classes rlvm.export.tools.ExportStats [--threads N] [--top N] FILE` |
| `tools.ExportLoad` | loads a file into a sink and prints counts and time; the command measured below. Exit 0 / 1 malformed / 2 unsupported / 3 I/O. |
| `tools.NullSink` | sink that only numbers (to measure the reader alone). |

### The sink contract (`ExportSink`)

- **The sink assigns the ids.** Every `name*`, `level*`, `expr*` method returns the `int` id of what it
  created; the reader keeps the map "id in the file → sink id" and passes only sink ids back. A sink
  may number densely, hash-cons (return an existing id), or drop nodes (`exprMData` may return its
  argument). `Integer.MIN_VALUE` is reserved. `nameAnonymous()` and `levelZero()` are asked once and
  bound to file id 0.
- If the sink numbers each id space consecutively in call order and the file's ids are dense and in
  order (every exporter-written file), **the reader keeps no translation table at all** (a reference
  is one compare and one add; `Result.tableFree`). Anything else switches that id space to a table
  (+ hash map for far ids), transparently.
- Callbacks: `meta`; `nameStr/nameNum/nameNumBig`; `levelSucc/Max/IMax/Param`;
  `exprBVar/Sort/Const/App/Lam/Forall/Let/Proj/NatLit/StrLit/MData`;
  `declAxiom/Def/Thm/Opaque/Quot`; `inductBegin`, then all `inductType`, all `inductCtor`, all
  (`inductRec` + its `inductRecRule`s), `inductEnd` (the order in which the official reader registers
  the constants of a block).
- Strings arrive as `(byte[], off, len)`: valid UTF-8, escapes resolved, valid only during the call.
  Nat literals and numeric name components ≥ 2^63 arrive as canonical decimal digit ranges. Name lists
  (`levelParams`, `all`, `ctors`) and universe lists are ranges of a scratch `int[]`. Counts are
  `long`s in `[0, 2^63)`. `regular` hint heights are reduced mod 2^32 like `Nat.toUInt32`.
- **Obligation of the sink:** reject a second declaration of a structurally equal constant name
  (`ExportException.malformed("Duplicate declaration: …")`). It is the one parse-level rejection of
  the official reader that needs name identity, which only the store has. `ColumnarStore` does it.
- All callbacks come from the thread that called `read`, also in parallel mode.

### Id conventions of `ColumnarStore`

Id 0 is "none" in every space. Names: 1 = anonymous, others from 2, hash-consed (structurally equal
names have one id). Levels: 1 = zero, others from 2 in file order. Expressions: from 1 in file order;
children have smaller ids than parents. So for an exporter-written file: store id = file id + 1 in
all three spaces. Constants from 1 in file order (in a block: types, constructors, recursors).

Columns (all `int[]`/`long[]`/`byte[]`, append-only, never modified after the creating call, hence
readable without locks once loading is done; an overlay can continue at `count + 1`):
`exprHead` = kind (bits 0-3) | HAS_LEVEL_PARAM (bit 4) | binder info (bits 8-9) | loose bound variable
range (bits 12-31); `exprA`, `exprB` (operands, or an index into the side pool `wide` for let / proj /
const); optional `exprMask` (64-bit free-variable mask of docs/research/09 §1.2: exact when the range
is ≤ 63, a superset otherwise); optional `exprBinderName`; names, levels, constants and declarations
analogously. 20 bytes per expression with masks, 12 without. mathlib: 2.5 GB / 1.7 GB of columns.

## What is accepted

The reader accepts a file exactly when the official reader (`LeanExport/Parse.lean`, Lean v4.36.0,
behind `leanchecker --from-export`) parses it, except for the listed `UNSUPPORTED` cases and for
duplicate declarations (sink). This was established from the source and then **checked against the
real `leanchecker` binary on 288 hand-written edge cases** (`EdgeCaseTest`, runs when the binary is
installed). Points that are easy to get wrong:

- Line 1 is the header whatever it contains (never an error; `meta(wellFormed=false, …)` if it is not
  `{"meta":{…}}`). An empty file is fine. A blank line is an error. A missing final newline is fine;
  CR before LF is whitespace. Lines are split at `\n` only.
- A record is an object with exactly two keys (`in`/`il`/`ie` + kind) or one key (declaration), in any
  key order; extra keys are an error at the top level and **ignored** in nested objects; duplicate
  keys are an error at any depth (also inside ignored values).
- Numbers follow Lean's `JsonNumber`: `1e2`, `1.5e1`, `-0`, `0.1e1` are naturals; `1.0`, `10e-1`,
  `-1` are not (then the line is "unknown"); exponents above 100000 are a JSON error.
- Strings: all JSON escapes; a `\uD8xx\uDCxx` pair is one character; **any other surrogate escape
  becomes U+FFFD, it is not an error**; raw control characters are errors; raw DEL is fine.
- `natVal` follows `String.toNat?` of 4.36: digits, single `_` allowed after a digit, leading zeros
  allowed; the sink gets canonical digits.
- `bvar` above 2^20 − 2 is rejected: the official reader aborts (`INTERNAL PANIC: too many bound
  variables`, exit status 1).
- `opaque` may omit `isUnsafe`; `mdata.data` must be an object and is otherwise ignored.
- Ids are arbitrary naturals: "defined before use, bound once" is the only rule (sparse and
  out-of-order ids are accepted; the two arena tests that do this pass). That includes ids of 2^63
  and above, of any length and in any spelling `JsonNumber` allows (`1e30`): the general path gives
  each distinct number a key (`IdSpace.BIG_KEY_MAX` and below, by its canonical digits) and the id
  spaces keep such ids in their hash maps. No exporter file has one; the test kit's `renumber_ids`
  mutants do (all accepted by the official checker).

`UNSUPPORTED` (decline, never guess): a line containing ill-formed UTF-8 (the official reader does not
validate and its behaviour on such bytes is unspecified; it happens to accept them inside strings);
a count or projection index ≥ 2^63; a line longer than 1 GiB; in `ColumnarStore`: more than 2^31 records or 2 GiB of text.

Of the 230 published arena tests, all 138 good ones parse; of the 92 bad ones exactly the six
"duplicate declaration" tests (tutorial 132, 133, 134, 135, 138, 139) are rejected at this level —
the same six the official reader rejects before the kernel runs (`CorpusTest` checks both sides).

## Design

**Two paths per line.**
`Scanner` (fast path): the exporter writes each record kind with one exact byte shape, so a line is
matched against templates with unaligned 8-byte loads compared to packed literals, and decimal numbers
are converted 8 digits at a time (SWAR). Templates: `app`, `lam`, `forallE`, `letE`, `proj`, `const`,
name `str` (plain ASCII) / `num`, `thm`, safe `def`: 99.93 % of the lines of init/std, 99.90 % of
mathlib. A line is taken by the fast path only if *every byte* including the final `\n` is the
template's; otherwise it goes, untouched, to the general path. The fast path never rejects anything
and never reads a load starting beyond the first unverified byte of the line.
`JsonLine` + `Linker` (general path): a strict, non-recursive JSON parser into flat arrays
(reused, no allocation per line) that mirrors Lean's `Json.parse`, then the dispatch of the official
`parseItem`. 4 × slower than the fast path (425 MB/s vs 1.8 GB/s), irrelevant at 0.1 % of the lines.

**Ids** (`IdSpace`): dense mode (no table) → table → hash map, per id space, switching automatically.
The "bound twice" check runs before the sink sees the record.

**I/O — chosen by measurement.** The parse buffer is a `byte[]` accessed through a
`byteArrayViewVarHandle` (`Mem`, the only place that loads words). Two ways to fill it: `MAPPED`
(default for regular files): map the file, copy 1 MiB chunks out, `madvise(DONTNEED)` the processed
part; `BUFFERED`: `read` into the buffer (any channel). Complete lines are scanned in place, the
partial last line is carried to the front. What was measured (std/mathlib, steady state unless noted):

| variant | result |
|---|---|
| parse a mapped `MemorySegment` in place | fastest steady state (0.17 s per 584 MB vs 0.22–0.30), **but 7.8 G instructions of JIT warm-up** against 0.7–2 G for `byte[]` (every access goes through layers that only the top-tier JIT removes), and the whole file counts as RSS unless unloaded. Rejected. |
| `byte[]`, mapped + copy (**chosen**) | mathlib: 40.1 G instr, 3.36 s, RSS 93 MB |
| `byte[]`, `FileChannel.read` | mathlib: 40.8 G instr, 3.53 s, RSS 94 MB (same instructions, 5 % slower) |
| mapped without `unload` | same instructions, RSS = file size (6.4 GB for mathlib) |
| buffer 256 KiB … 4 MiB | no measurable difference; 1 MiB kept |
| word loads via `sun.misc.Unsafe` | −12 % instructions under C2 (35.3 G vs 40.3 G on mathlib), no gain under Graal or native image; not adopted (unchecked loads; JDK 24+ warns). `Mem` is the one place to change. |
| byte-wise composition of words | 2.3 × more instructions. Rejected. |
| comparing the id of an `app` line with the expected next id as text instead of parsing it | 16 % less time but 12 % *more* instructions under C2. Rejected (the arena counts instructions). |

**Parallel mode** (`Options.threads = N > 1`, mapped or in-memory input): the file is cut at line
boundaries into 4 MiB chunks; N scanner threads copy their chunk, run the fast path and record the
result on a `Tape` (ints); the calling thread replays the tapes in file order into the `Linker`.
Scanner threads never interpret anything (non-template lines are recorded as "slow" and parsed during
the replay), so the sink sees exactly the single-threaded call sequence and the first error of the
file is reported — verified by differential tests on all corpora. At most 2N+2 chunks are in flight.

## Parallel packing (`Options.runs`, LP2 2026-11-24)

In parallel mode the scanner threads can do more than tokenise. With `ExportReader.Options.runs` set
to a `RunFormat` they also prepare the PACKED form of the proof-private records, and the reader
offers it to the sink in place of the single records (`ExportSink.exprRun`, default: refuse).

**What a scanner thread recognises** (`RunPacker`, between `Scanner` and the chunk's `Tape`).
lean4export writes the records a declaration needs right before its declaration line, children
first, the type's records before the value's. So at a `thm` line with type id T the records since
the previous declaration line with an id above T are the proof (finding F1 of scout X5,
*agentic-note*: rep-X5). The packer keeps the entries since the last declaration line in a
window; at a template `thm` line it copies the window to the tape and puts a run header in front of
every stretch of at least `RunFormat.minRecords` consecutive `app` / `lam` / `forallE` / `letE` entries
with consecutive ids above the bound `max(T, first expression id of the window - 1)` whose children
are all earlier records. A `letE` entry with `nondep = true` is never part of a run (R2, 2026-11-24,
finding R2-F1: the flag is part of the official term identity and the packed form has no place for
it; such a record ends the stretch and reaches the sink through `exprLet`, where the strict loader
declines the declaration; no letE of the eight corpora has the flag, so no run is shorter for it;
`PackedRunsTest`: the sink sees every such record). At any other declaration line (a line that starts with `{"def":{`,
`{"axiom"`, `{"induct`, `{"opaque`, `{"quot":`) and at the end of the chunk the window is copied as it
is. With packing the chunk borders are moved behind the next declaration line (if it ends within
8 MiB), so that a declaration's records lie in one chunk; the borders remain a function of the file
content and the chunk size.

**The packed form** (`ExportReader.RunFormat`; the tags of the four kinds are chosen by the sink's
owner): per record its tag byte and its children in order, each LOCAL (LEB128 of twice the id
distance: a child above the bound), EARLY (LEB128 of `2R + 1`, `R` the value the sink's table of
finished expressions gives for the child: `ExportReader.EarlyRefs`, read by the scanner thread once
per declaration) or OPEN (nothing in the bytes; an entry `(offset, child id)` in the run's list of
open children); one length byte per record. For `rlvm-load` the tags are the store's node kinds and
`R` is a shared term id, so the bytes are the bytes of a proof block of `rlvm-store` with holes
where the loader thread has to resolve a reference itself.

**Nothing in the packer decides anything.** A run header is an offer. The ordinary entries of the
run follow it on the tape, and the replay makes exactly the calls of the single-threaded mode
unless the `Linker` passes the run on and the sink takes it. The `Linker` passes it on only if the
records could not fail: the expression ids are dense with a known delta and the run's first id is
the next one (so every child, which the packer has checked to be smaller than its record, is
bound), and every binder name is bound (`IdSpace.nextDense`, `allBoundUpTo`). A sink that takes a run
must behave as if it had received the calls; what the bound means for it is its own business
(`rlvm-load` checks it against its segment and the type root, and expands a run that was packed for
the wrong segment). So a file that is not exporter output - sparse ids, metadata, a declaration in
another spelling, a damaged line - is read as before, with the same calls, the same error and the
same line; it only costs offers that are refused.

**Also in this change**: the pages of a mapped chunk leave the resident set on the scanner thread
that copied it (`Source.copied`), not on the reading thread (2 % of that thread's time; the pages
that straddle two chunks are still released in file order).

Tests: `PackedRunsTest` (exporter-shaped files through every chunk size, with and without a table of
finished expressions; a 12 MB file with the default chunks; files that leave the shape) and every
existing differential test through `T.agree`, which now also reads each input with runs offered and
refused (complete trace of sink calls equal to the single-threaded read), taken, and taken at
random by a sink that decodes them (`StructSink`).

## Measurements

`bench/modules/rlvm-export/measure-all.sh` (arena container: 8 performance cores, 16 GB, `perf stat`
user-space instructions of all threads, one run each; raw output in `stats/measurements.txt`).
Host is aarch64; the arena is x86-64, so absolute counts will differ there. The machine was shared
with 15 other agents: wall/CPU times are noisy, and JVM instruction counts vary by a few percent
between runs because JIT timing varies. Command: `java [flags] -Xmx10g -cp rlvm-export/target/classes
rlvm.export.tools.ExportLoad --sink null|store FILE`.

Whole process, instructions (per input byte) and wall time:

| file | sink | default JIT (libgraal) | C2 (`-XX:-UseJVMCICompiler`) | native image (`-O3`) |
|---|---|---|---|---|
| init 348 MB | none | 15.4 G (44.3) 0.38 s | 4.50 G (12.9) 0.29 s | 3.46 G (9.9) 0.25 s |
| init | ColumnarStore | 21.0 G (60.4) 0.57 s | 6.09 G (17.5) 0.45 s | 4.05 G (11.6) 0.41 s |
| std 584 MB | none | 15.0 G (25.7) 0.49 s | 6.15 G (10.5) 0.43 s | 5.78 G (9.9) 0.41 s |
| std | ColumnarStore | 23.3 G (39.8) 0.85 s | 8.68 G (14.8) 0.71 s | 6.77 G (11.6) 0.69 s |
| mathlib 6326 MB | none | 70.7 G (11.2) 3.42 s | **40.1 G (6.35) 3.36 s** | 60.0 G (9.48) 3.92 s |
| mathlib | ColumnarStore | 87.8 G (13.9) 6.41 s | **49.5 G (7.82) 5.93 s** | 70.0 G (11.1) 7.88 s |

- Throughput, parse + ColumnarStore, C2: init 780 MB/s, std 820 MB/s, mathlib 1070 MB/s (parse only:
  1.2 / 1.35 / 1.9 GB/s). Max RSS for mathlib: 93 MB parse only, 3.0 GB with the store (2.5 GB columns).
- Steady state under C2 (difference mathlib − std): **5.9 instructions per byte** to parse,
  **7.1** with the ColumnarStore. Scanner alone (`ScanBench` in the test tree): 4.4 per byte.
- `-XX:-TieredCompilation` with C2: about 10 % less on init/std, equal on mathlib.
- init-prelude (3.8 MB) + store: 1.66 G default, 1.20 G C2, 0.99 G C2 + SerialGC, 0.59 G C1 only,
  **0.058 G native image**. `java -version` alone is 0.34 G (0.25 G with C2 + SerialGC).
- Parallel mode, mathlib, C2, parse only: 1 thread 40.1 G / 3.36 s; 2 scanner threads 47.3 G / 1.85 s;
  4: 47.9 G / 1.33 s; 8: 49.4 G / 1.60 s. With the ColumnarStore and 4 scanner threads: 59.6 G / 3.25 s
  (1 thread: 49.5 G / 5.93 s). **Overhead of the parallel mode: +18 % … +23 % instructions** (tape
  write and replay, a second copy of the hot code to compile, thread hand-over); it buys a 2.5 ×
  shorter parse and is limited by the single thread that feeds the sink.

### What limits us

1. **The JIT compiler.** On mathlib, perf attributes 46 % of all instructions of the default
   configuration to the libgraal compiler thread (1.6 s of compile time for 109 methods; two mass
   deoptimisations when ids reach 8 and 9 digits recompile the whole scanner). With C2 the compiler
   share is a few G. This is a property of the JVM configuration, not of the reader, and it will hit
   every other module in the same way: **measure the checker with `-XX:-UseJVMCICompiler`** (Truffle
   compilation does not need libgraal as the *host* JIT) or as a native image.
2. **Warm-up.** Under C2 about 2.5 G (std) to 6 G (mathlib) go to start-up, interpretation, profiled
   tier-3 code and compilation. A native image has none (init-prelude: 58 M instructions in total),
   but its steady state is 9.5 per byte instead of 5.9: without profiles the image builder does not
   inline `Scanner.num` and `Mem.u64` (34 % and 22 % of the instructions are in those two functions).
   Raising the trivial-inlining limits (`-H:MaxNodesInTrivialMethod=120
   -H:MaxNodesInTrivialLeafMethod=200 -H:MaxInvokesInTrivialMethod=4`) gave 8.4 per byte; break-even
   with C2 is at roughly 1 GB of input. PGO (not in the CE edition) would be the real fix.
3. **Steady state**, per `app` line (48 bytes, 86 % of mathlib's lines): three number conversions
   (~45 instructions each on aarch64: bounds-checked load, digit test, variable shift, three
   multiply/shift/mask steps), five literal compares, then id checks and the sink call (~60 with a
   trivial sink). The ColumnarStore adds ~65 per expression for the metadata (loads of both children's
   head and mask, mostly cache misses: this is what makes the store run slower in time).
4. Not done, could still help: the bound check of every word load (see `Unsafe` row above); a
   run-of-`app`-lines loop with the linker state in locals; templates for the remaining kinds (0.1 % of
   lines, 1 % of the instructions).

## Statistics (`ExportStats`)

Full outputs: `stats/init.txt`, `stats/std.txt`, `stats/mathlib.txt` (12 s for mathlib, `-Xmx24g`).
All numbers are static counts over the DAG of records.

| | init | std | mathlib |
|---|---|---|---|
| lines / bytes per line | 6,495,296 / 53.5 | 10,611,507 / 55.1 | 112,081,718 / 56.4 |
| general-path lines | 4,359 (0.07 %) | 7,311 (0.07 %) | 111,443 (0.10 %) |
| names (str / num) | 299,085 (58 / 42 %) | 580,893 | 4,273,914 (53 / 47 %) |
| levels | 571 | 953 | 48,246 (83 % `max`) |
| expressions | 6,137,092 | 9,928,514 | 107,037,712 |
| app / lam / forallE / const | 85.2 / 8.7 / 4.9 / 0.94 % | 81.5 / 10.7 / 6.4 / 1.0 % | 85.5 / 7.7 / 5.1 / 1.35 % |
| letE / proj / natVal / strVal | 11,380 / 2,948 / 285 / 1,837 | 22,116 / 9,329 / 453 / 3,055 | 255,403 / 34,595 / 5,660 / 19,699 |
| constants (decl lines) | 59,979 (58,547) | 103,885 (101,146) | 739,167 (721,845) |
| thm / def / opaque / ind / ctor / rec | 42,451 / 15,239 / 244 / 605 / 825 / 607 | 71,751 / 27,944 / 397 / 1,046 / 1,689 / 1,050 | 524,582 / 187,271 / 2,855 / 7,162 / 10,006 / 7,283 |
| ids | dense, in order | dense, in order | dense, in order (max expr id 107,037,711) |
| referenced once / ≥ 5 times / max in-degree | 83.2 % / 3.1 % / 191,358 | 83.9 % / 2.9 % / 322,960 | 84.5 % / 3.0 % / 2,956,115 |
| **proof-private** (reachable only from one theorem's value) | 72.9 % | 68.4 % | **63.8 %** (68.3 M) |
| theorems owning private expressions / largest block | 35,988 / 26,357 | 62,216 / 66,570 | 461,770 / 91,228 |
| dead after the declaration that introduced them | 85.9 % | 84.4 % | 82.5 % |
| closed records (no loose bvar) | 5.25 % | 5.00 % | **4.13 %** |
| closed maximal spines | 2.4 % | 2.1 % | 1.5 % |
| loose range > 64 (mask may be inexact) | 0.13 % | 0.30 % | 1.06 % |
| records containing a level parameter | 51.6 % | 58.5 % | 86.5 % |
| longest spine / deepest binder nesting | 99 / 147 | 99 / 147 | 106 / 361 |
| maximal spines (headed by a constant) | 2.13 M (98.0 %) | 3.19 M (97.8 %) | 30.6 M (98.3 %) |

Spines (mathlib): app records by position 1/2/3/4/5/6/7-8/9-16/17+: 13.2 / 19.0 / 17.3 / 14.2 / 11.4 /
10.0 / 7.3 / 6.8 / 0.8 %; maximal spines by number of arguments 1/2/3/4/5/6/7-8/9-16/17+:
4.5 / 21.4 / 16.5 / 14.8 / 9.8 / 18.6 / 8.1 / 5.8 / 0.6 %.
Binder nesting of declaration values (mathlib): ≤ 4: 27.7 %, ≤ 8: 59.7 %, ≤ 16: 89.6 %, ≤ 32: 98.6 %.
Loose range of records (mathlib): ≤ 8: 36 %, 9–16: 35 %, 17–32: 22 %, 33–64: 5.9 %.

"proof-private" is strict ownership (never needed by anything but that one proof, ever), hence lower
than the 79–83 % "introduced by a theorem and reached from its value" of docs/research/01; it agrees
with Susz (67 M block expressions in 452 k blocks for its mathlib). "dead after the declaration that
introduced them" counts transitive reachability from later declarations, hence 82–86 % and not the
94 % "never directly referenced later" of docs/research/01. The "introducing declaration" is the next
declaration line, which is slightly off where the exporter emits `Nat`/`String` support declarations
in the middle of another declaration's records.

### HEAT: how concentrated the referenced library is

A *spine head* is the constant at the head of a maximal application spine; *references* are all
references to `const` records of that name.

Cumulative coverage of the hottest constants (each column ranked by itself):

| top N | init heads / refs | std heads / refs | mathlib heads / refs |
|---|---|---|---|
| 1 | 11.2 % / 9.8 % | 11.3 % / 9.7 % | 7.8 % / 3.8 % |
| 10 | 37.9 % / 32.2 % | 33.9 % / 30.1 % | 24.9 % / 20.4 % |
| 100 | 71.7 % / 62.7 % | 66.8 % / 59.1 % | **52.4 % / 45.3 %** |
| 1,000 | 89.3 % / 82.6 % | 85.2 % / 78.3 % | **75.4 % / 68.4 %** |
| 10,000 | 97.9 % / 95.1 % | 96.2 % / 91.8 % | **90.0 % / 83.8 %** |
| 100,000 | 100 % | 100 % | 97.5 % / 94.9 % |

mathlib: 30.1 M spines are headed by a constant; 523,983 of the 739,167 constants head at least one
spine. By kind of the head: definitions 58.5 %, theorems 19.9 %, inductive types 16.5 %, constructors
4.7 %, recursors 0.19 %, axioms 0.26 %. The top 20 (spine heads, share):
`Eq` 7.79 %, `congrArg` 2.91, `DFunLike.coe` 2.28, `Eq.trans` 2.18, `HMul.hMul` 1.85,
`Membership.mem` 1.73, `HAdd.hAdd` 1.69, `CategoryTheory.CategoryStruct.comp` 1.63, `id` 1.50,
`And` 1.37, `CategoryTheory.Functor.obj` 1.21, `LE.le` 1.18, `Quiver.Hom` 1.12, `Eq.mpr` 1.09,
`Exists` 0.87, `Eq.refl` 0.82, `Eq.symm` 0.81, `HSub.hSub` 0.73, `congrFun'` 0.67, `Iff` 0.62.
init: `Eq` 11.2 %, `Eq.trans` 5.1, `Lean.Omega.LinearCombo.eval` 3.9, `congrArg` 3.7, `HSub.hSub` 3.2.

## Tests

`$MVN -o -q -pl rlvm-export test`: 13 classes, 73 tests (72 run by default, all passing), about 85 s.

- `RecordKindsTest`: every record kind and flag, with the exact sink calls expected.
- `EdgeCaseTest`: 296 edge cases with expected verdict and line; all reader configurations and the
  reference reader must agree with the table, **and so must the official `leanchecker` 4.36.0** as to
  whether the file parses (294 of them; the two ill-formed-UTF-8-outside-strings cases are not
  asserted; skipped if the binary is absent). Eleven of the cases are about ids of 2^63 and above
  (bound, used in every position, bound twice in two spellings, one number in three id spaces).
- `TemplateExhaustiveTest`: for one canonical line per template, every single-byte substitution
  (30 nasty bytes), deletion and insertion (86 k files), numbers of 1–21 digits in every field, cut-off
  final lines, runs of `app` lines with all kinds of id sequences: fast path ≡ general path.
- `RandomDifferentialTest`: generated exports (canonical / wild formatting, dense / sparse ids), intact
  and damaged; fast path, general path, tiny buffers, dribbling channel, parallel mode and the
  reference reader must make the same sink calls and fail at the same line with the same kind.
  Soak runs passed (`-Drlvm.export.rounds=60000`: 60 k intact + 240 k damaged files on an earlier
  state; 30 k + 120 k on the final code).
- `RefReader` + `MiniJson` (test tree): an independent, object-based transliteration of the official
  reader on a tiny recursive JSON parser; `JsonLineTest` compares the JSON parsers directly on 160 k
  random and damaged documents.
- `CorpusTest`: all 230 published tests through every configuration; good ones load into the store
  with all invariants; bad ones are rejected exactly where the official reader rejects.
- `RealFilesTest`: init and std: counts as published, fast ≡ general ≡ parallel ≡ buffered (hash of all
  sink calls), a 40 MB prefix against the reference reader, store invariants. mathlib the same with
  `-Drlvm.export.big=true -DargLine="-Xss512m -Xmx6g -Drlvm.export.big=true"` (21 s, passes).
- `ColumnarStoreTest` (layout, name interning, duplicate declarations, metadata against a brute-force
  definition on random DAGs), `OwnershipTest` (against brute-force reachability), `IdSpaceTest`,
  `ScannerPrimitivesTest`, `ReaderApiTest` (line numbers in every mode, sink errors, remapping sinks,
  long lines, I/O modes), `ExportStatsTest`.

## Known gaps

- Instruction counts are from aarch64; nothing was measured on x86-64.
- The default GraalVM JIT makes this module (and presumably every module) 1.8 × more expensive on
  mathlib than C2; this needs a project-level decision (JVM flags or native image).
- Emulation is of Lean 4.36.0's reader (e.g. `_` in `natVal`); other versions were not compared.
- Every distinct id of 2^63 or more costs a `String` and a map entry (general path only); counts and
  projection indices of that size also pass through the map before they are declined.
- `Result.maxNameId / maxLevelId / maxExprId` saturate at `Long.MAX_VALUE` when such an id is bound.
- Parallel mode exists for mapped/in-memory input only, and only the scanning is parallel.
- `ColumnarStore` is a reference store: no overlay, levels and expressions not hash-consed, counts
  saturate at 2^31 − 1, `mdata` kept as a node. The header's format version is passed on, not judged.
- `tools.IoBench` is the early accessor/I-O prototype (kept as evidence; not part of the reader).

## Files

`src/main/java/rlvm/export/`: `ExportReader`, `ExportSink`, `ExportException`, `ColumnarStore`,
`Ownership` (public); `Scanner`, `Mem`, `JsonLine`, `Utf8`, `Linker`, `IdSpace`, `RecordOut`, `Tape`,
`ParallelScan` (package-private); `tools/`. `stats/`: tool outputs and raw measurements.
`bench/modules/rlvm-export/`: `m.sh` (one measured command), `measure-all.sh` (the table above).
Native image: `native-image -cp rlvm-export/target/classes -o rlvm-export/target/native/exportload
--no-fallback -O3 rlvm.export.tools.ExportLoad` (40 s; no reflection or resource configuration needed).

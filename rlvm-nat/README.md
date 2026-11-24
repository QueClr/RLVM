# rlvm-nat

Natural-number and string literal support for the RLVM kernel (Java package `rlvm.nat`, no dependencies):
the Nat literal operations of the Lean 4 kernel v4.36 with their exact semantics and guards, a store for
numbers that do not fit a `long`, decimal conversion, strict UTF-8, and the data needed to expand a
string literal.

Semantics were not only read from `refs/lean4/src/kernel/type_checker.cpp` and `src/runtime/object.cpp`:
77,720 results computed by the official 4.36.0 kernel itself (`Lean.Kernel.whnf`, under seven values
of `LEAN_NAT_MAX_SIZE`) and its def-eq verdicts on 462 string literals are replayed by the tests.

## Public API

| class | what it is |
|---|---|
| `Nat` | handles and all operations (static methods; `long` fast path inline, big path in the store) |
| `NatStore` | hash-consed store of the numbers `>= 2^63`: frozen base + per-worker overlay |
| `NatOp` | operation codes, constant names (`Nat.add` ...), arity, which ones are official / guarded |
| `Utf8` | strict UTF-8 validation / decoding / encoding on byte ranges; the official reader's lossy repair |
| `StrLit` | constructor form of a string literal (names, shape), `Char` validity, `Char.ofNat` |

`Limbs` (package-private) holds the arithmetic kernels on `long[]` limb ranges.

### Handles (`long`)

```
h >= 0                        the number h itself (every natural below 2^63); never stored anywhere
h = Nat.BIG | id, id >= 1     the number with that id in a NatStore; always >= 2^63   (Nat.BIG = Long.MIN_VALUE)
other negative h              not a number: Nat.NONE (= Long.MIN_VALUE, id 0), Nat.TOO_BIG, Nat.COUNT_TOO_BIG, Nat.MALFORMED
```

`Nat.isSmall / isBig / isValue / isError`, `Nat.bigId(h)`, `Nat.ofBigId(id)`. The encoding is canonical:
within one store view `a == b` on handles is equality of the numbers, so a handle can be stored in a term
node and used as a hash key as it is. The small/big split is the official runtime's scalar/mpz split, in
whose terms the size limit is defined (8 bytes below 2^63, else 8 bytes per 64-bit limb).

### Operations (`Nat`)

All take the store first: `add sub mul div mod gcd land lor xor shiftLeft shiftRight pow succ` return a
handle; `beq ble` (and `blt`, `compare`) return `boolean` / `int`; `apply2(store, NatOp.X, a, b)` and
`apply1` dispatch on a code (predicates give 1 / 0). Utilities that the v4.36 kernel does *not* accelerate:
`log2`, `pred` (literal `n > 0` viewed as `Nat.succ (n - 1)`), `isZero`, `bitLength`, `sizeInBytes`,
`fitsLimit` (the `infer_lit` check). Conversions: `parseDecimal(store, bytes, from, to)`,
`parseDecimalLenient` (grammar of the official reader), `toDecimalString`, `ofUnsignedLong`,
`ofBigInteger` / `toBigInteger`, `transfer(src, h, dst)`, `describe`.

Semantics, as the official kernel computes them (`reduce_nat`; "error" = kernel exception there):

| operation | result | guard |
|---|---|---|
| `succ a` | `a + 1` | result larger than the limit -> `TOO_BIG` |
| `add`, `sub`, `mul` | exact; `sub` is 0 when `a <= b` | result larger than the limit -> `TOO_BIG` (also `x - 0`, `x * 1`) |
| `div`, `mod` | floor / remainder; `a / 0 = 0`, `a % 0 = a` | none |
| `gcd` | `gcd 0 b = b`, `gcd a 0 = a` | none |
| `beq`, `ble` | `Bool.true` / `Bool.false` | none |
| `land`, `lor`, `xor` | bitwise | none |
| `shiftRight a b` | `floor(a / 2^b)`; 0 as soon as `b >= bitLength(a)`, also for `b >= 2^64` | none |
| `shiftLeft a b` | `a * 2^b` | `a = 0` -> 0 for **any** `b`; else `b > 2^32-1` -> `COUNT_TOO_BIG`; else `size(a) + b/8 + 1 > limit` -> `TOO_BIG`; result not checked again |
| `pow a b` | `a^b` | `b > 2^32-1` -> `COUNT_TOO_BIG` **even for a = 0 and a = 1**; else `a > 1 && b != 0 && size(a) > limit / b` -> `TOO_BIG`; result not checked again |

`size(n)` = 8 if `n < 2^63`, else `8 * ceil(bitLength / 64)`; divisions are integer divisions; the limit is
`LEAN_NAT_MAX_SIZE`, 128 MiB = 134217728 by default (`Nat.OFFICIAL_MAX_BYTES`). Consequences: a word-sized
base >= 2 admits exponents up to 2^24; `2^(2^24)` is computed, `2^(2^24+1)` is an error;
`1 <<< 1073741759` is computed (128 MiB), `1 <<< 1073741760` is an error.

### Limits as parameters (compute / leave stuck / decline)

* The limit of the result-checked operations is a property of the store: `NatStore.newBase(maxBytes)`,
  `base.newOverlay(maxBytes)`, default the official value. `pow` and `shiftLeft` also take it as an explicit
  argument: `Nat.pow(store, a, b, limitBytes)`, `Nat.shiftLeft(store, a, b, limitBytes)`.
* `Nat.powGuard` / `Nat.shiftLeftGuard(store, a, b, limitBytes)` evaluate the guard only (constant time)
  and return `Nat.OK`, `COUNT_TOO_BIG` or `TOO_BIG`. A kernel with its own, lower work limit asks twice:
  with `OFFICIAL_MAX_BYTES` (an error means the official kernel rejects the declaration) and with its own
  limit (an error means "too expensive for us": leave the application stuck, or decline).
* Hard limit: no stored value exceeds `NatStore.HARD_MAX_BYTES` = 128 MiB; larger limits are treated as
  that. A raised `LEAN_NAT_MAX_SIZE` therefore cannot be mirrored.
* What the others do: nano-lean declines `pow` / `shiftLeft` when the count exceeds 2^24 or the result would
  exceed 2^26 bits (and refuses numerals above 65536 digits); rpylean declines above a count of 2^24;
  gruszka rejects results above 128 MiB like the official kernel; sokonanoda panics on a shift count that
  is not a machine word and tenet-jvm runs out of memory on `bugs/nat-shift-huge`.
* Cost near the limit (host, cold JVM, wall): `3^(2^20)` 0.10 s, `3^(2^22)` 0.35 s (so about 3 s for
  `3^(2^24)`), `(2^64)^(2^23)` 0.07 s, product of two 16 Mbit numbers 2.0 s, of two 64 Mbit numbers 13.4 s.
  `BigInteger` has no FFT multiplication: by extrapolation the largest operations the official guards
  admit (a 2^30-bit product or power) take minutes here; the official kernel uses GMP for them (not
  measured). A work limit of about 2^26 bits (8 MiB) for results of `mul` / `pow` keeps a single operation
  at about 5 s or less (interpolated from the two products above).

### Store (`NatStore`)

```java
NatStore base = NatStore.newBase();                 // loader thread: literals of the export
long lit = Nat.parseDecimal(base, buf, from, to);   // small handle, or BIG | id with id = 1, 2, ...
base.freeze();                                      // from now on immutable, shared by all workers
NatStore s = base.newOverlay();                     // one per worker; ids start at base.nextId()
long r = Nat.mul(s, lit, lit);                      // base values keep their handle; new ones go to s
int m = s.mark();  ...  s.truncate(m);              // release the values interned since the mark (LIFO)
s.reset();                                          // session boundary: drop the whole overlay
```

* A value is a run of little-endian 64-bit limbs in one `long[]` slab per layer; per id there is an offset,
  a length and a hash in `int[]` arrays; the table is open addressing over ids. No object per value, no
  allocation per operation (except slab growth and the `BigInteger` delegations listed below).
* Lookup probes the base table first (read-only), then the overlay's. A frozen base answers every
  read-only question from any thread (`compare`, `ble`, `beq`, `bitLength`, `sizeInBytes`, `toBigInteger`,
  printing, and operations whose result is small or an operand); anything that has to build limbs needs
  an overlay and throws `IllegalStateException` on a frozen store.
* `bytesUsed()` is what the layer retains (8 per limb); `count()`, `firstId()`, `nextId()`, `limbCount(id)`,
  `limb(id, i)`, `hashOf(id)` give raw access.
* `truncate` restores the exact earlier state (ids are reused); `reset` also returns large slabs
  (> 512 KiB) and large tables to the GC.

### Strings (`Utf8`, `StrLit`)

* `Utf8.validate(b, from, to)` -> number of scalar values, or `~i` for the first ill-formed byte index;
  `decode(...)` into an `int[]`; `codePointAt(b, pos, to)` (strict, -1 if ill-formed),
  `encodedLength(cp)`, `encode(cp, dst, off)`, `startOfPrevious(b, from, pos)` (walk backwards),
  `equal` / `compare` / `hash` of two literals, `isScalarValue`.
* The v4.36 kernel expands the literal with scalar values `c1 .. cn` (`string_lit_to_constructor`,
  `kernel/inductive.cpp:1356`) to

  ```
  App (Const String.ofList []) (cons c1 (... (cons cn nil)))
  cons c l = App (App (App (Const List.cons [Level.zero]) (Const Char [])) (App (Const Char.ofNat []) (Lit (natVal c)))) l
  nil      = App (Const List.nil [Level.zero]) (Const Char [])
  ```

  one element per scalar value, never per byte or UTF-16 unit. `String.ofList` is a *definition* in 4.36
  (`String` is a structure over `ByteArray` with constructor `String.ofByteArray`), not the `String.mk` of
  older descriptions, so the kernel always calls `whnf` on the expansion. It is used for a literal as
  recursor major, as the structure of a projection, and in def-eq against `String.ofList x`; two literals
  are compared by their bytes. `StrLit` has the constant names, `chars` / `length`, and
  `isValidChar(n)` / `charOfNat(n)`: `Char.ofNat n` is the character `n` if `n < 0xD800` or
  `0xDFFF < n < 0x110000`, and the character 0 otherwise. Confirmed by the official kernel:
  `"\0" =?= String.ofList [Char.ofNat 0xD800]` is true, `"A" =?= String.ofList [Char.ofNat (0x41 + 2^32)]`
  is false.

## Invariants

1. A value below 2^63 is never in a store; a stored value is normalised (top limb non-zero) and unique per
   view. Every result of every operation is re-established in this form (tests check it on each result).
2. Ids: 0 is "none"; a base store uses `1 .. nextId-1`; an overlay continues at `base.nextId()`. An overlay
   handle means nothing in another overlay; use `Nat.transfer`.
3. Single writer per layer. The base is written by one thread and published after `freeze()` (thread
   start or another safe publication); an overlay belongs to one thread.
4. Handles released by `truncate` / `reset` must not be used again. An id that is not live is detected
   (`IllegalArgumentException`); a reused id cannot be.
5. Error handles are never numbers: value-returning operations propagate them (first operand first),
   predicates and queries throw. Nothing malformed is ever turned into a number.
6. No stored value exceeds 2^30 bits; no `BigInteger` operation can overflow its own range
   (`mul` refuses by operand lengths before computing, `pow` / `shiftLeft` by their guards).

## Representation: limb slab, not `BigInteger` objects (measured)

Both designs were built and measured with the same hash-consing, handle encoding and loop
(`rlvm.nat.bench.NatBench`: `nat.*` = this module, `big.*` = a `BigInteger[]` side table in the test tree).
Instructions per operation (user space, all threads, JIT and GC included); the full table is below.

| what | limb slab vs `BigInteger` side table (instructions) |
|---|---|
| result leaves the word (63-bit add, 40x40-bit mul), 128-bit `div` / `mod` by 2^64 | 1.4 to 2.8 times fewer |
| big against word-sized operand (256-bit `add mul div mod gcd`; `land`) | 1.1 to 2.5 times fewer; `land` 11 times |
| `add`, `sub` at 256 / 4096 / 609,961 bits | 1.2 to 1.4 times fewer |
| `land`, `lor`, `xor` at 256 / 4096 / 609,961 bits | 1.6 / 4.0 / 6.0 to 6.6 times fewer |
| `mul`, long sparse operand times 781 bits (`perf/gf3-matrix-product`) | 6.3 times fewer |
| `mul`, dense, 256 / 1024 / 2048 / 4096 bits | **1.4 / 1.75 / 1.4 / 1.4 times more** (1024 and 2048 bits from the calibration runs) |
| `div`, `mod` by a multi-limb divisor: 256 / 128 bits; 4096 / 2048 bits | **1.8 to 1.9 times more**; 1.1 times more |
| `pow` of a 256-bit base (exponent 2 to 4); of a 4096-bit base | **2.2 times more**; equal |
| `shiftLeft`, `shiftRight` at 4096 and 609,961 bits; `succ` at 609,961 bits | **1.3 to 2.2 times more** (`BigInteger` shifts and hash are tighter loops) |
| `beq`, `ble` on two big handles | 27 and 73 instead of 5 and 50 instructions (handles are validated) |
| CPU time | lower for the slab wherever it does the work itself, also where it executes more instructions (dense 256-bit `mul`, shifts, `succ`: no allocation, no GC work); higher where it delegates (`div`, `mod`, `pow`, 4096-bit `mul`) |

Decision: values live in the slab. It is the cheaper design for everything linear, for mixed word / big
arithmetic and at the operand sizes of the one arena test that is about big numbers; it produces no
garbage, shares a frozen base between workers without objects, and releases memory by moving one index.
Summing the per-operation numbers over one step of `gf3-matrix-product`'s inner loop (2 full and 2
half-length `shiftRight`, 2 `land`, 4 `mul`, 2 `add`, 7 `lor` / `xor`, 4 `succ` / `pred`) gives about 3.0 M
instructions against 12.8 M for the side table, i.e. about 7 G against 30 G for the test's 2,343 steps (an
estimate from the table, not a run of the test). `BigInteger` is kept as a coprocessor, reached through
byte arrays, where its algorithms matter:

* `mul` when the shorter operand has 40 limbs (2560 bits, `BigInteger`'s Karatsuba threshold) or more;
  below that a schoolbook kernel that skips zero limbs. Measured per product of two n-limb numbers: kernel
  about 22 n^2 instructions, `BigInteger` about 13 n^2 plus about 400 n for the three conversions; the
  curves cross near n = 50.
* `div` / `mod` by a divisor of two or more limbs that is not a power of two (single-limb divisors and
  powers of two are done on limbs), `gcd` of two big numbers,
* `pow` beyond a word-sized result (powers of a word-sized power of two are a shift),
* decimal conversion beyond 1200 digits: divide and conquer on top of `BigInteger` products, 0.32 s for
  1,000,000 digits where `new BigInteger(String)` takes 12.5 s (cold JVM, host); printing 0.49 s.

## Measured numbers

Method: `bench/modules/rlvm-nat/natbench.py` in the container with `--arena-limits` (8 CPUs, 16 GB),
GraalVM CE 25.0.5 JIT with `-Xbatch`, aarch64. Each case runs twice, with N and 3N operations after an
identical warm-up; the slope is the cost per operation, so JVM start-up and JIT compilation cancel.
`-Xbatch` matters: with background compilation the total instruction count of one run varies by more than
1e9, with it by about 0.1 %. Operands come from 256 pre-generated pairs; results are interned and the
overlay is truncated every 256 operations. CPU time is from a host shared with other jobs (treat it as
indicative); instruction counts do not depend on that. Raw data: `work/rlvm-nat/natbench-full.{log,json}`.

```
. ./env.sh && $MVN -o -q -pl rlvm-nat test-compile
bench/arena-docker.sh --arena-limits --no-network bench/modules/rlvm-nat/natbench.py \
    --out work/rlvm-nat/natbench-full.json           # all 124 cases, about 25 minutes
bench/arena-docker.sh --arena-limits --no-network bench/modules/rlvm-nat/natbench.py \
    --cases '^(baseline|nat\..*\.small)$' --repeat 2   # only the long fast paths
```

Long fast paths (both operands and the result below 2^63), net of the empty loop (4.1 instructions, 0.20 ns per iteration):

| operation | instructions | CPU ns |
|---|---|---|
| `add` | 3.0 | 0.02 |
| `sub` | 3.6 | 0.05 |
| `mul` | 9.0 | 0.37 |
| `div` | 7.0 | 3.65 |
| `mod` | 8.0 | 3.64 |
| `gcd` (62-bit operands, Euclid) | 271.1 | 65.05 |
| `land` | 2.5 | 0.04 |
| `lor` | 2.0 | 0.00 |
| `xor` | 2.5 | 0.03 |
| `shiftLeft` | 17.0 | 0.46 |
| `shiftRight` | 8.5 | 0.18 |
| `beq` | 4.0 | 0.06 |
| `ble` | 3.0 | 0.04 |
| `succ` | 3.5 | 0.02 |
| `pow` (bases 2..10, exponents 0..18) | 74.1 | 2.93 |
| `log2` | 8.0 | 0.22 |

(`mul` costs the same 9.0 when one operand is above 2^31. The `sub` row is from a separate run: the fast
path was made branch-free after the full run, before that it was 4.0 instructions and 0.42 ns because of a
mispredicted branch.)

Big paths, this module (`nat`) against the `BigInteger` side table (`big`); instructions and CPU ns per operation:

| operands | operation | nat instr | big instr | big / nat | nat ns | big ns |
|---|---|---|---|---|---|---|
| word-sized operands, result >= 2^63 | `add` | 158 | 218 | 1.38 | 5.1 | 14.6 |
|  | `mul` | 175 | 347 | 1.98 | 6.0 | 27.9 |
| 128-bit value against 2^64 | `div` | 368 | 723 | 1.96 | 13.1 | 35.7 |
|  | `mod` | 277 | 777 | 2.81 | 8.2 | 34.3 |
| 256-bit against 30-bit | `add` | 368 | 414 | 1.12 | 15.8 | 21.5 |
|  | `mul` | 417 | 529 | 1.27 | 16.2 | 26.0 |
|  | `div` | 404 | 536 | 1.33 | 35.0 | 67.0 |
|  | `mod` | 157 | 294 | 1.87 | 25.2 | 53.0 |
|  | `gcd` | 281 | 711 | 2.53 | 57.7 | 84.4 |
|  | `land` | 44.2 | 492 | 11.11 | 2.1 | 24.6 |
| 256-bit operands (div, mod: 256 / 128 bits) | `add` | 450 | 556 | 1.24 | 17.8 | 31.2 |
|  | `sub` | 255 | 309 | 1.21 | 8.4 | 13.1 |
|  | `mul` | 961 | 685 | 0.71 | 41.6 | 45.4 |
|  | `div` | 2378 | 1235 | 0.52 | 116 | 84.2 |
|  | `mod` | 2292 | 1298 | 0.57 | 112 | 78.9 |
|  | `gcd` | 44.0k | 42.3k | 0.96 | 1820 | 1709 |
|  | `land` | 352 | 594 | 1.69 | 13.2 | 32.6 |
|  | `lor` | 370 | 595 | 1.61 | 14.7 | 33.4 |
|  | `xor` | 377 | 598 | 1.59 | 14.0 | 37.7 |
|  | `shiftLeft` | 452 | 439 | 0.97 | 17.1 | 26.7 |
|  | `shiftRight` | 282 | 350 | 1.24 | 9.5 | 26.0 |
|  | `succ` | 328 | 354 | 1.08 | 13.6 | 17.3 |
|  | `pow` | 4064 | 1808 | 0.44 | 178 | 102 |
|  | `beq` | 27.2 | 5.4 | 0.20 | 1.5 | ~0 |
|  | `ble` | 72.2 | 48.2 | 0.67 | 2.8 | 2.4 |
| 4096-bit operands (div, mod: 4096 / 2048 bits) | `add` | 1920 | 2745 | 1.43 | 99.8 | 162 |
|  | `sub` | 1008 | 1285 | 1.27 | 44.3 | 67.0 |
|  | `mul` | 83.1k | 58.5k | 0.70 | 4956 | 3850 |
|  | `div` | 88.2k | 78.9k | 0.89 | 8635 | 8256 |
|  | `mod` | 88.7k | 79.0k | 0.89 | 8563 | 8233 |
|  | `gcd` | 4945k | 4940k | 1.00 | 181k | 181k |
|  | `land` | 1042 | 4123 | 3.96 | 35.1 | 192 |
|  | `lor` | 1040 | 4215 | 4.05 | 47.0 | 199 |
|  | `xor` | 1044 | 4163 | 3.99 | 32.6 | 199 |
|  | `shiftLeft` | 1779 | 1279 | 0.72 | 80.6 | 125 |
|  | `shiftRight` | 1003 | 758 | 0.76 | 45.9 | 61.0 |
|  | `succ` | 943 | 874 | 0.93 | 36.6 | 84.2 |
|  | `pow` | 217k | 208k | 0.96 | 13.9k | 14.4k |
|  | `beq` | 27.0 | 3.8 | 0.14 | ~0 | ~0 |
|  | `ble` | 74.1 | 53.2 | 0.72 | ~0 | ~0 |
| 609,961-bit operands (gf3-matrix-product; mul: sparse x 781 bits) | `add` | 203k | 285k | 1.40 | 13.8k | 16.5k |
|  | `sub` | 210k | 283k | 1.35 | 14.1k | 15.6k |
|  | `mul` | 297k | 1870k | 6.30 | 15.3k | 99.8k |
|  | `land` | 76.9k | 506k | 6.58 | 6053 | 23.0k |
|  | `lor` | 78.7k | 470k | 5.97 | 9627 | 20.3k |
|  | `xor` | 78.9k | 470k | 5.96 | 7366 | 18.5k |
|  | `shiftLeft` | 201k | 91.9k | 0.46 | 12.2k | 13.7k |
|  | `shiftRight` | 75.5k | 42.7k | 0.56 | 4172 | 5456 |
|  | `succ` | 69.0k | 50.7k | 0.73 | 5777 | 7111 |
|  | `beq` | 27.5 | 5.5 | 0.20 | 1.6 | 1.3 |

Bare `BigInteger` operations without any store (`jdk.*`): add.256 333, add.4096 2439, mul.256 451, mul.4096 58.4k, xor.256 381, xor.4096 3882 instructions.

A big operation costs at least about 160 instructions (validation of the handles, limb kernel, hash,
two table probes, insertion), about 350 to 450 for 256-bit operands.

## Tests

`$MVN -o -q -pl rlvm-nat test`: 54 tests in 7 classes; 53 pass, 1 is skipped unless requested (the large
corpora). About 50 s on an idle host.

| test class | what it checks |
|---|---|
| `OfficialVectorsTest` | 77,720 results of the official kernel for all 15 functions, limits 8 / 16 / 20 / 64 / 100 / 4096 / default, including the 128 MiB boundary cases |
| `NatOpsDifferentialTest` | every operation against `RefNat` (plain `BigInteger`, test tree): boundary matrix (2^31, 2^32, 2^62 .. 2^64, 2^128 ...), random operands up to 6000 bits, 300,000 random word pairs, guards of `pow` / `shiftLeft` under 23 limits, algorithm switches of `mul` / `div` / `mod`, algebraic identities, error handles |
| `LimbsTest` | each limb kernel against `BigInteger` with independent conversions, sentinels around every buffer, carry chains, 200,000 single-limb divisions, hash quality on structured values |
| `NatStoreTest` | layering, freezing, read-only use of the base, mark / truncate / reset against a model, growth and shrinking, limits, 8 threads on one frozen base |
| `NatDecimalTest` | parse / print round trips for every length up to 2000 digits and up to 300,000 digits, all thresholds, leading zeros, every byte value in every position, the official lenient grammar, numerals beyond the hard limit |
| `Utf8Test` | all scalar values; all 1-, 2- and 3-byte sequences and 33 million 4-byte sequences against a table-driven reference; 300,000 random byte strings against that reference and the JDK decoder; backward walk; comparison; the official reader's repair; the official kernel's verdicts on 462 literals |
| `RealFilesTest` | every line of the 230 arena tests and of init / std: UTF-8 validation, every `natVal` parsed and printed back, every `strVal` decoded and compared with the JDK; with `-Drlvm.nat.corpora=true` also mathlib, cslib, cedar, ... (run once, see below) |

Ground truth files `src/test/resources/rlvm/nat/official-{nat,str}-vectors.txt` are regenerated by
`src/test/lean/gen-official-vectors.sh` (needs only the Lean 4.36.0 toolchain; the Lean scripts call the
kernel's `whnf` / `isDefEq` on literals).

Sensitivity of the tests was checked by mutation (`src/test/tools/mutate.py`, not part of the build):
54 one-line semantic changes to `Nat`, `NatStore`, `Limbs`, `Utf8` and `StrLit` (comparison operators and
constants of the guards and fast paths, carry / borrow formulas, shift and division kernels,
normalisation, table maintenance, UTF-8 ranges, `Char` validity). 51 made a test fail; the other 3 are
equivalent changes (they cannot alter any result). The 21 mutants of `Nat` and of the first half of
`NatStore` were run before the last performance edits (`Limbs.add` / `sub` / `hash`, `Nat.sub`), the
mutants of the edited code after them. The first carry simplification tried in the multiplication kernel
was wrong and was caught by `LimbsTest` at once.

## Facts found on the way (for the kernel and reader)

* `Nat.pow 0 4294967296` and `Nat.pow 1 4294967296` are kernel exceptions in the official kernel (the
  count is checked before the base), while `Nat.shiftLeft 0 x` is 0 for any `x`. Verified by running it.
* The official *reader* is lenient, the kernel never sees malformed text: every line is read with
  `lean_mk_string_from_bytes`, which replaces each ill-formed position by U+FFFD and skips the following
  continuation bytes (`Utf8.repairLossy` reproduces it; checked against `leanchecker` on five variants of
  `bugs/str-lit-utf8`); a `\ud83d\ude00` escape pair is combined, a lone surrogate escape becomes U+FFFD;
  `natVal` goes through `String.toNat?`, which accepts leading zeros and `_` between digits.
  RLVM's default should stay strict (`Utf8.validate`, `Nat.parseDecimal`); the lenient variants exist if
  the reader must reproduce the official verdict on such files.
* Literals in the corpora: mathlib has 5,660 `natVal` records, 1,845 of them `>= 2^63`, the longest
  1,085 digits; all other corpora at most 672 digits; init + std have 14 literals `>= 2^63`. All
  numerals of all corpora (112 M lines of mathlib included) parse, print back identically, and all
  `strVal` decode like the JDK decodes them.
* Every big result stays in the overlay until `truncate` / `reset`. `perf/gf3-matrix-product` produces
  about 15 intermediates of 76 KiB per step (2,343 steps): the kernel has to release them (mark /
  truncate around evaluation regions, or session resets); `bytesUsed()` is there for budgets.
* The kernel never checks that `Nat`, `Nat.add` ..., `String.ofList`, `Char.ofNat`, `List.cons` have their
  standard definitions (research doc 03, section 8); this module assumes they do.

## Known gaps

* Where the `BigInteger` side table needs fewer instructions (table above): dense multiplication from 256
  bits up (1.4 to 1.75 times), division by multi-limb divisors (1.9 times at 256 bits: no Knuth D on limbs,
  two conversions), small powers of big bases (2.2 times), shifts of long numbers (up to 2.2 times), `beq` /
  `ble` on big handles (validation). A tighter kernel loop, a cheaper bridge than byte arrays, or unrolled
  shift loops would close them. How often such operations occur while checking the corpora is not
  measured (there is no kernel yet); the exports themselves contain few big literals (see above).
* No sub-quadratic multiplication of our own and no FFT: the largest operations the official guards admit
  take minutes, not seconds (see "Limits as parameters").
* The hard limit equals the official default (128 MiB); a larger `LEAN_NAT_MAX_SIZE` is not supported.
* The hash is not keyed (see `Limbs.hash`): inputs crafted against its constants could make lookups slow,
  never wrong.
* Lookup of an existing big value on a *frozen* store is not offered (it needs a writable layer).
* x86-64 instruction counts were not measured (this host is aarch64). `Math.multiplyHigh` /
  `unsignedMultiplyHigh` are single instructions on both; whether the JIT uses the same `BigInteger`
  intrinsics there was not checked.
* The gf3 estimate above is arithmetic on per-operation numbers; the test itself was not run (no kernel
  yet).

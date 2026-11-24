# bench/ — measuring checkers the way the Lean Kernel Arena does

The arena (<https://arena.lean-lang.org>, source in `refs/lean-kernel-arena`)
ranks checkers by (1) bad proofs wrongly accepted, (2) good proofs wrongly
rejected, (3) mathlib "virtual time" = `perf stat` instruction count / 6e9,
(4) declines. This directory reproduces that measurement on this machine
(aarch64, 20 cores) so that RLVM can be compared with the leading checkers
under identical conditions.

Third-party code (arena checkers, lean4export, mathlib, pypy, ...) is only ever
built and run inside the `rlvm-arena` container. Scripts live here (tracked in
git); data lives in `work/` (gitignored).

| file | what it is | runs |
|---|---|---|
| `docker/Dockerfile`, `docker/entrypoint.sh` | the image `rlvm-arena:latest` (Ubuntu 24.04, linux/arm64) | — |
| `arena-docker.sh` | runs a command in the image: project root at the same path, host uid, caches, perf privilege | host |
| `measure.sh` (→ `measure.py`) | one command under `perf stat` + GNU `time` like `lka.py`; prints one JSON line | container |
| `run-suite.py` | a checker over a tests directory (`good/`, `bad/`), scored like the arena; table, summary, JSON report | host or container |
| `compare-published.py` | a `run-suite.py` report against the arena's published results for a checker | host or container |
| `perf-validate.py`, `perf-validate/` | checks that the instruction counts are trustworthy, and how stable they are | container |
| `selftest.py`, `fake-checkers/` | smoke test of all of the above with fake checkers | host and container |
| `build-corpus.sh` | builds the arena's complete test set (mathlib included) with the arena's own `lka.py build-test` and collects it in `work/exports/` | host (starts containers) |
| `arena-lka.py` | runs the arena's `lka.py` with bash as its shell, as on the arena | container |
| `collect-exports.py` | lays the built exports out as `work/exports/all/{good,bad,either}/` with a `manifest.json` | host or container |
| `export-stats.py` | streaming statistics of exports (`work/exports/STATS.md`) | host or container |
| `export-slice.sh`, `lean/ListModuleDecls.lean` | partial exports of a built project: mid-size slices of mathlib | container |
| `baselines/build-checker.sh` | builds arena checkers (nano-lean, gruszka, sokonanoda, official, ...) with the arena's own `lka.py build-checker` | host (starts containers) |
| `baselines/NAME.sh`, `baselines/gen-wrappers.py` | runs one baseline checker on one export exactly as the arena does; generated from the arena's yaml | container |
| `baselines/validate.sh`, `baselines/summary.py` | a baseline over the published tests with instruction counts; one table over the reports | host |
| `baselines/repeat.sh` | N measured runs of baselines on chosen tests: how repeatable their counts are | container |
| `baselines/big.sh` | a baseline, or any command such as RLVM, on the big corpora (init, std, mathlib, ...): one measured run per fresh container, records in `runs.jsonl` | host (starts containers) |
| `baselines/big-all.sh` | the complete series of `big.sh` calls behind `baselines.json` | host (starts containers) |
| `baselines/big-summary.py` | tables per corpus, aarch64/x86-64 ratios, thread scaling; writes `baselines.json` | host or container |
| `baselines.json` | the result: every baseline on init, std and mathlib on this machine (what RLVM has to beat) | — |
| `baselines/profile-mathlib.sh` | per-declaration cost profiles of nano-lean, gruszka, sokonanoda and rpylean, from the profiling switches of those checkers | container |
| `baselines/instrtap.c`, `baselines/instrtap-test.c`, `baselines/InstrtapTest.java` | `LD_PRELOAD` library: the calling thread's instruction count at every per-declaration line an unmodified checker prints; its self-tests (C, JVM) | container |
| `baselines/profile-summary.py` | the profiles as `work/baselines/mathlib-profile.json`, `.tsv.gz` per checker, tables | host or container |
| `baselines/render-tables.py` | refreshes the generated tables in `docs/research/12-baselines.md` and in this file | host |

## Quick start

```bash
bench/arena-docker.sh --build                 # build the image (about 2 min, 1.9 GB)
bench/arena-docker.sh                         # interactive shell in the container
bench/arena-docker.sh bench/selftest.py       # everything works? (45 s)

# measure one command (one JSON line on stdout); here the official kernel, whose
# Lean toolchain is selected by work/selftest/official/lean-toolchain
bench/arena-docker.sh bench/measure.sh --cwd work/selftest/official \
    --in work/arena-site/tests/good/init-prelude.ndjson -c 'leanchecker --from-export "$IN"'

# our own checker on the host: correctness only, no perf needed
bench/run-suite.py -j 8 --label rlvm -- java -jar build/rlvm.jar '"$IN"'

# any checker in the container, with instruction counts, sized like the arena runner
bench/arena-docker.sh --arena-limits bench/run-suite.py --perf --label rlvm -- \
    java -jar build/rlvm.jar '"$IN"'
```

## The image

`bench/arena-docker.sh --build` = `docker build --platform linux/arm64 -t rlvm-arena:latest bench/docker`.

Contents (versions as built on 2026-11-24, recorded in the image at `/opt/seed/VERSION`):

- Ubuntu 24.04 with build-essential (gcc 13.3), clang, lld, mold, cmake, ninja,
  pkg-config, git, curl, wget, jq, zstd, unzip, xz, bzip2, rsync, strace,
  hyperfine, libgmp-dev, libffi-dev, zlib1g-dev, libssl-dev, libzstd-dev.
- python3 3.12 with jinja2, yaml, jsonschema, markdown (all that `lka.py` imports).
- GNU time 1.9 (`/usr/bin/time`).
- perf 7.0.14, from `linux-tools-generic-hwe-24.04` (the host kernel is 7.0).
  `/usr/local/bin/perf` links to the real binary, because Ubuntu's
  `/usr/bin/perf` wrapper insists on a build for exactly `uname -r`.
- just 1.59.0 (release binary, sha256-pinned; Ubuntu's 1.21 cannot read
  rpylean's justfile).
- PyPy 2.7 v7.3.23 as `pypy` / `pypy2` (official aarch64 tarball, sha256-pinned),
  to run RPython for rpylean.
- rustup 1.29.3 (sha256-pinned) with stable Rust (1.100.0 at build time),
  profile minimal plus `llvm-tools`, `rustfmt`, `clippy`. `llvm-profdata`,
  `llvm-cov`, `llvm-objdump`, ... in `/usr/local/bin` dispatch to the
  llvm-tools of whichever toolchain is active, so PGO scripts get a
  `llvm-profdata` that matches `rustc`.
- elan 4.4.0 (sha256-pinned), no default toolchain.
- Not included (only needed by checkers we do not build): ocaml/opam, ghc,
  dotnet, zig, nodejs, binaryen, monolith, texlive. Java is the project's own
  GraalVM, mounted from `.toolchain/`.

To add a package: edit `docker/Dockerfile`, run `bench/arena-docker.sh --build`.
For a quick experiment use `bench/arena-docker.sh --root` (apt-get works there;
the change is gone when the container exits).

## arena-docker.sh

```
bench/arena-docker.sh [options] [--] [command [args...]]      (--help lists all options)
```

What a container gets:

- **Mounts.** The project root at the same absolute path, **read-only**, with
  `work/` mounted read-write on top. Third-party code can therefore write only
  below `work/`. `--rw` mounts the whole root read-write (e.g. to run Gradle in
  the container); `--mount SRC[:DST][:ro]` adds a bind mount.
- **User.** The command runs as the calling uid:gid, so nothing root-owned is
  left behind. (`--root` stays root; on exit everything root-owned below the
  writable mounts is chowned back. If such a container is killed with
  `docker kill`, that step is skipped; run any `--root` command once more to
  repair.)
- **Environment.** `JAVA_HOME=<root>/.toolchain/graalvm-ce-25` and its `bin` in
  `PATH`; `RUSTUP_HOME`, `CARGO_HOME`, `ELAN_HOME` and `HOME` point into
  `work/cache/{rustup,cargo,elan,home}`.
- **Caches.** On first use the entrypoint copies the image's rustup, cargo and
  elan homes (720 MB) into `work/cache/`; from then on toolchains installed by
  `rustup` or `elan`, the cargo registry, `~/.gradle`, `~/.m2` etc. persist
  between runs. After rebuilding the image with a newer Rust, delete
  `work/cache/rustup` and `work/cache/cargo` to re-seed them.
- **Privileges** (see next section): `--cap-drop ALL`, `no-new-privileges`, and
  only `CAP_PERFMON` for the command.
- **Network.** Default bridge network; `--no-network` for `--network none`
  (good practice when only running, not building, third-party checkers).
- **Resources.** `--arena-limits` restricts the container to 8 performance
  cores (CPUs 5-9,15-17) and 16 GB RAM, the size of the arena runner
  (x86-64, 8 vCPU, 16 GB). This matters for anything that sizes itself by the
  machine, above all the JVM: with it, `java` picks 4 JIT compiler threads,
  8 GC threads and a 4 GB default heap; without it 12, 15 and 30 GB.
  `--cpus`, `--cpuset-cpus`, `--memory` are passed to docker.

Batch jobs:

```bash
# foreground; put it in the background of your own shell if you like
bench/arena-docker.sh some long command > work/logs/job.log 2>&1

# detached: survives the calling shell; output goes to the log file
bench/arena-docker.sh --detach work/logs/job.log some long command
bench/arena-docker.sh --wait work/logs/job.log    # blocks; exits with the job's exit status
cat work/logs/job.log.exit                        # exit status, once finished
bench/arena-docker.sh --ps                        # containers started by this script
docker kill <name>                                # stop one of them
bench/arena-docker.sh --kill-all                  # stop ALL of them, other people's and agents' too
```

All containers are `--rm`, labelled `rlvm-arena=1`, and run under docker's
init, so SIGTERM/SIGINT to the script stops the container (exit status 143 / 130).

### Privileges needed for perf

The host has `kernel.perf_event_paranoid=4`: without a capability nobody may
open a perf event, on the host or in a container. What was tried, in order:

| container flags | as root | as host uid |
|---|---|---|
| none | `No permission to enable instructions:u event` | same |
| `--cap-add PERFMON` | **works** | fails: a non-root process does not get the capability |
| `--cap-add PERFMON` + entrypoint raising it as an *ambient* capability | — | **works** (what we use) |
| `+ --security-opt seccomp=unconfined`, `--cap-add SYS_ADMIN`, `--privileged` | not needed | not needed |

So the minimum is `CAP_PERFMON`; Docker's default seccomp profile already
allows `perf_event_open` when that capability is granted. The exact flags:

```
--cap-drop ALL --cap-add SETUID --cap-add SETGID --cap-add SETPCAP --cap-add KILL
--cap-add PERFMON --security-opt no-new-privileges
```

SETUID/SETGID/SETPCAP are used once by the entrypoint (as root) to switch to
the host uid:gid with `setpriv --ambient-caps +perfmon --bounding-set -all,+perfmon`;
KILL lets docker's init forward signals to the command. The command itself
runs with exactly `CapEff = CapBnd = CAP_PERFMON`, `NoNewPrivs: 1`, the default
seccomp filter. (File capabilities on the perf binary would also work but make
perf "secure-exec", which strips `LD_LIBRARY_PATH`, `TMPDIR` and
`GLIBC_TUNABLES` from the environment of the measured command.)
`--perf seccomp|sysadmin|privileged` exist as fallbacks for other hosts;
`--perf none` gives a container without counters.

No host setting was changed.

## measure.sh

```
bench/arena-docker.sh bench/measure.sh [options] -- command [args...]
bench/arena-docker.sh bench/measure.sh [options] --in FILE -c 'shell command using "$IN"'
```

Runs the command exactly as `lka.py` (`measure_perf_with_fallback`) runs a checker:

```
perf stat --no-scale -j -o TMP -e duration_time -e task-clock -e instructions:u [-e instructions:k] -- \
    time -f "real_seconds=%e\nuser_seconds=%U\nsys_seconds=%S\nmax_rss_kb=%M" -o TMP -- command
```

with `LC_ALL=C` in the command's environment (the arena sets it too: a checker
sees the C locale), and prints one JSON line:

```json
{"exit_code": 0, "timed_out": false, "instructions": 507952127, "virtual_time_s": 0.0847,
 "cpu_time_s": 0.0363, "wall_time_s": 0.0379, "max_rss_bytes": 8200192,
 "instructions_kernel": 8424392, "counter_coverage": 1.0, "user_time_s": 0.03, "sys_time_s": 0.0,
 "instructions_source": "perf stat --no-scale -e instructions:u, summed over PMUs",
 "cpu_time_source": "perf task-clock"}
```

- `instructions`: user-space instructions of the whole process tree (all
  threads and children); `virtual_time_s = instructions / 6e9`, the arena's
  ranking metric. `cpu_time_s` is perf's task-clock, `wall_time_s` its
  duration_time, `max_rss_bytes` GNU time's `%M` × 1024.
- `exit_code` is what perf returns, as in `lka.py`: the command's exit status;
  137 if it died of SIGKILL; -9 with `"timed_out": true` if `--timeout` expired
  (then the whole process group is killed and there are no counters, as on the arena).
- `counter_coverage` is a self-check: the fraction of the task's CPU time
  during which the instruction counters were counting. It must be 1.0 (it was
  in every run made so far, for threads, forks and the JVM alike); a lower
  value means the counters were multiplexed and the count is too low, and
  `measure.sh` and `run-suite.py` then print a warning.
- The command's stdout is sent to stderr so that stdout carries only the JSON
  line; `-o FILE` writes the JSON to a file instead and leaves stdout alone.
- `measure.sh` exits 0 whatever the command did (`--propagate-exit-code` to
  change that), 3 with `--require-perf` if counters are unavailable.

Two deliberate differences from the arena's command line, both needed to get
the arena's *numbers* on this machine:

1. **`instructions:u`, not `instructions`.** The arena runner has
   `perf_event_paranoid=2`, where perf silently falls back to user-space-only
   counting. Here perf holds CAP_PERFMON and would add the kernel's
   instructions. The kernel share is reported separately as
   `instructions_kernel` (it is not part of the arena's metric; for a JVM it
   is 7–19 % of the total, for the C++ kernel typically around 1 %).
2. **`--no-scale`, summed over PMUs.** This host is big.LITTLE (Cortex-X925 on
   CPUs 5-9,15-19 = PMU `armv8_pmuv3_1`; Cortex-A725 on CPUs 0-4,10-14 =
   `armv8_pmuv3_0`). perf opens one `instructions` event per PMU; each counts
   only while a thread runs on that core type, and by default perf
   extrapolates ("scales") each to the whole run, so the two numbers add up to
   nonsense (observed: 1.31e9 and 0.92e9 for the same 0.515e9-instruction
   program). With `--no-scale` the raw counts add up to the true total.

For the same reason **`lka.py run` cannot measure instructions on this host**:
it gets events named `armv8_pmuv3_0/instructions/`, which its parser does not
recognise (it records 0), and they would be scaled and include the kernel.
Use `run-suite.py --perf` instead.

Without hardware counters (on the host, or `--perf none`) `instructions` and
`virtual_time_s` are `null` and `cpu_time_s` is GNU time's user+sys, marked
`"cpu_time_source": "GNU time user+sys (FALLBACK: no perf counters)"`.

## run-suite.py

```
bench/run-suite.py [options] [--] CHECKER COMMAND ...        (--help lists all options)
```

- The checker command is a shell command run with `sh -c` once per test, like
  a `run:` entry of the arena. The export's path is in `$IN` (write `'"$IN"'`
  so that your own shell does not expand it), or use `{IN}`, which is replaced
  by the quoted path. Exit status 0 accept, 1 reject, 2 decline, other = error.
  The checker's stdin is `/dev/null`.
- `--tests DIR` (default `work/arena-site/tests`): `good/**/*.ndjson` must be
  accepted, `bad/**/*.ndjson` rejected, and `either/**/*.ndjson`, if present,
  may go either way. A test's name is its path below the bucket without the
  extension (`perf/app-lam`), as in the arena.
- `--timeout S` (default 120; 0 = none) kills the checker's whole process
  group. `--arena-timeouts` uses the arena's own per-test timeout where it has
  one (`perf/*` 40–360 s, `init-prelude` 40 s, `mathlib` 4120 s, ...).
- `-j N` runs tests in parallel. Verdicts and instruction counts do not care;
  wall and CPU times do, so use `-j 1` (the default) when those matter.
- `--perf` wraps each run in the measurement of `measure.sh` (same code,
  imported from `measure.py`). It is refused with exit status 3 where counters
  are unavailable, rather than silently measuring something else. Without
  `--perf` (host use) the wall time, and CPU time and max RSS from GNU time,
  are still recorded.
- `--only GLOB`, `--exclude GLOB` select tests by name; `--list` shows them;
  `--cwd DIR` is the checker's working directory; `--env VAR=VALUE`.
- Output: a table of every test that is not correct (expected, status, exit
  status, wall time, last line of output), one `SUMMARY` line, and a JSON
  report (`--report FILE`, default `work/suite-reports/<label>-<UTC time>.json`)
  with `meta`, `summary` and per-test `results` (field names as in the arena's
  results: `status`, `correctness`, `exit_code`, `wall_time`, `cpu_time`,
  `max_rss`, `instructions`, plus `virtual_time`, `instructions_kernel`,
  bounded `stdout`/`stderr`).
- Exit status: 0 if nothing is incorrect, in error or timed out (declines are
  allowed); 1 otherwise.

Scoring is the arena's (`run_checker_on_test`, `compute_checker_stats`):
status `accepted | rejected | declined | error | timeout`; correctness
`correct | incorrect | either | declined | error`; errors and timeouts count
like declines (neither right nor wrong). `summary.arena_sort_key` is
`[bad accepted, good rejected, declined+error+timeout]`, the arena's ranking
key without the mathlib term.

Tests with outcome `either` (30 corner cases in `refs/lean-kernel-arena/tests/corner-cases/`):
the published tarball does not contain them, so the default tests directory
has none. The complete test set in `work/exports/all` (see "The test corpus"
below) has them under `either/`; in addition any test that the metadata marks
`either` is scored as such wherever it lies. The metadata (`--meta`) is a
`manifest.json` in the tests directory (written by `collect-exports.py`), else
the arena's `results.json` (next to the tests directory, i.e.
`work/arena-site/results.json`), else a checkout of its `tests/` directory; it
also supplies `--arena-timeouts`.

```bash
bench/compare-published.py work/suite-reports/<report>.json --checker official
```

compares a report with what the arena published for one of its checkers:
verdict agreement, and the ratio of instruction counts (ours/arena) overall
and per heavy test.

## The test corpus (`work/exports/`)

The arena does not publish its large exports (the tarball stops at 10 MiB and
leaves out the `either` tests), so the complete test set, mathlib above all, is
built here with the arena's own tooling: `lka.py build-test` of
<https://github.com/leanprover/lean-kernel-arena> at the revision the
published results were made from (`99195b6b`, 130 test definitions, 270
tests), inside the container.

```bash
bench/build-corpus.sh             # everything: clone, toolchains, build, collect, stats, slices
bench/build-corpus.sh --help      # steps can be run one by one; --tests 'PAT ...' builds a selection
```

**Result: all 270 exports are byte-identical to the arena's** (sha256 equal to
the one in the published `results.json`), mathlib included
(`f749dfd084fc…`, 6,326,141,213 bytes, 112,081,718 lines). lean4export's
output does not depend on the platform, so what RLVM is measured on here is
exactly the file the arena checks.

Checked end to end with the official kernel (`leanchecker` v4.35.0) through
`run-suite.py --tests work/exports/all`, on everything except mathlib, cslib
and navier-stokes-euler: 267 of 267 verdicts equal the published ones (237
correct; 28 of the 30 `either` tests decided, the other two time out after
60 s as they do on the arena), and its instruction count is 0.945 of the
arena's in total (report: `work/suite-reports/official-v4.35.0-all-exports.json`).
The official kernel on the three largest corpora was not run as part of
building the corpus.

### Layout

```
work/exports/
  init-prelude.ndjson init.ndjson std.ndjson mathlib.ndjson cslib.ndjson cedar.ndjson
  con-leche.ndjson navier-stokes-euler.ndjson reachability.ndjson     the nine top-level corpora
  all/good/**/*.ndjson      148 tests that must be accepted   (tutorial/, perf/, bugs/, other/, corner-cases/, top level)
  all/bad/**/*.ndjson        92 tests that must be rejected
  all/either/**/*.ndjson     30 corner cases where both verdicts are fine
  all/manifest.json         one entry per test: name, outcome, bucket, path, source, size, lines, sha256,
                            timeout (the arena's, seconds; null = none), lean_version, in_arena_tarball,
                            sha256_matches_arena, description, ...
  STATS.md, stats/*.stats.json    statistics of the nine corpora (export-stats.py)
  slices/good/*.ndjson      mid-size parts of mathlib, see below; slices/NAME.info.json says how each was made
  slices/STATS.md, slices/stats/, slices/official-v4.35.0.report.json
```

Everything outside `slices/` is a relative symbolic link into
`work/arena-run/_build/tests/`, where `lka.py` puts what it builds; nothing is
copied. The names are the arena's (`perf/app-lam`, `tutorial/001_basicDef`,
`mathlib`). The tests the published tarball lacks are the 30 `either` tests
and the 10 exports over 10 MiB (the eight large corpora,
`perf/magma-string-n4`, `perf/magma-string-pair-n9`).

```bash
# a checker over the complete set, like the arena (expected outcomes, `either`
# and the arena's timeouts come from all/manifest.json)
bench/arena-docker.sh --arena-limits --no-network bench/run-suite.py --perf --arena-timeouts \
    --tests work/exports/all --label NAME --cwd CHECKER_DIR -- 'CHECKER_CMD "$IN"'
#   ... --only mathlib             the ranking test alone (official kernel: 37 min wall on the arena, 10 GB)
#   ... --exclude mathlib --exclude cslib --exclude navier-stokes-euler -j 8      everything else, quickly

# one export, one JSON line
bench/arena-docker.sh --arena-limits --no-network bench/measure.sh --timeout 4120 \
    --in work/exports/mathlib.ndjson -c 'java -jar build/rlvm.jar "$IN"'

# fast iteration: the slices (all must be accepted)
bench/arena-docker.sh --arena-limits --no-network bench/run-suite.py --perf --tests work/exports/slices \
    --timeout 1200 -- 'CHECKER_CMD "$IN"'
```

### How it is built, step by step

`build-corpus.sh` does the following (each line is one of its steps; all
third-party code runs in the container, as the host uid, with only `work/`
writable):

```bash
# clone: the arena at the revision of the published results (work/arena-site/results.json, meta.git_revision)
bench/arena-docker.sh bash -c 'git clone https://github.com/leanprover/lean-kernel-arena work/arena-run &&
    cd work/arena-run && git checkout 99195b6b009bc3df2c946ddbcfa4a2ab1eb40350'

# toolchains: elan fetches the linux_aarch64 builds (work/cache/elan); v4.36.0 is tests/lean-toolchain,
# the other two are what navier-stokes-euler and reachability pin
for t in v4.36.0 v4.34.0-rc2 v4.34.1; do bench/arena-docker.sh elan toolchain install leanprover/lean4:$t; done

# build: lka.py build-test, six containers side by side (logs: work/logs/corpus-GROUP.log)
A=$PWD/work/arena-run; L=$PWD/bench/arena-lka.py
bench/arena-docker.sh --name rlvm-corpus-mathlib --detach work/logs/corpus-mathlib.log -w $A $L -v build-test mathlib
bench/arena-docker.sh --name rlvm-corpus-core    --detach work/logs/corpus-core.log    -w $A $L -v build-test init-prelude init std
bench/arena-docker.sh --name rlvm-corpus-cslib   --detach work/logs/corpus-cslib.log   -w $A $L -v build-test cslib
bench/arena-docker.sh --name rlvm-corpus-cedar   --detach work/logs/corpus-cedar.log   -w $A $L -v build-test cedar con-leche
bench/arena-docker.sh --name rlvm-corpus-nse     --detach work/logs/corpus-nse.log     -w $A $L -v build-test navier-stokes-euler reachability
bench/arena-docker.sh --name rlvm-corpus-small   --detach work/logs/corpus-small.log   -w $A $L -v build-test tutorial 'bugs/*' 'corner-cases/*' 'perf/*' 'other/*'
for g in mathlib core cslib cedar nse small; do bench/arena-docker.sh --wait work/logs/corpus-$g.log; done

# collect: links + manifest, compared with the published sizes and hashes (--verify-hashes rehashes all 13 GB)
python3 -I bench/collect-exports.py

# stats
python3 -I bench/export-stats.py -j 9 --out-dir work/exports/stats --annotate work/exports/all/manifest.json \
    --order init-prelude,reachability,init,std,con-leche,cedar,navier-stokes-euler,cslib,mathlib \
    --md work/exports/STATS.md work/exports/*.ndjson

# slices: see below
```

What `lka.py build-test` does for a test, for reference: for a `leanfile:`
test (init, std, perf/*, most bugs) it copies the Lean file as `Test.lean`
into a fresh one-library Lake project with `tests/lean-toolchain`; for a
`url:` test it fetches the pinned commit (`git fetch --depth 1 origin REV`);
then `pre-build` (mathlib, cslib, navier-stokes-euler: `lake exe cache get`),
`lake build MODULE`, and `lake env leanexport MODULE [-- DECLS] > NAME.ndjson`
with the `leanexport` shipped in the toolchain (lean4export 3.1.0; for
toolchains before v4.35.0 it clones lean4export's master and builds it with
that toolchain into `work/arena-run/_build/lean4export/`). `file:` tests are
hand-written exports copied as they are; the tutorial is one `lake env lean
Tutorial.lean` run that writes 141 exports.

Time and space on this host (20 cores, fast network), 2026-11-23:

| test | what takes the time | wall |
|---|---|---|
| mathlib | fetch 8 s, `lake exe cache get` 45 s (9117 files), `lake build Mathlib` 6 s (nothing to build), export 6.7 min | 8 min |
| cslib | cache get 62 s, build 3.6 min, export 2.2 min | 7 min |
| cedar | build 5.8 min (from source), export 40 s | 7 min |
| con-leche | build 9.1 min (from source), export 44 s | 10 min |
| navier-stokes-euler | cache get 100 s, build 28.5 min (about 2,400 modules of the project itself, from source: the slowest by far), lean4export built for v4.34.0-rc2 in 7 s, export 78 s | 32 min |
| reachability | build 3.9 min (no cache: 450 Mathlib modules and their dependencies from source), export 3 s | 4 min |
| init-prelude, init, std | export 0.3 s, 10 s, 18 s | 1 min |
| the other 121 definitions (261 tests) | 121 small Lake projects, one after the other | 6 min |

Everything together, side by side, takes about 40 minutes. Disk: `work/arena-run/_build/tests`
49 GB (the 270 exports are 13.0 GB; the rest are the Lake work directories in
`_build/tests/work/`, which can be deleted once the exports exist, except
`work/mathlib` if more slices are wanted), `work/cache/elan` 13 GB (four
toolchains), `work/exports/slices` 2.4 GB.

### Deviations from the arena's build

1. **aarch64 toolchains.** The arena builds on x86-64; elan installs
   `lean-*-linux_aarch64` here. The exports do not differ (hashes above).
2. **The shell.** `lka.py` runs `pre-build:`/`run:` with
   `subprocess(shell=True)`. On the arena that shell is bash, because its
   Python comes from nixpkgs, which patches `subprocess` to use bash's `sh`
   (`substituteInPlace Lib/subprocess.py --replace-fail "'/bin/sh'"
   "'${bashNonInteractive}/bin/sh'"` in `cpython/default.nix` of nixos-26.05,
   the arena's flake input); in our Ubuntu image `/bin/sh` is dash, and `tests/cedar.yaml`
   (`shopt -s dotglob && ...`) fails with `shopt: not found` (exit 127,
   "Pre-build failed"). `bench/arena-lka.py` runs the unmodified `lka.py` with
   every `shell=True` command going to `/bin/bash` under the name `sh`. All
   other tests build the same under dash. (For measuring, the difference is
   immaterial: `sh -c true` is 0.17 M instructions with dash, 0.98 M with
   bash; `measure.py` uses `/bin/sh`.)
3. **No Nix shell.** Building tests needs only python3 (with yaml, jsonschema,
   jinja2, markdown), git, curl and elan, which the image has.
4. **`lka.py run` is not used** for measurements (see `measure.sh` above).

### Statistics

`work/exports/STATS.md` (from `bench/export-stats.py`, one streaming pass per
file, 130 s for mathlib) has, per corpus: size, lines, header, records per
kind, largest ids, declarations per kind, literals, and the incremental size
of every declaration with the largest ones by name. The headline numbers:

| export (`work/exports/NAME.ndjson`) | bytes | lines | names | levels | expressions | constants | theorems | definitions | top-100 declarations | arena timeout |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| `init-prelude` | 3,811,816 | 65,406 | 7,491 | 113 | 55,961 | 2,121 | 161 | 1,536 | 0.9 MiB | 40 s |
| `reachability` | 19,031,110 | 361,766 | 30,662 | 73 | 326,617 | 4,846 | 2,532 | 1,687 | 4.8 MiB | 220 s |
| `init` | 347,772,063 | 6,495,296 | 299,085 | 571 | 6,137,092 | 59,979 | 42,451 | 15,239 | 37.5 MiB | 370 s |
| `std` | 584,315,500 | 10,611,507 | 580,893 | 953 | 9,928,514 | 103,885 | 71,751 | 27,944 | 56.3 MiB | 600 s |
| `con-leche` | 701,947,717 | 12,734,575 | 476,319 | 256 | 12,221,252 | 38,246 | 21,663 | 14,599 | 148.4 MiB | 920 s |
| `cedar` | 904,722,064 | 15,913,292 | 1,037,701 | 1,029 | 14,728,503 | 151,772 | 82,625 | 60,089 | 108.4 MiB | 640 s |
| `navier-stokes-euler` | 1,559,685,706 | 28,569,038 | 691,465 | 3,907 | 27,747,197 | 129,845 | 100,424 | 24,370 | 157.6 MiB | 1840 s |
| `cslib` | 2,500,956,112 | 44,051,112 | 2,349,377 | 12,713 | 41,287,036 | 413,186 | 274,986 | 119,982 | 68.9 MiB | 1600 s |
| `mathlib` | 6,326,141,213 | 112,081,718 | 4,273,914 | 48,246 | 107,037,712 | 739,167 | 524,582 | 187,271 | 144.5 MiB | 4120 s |

All nine have header `lean4export 3.1.0`, format `3.1.0`; Lean 4.36.0
(`611c117bfaf7`) except navier-stokes-euler (4.34.0-rc2) and reachability
(4.34.1). Things a reader must cope with in mathlib: a 2.7 MB line (a string
literal of 2,663,470 bytes), 1,820 natural-number literals of 2^64 or more
(the longest has 1,085 digits), bound-variable indices up to 360, 30 universe
parameters on one declaration, a mutual block of 12 inductive types.

### Slices of mathlib for fast iteration

`leanexport MODULE` exports the whole environment of a module, i.e. everything
it imports: for `Mathlib.Data.Nat.Basic` that is already 522 MB (all of Init
and much of Std, Batteries and Lean itself). Smaller and more telling are
*cones*: only the constants declared in a module, with everything they depend
on (types, values and proofs, transitively), which is the arena's
`export-decls:` applied to a module's own declarations:

```bash
bench/arena-docker.sh --no-network bench/export-slice.sh Mathlib.Data.Nat.Basic            # whole environment
bench/arena-docker.sh --no-network bench/export-slice.sh -m Mathlib.Order.Basic            # cone of a module
bench/arena-docker.sh --no-network bench/export-slice.sh -n flt4 Mathlib.NumberTheory.FLT.Four -- fermatLastTheoremFour   # cone of chosen constants (34 MB)
bench/build-corpus.sh slices                                                               # the 16 below + slices/STATS.md
```

They are exported from the arena's own mathlib checkout
(`work/arena-run/_build/tests/work/mathlib/src`, v4.36.0, `fc4f59e5`) with the
same `leanexport`, in 5-40 s each, and are deterministic (re-exporting gives the same sha256). All are accepted by
the official kernel (`leanchecker` v4.35.0), measured here with
`run-suite.py --perf`:

| slice (`work/exports/slices/good/NAME.ndjson`) | MiB | lines | roots | constants | theorems | official: instructions | virtual s | max RSS |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| `Mathlib.Algebra.Group.Basic.cone` | 5.2 | 105,855 | 449 | 2,150 | 1,098 | 4.2e9 | 0.7 | 114 MiB |
| `Mathlib.Data.Nat.Basic.cone` | 11.1 | 223,123 | 20 | 3,222 | 1,516 | 8.3e9 | 1.4 | 130 MiB |
| `Mathlib.Topology.Basic.cone` | 11.9 | 236,426 | 55 | 3,850 | 2,043 | 8.4e9 | 1.4 | 132 MiB |
| `Mathlib.Data.Fintype.Basic.cone` | 18.4 | 366,504 | 61 | 5,180 | 2,758 | 13.6e9 | 2.3 | 138 MiB |
| `Mathlib.Data.List.Basic.cone` | 20.0 | 400,962 | 246 | 4,845 | 2,592 | 15.5e9 | 2.6 | 144 MiB |
| `Mathlib.Order.Basic.cone` | 48.6 | 986,754 | 298 | 9,240 | 4,502 | 59.3e9 | 9.9 | 192 MiB |
| `Mathlib.GroupTheory.Sylow.cone` | 60.5 | 1,195,384 | 123 | 15,073 | 9,571 | 48.5e9 | 8.1 | 210 MiB |
| `Mathlib.Analysis.SpecialFunctions.Pow.Real.cone` | 120.1 | 2,340,214 | 217 | 26,037 | 14,967 | 124.4e9 | 20.7 | 303 MiB |
| `Mathlib.AlgebraicGeometry.Scheme.cone` | 145.0 | 2,857,065 | 258 | 24,239 | 14,292 | 219.9e9 | 36.7 | 334 MiB |
| `Mathlib.FieldTheory.Galois.Basic.cone` | 165.7 | 3,257,245 | 102 | 28,495 | 19,944 | 346.3e9 | 57.7 | 924 MiB |
| `Mathlib.Probability.StrongLaw.cone` | 187.4 | 3,637,329 | 33 | 36,282 | 26,117 | 176.0e9 | 29.3 | 442 MiB |
| `Mathlib.MeasureTheory.Integral.IntervalIntegral.FundThmCalculus.cone` | 203.3 | 3,938,429 | 81 | 38,623 | 27,981 | 191.1e9 | 31.8 | 450 MiB |
| `Mathlib.NumberTheory.LegendreSymbol.QuadraticReciprocity.cone` | 208.7 | 4,081,392 | 11 | 37,060 | 26,492 | 374.4e9 | 62.4 | 956 MiB |
| `Mathlib.Analysis.Complex.CauchyIntegral.cone` | 298.7 | 5,799,530 | 53 | 48,310 | 35,951 | 333.7e9 | 55.6 | 560 MiB |
| `Mathlib.NumberTheory.LSeries.RiemannZeta.cone` | 362.5 | 7,041,125 | 38 | 56,164 | 42,671 | 404.5e9 | 67.4 | 754 MiB |
| `Mathlib.Data.Nat.Basic` | 497.8 | 9,448,749 | all | 122,113 | 54,907 | 515.5e9 | 85.9 | 884 MiB |

## Baseline checkers (`bench/baselines/`)

The checkers RLVM is compared with are built from the arena's own definitions
(`checkers/NAME.yaml`: pinned source revision, `build:` and `run:` commands)
with the arena's own tool, `lka.py build-checker`, inside the container. The
arena working copy for this is `work/arena-checkers`, a clone of
<https://github.com/leanprover/lean-kernel-arena> at the revision in
`bench/baselines/ARENA_REV` (99195b6b, the one the published results in
`work/arena-site/results.json` were made from). Build trees are where `lka.py`
puts them: `work/arena-checkers/_build/checkers/NAME/src`
(`.../checkers/official` for the official kernel, which has no source checkout).
`work/arena-run` is a different clone, used for building tests.

```bash
# nano-lean pins Rust 1.97.1 in rust-toolchain.toml; install it first so that
# builds running side by side do not race inside rustup
bench/arena-docker.sh rustup toolchain install 1.97.1 --profile minimal \
    --component llvm-tools --component clippy --component rustfmt

# build: one detached container per checker, `./lka.py -v build-checker NAME`;
# logs in work/logs/baselines/build-NAME.log (17 min for all nine side by side)
bench/baselines/build-checker.sh --wait nano-lean gruszka sokonanoda official rpylean tenet-jvm nanoda pestka mathgraph

# wrappers bench/baselines/NAME.sh (+ MANIFEST.json), generated from the yaml
bench/baselines/gen-wrappers.py nano-lean gruszka sokonanoda official rpylean tenet-jvm nanoda pestka mathgraph

# one export: verdict, or measured like the arena
bench/arena-docker.sh --arena-limits --no-network bench/baselines/gruszka.sh work/exports/init.ndjson; echo $?
bench/arena-docker.sh --arena-limits --no-network bench/measure.sh \
    --in work/exports/init.ndjson -c 'bench/baselines/gruszka.sh "$IN"'

# the 230 published tests, one checker after the other (48 min, 31 of them the two tenet-jvm runs)
bench/baselines/validate.sh nano-lean gruszka sokonanoda official rpylean tenet-jvm tenet-jvm-c2 nanoda pestka mathgraph
bench/baselines/summary.py                 # the table below (--markdown, --json FILE)
bench/compare-published.py work/baselines/small/gruszka.json --checker gruszka    # per heavy test

# repeatability: N runs per checker and test, one JSON line per run, table at the end
bench/arena-docker.sh --arena-limits --no-network bench/baselines/repeat.sh 5 work/baselines/repeat.jsonl \
    nano-lean gruszka sokonanoda pestka mathgraph -- perf/magma-list-deep-n36 perf/app-lam init-prelude
bench/arena-docker.sh --arena-limits --no-network bench/baselines/repeat.sh 5 work/baselines/repeat-jit.jsonl \
    official rpylean nanoda tenet-jvm tenet-jvm-c2 -- perf/app-lam init-prelude
```

A wrapper `bench/baselines/NAME.sh EXPORT.ndjson` (to be run inside the
container) does what `lka.py` does for a test: the yaml's `run:` command,
copied verbatim by `gen-wrappers.py`, through `sh -c`, with the working
directory set to the build tree, the export's absolute path in `$IN` and
`LC_ALL=C`. The exit status is passed through (0 accept, 1 reject, 2 decline,
other = error; the wrapper's own: 64 usage, 66 export not readable, 70 not
built). The wrapper costs 0.54e6 instructions per run (0.0001 virtual
seconds) over the bare run command.

### What was built

All nine built with the unmodified `lka.py build-checker` and the unmodified
yaml recipes; no x86-specific flag had to be changed, and the PGO scripts of
nano-lean, gruszka and pestka ran as they are (training files downloaded and
sha256-checked by the recipes themselves).

| checker | source pinned by the arena | built here with | what the build does | run command: workers | build |
|---|---|---|---|---|---|
| nano-lean (arena #1) | sdiehl/nano-lean v0.4.2 `5cd4a25` | Rust 1.97.1 (its `rust-toolchain.toml`) | `scripts/pgo.sh`: PGO trained on an Init export (release asset), fat LTO, `-C target-cpu=native -C llvm-args=-align-all-functions=6` | `nl-fast -q -j 4`: 4 | 4.8 min |
| gruszka (#2) | jasisz/gruszka `ebc80dc` | Rust 1.100.0 | `cargo install susz` 0.1.4; `scripts/build-pgo.sh init.susz` (training file from huggingface), fat LTO, `target-cpu=native` | `scripts/arena-run "$IN" 4`: `susz pack`, then 4 workers | 2.0 min |
| sokonanoda (#3) | intgrah/sokonanoda `9a021b6` | Rust 1.100.0 | `cargo build --release`, `target-cpu=native` | `--threads 4`: 4 | 20 s |
| official | Lean v4.35.0 | elan's aarch64 release | `lean-toolchain` file | `leanchecker --from-export`: 1 | < 1 s |
| rpylean (#4) | Julian/rpylean v2026.11.11 `bbf354d` | PyPy 2.7 v7.3.23 running RPython of pypy `main` (`0c174b0`), gcc 13.3 | `git clone pypy`; `just translate` (`--opt=jit`) | `./rpylean-c check`: 1 | 9.0 min |
| tenet-jvm | keithadler/tenet-jvm 0.1.5 `4cc6e20` | `javac` of GraalVM CE 25.0.5 | `make` | `bin/tenet-jvm`: 1 (+ the JVM's GC and JIT threads) | 1 s |
| nanoda | ammkrn/nanoda_lib `0da9f98` | Rust 1.100.0 | `cargo build --release`; `config.json` | `num_threads` 4, 256 MiB stacks | 18 s |
| pestka | jasisz/pestka `cea66e6` | Rust 1.100.0, aver-lang 0.37.0 | `cargo install` susz 0.1.4 and aver-lang; `bin/build-pgo init.susz`, `target-cpu=native` | `bin/arena-run "$IN" 4`: `susz pack`, then 4 workers | 15.7 min |
| mathgraph | metalogiclabs/mathgraph-lean-kernel `flash` `78c7502` | Rust 1.100.0 | `cargo build --release`, `target-cpu=native`; `config.json` | `num_threads` 2 | 25 s |

Build trees take 2.5 GB (nano-lean 1.3 GB, pestka 0.6 GB).

### Differences from the arena's build

- **Architecture.** aarch64 instead of x86-64. `-C target-cpu=native`
  resolves to `cortex-x925` whichever core rustc runs on; both core types of
  this host have the same features. PGO profiles are trained here, by the
  same scripts.
- **Rust.** rustup's stable (1.100.0) with its own `llvm-profdata`, instead
  of the `rustc` and LLVM 21 of nixpkgs 26.05. nano-lean is built with 1.97.1
  because rustup honours its `rust-toolchain.toml`; the arena's nix `cargo`
  ignores that file.
- **JDK.** tenet-jvm is compiled and run with the project's GraalVM CE 25,
  whose optimising JIT is the Graal compiler; the arena uses the `jdk` of its
  flake, a stock OpenJDK with C2. `baselines/tenet-jvm-c2.sh` runs the same
  checker with `-XX:-UseJVMCICompiler` (C2 as in a stock OpenJDK) and
  reproduces the published numbers; see the table.
- **PyPy.** rpylean's recipe clones pypy's `main` unpinned; here that was
  `0c174b0` (2026-11-23). The translating interpreter is PyPy 2.7 v7.3.23.
- **Shell.** `lka.py` ran the build commands with the image's `/bin/sh`
  (dash); on the arena `sh` is bash. None of the nine recipes uses anything
  bash-only. `build-checker.sh --bash-sh` builds through `bench/arena-lka.py`
  for recipes that do. Run commands also go through dash here: 0.29e6
  instructions per `sh -c`, against 0.73e6 for bash.
- **Image.** PyPy's tarball ships its `lib/` unreadable for non-root users
  (`pypy: error while loading shared libraries: libtinfow.so.6`); the
  Dockerfile now makes it readable and checks `pypy --version` as a non-root
  uid. Rebuild the image (`bench/arena-docker.sh --build`) if yours is older
  than 2026-11-24 03:10 UTC.

### Results on the 230 published tests (2026-11-24)

`validate.sh` = `run-suite.py --perf --arena-timeouts --timeout 900 -j 1`
through the wrappers, in a container with `--arena-limits --no-network`.
Reports: `work/baselines/small/NAME.json`; table: `summary.py`
(`work/baselines/small-summary.json`).

| checker | correct | bad acc | good rej | declined | error | timeout | instr total | virt s | startup | median | rss max | = arena | ours/arena |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| gruszka | 230/230 | 0 | 0 | 0 | 0 | 0 | 136.8G | 22.80 | 2.833M | 5.37M | 1058M | 230/230 | 0.926 |
| nano-lean | 229/230 | 0 | 0 | 1 | 0 | 0 | 149.5G | 24.92 | 1.314M | 1.852M | 10316M | 230/230 | 0.934 |
| sokonanoda | 226/230 | 0 | 0 | 2 | 2 | 0 | 168.5G | 28.08 | 1.417M | 3.389M | 6886M | 230/230 | 0.933 |
| pestka | 230/230 | 0 | 0 | 0 | 0 | 0 | 230.5G | 38.41 | 2.245M | 6.506M | 1199M | 230/230 | 0.873 |
| rpylean | 229/230 | 0 | 0 | 0 | 0 | 1 | 258.9G | 43.14 | 2.765M | 3.25M | 2017M | 230/230 | 1.006 |
| nanoda | 228/230 | 0 | 0 | 2 | 0 | 0 | 920G | 153.33 | 1.078M | 2.306M | 4151M | 230/230 | 0.933 |
| official | 230/230 | 0 | 0 | 0 | 0 | 0 | 1.145T | 190.76 | 187.5M | 191.7M | 7992M | 230/230 | 0.944 |
| tenet-jvm-c2 | 229/230 | 0 | 0 | 0 | 1 | 0 | 11.29T | 1880.93 | 528.5M | 682.9M | 12468M | 230/230 | 0.892 |
| tenet-jvm | 229/230 | 0 | 0 | 0 | 1 | 0 | 19.48T | 3246.12 | 884.8M | 1.22G | 12453M | 230/230 | 1.539 |
| mathgraph | 221/230 | 8 | 1 | 0 | 0 | 0 | 292.1G | 48.68 | 1.565M | 2.648M | 11794M | 230/230 | 0.991 |

Columns: `instr total` = user-space instructions over all tests, `virt s` =
that / 6e9; `startup` = the smallest count of any test (process start-up,
wrapper included), `median` the median test; `= arena` = tests whose status
equals the published status of that checker; `ours/arena` = our instruction
total over the arena's, on the tests where both have a count.

- **Every baseline gives the published status on all 230 tests**, the wrong
  ones included (mathgraph's 8 accepted bad proofs and 1 rejected good one,
  sokonanoda's 2 panics and 2 declines, nanoda's 2 declines, nano-lean's
  decline and tenet-jvm's `OutOfMemoryError` on `bugs/nat-shift-huge`,
  rpylean's timeout on `perf/unfold-before-literal-fallback` at 270 s).
- **aarch64 counts differ from the x86-64 ones by a factor that depends on
  the checker**: totals are 0.87 (pestka) and 0.93–0.94 (gruszka, nano-lean,
  sokonanoda, nanoda, official) of the published ones, 0.99 for mathgraph and
  1.01 for rpylean. Per heavy test (published count above 3e9) the median
  ratio is 0.87–0.97, and `perf/gf3-matrix-product` is the outlier for every
  Rust and C++ checker (1.16–1.32). Rank checkers by measurements made here,
  not against the published numbers.
- **tenet-jvm's total is one test.** `bugs/nat-shift-huge` fills the 12 GB
  heap and collects garbage on 8 threads until the JVM gives up: 17.6e12
  instructions and 897 s with the Graal JIT, 9.6e12 and 519 s with C2
  (arena: 10.9e12, 366 s). The arena has no timeout for this test; 900 s is
  ours, and the Graal run ended 3 s before it. Without that test the totals
  are 1.918e12 (Graal JIT, 1.09 x arena) and 1.687e12 (C2, 0.96 x arena).
- **The JIT compiler matters for a JVM checker's count.** The same bytecode
  on the same JVM costs at least 885e6 instructions per run with the Graal
  JIT against 529e6 with C2, and 1.22e9 against 0.68e9 on the median test
  (Graal/C2: median 1.76 over the 199 tests below 3e9). On the 30 heavier
  tests the Graal JIT costs a median 1.21 times C2 (0.89 to 1.67). The arena
  counts the compiler threads' instructions like any others.
- **`-j 1` is needed for memory**: several baselines use 8–12 GB on single
  tests (tenet-jvm and nano-lean on `bugs/nat-shift-huge`, mathgraph on
  `perf/magma-list-pair-n21`), and the container has the arena's 16 GB.
- Start-up, for scale: a native checker costs 1–3e6 instructions per run,
  the official kernel 188e6, tenet-jvm 0.5–0.9e9.

### How repeatable the baselines' counts are

`bench/baselines/repeat.sh`, 5 runs each, `--arena-limits`
(`work/baselines/repeat.jsonl`, `repeat-jit.jsonl`); CV = stdev/mean, spread =
(max − min)/mean:

| test | checker | mean instructions | CV | spread |
|---|---|---|---|---|
| `perf/magma-list-deep-n36` | nano-lean | 2.333e9 | 0.05 % | 0.12 % |
| | gruszka | 50.95e9 | 0.06 % | 0.18 % |
| | pestka | 7.560e9 | 0.32 % | 0.84 % |
| | mathgraph | 31.37e9 | 0.01 % | 0.03 % |
| | **sokonanoda** | 25.47e9 | **15.4 %** | **31.6 %** (22.3e9 to 30.3e9; max RSS 4.6 to 8.4 GiB) |
| `perf/app-lam` | nano-lean | 41.1e6 | 1.61 % | 4.09 % |
| | gruszka, pestka, sokonanoda, mathgraph | 0.08e9 to 5.3e9 | ≤ 0.25 % | ≤ 0.65 % |
| | official, nanoda, rpylean | 0.10e9 to 27.8e9 | ≤ 0.02 % | ≤ 0.05 % |
| | tenet-jvm (Graal JIT) | 71.3e9 | 1.24 % | 3.50 % |
| | tenet-jvm-c2 | 67.6e9 | 1.49 % | 3.63 % |
| `init-prelude` | nano-lean, gruszka, sokonanoda, pestka, mathgraph | 0.15e9 to 0.37e9 | ≤ 0.43 % | ≤ 1.29 % |
| | official, nanoda, rpylean | 0.19e9 to 2.2e9 | ≤ 0.02 % | ≤ 0.06 % |
| | tenet-jvm (Graal JIT) | 11.0e9 | 6.51 % | 20.1 % |
| | tenet-jvm-c2 | 9.08e9 | 0.50 % | 1.41 % |

official, nanoda and rpylean repeat to within 0.06 %. The other multi-threaded
checkers vary by about 1 % or less (nano-lean by 4 % on a test of only 41e6
instructions), with one exception: sokonanoda on `perf/magma-list-deep-n36`
has two modes, 30.3e9 instructions with 8.0–8.4 GiB max RSS (two of these
five runs) and 22.3e9 with 4.6 GiB (three of them). The arena's published
count for that test (31.9e9) corresponds to the higher mode. The cause was
not investigated. That one test moved sokonanoda's suite total from 177.9e9
(a first run, with `-j 2`) to 168.5e9 (the run in the table above). Measure
a multi-threaded baseline more than once before comparing against it. The
JVM varies by 1–1.5 % on a 12-second test, and by several percent on a
half-second one with the Graal JIT.

On a full-size export the wrappers behave the same. `work/exports/init.ndjson`
(348 MB), one run each: nano-lean 22.26e9 instructions (3.71 virtual s),
gruszka 22.67e9 (3.78), sokonanoda 27.63e9 (4.60), pestka 58.65e9 (9.77); all
accepted, 0.90–0.96 of the published counts.

### Baselines on the big corpora: init, std, mathlib (2026-11-24)

What RLVM has to beat on this machine. Full discussion: `docs/research/12-baselines.md`; machine-readable:
`bench/baselines.json`.

```bash
# one checker on one corpus, by hand: one JSON line
bench/arena-docker.sh --arena-limits --no-network bench/measure.sh --timeout 8240 \
    --in work/exports/mathlib.ndjson -c 'bench/baselines/nano-lean.sh "$IN"'

# the same through big.sh: one fresh container per run, a record per run in work/baselines/big/runs.jsonl,
# the raw measurement in work/baselines/big/raw/, the checker's output in work/baselines/big/logs/
bench/baselines/big.sh --corpora 'init std mathlib' --runs 3 nano-lean gruszka sokonanoda
bench/baselines/big.sh --help

# the complete series behind bench/baselines.json (about 2 h), stage by stage:
bench/baselines/big-all.sh mathlib-fast-1      # mathlib, run 1 of nano-lean gruszka sokonanoda pestka rpylean mathgraph
bench/baselines/big-all.sh small               # init and std, 3 runs of all ten baselines
bench/baselines/big-all.sh mathlib-fast-23     # mathlib, runs 2 and 3 of the six fast ones
bench/baselines/big-all.sh threads             # mathlib with 1, 2, 8 workers: nano-lean, gruszka, sokonanoda
bench/baselines/big-all.sh mathlib-slow        # mathlib, 1 run of nanoda, tenet-jvm-c2, tenet-jvm
OFFICIAL_CPUSET=18,19 bench/baselines/big-all.sh mathlib-official   # mathlib, 1 run of the official kernel (30 min),
                                               # started next to the series on two performance cores of its own
#   (bench/baselines/big-all.sh without arguments runs all six stages one after the other)

# tables (per corpus, aarch64/x86-64, thread scaling) and bench/baselines.json
bench/baselines/big-summary.py [--markdown] [--no-json] [--section corpora|arch|threads]

# any other command the same way, e.g. RLVM, kept in a file of its own and shown next to the baselines
bench/baselines/big.sh --runs 3 --out work/rlvm/big --label rlvm --cwd . --cmd "$(bench/rlvm/run.sh --print)"
bench/baselines/big-summary.py --no-json --runs work/baselines/big/runs.jsonl --runs work/rlvm/big/runs.jsonl
```

`big.sh` runs `bench/measure.sh --timeout T --in EXPORT -o RAW.json -c 'bench/baselines/NAME.sh "$IN"'` in a
container started with `--arena-limits --no-network`; `T` is twice the arena's timeout of the corpus (init 370 s,
std 600 s, mathlib 4120 s). Every record carries the host's load average at the start and at the end of the run:
instruction counts do not care what else the host is doing, wall and CPU times do, and other agents were using
the host throughout (the wall column gives minimum and median for that reason). The figure per checker and
corpus is the median over its runs.

<!-- BEGIN big:corpora -->
### init  (348 MB, 6,495,296 lines, arena timeout 370 s)

| checker | runs | status | instructions | virtual time | x fastest | spread | CPU s | wall s (min / median) | max RSS MiB | arena x86-64 virtual | aarch64/x86-64 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| nano-lean | 3 | accepted | 22.3e9 | 3.71 s | 1.00 | 0.18 % | 4.5 | 1.3 / 1.3 | 956 | 4.05 s | 0.915 |
| gruszka | 3 | accepted | 22.6e9 | 3.76 s | 1.01 | 0.57 % | 2.8 | 0.8 / 0.8 | 379 | 4.14 s | 0.909 |
| sokonanoda | 3 | accepted | 27.7e9 | 4.62 s | 1.25 | 0.72 % | 4.5 | 1.3 / 1.4 | 764 | 4.81 s | 0.962 |
| rpylean | 3 | accepted | 30.2e9 | 5.03 s | 1.36 | 0.00 % | 4.9 | 4.6 / 4.9 | 365 | 5.42 s | 0.927 |
| mathgraph | 3 | accepted | 43.2e9 | 7.20 s | 1.94 | 0.50 % | 8.1 | 3.9 / 4.2 | 838 | 7.44 s | 0.967 |
| pestka | 3 | accepted | 58.6e9 | 9.77 s | 2.63 | 0.02 % | 11.5 | 3.4 / 3.4 | 538 | 10.91 s | 0.896 |
| nanoda | 3 | accepted | 215.4e9 | 35.89 s | 9.67 | 0.06 % | 26.0 | 8.5 / 8.6 | 529 | 37.30 s | 0.962 |
| tenet-jvm-c2 | 3 | accepted | 230.9e9 | 38.48 s | 10.37 | 4.95 % | 32.3 | 26.1 / 28.5 | 4,550 | 37.28 s | 1.032 |
| tenet-jvm | 3 | accepted | 247.0e9 | 41.17 s | 11.10 | 1.81 % | 35.5 | 25.4 / 30.6 | 4,639 | 37.28 s | 1.105 |
| official | 3 | accepted | 381.2e9 | 63.53 s | 17.12 | 0.06 % | 35.4 | 35.1 / 35.4 | 604 | 66.92 s | 0.949 |

### std  (584 MB, 10,611,507 lines, arena timeout 600 s)

| checker | runs | status | instructions | virtual time | x fastest | spread | CPU s | wall s (min / median) | max RSS MiB | arena x86-64 virtual | aarch64/x86-64 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| gruszka | 3 | accepted | 36.5e9 | 6.08 s | 1.00 | 0.23 % | 5.7 | 1.8 / 1.9 | 484 | 6.72 s | 0.905 |
| nano-lean | 3 | accepted | 37.0e9 | 6.16 s | 1.01 | 0.17 % | 9.2 | 3.3 / 3.6 | 1,158 | 6.71 s | 0.918 |
| sokonanoda | 3 | accepted | 46.4e9 | 7.73 s | 1.27 | 0.74 % | 8.8 | 3.1 / 3.3 | 887 | 8.06 s | 0.959 |
| rpylean | 3 | accepted | 51.2e9 | 8.54 s | 1.40 | 0.01 % | 10.7 | 10.2 / 10.7 | 520 | 9.19 s | 0.929 |
| mathgraph | 3 | accepted | 72.2e9 | 12.04 s | 1.98 | 0.24 % | 14.9 | 7.6 / 7.7 | 1,013 | 12.45 s | 0.967 |
| pestka | 3 | accepted | 96.9e9 | 16.15 s | 2.65 | 0.01 % | 21.6 | 6.7 / 6.7 | 727 | 17.95 s | 0.899 |
| nanoda | 3 | accepted | 332.4e9 | 55.40 s | 9.11 | 0.08 % | 46.4 | 15.1 / 15.5 | 764 | 57.60 s | 0.962 |
| tenet-jvm-c2 | 3 | accepted | 379.0e9 | 63.16 s | 10.38 | 0.83 % | 53.4 | 44.8 / 47.3 | 4,996 | 60.20 s | 1.049 |
| tenet-jvm | 3 | accepted | 393.3e9 | 65.56 s | 10.78 | 0.83 % | 60.9 | 54.8 / 54.9 | 4,963 | 60.20 s | 1.089 |
| official | 3 | accepted | 632.0e9 | 105.3 s | 17.32 | 0.04 % | 68.5 | 65.9 / 68.6 | 956 | 110.9 s | 0.950 |

### mathlib  (6,326 MB, 112,081,718 lines, arena timeout 4120 s)

| checker | runs | status | instructions | virtual time | x fastest | spread | CPU s | wall s (min / median) | max RSS MiB | arena x86-64 virtual | aarch64/x86-64 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| nano-lean | 3 | accepted | 361.3e9 | 60.22 s | 1.00 | 0.04 % | 107.2 | 25.5 / 36.9 | 5,157 | 65.81 s | 0.915 |
| gruszka | 3 | accepted | 418.7e9 | 69.78 s | 1.16 | 0.30 % | 75.9 | 18.1 / 22.3 | 2,592 | 77.00 s | 0.906 |
| sokonanoda | 3 | accepted | 504.3e9 | 84.05 s | 1.40 | 0.11 % | 113.2 | 30.4 / 35.7 | 5,228 | 87.90 s | 0.956 |
| rpylean | 3 | accepted | 544.6e9 | 90.77 s | 1.51 | 0.05 % | 131.8 | 117.9 / 132.2 | 4,545 | 96.35 s | 0.942 |
| mathgraph | 3 | accepted | 880.4e9 | 146.7 s | 2.44 | 0.01 % | 156.3 | 78.5 / 80.6 | 6,543 | 152.0 s | 0.966 |
| pestka | 3 | accepted | 1.340e12 | 223.3 s | 3.71 | 0.01 % | 348.4 | 89.0 / 125.9 | 3,627 | 245.9 s | 0.908 |
| nanoda | 1 | accepted | 5.533e12 | 922 s (15.4 min) | 15.31 | - | 973.4 | 290.8 | 6,053 | 962 s (16.0 min) | 0.958 |
| tenet-jvm-c2 | 1 | accepted | 9.313e12 | 1552 s (25.9 min) | 25.77 | - | 1144.5 | 1064.0 | 12,134 | 1633 s (27.2 min) | 0.951 |
| official | 1 | accepted | 11.424e12 | 1904 s (31.7 min) | 31.62 | - | 1752.2 | 1757.2 | 10,167 | 2016 s (33.6 min) | 0.945 |
| tenet-jvm | 1 | accepted | 11.960e12 | 1993 s (33.2 min) | 33.10 | - | 1160.5 | 1082.8 | 12,558 | 1633 s (27.2 min) | 1.221 |
<!-- END big:corpora -->

<!-- BEGIN big:threads -->
### thread scaling on mathlib

| checker | threads | instructions | virtual time | vs 1 thread | CPU s | wall s | max RSS MiB |
|---|---:|---:|---:|---:|---:|---:|---:|
| nano-lean | 1 | 361.0e9 | 60.16 s | 1.000 | 90.5 | 89.9 | 5,157 |
| nano-lean | 2 | 361.2e9 | 60.20 s | 1.001 | 82.5 | 43.7 | 5,157 |
| nano-lean | 4 | 361.3e9 | 60.22 s | 1.001 | 107.2 | 36.9 | 5,157 |
| nano-lean | 8 | 361.9e9 | 60.31 s | 1.002 | 99.1 | 17.9 | 5,452 |
| gruszka | 1 | 414.6e9 | 69.10 s | 1.000 | 61.3 | 58.1 | 2,211 |
| gruszka | 2 | 417.0e9 | 69.50 s | 1.006 | 71.7 | 36.8 | 2,301 |
| gruszka | 4 | 418.7e9 | 69.78 s | 1.010 | 75.9 | 22.3 | 2,592 |
| gruszka | 8 | 421.6e9 | 70.26 s | 1.017 | 68.2 | 14.4 | 2,831 |
| sokonanoda | 1 | 487.2e9 | 81.19 s | 1.000 | 95.2 | 94.4 | 4,630 |
| sokonanoda | 2 | 497.2e9 | 82.86 s | 1.021 | 90.3 | 48.1 | 4,759 |
| sokonanoda | 4 | 504.3e9 | 84.05 s | 1.035 | 113.2 | 35.7 | 5,228 |
| sokonanoda | 8 | 510.7e9 | 85.12 s | 1.048 | 106.6 | 19.7 | 5,607 |
<!-- END big:threads -->

Ratios of these counts to the arena's published x86-64 counts are in `docs/research/12-baselines.md`, section 3
(`bench/baselines/big-summary.py --section arch`).

### Per-declaration profiles on mathlib

Where the baselines spend their instructions, declaration by declaration, from switches the checkers have
themselves (`nl-fast --trace` and `--import-only`; gruszka's `SK_DECL_INSTR=1`; sokonanoda's `--verbose` and
`--parse-only`; rpylean's `check --print jsonl`). None of them counts instructions per declaration on Linux, so
`baselines/instrtap.c` adds that from outside: an `LD_PRELOAD` library that interposes `write`/`fwrite` and,
whenever the checker starts one of its per-declaration lines, first prints `#I <tid> <count>`, the calling
thread's user-space instruction count (its own `perf_event_open` counter, INST_RETIRED, one event per PMU,
summed: what `perf stat -e instructions:u` counts, per thread).

```bash
# in the container; outputs in work/baselines/profile/ (2.0 GB, of which export.susz 0.65 GB)
bench/arena-docker.sh --arena-limits --no-network bench/baselines/profile-mathlib.sh build selftest
bench/arena-docker.sh --arena-limits --no-network bench/baselines/profile-mathlib.sh \
    nano-lean-import nano-lean-j1 gruszka-pack gruszka-w1 gruszka-check-w1 \
    sokonanoda-parse sokonanoda-v1 rpylean-jsonl nano-lean-j4 gruszka-w4 gruszka-check-w4 sokonanoda-v4
bench/baselines/profile-mathlib.sh --help        # what each step runs; --export FILE for another corpus

# summary: work/baselines/mathlib-profile.json (totals, read/check split, distribution, by kind and name,
# the 200 most expensive declarations per profile, agreement between checkers, thread scaling),
# mathlib-profile.top200.tsv, and mathlib-profile.<profile>.tsv.gz with every declaration
bench/baselines/profile-summary.py
bench/baselines/profile-summary.py --markdown [--section split|distribution|classes|kinds|names|top|workers|agreement]

# the library on its own: any program that prints a line per unit of work
gcc -O2 -shared -fPIC -o libinstrtap.so bench/baselines/instrtap.c
LD_PRELOAD=$PWD/libinstrtap.so INSTRTAP_PREFIXES='start |end ' [INSTRTAP_FD=2] PROGRAM ... 2> trace.txt
```

`profile-mathlib.sh selftest` checks the library against a loop of known length on 4 threads (1,200,000,522
counted for 1,200,000,000 + the two calls). `profile-mathlib.sh selftest-java` does the same with a JVM program
(`System.err.println` ends in libc's `write`; the count is the Java thread's own, without JIT and GC threads).
Results and their reading: `docs/research/12-baselines.md`, section 5.

As run on 2026-11-24, to fit next to the series: the single-worker steps in a container on CPUs 18,19
(`bench/arena-docker.sh --cpuset-cpus 18,19 --memory 16g --no-network ...` instead of `--arena-limits`), the
4-worker steps under `--arena-limits` between two stages of the series.

## Validation results on this host (2026-11-24)

Reproduce with the commands in the next section; raw data in
`work/perf-validate/report*.json` and `work/selftest/`.

**Counters work**, with CAP_PERFMON only. **Threads and children are counted**
(`perf-validate.py`, 200M-iteration loop per worker, 1.2e9 instructions each):

| workload | instructions:u | expected | ratio |
|---|---|---|---|
| 4 threads (pthread) | 4,800,226,612 | 4 × 1 thread | 1.00005 |
| 4 forked children | 4,800,214,273 | 4 × 1 thread | 1.00004 |
| 3 processes spawned by `sh -c` | 3,600,634,994 | 3 × 1 thread | 1.00018 |

**The core type does not matter**: the same program pinned to the A725 cores
and to the X925 cores gives the same count (1,200,348,215 both times in one
run of the validation, 5 instructions apart in another).

**Run-to-run variation over 5 runs** (CV = stdev/mean; spread = (max−min)/mean):

| workload | mean instructions:u | CV | spread | kernel share |
|---|---|---|---|---|
| C, 1 thread, deterministic | 1,200,216,062 | 0.000002 % (stdev 18) | 0.000003 % | 0.2 % |
| C, 4 threads, deterministic | 4,800,226,791 | 0.000002 % (stdev 96) | 0.000005 % | 0.2 % |
| CPython, 1 thread, `PYTHONHASHSEED=0` | 2,376,116,704 | 0.07 % | 0.14 % | 3.4 % |
| CPython, 1 thread, random hash seed | 2,388,039,225 | 1.2 % | 2.9 % | 3.4 % |
| JVM `java -version` | 417,785,323 | 8.1 % | 17 % | 17 % |
| JVM, 1 application thread | 5,175,559,081 | 2.5 % | 6.3 % | 19 % |
| JVM, 4 application threads | 16,205,547,786 | 0.7 % | 2.0 % | 12 % |

With `--arena-limits` the native rows are unchanged; the JVM rows become
4.1 % / 1.7 % / 0.7 % CV, at 401M / 5.54e9 / 17.8e9 instructions (the JVM
executes 7–10 % more user instructions on 8 CPUs / 16 GB than unconstrained,
and its kernel share drops to 7–16 %). The official C++ kernel, three runs of
five tests each: spread 0.01–0.05 %.

So the measurement itself is exact to a few dozen instructions; what varies is
the program. For a JVM checker expect about ±2 % per run on multi-second
workloads, and a start-up cost of about 0.4e9 instructions (0.07 virtual
seconds) per test; measure RLVM several times and with `--arena-limits`.

**Scoring agrees with the arena.** `selftest.py` replays the published exit
status of each of the 37 arena checkers on the 230 tarball tests through
`run-suite.py`: all 8510 (checker, test) pairs get the published status and
correctness.

**A real checker reproduces the published picture.** The official kernel
(`leanchecker` of Lean v4.35.0, installed with elan in the container) on the
230 tarball tests: 230/230 verdicts equal the published ones; instructions
ours/arena = 0.944 in total, median 0.947 over the 22 heavy tests (20 of them
within 0.933–0.961; outliers `perf/loop-unfolded-once-bad` 0.864 and
`perf/gf3-matrix-product` 1.272); max RSS within about 1 % (7992 MiB vs
7975 MiB on the largest). So for this C++ checker an aarch64 count is about
5 % below the x86-64 count; other compilers and runtimes will have their own
factor, which is why checkers must be compared by measurements made here and
not against the published numbers.

## Reproducing everything

```bash
cd /home/queclr/Documents/rabdos-lean-vm

# 0. arena tests + published results (done by a research agent; for reference)
#    mkdir -p work/arena-site/tests && cd work/arena-site &&
#    curl -sSfLO https://arena.lean-lang.org/lean-arena-tests.tar.gz &&
#    curl -sSfLO https://arena.lean-lang.org/results.json &&
#    tar -xzf lean-arena-tests.tar.gz -C tests        # -> tests/{good,bad}/**/*.ndjson (230 files)

# 1. image
bench/arena-docker.sh --build 2>&1 | tee work/logs/docker-build.log

# 2. first run seeds work/cache/{rustup,cargo,elan}; shows the environment
bench/arena-docker.sh bash -c 'id; grep -E "^Cap(Eff|Bnd|Amb)|NoNewPrivs" /proc/self/status;
    cat /opt/seed/VERSION; java -version; echo $JAVA_HOME $CARGO_HOME $ELAN_HOME'

# 3. perf privilege ladder (raw docker, to see what is and is not enough)
docker run --rm --entrypoint perf rlvm-arena:latest stat -e instructions:u -- true                      # fails
docker run --rm --entrypoint perf --cap-add PERFMON rlvm-arena:latest stat -e instructions:u -- true    # works (root)
docker run --rm --entrypoint perf --user "$(id -u):$(id -g)" --cap-add PERFMON rlvm-arena:latest \
    stat -e instructions:u -- true                                                                      # fails (non-root)
bench/arena-docker.sh perf stat --no-scale -e instructions:u -e task-clock -- python3 -c 'sum(range(2000000))'  # works (host uid)

# 4. perf validation: threads/children, core types, stability over 5 runs
bench/arena-docker.sh bench/perf-validate.py
bench/arena-docker.sh --arena-limits bench/perf-validate.py --tag arena-limits

# 5. smoke test with fake checkers (host: scoring; container: also perf)
bench/selftest.py
bench/arena-docker.sh bench/selftest.py

# 6. a real checker: the official kernel, as in refs/lean-kernel-arena/checkers/official.yaml
bench/arena-docker.sh elan toolchain install leanprover/lean4:v4.35.0
mkdir -p work/selftest/official && echo leanprover/lean4:v4.35.0 > work/selftest/official/lean-toolchain
bench/arena-docker.sh --arena-limits --no-network bench/run-suite.py --perf -j 4 --arena-timeouts \
    --label official-v4.35.0 --cwd work/selftest/official --report work/selftest/official-perf.json \
    -- 'leanchecker --from-export "$IN"'
bench/compare-published.py work/selftest/official-perf.json --checker official

# 7. the complete test set, mathlib included (40 min, 65 GB below work/; details in "The test corpus")
bench/build-corpus.sh
python3 -I bench/collect-exports.py --verify-hashes --strict     # 270 of 270 byte-identical to the arena's

# 8. the official kernel on the slices and on the complete set except the three largest corpora
bench/arena-docker.sh --no-network bench/run-suite.py --perf -j 6 --timeout 1200 --tests work/exports/slices \
    --label official-v4.35.0-slices --cwd work/selftest/official \
    --report work/exports/slices/official-v4.35.0.report.json -- 'leanchecker --from-export "$IN"'
bench/arena-docker.sh --no-network bench/run-suite.py --perf -j 8 --arena-timeouts --tests work/exports/all \
    --exclude mathlib --exclude cslib --exclude navier-stokes-euler --label official-v4.35.0-all \
    --cwd work/selftest/official --report work/suite-reports/official-v4.35.0-all-exports.json \
    -- 'leanchecker --from-export "$IN"'       # exits 1: the two `either` tests on which it times out, as on the arena
bench/compare-published.py work/suite-reports/official-v4.35.0-all-exports.json --checker official

# 9. the baseline checkers: build, wrappers, the 230 published tests (see "Baseline checkers")
bench/arena-docker.sh rustup toolchain install 1.97.1 --profile minimal --component llvm-tools --component clippy --component rustfmt
bench/baselines/build-checker.sh --wait nano-lean gruszka sokonanoda official rpylean tenet-jvm nanoda pestka mathgraph
bench/baselines/gen-wrappers.py nano-lean gruszka sokonanoda official rpylean tenet-jvm nanoda pestka mathgraph
bench/baselines/validate.sh nano-lean gruszka sokonanoda official rpylean tenet-jvm tenet-jvm-c2 nanoda pestka mathgraph

# 10. the baselines on init, std and mathlib (2 h) -> work/baselines/big/runs.jsonl, bench/baselines.json
bench/baselines/big-all.sh
bench/baselines/big-summary.py

# 11. per-declaration profiles of mathlib (10 min) -> work/baselines/mathlib-profile.*
bench/arena-docker.sh --arena-limits --no-network bench/baselines/profile-mathlib.sh build selftest \
    nano-lean-import nano-lean-j1 gruszka-pack gruszka-w1 gruszka-check-w1 sokonanoda-parse sokonanoda-v1 \
    rpylean-jsonl nano-lean-j4 gruszka-w4 gruszka-check-w4 sokonanoda-v4
bench/baselines/profile-summary.py
bench/baselines/render-tables.py docs/research/12-baselines.md bench/README.md
```

## Things to keep in mind

- Measure inside the container only; the host cannot count instructions
  (`perf_event_paranoid=4`, no sudo).
- Only instruction counts are comparable across runs and with the arena's
  metric. Wall and CPU times depend on the core type a thread happens to land
  on (X925 vs A725) unless `--arena-limits` (performance cores only) is used.
- For anything JVM-based always measure with `--arena-limits`; the JVM's
  thread counts and heap size, and with them its instruction count, follow the
  CPUs and memory it sees.
- The arena runs a checker with `LC_ALL=C`, cwd = the checker's build
  directory, the export path in `$IN`, through `sh -c`. `run-suite.py` and
  `measure.sh -c` do the same (cwd via `--cwd`).
- The arena's mathlib timeout is 4120 s wall time on its runner; per-test
  timeouts for the other corpora are in `work/arena-site/results.json` and
  `work/exports/all/manifest.json` (`--arena-timeouts`).
- The exports in `work/exports/` are symbolic links into
  `work/arena-run/_build/tests/`: building a test again with `lka.py`
  replaces the file behind the link, and deleting `work/arena-run` deletes the
  corpus. Run `bench/collect-exports.py` after any rebuild.
- `refs/` is mounted read-only in the container: to build a third-party
  checker, clone or copy it below `work/` first.
- Rust checkers built with `-C target-cpu=native` (sokonanoda, kefir, pestka)
  get this host's CPU features; that is the arena's own build recipe, kept
  as is.
- Rank by instruction count, on mathlib. The same JVM checker (tenet-jvm)
  with the Graal JIT instead of C2 needs 1.4 % more CPU time on mathlib and
  28 % more instructions (7 % more on init); gruszka and nano-lean are level
  on init and std and 16 % apart on mathlib (`docs/research/12-baselines.md`).
- `baselines/big.sh` stops its container when it gets SIGINT or SIGTERM; a
  measurement in progress is lost, the records already in `runs.jsonl` stay.

## Timing on a busy host: `bench/quiet.py` and `bench/slot.sh`

The team's builds and tests oversubscribe this host several times, so a wall-clock number is evidence
only when it was taken through `bench/quiet.py -- CMD...`:

- one measurement at a time on the host (lock `work/perf-history/.quiet.lock`);
- while `CMD` runs, every other process of the session's cgroup and every other container is moved off
  the measurement cores (default: the 8 performance cores of `arena-docker.sh --arena-limits`,
  5-9,15-17 here) and put back afterwards; nothing is paused;
- `CMD` starts with its affinity on those cores; a container it starts must be pinned there itself
  (`--arena-limits` is);
- every window is logged in `work/perf-history/quiet.log` (cores, duration, load before, how many
  threads were moved, containers that were pinned on the same cores during the run).

`bench/quiet.py --status` shows the cores; `--restore` undoes a herd left behind by a killed wrapper.
Memory bandwidth and the shared cache are still shared with the other 12 cores: compare runs taken in
the same window where you can.

`bench/slot.sh [-n SLOTS] [-c CLASS] -- CMD...` is the host-wide limiter for heavy jobs that are not
timings (8 slots; native-image builds: `-c ni -n 2`).

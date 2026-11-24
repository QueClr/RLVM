#!/usr/bin/env bash
# scripts/machine-sandbox.sh - parallel work of several packages inside the ONE Maven module rlvm-machine.
#
# The judgments of the machine are mutually recursive, so the work packages M1..M8 cannot be Maven
# sub-modules. Instead every package works in a private copy of the module (its sandbox), owns a fixed
# set of files (rlvm-machine/OWNERS.tsv), and publishes only those files into the main tree, under an
# exclusive lock, after the main module compiled and passed its fast tests with them.
#
#   scripts/machine-sandbox.sh init WP           create work/sandbox/WP/rlvm-machine from the main tree
#   scripts/machine-sandbox.sh pull WP           refresh every file WP does NOT own from the main tree
#                                                (owned files are never overwritten; files that left the
#                                                main tree and are not WP's are removed)
#   scripts/machine-sandbox.sh test WP [args]    compile and test the sandbox copy:
#                                                $MVN -o -q -f work/sandbox/WP/rlvm-machine/pom.xml test [args]
#                                                e.g. args = -Dtest=M3ReduceTest  or  -Dgroups=fast
#   scripts/machine-sandbox.sh publish WP        under the lock work/sandbox/.lock: copy WP's owned files
#                                                into the main tree, compile the main module and run its
#                                                fast test group (JUnit tag "fast"). On failure the
#                                                previous files are restored and the exit status is 1; on
#                                                success the jar is installed into .toolchain/m2 and a line
#                                                is appended to work/status/machine-WP.log
#   scripts/machine-sandbox.sh status            who published what and when; who holds the lock
#   scripts/machine-sandbox.sh claim WP PATH     give WP a NEW file (PATH relative to rlvm-machine/, not
#                                                owned by anybody yet): appends a line to OWNERS.tsv
#   scripts/machine-sandbox.sh owner PATH        print the package that owns PATH
#   scripts/machine-sandbox.sh changed WP        list WP's owned files that differ from the main tree
#
# Ownership: rlvm-machine/OWNERS.tsv, lines "pattern<TAB>package", patterns relative to rlvm-machine/,
# bash globs (* also crosses /), first match wins. A file that matches no line belongs to nobody and is
# never published.
#
# Exit status: 0 ok; 1 the operation failed (publish: main tree restored); 2 usage error.
#
# A publication is atomic for the main tree, also when it is interrupted:
#   - SIGINT / SIGTERM / SIGHUP during a publication (a caller's timeout, a closed terminal) do not stop
#     the script between "files replaced" and "verdict": it finishes (success), or it stops Maven, restores
#     the main tree and exits 1. Maven runs in a session of its own so that it can be stopped as a group.
#   - If the script is killed outright (SIGKILL, power), the journal work/sandbox/.inflight names what it
#     had replaced; the next command that takes the lock (init, pull, publish, claim) stops what is left
#     of that Maven run and either completes the publication (Maven had ended with status 0: it is
#     compiled, tested and installed) or restores the main tree from the backup, compiles and installs
#     it again and logs "publish WP: DIED before its verdict, main tree restored".
# Do not wrap `publish` in `timeout`: let it finish; it is bounded by Maven and the lock wait.
#
# Environment (for the self-test, scripts/machine-sandbox-selftest.sh):
#   MACHINE_SANDBOX_PROJECT   project root to work in (default: the root this script lives in)
#   MACHINE_SANDBOX_MODULE    module directory (default: rlvm-machine)
#   MACHINE_SANDBOX_LOCK_WAIT seconds to wait for the lock (default 900)
set -euo pipefail

TOOLROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
PROJ="${MACHINE_SANDBOX_PROJECT:-$TOOLROOT}"
MODULE="${MACHINE_SANDBOX_MODULE:-rlvm-machine}"
MAIN="$PROJ/$MODULE"
SANDBOXES="$PROJ/work/sandbox"
LOCK="$SANDBOXES/.lock"
PUBLOG="$SANDBOXES/publish.log"
STATUS="$PROJ/work/status"
OWNERS="$MAIN/OWNERS.tsv"
LOCK_WAIT="${MACHINE_SANDBOX_LOCK_WAIT:-900}"

die() { echo "machine-sandbox: $*" >&2; exit 2; }
fail() { echo "machine-sandbox: $*" >&2; exit 1; }
usage() { sed -n '2,48p' "$0" | sed 's/^# \{0,1\}//'; }
now() { date -u +%Y-%m-%dT%H:%M:%SZ; }

[ "$#" -ge 1 ] || { usage; exit 2; }
cmd="$1"; shift

mvn_run() {
  # shellcheck source=../env.sh
  . "$TOOLROOT/env.sh"
  # shellcheck disable=SC2086
  $MVN -o -q "$@"
}

# ---- ownership -----------------------------------------------------------------------------------------

PATTERNS=()
PACKAGES=()
load_owners() {
  local file="${1:-$OWNERS}"
  [ -f "$file" ] || die "no ownership table $file"
  PATTERNS=(); PACKAGES=()
  local pattern pkg
  while IFS=$'\t' read -r pattern pkg _; do
    case "$pattern" in ''|'#'*) continue ;; esac
    [ -n "${pkg:-}" ] || continue
    PATTERNS+=("$pattern"); PACKAGES+=("$pkg")
  done < "$file"
}

# owner_of RELPATH -> prints the package, or nothing
owner_of() {
  local path="$1" i
  for i in "${!PATTERNS[@]}"; do
    # shellcheck disable=SC2053
    if [[ "$path" == ${PATTERNS[$i]} ]]; then echo "${PACKAGES[$i]}"; return 0; fi
  done
  return 0
}

known_package() {
  local wp="$1" p
  for p in "${PACKAGES[@]}"; do [ "$p" = "$wp" ] && return 0; done
  return 1
}

need_wp() {
  [ "$#" -ge 1 ] || die "$cmd needs a work package (e.g. M3)"
  case "$1" in *[!A-Za-z0-9_-]*|'') die "bad work package name '$1'" ;; esac
  load_owners
  known_package "$1" || die "work package '$1' owns nothing in $OWNERS"
}

# files DIR -> relative paths of all files of a module copy (no build output)
files() {
  (cd "$1" && find . -type f -not -path './target/*' -not -name '*.class' | sed 's|^\./||' | LC_ALL=C sort)
}

with_lock() {
  mkdir -p "$SANDBOXES"
  exec 9>>"$LOCK"
  if ! flock -x -w "$LOCK_WAIT" 9; then
    fail "could not get $LOCK within $LOCK_WAIT s (holder: $(cat "$LOCK.holder" 2>/dev/null || echo unknown))"
  fi
  echo "$1 pid $$ since $(now)" > "$LOCK.holder"
  recover_inflight "$1"
}

JOURNAL="$SANDBOXES/.inflight"

# kill_group PGID: stops every process of a Maven session started by run_maven_session (TERM, then KILL)
kill_group() {
  local pgid="$1" i
  case "$pgid" in ''|*[!0-9]*) return 0 ;; esac
  kill -TERM -- "-$pgid" 2>/dev/null || return 0
  for i in 1 2 3 4 5 6 7 8 9 10; do
    kill -0 -- "-$pgid" 2>/dev/null || return 0
    sleep 0.3
  done
  kill -KILL -- "-$pgid" 2>/dev/null || true
  sleep 0.2
}

# run_maven_session PGIDFILE mvn-args...: Maven in a session (= process group) of its own; its id is written
# to PGIDFILE, and PGIDFILE.ok exists iff Maven ended with status 0. The session does not inherit the lock.
run_maven_session() {
  local pgidfile="$1"; shift
  # shellcheck source=../env.sh
  . "$TOOLROOT/env.sh"
  rm -f "$pgidfile" "$pgidfile.ok"
  # shellcheck disable=SC2086
  setsid -w bash -c 'echo $$ > "$0"; "$@" || exit $?; : > "$0.ok"' "$pgidfile" $MVN -o -q "$@" 9>&-
}

# reinstall_main: the main tree as it is now, compiled and installed without running tests (after an undo:
# an interrupted Maven run may have left half-written classes or a half-copied jar in .toolchain/m2)
reinstall_main() {
  rm -rf "$MAIN/target/classes" "$MAIN/target/test-classes"
  mvn_run -f "$MAIN/pom.xml" "-Drlvm.root=$PROJ" install -DskipTests > /dev/null 2>&1 || true
}

# undo_journal FILE: restores what the journal says a publication replaced (C changed, N new, D deleted)
undo_journal() {
  local j="$1" kind rest backup=""
  while IFS=' ' read -r kind rest; do
    case "$kind" in
      backup) backup="$rest" ;;
      C|D) [ -n "$backup" ] && [ -f "$backup/$rest" ] && copy_file "$backup" "$MAIN" "$rest" ;;
      N) rm -f "$MAIN/$rest" ;;
    esac
  done < "$j"
}

# recover_inflight WHO: called with the lock held. A journal means that a publication died between
# replacing files in the main tree and its bookkeeping. If its Maven run had ended with status 0 the
# publication is valid (compiled, fast tests green, jar installed) and is completed; otherwise what is
# left of the Maven run is stopped, the files are restored and the tree is compiled and installed again.
recover_inflight() {
  [ -f "$JOURNAL" ] || return 0
  local wp pgid="" backup n
  wp="$(sed -n 's/^wp //p' "$JOURNAL" | head -n 1)"
  backup="$(sed -n 's/^backup //p' "$JOURNAL" | head -n 1)"
  [ ! -f "$JOURNAL.pgid" ] || pgid="$(cat "$JOURNAL.pgid" 2>/dev/null || true)"
  kill_group "$pgid"
  mkdir -p "$STATUS"
  if [ -f "$JOURNAL.pgid.ok" ]; then
    n="$(grep -c '^[CND] ' "$JOURNAL" || true)"
    local line
    line="$(now) publish ${wp:-?}: $n files; main module compiled, fast tests green, jar installed (the script died after the verdict; completed by '$1')"
    echo "$line" >> "$PUBLOG"
    [ -z "$wp" ] || echo "$line" >> "$STATUS/machine-$wp.log"
    echo "machine-sandbox: a publication of ${wp:-?} whose script died after its verdict was completed." >&2
  else
    undo_journal "$JOURNAL"
    reinstall_main
    echo "$(now) publish ${wp:-?}: DIED before its verdict, main tree restored (found by '$1')" >> "$PUBLOG"
    [ -z "$wp" ] || echo "$(now) publish $wp: DIED before its verdict (the script was killed); the main tree was restored by '$1'. Publish again." >> "$STATUS/machine-$wp.log"
    echo "machine-sandbox: an interrupted publication of ${wp:-?} was found and undone (main tree restored)." >&2
  fi
  rm -f "$JOURNAL" "$JOURNAL.pgid" "$JOURNAL.pgid.ok"
  [ -z "$backup" ] || rm -rf "$backup"
}

unlock() {
  rm -f "$LOCK.holder"
  flock -u 9 || true
  exec 9>&-
}

copy_file() { # copy_file SRCROOT DSTROOT RELPATH
  mkdir -p "$(dirname "$2/$3")"
  cp -p "$1/$3" "$2/$3"
}

# ---- commands ------------------------------------------------------------------------------------------

case "$cmd" in
  init)
    need_wp "$@"; wp="$1"
    sb="$SANDBOXES/$wp/$MODULE"
    [ ! -e "$sb" ] || die "$sb exists already (use: pull $wp; or remove the directory to start over)"
    with_lock "init $wp"
    mkdir -p "$sb"
    while IFS= read -r f; do copy_file "$MAIN" "$sb" "$f"; done < <(files "$MAIN")
    # the parent pom next to the copy, so that <parent> resolves as in the main tree
    cp -p "$PROJ/pom.xml" "$SANDBOXES/$wp/pom.xml"
    unlock
    echo "sandbox $sb created ($(files "$sb" | wc -l) files). Test with: scripts/machine-sandbox.sh test $wp"
    ;;

  pull)
    need_wp "$@"; wp="$1"
    sb="$SANDBOXES/$wp/$MODULE"
    [ -d "$sb" ] || die "no sandbox $sb (use: init $wp)"
    with_lock "pull $wp"
    updated=0; removed=0
    while IFS= read -r f; do
      [ "$(owner_of "$f")" = "$wp" ] && continue
      if [ ! -f "$sb/$f" ] || ! cmp -s "$MAIN/$f" "$sb/$f"; then copy_file "$MAIN" "$sb" "$f"; updated=$((updated + 1)); fi
    done < <(files "$MAIN")
    while IFS= read -r f; do
      [ "$(owner_of "$f")" = "$wp" ] && continue
      if [ ! -f "$MAIN/$f" ]; then rm -f "$sb/$f"; removed=$((removed + 1)); fi
    done < <(files "$sb")
    cp -p "$PROJ/pom.xml" "$SANDBOXES/$wp/pom.xml"
    unlock
    echo "pull $wp: $updated files refreshed, $removed removed"
    ;;

  test)
    need_wp "$@"; wp="$1"; shift
    sb="$SANDBOXES/$wp/$MODULE"
    [ -d "$sb" ] || die "no sandbox $sb (use: init $wp)"
    # no stale classes of deleted or renamed sources (Maven recompiles the module on any change anyway)
    rm -rf "$sb/target/classes" "$sb/target/test-classes"
    mvn_run -f "$sb/pom.xml" "-Drlvm.root=$PROJ" test "$@"
    ;;

  changed)
    need_wp "$@"; wp="$1"
    sb="$SANDBOXES/$wp/$MODULE"
    [ -d "$sb" ] || die "no sandbox $sb (use: init $wp)"
    while IFS= read -r f; do
      [ "$(owner_of "$f")" = "$wp" ] || continue
      if [ ! -f "$MAIN/$f" ]; then echo "new      $f"; elif ! cmp -s "$MAIN/$f" "$sb/$f"; then echo "changed  $f"; fi
    done < <(files "$sb")
    while IFS= read -r f; do
      [ "$(owner_of "$f")" = "$wp" ] || continue
      [ -f "$sb/$f" ] || echo "deleted  $f"
    done < <(files "$MAIN")
    ;;

  publish)
    need_wp "$@"; wp="$1"
    sb="$SANDBOXES/$wp/$MODULE"
    [ -d "$sb" ] || die "no sandbox $sb (use: init $wp)"
    with_lock "publish $wp"
    trap 'unlock' EXIT
    stamp="$(date -u +%Y%m%dT%H%M%SZ)-$$"
    backup="$SANDBOXES/.backup/$wp-$stamp"
    out="$SANDBOXES/.publish-$wp.out"
    changed=(); created=(); deleted=()
    while IFS= read -r f; do
      [ "$(owner_of "$f")" = "$wp" ] || continue
      if [ ! -f "$MAIN/$f" ]; then created+=("$f")
      elif ! cmp -s "$MAIN/$f" "$sb/$f"; then changed+=("$f"); fi
    done < <(files "$sb")
    while IFS= read -r f; do
      [ "$(owner_of "$f")" = "$wp" ] || continue
      [ -f "$sb/$f" ] || deleted+=("$f")
    done < <(files "$MAIN")
    total=$(( ${#changed[@]} + ${#created[@]} + ${#deleted[@]} ))
    if [ "$total" -eq 0 ]; then
      echo "publish $wp: nothing to publish (owned files equal the main tree)"
      exit 0
    fi
    mkdir -p "$backup"
    # back up, write the journal (what a recovery must undo), then replace. From here to the verdict the
    # script is not stopped by INT / TERM / HUP: it finishes, or it undoes the publication itself.
    interrupted=0
    trap 'interrupted=1' INT TERM HUP
    for f in ${changed[@]+"${changed[@]}"} ${deleted[@]+"${deleted[@]}"}; do copy_file "$MAIN" "$backup" "$f"; done
    {
      echo "wp $wp"
      echo "backup $backup"
      for f in ${changed[@]+"${changed[@]}"}; do echo "C $f"; done
      for f in ${created[@]+"${created[@]}"}; do echo "N $f"; done
      for f in ${deleted[@]+"${deleted[@]}"}; do echo "D $f"; done
    } > "$JOURNAL.tmp"
    rm -f "$JOURNAL.pgid"
    mv "$JOURNAL.tmp" "$JOURNAL"
    for f in ${changed[@]+"${changed[@]}"} ${created[@]+"${created[@]}"}; do copy_file "$sb" "$MAIN" "$f"; done
    for f in ${deleted[@]+"${deleted[@]}"}; do rm -f "$MAIN/$f"; done
    echo "$(now) publish $wp: start ($total files)" >> "$PUBLOG"
    rm -rf "$MAIN/target/classes" "$MAIN/target/test-classes"
    # one Maven run: compile, the fast test group, then jar + install (nothing is installed if a test fails)
    rc=0
    run_maven_session "$JOURNAL.pgid" -f "$MAIN/pom.xml" "-Drlvm.root=$PROJ" install -Dgroups=fast -DexcludedGroups=slow > "$out" 2>&1 || rc=$?
    if [ "$rc" -ne 0 ]; then
      # Maven failed, or was stopped (then part of its session may still run: stop it before the tree changes)
      kill_group "$(cat "$JOURNAL.pgid" 2>/dev/null || true)"
      undo_journal "$JOURNAL"
      # leave the main tree compiled as it was before: the classes built from the rejected files must go
      # (the restored sources keep their old time stamps, so Maven would not recompile them); after a signal
      # the installed jar is rebuilt too (Maven may have been stopped while it copied it)
      if [ "$interrupted" -eq 1 ] || [ "$rc" -gt 128 ]; then
        reinstall_main
      else
        rm -rf "$MAIN/target/classes" "$MAIN/target/test-classes"
        mvn_run -f "$MAIN/pom.xml" "-Drlvm.root=$PROJ" test-compile > /dev/null 2>&1 || true
      fi
      rm -f "$JOURNAL" "$JOURNAL.pgid" "$JOURNAL.pgid.ok"
      rm -rf "$backup"
      if [ "$interrupted" -eq 1 ]; then
        echo "$(now) publish $wp: INTERRUPTED, main tree restored" >> "$PUBLOG"
        echo "machine-sandbox: publish $wp was interrupted (signal); the main tree was restored. Publish again." >&2
        exit 1
      fi
      echo "$(now) publish $wp: FAILED, main tree restored (output: $out)" >> "$PUBLOG"
      echo "machine-sandbox: publish $wp FAILED: the main module does not compile or its fast tests fail with your files." >&2
      echo "The main tree was restored. Run 'pull $wp', then 'test $wp -Dgroups=fast'. Last lines of $out:" >&2
      grep -v '^\[ERROR\] *$' "$out" | tail -n 40 >&2
      exit 1
    fi
    # (self-test hook: die here, after the verdict and before the bookkeeping)
    [ "${MACHINE_SANDBOX_TEST_DIE_AFTER_VERDICT:-0}" != 1 ] || kill -KILL $$
    rm -f "$JOURNAL" "$JOURNAL.pgid" "$JOURNAL.pgid.ok"
    trap - INT TERM HUP
    list="$(printf '%s ' ${changed[@]+"${changed[@]}"} ${created[@]+"${created[@]}"} | sed 's|src/main/java/rlvm/machine/||g; s|src/test/java/rlvm/machine/||g; s| $||')"
    [ "${#deleted[@]}" -eq 0 ] || list="$list (deleted: ${deleted[*]})"
    line="$(now) publish $wp: $total files [$list]; main module compiled, fast tests green, jar installed"
    mkdir -p "$STATUS"
    echo "$line" >> "$STATUS/machine-$wp.log"
    echo "$line" >> "$PUBLOG"
    rm -rf "$backup"
    echo "$line"
    ;;

  status)
    load_owners
    echo "module:     $MAIN"
    echo "sandboxes:  $(ls -d "$SANDBOXES"/*/"$MODULE" 2>/dev/null | sed "s|$SANDBOXES/||; s|/$MODULE||" | paste -sd' ' - || true)"
    if [ -f "$LOCK.holder" ]; then echo "lock:       held by $(cat "$LOCK.holder")"; else echo "lock:       free"; fi
    echo "--- last publications ($PUBLOG)"
    if [ -f "$PUBLOG" ]; then grep -v ': start' "$PUBLOG" | tail -n "${1:-25}"; else echo "(none yet)"; fi
    ;;

  claim)
    need_wp "$@"; wp="$1"
    [ "$#" -ge 2 ] || die "claim needs a path relative to $MODULE/"
    path="$2"
    case "$path" in /*|*..*) die "the path must be relative to $MODULE/ and must not contain '..'" ;; esac
    with_lock "claim $wp"
    load_owners
    cur="$(owner_of "$path")"
    if [ -n "$cur" ]; then unlock; [ "$cur" = "$wp" ] && { echo "$path is already owned by $wp"; exit 0; }; fail "$path is owned by $cur"; fi
    [ ! -e "$MAIN/$path" ] || { unlock; fail "$path exists in the main tree and has no owner: ask M6"; }
    # insert before the first glob line, so that the explicit path wins over catch-all patterns
    tmp="$OWNERS.tmp.$$"
    awk -v line="$path	$wp" 'BEGIN{done=0} { if (!done && $0 !~ /^#/ && $0 ~ /[*?]/) { print line; done=1 } print } END{ if (!done) print line }' "$OWNERS" > "$tmp"
    mv "$tmp" "$OWNERS"
    echo "$(now) claim $wp: $path" >> "$PUBLOG"
    unlock
    # every sandbox reads the table of the main tree, so the claim is visible at once
    echo "$path now belongs to $wp"
    ;;

  owner)
    [ "$#" -ge 1 ] || die "owner needs a path relative to $MODULE/"
    load_owners
    o="$(owner_of "$1")"
    echo "${o:-nobody}"
    ;;

  -h|--help|help)
    usage
    ;;

  *)
    die "unknown command '$cmd' (init, pull, test, publish, status, claim, owner, changed)"
    ;;
esac

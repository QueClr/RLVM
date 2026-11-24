#!/usr/bin/env bash
# scripts/machine-sandbox-selftest.sh - tests scripts/machine-sandbox.sh on a throw-away project with two
# fake packages (FA, FB) that publish concurrently. Nothing of the real module is touched; the fake
# artifact (rlvm:rlvm-sandbox-selftest) is removed from .toolchain/m2 at the end.
#
#   scripts/machine-sandbox-selftest.sh          exit 0 = every check passed
#   MACHINE_SANDBOX_SCRIPT=PATH ...              test another copy of the script (it must lie in scripts/)
#   KEEP=1 ...                                   keep the throw-away project work/sandbox/.selftest
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
SB="${MACHINE_SANDBOX_SCRIPT:-$ROOT/scripts/machine-sandbox.sh}"   # the script under test (a candidate before it is installed)
T="$ROOT/work/sandbox/.selftest"
rm -rf "$T"
mkdir -p "$T/fakemod/src/main/java/fake" "$T/fakemod/src/test/java/fake" "$T/work/status"
export MACHINE_SANDBOX_PROJECT="$T" MACHINE_SANDBOX_MODULE=fakemod MACHINE_SANDBOX_LOCK_WAIT=300

cleanup() {
  rm -rf "$ROOT/.toolchain/m2/rlvm/rlvm-sandbox-selftest"
  if [ "${KEEP:-0}" != 1 ]; then rm -rf "$T"; fi
}
trap cleanup EXIT

checks=0
ok() { checks=$((checks + 1)); echo "ok   $*"; }
bad() { echo "FAIL $*" >&2; exit 1; }

cp "$ROOT/pom.xml" "$T/pom.xml"
cat > "$T/fakemod/pom.xml" <<'POM'
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <parent><groupId>rlvm</groupId><artifactId>rlvm-parent</artifactId><version>0.1.0-SNAPSHOT</version></parent>
  <artifactId>rlvm-sandbox-selftest</artifactId>
  <packaging>jar</packaging>
  <properties><groups></groups><excludedGroups>slow</excludedGroups></properties>
  <build><plugins><plugin>
    <groupId>org.apache.maven.plugins</groupId><artifactId>maven-surefire-plugin</artifactId>
    <configuration><argLine>-Xmx256m</argLine><groups>${groups}</groups><excludedGroups>${excludedGroups}</excludedGroups></configuration>
  </plugin></plugins></build>
</project>
POM
printf 'src/main/java/fake/A.java\tFA\nsrc/main/java/fake/B.java\tFB\nsrc/test/java/fake/Shared.java\tFS\nsrc/test/java/fake/FA*.java\tFA\nsrc/test/java/fake/FB*.java\tFB\nNOTES-FA.md\tFA\n' > "$T/fakemod/OWNERS.tsv"
cat > "$T/fakemod/src/main/java/fake/A.java" <<'J'
package fake;
final class A { static int a() { return 1; } static int viaB() { return B.b() + 1; } }
J
cat > "$T/fakemod/src/main/java/fake/B.java" <<'J'
package fake;
final class B { static int b() { return 10; } static int viaA() { return A.a() + 10; } }
J
cat > "$T/fakemod/src/test/java/fake/Shared.java" <<'J'
package fake;
final class Shared { static final int VERSION = 1; }
J
cat > "$T/fakemod/src/test/java/fake/FATest.java" <<'J'
package fake;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
@Tag("fast") class FATest { @Test void aIsPositive() { assertTrue(A.a() > 0); assertTrue(A.viaB() > 0); } }
J
cat > "$T/fakemod/src/test/java/fake/FBTest.java" <<'J'
package fake;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
@Tag("fast") class FBTest { @Test void bIsPositive() { assertTrue(B.b() > 0); assertTrue(B.viaA() > 0); } }
J

main="$T/fakemod"
sa="$T/work/sandbox/FA/fakemod"
sb="$T/work/sandbox/FB/fakemod"

# ---- init, test ------------------------------------------------------------------------------------------
"$SB" init FA > /dev/null
"$SB" init FB > /dev/null
[ -f "$sa/src/main/java/fake/B.java" ] && [ -f "$T/work/sandbox/FA/pom.xml" ] || bad "init did not copy the module"
ok "init creates private copies"
"$SB" init FA > /dev/null 2>&1 && bad "a second init must refuse" || ok "a second init refuses"
"$SB" test FA > /dev/null || bad "test FA fails on the untouched copy"
ok "test compiles and tests the sandbox copy"
"$SB" publish FA | grep -q "nothing to publish" || bad "publish without changes"
ok "publish without changes does nothing"
[ "$("$SB" owner src/test/java/fake/FAExtraTest.java)" = FA ] || bad "owner by naming convention"
[ "$("$SB" owner pom.xml)" = nobody ] || bad "owner of an unlisted file"
ok "ownership: explicit lines, naming convention, nobody"

# ---- two packages publish concurrently -------------------------------------------------------------------
sed -i 's/return 1;/return 2;/' "$sa/src/main/java/fake/A.java"
echo "notes of FA" > "$sa/NOTES-FA.md"
sed -i 's/return 10;/return 20;/' "$sb/src/main/java/fake/B.java"
cat > "$sb/src/test/java/fake/FBMoreTest.java" <<'J'
package fake;
import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
@Tag("fast") class FBMoreTest { @Test void b() { assertEquals(20, B.b()); } }
J
"$SB" publish FA > "$T/pubA.out" 2>&1 & pa=$!
"$SB" publish FB > "$T/pubB.out" 2>&1 & pb=$!
ra=0; rb=0
wait "$pa" || ra=$?
wait "$pb" || rb=$?
[ "$ra" = 0 ] && [ "$rb" = 0 ] || { cat "$T/pubA.out" "$T/pubB.out" >&2; bad "concurrent publish: exit $ra / $rb"; }
grep -q 'return 2;' "$main/src/main/java/fake/A.java" || bad "A was not published"
grep -q 'return 20;' "$main/src/main/java/fake/B.java" || bad "B was not published"
[ -f "$main/src/test/java/fake/FBMoreTest.java" ] && [ -f "$main/NOTES-FA.md" ] || bad "new owned files were not published"
ok "two packages publish concurrently; both sets of files are in the main tree"
# the lock serialised them: in the publication log the second 'start' comes after the first 'jar installed'
order="$(grep -E 'publish F[AB]: (start|[0-9]+ files)' "$T/work/sandbox/publish.log" | sed -E 's/.*publish (F[AB]): (start|[0-9]+ files).*/\1 \2/' | awk '{print $1 ($2 == "start" ? "s" : "e")}' | paste -sd' ' -)"
case "$order" in "FAs FAe FBs FBe"|"FBs FBe FAs FAe") ok "the lock serialises publications ($order)" ;; *) bad "publications interleaved: $order" ;; esac
grep -q 'publish FA' "$T/work/status/machine-FA.log" && grep -q 'publish FB' "$T/work/status/machine-FB.log" || bad "status logs"
ok "each publication is logged in work/status/machine-WP.log"
[ -f "$ROOT/.toolchain/m2/rlvm/rlvm-sandbox-selftest/0.1.0-SNAPSHOT/rlvm-sandbox-selftest-0.1.0-SNAPSHOT.jar" ] || bad "the jar was not installed"
ok "the jar is installed after a publication"
"$SB" status | grep -q 'publish FB' || bad "status"
ok "status lists the publications"

# ---- pull never overwrites owned files -------------------------------------------------------------------
echo "// FA's unpublished work" >> "$sa/src/main/java/fake/A.java"
"$SB" pull FA > /dev/null
grep -q "unpublished work" "$sa/src/main/java/fake/A.java" || bad "pull overwrote an owned file"
grep -q 'return 20;' "$sa/src/main/java/fake/B.java" || bad "pull did not refresh a foreign file"
[ -f "$sa/src/test/java/fake/FBMoreTest.java" ] || bad "pull did not bring a new foreign file"
ok "pull refreshes foreign files and keeps owned ones"

# ---- a publication that breaks the main module is rolled back ---------------------------------------------
before="$(cat "$main/src/main/java/fake/A.java")"
sed -i 's/static int a() { return 2; }/static int renamed() { return 2; }/' "$sa/src/main/java/fake/A.java"   # B.viaA() no longer compiles
if "$SB" publish FA > "$T/pubBad.out" 2>&1; then bad "a publication that does not compile must fail"; fi
[ "$(cat "$main/src/main/java/fake/A.java")" = "$before" ] || bad "the main tree was not restored after a failed publication"
grep -q 'FAILED' "$T/work/sandbox/publish.log" || bad "the failure is not in the publication log"
ok "a publication that does not compile fails and the main tree is restored"
# ... and one whose fast tests fail
sed -i 's/static int renamed() { return 2; }/static int a() { return -5; }/' "$sa/src/main/java/fake/A.java"
if "$SB" publish FA > "$T/pubBad2.out" 2>&1; then bad "a publication with failing fast tests must fail"; fi
[ "$(cat "$main/src/main/java/fake/A.java")" = "$before" ] || bad "the main tree was not restored after failing tests"
ok "a publication whose fast tests fail is rolled back"
"$SB" publish FB | grep -q "nothing to publish" || bad "the main tree is not usable after a rollback"
( . "$ROOT/env.sh"; $MVN -o -q -f "$main/pom.xml" test > /dev/null ) || bad "the main module does not build after a rollback"
ok "the main module builds after the rollbacks"

# ---- foreign files are never published -------------------------------------------------------------------
sed -i 's/return -5;/return 3;/' "$sa/src/main/java/fake/A.java"
echo "// FA edits B, which it does not own" >> "$sa/src/main/java/fake/B.java"
echo "final class Stray {}" > "$sa/src/main/java/fake/Stray.java"
"$SB" publish FA > /dev/null || bad "publish FA"
grep -q 'return 3;' "$main/src/main/java/fake/A.java" || bad "owned change not published"
grep -q "does not own" "$main/src/main/java/fake/B.java" && bad "a foreign file was published"
[ ! -f "$main/src/main/java/fake/Stray.java" ] || bad "an unowned new file was published"
ok "only owned files are published"
"$SB" claim FA src/main/java/fake/Stray.java > /dev/null
[ "$("$SB" owner src/main/java/fake/Stray.java)" = FA ] || bad "claim"
if "$SB" claim FB src/main/java/fake/A.java > /dev/null 2>&1; then bad "claiming an owned file must fail"; fi
"$SB" publish FA > /dev/null || bad "publish after claim"
[ -f "$main/src/main/java/fake/Stray.java" ] || bad "a claimed file was not published"
ok "claim gives a package a new file; an owned file cannot be claimed"
"$SB" pull FA > /dev/null
grep -q "does not own" "$sa/src/main/java/fake/B.java" && bad "pull did not restore a foreign file" || ok "pull restores foreign files that were edited"
# deletion of an owned file is published too
rm "$sa/src/main/java/fake/Stray.java"
"$SB" changed FA | grep -q "deleted  src/main/java/fake/Stray.java" || bad "changed does not list a deletion"
"$SB" publish FA > /dev/null || bad "publish deletion"
[ ! -f "$main/src/main/java/fake/Stray.java" ] || bad "the deletion was not published"
ok "deleting an owned file is published"

# ---- a publication that is interrupted leaves the main tree whole ------------------------------------------
# A slow fast test keeps Maven busy long enough to interrupt the publication in the middle.
cat > "$sa/src/test/java/fake/FASlowTest.java" <<'J'
package fake;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
@Tag("fast") class FASlowTest { @Test void slow() throws Exception { Thread.sleep(Long.getLong("fake.sleep", 6000L)); } }
J
journal="$T/work/sandbox/.inflight"
jar="$ROOT/.toolchain/m2/rlvm/rlvm-sandbox-selftest/0.1.0-SNAPSHOT/rlvm-sandbox-selftest-0.1.0-SNAPSHOT.jar"
wait_maven() { # wait_maven: until the publication in flight has started its Maven session
  local i
  for i in $(seq 1 200); do [ -s "$journal.pgid" ] && return 0; sleep 0.1; done
  bad "the publication did not start a Maven session"
}
main_builds() { ( . "$ROOT/env.sh"; $MVN -o -q -f "$main/pom.xml" test -Dfake.sleep=1 > /dev/null ) || bad "the main module does not build $1"; }

# (1) SIGTERM to the script alone (a caller's timeout): it is not stopped between "files replaced" and the
#     verdict; Maven, in its own session, finishes; the publication is complete
sed -i 's/return 3;/return 4;/' "$sa/src/main/java/fake/A.java"
"$SB" publish FA > "$T/pubTerm.out" 2>&1 & pp=$!
wait_maven
kill -TERM "$pp"
rt=0; wait "$pp" || rt=$?
[ "$rt" = 0 ] || { cat "$T/pubTerm.out" >&2; bad "a publication that got SIGTERM must finish (exit $rt)"; }
grep -q 'return 4;' "$main/src/main/java/fake/A.java" && [ -f "$main/src/test/java/fake/FASlowTest.java" ] || bad "SIGTERM: the files are not in the main tree"
[ ! -e "$journal" ] || bad "SIGTERM: the journal was left behind"
ok "SIGTERM during a publication does not stop it: it finishes and is complete"

# (2) Maven itself is stopped (the whole process group was signalled): the publication fails, the tree is restored
before="$(cat "$main/src/main/java/fake/A.java")"
sed -i 's/return 4;/return 5;/' "$sa/src/main/java/fake/A.java"
"$SB" publish FA > "$T/pubGroup.out" 2>&1 & pp=$!
wait_maven
kill -TERM -- "-$(cat "$journal.pgid")"
rt=0; wait "$pp" || rt=$?
[ "$rt" = 1 ] || { cat "$T/pubGroup.out" >&2; bad "a publication whose Maven run was stopped must fail (exit $rt)"; }
[ "$(cat "$main/src/main/java/fake/A.java")" = "$before" ] || bad "stopped Maven: the main tree was not restored"
[ ! -e "$journal" ] || bad "stopped Maven: the journal was left behind"
[ -f "$jar" ] || bad "stopped Maven: no installed jar"
main_builds "after a stopped Maven run"
ok "a publication whose Maven run is stopped fails, restores the main tree and reinstalls the jar"

# (3) SIGKILL to the script in the middle: nothing can react; the next command that takes the lock undoes it
"$SB" publish FA > "$T/pubKill.out" 2>&1 & pp=$!
wait_maven
pg="$(cat "$journal.pgid")"
kill -KILL "$pp"
wait "$pp" 2>/dev/null || true
[ -f "$journal" ] || bad "SIGKILL: no journal to recover from"
grep -q 'return 5;' "$main/src/main/java/fake/A.java" || bad "SIGKILL: expected the unverified file in the main tree before recovery"
"$SB" pull FB > "$T/pullRecover.out" 2>&1 || { cat "$T/pullRecover.out" >&2; bad "pull after a killed publication"; }
[ "$(cat "$main/src/main/java/fake/A.java")" = "$before" ] || bad "SIGKILL: recovery did not restore the main tree"
[ ! -e "$journal" ] || bad "SIGKILL: the journal was left behind"
kill -0 -- "-$pg" 2>/dev/null && bad "SIGKILL: the orphaned Maven session is still running"
grep -q 'DIED before its verdict' "$T/work/sandbox/publish.log" || bad "SIGKILL: recovery is not in the publication log"
grep -q 'DIED before its verdict' "$T/work/status/machine-FA.log" || bad "SIGKILL: the package's log does not say that its publication died"
grep -q 'return 4;' "$sb/src/main/java/fake/A.java" || bad "SIGKILL: pull after the recovery brought the unverified file"
main_builds "after the recovery"
ok "a publication killed in the middle is undone by the next command that takes the lock"

# (4) SIGKILL after the verdict, before the bookkeeping: the publication is valid and is completed
MACHINE_SANDBOX_TEST_DIE_AFTER_VERDICT=1 "$SB" publish FA > "$T/pubLate.out" 2>&1 & pp=$!
wait "$pp" 2>/dev/null || true
[ -f "$journal" ] && [ -f "$journal.pgid.ok" ] || bad "late SIGKILL: journal and verdict marker expected"
"$SB" publish FB > "$T/pubAfterLate.out" 2>&1 || { cat "$T/pubAfterLate.out" >&2; bad "publish after a late kill"; }
grep -q 'return 5;' "$main/src/main/java/fake/A.java" || bad "late SIGKILL: a verified publication was undone"
grep -q 'completed by' "$T/work/sandbox/publish.log" || bad "late SIGKILL: completion is not in the publication log"
[ ! -e "$journal" ] && [ ! -e "$journal.pgid.ok" ] || bad "late SIGKILL: the journal was left behind"
"$SB" publish FA | grep -q "nothing to publish" || bad "late SIGKILL: the sandbox and the main tree differ after completion"
main_builds "after a completed late publication"
ok "a publication killed after its verdict is completed, not undone"
[ -z "$(ls -A "$T/work/sandbox/.backup" 2>/dev/null)" ] || bad "backups were left behind: $(ls "$T/work/sandbox/.backup")"
ok "no backup directory is left behind"

echo "machine-sandbox self-test: $checks checks passed"

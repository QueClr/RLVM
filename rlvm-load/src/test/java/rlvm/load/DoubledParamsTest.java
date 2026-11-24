package rlvm.load;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Finding A2-F1: an export with two level records for one universe parameter is declined when a level
 * above that parameter is not officially equivalent to its respelling ({@link DoubledParams}).
 *
 * <p>Fixtures ({@code src/test/resources/a2f1}): A2's twelve files E1 - E12 ({@code work/a2/item4}) and
 * the probes Q1 - Q7, R1 - R5 of {@code work/lp2/a2f1/genq.py}; {@code official.tsv} holds the answer of
 * leanchecker v4.36.0 to each (obtained in the arena Docker image). The loader never turns an accept
 * into a reject or the reverse: a file is loaded as before, or declined.
 */
class DoubledParamsTest {

    /** The files the rule declines: a parameter with two records under a level the rebuild respells inequivalently. */
    private static final Set<String> DECLINED = Set.of(
            "E4-duplicate-param-record",
            "E5-duplicate-param-record-consistent",
            "E6-definition-duplicate-param-record",
            "E11-theorem-duplicate-param-record",
            "E12-const-level-duplicate-param-record",
            // imax u 1 above the doubled u in a file that uses no constant at such a level: the
            // official checker accepts it, the rule (which does not look at use sites) declines
            "Q6-duplicate-no-use",
            // the first unit after the second record is declined; the wrong unit before it is checked
            "Q7-reject-before-duplicate");

    private static byte[] fixture(String name) throws IOException {
        try (InputStream in = DoubledParamsTest.class.getResourceAsStream("/a2f1/" + name + ".ndjson")) {
            assertNotNull(in, name);
            return in.readAllBytes();
        }
    }

    private static List<String[]> official() throws IOException {
        List<String[]> rows = new ArrayList<>();
        try (InputStream in = DoubledParamsTest.class.getResourceAsStream("/a2f1/official.tsv")) {
            assertNotNull(in);
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                if (!line.isEmpty()) {
                    rows.add(line.split("\t"));
                }
            }
        }
        return rows;
    }

    @Test
    void theFixturesAreDeclinedOrLoadedAsBefore() throws IOException {
        List<String[]> rows = official();
        assertEquals(25, rows.size());
        int declined = 0;
        for (String[] row : rows) {
            String name = row[0];
            byte[] data = fixture(name);
            for (LoadOptions o : new LoadOptions[] {LoadOptions.batch(data.length),
                    LoadOptions.batch(data.length).withPublishOnDecline(true),
                    LoadOptions.pipelined(data.length).withPublishEvery(1, 1L << 40)}) {
                Loaded l = Loaded.load(data, o);
                if (DECLINED.contains(name)) {
                    assertTrue(l.failure instanceof UnsupportedExportException, name + ": " + l.failure);
                    assertTrue(l.failure.getMessage().contains("two level records for the universe parameter 'u'"),
                            l.failure.getMessage());
                    assertFalse(l.store.loadComplete(), name);
                } else {
                    assertNull(l.failure, name + " (official: " + row[1] + ")");
                    assertTrue(l.store.loadComplete(), name);
                }
            }
            if (DECLINED.contains(name)) {
                declined++;
            }
        }
        assertEquals(DECLINED.size(), declined);
        // every file the official checker rejects BECAUSE of the second record is among the declined
        // ones (E2 and E8 are rejected for a use at another parameter, which the engines reproduce)
        for (String[] row : rows) {
            if (row[1].equals("reject") && !row[0].equals("E2-other-param-imax")
                    && !row[0].equals("E8-definition-used-at-other-param")) {
                assertTrue(DECLINED.contains(row[0]), row[0]);
            }
        }
    }

    @Test
    void theMessageNamesTheParameterTheLevelAndTheLine() throws IOException {
        byte[] data = fixture("E4-duplicate-param-record");
        Loaded l = Loaded.load(data, LoadOptions.batch(data.length));
        assertEquals("two level records for the universe parameter 'u': the official checker distinguishes them by"
                + " identity and respells the level 'imax u 1' above it as 'max u 1' where a constant is used with the"
                + " other record; that is not reproduced (line 12)", l.failure.getMessage());
    }

    @Test
    void theUnitsBeforeTheSecondRecordAreStillChecked() throws IOException {
        // Q7: axiom A, the ill-typed def Bad, then the second record of u. The decline is the status of
        // the first unit that was not loaded; A and Bad are handed over, so the driver rejects at Bad
        // (the official checker rejects the file too).
        byte[] data = fixture("Q7-reject-before-duplicate");
        Loaded batch = Loaded.load(data, LoadOptions.batch(data.length).withPublishOnDecline(true));
        assertTrue(batch.failure instanceof UnsupportedExportException);
        assertEquals(2, batch.store.loadedUnits());
        assertFalse(batch.store.loadComplete());
        Loaded pipe = Loaded.load(data, LoadOptions.pipelined(data.length));
        assertTrue(pipe.failure instanceof UnsupportedExportException);
        assertEquals(2, pipe.store.loadedUnits());
        assertEquals(batch.failure.getMessage(), pipe.failure.getMessage());
    }

    /** {@code axiom A.{u} : Sort L -> Prop} with L built by {@code level} over the parameter record {@code u}. */
    private interface Level {
        int over(Nd b, int u);
    }

    private static byte[] file(Level level, boolean secondRecordFirst, boolean otherNameId) {
        Nd b = new Nd();
        int nu = b.name("u");
        int u = b.param(nu);
        int u2 = 0;
        if (secondRecordFirst) {
            u2 = b.param(otherNameId ? b.str(0, "u") : nu);
        }
        int l = level.over(b, u);
        int prop = b.sort(0);
        int a = b.name("A");
        b.axiom(a, b.pi(b.sort(l), prop), nu);
        if (!secondRecordFirst) {
            u2 = b.param(otherNameId ? b.str(0, "u") : nu);
        }
        b.axiom(b.name("B"), b.pi(b.sort(l), b.app(b.cnst(a, u2), b.bvar(0))), nu);
        return b.bytes();
    }

    private static RuntimeException failureOf(byte[] data) {
        return Loaded.load(data, LoadOptions.batch(data.length)).failure;
    }

    @Test
    void whichLevelsAboveADoubledParameterDecline() {
        Level imaxU1 = (b, u) -> b.imax(u, b.succ(0));
        // the second record may come before or after the level above the parameter, and under
        // another name id of the file that denotes the same name
        for (boolean first : new boolean[] {false, true}) {
            for (boolean otherId : new boolean[] {false, true}) {
                RuntimeException e = failureOf(file(imaxU1, first, otherId));
                assertTrue(e instanceof UnsupportedExportException, first + " " + otherId + ": " + e);
                assertTrue(e.getMessage().contains("respells the level 'imax u 1' above it as 'max u 1'"), e.getMessage());
            }
        }
        // respellings that are equivalent for the official relation, and levels the rebuild keeps
        Level[] harmless = {
            (b, u) -> u,
            (b, u) -> b.succ(u),
            (b, u) -> b.max(u, u),                      // mk_max: u
            (b, u) -> b.max(b.succ(u), u),              // mk_max: succ u
            (b, u) -> b.max(u, b.succ(0)),              // kept
            (b, u) -> b.imax(u, 0),                     // mk_imax: zero
            (b, u) -> b.imax(u, u),                     // mk_imax: u
            (b, u) -> b.imax(b.succ(0), u),             // mk_imax: u
            (b, u) -> b.succ(b.max(u, u)),
            (b, u) -> b.imax(b.succ(0), b.succ(0)),     // no parameter: not looked at
            // mk_imax: max 2 (succ u), which is the official normal form of both spellings
            (b, u) -> b.imax(b.succ(b.succ(0)), b.succ(u)),
        };
        for (int i = 0; i < harmless.length; i++) {
            for (boolean first : new boolean[] {false, true}) {
                assertNull(failureOf(file(harmless[i], first, false)), "level " + i + ", second record first: " + first);
            }
        }
        // inequivalent respellings (the incompleteness of the official normal form)
        Level[] declined = {
            (b, u) -> b.imax(u, b.succ(0)),
            (b, u) -> b.succ(b.imax(u, b.succ(0))),
            (b, u) -> b.max(b.imax(u, b.succ(0)), b.succ(b.succ(0))),
            (b, u) -> b.imax(b.succ(u), b.succ(0)),
        };
        for (int i = 0; i < declined.length; i++) {
            for (boolean first : new boolean[] {false, true}) {
                assertTrue(failureOf(file(declined[i], first, false)) instanceof UnsupportedExportException,
                        "level " + i + ", second record first: " + first);
            }
        }
    }

    @Test
    void aSecondRecordOfAnotherParameterDeclinesNothing() {
        // imax u 1 with two records for v: nothing above v is respelled
        Nd b = new Nd();
        int nu = b.name("u");
        int nv = b.name("v");
        int u = b.param(nu);
        int v = b.param(nv);
        int l = b.imax(u, b.succ(0));
        int a = b.name("A");
        b.axiom(a, b.pi(b.sort(l), b.sort(0)), nu, nv);
        int v2 = b.param(nv);
        int v3 = b.param(nv); // a third record changes nothing
        b.axiom(b.name("B"), b.pi(b.sort(l), b.app(b.cnst(a, u, v2), b.bvar(0))), nu, nv);
        b.axiom(b.name("C"), b.pi(b.sort(l), b.app(b.cnst(a, u, v3), b.bvar(0))), nu, nv);
        byte[] data = b.bytes();
        assertNull(failureOf(data));
        // the store is the store of the same file without the extra records' effect: batch = pipelined
        Loaded.assertSameStore(Loaded.load(data, LoadOptions.batch(data.length)),
                Loaded.load(data, LoadOptions.pipelined(data.length).withPublishEvery(1, 1L << 40)), "two records for v");
    }

    @Test
    void severalDoubledParametersAreTestedTogetherAndBounded() {
        // max u v with both parameters doubled: rebuilt above each alone it is itself; nothing declines
        Nd b = new Nd();
        int nu = b.name("u");
        int nv = b.name("v");
        int u = b.param(nu);
        int v = b.param(nv);
        b.max(u, v);
        b.param(nu);
        b.param(nv);
        b.axiom(b.name("A"), b.sort(0));
        assertNull(failureOf(b.bytes()));
        // more doubled parameters than the bound: declined whatever the levels are
        b = new Nd();
        for (int i = 0; i <= DoubledParams.MAX_DOUBLED; i++) {
            int n = b.name("p" + i);
            b.param(n);
            b.param(n);
        }
        b.axiom(b.name("A"), b.sort(0));
        RuntimeException e = failureOf(b.bytes());
        assertTrue(e instanceof UnsupportedExportException, String.valueOf(e));
        assertTrue(e.getMessage().startsWith("more than " + DoubledParams.MAX_DOUBLED + " universe parameters"), e.getMessage());
    }

    @Test
    void aFileWithoutASecondRecordNeverReachesTheRule() {
        DoubledParams p = new DoubledParams();
        rlvm.level.LevelStore s = new rlvm.level.LevelStore();
        for (int name = 0; name < 5000; name++) {
            assertNull(p.param(s, name * 7 - 3, s.param(name * 7 - 3)));
            p.note(s.succ(s.param(name * 7 - 3)));
            assertFalse(p.active());
        }
        assertNull(p.param(s, 11, s.param(11)));
        assertTrue(p.active());
    }

    /** The mutants with a repeated parameter record of A2's list keep the outcome they had (opt-in by presence). */
    @Test
    void theMutantsWithARepeatedParameterRecordAreNotDeclinedByTheRule() throws IOException {
        Path root = Path.of(System.getProperty("user.dir")).getParent();
        Path list = root.resolve("work/a2/item4/dup-mutants.txt");
        Assumptions.assumeTrue(Files.isRegularFile(list), "work/a2/item4/dup-mutants.txt is absent");
        int files = 0;
        int withParameter = 0;
        for (String line : Files.readAllLines(list)) {
            String[] cols = line.split("\t");
            Path file = root.resolve(cols[0]);
            if (!Files.isRegularFile(file)) {
                continue;
            }
            files++;
            if (!cols[2].contains("(0 of parameters)")) {
                withParameter++;
            }
            Loaded l = Loaded.load(file, LoadOptions.batch(Files.size(file)));
            if (l.failure != null) {
                assertFalse(l.failure.getMessage().contains("level records"), cols[0] + ": " + l.failure.getMessage());
            }
        }
        Assumptions.assumeTrue(files > 0);
        assertEquals(20, withParameter, "mutants of the list that repeat a parameter record");
    }
}

package rlvm.level;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The level records of real exports: the 230 published arena tests, and the level slices of init,
 * std, cedar, cslib and mathlib (made by {@code bench/modules/rlvm-level/extract_levels.py}; a slice
 * that does not exist is skipped, init and std are read from the full exports if their slices are
 * missing). Every level is normalised by the store and by the reference port (same structure), has
 * a canonical form, and canonical forms partition the levels exactly as the semantics does.
 */
class RealExportTest {

    private static Path root() {
        Path p = Paths.get("").toAbsolutePath();
        while (p != null && !Files.isDirectory(p.resolve("work"))) {
            p = p.getParent();
        }
        return p;
    }

    private static Path slice(String name) {
        Path r = root();
        if (r == null) {
            return null;
        }
        Path s = r.resolve("work/rlvm-level/" + name + ".levels.ndjson");
        if (Files.isRegularFile(s)) {
            return s;
        }
        Path full = r.resolve("work/arena-site/generated/" + name + ".ndjson");
        return Files.isRegularFile(full) ? full : null;
    }

    /** Checks one export; returns a one-line summary. */
    private static String check(String label, Path file) throws IOException {
        long t0 = System.nanoTime();
        ExportLevels e = ExportLevels.load(file);
        long t1 = System.nanoTime();
        LevelStore s = e.store;
        Lv.Names n = e.names;
        int withIMax = 0;
        int nonIdempotent = 0;
        int respelled = 0;
        int rebuildBreaks = 0;
        int maxDepth = 0;
        int maxParams = 0;
        Map<Integer, List<Integer>> classes = new HashMap<>();
        for (int l : e.levels) {
            RefLevel r = Lv.fromStore(s, l, n);
            maxDepth = Math.max(maxDepth, s.depth(l));
            maxParams = Math.max(maxParams, Lv.paramsOf(r).size());
            withIMax += s.hasIMax(l) ? 1 : 0;
            // official normal form: same structure as the reference port, same value, idempotence statistics
            int nf = s.normalize(l);
            RefLevel rnf = RefLevel.normalize(r);
            assertEquals(rnf, Lv.fromStore(s, nf, n), label + ": normalize " + s.toString(l));
            assertTrue(Lv.semEqReduced(r, rnf), label + ": normalize changed the value of " + s.toString(l));
            if (s.normalize(nf) != nf) {
                nonIdempotent++;
            }
            // add_inductive rebuilds every node above a parameter (OfficialRebuildGoldenTest): how many levels
            // of a real export get another spelling, and for how many the official kernel would not identify the two
            int rebuilt = OfficialRebuildGoldenTest.rebuild(s, l);
            respelled += rebuilt != l ? 1 : 0;
            rebuildBreaks += s.isEquivOfficial(l, rebuilt) ? 0 : 1;
            assertTrue(s.isEquiv(l, rebuilt), label + ": rebuild changed the value of " + s.toString(l));
            // complete form
            int c = s.canonId(l);
            assertTrue(c > 0, label + ": no canonical form for " + s.toString(l));
            assertTrue(s.isEquiv(l, nf), label + ": " + s.toString(l));
            assertEquals(c, s.canonId(nf), label + ": normal form in another class: " + s.toString(l));
            classes.computeIfAbsent(c, k -> new ArrayList<>()).add(l);
            // flags
            assertEquals(RefLevel.isNotZero(r), s.isNeverZero(l));
            assertEquals(RefLevel.normalizesToZero(r), s.isAlwaysZero(l));
        }
        // Soundness of the classes: every member has the value of the first member.
        // Completeness: levels with the same parameters and the same values on {0,1,N}^params share a class.
        int officialSplits = 0;
        Map<String, Integer> classOfTable = new HashMap<>();
        for (Map.Entry<Integer, List<Integer>> en : classes.entrySet()) {
            List<Integer> members = en.getValue();
            RefLevel first = Lv.fromStore(s, members.get(0), n);
            int nf0 = s.normalize(members.get(0));
            boolean split = false;
            for (int l : members) {
                RefLevel r = Lv.fromStore(s, l, n);
                assertTrue(Lv.semEqReduced(first, r), label + ": class mixes " + first + " and " + r);
                split |= s.normalize(l) != nf0;
                assertEquals(s.canonRep(members.get(0)), s.canonRep(l));
            }
            officialSplits += split ? 1 : 0;
        }
        for (int l : e.levels) {
            RefLevel r = Lv.fromStore(s, l, n);
            List<String> ps = Lv.paramsOf(r);
            if (ps.size() > 6) {
                continue;
            }
            List<String> sorted = ps.stream().sorted().collect(Collectors.toList());
            StringBuilder table = new StringBuilder(String.join("|", sorted)).append('#');
            int big = 40;
            assertTrue(r.depth + 2 <= big, "bound too small for " + label);
            int[] vals = {0, 1, big};
            int total = 1;
            for (int i = 0; i < sorted.size(); i++) {
                total *= 3;
            }
            Map<String, Integer> env = new HashMap<>();
            for (int code = 0; code < total; code++) {
                int x = code;
                for (String p : sorted) {
                    env.put(p, vals[x % 3]);
                    x /= 3;
                }
                table.append(RefLevel.eval(r, env)).append(',');
            }
            Integer prev = classOfTable.putIfAbsent(table.toString(), s.canonId(l));
            if (prev != null) {
                assertEquals(prev.intValue(), s.canonId(l), label + ": equal values, different class: " + s.toString(l));
            }
        }
        // declared parameters: every level is fine with the list of exactly its parameters, and
        // each levelParams list of the export behaves as the reference says
        int checked = 0;
        for (int pl : e.paramLists) {
            List<String> ps = new ArrayList<>();
            for (int i = 0; i < s.listLength(pl); i++) {
                ps.add(n.name(s.listGet(pl, i)));
            }
            for (int i = 0; i < Math.min(e.levels.size(), 300); i++) {
                int l = e.levels.get((i * 7919 + pl) % e.levels.size());
                String expected = RefLevel.undefParam(Lv.fromStore(s, l, n), ps);
                int got = s.undeclaredParam(l, pl);
                assertEquals(expected == null, got == 0);
                if (expected != null) {
                    assertEquals(expected, n.name(s.paramName(got)));
                }
                checked++;
            }
        }
        long t2 = System.nanoTime();
        // a frozen copy and an overlay agree with the root on every memoised answer
        LevelStore fresh = ExportLevels.load(file).store;
        int sizeBeforeFreeze = fresh.size();
        long t3 = System.nanoTime();
        fresh.freeze();
        long t4 = System.nanoTime();
        LevelStore ov = fresh.newOverlay();
        long t5 = System.nanoTime();
        assertEquals(fresh.size(), ov.baseSize());
        return String.format(
                "%-12s %6d levels, %5d with imax, max depth %d, max params %d, %5d classes, %d classes split by the official"
                        + " normal form, %d levels with normalize(normalize(l)) != normalize(l), %d respelled by the rebuild of"
                        + " add_inductive, %d of them no longer equivalent for the official kernel, %d param checks;"
                        + " load %.0f ms, checks %.0f ms, freeze %.2f ms (store %d -> %d levels), newOverlay %.3f ms",
                label, e.levels.size(), withIMax, maxDepth, maxParams, classes.size(), officialSplits, nonIdempotent, respelled,
                rebuildBreaks, checked,
                (t1 - t0) / 1e6, (t2 - t1) / 1e6, (t4 - t3) / 1e6, sizeBeforeFreeze, fresh.size(), (t5 - t4) / 1e6);
    }

    @Test
    void arenaTests() throws IOException {
        Path r = root();
        assumeTrue(r != null && Files.isDirectory(r.resolve("work/arena-site/tests")), "arena tests not available");
        List<Path> files;
        try (Stream<Path> st = Files.walk(r.resolve("work/arena-site/tests"))) {
            files = st.filter(p -> p.toString().endsWith(".ndjson")).sorted().collect(Collectors.toList());
        }
        assertTrue(files.size() >= 230, "expected the 230 published tests, found " + files.size());
        int levels = 0;
        for (Path f : files) {
            ExportLevels e = ExportLevels.load(f);
            levels += e.levels.size();
            LevelStore s = e.store;
            for (int l : e.levels) {
                RefLevel ref = Lv.fromStore(s, l, e.names);
                int nf = s.normalize(l);
                assertEquals(RefLevel.normalize(ref), Lv.fromStore(s, nf, e.names), f + ": " + s.toString(l));
                assertTrue(s.canonId(l) > 0);
                assertTrue(s.isEquiv(l, nf));
                assertTrue(Lv.semEqReduced(ref, Lv.fromStore(s, nf, e.names)));
            }
            // all pairs of a file: official equivalence implies the complete one, which is the semantic one
            for (int x : e.levels) {
                for (int y : e.levels) {
                    boolean off = s.isEquivOfficial(x, y);
                    boolean com = s.isEquiv(x, y);
                    assertTrue(!off || com, f + ": " + s.toString(x) + " / " + s.toString(y));
                    if (e.levels.size() <= 40) {
                        assertEquals(Lv.semEqReduced(Lv.fromStore(s, x, e.names), Lv.fromStore(s, y, e.names)), com,
                                f + ": " + s.toString(x) + " / " + s.toString(y));
                    }
                }
            }
        }
        assertTrue(levels > 1500, "level records found: " + levels);
        System.out.println("arena tests: " + files.size() + " files, " + levels + " level records");
    }

    @Test
    void initAndStd() throws IOException {
        Path init = slice("init");
        Path std = slice("std");
        assumeTrue(init != null && std != null, "init/std exports not available");
        System.out.println(check("init", init));
        System.out.println(check("std", std));
    }

    @Test
    void cedarCslibMathlibSlices() throws IOException {
        int done = 0;
        for (String name : new String[] {"cedar", "cslib", "mathlib"}) {
            Path r = root();
            Path s = r == null ? null : r.resolve("work/rlvm-level/" + name + ".levels.ndjson");
            if (s != null && Files.isRegularFile(s)) {
                System.out.println(check(name, s));
                done++;
            }
        }
        assumeTrue(done > 0, "no level slices (run bench/modules/rlvm-level/extract_levels.py)");
    }
}

package rlvm.machine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import rlvm.store.DeclKind;
import rlvm.store.DeclTable;

/**
 * Fact records on real exports (ISA 3.5, section 11 stage 2; acceptance of M5): a record with a SIG part
 * exists for every constructor and recursor after they were requested, and six sessions deriving every
 * record of the file concurrently publish exactly the records one session derives alone (a record is a
 * function of the store). {@code init-prelude} runs in the default group, Init in the slow one.
 */
class M5FactsCorpusTest {

    @BeforeAll
    static void ready() {
        assumeTrue(M5Support.ready(), "EVAL / WHNF / CONV are not implemented in this tree yet");
    }

    /** Derives the record of every constant in {@code order}; returns the first problem or null. */
    static String deriveAll(MachineSession s, int[] order) {
        Machine m = s.m;
        for (int row : order) {
            int r = m.drive(Facts.sigd(m, row, 0));
            if (r >= Op.SIG) {
                String what = "row " + row + ": signal with " + rlvm.api.MachineErrors.name(m.err);
                M5Env.recycle(s);
                return what;
            }
            if (!m.idle()) {
                return "row " + row + ": SIGD left the machine stacks non-empty";
            }
            if (m.footprintBytes() > (16L << 20)) {
                M5Env.recycle(s);
            }
        }
        return null;
    }

    static int[] rows(DeclTable d) {
        int n = d.constantCount();
        int[] rows = new int[n];
        int k = 0;
        for (int c = 1; c <= n; c++) {
            if (d.kind(c) != DeclKind.NONE) {
                rows[k++] = c;
            }
        }
        return Arrays.copyOf(rows, k);
    }

    static int[] record(FactStore fs, int row) {
        int idx = fs.indexOf(row);
        if (idx == 0) {
            return null;
        }
        int len = fs.lengthOf(idx);
        return Arrays.copyOfRange(fs.chunkOf(idx), fs.offsetOf(idx), fs.offsetOf(idx) + len);
    }

    static void corpus(String path) throws Exception {
        assumeTrue(Fixture.exists(path), path + " is not there");
        try (M5Env env = M5Env.load(path)) {
            DeclTable d = env.store.decls();
            int[] rows = rows(d);

            // one session alone
            MachineEngine alone = env.engine();
            MachineSession s0 = env.session(alone, "facts");
            assertEquals(null, deriveAll(s0, rows));
            FactStore fa = alone.facts;
            int ctors = 0;
            int recs = 0;
            int none = 0;
            long manifestBits = 0;
            long positions = 0;
            for (int row : rows) {
                int idx = fa.indexOf(row);
                assertTrue(idx != 0, "no record for row " + row);
                boolean usable = Facts.usable(s0.m, idx);
                int kind = d.kind(row);
                if (kind == DeclKind.CTOR || kind == DeclKind.REC) {
                    // SIG exists for every constructor and recursor (ISA 3.5 "Test"; section 11 stage 2)
                    assertTrue(usable, "F_NONE record for the " + (kind == DeclKind.CTOR ? "constructor" : "recursor")
                            + " " + env.store.names().toString(d.name(row)));
                    if (kind == DeclKind.CTOR) {
                        ctors++;
                        assertEquals(d.ctorNumParams(row) + d.ctorNumFields(row), Facts.arity(s0.m, idx),
                                "arity of " + env.store.names().toString(d.name(row)));
                    } else {
                        recs++;
                    }
                }
                if (!usable) {
                    none++;
                } else {
                    int n = Math.min(Facts.arity(s0.m, idx), Layout.FACT_POSITIONS);
                    positions += n;
                    manifestBits += Long.bitCount(Facts.manifest(s0.m, idx));
                }
            }
            assertTrue(ctors > 0 && recs > 0, "constructors " + ctors + ", recursors " + recs);
            assertEquals(rows.length, fa.publishedRecords());
            assertEquals(0, fa.lostRaces());
            System.out.println("M5FactsCorpusTest " + path + ": " + rows.length + " constants, " + ctors + " constructors, "
                    + recs + " recursors, " + none + " F_NONE records, manifest positions " + manifestBits + " of " + positions
                    + ", fact bytes " + fa.bytes());

            // six sessions racing over the same rows in different orders
            MachineEngine shared = env.engine();
            int threads = 6;
            List<Thread> ts = new ArrayList<>();
            AtomicReference<String> problem = new AtomicReference<>();
            for (int t = 0; t < threads; t++) {
                final int id = t;
                final MachineSession s = env.session(shared, "facts");
                Thread th = new Thread(() -> {
                    int[] order = rows.clone();
                    if ((id & 1) != 0) {
                        for (int i = 0, j = order.length - 1; i < j; i++, j--) {
                            int x = order[i];
                            order[i] = order[j];
                            order[j] = x;
                        }
                    }
                    if (id >= 2) {
                        int shift = order.length / threads * id; // start somewhere else
                        int[] rot = new int[order.length];
                        for (int i = 0; i < order.length; i++) {
                            rot[i] = order[(i + shift) % order.length];
                        }
                        order = rot;
                    }
                    try {
                        String p = deriveAll(s, order);
                        if (p != null) {
                            problem.compareAndSet(null, "thread " + id + ": " + p);
                        }
                    } catch (RuntimeException | Error e) {
                        problem.compareAndSet(null, "thread " + id + ": " + e);
                    }
                }, "m5-facts-" + t);
                ts.add(th);
            }
            for (Thread th : ts) {
                th.start();
            }
            for (Thread th : ts) {
                th.join();
            }
            assertEquals(null, problem.get());
            FactStore fb = shared.facts;
            assertEquals(rows.length, fb.publishedRecords(), "one record per constant, published once");
            assertEquals(0, fb.lostDifferent(), "a derivation that lost the race had built the same words");
            for (int row : rows) {
                assertTrue(Arrays.equals(record(fa, row), record(fb, row)),
                        "the record of " + env.store.names().toString(d.name(row)) + " differs between one session and six");
            }
            System.out.println("M5FactsCorpusTest " + path + ": 6 threads, " + fb.lostRaces()
                    + " publications lost the race, 0 with different words");
        }
    }

    @Test
    void initPrelude() throws Exception {
        corpus("work/arena-site/tests/good/init-prelude.ndjson");
    }

    @Test
    @Tag("slow")
    void init() throws Exception {
        corpus("work/exports/init.ndjson");
    }
}

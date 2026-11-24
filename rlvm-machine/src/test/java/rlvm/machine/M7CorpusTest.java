package rlvm.machine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.Arrays;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import rlvm.store.DeclKind;
import rlvm.store.DeclTable;

/**
 * The elaborator over whole exports (ISA 3.3, 3.4; acceptance of M7): every role of every constant is
 * elaborated, every block passes pubcheck, theorem and opaque values kept as proof blocks are never
 * elaborated, and two workers that elaborate the same roles concurrently build equal blocks and publish
 * one block per role. Reports sizes and the elaboration time per role.
 */
class M7CorpusTest {

    private static final int[] ROLES = {Op.ROLE_TELE, Op.ROLE_BODY, Op.ROLE_RULE, Op.ROLE_RULES};

    /** Elaborates and publishes every role of every constant with machine {@code m}; returns per-role counts and nanoseconds. */
    private static long[] elaborateAll(M7World w, Machine m, boolean check) {
        long[] stat = new long[4 * Op.ROLE_COUNT]; // [role]: blocks, [C + role]: words, [2C + role]: ns, [3C + role]: generic
        DeclTable decls = w.store.decls();
        int n = decls.constantCount();
        CodeStore cs = w.engine.code;
        for (int role : ROLES) {
            long t0 = System.nanoTime();
            for (int row = 1; row <= n; row++) {
                int b = Elab.elaborate(m, row, role);
                assertTrue(b < Op.SIG, "pubcheck failed in debug mode: " + w.nameOf(row) + " role " + role);
                if (b != 0) {
                    stat[role]++;
                    stat[Op.ROLE_COUNT + role] += cs.lengthOf(b);
                } else {
                    stat[3 * Op.ROLE_COUNT + role]++;
                }
            }
            stat[2 * Op.ROLE_COUNT + role] = System.nanoTime() - t0;
        }
        if (check) {
            for (int id = 1; id <= cs.blockCount(); id++) {
                int bad = Code.pubcheckSlow(m, cs.chunkOf(id), cs.offsetOf(id), cs.lengthOf(id));
                assertEquals(0, bad, () -> "pubcheck of a published block");
            }
            for (int row = 1; row <= n; row++) {
                int k = decls.kind(row);
                boolean blockValue = (k == DeclKind.DEFN || k == DeclKind.THM || k == DeclKind.OPAQUE) && decls.value(row) < 0;
                if (blockValue || k == DeclKind.OPAQUE || k == DeclKind.AXIOM) {
                    assertEquals(0, cs.blockOf(row, Op.ROLE_BODY), "a proof block or opaque value was elaborated: " + w.nameOf(row));
                    assertEquals(0, cs.blockOf(row, Op.ROLE_RULES));
                }
            }
        }
        return stat;
    }

    private static String report(String corpus, M7World w, long[] st) {
        StringBuilder sb = new StringBuilder();
        CodeStore cs = w.engine.code;
        sb.append(corpus).append(": ").append(w.store.decls().constantCount()).append(" constants, ").append(cs.blockCount())
                .append(" blocks, ").append(cs.bytes() >> 10).append(" KiB of code\n");
        String[] names = {"", "TELE", "BODY", "RULE", "FIELDS", "CONG", "RULES"};
        for (int role : ROLES) {
            long blocks = st[role];
            long tried = blocks + st[3 * Op.ROLE_COUNT + role];
            sb.append(String.format("  %-5s  %7d blocks  %7d generic  %9d words  %6.2f us per constant tried  (%d ms)%n", names[role],
                    blocks, st[3 * Op.ROLE_COUNT + role], st[Op.ROLE_COUNT + role],
                    tried == 0 ? 0.0 : st[2 * Op.ROLE_COUNT + role] / 1e3 / tried, st[2 * Op.ROLE_COUNT + role] / 1_000_000));
        }
        return sb.toString();
    }

    private static void corpus(String path, String name) throws Exception {
        assumeTrue(Fixture.exists(path));
        M7World w = M7World.load(path);
        w.m.debug |= Op.DBG_PUBCHECK;
        long[] st = elaborateAll(w, w.m, true);
        String r = report(name, w, st);
        System.out.println(r);
        java.nio.file.Files.writeString(Fixture.root().resolve("work/status/machine-M7-elab-" + name + ".txt"), r);
        assertTrue(st[Op.ROLE_TELE] > 0 && st[Op.ROLE_BODY] > 0 && st[Op.ROLE_RULE] > 0 && st[Op.ROLE_RULES] > 0);
    }

    @Test
    void preludeEveryRolePassesPubcheck() throws Exception {
        corpus("work/arena-site/tests/good/init-prelude.ndjson", "init-prelude");
    }

    @Test
    @Tag("slow")
    void initEveryRolePassesPubcheck() throws Exception {
        corpus("work/exports/init.ndjson", "init");
    }

    @Test
    @Tag("slow")
    void stdEveryRolePassesPubcheck() throws Exception {
        corpus("work/exports/std.ndjson", "std");
    }

    /**
     * Two workers elaborating one role publish equal blocks (ISA 3.3): two sessions build every role of
     * the prelude at the same time, in opposite orders; the words they build are identical (sub-block
     * ids included: a binder has one block), and each role ends up with exactly one published block.
     */
    @Test
    void twoWorkersBuildEqualBlocks() throws Exception {
        String path = "work/arena-site/tests/good/init-prelude.ndjson";
        assumeTrue(Fixture.exists(path));
        M7World w = M7World.load(path);
        int n = w.store.decls().constantCount();
        Machine[] ms = {w.m, w.newMachine()};
        int[][][] built = new int[2][(n + 1) * Op.ROLE_COUNT][];
        int[][] published = new int[2][(n + 1) * Op.ROLE_COUNT];
        Throwable[] failure = new Throwable[1];
        Thread[] ts = new Thread[2];
        for (int t = 0; t < 2; t++) {
            int id = t;
            ts[t] = new Thread(() -> {
                try {
                    Machine m = ms[id];
                    for (int k = 1; k <= n; k++) {
                        int row = id == 0 ? k : n + 1 - k;
                        for (int role : ROLES) {
                            Elab.State s = new Elab.State();
                            Elab.begin(s);
                            int len = Elab.build(m, s, row, role);
                            built[id][row * Op.ROLE_COUNT + role] = Arrays.copyOf(s.buf, len);
                            published[id][row * Op.ROLE_COUNT + role] = Elab.elaborate(m, row, role);
                        }
                    }
                } catch (Throwable e) {
                    failure[0] = e;
                }
            });
            ts[t].start();
        }
        for (Thread t : ts) {
            t.join();
        }
        if (failure[0] != null) {
            throw new AssertionError(failure[0]);
        }
        int roles = 0;
        CodeStore cs = w.engine.code;
        for (int i = Op.ROLE_COUNT; i < (n + 1) * Op.ROLE_COUNT; i++) {
            assertTrue(Arrays.equals(built[0][i], built[1][i]), "row " + i / Op.ROLE_COUNT + " role " + i % Op.ROLE_COUNT);
            assertEquals(published[0][i], published[1][i]);
            if (published[0][i] != 0) {
                roles++;
                int b = published[0][i];
                int[] words = Arrays.copyOfRange(cs.chunkOf(b), cs.offsetOf(b), cs.offsetOf(b) + cs.lengthOf(b));
                assertTrue(Arrays.equals(built[0][i], words), "the published block is the block both built");
            }
        }
        assertTrue(roles > 2000, "roles: " + roles);
    }
}

package rlvm.machine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Tier equivalence at the level of verdicts (ISA section 11; acceptance of M7 "tier equivalence K0 = K1
 * with M8"): the machine with the K1 roles switched on ({@code code,tele,casetrees}, every role
 * elaborated at its first request) against the reference engine, per declaration, through M8's
 * differential runner; then K0 and K1 side by side on the same corpus (outcomes and machine time).
 *
 * <p>The K1 entry points are called from files of M2 (closures in APPLY / INST), M3 (BODY in DELTA, RULE in
 * IOTA, RULES) and M5 (TELE in INFER of an application and in VTYPE of a NEU), each behind its staging
 * switch; all three carry their hooks in the main tree since 2026-11-24T10:41Z. The tests skip themselves
 * in a tree where DELTA does not enter blocks. The corpus runs are tagged slow
 * ({@code -Dgroups=slow -DexcludedGroups=}).
 */
class M7HookedTest {

    static final String K1 = "code,tele,casetrees";

    /** Do DELTA / APPLY / INFER of this build call the K1 entry points? (A behavioural probe: a block is entered.) */
    static boolean hooked() {
        String old = System.getProperty("rlvm.machine.elabAt");
        System.setProperty("rlvm.machine.elabAt", "1");
        try {
            Fixture f = Fixture.prelude();
            f.seal();
            f.switches(K1);
            int n = f.fvar("n", "Nat");
            int v = f.eval(f.term("((@ id 1) Nat n)"));
            long before = f.m.x7.entered;
            int r = f.delta(v);
            return r == n && f.m.x7.entered > before;
        } finally {
            if (old == null) {
                System.clearProperty("rlvm.machine.elabAt");
            } else {
                System.setProperty("rlvm.machine.elabAt", old);
            }
        }
    }

    private static Diff.Report run(String label, String switches, String... roots) {
        return run(label, switches, Op.HOST_DEPTH, roots);
    }

    private static Diff.Report run(String label, String switches, int hostDepth, String... roots) {
        Diff.Options o = new Diff.Options();
        o.config.hostDepth = hostDepth;
        o.config.switches = Debug.parseSwitches(switches);
        o.config.digest = false;
        o.progress = false;
        List<String> paths = Diff.exports(roots);
        assumeTrue(!paths.isEmpty());
        return Diff.run(label, paths, o);
    }

    private static String line(String label, Diff.Report r) {
        long machine = 0;
        long checks = 0;
        int declined = 0;
        for (Diff.FileResult f : r.files) {
            machine += f.machineNanos;
            checks += f.engineChecks;
            declined += f.declined;
        }
        return String.format("%-28s %4d files, %6d engine checks, clean %b, SOUNDNESS %d, COMPLETENESS %d, declined %d, machine %d ms",
                label, r.files.size(), checks, r.clean(), r.soundness(), r.completeness(), declined, machine / 1_000_000);
    }

    /** K1 on, elaboration at the first request, against the reference; then the K0 run of the same corpus for comparison. */
    private static void corpus(String name, String... roots) throws Exception {
        assumeTrue(hooked(), "DELTA does not enter blocks in this tree");
        String old = System.getProperty("rlvm.machine.elabAt");
        StringBuilder sb = new StringBuilder();
        try {
            Diff.Report k0 = run(name + "-k0", "", roots);
            sb.append(line(name + " K0", k0)).append('\n');
            for (String at : new String[] {"1", "10"}) {
                System.setProperty("rlvm.machine.elabAt", at);
                Diff.Report k1 = run(name + "-k1", K1, roots);
                sb.append(line(name + " K1 (ELAB_AT " + at + ")", k1)).append('\n');
                System.out.print(sb);
                assertTrue(k1.sound(), "a wrong verdict with K1 on:\n" + Diff.render(k1));
                // K1 changes no outcome: the same files are clean, the same declarations declined
                assertEquals(k0.files.size(), k1.files.size());
                for (int i = 0; i < k0.files.size(); i++) {
                    Diff.FileResult a = k0.files.get(i);
                    Diff.FileResult b = k1.files.get(i);
                    assertEquals(a.status, b.status, a.path);
                    assertEquals(a.agree, b.agree, a.path);
                    assertEquals(a.declined, b.declined, a.path);
                    assertEquals(a.machineVerdict, b.machineVerdict, a.path);
                }
            }
        } finally {
            if (old == null) {
                System.clearProperty("rlvm.machine.elabAt");
            } else {
                System.setProperty("rlvm.machine.elabAt", old);
            }
        }
        java.nio.file.Files.writeString(Fixture.root().resolve("work/status/machine-M7-k1-" + name + ".txt"), sb.toString());
    }

    @Test
    @Tag("slow")
    void tutorial() throws Exception {
        corpus("tutorial", Diff.corpus("tutorial"));
    }

    /**
     * The worklist machine (D = 0: every sub-judgment of an activation is a suspension, so every ACT frame, the
     * ERF + REQ_APP continuation of TARG's CONV and the DELTA / RULE continuations run) gives the same outcomes.
     */
    @Test
    @Tag("slow")
    void tutorialAndBugsWithEveryFrameMaterialised() throws Exception {
        assumeTrue(hooked(), "DELTA does not enter blocks in this tree");
        String old = System.getProperty("rlvm.machine.elabAt");
        System.setProperty("rlvm.machine.elabAt", "1");
        try {
            for (String name : new String[] {"tutorial", "bugs"}) {
                Diff.Report k1 = run(name + "-k1-d0", K1, 0, Diff.corpus(name));
                System.out.println(line(name + " K1, D = 0", k1));
                assertTrue(k1.clean(), Diff.render(k1));
            }
        } finally {
            if (old == null) {
                System.clearProperty("rlvm.machine.elabAt");
            } else {
                System.setProperty("rlvm.machine.elabAt", old);
            }
        }
    }

    @Test
    @Tag("slow")
    void bugs() throws Exception {
        corpus("bugs", Diff.corpus("bugs"));
    }

    @Test
    @Tag("slow")
    void all230() throws Exception {
        corpus("all", Diff.corpus("all"));
    }

    @Test
    @Tag("slow")
    void init() throws Exception {
        corpus("init", Diff.corpus("init"));
    }

    /** With the hooks, blocks really run: a loaded tutorial file elaborates and enters blocks, and stays diff-clean. */
    @Test
    void blocksRunOnALoadedExport() throws Exception {
        assumeTrue(hooked(), "DELTA does not enter blocks in this tree");
        String path = "work/arena-site/tests/good/init-prelude.ndjson";
        assumeTrue(Fixture.exists(path));
        String oldAt = System.getProperty("rlvm.machine.elabAt");
        String oldSw = System.getProperty("rlvm.machine.switches");
        System.setProperty("rlvm.machine.elabAt", "1");
        System.setProperty("rlvm.machine.switches", K1);
        try {
            Fixture f = Fixture.load(path);
            Fixture.DiffReport r = f.diffAgainstReference();
            String s = "init-prelude with K1: " + r + "\n  blocks " + f.engine.code.blockCount() + " (" + (f.engine.code.bytes() >> 10)
                    + " KiB), activations entered " + f.m.x7.entered + ", elaborated TELE " + f.m.x7.elaborated[Op.ROLE_TELE] + " BODY "
                    + f.m.x7.elaborated[Op.ROLE_BODY] + " RULE " + f.m.x7.elaborated[Op.ROLE_RULE] + " RULES "
                    + f.m.x7.elaborated[Op.ROLE_RULES] + ", stayed generic " + java.util.Arrays.toString(f.m.x7.stayedGeneric);
            System.out.println(s);
            assertEquals(0, r.soundness + r.completeness, s);
            assertTrue(f.m.x7.entered > 1000, s);
            assertTrue(f.engine.code.blockCount() > 100, s);
            f.close();
        } finally {
            if (oldAt == null) {
                System.clearProperty("rlvm.machine.elabAt");
            } else {
                System.setProperty("rlvm.machine.elabAt", oldAt);
            }
            if (oldSw == null) {
                System.clearProperty("rlvm.machine.switches");
            } else {
                System.setProperty("rlvm.machine.switches", oldSw);
            }
        }
    }
}

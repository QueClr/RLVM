package rlvm.machine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import rlvm.api.EngineConfig;

/**
 * The tier hook ({@link Tier}; ISA 8.3, 8.4): an activation that an upper tier takes over must give the
 * results of the stepper. The tiers here are the two generic ones a real compiled tier starts from - the
 * stepper called as a whole ({@code Tier.interpret}) and call threading over {@code Tier.step} - offered
 * every entry and every resume after a suspension (D = 0: every sub-judgment suspends), and a tier that
 * takes every second activation and answers MISS on the others. The oracle is the K0 = K1 comparison of
 * {@link M7Tier}: value ids against the generic tier.
 */
@Tag("fast")
class M7TierHookTest {

    /** Call threading: one {@code Tier.step} per instruction, the block words as a compiled tier holds them. */
    static final class Stepping extends Tier {
        final HashMap<Integer, int[]> blocks = new HashMap<>();
        final int takeEvery;
        long offered;
        long runs;
        long steps;

        Stepping(int takeEvery) {
            this.takeEvery = takeEvery;
        }

        @Override
        public int run(Machine m, int blk, int pc, int rb, int d) {
            if (++offered % takeEvery != 0) {
                return Op.MISS;
            }
            int[] code = blocks.computeIfAbsent(blk, b -> Tier.block(m, b));
            runs++;
            for (;;) {
                steps++;
                pc = Tier.step(m, code, blk, pc, rb, d);
                if (pc == Tier.DONE) {
                    return Tier.result(m);
                }
            }
        }
    }

    private static M7Tier world(int depth) {
        Fixture f = Fixture.prelude();
        f.seal();
        f.m.hostDepth = depth;
        return new M7Tier(f.m);
    }

    private static void exercise(M7Tier t) {
        int n = t.m.decls.constantCount();
        t.bodies(1, n);
        t.rules(1, n);
        t.closures();
        t.caseTrees(1, n);
        t.telescopes(1, n);
        t.assertIdle("after every role");
    }

    @Test
    void steppedBlocksGiveTheValuesOfTheGenericTier() {
        for (int depth : new int[] {Op.HOST_DEPTH, 0}) {
            for (int every : new int[] {1, 2}) {
                M7Tier t = world(depth);
                Stepping tier = new Stepping(every);
                Tier.set(t.m, tier);
                assertSame(tier, Tier.of(t.m));
                Tier.Heat heat = Tier.heat(t.m.engine);
                assertNotNull(heat);
                assertSame(heat, Tier.heat(t.m.engine));
                heat.timing(4);
                long[] fired = new long[1];
                heat.listen((h, m, blk) -> fired[0]++, 8);
                Tier.beginDecl(t.m, t.m.x7); // what the start of a declaration does: the session joins the accounting
                exercise(t);
                String s = t.summary("hook, step, D=" + depth + ", every " + every);
                assertTrue(t.clean(), s + t.failures);
                assertTrue(t.identical > 300, s);
                assertTrue(tier.runs > 150 && tier.steps > tier.runs && tier.offered >= tier.runs * every,
                        "the tier ran: " + tier.offered + " offers, " + tier.runs + " runs, " + tier.steps + " steps");
                long entries = 0;
                long timed = 0;
                for (int b = 1; b <= heat.blockCount(); b++) {
                    entries += heat.entries(b);
                    timed += heat.timed(b);
                    assertTrue(heat.nanos(b) >= heat.selfNanos(b) && heat.selfNanos(b) >= 0, heat.describe(b));
                    assertNotNull(heat.block(b));
                }
                assertEquals(t.m.x7.entered, entries, "every activation is counted once");
                assertTrue(timed > 0 && timed <= tier.offered, "timed " + timed + " of " + tier.offered);
                assertTrue(fired[0] > 0, "the listener fired");
                assertTrue(heat.report(5).contains("entries"), heat.report(5));
            }
        }
    }

    @Test
    void interpretedThroughTheHookAndTakenAway() {
        M7Tier t = world(0);
        long[] runs = new long[1];
        Tier tier = new Tier() {
            @Override
            public int run(Machine m, int blk, int pc, int rb, int d) {
                runs[0]++;
                return Tier.interpret(m, Tier.block(m, blk), blk, pc, rb, d);
            }
        };
        Tier.set(t.m, tier);
        exercise(t);
        assertTrue(t.clean(), t.summary("hook, interpret") + t.failures);
        assertTrue(runs[0] > 300, "runs " + runs[0]);
        long before = runs[0];
        Tier.set(t.m, null);
        long cases = t.cases;
        t.bodies(1, t.m.decls.constantCount());
        assertTrue(t.clean() && t.cases > cases, t.summary("after the tier was taken away") + t.failures);
        assertEquals(before, runs[0], "no tier is called after Tier.set(m, null)");
        assertNull(Tier.block(t.m, Tier.blockCount(t.m) + 1));
        assertTrue(Tier.describe(t.m, 1).startsWith("block 1 "), Tier.describe(t.m, 1));
    }

    @Test
    void noTierAndNoHeatUnlessAskedFor() {
        Fixture f = Fixture.prelude();
        f.seal();
        assertNull(Tier.heat(new MachineEngine(f.store, EngineConfig.defaults().withStrict(true))), "a strict engine counts nothing");
        assertNull(Tier.heat(null));
        Tier.beginDecl(f.m, f.m.x7);
        assertNull(Tier.of(f.m), "no tier without switch t2 (and without a factory on the class path)");
        assertTrue(!f.m.x7.hooked && f.m.x7.heat == null, "a session is not hooked unless its engine counts heat or it has a tier");
        f.m.sw |= Op.SW_T2;
        f.m.x7.tierAsked = false;
        Tier.beginDecl(f.m, f.m.x7);
        assertNull(Tier.of(f.m), "switch t2 without a factory on the class path: no tier");
        assertNull(Tier.factory());
    }
}

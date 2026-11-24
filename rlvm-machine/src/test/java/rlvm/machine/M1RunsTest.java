package rlvm.machine;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import rlvm.api.MachineErrors;

/**
 * M1 acceptance: runs and working environments (ISA 2.4): SPINE, FRAME, PRUNE (HOLE outside the mask, closure
 * identity independent of the path to the environment), far frames with their REST run, PUSH in place, ENTER,
 * GET, the OPEN bit of a run, and growth on a tiny machine. No store is needed: values are free variables,
 * sorts and immediates.
 */
@Tag("fast")
class M1RunsTest {

    static Machine tiny() {
        return new Machine(Machine.Sizes.tiny());
    }

    /** Loads the values as an environment at the top of es; returns its base. */
    static int env(Machine m, int... vs) {
        if (!m.ensureEnv(vs.length)) {
            throw new IllegalStateException("env");
        }
        int base = m.ep;
        System.arraycopy(vs, 0, m.es, base, vs.length);
        m.ep = base + vs.length;
        return base;
    }

    static int scratch(Machine m, int... vs) {
        if (!m.ensureScratch(vs.length)) {
            throw new IllegalStateException("scratch");
        }
        System.arraycopy(vs, 0, m.X, m.xp, vs.length);
        return m.xp;
    }

    static int[] slots(Machine m, int run) {
        int n = Runs.len(m, run);
        int[] r = new int[n];
        for (int i = 0; i < n; i++) {
            r[i] = Runs.arg(m, run, i);
        }
        return r;
    }

    static int[] imms(int from, int n) {
        int[] r = new int[n];
        for (int i = 0; i < n; i++) {
            r[i] = Layout.imm(from + i);
        }
        return r;
    }

    @Test
    void framesAreInternedByContentAndSpinesAreNot() {
        Machine m = tiny();
        int a = Layout.imm(1);
        int b = Layout.imm(2);
        int f1 = Runs.frame(m, scratch(m, a, b), 2, 0);
        assertEquals(f1, Runs.frame(m, scratch(m, a, b), 2, 0));
        assertNotEquals(f1, Runs.frame(m, scratch(m, b, a), 2, 0));
        assertNotEquals(f1, Runs.frame(m, scratch(m, a), 1, 0));
        assertNotEquals(f1, Runs.frame(m, scratch(m, a, b, a), 3, 0));
        assertArrayEquals(new int[] {a, b}, slots(m, f1));
        assertEquals(Layout.RUN_FRAME, Layout.runKind(m.rs[f1]));
        assertEquals(Layout.RUN_EMPTY, Runs.frame(m, m.xp, 0, 0), "the empty frame is run 0");
        assertEquals(0, Runs.len(m, Layout.RUN_EMPTY));
        // FGET: de Bruijn index 0 is the innermost = the last slot
        assertEquals(b, Runs.fget(m, f1, 0));
        assertEquals(a, Runs.fget(m, f1, 1));
        // the header of a FRAME carries the number of slots that are not HOLE (NOTES-M1 D16); the length, kind
        // and far bit read as before
        assertEquals(2, (m.rs[f1] & Runs.CNT_MASK) >>> Runs.CNT_SHIFT);
        assertEquals(2, Layout.runLen(m.rs[f1]));
        assertEquals(2, Layout.runHeaderWords(m.rs[f1]));
        // a REST run with the same slots is another run (the kind is part of the content hash)
        int rest = Runs.restRun(m, scratch(m, a, b), 2);
        assertNotEquals(f1, rest);
        assertEquals(rest, Runs.restRun(m, scratch(m, a, b), 2));
        assertEquals(Layout.RUN_REST, Layout.runKind(m.rs[rest]));
        // spines: a new run every time, not in rtab
        int used = m.rtabUsed;
        int s1 = Runs.spine(m, scratch(m, a, b), 2);
        int s2 = Runs.spine(m, scratch(m, a, b), 2);
        assertNotEquals(s1, s2);
        assertArrayEquals(new int[] {a, b}, slots(m, s1));
        assertEquals(Layout.RUN_SPINE, Layout.runKind(m.rs[s1]));
        assertEquals(used, m.rtabUsed);
        assertEquals(Layout.RUN_EMPTY, Runs.spine(m, m.xp, 0));
    }

    @Test
    void pruneKeepsTheMaskedSlotsAndPutsHoleElsewhere() {
        Machine m = tiny();
        int[] vs = imms(10, 6); // outermost first: index 5 .. index 0
        int eb = env(m, vs);
        assertEquals(Layout.RUN_EMPTY, Runs.prune(m, eb, 6, 0L), "a closed term gets the empty frame");
        int f0 = Runs.prune(m, eb, 6, 0b1L); // reads index 0 only
        assertArrayEquals(new int[] {vs[5]}, slots(m, f0));
        int f = Runs.prune(m, eb, 6, 0b101010L); // reads indices 1, 3, 5: length 6
        assertArrayEquals(new int[] {vs[0], Layout.HOLE, vs[2], Layout.HOLE, vs[4], Layout.HOLE}, slots(m, f));
        assertEquals(vs[4], Runs.fget(m, f, 1));
        assertEquals(vs[2], Runs.fget(m, f, 3));
        assertEquals(vs[0], Runs.fget(m, f, 5));
        assertEquals(Layout.HOLE, Runs.fget(m, f, 0));
        int g = Runs.prune(m, eb, 6, 0b000110L); // highest set bit 2: length 3
        assertArrayEquals(new int[] {vs[3], vs[4], Layout.HOLE}, slots(m, g));
        // PRUNE and FRAME agree: the same content is the same run
        assertEquals(g, Runs.frame(m, scratch(m, vs[3], vs[4], Layout.HOLE), 3, 0));
        assertEquals(f, Runs.prune(m, eb, 6, 0b101010L));
        // the full mask is the environment itself
        assertArrayEquals(vs, slots(m, Runs.prune(m, eb, 6, 0b111111L)));
        // a mask that names an index outside the environment is a fault, never a read of a neighbour
        assertEquals(Op.FATAL, Runs.prune(m, eb, 6, 0b1000000L));
        assertEquals(MachineErrors.E_INTERNAL, m.err);
        m.err = 0;
        assertEquals(Op.FATAL, Runs.prune(m, eb, 0, 0b1L));
        assertEquals(MachineErrors.E_INTERNAL, m.err);
    }

    /** ISA 2.4, 2.5: a closure gets the same id however its environment was reached. */
    @Test
    void closureIdentityIsIndependentOfThePathToTheEnvironment() {
        Machine m = tiny();
        int ty = Values.mkSort(m, 5);
        int x = Values.mkFVar(m, ty, 0);
        int y = Values.mkFVar(m, ty, 1);
        int z = Values.mkFVar(m, ty, 2);
        long mask = 0b101L; // the term reads indices 0 and 2
        // environment 1: [x, y, z] (index 2 = x, index 0 = z)
        int e1 = env(m, x, y, z);
        // environment 2: longer, different unread bindings: [7, 8, x, 99, z]
        int e2 = env(m, Layout.imm(7), Layout.imm(8), x, Layout.imm(99), z);
        // environment 3: reached by PUSH from a copy
        int n3 = Runs.push(m, e1, 1, Layout.imm(55)); // copies [x], appends 55
        int b3 = m.envBase;
        n3 = Runs.push(m, b3, n3, z);
        assertEquals(3, n3);
        int f1 = Runs.prune(m, e1, 3, mask);
        int f2 = Runs.prune(m, e2, 5, mask);
        int f3 = Runs.prune(m, m.envBase, n3, mask);
        assertEquals(f1, f2);
        assertEquals(f1, f3);
        assertArrayEquals(new int[] {x, Layout.HOLE, z}, slots(m, f1));
        int term = 1234; // any term id: ls = 0 needs no store
        int lam = Values.mkLam(m, term, f1, 0);
        assertEquals(lam, Values.mkLam(m, term, f2, 0));
        assertEquals(lam, Values.mkLam(m, term, f3, 0));
        assertEquals(Values.suspRecord(m, term, f1, 0), Values.suspRecord(m, term, f3, 0));
        // a different binding at a read position is a different closure
        int e4 = env(m, y, y, z);
        assertNotEquals(lam, Values.mkLam(m, term, Runs.prune(m, e4, 3, mask), 0));
        // OPEN: the frame holds free variables, so the closure is OPEN; a frame of literals is not
        assertTrue(Runs.isOpen(m, f1));
        assertTrue(Values.isOpen(m, lam));
        int e5 = env(m, Layout.imm(1), x, Layout.imm(3));
        int closedFrame = Runs.prune(m, e5, 3, mask); // x sits at an unread position
        assertFalse(Runs.isOpen(m, closedFrame), "an open value outside the mask does not make the frame open");
        assertFalse(Values.isOpen(m, Values.mkLam(m, term, closedFrame, 0)));
        assertEquals(Layout.FL_WHNF, Values.flags(m, Values.mkPi(m, term, closedFrame, 0)));
        assertEquals(Layout.FL_WHNF | Layout.FL_OPEN, Values.flags(m, Values.mkPi(m, term, f1, 0)));
        assertEquals(Layout.FL_OPEN, Values.flags(m, Values.suspRecord(m, term, f1, 0)));
    }

    @Test
    void pushExtendsInPlaceAtTheTopAndCopiesOtherwise() {
        Machine m = tiny();
        int eb = env(m, Layout.imm(1), Layout.imm(2));
        assertEquals(2, m.ep);
        int n = Runs.push(m, eb, 2, Layout.imm(3)); // the environment ends at the top: one slot is written
        assertEquals(3, n);
        assertEquals(eb, m.envBase);
        assertEquals(3, m.ep);
        // a second extension of the same parent (eb, 2) finds the top moved and copies
        int n2 = Runs.push(m, eb, 2, Layout.imm(4));
        assertEquals(3, n2);
        int b2 = m.envBase;
        assertEquals(3, b2);
        assertEquals(6, m.ep);
        assertEquals(Layout.imm(3), Runs.get(m, eb, 3, 0), "the first child is untouched");
        assertEquals(Layout.imm(4), Runs.get(m, b2, 3, 0));
        assertEquals(Layout.imm(2), Runs.get(m, b2, 3, 1));
        assertEquals(Layout.imm(1), Runs.get(m, b2, 3, 2));
        // the owner truncates ep when it completes; the next push on the parent is in place again
        m.ep = 2;
        assertEquals(3, Runs.push(m, eb, 2, Layout.imm(9)));
        assertEquals(eb, m.envBase);
        assertEquals(Layout.imm(9), Runs.get(m, eb, 3, 0));
        // pushing on the empty environment at the top
        m.ep = 0;
        assertEquals(1, Runs.push(m, 0, 0, Layout.imm(7)));
        assertEquals(0, m.envBase);
        // growth: a thousand pushes on a 4-slot slab
        int en = 1;
        for (int i = 0; i < 1000; i++) {
            en = Runs.push(m, m.envBase, en, Layout.imm(i));
        }
        assertEquals(1001, en);
        assertEquals(Layout.imm(999), Runs.get(m, m.envBase, en, 0));
        assertEquals(Layout.imm(7), Runs.get(m, m.envBase, en, 1000));
        // GET is bounds-checked and HOLE is unreadable
        assertEquals(Op.FATAL, Runs.get(m, m.envBase, en, 1001));
        assertEquals(MachineErrors.E_INTERNAL, m.err);
        assertEquals(Op.FATAL, Runs.get(m, m.envBase, en, -1));
        int hb = env(m, Layout.HOLE);
        assertEquals(Op.FATAL, Runs.get(m, hb, 1, 0));
        assertEquals(MachineErrors.E_HOLE, m.err);
    }

    @Test
    void enterCopiesTheFrameAndPruneOfTheResultIsTheFrameAgain() {
        Machine m = tiny();
        int[] vs = imms(20, 5);
        int eb = env(m, vs);
        int f = Runs.prune(m, eb, 5, 0b10110L);
        int ep = m.ep;
        int n = Runs.enter(m, f);
        assertEquals(5, n);
        assertEquals(ep, m.envBase);
        assertEquals(ep + 5, m.ep);
        assertEquals(vs[3], Runs.get(m, m.envBase, n, 1));
        assertEquals(vs[2], Runs.get(m, m.envBase, n, 2));
        assertEquals(vs[0], Runs.get(m, m.envBase, n, 4));
        assertEquals(Op.FATAL, Runs.get(m, m.envBase, n, 0), "an unread binder holds HOLE");
        assertEquals(MachineErrors.E_HOLE, m.err);
        m.err = 0;
        assertEquals(f, Runs.prune(m, m.envBase, n, 0b10110L), "round trip");
        // the empty frame
        int ep2 = m.ep;
        assertEquals(0, Runs.enter(m, Layout.RUN_EMPTY));
        assertEquals(ep2, m.envBase);
        assertEquals(ep2, m.ep);
        // entering and pushing: the body's environment
        n = Runs.enter(m, f);
        int b = m.envBase;
        n = Runs.push(m, b, n, Layout.imm(77));
        assertEquals(b, m.envBase, "the entered environment is at the top: PUSH is in place");
        assertEquals(Layout.imm(77), Runs.get(m, b, n, 0));
        assertEquals(vs[3], Runs.get(m, b, n, 2));
        assertEquals(5, Runs.envLen(m, f));
    }

    /** ISA 2.4, 2.5, RECONCILIATION section 2 (C3.12): the REST run is part of a far frame's identity. */
    @Test
    void farFrameIdentityIncludesTheRestRun() {
        Machine m = tiny();
        int total = 70;
        int[] vs = imms(100, total); // vs[0] outermost .. vs[69] innermost (index 0)
        int eb = env(m, vs);
        long mask = Layout.MASK_FAR | 0b1001L; // reads indices 0, 3 and something at or beyond 63
        int f = Runs.prune(m, eb, total, mask);
        assertTrue(f < Op.SIG);
        assertEquals(Layout.NEAR_SLOTS, Runs.len(m, f), "63 near slots");
        int rest = Runs.rest(m, f);
        assertNotEquals(0, rest);
        assertEquals(total - Layout.NEAR_SLOTS, Runs.len(m, rest), "7 outer bindings: one chunk");
        assertEquals(total - Layout.NEAR_SLOTS, Runs.restLen(m, rest));
        assertEquals(Layout.RUN_REST, Layout.runKind(m.rs[rest]));
        assertEquals(vs[0], Runs.arg(m, rest, 0));
        assertEquals(vs[6], Runs.arg(m, rest, 6));
        assertEquals(vs[69], Runs.fget(m, f, 0));
        assertEquals(vs[66], Runs.fget(m, f, 3));
        assertEquals(Layout.HOLE, Runs.fget(m, f, 1));
        assertEquals(Layout.HOLE, Runs.fget(m, f, 62));
        assertEquals(f, Runs.prune(m, eb, total, mask), "two constructions, one id");
        assertEquals(total, Runs.envLen(m, f));
        // the same near slots over another outer environment: another frame, because the REST run differs
        int[] ws = vs.clone();
        ws[2] = Layout.imm(9999);
        int eb2 = env(m, ws);
        int f2 = Runs.prune(m, eb2, total, mask);
        assertNotEquals(f, f2, "far frames with different REST runs are different frames");
        assertNotEquals(rest, Runs.rest(m, f2));
        assertArrayEquals(slots(m, f), slots(m, f2));
        // the same REST and the same near slots reached through another environment: the same frame
        int[] us = vs.clone();
        us[68] = Layout.imm(4242); // index 1: not read
        int eb3 = env(m, us);
        assertEquals(f, Runs.prune(m, eb3, total, mask));
        // the near frame with the same slots (no far bit) is yet another run
        int near = Runs.prune(m, eb, total, 0b1001L);
        assertNotEquals(f, near);
        assertEquals(4, Runs.len(m, near));
        assertEquals(0, Runs.rest(m, near));
        // FRAME with an explicit REST run is the same construction
        int[] nearSlots = slots(m, f);
        assertEquals(f, Runs.frame(m, scratch(m, nearSlots), nearSlots.length, rest));
        // closures over the two far frames differ
        assertNotEquals(Values.mkLam(m, 77, f, 0), Values.mkLam(m, 77, f2, 0));
        assertEquals(Values.mkLam(m, 77, f, 0), Values.mkLam(m, 77, Runs.prune(m, eb3, total, mask), 0));
        // ENTER of a far frame: the REST slots, then the near slots
        int n = Runs.enter(m, f);
        assertEquals(total, n);
        int b = m.envBase;
        assertEquals(vs[69], Runs.get(m, b, n, 0));
        assertEquals(vs[66], Runs.get(m, b, n, 3));
        assertEquals(vs[6], Runs.get(m, b, n, 63), "index 63 is the innermost slot of the REST run");
        assertEquals(vs[0], Runs.get(m, b, n, 69));
        assertEquals(f, Runs.prune(m, b, n, mask), "round trip of a far frame");
        // bit 63 on an environment of at most 63 slots: no index >= 63 can be read; the frame is the near one
        int small = env(m, imms(500, 10));
        assertEquals(Runs.prune(m, small, 10, 0b1001L), Runs.prune(m, small, 10, mask));
        assertEquals(Layout.RUN_EMPTY, Runs.prune(m, small, 10, Layout.MASK_FAR));
        // OPEN of a far frame comes from the REST run too
        int ty = Values.mkSort(m, 3);
        int x = Values.mkFVar(m, ty, 0);
        int[] os = vs.clone();
        os[1] = x;
        int eb4 = env(m, os);
        int fo = Runs.prune(m, eb4, total, mask);
        assertTrue(Runs.isOpen(m, fo));
        assertFalse(Runs.isOpen(m, f));
        assertTrue(Values.isOpen(m, Values.mkLam(m, 77, fo, 0)));
    }

    /**
     * A REST run is a chain of chunks (NOTES-M1 D14): equal outer environments give equal chains, a difference
     * in ANY chunk gives another frame, ENTER rebuilds the environment, and environments that grow by one
     * binding per level share their complete chunks: the run words of a deep binder nest grow linearly.
     */
    @Test
    void restChainsOfDeepEnvironments() {
        Machine m = tiny();
        int total = 63 + 3 * Runs.REST_CHUNK + 17; // three complete chunks and a tail of 17
        int[] vs = imms(1000, total);
        long mask = Layout.MASK_FAR | 0b11L;
        int eb = env(m, vs);
        int f = Runs.prune(m, eb, total, mask);
        int rest = Runs.rest(m, f);
        assertEquals(17, Runs.len(m, rest), "the id of a chain is its last chunk");
        assertEquals(total - 63, Runs.restLen(m, rest));
        assertEquals(total, Runs.envLen(m, f));
        int c2 = Runs.rest(m, rest);
        int c1 = Runs.rest(m, c2);
        int c0 = Runs.rest(m, c1);
        assertEquals(Runs.REST_CHUNK, Runs.len(m, c2));
        assertEquals(Runs.REST_CHUNK, Runs.len(m, c0));
        assertEquals(0, Runs.rest(m, c0), "the first chunk has no predecessor");
        assertEquals(vs[0], Runs.arg(m, c0, 0));
        assertEquals(vs[Runs.REST_CHUNK], Runs.arg(m, c1, 0));
        assertEquals(vs[3 * Runs.REST_CHUNK + 16], Runs.arg(m, rest, 16));
        // two constructions, one id; through another copy of the environment too
        int eb2 = env(m, vs.clone());
        assertEquals(f, Runs.prune(m, eb2, total, mask));
        // a difference in the first chunk, in a middle chunk, in the tail: three other frames
        for (int at : new int[] {5, Runs.REST_CHUNK + 9, 3 * Runs.REST_CHUNK + 2}) {
            int[] ws = vs.clone();
            ws[at] = Layout.imm(7);
            int g = Runs.prune(m, env(m, ws), total, mask);
            assertNotEquals(f, g, "slot " + at + " of the outer environment is part of the identity");
            assertEquals(slots(m, f).length, slots(m, g).length);
        }
        // ENTER rebuilds all bindings in order
        int n = Runs.enter(m, f);
        assertEquals(total, n);
        int b = m.envBase;
        for (int i = 2; i < total; i++) {
            int expect = i < 63 ? Layout.HOLE : vs[total - 1 - i];
            if (expect == Layout.HOLE) {
                assertEquals(Layout.HOLE, m.es[b + n - 1 - i]);
            } else {
                assertEquals(expect, Runs.get(m, b, n, i), "index " + i);
            }
        }
        assertEquals(vs[total - 1], Runs.get(m, b, n, 0));
        assertEquals(f, Runs.prune(m, b, n, mask), "round trip through ENTER");
        // FRAME + restRun from scratch is the same construction
        int rest2 = Runs.restRun(m, scratch(m, java.util.Arrays.copyOf(vs, total - 63)), total - 63);
        assertEquals(rest, rest2);
        // a ladder: environments of depth 64 .. 3000 that extend each other; one far frame per depth
        Machine t = tiny();
        int depth = 3000;
        int[] ladder = imms(5000, depth);
        int base = env(t, ladder);
        int before = t.rsp;
        int[] frames = new int[depth + 1];
        for (int d = 64; d <= depth; d++) {
            frames[d] = Runs.prune(t, base, d, mask);
            assertTrue(frames[d] < Op.SIG);
        }
        int words = t.rsp - before;
        // per depth: one far frame (63 + 3 words) and one tail chunk (at most 64 + 3 words); quadratic storage
        // would be about depth^2 / 2 = 4.5 M words
        assertTrue(words < (depth - 63) * (66 + 67), "REST storage must be linear in the depth: " + words + " run words");
        for (int d = 64; d <= depth; d += 97) {
            assertEquals(frames[d], Runs.prune(t, base, d, mask));
            assertEquals(d, Runs.envLen(t, frames[d]));
        }
        assertNotEquals(frames[1000], frames[1001]);
    }

    /** Random environments and masks against a naive model, on a machine whose tables grow many times. */
    @Test
    void randomPrunesAgreeWithANaiveModel() {
        Machine m = tiny();
        Random rnd = new Random(7);
        java.util.HashMap<String, Integer> model = new java.util.HashMap<>();
        for (int round = 0; round < 3000; round++) {
            m.ep = 0;
            int en = rnd.nextInt(12);
            int[] vs = new int[en];
            for (int i = 0; i < en; i++) {
                vs[i] = Layout.imm(rnd.nextInt(4));
            }
            int eb = env(m, vs);
            long mask = en == 0 ? 0 : rnd.nextLong() & ((1L << en) - 1);
            int f = Runs.prune(m, eb, en, mask);
            assertTrue(f < Op.SIG);
            int len = 64 - Long.numberOfLeadingZeros(mask);
            StringBuilder key = new StringBuilder();
            int[] expect = new int[len];
            for (int j = 0; j < len; j++) {
                int i = len - 1 - j;
                expect[j] = (mask >>> i & 1) != 0 ? vs[en - 1 - i] : Layout.HOLE;
                key.append(expect[j]).append(',');
            }
            assertArrayEquals(expect, slots(m, f));
            Integer prev = model.put(key.toString(), f);
            assertTrue(prev == null || prev == f, "equal contents, one run");
        }
        java.util.HashSet<Integer> ids = new java.util.HashSet<>(model.values());
        assertEquals(model.size(), ids.size(), "different contents, different runs");
        assertEquals(model.size() - 1, m.rtabUsed, "one rtab slot per frame (the empty frame has none)");
    }

    /**
     * I-ID of frames under the kept-slot probe (NOTES-M1 D16): PRUNE, FRAME over the dense slots and PRUNE of the
     * entered frame give one id for one content and different ids for different contents - with HOLE at kept
     * positions (the placeholder of an unread binder that the mask over-approximates), with 15 and more kept
     * bindings (the saturated count: slot-by-slot comparison), with values that differ only in position, and
     * with far frames. Checked against a model keyed by the dense content.
     */
    @Test
    void keptSlotProbeKeepsIdentityExactly() {
        Machine m = tiny();
        Random rnd = new Random(20261124);
        java.util.HashMap<String, Integer> model = new java.util.HashMap<>();
        int ty = Values.mkSort(m, 2);
        int[] pool = new int[9];
        for (int i = 0; i < 6; i++) {
            pool[i] = i < 3 ? Layout.imm(i) : Values.mkFVar(m, ty, i);
        }
        pool[6] = Layout.HOLE; // an environment slot may hold the placeholder
        pool[7] = Layout.HOLE;
        pool[8] = Layout.imm(Integer.MAX_VALUE);
        int rounds = 0;
        for (int round = 0; round < 6000; round++) {
            m.ep = 0;
            int en = round % 5 == 0 ? 64 + rnd.nextInt(80) : rnd.nextInt(64);
            int[] vs = new int[en];
            for (int i = 0; i < en; i++) {
                vs[i] = pool[rnd.nextInt(round % 3 == 0 ? 3 : pool.length)];
            }
            int eb = env(m, vs);
            long mask = rnd.nextLong();
            switch (round % 4) {
                case 0: mask &= rnd.nextLong() & rnd.nextLong(); break; // sparse
                case 1: mask |= rnd.nextLong(); break;                  // dense: saturated counts
                default: break;
            }
            if (en <= 63) {
                mask &= en == 0 ? 0 : (1L << en) - 1;
            } else if (round % 2 == 0) {
                mask &= ~Layout.MASK_FAR;
            }
            int f = Runs.prune(m, eb, en, mask);
            assertTrue(f < Op.SIG);
            // the model: dense near slots + the outer bindings of a far frame
            long near = mask & ~Layout.MASK_FAR;
            boolean far = mask < 0 && en > 63;
            int len = far ? 63 : 64 - Long.numberOfLeadingZeros(near);
            int[] dense = new int[len];
            StringBuilder key = new StringBuilder(far ? "far" : "near");
            for (int j = 0; j < len; j++) {
                int i = len - 1 - j;
                dense[j] = (near >>> i & 1) != 0 ? vs[en - 1 - i] : Layout.HOLE;
                key.append(',').append(dense[j]);
            }
            if (far) {
                key.append('|');
                for (int j = 0; j < en - 63; j++) {
                    key.append(',').append(vs[j]);
                }
            }
            if (len == 0) {
                assertEquals(Layout.RUN_EMPTY, f);
                continue;
            }
            rounds++;
            assertArrayEquals(dense, slots(m, f));
            int nonHole = 0;
            for (int v : dense) {
                nonHole += v != Layout.HOLE ? 1 : 0;
            }
            assertEquals(Math.min(nonHole, Runs.CNT_MAX), (m.rs[f] & Runs.CNT_MASK) >>> Runs.CNT_SHIFT, "count of the header");
            Integer prev = model.put(key.toString(), f);
            assertTrue(prev == null || prev == f, "equal contents, one run: " + key);
            // FRAME over the dense slots is the same construction
            int rest = far ? Runs.rest(m, f) : 0;
            assertEquals(f, Runs.frame(m, scratch(m, dense), len, rest), "FRAME of the dense slots");
            // PRUNE again (the REST cache answers a far one), and PRUNE of the entered frame
            assertEquals(f, Runs.prune(m, eb, en, mask));
            int n = Runs.enter(m, f);
            assertEquals(f, Runs.prune(m, m.envBase, n, mask), "round trip through ENTER");
            // a neighbour: one kept binding replaced, or one kept position less: never the same run
            if (near != 0) {
                int i = Long.numberOfTrailingZeros(near);
                int[] ws = vs.clone();
                ws[en - 1 - i] = vs[en - 1 - i] == Layout.imm(77) ? Layout.imm(78) : Layout.imm(77);
                m.ep = 0;
                int eb2 = env(m, ws);
                assertNotEquals(f, Runs.prune(m, eb2, en, mask), "a kept binding differs");
                if (vs[en - 1 - i] != Layout.HOLE) {
                    assertNotEquals(f, Runs.prune(m, eb2, en, mask & ~(1L << i)), "a kept position less");
                }
            }
        }
        assertTrue(rounds > 5000);
        java.util.HashSet<Integer> ids = new java.util.HashSet<>(model.values());
        assertEquals(model.size(), ids.size(), "different contents, different runs");
    }

    /**
     * The REST cache of far PRUNE is a hint that is verified by content: an environment slab that was cut and
     * rewritten at the same base and length never gets the chain of the bindings that were there before.
     */
    @Test
    void restCacheIsVerifiedByContent() {
        Machine m = tiny();
        long mask = Layout.MASK_FAR | 0b1L;
        int total = 63 + 2 * Runs.REST_CHUNK + 5;
        int[] vs = imms(3000, total);
        int eb = env(m, vs);
        int f = Runs.prune(m, eb, total, mask);
        assertEquals(f, Runs.prune(m, eb, total, mask), "answered through the cache");
        for (int at : new int[] {0, 63, Runs.REST_CHUNK, 2 * Runs.REST_CHUNK + 4}) {
            // the same base and length, one outer binding rewritten in place: the cell is stale
            int old = m.es[eb + at];
            m.es[eb + at] = Layout.imm(9);
            int g = Runs.prune(m, eb, total, mask);
            assertNotEquals(f, g, "outer slot " + at + " was rewritten");
            assertEquals(Layout.imm(9), Runs.arg(m, chunkOf(m, Runs.rest(m, g), at), at % Runs.REST_CHUNK));
            m.es[eb + at] = old;
            assertEquals(f, Runs.prune(m, eb, total, mask), "and back");
        }
        // a longer and a shorter environment at the same base
        assertNotEquals(f, Runs.prune(m, eb, total - 1, mask));
        assertEquals(f, Runs.prune(m, eb, total, mask));
        // after a reset the cells of the old session are not used (another epoch), and the ids are fresh
        m.reset();
        eb = env(m, vs);
        int f2 = Runs.prune(m, eb, total, mask);
        assertTrue(f2 < Op.SIG);
        assertEquals(total, Runs.envLen(m, f2));
        assertEquals(vs[0], Runs.arg(m, chunkOf(m, Runs.rest(m, f2), 0), 0));
    }

    /** The chunk of the chain ending in {@code rest} that holds outer binding {@code at}. */
    static int chunkOf(Machine m, int rest, int at) {
        int n = Runs.restLen(m, rest);
        int c = rest;
        int hi = n;
        for (;;) {
            int lo = hi - Runs.len(m, c);
            if (at >= lo) {
                return c;
            }
            hi = lo;
            c = Runs.rest(m, c);
        }
    }

    /**
     * The probe sequences stay short: the content hash of frames (a sum over the kept slots) and the hash of
     * applications (a sum over the arguments) spread realistic keys - consecutive record ids at shifting
     * positions - over the tables.
     */
    @Test
    void hashesSpreadConsecutiveIdsAndShiftedPositions() {
        Machine m = new Machine(Machine.Sizes.standard());
        int ty = Values.mkSort(m, 2);
        int[] xs = new int[40];
        for (int i = 0; i < xs.length; i++) {
            xs[i] = Values.mkFVar(m, ty, i);
        }
        java.util.HashSet<Integer> frames = new java.util.HashSet<>();
        int eb = env(m, xs);
        for (int hi = 0; hi < 40; hi++) {
            for (int lo = 0; lo <= hi; lo++) {
                for (int third = 0; third <= lo; third += 3) {
                    frames.add(Runs.prune(m, eb, 40, 1L << hi | 1L << lo | 1L << third));
                }
            }
        }
        // displacement of every frame from its home slot in rtab
        long steps = 0;
        int[] tab = m.rtab;
        for (int slot = 0; slot < tab.length; slot++) {
            int e = tab[slot];
            if (e != 0) {
                int home = m.rs[(e & Layout.TAB_INDEX_MASK) + Layout.RUN_HASH] & (tab.length - 1);
                steps += (slot - home) & (tab.length - 1);
            }
        }
        assertTrue(steps < frames.size(), "frames: " + steps + " probe steps for " + frames.size() + " runs");
        // applications of one head to (x_i, x_j) and (x_j, x_i), and to shifted windows of the variables
        java.util.HashSet<Integer> neus = new java.util.HashSet<>();
        for (int i = 0; i < 40; i++) {
            for (int j = 0; j < 40; j++) {
                neus.add(Values.mkNeu(m, xs[0], scratch(m, xs[i], xs[j]), 2));
                neus.add(Values.mkNeu(m, xs[1], scratch(m, xs[i], xs[j], xs[(i + j) % 40]), 3));
            }
        }
        assertEquals(3200, neus.size());
        steps = 0;
        tab = m.vtab;
        int n = 0;
        for (int slot = 0; slot < tab.length; slot++) {
            int e = tab[slot];
            if (e != 0) {
                int home = Hash.ofRecord(m.vh, e & Layout.TAB_INDEX_MASK) & (tab.length - 1);
                steps += (slot - home) & (tab.length - 1);
                n++;
            }
        }
        assertTrue(steps < n, "records: " + steps + " probe steps for " + n + " records");
    }

    @Test
    void neuSpinesSurviveGrowthOfTheRunSlab() {
        Machine m = tiny();
        int ty = Values.mkSort(m, 5);
        int x = Values.mkFVar(m, ty, 0);
        int[] refs = new int[500];
        for (int i = 0; i < refs.length; i++) {
            int xb = scratch(m, Layout.imm(i), x, Layout.imm(i * 3));
            refs[i] = Values.mkNeu(m, x, xb, 3);
        }
        for (int i = 0; i < refs.length; i++) {
            int xb = scratch(m, Layout.imm(i), x, Layout.imm(i * 3));
            assertEquals(refs[i], Values.mkNeu(m, x, xb, 3));
            int run = Values.b(m, refs[i]);
            assertArrayEquals(new int[] {Layout.imm(i), x, Layout.imm(i * 3)}, slots(m, run));
            assertTrue(Runs.isOpen(m, run));
            // extension by MKAPP: prefix from the old spine, new arguments from scratch
            int ext = Values.mkApp(m, refs[i], scratch(m, Layout.imm(1)), 1);
            assertArrayEquals(new int[] {Layout.imm(i), x, Layout.imm(i * 3), Layout.imm(1)}, slots(m, Values.b(m, ext)));
            assertEquals(ext, Values.mkNeu(m, x, scratch(m, Layout.imm(i), x, Layout.imm(i * 3), Layout.imm(1)), 4));
        }
    }
}

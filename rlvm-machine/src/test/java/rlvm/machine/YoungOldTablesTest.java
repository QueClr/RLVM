package rlvm.machine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The young and old tables of a session ({@code Machine}, "Young and old tables"; *agentic-note*: rep-OPT-D):
 * a probe that skips the old table for a key that holds a young record or run must still find every value,
 * frame and pair that exists. Random values, frames, REST chains and pairs made of the values that exist are
 * checked against a model (a map from the key to the id), on a machine whose young tables hold 2 to 8 entries,
 * so that every few operations move a young table into the old one; {@link Debug#tablesAudit} looks at the
 * tables themselves.
 */
@Tag("fast")
class YoungOldTablesTest {

    /** A session of random operations; the model knows the id of every key that was made. */
    private static final class Model {
        final Machine t;
        final Random rnd;
        final Map<String, Integer> ids = new HashMap<>();
        final Map<Long, Integer> pairs = new HashMap<>();
        final List<Integer> values = new ArrayList<>();
        /** head and arguments of the applications made, by record. */
        final Map<Integer, int[]> spines = new HashMap<>();
        final List<Integer> heads = new ArrayList<>();
        final List<Integer> frames = new ArrayList<>();
        final List<Integer> rests = new ArrayList<>();
        int made;
        int found;

        Model(Machine t, long seed) {
            this.t = t;
            this.rnd = new Random(seed);
        }

        void forget() {
            ids.clear();
            pairs.clear();
            values.clear();
            spines.clear();
            heads.clear();
            frames.clear();
            rests.clear();
        }

        /** A value that exists: a recent record, any record, or a small literal. */
        int pick() {
            int n = values.size();
            int c = rnd.nextInt(8);
            if (n == 0 || c == 0) {
                return Layout.imm(rnd.nextInt(4));
            }
            return c < 4 ? values.get(n - 1 - rnd.nextInt(Math.min(n, 6))) : values.get(rnd.nextInt(n));
        }

        int pickFrom(List<Integer> list) {
            int n = list.size();
            return rnd.nextInt(3) == 0 ? list.get(n - 1 - rnd.nextInt(Math.min(n, 4))) : list.get(rnd.nextInt(n));
        }

        /** The record a constructor returned for {@code key}: the one the model knows, or the next new one. */
        int record(String key, int vpBefore, int got) {
            assertTrue(got >= Layout.FIRST_RECORD && got < Op.SIG, key + ": a record ref, not " + got);
            Integer known = ids.get(key);
            if (known != null) {
                assertEquals(known, got, key + ": the record that exists is found (vY " + t.vY + ", vp " + t.vp + ")");
                assertEquals(vpBefore, t.vp, key + ": nothing was made");
                found++;
            } else {
                assertEquals(vpBefore, got, key + ": a new record (not an older one: " + got + ")");
                ids.put(key, got);
                values.add(got);
                made++;
            }
            return got;
        }

        int run(String key, int rspBefore, int got) {
            assertTrue(got >= Layout.FIRST_RUN && got < Op.SIG, key + ": a run id, not " + got);
            Integer known = ids.get(key);
            if (known != null) {
                assertEquals(known, got, key + ": the run that exists is found (rY " + t.rY + ", rsp " + t.rsp + ")");
                assertEquals(rspBefore, t.rsp, key + ": nothing was made");
                found++;
            } else {
                assertEquals(rspBefore, got, key + ": a new run (not an older one: " + got + ")");
                ids.put(key, got);
                made++;
            }
            return got;
        }

        int frame(int[] slots, int rest) {
            StringBuilder key = new StringBuilder("F").append(rest);
            for (int s : slots) {
                key.append(',').append(s);
            }
            int before = t.rsp;
            int f = run(key.toString(), before, Runs.frame(t, M1ValuesTest.args(t, slots), slots.length, rest));
            if (f == before) {
                frames.add(f);
            }
            return f;
        }

        void step() {
            switch (rnd.nextInt(14)) {
                case 0: {
                    int level = 1 + rnd.nextInt(30);
                    record("S" + level, t.vp, Values.mkSort(t, level));
                    break;
                }
                case 1:
                case 2:
                case 3: { // a closure or suspension over a frame of values that exist (or over an older frame)
                    int f;
                    if (!frames.isEmpty() && rnd.nextInt(3) == 0) {
                        f = pickFrom(frames);
                    } else {
                        int[] slots = new int[rnd.nextInt(4)];
                        for (int i = 0; i < slots.length; i++) {
                            slots[i] = rnd.nextInt(6) == 0 ? Layout.HOLE : pick();
                        }
                        f = slots.length == 0 ? Layout.RUN_EMPTY : frame(slots, 0);
                    }
                    int term = 1 + rnd.nextInt(5);
                    int k = rnd.nextInt(4);
                    int before = t.vp;
                    int v = k == 0 ? Values.mkLam(t, term, f, 0) : k == 1 ? Values.mkPi(t, term, f, 0)
                            : k == 2 ? Values.mkPii(t, term, f, 0) : Values.suspRecord(t, term, f, 0);
                    record("C" + k + "," + term + "," + f, before, v);
                    break;
                }
                case 4: { // a free variable of a type that exists
                    int type = pick();
                    int k = rnd.nextInt(3);
                    int before = t.vp;
                    int v = record("V" + type + "," + k, before, Values.mkFVar(t, type, k));
                    if (v == before) {
                        heads.add(v);
                    }
                    break;
                }
                case 5:
                case 6:
                case 7: { // an application in one step, or the extension of one that exists
                    if (heads.isEmpty()) {
                        break;
                    }
                    int f = pickFrom(heads);
                    int[] pre = spines.get(f);
                    int head = pre == null ? f : pre[0];
                    int pn = pre == null ? 0 : pre.length - 1;
                    int n = 1 + rnd.nextInt(3);
                    int[] all = new int[1 + pn + n];
                    all[0] = head;
                    int[] args = new int[n];
                    for (int i = 0; i < n; i++) {
                        args[i] = pick();
                        all[1 + pn + i] = args[i];
                    }
                    if (pre != null) {
                        System.arraycopy(pre, 1, all, 1, pn);
                    }
                    StringBuilder key = new StringBuilder("N").append(head);
                    for (int i = 1; i < all.length; i++) {
                        key.append(',').append(all[i]);
                    }
                    int before = t.vp;
                    int v;
                    if (pre == null && rnd.nextBoolean()) {
                        v = Values.mkNeu(t, head, M1ValuesTest.args(t, args), n);
                    } else if (pre != null && rnd.nextInt(4) == 0) { // the same application in one step
                        int[] whole = new int[pn + n];
                        System.arraycopy(all, 1, whole, 0, pn + n);
                        v = Values.mkNeu(t, head, M1ValuesTest.args(t, whole), pn + n);
                    } else {
                        v = Values.mkApp(t, f, M1ValuesTest.args(t, args), n);
                    }
                    record(key.toString(), before, v);
                    if (v == before) {
                        spines.put(v, all);
                        heads.add(v);
                    }
                    break;
                }
                case 10: { // an application that exists, made again by extending a prefix of it (which may be younger than it)
                    if (heads.isEmpty()) {
                        break;
                    }
                    int f = pickFrom(heads);
                    int[] all = spines.get(f);
                    if (all == null || all.length < 3) {
                        break;
                    }
                    int k = 1 + rnd.nextInt(all.length - 2); // arguments of the prefix
                    int[] first = new int[k];
                    System.arraycopy(all, 1, first, 0, k);
                    StringBuilder key = new StringBuilder("N").append(all[0]);
                    for (int i = 1; i <= k; i++) {
                        key.append(',').append(all[i]);
                    }
                    int before = t.vp;
                    int prefix = record(key.toString(), before, Values.mkNeu(t, all[0], M1ValuesTest.args(t, first), k));
                    if (prefix == before) {
                        int[] sp = new int[k + 1];
                        System.arraycopy(all, 0, sp, 0, k + 1);
                        spines.put(prefix, sp);
                        heads.add(prefix);
                    }
                    int[] rest = new int[all.length - 1 - k];
                    System.arraycopy(all, 1 + k, rest, 0, rest.length);
                    before = t.vp;
                    assertEquals(f, Values.mkApp(t, prefix, M1ValuesTest.args(t, rest), rest.length), "the application that exists is found through its prefix " + prefix + " (vY " + t.vY + ")");
                    assertEquals(before, t.vp);
                    found++;
                    break;
                }
                case 8: { // a projection of a variable or an application
                    if (heads.isEmpty()) {
                        break;
                    }
                    int s = pickFrom(heads);
                    int row = 1 + rnd.nextInt(3);
                    int field = rnd.nextInt(2);
                    int before = t.vp;
                    int v = record("J" + s + "," + row + "," + field, before, Values.mkProj(t, row, field, s));
                    if (v == before) {
                        heads.add(v);
                    }
                    break;
                }
                case 9: { // a REST chain and a far frame over it
                    int[] outer = new int[1 + rnd.nextInt(70)];
                    StringBuilder key = new StringBuilder("R");
                    for (int i = 0; i < outer.length; i++) {
                        outer[i] = pick();
                        key.append(',').append(outer[i]);
                    }
                    int rest;
                    Integer known = ids.get(key.toString());
                    int before = t.rsp;
                    rest = Runs.restRun(t, M1ValuesTest.args(t, outer), outer.length);
                    if (known != null) {
                        assertEquals(known, rest, "the REST chain that exists is found");
                        assertEquals(before, t.rsp, "no chunk was made");
                    } else {
                        assertTrue(rest >= Layout.FIRST_RUN && rest < Op.SIG);
                        ids.put(key.toString(), rest);
                        rests.add(rest);
                    }
                    int[] near = new int[Layout.NEAR_SLOTS];
                    for (int i = 0; i < near.length; i++) {
                        near[i] = i % 9 == 0 ? pick() : Layout.HOLE;
                    }
                    frame(near, rnd.nextInt(4) == 0 ? pickFrom(rests) : rest);
                    break;
                }
                default: { // a pair of values that exist
                    int a = pick();
                    int b = pick();
                    int lo = Integer.compareUnsigned(a, b) <= 0 ? a : b;
                    int hi = lo == a ? b : a;
                    long key = (long) lo << 32 | (hi & 0xFFFFFFFFL);
                    Integer st = pairs.get(key);
                    assertEquals(st == null ? 0 : st, Pairs.status(t, a, b), "status of (" + lo + ", " + hi + "), vYp " + t.vYp);
                    assertEquals(st == null ? 0 : st, Pairs.status(t, b, a));
                    if (rnd.nextInt(3) != 0) {
                        int bits = 1 << rnd.nextInt(3);
                        int used = t.pairUsed;
                        boolean swap = rnd.nextBoolean();
                        Pairs.add(t, swap ? b : a, swap ? a : b, bits);
                        assertEquals(st == null ? used + 1 : used, t.pairUsed, "one entry per pair");
                        pairs.put(key, st == null ? bits : st | bits);
                        assertEquals(pairs.get(key), Pairs.status(t, b, a));
                    }
                    break;
                }
            }
        }
    }

    private static void run(Machine t, long seed, int steps, int resetEvery, int auditEvery) {
        Model md = new Model(t, seed);
        for (int i = 1; i <= steps; i++) {
            md.step();
            assertEquals(md.pairs.size(), t.pairUsed);
            if (i % auditEvery == 0) {
                assertNull(Debug.tablesAudit(t), "seed " + seed + ", step " + i);
            }
            if (i % resetEvery == 0) {
                assertNull(Debug.tablesAudit(t), "seed " + seed + ", step " + i + ", before the reset");
                t.reset();
                md.forget();
                assertTrue(t.tablesClean(), "seed " + seed + ", step " + i + ": a table is not empty after the reset");
                assertNull(Debug.tablesAudit(t));
            }
        }
        assertTrue(md.made > steps / 4 && md.found > steps / 20, "the walk makes and finds: " + md.made + " made, " + md.found + " found");
    }

    @Test
    void randomValuesFramesAndPairsAgreeWithAModelOnATinyMachine() {
        for (long seed = 1; seed <= 12; seed++) {
            run(new Machine(Machine.Sizes.tiny()), seed, 6000, 1500 + 311 * (int) seed, 53);
        }
    }

    /** Young tables of 2 slots / 2 entries: every record, run and pair is moved to the old table at once. */
    @Test
    void theSmallestYoungTables() {
        Machine.Sizes z = Machine.Sizes.tiny();
        z.vtab = 1;
        z.rtab = 1;
        z.pairs = 1;
        run(new Machine(z), 77, 5000, 1700, 41);
    }

    /** The standard sizes: sessions that never leave the young tables, and sessions that move into old tables of the size the last session ended with. */
    @Test
    void standardSizesAndTheSizeHintOfTheOldTables() {
        Machine t = new Machine();
        run(t, 5, 4000, 1000, 500);
        int[] size = new int[3];
        for (int round = 0; round < 3; round++) {
            Model md = new Model(t, 100 + round);
            for (int i = 0; i < 150_000; i++) {
                md.step();
            }
            assertNull(Debug.tablesAudit(t), "round " + round);
            assertTrue(t.vY > t.vbase && t.rY > t.rbase && t.plogN > 0, "the session moved records, runs and pairs into old tables");
            size[round] = t.vold.length;
            t.reset();
            assertTrue(t.tablesClean());
        }
        assertTrue(size[1] >= size[0] && size[2] >= size[1], "an old table starts at the size the last session ended with");
    }

    /** The audit itself: a second record with the key of another one, and a record in the wrong table, are found. */
    @Test
    void theAuditFindsADuplicateAndAMisplacedRecord() {
        Machine t = new Machine(Machine.Sizes.tiny());
        int type = Values.mkSort(t, 3);
        int x = Values.mkFVar(t, type, 0);
        assertNull(Debug.tablesAudit(t));
        // a duplicate: the same key written again behind the probe's back
        int[] tab = t.vtab;
        int hash = Hash.record(Layout.FVAR << Layout.KIND_SHIFT, type, 0, 0);
        int slot = hash & (tab.length - 1);
        while (tab[slot] != 0) {
            slot = (slot + 1) & (tab.length - 1);
        }
        int dup = t.addRecordAt(slot, hash, t.vh[x << Layout.REC_SHIFT], type, 0, 0);
        assertTrue(dup != x);
        String wrong = Debug.tablesAudit(t);
        assertNotNull(wrong);
        assertTrue(wrong.contains("I-ID"), wrong);
        t.reset();
        // a record that the old table holds although a component of it is young
        Machine u = new Machine(Machine.Sizes.tiny());
        int ty = Values.mkSort(u, 3);
        for (int i = 0; i < 40; i++) {
            Values.mkFVar(u, ty, i);
        }
        assertNull(Debug.tablesAudit(u));
        assertTrue(u.vY > u.vbase + 8);
        u.vY -= 4; // pretend the young table was emptied four records earlier than it was
        assertNotNull(Debug.tablesAudit(u));
    }
}

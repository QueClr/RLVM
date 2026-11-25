package rlvm.level;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * The shared overlay of a worker ({@code newSharedOverlay()}: it reads the frozen base in place and holds
 * only what it creates). Every test of {@link OverlayTest} runs on it, and the tests here pin the one
 * property the layout must have: <b>the same ids and the same answers as the private flat copy
 * ({@code newOverlay()}), operation by operation</b> - within a session, across {@code reset()}, across
 * {@code rebase} onto later snapshots of a chain, where the closure of {@code freeze()} was cut, and
 * while a publisher goes on making snapshots next to eight workers. The flat copy is the layout the
 * module had before the shared one and the layout the snapshots themselves still have.
 */
class SharedOverlayTest extends OverlayTest {

    @Override
    LevelStore overlay(LevelStore base) {
        return base.newSharedOverlay();
    }

    // ---- a script of random operations whose every observable result goes into a trace ---------------

    /** What a script knows between steps: the name lists and level lists it may use. Equal on both stores by construction. */
    private static final class Ctx {
        final Lv.Names n;
        final String[] ps = {"u", "v", "w", "x"};
        final List<Integer> nameLists = new ArrayList<>();
        final List<Integer> levelLists = new ArrayList<>();
        final List<Integer> substs = new ArrayList<>();
        int baseNameLists;
        int baseLevelLists;
        int baseSubsts;

        Ctx(Lv.Names n) {
            this.n = n;
        }

        /** Forget what the session made (after a reset or a rebase). */
        void endSession() {
            trim(nameLists, baseNameLists);
            trim(levelLists, baseLevelLists);
            trim(substs, baseSubsts);
        }

        /** What is in the lists now belongs to the base. */
        void markBase() {
            baseNameLists = nameLists.size();
            baseLevelLists = levelLists.size();
            baseSubsts = substs.size();
        }

        private static void trim(List<Integer> l, int size) {
            while (l.size() > size) {
                l.remove(l.size() - 1);
            }
        }

        Ctx copy() {
            Ctx c = new Ctx(n);
            c.nameLists.addAll(nameLists);
            c.levelLists.addAll(levelLists);
            c.substs.addAll(substs);
            c.baseNameLists = baseNameLists;
            c.baseLevelLists = baseLevelLists;
            c.baseSubsts = baseSubsts;
            return c;
        }
    }

    private static int anyLevel(LevelStore s, Random rnd) {
        // half of the picks among the newest levels, so that overlay levels are used as operands
        int size = s.size();
        if (rnd.nextBoolean() && size > 40) {
            return size - 1 - rnd.nextInt(40);
        }
        return 1 + rnd.nextInt(size - 1);
    }

    /** One random operation on {@code s}; appends everything it returned to {@code out}. */
    private static void step(LevelStore s, Random rnd, Ctx c, StringBuilder out) {
        int op = rnd.nextInt(26);
        out.append(op).append(':');
        switch (op) {
            case 0: {
                out.append(s.succ(anyLevel(s, rnd)));
                break;
            }
            case 1: {
                out.append(s.max(anyLevel(s, rnd), anyLevel(s, rnd)));
                break;
            }
            case 2: {
                out.append(s.imax(anyLevel(s, rnd), anyLevel(s, rnd)));
                break;
            }
            case 3: {
                out.append(s.param(c.n.id(c.ps[rnd.nextInt(c.ps.length)]))).append(',').append(s.ofNat(rnd.nextInt(12)));
                break;
            }
            case 4: {
                out.append(s.succN(anyLevel(s, rnd), rnd.nextInt(4)));
                break;
            }
            case 5: {
                out.append(s.mkMax(anyLevel(s, rnd), anyLevel(s, rnd)));
                break;
            }
            case 6: {
                out.append(s.mkIMax(anyLevel(s, rnd), anyLevel(s, rnd)));
                break;
            }
            case 7: {
                int l = anyLevel(s, rnd);
                int k = s.kind(l);
                out.append(k).append(',').append(s.depth(l)).append(',').append(s.hasParam(l)).append(',').append(s.hasIMax(l))
                        .append(',').append(s.isExplicit(l)).append(',').append(s.offsetBase(l)).append(',').append(s.offset(l))
                        .append(',').append(s.isZero(l)).append(',').append(s.isNeverZero(l)).append(',').append(s.isAlwaysZero(l));
                if (k == LevelStore.KIND_MAX || k == LevelStore.KIND_IMAX) {
                    out.append(',').append(s.lhs(l)).append(',').append(s.rhs(l));
                } else if (k == LevelStore.KIND_SUCC) {
                    out.append(',').append(s.succOf(l));
                } else if (k == LevelStore.KIND_PARAM) {
                    out.append(',').append(s.paramName(l));
                }
                break;
            }
            case 8: {
                out.append(s.normalize(anyLevel(s, rnd)));
                break;
            }
            case 9: {
                int x = anyLevel(s, rnd);
                int y = anyLevel(s, rnd);
                out.append(s.isEquivOfficial(x, y)).append(',').append(s.isGeqOfficial(x, y));
                break;
            }
            case 10: {
                int x = anyLevel(s, rnd);
                out.append(s.canonId(x)).append(',').append(s.canonRep(x));
                break;
            }
            case 11: {
                int x = anyLevel(s, rnd);
                int y = anyLevel(s, rnd);
                out.append(s.isEquiv(x, y)).append(',').append(s.isGeq(x, y)).append(',').append(s.isLeq(x, y));
                break;
            }
            case 12:
            case 13: {
                // a level list
                int len = rnd.nextInt(4);
                int[] buf = new int[len + 2];
                for (int i = 0; i < len; i++) {
                    buf[1 + i] = rnd.nextInt(4) == 0 ? 1 + rnd.nextInt(Math.min(s.size() - 1, 30)) : anyLevel(s, rnd);
                }
                int id = s.internList(buf, 1, len);
                c.levelLists.add(id);
                out.append(id);
                break;
            }
            case 14: {
                // a name list
                int len = 1 + rnd.nextInt(3);
                int[] buf = new int[len];
                for (int i = 0; i < len; i++) {
                    buf[i] = c.n.id(c.ps[rnd.nextInt(c.ps.length)]);
                }
                int id = s.internList(buf, 0, len);
                c.nameLists.add(id);
                out.append(id);
                break;
            }
            case 15: {
                int l = c.levelLists.get(rnd.nextInt(c.levelLists.size()));
                int len = s.listLength(l);
                out.append(len).append(',').append(s.listHasDuplicates(l)).append(',').append(s.listToString(l));
                IntListStore ls = s.lists();
                int[] copy = new int[len + 1];
                ls.copyTo(l, copy, 1);
                int[] pool = ls.poolOf(l);
                int st = ls.start(l);
                for (int i = 0; i < len; i++) {
                    out.append(',').append(s.listGet(l, i));
                    assertEquals(s.listGet(l, i), ls.get(l, i));
                    assertEquals(s.listGet(l, i), copy[1 + i]);
                    assertEquals(s.listGet(l, i), pool[st + i]);
                }
                assertEquals(len, ls.length(l));
                break;
            }
            case 16: {
                int l1 = c.levelLists.get(rnd.nextInt(c.levelLists.size()));
                int l2 = c.levelLists.get(rnd.nextInt(c.levelLists.size()));
                out.append(s.listsEquiv(l1, l2));
                break;
            }
            case 17:
            case 18:
            case 19: {
                // a substitution: a name list with a level list (of the same length, mostly)
                int names = c.nameLists.get(rnd.nextInt(c.nameLists.size()));
                int k = s.listLength(names);
                int levels;
                if (rnd.nextInt(5) == 0) {
                    levels = c.levelLists.get(rnd.nextInt(c.levelLists.size()));
                } else {
                    int[] buf = new int[k];
                    for (int i = 0; i < k; i++) {
                        int r = rnd.nextInt(4);
                        buf[i] = r == 0 ? s.param(c.n.id(c.ps[rnd.nextInt(c.ps.length)])) : r == 1 ? s.ofNat(rnd.nextInt(9)) : anyLevel(s, rnd);
                    }
                    levels = s.internList(buf, 0, k);
                    c.levelLists.add(levels);
                }
                int subst = s.mkSubst(names, levels);
                out.append(levels).append(',').append(subst);
                if (subst > 0) {
                    c.substs.add(subst);
                    out.append(',').append(s.substParams(subst)).append(',').append(s.substLevels(subst));
                }
                break;
            }
            case 20:
            case 21: {
                int subst = c.substs.isEmpty() || rnd.nextInt(8) == 0 ? 0 : c.substs.get(rnd.nextInt(c.substs.size()));
                int l = anyLevel(s, rnd);
                out.append(s.instantiate(l, subst)).append(',').append(s.instantiate(l, subst));
                break;
            }
            case 22: {
                int subst = c.substs.isEmpty() ? 0 : c.substs.get(rnd.nextInt(c.substs.size()));
                int list = c.levelLists.get(rnd.nextInt(c.levelLists.size()));
                int r = s.instantiateList(list, subst);
                c.levelLists.add(r);
                out.append(r).append(',').append(s.instantiateList(list, subst));
                break;
            }
            case 23: {
                int names = c.nameLists.get(rnd.nextInt(c.nameLists.size()));
                int levels = c.levelLists.get(rnd.nextInt(c.levelLists.size()));
                out.append(s.instantiate(anyLevel(s, rnd), names, levels));
                break;
            }
            case 24: {
                int names = c.nameLists.get(rnd.nextInt(c.nameLists.size()));
                int l = anyLevel(s, rnd);
                out.append(s.undeclaredParam(l, names)).append(',').append(s.paramsDeclared(l, names)).append(',')
                        .append(s.undeclaredParamInList(c.levelLists.get(rnd.nextInt(c.levelLists.size())), names)).append(',')
                        .append(s.undeclaredParam(l, names));
                break;
            }
            default: {
                out.append(s.toString(anyLevel(s, rnd)));
                break;
            }
        }
        out.append(" [").append(s.size()).append(' ').append(s.listCount()).append(' ').append(s.baseSize()).append(' ')
                .append(s.baseListCount()).append("]\n");
    }

    /** Levels, name lists, level lists, substitutions and the LV1-F1 aliases of an open store (the loader's side). */
    private static void grow(LevelStore w, Random rnd, Ctx c, int levels, int aliases) {
        for (int i = 0; i < levels; i++) {
            Lv.toStore(w, Lv.random(rnd, 1 + rnd.nextInt(5), 4), c.n);
        }
        // lists, substitutions, instantiations and memoised answers, as a loader leaves them in its store
        StringBuilder sink = new StringBuilder();
        for (int i = 0; i < levels / 2; i++) {
            step(w, rnd, c, sink);
            sink.setLength(0);
        }
        // Aliases (finding LV1-F1): two-element lists whose ints are (a name list, one of the first list ids an
        // overlay of this store will hand out), so that a session's substitution pair is FOUND as a base list.
        int first = w.listCount() + aliases * Math.max(1, c.nameLists.size());
        while (w.size() <= first + aliases) {
            w.succ(w.size() - 1);
        }
        int[] buf = new int[2];
        for (int nl : c.nameLists) {
            for (int j = 0; j < aliases; j++) {
                buf[0] = nl;
                buf[1] = first + j;
                c.levelLists.add(w.internList(buf, 0, 2));
            }
        }
    }

    private static Ctx seed(LevelStore root, Lv.Names n) {
        Ctx c = new Ctx(n);
        int[] buf = new int[3];
        buf[0] = n.id("u");
        c.nameLists.add(root.internList(buf, 0, 1));
        buf[1] = n.id("v");
        c.nameLists.add(root.internList(buf, 0, 2));
        buf[0] = LevelStore.ONE;
        c.levelLists.add(root.internList(buf, 0, 1));
        return c;
    }

    /** Runs the same script on both stores and requires equal traces. */
    private static void sameTrace(LevelStore flat, LevelStore shared, Ctx cf, Ctx cs, long seed, int steps, String what) {
        Random a = new Random(seed);
        Random b = new Random(seed);
        StringBuilder x = new StringBuilder();
        StringBuilder y = new StringBuilder();
        for (int i = 0; i < steps; i++) {
            x.setLength(0);
            y.setLength(0);
            RuntimeException ex = null;
            RuntimeException ey = null;
            try {
                step(flat, a, cf, x);
            } catch (LevelLimitException | IllegalArgumentException e) {
                ex = e;
            }
            try {
                step(shared, b, cs, y);
            } catch (LevelLimitException | IllegalArgumentException e) {
                ey = e;
            }
            assertEquals(ex == null ? null : ex.getClass(), ey == null ? null : ey.getClass(), what + ", step " + i);
            assertEquals(x.toString(), y.toString(), what + ", seed " + seed + ", step " + i);
        }
        assertEquals(flat.size(), shared.size(), what);
        assertEquals(flat.listCount(), shared.listCount(), what);
    }

    // ---- the tests -----------------------------------------------------------------------------------

    @Test
    void sameIdsAsThePrivateCopyOperationByOperation() {
        Lv.Names n = names();
        for (int round = 0; round < 6; round++) {
            LevelStore base = new LevelStore(n);
            Ctx c = seed(base, n);
            grow(base, new Random(500 + round), c, 60 + 150 * round, 6);
            c.markBase();
            base.freeze();
            LevelStore flat = base.newOverlay();
            LevelStore shared = base.newSharedOverlay();
            assertTrue(shared.isSharedOverlay() && shared.isOverlay() && !flat.isSharedOverlay());
            Ctx cf = c.copy();
            Ctx cs = c.copy();
            for (int session = 0; session < 40; session++) {
                sameTrace(flat, shared, cf, cs, 9000 + 100 * round + session, 30 + 40 * (session % 5), "round " + round + ", session " + session);
                flat.reset();
                shared.reset();
                cf.endSession();
                cs.endSession();
                assertEquals(base.size(), shared.size());
                assertEquals(base.listCount(), shared.listCount());
            }
        }
    }

    @Test
    void sameIdsAlongAChainOfSnapshots() {
        Lv.Names n = names();
        Random rnd = new Random(61);
        LevelStore open = new LevelStore(n);
        Ctx loader = seed(open, n);
        grow(open, rnd, loader, 40, 5);
        open.freeze();
        loader.markBase();
        LevelStore snapshot = open;
        LevelStore flat = snapshot.newOverlay();
        LevelStore shared = snapshot.newSharedOverlay();
        LevelStore sharedFirst = shared;
        Ctx cf = loader.copy();
        Ctx cs = loader.copy();
        int rebases = 0;
        for (int publication = 1; publication <= 60; publication++) {
            // the loader goes on in a private copy of the snapshot, exactly as rlvm.load.LevelChain does
            open = snapshot.newOverlay();
            grow(open, rnd, loader, 5 + rnd.nextInt(60), 3);
            open.freeze();
            loader.markBase();
            snapshot = open;
            // the worker works on the snapshot it has, with resets, and takes a newer one only now and then
            for (int session = 0; session < 3; session++) {
                sameTrace(flat, shared, cf, cs, 70000 + 10 * publication + session, 60, "publication " + publication + ", session " + session);
                flat.reset();
                shared.reset();
                cf.endSession();
                cs.endSession();
            }
            if (rnd.nextInt(3) != 0) {
                if (rnd.nextBoolean()) {
                    sameTrace(flat, shared, cf, cs, 80000 + publication, 25, "before the rebase at publication " + publication);
                }
                flat = snapshot.newOverlay();   // what a worker did before: a copy of the whole snapshot
                shared.rebase(snapshot);        // what it does now: nothing is copied
                cf = loader.copy();
                cs = loader.copy();
                rebases++;
                assertEquals(snapshot.size(), shared.size());
                assertEquals(snapshot.size(), shared.baseSize());
                assertEquals(snapshot.listCount(), shared.baseListCount());
            }
        }
        assertTrue(rebases > 20);
        assertTrue(shared == sharedFirst);
        // a rebase onto the snapshot the overlay already sits on is a reset that also empties the caches
        shared.rebase(snapshot);
        flat = snapshot.newOverlay();
        sameTrace(flat, shared, loader.copy(), loader.copy(), 99, 200, "after a rebase onto the same snapshot");
    }

    /**
     * A cached instantiation made of base ids only survives {@code reset()} and so skips the levels its
     * computation would create; a fresh overlay of a newer snapshot has no such entry. The rebased overlay
     * must be that fresh overlay: the ids that follow depend on it.
     */
    @Test
    void cacheEntriesThatSurviveResetDoNotSurviveRebase() {
        Lv.Names n = names();
        LevelStore root = new LevelStore(n);
        int u = root.param(n.id("u"));
        int v = root.param(n.id("v"));
        int w = root.param(n.id("w"));
        int x = root.param(n.id("x"));
        // imax (succ (max u x)) v  with  u := w+2, v := 0   is 0, and computing it builds max (w+2) x and its successor
        int l = root.imax(root.succ(root.max(u, x)), v);
        int namesUV = root.internList(new int[] {n.id("u"), n.id("v")}, 0, 2);
        int args = root.internList(new int[] {root.succ(root.succ(w)), LevelStore.ZERO}, 0, 2);
        int subst = root.mkSubst(namesUV, args);
        assertTrue(subst > 0);
        root.freeze();
        LevelStore second = root.newOverlay();
        second.succ(second.succ(second.succ(second.param(n.id("x")))));
        second.freeze();

        for (boolean sharedKind : new boolean[] {false, true}) {
            LevelStore ov = sharedKind ? root.newSharedOverlay() : root.newOverlay();
            int bs = ov.size();
            assertEquals(LevelStore.ZERO, ov.instantiate(l, subst));
            int created = ov.size() - bs;
            assertTrue(created >= 2, "the computation creates levels: " + created);
            ov.reset();
            assertEquals(LevelStore.ZERO, ov.instantiate(l, subst));
            assertEquals(bs, ov.size(), "the entry of base ids answers after a reset and creates nothing");
            // a newer snapshot
            if (sharedKind) {
                ov.rebase(second);
            } else {
                ov = second.newOverlay();
            }
            assertEquals(second.size(), ov.size());
            assertEquals(LevelStore.ZERO, ov.instantiate(l, subst));
            assertEquals(second.size() + created, ov.size(), "a fresh overlay of the newer snapshot computes it again (shared: " + sharedKind + ")");
        }
    }

    /**
     * Where the closure of {@code freeze()} was cut, base levels have no stored normal form and an overlay
     * computes it; the private copy remembers a result that is a base level in its copy of the base's
     * array. The shared overlay must give the same ids without writing the base.
     */
    @Test
    void baseLevelsWithoutAStoredNormalFormBehaveAsInThePrivateCopy() {
        Lv.Names n = names();
        LevelStore base = new LevelStore(n);
        int m = base.max(base.param(n.id("u")), base.max(base.param(n.id("v")), base.max(base.param(n.id("w")), base.param(n.id("x")))));
        int[] chain = new int[400];
        int prev = m;
        for (int i = 0; i < chain.length; i++) {
            prev = base.succ(prev);
            chain[i] = prev;
        }
        // above the levels the closure reaches: imax (succ^400 m) 0 normalises to 0, a base level, and computing it
        // builds the normal form of succ^400 m, which the base does not have
        int special = base.imax(chain[chain.length - 1], LevelStore.ZERO);
        int before = base.size();
        base.freeze();
        assertTrue(base.size() > 4 * before, "the normal forms outgrow the bound: " + before + " -> " + base.size());
        int unknown = 0;
        for (int l : chain) {
            try {
                base.normalize(l);
            } catch (IllegalStateException e) {
                unknown++;
            }
        }
        assertTrue(unknown > 50, "levels the closure did not reach: " + unknown);
        long digest = digest(base);

        LevelStore flat = base.newOverlay();
        LevelStore shared = base.newSharedOverlay();
        // the memo of a base level whose normal form is a base level: written once, valid across reset()
        assertThrows(IllegalStateException.class, () -> base.normalize(special));
        assertEquals(LevelStore.ZERO, flat.normalize(special));
        assertEquals(LevelStore.ZERO, shared.normalize(special));
        assertTrue(flat.size() > base.size(), "the computation creates levels");
        assertEquals(flat.size(), shared.size());
        flat.reset();
        shared.reset();
        assertEquals(LevelStore.ZERO, flat.normalize(special));
        assertEquals(LevelStore.ZERO, shared.normalize(special));
        assertEquals(base.size(), flat.size(), "the private copy remembers it");
        assertEquals(base.size(), shared.size(), "and so does the shared overlay, in a copy of that one array");
        assertThrows(IllegalStateException.class, () -> base.normalize(special), "the base itself was not written");
        for (int round = 0; round < 4; round++) {
            for (int i = chain.length - 1; i >= 0; i -= 3) {
                assertEquals(flat.normalize(chain[i]), shared.normalize(chain[i]), "normalize of base level " + chain[i]);
                assertEquals(flat.size(), shared.size(), "levels after normalize of base level " + chain[i]);
                assertEquals(flat.isEquivOfficial(chain[i], chain[i / 2]), shared.isEquivOfficial(chain[i], chain[i / 2]));
                assertEquals(flat.isGeqOfficial(chain[i], m), shared.isGeqOfficial(chain[i], m));
                assertEquals(flat.size(), shared.size());
            }
            Ctx cf = seedExisting(flat, n);
            Ctx cs = seedExisting(shared, n);
            sameTrace(flat, shared, cf, cs, 4242 + round, 300, "closure cut, round " + round);
            flat.reset();
            shared.reset();
        }
        assertEquals(digest, digest(base), "the shared overlay wrote the base");
    }

    private static Ctx seedExisting(LevelStore s, Lv.Names n) {
        Ctx c = new Ctx(n);
        c.nameLists.add(s.internList(new int[] {n.id("u")}, 0, 1));
        c.nameLists.add(s.internList(new int[] {n.id("x"), n.id("v")}, 0, 2));
        c.levelLists.add(s.internList(new int[] {LevelStore.ONE}, 0, 1));
        return c;
    }

    @Test
    void whatASharedOverlayRefuses() {
        Lv.Names n = names();
        LevelStore base = new LevelStore(n);
        int u = base.param(n.id("u"));
        assertThrows(IllegalStateException.class, base::newSharedOverlay);
        base.freeze();
        LevelStore shared = base.newSharedOverlay();
        LevelStore flat = base.newOverlay();
        // only a flat store becomes a base
        assertThrows(IllegalStateException.class, shared::freeze);
        assertThrows(IllegalStateException.class, shared::newOverlay);
        assertThrows(IllegalStateException.class, shared::newSharedOverlay);
        // it has two backing arrays for its lists
        assertThrows(IllegalStateException.class, () -> shared.lists().pool());
        assertNotEquals(null, flat.lists().pool());
        // rebase: a frozen flat store of the same chain, never a smaller one
        assertThrows(IllegalStateException.class, () -> flat.rebase(base));
        assertThrows(IllegalStateException.class, () -> base.rebase(base));
        assertThrows(IllegalArgumentException.class, () -> shared.rebase(null));
        assertThrows(IllegalArgumentException.class, () -> shared.rebase(flat));
        assertThrows(IllegalArgumentException.class, () -> shared.rebase(shared));
        assertThrows(IllegalArgumentException.class, () -> shared.rebase(new LevelStore(new Lv.Names("u"))));
        LevelStore next = base.newOverlay();
        int su = next.succ(u);
        next.freeze();
        shared.rebase(next);
        assertEquals(su, shared.succ(u));
        assertThrows(IllegalArgumentException.class, () -> shared.rebase(base), "a snapshot never shrinks");
        // the overlay of a snapshot (a frozen private copy) is as good as the overlay of a root store
        LevelStore onSnapshot = next.newSharedOverlay();
        assertEquals(su, onSnapshot.succ(u));
        assertEquals(next.size(), onSnapshot.size());
        int fresh = onSnapshot.succ(su);
        assertEquals(next.size(), fresh);
        onSnapshot.reset();
        assertEquals(next.size(), onSnapshot.size());
    }

    /** The point of the layout: an overlay of a large base holds no copy of it, and a rebase allocates nothing per base level. */
    @Test
    void aSharedOverlayHoldsOnlyWhatItCreates() throws ReflectiveOperationException {
        Lv.Names n = names();
        LevelStore base = randomBase(n, 31, 20000, null);
        int[] buf = new int[3];
        Random rnd = new Random(32);
        for (int i = 0; i < 30000; i++) {
            buf[0] = 1 + rnd.nextInt(base.size() - 1);
            buf[1] = 1 + rnd.nextInt(base.size() - 1);
            buf[2] = 1 + rnd.nextInt(base.size() - 1);
            base.internList(buf, 0, 1 + rnd.nextInt(3));
        }
        base.freeze();
        assertTrue(base.size() > 20000 && base.listCount() > 20000);
        LevelStore shared = base.newSharedOverlay();
        long own = ownInts(shared);
        long flat = ownInts(base.newOverlay());
        assertTrue(own < 40000, "ints a new shared overlay owns: " + own);
        assertTrue(flat > 10 * own, "ints a private copy owns: " + flat);
        // and the arrays it reads are the base's own, not copies
        for (String f : new String[] {"bInfo:info", "bA:a", "bB:b", "bOffBase:offBase", "bOffK:offK", "bNorm:norm", "bCanon:canon", "bTable:table",
            "bCanonRep:canonRep"}) {
            String[] p = f.split(":");
            assertTrue(field(shared, p[0]) == field(base, p[1]), p[0] + " is the base's " + p[1]);
        }
        Object lists = field(shared, "lists");
        Object baseLists = field(base, "lists");
        for (String f : new String[] {"bPool:pool", "bOff:off", "bHash:hash", "bTable:table"}) {
            String[] p = f.split(":");
            assertTrue(field(lists, p[0]) == field(baseLists, p[1]), "lists." + p[0] + " is the base's " + p[1]);
        }
        // work, reset, rebase: the own arrays stay what the sessions needed
        LevelStore next = base.newOverlay();
        next.succ(next.succ(next.size() - 1));
        next.freeze();
        work(shared, n, 33, 600);
        shared.rebase(next);
        work(shared, n, 34, 600);
        shared.reset();
        assertTrue(ownInts(shared) < 80000, "ints after two sessions: " + ownInts(shared));
        assertTrue(field(shared, "bInfo") == field(next, "info"));
    }

    private static Object field(Object o, String name) throws ReflectiveOperationException {
        Field f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(o);
    }

    /** Ints in the arrays a store owns (the fixed-size caches and scratch arrays excluded). */
    private static long ownInts(LevelStore s) throws ReflectiveOperationException {
        long sum = 0;
        for (String f : new String[] {"info", "a", "b", "offBase", "offK", "norm", "canon", "chk", "table", "canonRep", "bChk"}) {
            sum += ((int[]) field(s, f)).length;
        }
        for (String l : new String[] {"lists", "canons"}) {
            Object ls = field(s, l);
            for (String f : new String[] {"pool", "off", "hash", "table"}) {
                sum += ((int[]) field(ls, f)).length;
            }
        }
        return sum;
    }

    /** Everything a frozen store answers, folded into one number. */
    private static long digest(LevelStore s) {
        long h = 1125899906842597L;
        int size = s.size();
        for (int id = 1; id < size; id++) {
            int k = s.kind(id);
            h = 31 * h + k;
            h = 31 * h + s.depth(id);
            h = 31 * h + s.offsetBase(id);
            h = 31 * h + s.offset(id);
            h = 31 * h + (k == LevelStore.KIND_MAX || k == LevelStore.KIND_IMAX ? s.lhs(id) * 131 + s.rhs(id)
                    : k == LevelStore.KIND_SUCC ? s.succOf(id) : k == LevelStore.KIND_PARAM ? s.paramName(id) : 0);
            h = 31 * h + s.canonId(id);
            h = 31 * h + s.canonRep(id);
            int nf;
            try {
                nf = s.normalize(id);
            } catch (IllegalStateException e) {
                nf = -2; // no stored normal form (the closure of freeze() was cut)
            }
            h = 31 * h + nf;
        }
        int lists = s.listCount();
        for (int l = 0; l < lists; l++) {
            int len = s.listLength(l);
            h = 31 * h + len;
            for (int i = 0; i < len; i++) {
                h = 31 * h + s.listGet(l, i);
            }
        }
        return 31 * h + size * 1000003L + lists;
    }

    /**
     * Eight workers on shared overlays while a publisher goes on making snapshots (a private copy of the
     * newest one, grown and frozen, published through one volatile reference, as the level chain of a
     * pipelined load does). Every worker runs each of its sessions twice, on its shared overlay and on a
     * private copy of the same snapshot, and requires the same trace; it moves to the newest snapshot
     * between sessions. No snapshot may change after its publication.
     */
    @Test
    void workersShareTheSnapshotsOfAPublisher() throws InterruptedException {
        final Lv.Names n = names();
        final class Published {
            final LevelStore store;
            final Ctx ctx;
            final long digest;

            Published(LevelStore store, Ctx ctx) {
                this.store = store;
                this.ctx = ctx;
                this.digest = digest(store);
            }
        }
        final int publications = 120;
        final ConcurrentLinkedQueue<Published> all = new ConcurrentLinkedQueue<>();
        final ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();
        final AtomicBoolean done = new AtomicBoolean();
        final AtomicLong rebases = new AtomicLong();
        final AtomicLong sessions = new AtomicLong();
        final java.util.concurrent.atomic.AtomicReference<Published> current = new java.util.concurrent.atomic.AtomicReference<>();

        LevelStore root = new LevelStore(n);
        final Ctx loader = seed(root, n);
        grow(root, new Random(71), loader, 300, 4);
        root.freeze();
        loader.markBase();
        Published first = new Published(root, loader.copy());
        all.add(first);
        current.set(first);

        Thread publisher = new Thread(() -> {
            try {
                Random rnd = new Random(72);
                LevelStore snapshot = root;
                for (int p = 0; p < publications; p++) {
                    LevelStore open = snapshot.newOverlay();
                    grow(open, rnd, loader, 20 + rnd.nextInt(200), 2);
                    open.freeze();
                    loader.markBase();
                    Published next = new Published(open, loader.copy());
                    all.add(next);
                    current.set(next); // the publication
                    snapshot = open;
                    Thread.sleep(1);
                }
            } catch (Throwable e) {
                failures.add(e);
            } finally {
                done.set(true);
            }
        }, "level-publisher");

        int workers = 8;
        Thread[] threads = new Thread[workers];
        for (int t = 0; t < workers; t++) {
            final int tid = t;
            threads[t] = new Thread(() -> {
                try {
                    Published on = current.get();
                    LevelStore shared = on.store.newSharedOverlay();
                    LevelStore flat = null;
                    Random rnd = new Random(900 + tid);
                    int session = 0;
                    boolean last = false;
                    while (!last) {
                        last = done.get();
                        Published newest = current.get();
                        if (newest != on) {
                            shared.rebase(newest.store);
                            on = newest;
                            rebases.incrementAndGet();
                            flat = null;
                        } else {
                            shared.reset();
                        }
                        // The reference of this session: a private copy of the same snapshot, used and reset as the
                        // shared overlay is - a NEW copy only where the shared overlay was rebased. (Until INV-1 it
                        // was a new copy in every round: a worker that found no new snapshot then held an overlay
                        // that was reset, with the cache entries of base ids that survive a reset, against a copy
                        // without them, and the ids that follow differ. The test failed for that reason of its own
                        // whenever the publisher was slower than a worker, on a loaded host.)
                        if (flat == null) {
                            flat = on.store.newOverlay();
                        } else {
                            flat.reset();
                        }
                        Ctx cf = on.ctx.copy();
                        Ctx cs = on.ctx.copy();
                        int inner = 1 + rnd.nextInt(3);
                        for (int i = 0; i < inner; i++) {
                            sameTrace(flat, shared, cf, cs, 1000003L * tid + session, 40 + rnd.nextInt(120), "worker " + tid + ", session " + session);
                            session++;
                            if (i + 1 < inner) {
                                flat.reset();
                                shared.reset();
                                cf.endSession();
                                cs.endSession();
                            }
                        }
                        sessions.addAndGet(inner);
                    }
                } catch (Throwable e) {
                    failures.add(e);
                }
            }, "level-worker-" + t);
        }
        publisher.start();
        for (Thread th : threads) {
            th.start();
        }
        publisher.join();
        for (Thread th : threads) {
            th.join();
        }
        if (!failures.isEmpty()) {
            Throwable f = failures.peek();
            throw new AssertionError(failures.size() + " failures, first: " + f, f);
        }
        assertEquals(publications + 1, all.size());
        for (Published p : all) {
            assertEquals(p.digest, digest(p.store), "a snapshot changed after its publication");
        }
        assertTrue(rebases.get() >= workers, "rebases: " + rebases.get());
        assertTrue(sessions.get() > rebases.get());
    }
}

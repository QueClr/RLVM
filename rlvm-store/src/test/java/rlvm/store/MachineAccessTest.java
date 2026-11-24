package rlvm.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

import rlvm.store.Model.M;

/**
 * What the machine reads from the store (ISA.md 12, R1 and R1'): the one-dispatch node accessor
 * ({@link Terms#regionOf} + {@link Node#wordOf}), the writer of the reserved {@code c} word of
 * export LAM / PI nodes ({@link TermStore#setBinderHint}), and the first term of a unit's segment.
 */
class MachineAccessTest {

    private static Model.Gen gen(int size) {
        Model.Gen g = new Model.Gen();
        g.size = size;
        return g;
    }

    private static void assertSameWords(Terms t, int id) {
        int[] r = t.regionOf(id);
        int w = Node.wordOf(id);
        assertEquals(t.header(id), r[w + Node.W_HEADER]);
        assertEquals(t.kind(id), Node.kind(r[w]));
        assertEquals(t.flags(id), Node.flags(r[w]));
        assertEquals(t.looseRangeField(id), Node.looseRange(r[w]));
        assertEquals(t.a(id), r[w + Node.W_A]);
        assertEquals(t.b(id), r[w + Node.W_B]);
        assertEquals(t.c(id), r[w + Node.W_C]);
        assertEquals(t.fvMask(id), (r[w + Node.W_MASK_LO] & 0xFFFFFFFFL) | ((long) r[w + Node.W_MASK_HI] << 32));
        assertEquals(t.depMax(id), r[w + Node.W_DEPMAX]);
        assertEquals(t.lpSet(id), r[w + Node.W_LPSET]);
        for (int k = 0; k < Node.WORDS; k++) {
            assertEquals(t.word(id, k), r[w + k]);
        }
    }

    @Test
    void oneDispatchAccessorAgreesWithTheFieldAccessorsInEveryRegion() {
        Model fx = new Model();
        fx.declareLiteralPrerequisites(true);
        Random rnd = new Random(11);
        List<M> shared = fx.random(rnd, gen(400));
        IdentityHashMap<M, Integer> memo = new IdentityHashMap<>();
        int[] sharedIds = new int[shared.size()];
        for (int i = 0; i < sharedIds.length; i++) {
            sharedIds[i] = fx.toShared(shared.get(i), memo);
        }
        fx.builder.finish();
        Overlay o = new Overlay(fx.store, fx.store.lists(), 1L << 26);
        Terms t = new Terms(fx.store.terms(), o);
        for (int id : sharedIds) {
            assertSame(fx.store.terms().exportArray(), t.regionOf(id));
            assertSameWords(t, id);
        }
        List<M> local = fx.random(rnd, gen(600));
        IdentityHashMap<M, Integer> omemo = new IdentityHashMap<>();
        int[] ids = new int[local.size()];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = fx.toOverlay(o, local.get(i), omemo);
        }
        for (int id : ids) {
            assertSameWords(t, id);
            if (Node.isOverlay(id)) {
                assertSame(o.array(), t.regionOf(id));
            }
        }
        // an application over a shared function and an overlay argument: both children through one accessor
        int mixed = o.mkApp(sharedIds[sharedIds.length - 1], o.mkBVar(3));
        assertSameWords(t, mixed);
        assertSameWords(t, t.a(mixed));
        assertSameWords(t, t.b(mixed));
        assertTrue((t.regionOf(mixed)[Node.wordOf(mixed)] & Node.F_SHARED) != 0, "hash-consed overlay nodes are SHARED");
    }

    @Test
    void regionOfFollowsTheOverlayWhenItGrowsAndWhenItIsReset() {
        Model fx = new Model();
        fx.declareLiteralPrerequisites(false);
        fx.builder.finish();
        Overlay o = new Overlay(fx.store, fx.store.lists(), 1L << 26);
        Terms t = new Terms(fx.store.terms(), o);
        int first = o.mkBVar(0);
        int[] before = t.regionOf(first);
        int cur = first;
        for (int i = 1; i < 40_000; i++) {
            cur = o.mkApp(cur, o.mkBVar(i & 7));
        }
        int[] after = t.regionOf(first);
        assertNotSame(before, after, "the overlay grew: a held array is stale, regionOf is current");
        assertSame(o.array(), after);
        assertEquals(Kind.BVAR, Node.kind(after[Node.wordOf(first)]));
        assertEquals(Kind.APP, Node.kind(t.regionOf(cur)[Node.wordOf(cur)]));
        assertEquals(first, t.regionOf(cur)[Node.wordOf(cur) + Node.W_C], "c of an APP is the head of its spine");
        o.reset();
        int again = o.mkBVar(5);
        assertSame(o.array(), t.regionOf(again));
        assertEquals(5, t.regionOf(again)[Node.wordOf(again) + Node.W_A]);
    }

    @Test
    void binderHintIsWrittenOnlyIntoExportBindersAndChangesNothingElse() {
        Model fx = new Model();
        fx.declareLiteralPrerequisites(true);
        StoreBuilder b = fx.builder;
        b.beginUnit(DeclKind.DEFN);
        int cid = b.reserve(DeclKind.DEFN, fx.name("hinted"), 0);
        int sort = b.sort(fx.levelIds[0]);
        int v0 = b.bvar(0);
        int lam = b.lam(sort, v0);
        int pi = b.pi(sort, sort);
        int app = b.app(lam, sort);
        int let = b.let(pi, lam, v0);
        int nat = b.natSmall(7);
        b.complete(cid, pi, lam, 1);
        b.endUnit();
        b.finish();
        TermStore ts = fx.store.terms();
        Overlay o = new Overlay(fx.store, fx.store.lists(), 1L << 22);
        Terms t = new Terms(ts, o);
        long lamDigest = fx.digest(t, lam);
        long letDigest = fx.digest(t, let);
        int[] snapshot = ts.exportArray().clone();

        assertEquals(0, t.c(lam), "the reserved word starts as 0 = no hint");
        assertEquals(0, t.c(pi));
        ts.setBinderHint(lam, 0x1234567);
        ts.setBinderHint(pi, -5);
        assertEquals(0x1234567, t.c(lam));
        assertEquals(0x1234567, t.regionOf(lam)[Node.wordOf(lam) + Node.W_C]);
        assertEquals(-5, t.c(pi));
        // every other kind, and everything that is not an export id, is left alone
        ts.setBinderHint(app, 99);
        ts.setBinderHint(let, 99);
        ts.setBinderHint(nat, 99);
        ts.setBinderHint(sort, 99);
        ts.setBinderHint(v0, 99);
        ts.setBinderHint(0, 99);
        ts.setBinderHint(-1, 99);
        ts.setBinderHint(ts.exportEnd() + 5, 99);
        ts.setBinderHint(Integer.MAX_VALUE, 99);
        int olam = o.mkLam(o.mkSort(fx.levelIds[0]), o.mkBVar(0));
        ts.setBinderHint(olam, 99);
        assertEquals(0, t.c(olam), "overlay binders have no hint word to write");
        int[] now = ts.exportArray();
        for (int i = 0; i < snapshot.length; i++) {
            boolean hintWord = i == Node.wordOf(lam) + Node.W_C || i == Node.wordOf(pi) + Node.W_C;
            if (!hintWord) {
                assertEquals(snapshot[i], now[i], "word " + i);
            }
        }
        // nothing the store computes depends on the hint: structure, summaries, equivalence, hash-consing
        assertEquals(lamDigest, fx.digest(t, lam));
        assertEquals(letDigest, fx.digest(t, let));
        TermOps ops = new TermOps(t, fx.store.lists(), fx.store.levelInfo());
        assertTrue(ops.eqv(lam, olam), "a hinted export binder is still equivalent to its overlay twin");
        int o1 = o.mkApp(lam, sort);
        ts.setBinderHint(lam, 77);
        assertEquals(o1, o.mkApp(lam, sort), "hash-consing over a hinted child is unaffected");
        assertEquals(t.fvMask(lam), 0L);
        assertEquals(0, t.looseRange(lam));
        // the hint can be withdrawn
        ts.setBinderHint(lam, 0);
        assertEquals(0, t.c(lam));
    }

    @Test
    void binderHintSurvivesOrIsLostButNeverCorruptsWhileTheRegionGrows() throws Exception {
        // The loader keeps appending (and reallocating the region) while another thread writes hints
        // into binders of published units: a hint may be lost, every other word stays what it was.
        Model fx = new Model(0);
        fx.declareLiteralPrerequisites(false);
        StoreBuilder b = fx.builder;
        TermStore ts = fx.store.terms();
        b.beginUnit(DeclKind.AXIOM);
        int cid = b.reserve(DeclKind.AXIOM, fx.name("grow.first"), 0);
        int sort = b.sort(fx.levelIds[0]);
        int[] binders = new int[64];
        for (int i = 0; i < binders.length; i++) {
            binders[i] = (i & 1) == 0 ? b.lam(sort, sort) : b.pi(sort, sort);
        }
        b.complete(cid, binders[1], 0, 0);
        b.endUnit();
        b.publish();
        final int[] expectA = new int[binders.length];
        final int[] expectB = new int[binders.length];
        Terms t0 = new Terms(ts, null);
        for (int i = 0; i < binders.length; i++) {
            expectA[i] = t0.a(binders[i]);
            expectB[i] = t0.b(binders[i]);
        }
        final java.util.concurrent.atomic.AtomicBoolean stop = new java.util.concurrent.atomic.AtomicBoolean();
        Thread hinter = new Thread(() -> {
            int round = 1;
            while (!stop.get()) {
                for (int i = 0; i < binders.length; i++) {
                    ts.setBinderHint(binders[i], round);
                }
                round++;
            }
        });
        hinter.start();
        int startLength = ts.exportArray().length;
        b.beginUnit(DeclKind.AXIOM);
        int cid2 = b.reserve(DeclKind.AXIOM, fx.name("grow.second"), 0);
        int cur = b.sort(fx.levelIds[0]);
        for (int i = 0; i < 300_000; i++) {
            cur = b.app(cur, sort);
        }
        b.complete(cid2, cur, 0, 0);
        b.endUnit();
        stop.set(true);
        hinter.join();
        b.finish();
        assertNotEquals(startLength, ts.exportArray().length, "the region was reallocated under the hint writer");
        Terms t = new Terms(ts, null);
        for (int i = 0; i < binders.length; i++) {
            assertEquals((i & 1) == 0 ? Kind.LAM : Kind.PI, t.kind(binders[i]));
            assertEquals(expectA[i], t.a(binders[i]));
            assertEquals(expectB[i], t.b(binders[i]));
            assertEquals(0L, t.fvMask(binders[i]));
        }
        assertEquals(300_000, t.spineLength(cur));
        // after the growth a hint written again is read back
        ts.setBinderHint(binders[3], 4242);
        assertEquals(4242, t.c(binders[3]));
    }

    @Test
    void unitFirstTermSeparatesTheTermsOfAUnitFromEarlierOnes() {
        Model fx = new Model();
        fx.declareLiteralPrerequisites(false);
        StoreBuilder b = fx.builder;
        DeclTable d = fx.store.decls();
        int prevEnd = fx.store.terms().exportEnd();
        int[] units = new int[5];
        int[] firstNode = new int[5];
        int[] lastNode = new int[5];
        for (int u = 0; u < units.length; u++) {
            units[u] = b.beginUnit(DeclKind.AXIOM);
            int cid = b.reserve(DeclKind.AXIOM, fx.name("seg.u" + u), 0);
            int s = b.sort(fx.levelIds[0]);
            firstNode[u] = s;
            int cur = s;
            for (int i = 0; i <= u; i++) {
                cur = b.pi(cur, s);
            }
            lastNode[u] = cur;
            b.complete(cid, cur, 0, 0);
            b.endUnit();
        }
        b.finish();
        for (int u = 0; u < units.length; u++) {
            assertEquals(firstNode[u], d.unitFirstTerm(units[u]));
            assertTrue(d.unitFirstTerm(units[u]) >= prevEnd);
            if (u > 0) {
                assertTrue(lastNode[u - 1] < d.unitFirstTerm(units[u]), "earlier units lie below");
                assertEquals(lastNode[u - 1] + 1, d.unitFirstTerm(units[u]), "segments are contiguous");
            }
        }
        Terms t = new Terms(fx.store.terms(), null);
        // a node referenced twice inside its own segment is SHARED; a declaration root carries BODY
        assertTrue((t.flags(firstNode[2]) & Node.F_SHARED) != 0);
        assertTrue((t.flags(lastNode[2]) & Node.F_BODY) != 0);
    }
}

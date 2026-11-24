package rlvm.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.Test;

import rlvm.store.Model.M;

/**
 * WP-A3: proof blocks. Round trips (write, decode), promotion, the block table, and the sharing
 * flags of RECONCILIATION.md section 3 against a naive referrer count.
 */
class ProofBlocksTest {

    private static Model.Gen gen(Random rnd, int size) {
        Model.Gen g = new Model.Gen();
        g.size = size;
        g.bigVars = rnd.nextInt(4) == 0;
        return g;
    }

    /** A random file: definitions (all shared) and theorems (type shared, value in a block), with cross references. */
    private static final class Corpus {
        final Model fx = new Model();
        final Segs segs = new Segs(fx);
        final List<Segs.Seg> all = new ArrayList<>();
        final List<M> earlier = new ArrayList<>();
        final List<M> values = new ArrayList<>();
        final List<M> types = new ArrayList<>();

        Corpus(long seed, int decls, int maxSize) {
            this(seed, decls, maxSize, true);
        }

        /** @param finish false: the load stays open (records can still be promoted); the caller finishes */
        Corpus(long seed, int decls, int maxSize, boolean finish) {
            Random rnd = new Random(seed);
            if (seed % 2 == 0) {
                fx.declareLiteralPrerequisites(true);
            }
            for (int d = 0; d < decls; d++) {
                List<M> typeNodes = new ArrayList<>(fx.random(rnd, gen(rnd, 4 + rnd.nextInt(10))));
                List<M> valueNodes = new ArrayList<>(fx.random(rnd, gen(rnd, 6 + rnd.nextInt(maxSize))));
                // cross references: to nodes of earlier declarations (shared or in blocks) and to the type
                for (int i = 0; i < 6 && !earlier.isEmpty(); i++) {
                    M old = earlier.get(rnd.nextInt(earlier.size()));
                    valueNodes.add(Model.app(valueNodes.get(rnd.nextInt(valueNodes.size())), old));
                }
                for (int i = 0; i < 3; i++) {
                    valueNodes.add(Model.app(valueNodes.get(valueNodes.size() - 1),
                            typeNodes.get(rnd.nextInt(typeNodes.size()))));
                }
                M type = typeNodes.get(typeNodes.size() - 1);
                M value = valueNodes.get(valueNodes.size() - 1);
                // interleave: some value records come before the type's records, as in real exports
                List<M> nodes = new ArrayList<>();
                int cut = rnd.nextInt(valueNodes.size());
                // a prefix of the value records must not reference type records: keep the cross references last
                cut = Math.min(cut, valueNodes.size() - 3);
                nodes.addAll(valueNodes.subList(0, cut));
                nodes.addAll(typeNodes);
                nodes.addAll(valueNodes.subList(cut, valueNodes.size()));
                boolean thm = rnd.nextInt(4) != 0;
                Segs.Seg s = segs.write(thm ? DeclKind.THM : DeclKind.DEFN, fx.name("decl" + d), 0, nodes, type, value);
                all.add(s);
                earlier.addAll(nodes);
                values.add(value);
                types.add(type);
            }
            if (finish) {
                fx.builder.finish();
            } else {
                fx.builder.publish();
            }
        }
    }

    @Test
    void roundTripOfRandomFiles() {
        int unresolvedBlocks = 0;
        int resolvedBlocks = 0;
        for (int seed = 0; seed < 60; seed++) {
            Corpus c = new Corpus(seed, 12, 50);
            Model fx = c.fx;
            Overlay o = new Overlay(fx.store, fx.store.lists(), 1L << 24);
            Terms t = new Terms(fx.store.terms(), o);
            DeclTable d = fx.store.decls();
            ProofBlocks pb = fx.store.blocks();
            Map<Integer, Long> memo = new HashMap<>();
            int records = 0;
            for (int i = 0; i < c.all.size(); i++) {
                Segs.Seg s = c.all.get(i);
                assertEquals(fx.digest(c.types.get(i)), fx.digest(t, d.type(s.cid), memo), "type of decl " + i);
                int v = d.value(s.cid);
                int root = v > 0 ? v : o.blockRoot(-v - 1);
                assertEquals(fx.digest(c.values.get(i)), fx.digest(t, root, memo), "seed " + seed + " value of decl " + i);
                if (v < 0) {
                    Checks.clear();
                    Checks.checkNode(fx, t, root, c.values.get(i), "seed " + seed + " root of block " + (-v - 1));
                    assertTrue((t.header(root) & Node.F_BODY) != 0, "a block root is flagged BODY");
                }
                if (s.block >= 0) {
                    // the block's "something was unresolved when it was written" flag, against the model:
                    // an unresolved record of the block, or a reference to an unresolved shared term
                    java.util.IdentityHashMap<M, Integer> inSegment = new java.util.IdentityHashMap<>();
                    for (int k = 0; k < s.nodes.size(); k++) {
                        inSegment.put(s.nodes.get(k), k);
                    }
                    boolean expectUnresolved = false;
                    for (int k = 0; k < s.nodes.size(); k++) {
                        if (s.shared[k]) {
                            continue;
                        }
                        M m = s.nodes.get(k);
                        if (m.kind == Kind.CONST || m.kind == Kind.NATLIT || m.kind == Kind.STRLIT) {
                            expectUnresolved |= fx.depMax(m) == Node.DEP_UNRESOLVED;
                        } else if (m.kind == Kind.PROJ) {
                            expectUnresolved |= fx.store.names().constOf(m.x) <= 0;
                        }
                        for (M ch : new M[] {m.a, m.b, m.c}) {
                            if (ch != null) {
                                Integer at = inSegment.get(ch);
                                boolean sharedChild = at == null || s.shared[at];
                                expectUnresolved |= sharedChild && fx.depMax(ch) == Node.DEP_UNRESOLVED;
                            }
                        }
                    }
                    assertEquals(expectUnresolved, pb.hasUnresolved(s.block), "seed " + seed + " block " + s.block);
                    unresolvedBlocks += expectUnresolved ? 1 : 0;
                    resolvedBlocks += expectUnresolved ? 0 : 1;
                    if (v < 0 && !pb.hasUnresolved(s.block)) {
                        assertTrue(t.depMax(root) != Node.DEP_UNRESOLVED, "a clean block decodes to a resolved root");
                        assertEquals(0, t.header(root) & Node.F_UNRESOLVED);
                    }
                    assertEquals(records, pb.firstRecord(s.block));
                    for (int r = 0; r < pb.nodeCount(s.block); r++) {
                        assertEquals(s.block, pb.blockOfRecord(records + r));
                    }
                    records += pb.nodeCount(s.block);
                }
            }
            assertEquals(records, pb.recordCount());
            assertThrows(IndexOutOfBoundsException.class, () -> pb.blockOfRecord(pb.recordCount()));
            // a second session decodes to the same structure
            o.reset();
            for (int i = 0; i < c.all.size(); i++) {
                int v = d.value(c.all.get(i).cid);
                if (v < 0) {
                    assertEquals(fx.digest(c.values.get(i)), fx.digest(t, o.blockRoot(-v - 1)));
                }
            }
        }
        assertTrue(unresolvedBlocks > 20 && resolvedBlocks > 20,
                "both kinds of block occur: " + unresolvedBlocks + " unresolved, " + resolvedBlocks + " clean");
    }

    @Test
    void promotionCopiesExactlyTheReachableRecords() {
        for (int seed = 0; seed < 60; seed++) {
            promotionRounds(seed, new Corpus(100 + seed, 6, 80, false), 60);
        }
    }

    /**
     * Promotion over many blocks and records: the memo spans several of its chunks (4,096 records
     * each), the coarse record-to-block index several entries (512 records each), and the walk seeks
     * into groups of 16 records of many blocks in arbitrary order.
     */
    @Test
    void promotionAcrossManyBlocksAndRecords() {
        for (int seed = 0; seed < 4; seed++) {
            Corpus c = new Corpus(900 + seed, 150, 300, false);
            assertTrue(c.fx.store.blocks().recordCount() > 3 * 4096, "records: " + c.fx.store.blocks().recordCount());
            assertTrue(c.fx.store.blocks().count() > 50);
            promotionRounds(seed, c, 1500);
        }
    }

    private static void promotionRounds(int seed, Corpus c, int rounds) {
        {
            Model fx = c.fx;
            Random rnd = new Random(seed);
            ProofBlocks pb = fx.store.blocks();
            Terms t = new Terms(fx.store.terms(), null);
            List<M> inBlocks = new ArrayList<>(c.segs.record.keySet());
            inBlocks.sort((x, y) -> Integer.compare(c.segs.record.get(x), c.segs.record.get(y)));
            // the loader's block lookup (coarse index) agrees with the binary search over all blocks
            for (int r = 0; r < pb.recordCount(); r++) {
                assertEquals(pb.blockOfRecord(r), pb.blockOfRecordLoader(r), "block of record " + r);
            }
            assertThrows(IndexOutOfBoundsException.class, () -> pb.blockOfRecordLoader(pb.recordCount()));
            assertThrows(IndexOutOfBoundsException.class, () -> pb.blockOfRecordLoader(-1));
            for (int round = 0; round < rounds; round++) {
                M m = inBlocks.get(rnd.nextInt(inBlocks.size()));
                int rec = c.segs.record.get(m);
                int before = fx.store.terms().exportEnd();
                int promotedBefore = pb.promotedCount();
                // exactly the reachable block records that were not promoted before are copied
                int expected = countNew(c, m, new IdentityHashMap<>());
                int id = fx.builder.promote(rec);
                t.refresh();
                assertEquals(fx.digest(m), fx.digest(t, id), "seed " + seed + " round " + round);
                Checks.clear();
                fx.clearMemos();
                Checks.checkNode(fx, t, id, m, "promoted node");
                assertTrue((t.header(id) & Node.F_SHARED) != 0, "promoted nodes are SHARED");
                assertEquals(id, fx.builder.promote(rec), "promotion is memoised");
                assertEquals(id, pb.promotedId(rec));
                assertEquals(expected, fx.store.terms().exportEnd() - before, "nodes copied");
                assertEquals(expected, pb.promotedCount() - promotedBefore);
            }
            // the end of the load ends promotion and keeps the count
            int promoted = pb.promotedCount();
            int someRecord = c.segs.record.get(inBlocks.get(0));
            fx.builder.finish();
            assertTrue(pb.loaderStateReleased());
            assertEquals(promoted, pb.promotedCount());
            assertThrows(IllegalStateException.class, () -> fx.builder.promote(someRecord));
        }
    }

    /** Number of records reachable from {@code m} inside its block that are not promoted yet. */
    private static int countNew(Corpus c, M m, IdentityHashMap<M, Boolean> seen) {
        ProofBlocks pb = c.fx.store.blocks();
        int rec = c.segs.record.get(m);
        if (pb.promotedId(rec) != 0 || seen.put(m, true) != null) {
            return 0;
        }
        int n = 1;
        for (M ch : new M[] {m.a, m.b, m.c}) {
            Integer chRec = ch == null ? null : c.segs.record.get(ch);
            if (chRec != null && pb.blockOfRecord(chRec) == pb.blockOfRecord(rec)) {
                n += countNew(c, ch, seen);
            }
        }
        return n;
    }

    @Test
    void sharingFlagsMatchANaiveReferrerCount() {
        for (int seed = 0; seed < 150; seed++) {
            Random rnd = new Random(500 + seed);
            Model fx = new Model();
            fx.declareLiteralPrerequisites(true);
            Segs segs = new Segs(fx);
            // an earlier unit whose nodes must not be touched by later referrers
            List<M> old = fx.random(rnd, gen(rnd, 12));
            Segs.Seg oldSeg = segs.write(DeclKind.DEFN, fx.name("old"), 0, old, old.get(old.size() - 1), old.get(old.size() - 1));
            Terms t = new Terms(fx.store.terms(), null);
            int[] oldHeaders = new int[old.size()];
            for (int i = 0; i < old.size(); i++) {
                oldHeaders[i] = t.header(oldSeg.at[i]);
            }
            List<M> typeNodes = fx.random(rnd, gen(rnd, 5 + rnd.nextInt(25)));
            List<M> valueNodes = new ArrayList<>(fx.random(rnd, gen(rnd, 10 + rnd.nextInt(60))));
            for (int i = 0; i < 5; i++) {
                valueNodes.add(Model.app(valueNodes.get(rnd.nextInt(valueNodes.size())), typeNodes.get(rnd.nextInt(typeNodes.size()))));
                valueNodes.add(Model.lam(old.get(rnd.nextInt(old.size())), valueNodes.get(valueNodes.size() - 1)));
            }
            List<M> nodes = new ArrayList<>(typeNodes);
            nodes.addAll(valueNodes);
            M type = typeNodes.get(typeNodes.size() - 1);
            M value = valueNodes.get(valueNodes.size() - 1);
            boolean thm = seed % 3 != 0;
            Segs.Seg s = segs.write(thm ? DeclKind.THM : DeclKind.DEFN, fx.name("x"), 0, nodes, type, value);
            fx.builder.finish();
            t.refresh();
            assertEquals(s.firstTerm, fx.store.decls().unitFirstTerm(s.unit));
            assertTrue(oldSeg.at[old.size() - 1] < s.firstTerm, "the earlier unit's nodes lie below unitFirstTerm");
            for (int i = 0; i < old.size(); i++) {
                assertEquals(oldHeaders[i], t.header(oldSeg.at[i]), "a published node was modified");
            }
            Overlay o = new Overlay(fx.store, fx.store.lists(), 1L << 24);
            Terms to = new Terms(fx.store.terms(), o);
            int base = 0;
            if (s.block >= 0) {
                int root = o.blockRoot(s.block);
                int rootLocal = fx.store.blocks().rootRecord(s.block);
                base = (root & Node.INDEX_MASK) - rootLocal;
            }
            // naive: every reference, in writing order
            int n = nodes.size();
            IdentityHashMap<M, Integer> index = new IdentityHashMap<>();
            for (int i = 0; i < n; i++) {
                index.put(nodes.get(i), i);
            }
            int[] refs = new int[n];
            boolean[] body = new boolean[n];
            boolean[] inv = new boolean[n];
            int[] ids = new int[n];
            for (int i = 0; i < n; i++) {
                ids[i] = s.shared[i] ? s.at[i] : (base + s.at[i]) | Node.OVERLAY_BIT;
            }
            for (int i = 0; i < n; i++) {
                M m = nodes.get(i);
                M[] kids = {m.a, m.b, m.c};
                for (int slot = 0; slot < 3; slot++) {
                    M ch = kids[slot];
                    if (ch == null || !index.containsKey(ch)) {
                        continue;
                    }
                    int j = index.get(ch);
                    boolean bodySlot = (slot == 1 && (m.kind == Kind.LAM || m.kind == Kind.PI)) || (slot == 2 && m.kind == Kind.LET);
                    if (refs[j] == 0 && !bodySlot && s.shared[j] == s.shared[i]) {
                        // first referrer in the same part: INVARIANT iff the masks differ
                        inv[j] = to.fvMask(ids[j]) != to.fvMask(ids[i]);
                    }
                    refs[j]++;
                    body[j] |= bodySlot;
                }
            }
            body[index.get(type)] = true;
            body[index.get(value)] = true;
            for (int i = 0; i < n; i++) {
                int h = to.header(ids[i]);
                String where = "seed " + seed + " node " + i + (s.shared[i] ? " (shared)" : " (block)");
                assertEquals(refs[i] >= 2, (h & Node.F_SHARED) != 0, where + ": SHARED with " + refs[i] + " referrers");
                assertEquals(body[i], (h & Node.F_BODY) != 0, where + ": BODY");
                if (refs[i] == 1) {
                    assertEquals(inv[i], (h & Node.F_INVARIANT) != 0, where + ": INVARIANT");
                }
                if (refs[i] == 0) {
                    assertEquals(0, h & Node.F_INVARIANT, where + ": INVARIANT without a referrer");
                }
            }
        }
    }

    @Test
    void blockTableAndStorage() {
        Model fx = new Model();
        fx.declareLiteralPrerequisites(true);
        ProofBlocks pb = fx.store.blocks();
        ProofBlocks.Writer w = fx.builder.blockWriter();
        int shared = fx.builder.sort(fx.levelIds[1]);
        // an empty block, then blocks around the chunk limit, then one larger than a chunk
        int empty = w.finish(-1);
        assertEquals(0, pb.nodeCount(empty));
        assertEquals(-1, pb.rootRecord(empty));
        int total = 0;
        List<Integer> blocks = new ArrayList<>();
        List<Integer> sizes = new ArrayList<>();
        int[] counts = {1, 10, 20000, 30000, 150000, 3, 400000, 7, 1};
        for (int n : counts) {
            w.bvar(0);
            for (int i = 1; i < n; i++) {
                if (i % 3 == 0) {
                    w.app(ProofBlocks.Writer.local(1), ProofBlocks.Writer.shared(shared));
                } else {
                    w.app(ProofBlocks.Writer.local(1), ProofBlocks.Writer.local(i));
                }
            }
            assertEquals(n, w.size());
            int b = w.finish(n - 1);
            blocks.add(b);
            sizes.add(n);
            assertEquals(total, pb.firstRecord(b));
            total += n;
            assertTrue(pb.byteLength(b) <= 6L * n, "at most 6 bytes per record: " + pb.byteLength(b) + " for " + n);
        }
        assertEquals(counts.length + 1, pb.count());
        assertEquals(total, pb.recordCount());
        fx.builder.publish();
        Overlay o = new Overlay(fx.store, fx.store.lists(), 1L << 28);
        Terms t = new Terms(fx.store.terms(), o);
        assertEquals(0, o.blockRoot(empty));
        for (int i = 0; i < blocks.size(); i++) {
            int root = o.blockRoot(blocks.get(i));
            int n = sizes.get(i);
            assertEquals(n == 1 ? 0 : n - 1, t.spineLength(root), "block " + i);
            assertEquals(1, t.looseRange(root));
            assertEquals(Kind.BVAR, t.kind(t.head(root)));
        }
        // promotion out of the middle of the big block
        int big = blocks.get(6);
        int rec = pb.firstRecord(big) + 123456;
        int before = fx.store.terms().exportEnd();
        int id = fx.builder.promote(rec);
        assertEquals(123457, fx.store.terms().exportEnd() - before);
        t.refresh();
        assertEquals(123456, t.spineLength(id));
        // aborting a block leaves no trace
        w.bvar(1);
        w.abort();
        assertEquals(0, w.size());
        assertEquals(counts.length + 1, pb.count());
        assertThrows(IllegalArgumentException.class, () -> w.app(ProofBlocks.Writer.local(1), ProofBlocks.Writer.local(1)));
        w.abort();
        assertThrows(IllegalArgumentException.class, () -> w.app(ProofBlocks.Writer.shared(1 << 27), ProofBlocks.Writer.shared(shared)));
        w.abort();
        assertNotEquals(0, pb.bytes());
        assertFalse(pb.count() == 0);
        // the end of the load: what was decoded stays decodable, nothing more is promoted or written
        fx.builder.finish();
        o.reset();
        assertEquals(399_999, t.spineLength(o.blockRoot(big)));
        assertThrows(IllegalStateException.class, () -> fx.builder.promote(rec));
        w.bvar(0);
        assertThrows(IllegalStateException.class, () -> w.finish(0));
    }

    @Test
    void recordEncodingsRoundTrip() {
        Model fx = new Model();
        ProofBlocks.Writer w = fx.builder.blockWriter();
        LitPool lits = fx.store.lits();
        int oneLimb = lits.addNat(new long[] {77}, 0, 1);
        long[] values = {0, 1, 127, 128, 16383, 16384, 0xFFFFFFFFL, 0x100000000L, Long.MAX_VALUE, -1L, Long.MIN_VALUE};
        int[] indices = {0, 1, 14, 15, 16, 127, 128, 70000, Integer.MAX_VALUE};
        for (long v : values) {
            w.natSmall(v);
        }
        for (int i : indices) {
            w.bvar(i);
        }
        w.natBig(fx.bigNat);
        w.natBig(oneLimb);
        w.str(fx.strLit2);
        w.sort(fx.levelIds[5]);
        w.constant(fx.builder.bindName(fx.constNames[1]), fx.levelLists[1], fx.constNames[1]);
        w.constant(fx.builder.bindName(fx.constNames[1]), 0, fx.constNames[1]);
        w.constant(fx.builder.bindName(fx.undeclaredName), 0, fx.undeclaredName);
        w.proj(fx.builder.bindName(fx.structName), 300, ProofBlocks.Writer.local(1));
        w.proj(fx.builder.bindName(fx.undeclaredName), 0, ProofBlocks.Writer.local(1));
        int n = w.size();
        int b = w.finish(n - 1);
        fx.builder.finish();
        Overlay o = new Overlay(fx.store, fx.store.lists(), 1L << 20);
        Terms t = new Terms(fx.store.terms(), o);
        int root = o.blockRoot(b);
        int id = (root & Node.INDEX_MASK) - (n - 1) | Node.OVERLAY_BIT;
        for (long v : values) {
            assertEquals(Kind.NATLIT, t.kind(id));
            assertFalse(t.isNatBig(id));
            assertEquals(v, t.natSmall(id));
            assertEquals(Node.DEP_UNRESOLVED, t.depMax(id), "Nat is not declared in this file");
            id++;
        }
        for (int i : indices) {
            assertEquals(Kind.BVAR, t.kind(id));
            assertEquals(i, t.a(id));
            id++;
        }
        assertTrue(t.isNatBig(id));
        assertEquals(fx.bigNat, t.a(id++));
        assertFalse(t.isNatBig(id), "a pooled value below 2^64 is stored in the node");
        assertEquals(77, t.natSmall(id++));
        assertEquals(Kind.STRLIT, t.kind(id));
        assertEquals(fx.strLit2, t.a(id));
        assertEquals(5, t.b(id++), "number of scalars");
        assertEquals(fx.levelIds[5], t.a(id++));
        assertEquals(fx.constCids[1], t.a(id));
        assertEquals(fx.levelLists[1], t.b(id));
        assertEquals(fx.constNames[1], t.c(id));
        assertEquals(fx.store.decls().rank(fx.constCids[1]), t.depMax(id));
        assertEquals(0, t.header(id++) & Node.F_UNRESOLVED);
        assertTrue((t.header(id) & Node.F_UNRESOLVED) != 0, "wrong number of levels");
        assertEquals(Node.DEP_UNRESOLVED, t.depMax(id++));
        assertEquals(~fx.undeclaredName, t.a(id));
        assertTrue((t.header(id++) & Node.F_UNRESOLVED) != 0);
        assertEquals(fx.constCids[3], t.c(id));
        assertEquals(300, t.b(id++));
        assertEquals(~fx.undeclaredName, t.c(id));
        assertEquals(root, id);
    }
}

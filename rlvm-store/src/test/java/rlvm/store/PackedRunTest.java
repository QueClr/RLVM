package rlvm.store;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Random;

import org.junit.jupiter.api.Test;

/**
 * {@link ProofBlocks.Writer#run}: a run of APP / LAM / PI / LET records handed over in packed form
 * (LP2, scanner-packed runs) must leave the store exactly as the same records written one by one:
 * every byte of the block (through its decoded nodes), the block row, the sharing flags the block's
 * references set in the open unit's shared nodes, the sparse record index (through promotion).
 */
class PackedRunTest {

    /** One record of the script: kind, and per child a shared id (&gt; 0) or -(block index + 1). */
    private static final class Rec {
        int kind;
        int[] child = new int[3];
        int leaf;
    }

    private static final int BY_RECORD = 0;
    private static final int DEFAULT_RUN = 1;
    private static final int WRITER_RUN = 2;

    /** A writer whose {@code run} is the interface default (record by record) over {@code w}. */
    private static ProofBlocks.Writer plain(ProofBlocks.Writer w) {
        return new ProofBlocks.Writer() {
            @Override
            public void bvar(int index) {
                w.bvar(index);
            }

            @Override
            public void sort(int level) {
                w.sort(level);
            }

            @Override
            public void constant(int cidOrNotName, int levelList, int name) {
                w.constant(cidOrNotName, levelList, name);
            }

            @Override
            public void app(int refFn, int refArg) {
                w.app(refFn, refArg);
            }

            @Override
            public void lam(int refDomain, int refBody) {
                w.lam(refDomain, refBody);
            }

            @Override
            public void pi(int refDomain, int refBody) {
                w.pi(refDomain, refBody);
            }

            @Override
            public void let(int refType, int refValue, int refBody) {
                w.let(refType, refValue, refBody);
            }

            @Override
            public void proj(int cidOrNotName, int index, int refStruct) {
                w.proj(cidOrNotName, index, refStruct);
            }

            @Override
            public void natSmall(long unsignedValue) {
                w.natSmall(unsignedValue);
            }

            @Override
            public void natBig(int litIndex) {
                w.natBig(litIndex);
            }

            @Override
            public void str(int litIndex) {
                w.str(litIndex);
            }

            @Override
            public int size() {
                return w.size();
            }

            @Override
            public int finish(int rootLocal) {
                return w.finish(rootLocal);
            }

            @Override
            public void abort() {
                w.abort();
            }
        };
    }

    private static int structure(int kind) {
        return kind == Kind.LET ? 3 : kind == Kind.APP || kind == Kind.LAM || kind == Kind.PI ? 2 : 0;
    }

    /** The fixture store with one open theorem unit: some shared nodes of the unit, then the block of {@code script}. */
    private static Model write(Rec[] script, long seed, int mode) {
        Model fx = new Model();
        StoreBuilder b = fx.builder;
        b.beginUnit(DeclKind.THM);
        int cid = b.reserve(DeclKind.THM, fx.name("thm"), 0);
        // shared nodes of the open unit: the block's references set their sharing flags
        int s1 = b.bvar(0);
        int s2 = b.sort(fx.levelIds[1]);
        int s3 = b.app(s1, s2);
        int s4 = b.lam(s2, s3);
        int s5 = b.constant(fx.undeclaredName, 0); // unresolved: a reference to it marks the block
        assertEquals(sharedEnd(fx) - 1, s5);
        ProofBlocks.Writer w = b.blockWriter();
        ProofBlocks.Writer out = mode == DEFAULT_RUN ? plain(w) : w;
        Random rnd = new Random(seed * 31 + 7); // the same cuts and encodings for both run modes
        byte[] packed = new byte[64];
        int[] ext = new int[16];
        int i = 0;
        while (i < script.length) {
            Rec r = script[i];
            int nc = structure(r.kind);
            if (nc == 0) {
                if (r.kind == Kind.BVAR) {
                    out.bvar(r.leaf);
                } else {
                    out.sort(fx.levelIds[r.leaf]);
                }
                i++;
                continue;
            }
            if (mode == BY_RECORD) {
                int[] ref = new int[3];
                for (int c = 0; c < nc; c++) {
                    int v = r.child[c];
                    ref[c] = v > 0 ? ProofBlocks.Writer.shared(v) : ProofBlocks.Writer.local(i - (-v - 1));
                }
                switch (r.kind) {
                    case Kind.APP -> out.app(ref[0], ref[1]);
                    case Kind.LAM -> out.lam(ref[0], ref[1]);
                    case Kind.PI -> out.pi(ref[0], ref[1]);
                    default -> out.let(ref[0], ref[1], ref[2]);
                }
                i++;
                continue;
            }
            // a run: the structure records from here on, cut at random
            int end = i;
            while (end < script.length && structure(script[end].kind) != 0 && (end == i || rnd.nextInt(12) != 0)) {
                end++;
            }
            int p = 3; // an offset, to see that offsets are honoured
            int e = 2;
            int e0 = e;
            for (int j = i; j < end; j++) {
                Rec q = script[j];
                int kids = structure(q.kind);
                if (packed.length < p + 1 + 5 * kids) {
                    packed = Arrays.copyOf(packed, packed.length * 2);
                }
                if (ext.length < e + kids) {
                    ext = Arrays.copyOf(ext, ext.length * 2);
                }
                int headAt = p++;
                int head = q.kind;
                for (int c = 0; c < kids; c++) {
                    int v = q.child[c];
                    // a local child is pre-encoded or external; a shared child is always external
                    if (v > 0 || rnd.nextBoolean()) {
                        head |= 0x10 << c;
                        ext[e++] = v;
                    } else {
                        int enc = ProofBlocks.Writer.local(j - (-v - 1));
                        while ((enc & ~0x7F) != 0) {
                            packed[p++] = (byte) (enc | 0x80);
                            enc >>>= 7;
                        }
                        packed[p++] = (byte) enc;
                    }
                }
                packed[headAt] = (byte) head;
            }
            out.run(packed, 3, p - 3, end - i, ext, e0, e - e0);
            assertEquals(end, out.size());
            i = end;
        }
        int block = out.finish(script.length - 1);
        b.complete(cid, s4, -(block + 1), 0);
        b.endUnit();
        b.publish();
        return fx;
    }

    private static int sharedEnd(Model fx) {
        return fx.store.terms().exportEnd();
    }

    private static Rec[] script(Random rnd, int n, int sharedLo, int sharedHi) {
        Rec[] s = new Rec[n];
        for (int i = 0; i < n; i++) {
            Rec r = new Rec();
            int pick = i == 0 ? 0 : rnd.nextInt(10);
            r.kind = pick == 0 ? (rnd.nextBoolean() ? Kind.BVAR : Kind.SORT)
                    : pick < 6 ? Kind.APP : pick == 6 ? Kind.LAM : pick == 7 ? Kind.PI : Kind.LET;
            r.leaf = r.kind == Kind.BVAR ? rnd.nextInt(40) : rnd.nextInt(4);
            for (int c = 0; c < 3; c++) {
                if (i > 0 && rnd.nextInt(3) != 0) {
                    // an earlier record of the block: often a near one, sometimes far (multi-byte distance)
                    int back = rnd.nextBoolean() ? 1 + rnd.nextInt(Math.min(i, 4)) : 1 + rnd.nextInt(i);
                    r.child[c] = -(i - back + 1);
                } else {
                    r.child[c] = sharedLo + rnd.nextInt(sharedHi - sharedLo);
                }
            }
            s[i] = r;
        }
        return s;
    }

    private static void assertSame(Model a, Model b, String what) {
        TermStore ta = a.store.terms();
        TermStore tb = b.store.terms();
        assertEquals(ta.exportEnd(), tb.exportEnd(), what);
        assertArrayEquals(Arrays.copyOf(ta.exportArray(), ta.exportEnd() << 3), Arrays.copyOf(tb.exportArray(), tb.exportEnd() << 3),
                what + ": export region");
        ProofBlocks pa = a.store.blocks();
        ProofBlocks pb = b.store.blocks();
        assertEquals(pa.count(), pb.count(), what);
        assertEquals(pa.recordCount(), pb.recordCount(), what);
        for (int k = 0; k < pa.count(); k++) {
            assertEquals(pa.byteLength(k), pb.byteLength(k), what + ": bytes of block " + k);
            assertEquals(pa.nodeCount(k), pb.nodeCount(k), what);
            assertEquals(pa.rootRecord(k), pb.rootRecord(k), what);
            assertEquals(pa.firstRecord(k), pb.firstRecord(k), what);
            assertEquals(pa.hasUnresolved(k), pb.hasUnresolved(k), what + ": unresolved flag of block " + k);
            Overlay oa = new Overlay(a.store, a.store.lists(), 1L << 26);
            Overlay ob = new Overlay(b.store, b.store.lists(), 1L << 26);
            assertEquals(oa.blockRoot(k), ob.blockRoot(k), what);
            assertEquals(oa.nodeCount(), ob.nodeCount(), what);
            assertArrayEquals(Arrays.copyOf(oa.array(), oa.nodeCount() << 3), Arrays.copyOf(ob.array(), ob.nodeCount() << 3),
                    what + ": decoded block " + k);
        }
    }

    @Test
    void aPackedRunWritesWhatTheRecordsWrite() {
        int unresolved = 0;
        int records = 0;
        for (int seed = 0; seed < 300; seed++) {
            Random rnd = new Random(seed);
            Model probe = new Model();
            int lo = sharedEnd(probe); // the first shared id of the open unit (s1)
            // references reach the constants' type (an earlier unit) and s1 .. s4; a tenth of the scripts s5 too
            int hi = lo + (seed % 10 == 0 ? 5 : 4);
            int n = seed % 7 == 0 ? 400 + rnd.nextInt(600) : 1 + rnd.nextInt(70);
            Rec[] script = script(rnd, n, lo - 1, hi);
            Model byRecord = write(script, seed, BY_RECORD);
            Model byDefault = write(script, seed, DEFAULT_RUN);
            Model byWriter = write(script, seed, WRITER_RUN);
            assertSame(byRecord, byDefault, "seed " + seed + ", interface default");
            assertSame(byRecord, byWriter, "seed " + seed + ", writer");
            records += n;
            if (byRecord.store.blocks().hasUnresolved(0)) {
                unresolved++;
            }
            // the sparse record index: promote some records of the block in a later unit
            int[] promoted = new int[3];
            Model[] all = {byRecord, byDefault, byWriter};
            for (int m = 0; m < 3; m++) {
                Model fx = all[m];
                Random pr = new Random(seed);
                fx.builder.beginUnit(DeclKind.AXIOM);
                int cid = fx.builder.reserve(DeclKind.AXIOM, fx.name("later"), 0);
                int last = 0;
                for (int k = 0; k < 6; k++) {
                    last = fx.builder.promote(fx.store.blocks().firstRecord(0) + pr.nextInt(n));
                }
                promoted[m] = last;
                fx.builder.complete(cid, last, 0, 0);
                fx.builder.endUnit();
                fx.builder.finish();
            }
            assertEquals(promoted[0], promoted[1]);
            assertEquals(promoted[0], promoted[2]);
            assertSame(byRecord, byDefault, "seed " + seed + ", after promotion, interface default");
            assertSame(byRecord, byWriter, "seed " + seed + ", after promotion, writer");
        }
        assertTrue(unresolved > 5 && unresolved < 290, "blocks with an unresolved reference: " + unresolved);
        assertTrue(records > 20_000, "records " + records);
    }

    @Test
    void aCorruptRunIsAnError() {
        Model fx = new Model();
        fx.builder.beginUnit(DeclKind.THM);
        fx.builder.reserve(DeclKind.THM, fx.name("thm"), 0);
        int s1 = fx.builder.bvar(0);
        ProofBlocks.Writer w = fx.builder.blockWriter();
        w.bvar(1);
        int[] ext = {s1, s1};
        // too few bytes for the announced records
        assertThrows(IllegalArgumentException.class, () -> w.run(new byte[] {(byte) (Kind.APP | 0x30)}, 0, 1, 2, ext, 0, 2));
        w.abort();
        w.bvar(1);
        // an external count that the records do not use up
        assertThrows(IllegalArgumentException.class, () -> w.run(new byte[] {(byte) (Kind.APP | 0x10), 2}, 0, 2, 1, ext, 0, 2));
        w.abort();
        w.bvar(1);
        // a kind that is not a structure record
        assertThrows(IllegalArgumentException.class, () -> w.run(new byte[] {(byte) (Kind.BVAR | 0x10)}, 0, 1, 1, ext, 0, 1));
        w.abort();
        w.bvar(1);
        // an external local reference to a record that does not exist yet
        assertThrows(IllegalArgumentException.class, () -> w.run(new byte[] {(byte) (Kind.APP | 0x30)}, 0, 1, 1, new int[] {-2, s1}, 0, 2));
        w.abort();
        // a shared term that does not exist
        w.bvar(1);
        assertThrows(IllegalArgumentException.class,
                () -> w.run(new byte[] {(byte) (Kind.APP | 0x30)}, 0, 1, 1, new int[] {s1 + 100, s1}, 0, 2));
        w.abort();
        // and a well-formed one
        w.bvar(1);
        w.run(new byte[] {(byte) (Kind.APP | 0x30)}, 0, 1, 1, new int[] {-1, s1}, 0, 2);
        assertEquals(2, w.size());
    }
}

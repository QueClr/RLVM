package rlvm.machine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import rlvm.api.MachineErrors;
import rlvm.nat.Nat;
import rlvm.nat.NatStore;
import rlvm.store.LitPool;

/**
 * M1 acceptance: the literal bridge (ISA 2.9; RECONCILIATION section 2 "Literal identity"): the three
 * representations of a Nat literal and their boundaries, KLIT of every literal node form, content comparison
 * of two literal refs with different ids, the staleness check of NatStore ids, and the pair key with an
 * immediate (ISA 1.5).
 */
@Tag("fast")
class M1LitsTest {

    static Fixture f;
    static Machine m;

    @BeforeAll
    static void prelude() {
        f = Fixture.prelude0();
        m = f.m;
    }

    static BigInteger value(int v) {
        return Nat.toBigInteger(m.nats, Lits.natHandle(m, v));
    }

    static final BigInteger TWO = BigInteger.TWO;

    @Test
    void natHandlesMapToTheThreeRepresentations() {
        assertEquals(Layout.imm(0), Values.mkNat(m, 0));
        assertEquals(Layout.imm(Integer.MAX_VALUE), Values.mkNat(m, Integer.MAX_VALUE));
        assertEquals(-1, Values.mkNat(m, Integer.MAX_VALUE), "the immediate 2^31 - 1 is the int -1: not a miss, not a signal");
        assertTrue(Lits.isNat(m, -1) && Lits.isLit(m, -1));
        assertEquals(Integer.MAX_VALUE, Lits.natHandle(m, -1));
        // [2^31, 2^63): two words, the value is the identity
        long[] mids = {1L << 31, (1L << 31) + 1, 1L << 32, (1L << 32) - 1, 0x1234_5678_9ABC_DEF0L, Long.MAX_VALUE};
        for (long v : mids) {
            int r = Values.mkNat(m, v);
            assertEquals(Layout.NBIG, Values.kind(m, r));
            assertEquals(Layout.NBIG_INLINE, Values.aux(m, r));
            assertEquals(v, Lits.natHandle(m, r));
            assertEquals(r, Values.mkNat(m, v), "two constructions, one id");
            assertEquals(Layout.FL_WHNF, Values.flags(m, r));
            assertTrue(Lits.isNat(m, r));
        }
        assertNotEquals(Values.mkNat(m, 1L << 32), Values.mkNat(m, 1L << 33));
        // >= 2^63: an id of the worker's NatStore, interned by content
        long h = Nat.ofBigInteger(m.nats, TWO.pow(100));
        int big = Values.mkNat(m, h);
        assertEquals(Layout.NBIG_STORE, Values.aux(m, big));
        assertEquals(h, Lits.natHandle(m, big));
        assertEquals(TWO.pow(100), value(big));
        assertEquals(big, Values.mkNat(m, Nat.ofBigInteger(m.nats, TWO.pow(100))));
        assertEquals(big, Values.mkNat(m, Nat.mul(m.nats, 1L << 50, 1L << 50)), "computed or imported: one id per number");
        // error handles of rlvm.nat become signals
        assertEquals(Op.ABORT, Values.mkNat(m, Nat.TOO_BIG));
        assertEquals(MachineErrors.E_NATSIZE, m.err);
        assertEquals(Op.ABORT, Values.mkNat(m, Nat.COUNT_TOO_BIG));
        assertEquals(Op.FATAL, Values.mkNat(m, Nat.NONE));
        assertEquals(MachineErrors.E_INTERNAL, m.err);
        m.err = 0;
        // not literals
        int nat = Values.mkConst(m, f.cid("Nat"), 0);
        assertFalse(Lits.isNat(m, nat));
        assertFalse(Lits.isLit(m, nat));
        assertFalse(Lits.isNat(m, Layout.HOLE));
        assertFalse(Lits.isLit(m, Layout.NONE));
    }

    @Test
    void klitOfEveryLiteralNodeForm() {
        assertEquals(Layout.imm(5), Lits.klit(m, f.term("(lit 5)")));
        assertEquals(Layout.imm(0), Lits.klit(m, f.term("(lit 0)")));
        assertEquals(-1, Lits.klit(m, f.term("(lit 2147483647)")));
        int a = Lits.klit(m, f.term("(lit 2147483648)"));
        assertEquals(Values.mkNat(m, 1L << 31), a);
        assertEquals(Values.mkNat(m, 0xFFFF_FFFFL), Lits.klit(m, f.term("(lit 4294967295)")));
        assertEquals(Values.mkNat(m, Long.MAX_VALUE), Lits.klit(m, f.term("(lit 9223372036854775807)")));
        // [2^63, 2^64): inline node words, but a NatStore value
        int b63 = Lits.klit(m, f.term("(lit 9223372036854775808)"));
        assertEquals(Layout.NBIG_STORE, Values.aux(m, b63));
        assertEquals(TWO.pow(63), value(b63));
        assertEquals(b63, Values.nsucc(m, Values.mkNat(m, Long.MAX_VALUE)), "the literal and the computed number are one value");
        int max64 = Lits.klit(m, f.term("(lit 18446744073709551615)"));
        assertEquals(TWO.pow(64).subtract(BigInteger.ONE), value(max64));
        // >= 2^64: the node refers to the literal pool; the limbs are imported into the NatStore
        int b64 = Lits.klit(m, f.term("(lit 18446744073709551616)"));
        assertEquals(TWO.pow(64), value(b64));
        assertEquals(b64, Values.nsucc(m, max64));
        assertEquals(b64, Lits.klit(m, f.term("(lit 18446744073709551616)")));
        String big = TWO.pow(200).add(BigInteger.valueOf(12345)).toString();
        int b200 = Lits.klit(m, f.term("(lit " + big + ")"));
        assertEquals(new BigInteger(big), value(b200));
        assertEquals(b200, Values.mkNat(m, Nat.ofBigInteger(m.nats, new BigInteger(big))));
        // strings
        int s = Lits.klit(m, f.term("(str \"abc\")"));
        assertEquals(Layout.STR, Values.kind(m, s));
        assertEquals(Layout.FL_WHNF, Values.flags(m, s));
        assertEquals(s, Lits.klit(m, f.term("(str \"abc\")")));
        assertNotEquals(s, Lits.klit(m, f.term("(str \"abd\")")));
        assertTrue(Lits.isLit(m, s));
        assertFalse(Lits.isNat(m, s));
        assertEquals(3, m.lits.stringCodePointCount(Lits.strIndex(m, s)));
        // not a literal node
        assertEquals(Op.FATAL, Lits.klit(m, f.term("Nat")));
        assertEquals(MachineErrors.E_INTERNAL, m.err);
        m.err = 0;
    }

    /** An export literal of the SHARED region and pool (the loader's form), imported once per session. */
    @Test
    void klitOfAnExportLiteralOfTheSharedPool() {
        Fixture g = Fixture.prelude();
        String big = TWO.pow(130).add(BigInteger.valueOf(7)).toString();
        g.def("bigLit", "Nat", "(lit " + big + ")", Fixture.regular(1));
        g.def("bigLit2", "Nat", "(lit " + big + ")", Fixture.regular(1));
        g.def("mid", "Nat", "(lit 9223372036854775809)", Fixture.regular(1));
        g.seal();
        Machine t = g.m;
        int t1 = g.store.decls().value(g.cid("bigLit"));
        int t2 = g.store.decls().value(g.cid("bigLit2"));
        assertTrue(t.terms.isNatBig(t1));
        assertTrue(t.terms.a(t1) > 0, "a shared pool index");
        int v1 = Lits.klit(t, t1);
        assertEquals(new BigInteger(big), Nat.toBigInteger(t.nats, Lits.natHandle(t, v1)));
        assertEquals(v1, Lits.klit(t, t2), "two literal nodes, one number, one value");
        assertEquals(v1, Lits.klit(t, t1), "the import cache answers the second time");
        assertEquals(v1, Values.mkNat(t, Nat.ofBigInteger(t.nats, new BigInteger(big))));
        int mid = Lits.klit(t, g.store.decls().value(g.cid("mid")));
        assertEquals(TWO.pow(63).add(BigInteger.ONE), Nat.toBigInteger(t.nats, Lits.natHandle(t, mid)));
        // after a session reset the cache must not hand out a record of the dead session
        t.reset();
        t.beginDecl(Integer.MAX_VALUE, -1, Op.FUEL_UNLIMITED, Long.MAX_VALUE);
        int again = Lits.klit(t, t1);
        assertEquals(Layout.NBIG, Values.kind(t, again));
        assertEquals(new BigInteger(big), Nat.toBigInteger(t.nats, Lits.natHandle(t, again)));
        g.close();
    }

    @Test
    void contentComparisonOfNatLiterals() {
        int i5 = Layout.imm(5);
        assertTrue(Lits.sameContent(m, i5, i5));
        assertFalse(Lits.sameContent(m, i5, Layout.imm(6)));
        int a = Values.mkNat(m, 1L << 40);
        int b = Values.mkNat(m, (1L << 40) + 1);
        assertTrue(Lits.sameContent(m, a, a));
        assertFalse(Lits.sameContent(m, a, b));
        assertFalse(Lits.sameContent(m, a, i5), "an immediate differs from every record");
        assertFalse(Lits.sameContent(m, i5, a));
        int x = Values.mkNat(m, Nat.ofBigInteger(m.nats, TWO.pow(64)));
        int y = Values.mkNat(m, Nat.ofBigInteger(m.nats, TWO.pow(64).add(BigInteger.ONE)));
        int z = Values.mkNat(m, Nat.ofBigInteger(m.nats, TWO.pow(128)));
        assertFalse(Lits.sameContent(m, x, y), "same limb count, different low limb");
        assertFalse(Lits.sameContent(m, x, z), "different limb counts");
        assertFalse(Lits.sameContent(m, x, a), "a stored number is 2^63 or more, a two-word one is below");
        assertFalse(Lits.sameContent(m, a, x));
        assertTrue(Lits.sameContent(m, x, Values.mkNat(m, Nat.ofBigInteger(m.nats, TWO.pow(64)))));
        // different kinds
        int s = Lits.klit(m, f.term("(str \"a\")"));
        assertFalse(Lits.sameContent(m, s, a));
        assertFalse(Lits.sameContent(m, s, i5));
        assertFalse(Lits.sameContent(m, s, Values.mkConst(m, f.cid("Nat"), 0)));
        assertFalse(Lits.sameContent(m, Values.mkConst(m, f.cid("Nat"), 0), Values.mkConst(m, f.cid("Bool"), 0)));
    }

    /**
     * Two NBIG records with different store ids and equal content (ISA 2.9): fabricated the only way the
     * NatStore allows two ids for one number - the first id was released and reissued. The record that holds
     * the released id is detected as stale (a decline), it is never compared as "different".
     */
    @Test
    void aReissuedNatStoreIdIsDetectedNotMiscompared() {
        Fixture g = Fixture.prelude0();
        Machine t = g.m;
        NatStore s = t.nats;
        BigInteger n = TWO.pow(90).add(BigInteger.valueOf(3));
        BigInteger other = TWO.pow(91).add(BigInteger.valueOf(5));
        int mark = s.mark();
        int a = Values.mkNat(t, Nat.ofBigInteger(s, n));
        int idA = Values.a(t, a);
        s.truncate(mark); // what NatBinding.dropCache / a truncating owner does under a live record
        long hOther = Nat.ofBigInteger(s, other);
        assertEquals(idA, Nat.bigId(hOther), "the id is reissued to another number");
        int b = Values.mkNat(t, Nat.ofBigInteger(s, n)); // the same number again: a new id, a new record
        assertNotEquals(a, b);
        assertNotEquals(idA, Values.a(t, b));
        assertEquals(n, Nat.toBigInteger(s, Lits.natHandle(t, b)));
        assertThrows(IllegalStateException.class, () -> Lits.natHandle(t, a), "the stale record must not read as `other`");
        assertThrows(IllegalStateException.class, () -> Lits.sameContent(t, a, b));
        assertThrows(IllegalStateException.class, () -> Values.nsucc(t, a));
        // the record made for `other` is its own value
        int c = Values.mkNat(t, hOther);
        assertNotEquals(a, c, "same id, different number: different record (hash and limb count are in the key)");
        assertEquals(other, Nat.toBigInteger(s, Lits.natHandle(t, c)));
        assertFalse(Lits.sameContent(t, b, c));
        g.close();
    }

    /** Two STR records with different pool indices and equal bytes are the same literal (ISA 2.9). */
    @Test
    void contentComparisonOfStringLiteralsWithDifferentIds() {
        Fixture g = Fixture.prelude0();
        Machine t = g.m;
        LitPool savedPool = t.lits;
        try {
            LitPool shared = new LitPool();
            LitPool worker = new LitPool(shared);
            byte[] hello = "héllo".getBytes(StandardCharsets.UTF_8);
            byte[] hellp = "héllp".getBytes(StandardCharsets.UTF_8);
            int inShared = shared.addString(hello, 0, hello.length); // not published yet ...
            int inWorker = worker.addString(hello, 0, hello.length); // ... so the worker interns its own copy
            int otherIdx = worker.addString(hellp, 0, hellp.length);
            shared.publish();
            assertTrue(inShared > 0 && inWorker < 0, "one string, two indices");
            t.lits = worker;
            int a = Values.mkStr(t, inShared);
            int b = Values.mkStr(t, inWorker);
            int c = Values.mkStr(t, otherIdx);
            assertNotEquals(a, b, "different ids");
            assertTrue(Lits.sameContent(t, a, b), "equal bytes");
            assertTrue(Lits.sameContent(t, b, a));
            assertFalse(Lits.sameContent(t, a, c));
            assertFalse(Lits.sameContent(t, b, c));
            assertEquals(a, Values.mkStr(t, inShared));
            assertEquals(Op.FATAL, Values.mkStr(t, 0));
        } finally {
            t.lits = savedPool;
            g.close();
        }
    }

    /**
     * ISA 1.5: pair keys are ordered as UNSIGNED numbers, so an immediate (bit 31 set) is the high word and a
     * pair that contains a literal is as distinct as any other. Checked on the frozen table format with the
     * reference probe; Pairs (M4) must use the same order.
     */
    @Test
    void pairKeyWithAnImmediate() {
        Machine t = new Machine();
        int r = Values.mkSort(t, 9); // record 2
        int imm2 = Layout.imm(2); // the literal 2: same low bits as record ref 2
        int imm5 = Layout.imm(5);
        assertTrue(Integer.compareUnsigned(r, imm5) < 0, "a record ref orders below every immediate");
        assertTrue(r < 0 == false && imm5 < 0, "as signed ints it would be the other way round");
        TestTables.pairAdd(t, imm5, r, Layout.POS);
        assertEquals(Layout.POS, TestTables.pairStatus(t, imm5, r));
        assertEquals(Layout.POS, TestTables.pairStatus(t, r, imm5), "unordered");
        int e = TestTables.findPair(t, r, imm5);
        assertTrue(e >= 0);
        assertEquals(r, t.pair[(e << Layout.PAIR_SHIFT) + Layout.PAIR_LO], "lo = the record: never 0, never the immediate");
        assertEquals(imm5, t.pair[(e << Layout.PAIR_SHIFT) + Layout.PAIR_HI]);
        assertEquals(0, TestTables.pairStatus(t, imm2, r), "(literal 2, r) is not (r, r) nor (literal 5, r)");
        assertEquals(0, TestTables.pairStatus(t, Layout.imm(6), r));
        // two immediates: both orders are one entry; the literal 0 is IMM, not 0, so lo is never the empty mark
        TestTables.pairAdd(t, Layout.imm(0), imm5, Layout.NEG);
        assertEquals(Layout.NEG, TestTables.pairStatus(t, imm5, Layout.imm(0)));
        assertEquals(0, TestTables.pairStatus(t, Layout.imm(0), imm2));
        int e2 = TestTables.findPair(t, imm5, Layout.imm(0));
        assertEquals(Layout.imm(0), t.pair[(e2 << Layout.PAIR_SHIFT) + Layout.PAIR_LO]);
        assertNotEquals(0, t.pair[(e2 << Layout.PAIR_SHIFT) + Layout.PAIR_LO]);
        // the immediate 2^31 - 1 (the int -1) is the largest key
        TestTables.pairAdd(t, -1, r, Layout.POS);
        assertEquals(Layout.POS, TestTables.pairStatus(t, r, -1));
        assertEquals(Layout.POS, TestTables.pairStatus(t, imm5, r), "earlier entries are untouched");
    }
}

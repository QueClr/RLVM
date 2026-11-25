package rlvm.load;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import rlvm.store.DeclKind;
import rlvm.store.Kind;

/** The ownership split of ARCHITECTURE.md 3.3 on small hand-made exports. */
class SplitTest {

    private static Oracle load(Nd nd) throws IOException {
        Oracle o = new Oracle(nd.bytes()).load();
        o.verify();
        if (o.report.exprMData == 0) {
            o.verifySharingFlags();
        }
        return o;
    }

    /** {@code Prop} and a fresh axiom {@code name : Prop}; returns the expression id of {@code Sort 0}. */
    private static int prop(Nd nd) {
        return nd.sort(0);
    }

    @Test
    void theoremValueGoesToABlockAndItsTypeToTheSharedRegion() throws IOException {
        Nd nd = new Nd();
        int prop = prop(nd);                       // e0
        int p = nd.name("P");
        nd.axiom(p, prop);
        int cp = nd.cnst(p);                       // e1: type of the theorem
        int ty = nd.pi(cp, cp);                    // e2: P -> P
        int b0 = nd.bvar(0);                       // e3
        int val = nd.lam(cp, b0);                  // e4: fun x => x   (cp is shared with the type)
        nd.thm(nd.name("T"), ty, val);
        Oracle o = load(nd);
        FakeTarget f = o.fake;
        int t = f.constOf[f.findName("T")];
        assertEquals(DeclKind.THM, f.cKind[t]);
        assertTrue(f.cType[t] > 0);
        assertTrue(f.cValue[t] < 0, "value is a block");
        int block = -f.cValue[t] - 1;
        assertEquals(2, f.blockCount[block], "bvar and lam");
        assertEquals(1, f.blockRoot[block]);
        // ordinals: e0..e4 = 1..5
        assertTrue(o.loader.locationOf(cp + 1) > 0, "const P is reachable from the type");
        assertTrue(o.loader.locationOf(ty + 1) > 0);
        assertTrue(o.loader.locationOf(b0 + 1) < 0);
        assertTrue(o.loader.locationOf(val + 1) < 0);
        // the lam record refers to the shared const and to the previous block record
        int lamRec = (f.blockFirst[block] + 1) * 4;
        assertEquals(Kind.LAM, f.bRec[lamRec] & 0xF);
        assertEquals(LoadTarget.blockShared(o.loader.locationOf(cp + 1)), f.bRec[lamRec + 1]);
        assertEquals(LoadTarget.blockLocal(1), f.bRec[lamRec + 2]);
        assertEquals(1, o.report.blocks);
        assertEquals(1, o.report.blockValues);
        assertEquals(2, o.report.blockNodes);
        assertEquals(3, o.report.sharedNodes);
        assertEquals(0, o.report.promotions);
        // const P is referenced by the pi twice and by the lam once: SHARED; the pi is the type root: BODY
        assertTrue((f.nFlags[o.loader.locationOf(cp + 1)] & LoadTarget.F_SHARED) != 0);
        assertTrue((f.nFlags[o.loader.locationOf(ty + 1)] & LoadTarget.F_BODY) != 0);
        assertEquals(0, f.nFlags[o.loader.locationOf(ty + 1)] & LoadTarget.F_SHARED);
    }

    @Test
    void definitionsAxiomsAndWithoutProofBlocksEverythingIsShared() throws IOException {
        Nd nd = new Nd();
        int prop = prop(nd);
        int p = nd.name("P");
        nd.axiom(p, prop);
        int cp = nd.cnst(p);
        int ty = nd.pi(cp, cp);
        int val = nd.lam(cp, nd.bvar(0));
        nd.def(nd.name("d"), ty, val);
        int ty2 = nd.pi(cp, ty);
        int val2 = nd.lam(cp, val);
        nd.thm(nd.name("t"), ty2, val2);
        Oracle o = load(nd);
        assertEquals(1, o.report.blockNodes, "only the theorem's lam");
        assertTrue(o.fake.cValue[o.fake.constOf[o.fake.findName("d")]] > 0);

        Oracle all = new Oracle(nd.bytes()).load(LoadOptions.batch(0).withProofBlocks(false));
        all.verify();
        assertEquals(0, all.report.blockNodes);
        assertEquals(0, all.fake.blocks);
        assertTrue(all.fake.cValue[all.fake.constOf[all.fake.findName("t")]] > 0);
    }

    @Test
    void laterReferenceToAProofRecordPromotesIt() throws IOException {
        Nd nd = new Nd();
        int prop = prop(nd);
        int p = nd.name("P");
        nd.axiom(p, prop);
        int cp = nd.cnst(p);
        int ty = nd.pi(cp, cp);
        int b0 = nd.bvar(0);
        int inner = nd.lam(cp, b0);               // proof-only
        int val = nd.app(inner, inner);           // proof-only (ill-typed, which the loader does not care about)
        nd.thm(nd.name("T1"), ty, val);
        // the next theorem uses `inner` in its TYPE and `val` in its value
        int ty2 = nd.pi(cp, nd.app(inner, cp));
        int val2 = nd.lam(cp, val);
        nd.thm(nd.name("T2"), ty2, val2);
        // and a definition whose value is the first proof's root
        nd.def(nd.name("D"), ty, val);
        Oracle o = load(nd);
        FakeTarget f = o.fake;
        assertEquals(2, o.report.promotions, "inner once, val once (the third use finds the shared copy)");
        assertEquals(3, o.report.promotedNodes, "bvar, lam; then app (lam is found in the promotion memo)");
        assertTrue(o.loader.locationOf(inner + 1) > 0);
        assertTrue(o.loader.locationOf(val + 1) > 0);
        assertTrue(o.loader.locationOf(b0 + 1) < 0, "the loader only learns the new place of records it asked for");
        assertTrue(f.nPromoted[o.loader.locationOf(inner + 1)]);
        // T1 keeps its block
        int t1 = f.constOf[f.findName("T1")];
        assertTrue(f.cValue[t1] < 0);
        // D's value is the promoted copy
        assertEquals(o.loader.locationOf(val + 1), f.cValue[f.constOf[f.findName("D")]]);
    }

    @Test
    void valueThatIsPartOfTheTypeOrAnEarlierRecordIsShared() throws IOException {
        Nd nd = new Nd();
        int prop = prop(nd);
        int p = nd.name("P");
        nd.axiom(p, prop);
        int cp = nd.cnst(p);
        int ty = nd.pi(cp, cp);
        nd.thm(nd.name("A"), ty, cp);             // value = a subterm of the type: no block
        int junk = nd.lam(cp, nd.bvar(0));        // reached by nothing
        nd.thm(nd.name("B"), cp, cp);             // type and value are the same earlier record; junk -> ownerless block
        int v3 = nd.app(junk, cp);
        nd.thm(nd.name("C"), cp, v3);             // uses the junk of B: promotion out of an ownerless block
        nd.thm(nd.name("D"), cp, v3);             // value root in C's block: promoted, D has no block
        Oracle o = load(nd);
        FakeTarget f = o.fake;
        assertTrue(f.cValue[f.constOf[f.findName("A")]] > 0);
        assertTrue(f.cValue[f.constOf[f.findName("B")]] > 0);
        assertEquals(f.cType[f.constOf[f.findName("B")]], f.cValue[f.constOf[f.findName("B")]]);
        assertEquals(2, f.blocks, "B's ownerless block and C's block");
        assertEquals(-1, f.blockRoot[0]);
        assertEquals(2, f.blockCount[0]);
        assertTrue(f.cValue[f.constOf[f.findName("C")]] < 0);
        assertTrue(f.cValue[f.constOf[f.findName("D")]] > 0);
        assertEquals(1, o.report.blockValues);
        assertEquals(2, o.report.promotions);
        // the type root of B is an earlier record: referenced twice by the declaration, but not in its segment
        assertEquals(0, o.report.trailingExprRecords);
    }

    @Test
    void recordsOfADeclarationInterruptedByAnotherDeclaration() throws IOException {
        // lean4export emits the block of Nat in the middle of the first declaration that needs a literal
        Nd nd = new Nd();
        int prop = prop(nd);
        int p = nd.name("P");
        nd.axiom(p, prop);
        int cp = nd.cnst(p);
        int early = nd.lam(cp, nd.bvar(0));       // belongs to theorem T, emitted before the inductive line
        int nat = nd.name("Nat");
        int zero = nd.str(nat, "zero");
        int type1 = nd.sort(nd.succ(0));
        int cnat = nd.cnst(nat);
        nd.inductive(Nd.strs(Nd.type(nat, type1, 0, 0, Nd.ints(nat), Nd.ints(zero), false)),
                Nd.strs(Nd.ctor(zero, cnat, nat, 0, 0, 0, false)), Nd.strs());
        int lit = nd.nat("5");
        int val = nd.app(early, lit);
        nd.thm(nd.name("T"), cp, val);
        Oracle o = load(nd);
        FakeTarget f = o.fake;
        // No root of the Nat block reaches const P, bvar 0 and `early`: they go to an ownerless block at the
        // inductive line and are promoted when T (whose names are claimed by then) refers to them.
        assertEquals(2, f.blocks);
        assertEquals(-1, f.blockRoot[0]);
        assertEquals(3, f.blockCount[0]);
        assertTrue(o.loader.locationOf(early + 1) > 0);
        assertTrue(f.nPromoted[o.loader.locationOf(early + 1)]);
        assertEquals(3, o.report.promotedNodes);
        int t = f.constOf[f.findName("T")];
        assertTrue(f.cValue[t] < 0);
        assertEquals(2, f.blockCount[-f.cValue[t] - 1], "lit and app");
        // members of the block see each other: the constructor's type is a bound reference to Nat
        int natCid = f.constOf[f.findName("Nat")];
        int zeroCid = f.constOf[f.findName("Nat.zero")];
        assertEquals(DeclKind.INDUCT, f.cKind[natCid]);
        assertEquals(DeclKind.CTOR, f.cKind[zeroCid]);
        assertEquals(Kind.CONST, f.nKind[f.cType[zeroCid]]);
        assertEquals(natCid, f.nA[f.cType[zeroCid]], "CONST Nat bound to the cid of the same unit");
        assertEquals(0, f.unresolved);
        assertEquals(0, f.unresolvedInBlocks);
        assertEquals(f.cUnit[natCid], f.cUnit[zeroCid]);
    }

    @Test
    void selfReferenceBindsToTheOwnConstant() throws IOException {
        Nd nd = new Nd();
        int prop = prop(nd);
        int p = nd.name("P");
        nd.axiom(p, prop);
        int cp = nd.cnst(p);
        int self = nd.name("selfProof");
        int cself = nd.cnst(self);
        nd.thm(self, cp, cself);
        Oracle o = load(nd);
        FakeTarget f = o.fake;
        int t = f.constOf[f.findName("selfProof")];
        int block = -f.cValue[t] - 1;
        int rec = f.blockFirst[block] * 4;
        assertEquals(Kind.CONST, f.bRec[rec] & 0xF);
        assertEquals(t, f.bRec[rec + 1], "the name was claimed before the segment was swept");
        assertEquals(0, f.unresolved + f.unresolvedInBlocks);
    }

    @Test
    void unknownAndLaterConstantsStayUnbound() throws IOException {
        Nd nd = new Nd();
        int prop = prop(nd);
        int later = nd.name("later");
        int cl = nd.cnst(later);
        nd.def(nd.name("user"), prop, cl);
        nd.axiom(later, prop);
        Oracle o = load(nd);
        FakeTarget f = o.fake;
        int user = f.constOf[f.findName("user")];
        assertEquals(~f.findName("later"), f.nA[f.cValue[user]], "forward reference: ~nameId");
        assertEquals(1, o.report.unresolvedRefs);
    }

    @Test
    void unsafeAndPartialRecordsAreDroppedAndTheirNamesRemembered() throws IOException {
        Nd nd = new Nd();
        int prop = prop(nd);
        int p = nd.name("P");
        nd.axiom(p, prop);
        int cp = nd.cnst(p);
        int loopName = nd.name("unsafeLoop");
        int cloop = nd.cnst(loopName);
        nd.def(loopName, cp, cloop, "\"opaque\"", "unsafe");
        int partName = nd.name("partialLoop");
        int cpart = nd.cnst(partName);
        nd.def(partName, cp, cpart, "\"opaque\"", "partial");
        int ua = nd.name("unsafeAxiom");
        nd.axiom(ua, cp, true);
        int uo = nd.name("unsafeOpaque");
        nd.opaque(uo, cp, cp, true);
        nd.thm(nd.name("user"), cp, cloop);
        Oracle o = load(nd);
        FakeTarget f = o.fake;
        assertEquals(4, o.report.droppedUnsafe);
        assertEquals(2, o.report.constants);
        assertEquals(2, o.report.units);
        assertEquals(6, o.report.declRecords);
        for (String n : new String[] {"unsafeLoop", "partialLoop", "unsafeAxiom", "unsafeOpaque"}) {
            assertEquals(FakeTarget.DROPPED, f.constOf[f.findName(n)], n);
        }
        // the user's reference is unbound (admission turns it into "unknown constant")
        int user = f.constOf[f.findName("user")];
        int block = -f.cValue[user] - 1;
        int root = (f.blockFirst[block] + f.blockRoot[block]) * 4;
        assertEquals(~f.findName("unsafeLoop"), f.bRec[root + 1]);
        // the records of the dropped declarations went into the next declaration's segment
        assertEquals(0, o.report.trailingExprRecords);
        assertEquals(2, f.blockCount[block], "const unsafeLoop, const partialLoop");
    }

    @Test
    void aDroppedNameCannotBeDeclaredAgainAndViceVersa() {
        for (int variant = 0; variant < 4; variant++) {
            Nd nd = new Nd();
            int prop = prop(nd);
            int x = nd.name("x");
            switch (variant) {
                case 0 -> {
                    nd.axiom(x, prop, true);
                    nd.axiom(x, prop);
                }
                case 1 -> {
                    nd.axiom(x, prop);
                    nd.def(x, prop, prop, "\"abbrev\"", "unsafe");
                }
                case 2 -> {
                    nd.def(x, prop, prop, "\"abbrev\"", "partial");
                    nd.opaque(x, prop, prop, true);
                }
                default -> {
                    nd.opaque(x, prop, prop, true);
                    nd.thm(x, prop, prop);
                }
            }
            MalformedExportException e = assertThrows(MalformedExportException.class, () -> new Oracle(nd.bytes()).load());
            assertEquals("Duplicate declaration: x", e.getMessage());
            assertEquals(5, e.line(), "variant " + variant);
        }
    }

    @Test
    void mdataIsDroppedAndTheWrappedExpressionStandsForIt() throws IOException {
        // metadata that no admitted declaration reaches: dropped, the wrapped record stands for it
        Nd nd = new Nd();
        int prop = prop(nd);
        int p = nd.name("P");
        nd.axiom(p, prop);
        int cp = nd.cnst(p);
        int ty = nd.pi(cp, cp);
        nd.def(nd.name("d"), ty, nd.lam(cp, nd.bvar(0)));
        int q = nd.cnst(p);
        int wrapped = nd.mdata(q);
        nd.def(nd.name("u"), nd.pi(wrapped, nd.mdata(wrapped)), nd.mdata(nd.lam(q, nd.bvar(0))), "\"abbrev\"", "unsafe");
        Oracle o = load(nd);
        assertEquals(3, o.report.exprMData);
        assertEquals(1, o.report.droppedUnsafe);
        FakeTarget f = o.fake;
        int d = f.constOf[f.findName("d")];
        assertEquals(Kind.PI, f.nKind[f.cType[d]]);
        assertEquals(f.nA[f.cType[d]], f.nB[f.cType[d]], "both children are the one const node");
        assertEquals(Kind.LAM, f.nKind[f.cValue[d]]);
    }

    /**
     * Metadata in the type or the value of an admitted declaration: loaded with the node dropped by
     * default (the practical policy, OWNER-NOTES 13), declined with {@code LoadOptions.strictMetadata}
     * (finding S3-F2 / R2: officially the node is a term of its own for every cache of the kernel and
     * hides what is under it from the kernel's syntactic tests).
     */
    @Test
    void aDeclarationWhoseTypeOrValueCarriesMetadataIsDeclinedInStrictMode() {
        String expected = "metadata (mdata) node in the type or value of a declaration";
        for (int variant = 0; variant < 8; variant++) {
            Nd nd = new Nd();
            int prop = prop(nd);
            int p = nd.name("P");
            nd.axiom(p, prop);
            int cp = nd.cnst(p);
            int plain = nd.pi(cp, cp);
            int id = nd.lam(cp, nd.bvar(0));
            switch (variant) {
                case 0 -> nd.def(nd.name("d"), nd.mdata(plain), id);                       // the type root
                case 1 -> nd.def(nd.name("d"), plain, nd.mdata(id));                       // the value root
                case 2 -> nd.def(nd.name("d"), nd.pi(nd.mdata(cp), cp), id);               // below the type root
                case 3 -> nd.thm(nd.name("t"), plain, nd.app(nd.lam(plain, nd.bvar(0)), nd.mdata(id))); // inside a proof
                case 4 -> nd.axiom(nd.name("a"), nd.pi(plain, nd.mdata(prop)));
                case 5 -> nd.opaque(nd.name("o"), plain, nd.mdata(id), false);
                case 6 -> {
                    // the wrapped record referred to directly: counted as carrying metadata (fail closed)
                    nd.mdata(id);
                    nd.def(nd.name("d"), plain, id);
                }
                default -> {
                    // the metadata was read with an earlier declaration's records and is used by a later one
                    int w = nd.mdata(id);
                    nd.axiom(nd.name("a"), plain);
                    nd.def(nd.name("d"), plain, w);
                }
            }
            byte[] data = nd.bytes();
            // default: the metadata is transparent, the file loads as it always did
            Loaded practical = Loaded.load(data, LoadOptions.batch(data.length));
            assertTrue(practical.failure == null, "variant " + variant + ": " + practical.failure);
            assertTrue(practical.report.exprMData > 0, "variant " + variant);
            // strict: declined, in batch and pipelined mode alike
            for (LoadOptions o : new LoadOptions[] {LoadOptions.batch(data.length).withStrictMetadata(true),
                    LoadOptions.pipelined(data.length).withPublishEvery(1, 1L << 40).withStrictMetadata(true)}) {
                Loaded strict = Loaded.load(data, o);
                assertTrue(strict.failure instanceof UnsupportedExportException, "variant " + variant + ": " + strict.failure);
                assertTrue(strict.failure.getMessage().startsWith(expected), "variant " + variant + ": " + strict.failure.getMessage());
            }
            // the same file without the option but with metadata nowhere near a declaration is untouched by it
        }
    }

    /**
     * A metadata node around an application of {@code eagerReduce} (S3's {@code s3-mdata-eager-hidden-ok}):
     * officially the node hides the application from {@code is_eager_reduce}, so the argument is checked
     * without eager reduction; with the node dropped the kernel would enter eager reduction and could
     * reject what the official checker accepts. Declined under BOTH policies; metadata anywhere else
     * stays transparent under the practical one.
     */
    @Test
    void metadataAroundAnApplicationOfEagerReduceIsDeclinedUnderBothPolicies() {
        String expected = "metadata (mdata) node around an application of eagerReduce";
        for (int variant = 0; variant < 10; variant++) {
            boolean hides = variant < 5 || variant == 9;
            Nd nd = new Nd();
            int prop = prop(nd);
            int p = nd.name("P");
            nd.axiom(p, prop);
            int cp = nd.cnst(p);
            int plain = nd.pi(cp, cp);
            int id = nd.lam(cp, nd.bvar(0));
            int er = nd.name(variant == 8 ? "eagerReduceX" : "eagerReduce");
            nd.axiom(er, nd.pi(prop, nd.pi(nd.bvar(0), nd.bvar(1))));
            int use = nd.name("use");
            nd.axiom(use, nd.pi(plain, cp));
            int cuse = nd.cnst(use);
            switch (variant) {
                case 0 -> {   // mdata (eagerReduce (P -> P) id), all records in the declaration's own segment
                    int e = nd.app(nd.app(nd.cnst(er), plain), id);
                    nd.thm(nd.name("t"), cp, nd.app(cuse, nd.mdata(e)));
                }
                case 1 -> {   // (mdata (eagerReduce (P -> P))) id
                    int e1 = nd.mdata(nd.app(nd.cnst(er), plain));
                    nd.thm(nd.name("t"), cp, nd.app(cuse, nd.app(e1, id)));
                }
                case 2 -> {   // (mdata eagerReduce) (P -> P) id
                    int e0 = nd.mdata(nd.cnst(er));
                    nd.def(nd.name("d"), cp, nd.app(cuse, nd.app(nd.app(e0, plain), id)));
                }
                case 3 -> {   // the constant record belongs to an earlier declaration
                    int ce = nd.cnst(er);
                    nd.axiom(nd.name("a"), nd.pi(nd.app(nd.app(ce, prop), cp), cp));
                    int e = nd.app(nd.app(ce, plain), id);
                    nd.thm(nd.name("t"), cp, nd.app(cuse, nd.mdata(e)));
                }
                case 4 -> {   // the wrapped application belongs to an earlier declaration: cannot be read back, fail closed
                    int e = nd.app(nd.app(nd.cnst(er), prop), cp);
                    nd.axiom(nd.name("a"), nd.pi(e, cp));
                    nd.thm(nd.name("t"), cp, nd.app(cuse, nd.app(nd.lam(nd.mdata(e), id), id)));
                }
                case 5 -> {   // eagerReduce not wrapped, metadata elsewhere in the same declaration
                    int e = nd.app(nd.app(nd.cnst(er), plain), id);
                    nd.thm(nd.name("t"), cp, nd.app(nd.mdata(cuse), e));
                }
                case 6 -> {   // metadata INSIDE the arguments of eagerReduce: the official test sees the application
                    int e = nd.app(nd.app(nd.cnst(er), nd.mdata(nd.pi(cp, cp))), nd.mdata(nd.lam(cp, nd.bvar(0))));
                    nd.thm(nd.name("t"), cp, nd.app(cuse, e));
                }
                case 7 -> {   // three arguments: not `eagerReduce _ _`
                    int e = nd.app(nd.app(nd.app(nd.cnst(er), plain), id), cp);
                    nd.def(nd.name("d"), cp, nd.app(nd.lam(cp, nd.bvar(0)), nd.mdata(e)));
                }
                case 8 -> {   // another constant
                    int e = nd.app(nd.app(nd.cnst(er), plain), id);
                    nd.thm(nd.name("t"), cp, nd.app(cuse, nd.mdata(e)));
                }
                default -> {  // a file that names eagerReduce, metadata around a record of an EARLIER declaration that
                              // is not such an application: the loader cannot read it back and fails closed
                    nd.thm(nd.name("t"), cp, nd.app(cuse, nd.mdata(id)));
                }
            }
            byte[] data = nd.bytes();
            for (LoadOptions o : new LoadOptions[] {LoadOptions.batch(data.length),
                    LoadOptions.pipelined(data.length).withPublishEvery(1, 1L << 40)}) {
                Loaded practical = Loaded.load(data, o);
                if (hides) {
                    assertTrue(practical.failure instanceof UnsupportedExportException, "variant " + variant + ": " + practical.failure);
                    assertTrue(practical.failure.getMessage().startsWith(expected), "variant " + variant + ": " + practical.failure.getMessage());
                } else {
                    assertTrue(practical.failure == null, "variant " + variant + ": " + practical.failure);
                }
                // strict: declined either way (metadata in a declaration)
                Loaded strict = Loaded.load(data, o.withStrictMetadata(true));
                assertTrue(strict.failure instanceof UnsupportedExportException, "variant " + variant + ": " + strict.failure);
            }
        }
    }

    /**
     * A let whose {@code nondep} flag is set (finding R2-F1): the flag is part of the official term
     * identity and is not stored, so in strict mode the declaration is declined like one with
     * metadata; by default, and with the flag clear, the file loads as it always did.
     */
    @Test
    void aDeclarationWithANondepLetIsDeclinedInStrictMode() {
        for (int variant = 0; variant < 6; variant++) {
            boolean nondep = variant != 5;
            Nd nd = new Nd();
            int prop = prop(nd);
            int p = nd.name("P");
            nd.axiom(p, prop);
            int cp = nd.cnst(p);
            int plain = nd.pi(cp, cp);
            int id = nd.lam(cp, nd.bvar(0));
            int let = nd.let(plain, id, nd.bvar(0), nondep);   // let x : P -> P := id; x
            switch (variant) {
                case 0 -> nd.def(nd.name("d"), plain, let);                                   // the value root
                case 1 -> nd.thm(nd.name("t"), plain, nd.app(nd.lam(plain, nd.bvar(0)), let)); // inside a proof
                case 2 -> nd.axiom(nd.name("a"), nd.pi(plain, nd.let(prop, cp, nd.bvar(0), true))); // in a type
                case 3 -> nd.opaque(nd.name("o"), plain, let, false);
                case 4 -> {
                    // read with an earlier declaration's records, used by a later one
                    nd.axiom(nd.name("a"), plain);
                    nd.def(nd.name("d"), plain, let);
                }
                default -> nd.def(nd.name("d"), plain, let);                                  // nondep = false
            }
            byte[] data = nd.bytes();
            Loaded practical = Loaded.load(data, LoadOptions.batch(data.length));
            assertTrue(practical.failure == null, "variant " + variant + ": " + practical.failure);
            for (LoadOptions o : new LoadOptions[] {LoadOptions.batch(data.length).withStrictMetadata(true),
                    LoadOptions.pipelined(data.length).withPublishEvery(1, 1L << 40).withStrictMetadata(true)}) {
                Loaded strict = Loaded.load(data, o);
                if (nondep) {
                    assertTrue(strict.failure instanceof UnsupportedExportException, "variant " + variant + ": " + strict.failure);
                    assertTrue(strict.failure.getMessage().contains("nondep = true"), strict.failure.getMessage());
                } else {
                    assertTrue(strict.failure == null, "variant " + variant + ": " + strict.failure);
                }
            }
        }
    }

    @Test
    void trailingRecordsAfterTheLastDeclarationAreNotStored() throws IOException {
        Nd nd = new Nd();
        int prop = prop(nd);
        nd.axiom(nd.name("P"), prop);
        nd.app(prop, prop);
        nd.app(prop, prop);
        Oracle o = load(nd);
        assertEquals(2, o.report.trailingExprRecords);
        assertEquals(3, o.report.exprRecords);
        assertEquals(1, o.report.sharedNodes);
        assertEquals(0, o.loader.locationOf(2));
        assertEquals(0, o.loader.locationOf(3));
        assertTrue(o.loader.locationOf(1) > 0);
    }

    @Test
    void deepTermsNeedNoRecursion() throws IOException {
        // perf/app-lam nests 19,999 deep; here 60,000 lambdas in a proof, later promoted as a whole
        Nd nd = new Nd();
        int prop = prop(nd);
        int p = nd.name("P");
        nd.axiom(p, prop);
        int cp = nd.cnst(p);
        int e = nd.bvar(0);
        for (int i = 0; i < 60_000; i++) {
            e = (i & 1) == 0 ? nd.lam(cp, e) : nd.app(e, cp);
        }
        nd.thm(nd.name("deep"), cp, e);
        nd.def(nd.name("again"), cp, e);
        Oracle o = load(nd);
        assertEquals(60_001, o.report.blockNodes);
        assertEquals(1, o.report.promotions);
        assertEquals(60_001, o.report.promotedNodes);
    }

    @Test
    void referrerCounterSaturatesAndCountsBothSlotsOfOneParent() throws IOException {
        Nd nd = new Nd();
        int prop = prop(nd);
        int p = nd.name("P");
        nd.axiom(p, prop);
        int cp = nd.cnst(p);                      // many referrers
        int twice = nd.app(cp, cp);               // one parent, two slots -> cp SHARED
        int once = nd.lam(cp, twice);             // `twice` is a body with one referrer
        int root = nd.app(once, cp);
        for (int i = 0; i < 10; i++) {
            root = nd.app(root, cp);
        }
        nd.def(nd.name("d"), prop, root);
        Oracle o = load(nd);
        FakeTarget f = o.fake;
        int cpNode = o.loader.locationOf(cp + 1);
        assertTrue((f.nFlags[cpNode] & LoadTarget.F_SHARED) != 0);
        int twiceNode = o.loader.locationOf(twice + 1);
        assertEquals(LoadTarget.F_BODY, f.nFlags[twiceNode]);
        int onceNode = o.loader.locationOf(once + 1);
        assertEquals(0, f.nFlags[onceNode]);
        // `once` (a lam with one referrer, an application) is reported as a single-referrer candidate
        boolean found = false;
        for (int i = 0; i < f.singleCount; i++) {
            found |= f.singles[2 * i] == onceNode;
            assertFalse(f.singles[2 * i] == twiceNode, "a binder body is not a candidate");
        }
        assertTrue(found);

        Oracle off = new Oracle(nd.bytes()).load(LoadOptions.batch(0).withSharingFlags(false));
        off.verify();
        assertEquals(0, off.fake.singleCount);
        for (int i = 1; i < off.fake.nodes; i++) {
            assertEquals(0, off.fake.nFlags[i]);
        }
    }
}

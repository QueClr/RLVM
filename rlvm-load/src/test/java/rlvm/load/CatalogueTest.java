package rlvm.load;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import rlvm.store.DeclKind;
import rlvm.store.Kind;

/**
 * The catalogue of *agentic-note*: design-architecture-document, 3.4 (non-canonical and hostile input), row by row, through reader
 * and loader: what must load unchanged, what must be rejected ({@link MalformedExportException}: the
 * official reader fails) and what must be declined ({@link UnsupportedExportException}).
 */
class CatalogueTest {

    private static Oracle load(byte[] data) throws IOException {
        Oracle o = new Oracle(data).load();
        o.verify();
        return o;
    }

    private static Oracle load(Nd nd) throws IOException {
        return load(nd.bytes());
    }

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /** A small well-formed file: {@code axiom P : Prop}, {@code theorem T : P -> P := fun x => x}. */
    private static Nd small() {
        Nd nd = new Nd();
        int prop = nd.sort(0);
        int p = nd.name("P");
        nd.axiom(p, prop);
        int cp = nd.cnst(p);
        int ty = nd.pi(cp, cp);
        int val = nd.lam(cp, nd.bvar(0));
        nd.thm(nd.name("T"), ty, val);
        return nd;
    }

    private static void rejected(byte[] data, String messagePart) {
        MalformedExportException e = assertThrows(MalformedExportException.class, () -> new Oracle(data).load());
        assertTrue(e.getMessage().contains(messagePart), e.getMessage());
    }

    private static void declined(byte[] data, String messagePart) {
        UnsupportedExportException e = assertThrows(UnsupportedExportException.class, () -> new Oracle(data).load());
        assertTrue(e.getMessage().contains(messagePart), e.getMessage());
    }

    // ---- row 1: other key order, whitespace, \\uXXXX escapes, CRLF, no final newline -----------------

    @Test
    void nonCanonicalJsonLoadsToTheSameStore() throws IOException {
        Oracle canonical = load(small());
        String text = new String(small().bytes(), StandardCharsets.UTF_8);
        // spaces after separators, CRLF line ends, no final newline, an escaped name component
        String loose = text.replace(":", ": ").replace(",", " , ").replace("\"P\"", "\"\\u0050\"").replace("\n", "\r\n");
        loose = loose.substring(0, loose.length() - 2);
        Oracle o = load(utf8(loose));
        assertEquals(3, canonical.report.generalPathLines, "sort, axiom, bvar: kinds without a byte template");
        assertEquals(o.report.lines - 1, o.report.generalPathLines, "every line of the loose file");
        assertEquals(canonical.report.constants, o.report.constants);
        assertEquals(canonical.report.sharedNodes, o.report.sharedNodes);
        assertEquals(canonical.report.blockNodes, o.report.blockNodes);
        assertEquals(canonical.report.lines, o.report.lines);
        assertTrue(o.fake.findName("P") != 0);
        // keys in another order
        Nd nd = new Nd();
        nd.raw("{\"sort\":0,\"ie\":0}");
        nd.raw("{\"str\":{\"str\":\"P\",\"pre\":0},\"in\":1}");
        nd.raw("{\"axiom\":{\"type\":0,\"name\":1,\"levelParams\":[],\"isUnsafe\":false}}");
        Oracle k = load(nd);
        assertEquals(1, k.report.constants);
        assertEquals(3, k.report.generalPathLines);
    }

    // ---- row 2: sparse or out-of-order ids -----------------------------------------------------------

    @Test
    void sparseAndOutOfOrderIdsNeverReachTheLoader() throws IOException {
        Nd nd = new Nd();
        nd.raw("{\"in\":70,\"str\":{\"pre\":0,\"str\":\"P\"}}");
        nd.raw("{\"il\":9,\"succ\":0}");
        nd.raw("{\"il\":3,\"succ\":9}");
        nd.raw("{\"ie\":1000000,\"sort\":3}");
        nd.raw("{\"ie\":5,\"sort\":9}");
        nd.raw("{\"axiom\":{\"isUnsafe\":false,\"levelParams\":[],\"name\":70,\"type\":1000000}}");
        Oracle o = load(nd);
        assertTrue(o.report.sparseIds);
        assertEquals(2, o.loader.exprCount(), "ordinals stay dense");
        assertEquals(1, o.report.sharedNodes, "the axiom's type");
        assertEquals(1, o.report.blockNodes, "the record no root reaches goes to an ownerless block");
        assertEquals(0, o.report.blockValues);
        int p = o.fake.constOf[o.fake.findName("P")];
        assertEquals(Kind.SORT, o.fake.nKind[o.fake.cType[p]]);
        assertFalse(load(small()).report.sparseIds);
    }

    // ---- row 3: what the official reader rejects -----------------------------------------------------

    @Test
    void readerLevelErrorsAreRejections() {
        // id used before its definition
        rejected(new Nd().raw("{\"app\":{\"arg\":1,\"fn\":0},\"ie\":0}").bytes(), "");
        rejected(new Nd().raw("{\"il\":1,\"succ\":7}").bytes(), "");
        rejected(new Nd().raw("{\"in\":1,\"str\":{\"pre\":4,\"str\":\"x\"}}").bytes(), "");
        // id bound twice
        Nd twice = new Nd();
        twice.raw("{\"in\":1,\"str\":{\"pre\":0,\"str\":\"x\"}}");
        twice.raw("{\"in\":1,\"str\":{\"pre\":0,\"str\":\"y\"}}");
        MalformedExportException e = assertThrows(MalformedExportException.class, () -> new Oracle(twice.bytes()).load());
        assertEquals(3, e.line());
        assertTrue(e.getMessage().contains("bound twice"), e.getMessage());
        Nd twiceExpr = new Nd();
        twiceExpr.raw("{\"ie\":0,\"sort\":0}");
        twiceExpr.raw("{\"ie\":0,\"sort\":0}");
        rejected(twiceExpr.bytes(), "bound twice");
        // blank line
        Nd blank = small();
        blank.raw("");
        blank.raw("{\"ie\":90,\"sort\":0}");
        rejected(blank.bytes(), "");
        // natVal that is not a digit string
        rejected(new Nd().raw("{\"ie\":0,\"natVal\":\"12a\"}").bytes(), "");
        rejected(new Nd().raw("{\"ie\":0,\"natVal\":\"\"}").bytes(), "");
        rejected(new Nd().raw("{\"ie\":0,\"natVal\":5}").bytes(), "");
        // unknown record shapes, broken JSON
        rejected(new Nd().raw("{\"ie\":0,\"fvar\":0}").bytes(), "");
        rejected(new Nd().raw("{\"lemma\":{}}").bytes(), "");
        rejected(new Nd().raw("{\"ie\":0,\"sort\":0").bytes(), "");
        rejected(new Nd().raw("[]").bytes(), "");
        // a de Bruijn index the official reader does not survive
        rejected(new Nd().raw("{\"bvar\":1048575,\"ie\":0}").bytes(), "");
    }

    // ---- row 4: a declaration name declared twice, in any kinds ----------------------------------------

    @Test
    void duplicateDeclarationNamesAreRejectedStructurally() {
        // two different name ids for the same name
        Nd nd = new Nd();
        int prop = nd.sort(0);
        int a = nd.str(nd.str(0, "A"), "b");
        nd.axiom(a, prop);
        int a2 = nd.str(nd.str(0, "A"), "b");
        assertNotEquals(a, a2);
        nd.thm(a2, prop, prop);
        MalformedExportException e = assertThrows(MalformedExportException.class, () -> new Oracle(nd.bytes()).load());
        assertEquals("Duplicate declaration: A.b", e.getMessage());
        assertEquals(8, e.line());

        // members of one inductive block with the same name; a member against an earlier definition
        for (int variant = 0; variant < 4; variant++) {
            Nd b = new Nd();
            int type1 = b.sort(b.succ(0));
            int t = b.name("T");
            int mk = b.str(t, "mk");
            int rec = b.str(t, "rec");
            int ct = b.cnst(t);
            String ty = Nd.type(t, type1, 0, 0, Nd.ints(t), Nd.ints(mk), false);
            String ctor = Nd.ctor(mk, ct, t, 0, 0, 0, false);
            String recursor = Nd.rec(rec, ct, Nd.ints(t), 0, 0, 1, 1, false, false, Nd.ints(mk, 0, ct));
            String expect;
            switch (variant) {
                case 0 -> {
                    b.inductive(Nd.strs(ty, ty), Nd.strs(ctor), Nd.strs(recursor));
                    expect = "T";
                }
                case 1 -> {
                    b.inductive(Nd.strs(ty), Nd.strs(ctor, ctor), Nd.strs(recursor));
                    expect = "T.mk";
                }
                case 2 -> {
                    b.def(rec, type1, type1);
                    b.inductive(Nd.strs(ty), Nd.strs(ctor), Nd.strs(recursor));
                    expect = "T.rec";
                }
                default -> {
                    b.inductive(Nd.strs(ty), Nd.strs(ctor), Nd.strs(recursor));
                    b.quot(mk, type1, "type");
                    expect = "T.mk";
                }
            }
            MalformedExportException d = assertThrows(MalformedExportException.class, () -> new Oracle(b.bytes()).load());
            assertEquals("Duplicate declaration: " + expect, d.getMessage(), "variant " + variant);
        }
    }

    // ---- rows 5, 6: the header ---------------------------------------------------------------------------

    @Test
    void theHeaderDecidesNothing() throws IOException {
        String body = new String(small().bytes(), StandardCharsets.UTF_8).substring(Nd.META.length() + 1);
        // garbage as line 1: never interpreted
        Oracle garbage = load(utf8("this is not JSON\n" + body));
        assertFalse(garbage.report.metaWellFormed);
        assertEquals(2, garbage.report.constants);
        // an empty object, a header without a format version
        assertEquals(2, load(utf8("{}\n" + body)).report.constants);
        assertEquals(2, load(utf8("{\"meta\":{\"exporter\":{\"name\":\"x\"}}}\n" + body)).report.constants);
        // format 3.x of any exporter
        Oracle h = load(utf8(Nd.META.replace("3.1.0\"},\"lean", "3.7\"},\"lean").replace("lean4export", "handcrafted") + "\n" + body));
        assertEquals("3.7", h.report.formatVersion);
        assertEquals("handcrafted", h.report.exporterName);
        assertEquals("4.36.0", h.report.leanVersion);
        // no header line: the first record is swallowed as the header, exactly as in the official reader
        rejected(utf8(body), "");
        // another major version: read like any other file (the official reader never looks at line 1,
        // probe work/rlvm-load/official/format-9.9.9.ndjson); the report says so, and a caller that
        // wants the decline back sets LoadOptions.strictFormatVersion
        for (String v : new String[] {"2.0.0", "4.0.0", "9.9.9", "30.1", "", "x"}) {
            String header = Nd.META.replace("\"format\":{\"version\":\"3.1.0\"}", "\"format\":{\"version\":\"" + v + "\"}");
            byte[] data = utf8(header + "\n" + body);
            Oracle o = load(data);
            assertEquals(2, o.report.constants, v);
            assertEquals(v, o.report.formatVersion);
            assertFalse(o.report.formatVersionSupported, v);
            UnsupportedExportException e = assertThrows(UnsupportedExportException.class,
                    () -> new Oracle(data).load(LoadOptions.batch(data.length).withStrictFormatVersion(true)));
            assertTrue(e.getMessage().contains("format version"), e.getMessage());
        }
        assertTrue(h.report.formatVersionSupported);
        assertTrue(garbage.report.formatVersionSupported, "a header that is not a header announces nothing");
        // strict mode still reads every 3.x file
        byte[] plain = small().bytes();
        assertEquals(2, new Oracle(plain).load(LoadOptions.batch(plain.length).withStrictFormatVersion(true)).report.constants);
    }

    // ---- row 7: two ids for one object ---------------------------------------------------------------------

    @Test
    void namesAreInternedAndDuplicateTermsAreBothKept() throws IOException {
        Nd nd = new Nd();
        int u1 = nd.str(0, "u");
        int u2 = nd.str(0, "u");
        int l1 = nd.param(u1);
        int l2 = nd.param(u2);
        int s1 = nd.sort(l1);
        int s2 = nd.sort(l2);
        int ty = nd.sort(nd.succ(l1));
        nd.def(nd.name("d"), ty, nd.app(s1, s2), u2);
        Oracle o = load(nd);
        FakeTarget f = o.fake;
        assertEquals(2, f.names - 1, "u and d");
        int d = f.constOf[f.findName("d")];
        int app = f.cValue[d];
        assertNotEquals(f.nA[app], f.nB[app], "two expression records, two nodes");
        assertEquals(f.nA[f.nA[app]], f.nA[f.nB[app]], "but one level: the level store hash-conses");
        assertEquals(o.ls.lists().get(f.cLparams[d], 0), f.findName("u"));
    }

    // ---- row 8: numbers out of range -----------------------------------------------------------------------

    @Test
    void numbersOutOfRange() throws IOException {
        // projection index beyond an int: declined by the loader
        Nd proj = new Nd();
        int s = proj.sort(0);
        proj.proj(proj.name("S"), "2147483648", s);
        declined(proj.bytes(), "projection index");
        Nd projOk = new Nd();
        int s0 = projOk.sort(0);
        int pr = projOk.proj(projOk.name("S"), "2147483647", s0);
        projOk.axiom(projOk.name("a"), pr);
        Oracle o = load(projOk);
        int a = o.fake.constOf[o.fake.findName("a")];
        assertEquals(Integer.MAX_VALUE, o.fake.nB[o.fake.cType[a]]);
        assertEquals(1, o.report.unresolvedRefs, "structure name S is not declared");
        // ids of 2^63 and beyond are ids like any other (the official reader's ids are arbitrary
        // naturals; mutants "renumber_ids" of the test kit): the loader never sees file ids
        String two63 = "9223372036854775808";
        String huge = "340282366920938463463374607431768211456123";
        Nd big = new Nd();
        big.raw("{\"in\":" + huge + ",\"str\":{\"pre\":0,\"str\":\"u\"}}");
        big.raw("{\"in\":" + two63 + ",\"str\":{\"pre\":0,\"str\":\"c\"}}");
        big.raw("{\"il\":" + huge + ",\"param\":" + huge + "}");
        big.raw("{\"il\":" + two63 + ",\"succ\":" + huge + "}");
        big.raw("{\"ie\":" + two63 + ",\"sort\":" + two63 + "}");
        big.raw("{\"ie\":" + huge + ",\"sort\":" + huge + "}");
        big.raw("{\"axiom\":{\"isUnsafe\":false,\"levelParams\":[" + huge + "],\"name\":" + two63 + ",\"type\":" + two63 + "}}");
        big.raw("{\"ie\":18446744073709551616,\"const\":{\"name\":" + two63 + ",\"us\":[" + huge + "]}}");
        big.raw("{\"def\":{\"all\":[" + huge + "],\"hints\":\"opaque\",\"levelParams\":[" + huge + "],\"name\":" + huge
                + ",\"safety\":\"safe\",\"type\":" + huge + ",\"value\":18446744073709551616}}");
        Oracle bo = load(big);
        assertEquals(2, bo.report.constants);
        assertEquals(3, bo.report.exprRecords);
        assertTrue(bo.report.sparseIds && bo.report.readerIdTables);
        FakeTarget bf = bo.fake;
        int c = bf.constOf[bf.findName("c")];
        int u = bf.constOf[bf.findName("u")];
        assertEquals(Kind.SORT, bf.nKind[bf.cType[c]]);
        assertEquals(Kind.CONST, bf.nKind[bf.cValue[u]]);
        assertEquals(c, bf.nA[bf.cValue[u]], "the definition's value is the axiom, bound");
        // a reference to an id that was never bound is still the reader's rejection, however large
        rejected(new Nd().raw("{\"ie\":0,\"sort\":" + two63 + "}").bytes(), "Level not found");
        rejected(new Nd().raw("{\"il\":" + huge + ",\"succ\":0}").raw("{\"il\":" + huge + ",\"succ\":0}").bytes(),
                "bound twice");
        // numeric name components: below 2^63, between 2^63 and 2^64, above
        Nd names = new Nd();
        int n1 = names.num(0, "9223372036854775807");
        int n2 = names.num(0, "9223372036854775808");
        int n3 = names.num(0, "18446744073709551615");
        int n4 = names.num(0, "18446744073709551616");
        int n5 = names.num(0, "123456789012345678901234567890");
        int n2b = names.num(0, "9223372036854775808");
        int sort = names.sort(0);
        for (int n : new int[] {n1, n2, n3, n4, n5}) {
            names.axiom(n, sort);
        }
        names.axiom(names.str(n2b, "x"), sort);
        Oracle no = load(names);
        assertEquals(6, no.report.constants);
        FakeTarget f = no.fake;
        assertEquals(FakeTarget.NAME_NUM, f.nameKind[f.findName("9223372036854775808")]);
        assertEquals(FakeTarget.NAME_NUM, f.nameKind[f.findName("18446744073709551615")]);
        assertEquals(FakeTarget.NAME_NUM_BIG, f.nameKind[f.findName("18446744073709551616")]);
        assertEquals(FakeTarget.NAME_NUM_BIG, f.nameKind[f.findName("123456789012345678901234567890")]);
        assertEquals(6, f.names - 1, "the second record of 2^63 is the same name");
        // the reducibility height is reduced modulo 2^32 by the reader, as in the official reader
        Nd hint = new Nd();
        int hs = hint.sort(0);
        hint.def(hint.name("h"), hs, hs, "{\"regular\":4294967301}", "safe");
        hint.def(hint.name("g"), hs, hs, "{\"regular\":4294967295}", "safe");
        hint.def(hint.name("o"), hs, hs, "\"opaque\"", "safe");
        hint.def(hint.name("ab"), hs, hs, "\"abbrev\"", "safe");
        Oracle ho = load(hint);
        FakeTarget hf = ho.fake;
        assertEquals(DeclKind.hintRegular(5), hf.cHint[hf.constOf[hf.findName("h")]]);
        assertEquals(DeclKind.hintRegular(4294967295L), hf.cHint[hf.constOf[hf.findName("g")]]);
        assertEquals(DeclKind.HINT_OPAQUE, hf.cHint[hf.constOf[hf.findName("o")]]);
        assertEquals(DeclKind.HINT_ABBREV, hf.cHint[hf.constOf[hf.findName("ab")]]);
    }

    // ---- row 9: long lines, long numerals, strings ---------------------------------------------------------

    @Test
    void longLinesNumeralsAndStrings() throws IOException {
        Nd nd = new Nd();
        int sort = nd.sort(0);
        // Nat literals around the 64-bit boundary and far beyond
        String[] numerals = {"0", "7", "18446744073709551615", "18446744073709551616", "99999999999999999999",
                BigInteger.ONE.shiftLeft(4000).subtract(BigInteger.TEN).toString(), BigInteger.TEN.pow(30_000).toString()};
        int e = sort;
        for (String n : numerals) {
            e = nd.app(e, nd.nat(n));
        }
        // strings: every escape of the writer, U+0000, non-BMP, and a 3 MB literal on one line
        String big = "x".repeat(3_000_000);
        String[] strings = {"", "a\"b\\c\nd\re\u0000f\u001f", "\u00e9\u4e2d\ud83d\ude00", big};
        for (String s : strings) {
            e = nd.app(e, nd.strLit(s));
        }
        nd.def(nd.name("lits"), sort, e);
        Oracle o = load(nd);
        FakeTarget f = o.fake;
        assertEquals(numerals.length, o.report.exprNatLit);
        assertEquals(4, o.report.bigNatLits);
        assertEquals(4, f.nats.size());
        assertEquals(63, f.nats.get(2).length, "2^4000 - 10 has 63 limbs");
        assertEquals(strings.length, o.report.exprStrLit);
        assertEquals(3_000_000, f.strings.get(3).length);
        assertEquals("a\"b\\c\nd\re\u0000f\u001f", new String(f.strings.get(1), StandardCharsets.UTF_8));
        assertEquals("\u00e9\u4e2d\ud83d\ude00", new String(f.strings.get(2), StandardCharsets.UTF_8));
        // small literals live in the node
        int zero = o.loader.locationOf(2);
        assertEquals(Kind.NATLIT, f.nKind[zero]);
        assertFalse(f.nBig[zero]);
        int max = o.loader.locationOf(6);
        assertEquals(-1, f.nA[max]);
        assertEquals(-1, f.nB[max]);
        assertFalse(f.nBig[max]);
        assertTrue(f.nBig[o.loader.locationOf(8)]);
    }

    @Test
    void literalsInProofBlocks() throws IOException {
        Nd nd = new Nd();
        int sort = nd.sort(0);
        int e = nd.app(nd.nat("18446744073709551616"), nd.strLit("s"));
        e = nd.app(e, nd.nat("12345678901234567890"));
        e = nd.app(e, nd.proj(nd.name("S"), "3", nd.bvar(2)));
        e = nd.let(sort, e, nd.bvar(0));
        nd.thm(nd.name("t"), sort, e);
        nd.def(nd.name("again"), sort, e); // promotes the whole proof, literals included
        Oracle o = load(nd);
        assertEquals(10, o.report.blockNodes);
        assertEquals(10, o.report.promotedNodes);
    }

    // ---- row 10: properties that admission decides -------------------------------------------------------

    @Test
    void looseVariablesWrongArityAndUnknownConstantsLoad() throws IOException {
        Nd nd = new Nd();
        int sort = nd.sort(0);
        int u = nd.name("u");
        int lu = nd.param(u);
        nd.axiom(nd.name("loose"), nd.bvar(3));
        int c = nd.name("c");
        nd.axiom(c, sort);
        nd.def(nd.name("arity"), sort, nd.cnst(c, lu, lu));
        nd.def(nd.name("undeclaredParam"), nd.sort(lu), sort);
        nd.def(nd.name("unknown"), sort, nd.cnst(nd.name("nowhere")));
        nd.axiom(nd.name("dupParams"), sort, u, u);
        Oracle o = load(nd);
        assertEquals(6, o.report.constants);
        assertEquals(1, o.report.unresolvedRefs);
        int dup = o.fake.constOf[o.fake.findName("dupParams")];
        assertTrue(o.ls.listHasDuplicates(o.fake.cLparams[dup]), "duplicates are kept for admission to reject");
    }

    // ---- metadata inside inductive and quot records (fail closed; in other declarations: SplitTest) --------

    /**
     * Metadata is dropped at load. The official checker compares inductive and quot records, metadata
     * included, with what its kernel generates and tests their spines syntactically, so a record whose
     * roots carry metadata could be accepted here and rejected there: declined at load.
     * Verified with leanchecker 4.36 on hand-written files (work/integration/probe/mdata-*.ndjson).
     */
    @Test
    void metadataInsideInductiveAndQuotRecordsIsDeclined() throws IOException {
        for (int variant = 0; variant < 7; variant++) {
            Nd b = new Nd();
            int type1 = b.sort(b.succ(0));
            int t = b.name("T");
            int mk = b.str(t, "mk");
            int rec = b.str(t, "rec");
            int ct = b.cnst(t);
            // the metadata sits one level below the root in variants 3 and 4: it must propagate upwards
            int wrappedSort = variant == 0 ? b.mdata(type1) : type1;
            int ctorType = variant == 1 ? b.mdata(ct) : variant == 3 ? b.pi(b.mdata(ct), ct) : ct;
            int recType = variant == 2 ? b.mdata(ct) : ct;
            int rhs = variant == 4 ? b.app(b.lam(ct, b.bvar(0)), b.mdata(ct)) : ct;
            if (variant == 5) {
                // metadata that no declaration of the file reaches does not matter
                b.mdata(b.sort(0));
            }
            String ty = Nd.type(t, wrappedSort, 0, 0, Nd.ints(t), Nd.ints(mk), false);
            String ctor = Nd.ctor(mk, ctorType, t, 0, 0, 0, false);
            String recursor = Nd.rec(rec, recType, Nd.ints(t), 0, 0, 1, 1, false, false, Nd.ints(mk, 0, rhs));
            b.inductive(Nd.strs(ty), Nd.strs(ctor), Nd.strs(recursor));
            if (variant == 6) {
                b.quot(b.name("Quot"), b.mdata(b.pi(type1, type1)), "type");
            }
            if (variant <= 4) {
                declined(b.bytes(), "metadata (mdata) node inside an inductive record");
            } else if (variant == 5) {
                Oracle o = load(b);
                assertEquals(1, o.report.exprMData);
                assertEquals(3, o.report.constants);
            } else {
                declined(b.bytes(), "metadata (mdata) node inside a quot record");
            }
        }
    }

    // ---- row 11: unsafe inductive blocks (unsafe definitions are in SplitTest) ------------------------------

    @Test
    void unsafeInductiveBlocks() throws IOException {
        for (int variant = 0; variant < 4; variant++) {
            Nd b = new Nd();
            int type1 = b.sort(b.succ(0));
            int t = b.name("T");
            int mk = b.str(t, "mk");
            int rec = b.str(t, "rec");
            int ct = b.cnst(t);
            boolean typeUnsafe = variant != 3;
            boolean ctorUnsafe = variant != 1;
            String ty = Nd.type(t, type1, 0, 0, Nd.ints(t), Nd.ints(mk), typeUnsafe);
            String ty2 = Nd.type(b.name("T2"), type1, 0, 0, Nd.ints(t), Nd.ints(), false);
            String ctor = Nd.ctor(mk, ct, t, 0, 0, 0, ctorUnsafe);
            String recursor = Nd.rec(rec, ct, Nd.ints(t), 0, 0, 1, 1, false, true, Nd.ints(mk, 0, ct));
            b.inductive(variant == 2 ? Nd.strs(ty, ty2) : Nd.strs(ty), Nd.strs(ctor), Nd.strs(recursor));
            b.axiom(b.name("after"), type1);
            switch (variant) {
                case 0 -> {
                    // all records unsafe: dropped as a whole, names remembered
                    Oracle o = load(b);
                    assertEquals(3, o.report.droppedUnsafe);
                    assertEquals(1, o.report.constants);
                    assertEquals(1, o.report.units);
                    assertEquals(1, o.report.inductiveRecords);
                    for (String n : new String[] {"T", "T.mk", "T.rec"}) {
                        assertEquals(FakeTarget.DROPPED, o.fake.constOf[o.fake.findName(n)]);
                    }
                    // a later declaration of one of the names is a duplicate
                    b.axiom(mk, type1);
                    rejected(b.bytes(), "Duplicate declaration: T.mk");
                }
                case 1 -> {
                    // unsafe type and recursor, SAFE constructor record: the unsafe records are dropped
                    // (official: never replayed), the safe one is loaded as a unit without types and
                    // left to admission (official: "No such constructor" unless a safe type generates it)
                    Oracle o = load(b);
                    assertEquals(2, o.report.droppedUnsafe);
                    assertEquals(2, o.report.constants, "T.mk and the axiom");
                    assertEquals(2, o.report.units);
                    assertEquals(FakeTarget.DROPPED, o.fake.constOf[o.fake.findName("T")]);
                    assertEquals(FakeTarget.DROPPED, o.fake.constOf[o.fake.findName("T.rec")]);
                    int ctorCid = o.fake.constOf[o.fake.findName("T.mk")];
                    assertTrue(ctorCid > 0);
                    assertEquals(DeclKind.CTOR, o.fake.cKind[ctorCid]);
                    int[] claims = o.fake.uClaims[0];
                    assertEquals(0, BlockClaims.numTypes(claims));
                    assertEquals(1, BlockClaims.numCtors(claims));
                    assertEquals(0, BlockClaims.numRecs(claims));
                    assertEquals(ctorCid, claims[BlockClaims.ctorAt(claims, 0) + BlockClaims.C_CID]);
                    assertEquals(o.fake.findName("T"), claims[BlockClaims.ctorAt(claims, 0) + BlockClaims.C_INDUCT]);
                    // the dropped names stay claimed: a later declaration of one is a duplicate
                    b.axiom(rec, type1);
                    rejected(b.bytes(), "Duplicate declaration: T.rec");
                }
                case 2 -> declined(b.bytes(), "mixes safe and unsafe");
                default -> {
                    // safe types, unsafe constructor and recursor records: stored, flagged in the claims
                    Oracle o = load(b);
                    assertEquals(4, o.report.constants);
                    int[] claims = o.fake.uClaims[0];
                    assertEquals(BlockClaims.F_UNSAFE, claims[BlockClaims.ctorAt(claims, 0) + BlockClaims.C_FLAGS]);
                    assertEquals(BlockClaims.F_UNSAFE, claims[BlockClaims.firstRec(claims) + BlockClaims.R_FLAGS]);
                    assertEquals(0, claims[BlockClaims.typeAt(claims, 0) + BlockClaims.T_FLAGS]);
                }
            }
        }
    }

    @Test
    void droppedRecordsWithAQuotientNameAreRejected() throws IOException {
        // official: "Quotient post-check rejects the solution: Could not find quotient constant in final kernel env"
        for (String dotted : new String[] {"Quot", "Quot.mk", "Quot.lift", "Quot.ind"}) {
            for (int variant = 0; variant < 3; variant++) {
                Nd nd = new Nd();
                int sort = nd.sort(0);
                int n = nd.name(dotted);
                switch (variant) {
                    case 0 -> nd.axiom(n, sort, true);
                    case 1 -> nd.def(n, sort, sort, "\"opaque\"", "partial");
                    default -> nd.opaque(n, sort, sort, true);
                }
                MalformedExportException e = assertThrows(MalformedExportException.class, () -> new Oracle(nd.bytes()).load());
                assertEquals("Could not find quotient constant in final kernel env: " + dotted
                        + " (declared by an unsafe or partial record)", e.getMessage());
            }
        }
        // other names below Quot, and the quotient names under another root, are simply dropped
        Nd ok = new Nd();
        int sort = ok.sort(0);
        ok.axiom(ok.name("Quot.sound"), sort, true);
        ok.axiom(ok.name("Foo.mk"), sort, true);
        ok.axiom(ok.str(ok.str(0, "Quot"), "mk2"), sort, true);
        ok.axiom(ok.name("quot"), sort, true);
        ok.axiom(ok.name("keep"), sort);
        Oracle o = load(ok);
        assertEquals(4, o.report.droppedUnsafe);
        // a safe record with such a name is not the loader's business (admission compares it)
        Nd safe = new Nd();
        int s2 = safe.sort(0);
        safe.axiom(safe.name("Quot.mk"), s2);
        assertEquals(1, load(safe).report.constants);
        // an unsafe inductive block that declares Quot
        Nd b = new Nd();
        int type1 = b.sort(b.succ(0));
        int t = b.name("Quot");
        b.inductive(Nd.strs(Nd.type(t, type1, 0, 0, Nd.ints(t), Nd.ints(), true)), Nd.strs(), Nd.strs());
        rejected(b.bytes(), "Could not find quotient constant");
    }

    @Test
    void inductiveBlockClaimsKeepLargeNumbers() throws IOException {
        Nd b = new Nd();
        int type1 = b.sort(b.succ(0));
        int t = b.name("T");
        int mk = b.str(t, "mk");
        int rec = b.str(t, "rec");
        int ct = b.cnst(t);
        b.raw("{\"inductive\":{\"ctors\":[{\"cidx\":4294967296,\"induct\":" + t + ",\"isUnsafe\":false,\"levelParams\":[],\"name\":"
                + mk + ",\"numFields\":9223372036854775807,\"numParams\":5000000000,\"type\":" + ct + "}],\"recs\":[{\"all\":[" + t
                + "],\"isUnsafe\":false,\"k\":true,\"levelParams\":[],\"name\":" + rec
                + ",\"numIndices\":1,\"numMinors\":2,\"numMotives\":3,\"numParams\":4,\"rules\":[{\"ctor\":" + mk
                + ",\"nfields\":6000000000,\"rhs\":" + ct + "}],\"type\":" + ct + "}],\"types\":[{\"all\":[" + t + "],\"ctors\":[" + mk
                + "],\"isRec\":true,\"isReflexive\":true,\"isUnsafe\":false,\"levelParams\":[],\"name\":" + t
                + ",\"numIndices\":7000000000,\"numNested\":8000000000,\"numParams\":9000000000,\"type\":" + type1 + "}]}}");
        Oracle o = load(b);
        int[] c = o.fake.uClaims[0];
        int ty = BlockClaims.typeAt(c, 0);
        assertEquals(9000000000L, BlockClaims.getLong(c, ty + BlockClaims.T_NUM_PARAMS));
        assertEquals(7000000000L, BlockClaims.getLong(c, ty + BlockClaims.T_NUM_INDICES));
        assertEquals(8000000000L, BlockClaims.getLong(c, ty + BlockClaims.T_NUM_NESTED));
        assertEquals(BlockClaims.F_REC | BlockClaims.F_REFLEXIVE, c[ty + BlockClaims.T_FLAGS]);
        int ctor = BlockClaims.ctorAt(c, 0);
        assertEquals(4294967296L, BlockClaims.getLong(c, ctor + BlockClaims.C_CIDX));
        assertEquals(5000000000L, BlockClaims.getLong(c, ctor + BlockClaims.C_NUM_PARAMS));
        assertEquals(Long.MAX_VALUE, BlockClaims.getLong(c, ctor + BlockClaims.C_NUM_FIELDS));
        int r = BlockClaims.firstRec(c);
        assertEquals(BlockClaims.F_K, c[r + BlockClaims.R_FLAGS]);
        assertEquals(6000000000L, BlockClaims.getLong(c, BlockClaims.ruleAt(r, 0) + BlockClaims.RULE_NFIELDS));
        assertEquals(c.length, BlockClaims.nextRec(c, r));
    }

    @Test
    void anEmptyInductiveLineDeclaresNothing() throws IOException {
        Nd nd = new Nd();
        int sort = nd.sort(0);
        nd.inductive(Nd.strs(), Nd.strs(), Nd.strs());
        nd.axiom(nd.name("a"), sort);
        Oracle o = load(nd);
        assertEquals(2, o.report.declRecords);
        assertEquals(1, o.report.units);
        assertEquals(1, o.report.constants);
    }

    // ---- row 13: memory limits ---------------------------------------------------------------------------

    @Test
    void aSegmentBeyondTheLimitIsDeclined() throws IOException {
        Nd nd = new Nd();
        int e = nd.sort(0);
        for (int i = 0; i < 5000; i++) {
            e = nd.app(e, e);
        }
        nd.axiom(nd.name("a"), e);
        byte[] data = nd.bytes();
        UnsupportedExportException ex = assertThrows(UnsupportedExportException.class,
                () -> new Oracle(data).load(LoadOptions.batch(data.length).withMaxSegmentRecords(4096)));
        assertTrue(ex.getMessage().contains("between two declarations"), ex.getMessage());
        assertTrue(ex.getMessage().contains("line 4098"), ex.getMessage());
        new Oracle(data).load(LoadOptions.batch(data.length).withMaxSegmentRecords(8192)).verify();
    }

    @Test
    void pipelinedModeWritesTheSameCallsToAnyTarget() throws IOException {
        // the in-memory target: publication is the only difference, and the split is the batch split
        Oracle batch = load(small());
        byte[] data = small().bytes();
        Oracle piped = new Oracle(data).load(LoadOptions.pipelined(data.length).withPublishEvery(1, 1L << 40));
        piped.verify();
        assertEquals(batch.report.constants, piped.report.constants);
        assertEquals(3, piped.report.publications, "one per unit and the final one");
        assertEquals(1, batch.report.publications);
        assertEquals(piped.fake.units, piped.fake.published);
    }

    @Test
    void reportIsAvailableAfterAFailure() {
        Nd nd = small();
        nd.thm(nd.name("T"), 0, 0);
        Loader loader = new Loader(new FakeTarget(), LoadOptions.batch(0), new LevelStoreInterner(new rlvm.level.LevelStore()),
                new NatStoreDecoder());
        assertThrows(MalformedExportException.class, () -> ExportLoader.read(nd.bytes(), loader));
        LoadReport r = loader.report();
        assertFalse(r.complete);
        assertEquals(3, r.declRecords);
        assertEquals(2, r.constants);
        assertTrue(r.toString().contains("INCOMPLETE"));
    }
}

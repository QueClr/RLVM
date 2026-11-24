package rlvm.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

class ColumnarStoreTest {
    private static ColumnarStore load(String body) throws ExportException {
        ColumnarStore s = new ColumnarStore(0, true, true);
        ExportReader.read(T.utf8(T.META + body), s);
        StoreChecks.checkInvariants(s);
        return s;
    }

    @Test
    void layoutOfEveryKind() throws ExportException {
        ColumnarStore s = load(
                "{\"in\":1,\"str\":{\"pre\":0,\"str\":\"Nat\"}}\n"
                        + "{\"in\":2,\"str\":{\"pre\":1,\"str\":\"succ\"}}\n"
                        + "{\"in\":3,\"num\":{\"i\":7,\"pre\":2}}\n"
                        + "{\"in\":4,\"num\":{\"i\":123456789012345678901234567890,\"pre\":0}}\n"
                        + "{\"in\":5,\"str\":{\"pre\":0,\"str\":\"u\"}}\n"
                        + "{\"il\":1,\"succ\":0}\n"
                        + "{\"il\":2,\"param\":5}\n"
                        + "{\"il\":3,\"max\":[1,2]}\n"
                        + "{\"il\":4,\"imax\":[1,1]}\n"
                        + "{\"bvar\":0,\"ie\":0}\n" // 1
                        + "{\"bvar\":2,\"ie\":1}\n" // 2
                        + "{\"ie\":2,\"sort\":1}\n" // 3
                        + "{\"ie\":3,\"sort\":3}\n" // 4
                        + "{\"const\":{\"name\":1,\"us\":[]},\"ie\":4}\n" // 5
                        + "{\"const\":{\"name\":2,\"us\":[1,2]},\"ie\":5}\n" // 6
                        + "{\"app\":{\"arg\":1,\"fn\":5},\"ie\":6}\n" // 7: succ.{1,u} #2
                        + "{\"ie\":7,\"lam\":{\"binderInfo\":\"implicit\",\"body\":6,\"name\":5,\"type\":2}}\n" // 8
                        + "{\"forallE\":{\"binderInfo\":\"instImplicit\",\"body\":0,\"name\":1,\"type\":3},\"ie\":8}\n" // 9
                        + "{\"ie\":9,\"letE\":{\"body\":1,\"name\":5,\"nondep\":true,\"type\":2,\"value\":4}}\n" // 10
                        + "{\"ie\":10,\"proj\":{\"idx\":3,\"struct\":6,\"typeName\":1}}\n" // 11
                        + "{\"ie\":11,\"natVal\":\"00123\"}\n" // 12
                        + "{\"ie\":12,\"strVal\":\"h\\u00e9\"}\n" // 13
                        + "{\"ie\":13,\"mdata\":{\"data\":{},\"expr\":6}}\n"); // 14
        assertEquals(6, s.nameCount());
        assertEquals(ColumnarStore.NAME_STR, s.nameKind(2));
        assertEquals("Nat", s.nameString(2));
        assertEquals(ColumnarStore.ANONYMOUS, s.namePrefix(2));
        assertEquals(2, s.namePrefix(3));
        assertEquals(ColumnarStore.NAME_NUM, s.nameKind(4));
        assertEquals(7, s.nameNumber(4));
        assertEquals(ColumnarStore.NAME_NUM_BIG, s.nameKind(5));
        assertEquals("Nat.succ.7", s.nameToString(4));
        assertEquals("123456789012345678901234567890", s.nameToString(5));
        assertEquals("[anonymous]", s.nameToString(1));

        assertEquals(5, s.levelCount());
        assertEquals(ColumnarStore.LEVEL_SUCC, s.levelKind(2));
        assertEquals(ColumnarStore.ZERO, s.levelA(2));
        assertFalse(s.levelHasParam(2));
        assertEquals(ColumnarStore.LEVEL_PARAM, s.levelKind(3));
        assertEquals(6, s.levelA(3));
        assertTrue(s.levelHasParam(3));
        assertEquals(ColumnarStore.LEVEL_MAX, s.levelKind(4));
        assertTrue(s.levelHasParam(4));
        assertEquals(ColumnarStore.LEVEL_IMAX, s.levelKind(5));
        assertFalse(s.levelHasParam(5));

        assertEquals(14, s.exprCount());
        assertEquals(ColumnarStore.EXPR_BVAR, s.exprKind(1));
        assertEquals(1, s.exprLoose(1));
        assertEquals(1L, s.exprMask(1));
        assertEquals(3, s.exprLoose(2));
        assertEquals(4L, s.exprMask(2));
        assertEquals(ColumnarStore.EXPR_SORT, s.exprKind(3));
        assertEquals(2, s.exprA(3));
        assertFalse(s.exprHasLevelParam(3));
        assertTrue(s.exprHasLevelParam(4));
        assertEquals(ColumnarStore.EXPR_CONST, s.exprKind(5));
        assertEquals(0, s.constLevelCount(5));
        assertEquals(2, s.constLevelCount(6));
        assertEquals(2, s.constLevel(6, 0));
        assertEquals(3, s.constLevel(6, 1));
        assertTrue(s.exprHasLevelParam(6));
        assertEquals(ColumnarStore.EXPR_APP, s.exprKind(7));
        assertEquals(6, s.exprA(7));
        assertEquals(2, s.exprB(7));
        assertEquals(3, s.exprLoose(7));
        assertTrue(s.exprHasLevelParam(7));
        // lam (type := Sort 1) (body := succ #2): index 2 of the body is index 1 outside
        assertEquals(ColumnarStore.EXPR_LAM, s.exprKind(8));
        assertEquals(ExportSink.BINDER_IMPLICIT, s.binderInfo(8));
        assertEquals(3, s.exprA(8));
        assertEquals(7, s.exprB(8));
        assertEquals(2, s.exprLoose(8));
        assertEquals(2L, s.exprMask(8));
        assertEquals(6, s.exprBinderNameColumn()[8]);
        // forallE (type := Sort (max 1 u)) (body := #0): closed
        assertEquals(ColumnarStore.EXPR_PI, s.exprKind(9));
        assertEquals(ExportSink.BINDER_INST_IMPLICIT, s.binderInfo(9));
        assertEquals(0, s.exprLoose(9));
        assertEquals(0L, s.exprMask(9));
        assertTrue(s.exprHasLevelParam(9));
        // let (type := Sort 1) (value := Nat) (body := #2)
        assertEquals(ColumnarStore.EXPR_LET, s.exprKind(10));
        assertEquals(3, s.exprA(10));
        assertEquals(5, s.letValue(10));
        assertEquals(2, s.letBody(10));
        assertTrue(s.letNondep(10));
        assertEquals(2, s.exprLoose(10));
        assertEquals(2L, s.exprMask(10));
        assertEquals(ColumnarStore.EXPR_PROJ, s.exprKind(11));
        assertEquals(7, s.exprA(11));
        assertEquals(2, s.projTypeName(11));
        assertEquals(3, s.projIndex(11));
        assertEquals(3, s.exprLoose(11));
        assertEquals(ColumnarStore.EXPR_NATLIT, s.exprKind(12));
        assertEquals("123", s.literal(12));
        assertEquals(ColumnarStore.EXPR_STRLIT, s.exprKind(13));
        assertEquals("hé", s.literal(13));
        assertEquals(ColumnarStore.EXPR_MDATA, s.exprKind(14));
        assertEquals(7, s.exprA(14));
        assertEquals(3, s.exprLoose(14));
        assertTrue(s.exprHasLevelParam(14));
        assertTrue(s.memoryBytes() > 0);
    }

    @Test
    void namesAreHashConsed() throws ExportException {
        ColumnarStore s = load(
                "{\"in\":1,\"str\":{\"pre\":0,\"str\":\"a\"}}\n"
                        + "{\"in\":2,\"str\":{\"pre\":1,\"str\":\"b\"}}\n"
                        + "{\"in\":7,\"str\":{\"pre\":0,\"str\":\"a\"}}\n" // again a
                        + "{\"in\":8,\"str\":{\"pre\":7,\"str\":\"\\u0062\"}}\n" // again a.b
                        + "{\"in\":9,\"num\":{\"i\":5,\"pre\":8}}\n"
                        + "{\"in\":10,\"num\":{\"i\":5e0,\"pre\":2}}\n" // again a.b.5
                        + "{\"in\":11,\"str\":{\"pre\":0,\"str\":\"a.b\"}}\n" // a different name
                        + "{\"in\":12,\"num\":{\"i\":18446744073709551616,\"pre\":0}}\n"
                        + "{\"in\":13,\"num\":{\"i\":18446744073709551616,\"pre\":0}}\n"
                        + "{\"in\":14,\"str\":{\"pre\":0,\"str\":\"5\"}}\n"
                        + "{\"in\":15,\"num\":{\"i\":5,\"pre\":0}}\n"
                        + "{\"ie\":0,\"sort\":0}\n"
                        + "{\"axiom\":{\"isUnsafe\":false,\"levelParams\":[],\"name\":2,\"type\":0}}\n"
                        + "{\"axiom\":{\"isUnsafe\":false,\"levelParams\":[],\"name\":11,\"type\":0}}\n");
        // anonymous, a, a.b, a.b.5, "a.b", 2^64, "5", 5
        assertEquals(8, s.nameCount());
        assertEquals(2, s.constCount());
        assertEquals("a.b", s.nameToString(s.constName(1)));
        assertEquals("a.b", s.nameToString(s.constName(2)));
        assertTrue(s.constName(1) != s.constName(2));
    }

    @Test
    void aSecondDeclarationOfANameIsRejectedWhateverItsIds() {
        String pre = "{\"in\":1,\"str\":{\"pre\":0,\"str\":\"a\"}}\n"
                + "{\"in\":2,\"str\":{\"pre\":0,\"str\":\"a\"}}\n"
                + "{\"ie\":0,\"sort\":0}\n";
        String ax1 = "{\"axiom\":{\"isUnsafe\":false,\"levelParams\":[],\"name\":1,\"type\":0}}\n";
        String ax2 = "{\"axiom\":{\"isUnsafe\":false,\"levelParams\":[],\"name\":2,\"type\":0}}\n";
        String thm2 = "{\"thm\":{\"all\":[2],\"levelParams\":[],\"name\":2,\"type\":0,\"value\":0}}\n";
        String def2 = "{\"def\":{\"all\":[2],\"hints\":\"abbrev\",\"levelParams\":[],\"name\":2,\"safety\":\"safe\",\"type\":0,\"value\":0}}\n";
        String ind2 = "{\"inductive\":{\"ctors\":[],\"recs\":[],\"types\":[{\"all\":[1],\"ctors\":[],\"isRec\":false,\"isReflexive\":false,\"isUnsafe\":false,\"levelParams\":[],\"name\":2,\"numIndices\":0,\"numNested\":0,\"numParams\":0,\"type\":0}]}}\n";
        String ctor2 = "{\"inductive\":{\"ctors\":[{\"cidx\":0,\"induct\":1,\"isUnsafe\":false,\"levelParams\":[],\"name\":2,\"numFields\":0,\"numParams\":0,\"type\":0}],\"recs\":[],\"types\":[]}}\n";
        String rec2 = "{\"inductive\":{\"ctors\":[],\"recs\":[{\"all\":[1],\"isUnsafe\":false,\"k\":false,\"levelParams\":[],\"name\":2,\"numIndices\":0,\"numMinors\":0,\"numMotives\":0,\"numParams\":0,\"rules\":[],\"type\":0}],\"types\":[]}}\n";
        String quot2 = "{\"quot\":{\"kind\":\"type\",\"levelParams\":[],\"name\":2,\"type\":0}}\n";
        String opaque2 = "{\"opaque\":{\"all\":[2],\"isUnsafe\":false,\"levelParams\":[],\"name\":2,\"type\":0,\"value\":0}}\n";
        for (String second : new String[] {ax2, thm2, def2, ind2, ctor2, rec2, quot2, opaque2, ax1}) {
            ExportException e = assertThrows(ExportException.class,
                    () -> ExportReader.read(T.utf8(T.META + pre + ax1 + second), new ColumnarStore()), second);
            assertEquals(ExportException.Kind.MALFORMED, e.kind());
            assertEquals(6, e.line());
            assertEquals("Duplicate declaration: a", e.reason());
        }
        // inside one block
        ExportException e = assertThrows(ExportException.class, () -> ExportReader.read(T.utf8(T.META + pre
                + "{\"inductive\":{\"ctors\":[{\"cidx\":0,\"induct\":1,\"isUnsafe\":false,\"levelParams\":[],\"name\":2,\"numFields\":0,\"numParams\":0,\"type\":0}],\"recs\":[],"
                + "\"types\":[{\"all\":[1],\"ctors\":[],\"isRec\":false,\"isReflexive\":false,\"isUnsafe\":false,\"levelParams\":[],\"name\":1,\"numIndices\":0,\"numNested\":0,\"numParams\":0,\"type\":0}]}}\n"),
                new ColumnarStore()));
        assertEquals("Duplicate declaration: a", e.reason());
        assertEquals(5, e.line());
    }

    @Test
    void constantsAndDeclarations() throws ExportException {
        ColumnarStore s = load(
                "{\"in\":1,\"str\":{\"pre\":0,\"str\":\"c\"}}\n"
                        + "{\"in\":2,\"str\":{\"pre\":0,\"str\":\"u\"}}\n"
                        + "{\"in\":3,\"str\":{\"pre\":1,\"str\":\"mk\"}}\n"
                        + "{\"in\":4,\"str\":{\"pre\":1,\"str\":\"rec\"}}\n"
                        + "{\"in\":5,\"str\":{\"pre\":0,\"str\":\"d\"}}\n"
                        + "{\"in\":6,\"str\":{\"pre\":0,\"str\":\"t\"}}\n"
                        + "{\"in\":7,\"str\":{\"pre\":0,\"str\":\"o\"}}\n"
                        + "{\"in\":8,\"str\":{\"pre\":0,\"str\":\"q\"}}\n"
                        + "{\"in\":9,\"str\":{\"pre\":0,\"str\":\"ax\"}}\n"
                        + "{\"ie\":0,\"sort\":0}\n"
                        + "{\"inductive\":{\"ctors\":[{\"cidx\":0,\"induct\":1,\"isUnsafe\":false,\"levelParams\":[2],\"name\":3,\"numFields\":3,\"numParams\":2,\"type\":0}],"
                        + "\"recs\":[{\"all\":[1],\"isUnsafe\":false,\"k\":true,\"levelParams\":[2,2],\"name\":4,\"numIndices\":5,\"numMinors\":6,\"numMotives\":7,\"numParams\":8,"
                        + "\"rules\":[{\"ctor\":3,\"nfields\":3,\"rhs\":0}],\"type\":0}],"
                        + "\"types\":[{\"all\":[1],\"ctors\":[3],\"isRec\":true,\"isReflexive\":false,\"isUnsafe\":true,\"levelParams\":[2],\"name\":1,\"numIndices\":4,\"numNested\":9,\"numParams\":99999999999,\"type\":0}]}}\n"
                        + "{\"ie\":1,\"sort\":0}\n"
                        + "{\"def\":{\"all\":[5,6],\"hints\":{\"regular\":12},\"levelParams\":[2],\"name\":5,\"safety\":\"partial\",\"type\":0,\"value\":1}}\n"
                        + "{\"thm\":{\"all\":[6],\"levelParams\":[],\"name\":6,\"type\":1,\"value\":0}}\n"
                        + "{\"opaque\":{\"all\":[],\"isUnsafe\":true,\"levelParams\":[],\"name\":7,\"type\":0,\"value\":1}}\n"
                        + "{\"quot\":{\"kind\":\"lift\",\"levelParams\":[2],\"name\":8,\"type\":0}}\n"
                        + "{\"axiom\":{\"isUnsafe\":true,\"levelParams\":[],\"name\":9,\"type\":1}}\n");
        assertEquals(8, s.constCount());
        assertEquals(6, s.declCount());
        // block order: type, constructor, recursor
        assertEquals(ColumnarStore.CONST_INDUCT, s.constKind(1));
        assertEquals("c", s.nameToString(s.constName(1)));
        assertEquals(Integer.MAX_VALUE, s.constField(1, 0)); // numParams saturates
        assertEquals(4, s.constField(1, 1));
        assertEquals(9, s.constField(1, 2));
        assertEquals(1 | 2, s.constField(1, 3));
        assertEquals(1, s.listSize(s.constField(1, 4)));
        assertEquals(s.constName(1), s.listGet(s.constField(1, 4), 0));
        assertEquals(s.constName(2), s.listGet(s.constField(1, 5), 0));
        assertEquals(ColumnarStore.CONST_CTOR, s.constKind(2));
        assertEquals("c.mk", s.nameToString(s.constName(2)));
        assertEquals(s.constName(1), s.constField(2, 0));
        assertEquals(0, s.constField(2, 1));
        assertEquals(2, s.constField(2, 2));
        assertEquals(3, s.constField(2, 3));
        assertEquals(0, s.constField(2, 4));
        assertEquals(ColumnarStore.CONST_REC, s.constKind(3));
        assertEquals(8, s.constField(3, 0));
        assertEquals(5, s.constField(3, 1));
        assertEquals(7, s.constField(3, 2));
        assertEquals(6, s.constField(3, 3));
        assertEquals(1, s.constField(3, 4));
        assertEquals(1, s.constField(3, 6));
        assertEquals(s.constName(2), s.constField(3, 7));
        assertEquals(3, s.constField(3, 8));
        assertEquals(1, s.constField(3, 9));
        assertEquals(2, s.listSize(s.constLevelParamsColumn()[3]));
        for (int c = 1; c <= 3; c++) {
            assertEquals(1, s.constDeclColumn()[c]);
        }
        assertEquals(1, s.declFirstConstColumn()[1]);
        assertEquals(1, s.declExprEndColumn()[1]);
        assertEquals(ColumnarStore.CONST_DEF, s.constKind(4));
        assertEquals(2, s.constDeclColumn()[4]);
        assertEquals(2, s.declExprEndColumn()[2]);
        assertEquals(1, s.constType(4));
        assertEquals(2, s.constValue(4));
        assertEquals(ExportSink.HINT_REGULAR, s.constField(4, 0));
        assertEquals(12, s.constField(4, 1));
        assertEquals(ExportSink.SAFETY_PARTIAL, s.constField(4, 2));
        assertEquals(2, s.listSize(s.constField(4, 3)));
        assertEquals(ColumnarStore.CONST_THM, s.constKind(5));
        assertEquals(2, s.constType(5));
        assertEquals(1, s.constValue(5));
        assertEquals(ColumnarStore.CONST_OPAQUE, s.constKind(6));
        assertEquals(1, s.constField(6, 0));
        assertEquals(0, s.listSize(s.constField(6, 1)));
        assertEquals(ColumnarStore.CONST_QUOT, s.constKind(7));
        assertEquals(ExportSink.QUOT_LIFT, s.constField(7, 0));
        assertEquals(ColumnarStore.CONST_AXIOM, s.constKind(8));
        assertEquals(1, s.constField(8, 0));
        assertEquals(8, s.constOfName(s.constName(8)));
        assertEquals(0, s.constOfName(3)); // "u" is not a constant
    }

    @Test
    void header() throws ExportException {
        ColumnarStore s = load("");
        assertTrue(s.metaWellFormed());
        assertEquals("lean4export", s.exporterName());
        assertEquals("3.1.0", s.exporterVersion());
        assertEquals("3.1.0", s.formatVersion());
        assertEquals("abc", s.leanGithash());
        assertEquals("4.36.0", s.leanVersion());
        ColumnarStore t = new ColumnarStore();
        ExportReader.read(T.utf8("{\"meta\":{\"format\":{\"version\":7},\"lean\":[]}}\n"), t);
        assertTrue(t.metaWellFormed());
        assertNull(t.formatVersion());
        assertNull(t.exporterName());
        assertNull(t.leanVersion());
        ColumnarStore u = new ColumnarStore();
        ExportReader.read(T.utf8("{\"in\":1,\"str\":{\"pre\":0,\"str\":\"a\"}}\n"), u);
        assertFalse(u.metaWellFormed());
        assertEquals(1, u.nameCount());
    }

    // ------------------------------------------------------------------------------ metadata, brute force

    /** A random expression DAG written as an export; {@code kinds[i]} etc. describe expression i. */
    private static final class Dag {
        final List<int[]> nodes = new ArrayList<>(); // {kind, x, y, z}
        final StringBuilder text = new StringBuilder(T.META);

        Dag(Random rnd, int size, int maxIndex) {
            text.append("{\"in\":1,\"str\":{\"pre\":0,\"str\":\"u\"}}\n{\"il\":1,\"param\":1}\n{\"il\":2,\"succ\":0}\n");
            for (int i = 0; i < size; i++) {
                int k = i < 3 ? rnd.nextInt(3) : rnd.nextInt(10);
                int x = i == 0 ? 0 : rnd.nextBoolean() ? i - 1 : rnd.nextInt(i);
                int y = i == 0 ? 0 : rnd.nextInt(i);
                int z = i == 0 ? 0 : rnd.nextInt(i);
                switch (k) {
                    case 0 -> {
                        int idx = rnd.nextInt(4) == 0 ? rnd.nextInt(maxIndex) : rnd.nextInt(3);
                        nodes.add(new int[] {ColumnarStore.EXPR_BVAR, idx, 0, 0});
                        text.append("{\"bvar\":").append(idx).append(",\"ie\":").append(i).append("}\n");
                    }
                    case 1 -> {
                        int level = rnd.nextInt(3);
                        nodes.add(new int[] {ColumnarStore.EXPR_SORT, level, 0, 0});
                        text.append("{\"ie\":").append(i).append(",\"sort\":").append(level).append("}\n");
                    }
                    case 2 -> {
                        int level = rnd.nextInt(3);
                        nodes.add(new int[] {ColumnarStore.EXPR_CONST, level, 0, 0});
                        text.append("{\"const\":{\"name\":1,\"us\":[2,").append(level).append("]},\"ie\":").append(i).append("}\n");
                    }
                    case 3, 4, 5 -> {
                        nodes.add(new int[] {ColumnarStore.EXPR_APP, x, y, 0});
                        text.append("{\"app\":{\"arg\":").append(y).append(",\"fn\":").append(x).append("},\"ie\":").append(i).append("}\n");
                    }
                    case 6 -> {
                        nodes.add(new int[] {ColumnarStore.EXPR_LAM, y, x, 0});
                        text.append("{\"ie\":").append(i).append(",\"lam\":{\"binderInfo\":\"default\",\"body\":").append(x)
                                .append(",\"name\":1,\"type\":").append(y).append("}}\n");
                    }
                    case 7 -> {
                        nodes.add(new int[] {ColumnarStore.EXPR_PI, y, x, 0});
                        text.append("{\"forallE\":{\"binderInfo\":\"default\",\"body\":").append(x).append(",\"name\":1,\"type\":")
                                .append(y).append("},\"ie\":").append(i).append("}\n");
                    }
                    case 8 -> {
                        nodes.add(new int[] {ColumnarStore.EXPR_LET, y, z, x});
                        text.append("{\"ie\":").append(i).append(",\"letE\":{\"body\":").append(x).append(",\"name\":1,\"nondep\":false,\"type\":")
                                .append(y).append(",\"value\":").append(z).append("}}\n");
                    }
                    default -> {
                        nodes.add(new int[] {ColumnarStore.EXPR_PROJ, x, 0, 0});
                        text.append("{\"ie\":").append(i).append(",\"proj\":{\"idx\":0,\"struct\":").append(x).append(",\"typeName\":1}}\n");
                    }
                }
            }
        }

        /** The loose de Bruijn indices of node i, from the definition. */
        TreeSet<Integer> loose(int i, List<TreeSet<Integer>> memo) {
            int[] n = nodes.get(i);
            TreeSet<Integer> r = new TreeSet<>();
            switch (n[0]) {
                case ColumnarStore.EXPR_BVAR -> r.add(n[1]);
                case ColumnarStore.EXPR_APP -> {
                    r.addAll(memo.get(n[1]));
                    r.addAll(memo.get(n[2]));
                }
                case ColumnarStore.EXPR_LAM, ColumnarStore.EXPR_PI -> {
                    r.addAll(memo.get(n[1]));
                    for (int v : memo.get(n[2])) {
                        if (v > 0) {
                            r.add(v - 1);
                        }
                    }
                }
                case ColumnarStore.EXPR_LET -> {
                    r.addAll(memo.get(n[1]));
                    r.addAll(memo.get(n[2]));
                    for (int v : memo.get(n[3])) {
                        if (v > 0) {
                            r.add(v - 1);
                        }
                    }
                }
                case ColumnarStore.EXPR_PROJ -> r.addAll(memo.get(n[1]));
                default -> {
                }
            }
            return r;
        }

        boolean levelParam(int i, boolean[] memo) {
            int[] n = nodes.get(i);
            return switch (n[0]) {
                case ColumnarStore.EXPR_SORT, ColumnarStore.EXPR_CONST -> n[1] == 1; // level 1 is the parameter u
                case ColumnarStore.EXPR_APP, ColumnarStore.EXPR_LAM, ColumnarStore.EXPR_PI -> memo[n[1]] || memo[n[2]];
                case ColumnarStore.EXPR_LET -> memo[n[1]] || memo[n[2]] || memo[n[3]];
                case ColumnarStore.EXPR_PROJ -> memo[n[1]];
                default -> false;
            };
        }
    }

    @Test
    void looseRangesMasksAndLevelParamFlagsMatchTheDefinition() throws ExportException {
        Random rnd = new Random(99);
        for (int round = 0; round < 300; round++) {
            int maxIndex = round % 3 == 0 ? 200 : round % 3 == 1 ? 70 : 8;
            Dag dag = new Dag(rnd, 5 + rnd.nextInt(150), maxIndex);
            ColumnarStore s = new ColumnarStore(0, true, false);
            ExportReader.read(T.utf8(dag.text.toString()), s);
            StoreChecks.checkInvariants(s);
            List<TreeSet<Integer>> looseSets = new ArrayList<>();
            boolean[] lp = new boolean[dag.nodes.size()];
            for (int i = 0; i < dag.nodes.size(); i++) {
                TreeSet<Integer> set = dag.loose(i, looseSets);
                looseSets.add(set);
                lp[i] = dag.levelParam(i, lp);
                int id = i + 1;
                assertEquals(set.isEmpty() ? 0 : set.last() + 1, s.exprLoose(id), "loose range of " + i);
                assertEquals(lp[i], s.exprHasLevelParam(id), "level param flag of " + i);
                long exact = 0;
                for (int v : set) {
                    exact |= 1L << Math.min(v, 63);
                }
                long mask = s.exprMask(id);
                // never misses an index; exact below bit 62 as long as nothing above 62 occurs
                assertEquals(exact, mask & exact, "mask of " + i + " misses an index");
                assertEquals(exact < 0, mask < 0, "bit 63 of the mask of " + i);
                if (set.isEmpty() || set.last() <= 62) {
                    assertEquals(exact, mask, "mask of " + i);
                }
                assertEquals((exact & 1) != 0, (mask & 1) != 0, "bit 0 must be exact for " + i + " unless indices >= 63 were shifted down");
            }
        }
    }

    @Test
    void growsFromTheSmallestCapacity() throws ExportException {
        Random rnd = new Random(5);
        byte[] data = Gen.file(rnd, 30000, true, 0.0, false);
        ColumnarStore small = new ColumnarStore(0, true, true);
        ExportReader.Result r = ExportReader.read(data, small);
        StoreChecks.checkInvariants(small);
        assertEquals(r.exprs, small.exprCount());
        ColumnarStore sized = new ColumnarStore(data.length, false, false);
        ExportReader.read(data, sized);
        StoreChecks.checkInvariants(sized);
        assertEquals(small.exprCount(), sized.exprCount());
        assertNull(sized.exprMaskColumn());
        assertNull(sized.exprBinderNameColumn());
        for (int e = 1; e <= small.exprCount(); e++) {
            assertEquals(small.exprHeadColumn()[e], sized.exprHeadColumn()[e]);
            assertEquals(small.exprA(e), sized.exprA(e));
        }
    }
}

package rlvm.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Every record kind in its canonical form, with the exact sink calls it must produce. */
class RecordKindsTest {
    private static String trace(String... lines) {
        StringBuilder sb = new StringBuilder(T.META);
        for (String l : lines) {
            sb.append(l).append('\n');
        }
        T.Run r = T.agree("kinds", T.utf8(sb.toString()));
        T.assertOk(r);
        return r.trace.substring(r.trace.indexOf('\n') + 1); // without the meta event
    }

    @Test
    void header() {
        T.Run r = T.agree("meta", T.utf8(T.META));
        T.assertOk(r);
        assertEquals("meta 1 lean4export|3.1.0|3.1.0|abc|4.36.0\n", r.trace);
        assertEquals(1, r.result.lines);
        assertEquals(1, r.result.names);
        assertEquals(1, r.result.levels);
        assertEquals(0, r.result.exprs);
    }

    @Test
    void names() {
        assertEquals(
                "nameStr 1 2 \"Nat\"\n"
                        + "nameStr 2 3 \"succ\"\n"
                        + "nameNum 3 7 4\n"
                        + "nameNum 1 4283319217 5\n"
                        + "nameNum 1 999999999999999 6\n"
                        + "nameStr 1 7 \"\"\n"
                        + "nameStr 1 8 \"a.b c\"\n",
                trace(
                        "{\"in\":1,\"str\":{\"pre\":0,\"str\":\"Nat\"}}",
                        "{\"in\":2,\"str\":{\"pre\":1,\"str\":\"succ\"}}",
                        "{\"in\":3,\"num\":{\"i\":7,\"pre\":2}}",
                        "{\"in\":4,\"num\":{\"i\":4283319217,\"pre\":0}}",
                        "{\"in\":5,\"num\":{\"i\":999999999999999,\"pre\":0}}",
                        "{\"in\":6,\"str\":{\"pre\":0,\"str\":\"\"}}",
                        "{\"in\":7,\"str\":{\"pre\":0,\"str\":\"a.b c\"}}"));
    }

    @Test
    void nameStringsWithEscapesAndUnicode() {
        assertEquals(
                "nameStr 1 2 \"\\u03b1\"\n"
                        + "nameStr 1 3 \"\\u03b1\\u03b2\"\n"
                        + "nameStr 1 4 \"a\\u0022b\\u005cc\\u000ad\\u000d\"\n"
                        + "nameStr 1 5 \"\\ud83d\\ude00\"\n"
                        + "nameStr 1 6 \"\\ud83d\\ude00\"\n"
                        + "nameStr 1 7 \"\\u0000\\u007f\\u0001\"\n"
                        + "nameStr 1 8 \"\\ufffd\"\n"
                        + "nameStr 1 9 \"\\ufffdA\"\n"
                        + "nameStr 1 10 \"\\ufffd\\ufffd\"\n"
                        + "nameStr 1 11 \"/\\u0008\\u000c\\u0009\"\n",
                trace(
                        "{\"in\":1,\"str\":{\"pre\":0,\"str\":\"α\"}}",
                        "{\"in\":2,\"str\":{\"pre\":0,\"str\":\"\\u03b1\\u03B2\"}}",
                        "{\"in\":3,\"str\":{\"pre\":0,\"str\":\"a\\\"b\\\\c\\nd\\r\"}}",
                        "{\"in\":4,\"str\":{\"pre\":0,\"str\":\"😀\"}}",
                        "{\"in\":5,\"str\":{\"pre\":0,\"str\":\"\\ud83d\\uDE00\"}}",
                        "{\"in\":6,\"str\":{\"pre\":0,\"str\":\"\\u0000\u007f\\u0001\"}}",
                        "{\"in\":7,\"str\":{\"pre\":0,\"str\":\"\\ud83d\"}}",
                        "{\"in\":8,\"str\":{\"pre\":0,\"str\":\"\\ude00A\"}}",
                        "{\"in\":9,\"str\":{\"pre\":0,\"str\":\"\\ud83d\\ud83d\"}}",
                        "{\"in\":10,\"str\":{\"pre\":0,\"str\":\"\\/\\b\\f\\t\"}}"));
    }

    @Test
    void bigNumericNameComponent() {
        assertEquals(
                "nameNum 1 9223372036854775807 2\n"
                        + "nameNumBig 1 3 \"9223372036854775808\"\n"
                        + "nameNumBig 1 4 \"123456789012345678901234567890\"\n"
                        + "nameNum 1 100 5\n",
                trace(
                        "{\"in\":1,\"num\":{\"i\":9223372036854775807,\"pre\":0}}",
                        "{\"in\":2,\"num\":{\"i\":9223372036854775808,\"pre\":0}}",
                        "{\"in\":3,\"num\":{\"i\":123456789012345678901234567890,\"pre\":0}}",
                        "{\"in\":4,\"num\":{\"i\":1e2,\"pre\":0}}"));
    }

    @Test
    void levels() {
        assertEquals(
                "nameStr 1 2 \"u\"\n"
                        + "succ 1 2\n"
                        + "param 2 3\n"
                        + "max 2 3 4\n"
                        + "imax 4 1 5\n",
                trace(
                        "{\"in\":1,\"str\":{\"pre\":0,\"str\":\"u\"}}",
                        "{\"il\":1,\"succ\":0}",
                        "{\"il\":2,\"param\":1}",
                        "{\"il\":3,\"max\":[1,2]}",
                        "{\"il\":4,\"imax\":[3,0]}"));
    }

    @Test
    void expressions() {
        assertEquals(
                "nameStr 1 2 \"x\"\n"
                        + "succ 1 2\n"
                        + "bvar 0 1\n"
                        + "sort 2 2\n"
                        + "const 2 3\n  us 0 []\n"
                        + "const 2 4\n  us 3 [1,2,2]\n"
                        + "app 3 1 5\n"
                        + "lam 2 0 2 1 6\n"
                        + "lam 1 1 2 1 7\n"
                        + "lam 2 2 2 1 8\n"
                        + "lam 2 3 2 1 9\n"
                        + "pi 2 0 2 1 10\n"
                        + "pi 2 1 2 1 11\n"
                        + "pi 2 2 2 1 12\n"
                        + "pi 2 3 2 1 13\n"
                        + "let 2 2 3 1 0 14\n"
                        + "let 2 2 3 1 1 15\n"
                        + "proj 2 3 5 16\n"
                        + "natLit 17 \"0\"\n"
                        + "natLit 18 \"18446744073709551616\"\n"
                        + "strLit 19 \"h\\u00e9\\u20ac\\ud83d\\ude00\"\n"
                        + "strLit 20 \"\"\n"
                        + "mdata 5 21\n"
                        + "bvar 1048574 22\n",
                trace(
                        "{\"in\":1,\"str\":{\"pre\":0,\"str\":\"x\"}}",
                        "{\"il\":1,\"succ\":0}",
                        "{\"bvar\":0,\"ie\":0}",
                        "{\"ie\":1,\"sort\":1}",
                        "{\"const\":{\"name\":1,\"us\":[]},\"ie\":2}",
                        "{\"const\":{\"name\":1,\"us\":[0,1,1]},\"ie\":3}",
                        "{\"app\":{\"arg\":0,\"fn\":2},\"ie\":4}",
                        "{\"ie\":5,\"lam\":{\"binderInfo\":\"default\",\"body\":0,\"name\":1,\"type\":1}}",
                        "{\"ie\":6,\"lam\":{\"binderInfo\":\"implicit\",\"body\":0,\"name\":0,\"type\":1}}",
                        "{\"ie\":7,\"lam\":{\"binderInfo\":\"strictImplicit\",\"body\":0,\"name\":1,\"type\":1}}",
                        "{\"ie\":8,\"lam\":{\"binderInfo\":\"instImplicit\",\"body\":0,\"name\":1,\"type\":1}}",
                        "{\"forallE\":{\"binderInfo\":\"default\",\"body\":0,\"name\":1,\"type\":1},\"ie\":9}",
                        "{\"forallE\":{\"binderInfo\":\"implicit\",\"body\":0,\"name\":1,\"type\":1},\"ie\":10}",
                        "{\"forallE\":{\"binderInfo\":\"strictImplicit\",\"body\":0,\"name\":1,\"type\":1},\"ie\":11}",
                        "{\"forallE\":{\"binderInfo\":\"instImplicit\",\"body\":0,\"name\":1,\"type\":1},\"ie\":12}",
                        "{\"ie\":13,\"letE\":{\"body\":0,\"name\":1,\"nondep\":false,\"type\":1,\"value\":2}}",
                        "{\"ie\":14,\"letE\":{\"body\":0,\"name\":1,\"nondep\":true,\"type\":1,\"value\":2}}",
                        "{\"ie\":15,\"proj\":{\"idx\":3,\"struct\":4,\"typeName\":1}}",
                        "{\"ie\":16,\"natVal\":\"0\"}",
                        "{\"ie\":17,\"natVal\":\"18446744073709551616\"}",
                        "{\"ie\":18,\"strVal\":\"hé€😀\"}",
                        "{\"ie\":19,\"strVal\":\"\"}",
                        "{\"ie\":20,\"mdata\":{\"data\":{\"k\":[1,{\"x\":null}]},\"expr\":4}}",
                        "{\"bvar\":1048574,\"ie\":21}"));
    }

    @Test
    void natLiteralForms() {
        assertEquals(
                "natLit 1 \"7\"\n" + "natLit 2 \"1000\"\n" + "natLit 3 \"0\"\n" + "natLit 4 \"120\"\n",
                trace(
                        "{\"ie\":0,\"natVal\":\"007\"}",
                        "{\"ie\":1,\"natVal\":\"1_000\"}",
                        "{\"ie\":2,\"natVal\":\"0_0\"}",
                        "{\"ie\":3,\"natVal\":\"\\u0031\\u0032\\u0030\"}"));
    }

    private static final String[] PRELUDE = {
        "{\"in\":1,\"str\":{\"pre\":0,\"str\":\"c\"}}",
        "{\"in\":2,\"str\":{\"pre\":0,\"str\":\"u\"}}",
        "{\"in\":3,\"str\":{\"pre\":1,\"str\":\"mk\"}}",
        "{\"in\":4,\"str\":{\"pre\":1,\"str\":\"rec\"}}",
        "{\"il\":1,\"succ\":0}",
        "{\"ie\":0,\"sort\":1}",
        "{\"ie\":1,\"sort\":0}",
    };
    private static final String PRELUDE_TRACE = "nameStr 1 2 \"c\"\n"
            + "nameStr 1 3 \"u\"\n"
            + "nameStr 2 4 \"mk\"\n"
            + "nameStr 2 5 \"rec\"\n"
            + "succ 1 2\n"
            + "sort 2 1\n"
            + "sort 1 2\n";

    private static String declTrace(String... lines) {
        String[] all = new String[PRELUDE.length + lines.length];
        System.arraycopy(PRELUDE, 0, all, 0, PRELUDE.length);
        System.arraycopy(lines, 0, all, PRELUDE.length, lines.length);
        String t = trace(all);
        assertTrue(t.startsWith(PRELUDE_TRACE));
        return t.substring(PRELUDE_TRACE.length());
    }

    @Test
    void axiomAndQuot() {
        assertEquals(
                "axiom 2 1 0\n  lp 0 []\n"
                        + "axiom 4 2 1\n  lp 2 [3,3]\n"
                        + "quot 5 1 0\n  lp 1 [3]\n"
                        + "quot 3 1 1\n  lp 0 []\n",
                declTrace(
                        "{\"axiom\":{\"isUnsafe\":false,\"levelParams\":[],\"name\":1,\"type\":0}}",
                        "{\"axiom\":{\"isUnsafe\":true,\"levelParams\":[2,2],\"name\":3,\"type\":1}}",
                        "{\"quot\":{\"kind\":\"type\",\"levelParams\":[2],\"name\":4,\"type\":0}}",
                        "{\"quot\":{\"kind\":\"ctor\",\"levelParams\":[],\"name\":2,\"type\":0}}"));
        assertEquals(
                "quot 2 1 2\n  lp 0 []\n" + "quot 3 1 3\n  lp 0 []\n",
                declTrace(
                        "{\"quot\":{\"kind\":\"lift\",\"levelParams\":[],\"name\":1,\"type\":0}}",
                        "{\"quot\":{\"kind\":\"ind\",\"levelParams\":[],\"name\":2,\"type\":0}}"));
    }

    @Test
    void definitions() {
        assertEquals(
                "def 2 1 2 2 5 1\n  lp 1 [3]\n  all 1 [2]\n"
                        + "def 3 1 2 1 0 1\n  lp 0 []\n  all 2 [3,2]\n"
                        + "def 4 1 2 0 0 1\n  lp 0 []\n  all 0 []\n"
                        + "def 5 1 2 2 4294967295 0\n  lp 0 []\n  all 1 [5]\n"
                        + "def 1 1 2 2 1 2\n  lp 0 []\n  all 1 [1]\n",
                declTrace(
                        "{\"def\":{\"all\":[1],\"hints\":{\"regular\":5},\"levelParams\":[2],\"name\":1,\"safety\":\"safe\",\"type\":0,\"value\":1}}",
                        "{\"def\":{\"all\":[2,1],\"hints\":\"abbrev\",\"levelParams\":[],\"name\":2,\"safety\":\"safe\",\"type\":0,\"value\":1}}",
                        "{\"def\":{\"all\":[],\"hints\":\"opaque\",\"levelParams\":[],\"name\":3,\"safety\":\"safe\",\"type\":0,\"value\":1}}",
                        "{\"def\":{\"all\":[4],\"hints\":{\"regular\":4294967295},\"levelParams\":[],\"name\":4,\"safety\":\"unsafe\",\"type\":0,\"value\":1}}",
                        "{\"def\":{\"all\":[0],\"hints\":{\"regular\":4294967297},\"levelParams\":[],\"name\":0,\"safety\":\"partial\",\"type\":0,\"value\":1}}"));
    }

    @Test
    void theoremsAndOpaques() {
        assertEquals(
                "thm 2 1 2\n  lp 0 []\n  all 1 [2]\n"
                        + "thm 3 2 1\n  lp 2 [3,2]\n  all 3 [2,3,4]\n"
                        + "opaque 4 1 2 0\n  lp 0 []\n  all 1 [4]\n"
                        + "opaque 5 1 2 1\n  lp 1 [3]\n  all 0 []\n"
                        + "opaque 1 1 2 0\n  lp 0 []\n  all 0 []\n",
                declTrace(
                        "{\"thm\":{\"all\":[1],\"levelParams\":[],\"name\":1,\"type\":0,\"value\":1}}",
                        "{\"thm\":{\"all\":[1,2,3],\"levelParams\":[2,1],\"name\":2,\"type\":1,\"value\":0}}",
                        "{\"opaque\":{\"all\":[3],\"isUnsafe\":false,\"levelParams\":[],\"name\":3,\"type\":0,\"value\":1}}",
                        "{\"opaque\":{\"all\":[],\"isUnsafe\":true,\"levelParams\":[2],\"name\":4,\"type\":0,\"value\":1}}",
                        "{\"opaque\":{\"all\":[],\"levelParams\":[],\"name\":0,\"type\":0,\"value\":1}}"));
    }

    @Test
    void inductiveBlock() {
        assertEquals(
                "inductive 1 1 1\n"
                        + "  type 2 1 2 0 1 0 0 1\n    lp 1 [3]\n    all 1 [2]\n    ctors 1 [4]\n"
                        + "  ctor 4 2 2 0 2 3 0\n    lp 1 [3]\n"
                        + "  rec 5 1 2 0 1 1 1 0 1\n    lp 2 [3,3]\n    all 1 [2]\n"
                        + "    rule 4 3 2\n"
                        + "end\n"
                        + "inductive 0 0 0\nend\n",
                declTrace(
                        "{\"inductive\":{\"ctors\":[{\"cidx\":0,\"induct\":1,\"isUnsafe\":false,\"levelParams\":[2],\"name\":3,\"numFields\":3,\"numParams\":2,\"type\":1}],"
                                + "\"recs\":[{\"all\":[1],\"isUnsafe\":false,\"k\":true,\"levelParams\":[2,2],\"name\":4,\"numIndices\":0,\"numMinors\":1,\"numMotives\":1,\"numParams\":2,"
                                + "\"rules\":[{\"ctor\":3,\"nfields\":3,\"rhs\":1}],\"type\":0}],"
                                + "\"types\":[{\"all\":[1],\"ctors\":[3],\"isRec\":false,\"isReflexive\":true,\"isUnsafe\":false,\"levelParams\":[2],\"name\":1,\"numIndices\":0,\"numNested\":1,\"numParams\":2,\"type\":0}]}}",
                        "{\"inductive\":{\"ctors\":[],\"recs\":[],\"types\":[]}}"));
    }

    @Test
    void sparseAndOutOfOrderIds() {
        T.Run r = T.agree("sparse", T.utf8(T.META
                + "{\"in\":7,\"str\":{\"pre\":0,\"str\":\"a\"}}\n"
                + "{\"in\":3,\"str\":{\"pre\":7,\"str\":\"b\"}}\n"
                + "{\"in\":123456789012,\"num\":{\"i\":1,\"pre\":3}}\n"
                + "{\"in\":9223372036854775807,\"num\":{\"i\":2,\"pre\":123456789012}}\n"
                + "{\"il\":2,\"succ\":0}\n"
                + "{\"il\":1,\"succ\":2}\n"
                + "{\"ie\":4,\"sort\":1}\n"
                + "{\"ie\":1000000,\"sort\":2}\n"
                + "{\"app\":{\"arg\":4,\"fn\":1000000},\"ie\":0}\n"
                + "{\"app\":{\"arg\":0,\"fn\":4},\"ie\":5}\n"
                + "{\"axiom\":{\"isUnsafe\":false,\"levelParams\":[],\"name\":9223372036854775807,\"type\":5}}\n"));
        T.assertOk(r);
        assertEquals(
                "nameStr 1 2 \"a\"\n"
                        + "nameStr 2 3 \"b\"\n"
                        + "nameNum 3 1 4\n"
                        + "nameNum 4 2 5\n"
                        + "succ 1 2\n"
                        + "succ 2 3\n"
                        + "sort 3 1\n"
                        + "sort 2 2\n"
                        + "app 2 1 3\n"
                        + "app 1 3 4\n"
                        + "axiom 5 4 0\n  lp 0 []\n",
                r.trace.substring(r.trace.indexOf('\n') + 1));
        assertFalse(r.result.namesDense);
        assertFalse(r.result.levelsDense);
        assertFalse(r.result.exprsDense);
        assertFalse(r.result.tableFree);
        assertEquals(Long.MAX_VALUE, r.result.maxNameId);
        assertEquals(1000000, r.result.maxExprId);
        assertEquals(5, r.result.names);
        assertEquals(4, r.result.exprs);
    }

    @Test
    void nonCanonicalFormattingGoesThroughTheGeneralPath() {
        // other key order, whitespace, escapes in keys, numbers in exponent form, CR LF
        T.Run r = T.agree("formatting", T.utf8(T.META
                + " { \"str\" : { \"str\" : \"a\" , \"pre\" : 0 } , \"in\" : 1 } \r\n"
                + "{\"ie\":0,\"bvar\":0}\n"
                + "{\"ie\":1,\"app\":{\"fn\":0,\"arg\":0,\"extra\":[true,false,null,1.5e-3,\"x\"]}}\t\n"
                + "{\"in\":10,\"num\":{\"pre\":1,\"i\":0.5e1}}\n"
                + "{\"\\u0069e\":2,\"lam\":{\"type\":1e0,\"name\":1.0e1,\"body\":-0,\"binderInfo\":\"\\u0064efault\"}}\n"
                + "{\"ie\":3,\"lam\":{\"type\":1,\"name\":10,\"body\":0,\"binderInfo\":\"default\"}}"));
        T.assertOk(r);
        assertEquals(
                "nameStr 1 2 \"a\"\n"
                        + "bvar 0 1\n"
                        + "app 1 1 2\n"
                        + "nameNum 2 5 3\n"
                        + "lam 3 0 2 1 3\n"
                        + "lam 3 0 2 1 4\n",
                r.trace.substring(r.trace.indexOf('\n') + 1));
        assertEquals(7, r.result.lines);
        assertEquals(6, r.result.generalPathLines);
    }
}

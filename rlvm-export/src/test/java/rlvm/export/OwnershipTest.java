package rlvm.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

class OwnershipTest {
    @Test
    void handWrittenExample() throws ExportException {
        // exprs (store ids): 1 Prop, 2 #0, 3 Prop->#0..., see the comments
        ColumnarStore s = new ColumnarStore();
        ExportReader.read(T.utf8(T.META
                + "{\"in\":1,\"str\":{\"pre\":0,\"str\":\"t1\"}}\n"
                + "{\"in\":2,\"str\":{\"pre\":0,\"str\":\"t2\"}}\n"
                + "{\"in\":3,\"str\":{\"pre\":0,\"str\":\"d\"}}\n"
                + "{\"ie\":0,\"sort\":0}\n" // 1: used by everything
                + "{\"bvar\":0,\"ie\":1}\n" // 2: only inside t1's proof
                + "{\"ie\":2,\"lam\":{\"binderInfo\":\"default\",\"body\":1,\"name\":0,\"type\":0}}\n" // 3: t1's proof
                + "{\"bvar\":1,\"ie\":3}\n" // 4: in t1's proof and t2's proof
                + "{\"app\":{\"arg\":3,\"fn\":2},\"ie\":4}\n" // 5: t1's proof root
                + "{\"bvar\":5,\"ie\":5}\n" // 6: never used
                + "{\"thm\":{\"all\":[1],\"levelParams\":[],\"name\":1,\"type\":0,\"value\":4}}\n" // decl 1
                + "{\"app\":{\"arg\":3,\"fn\":3},\"ie\":6}\n" // 7: t2's proof root
                + "{\"app\":{\"arg\":0,\"fn\":0},\"ie\":7}\n" // 8: t2's type and d's value
                + "{\"thm\":{\"all\":[2],\"levelParams\":[],\"name\":2,\"type\":7,\"value\":6}}\n" // decl 2
                + "{\"def\":{\"all\":[3],\"hints\":\"abbrev\",\"levelParams\":[],\"name\":3,\"safety\":\"safe\",\"type\":0,\"value\":7}}\n"), // decl 3
                s);
        Ownership o = Ownership.compute(s);
        assertEquals(8, o.exprCount);
        assertEquals(Ownership.SHARED, o.owner[1]);
        assertEquals(1, o.owner[2]);
        assertEquals(1, o.owner[3]);
        assertEquals(Ownership.SHARED, o.owner[4]); // two theorems
        assertEquals(1, o.owner[5]);
        assertEquals(Ownership.UNREACHABLE, o.owner[6]);
        assertEquals(2, o.owner[7]);
        assertEquals(Ownership.SHARED, o.owner[8]);
        assertTrue(o.isProofPrivate(5));
        assertFalse(o.isProofPrivate(8));
        assertEquals(3, o.lastUse[1]);
        assertEquals(1, o.lastUse[2]);
        assertEquals(1, o.lastUse[3]);
        assertEquals(2, o.lastUse[4]);
        assertEquals(1, o.lastUse[5]);
        assertEquals(0, o.lastUse[6]);
        assertEquals(2, o.lastUse[7]);
        assertEquals(3, o.lastUse[8]);
        assertEquals(4, o.privateCount);
        assertEquals(3, o.sharedCount);
        assertEquals(1, o.unreachableCount);
        assertEquals(2, o.theoremsWithPrivateExprs);
        assertEquals(3, o.largestPrivateBlock);
        // 1, 4 and 8 outlive the declaration that introduced them
        assertEquals(3, o.usedLater);
        assertEquals(4, o.diesWithIntroducer);
    }

    /** Random export with unique declaration names; returns the text and fills {@code roots}. */
    private static String randomExport(Random rnd, int exprs, int decls) {
        StringBuilder sb = new StringBuilder(T.META);
        int nextName = 1;
        int declared = 0;
        for (int i = 0; i < exprs; i++) {
            int x = i == 0 ? 0 : rnd.nextBoolean() ? i - 1 : rnd.nextInt(i);
            int y = i == 0 ? 0 : rnd.nextInt(i);
            int z = i == 0 ? 0 : rnd.nextInt(i);
            switch (i < 2 ? 0 : rnd.nextInt(8)) {
                case 0 -> sb.append("{\"bvar\":").append(rnd.nextInt(3)).append(",\"ie\":").append(i).append("}\n");
                case 1, 2, 3 -> sb.append("{\"app\":{\"arg\":").append(y).append(",\"fn\":").append(x).append("},\"ie\":").append(i).append("}\n");
                case 4 -> sb.append("{\"ie\":").append(i).append(",\"lam\":{\"binderInfo\":\"default\",\"body\":").append(x)
                        .append(",\"name\":0,\"type\":").append(y).append("}}\n");
                case 5 -> sb.append("{\"forallE\":{\"binderInfo\":\"default\",\"body\":").append(x).append(",\"name\":0,\"type\":")
                        .append(y).append("},\"ie\":").append(i).append("}\n");
                case 6 -> sb.append("{\"ie\":").append(i).append(",\"letE\":{\"body\":").append(x).append(",\"name\":0,\"nondep\":false,\"type\":")
                        .append(y).append(",\"value\":").append(z).append("}}\n");
                default -> sb.append("{\"ie\":").append(i).append(",\"proj\":{\"idx\":0,\"struct\":").append(x).append(",\"typeName\":0}}\n");
            }
            // declarations are spread over the file and refer to what exists so far
            while (declared < decls && rnd.nextInt(Math.max(1, exprs / decls)) == 0) {
                int n = nextName++;
                sb.append("{\"in\":").append(n).append(",\"num\":{\"i\":").append(n).append(",\"pre\":0}}\n");
                int t = rnd.nextInt(i + 1);
                int v = rnd.nextInt(i + 1);
                switch (rnd.nextInt(6)) {
                    case 0, 1, 2 -> sb.append("{\"thm\":{\"all\":[],\"levelParams\":[],\"name\":").append(n).append(",\"type\":").append(t)
                            .append(",\"value\":").append(v).append("}}\n");
                    case 3 -> sb.append("{\"def\":{\"all\":[],\"hints\":\"abbrev\",\"levelParams\":[],\"name\":").append(n)
                            .append(",\"safety\":\"safe\",\"type\":").append(t).append(",\"value\":").append(v).append("}}\n");
                    case 4 -> sb.append("{\"axiom\":{\"isUnsafe\":false,\"levelParams\":[],\"name\":").append(n).append(",\"type\":").append(t).append("}}\n");
                    default -> {
                        int ctor = nextName++;
                        int rec = nextName++;
                        sb.append("{\"in\":").append(ctor).append(",\"num\":{\"i\":").append(ctor).append(",\"pre\":0}}\n");
                        sb.append("{\"in\":").append(rec).append(",\"num\":{\"i\":").append(rec).append(",\"pre\":0}}\n");
                        sb.append("{\"inductive\":{\"ctors\":[{\"cidx\":0,\"induct\":").append(n).append(",\"isUnsafe\":false,\"levelParams\":[],\"name\":")
                                .append(ctor).append(",\"numFields\":0,\"numParams\":0,\"type\":").append(v).append("}],\"recs\":[{\"all\":[],\"isUnsafe\":false,\"k\":false,\"levelParams\":[],\"name\":")
                                .append(rec).append(",\"numIndices\":0,\"numMinors\":0,\"numMotives\":0,\"numParams\":0,\"rules\":[{\"ctor\":").append(ctor)
                                .append(",\"nfields\":0,\"rhs\":").append(rnd.nextInt(i + 1)).append("},{\"ctor\":").append(ctor).append(",\"nfields\":0,\"rhs\":")
                                .append(rnd.nextInt(i + 1)).append("}],\"type\":").append(rnd.nextInt(i + 1))
                                .append("}],\"types\":[{\"all\":[],\"ctors\":[],\"isRec\":false,\"isReflexive\":false,\"isUnsafe\":false,\"levelParams\":[],\"name\":")
                                .append(n).append(",\"numIndices\":0,\"numNested\":0,\"numParams\":0,\"type\":").append(t).append("}]}}\n");
                    }
                }
                declared++;
            }
        }
        return sb.toString();
    }

    private static int[] children(ColumnarStore s, int e) {
        return switch (s.exprKind(e)) {
            case ColumnarStore.EXPR_APP, ColumnarStore.EXPR_LAM, ColumnarStore.EXPR_PI -> new int[] {s.exprA(e), s.exprB(e)};
            case ColumnarStore.EXPR_LET -> new int[] {s.exprA(e), s.letValue(e), s.letBody(e)};
            case ColumnarStore.EXPR_PROJ, ColumnarStore.EXPR_MDATA -> new int[] {s.exprA(e)};
            default -> new int[0];
        };
    }

    private static void reach(ColumnarStore s, int root, Set<Integer> seen) {
        if (!seen.add(root)) {
            return;
        }
        for (int c : children(s, root)) {
            reach(s, c, seen);
        }
    }

    @Test
    void agreesWithBruteForceReachability() throws ExportException {
        Random rnd = new Random(8);
        for (int round = 0; round < 150; round++) {
            int exprs = 10 + rnd.nextInt(300);
            ColumnarStore s = new ColumnarStore();
            ExportReader.read(T.utf8(randomExport(rnd, exprs, 1 + rnd.nextInt(30))), s);
            StoreChecks.checkInvariants(s);
            Ownership o = Ownership.compute(s);
            int n = s.exprCount();
            // roots: {expression, declaration ordinal, theorem-value?}
            List<int[]> roots = new ArrayList<>();
            for (int c = 1; c <= s.constCount(); c++) {
                int d = s.constDeclColumn()[c];
                roots.add(new int[] {s.constType(c), d, 0});
                if (s.constValue(c) != 0) {
                    roots.add(new int[] {s.constValue(c), d, s.constKind(c) == ColumnarStore.CONST_THM ? 1 : 0});
                }
                if (s.constKind(c) == ColumnarStore.CONST_REC) {
                    for (int i = 0; i < s.constField(c, 6); i++) {
                        roots.add(new int[] {s.constField(c, 7 + 3 * i + 2), d, 0});
                    }
                }
            }
            int[] owner = new int[n + 1];
            int[] lastUse = new int[n + 1];
            boolean[] reached = new boolean[n + 1];
            for (int[] root : roots) {
                Set<Integer> seen = new HashSet<>();
                reach(s, root[0], seen);
                for (int e : seen) {
                    int who = root[2] == 1 ? root[1] : Ownership.SHARED;
                    owner[e] = !reached[e] || owner[e] == who ? who : Ownership.SHARED;
                    reached[e] = true;
                    lastUse[e] = Math.max(lastUse[e], root[1]);
                }
            }
            long priv = 0;
            long shared = 0;
            for (int e = 1; e <= n; e++) {
                assertEquals(reached[e] ? owner[e] : Ownership.UNREACHABLE, o.owner[e], "round " + round + " owner of " + e);
                assertEquals(lastUse[e], o.lastUse[e], "round " + round + " last use of " + e);
                if (o.owner[e] > 0) {
                    priv++;
                } else if (o.owner[e] == Ownership.SHARED) {
                    shared++;
                }
            }
            assertEquals(priv, o.privateCount);
            assertEquals(shared, o.sharedCount);
            assertEquals(n - priv - shared, o.unreachableCount);
            assertEquals(priv + shared, o.usedLater + o.diesWithIntroducer);
        }
    }

    @Test
    void initPreludeShares() throws Exception {
        java.nio.file.Path p = T.TESTS.resolve("good/init-prelude.ndjson");
        assumeTrue(Files.exists(p));
        ColumnarStore s = new ColumnarStore(Files.size(p), true, false);
        ExportReader.read(p, s);
        Ownership o = Ownership.compute(s);
        assertEquals(55_961, o.exprCount);
        assertEquals(o.exprCount, o.privateCount + o.sharedCount + o.unreachableCount);
        // lean4export only writes what a declaration needs
        assertEquals(0, o.unreachableCount);
        assertTrue(o.privateCount > 0 && o.sharedCount > o.privateCount, o.privateCount + " private");
        assertTrue(o.theoremsWithPrivateExprs <= 161);
    }
}

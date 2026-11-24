package rlvm.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.HashMap;
import java.util.Map;

/**
 * Structural invariants of a {@link ColumnarStore}, recomputed here from the documented layout in the
 * plainest possible way (one pass, children before parents).
 */
final class StoreChecks {
    private StoreChecks() {}

    static void checkInvariants(ColumnarStore s) {
        int names = s.nameCount();
        int levels = s.levelCount();
        int exprs = s.exprCount();
        // ---- names: prefixes come first, and structurally equal names are one row
        assertEquals(ColumnarStore.NAME_ANONYMOUS, s.nameKind(ColumnarStore.ANONYMOUS));
        Map<String, Integer> seen = new HashMap<>();
        for (int n = 2; n <= names; n++) {
            int p = s.namePrefix(n);
            assertTrue(p >= 1 && p < n, "name " + n + " prefix " + p);
            String key = switch (s.nameKind(n)) {
                case ColumnarStore.NAME_STR -> p + "/s/" + s.nameString(n);
                case ColumnarStore.NAME_NUM -> p + "/n/" + s.nameNumber(n);
                case ColumnarStore.NAME_NUM_BIG -> p + "/b/" + s.nameToString(n);
                default -> {
                    fail("name kind " + s.nameKind(n));
                    yield "";
                }
            };
            Integer old = seen.put(key, n);
            assertTrue(old == null, "names " + old + " and " + n + " are structurally equal");
        }
        // ---- levels
        boolean[] levelParam = new boolean[levels + 1];
        assertEquals(ColumnarStore.LEVEL_ZERO, s.levelKind(ColumnarStore.ZERO));
        for (int l = 2; l <= levels; l++) {
            int a = s.levelA(l);
            int b = s.levelB(l);
            switch (s.levelKind(l)) {
                case ColumnarStore.LEVEL_SUCC -> {
                    assertTrue(a >= 1 && a < l);
                    levelParam[l] = levelParam[a];
                }
                case ColumnarStore.LEVEL_MAX, ColumnarStore.LEVEL_IMAX -> {
                    assertTrue(a >= 1 && a < l && b >= 1 && b < l);
                    levelParam[l] = levelParam[a] || levelParam[b];
                }
                case ColumnarStore.LEVEL_PARAM -> {
                    assertTrue(a >= 1 && a <= names);
                    levelParam[l] = true;
                }
                default -> fail("level kind " + s.levelKind(l));
            }
            assertEquals(levelParam[l], s.levelHasParam(l), "level " + l);
        }
        // ---- expressions: recompute range, flag and mask from the children
        long[] masks = s.exprMaskColumn();
        for (int e = 1; e <= exprs; e++) {
            int a = s.exprA(e);
            int b = s.exprB(e);
            int loose;
            boolean lp;
            long mask;
            switch (s.exprKind(e)) {
                case ColumnarStore.EXPR_BVAR -> {
                    assertTrue(a >= 0 && a <= (1 << 20) - 2);
                    loose = a + 1;
                    lp = false;
                    mask = 1L << Math.min(a, 63);
                }
                case ColumnarStore.EXPR_SORT -> {
                    assertTrue(a >= 1 && a <= levels);
                    loose = 0;
                    lp = levelParam[a];
                    mask = 0;
                }
                case ColumnarStore.EXPR_CONST -> {
                    assertTrue(a >= 1 && a <= names);
                    loose = 0;
                    lp = false;
                    for (int i = 0; i < s.constLevelCount(e); i++) {
                        int l = s.constLevel(e, i);
                        assertTrue(l >= 1 && l <= levels);
                        lp |= levelParam[l];
                    }
                    mask = 0;
                }
                case ColumnarStore.EXPR_APP -> {
                    assertTrue(a >= 1 && a < e && b >= 1 && b < e, "app " + e);
                    loose = Math.max(s.exprLoose(a), s.exprLoose(b));
                    lp = s.exprHasLevelParam(a) || s.exprHasLevelParam(b);
                    mask = masks == null ? 0 : masks[a] | masks[b];
                }
                case ColumnarStore.EXPR_LAM, ColumnarStore.EXPR_PI -> {
                    assertTrue(a >= 1 && a < e && b >= 1 && b < e);
                    loose = Math.max(s.exprLoose(a), Math.max(0, s.exprLoose(b) - 1));
                    lp = s.exprHasLevelParam(a) || s.exprHasLevelParam(b);
                    mask = masks == null ? 0 : masks[a] | under(masks[b], s.exprLoose(b));
                    assertTrue(s.binderInfo(e) >= 0 && s.binderInfo(e) <= 3);
                }
                case ColumnarStore.EXPR_LET -> {
                    int v = s.letValue(e);
                    int body = s.letBody(e);
                    assertTrue(a >= 1 && a < e && v >= 1 && v < e && body >= 1 && body < e);
                    loose = Math.max(Math.max(s.exprLoose(a), s.exprLoose(v)), Math.max(0, s.exprLoose(body) - 1));
                    lp = s.exprHasLevelParam(a) || s.exprHasLevelParam(v) || s.exprHasLevelParam(body);
                    mask = masks == null ? 0 : masks[a] | masks[v] | under(masks[body], s.exprLoose(body));
                }
                case ColumnarStore.EXPR_PROJ -> {
                    assertTrue(a >= 1 && a < e);
                    assertTrue(s.projTypeName(e) >= 1 && s.projTypeName(e) <= names);
                    assertTrue(s.projIndex(e) >= 0);
                    loose = s.exprLoose(a);
                    lp = s.exprHasLevelParam(a);
                    mask = masks == null ? 0 : masks[a];
                }
                case ColumnarStore.EXPR_MDATA -> {
                    assertTrue(a >= 1 && a < e);
                    loose = s.exprLoose(a);
                    lp = s.exprHasLevelParam(a);
                    mask = masks == null ? 0 : masks[a];
                }
                case ColumnarStore.EXPR_NATLIT -> {
                    String digits = s.literal(e);
                    assertTrue(digits.matches("0|[1-9][0-9]*"), "natLit " + digits);
                    loose = 0;
                    lp = false;
                    mask = 0;
                }
                case ColumnarStore.EXPR_STRLIT -> {
                    loose = 0;
                    lp = false;
                    mask = 0;
                }
                default -> {
                    fail("expr kind " + s.exprKind(e));
                    return;
                }
            }
            if (loose != s.exprLoose(e) || lp != s.exprHasLevelParam(e) || (masks != null && mask != masks[e])) {
                fail("expr " + e + ": loose " + s.exprLoose(e) + " (expected " + loose + "), level param "
                        + s.exprHasLevelParam(e) + " (expected " + lp + ")"
                        + (masks == null ? "" : ", mask " + Long.toHexString(masks[e]) + " (expected " + Long.toHexString(mask) + ")"));
            }
            if (masks != null) {
                // the mask is consistent with the range
                assertEquals(loose == 0, masks[e] == 0, "expr " + e);
                if (loose > 0 && loose <= 63) {
                    assertEquals(loose - 1, 63 - Long.numberOfLeadingZeros(masks[e]), "expr " + e);
                }
                assertEquals(loose > 63, masks[e] < 0, "expr " + e);
            }
        }
        // ---- constants and declarations
        int consts = s.constCount();
        int lastDecl = 0;
        for (int c = 1; c <= consts; c++) {
            int name = s.constName(c);
            assertTrue(name >= 1 && name <= names);
            assertEquals(c, s.constOfName(name), "constant " + c + " is not the one registered for its name");
            assertTrue(s.constType(c) >= 1 && s.constType(c) <= exprs);
            int kind = s.constKind(c);
            boolean hasValue = kind == ColumnarStore.CONST_DEF || kind == ColumnarStore.CONST_THM || kind == ColumnarStore.CONST_OPAQUE;
            assertEquals(hasValue, s.constValue(c) != 0);
            assertTrue(s.constValue(c) >= 0 && s.constValue(c) <= exprs);
            int d = s.constDeclColumn()[c];
            assertTrue(d >= lastDecl && d >= 1 && d <= s.declCount());
            lastDecl = d;
            assertTrue(s.declFirstConstColumn()[d] <= c);
            assertTrue(s.declExprEndColumn()[d] >= Math.max(s.constType(c), s.constValue(c)));
            int lps = s.constLevelParamsColumn()[c];
            for (int i = 0; i < s.listSize(lps); i++) {
                assertTrue(s.listGet(lps, i) >= 1 && s.listGet(lps, i) <= names);
            }
            if (kind == ColumnarStore.CONST_REC) {
                int rules = s.constField(c, 6);
                for (int i = 0; i < rules; i++) {
                    assertTrue(s.constField(c, 7 + 3 * i) >= 1 && s.constField(c, 7 + 3 * i) <= names);
                    assertTrue(s.constField(c, 7 + 3 * i + 2) >= 1 && s.constField(c, 7 + 3 * i + 2) <= exprs);
                }
            }
        }
        int named = 0;
        for (int n = 1; n <= names; n++) {
            if (s.constOfName(n) != 0) {
                named++;
            }
        }
        assertEquals(consts, named);
    }

    private static long under(long bodyMask, int bodyLoose) {
        return (bodyMask >>> 1) | (bodyLoose > 64 ? Long.MIN_VALUE : 0);
    }
}

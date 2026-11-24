package rlvm.export;

/**
 * Analysis pass over a loaded {@link ColumnarStore}: for every expression, who needs it and until when.
 *
 * <ul>
 *   <li>{@link #owner}: {@link #UNREACHABLE} if no declaration reaches the expression; a declaration
 *       ordinal {@code d > 0} if it is <b>proof-private</b>: reachable only from the value (the proof)
 *       of the theorem of declaration {@code d}, directly or through other expressions private to that
 *       theorem; {@link #SHARED} otherwise (reached from a type, a definition's value, a recursor rule,
 *       or from more than one theorem). This is the ownership split of the Susz packer
 *       (docs/research/05, section 1.2): a proof-private expression can be decoded when its theorem is
 *       checked and dropped right after, and it can never be needed by any other declaration.</li>
 *   <li>{@link #lastUse}: the ordinal of the last declaration (in file order) that reaches the
 *       expression, 0 if none. Checking in file order, the expression is dead after that declaration.</li>
 * </ul>
 *
 * <p>One backward walk over the expression columns: an expression has a larger id than its children,
 * so when the walk arrives at an expression all its parents have been seen and its own entry is final.
 * Cost: two {@code int} columns and a few instructions per expression.
 */
public final class Ownership {
    public static final int UNREACHABLE = 0;
    public static final int SHARED = -1;

    /** Per expression id: {@link #UNREACHABLE}, {@link #SHARED}, or the declaration ordinal of the owning theorem. */
    public final int[] owner;
    /** Per expression id: ordinal of the last declaration that reaches the expression, 0 if none. */
    public final int[] lastUse;

    /** Number of expressions (ids {@code 1 .. exprCount}). */
    public final int exprCount;
    public long privateCount;
    public long sharedCount;
    public long unreachableCount;
    /** Expressions whose last use is the declaration that introduced them (the next declaration line). */
    public long diesWithIntroducer;
    /** Expressions used by a declaration later than the one that introduced them. */
    public long usedLater;
    /** Theorems that own at least one private expression. */
    public long theoremsWithPrivateExprs;
    /** Largest number of expressions private to one theorem. */
    public long largestPrivateBlock;

    private Ownership(int n) {
        exprCount = n;
        owner = new int[n + 1];
        lastUse = new int[n + 1];
    }

    private void mark(int expr, int who, int decl) {
        int o = owner[expr];
        owner[expr] = o == UNREACHABLE || o == who ? who : SHARED;
        if (lastUse[expr] < decl) {
            lastUse[expr] = decl;
        }
    }

    public static Ownership compute(ColumnarStore s) {
        int n = s.exprCount();
        Ownership r = new Ownership(n);
        int[] head = s.exprHeadColumn();
        int[] a = s.exprAColumn();
        int[] b = s.exprBColumn();
        int[] wide = s.widePool();
        byte[] constKind = s.constKindColumn();
        int[] constType = s.constTypeColumn();
        int[] constValue = s.constValueColumn();
        int[] constInfo = s.constInfoColumn();
        int[] constDecl = s.constDeclColumn();

        // roots
        for (int c = 1; c <= s.constCount(); c++) {
            int d = constDecl[c];
            r.mark(constType[c], SHARED, d);
            switch (constKind[c]) {
                case ColumnarStore.CONST_THM -> r.mark(constValue[c], d, d);
                case ColumnarStore.CONST_DEF, ColumnarStore.CONST_OPAQUE -> r.mark(constValue[c], SHARED, d);
                case ColumnarStore.CONST_REC -> {
                    int info = constInfo[c];
                    int rules = wide[info + 6];
                    for (int i = 0; i < rules; i++) {
                        r.mark(wide[info + 7 + 3 * i + 2], SHARED, d);
                    }
                }
                default -> {
                }
            }
        }
        // backward walk
        int[] owner = r.owner;
        int[] lastUse = r.lastUse;
        for (int e = n; e >= 1; e--) {
            int o = owner[e];
            if (o == UNREACHABLE) {
                continue;
            }
            int d = lastUse[e];
            switch (head[e] & ColumnarStore.EXPR_KIND_MASK) {
                case ColumnarStore.EXPR_APP, ColumnarStore.EXPR_LAM, ColumnarStore.EXPR_PI -> {
                    r.mark(a[e], o, d);
                    r.mark(b[e], o, d);
                }
                case ColumnarStore.EXPR_LET -> {
                    r.mark(a[e], o, d);
                    r.mark(wide[b[e]], o, d);
                    r.mark(wide[b[e] + 1], o, d);
                }
                case ColumnarStore.EXPR_PROJ, ColumnarStore.EXPR_MDATA -> r.mark(a[e], o, d);
                default -> {
                }
            }
        }
        // summary
        int[] declExprEnd = s.declExprEndColumn();
        int decls = s.declCount();
        int[] blockSize = new int[decls + 1];
        int intro = 1; // the declaration that introduced e: the first one read after e
        for (int e = 1; e <= n; e++) {
            while (intro <= decls && declExprEnd[intro] < e) {
                intro++;
            }
            int o = owner[e];
            if (o == UNREACHABLE) {
                r.unreachableCount++;
                continue;
            }
            if (o == SHARED) {
                r.sharedCount++;
            } else {
                r.privateCount++;
                blockSize[o]++;
            }
            if (lastUse[e] > intro) {
                r.usedLater++;
            } else {
                r.diesWithIntroducer++;
            }
        }
        for (int d = 1; d <= decls; d++) {
            if (blockSize[d] > 0) {
                r.theoremsWithPrivateExprs++;
                r.largestPrivateBlock = Math.max(r.largestPrivateBlock, blockSize[d]);
            }
        }
        return r;
    }

    /** Whether expression {@code id} is reachable only from the value of a single theorem. */
    public boolean isProofPrivate(int id) {
        return owner[id] > 0;
    }
}

package rlvm.export.tools;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import rlvm.export.ColumnarStore;
import rlvm.export.ExportException;
import rlvm.export.ExportReader;
import rlvm.export.Ownership;

/**
 * Statistics of an export, for the architects of the term store and the evaluator.
 *
 * <pre>
 * ExportStats [--threads N] [--top N] FILE
 * </pre>
 * Prints: record counts per kind, id ranges, sharing (in-degree), the ownership split (proof-private
 * share, last use), application spine lengths, binder depths and loose-variable ranges, the share of
 * closed sub-terms, and the HEAT table of constants (how often a constant heads an application spine
 * and how often it is referenced at all, with the cumulative coverage of the hottest constants).
 *
 * <p>All counts are static counts over the DAG of expression records (each record once), not counts
 * of what a checker executes.
 */
public final class ExportStats {
    private ExportStats() {}

    private static final String[] EXPR_KINDS = {
        "?", "bvar", "sort", "const", "app", "lam", "forallE", "letE", "proj", "natVal", "strVal", "mdata"
    };
    private static final String[] CONST_KINDS = {
        "?", "axiom", "def", "thm", "opaque", "quot", "inductive type", "constructor", "recursor"
    };

    public static void main(String[] args) {
        ExportReader.Options o = new ExportReader.Options();
        int top = 40;
        Path file = null;
        try {
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--threads" -> o.threads = Integer.parseInt(args[++i]);
                    case "--top" -> top = Integer.parseInt(args[++i]);
                    default -> file = Path.of(args[i]);
                }
            }
            if (file == null) {
                throw new IllegalArgumentException();
            }
        } catch (RuntimeException e) {
            System.err.println("usage: ExportStats [--threads N] [--top N] FILE");
            System.exit(3);
            return;
        }
        try {
            run(file, o, top, System.out);
        } catch (ExportException e) {
            System.err.println(e.getMessage());
            System.exit(e.kind() == ExportException.Kind.MALFORMED ? 1 : 2);
        } catch (IOException | RuntimeException e) {
            System.err.println("error: " + e);
            System.exit(3);
        }
    }

    private static String pct(long part, long whole) {
        return whole == 0 ? "-" : String.format(Locale.ROOT, "%.2f %%", 100.0 * part / whole);
    }

    private static String n(long v) {
        return String.format(Locale.ROOT, "%,d", v);
    }

    private static void row(PrintStream out, String label, long count, long whole) {
        out.printf(Locale.ROOT, "  %-28s %15s  %9s%n", label, n(count), pct(count, whole));
    }

    /** Histogram with the buckets 0, 1, 2, 3, 4, 5, 6, 7-8, 9-16, 17-32, 33-64, 65+. */
    private static final String[] BUCKETS = {"0", "1", "2", "3", "4", "5", "6", "7-8", "9-16", "17-32", "33-64", "65+"};

    private static int bucket(int v) {
        if (v <= 6) {
            return v;
        }
        if (v <= 8) {
            return 7;
        }
        if (v <= 16) {
            return 8;
        }
        if (v <= 32) {
            return 9;
        }
        return v <= 64 ? 10 : 11;
    }

    private static void histogram(PrintStream out, long[] h, int from) {
        long total = 0;
        for (long v : h) {
            total += v;
        }
        int last = from;
        for (int i = from; i < h.length; i++) {
            if (h[i] != 0) {
                last = i;
            }
        }
        long cum = 0;
        for (int i = from; i <= last; i++) {
            cum += h[i];
            out.printf(Locale.ROOT, "  %-28s %15s  %9s  cumulative %9s%n", BUCKETS[i], n(h[i]), pct(h[i], total), pct(cum, total));
        }
    }

    /** Loads {@code file} and prints its statistics. */
    public static void run(Path file, ExportReader.Options o, int top, PrintStream out)
            throws IOException, ExportException {
        long size = Files.size(file);
        ColumnarStore s = new ColumnarStore(size, true, false);
        long t0 = System.nanoTime();
        ExportReader.Result res = ExportReader.read(file, s, o);
        double loadSeconds = (System.nanoTime() - t0) / 1e9;

        out.printf(Locale.ROOT, "== export %s%n", file.getFileName());
        out.printf(Locale.ROOT, "  exporter %s %s, format %s, Lean %s (%s)%n", s.exporterName(), s.exporterVersion(),
                s.formatVersion(), s.leanVersion(), s.leanGithash());
        out.printf(Locale.ROOT, "  %s bytes, %s lines, %.1f bytes per line%n", n(res.bytes), n(res.lines),
                (double) res.bytes / Math.max(1, res.lines));
        out.printf(Locale.ROOT, "  read in %.2f s (%s, %d scanner thread%s); general JSON path: %s lines (%s)%n", loadSeconds,
                res.io, res.threads, res.threads == 1 ? "" : "s", n(res.generalPathLines), pct(res.generalPathLines, res.lines));

        int exprs = s.exprCount();
        int[] head = s.exprHeadColumn();
        int[] a = s.exprAColumn();
        int[] b = s.exprBColumn();
        int[] wide = s.widePool();

        // ---------------------------------------------------------------- record counts
        out.println("== records");
        long[] nameKinds = new long[5];
        byte[] nameKind = s.nameKindColumn();
        for (int i = 2; i <= s.nameCount(); i++) {
            nameKinds[nameKind[i]]++;
        }
        long namesTotal = s.nameCount() - 1;
        row(out, "names", namesTotal, res.lines);
        row(out, "  str", nameKinds[ColumnarStore.NAME_STR], namesTotal);
        row(out, "  num", nameKinds[ColumnarStore.NAME_NUM] + nameKinds[ColumnarStore.NAME_NUM_BIG], namesTotal);
        long[] levelKinds = new long[6];
        for (int i = 2; i <= s.levelCount(); i++) {
            levelKinds[s.levelKind(i)]++;
        }
        long levelsTotal = s.levelCount() - 1;
        row(out, "levels", levelsTotal, res.lines);
        row(out, "  succ", levelKinds[ColumnarStore.LEVEL_SUCC], levelsTotal);
        row(out, "  max", levelKinds[ColumnarStore.LEVEL_MAX], levelsTotal);
        row(out, "  imax", levelKinds[ColumnarStore.LEVEL_IMAX], levelsTotal);
        row(out, "  param", levelKinds[ColumnarStore.LEVEL_PARAM], levelsTotal);
        long[] kinds = new long[16];
        long[] closedByKind = new long[16];
        long[] levelParamByKind = new long[16];
        long[] looseHist = new long[BUCKETS.length];
        int maxLoose = 0;
        for (int e = 1; e <= exprs; e++) {
            int h = head[e];
            int k = h & ColumnarStore.EXPR_KIND_MASK;
            kinds[k]++;
            int loose = h >>> ColumnarStore.LOOSE_SHIFT;
            if (loose == 0) {
                closedByKind[k]++;
            }
            if ((h & ColumnarStore.HAS_LEVEL_PARAM) != 0) {
                levelParamByKind[k]++;
            }
            looseHist[bucket(loose)]++;
            maxLoose = Math.max(maxLoose, loose);
        }
        row(out, "expressions", exprs, res.lines);
        for (int k = 1; k <= ColumnarStore.EXPR_MDATA; k++) {
            if (kinds[k] != 0 || k != ColumnarStore.EXPR_MDATA) {
                row(out, "  " + EXPR_KINDS[k], kinds[k], exprs);
            }
        }
        long[] constKinds = new long[9];
        for (int c = 1; c <= s.constCount(); c++) {
            constKinds[s.constKind(c)]++;
        }
        row(out, "declaration lines", s.declCount(), res.lines);
        row(out, "constants", s.constCount(), s.constCount());
        for (int k = 1; k < constKinds.length; k++) {
            row(out, "  " + CONST_KINDS[k], constKinds[k], s.constCount());
        }

        // ---------------------------------------------------------------- id ranges
        out.println("== id ranges (ids as written in the file)");
        out.printf(Locale.ROOT, "  names   1 .. %s  %s%n", n(res.maxNameId), res.namesDense ? "dense, in order" : "SPARSE or out of order");
        out.printf(Locale.ROOT, "  levels  1 .. %s  %s%n", n(res.maxLevelId), res.levelsDense ? "dense, in order" : "SPARSE or out of order");
        out.printf(Locale.ROOT, "  exprs   0 .. %s  %s%n", n(res.maxExprId), res.exprsDense ? "dense, in order" : "SPARSE or out of order");
        out.printf(Locale.ROOT, "  id translation tables needed: %s%n", res.tableFree ? "no" : "yes");
        out.printf(Locale.ROOT, "  ColumnarStore: %s MB of columns (%.1f bytes per input byte)%n", n(s.memoryBytes() >> 20),
                (double) s.memoryBytes() / Math.max(1, res.bytes));

        // ---------------------------------------------------------------- references and spines
        int[] indeg = new int[exprs + 1];
        byte[] nonFnUse = new byte[exprs + 1]; // referenced at least once other than as the function of an app
        for (int e = 1; e <= exprs; e++) {
            switch (head[e] & ColumnarStore.EXPR_KIND_MASK) {
                case ColumnarStore.EXPR_APP -> {
                    indeg[a[e]]++;
                    indeg[b[e]]++;
                    nonFnUse[b[e]] = 1;
                }
                case ColumnarStore.EXPR_LAM, ColumnarStore.EXPR_PI -> {
                    indeg[a[e]]++;
                    indeg[b[e]]++;
                    nonFnUse[a[e]] = 1;
                    nonFnUse[b[e]] = 1;
                }
                case ColumnarStore.EXPR_LET -> {
                    int v = wide[b[e]];
                    int body = wide[b[e] + 1];
                    indeg[a[e]]++;
                    indeg[v]++;
                    indeg[body]++;
                    nonFnUse[a[e]] = 1;
                    nonFnUse[v] = 1;
                    nonFnUse[body] = 1;
                }
                case ColumnarStore.EXPR_PROJ, ColumnarStore.EXPR_MDATA -> {
                    indeg[a[e]]++;
                    nonFnUse[a[e]] = 1;
                }
                default -> {
                }
            }
        }
        long rootRefs = 0;
        for (int c = 1; c <= s.constCount(); c++) {
            int t = s.constType(c);
            indeg[t]++;
            nonFnUse[t] = 1;
            rootRefs++;
            int v = s.constValue(c);
            if (v != 0) {
                indeg[v]++;
                nonFnUse[v] = 1;
                rootRefs++;
            }
            if (s.constKind(c) == ColumnarStore.CONST_REC) {
                int rules = s.constField(c, 6);
                for (int i = 0; i < rules; i++) {
                    int rhs = s.constField(c, 7 + 3 * i + 2);
                    indeg[rhs]++;
                    nonFnUse[rhs] = 1;
                    rootRefs++;
                }
            }
        }
        out.println("== sharing: references to an expression record (from other records and from declarations)");
        long[] deg = new long[6];
        int maxDeg = 0;
        for (int e = 1; e <= exprs; e++) {
            deg[Math.min(indeg[e], 5)]++;
            maxDeg = Math.max(maxDeg, indeg[e]);
        }
        row(out, "never referenced", deg[0], exprs);
        row(out, "referenced once", deg[1], exprs);
        row(out, "twice", deg[2], exprs);
        row(out, "3 times", deg[3], exprs);
        row(out, "4 times", deg[4], exprs);
        row(out, "5 times or more", deg[5], exprs);
        out.printf(Locale.ROOT, "  largest in-degree %s; %s references from declarations%n", n(maxDeg), n(rootRefs));

        // ---------------------------------------------------------------- ownership
        Ownership own = Ownership.compute(s);
        out.println("== ownership split and last use (Ownership)");
        row(out, "proof-private", own.privateCount, exprs);
        row(out, "shared", own.sharedCount, exprs);
        row(out, "unreachable", own.unreachableCount, exprs);
        row(out, "dead after introducing decl", own.diesWithIntroducer, exprs);
        row(out, "used by a later declaration", own.usedLater, exprs);
        out.printf(Locale.ROOT, "  %s of %s theorems own private expressions; largest private block %s expressions%n",
                n(own.theoremsWithPrivateExprs), n(constKinds[ColumnarStore.CONST_THM]), n(own.largestPrivateBlock));
        long[] privateByKind = new long[16];
        for (int e = 1; e <= exprs; e++) {
            if (own.owner[e] > 0) {
                privateByKind[head[e] & ColumnarStore.EXPR_KIND_MASK]++;
            }
        }
        out.printf(Locale.ROOT, "  proof-private share by kind: app %s, lam %s, forallE %s, const %s%n",
                pct(privateByKind[ColumnarStore.EXPR_APP], kinds[ColumnarStore.EXPR_APP]),
                pct(privateByKind[ColumnarStore.EXPR_LAM], kinds[ColumnarStore.EXPR_LAM]),
                pct(privateByKind[ColumnarStore.EXPR_PI], kinds[ColumnarStore.EXPR_PI]),
                pct(privateByKind[ColumnarStore.EXPR_CONST], kinds[ColumnarStore.EXPR_CONST]));

        // ---------------------------------------------------------------- spines
        int[] spine = new int[exprs + 1]; // APP: number of arguments of the spine ending here
        int[] spineHead = new int[exprs + 1]; // APP: the expression at the head of the spine
        long[] positionHist = new long[BUCKETS.length];
        long[] maximalHist = new long[BUCKETS.length];
        long maximalSpines = 0;
        int maxSpine = 0;
        long[] headKinds = new long[16];
        for (int e = 1; e <= exprs; e++) {
            if ((head[e] & ColumnarStore.EXPR_KIND_MASK) != ColumnarStore.EXPR_APP) {
                continue;
            }
            int f = a[e];
            if ((head[f] & ColumnarStore.EXPR_KIND_MASK) == ColumnarStore.EXPR_APP) {
                spine[e] = spine[f] + 1;
                spineHead[e] = spineHead[f];
            } else {
                spine[e] = 1;
                spineHead[e] = f;
            }
            positionHist[bucket(spine[e])]++;
            maxSpine = Math.max(maxSpine, spine[e]);
            if (nonFnUse[e] != 0) {
                maximalSpines++;
                maximalHist[bucket(spine[e])]++;
                headKinds[head[spineHead[e]] & ColumnarStore.EXPR_KIND_MASK]++;
            }
        }
        out.println("== application spines");
        out.printf(Locale.ROOT, "  %s app records; longest spine %d arguments%n", n(kinds[ColumnarStore.EXPR_APP]), maxSpine);
        out.println("  app records by position in their spine (1 = innermost application):");
        histogram(out, positionHist, 1);
        out.printf(Locale.ROOT, "  %s maximal spines (app records used other than as the function of an app), by number of arguments:%n",
                n(maximalSpines));
        histogram(out, maximalHist, 1);
        out.println("  head of the maximal spines:");
        for (int k = 1; k <= ColumnarStore.EXPR_MDATA; k++) {
            if (headKinds[k] != 0) {
                row(out, "  " + EXPR_KINDS[k], headKinds[k], maximalSpines);
            }
        }

        // ---------------------------------------------------------------- binders and closedness
        out.println("== binders");
        short[] depth = new short[exprs + 1]; // largest number of nested binders inside the expression
        for (int e = 1; e <= exprs; e++) {
            int d;
            switch (head[e] & ColumnarStore.EXPR_KIND_MASK) {
                case ColumnarStore.EXPR_APP -> d = Math.max(depth[a[e]], depth[b[e]]);
                case ColumnarStore.EXPR_LAM, ColumnarStore.EXPR_PI -> d = Math.max(depth[a[e]], depth[b[e]] + 1);
                case ColumnarStore.EXPR_LET -> d = Math.max(Math.max(depth[a[e]], depth[wide[b[e]]]), depth[wide[b[e] + 1]] + 1);
                case ColumnarStore.EXPR_PROJ, ColumnarStore.EXPR_MDATA -> d = depth[a[e]];
                default -> d = 0;
            }
            depth[e] = (short) Math.min(d, Short.MAX_VALUE);
        }
        long[] typeDepth = new long[BUCKETS.length];
        long[] valueDepth = new long[BUCKETS.length];
        int maxDepth = 0;
        for (int c = 1; c <= s.constCount(); c++) {
            int dt = depth[s.constType(c)];
            typeDepth[bucket(dt)]++;
            maxDepth = Math.max(maxDepth, dt);
            if (s.constValue(c) != 0) {
                int dv = depth[s.constValue(c)];
                valueDepth[bucket(dv)]++;
                maxDepth = Math.max(maxDepth, dv);
            }
        }
        out.printf(Locale.ROOT, "  deepest binder nesting in a declaration: %d%n", maxDepth);
        out.println("  binder nesting depth of declaration types:");
        histogram(out, typeDepth, 0);
        out.println("  binder nesting depth of declaration values (def, thm, opaque):");
        histogram(out, valueDepth, 0);
        out.printf(Locale.ROOT, "  loose bound variable range of expression records (0 = closed); largest %d:%n", maxLoose);
        histogram(out, looseHist, 0);
        out.println("== closed sub-terms (no loose bound variable)");
        long closed = 0;
        long withLevelParam = 0;
        for (int k = 1; k < 16; k++) {
            closed += closedByKind[k];
            withLevelParam += levelParamByKind[k];
        }
        row(out, "all expression records", closed, exprs);
        row(out, "  app", closedByKind[ColumnarStore.EXPR_APP], kinds[ColumnarStore.EXPR_APP]);
        row(out, "  lam", closedByKind[ColumnarStore.EXPR_LAM], kinds[ColumnarStore.EXPR_LAM]);
        row(out, "  forallE", closedByKind[ColumnarStore.EXPR_PI], kinds[ColumnarStore.EXPR_PI]);
        row(out, "  letE", closedByKind[ColumnarStore.EXPR_LET], kinds[ColumnarStore.EXPR_LET]);
        row(out, "  proj", closedByKind[ColumnarStore.EXPR_PROJ], kinds[ColumnarStore.EXPR_PROJ]);
        long closedMaximal = 0;
        for (int e = 1; e <= exprs; e++) {
            if ((head[e] & ColumnarStore.EXPR_KIND_MASK) == ColumnarStore.EXPR_APP && nonFnUse[e] != 0
                    && (head[e] >>> ColumnarStore.LOOSE_SHIFT) == 0) {
                closedMaximal++;
            }
        }
        row(out, "maximal spines", closedMaximal, maximalSpines);
        row(out, "records with a level param", withLevelParam, exprs);

        // ---------------------------------------------------------------- heat
        heat(out, s, head, a, indeg, nonFnUse, spineHead, top);
    }

    private static void heat(PrintStream out, ColumnarStore s, int[] head, int[] a, int[] indeg, byte[] nonFnUse,
            int[] spineHead, int top) {
        int exprs = s.exprCount();
        int names = s.nameCount();
        long[] heads = new long[names + 1]; // maximal spines headed by a const of this name
        long[] refs = new long[names + 1]; // references to const records of this name
        long totalHeads = 0;
        long totalRefs = 0;
        for (int e = 1; e <= exprs; e++) {
            int k = head[e] & ColumnarStore.EXPR_KIND_MASK;
            if (k == ColumnarStore.EXPR_CONST) {
                refs[a[e]] += indeg[e];
                totalRefs += indeg[e];
            } else if (k == ColumnarStore.EXPR_APP && nonFnUse[e] != 0) {
                int h = spineHead[e];
                if ((head[h] & ColumnarStore.EXPR_KIND_MASK) == ColumnarStore.EXPR_CONST) {
                    heads[a[h]]++;
                    totalHeads++;
                }
            }
        }
        out.println("== HEAT: constants as heads of application spines, and references to constants");
        out.printf(Locale.ROOT, "  %s maximal spines are headed by a constant; %s references to const records%n", n(totalHeads),
                n(totalRefs));
        // by kind of the constant
        long[] headByKind = new long[9];
        long[] refByKind = new long[9];
        long distinctHeads = 0;
        long distinctRefs = 0;
        for (int nm = 1; nm <= names; nm++) {
            int k = s.constOfName(nm) == 0 ? 0 : s.constKind(s.constOfName(nm));
            headByKind[k] += heads[nm];
            refByKind[k] += refs[nm];
            if (heads[nm] != 0) {
                distinctHeads++;
            }
            if (refs[nm] != 0) {
                distinctRefs++;
            }
        }
        out.printf(Locale.ROOT, "  %s distinct constants head a spine, %s are referenced, of %s declared%n", n(distinctHeads),
                n(distinctRefs), n(s.constCount()));
        out.println("  by kind of constant:              spine heads              references");
        for (int k = 0; k < 9; k++) {
            if (headByKind[k] != 0 || refByKind[k] != 0) {
                out.printf(Locale.ROOT, "  %-22s %15s %9s %15s %9s%n", k == 0 ? "(not declared)" : CONST_KINDS[k], n(headByKind[k]),
                        pct(headByKind[k], totalHeads), n(refByKind[k]), pct(refByKind[k], totalRefs));
            }
        }
        long[] headsSorted = sortedDescending(heads);
        long[] refsSorted = sortedDescending(refs);
        out.println("  cumulative coverage of the hottest constants (each column ranked by itself):");
        out.println("    top N        spine heads   references");
        for (int limit : new int[] {1, 10, 100, 1000, 10000, 100000}) {
            out.printf(Locale.ROOT, "    %-10s %12s %12s%n", n(limit), pct(prefixSum(headsSorted, limit), totalHeads),
                    pct(prefixSum(refsSorted, limit), totalRefs));
        }
        // top list by spine heads
        int[] order = topIndices(heads, top);
        out.printf(Locale.ROOT, "  the %d constants that head the most spines:%n", order.length);
        out.println("    rank    spine heads   share   cumulative   references  kind            name");
        long cum = 0;
        for (int i = 0; i < order.length; i++) {
            int nm = order[i];
            cum += heads[nm];
            int k = s.constOfName(nm) == 0 ? 0 : s.constKind(s.constOfName(nm));
            out.printf(Locale.ROOT, "    %4d %14s %7s %12s %12s  %-15s %s%n", i + 1, n(heads[nm]), pct(heads[nm], totalHeads),
                    pct(cum, totalHeads), n(refs[nm]), k == 0 ? "-" : CONST_KINDS[k], s.nameToString(nm));
        }
    }

    private static long[] sortedDescending(long[] v) {
        long[] c = v.clone();
        java.util.Arrays.sort(c);
        for (int i = 0, j = c.length - 1; i < j; i++, j--) {
            long t = c[i];
            c[i] = c[j];
            c[j] = t;
        }
        return c;
    }

    private static long prefixSum(long[] sortedDescending, int limit) {
        long sum = 0;
        for (int i = 0; i < limit && i < sortedDescending.length; i++) {
            sum += sortedDescending[i];
        }
        return sum;
    }

    /** Indices of the {@code top} largest entries, largest first (ties: smaller index first). */
    private static int[] topIndices(long[] v, int top) {
        int n = 0;
        for (long x : v) {
            if (x != 0) {
                n++;
            }
        }
        int size = Math.min(top, n);
        int[] idx = new int[size];
        int filled = 0;
        for (int i = 0; i < v.length; i++) {
            if (v[i] == 0) {
                continue;
            }
            // insertion into the sorted prefix
            int p = filled;
            if (filled < size) {
                filled++;
            } else if (v[idx[size - 1]] >= v[i]) {
                continue;
            } else {
                p = size - 1;
            }
            while (p > 0 && v[idx[p - 1]] < v[i]) {
                idx[p] = idx[p - 1];
                p--;
            }
            idx[p] = i;
        }
        return idx;
    }
}

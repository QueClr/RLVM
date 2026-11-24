package rlvm.load;

import java.util.Arrays;

import rlvm.level.LevelLimitException;
import rlvm.level.LevelStore;

/**
 * Finding A2-F1: an export with two level records for the same universe parameter.
 *
 * <p>The official reader makes one level object per level record, and the official
 * {@code instantiate} keeps a level node only when its operands come back pointer-equal. A constant
 * used with the OTHER record of one of its own parameters therefore gets the levels of its declared
 * type rebuilt through {@code mk_succ} / {@code mk_max} / {@code mk_imax} at that use site
 * ({@code imax u 1} becomes {@code max u 1}), and the official checker can reject what a kernel that
 * interns levels by structure accepts (RLVM interns: both records are one id, and instantiating a
 * parameter by itself is the identity). lean4export shares levels, so its output never has two
 * records for one parameter; hand-written and mutated files can.
 *
 * <p>The rule (the narrow one of the finding): the export is <b>declined</b> when a parameter
 * {@code p} has two level records and some level record {@code l} of the export that mentions
 * {@code p} is not equivalent, for the official relation, to its rebuild {@code rebuild_p(l)} (every
 * node above {@code p} rebuilt through the official smart constructors). If every level of the file
 * is officially equivalent to its respelling, no comparison of the official kernel changes its
 * answer. With several doubled parameters the rebuild above all of them together is tested as well.
 * The rule only ever declines; it never turns an accept into a reject or the reverse.
 *
 * <p>Everything is computed in a private level arena ({@link #scratch}) with the run's name order:
 * the run's level store is only read, so a file that is not declined loads to exactly the store it
 * had without this class. Nothing happens (beyond one list append per level record and one set
 * probe per parameter record) until a parameter gets its second record. Loader thread only.
 */
final class DoubledParams {
    /** With more doubled parameters than this the export is declined without looking further. */
    static final int MAX_DOUBLED = 8;

    /** Name ids that a parameter record introduced (open addressing; 0 = empty, names stored + 1). */
    private long[] seen = new long[64];
    private int seenCount;
    /** Level ids of the level records (succ, max, imax) of the export, in file order. */
    private int[] records = new int[64];
    private int recordCount;
    /** Name ids that have two parameter records. */
    private final int[] doubled = new int[MAX_DOUBLED];
    private int doubledCount;

    /** Private arena, created at the first doubled parameter. */
    private LevelStore scratch;
    /** Level id of the run -> level id of {@link #scratch} (0 = not copied yet). */
    private int[] image = new int[64];
    /**
     * Memo of the rebuild per parameter set and scratch level: sets {@code 0 .. MAX_DOUBLED-1} are the
     * single doubled parameters, set {@code MAX_DOUBLED} is all of them together.
     */
    private final int[][] rebuilt = new int[MAX_DOUBLED + 1][];
    /** 2 if the level mentions a parameter of the set, 1 if not, 0 = not computed. */
    private final byte[][] touched = new byte[MAX_DOUBLED + 1][];
    private int[] stack = new int[64];

    /** True once a parameter has two records: from then on every level record is examined. */
    boolean active() {
        return doubledCount != 0;
    }

    /** A succ / max / imax record gave level {@code level}; only remembered while no parameter is doubled. */
    void note(int level) {
        int n = recordCount;
        if (n == records.length) {
            records = Arrays.copyOf(records, n * 2);
        }
        records[n] = level;
        recordCount = n + 1;
    }

    /**
     * A succ / max / imax record gave level {@code level} of {@code run} while a parameter is doubled.
     *
     * @return null, or why the export is declined
     */
    String node(LevelStore run, int level) {
        note(level);
        try {
            for (int d = 0; d < doubledCount; d++) {
                String why = check(run, level, d);
                if (why != null) {
                    return why;
                }
            }
            return doubledCount > 1 ? check(run, level, MAX_DOUBLED) : null;
        } catch (LevelLimitException | StackOverflowError e) {
            return tooDeep();
        }
    }

    /**
     * A parameter record for name {@code name} gave level {@code level} of {@code run}.
     *
     * @return null, or why the export is declined
     */
    String param(LevelStore run, int name, int level) {
        if (firstRecord(name)) {
            return null;
        }
        for (int d = 0; d < doubledCount; d++) {
            if (doubled[d] == name) {
                return null; // a third record: the rule for this parameter has been applied to every record
            }
        }
        if (doubledCount == MAX_DOUBLED) {
            return "more than " + MAX_DOUBLED + " universe parameters have two level records: the official checker"
                    + " distinguishes the records of a parameter by identity, which is not reproduced";
        }
        if (scratch == null) {
            scratch = new LevelStore(run.names());
        }
        int d = doubledCount;
        doubled[d] = name;
        doubledCount = d + 1;
        rebuilt[d] = new int[64];
        touched[d] = new byte[64];
        rebuilt[MAX_DOUBLED] = new int[64]; // the set of all doubled parameters changed
        touched[MAX_DOUBLED] = new byte[64];
        try {
            for (int i = 0, n = recordCount; i < n; i++) {
                String why = check(run, records[i], d);
                if (why == null && doubledCount > 1) {
                    why = check(run, records[i], MAX_DOUBLED);
                }
                if (why != null) {
                    return why;
                }
            }
        } catch (LevelLimitException | StackOverflowError e) {
            return tooDeep();
        }
        return null;
    }

    private static String tooDeep() {
        return "a universe parameter has two level records and the levels above it are too deep to compare with"
                + " their official respelling";
    }

    /** Adds {@code name} to the set of parameters with a record; false if it was there. */
    private boolean firstRecord(int name) {
        long key = (name & 0xFFFF_FFFFL) + 1;
        long[] t = seen;
        int mask = t.length - 1;
        int i = (int) ((key * 0x9E3779B97F4A7C15L) >>> 40) & mask;
        while (t[i] != 0) {
            if (t[i] == key) {
                return false;
            }
            i = (i + 1) & mask;
        }
        t[i] = key;
        if (++seenCount * 2 > t.length) {
            long[] bigger = new long[t.length * 2];
            int m = bigger.length - 1;
            for (long k : t) {
                if (k != 0) {
                    int j = (int) ((k * 0x9E3779B97F4A7C15L) >>> 40) & m;
                    while (bigger[j] != 0) {
                        j = (j + 1) & m;
                    }
                    bigger[j] = k;
                }
            }
            seen = bigger;
        }
        return true;
    }

    private boolean inSet(int set, int name) {
        if (set < MAX_DOUBLED) {
            return doubled[set] == name;
        }
        for (int d = 0; d < doubledCount; d++) {
            if (doubled[d] == name) {
                return true;
            }
        }
        return false;
    }

    /** The rule for one level record and one parameter set. */
    private String check(LevelStore run, int level, int set) {
        int l = image(run, level);
        int r = rebuild(set, l);
        if (r == l || scratch.isEquivOfficial(l, r)) {
            return null;
        }
        StringBuilder sb = new StringBuilder("two level records for the universe parameter '");
        if (set < MAX_DOUBLED) {
            scratch.names().appendName(sb, doubled[set]);
        } else {
            for (int d = 0; d < doubledCount; d++) {
                if (d > 0) {
                    sb.append("', '");
                }
                scratch.names().appendName(sb, doubled[d]);
            }
        }
        sb.append("': the official checker distinguishes them by identity and respells the level '");
        scratch.appendTo(sb, l);
        sb.append("' above it as '");
        scratch.appendTo(sb, r);
        sb.append("' where a constant is used with the other record; that is not reproduced");
        return sb.toString();
    }

    private int push(int sp, int l) {
        if (sp == stack.length) {
            stack = Arrays.copyOf(stack, sp * 2);
        }
        stack[sp] = l;
        return sp + 1;
    }

    /** The level of {@link #scratch} with the structure of level {@code level} of {@code run}. Iterative. */
    private int image(LevelStore run, int level) {
        if (image.length < run.size()) {
            image = Arrays.copyOf(image, Math.max(run.size(), image.length * 2));
        }
        int[] im = image;
        if (im[level] != 0) {
            return im[level];
        }
        final LevelStore s = scratch;
        int sp = push(0, level);
        while (sp > 0) {
            int l = stack[sp - 1];
            if (im[l] != 0) {
                sp--;
                continue;
            }
            int v;
            switch (run.kind(l)) {
                case LevelStore.KIND_ZERO -> v = LevelStore.ZERO;
                case LevelStore.KIND_PARAM -> v = s.param(run.paramName(l));
                case LevelStore.KIND_SUCC -> {
                    int a = im[run.succOf(l)];
                    if (a == 0) {
                        sp = push(sp, run.succOf(l));
                        continue;
                    }
                    v = s.succ(a);
                }
                case LevelStore.KIND_MAX, LevelStore.KIND_IMAX -> {
                    int a = im[run.lhs(l)];
                    if (a == 0) {
                        sp = push(sp, run.lhs(l));
                        continue;
                    }
                    int b = im[run.rhs(l)];
                    if (b == 0) {
                        sp = push(sp, run.rhs(l));
                        continue;
                    }
                    v = run.kind(l) == LevelStore.KIND_MAX ? s.max(a, b) : s.imax(a, b);
                }
                default -> throw new IllegalStateException("level " + l + " has kind " + run.kind(l));
            }
            im[l] = v;
            sp--;
        }
        return im[level];
    }

    /**
     * The official {@code instantiate} of scratch level {@code level} for a substitution that maps the
     * parameters of {@code set} to other objects of the same names: a level that mentions none of
     * them is kept; such a parameter is itself; every other node is rebuilt from its rebuilt operands
     * with {@code mk_succ} / {@code mk_max} / {@code mk_imax}. Iterative, memoised per set.
     */
    private int rebuild(int set, int level) {
        final LevelStore s = scratch;
        int sp = push(0, level);
        while (sp > 0) {
            int l = stack[sp - 1];
            if (done(set, l)) {
                sp--;
                continue;
            }
            int r;
            boolean t;
            if (!s.hasParam(l)) {
                r = l;
                t = false;
            } else {
                switch (s.kind(l)) {
                    case LevelStore.KIND_PARAM -> {
                        r = l;
                        t = inSet(set, s.paramName(l));
                    }
                    case LevelStore.KIND_SUCC -> {
                        int a = s.succOf(l);
                        if (!done(set, a)) {
                            sp = push(sp, a);
                            continue;
                        }
                        t = touched[set][a] == 2;
                        r = t ? s.succ(rebuilt[set][a]) : l;
                    }
                    case LevelStore.KIND_MAX, LevelStore.KIND_IMAX -> {
                        int a = s.lhs(l);
                        int b = s.rhs(l);
                        if (!done(set, a)) {
                            sp = push(sp, a);
                            continue;
                        }
                        if (!done(set, b)) {
                            sp = push(sp, b);
                            continue;
                        }
                        t = touched[set][a] == 2 || touched[set][b] == 2;
                        if (!t) {
                            r = l;
                        } else if (s.kind(l) == LevelStore.KIND_MAX) {
                            r = s.mkMax(rebuilt[set][a], rebuilt[set][b]);
                        } else {
                            r = s.mkIMax(rebuilt[set][a], rebuilt[set][b]);
                        }
                    }
                    default -> {
                        r = l;
                        t = false;
                    }
                }
            }
            put(set, l, r, t);
            sp--;
        }
        return rebuilt[set][level];
    }

    private boolean done(int set, int l) {
        byte[] t = touched[set];
        return l < t.length && t[l] != 0;
    }

    private void put(int set, int l, int r, boolean isTouched) {
        if (l >= touched[set].length) {
            int cap = Math.max(l + 1, touched[set].length * 2);
            touched[set] = Arrays.copyOf(touched[set], cap);
            rebuilt[set] = Arrays.copyOf(rebuilt[set], cap);
        }
        rebuilt[set][l] = r;
        touched[set][l] = (byte) (isTouched ? 2 : 1);
    }
}

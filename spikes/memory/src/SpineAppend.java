import java.util.Arrays;

/**
 * B2: neutral-spine / partial-application growth strategies.
 *
 * Workload: build NSPINES spines; spine i gets LEN[i] arguments appended ONE AT A TIME
 * (as an evaluator does: app(app(app(h, a), b), c)). A fraction SHARE of intermediate spines
 * is "captured" (aliased) and later extended a second time from the capture point, so strategies
 * that mutate must preserve the captured prefix. After building, every spine is read once
 * (sum of identity-ish payload) to model conversion checking.
 *
 * Strategies:
 *   copy   : immutable Object[]; append = Arrays.copyOf(n+1)                  (baseline in many kernels)
 *   cons   : snoc list node {prev, arg, len}                                  (persistent, O(1) append, O(n) index)
 *   flag   : mutable {Object[] buf; int len; boolean shared}; append in place when !shared and room,
 *            capture sets shared=true (the "few-bit sticky uniqueness" scheme)
 *   claim  : immutable header {Buf buf; int len}; Buf {Object[] a; int used}; append claims slot len
 *            when buf.used == len (no flag writes on capture at all)
 */
public final class SpineAppend {
    static final class Arg { final int v; Arg(int v) { this.v = v; } }

    // ---- copy
    static Object[] copyAppend(Object[] s, Object a) {
        Object[] n = Arrays.copyOf(s, s.length + 1);
        n[s.length] = a;
        return n;
    }

    // ---- cons
    static final class Snoc { final Snoc prev; final Object arg; final int len;
        Snoc(Snoc p, Object a) { prev = p; arg = a; len = p == null ? 1 : p.len + 1; } }

    // ---- flag
    static final class FSpine { Object[] buf; int len; boolean shared;
        FSpine(int cap) { buf = new Object[cap]; } }
    static FSpine flagAppend(FSpine s, Object a) {
        if (!s.shared) {
            if (s.len == s.buf.length) s.buf = Arrays.copyOf(s.buf, s.len * 2);
            s.buf[s.len++] = a;
            return s;
        }
        FSpine n = new FSpine(Math.max(4, s.len * 2));
        System.arraycopy(s.buf, 0, n.buf, 0, s.len);
        n.buf[s.len] = a;
        n.len = s.len + 1;
        return n;
    }

    // ---- claim
    static final class Buf { Object[] a; int used; Buf(int cap) { a = new Object[cap]; } }
    static final class CSpine { final Buf buf; final int len; CSpine(Buf b, int l) { buf = b; len = l; } }
    static CSpine claimAppend(CSpine s, Object a) {
        Buf b = s.buf;
        if (b.used == s.len && s.len < b.a.length) {
            b.a[s.len] = a;
            b.used = s.len + 1;
            return new CSpine(b, s.len + 1);
        }
        Buf nb = new Buf(Math.max(4, s.len * 2));
        System.arraycopy(b.a, 0, nb.a, 0, s.len);
        nb.a[s.len] = a;
        nb.used = s.len + 1;
        return new CSpine(nb, s.len + 1);
    }

    static int[] lens; static boolean[] capture; static Arg[] argPool;
    /** every intermediate spine is published here so that it escapes, as in a real interpreter */
    static Object sink; static boolean ESCAPE;

    static long runCopy(int n) {
        long sum = 0;
        for (int i = 0; i < n; i++) {
            Object[] s = new Object[0]; Object[] cap = null;
            int L = lens[i];
            for (int k = 0; k < L; k++) {
                s = copyAppend(s, argPool[(i + k) & 1023]);
                if (ESCAPE) sink = s;
                if (capture[i] && k == L / 2) cap = s;
            }
            for (int k = 0; k < s.length; k++) sum += ((Arg) s[k]).v;
            if (cap != null) {
                Object[] t = copyAppend(cap, argPool[i & 1023]);
                sum += ((Arg) t[t.length - 1]).v + ((Arg) cap[cap.length - 1]).v;
            }
        }
        return sum;
    }
    static long runCons(int n) {
        long sum = 0;
        for (int i = 0; i < n; i++) {
            Snoc s = null, cap = null;
            int L = lens[i];
            for (int k = 0; k < L; k++) {
                s = new Snoc(s, argPool[(i + k) & 1023]);
                if (ESCAPE) sink = s;
                if (capture[i] && k == L / 2) cap = s;
            }
            for (Snoc p = s; p != null; p = p.prev) sum += ((Arg) p.arg).v;
            if (cap != null) {
                Snoc t = new Snoc(cap, argPool[i & 1023]);
                sum += ((Arg) t.arg).v + ((Arg) cap.arg).v;
            }
        }
        return sum;
    }
    static long runFlag(int n, int initCap) {
        long sum = 0;
        for (int i = 0; i < n; i++) {
            FSpine s = new FSpine(initCap), cap = null;
            int L = lens[i];
            for (int k = 0; k < L; k++) {
                s = flagAppend(s, argPool[(i + k) & 1023]);
                if (ESCAPE) sink = s;
                if (capture[i] && k == L / 2) { cap = s; s.shared = true; }
            }
            for (int k = 0; k < s.len; k++) sum += ((Arg) s.buf[k]).v;
            if (cap != null) {
                int capLen = L / 2 + 1; // the captured view's length (header is shared => must remember)
                FSpine t = flagAppend(cap, argPool[i & 1023]);
                sum += ((Arg) t.buf[t.len - 1]).v + ((Arg) cap.buf[capLen - 1]).v;
            }
        }
        return sum;
    }
    static long runClaim(int n, int initCap) {
        long sum = 0;
        for (int i = 0; i < n; i++) {
            CSpine s = new CSpine(new Buf(initCap), 0), cap = null;
            int L = lens[i];
            for (int k = 0; k < L; k++) {
                s = claimAppend(s, argPool[(i + k) & 1023]);
                if (ESCAPE) sink = s;
                if (capture[i] && k == L / 2) cap = s;
            }
            for (int k = 0; k < s.len; k++) sum += ((Arg) s.buf.a[k]).v;
            if (cap != null) {
                CSpine t = claimAppend(cap, argPool[i & 1023]);
                sum += ((Arg) t.buf.a[t.len - 1]).v + ((Arg) cap.buf.a[cap.len - 1]).v;
            }
        }
        return sum;
    }

    /** n-ary application: the evaluator knows all L arguments at once and allocates the spine array once. */
    static long runExact(int n) {
        long sum = 0;
        for (int i = 0; i < n; i++) {
            int L = lens[i];
            Object[] s = new Object[L];
            for (int k = 0; k < L; k++) s[k] = argPool[(i + k) & 1023];
            if (ESCAPE) sink = s;
            for (int k = 0; k < L; k++) sum += ((Arg) s[k]).v;
            if (capture[i]) {   // a later over-application of a shared prefix: one copy
                Object[] t = Arrays.copyOf(s, L / 2 + 2);
                t[L / 2 + 1] = argPool[i & 1023];
                sum += ((Arg) t[L / 2 + 1]).v + ((Arg) s[L / 2]).v;
            }
        }
        return sum;
    }

    public static void main(String[] args) {
        int n = Integer.parseInt(args.length > 0 ? args[0] : "4000000");
        double meanLen = Double.parseDouble(args.length > 1 ? args[1] : "4");
        double share = Double.parseDouble(args.length > 2 ? args[2] : "0.05");
        int reps = Integer.parseInt(args.length > 3 ? args[3] : "5");
        ESCAPE = args.length > 4 && args[4].equals("escape");
        final String only = args.length > 5 ? args[5] : "all";   // all | none | copy | cons | flag4 | flag2 | claim
        java.util.Random r = new java.util.Random(42);
        lens = new int[n]; capture = new boolean[n]; argPool = new Arg[1024];
        for (int i = 0; i < 1024; i++) argPool[i] = new Arg(i);
        long totalArgs = 0;
        for (int i = 0; i < n; i++) {
            // geometric-ish distribution with the requested mean, min 1, max 64
            int L = 1 + (int) Math.min(63, Math.floor(-Math.log(1 - r.nextDouble()) * (meanLen - 1)));
            lens[i] = L; totalArgs += L;
            capture[i] = r.nextDouble() < share;
        }
        System.out.printf("spines=%d meanLen=%.2f share=%.2f totalAppends=%d%n", n, (double) totalArgs / n, share, totalArgs);
        final long ops = totalArgs;
        final long[] chk = new long[5];
        int warm = Math.max(1, reps / 2);
        boolean all = only.equals("all");
        if (all || only.equals("copy")) Bench.best("copy (Arrays.copyOf each append)", ops, reps, warm, () -> chk[0] = runCopy(n));
        if (all || only.equals("cons")) Bench.best("cons (snoc list)", ops, reps, warm, () -> chk[1] = runCons(n));
        if (all || only.equals("flag4")) Bench.best("flag (unique in-place, cap 4)", ops, reps, warm, () -> chk[2] = runFlag(n, 4));
        if (all || only.equals("flag2")) Bench.best("flag (unique in-place, cap 2)", ops, reps, warm, () -> chk[3] = runFlag(n, 2));
        if (all || only.equals("claim")) Bench.best("claim (slot-claim buf, cap 4)", ops, reps, warm, () -> chk[4] = runClaim(n, 4));
        if (all || only.equals("exact")) Bench.best("exact (n-ary: one array of final size)", ops, reps, warm, () -> chk[0] = runExact(n));
        if (all) for (int i = 1; i < 5; i++) if (chk[i] != chk[1]) throw new AssertionError(java.util.Arrays.toString(chk));
        System.out.println("checksum " + chk[0]);
    }
}

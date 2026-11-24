import java.lang.management.ManagementFactory;
import java.util.function.IntFunction;

/**
 * B1: exact bytes per object for candidate RLVM node shapes.
 * Measures thread-allocated bytes for N allocations kept alive in an array
 * (prevents scalar replacement), minus the array itself.
 * Run with and without -XX:+UseCompactObjectHeaders, and with -XX:-UseCompressedOops.
 */
public final class Layout {
    static final com.sun.management.ThreadMXBean TMX =
        (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();

    static final class Empty {}
    static final class R1 { Object a; }
    static final class R2 { Object a, b; }
    static final class R3 { Object a, b, c; }
    static final class R4 { Object a, b, c, d; }
    static final class I1 { int x; }
    static final class I2 { int x, y; }
    static final class I3 { int x, y, z; }
    static final class R1I1 { Object a; int x; }
    static final class R2I1 { Object a, b; int x; }
    static final class R2I2 { Object a, b; int x, y; }
    static final class R3I1 { Object a, b, c; int x; }
    static final class R2L1 { Object a, b; long x; }
    static final class R1B1 { Object a; byte f; }
    static final class R2B1 { Object a, b; byte f; }
    static final class R3B1 { Object a, b, c; byte f; }
    static final class L1 { long x; }
    // typical "hash-consed expr node": 2 children + hash + packed flags/loose-bvar-range
    static final class ExprApp { Object fn, arg; int hash; int meta; }
    // thunk: term id + env + cached value + state byte
    static final class Thunk { int term; Object env; Object value; byte state; }
    // closure: term id + env + levels
    static final class Clo { int term; Object env; Object ls; }
    // neutral with spine header
    static final class Neu { Object head; Object[] spine; int len; byte flags; }
    // env cons frame
    static final class Frame { Object val; Object parent; }
    static final class FrameD { Object val; Object parent; int depth; }

    static long measure(int n, IntFunction<Object> f) {
        Object[] keep = new Object[n];
        // warm up allocation path
        for (int i = 0; i < n; i++) keep[i] = f.apply(i);
        long t = Thread.currentThread().threadId();
        long before = TMX.getThreadAllocatedBytes(t);
        for (int i = 0; i < n; i++) keep[i] = f.apply(i);
        long after = TMX.getThreadAllocatedBytes(t);
        if (keep[n / 2] == null) throw new AssertionError();
        return after - before;
    }

    static void row(String name, IntFunction<Object> f) {
        int n = 1 << 20;
        long best = Long.MAX_VALUE;
        for (int r = 0; r < 5; r++) best = Math.min(best, measure(n, f));
        System.out.printf("%-28s %6.2f bytes%n", name, (double) best / n);
    }

    public static void main(String[] args) {
        System.out.println("java.vm.name=" + System.getProperty("java.vm.name")
            + " args=" + ManagementFactory.getRuntimeMXBean().getInputArguments());
        row("Empty", i -> new Empty());
        row("R1 (1 ref)", i -> new R1());
        row("R2 (2 refs)", i -> new R2());
        row("R3 (3 refs)", i -> new R3());
        row("R4 (4 refs)", i -> new R4());
        row("I1 (1 int)", i -> new I1());
        row("I2 (2 ints)", i -> new I2());
        row("I3 (3 ints)", i -> new I3());
        row("L1 (1 long)", i -> new L1());
        row("R1I1", i -> new R1I1());
        row("R2I1", i -> new R2I1());
        row("R2I2 / ExprApp", i -> new ExprApp());
        row("R3I1", i -> new R3I1());
        row("R2L1", i -> new R2L1());
        row("R1B1", i -> new R1B1());
        row("R2B1", i -> new R2B1());
        row("R3B1", i -> new R3B1());
        row("Thunk{int,ref,ref,byte}", i -> new Thunk());
        row("Clo{int,ref,ref}", i -> new Clo());
        row("Neu{ref,ref,int,byte}", i -> new Neu());
        row("Frame{ref,ref}", i -> new Frame());
        row("FrameD{ref,ref,int}", i -> new FrameD());
        row("Object[0]", i -> new Object[0]);
        row("Object[1]", i -> new Object[1]);
        row("Object[2]", i -> new Object[2]);
        row("Object[3]", i -> new Object[3]);
        row("Object[4]", i -> new Object[4]);
        row("Object[8]", i -> new Object[8]);
        row("int[0]", i -> new int[0]);
        row("int[1]", i -> new int[1]);
        row("int[2]", i -> new int[2]);
        row("int[3]", i -> new int[3]);
        row("int[4]", i -> new int[4]);
        row("long[1]", i -> new long[1]);
        row("byte[8]", i -> new byte[8]);
        row("Long.valueOf(big)", i -> Long.valueOf(1_000_000L + i));
        row("Integer.valueOf(big)", i -> Integer.valueOf(1_000_000 + i));
        row("BigInteger(small)", i -> java.math.BigInteger.valueOf(1_000_000L + i));
        row("lambda capturing 1 ref", i -> { Object o = TMX; Runnable r = () -> o.hashCode(); return r; });
    }
}

package rlvm.spike.machine;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.reflect.Field;
import java.util.Arrays;

/**
 * The only place that touches the value arena (H) and the hash-consing table (T). The access mode is a
 * {@code static final} constant so that every runtime folds the dispatch away: the HotSpot JIT because static
 * finals are trusted constants, native-image because this class is initialised at image build time
 * (pass {@code -Drlvm.mem=N} to the native-image builder).
 *
 * <pre>
 *   0  int[] with bounds checks (default)
 *   1  off-heap via sun.misc.Unsafe (no checks)
 *   2  off-heap via MemorySegment.getAtIndex(JAVA_INT, i)
 *   3  off-heap via MemorySegment.get(JAVA_INT_UNALIGNED, i * 4)
 * </pre>
 */
public final class Mem {
    private Mem() {
    }

    public static final int MODE = Integer.getInteger("rlvm.mem", 0);

    public static int[] H, T;
    public static long HA, TA;
    /** Capacities in words (ints). */
    public static int hcap, tcap;

    private static final sun.misc.Unsafe U = unsafe();

    private static sun.misc.Unsafe unsafe() {
        try {
            Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            return (sun.misc.Unsafe) f.get(null);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    public static void alloc(int hWords, int tWords) {
        hcap = hWords;
        tcap = tWords;
        if (MODE == 0) {
            H = new int[hWords];
            T = new int[tWords];
        } else if (MODE == 1) {
            HA = U.allocateMemory((long) hWords << 2);
            TA = U.allocateMemory((long) tWords << 2);
            U.setMemory(TA, (long) tWords << 2, (byte) 0);
        } else {
            if (SegHolder.H.byteSize() != ((long) hWords << 2) || SegHolder.T.byteSize() != ((long) tWords << 2)) {
                throw new IllegalStateException("segment mode: sizes are fixed by -Drlvm.hcap / -Drlvm.tbits");
            }
        }
    }

    public static int h(int i) {
        if (MODE == 0) {
            return H[i];
        } else if (MODE == 1) {
            return U.getInt(HA + ((long) i << 2));
        } else if (MODE == 2) {
            return SegHolder.H.getAtIndex(ValueLayout.JAVA_INT, (long) i);
        } else {
            return SegHolder.H.get(ValueLayout.JAVA_INT_UNALIGNED, (long) i << 2);
        }
    }

    public static void hset(int i, int v) {
        if (MODE == 0) {
            H[i] = v;
        } else if (MODE == 1) {
            U.putInt(HA + ((long) i << 2), v);
        } else if (MODE == 2) {
            SegHolder.H.setAtIndex(ValueLayout.JAVA_INT, (long) i, v);
        } else {
            SegHolder.H.set(ValueLayout.JAVA_INT_UNALIGNED, (long) i << 2, v);
        }
    }

    public static int t(int i) {
        if (MODE == 0) {
            return T[i];
        } else if (MODE == 1) {
            return U.getInt(TA + ((long) i << 2));
        } else if (MODE == 2) {
            return SegHolder.T.getAtIndex(ValueLayout.JAVA_INT, (long) i);
        } else {
            return SegHolder.T.get(ValueLayout.JAVA_INT_UNALIGNED, (long) i << 2);
        }
    }

    public static void tset(int i, int v) {
        if (MODE == 0) {
            T[i] = v;
        } else if (MODE == 1) {
            U.putInt(TA + ((long) i << 2), v);
        } else if (MODE == 2) {
            SegHolder.T.setAtIndex(ValueLayout.JAVA_INT, (long) i, v);
        } else {
            SegHolder.T.set(ValueLayout.JAVA_INT_UNALIGNED, (long) i << 2, v);
        }
    }

    /** Grows the arena to at least {@code need} words. */
    public static void growH(int need) {
        int n = Math.max(need, hcap + (hcap >> 1));
        if (MODE == 0) {
            H = Arrays.copyOf(H, n);
        } else if (MODE == 1) {
            HA = U.reallocateMemory(HA, (long) n << 2);
        } else {
            throw new IllegalStateException("segment mode: arena full, raise -Drlvm.hcap");
        }
        hcap = n;
    }

    /** Replaces the table by a zeroed one of {@code words} entries; returns a handle on the old one. */
    public static long swapT(int words) {
        long old = 0;
        if (MODE == 0) {
            OLD = T;
            T = new int[words];
        } else if (MODE == 1) {
            old = TA;
            TA = U.allocateMemory((long) words << 2);
            U.setMemory(TA, (long) words << 2, (byte) 0);
        } else {
            throw new IllegalStateException("segment mode: table full, raise -Drlvm.tbits");
        }
        tcap = words;
        return old;
    }

    private static int[] OLD;

    public static int oldT(long handle, int i) {
        return MODE == 0 ? OLD[i] : U.getInt(handle + ((long) i << 2));
    }

    public static void freeOldT(long handle) {
        if (MODE == 0) {
            OLD = null;
        } else {
            U.freeMemory(handle);
        }
    }

    /** Kept out of {@link Mem} so that its static finals are real constants for the JIT (run-time initialised). */
    static final class SegHolder {
        static final MemorySegment H = java.lang.foreign.Arena.global().allocate((long) Integer.getInteger("rlvm.hcap", 1 << 24) << 2, 8);
        static final MemorySegment T = java.lang.foreign.Arena.global().allocate(4L << Integer.getInteger("rlvm.tbits", 16), 8);
    }
}

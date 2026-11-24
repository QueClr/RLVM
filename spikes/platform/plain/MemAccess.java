import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;

/**
 * Packed term storage access cost: int[] vs IntBuffer vs MemorySegment (heap / native) vs
 * byte[] + VarHandle vs sun.misc.Unsafe. A "record" is 3 ints (tag, a, b) where a and b are record
 * indices; the kernel follows a/b references (random access) and scans sequentially.
 *
 * <pre>
 * java MemAccess.java [records=20000000] [rounds=5]
 * </pre>
 *
 * Reports ns per record for (seq) a sequential scan and (chase) pointer chasing through 'a' links.
 */
public final class MemAccess {
    static final ValueLayout.OfInt INT = ValueLayout.JAVA_INT;
    static final VarHandle BYTES_INT = MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.nativeOrder());
    static final sun.misc.Unsafe U;

    static {
        try {
            java.lang.reflect.Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            U = (sun.misc.Unsafe) f.get(null);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    interface Store {
        long seq(int n);

        long chase(int n, int steps);
    }

    static final class IntArrayStore implements Store {
        final int[] d;

        IntArrayStore(int[] d) {
            this.d = d;
        }

        public long seq(int n) {
            long s = 0;
            int[] a = d;
            for (int i = 0; i < n; i++) {
                s += a[3 * i] + a[3 * i + 1] + a[3 * i + 2];
            }
            return s;
        }

        public long chase(int n, int steps) {
            long s = 0;
            int[] a = d;
            int i = 1;
            for (int k = 0; k < steps; k++) {
                s += a[3 * i] ^ a[3 * i + 2];
                i = a[3 * i + 1];
            }
            return s;
        }
    }

    static final class IntBufferStore implements Store {
        final IntBuffer d;

        IntBufferStore(IntBuffer d) {
            this.d = d;
        }

        public long seq(int n) {
            long s = 0;
            IntBuffer a = d;
            for (int i = 0; i < n; i++) {
                s += a.get(3 * i) + a.get(3 * i + 1) + a.get(3 * i + 2);
            }
            return s;
        }

        public long chase(int n, int steps) {
            long s = 0;
            IntBuffer a = d;
            int i = 1;
            for (int k = 0; k < steps; k++) {
                s += a.get(3 * i) ^ a.get(3 * i + 2);
                i = a.get(3 * i + 1);
            }
            return s;
        }
    }

    static final class SegmentStore implements Store {
        final MemorySegment d;

        SegmentStore(MemorySegment d) {
            this.d = d;
        }

        public long seq(int n) {
            long s = 0;
            MemorySegment a = d;
            for (int i = 0; i < n; i++) {
                long o = 12L * i;
                s += a.get(INT, o) + a.get(INT, o + 4) + a.get(INT, o + 8);
            }
            return s;
        }

        public long chase(int n, int steps) {
            long s = 0;
            MemorySegment a = d;
            int i = 1;
            for (int k = 0; k < steps; k++) {
                long o = 12L * i;
                s += a.get(INT, o) ^ a.get(INT, o + 8);
                i = a.get(INT, o + 4);
            }
            return s;
        }
    }

    static final class ByteArrayStore implements Store {
        final byte[] d;

        ByteArrayStore(byte[] d) {
            this.d = d;
        }

        public long seq(int n) {
            long s = 0;
            byte[] a = d;
            for (int i = 0; i < n; i++) {
                int o = 12 * i;
                s += (int) BYTES_INT.get(a, o) + (int) BYTES_INT.get(a, o + 4) + (int) BYTES_INT.get(a, o + 8);
            }
            return s;
        }

        public long chase(int n, int steps) {
            long s = 0;
            byte[] a = d;
            int i = 1;
            for (int k = 0; k < steps; k++) {
                int o = 12 * i;
                s += (int) BYTES_INT.get(a, o) ^ (int) BYTES_INT.get(a, o + 8);
                i = (int) BYTES_INT.get(a, o + 4);
            }
            return s;
        }
    }

    static final class UnsafeStore implements Store {
        final long base;

        UnsafeStore(long base) {
            this.base = base;
        }

        public long seq(int n) {
            long s = 0;
            long b = base;
            for (int i = 0; i < n; i++) {
                long o = b + 12L * i;
                s += U.getInt(o) + U.getInt(o + 4) + U.getInt(o + 8);
            }
            return s;
        }

        public long chase(int n, int steps) {
            long s = 0;
            long b = base;
            int i = 1;
            for (int k = 0; k < steps; k++) {
                long o = b + 12L * i;
                s += U.getInt(o) ^ U.getInt(o + 8);
                i = U.getInt(o + 4);
            }
            return s;
        }
    }

    static void bench(String name, Store st, int n, int rounds) {
        long bestSeq = Long.MAX_VALUE;
        long bestChase = Long.MAX_VALUE;
        long check = 0;
        int steps = Math.min(n, 20_000_000);
        for (int r = 0; r < rounds; r++) {
            long t0 = System.nanoTime();
            check += st.seq(n);
            long t1 = System.nanoTime();
            check += st.chase(n, steps);
            long t2 = System.nanoTime();
            bestSeq = Math.min(bestSeq, t1 - t0);
            bestChase = Math.min(bestChase, t2 - t1);
        }
        System.out.printf("%-28s seq %6.2f ns/record   chase %7.2f ns/step   (check %x)%n", name, bestSeq / (double) n, bestChase / (double) steps, check);
    }

    public static void main(String[] args) {
        int n = args.length > 0 ? Integer.parseInt(args[0]) : 20_000_000;
        int rounds = args.length > 1 ? Integer.parseInt(args[1]) : 5;
        System.out.println("vm: " + System.getProperty("java.vm.name") + " " + System.getProperty("java.vm.version") + ", records=" + n + " (" + (12L * n >> 20) + " MB)");
        int[] ints = new int[3 * n];
        long seed = 42;
        for (int i = 0; i < n; i++) {
            seed = seed * 6364136223846793005L + 1442695040888963407L;
            ints[3 * i] = (int) (seed >>> 60);
            ints[3 * i + 1] = (int) ((seed >>> 20) % n); // random link: defeats the prefetcher
            ints[3 * i + 2] = i;
        }
        byte[] bytes = new byte[12 * n];
        ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder()).asIntBuffer().put(ints);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment nat = arena.allocate(12L * n, 64);
            MemorySegment.copy(ints, 0, nat, INT, 0, ints.length);
            ByteBuffer direct = ByteBuffer.allocateDirect(12 * n).order(ByteOrder.nativeOrder());
            direct.asIntBuffer().put(ints);
            for (int pass = 0; pass < 2; pass++) { // pass 0 warms every implementation, pass 1 is reported
                if (pass == 1) {
                    System.out.println("--- measured pass");
                }
                bench("int[]", new IntArrayStore(ints), n, rounds);
                bench("IntBuffer (heap)", new IntBufferStore(IntBuffer.wrap(ints)), n, rounds);
                bench("IntBuffer (direct)", new IntBufferStore(direct.asIntBuffer()), n, rounds);
                bench("MemorySegment.ofArray(int[])", new SegmentStore(MemorySegment.ofArray(ints)), n, rounds);
                bench("MemorySegment (native)", new SegmentStore(nat), n, rounds);
                bench("byte[] + VarHandle", new ByteArrayStore(bytes), n, rounds);
                bench("Unsafe (native address)", new UnsafeStore(nat.address()), n, rounds);
            }
        }
    }
}

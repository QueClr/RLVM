import java.util.function.Function;
import java.util.function.Supplier;

/**
 * JOL-free object size measurement. Two independent methods:
 * (1) exact bytes allocated by this thread (com.sun.management.ThreadMXBean) per object, HotSpot only;
 * (2) retained heap delta of N live objects after System.gc(), works on HotSpot and native-image.
 *
 * <pre>
 * java ObjSize.java                                   (compressed oops, 12-byte headers)
 * java -XX:+UseCompactObjectHeaders ObjSize.java      (JDK 25: 8-byte headers)
 * java -XX:-UseCompressedOops ObjSize.java            (what a heap above 32 GB costs)
 * </pre>
 */
public final class ObjSize {
    // Fields are written with run-time values and read back in consume(): otherwise native-image's
    // closed-world analysis removes them and every class measures as a bare header.
    static final class Ref1 { Object a; Ref1() { a = V; } }
    static final class Ref2 { Object a, b; Ref2() { a = V; b = V; } }
    static final class Ref2Int { Object a, b; int x; Ref2Int() { a = V; b = V; x = I; } }
    static final class Ref2Int2 { Object a, b; int x, y; Ref2Int2() { a = V; b = V; x = I; y = I; } }
    static final class Ref3 { Object a, b, c; Ref3() { a = V; b = V; c = V; } }
    static final class Ref3Int { Object a, b, c; int x; Ref3Int() { a = V; b = V; c = V; x = I; } }
    static final class Ref4 { Object a, b, c, d; Ref4() { a = V; b = V; c = V; d = V; } }
    static final class IntOnly { int x; IntOnly() { x = I; } }
    static final class LongOnly { long x; LongOnly() { x = I; } }
    static final class ByteRef2 { byte tag; Object a, b; ByteRef2() { tag = (byte) I; a = V; b = V; } }
    static final class LongRef { long x; Object a; LongRef() { x = I; a = V; } }

    static Object V = new Object();
    static int I = (int) System.nanoTime();
    static volatile long consumed;

    static void consume(Object o) {
        long h = 0;
        switch (o) {
            case Ref1 r -> h = System.identityHashCode(r.a);
            case Ref2 r -> h = System.identityHashCode(r.a) + System.identityHashCode(r.b);
            case Ref2Int r -> h = System.identityHashCode(r.a) + System.identityHashCode(r.b) + r.x;
            case Ref2Int2 r -> h = System.identityHashCode(r.a) + System.identityHashCode(r.b) + r.x + r.y;
            case Ref3 r -> h = System.identityHashCode(r.a) + System.identityHashCode(r.b) + System.identityHashCode(r.c);
            case Ref3Int r -> h = System.identityHashCode(r.a) + System.identityHashCode(r.b) + System.identityHashCode(r.c) + r.x;
            case Ref4 r -> h = System.identityHashCode(r.a) + System.identityHashCode(r.b) + System.identityHashCode(r.c) + System.identityHashCode(r.d);
            case IntOnly r -> h = r.x;
            case LongOnly r -> h = r.x;
            case ByteRef2 r -> h = r.tag + System.identityHashCode(r.a) + System.identityHashCode(r.b);
            case LongRef r -> h = r.x + System.identityHashCode(r.a);
            default -> h = 1;
        }
        consumed += h;
    }

    static Object sink;

    static long allocatedBytes() {
        try {
            return ((com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean()).getCurrentThreadAllocatedBytes();
        } catch (Throwable t) {
            return -1;
        }
    }

    static long usedHeap() {
        for (int i = 0; i < 3; i++) {
            System.gc();
        }
        Runtime r = Runtime.getRuntime();
        return r.totalMemory() - r.freeMemory();
    }

    static void measure(String name, Supplier<Object> f) {
        final int n = 2_000_000;
        Object[] keep = new Object[n];
        for (int i = 0; i < 20_000; i++) {
            sink = f.get(); // warm up so that allocation is compiled and not scalar-replaced below
        }
        long h0 = usedHeap();
        long a0 = allocatedBytes();
        for (int i = 0; i < n; i++) {
            keep[i] = f.get();
        }
        long a1 = allocatedBytes();
        long h1 = usedHeap();
        double exact = a0 < 0 ? Double.NaN : (a1 - a0) / (double) n;
        double retained = (h1 - h0) / (double) n;
        System.out.printf("%-34s allocated %6.2f B/obj   retained %6.2f B/obj%n", name, exact, retained);
        sink = keep[n / 2];
        consume(keep[n / 3]);
    }

    public static void main(String[] args) {
        System.out.println("vm: " + System.getProperty("java.vm.name") + " " + System.getProperty("java.vm.version") + "  maxHeap=" + (Runtime.getRuntime().maxMemory() >> 20) + "MB");
        Object o = new Object();
        measure("Object", Object::new);
        measure("IntOnly {int}", IntOnly::new);
        measure("LongOnly {long}", LongOnly::new);
        measure("Ref1 {ref}", Ref1::new);
        measure("Ref2 {ref,ref}", Ref2::new);
        measure("ByteRef2 {byte,ref,ref}", ByteRef2::new);
        measure("Ref2Int {ref,ref,int}", Ref2Int::new);
        measure("Ref2Int2 {ref,ref,int,int}", Ref2Int2::new);
        measure("Ref3 {ref,ref,ref}", Ref3::new);
        measure("Ref3Int {ref,ref,ref,int}", Ref3Int::new);
        measure("Ref4 {ref x4}", Ref4::new);
        measure("LongRef {long,ref}", LongRef::new);
        measure("Long (boxed)", () -> Long.valueOf(System.nanoTime() | 1L << 40));
        measure("int[0]", () -> new int[0]);
        measure("int[3]", () -> new int[3]);
        measure("int[4]", () -> new int[4]);
        measure("Object[1]", () -> new Object[1]);
        measure("Object[2]", () -> new Object[2]);
        measure("Object[4]", () -> new Object[4]);
        measure("lambda capturing 1 ref", () -> {
            Function<Object, Object> f = x -> o;
            return f;
        });
        measure("lambda capturing 2 refs", () -> {
            Object p = sink;
            Function<Object, Object> f = x -> x == null ? o : p;
            return f;
        });
    }
}

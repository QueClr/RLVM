package rlvm.export;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;

/**
 * The one place where the reader loads multi-byte words from a buffer.
 *
 * <p>{@link #u64} is an unaligned little-endian 64-bit load through a {@code byte[]} view
 * {@link VarHandle}: bounds-checked, no {@code sun.misc.Unsafe}, supported by native-image. The byte
 * order is fixed, so the packed literals of {@link Scanner} are platform independent.
 */
final class Mem {
    private Mem() {}

    /**
     * The handle of {@link #u64}. {@link Scanner} loads through it directly, so that the load stands in
     * the scan loop whatever a compiler decides about inlining (see there).
     */
    static final VarHandle LE64 =
            MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);

    static long u64(byte[] b, int i) {
        return (long) LE64.get(b, i);
    }

    static byte u8(byte[] b, int i) {
        return b[i];
    }
}

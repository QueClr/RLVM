package rlvm.cli;

import java.nio.charset.StandardCharsets;

import org.graalvm.nativeimage.UnmanagedMemory;
import org.graalvm.nativeimage.c.function.CFunction;
import org.graalvm.nativeimage.c.type.CCharPointer;
import org.graalvm.nativeimage.c.type.CCharPointerPointer;

/**
 * {@code execv(2)} for the executable: the one C function the hand-over to a JVM needs ({@link Handover}).
 *
 * <p>This class is only ever used inside a native image. On a JVM nothing refers to it at run time (the
 * hand-over is a step of the executable alone), so the SDK classes it names need not be on the class path of
 * a JVM run: the jars the executable hands over do not contain them.
 */
final class NativeExec {
    private NativeExec() {
    }

    /** {@code int execv(const char *path, char *const argv[])} of the C library. */
    @CFunction(value = "execv")
    private static native int execv(CCharPointer path, CCharPointerPointer argv);

    /**
     * Replaces this process by {@code path} with the arguments {@code argv} ({@code argv[0]} included) and the
     * environment as it is. Returns only if that failed; the process is then as it was.
     */
    static void exec(String path, String[] argv) {
        CCharPointer cPath = cString(path);
        // calloc: the terminating null pointer of the array is already there.
        CCharPointerPointer cArgv = UnmanagedMemory.calloc((argv.length + 1) * 8);
        for (int i = 0; i < argv.length; i++) {
            cArgv.write(i, cString(argv[i]));
        }
        execv(cPath, cArgv);
        // Still here: execv failed. Give the memory back; the caller checks in-process.
        for (int i = 0; i < argv.length; i++) {
            UnmanagedMemory.free(cArgv.read(i));
        }
        UnmanagedMemory.free(cArgv);
        UnmanagedMemory.free(cPath);
    }

    private static CCharPointer cString(String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        CCharPointer p = UnmanagedMemory.malloc(b.length + 1);
        for (int i = 0; i < b.length; i++) {
            p.write(i, b[i]);
        }
        p.write(b.length, (byte) 0);
        return p;
    }
}

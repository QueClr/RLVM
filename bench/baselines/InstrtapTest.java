/* bench/baselines/InstrtapTest.java - instrtap.c with a JVM program.
 *
 *   java InstrtapTest ITERATIONS
 *
 * Prints "start K" / "end K" on System.err around a loop, four times on the
 * main thread and once on a second thread. Under LD_PRELOAD=libinstrtap.so
 * with INSTRTAP_PREFIXES='start |end ' every such line is preceded by the
 * thread's own instruction count (the JIT compiler's and the collector's
 * threads are not part of it). The loop is 6 instructions per iteration once
 * compiled; the first rounds are cheaper or dearer while it is interpreted
 * and compiled on the stack. Run by bench/baselines/profile-mathlib.sh selftest-java.
 */
public class InstrtapTest {
    static long spin(long n) {
        long x = 88172645463325252L;
        for (long i = 0; i < n; i++) { x ^= x << 13; x ^= x >>> 7; x ^= x << 17; }
        return x;
    }

    public static void main(String[] args) throws Exception {
        long n = Long.parseLong(args[0]);
        for (int k = 0; k < 4; k++) {
            System.err.println("start " + k);
            long r = spin(n);
            System.err.println("end " + k + " " + r);
        }
        Thread t = new Thread(() -> {
            System.err.println("start 9");
            long r = spin(n);
            System.err.println("end 9 " + r);
        });
        t.start();
        t.join();
    }
}

// JVM workload for bench/perf-validate.py: a fixed amount of allocation-heavy
// work, single-threaded or on T threads. Unlike burn.c the instruction count
// is not deterministic (JIT compilation and GC run on background threads),
// which is exactly what we want to quantify for a JVM-based checker.
//
//   java Burn <threads> <iterations>
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public final class Burn {
    static long work(long n) {
        HashMap<Long, Long> map = new HashMap<>();
        long x = 88172645463325252L;
        long acc = 0;
        for (long i = 0; i < n; i++) {
            x ^= x << 13;
            x ^= x >>> 7;
            x ^= x << 17;
            long key = x & 0xffff;
            Long old = map.put(key, x);
            if (old != null) acc += old;
        }
        return acc + map.size();
    }

    public static void main(String[] args) throws Exception {
        int threads = Integer.parseInt(args[0]);
        long n = Long.parseLong(args[1]);
        long[] results = new long[threads];
        List<Thread> pool = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            final int id = t;
            Thread thread = new Thread(() -> results[id] = work(n));
            pool.add(thread);
            thread.start();
        }
        long sum = 0;
        for (int t = 0; t < threads; t++) {
            pool.get(t).join();
            sum += results[t];
        }
        System.out.println(sum);
    }
}

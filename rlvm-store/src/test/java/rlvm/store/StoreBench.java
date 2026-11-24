package rlvm.store;

import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.util.Random;

/**
 * Micro-benchmarks of the store's write paths, meant to be run twice with different sizes under
 * {@code bench/measure.sh} in the arena container: the cost per operation is the difference of the
 * two runs divided by the difference of the sizes, which removes JVM start-up and most of the
 * warm-up. Each mode prints one line with its own in-process timing as a cross-check.
 *
 * <pre>
 *   java [-Dwarm=W] -cp ... rlvm.store.StoreBench MODE N        (W operations first, not timed in-process)
 *   append        N APP nodes into the export region, children: the previous node and a nearby leaf
 *   append-far    the same, second child anywhere in the region (cache-unfriendly)
 *   mk            N overlay mkApp calls that create a node (session reset every 16 MiB)
 *   mk-hit        N overlay mkApp calls that find an existing node (table of 2^16 nodes)
 *   decode        N proof-block records decoded (blocks of 4,096 records, half of the references shared)
 *   session       N sessions: 64 nodes, then reset
 *   overlay-new   N overlay objects created (with their first node)
 *   names         N distinct names interned (components of 6..21 bytes, prefix chains)
 *   names-gen     the generation of those N components alone (subtract it from "names")
 *   instantiate   N instantiate1 calls on a body of about 40 nodes
 * </pre>
 */
final class StoreBench {
    private StoreBench() {}

    public static void main(String[] args) {
        String mode = args[0];
        long n = Long.parseLong(args[1]);
        long warm = Long.getLong("warm", 0);
        if (warm > 0) {
            run(mode, warm);
        }
        long t0 = System.nanoTime();
        long c0 = ManagementFactory.getThreadMXBean().getCurrentThreadCpuTime();
        long check = run(mode, n);
        long cpu = ManagementFactory.getThreadMXBean().getCurrentThreadCpuTime() - c0;
        long wall = System.nanoTime() - t0;
        System.out.println(mode + " n=" + n + " wallNsPerOp=" + (wall / Math.max(n, 1)) + " cpuNsPerOp=" + (cpu / Math.max(n, 1))
                + " check=" + check);
    }

    private static long run(String mode, long n) {
        long check;
        switch (mode) {
            case "append":
                check = append(n, false);
                break;
            case "append-far":
                check = append(n, true);
                break;
            case "mk":
                check = mk(n);
                break;
            case "mk-hit":
                check = mkHit(n);
                break;
            case "decode":
                check = decode(n);
                break;
            case "session":
                check = session(n);
                break;
            case "overlay-new":
                check = overlayNew(n);
                break;
            case "names":
                check = names(n, true);
                break;
            case "names-gen":
                check = names(n, false);
                break;
            case "instantiate":
                check = instantiate(n);
                break;
            default:
                throw new IllegalArgumentException(mode);
        }
        return check;
    }

    private static Model fixture(long inputBytes) {
        Model fx = new Model(inputBytes);
        fx.declareLiteralPrerequisites(true);
        return fx;
    }

    private static long append(long n, boolean far) {
        Model fx = fixture(n * 144);
        StoreBuilder b = fx.builder;
        int[] leaves = new int[8];
        leaves[0] = b.constant(fx.constNames[0], 0);
        leaves[1] = b.constant(fx.constNames[1], fx.levelLists[1]);
        leaves[2] = b.sort(fx.levelIds[1]);
        leaves[3] = b.bvar(0);
        leaves[4] = b.bvar(1);
        leaves[5] = b.natSmall(7);
        leaves[6] = b.constant(fx.constNames[3], 0);
        leaves[7] = b.sort(fx.levelIds[2]);
        int cur = leaves[0];
        int unit = 0;
        long x = 88172645463325252L;
        for (long i = 0; i < n; i++) {
            if ((i & 1023) == 0) {
                if (i != 0) {
                    b.endUnit();
                }
                b.beginUnit(DeclKind.DEFN);
                unit++;
            }
            x ^= x << 13;
            x ^= x >>> 7;
            x ^= x << 17;
            int other = far ? 1 + (int) ((x >>> 1) % cur) : leaves[(int) x & 7];
            cur = b.app(cur, other);
        }
        b.endUnit();
        return cur + unit;
    }

    private static long mk(long n) {
        Model fx = fixture(0);
        fx.builder.finish();
        Overlay o = new Overlay(fx.store, fx.store.lists(), 1L << 30);
        long sum = 0;
        int c = 0;
        int cur = 0;
        for (long i = 0; i < n; i++) {
            if (o.usedBytes() >= (16L << 20) || cur == 0) {
                o.reset();
                c = o.mkConst(fx.constCids[0], 0);
                cur = c;
            }
            cur = o.mkApp(cur, c);
            sum += cur;
        }
        return sum;
    }

    private static long mkHit(long n) {
        Model fx = fixture(0);
        fx.builder.finish();
        Overlay o = new Overlay(fx.store, fx.store.lists(), 1L << 30);
        int size = 1 << 16;
        int[] f = new int[size];
        int c = o.mkConst(fx.constCids[0], 0);
        f[0] = c;
        for (int i = 1; i < size; i++) {
            f[i] = o.mkApp(f[i - 1], c);
        }
        long sum = 0;
        int k = 1;
        for (long i = 0; i < n; i++) {
            sum += o.mkApp(f[k - 1], c);
            k = k + 1 < size ? k + 1 : 1;
        }
        if (o.nodeCount() != size) {
            throw new IllegalStateException("a hit made a node");
        }
        return sum;
    }

    private static long decode(long n) {
        Model fx = fixture(64L << 20);
        StoreBuilder b = fx.builder;
        // 200,000 shared nodes to reference
        int sharedCount = 200_000;
        int[] shared = new int[sharedCount];
        int cur = b.constant(fx.constNames[0], 0);
        for (int i = 0; i < sharedCount; i++) {
            cur = b.app(cur, b.constant(fx.constNames[i % 6 == 3 ? 3 : 0], 0));
            shared[i] = cur;
        }
        ProofBlocks.Writer w = b.blockWriter();
        Random rnd = new Random(1);
        int blocks = 64;
        int per = 4096;
        for (int blk = 0; blk < blocks; blk++) {
            w.bvar(0);
            for (int k = 1; k < per; k++) {
                int r1 = ProofBlocks.Writer.local(1);
                int r2 = (k & 1) == 0 ? ProofBlocks.Writer.shared(shared[rnd.nextInt(sharedCount)])
                        : ProofBlocks.Writer.local(1 + rnd.nextInt(Math.min(k, 30)));
                if ((k & 15) == 15) {
                    w.lam(r2, r1);
                } else {
                    w.app(r1, r2);
                }
            }
            w.finish(per - 1);
        }
        b.finish();
        Overlay o = new Overlay(fx.store, fx.store.lists(), 1L << 30);
        long sum = 0;
        long done = 0;
        int blk = 0;
        while (done < n) {
            if (o.usedBytes() >= (16L << 20)) {
                o.reset();
            }
            sum += o.blockRoot(blk);
            blk = blk + 1 < blocks ? blk + 1 : 0;
            if (blk == 0) {
                o.reset();
            }
            done += per;
        }
        return sum;
    }

    private static long session(long n) {
        Model fx = fixture(0);
        fx.builder.finish();
        Overlay o = new Overlay(fx.store, fx.store.lists(), 1L << 30);
        long sum = 0;
        for (long i = 0; i < n; i++) {
            int c = o.mkConst(fx.constCids[0], 0);
            int cur = c;
            for (int k = 0; k < 63; k++) {
                cur = o.mkApp(cur, c);
            }
            sum += cur;
            o.reset();
        }
        return sum;
    }

    private static long overlayNew(long n) {
        Model fx = fixture(0);
        fx.builder.finish();
        long sum = 0;
        for (long i = 0; i < n; i++) {
            Overlay o = new Overlay(fx.store, fx.store.lists(), 16L << 20);
            Terms t = new Terms(fx.store.terms(), o);
            sum += t.kind(o.mkConst(fx.constCids[0], 0));
        }
        return sum;
    }

    /** {@code intern} false: only the generation of the components (the part of "names" that is not the table's). */
    private static long names(long n, boolean intern) {
        NameTable t = new NameTable((int) Math.min(n, 1 << 28));
        int[] recent = new int[1 << 12];
        byte[] buf = new byte[32];
        long x = 88172645463325252L;
        long sum = 0;
        for (long i = 0; i < n; i++) {
            x ^= x << 13;
            x ^= x >>> 7;
            x ^= x << 17;
            int prefix = recent[(int) (x >>> 20) & 4095];
            int id;
            if ((x & 15) == 0) {
                id = intern ? t.internNum(prefix, i) : (int) i;
            } else {
                int len = 6 + (int) ((x >>> 8) & 15);
                long v = i;
                for (int k = 0; k < len; k++) {
                    buf[k] = (byte) ('a' + (int) (v % 26));
                    v = v / 26 + (k == 9 ? x >>> 40 : 0);
                }
                id = intern ? t.internStr(prefix, buf, 0, len) : buf[len - 1] + (int) i;
            }
            recent[(int) i & 4095] = id;
            sum += id;
        }
        if (intern && t.size() < n / 2) {
            throw new IllegalStateException("names were not distinct");
        }
        return sum + "x".getBytes(StandardCharsets.US_ASCII).length;
    }

    private static long instantiate(long n) {
        Model fx = fixture(0);
        // the body lives in the shared region: fun-body with #0 at several depths
        StoreBuilder b = fx.builder;
        int c0 = b.constant(fx.constNames[0], 0);
        int c3 = b.constant(fx.constNames[3], 0);
        int v0 = b.bvar(0);
        int v1 = b.bvar(1);
        int dom = b.sort(fx.levelIds[1]);
        int body = b.app(b.app(c0, v0), c3);
        for (int i = 0; i < 6; i++) {
            int inner = b.lam(dom, b.app(b.app(c3, v1), b.app(body, v0)));
            body = b.app(b.app(c0, inner), b.app(body, v0));
        }
        b.finish();
        Overlay o = new Overlay(fx.store, fx.store.lists(), 1L << 30);
        Terms t = new Terms(fx.store.terms(), o);
        TermOps ops = new TermOps(t, fx.store.lists());
        long sum = 0;
        int arg = 0;
        for (long i = 0; i < n; i++) {
            if (o.usedBytes() >= (16L << 20) || arg == 0) {
                o.reset();
                arg = o.mkConst(fx.constCids[1], fx.levelLists[1]);
            }
            arg = o.mkApp(arg, arg);
            sum += ops.instantiate1(body, arg);
        }
        return sum;
    }
}

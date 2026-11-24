package rlvm.spike.machine;

import java.nio.file.Path;
import java.util.Locale;

/**
 * Plain (Truffle-free) launcher.
 *
 * <pre>
 *   parse  FILE.ndjson CACHE          parse the export once, write the binary cache
 *   heat   CACHE                      heat distribution of experiment 1
 *   exp1   CACHE VARIANT              none | null | a0 | a1 | elab | b | b2 | brec | bm
 *                                     (brec = b with all outputs recorded; bm = brec + one match-mode pass)
 *   verify CACHE                      a0, a1, b, b2 in one arena: identical ids?
 *   exp2   CACHE VARIANT              none | rec | wl | wl2
 *   profile CACHE                     dynamic instruction mix of variant (b)
 *   micro  CACHE WHAT N               loop | app-hit | app-hit-local | app-insert | clo2-hit, N times (calibration)
 * </pre>
 * Run-time properties: -Drlvm.hcap=WORDS (initial arena), -Drlvm.tbits=N (initial table = 2^N slots),
 * -Drlvm.passes=N, -Drlvm.intern=false (experiment 2). Build/class-init-time property: -Drlvm.mem=0..3.
 */
public final class Main {
    private Main() {
    }

    public static void main(String[] args) throws Exception {
        Throwable[] failure = new Throwable[1];
        Thread t = new Thread(null, () -> {
            try {
                if (!run(args)) {
                    System.err.println("usage: see Main.java");
                    System.exit(3);
                }
            } catch (Throwable e) {
                failure[0] = e;
            }
        }, "machine-main", 1L << 30);
        t.start();
        t.join();
        if (failure[0] != null) {
            failure[0].printStackTrace();
            System.exit(3);
        }
    }

    public static void setup(String cache) throws Exception {
        long t0 = System.nanoTime();
        Corpus.load(Path.of(cache));
        Corpus.derive();
        Workload.build();
        Machine.init();
        Vals.init(Integer.getInteger("rlvm.hcap", 1 << 22), Integer.getInteger("rlvm.tbits", 16));
        Generic.init();
        Elab.init();
        System.out.printf(Locale.ROOT, "SETUP %s exprs=%d constants=%d spines=%d setup_ms=%d%n", cache, Corpus.nExpr, Corpus.nConst, Workload.nSpines, (System.nanoTime() - t0) / 1_000_000);
    }

    public static Instantiator plainVariant(String v) {
        switch (v) {
            case "null":
                return new Instantiator.Null();
            case "a0":
                Generic.MEMO = false;
                return new Instantiator.GenericInst();
            case "a1":
                Generic.MEMO = true;
                return new Instantiator.GenericInst();
            case "elab":
                return new Instantiator.ElabOnly();
            case "b":
                return new Instantiator.StepperInst();
            case "b2":
                return new Instantiator.Stepper2Inst();
            default:
                return null;
        }
    }

    public static boolean run(String[] args) throws Exception {
        if (args.length < 2) {
            return false;
        }
        switch (args[0]) {
            case "parse": {
                long t0 = System.nanoTime();
                Corpus.parseNdjson(Path.of(args[1]));
                long t1 = System.nanoTime();
                Corpus.save(Path.of(args[2]));
                System.out.printf(Locale.ROOT, "PARSE %s exprs=%d constants=%d names=%d rule_rhs=%d parse_ms=%d save_ms=%d%n", args[1], Corpus.nExpr, Corpus.nConst, Corpus.nNames,
                                Corpus.extraRoots.length, (t1 - t0) / 1_000_000, (System.nanoTime() - t1) / 1_000_000);
                return true;
            }
            case "heat":
                setup(args[1]);
                Exp1.heatReport();
                return true;
            case "exp1": {
                setup(args[1]);
                String v = args[2];
                if (v.equals("none")) {
                    System.out.println("RESULT exp1 variant=none");
                    return true;
                }
                if (v.equals("brec") || v.equals("bm")) {
                    Exp1.runMatch(v, v.equals("bm"), Integer.getInteger("rlvm.passes", 1));
                    return true;
                }
                Instantiator inst = plainVariant(v);
                if (inst == null) {
                    return false;
                }
                Exp1.run(v, inst, Integer.getInteger("rlvm.passes", 1));
                return true;
            }
            case "verify": {
                setup(args[1]);
                Instantiator a0 = plainVariant("a0");
                String[] names = {"a0", "a1", "b", "b2"};
                // a1 flips Generic.MEMO when created, so create it lazily through a wrapper
                Instantiator a1 = new Instantiator() {
                    @Override
                    public void inst(int c, int[] S, int fp, int k) {
                        Generic.MEMO = true;
                        Generic.instantiate(c, S, fp, k);
                    }
                };
                boolean ok = Exp1.verify(names, new Instantiator[]{a0, a1, new Instantiator.StepperInst(), new Instantiator.Stepper2Inst()});
                System.out.println(ok ? "VERIFY OK" : "VERIFY FAILED");
                return true;
            }
            case "exp2":
                setup(args[1]);
                return Exp2.run(args[2]);
            case "profile":
                setup(args[1]);
                Micro.profile();
                return true;
            case "micro":
                setup(args[1]);
                return Micro.micro(args[2], Integer.parseInt(args[3]));
            default:
                return false;
        }
    }
}

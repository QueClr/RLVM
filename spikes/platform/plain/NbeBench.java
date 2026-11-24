import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.function.Function;

/**
 * Port of AndrasKovacs/normalization-bench (scala/normalization-bench/HOAS.scala) to plain Java, plus
 * the "NBE.closure.list" normaliser of Guest0x0/normalization-bench (environment machine over
 * de Bruijn terms) in three dispatch styles. Untyped lambda calculus, call-by-value.
 *
 * <pre>
 * java NbeBench.java &lt;variant&gt; &lt;workload&gt; &lt;iterations&gt; [stackMB]
 *   variant : hoas | tag | virt | pat
 *     hoas  host-language closures (java.util.function.Function), exactly as in Kovacs' Scala code
 *     tag   closure = (env, body); eval dispatches with switch on a byte tag field
 *     virt  closure = (env, body); eval is a virtual method on the term node
 *     pat   closure = (env, body); eval uses a Java 21 pattern-matching switch on a sealed hierarchy
 *   workload: nat5m-conv nat5m-norm nat10m-conv nat10m-norm tree2m-conv tree2m-norm
 *             tree4m-conv tree4m-norm tree8m-conv tree8m-norm
 * </pre>
 *
 * Recursion depth of quote/conv on natN is N frames, so the benchmark runs in a thread with a big
 * stack (default 4096 MB reserved; only touched pages are committed).
 */
public final class NbeBench {

    // ------------------------------------------------------------------ syntax (de Bruijn indices)
    sealed abstract static class Tm permits Var, App, Lam {
        final byte tag;

        Tm(int tag) {
            this.tag = (byte) tag;
        }

        abstract Val evalVirt(Env env);
    }

    static final class Var extends Tm {
        final int ix;

        Var(int ix) {
            super(0);
            this.ix = ix;
        }

        @Override
        Val evalVirt(Env env) {
            Env e = env;
            for (int i = ix; i > 0; i--) {
                e = e.next;
            }
            return e.v;
        }
    }

    static final class App extends Tm {
        final Tm f;
        final Tm a;

        App(Tm f, Tm a) {
            super(1);
            this.f = f;
            this.a = a;
        }

        @Override
        Val evalVirt(Env env) {
            return applyVirt(f.evalVirt(env), a.evalVirt(env));
        }
    }

    static final class Lam extends Tm {
        final Tm b;

        Lam(Tm b) {
            super(2);
            this.b = b;
        }

        @Override
        Val evalVirt(Env env) {
            return new VLam(env, b);
        }
    }

    // ------------------------------------------------------------------ values
    sealed abstract static class Val permits VVar, VApp, VLam, VHLam {
        final byte tag;

        Val(int tag) {
            this.tag = (byte) tag;
        }
    }

    static final class VVar extends Val {
        final int lvl;

        VVar(int lvl) {
            super(0);
            this.lvl = lvl;
        }
    }

    static final class VApp extends Val {
        final Val f;
        final Val a;

        VApp(Val f, Val a) {
            super(1);
            this.f = f;
            this.a = a;
        }
    }

    /** First-order closure: environment + body. */
    static final class VLam extends Val {
        final Env env;
        final Tm body;

        VLam(Env env, Tm body) {
            super(2);
            this.env = env;
            this.body = body;
        }
    }

    /** Higher-order (host) closure. */
    static final class VHLam extends Val {
        final Function<Val, Val> f;

        VHLam(Function<Val, Val> f) {
            super(3);
            this.f = f;
        }
    }

    static final class Env {
        final Val v;
        final Env next;

        Env(Val v, Env next) {
            this.v = v;
            this.next = next;
        }
    }

    // ------------------------------------------------------------------ evaluators
    static Val evalTag(Env env, Tm t) {
        switch (t.tag) {
            case 0: {
                Env e = env;
                for (int i = ((Var) t).ix; i > 0; i--) {
                    e = e.next;
                }
                return e.v;
            }
            case 1: {
                App a = (App) t;
                return applyTag(evalTag(env, a.f), evalTag(env, a.a));
            }
            default:
                return new VLam(env, ((Lam) t).b);
        }
    }

    static Val applyTag(Val f, Val a) {
        if (f.tag == 2) {
            VLam l = (VLam) f;
            return evalTag(new Env(a, l.env), l.body);
        }
        return new VApp(f, a);
    }

    static Val applyVirt(Val f, Val a) {
        if (f instanceof VLam l) {
            return l.body.evalVirt(new Env(a, l.env));
        }
        return new VApp(f, a);
    }

    static Val evalPat(Env env, Tm t) {
        return switch (t) {
            case Var v -> {
                Env e = env;
                for (int i = v.ix; i > 0; i--) {
                    e = e.next;
                }
                yield e.v;
            }
            case App a -> applyPat(evalPat(env, a.f), evalPat(env, a.a));
            case Lam l -> new VLam(env, l.b);
        };
    }

    static Val applyPat(Val f, Val a) {
        return switch (f) {
            case VLam l -> evalPat(new Env(a, l.env), l.body);
            case VVar v -> new VApp(f, a);
            case VApp v -> new VApp(f, a);
            case VHLam h -> h.f.apply(a);
        };
    }

    static Val applyHoas(Val f, Val a) {
        if (f instanceof VHLam h) {
            return h.f.apply(a);
        }
        return new VApp(f, a);
    }

    /** 0 = hoas, 1 = tag, 2 = virt, 3 = pat. */
    static int mode;

    static Val apply(Val f, Val a) {
        switch (mode) {
            case 0:
                return applyHoas(f, a);
            case 1:
                return applyTag(f, a);
            case 2:
                return applyVirt(f, a);
            default:
                return applyPat(f, a);
        }
    }

    static boolean isLam(Val v) {
        return v.tag >= 2;
    }

    // ------------------------------------------------------------------ quote / conversion (shared)
    static Tm quote(int l, Val v) {
        switch (v.tag) {
            case 0:
                return new Var(l - ((VVar) v).lvl - 1);
            case 1: {
                VApp a = (VApp) v;
                return new App(quote(l, a.f), quote(l, a.a));
            }
            default:
                return new Lam(quote(l + 1, apply(v, new VVar(l))));
        }
    }

    static boolean conv(int d, Val l, Val r) {
        switch (l.tag) {
            case 0:
                return r.tag == 0 && ((VVar) l).lvl == ((VVar) r).lvl;
            case 1: {
                if (r.tag != 1) {
                    return false;
                }
                VApp a = (VApp) l;
                VApp b = (VApp) r;
                return conv(d, a.f, b.f) && conv(d, a.a, b.a);
            }
            default: {
                if (!isLam(r)) {
                    return false;
                }
                VVar x = new VVar(d);
                return conv(d + 1, apply(l, x), apply(r, x));
            }
        }
    }

    // ------------------------------------------------------------------ object-level programs: HOAS
    static Val ap2(Val f, Val t, Val u) {
        return applyHoas(applyHoas(f, t), u);
    }

    static Val hl(Function<Val, Val> f) {
        return new VHLam(f);
    }

    static final class H {
        static Val ap(Val f, Val a) {
            return applyHoas(f, a);
        }

        final Val n2 = hl(s -> hl(z -> ap(s, ap(s, z))));
        final Val n5 = hl(s -> hl(z -> ap(s, ap(s, ap(s, ap(s, ap(s, z)))))));
        final Val mul = hl(a -> hl(b -> hl(s -> hl(z -> ap(ap(a, ap(b, s)), z)))));

        Val suc(Val n) {
            return hl(s -> hl(z -> ap(s, ap2(n, s, z))));
        }

        final Val n10 = ap2(mul, n2, n5);
        final Val n10b = ap2(mul, n5, n2);
        final Val n20 = ap2(mul, n2, n10);
        final Val n20b = ap2(mul, n2, n10b);
        final Val n21 = suc(n20);
        final Val n21b = suc(n20b);
        final Val n22 = suc(n21);
        final Val n22b = suc(n21b);
        final Val n100 = ap2(mul, n10, n10);
        final Val n100b = ap2(mul, n10b, n10b);
        final Val n10k = ap2(mul, n100, n100);
        final Val n10kb = ap2(mul, n100b, n100b);
        final Val n1M = ap2(mul, n10k, n100);
        final Val n1Mb = ap2(mul, n10kb, n100b);
        final Val n5M = ap2(mul, n1M, n5);
        final Val n5Mb = ap2(mul, n1Mb, n5);
        final Val n10M = ap2(mul, n1M, n10);
        final Val n10Mb = ap2(mul, n1Mb, n10b);
        final Val leaf = hl(l -> hl(n -> l));
        final Val node = hl(t1 -> hl(t2 -> hl(l -> hl(n -> ap2(n, ap2(t1, l, n), ap2(t2, l, n))))));
        final Val fullTree = hl(n -> ap2(n, hl(t -> ap2(node, t, t)), leaf));
    }

    // ------------------------------------------------------------------ object-level programs: terms
    static Tm v(int i) {
        return new Var(i);
    }

    static Tm lam(Tm b) {
        return new Lam(b);
    }

    static Tm app(Tm f, Tm... args) {
        Tm r = f;
        for (Tm a : args) {
            r = new App(r, a);
        }
        return r;
    }

    static final class T {
        final Tm n2 = lam(lam(app(v(1), app(v(1), v(0)))));
        final Tm n5 = lam(lam(app(v(1), app(v(1), app(v(1), app(v(1), app(v(1), v(0))))))));
        final Tm mul = lam(lam(lam(lam(app(v(3), app(v(2), v(1)), v(0))))));
        final Tm suc = lam(lam(lam(app(v(1), app(v(2), v(1), v(0))))));
        final Tm n10 = app(mul, n2, n5);
        final Tm n10b = app(mul, n5, n2);
        final Tm n20 = app(mul, n2, n10);
        final Tm n20b = app(mul, n2, n10b);
        final Tm n21 = app(suc, n20);
        final Tm n21b = app(suc, n20b);
        final Tm n22 = app(suc, n21);
        final Tm n22b = app(suc, n21b);
        final Tm n100 = app(mul, n10, n10);
        final Tm n100b = app(mul, n10b, n10b);
        final Tm n10k = app(mul, n100, n100);
        final Tm n10kb = app(mul, n100b, n100b);
        final Tm n1M = app(mul, n10k, n100);
        final Tm n1Mb = app(mul, n10kb, n100b);
        final Tm n5M = app(mul, n1M, n5);
        final Tm n5Mb = app(mul, n1Mb, n5);
        final Tm n10M = app(mul, n1M, n10);
        final Tm n10Mb = app(mul, n1Mb, n10b);
        final Tm leaf = lam(lam(v(1)));
        final Tm node = lam(lam(lam(lam(app(v(0), app(v(3), v(1), v(0)), app(v(2), v(1), v(0)))))));
        final Tm fullTree = lam(app(v(0), lam(app(node, v(0), v(0))), leaf));
    }

    static Val ev(Tm t) {
        switch (mode) {
            case 1:
                return evalTag(null, t);
            case 2:
                return t.evalVirt(null);
            default:
                return evalPat(null, t);
        }
    }

    // ------------------------------------------------------------------ driver
    interface Work {
        boolean run();
    }

    static Work workload(String name) {
        if (mode == 0) {
            H h = new H();
            return switch (name) {
                case "nat5m-conv" -> () -> conv(0, h.n5M, h.n5Mb);
                case "nat5m-norm" -> () -> quote(0, h.n5M) instanceof Lam;
                case "nat10m-conv" -> () -> conv(0, h.n10M, h.n10Mb);
                case "nat10m-norm" -> () -> quote(0, h.n10M) instanceof Lam;
                case "tree2m-conv" -> () -> conv(0, H.ap(h.fullTree, h.n20), H.ap(h.fullTree, h.n20b));
                case "tree2m-norm" -> () -> quote(0, H.ap(h.fullTree, h.n20)) instanceof Lam;
                case "tree4m-conv" -> () -> conv(0, H.ap(h.fullTree, h.n21), H.ap(h.fullTree, h.n21b));
                case "tree4m-norm" -> () -> quote(0, H.ap(h.fullTree, h.n21)) instanceof Lam;
                case "tree8m-conv" -> () -> conv(0, H.ap(h.fullTree, h.n22), H.ap(h.fullTree, h.n22b));
                case "tree8m-norm" -> () -> quote(0, H.ap(h.fullTree, h.n22)) instanceof Lam;
                default -> throw new IllegalArgumentException(name);
            };
        }
        T t = new T();
        return switch (name) {
            case "nat5m-conv" -> () -> conv(0, ev(t.n5M), ev(t.n5Mb));
            case "nat5m-norm" -> () -> quote(0, ev(t.n5M)) instanceof Lam;
            case "nat10m-conv" -> () -> conv(0, ev(t.n10M), ev(t.n10Mb));
            case "nat10m-norm" -> () -> quote(0, ev(t.n10M)) instanceof Lam;
            case "tree2m-conv" -> () -> conv(0, ev(app(t.fullTree, t.n20)), ev(app(t.fullTree, t.n20b)));
            case "tree2m-norm" -> () -> quote(0, ev(app(t.fullTree, t.n20))) instanceof Lam;
            case "tree4m-conv" -> () -> conv(0, ev(app(t.fullTree, t.n21)), ev(app(t.fullTree, t.n21b)));
            case "tree4m-norm" -> () -> quote(0, ev(app(t.fullTree, t.n21))) instanceof Lam;
            case "tree8m-conv" -> () -> conv(0, ev(app(t.fullTree, t.n22)), ev(app(t.fullTree, t.n22b)));
            case "tree8m-norm" -> () -> quote(0, ev(app(t.fullTree, t.n22))) instanceof Lam;
            default -> throw new IllegalArgumentException(name);
        };
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("usage: NbeBench <hoas|tag|virt|pat> <workload> <iterations> [stackMB]");
            System.exit(3);
        }
        mode = switch (args[0]) {
            case "hoas" -> 0;
            case "tag" -> 1;
            case "virt" -> 2;
            case "pat" -> 3;
            default -> throw new IllegalArgumentException(args[0]);
        };
        String name = args[1];
        int iterations = Integer.parseInt(args[2]);
        long stackMb = args.length > 3 ? Long.parseLong(args[3]) : 4096;
        Throwable[] failure = new Throwable[1];
        Thread th = new Thread(null, () -> {
            try {
                bench(args[0], name, iterations);
            } catch (Throwable e) {
                failure[0] = e;
            }
        }, "nbe-main", stackMb << 20);
        th.start();
        th.join();
        if (failure[0] != null) {
            System.out.println(args[0] + " " + name + " FAILED: " + failure[0]);
            System.exit(3);
        }
    }

    static void bench(String variant, String name, int iterations) {
        Work w = workload(name);
        long best = Long.MAX_VALUE;
        long total = 0;
        boolean ok = true;
        StringBuilder times = new StringBuilder();
        for (int i = 0; i < iterations; i++) {
            long t0 = System.nanoTime();
            ok &= w.run();
            long dt = System.nanoTime() - t0;
            total += dt;
            best = Math.min(best, dt);
            if (i < 10 || i == iterations - 1) {
                times.append(dt / 1_000_000).append(' ');
            } else if (i == 10) {
                times.append("... ");
            }
        }
        long gcCount = 0;
        long gcMs = 0;
        try {
            for (GarbageCollectorMXBean b : ManagementFactory.getGarbageCollectorMXBeans()) {
                gcCount += Math.max(0, b.getCollectionCount());
                gcMs += Math.max(0, b.getCollectionTime());
            }
        } catch (Throwable e) {
            gcCount = -1;
        }
        long threadCpu = -1;
        long processCpu = -1;
        try {
            threadCpu = ManagementFactory.getThreadMXBean().getCurrentThreadCpuTime() / 1_000_000;
            processCpu = ((com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean()).getProcessCpuTime() / 1_000_000;
        } catch (Throwable e) {
            // not available
        }
        System.out.printf("%s %s ok=%b | iter ms: %s| best %.1f | avg %.1f | wall %d ms | bench-thread cpu %d ms | process cpu %d ms | GCs %d (%d ms pause)%n",
                        variant, name, ok, times, best / 1e6, total / 1e6 / iterations, total / 1_000_000, threadCpu, processCpu, gcCount, gcMs);
    }
}

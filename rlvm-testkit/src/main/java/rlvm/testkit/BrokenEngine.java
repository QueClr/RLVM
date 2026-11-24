package rlvm.testkit;

import java.util.Locale;

import rlvm.api.Budget;
import rlvm.api.Engine;
import rlvm.api.EngineFactory;
import rlvm.api.KernelOps;
import rlvm.api.LevelOps;
import rlvm.api.Outcome;
import rlvm.api.Reason;
import rlvm.api.Session;
import rlvm.api.StatsSink;
import rlvm.api.WorkerContext;
import rlvm.store.TermOps;
import rlvm.store.Terms;

/**
 * A deliberately broken engine: a wrapper that passes every question to a correct engine and then
 * lies about some of the answers. It exists to test the test: a differential run against it must
 * report every kind of fault below, and a harness that stays silent on it is broken itself.
 *
 * <pre>
 * accept-rejected      a REJECT of the wrapped engine becomes ACCEPT                        SOUNDNESS
 * reject-every=N       an ACCEPT becomes REJECT for every constant id divisible by N        COMPLETENESS
 * warm-reject-after=N  an ACCEPT becomes REJECT once the session has made N checks since
 *                      its last reset (a stale cache: right in a fresh session)             COMPLETENESS, warm-only
 * defeq-true           KernelOps.isDefEq answers true                                       sampler: DEFEQ
 * defeq-false          KernelOps.isDefEq answers false                                      sampler: DEFEQ
 * whnf-identity        KernelOps.whnf returns its argument                                  sampler: WHNF_HEAD
 * infer-prop           KernelOps.infer answers Prop for every term                          sampler: INFER
 * isprop-false         KernelOps.isProp answers false                                       sampler: ISPROP
 * all                  accept-rejected, reject-every=97, defeq-true, whnf-identity, infer-prop, isprop-false
 * </pre>
 * Never registered as a service in the jar: {@code rlvm-testkit/spi-broken} holds the service file of
 * {@link BrokenProvider}, and only a differential self-test puts it on the class path.
 */
public final class BrokenEngine implements Engine {

    /** Which lies to tell. */
    public static final class Faults {
        public boolean acceptRejected;
        public int rejectEvery;
        public int warmRejectAfter;
        public boolean defEqTrue;
        public boolean defEqFalse;
        public boolean whnfIdentity;
        public boolean inferProp;
        public boolean isPropFalse;

        /** Parses a comma list of the names in the class comment; null or empty = {@code all}. */
        public static Faults parse(String spec) {
            Faults f = new Faults();
            if (spec == null || spec.trim().isEmpty()) {
                spec = "all";
            }
            for (String item : spec.split(",")) {
                String s = item.trim().toLowerCase(Locale.ROOT);
                if (s.isEmpty()) {
                    continue;
                }
                String value = null;
                int eq = s.indexOf('=');
                if (eq >= 0) {
                    value = s.substring(eq + 1);
                    s = s.substring(0, eq);
                }
                switch (s) {
                    case "all":
                        f.acceptRejected = true;
                        f.rejectEvery = 97;
                        f.defEqTrue = true;
                        f.whnfIdentity = true;
                        f.inferProp = true;
                        f.isPropFalse = true;
                        break;
                    case "accept-rejected":
                        f.acceptRejected = true;
                        break;
                    case "reject-every":
                        f.rejectEvery = number(s, value);
                        break;
                    case "warm-reject-after":
                        f.warmRejectAfter = number(s, value);
                        break;
                    case "defeq-true":
                        f.defEqTrue = true;
                        break;
                    case "defeq-false":
                        f.defEqFalse = true;
                        break;
                    case "whnf-identity":
                        f.whnfIdentity = true;
                        break;
                    case "infer-prop":
                        f.inferProp = true;
                        break;
                    case "isprop-false":
                        f.isPropFalse = true;
                        break;
                    default:
                        throw new IllegalArgumentException("unknown fault '" + item.trim() + "' (accept-rejected, reject-every=N,"
                                + " warm-reject-after=N, defeq-true, defeq-false, whnf-identity, infer-prop, isprop-false, all)");
                }
            }
            return f;
        }

        private static int number(String name, String value) {
            try {
                int n = Integer.parseInt(value);
                if (n > 0) {
                    return n;
                }
            } catch (NumberFormatException | NullPointerException e) {
                // below
            }
            throw new IllegalArgumentException("fault " + name + " needs a positive number (" + name + "=N)");
        }

        boolean anyOps() {
            return defEqTrue || defEqFalse || whnfIdentity || inferProp || isPropFalse;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            if (acceptRejected) {
                sb.append("accept-rejected,");
            }
            if (rejectEvery > 0) {
                sb.append("reject-every=").append(rejectEvery).append(',');
            }
            if (warmRejectAfter > 0) {
                sb.append("warm-reject-after=").append(warmRejectAfter).append(',');
            }
            if (defEqTrue) {
                sb.append("defeq-true,");
            }
            if (defEqFalse) {
                sb.append("defeq-false,");
            }
            if (whnfIdentity) {
                sb.append("whnf-identity,");
            }
            if (inferProp) {
                sb.append("infer-prop,");
            }
            if (isPropFalse) {
                sb.append("isprop-false,");
            }
            return sb.length() == 0 ? "none" : sb.substring(0, sb.length() - 1);
        }
    }

    private final Engine wrapped;
    private final Faults faults;

    public BrokenEngine(Engine wrapped, Faults faults) {
        this.wrapped = wrapped;
        this.faults = faults == null ? Faults.parse(null) : faults;
    }

    /** A factory that wraps the engines of {@code wrapped}. */
    public static EngineFactory of(EngineFactory wrapped, Faults faults) {
        return (store, config) -> new BrokenEngine(wrapped.create(store, config), faults);
    }

    @Override
    public String name() {
        return "broken";
    }

    @Override
    public Session newSession(WorkerContext ctx) {
        return new BrokenSession(wrapped.newSession(ctx), ctx);
    }

    @Override
    public void environmentAdvanced(int units) {
        wrapped.environmentAdvanced(units);
    }

    @Override
    public void dumpStats(StatsSink sink) {
        sink.put("broken.faults", faults.toString());
        wrapped.dumpStats(sink);
    }

    @Override
    public void close() {
        wrapped.close();
    }

    private final class BrokenSession implements Session {
        private final Session inner;
        private final WorkerContext ctx;
        private long checksSinceReset;
        /** The last outcome was invented here. */
        private boolean invented;
        private KernelOps ops;

        BrokenSession(Session inner, WorkerContext ctx) {
            this.inner = inner;
            this.ctx = ctx;
        }

        @Override
        public int check(int cid, Budget budget) {
            checksSinceReset++;
            invented = false;
            int o = inner.check(cid, budget);
            if (o == Outcome.REJECT && faults.acceptRejected) {
                return Outcome.ACCEPT;
            }
            if (o == Outcome.ACCEPT && ((faults.rejectEvery > 0 && cid % faults.rejectEvery == 0)
                    || (faults.warmRejectAfter > 0 && checksSinceReset >= faults.warmRejectAfter))) {
                invented = true;
                return Outcome.REJECT;
            }
            return o;
        }

        @Override
        public Reason lastReason() {
            return invented ? Reason.DECL_TYPE_MISMATCH : inner.lastReason();
        }

        @Override
        public String lastMessage() {
            return invented ? "the broken engine made this rejection up (" + faults + ")" : inner.lastMessage();
        }

        @Override
        public KernelOps ops() {
            KernelOps real = inner.ops();
            if (real == null || !faults.anyOps()) {
                return real;
            }
            if (ops == null) {
                ops = new BrokenOps(real, ctx);
            }
            return ops;
        }

        @Override
        public void reset() {
            checksSinceReset = 0;
            inner.reset();
        }

        @Override
        public long footprintBytes() {
            return inner.footprintBytes();
        }

        @Override
        public long steps() {
            return inner.steps();
        }

        @Override
        public void close() {
            inner.close();
        }
    }

    /** The wrapped engine's judgments with the chosen lies. */
    private final class BrokenOps implements KernelOps {
        private final KernelOps real;
        private final WorkerContext ctx;

        BrokenOps(KernelOps real, WorkerContext ctx) {
            this.real = real;
            this.ctx = ctx;
        }

        @Override
        public void begin(int rank, int levelParams) {
            real.begin(rank, levelParams);
        }

        @Override
        public void exposeUnitMembers(int kindMask) {
            real.exposeUnitMembers(kindMask);
        }

        @Override
        public int infer(int term) {
            if (faults.inferProp) {
                return ctx.overlay.mkSort(ctx.levels.zero());
            }
            return real.infer(term);
        }

        @Override
        public int check(int term) {
            return real.check(term);
        }

        @Override
        public int whnf(int term) {
            return faults.whnfIdentity ? term : real.whnf(term);
        }

        @Override
        public int whnfCore(int term) {
            return faults.whnfIdentity ? term : real.whnfCore(term);
        }

        @Override
        public boolean isDefEq(int a, int b) {
            if (faults.defEqTrue) {
                return true;
            }
            if (faults.defEqFalse) {
                return false;
            }
            return real.isDefEq(a, b);
        }

        @Override
        public int ensureSort(int term) {
            return real.ensureSort(term);
        }

        @Override
        public int ensurePi(int term) {
            return real.ensurePi(term);
        }

        @Override
        public boolean isProp(int type) {
            return !faults.isPropFalse && real.isProp(type);
        }

        @Override
        public int mkLocal(int type) {
            return real.mkLocal(type);
        }

        @Override
        public Terms terms() {
            return real.terms();
        }

        @Override
        public TermOps termOps() {
            return real.termOps();
        }

        @Override
        public LevelOps levels() {
            return real.levels();
        }

        @Override
        public boolean whnfIsSyntactic() {
            return false;
        }

        @Override
        public long steps() {
            return real.steps();
        }
    }
}

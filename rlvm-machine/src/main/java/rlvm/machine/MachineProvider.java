package rlvm.machine;

import java.util.Map;
import java.util.Set;
import rlvm.api.EngineFactory;
import rlvm.api.EngineProvider;

/**
 * Registers the machine as engine "machine" ({@code META-INF/services/rlvm.api.EngineProvider}; ISA 13.1,
 * 14.1). Owner: M6.
 *
 * <p>Settings ({@code rlvm -X KEY=VALUE}; each also exists as the system property {@code rlvm.KEY}, which
 * the {@code -X} form overrides):
 *
 * <pre>
 * machine.switches=warm,gated,..   staging switches (Debug.parseSwitches: names, -name, none, default, all; without the
 *                                  setting: Op.SW_DEFAULT; ignored by a strict engine; rlvm-machine/SWITCHES.md)
 * machine.session-kib=N            the machine's own cap on a warm session: the machine forgets everything before a
 *                                  declaration once its arenas hold more than N KiB (default 1024; 0 = no cap of its
 *                                  own; the driver's -X session-mib applies in any case: the smaller one resets first)
 * machine.unit-ticks=N             the machine's own tick bound per unit (default MachineEngine.DEFAULT_UNIT_TICKS;
 *                                  0 = none): beyond it the unit is declined (BUDGET_STEPS) and the reference
 *                                  engine decides it
 * machine.eager-probe=N            with the switch eager: the tick budget of one probe of the plain phase (the test
 *                                  X = Bool.true on an open X, which only eager reduction evaluates; default
 *                                  MachineEngine.DEFAULT_EAGER_PROBE; 0 = none): a probe cut at it leaves the point
 *                                  undecided and the unit is declined if it enters eager reduction
 *                                  (Machine.erProbeBudget)
 * machine.declines=FILE            append one line per non-ACCEPT outcome of the machine to FILE (outcome, error code,
 *                                  unit, rank, units accepted in the session before it, name, message)
 * levels=official|complete         (the launcher's key, shared with the reference engine) complete = switch
 *                                  levels-complete on, official = off; wins over machine.switches
 * machine.debug=memocheck,poison,scopeaudit,maskaudit,sharecheck,shortcheck,pubcheck,trace | all
 * machine.hostDepth=N              host depth bound D (default Op.HOST_DEPTH = 128; 0 = the worklist machine)
 * machine.memocheck.every=N        re-compute one memo hit in N (default 1)
 * machine.memocheck.budget=N       tick budget of one re-computation (default 65536)
 * </pre>
 *
 * <p>{@link #priority()} is 10: the machine is NOT the default engine of a distribution that contains it; it is
 * selected with {@code -X engine=machine}, and the reference engine is the default and the re-check engine (every
 * rejection and every decline of the machine is decided by it). It was 100 =
 * {@code Engines.DEFAULT_ENGINE_MIN_PRIORITY} (the default engine) from the integration of wave 6 (2026-11-24 19:41Z;
 * gates in {@code rlvm-machine/SWITCHES.md} and {@code work/status/machine-integration.md}) until the orchestrator
 * took it back the same day at 20:28Z: the machine accepts exports the official checker rejects (findings E1-F1 ..
 * E1-F7 and S3-F1 .. S3-F6 of 2026-11-24; E1-F1 .. F6 are fixed, {@code NOTES-M5.md}; the open classes are listed in
 * {@code docs/design/review/official-divergence.md}). The priority goes back to 100 only as a publication of its own.
 */
public final class MachineProvider implements EngineProvider {

    static final String K_SWITCHES = "machine.switches";
    static final String K_DEBUG = "machine.debug";
    static final String K_HOST_DEPTH = "machine.hostDepth";
    static final String K_MEMO_EVERY = "machine.memocheck.every";
    static final String K_MEMO_BUDGET = "machine.memocheck.budget";
    static final String K_SESSION_KIB = "machine.session-kib";
    static final String K_DECLINES = "machine.declines";
    static final String K_UNIT_TICKS = "machine.unit-ticks";
    static final String K_EAGER_PROBE = "machine.eager-probe";
    /**
     * The launcher's {@code -X levels=official|complete}, shared with the reference engine's provider (the keys
     * of all providers are a union): {@code complete} turns {@code Op.SW_LEVELS_COMPLETE} on, {@code official}
     * off, whatever {@code machine.switches} says, so that one setting gives both engines of a run (primary
     * and re-check, or the two sides of a differential run) the same relation. Request of L, 2026-11-24.
     */
    static final String K_LEVELS = "levels";
    private static final Set<String> KEYS = Set.of(K_SWITCHES, K_DEBUG, K_HOST_DEPTH, K_MEMO_EVERY, K_MEMO_BUDGET,
            K_SESSION_KIB, K_DECLINES, K_LEVELS, K_UNIT_TICKS, K_EAGER_PROBE);

    public MachineProvider() {}

    @Override
    public String name() {
        return "machine";
    }

    @Override
    public int priority() {
        // 10 = not the default engine. It was 100 (Engines.DEFAULT_ENGINE_MIN_PRIORITY, the default engine)
        // from 2026-11-24T19:41Z to 20:35Z; set back by the orchestrator because of the wrong accepts
        // E1-F1 .. E1-F4 (INFER types a bound variable by what its slot holds, not by the binder's
        // declared type; work/status/requests.log 20:10Z, 20:19Z, 20:25Z). Back to 100 when they are
        // closed and gated.
        return 10;
    }

    @Override
    public boolean isReference() {
        return false;
    }

    @Override
    public EngineFactory factory() {
        return MachineEngine.FACTORY;
    }

    @Override
    public Set<String> optionKeys() {
        return KEYS;
    }

    /** The engine configured by {@code -X machine.*} settings; a value that does not parse is an {@code IllegalArgumentException}. */
    @Override
    public EngineFactory factory(Map<String, String> options) {
        MachineEngine.Settings s = MachineEngine.Settings.fromProperties();
        String v = options.get(K_SWITCHES);
        if (v != null) {
            s.switches = Debug.parseSwitches(v);
        }
        v = options.get(K_LEVELS);
        if (v != null) {
            if (v.equals("complete")) {
                s.switches |= Op.SW_LEVELS_COMPLETE;
            } else if (v.equals("official")) {
                s.switches &= ~Op.SW_LEVELS_COMPLETE;
            } else {
                throw new IllegalArgumentException(K_LEVELS + "=" + v + ": official or complete");
            }
        }
        v = options.get(K_DEBUG);
        if (v != null) {
            s.debug = Debug.parseModes(v);
        }
        v = options.get(K_HOST_DEPTH);
        if (v != null) {
            s.hostDepth = (int) number(K_HOST_DEPTH, v, 0, Integer.MAX_VALUE);
        }
        v = options.get(K_MEMO_EVERY);
        if (v != null) {
            s.memoEvery = number(K_MEMO_EVERY, v, 1, Long.MAX_VALUE);
        }
        v = options.get(K_MEMO_BUDGET);
        if (v != null) {
            s.memoBudget = number(K_MEMO_BUDGET, v, 1, Long.MAX_VALUE);
        }
        v = options.get(K_SESSION_KIB);
        if (v != null) {
            s.sessionCapBytes = number(K_SESSION_KIB, v, 0, Long.MAX_VALUE >> 10) << 10;
        }
        v = options.get(K_UNIT_TICKS);
        if (v != null) {
            s.unitTicks = number(K_UNIT_TICKS, v, 0, Long.MAX_VALUE);
        }
        v = options.get(K_EAGER_PROBE);
        if (v != null) {
            s.eagerProbe = number(K_EAGER_PROBE, v, 0, Long.MAX_VALUE);
        }
        v = options.get(K_DECLINES);
        if (v != null) {
            s.declines = v.isEmpty() ? null : v;
        }
        return (store, config) -> new MachineEngine(store, config, s);
    }

    private static long number(String key, String value, long min, long max) {
        long n;
        try {
            n = Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + "=" + value + ": not a number");
        }
        if (n < min || n > max) {
            throw new IllegalArgumentException(key + "=" + value + ": out of range [" + min + ", " + max + "]");
        }
        return n;
    }
}

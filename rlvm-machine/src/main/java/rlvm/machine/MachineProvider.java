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
 *                                  declaration once its arenas hold more than N KiB (0 = no cap of its own; the
 *                                  driver's -X session-mib applies in any case)
 * machine.declines=FILE            append one line per non-ACCEPT outcome of the machine to FILE (outcome, error code,
 *                                  unit, rank, units accepted in the session before it, name, message)
 * machine.debug=memocheck,poison,scopeaudit,maskaudit,sharecheck,shortcheck,pubcheck,trace | all
 * machine.hostDepth=N              host depth bound D (default 64; 0 = the worklist machine)
 * machine.memocheck.every=N        re-compute one memo hit in N (default 1)
 * machine.memocheck.budget=N       tick budget of one re-computation (default 65536)
 * </pre>
 *
 * <p>{@link #priority()} is 10 on purpose: below {@code Engines.DEFAULT_ENGINE_MIN_PRIORITY} (100) the
 * machine is never the default engine; it runs with {@code -X engine=machine}. It is raised when the
 * gates of ISA section 11 are passed.
 */
public final class MachineProvider implements EngineProvider {

    static final String K_SWITCHES = "machine.switches";
    static final String K_DEBUG = "machine.debug";
    static final String K_HOST_DEPTH = "machine.hostDepth";
    static final String K_MEMO_EVERY = "machine.memocheck.every";
    static final String K_MEMO_BUDGET = "machine.memocheck.budget";
    static final String K_SESSION_KIB = "machine.session-kib";
    static final String K_DECLINES = "machine.declines";
    private static final Set<String> KEYS = Set.of(K_SWITCHES, K_DEBUG, K_HOST_DEPTH, K_MEMO_EVERY, K_MEMO_BUDGET,
            K_SESSION_KIB, K_DECLINES);

    public MachineProvider() {}

    @Override
    public String name() {
        return "machine";
    }

    @Override
    public int priority() {
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

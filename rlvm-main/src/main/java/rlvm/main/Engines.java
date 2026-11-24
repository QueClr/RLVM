package rlvm.main;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeSet;

import rlvm.api.EngineFactory;
import rlvm.api.EngineProvider;

/**
 * Engine lookup through {@code ServiceLoader.load(EngineProvider.class)} (a constant class, so it
 * works in a native image without registration).
 */
public final class Engines {
    private Engines() {}

    /** Every provider on the class path, in ServiceLoader order. */
    public static List<EngineProvider> providers() {
        List<EngineProvider> all = new ArrayList<>();
        for (EngineProvider p : ServiceLoader.load(EngineProvider.class)) {
            all.add(p);
        }
        return all;
    }

    /**
     * Smallest {@link EngineProvider#priority()} with which a non-reference engine becomes the DEFAULT
     * engine. An engine below it is on the class path as a candidate only: it runs when it is named
     * ({@code -X engine=NAME}) or compared ({@code -X engine=diff}), never by merely being in the
     * distribution. The machine registers with priority 10 until it has passed its gates and raises
     * the number itself then (request of the machine workflow, work/status/requests.log); the
     * differential harness has {@code Integer.MIN_VALUE}.
     */
    public static final int DEFAULT_ENGINE_MIN_PRIORITY = 100;

    /**
     * The provider called {@code name}; or, if {@code name} is null, the non-reference provider with
     * the highest priority of at least {@link #DEFAULT_ENGINE_MIN_PRIORITY}, and the reference
     * provider when there is no such engine (the reference kernel is a complete engine: the pipeline
     * runs on it alone until the machine has passed its gates).
     *
     * @throws UsageError if {@code name} is given and no provider has that name
     */
    public static EngineProvider primary(String name) {
        return primary(name, providers());
    }

    static EngineProvider primary(String name, List<EngineProvider> all) {
        if (name != null) {
            for (EngineProvider p : all) {
                if (name.equals(p.name())) {
                    return p;
                }
            }
            throw new UsageError("no engine '" + name + "' on the class path (available: " + names(all) + ")");
        }
        EngineProvider best = null;
        for (EngineProvider p : all) {
            // The differential harness is never the default engine, whatever is on the class path,
            // and neither is an engine that has not declared itself ready (priority below the bar).
            if (!p.isReference() && !DIFF.equals(p.name()) && p.priority() >= DEFAULT_ENGINE_MIN_PRIORITY
                    && (best == null || p.priority() > best.priority())) {
                best = p;
            }
        }
        return best != null ? best : reference(all);
    }

    /** Name of the test kit's differential provider ({@code rlvm.testkit.DiffProvider}). */
    static final String DIFF = "diff";

    /** Every {@code -X} key declared by a provider on the class path ({@link EngineProvider#optionKeys()}). */
    static Set<String> optionKeys(List<EngineProvider> all) {
        Set<String> keys = new TreeSet<>();
        for (EngineProvider p : all) {
            Set<String> own = p.optionKeys();
            if (own != null) {
                keys.addAll(own);
            }
        }
        return keys;
    }

    /**
     * The provider's factory configured by the run's engine settings.
     *
     * @throws UsageError if the provider refuses a value ({@code IllegalArgumentException})
     */
    static EngineFactory factory(EngineProvider provider, Map<String, String> options) {
        EngineFactory f;
        try {
            f = options == null || options.isEmpty() ? provider.factory()
                    : provider.factory(Collections.unmodifiableMap(new LinkedHashMap<>(options)));
        } catch (UsageError e) {
            throw e;
        } catch (IllegalArgumentException e) {
            throw new UsageError("engine '" + provider.name() + "': " + e.getMessage());
        }
        if (f == null) {
            throw new IllegalStateException("engine provider '" + provider.name() + "' (" + provider.getClass().getName()
                    + ") returned no factory");
        }
        return f;
    }

    /**
     * The run settings the provider prefers as the primary engine ({@link EngineProvider#runDefaults}),
     * checked: a provider may default settings of the wiring and of the driver only, never the engine
     * selection, a Truffle option or an engine's own key.
     *
     * @param options the engine settings of the run (what {@code factory(Map)} receives)
     * @param engineKeys every key declared by a provider on the class path
     * @return key -> value in sorted key order; empty when the provider prefers nothing
     * @throws IllegalStateException the provider names a key it may not default: a broken provider is
     *     an internal error (status 3), never a usage error of the caller and never a verdict
     */
    static Map<String, String> runDefaults(EngineProvider provider, Map<String, String> options, Set<String> engineKeys) {
        Map<String, String> d = provider.runDefaults(options == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(options)));
        if (d == null || d.isEmpty()) {
            return Map.of();
        }
        Map<String, String> sorted = new java.util.TreeMap<>();
        for (Map.Entry<String, String> e : d.entrySet()) {
            String key = e.getKey();
            if (key == null || key.isEmpty() || key.equals("engine") || key.startsWith("engine.")
                    || (engineKeys != null && engineKeys.contains(key))) {
                throw new IllegalStateException("engine provider '" + provider.name() + "' (" + provider.getClass().getName()
                        + ") names -X " + key + " as a run default: only settings of the wiring and of the driver "
                        + "can be defaulted, not the engine selection or an engine's own setting");
            }
            sorted.put(key, e.getValue());
        }
        return sorted;
    }

    /** The provider called {@code name}, or null. */
    public static EngineProvider find(String name) {
        return find(name, providers());
    }

    static EngineProvider find(String name, List<EngineProvider> all) {
        for (EngineProvider p : all) {
            if (name.equals(p.name())) {
                return p;
            }
        }
        return null;
    }

    /** The reference kernel's provider; its absence is a fatal configuration error. */
    public static EngineProvider reference() {
        return reference(providers());
    }

    static EngineProvider reference(List<EngineProvider> all) {
        EngineProvider ref = null;
        for (EngineProvider p : all) {
            if (p.isReference()) {
                if (ref != null) {
                    throw new IllegalStateException("two reference engines on the class path: " + ref.name() + " ("
                            + ref.getClass().getName() + ") and " + p.name() + " (" + p.getClass().getName() + ")");
                }
                ref = p;
            }
        }
        if (ref == null) {
            throw new IllegalStateException("no reference engine on the class path (rlvm-ref is missing; found: "
                    + names(all) + ")");
        }
        return ref;
    }

    /**
     * The engine under test of {@code -X engine=diff} when the test kit's provider is not on the class
     * path: the engine with the highest priority that is not the reference kernel. The driver then
     * runs the reference kernel beside it on every unit ({@code RunConfig.differential}).
     *
     * @throws UsageError if the reference kernel is the only engine (nothing to compare)
     */
    static EngineProvider underTest(List<EngineProvider> all) {
        // Any engine that is not the reference kernel, also one that is not yet eligible as the
        // default: comparing it with the reference kernel is how it gets there.
        EngineProvider p = null;
        for (EngineProvider q : all) {
            if (!q.isReference() && !DIFF.equals(q.name()) && (p == null || q.priority() > p.priority())) {
                p = q;
            }
        }
        if (p == null) {
            throw new UsageError("-X engine=diff needs an engine besides the reference kernel on the class path "
                    + "(available: " + names(all) + "); add one with RLVM_EXTRA_CP, or run scripts/dev.sh diff "
                    + "for the test kit's harness, which can compare the reference kernel with itself");
        }
        return p;
    }

    /** "reference*, machine" - the reference engine is starred; for {@code --version} and error messages. */
    public static String names(List<EngineProvider> all) {
        if (all.isEmpty()) {
            return "none";
        }
        StringBuilder sb = new StringBuilder();
        for (EngineProvider p : all) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(p.name());
            if (p.isReference()) {
                sb.append('*');
            }
        }
        return sb.toString();
    }
}

package rlvm.testkit;

import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;

import rlvm.api.EngineFactory;
import rlvm.api.EngineProvider;

/**
 * The differential engine as an {@link EngineProvider} named "diff", for launchers that find engines
 * through {@code ServiceLoader} and cannot depend on this module at compile time.
 *
 * <p>It is deliberately NOT registered in this module's jar: a provider that is neither the reference
 * nor the machine must never become the default engine by merely being on the class path. To use it,
 * put the directory {@code rlvm-testkit/spi} (which holds the service file) on the class path next to
 * the test kit, and select it by name ({@code -X engine=diff}).
 *
 * <p>Engine under test: the provider named by {@code rlvm.diff.engine} (system property, or the
 * environment variable {@code RLVM_DIFF_ENGINE}); without it the non-reference provider with the
 * highest priority (never the deliberately broken one of {@link BrokenProvider}); if there is none,
 * the reference engine itself (a self-check of the harness). Oracle: the reference provider. Options
 * come from {@link DiffEngine.Options#fromSystemProperties()}; the two sides share the caller's
 * context.
 */
public final class DiffProvider implements EngineProvider {

    public DiffProvider() {}

    @Override
    public String name() {
        return "diff";
    }

    /** Lowest possible: never preferred over a real engine. */
    @Override
    public int priority() {
        return Integer.MIN_VALUE;
    }

    @Override
    public boolean isReference() {
        return false;
    }

    /**
     * The harness's own settings as {@code -X} keys: {@code -X diff.engine=machine -X diff.sample=20
     * -X diff.report=FILE ...} (see {@link DiffEngine.Options#fromSettings}). The same settings can be
     * given as system properties ({@code rlvm.diff.*}) or environment variables ({@code RLVM_DIFF_*}).
     */
    @Override
    public Set<String> optionKeys() {
        return Set.of(DiffEngine.Options.KEYS);
    }

    @Override
    public EngineFactory factory() {
        return factory(Map.of());
    }

    /** Both engines are created with the same {@code -X} settings (each ignores the keys of the other). */
    @Override
    public EngineFactory factory(Map<String, String> options) {
        String wanted = DiffEngine.Options.setting(options, "rlvm.diff.engine");
        EngineProvider reference = null;
        EngineProvider underTest = null;
        for (EngineProvider p : ServiceLoader.load(EngineProvider.class)) {
            if (p instanceof DiffProvider) {
                continue;
            }
            if (p.isReference()) {
                reference = p;
            }
            if (wanted != null) {
                if (wanted.equals(p.name())) {
                    underTest = p;
                }
            } else if (!p.isReference() && !(p instanceof BrokenProvider)
                    && (underTest == null || p.priority() > underTest.priority())) {
                underTest = p;
            }
        }
        if (reference == null) {
            throw new IllegalStateException("differential mode needs the reference engine (rlvm-ref) on the class path");
        }
        if (underTest == null) {
            if (wanted != null) {
                throw new IllegalArgumentException("no engine '" + wanted + "' on the class path (diff.engine)");
            }
            underTest = reference;
        }
        DiffEngine.Options o = DiffEngine.Options.fromSettings(options);
        boolean plain = options == null || options.isEmpty();
        return DiffEngine.of(plain ? underTest.factory() : underTest.factory(options),
                plain ? reference.factory() : reference.factory(options), o);
    }
}

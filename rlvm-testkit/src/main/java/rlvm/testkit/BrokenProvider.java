package rlvm.testkit;

import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;

import rlvm.api.EngineFactory;
import rlvm.api.EngineProvider;

/**
 * {@link BrokenEngine} over the reference kernel as an {@link EngineProvider} named "broken": the
 * engine a differential run must catch. Like {@link DiffProvider} it is NOT registered in the jar;
 * the service file is in {@code rlvm-testkit/spi-broken}, which {@code testkit diff --engine broken}
 * adds to the class path. Faults: {@code -X broken=...}, the system property {@code rlvm.broken} or the
 * environment variable {@code RLVM_BROKEN}; default {@code all} (see {@link BrokenEngine}).
 */
public final class BrokenProvider implements EngineProvider {

    public BrokenProvider() {}

    @Override
    public String name() {
        return "broken";
    }

    /** Below every real engine, above only the differential wrapper. */
    @Override
    public int priority() {
        return Integer.MIN_VALUE + 1;
    }

    @Override
    public boolean isReference() {
        return false;
    }

    /** {@code -X broken=FAULTS}. */
    @Override
    public Set<String> optionKeys() {
        return Set.of("broken");
    }

    @Override
    public EngineFactory factory() {
        return factory(Map.of());
    }

    @Override
    public EngineFactory factory(Map<String, String> options) {
        EngineProvider reference = null;
        for (EngineProvider p : ServiceLoader.load(EngineProvider.class)) {
            if (p.isReference()) {
                reference = p;
            }
        }
        if (reference == null) {
            throw new IllegalStateException("the broken engine wraps the reference engine (rlvm-ref), which is not on the class path");
        }
        boolean plain = options == null || options.isEmpty();
        return BrokenEngine.of(plain ? reference.factory() : reference.factory(options),
                BrokenEngine.Faults.parse(DiffEngine.Options.setting(options, "rlvm.broken")));
    }
}

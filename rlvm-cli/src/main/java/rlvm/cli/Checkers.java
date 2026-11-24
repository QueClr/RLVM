package rlvm.cli;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.ServiceLoader;

/** Finds the {@link Checker} implementations on the class path. */
final class Checkers {
    private Checkers() {
    }

    /**
     * All implementations, best first: every provider registered under
     * {@code META-INF/services/rlvm.cli.Checker}, then the built-in placeholder. A provider that cannot be
     * instantiated makes {@link ServiceLoader} throw; that is not caught here, so a broken installation is
     * an internal error rather than a silent fall-back.
     */
    static List<Checker> all() {
        List<Checker> found = new ArrayList<>();
        // The class literal must stay a constant argument: native-image registers the providers of a
        // service only for ServiceLoader.load calls it can see through.
        for (Checker c : ServiceLoader.load(Checker.class)) {
            found.add(c);
        }
        found.add(new PlaceholderChecker());
        found.sort(Comparator.comparingInt(Checker::priority).reversed().thenComparing(Checker::name));
        return found;
    }

    /** The implementation called {@code name}, or the one with the highest priority when {@code name} is null. */
    static Checker select(String name) throws UsageException {
        List<Checker> all = all();
        if (name == null) {
            return all.get(0);
        }
        StringBuilder known = new StringBuilder();
        for (Checker c : all) {
            if (c.name().equals(name)) {
                return c;
            }
            known.append(known.length() == 0 ? "" : ", ").append(c.name());
        }
        throw new UsageException("unknown checker '" + name + "' (available: " + known + ")");
    }
}

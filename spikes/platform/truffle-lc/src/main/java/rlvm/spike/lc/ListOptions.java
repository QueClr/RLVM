package rlvm.spike.lc;

import org.graalvm.options.OptionDescriptor;
import org.graalvm.polyglot.Engine;

/** Prints every engine/compiler option of the running Truffle runtime (name, default, category, help). */
public final class ListOptions {
    private ListOptions() {
    }

    public static void main(String[] args) {
        try (Engine engine = Engine.newBuilder().allowExperimentalOptions(true).build()) {
            System.out.println("implementation: " + engine.getImplementationName() + " version " + engine.getVersion());
            for (OptionDescriptor d : engine.getOptions()) {
                System.out.printf("%s\t%s\t%s\t%s\t%s%n", d.getName(), d.getKey().getDefaultValue(), d.getCategory(), d.getStability(),
                                d.getHelp().replace('\n', ' '));
            }
        }
    }
}

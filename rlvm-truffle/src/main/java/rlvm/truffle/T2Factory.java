package rlvm.truffle;

import rlvm.machine.Machine;
import rlvm.machine.Tier;

/**
 * The service through which the machine finds the T2 tier ({@code META-INF/services/rlvm.machine.Tier$Factory};
 * hook {@link Tier}). The machine asks once per machine that runs with staging switch {@code t2}, at its
 * first declaration. Fail-safe (ISA 8.6): whatever goes wrong here, the answer is "no tier" and the
 * machine runs its blocks in the stepper.
 */
public final class T2Factory implements Tier.Factory {

    public T2Factory() {}

    @Override
    public Tier create(Machine m) {
        try {
            T2Engine e = T2Engine.of(m);
            return e == null ? null : new T2Tier(m, e);
        } catch (Throwable t) {
            T2Engine.warn("the tier cannot be created (" + t + "); the machine runs without it");
            return null;
        }
    }
}

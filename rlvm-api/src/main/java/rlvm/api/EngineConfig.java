package rlvm.api;

/** Options an engine receives at creation. Immutable. */
public final class EngineConfig {

    /** Which release of the official kernel the engine imitates where releases differ. */
    public enum KernelProfile {
        /** v4.35 / v4.36: the kernel the arena's ranked "official" checker runs. */
        LEAN_4_36,
        /** v4.37 def-eq changes (proof irrelevance first, eta for partially applied constructors, ...). */
        LEAN_4_37
    }

    public final KernelProfile profile;
    /**
     * Strict mode (RECONCILIATION.md section 2, facade): the generic tier only, the complete
     * procedure, only the exact shortcuts, a fresh session per unit, no shared facts and no shared
     * code (nothing is read from or written to {@code SharedFacts} or engine-wide tables), no cache
     * that survives a unit. It is fixed at engine creation, so the driver creates a second engine
     * instance with {@link #withStrict(boolean) withStrict(true)} for the cold re-check of a
     * rejection (ARCHITECTURE.md 8.2); the two instances share nothing. The reference kernel is
     * always strict in this sense and ignores the flag.
     */
    public final boolean strict;
    /** Recompute every memo hit and fail on disagreement (test builds only). */
    public final boolean memoCheck;
    /** Allow the Truffle tier (runtime compilation of hot code objects). */
    public final boolean truffleTier;
    /** Collect heat statistics for {@link Engine#dumpStats(StatsSink)}. */
    public final boolean stats;
    /** Number of worker sessions the engine should expect (sizing of shared tables). */
    public final int workers;

    public EngineConfig(KernelProfile profile, boolean strict, boolean memoCheck, boolean truffleTier, boolean stats,
            int workers) {
        this.profile = profile;
        this.strict = strict;
        this.memoCheck = memoCheck;
        this.truffleTier = truffleTier;
        this.stats = stats;
        this.workers = workers;
    }

    /** The plain configuration: given profile, not strict, no memo check, no Truffle tier, no statistics, one worker. */
    public static EngineConfig of(KernelProfile profile) {
        return new EngineConfig(profile, false, false, false, false, 1);
    }

    /** {@link #of(KernelProfile)} for the arena's kernel (v4.36). */
    public static EngineConfig defaults() {
        return of(KernelProfile.LEAN_4_36);
    }

    public EngineConfig withProfile(KernelProfile value) {
        return new EngineConfig(value, strict, memoCheck, truffleTier, stats, workers);
    }

    public EngineConfig withStrict(boolean value) {
        return new EngineConfig(profile, value, memoCheck, truffleTier, stats, workers);
    }

    public EngineConfig withMemoCheck(boolean value) {
        return new EngineConfig(profile, strict, value, truffleTier, stats, workers);
    }

    public EngineConfig withTruffleTier(boolean value) {
        return new EngineConfig(profile, strict, memoCheck, value, stats, workers);
    }

    public EngineConfig withStats(boolean value) {
        return new EngineConfig(profile, strict, memoCheck, truffleTier, value, workers);
    }

    public EngineConfig withWorkers(int value) {
        return new EngineConfig(profile, strict, memoCheck, truffleTier, stats, value);
    }

    @Override
    public String toString() {
        return "EngineConfig[" + profile + (strict ? ", strict" : "") + (memoCheck ? ", memoCheck" : "")
                + (truffleTier ? ", truffle" : "") + (stats ? ", stats" : "") + ", workers=" + workers + "]";
    }
}

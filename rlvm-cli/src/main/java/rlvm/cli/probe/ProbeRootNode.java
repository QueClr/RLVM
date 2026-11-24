package rlvm.cli.probe;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;

/**
 * The trivial guest function of the probe: a counted loop over its first argument. The result tells
 * the caller which tier executed it, because {@link CompilerDirectives#inCompiledCode()} and
 * {@link CompilerDirectives#hasNextTier()} are constants in each tier.
 */
final class ProbeRootNode extends RootNode {
    /** Result tags, stored in the two low bits. */
    static final int INTERPRETED = 0;
    static final int FIRST_TIER = 1;
    static final int LAST_TIER = 2;

    ProbeRootNode(TruffleLanguage<?> language) {
        super(language);
    }

    @Override
    public Object execute(VirtualFrame frame) {
        int n = (int) frame.getArguments()[0];
        long acc = 0;
        for (int i = 0; i < n; i++) {
            acc += (acc >>> 7) ^ i;
        }
        int tier;
        if (CompilerDirectives.inCompiledCode()) {
            tier = CompilerDirectives.hasNextTier() ? FIRST_TIER : LAST_TIER;
        } else {
            tier = INTERPRETED;
        }
        return (acc << 2) | tier;
    }

    @Override
    public String getName() {
        return "rlvm-probe";
    }
}

package rlvm.truffle;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.TruffleLanguage;

/**
 * The Truffle guest language "rlvm": its programs are the code blocks of the machine (ISA 3.3), i.e. the
 * judgment chains of constants (TELE, BODY, RULE, RULES and the blocks of binders), not Lean terms
 * (OWNER-NOTES 5: "truffle jit should be for the mechanized check progress, not the language itself").
 * A block becomes a {@link BlockRoot} when the governor promotes it; nothing is ever parsed, and a root
 * is never run by the Truffle interpreter: the base tier (the machine's stepper) runs a block until
 * compiled code is installed (ISA 8.1).
 */
@TruffleLanguage.Registration(id = RlvmLanguage.ID, name = "RLVM judgment chains", version = "0.1",
        contextPolicy = TruffleLanguage.ContextPolicy.SHARED)
public final class RlvmLanguage extends TruffleLanguage<RlvmLanguage.State> {
    public static final String ID = "rlvm";

    /** The language instance of the one engine this process creates ({@link T2Runtime}). */
    static volatile RlvmLanguage instance;

    /** The language has no context state: the machine is an argument of every root. */
    static final class State {
    }

    public RlvmLanguage() {
        instance = this;
    }

    @Override
    protected State createContext(Env env) {
        return new State();
    }

    @Override
    protected CallTarget parse(ParsingRequest request) {
        throw new UnsupportedOperationException("rlvm: code blocks are promoted by the machine, not parsed");
    }

    /** Roots are called by every worker thread of the checker. */
    @Override
    protected boolean isThreadAccessAllowed(Thread thread, boolean singleThreaded) {
        return true;
    }
}

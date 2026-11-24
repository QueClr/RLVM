package rlvm.spike.machine.truffle;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.TruffleLanguage;

/**
 * The guest "language" of the spike is the telescope-instantiation machine: its programs are code objects
 * elaborated from constants, not Lean terms. Roots are created by the promotion code, never parsed.
 */
@TruffleLanguage.Registration(id = TelLanguage.ID, name = "RLVM telescope machine", version = "0.1")
public final class TelLanguage extends TruffleLanguage<Object> {
    public static final String ID = "rlvmtel";

    static volatile TelLanguage instance;

    public TelLanguage() {
        instance = this;
    }

    @Override
    protected Object createContext(Env env) {
        return new Object();
    }

    @Override
    protected CallTarget parse(ParsingRequest request) {
        throw new UnsupportedOperationException("code objects are promoted, not parsed");
    }
}

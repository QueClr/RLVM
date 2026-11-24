package rlvm.cli.probe;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;

/**
 * A registered Truffle language with one program: evaluating any source runs {@link TruffleProbe#drive}
 * on a fresh {@link ProbeRootNode} that belongs to this language's engine, and returns the report line.
 * It exists to prove that the route the checker's upper tier is expected to take (language registration,
 * polyglot engine with options, call targets of that engine compiled at run time) works in the built
 * artifact, native image included.
 */
@TruffleLanguage.Registration(id = ProbeLanguage.ID, name = "RLVM Truffle probe", version = "1")
public final class ProbeLanguage extends TruffleLanguage<ProbeLanguage.ProbeContext> {
    public static final String ID = "rlvmprobe";

    /** The language has no state. */
    static final class ProbeContext {
    }

    @Override
    protected ProbeContext createContext(Env env) {
        return new ProbeContext();
    }

    @Override
    protected CallTarget parse(ParsingRequest request) {
        return new DriverRootNode(this).getCallTarget();
    }

    private static final class DriverRootNode extends RootNode {
        private final ProbeLanguage language;

        DriverRootNode(ProbeLanguage language) {
            super(language);
            this.language = language;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            return run();
        }

        @TruffleBoundary
        private String run() {
            return TruffleProbe.drive(new ProbeRootNode(language).getCallTarget()).toString();
        }
    }
}

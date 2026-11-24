package rlvm.spike.lc.ast;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.nodes.UnexpectedResultException;
import com.oracle.truffle.api.profiles.CountingConditionProfile;

import rlvm.spike.lc.Closure;
import rlvm.spike.lc.Global;
import rlvm.spike.lc.LcLanguage;

/** Hand-written (non-DSL) nodes of the AST interpreter. */
public final class Nodes {
    private Nodes() {
    }

    public static final class LitNode extends LcNode {
        private final long value;
        private final Object boxed;

        LitNode(long value) {
            this.value = value;
            this.boxed = value;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            return boxed;
        }

        @Override
        public long executeLong(VirtualFrame frame) {
            return value;
        }
    }

    public static final class ArgNode extends LcNode {
        private final int index;

        ArgNode(int index) {
            this.index = index + 1;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            return frame.getArguments()[index];
        }
    }

    public static final class CapNode extends LcNode {
        private final int index;

        CapNode(int index) {
            this.index = index;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            return ((Object[]) frame.getArguments()[0])[index];
        }
    }

    public static final class LocalNode extends LcNode {
        private final int slot;

        LocalNode(int slot) {
            this.slot = slot;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            return frame.getObject(slot);
        }
    }

    public static final class LetNode extends LcNode {
        private final int slot;
        @Child private LcNode init;
        @Child private LcNode body;

        LetNode(int slot, LcNode init, LcNode body) {
            this.slot = slot;
            this.init = init;
            this.body = body;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            frame.setObject(slot, init.execute(frame));
            return body.execute(frame);
        }
    }

    public static final class GlobalNode extends LcNode {
        private final Global global;

        GlobalNode(Global global) {
            this.global = global;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            return global.get();
        }
    }

    public static final class LamNode extends LcNode {
        private final RootCallTarget target;
        @Children private final LcNode[] captures;

        LamNode(RootCallTarget target, LcNode[] captures) {
            this.target = target;
            this.captures = captures;
        }

        @Override
        @ExplodeLoop
        public Object execute(VirtualFrame frame) {
            Object[] vals = new Object[captures.length];
            for (int i = 0; i < captures.length; i++) {
                vals[i] = captures[i].execute(frame);
            }
            return new Closure(target, vals);
        }
    }

    public static final class AppNode extends LcNode {
        @Child private LcNode fn;
        @Children private final LcNode[] args;
        @Child private DispatchNode dispatch = DispatchNodeGen.create();

        AppNode(LcNode fn, LcNode[] args) {
            this.fn = fn;
            this.args = args;
        }

        @Override
        @ExplodeLoop
        public Object execute(VirtualFrame frame) {
            Object f = fn.execute(frame);
            if (!(f instanceof Closure c)) {
                CompilerDirectives.transferToInterpreter();
                throw new IllegalStateException("not a function: " + f);
            }
            Object[] argv = new Object[args.length + 1];
            argv[0] = c.caps;
            for (int i = 0; i < args.length; i++) {
                argv[i + 1] = args[i].execute(frame);
            }
            return dispatch.executeDispatch(c, argv);
        }
    }

    public static final class IfNode extends LcNode {
        @Child private LcNode cond;
        @Child private LcNode then;
        @Child private LcNode otherwise;
        private final CountingConditionProfile profile = CountingConditionProfile.create();

        IfNode(LcNode cond, LcNode then, LcNode otherwise) {
            this.cond = cond;
            this.then = then;
            this.otherwise = otherwise;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            boolean c;
            try {
                c = cond.executeBoolean(frame);
            } catch (UnexpectedResultException e) {
                CompilerDirectives.transferToInterpreter();
                throw new IllegalStateException("condition is not a boolean: " + e.getResult());
            }
            return profile.profile(c) ? then.execute(frame) : otherwise.execute(frame);
        }
    }

    public static final class LcRootNode extends RootNode {
        @Child private LcNode body;
        private final String name;

        LcRootNode(LcLanguage language, FrameDescriptor fd, String name, LcNode body) {
            super(language, fd);
            this.name = name;
            this.body = body;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            return body.execute(frame);
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String toString() {
            return name;
        }

        /** -Dlc.veto=NAME: demonstrates the supported hook for refusing (deferring) compilation. */
        private static final String VETO = System.getProperty("lc.veto", "");

        @Override
        protected boolean prepareForCompilation(boolean rootCompilation, int compilationTier, boolean lastTier) {
            // Called on a compiler thread before each root compilation and before inlining this root.
            // Returning false means "not sufficiently profiled yet": the runtime does not compile now.
            return !name.equals(VETO);
        }
    }
}

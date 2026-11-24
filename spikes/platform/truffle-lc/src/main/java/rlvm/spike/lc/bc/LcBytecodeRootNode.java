package rlvm.spike.lc.bc;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.bytecode.BytecodeRootNode;
import com.oracle.truffle.api.bytecode.ConstantOperand;
import com.oracle.truffle.api.bytecode.GenerateBytecode;
import com.oracle.truffle.api.bytecode.Operation;
import com.oracle.truffle.api.bytecode.Variadic;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.nodes.RootNode;

import rlvm.spike.lc.Closure;
import rlvm.spike.lc.Global;
import rlvm.spike.lc.LcLanguage;

/**
 * Bytecode DSL interpreter for the spike language. The annotation processor generates
 * {@code LcBytecodeRootNodeGen} (builder + uncached interpreter + cached/quickening interpreter).
 *
 * Features switched on: uncached tier (no profiling nodes until a root is hot), quickening, and
 * boxing elimination for long/boolean (so {@code (+ (fib (- n 1)) ...)} does not box in the
 * interpreter either).
 */
@GenerateBytecode(languageClass = LcLanguage.class, //
                enableUncachedInterpreter = true, //
                enableQuickening = true, //
                boxingEliminationTypes = {long.class, boolean.class}, //
                enableSerialization = false, //
                enableTagInstrumentation = false)
public abstract class LcBytecodeRootNode extends RootNode implements BytecodeRootNode {
    String name = "<lambda>";

    protected LcBytecodeRootNode(LcLanguage language, FrameDescriptor.Builder frameDescriptorBuilder) {
        super(language, frameDescriptorBuilder.build());
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String toString() {
        return name;
    }

    @Operation
    public static final class Add {
        @Specialization
        public static long doLong(long a, long b) {
            return a + b;
        }
    }

    @Operation
    public static final class Sub {
        @Specialization
        public static long doLong(long a, long b) {
            return a - b;
        }
    }

    @Operation
    public static final class Mul {
        @Specialization
        public static long doLong(long a, long b) {
            return a * b;
        }
    }

    @Operation
    public static final class Less {
        @Specialization
        public static boolean doLong(long a, long b) {
            return a < b;
        }
    }

    @Operation
    public static final class Eq {
        @Specialization
        public static boolean doLong(long a, long b) {
            return a == b;
        }
    }

    /** Captured variable {@code index}: arguments[0] is the closure's captured-values array. */
    @Operation
    @ConstantOperand(type = int.class)
    public static final class LoadCap {
        @Specialization
        public static Object doLoad(VirtualFrame frame, int index) {
            return ((Object[]) frame.getArguments()[0])[index];
        }
    }

    @Operation
    @ConstantOperand(type = Global.class)
    public static final class LoadGlobal {
        @Specialization
        public static Object doLoad(Global global) {
            return global.get();
        }
    }

    @Operation
    @ConstantOperand(type = LcBytecodeRootNode.class)
    public static final class MakeClosure {
        @Specialization
        public static Closure doMake(LcBytecodeRootNode lambda, @Variadic Object[] caps) {
            // NB: do not name this operand "root": Truffle 25.0.4's generator emits a clashing local.
            return new Closure(lambda.getCallTarget(), caps);
        }
    }

    /** Call with a 3-entry inline cache on the call target; slot 0 of the argument array is reserved. */
    @Operation
    public static final class Call {
        @Specialization(guards = "c.target == cachedTarget", limit = "3")
        public static Object direct(Closure c, @Variadic(startOffset = 1) Object[] argv,
                        @SuppressWarnings("unused") @Cached("c.target") RootCallTarget cachedTarget,
                        @Cached("create(cachedTarget)") DirectCallNode call) {
            argv[0] = c.caps;
            return call.call(argv);
        }

        @Specialization(replaces = "direct")
        public static Object indirect(Closure c, @Variadic(startOffset = 1) Object[] argv, @Cached IndirectCallNode call) {
            argv[0] = c.caps;
            return call.call(c.target, argv);
        }
    }
}

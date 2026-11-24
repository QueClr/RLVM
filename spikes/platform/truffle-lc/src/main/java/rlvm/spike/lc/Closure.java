package rlvm.spike.lc;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;
import com.oracle.truffle.api.nodes.IndirectCallNode;

/**
 * Flat closure shared by the AST and the Bytecode DSL back-ends. Calling convention:
 * {@code arguments[0]} is the captured-values array, {@code arguments[1..]} are the actual arguments.
 */
@ExportLibrary(InteropLibrary.class)
public final class Closure implements TruffleObject {
    public final RootCallTarget target;
    public final Object[] caps;

    public Closure(RootCallTarget target, Object[] caps) {
        this.target = target;
        this.caps = caps;
    }

    @ExportMessage
    boolean isExecutable() {
        return true;
    }

    @ExportMessage
    Object execute(Object[] arguments, @Cached IndirectCallNode call) {
        Object[] argv = new Object[arguments.length + 1];
        argv[0] = caps;
        System.arraycopy(arguments, 0, argv, 1, arguments.length);
        return call.call(target, argv);
    }
}

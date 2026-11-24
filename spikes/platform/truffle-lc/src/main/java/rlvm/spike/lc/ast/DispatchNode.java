package rlvm.spike.lc.ast;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.nodes.Node;

import rlvm.spike.lc.Closure;

/** Polymorphic inline cache on the callee's call target (3 entries), then megamorphic indirect call. */
public abstract class DispatchNode extends Node {
    public abstract Object executeDispatch(Closure c, Object[] argv);

    @Specialization(guards = "c.target == cachedTarget", limit = "3")
    static Object direct(@SuppressWarnings("unused") Closure c, Object[] argv,
                    @SuppressWarnings("unused") @Cached("c.target") RootCallTarget cachedTarget,
                    @Cached("create(cachedTarget)") DirectCallNode call) {
        return call.call(argv);
    }

    @Specialization(replaces = "direct")
    static Object indirect(Closure c, Object[] argv, @Cached IndirectCallNode call) {
        return call.call(c.target, argv);
    }
}

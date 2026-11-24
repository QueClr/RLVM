package rlvm.spike.lc.ast;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.UnexpectedResultException;

/** Base class of the self-optimising AST interpreter. */
public abstract class LcNode extends Node {
    public abstract Object execute(VirtualFrame frame);

    public long executeLong(VirtualFrame frame) throws UnexpectedResultException {
        Object o = execute(frame);
        if (o instanceof Long l) {
            return l;
        }
        throw new UnexpectedResultException(o);
    }

    public boolean executeBoolean(VirtualFrame frame) throws UnexpectedResultException {
        Object o = execute(frame);
        if (o instanceof Boolean b) {
            return b;
        }
        throw new UnexpectedResultException(o);
    }
}

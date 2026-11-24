package rlvm.spike.lc;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.RootNode;

/** Runs the global initialisers in order, then the result expression. Back-end independent. */
public final class ProgramRootNode extends RootNode {
    private static final Object[] NO_CAPS = new Object[0];

    private final Global[] globals;
    @Children private final DirectCallNode[] inits;
    @Child private DirectCallNode main;

    public ProgramRootNode(LcLanguage language, Global[] globals, RootCallTarget[] inits, RootCallTarget main) {
        super(language);
        this.globals = globals;
        this.inits = new DirectCallNode[inits.length];
        for (int i = 0; i < inits.length; i++) {
            this.inits[i] = DirectCallNode.create(inits[i]);
        }
        this.main = DirectCallNode.create(main);
    }

    @Override
    @ExplodeLoop
    public Object execute(VirtualFrame frame) {
        for (int i = 0; i < inits.length; i++) {
            globals[i].set(inits[i].call(new Object[]{NO_CAPS}));
        }
        return main.call(new Object[]{NO_CAPS});
    }

    @Override
    public String getName() {
        return "<program>";
    }
}

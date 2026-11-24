package rlvm.spike.lc.ast;

import com.oracle.truffle.api.dsl.NodeChild;

@NodeChild(value = "left", type = LcNode.class)
@NodeChild(value = "right", type = LcNode.class)
public abstract class BinaryNode extends LcNode {
}

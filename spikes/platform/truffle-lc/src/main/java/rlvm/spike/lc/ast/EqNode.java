package rlvm.spike.lc.ast;

import com.oracle.truffle.api.dsl.Specialization;

public abstract class EqNode extends BinaryNode {
    @Specialization
    static boolean doLong(long a, long b) {
        return a == b;
    }
}

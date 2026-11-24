package rlvm.spike.lc.ast;

import com.oracle.truffle.api.dsl.Specialization;

public abstract class AddNode extends BinaryNode {
    @Specialization
    static long doLong(long a, long b) {
        return a + b;
    }
}

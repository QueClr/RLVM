package rlvm.spike.lc;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;

/**
 * A top-level definition cell. Written exactly once before it is read, so the value is
 * {@code @CompilationFinal}: partial evaluation folds a global read to a constant, which in turn makes
 * the callee of {@code (fib ...)} a compile-time constant (direct call, inlinable).
 */
public final class Global {
    public final String name;
    @CompilationFinal private Object value;

    public Global(String name) {
        this.name = name;
    }

    public Object get() {
        Object v = value;
        if (v == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new IllegalStateException("global " + name + " read before definition");
        }
        return v;
    }

    public void set(Object v) {
        CompilerDirectives.transferToInterpreterAndInvalidate();
        this.value = v;
    }

    @Override
    public String toString() {
        return "global:" + name;
    }
}

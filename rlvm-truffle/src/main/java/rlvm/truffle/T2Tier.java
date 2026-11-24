package rlvm.truffle;

import rlvm.machine.Machine;
import rlvm.machine.Op;
import rlvm.machine.T2Bridge;
import rlvm.machine.Tier;

/**
 * The tier of one machine (one worker session): the hand-over point of the hook {@link Tier}. The
 * stepper offers every activation here; a block that has installed compiled code and an entry at the
 * offered pc is run by it, everything else is answered {@code Op.MISS} at once (one array load), and the
 * stepper runs it.
 *
 * <p>The object is also the argument of every compiled root ({@link BlockRoot}): {@code entry}, {@code rb},
 * {@code d} are the entry registers of compiled code and {@code result} receives the result, so that
 * entering compiled code allocates nothing. Nested activations (a compiled block that applies a closure
 * whose block is compiled too) overwrite the entry registers only after the outer root has read them,
 * and each reads {@code result} right after its own call returned.
 */
final class T2Tier extends Tier {
    final Machine m;
    final T2Engine engine;
    /** The argument array of every call of this machine into compiled code. */
    final Object[] args = {this};

    int entry;
    int rb;
    int d;
    int result;

    /** Activations run by compiled code / offered for a promoted block but not taken (no entry at that pc, code not valid). */
    long calls;
    long notTaken;

    T2Tier(Machine m, T2Engine engine) {
        this.m = m;
        this.engine = engine;
        engine.register(this);
    }

    @Override
    public int run(Machine m, int blk, int pc, int rb, int d) {
        T2Engine.Installed[] tab = engine.table;
        if (blk >= tab.length) {
            return Op.MISS;
        }
        T2Engine.Installed c = tab[blk];
        if (c == null) {
            return Op.MISS;
        }
        int e = pc < c.entryOfPc.length ? c.entryOfPc[pc] : -1;
        if (e < 0 || !T2Runtime.enter(c, this, e, rb, d)) {
            notTaken++;
            return Op.MISS;
        }
        calls++;
        int r = result;
        if (r < Op.SIG) {
            T2Bridge.pop(m, rb); // RET (Tier.leave): a result pops the window; a signal leaves it
        }
        return r;
    }
}

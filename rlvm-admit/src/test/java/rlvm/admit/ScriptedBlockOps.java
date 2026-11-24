package rlvm.admit;

import java.util.function.BiConsumer;

import rlvm.api.KernelException;
import rlvm.api.Reason;
import rlvm.inductive.BlockInput;
import rlvm.inductive.GeneratedBlock;

/**
 * Block admission without rlvm-inductive: {@code generate} runs a script that fills the
 * {@code GeneratedBlock} (what the kernel would derive), {@code compare} answers a scripted bit set
 * or throws, {@code admitQuot} accepts unless told otherwise. Keeps the last input for inspection.
 */
final class ScriptedBlockOps implements BlockOps {
    BiConsumer<BlockInput, GeneratedBlock> script = (in, out) -> {
        throw new AssertionError("no generate script");
    };
    /** Bits of the derived recursors the export contains; -1 = all of them. */
    long present = -1L;
    RuntimeException generateFailure;
    RuntimeException compareFailure;
    Reason quotFailure;
    int generates;
    int compares;
    int quots;
    int lastQuotCid;
    BlockInput lastInput;

    @Override
    public void generate(BlockInput in, GeneratedBlock out) {
        generates++;
        lastInput = in;
        if (generateFailure != null) {
            throw generateFailure;
        }
        script.accept(in, out);
    }

    @Override
    public long compare(BlockInput in, GeneratedBlock generated) {
        compares++;
        if (compareFailure != null) {
            throw compareFailure;
        }
        return present == -1L ? (generated.recCount >= 64 ? -1L : (1L << generated.recCount) - 1) : present;
    }

    @Override
    public void admitQuot(int cid) {
        quots++;
        lastQuotCid = cid;
        if (quotFailure != null) {
            throw new KernelException(quotFailure);
        }
    }
}

package rlvm.admit;

import rlvm.api.Budget;
import rlvm.api.KernelOps;
import rlvm.api.Outcome;
import rlvm.api.Reason;
import rlvm.api.Session;

/** A scripted engine session: records the constants it was asked to check and answers a fixed outcome. */
final class FakeSession implements Session {
    int outcome = Outcome.ACCEPT;
    Reason reason;
    String message = "fake engine message";
    KernelOps ops;
    int checks;
    int lastCid;
    int[] checked = new int[16];

    @Override
    public int check(int cid, Budget budget) {
        if (checks == checked.length) {
            checked = java.util.Arrays.copyOf(checked, checks * 2);
        }
        checked[checks++] = cid;
        lastCid = cid;
        return outcome;
    }

    @Override
    public Reason lastReason() {
        return reason;
    }

    @Override
    public String lastMessage() {
        return message;
    }

    @Override
    public KernelOps ops() {
        return ops;
    }

    @Override
    public void reset() {}

    @Override
    public long footprintBytes() {
        return 0;
    }

    @Override
    public long steps() {
        return checks;
    }

    @Override
    public void close() {}
}

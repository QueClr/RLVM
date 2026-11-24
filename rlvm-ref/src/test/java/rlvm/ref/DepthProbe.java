package rlvm.ref;

import rlvm.api.Budget;
import rlvm.api.Outcome;
import rlvm.api.Session;

/**
 * Test tool: measures how deep the kernel's recursion can go on the deep stack before the
 * StackOverflowError backstop fires (run with a huge -Drlvm.ref.deepDepth). Not a unit test.
 *
 * <pre>java -Drlvm.ref.deepDepth=100000000 -cp ... rlvm.ref.DepthProbe 20000000</pre>
 */
public final class DepthProbe {
    private DepthProbe() {}

    public static void main(String[] args) {
        int n = Integer.parseInt(args[0]);
        Fixture fx = Fixture.standard(f -> {
            f.deepSucc("deepInfer", n);
            f.deepChain("deepWhnf", n, "Nat.add");
            f.deepEq("deepEqA", "deepEqB", n);
        });
        for (String name : new String[] {"deepInfer", "deepWhnf", "deepEqB"}) {
            Session s = fx.engine.newSession(fx.ctx);
            long t0 = System.nanoTime();
            int out = s.check(fx.cid(name), Budget.cold(64L << 30));
            long ms = (System.nanoTime() - t0) / 1_000_000;
            int depth = ((RefEngine.RefSession) s).kernel.maxDepthSeen();
            System.out.println(name + ": " + Outcome.name(out) + " " + s.lastReason() + " maxDepth " + depth + " -> "
                    + (out == Outcome.ACCEPT ? "no overflow" : (DeepStack.STACK_BYTES / Math.max(depth, 1)) + " bytes/level")
                    + " (" + ms + " ms)");
            s.close();
        }
    }
}

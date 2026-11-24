package rlvm.machine;

/**
 * Shared by the M5 tests: are the judgments of the other packages that INFER / CHECK / DECL call (EVAL, WHNF,
 * CONV) implemented in this tree yet? While one of them is still a stub the M5 tests skip themselves (an
 * assumption, not an assertion: a fast test must not depend on another package's stub).
 */
final class M5Support {
    private M5Support() {}

    private static Boolean ready;

    /** True once EVAL, INST, WHNF and CONV answer on a small example. */
    static synchronized boolean ready() {
        if (ready == null) {
            Fixture f = Fixture.prelude();
            try {
                int two = f.eval(f.term("two"));
                int lit = f.eval(f.term("(Nat.succ (Nat.succ Nat.zero))"));
                f.whnf(two);
                f.conv(two, lit);
                f.inst(f.eval(f.term("(-> Nat Nat)")), lit);
                f.conv(f.eval(f.term("(fun (x : Nat) (Nat.succ x))")), f.eval(f.term("Nat.succ")));
                ready = Boolean.TRUE;
            } catch (UnsupportedOperationException e) {
                ready = Boolean.FALSE;
            } finally {
                f.close();
            }
        }
        return ready;
    }
}

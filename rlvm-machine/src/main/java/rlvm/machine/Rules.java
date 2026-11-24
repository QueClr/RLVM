package rlvm.machine;

import static rlvm.machine.Op.SIG;

/**
 * Case-tree execution (ISA 3.6): the K1 shapes {@code CASESON} and {@code MATCHER}. Owner: M3.
 * Staging step 4; OFF in v0 ({@code Op.SW_CASETREES}).
 *
 * <p>The K1 case trees are elaborated and executed by M7 ({@code Elab}, {@code Code.rules}: the complete
 * RULES judgment of ISA 3.6, with over-application); this file is where the reduction family enters them:
 * {@link #unfold} is the delta step of the WHNF loop. With the switch off (v0, strict mode) nothing of it
 * runs. A tree that has no arm for the major ({@code NONE}) hands over to DELTA (ISA 6.3 B8).
 */
final class Rules {
    private Rules() {}

    static final int K_RULES_1 = Op.K_RULES | 1;

    /**
     * RULES(v) (ISA 3.6, 4.3): runs the case tree of the head (CASESON / MATCHER shape) on a spine that covers
     * lamArity. Staging step 4, behind {@code Op.SW_CASETREES} (off in v0). Sound as a finite composition of DELTA and
     * CORE steps. Entry frame: RULES[0](v).
     * @return the reduct, {@code Layout.NONE} if no arm matches, or a signal
     */
    static int rules(Machine m, int v, int d) {
        if ((m.sw & Op.SW_CASETREES) == 0 || m.strict) {
            return Layout.NONE; // v0: no case tree runs; the caller uses DELTA (ISA 6.3 B8)
        }
        return Code.rules(m, v, d);
    }

    /**
     * The delta step of the WHNF loop (ISA 4.3.4): a case tree of the head first when {@code SW_CASETREES} is on
     * (never in v0 or in strict mode), else, and when no arm matches, DELTA. Lazy delta keeps calling
     * {@code Reduce.delta} itself (ISA 3.6: single DELTA steps there).
     * @return the reduct, {@code Layout.NONE} if the head does not unfold, or a signal
     */
    static int unfold(Machine m, int v, int d) {
        if ((m.sw & Op.SW_CASETREES) == 0 || m.strict) {
            return Reduce.delta(m, v, d); // v0: the complete procedure (the same judgment, not a new call level)
        }
        int r = Code.rules(m, v, d); // call RULES(v) => 1 (the tree is entered one level down, by Code)
        return r >= SIG ? m.sig1(r, K_RULES_1, v) : k_rules1(m, v, r, d);
    }

    /** Frame RULES[1](v): r = RULES(v); NONE: the delta step (tail). */
    static int k_rules1(Machine m, int v, int r, int d) {
        return r != Layout.NONE ? r : Reduce.delta(m, v, d + 1);
    }
}

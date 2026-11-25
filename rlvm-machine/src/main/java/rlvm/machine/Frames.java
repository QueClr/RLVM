package rlvm.machine;

import static rlvm.machine.Op.ABORT;
import static rlvm.machine.Op.SIG;

import rlvm.api.MachineErrors;

/**
 * The control family CTL of the host convention ({@code stepper-convention.md} H6, H11; ISA 1.4): the
 * continuation of a speculative segment ({@code TRY}), the mode frame {@code ERF}, and the family's part
 * of the driver. The frame writers ({@code sigN}, {@code spillN}, {@code saveTry}) and the driver loop
 * are in {@link Machine}; binders ({@code OPEN} / {@code CLOSE}) are in {@link Scope}.
 *
 * <p>Shared file, owner M6.
 *
 * <h2>How a judgment runs a segment under a budget (H11; ISA 1.4 "TRY b; call J =&gt; k")</h2>
 * <pre>
 * static int tryConv(Machine m, int a, int b, long budget, int d) {        // result: T, F or U
 *     int ssp = m.ssp, xp = m.xp, ep = m.ep, rp = m.rp, er = m.ER;         // what the cut restores
 *     long saved = m.tryEnter(budget);                                      // fuel = min(budget, fuel)
 *     int r = Conv.conv(m, a, b, d + 1);
 *     return r == Op.SUSP ? m.saveTry(saved, budget, ssp, xp, ep, rp, er)
 *                         : Frames.k_try(m, saved, budget, ssp, xp, ep, rp, er, r);
 * }
 * </pre>
 * The caller of {@code tryConv} treats it like any judgment (H3). No TRY frame is written unless the
 * segment is suspended.
 */
final class Frames {
    private Frames() {}

    /**
     * Continuation of a speculative segment: {@code r} is the result of the segment, or ABORT (everything
     * the segment had on the host stack and on W is gone), or FATAL.
     *
     * <ul>
     *   <li>a result (T or F): the enclosing budget is charged what the segment used; the result is returned;
     *   <li>ABORT with {@code E_STEPS} (the segment's budget ran out): the machine stacks are restored (the
     *       {@code cut} of ISA 1.4), the enclosing budget is charged the whole segment budget, the result
     *       is U - unless the segment's budget was capped by the enclosing one ({@code saved < budget}),
     *       which is then empty as well: ABORT is passed on (ISA 1.4 {@code exhaust});
     *   <li>ABORT with a kernel error or {@code E_LIMIT}: the stacks are restored, the enclosing budget is
     *       charged what was used, the result is U (RECONCILIATION section 2: recoverable inside a TRY);
     *   <li>FATAL: passed on untouched (never turned into U).
     * </ul>
     * Nothing computed inside a segment that ended in U is in a memo: memos are written by the step that
     * completes a judgment, which a signal never reaches (I-MEMO).
     */
    static int k_try(Machine m, long saved, long budget, int ssp, int xp, int ep, int rp, int er, int r) {
        if (r >= SIG) {
            return trySignalSlow(m, saved, budget, ssp, xp, ep, rp, er, r);
        }
        long given = Math.min(budget, saved);
        m.tryUsed = given - m.fuel;
        m.fuel = saved - m.tryUsed;
        m.tickBase += saved - given;
        return (saved >>> 12) != (m.fuel >>> 12) ? tryPollSlow(m, r) : r;
    }

    /** The segment ended with a signal: FATAL is passed on; ABORT is the cut of ISA 1.4. */
    private static int trySignalSlow(Machine m, long saved, long budget, int ssp, int xp, int ep, int rp, int er, int r) {
        if (r != ABORT) {
            return r; // FATAL
        }
        long given = Math.min(budget, saved);
        m.closeBindersAbove(ssp);
        m.xp = xp;
        m.ep = ep;
        m.rp = rp;
        m.ER = er;
        if (m.err == MachineErrors.E_STEPS) {
            m.tryUsed = given;
            m.fuel = saved - given;
            m.tickBase += saved - given;
            if (saved < budget) {
                return ABORT; // capped by the enclosing budget, which is now empty too
            }
        } else {
            m.tryUsed = given - m.fuel;
            m.fuel = saved - m.tryUsed;
            m.tickBase += saved - given;
        }
        m.err = 0;
        return (saved >>> 12) != (m.fuel >>> 12) ? tryPollSlow(m, Layout.U) : Layout.U;
    }

    /** The enclosing budget crossed a poll point in one jump when the segment was charged to it. */
    private static int tryPollSlow(Machine m, int r) {
        int e = m.poll();
        return e != 0 ? m.fault(e) : r;
    }

    /** Continuation of a comparison that ran under another ER mode (ISA 1.3 "mode" frame): restores ER. */
    static int k_erf(Machine m, int savedER, int r) {
        if (r < SIG) {
            m.ER = savedER;
        }
        return r;
    }

    /** The family's part of the driver (H6). */
    static int resume(Machine m, int key, int[] w, int p, int r) {
        switch (key) {
            case Op.K_TRY:
                return k_try(m, (long) w[p + Op.TRY_FUEL_HI] << 32 | (w[p + Op.TRY_FUEL_LO] & 0xFFFFFFFFL),
                        (long) w[p + Op.TRY_BUDGET_HI] << 32 | (w[p + Op.TRY_BUDGET_LO] & 0xFFFFFFFFL), w[p + Op.TRY_SSP],
                        w[p + Op.TRY_XP], w[p + Op.TRY_EP], w[p + Op.TRY_RP], w[p + Op.TRY_ER], r);
            case Op.K_ERF:
                return k_erf(m, w[p], r);
            default:
                return m.fault(MachineErrors.E_INTERNAL);
        }
    }
}

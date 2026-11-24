package rlvm.machine;

import static rlvm.machine.Op.SIG;

import rlvm.api.MachineErrors;

/**
 * Binders: {@code OPEN} and {@code CLOSE} of ISA 2.8 and 4.1 (the rules {@code open} / {@code close} of
 * 1.3; official: a fresh free variable typed by the binder's domain, R02 section 6.3, made canonical as
 * in gruszka's {@code pick_var}, R05 section 2.9).
 *
 * <p>Owner: M1 (the "scope part of Frames" of ISA 13.2). Delivered working by the skeleton, because the
 * control primitives need it for the cut of a speculative segment; M1 may replace the body, not the
 * signatures.
 *
 * <p>A variable is {@code FVAR(type, k)}. The record {@code v0 = FVAR(type, 0)} carries the scope state
 * of its type: {@code M_OPENCNT} = number of open variables of this type; {@code M_TOPV} = the variable
 * the next {@code OPEN} takes, 0 if not known yet; every variable carries {@code M_NEXT} =
 * {@code FVAR(type, k + 1)} once that is known, and (skeleton addition) {@code M_FV0} = its {@code v0}
 * (0 on {@code v0} itself), so that {@code CLOSE} needs no probe. Binders close in LIFO order, so the
 * open variables of a type are exactly {@code k = 0 .. n-1} and a re-opened binder reproduces the ids
 * below it. The {@code NEXT} link of a variable is learned when it is closed (the variable closed just
 * before it is its successor).
 */
final class Scope {
    private Scope() {}

    /**
     * {@code OPEN(type)}: the first variable of this type that is not in scope, now in scope and on SC.
     *
     * @return the FVAR ref; or ABORT ({@code E_LIMIT}: 65535 open binders) or a signal of {@code mkFVar}
     */
    static int open(Machine m, int type) {
        int v0 = fvar0(m, type);
        if (v0 >= SIG) {
            return v0;
        }
        int[] vh = m.vh;
        int o0 = v0 << Layout.REC_SHIFT;
        int n = vh[o0 + Layout.M_OPENCNT];
        int x = vh[o0 + Layout.M_TOPV];
        if (x == 0) {
            if (n == 0) {
                x = v0;
            } else {
                x = Values.mkFVar(m, type, n);
                if (x >= SIG) {
                    return x;
                }
                vh = m.vh;
                vh[(x << Layout.REC_SHIFT) + Layout.M_FV0] = v0;
            }
        }
        if (!m.pushScope(x)) {
            return m.fail(MachineErrors.E_LIMIT);
        }
        int ox = x << Layout.REC_SHIFT;
        vh[ox] |= Layout.W0_INSCOPE;
        vh[o0 + Layout.M_OPENCNT] = n + 1;
        vh[o0 + Layout.M_TOPV] = vh[ox + Layout.M_NEXT];
        return x;
    }

    /** {@code CLOSE n}: closes the last {@code n} binders (they belong to the calling judgment, I-SCOPE). */
    static void close(Machine m, int n) {
        closeTo(m, m.ssp - n);
    }

    /** Closes every binder above scope-stack position {@code mark} (the cut of ISA 1.4). */
    static void closeTo(Machine m, int mark) {
        int[] sc = m.SC;
        int[] vh = m.vh;
        int p = m.ssp;
        while (p > mark) {
            int x = sc[--p];
            int ox = x << Layout.REC_SHIFT;
            int v0 = vh[ox + Layout.B] == 0 ? x : vh[ox + Layout.M_FV0];
            int o0 = v0 << Layout.REC_SHIFT;
            vh[ox] &= ~Layout.W0_INSCOPE;
            vh[o0 + Layout.M_OPENCNT]--;
            int next = vh[o0 + Layout.M_TOPV];
            if (next != 0 && next != x) {
                vh[ox + Layout.M_NEXT] = next; // the variable closed just before x is FVAR(type, k + 1)
            }
            vh[o0 + Layout.M_TOPV] = x;
        }
        m.ssp = p;
    }

    /** True if the FVAR record {@code x} is bound by an open binder (I-FV3; the scope audit of ISA 5.5). */
    static boolean inScope(Machine m, int x) {
        return (m.vh[x << Layout.REC_SHIFT] & Layout.W0_INSCOPE) != 0;
    }

    /** {@code FVAR(type, 0)} through the direct-mapped cache FV0 (ISA 1.5), else one probe. */
    private static int fvar0(Machine m, int type) {
        int[] c = m.fv0;
        int i = (Hash.cell(type, 0) & ((1 << Layout.FV0_LOG2) - 1)) << Layout.CELL_SHIFT;
        if (c[i + Layout.FV0_TYPE] == type && c[i + Layout.FV0_EPOCH] == m.epoch) {
            return c[i + Layout.FV0_VAR];
        }
        int v0 = Values.mkFVar(m, type, 0);
        if (v0 < SIG) {
            c[i + Layout.FV0_TYPE] = type;
            c[i + Layout.FV0_EPOCH] = m.epoch;
            c[i + Layout.FV0_VAR] = v0;
        }
        return v0;
    }
}

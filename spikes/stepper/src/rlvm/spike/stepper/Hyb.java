package rlvm.spike.stepper;

import static rlvm.spike.stepper.Terms.*;
import static rlvm.spike.stepper.Vals.*;

import java.util.Arrays;

/**
 * (H) Bounded host recursion with materialisation; the host stack is a cache of the top of the worklist.
 *
 * A judgment is a chain of step methods. Each step runs from one resume point to the next sub-judgment call:
 * <pre>
 *   static int J(x.., d) {                                   // entry step
 *       .. leaf cases: answer and return ..
 *       if (d &gt; D) return spill(F_J, x..);                  // too deep: become a frame
 *       int r = K(y.., d + 1);                               // call K(y) => J1
 *       return r &lt;= SIG ? sig(r, F_J1, live..) : J1(live.., r, d);
 *   }
 * </pre>
 * Below the depth bound D a sub-judgment is a nested host call and no frame exists. A judgment that finds itself
 * beyond the bound writes its own fresh frame {@code [x.., F_J]} ({@code spill}) and returns SUSP; SUSP travels
 * up the host stack and every step on the way writes the frame of its continuation ({@code save}: the live
 * variables and the resume point) and returns. The frames are written innermost first into a side buffer that
 * grows downwards, so its contents
 * are in worklist order; {@link #drive} copies them onto W in one move and then runs W like any worklist: pop the
 * top frame, call its step method with the result register and d = 0. W therefore holds exactly the frames a
 * pure worklist machine would hold for the suspended part of the computation, and the host stack never holds
 * more than D + 2 judgments. With D = 0 (variant H0) every sub-judgment that is not a leaf becomes a frame: the
 * same code then runs as the worklist machine of the specification.
 *
 * A call in tail position has no continuation: its result, signal or not, is returned as it is.
 *
 * Budgets and TRY (variants with TICKS): every judgment charges one tick on entry; when the fuel of the innermost
 * TRY is gone the judgment returns ABORT, every step above it returns ABORT without saving anything, and the
 * step that opened the TRY - reached on the host stack, or popped from W after {@link #drive} cut W down to its
 * frame - restores the fuel and continues with U. The tick count does not depend on D, so the outcome is exact.
 *
 * D and INERT are compile-time constants; scripts/build.sh generates Hyb4 .. Hyb256, HybInf (never
 * materialises) and HybI64 (inert nests through {@link Inert#eval}) from this file.
 */
public final class Hyb extends Engine {
    static final int D = 64;
    static final boolean INERT = false;
    /** Charge one tick per judgment started and support TRY segments (variants HT0, HT64, HTinf). */
    static final boolean TICKS = false;
    /**
     * The two signals a step can return instead of a result; both are below every value, term and truth value,
     * so one comparison ({@code r <= SIG}) separates them from results.
     * SUSP: the judgment was suspended, its frames are in the side buffer: save your continuation and return.
     * ABORT: the budget of the innermost TRY ran out: return at once, saving nothing.
     */
    static final int SUSP = Integer.MIN_VALUE, ABORT = Integer.MIN_VALUE + 1, SIG = ABORT;
    /** Result of a TRY whose budget ran out. */
    static final int U = 2;

    // resume points; the payload size is fixed per tag and repeated in the header (tag << 16 | n)
    static final int F_DONE = 0, F_EVAL = 1, F_EV_APP1 = 2, F_EV_APP2 = 3, F_EV_LET1 = 4, F_EV_PROJ1 = 5;
    static final int F_RB = 6, F_RB_APP1 = 7, F_RB_APP2 = 8, F_RB_PROJ1 = 9, F_RB_B1 = 10, F_RB_B2 = 11, F_RB_B3 = 12, F_RB_B4 = 13;
    static final int F_CONV = 14, F_CV_APP1 = 15, F_CV_B1 = 16, F_CV_B2 = 17, F_CV_B3 = 18, F_CV_B4 = 19, F_CV_B5 = 20;
    static final int F_TRY = 21;

    static int[] W = new int[1 << 12];
    static int wp;
    /** Side buffer of an unwind in progress: frames at S[sp .. S.length), innermost at the top. */
    static int[] S = new int[1 << 12];
    static int sp = S.length;
    /** Ticks left in the innermost budget (ISA 1.4). */
    static long fuel = Long.MAX_VALUE;

    // ------------------------------------------------------------------ materialisation

    private static int room(int n) {
        if (sp < n) {
            int[] s = new int[S.length * 2];
            int used = S.length - sp;
            System.arraycopy(S, sp, s, s.length - used, used);
            sp = s.length - used;
            S = s;
        }
        return sp -= n;
    }

    private static int frame1(int tag, int p0) {
        int p = room(2);
        S[p] = p0;
        S[p + 1] = tag << 16 | 1;
        return SUSP;
    }

    private static int frame2(int tag, int p0, int p1) {
        int p = room(3);
        S[p] = p0;
        S[p + 1] = p1;
        S[p + 2] = tag << 16 | 2;
        return SUSP;
    }

    private static int frame3(int tag, int p0, int p1, int p2) {
        int p = room(4);
        S[p] = p0;
        S[p + 1] = p1;
        S[p + 2] = p2;
        S[p + 3] = tag << 16 | 3;
        return SUSP;
    }

    private static int frame4(int tag, int p0, int p1, int p2, int p3) {
        int p = room(5);
        S[p] = p0;
        S[p + 1] = p1;
        S[p + 2] = p2;
        S[p + 3] = p3;
        S[p + 4] = tag << 16 | 4;
        return SUSP;
    }

    /** The frame of a fresh judgment that the depth bound did not let us call. */
    private static int spill2(int tag, int p0, int p1) {
        if (TICKS) {
            fuel++; // the judgment is entered again when its frame is run
        }
        if (STATS) {
            Stat.frames++;
            Stat.freshFrames++;
            Stat.frameWords += 3;
            Stat.spills++;
        }
        return frame2(tag, p0, p1);
    }

    private static int spill3(int tag, int p0, int p1, int p2) {
        if (TICKS) {
            fuel++; // the judgment is entered again when its frame is run
        }
        if (STATS) {
            Stat.frames++;
            Stat.freshFrames++;
            Stat.frameWords += 4;
            Stat.spills++;
        }
        return frame3(tag, p0, p1, p2);
    }

    /** The frame of a continuation whose sub-judgment was suspended. */
    private static int save1(int tag, int p0) {
        if (STATS) {
            Stat.frames++;
            Stat.frameWords += 2;
        }
        return frame1(tag, p0);
    }

    private static int save2(int tag, int p0, int p1) {
        if (STATS) {
            Stat.frames++;
            Stat.frameWords += 3;
        }
        return frame2(tag, p0, p1);
    }

    private static int save3(int tag, int p0, int p1, int p2) {
        if (STATS) {
            Stat.frames++;
            Stat.frameWords += 4;
        }
        return frame3(tag, p0, p1, p2);
    }

    private static int save4(int tag, int p0, int p1, int p2, int p3) {
        if (STATS) {
            Stat.frames++;
            Stat.frameWords += 5;
        }
        return frame4(tag, p0, p1, p2, p3);
    }

    /** A sub-judgment returned a signal: on SUSP save the continuation, on ABORT just pass it on. */
    private static int sig1(int r, int tag, int p0) {
        return r == SUSP ? save1(tag, p0) : r;
    }

    private static int sig2(int r, int tag, int p0, int p1) {
        return r == SUSP ? save2(tag, p0, p1) : r;
    }

    private static int sig3(int r, int tag, int p0, int p1, int p2) {
        return r == SUSP ? save3(tag, p0, p1, p2) : r;
    }

    private static int sig4(int r, int tag, int p0, int p1, int p2, int p3) {
        return r == SUSP ? save4(tag, p0, p1, p2, p3) : r;
    }

    /** Moves the frames of the finished unwind onto W (they are already in worklist order). */
    private static void flush() {
        int n = S.length - sp;
        if (wp + n > W.length) {
            W = Arrays.copyOf(W, Math.max(W.length * 2, wp + n));
        }
        System.arraycopy(S, sp, W, wp, n);
        wp += n;
        sp = S.length;
    }

    /**
     * Completes a top-level judgment: {@code a} is what its host call returned. If it was suspended, W is run
     * until the sentinel: pop the top frame, enter its step method with the result register and depth 0.
     */
    private static int drive(int a) {
        if (a != SUSP) {
            return a; // a result, or ABORT with no TRY below: the budget of the whole computation ran out
        }
        W[0] = F_DONE << 16;
        wp = 1;
        flush();
        for (;;) {
            int[] w = W;
            int h = w[--wp];
            switch (h >>> 16) {
                case F_DONE:
                    return a;
                case F_TRY:
                    wp -= 4;
                    a = tryDone((long) w[wp] << 32 | (w[wp + 1] & 0xFFFFFFFFL), w[wp + 2], w[wp + 3], a);
                    break;
                case F_EVAL:
                    wp -= 2;
                    a = eval(w[wp], w[wp + 1], 0);
                    break;
                case F_EV_APP1:
                    wp -= 2;
                    a = evalApp1(w[wp], w[wp + 1], a, 0);
                    break;
                case F_EV_APP2:
                    wp -= 1;
                    a = apply(w[wp], a, 0);
                    break;
                case F_EV_LET1:
                    wp -= 2;
                    a = evalLet1(w[wp], w[wp + 1], a, 0);
                    break;
                case F_EV_PROJ1:
                    wp -= 1;
                    a = mk2(w[wp], a);
                    break;
                case F_RB:
                    wp -= 2;
                    a = rb(w[wp], w[wp + 1], 0);
                    break;
                case F_RB_APP1:
                    wp -= 2;
                    a = rbApp1(w[wp], w[wp + 1], a, 0);
                    break;
                case F_RB_APP2:
                    wp -= 1;
                    a = mk(APP, w[wp], a, 0);
                    break;
                case F_RB_PROJ1:
                    wp -= 1;
                    a = mk(PROJ, 0, w[wp], a);
                    break;
                case F_RB_B1:
                    wp -= 2;
                    a = rbB1(w[wp], w[wp + 1], a, 0);
                    break;
                case F_RB_B2:
                    wp -= 2;
                    a = rbB2(w[wp], w[wp + 1], a, 0);
                    break;
                case F_RB_B3:
                    wp -= 3;
                    a = rbB3(w[wp], w[wp + 1], w[wp + 2], a, 0);
                    break;
                case F_RB_B4:
                    wp -= 2;
                    a = mk(w[wp], w[wp + 1], a, 0);
                    break;
                case F_CONV:
                    wp -= 3;
                    a = conv(w[wp], w[wp + 1], w[wp + 2], 0);
                    break;
                case F_CV_APP1:
                    wp -= 3;
                    a = convApp1(w[wp], w[wp + 1], w[wp + 2], a, 0);
                    break;
                case F_CV_B1:
                    wp -= 3;
                    a = convB1(w[wp], w[wp + 1], w[wp + 2], a, 0);
                    break;
                case F_CV_B2:
                    wp -= 4;
                    a = convB2(w[wp], w[wp + 1], w[wp + 2], w[wp + 3], a, 0);
                    break;
                case F_CV_B3:
                    wp -= 3;
                    a = convB3(w[wp], w[wp + 1], w[wp + 2], a, 0);
                    break;
                case F_CV_B4:
                    wp -= 2;
                    a = convB4(w[wp], w[wp + 1], a, 0);
                    break;
                case F_CV_B5:
                    wp -= 2;
                    a = convB5(w[wp], a, w[wp + 1], 0);
                    break;
                default:
                    throw new IllegalStateException();
            }
            if (a <= SIG) {
                if (a == SUSP) {
                    flush();
                } else { // ABORT: cut W to the innermost TRY frame (or to the sentinel), which receives the signal
                    for (int t = W[wp - 1]; (t >>> 16) != F_TRY && (t >>> 16) != F_DONE; t = W[wp - 1]) {
                        wp -= (t & 0xFFFF) + 1;
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ TRY (ISA 1.4)

    /** TRY budget; CONV(a, b): 1, 0, or U when the budget ran out. The enclosing budget bounds this one. */
    static int tryConv(int a, int b, int depth, int budget, int d) {
        long saved = fuel;
        long given = Math.min(budget, saved);
        fuel = given;
        int r = conv(a, b, depth, d + 1);
        return r == SUSP ? save4(F_TRY, (int) (saved >>> 32), (int) saved, (int) given, budget) : tryDone(saved, given, budget, r);
    }

    /** r = the result of the segment, or ABORT: everything the segment had on the host stack and on W is gone. */
    static int tryDone(long saved, long given, int budget, int r) {
        if (r == ABORT) {
            fuel = saved - given;
            return given < budget && fuel == 0 ? ABORT : U; // capped by the enclosing budget, which is now empty too
        }
        fuel = saved - (given - fuel);
        return r;
    }

    /** The benchmark's first three judgments without a budget, then the conversion under TRY {@code budget}. */
    @Override
    public int tryDecl(int root, int budget) {
        fuel = Long.MAX_VALUE / 2;
        int v = drive(eval(root, 0, 0));
        int t = drive(rb(v, 0, 0));
        int u = drive(eval(t, 0, 0));
        long before = fuel;
        int r = drive(tryConv(v, u, 0, budget, 0));
        tryTicks = before - fuel;
        if (wp != 0 || sp != S.length) {
            throw new IllegalStateException("worklist not empty after a TRY");
        }
        return r;
    }

    @Override
    public void decl(int root) {
        fuel = Long.MAX_VALUE / 2;
        int v = drive(eval(root, 0, 0));
        int t = drive(rb(v, 0, 0));
        int u = drive(eval(t, 0, 0));
        nf = t;
        w = u;
        ok = drive(conv(v, u, 0, 0)) != 0;
    }

    @Override
    public int stackWords() {
        return W.length;
    }

    // ------------------------------------------------------------------ EVAL

    static int eval(int e, int env, int d) {
        if (TICKS && --fuel < 0) {
            return ABORT;
        }
        switch (kind[e]) {
            case BVAR:
                return lookup(env, ea[e]);
            case SORT:
                return mk2(K_SORT, ea[e]);
            case CONST:
                return mk2(K_CONST, ea[e]);
            case LIT:
                return mk2(K_LIT, e);
            case APP: {
                if (INERT && (flag[e] & Terms.INERT) != 0) {
                    return Inert.eval(e, env);
                }
                if (d > D) {
                    return spill2(F_EVAL, e, env);
                }
                int f = eval(ea[e], env, d + 1);
                return f <= SIG ? sig2(f, F_EV_APP1, e, env) : evalApp1(e, env, f, d);
            }
            case LAM:
                return mk3(K_LAM, e, env);
            case PI:
                return mk3(K_PI, e, env);
            case LET: {
                if (d > D) {
                    return spill2(F_EVAL, e, env);
                }
                int v = eval(eb[e], env, d + 1);
                return v <= SIG ? sig2(v, F_EV_LET1, e, env) : evalLet1(e, env, v, d);
            }
            case PROJ: {
                if (INERT && (flag[e] & Terms.INERT) != 0) {
                    return Inert.eval(e, env);
                }
                if (d > D) {
                    return spill2(F_EVAL, e, env);
                }
                int ph = K_PROJ | eb[e] << 4;
                int s = eval(ec[e], env, d + 1);
                return s <= SIG ? sig1(s, F_EV_PROJ1, ph) : mk2(ph, s);
            }
            default:
                throw new IllegalStateException();
        }
    }

    /** f = the value of the function. */
    static int evalApp1(int e, int env, int f, int d) {
        int x = eval(eb[e], env, d + 1);
        return x <= SIG ? sig1(x, F_EV_APP2, f) : apply(f, x, d);
    }

    /** x = the value of the argument; the body of a closure is a tail position. */
    static int apply(int f, int x, int d) {
        if (f > 0 && H[f] == K_LAM) {
            int body = eb[H[f + 1]];
            int env = mk3(K_ENV, H[f + 2], x);
            return eval(body, env, d + 1);
        }
        return mk3(K_APP, f, x);
    }

    /** v = the let value; the body is a tail position. */
    static int evalLet1(int e, int env, int v, int d) {
        int env2 = mk3(K_ENV, env, v);
        return eval(ec[e], env2, d + 1);
    }

    // ------------------------------------------------------------------ READBACK

    static int rb(int v, int depth, int d) {
        if (TICKS && --fuel < 0) {
            return ABORT;
        }
        if (v < 0) {
            return mk(BVAR, depth + v, 0, 0);
        }
        int hdr = H[v];
        switch (hdr & 15) {
            case K_APP: {
                if (d > D) {
                    return spill2(F_RB, v, depth);
                }
                int tf = rb(H[v + 1], depth, d + 1);
                return tf <= SIG ? sig2(tf, F_RB_APP1, v, depth) : rbApp1(v, depth, tf, d);
            }
            case K_PROJ: {
                if (d > D) {
                    return spill2(F_RB, v, depth);
                }
                int ts = rb(H[v + 1], depth, d + 1);
                return ts <= SIG ? sig1(ts, F_RB_PROJ1, hdr >>> 4) : mk(PROJ, 0, hdr >>> 4, ts);
            }
            case K_LAM, K_PI: {
                if (d > D) {
                    return spill2(F_RB, v, depth);
                }
                int e = H[v + 1], env = H[v + 2];
                int dv = eval(ea[e], env, d + 1);
                return dv <= SIG ? sig2(dv, F_RB_B1, v, depth) : rbB1(v, depth, dv, d);
            }
            case K_SORT:
                return mk(SORT, H[v + 1], 0, 0);
            case K_CONST:
                return mk(CONST, H[v + 1], 0, 0);
            case K_LIT:
                return H[v + 1];
            default:
                throw new IllegalStateException();
        }
    }

    /** tf = the term of the function. */
    static int rbApp1(int v, int depth, int tf, int d) {
        int ta = rb(H[v + 2], depth, d + 1);
        return ta <= SIG ? sig1(ta, F_RB_APP2, tf) : mk(APP, tf, ta, 0);
    }

    /** dv = the value of the domain. */
    static int rbB1(int v, int depth, int dv, int d) {
        int td = rb(dv, depth, d + 1);
        return td <= SIG ? sig2(td, F_RB_B2, v, depth) : rbB2(v, depth, td, d);
    }

    /** td = the term of the domain. */
    static int rbB2(int v, int depth, int td, int d) {
        int e = H[v + 1];
        int k = (H[v] & 15) == K_LAM ? LAM : PI;
        int env2 = mk3(K_ENV, H[v + 2], -(depth + 1));
        int bv = eval(eb[e], env2, d + 1);
        return bv <= SIG ? sig3(bv, F_RB_B3, k, depth, td) : rbB3(k, depth, td, bv, d);
    }

    /** bv = the value of the body. */
    static int rbB3(int k, int depth, int td, int bv, int d) {
        int tb = rb(bv, depth + 1, d + 1);
        return tb <= SIG ? sig2(tb, F_RB_B4, k, td) : mk(k, td, tb, 0);
    }

    // ------------------------------------------------------------------ CONV (1 = convertible, 0 = not)

    static int conv(int a, int b, int depth, int d) {
        if (TICKS && --fuel < 0) {
            return ABORT;
        }
        if (a == b) {
            return 1;
        }
        if (a < 0 || b < 0) {
            return 0;
        }
        int hdr = H[a];
        if (hdr != H[b]) {
            return 0;
        }
        int k = hdr & 15;
        if (k != K_APP && k != K_PROJ && k != K_LAM && k != K_PI) {
            return 0; // SORT, CONST, LIT: hash-consed, so different ids are different values
        }
        if (d > D) {
            return spill3(F_CONV, a, b, depth);
        }
        switch (k) {
            case K_APP: {
                int r = conv(H[a + 1], H[b + 1], depth, d + 1);
                return r <= SIG ? sig3(r, F_CV_APP1, a, b, depth) : convApp1(a, b, depth, r, d);
            }
            case K_PROJ:
                return conv(H[a + 1], H[b + 1], depth, d + 1);
            default: { // K_LAM, K_PI
                int ta = H[a + 1];
                int da = eval(ea[ta], H[a + 2], d + 1);
                return da <= SIG ? sig3(da, F_CV_B1, a, b, depth) : convB1(a, b, depth, da, d);
            }
        }
    }

    /** r = functions convertible? The arguments are a tail position. */
    static int convApp1(int a, int b, int depth, int r, int d) {
        if (r == 0) {
            return 0;
        }
        return conv(H[a + 2], H[b + 2], depth, d + 1);
    }

    /** da = domain of a. */
    static int convB1(int a, int b, int depth, int da, int d) {
        int tb = H[b + 1];
        int db = eval(ea[tb], H[b + 2], d + 1);
        return db <= SIG ? sig4(db, F_CV_B2, a, b, depth, da) : convB2(a, b, depth, da, db, d);
    }

    /** db = domain of b. */
    static int convB2(int a, int b, int depth, int da, int db, int d) {
        int r = conv(da, db, depth, d + 1);
        return r <= SIG ? sig3(r, F_CV_B3, a, b, depth) : convB3(a, b, depth, r, d);
    }

    /** r = domains convertible? */
    static int convB3(int a, int b, int depth, int r, int d) {
        if (r == 0) {
            return 0;
        }
        int ta = H[a + 1];
        int env2 = mk3(K_ENV, H[a + 2], -(depth + 1));
        int va = eval(eb[ta], env2, d + 1);
        return va <= SIG ? sig2(va, F_CV_B4, b, depth) : convB4(b, depth, va, d);
    }

    /** va = body of a under the fresh variable. */
    static int convB4(int b, int depth, int va, int d) {
        int tb = H[b + 1];
        int env2 = mk3(K_ENV, H[b + 2], -(depth + 1));
        int vb = eval(eb[tb], env2, d + 1);
        return vb <= SIG ? sig2(vb, F_CV_B5, va, depth) : convB5(va, vb, depth, d);
    }

    /** vb = body of b; the comparison of the bodies is a tail position. */
    static int convB5(int va, int vb, int depth, int d) {
        return conv(va, vb, depth + 1, d + 1);
    }
}

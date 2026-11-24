package rlvm.spike.stepper;

import static rlvm.spike.stepper.Fast.MISS;
import static rlvm.spike.stepper.Terms.*;
import static rlvm.spike.stepper.Vals.*;

import java.util.Arrays;

/**
 * (W1) Fused loops per judgment family.
 *
 * <ul>
 * <li>One loop per family (EVAL; READBACK; CONV). Inside a loop {@code W}, {@code wp}, {@code xp} and the
 * result register {@code A} are locals; they are written back to the machine only when the loop returns.</li>
 * <li>The operands of the running judgment are locals too. A frame is written only for a judgment that is
 * suspended: it holds the live variables of the continuation and a header {@code (op << 4 | st) << 16 | n}.</li>
 * <li>Returning a result is one dense dispatch on {@code op << 4 | st} of the top frame. A sub-judgment of the
 * same family is a {@code continue} (operands in locals, no frame for the callee); a sub-judgment of another
 * family pushes a fresh frame for it and returns to {@link #run}, which enters that family's loop.</li>
 * <li>Fast paths answer atoms and identity inline. With INERT (W1; W1n is generated without it) an inert
 * application nest is evaluated by {@link Inert#eval}, one loop over a scratch buffer.</li>
 * </ul>
 */
public final class W1 extends Engine {
    static final boolean INERT = true;

    // frame codes = op << 4 | st; the op (bits 4..) selects the family loop
    static final int C_DONE = 0x00;
    static final int C_EVAL = 0x10, C_EV_HEAD = 0x11, C_EV_ARG = 0x12, C_EV_LET = 0x13, C_EV_PROJ = 0x14;
    static final int C_RB = 0x20, C_RB_HEAD = 0x21, C_RB_ARG = 0x22, C_RB_PROJ = 0x23, C_RB_B1 = 0x24, C_RB_B2 = 0x25, C_RB_B3 = 0x26, C_RB_B4 = 0x27;
    static final int C_CONV = 0x30, C_CV_SPINE = 0x31, C_CV_B1 = 0x32, C_CV_B2 = 0x33, C_CV_B3 = 0x34, C_CV_B4 = 0x35, C_CV_B5 = 0x36;

    int[] W = new int[1 << 12];
    int wp;
    int A;
    int[] X = new int[1 << 12];
    int xp;

    private int[] growW() {
        return W = Arrays.copyOf(W, W.length * 2);
    }

    private int[] growX() {
        return X = Arrays.copyOf(X, X.length * 2);
    }

    private static int argFast(int t, int env) {
        return INERT ? Fast.evalInert(t, env) : Fast.eval(t, env);
    }

    private static void count(int words, boolean fresh) {
        Stat.frames++;
        Stat.frameWords += words;
        if (fresh) {
            Stat.freshFrames++;
        }
    }

    // ------------------------------------------------------------------ the stepper

    /** Runs until the sentinel is on top; the result is in A. */
    private void run() {
        for (;;) {
            switch (W[wp - 1] >>> 20) {
                case 1:
                    evalLoop();
                    break;
                case 2:
                    rbLoop();
                    break;
                case 3:
                    convLoop();
                    break;
                default:
                    wp--;
                    return;
            }
        }
    }

    private int call2(int code, int p0, int p1) {
        W[0] = C_DONE << 16;
        W[1] = p0;
        W[2] = p1;
        W[3] = code << 16 | 2;
        wp = 4;
        if (STATS) {
            count(3, true);
        }
        run();
        return A;
    }

    @Override
    public void decl(int root) {
        int v = argFast(root, 0);
        if (v == MISS) {
            v = call2(C_EVAL, root, 0);
        }
        int t = Fast.rb(v, 0);
        if (t == MISS) {
            t = call2(C_RB, v, 0);
        }
        int u = argFast(t, 0);
        if (u == MISS) {
            u = call2(C_EVAL, t, 0);
        }
        int r = Fast.conv(v, u);
        if (r == MISS) {
            W[0] = C_DONE << 16;
            W[1] = v;
            W[2] = u;
            W[3] = 0;
            W[4] = C_CONV << 16 | 3;
            wp = 5;
            if (STATS) {
                count(4, true);
            }
            run();
            r = A;
        }
        nf = t;
        w = u;
        ok = r != 0;
    }

    @Override
    public int stackWords() {
        return W.length;
    }

    // ------------------------------------------------------------------ EVAL family

    private void evalLoop() {
        int[] w = W, x = X;
        int wp = this.wp, xp = this.xp, a = this.A;
        int e = 0, env = 0, f = 0, xb = 0, i = 0, xv = MISS;
        top: for (;;) {
            boolean spine;
            switch (w[wp - 1] >>> 16) {
                case C_EVAL: // fresh judgment pushed by another family
                    wp -= 3;
                    e = w[wp];
                    env = w[wp + 1];
                    spine = false;
                    break;
                case C_EV_HEAD: // a = the function value
                    wp -= 4;
                    env = w[wp];
                    xb = w[wp + 1];
                    i = w[wp + 2];
                    f = a;
                    xv = MISS;
                    spine = true;
                    break;
                case C_EV_ARG: // a = the value of argument i - 1
                    wp -= 5;
                    f = w[wp];
                    env = w[wp + 1];
                    xb = w[wp + 2];
                    i = w[wp + 3];
                    xv = a;
                    spine = true;
                    break;
                case C_EV_LET: // a = the let value; the body is a tail position
                    wp -= 3;
                    e = ec[w[wp]];
                    env = mk3(K_ENV, w[wp + 1], a);
                    spine = false;
                    break;
                case C_EV_PROJ:
                    wp -= 2;
                    a = mk2(w[wp], a);
                    continue top;
                default: // a frame of another family: hand the result back through the stepper
                    this.wp = wp;
                    this.xp = xp;
                    this.A = a;
                    return;
            }
            run: for (;;) {
                if (wp + 8 > w.length) {
                    w = growW();
                }
                if (!spine) {
                    ev: for (;;) { // EVAL(e, env)
                        if (wp + 8 > w.length) {
                            w = growW();
                        }
                        switch (kind[e]) {
                            case BVAR:
                                a = lookup(env, ea[e]);
                                continue top;
                            case SORT:
                                a = mk2(K_SORT, ea[e]);
                                continue top;
                            case CONST:
                                a = mk2(K_CONST, ea[e]);
                                continue top;
                            case LIT:
                                a = mk2(K_LIT, e);
                                continue top;
                            case LAM:
                                a = mk3(K_LAM, e, env);
                                continue top;
                            case PI:
                                a = mk3(K_PI, e, env);
                                continue top;
                            case APP: {
                                if (INERT && (flag[e] & Terms.INERT) != 0) {
                                    a = Inert.eval(e, env);
                                    continue top;
                                }
                                xb = xp;
                                int hd = e;
                                do {
                                    if (xp == x.length) {
                                        x = growX();
                                    }
                                    x[xp++] = eb[hd];
                                    hd = ea[hd];
                                } while (kind[hd] == APP);
                                i = xp - xb;
                                int r = Fast.eval(hd, env);
                                if (r == MISS) { // the head is a let or a projection
                                    w[wp] = env;
                                    w[wp + 1] = xb;
                                    w[wp + 2] = i;
                                    w[wp + 3] = C_EV_HEAD << 16 | 3;
                                    wp += 4;
                                    if (STATS) {
                                        count(4, false);
                                    }
                                    e = hd;
                                    continue ev;
                                }
                                f = r;
                                xv = MISS;
                                break ev;
                            }
                            case LET: {
                                int r = argFast(eb[e], env);
                                if (r == MISS) {
                                    w[wp] = e;
                                    w[wp + 1] = env;
                                    w[wp + 2] = C_EV_LET << 16 | 2;
                                    wp += 3;
                                    if (STATS) {
                                        count(3, false);
                                    }
                                    e = eb[e];
                                    continue ev;
                                }
                                env = mk3(K_ENV, env, r);
                                e = ec[e];
                                continue ev;
                            }
                            case PROJ: {
                                if (INERT && (flag[e] & Terms.INERT) != 0) {
                                    a = Inert.eval(e, env);
                                    continue top;
                                }
                                int ph = K_PROJ | eb[e] << 4;
                                int r = argFast(ec[e], env);
                                if (r == MISS) {
                                    w[wp] = ph;
                                    w[wp + 1] = C_EV_PROJ << 16 | 1;
                                    wp += 2;
                                    if (STATS) {
                                        count(2, false);
                                    }
                                    e = ec[e];
                                    continue ev;
                                }
                                a = mk2(ph, r);
                                continue top;
                            }
                            default:
                                throw new IllegalStateException();
                        }
                    }
                }
                // spine: f applied to the arguments x[xb + i - 1] .. x[xb]; xv = value of the next one, or MISS
                for (;;) {
                    if (xv == MISS) {
                        if (i == 0) {
                            xp = xb;
                            a = f;
                            continue top;
                        }
                        int arg = x[xb + i - 1];
                        xv = argFast(arg, env);
                        if (xv == MISS) {
                            w[wp] = f;
                            w[wp + 1] = env;
                            w[wp + 2] = xb;
                            w[wp + 3] = i;
                            w[wp + 4] = C_EV_ARG << 16 | 4;
                            wp += 5;
                            if (STATS) {
                                count(5, false);
                            }
                            e = arg;
                            spine = false;
                            continue run;
                        }
                    }
                    i--;
                    if (f > 0 && H[f] == K_LAM) {
                        int body = eb[H[f + 1]];
                        int env2 = mk3(K_ENV, H[f + 2], xv);
                        if (i == 0) { // tail call
                            xp = xb;
                            e = body;
                            env = env2;
                            spine = false;
                            continue run;
                        }
                        int r = argFast(body, env2);
                        if (r == MISS) {
                            w[wp] = env;
                            w[wp + 1] = xb;
                            w[wp + 2] = i;
                            w[wp + 3] = C_EV_HEAD << 16 | 3;
                            wp += 4;
                            if (STATS) {
                                count(4, false);
                            }
                            e = body;
                            env = env2;
                            spine = false;
                            continue run;
                        }
                        f = r;
                    } else {
                        f = mk3(K_APP, f, xv);
                    }
                    xv = MISS;
                }
            }
        }
    }

    // ------------------------------------------------------------------ READBACK family

    private static final int S_RB = 0, S_SPINE = 1, S_B1 = 2, S_B2 = 3, S_B3 = 4;

    @SuppressWarnings("fallthrough")
    private void rbLoop() {
        int[] w = W, x = X;
        int wp = this.wp, xp = this.xp, a = this.A;
        int v = 0, depth = 0, t = 0, xb = 0, i = 0, st, td = 0, k = 0;
        top: for (;;) {
            switch (w[wp - 1] >>> 16) {
                case C_RB:
                    wp -= 3;
                    v = w[wp];
                    depth = w[wp + 1];
                    st = S_RB;
                    break;
                case C_RB_HEAD: // a = the term of the head
                    wp -= 4;
                    depth = w[wp];
                    xb = w[wp + 1];
                    i = w[wp + 2];
                    t = a;
                    st = S_SPINE;
                    break;
                case C_RB_ARG: // a = the term of argument i - 1
                    wp -= 5;
                    t = mk(APP, w[wp], a, 0);
                    depth = w[wp + 1];
                    xb = w[wp + 2];
                    i = w[wp + 3] - 1;
                    st = S_SPINE;
                    break;
                case C_RB_PROJ:
                    wp -= 2;
                    a = mk(PROJ, 0, w[wp], a);
                    continue top;
                case C_RB_B1: // a = the value of the domain
                    wp -= 3;
                    v = w[wp];
                    depth = w[wp + 1];
                    st = S_B1;
                    break;
                case C_RB_B2: // a = the term of the domain
                    wp -= 3;
                    v = w[wp];
                    depth = w[wp + 1];
                    st = S_B2;
                    break;
                case C_RB_B3: // a = the value of the body
                    wp -= 4;
                    k = w[wp];
                    depth = w[wp + 1];
                    td = w[wp + 2];
                    st = S_B3;
                    break;
                case C_RB_B4: // a = the term of the body
                    wp -= 3;
                    a = mk(w[wp], w[wp + 1], a, 0);
                    continue top;
                default:
                    this.wp = wp;
                    this.xp = xp;
                    this.A = a;
                    return;
            }
            run: for (;;) {
                if (wp + 12 > w.length) {
                    w = growW();
                }
                switch (st) {
                    case S_RB: {
                        if (v < 0) {
                            a = mk(BVAR, depth + v, 0, 0);
                            continue top;
                        }
                        int hdr = H[v];
                        switch (hdr & 15) {
                            case K_APP: {
                                xb = xp;
                                int hd = v;
                                do {
                                    if (xp == x.length) {
                                        x = growX();
                                    }
                                    x[xp++] = H[hd + 2];
                                    hd = H[hd + 1];
                                } while (hd > 0 && (H[hd] & 15) == K_APP);
                                i = xp - xb;
                                int r = Fast.rb(hd, depth);
                                if (r == MISS) {
                                    w[wp] = depth;
                                    w[wp + 1] = xb;
                                    w[wp + 2] = i;
                                    w[wp + 3] = C_RB_HEAD << 16 | 3;
                                    wp += 4;
                                    if (STATS) {
                                        count(4, false);
                                    }
                                    v = hd;
                                    continue run;
                                }
                                t = r;
                                st = S_SPINE;
                                continue run;
                            }
                            case K_PROJ: {
                                int s = H[v + 1];
                                int r = Fast.rb(s, depth);
                                if (r == MISS) {
                                    w[wp] = hdr >>> 4;
                                    w[wp + 1] = C_RB_PROJ << 16 | 1;
                                    wp += 2;
                                    if (STATS) {
                                        count(2, false);
                                    }
                                    v = s;
                                    continue run;
                                }
                                a = mk(PROJ, 0, hdr >>> 4, r);
                                continue top;
                            }
                            case K_LAM, K_PI: {
                                int e = H[v + 1], env = H[v + 2];
                                int r = argFast(ea[e], env);
                                if (r == MISS) { // EVAL is another family: frame for it, back to the stepper
                                    w[wp] = v;
                                    w[wp + 1] = depth;
                                    w[wp + 2] = C_RB_B1 << 16 | 2;
                                    w[wp + 3] = ea[e];
                                    w[wp + 4] = env;
                                    w[wp + 5] = C_EVAL << 16 | 2;
                                    this.wp = wp + 6;
                                    this.xp = xp;
                                    if (STATS) {
                                        count(3, false);
                                        count(3, true);
                                    }
                                    return;
                                }
                                a = r;
                                st = S_B1;
                                continue run;
                            }
                            case K_SORT:
                                a = mk(SORT, H[v + 1], 0, 0);
                                continue top;
                            case K_CONST:
                                a = mk(CONST, H[v + 1], 0, 0);
                                continue top;
                            case K_LIT:
                                a = H[v + 1];
                                continue top;
                            default:
                                throw new IllegalStateException();
                        }
                    }
                    case S_SPINE:
                        for (;;) {
                            if (i == 0) {
                                xp = xb;
                                a = t;
                                continue top;
                            }
                            int arg = x[xb + i - 1];
                            int r = Fast.rb(arg, depth);
                            if (r == MISS) {
                                w[wp] = t;
                                w[wp + 1] = depth;
                                w[wp + 2] = xb;
                                w[wp + 3] = i;
                                w[wp + 4] = C_RB_ARG << 16 | 4;
                                wp += 5;
                                if (STATS) {
                                    count(5, false);
                                }
                                v = arg;
                                st = S_RB;
                                continue run;
                            }
                            t = mk(APP, t, r, 0);
                            i--;
                        }
                    case S_B1: { // a = the value of the domain
                        int r = Fast.rb(a, depth);
                        if (r == MISS) {
                            w[wp] = v;
                            w[wp + 1] = depth;
                            w[wp + 2] = C_RB_B2 << 16 | 2;
                            wp += 3;
                            if (STATS) {
                                count(3, false);
                            }
                            v = a;
                            st = S_RB;
                            continue run;
                        }
                        a = r;
                    }
                    case S_B2: { // a = the term of the domain
                        td = a;
                        int e = H[v + 1];
                        k = (H[v] & 15) == K_LAM ? LAM : PI;
                        int env2 = mk3(K_ENV, H[v + 2], -(depth + 1));
                        int r = argFast(eb[e], env2);
                        if (r == MISS) {
                            w[wp] = k;
                            w[wp + 1] = depth;
                            w[wp + 2] = td;
                            w[wp + 3] = C_RB_B3 << 16 | 3;
                            w[wp + 4] = eb[e];
                            w[wp + 5] = env2;
                            w[wp + 6] = C_EVAL << 16 | 2;
                            this.wp = wp + 7;
                            this.xp = xp;
                            if (STATS) {
                                count(4, false);
                                count(3, true);
                            }
                            return;
                        }
                        a = r;
                    }
                    default: { // S_B3: a = the value of the body
                        int r = Fast.rb(a, depth + 1);
                        if (r == MISS) {
                            w[wp] = k;
                            w[wp + 1] = td;
                            w[wp + 2] = C_RB_B4 << 16 | 2;
                            wp += 3;
                            if (STATS) {
                                count(3, false);
                            }
                            v = a;
                            depth++;
                            st = S_RB;
                            continue run;
                        }
                        a = mk(k, td, r, 0);
                        continue top;
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ CONV family

    private static final int S_CONV = 0, S_CB1 = 2, S_CB2 = 3, S_CB3 = 4, S_CB4 = 5, S_CB5 = 6;

    @SuppressWarnings("fallthrough")
    private void convLoop() {
        int[] w = W, x = X;
        int wp = this.wp, xp = this.xp, r = this.A;
        int a = 0, b = 0, depth = 0, xb = 0, i = 0, st, da = 0, va = 0;
        top: for (;;) {
            switch (w[wp - 1] >>> 16) {
                case C_CONV:
                    wp -= 4;
                    a = w[wp];
                    b = w[wp + 1];
                    depth = w[wp + 2];
                    st = S_CONV;
                    break;
                case C_CV_SPINE: // r = result of the head or of argument pair i
                    wp -= 4;
                    depth = w[wp];
                    xb = w[wp + 1];
                    i = w[wp + 2];
                    if (r == 0) {
                        xp = xb;
                        continue top;
                    }
                    st = S_SPINE;
                    break;
                case C_CV_B1: // r = domain of a
                    wp -= 4;
                    a = w[wp];
                    b = w[wp + 1];
                    depth = w[wp + 2];
                    st = S_CB1;
                    break;
                case C_CV_B2: // r = domain of b
                    wp -= 5;
                    a = w[wp];
                    b = w[wp + 1];
                    depth = w[wp + 2];
                    da = w[wp + 3];
                    st = S_CB2;
                    break;
                case C_CV_B3: // r = domains convertible?
                    wp -= 4;
                    a = w[wp];
                    b = w[wp + 1];
                    depth = w[wp + 2];
                    st = S_CB3;
                    break;
                case C_CV_B4: // r = body of a
                    wp -= 3;
                    b = w[wp];
                    depth = w[wp + 1];
                    st = S_CB4;
                    break;
                case C_CV_B5: // r = body of b
                    wp -= 3;
                    va = w[wp];
                    depth = w[wp + 1];
                    st = S_CB5;
                    break;
                default:
                    this.wp = wp;
                    this.xp = xp;
                    this.A = r;
                    return;
            }
            run: for (;;) {
                if (wp + 12 > w.length) {
                    w = growW();
                }
                switch (st) {
                    case S_CONV: { // a != b: two records of the same kind APP, PROJ, LAM or PI
                        switch (H[a] & 15) {
                            case K_APP: {
                                xb = xp;
                                int pa = a, pb = b;
                                for (;;) {
                                    if (xp + 2 > x.length) {
                                        x = growX();
                                    }
                                    x[xp] = H[pa + 2];
                                    x[xp + 1] = H[pb + 2];
                                    xp += 2;
                                    pa = H[pa + 1];
                                    pb = H[pb + 1];
                                    r = Fast.conv(pa, pb);
                                    if (r != MISS || (H[pa] & 15) != K_APP) {
                                        break;
                                    }
                                }
                                i = (xp - xb) >> 1;
                                if (r == 0) {
                                    xp = xb;
                                    continue top;
                                }
                                if (r == MISS) { // the heads are projections or closures
                                    w[wp] = depth;
                                    w[wp + 1] = xb;
                                    w[wp + 2] = i;
                                    w[wp + 3] = C_CV_SPINE << 16 | 3;
                                    wp += 4;
                                    if (STATS) {
                                        count(4, false);
                                    }
                                    a = pa;
                                    b = pb;
                                    continue run;
                                }
                                st = S_SPINE;
                                continue run;
                            }
                            case K_PROJ: { // tail position
                                int na = H[a + 1], nb = H[b + 1];
                                r = Fast.conv(na, nb);
                                if (r != MISS) {
                                    continue top;
                                }
                                a = na;
                                b = nb;
                                continue run;
                            }
                            default: { // K_LAM, K_PI
                                int ta = H[a + 1], env = H[a + 2];
                                r = argFast(ea[ta], env);
                                if (r == MISS) {
                                    w[wp] = a;
                                    w[wp + 1] = b;
                                    w[wp + 2] = depth;
                                    w[wp + 3] = C_CV_B1 << 16 | 3;
                                    w[wp + 4] = ea[ta];
                                    w[wp + 5] = env;
                                    w[wp + 6] = C_EVAL << 16 | 2;
                                    this.wp = wp + 7;
                                    this.xp = xp;
                                    if (STATS) {
                                        count(4, false);
                                        count(3, true);
                                    }
                                    return;
                                }
                                st = S_CB1;
                                continue run;
                            }
                        }
                    }
                    case S_SPINE:
                        for (;;) { // innermost pair first
                            if (i == 0) {
                                xp = xb;
                                r = 1;
                                continue top;
                            }
                            i--;
                            int xa = x[xb + 2 * i], xc = x[xb + 2 * i + 1];
                            r = Fast.conv(xa, xc);
                            if (r == MISS) {
                                w[wp] = depth;
                                w[wp + 1] = xb;
                                w[wp + 2] = i;
                                w[wp + 3] = C_CV_SPINE << 16 | 3;
                                wp += 4;
                                if (STATS) {
                                    count(4, false);
                                }
                                a = xa;
                                b = xc;
                                st = S_CONV;
                                continue run;
                            }
                            if (r == 0) {
                                xp = xb;
                                continue top;
                            }
                        }
                    case S_CB1: { // r = domain of a
                        da = r;
                        int tb = H[b + 1], env = H[b + 2];
                        r = argFast(ea[tb], env);
                        if (r == MISS) {
                            w[wp] = a;
                            w[wp + 1] = b;
                            w[wp + 2] = depth;
                            w[wp + 3] = da;
                            w[wp + 4] = C_CV_B2 << 16 | 4;
                            w[wp + 5] = ea[tb];
                            w[wp + 6] = env;
                            w[wp + 7] = C_EVAL << 16 | 2;
                            this.wp = wp + 8;
                            this.xp = xp;
                            if (STATS) {
                                count(5, false);
                                count(3, true);
                            }
                            return;
                        }
                    }
                    case S_CB2: { // r = domain of b
                        int db = r;
                        r = Fast.conv(da, db);
                        if (r == MISS) {
                            w[wp] = a;
                            w[wp + 1] = b;
                            w[wp + 2] = depth;
                            w[wp + 3] = C_CV_B3 << 16 | 3;
                            wp += 4;
                            if (STATS) {
                                count(4, false);
                            }
                            a = da;
                            b = db;
                            st = S_CONV;
                            continue run;
                        }
                    }
                    case S_CB3: { // r = domains convertible?
                        if (r == 0) {
                            continue top;
                        }
                        int ta = H[a + 1];
                        int env2 = mk3(K_ENV, H[a + 2], -(depth + 1));
                        r = argFast(eb[ta], env2);
                        if (r == MISS) {
                            w[wp] = b;
                            w[wp + 1] = depth;
                            w[wp + 2] = C_CV_B4 << 16 | 2;
                            w[wp + 3] = eb[ta];
                            w[wp + 4] = env2;
                            w[wp + 5] = C_EVAL << 16 | 2;
                            this.wp = wp + 6;
                            this.xp = xp;
                            if (STATS) {
                                count(3, false);
                                count(3, true);
                            }
                            return;
                        }
                    }
                    case S_CB4: { // r = body of a
                        va = r;
                        int tb = H[b + 1];
                        int env2 = mk3(K_ENV, H[b + 2], -(depth + 1));
                        r = argFast(eb[tb], env2);
                        if (r == MISS) {
                            w[wp] = va;
                            w[wp + 1] = depth;
                            w[wp + 2] = C_CV_B5 << 16 | 2;
                            w[wp + 3] = eb[tb];
                            w[wp + 4] = env2;
                            w[wp + 5] = C_EVAL << 16 | 2;
                            this.wp = wp + 6;
                            this.xp = xp;
                            if (STATS) {
                                count(3, false);
                                count(3, true);
                            }
                            return;
                        }
                    }
                    default: { // S_CB5: r = body of b; the comparison of the bodies is a tail position
                        int vb = r;
                        r = Fast.conv(va, vb);
                        if (r != MISS) {
                            continue top;
                        }
                        a = va;
                        b = vb;
                        depth++;
                        st = S_CONV;
                        continue run;
                    }
                }
            }
        }
    }
}

package rlvm.spike.stepper;

import static rlvm.spike.stepper.Fast.MISS;
import static rlvm.spike.stepper.Terms.*;
import static rlvm.spike.stepper.Vals.*;

import java.util.Arrays;

/**
 * (W0) The convention of *agentic-note*: design-isa-document, 1.3 as written.
 *
 * <ul>
 * <li>Registers are fields of the machine: {@code W}, {@code wp}, {@code A}, the scratch {@code X}, {@code xp}.</li>
 * <li>A frame is its payload with a header word on top: {@code hdr = op:8 | st:8 | n:16}. A judgment owns one
 * frame for its whole life; its state lives in the frame.</li>
 * <li>{@link #run} is the only loop: it switches on the op of the top frame and calls that judgment's
 * {@code step(m)}, which switches on the frame's state and runs until the judgment completes or needs a
 * sub-judgment that its inline fast path cannot answer.</li>
 * <li>{@code call J(x) => k}: {@code r = J.fast(x)}; a hit continues in state k; a miss stores k in the header,
 * pushes the frame of J in state 0 and returns to the stepper. {@code ret v}: {@code A = v}, pop, return.</li>
 * <li>Spines, let chains and tail positions are loops inside one frame (ISA section 9).</li>
 * </ul>
 * Frames (5 payload words each, as the generic judgment frames of ISA 1.3):
 * {@code EVAL [t, env, f, xb, i]}, {@code RB [v, depth, t, xb, i]}, {@code CONV [a, b, depth, xb / tmp, i]},
 * and the sentinel {@code DECL [root, v, nf, w]}.
 */
public final class W0 extends Engine {
    static final int OP_DECL = 1, OP_EVAL = 2, OP_RB = 3, OP_CONV = 4;

    int[] W = new int[1 << 12];
    int wp;
    int A;
    int[] X = new int[1 << 12];
    int xp;

    private static int hdr(int op, int st, int n) {
        return op << 24 | st << 16 | n;
    }

    private int[] growW() {
        return W = Arrays.copyOf(W, W.length * 2);
    }

    private int[] growX() {
        return X = Arrays.copyOf(X, X.length * 2);
    }

    /** Pushes a judgment frame in state 0 with three operands and two zeroed working slots. */
    private static void push(W0 m, int op, int p0, int p1, int p2) {
        int wp = m.wp;
        int[] w = m.W;
        if (wp + 6 > w.length) {
            w = m.growW();
        }
        w[wp] = p0;
        w[wp + 1] = p1;
        w[wp + 2] = p2;
        w[wp + 3] = 0;
        w[wp + 4] = 0;
        w[wp + 5] = hdr(op, 0, 5);
        m.wp = wp + 6;
        if (STATS) {
            Stat.frames++;
            Stat.freshFrames++;
            Stat.frameWords += 6;
        }
    }

    // ------------------------------------------------------------------ the stepper

    static void run(W0 m) {
        for (;;) {
            int h = m.W[m.wp - 1];
            switch (h >>> 24) {
                case OP_EVAL:
                    Eval.step(m);
                    break;
                case OP_RB:
                    Rb.step(m);
                    break;
                case OP_CONV:
                    Conv.step(m);
                    break;
                case OP_DECL:
                    if (Decl.step(m)) {
                        return;
                    }
                    break;
                default:
                    throw new IllegalStateException();
            }
        }
    }

    @Override
    public void decl(int root) {
        int[] w = W;
        w[0] = root;
        w[1] = 0;
        w[2] = 0;
        w[3] = 0;
        w[4] = hdr(OP_DECL, 0, 4);
        wp = 5;
        run(this);
    }

    @Override
    public int stackWords() {
        return W.length;
    }

    // ------------------------------------------------------------------ DECL [root, v, nf, w]

    static final class Decl {
        static boolean step(W0 m) {
            final int[] w = m.W;
            final int top = m.wp - 1, fp = top - 4;
            int st = (w[top] >>> 16) & 0xFF;
            for (;;) {
                switch (st) {
                    case 0: { // call EVAL(root, []) => 1
                        int r = Fast.eval(w[fp], 0);
                        if (r != MISS) {
                            m.A = r;
                            st = 1;
                            continue;
                        }
                        w[top] = hdr(OP_DECL, 1, 4);
                        push(m, OP_EVAL, w[fp], 0, 0);
                        return false;
                    }
                    case 1: { // call RB(v, 0) => 2
                        int v = m.A;
                        w[fp + 1] = v;
                        int r = Fast.rb(v, 0);
                        if (r != MISS) {
                            m.A = r;
                            st = 2;
                            continue;
                        }
                        w[top] = hdr(OP_DECL, 2, 4);
                        push(m, OP_RB, v, 0, 0);
                        return false;
                    }
                    case 2: { // call EVAL(nf, []) => 3
                        int t = m.A;
                        w[fp + 2] = t;
                        int r = Fast.eval(t, 0);
                        if (r != MISS) {
                            m.A = r;
                            st = 3;
                            continue;
                        }
                        w[top] = hdr(OP_DECL, 3, 4);
                        push(m, OP_EVAL, t, 0, 0);
                        return false;
                    }
                    case 3: { // call CONV(v, w, 0) => 4
                        int u = m.A;
                        w[fp + 3] = u;
                        int r = Fast.conv(w[fp + 1], u);
                        if (r != MISS) {
                            m.A = r;
                            st = 4;
                            continue;
                        }
                        w[top] = hdr(OP_DECL, 4, 4);
                        push(m, OP_CONV, w[fp + 1], u, 0);
                        return false;
                    }
                    default:
                        nf = w[fp + 2];
                        Engine.w = w[fp + 3];
                        ok = m.A != 0;
                        m.wp = fp;
                        return true;
                }
            }
        }
    }

    // ------------------------------------------------------------------ EVAL [t, env, f, xb, i]

    static final class Eval {
        static void step(W0 m) {
            final int[] w = m.W;
            final int top = m.wp - 1, fp = top - 5;
            int st = (w[top] >>> 16) & 0xFF;
            for (;;) {
                switch (st) {
                    case 0: {
                        int t = w[fp], env = w[fp + 1];
                        switch (kind[t]) {
                            case APP: { // collect the spine in X, then call EVAL(head) => 1
                                int xb = m.xp, xp = xb;
                                int[] x = m.X;
                                int hd = t;
                                do {
                                    if (xp == x.length) {
                                        x = m.growX();
                                    }
                                    x[xp++] = eb[hd];
                                    hd = ea[hd];
                                } while (kind[hd] == APP);
                                m.xp = xp;
                                w[fp + 3] = xb;
                                w[fp + 4] = xp - xb;
                                int r = Fast.eval(hd, env);
                                if (r != MISS) {
                                    m.A = r;
                                    st = 1;
                                    continue;
                                }
                                w[top] = hdr(OP_EVAL, 1, 5);
                                push(m, OP_EVAL, hd, env, 0);
                                return;
                            }
                            case LET: { // call EVAL(value) => 3
                                int r = Fast.eval(eb[t], env);
                                if (r != MISS) {
                                    m.A = r;
                                    st = 3;
                                    continue;
                                }
                                w[top] = hdr(OP_EVAL, 3, 5);
                                push(m, OP_EVAL, eb[t], env, 0);
                                return;
                            }
                            case PROJ: { // call EVAL(struct) => 4
                                int r = Fast.eval(ec[t], env);
                                if (r != MISS) {
                                    m.A = r;
                                    st = 4;
                                    continue;
                                }
                                w[top] = hdr(OP_EVAL, 4, 5);
                                push(m, OP_EVAL, ec[t], env, 0);
                                return;
                            }
                            default: // a frame for an atom: only when a caller skipped the fast path
                                m.A = Fast.eval(t, env);
                                m.wp = fp;
                                return;
                        }
                    }
                    case 1: // A = the function value
                        w[fp + 2] = m.A;
                        st = 2;
                        continue;
                    case 2: { // argument loop: call EVAL(arg) => 5
                        int i = w[fp + 4];
                        if (i == 0) {
                            m.xp = w[fp + 3];
                            m.A = w[fp + 2];
                            m.wp = fp;
                            return;
                        }
                        int arg = m.X[w[fp + 3] + i - 1], env = w[fp + 1];
                        int r = Fast.eval(arg, env);
                        if (r != MISS) {
                            m.A = r;
                            st = 5;
                            continue;
                        }
                        w[top] = hdr(OP_EVAL, 5, 5);
                        push(m, OP_EVAL, arg, env, 0);
                        return;
                    }
                    case 5: { // A = the argument value: apply
                        int x = m.A, f = w[fp + 2];
                        int i = w[fp + 4] - 1;
                        w[fp + 4] = i;
                        if (f > 0 && H[f] == K_LAM) {
                            int body = eb[H[f + 1]];
                            int env2 = mk3(K_ENV, H[f + 2], x);
                            if (i == 0) { // TAILAPPLY: this frame becomes EVAL(body, env2)
                                m.xp = w[fp + 3];
                                w[fp] = body;
                                w[fp + 1] = env2;
                                st = 0;
                                continue;
                            }
                            int r = Fast.eval(body, env2); // call EVAL(body) => 1
                            if (r != MISS) {
                                m.A = r;
                                st = 1;
                                continue;
                            }
                            w[top] = hdr(OP_EVAL, 1, 5);
                            push(m, OP_EVAL, body, env2, 0);
                            return;
                        }
                        w[fp + 2] = mk3(K_APP, f, x);
                        st = 2;
                        continue;
                    }
                    case 3: { // A = the let value: the body is a tail position
                        int t = w[fp];
                        w[fp + 1] = mk3(K_ENV, w[fp + 1], m.A);
                        w[fp] = ec[t];
                        st = 0;
                        continue;
                    }
                    default: // 4: A = the struct value
                        m.A = mk2(K_PROJ | eb[w[fp]] << 4, m.A);
                        m.wp = fp;
                        return;
                }
            }
        }
    }

    // ------------------------------------------------------------------ RB [v, depth, t, xb, i]

    static final class Rb {
        static void step(W0 m) {
            final int[] w = m.W;
            final int top = m.wp - 1, fp = top - 5;
            int st = (w[top] >>> 16) & 0xFF;
            for (;;) {
                switch (st) {
                    case 0: {
                        int v = w[fp], depth = w[fp + 1];
                        int k = v < 0 ? 0 : H[v] & 15;
                        switch (k) {
                            case K_APP: { // collect the spine in X, then call RB(head) => 1
                                int xb = m.xp, xp = xb;
                                int[] x = m.X;
                                int hd = v;
                                do {
                                    if (xp == x.length) {
                                        x = m.growX();
                                    }
                                    x[xp++] = H[hd + 2];
                                    hd = H[hd + 1];
                                } while (hd > 0 && (H[hd] & 15) == K_APP);
                                m.xp = xp;
                                w[fp + 3] = xb;
                                w[fp + 4] = xp - xb;
                                int r = Fast.rb(hd, depth);
                                if (r != MISS) {
                                    m.A = r;
                                    st = 1;
                                    continue;
                                }
                                w[top] = hdr(OP_RB, 1, 5);
                                push(m, OP_RB, hd, depth, 0);
                                return;
                            }
                            case K_PROJ: { // call RB(struct) => 4
                                int r = Fast.rb(H[v + 1], depth);
                                if (r != MISS) {
                                    m.A = r;
                                    st = 4;
                                    continue;
                                }
                                w[top] = hdr(OP_RB, 4, 5);
                                push(m, OP_RB, H[v + 1], depth, 0);
                                return;
                            }
                            case K_LAM, K_PI: { // call EVAL(domain) => 5
                                int e = H[v + 1], env = H[v + 2];
                                int r = Fast.eval(ea[e], env);
                                if (r != MISS) {
                                    m.A = r;
                                    st = 5;
                                    continue;
                                }
                                w[top] = hdr(OP_RB, 5, 5);
                                push(m, OP_EVAL, ea[e], env, 0);
                                return;
                            }
                            default:
                                m.A = Fast.rb(v, depth);
                                m.wp = fp;
                                return;
                        }
                    }
                    case 1: // A = the term of the head
                        w[fp + 2] = m.A;
                        st = 2;
                        continue;
                    case 2: { // argument loop: call RB(arg) => 3
                        int i = w[fp + 4];
                        if (i == 0) {
                            m.xp = w[fp + 3];
                            m.A = w[fp + 2];
                            m.wp = fp;
                            return;
                        }
                        int arg = m.X[w[fp + 3] + i - 1], depth = w[fp + 1];
                        int r = Fast.rb(arg, depth);
                        if (r != MISS) {
                            m.A = r;
                            st = 3;
                            continue;
                        }
                        w[top] = hdr(OP_RB, 3, 5);
                        push(m, OP_RB, arg, depth, 0);
                        return;
                    }
                    case 3: // A = the term of the argument
                        w[fp + 2] = mk(APP, w[fp + 2], m.A, 0);
                        w[fp + 4]--;
                        st = 2;
                        continue;
                    case 4: // A = the term of the struct
                        m.A = mk(PROJ, 0, H[w[fp]] >>> 4, m.A);
                        m.wp = fp;
                        return;
                    case 5: { // A = the value of the domain: call RB => 6
                        int dv = m.A, depth = w[fp + 1];
                        int r = Fast.rb(dv, depth);
                        if (r != MISS) {
                            m.A = r;
                            st = 6;
                            continue;
                        }
                        w[top] = hdr(OP_RB, 6, 5);
                        push(m, OP_RB, dv, depth, 0);
                        return;
                    }
                    case 6: { // A = the term of the domain: call EVAL(body, env + x) => 7
                        w[fp + 2] = m.A;
                        int v = w[fp], depth = w[fp + 1];
                        int e = H[v + 1];
                        int env2 = mk3(K_ENV, H[v + 2], -(depth + 1));
                        int r = Fast.eval(eb[e], env2);
                        if (r != MISS) {
                            m.A = r;
                            st = 7;
                            continue;
                        }
                        w[top] = hdr(OP_RB, 7, 5);
                        push(m, OP_EVAL, eb[e], env2, 0);
                        return;
                    }
                    case 7: { // A = the value of the body: call RB(depth + 1) => 8
                        int bv = m.A, depth = w[fp + 1] + 1;
                        int r = Fast.rb(bv, depth);
                        if (r != MISS) {
                            m.A = r;
                            st = 8;
                            continue;
                        }
                        w[top] = hdr(OP_RB, 8, 5);
                        push(m, OP_RB, bv, depth, 0);
                        return;
                    }
                    default: // 8: A = the term of the body
                        m.A = mk((H[w[fp]] & 15) == K_LAM ? LAM : PI, w[fp + 2], m.A, 0);
                        m.wp = fp;
                        return;
                }
            }
        }
    }

    // ------------------------------------------------------------------ CONV [a, b, depth, xb / tmp, i]

    static final class Conv {
        static void step(W0 m) {
            final int[] w = m.W;
            final int top = m.wp - 1, fp = top - 5;
            int st = (w[top] >>> 16) & 0xFF;
            for (;;) {
                switch (st) {
                    case 0: { // the fast path missed: two records of the same kind APP, PROJ, LAM or PI
                        int a = w[fp], b = w[fp + 1];
                        switch (H[a] & 15) {
                            case K_APP: { // walk both spines down to the first equal or non-application pair
                                int xb = m.xp, xp = xb;
                                int[] x = m.X;
                                int pa = a, pb = b, r;
                                for (;;) {
                                    if (xp + 2 > x.length) {
                                        x = m.growX();
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
                                if (r == 0) {
                                    m.A = 0;
                                    m.wp = fp;
                                    return;
                                }
                                m.xp = xp;
                                w[fp + 3] = xb;
                                w[fp + 4] = (xp - xb) >> 1;
                                if (r != MISS) {
                                    st = 2;
                                    continue;
                                }
                                w[top] = hdr(OP_CONV, 1, 5); // call CONV(heads) => 1
                                push(m, OP_CONV, pa, pb, w[fp + 2]);
                                return;
                            }
                            case K_PROJ: { // tail position
                                int na = H[a + 1], nb = H[b + 1];
                                int r = Fast.conv(na, nb);
                                if (r != MISS) {
                                    m.A = r;
                                    m.wp = fp;
                                    return;
                                }
                                w[fp] = na;
                                w[fp + 1] = nb;
                                continue;
                            }
                            default: { // K_LAM, K_PI: call EVAL(domain of a) => 5
                                int ta = H[a + 1];
                                int r = Fast.eval(ea[ta], H[a + 2]);
                                if (r != MISS) {
                                    m.A = r;
                                    st = 5;
                                    continue;
                                }
                                w[top] = hdr(OP_CONV, 5, 5);
                                push(m, OP_EVAL, ea[ta], H[a + 2], 0);
                                return;
                            }
                        }
                    }
                    case 1: // A = result of a head or argument comparison
                        if (m.A == 0) {
                            m.xp = w[fp + 3];
                            m.wp = fp;
                            return;
                        }
                        st = 2;
                        continue;
                    case 2: { // argument loop, innermost pair first: call CONV => 1
                        int i = w[fp + 4];
                        if (i == 0) {
                            m.xp = w[fp + 3];
                            m.A = 1;
                            m.wp = fp;
                            return;
                        }
                        i--;
                        w[fp + 4] = i;
                        int q = w[fp + 3] + 2 * i;
                        int xa = m.X[q], xc = m.X[q + 1];
                        int r = Fast.conv(xa, xc);
                        if (r != MISS) {
                            m.A = r;
                            st = 1;
                            continue;
                        }
                        w[top] = hdr(OP_CONV, 1, 5);
                        push(m, OP_CONV, xa, xc, w[fp + 2]);
                        return;
                    }
                    case 5: { // A = domain of a: call EVAL(domain of b) => 6
                        w[fp + 3] = m.A;
                        int b = w[fp + 1];
                        int tb = H[b + 1];
                        int r = Fast.eval(ea[tb], H[b + 2]);
                        if (r != MISS) {
                            m.A = r;
                            st = 6;
                            continue;
                        }
                        w[top] = hdr(OP_CONV, 6, 5);
                        push(m, OP_EVAL, ea[tb], H[b + 2], 0);
                        return;
                    }
                    case 6: { // A = domain of b: call CONV(domains) => 7
                        int da = w[fp + 3], db = m.A;
                        int r = Fast.conv(da, db);
                        if (r != MISS) {
                            m.A = r;
                            st = 7;
                            continue;
                        }
                        w[top] = hdr(OP_CONV, 7, 5);
                        push(m, OP_CONV, da, db, w[fp + 2]);
                        return;
                    }
                    case 7: { // A = domains convertible? call EVAL(body of a, env + x) => 8
                        if (m.A == 0) {
                            m.wp = fp;
                            return;
                        }
                        int a = w[fp];
                        int ta = H[a + 1];
                        int env2 = mk3(K_ENV, H[a + 2], -(w[fp + 2] + 1));
                        int r = Fast.eval(eb[ta], env2);
                        if (r != MISS) {
                            m.A = r;
                            st = 8;
                            continue;
                        }
                        w[top] = hdr(OP_CONV, 8, 5);
                        push(m, OP_EVAL, eb[ta], env2, 0);
                        return;
                    }
                    case 8: { // A = body of a: call EVAL(body of b, env + x) => 9
                        w[fp + 3] = m.A;
                        int b = w[fp + 1];
                        int tb = H[b + 1];
                        int env2 = mk3(K_ENV, H[b + 2], -(w[fp + 2] + 1));
                        int r = Fast.eval(eb[tb], env2);
                        if (r != MISS) {
                            m.A = r;
                            st = 9;
                            continue;
                        }
                        w[top] = hdr(OP_CONV, 9, 5);
                        push(m, OP_EVAL, eb[tb], env2, 0);
                        return;
                    }
                    default: { // 9: A = body of b: CONV(bodies, depth + 1) is a tail position
                        int va = w[fp + 3], vb = m.A;
                        int r = Fast.conv(va, vb);
                        if (r != MISS) {
                            m.A = r;
                            m.wp = fp;
                            return;
                        }
                        w[fp] = va;
                        w[fp + 1] = vb;
                        w[fp + 2]++;
                        st = 0;
                        continue;
                    }
                }
            }
        }
    }
}

package rlvm.spike.machine;

import static rlvm.spike.machine.Corpus.*;
import static rlvm.spike.machine.Vals.*;

import java.util.Locale;

/**
 * Experiment 2: what does it cost to run NbE as a first-order worklist machine instead of recursive Java?
 *
 * The same normaliser twice: eval + readback (quote) of every declaration type of the corpus, constants opaque.
 * Both versions call the same operation library in the same order, so they must intern the very same records
 * and return the very same term ids.
 *
 * Values: atoms (negative) are free variables, -(level + 1); APP/PROJ/SORT/CONST/LIT as in experiment 1;
 * closures LAME/PIE [hdr, expr, env] over hash-consed environment lists ENV [hdr, parent, value] (0 = empty).
 * Normal forms: TAPP/TLAM/TPI [hdr, a, b], TBVAR [hdr, index], TPROJ [hdr | idx<<4, t]; SORT/CONST/LIT records
 * are shared between values and normal forms.
 *
 * <pre>
 *   rec   direct recursive Java (host stack)
 *   wl    one loop over an explicit continuation stack in a flat int[]; no host recursion. The control state
 *         is a mode register (EVAL / RETURN-VALUE / READBACK / RETURN-TERM) dispatched at the top of the loop.
 *   wl2   the same frames and the same stack, control state in the program counter (phase loops)
 * </pre>
 */
public final class Exp2 {
    private Exp2() {
    }

    public static long steps;

    // ------------------------------------------------------------------ shared leaf operations

    private static int lookup(int env, int i) {
        while (i > 0) {
            env = Mem.h(env + 1);
            i--;
        }
        return Mem.h(env + 2);
    }

    // ------------------------------------------------------------------ direct recursion

    static int eval(int e, int env) {
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
                int f = eval(ea[e], env);
                int x = eval(eb[e], env);
                return apply(f, x);
            }
            case LAM:
                return mk3(K_LAME, e, env);
            case PI:
                return mk3(K_PIE, e, env);
            case LET: {
                int v = eval(eb[e], env);
                return eval(ec[e], mk3(K_ENV, env, v));
            }
            case PROJ:
                return mk2(K_PROJ | eb[e] << 4, eval(ec[e], env));
            default:
                throw new IllegalStateException();
        }
    }

    static int apply(int f, int x) {
        if (f > 0 && Mem.h(f) == K_LAME) {
            return eval(eb[Mem.h(f + 1)], mk3(K_ENV, Mem.h(f + 2), x));
        }
        return mk3(K_APP, f, x);
    }

    static int readback(int v, int depth) {
        if (v < 0) {
            return mk2(K_TBVAR, depth + v); // depth - 1 - level, level = -v - 1
        }
        int hdr = Mem.h(v);
        switch (hdr & 15) {
            case K_APP: {
                int tf = readback(Mem.h(v + 1), depth);
                int ta = readback(Mem.h(v + 2), depth);
                return mk3(K_TAPP, tf, ta);
            }
            case K_PROJ:
                return mk2(K_TPROJ | (hdr & ~15), readback(Mem.h(v + 1), depth));
            case K_LAME, K_PIE: {
                int e = Mem.h(v + 1), env = Mem.h(v + 2);
                int td = readback(eval(ea[e], env), depth);
                int tb = readback(eval(eb[e], mk3(K_ENV, env, -(depth + 1))), depth + 1);
                return mk3((hdr & 15) == K_LAME ? K_TLAM : K_TPI, td, tb);
            }
            default:
                return v; // SORT, CONST, LIT
        }
    }

    // ------------------------------------------------------------------ worklist machine

    private static final int F_DONE = 0, F_APP_ARG = 1, F_APP_FN = 2, F_LET = 3, F_PROJ = 4, F_RB = 5, F_RB_APP_ARG = 6, F_RB_APP_MK = 7, F_RB_PROJ = 8, F_RB_BODY = 9,
                    F_RB_BIND_MK = 10;
    private static final int M_EVAL = 0, M_RETV = 1, M_RB = 2, M_RETT = 3;

    private static int[] K = new int[1 << 16];

    /** Normal form of e (closed), as a term id. Frames are [fields.., tag] with the tag on top. */
    static int normalizeWl(int e0) {
        int[] k = K;
        int sp = 0;
        k[sp++] = F_DONE;
        k[sp++] = 0; // read the value back at depth 0
        k[sp++] = F_RB;
        int mode = M_EVAL;
        int e = e0, env = 0; // M_EVAL registers
        int v = 0, depth = 0; // M_RETV: v; M_RB: v, depth; M_RETT: v = term
        for (;;) {
            if (sp + 8 > k.length) {
                k = K = java.util.Arrays.copyOf(k, k.length * 2);
            }
            switch (mode) {
                case M_EVAL:
                    switch (kind[e]) {
                        case BVAR:
                            v = lookup(env, ea[e]);
                            mode = M_RETV;
                            break;
                        case SORT:
                            v = mk2(K_SORT, ea[e]);
                            mode = M_RETV;
                            break;
                        case CONST:
                            v = mk2(K_CONST, ea[e]);
                            mode = M_RETV;
                            break;
                        case LIT:
                            v = mk2(K_LIT, e);
                            mode = M_RETV;
                            break;
                        case APP:
                            k[sp] = eb[e];
                            k[sp + 1] = env;
                            k[sp + 2] = F_APP_ARG;
                            sp += 3;
                            e = ea[e];
                            break;
                        case LAM:
                            v = mk3(K_LAME, e, env);
                            mode = M_RETV;
                            break;
                        case PI:
                            v = mk3(K_PIE, e, env);
                            mode = M_RETV;
                            break;
                        case LET:
                            k[sp] = ec[e];
                            k[sp + 1] = env;
                            k[sp + 2] = F_LET;
                            sp += 3;
                            e = eb[e];
                            break;
                        case PROJ:
                            k[sp] = K_PROJ | eb[e] << 4;
                            k[sp + 1] = F_PROJ;
                            sp += 2;
                            e = ec[e];
                            break;
                        default:
                            throw new IllegalStateException();
                    }
                    break;
                case M_RETV:
                    switch (k[--sp]) {
                        case F_APP_ARG: { // v = function value; evaluate the argument next
                            sp -= 2;
                            e = k[sp];
                            env = k[sp + 1];
                            k[sp] = v;
                            k[sp + 1] = F_APP_FN;
                            sp += 2;
                            mode = M_EVAL;
                            break;
                        }
                        case F_APP_FN: { // v = argument value
                            int f = k[--sp];
                            if (f > 0 && Mem.h(f) == K_LAME) {
                                e = eb[Mem.h(f + 1)];
                                env = mk3(K_ENV, Mem.h(f + 2), v);
                                mode = M_EVAL;
                            } else {
                                v = mk3(K_APP, f, v);
                            }
                            break;
                        }
                        case F_LET:
                            sp -= 2;
                            e = k[sp];
                            env = mk3(K_ENV, k[sp + 1], v);
                            mode = M_EVAL;
                            break;
                        case F_PROJ:
                            v = mk2(k[--sp], v);
                            break;
                        case F_RB:
                            depth = k[--sp];
                            mode = M_RB;
                            break;
                        default:
                            throw new IllegalStateException();
                    }
                    break;
                case M_RB:
                    if (v < 0) {
                        v = mk2(K_TBVAR, depth + v);
                        mode = M_RETT;
                        break;
                    }
                    {
                        int hdr = Mem.h(v);
                        switch (hdr & 15) {
                            case K_APP:
                                k[sp] = Mem.h(v + 2);
                                k[sp + 1] = depth;
                                k[sp + 2] = F_RB_APP_ARG;
                                sp += 3;
                                v = Mem.h(v + 1);
                                break;
                            case K_PROJ:
                                k[sp] = K_TPROJ | (hdr & ~15);
                                k[sp + 1] = F_RB_PROJ;
                                sp += 2;
                                v = Mem.h(v + 1);
                                break;
                            case K_LAME, K_PIE: {
                                int ce = Mem.h(v + 1), cenv = Mem.h(v + 2);
                                k[sp] = (hdr & 15) == K_LAME ? K_TLAM : K_TPI;
                                k[sp + 1] = ce;
                                k[sp + 2] = cenv;
                                k[sp + 3] = depth;
                                k[sp + 4] = F_RB_BODY;
                                k[sp + 5] = depth;
                                k[sp + 6] = F_RB;
                                sp += 7;
                                e = ea[ce];
                                env = cenv;
                                mode = M_EVAL;
                                break;
                            }
                            default:
                                mode = M_RETT;
                                break;
                        }
                    }
                    break;
                default: // M_RETT: v is a term
                    switch (k[--sp]) {
                        case F_DONE:
                            return v;
                        case F_RB_APP_ARG: { // v = term of the function
                            sp -= 2;
                            int a = k[sp];
                            depth = k[sp + 1];
                            k[sp] = v;
                            k[sp + 1] = F_RB_APP_MK;
                            sp += 2;
                            v = a;
                            mode = M_RB;
                            break;
                        }
                        case F_RB_APP_MK:
                            v = mk3(K_TAPP, k[--sp], v);
                            break;
                        case F_RB_PROJ:
                            v = mk2(k[--sp], v);
                            break;
                        case F_RB_BODY: { // v = term of the domain
                            sp -= 4;
                            int tk = k[sp], ce = k[sp + 1], cenv = k[sp + 2];
                            depth = k[sp + 3];
                            k[sp] = tk;
                            k[sp + 1] = v;
                            k[sp + 2] = F_RB_BIND_MK;
                            k[sp + 3] = depth + 1;
                            k[sp + 4] = F_RB;
                            sp += 5;
                            e = eb[ce];
                            env = mk3(K_ENV, cenv, -(depth + 1));
                            mode = M_EVAL;
                            break;
                        }
                        case F_RB_BIND_MK:
                            sp -= 2;
                            v = mk3(k[sp], k[sp + 1], v);
                            break;
                        default:
                            throw new IllegalStateException();
                    }
                    break;
            }
        }
    }

    /**
     * The same machine with the control state held in the program counter instead of a mode register: four
     * nested phase loops (evaluate downwards, return a value, read back downwards, return a term). A transition
     * that stays in its phase is a jump to the head of that loop; there is no second dispatch on a mode.
     */
    static int normalizeWl2(int e0) {
        int[] k = K;
        int sp = 0;
        k[sp++] = F_DONE;
        k[sp++] = 0;
        k[sp++] = F_RB;
        int e = e0, env = 0, v = 0, depth = 0;
        outer: for (;;) {
            eval: for (;;) {
                if (sp + 8 > k.length) {
                    k = K = java.util.Arrays.copyOf(k, k.length * 2);
                }
                switch (kind[e]) {
                    case BVAR:
                        v = lookup(env, ea[e]);
                        break eval;
                    case SORT:
                        v = mk2(K_SORT, ea[e]);
                        break eval;
                    case CONST:
                        v = mk2(K_CONST, ea[e]);
                        break eval;
                    case LIT:
                        v = mk2(K_LIT, e);
                        break eval;
                    case APP:
                        k[sp] = eb[e];
                        k[sp + 1] = env;
                        k[sp + 2] = F_APP_ARG;
                        sp += 3;
                        e = ea[e];
                        continue eval;
                    case LAM:
                        v = mk3(K_LAME, e, env);
                        break eval;
                    case PI:
                        v = mk3(K_PIE, e, env);
                        break eval;
                    case LET:
                        k[sp] = ec[e];
                        k[sp + 1] = env;
                        k[sp + 2] = F_LET;
                        sp += 3;
                        e = eb[e];
                        continue eval;
                    case PROJ:
                        k[sp] = K_PROJ | eb[e] << 4;
                        k[sp + 1] = F_PROJ;
                        sp += 2;
                        e = ec[e];
                        continue eval;
                    default:
                        throw new IllegalStateException();
                }
            }
            retv: for (;;) {
                switch (k[--sp]) {
                    case F_APP_ARG:
                        sp -= 2;
                        e = k[sp];
                        env = k[sp + 1];
                        k[sp] = v;
                        k[sp + 1] = F_APP_FN;
                        sp += 2;
                        continue outer;
                    case F_APP_FN: {
                        int f = k[--sp];
                        if (f > 0 && Mem.h(f) == K_LAME) {
                            e = eb[Mem.h(f + 1)];
                            env = mk3(K_ENV, Mem.h(f + 2), v);
                            continue outer;
                        }
                        v = mk3(K_APP, f, v);
                        continue retv;
                    }
                    case F_LET:
                        sp -= 2;
                        e = k[sp];
                        env = mk3(K_ENV, k[sp + 1], v);
                        continue outer;
                    case F_PROJ:
                        v = mk2(k[--sp], v);
                        continue retv;
                    case F_RB:
                        depth = k[--sp];
                        break retv;
                    default:
                        throw new IllegalStateException();
                }
            }
            rb: for (;;) {
                int t;
                down: for (;;) {
                    if (sp + 8 > k.length) {
                        k = K = java.util.Arrays.copyOf(k, k.length * 2);
                    }
                    if (v < 0) {
                        t = mk2(K_TBVAR, depth + v);
                        break down;
                    }
                    int hdr = Mem.h(v);
                    switch (hdr & 15) {
                        case K_APP:
                            k[sp] = Mem.h(v + 2);
                            k[sp + 1] = depth;
                            k[sp + 2] = F_RB_APP_ARG;
                            sp += 3;
                            v = Mem.h(v + 1);
                            continue down;
                        case K_PROJ:
                            k[sp] = K_TPROJ | (hdr & ~15);
                            k[sp + 1] = F_RB_PROJ;
                            sp += 2;
                            v = Mem.h(v + 1);
                            continue down;
                        case K_LAME, K_PIE: {
                            int ce = Mem.h(v + 1), cenv = Mem.h(v + 2);
                            k[sp] = (hdr & 15) == K_LAME ? K_TLAM : K_TPI;
                            k[sp + 1] = ce;
                            k[sp + 2] = cenv;
                            k[sp + 3] = depth;
                            k[sp + 4] = F_RB_BODY;
                            k[sp + 5] = depth;
                            k[sp + 6] = F_RB;
                            sp += 7;
                            e = ea[ce];
                            env = cenv;
                            continue outer;
                        }
                        default:
                            t = v;
                            break down;
                    }
                }
                for (;;) {
                    switch (k[--sp]) {
                        case F_DONE:
                            return t;
                        case F_RB_APP_ARG: {
                            sp -= 2;
                            v = k[sp];
                            depth = k[sp + 1];
                            k[sp] = t;
                            k[sp + 1] = F_RB_APP_MK;
                            sp += 2;
                            continue rb;
                        }
                        case F_RB_APP_MK:
                            t = mk3(K_TAPP, k[--sp], t);
                            break;
                        case F_RB_PROJ:
                            t = mk2(k[--sp], t);
                            break;
                        case F_RB_BODY: {
                            sp -= 4;
                            int tk = k[sp], ce = k[sp + 1], cenv = k[sp + 2];
                            depth = k[sp + 3];
                            k[sp] = tk;
                            k[sp + 1] = t;
                            k[sp + 2] = F_RB_BIND_MK;
                            k[sp + 3] = depth + 1;
                            k[sp + 4] = F_RB;
                            sp += 5;
                            e = eb[ce];
                            env = mk3(K_ENV, cenv, -(depth + 1));
                            continue outer;
                        }
                        case F_RB_BIND_MK:
                            sp -= 2;
                            t = mk3(k[sp], k[sp + 1], t);
                            break;
                        default:
                            throw new IllegalStateException();
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ driver

    public static boolean run(String variant) {
        INTERN = Boolean.parseBoolean(System.getProperty("rlvm.intern", "true"));
        int passes = Integer.getInteger("rlvm.passes", 1);
        int limit = Integer.getInteger("rlvm.limit", nConst);
        if (variant.equals("none")) {
            System.out.println("RESULT exp2 variant=none");
            return true;
        }
        int which = variant.equals("rec") ? 0 : variant.equals("wl") ? 1 : variant.equals("wl2") ? 2 : -1;
        if (which < 0) {
            return false;
        }
        long[] ms = new long[passes];
        long cs = 0;
        for (int p = 0; p < passes; p++) {
            if (!INTERN) {
                hp = 8; // bump mode: reuse the arena for every pass
            }
            long t0 = System.nanoTime();
            cs = 0;
            for (int c = 0; c < limit; c++) {
                int t = which == 0 ? readback(eval(constType[c], 0), 0) : which == 1 ? normalizeWl(constType[c]) : normalizeWl2(constType[c]);
                cs = cs * 31 + t;
                if (!INTERN) {
                    hp = 8; // nothing survives a declaration
                }
            }
            ms[p] = (System.nanoTime() - t0) / 1_000_000;
        }
        System.out.printf(Locale.ROOT, "RESULT exp2 variant=%s intern=%b mem=%d types=%d pass_ms=%s checksum=%016x values=%d hp_words=%d table=%d kstack=%d%n", variant, INTERN, Mem.MODE,
                        limit, java.util.Arrays.toString(ms), cs, count, hp, tmask + 1, K.length);
        return true;
    }
}

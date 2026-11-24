package rlvm.spike.machine;

import static rlvm.spike.machine.Corpus.*;

import java.util.HashMap;

/**
 * Elaboration of a constant's type telescope into a code object (done once per constant, lazily).
 *
 * Frame of a code object with n parameters (relative to fp):
 * <pre>
 *   slots 0 .. n-1        arguments (pushed by the caller; only the first k are valid)
 *   slots n .. 2n-1       outputs: value of the domain of parameter i under the earlier arguments
 *   slot  2n              output: result type
 *   slots 2n+1 ..         temporaries
 * </pre>
 * Operand encoding: {@code op > 0} = immediate value id (pre-interned closed sub-term), {@code op <= 0} = slot -op.
 *
 * Code object layout in CODE (all blocks are straight-line):
 * <pre>
 *   [co]              n
 *   [co+1]            frame size (slots)
 *   [co+2 .. co+2+n]  start pc of the block computing domain i; entry n = end of the last domain block
 *   [co+3+n+j]        start pc of the result block for j supplied arguments (j = 0..n); entry n+1 = end
 * </pre>
 * Instructions (word 0 = opcode | dst slot << 8):
 * <pre>
 *   MOV  src                       APP1 f a      APP2 f a b      APP3 f a b c      APPN cnt f a1..acnt
 *   APPD cnt f a1..acnt            application whose head may be a closure at run time (parameter in head
 *                                  position, let-bound lambda, beta redex): checks, falls back to Generic
 *   PROJ hdr s                     hdr = K_PROJ | idx << 4
 *   CLO  hdr e c1..cn              hdr = K_LAM/K_PI | n << 4; captured operands in ascending de Bruijn order
 * </pre>
 */
public final class Elab {
    private Elab() {
    }

    public static final int OP_MOV = 0, OP_APP1 = 1, OP_APP2 = 2, OP_APP3 = 3, OP_APPN = 4, OP_APPD = 5, OP_PROJ = 6, OP_CLO = 7;
    public static final String[] OP_NAMES = {"MOV", "APP1", "APP2", "APP3", "APPN", "APPD", "PROJ", "CLO"};

    public static int[] CODE = new int[1 << 16];
    public static int cp = 1;
    /** Per constant: offset of its code object, 0 = not elaborated yet. */
    public static int[] codeStart;

    /**
     * Match mode (variants brec/bm): when set, every domain is additionally elaborated into a pattern, a pre-order
     * tree in PAT that {@link Stepper#match} walks over an EXISTING value (the type of the argument) without
     * building or hashing anything:
     * <pre>
     *   EQ op                                  the value is the operand's value (immediate or argument slot)
     *   APP cnt P_argcnt .. P_arg1 P_head      cnt nested APP cells
     *   APPEQ cnt op_cnt .. op_1 head          the same when every argument is an operand (the common case)
     *   PROJ hdr P                             CLO hdr e c1..cn (captures are operands)
     *   BUILD pcStart pcEnd slot               not a pattern (let, head that may be a closure): run that code, compare ids
     * </pre>
     */
    public static boolean PATTERNS;
    public static final int P_EQ = 0, P_APP = 1, P_PROJ = 2, P_CLO = 3, P_BUILD = 4, P_APPEQ = 5;
    public static final String[] P_NAMES = {"EQ", "APP", "PROJ", "CLO", "BUILD", "APPEQ"};
    public static int[] PAT = new int[1 << 16];
    public static int pp = 1;
    /** Per constant: index in PAT of a table with the pattern offset of each domain. */
    public static int[] patStart;
    public static final long[] patCount = new long[6];

    // statistics
    public static int elaborated, cseHits;
    public static final long[] opCount = new long[8];
    public static long immOperands, slotOperands;

    private static int n, nextTemp, scope, scopeCounter;
    private static int[] cenv = new int[256];
    private static boolean[] dyn = new boolean[256];
    private static int[] argStack = new int[256];
    private static int argTop;
    private static final HashMap<Long, Integer> cse = new HashMap<>();

    public static void init() {
        codeStart = new int[nConst];
        patStart = new int[nConst];
    }

    public static int elaborate(int c) {
        n = Workload.nparams[c];
        int co = cp;
        int hdr = 2 + (n + 1) + (n + 2);
        reserve(hdr);
        cp = co + hdr;
        nextTemp = 2 * n + 1;
        if (cenv.length < n + 64) {
            cenv = new int[n + 256];
        }
        for (int i = 0; i < n; i++) {
            cenv[i] = -i;
        }
        int e = constType[c];
        for (int i = 0; i < n; i++) {
            CODE[co + 2 + i] = cp;
            newScope();
            compileInto(ea[e], i, n + i);
            e = eb[e];
        }
        CODE[co + 2 + n] = cp;
        e = constType[c];
        for (int j = 0; j <= n; j++) {
            CODE[co + 3 + n + j] = cp;
            newScope();
            compileInto(e, j, 2 * n);
            if (j < n) {
                e = eb[e];
            }
        }
        CODE[co + 3 + n + n + 1] = cp;
        if (PATTERNS) {
            int tbl = pp;
            while (pp + n > PAT.length) {
                PAT = java.util.Arrays.copyOf(PAT, PAT.length * 2);
            }
            pp += n;
            e = constType[c];
            for (int i = 0; i < n; i++) {
                PAT[tbl + i] = pp;
                newScope();
                pattern(ea[e], i);
                e = eb[e];
            }
            patStart[c] = tbl;
        }
        CODE[co] = n;
        CODE[co + 1] = nextTemp;
        codeStart[c] = co;
        elaborated++;
        return co;
    }

    private static void newScope() {
        scope = ++scopeCounter;
        cse.clear();
    }

    private static void reserve(int words) {
        if (cp + words > CODE.length) {
            CODE = java.util.Arrays.copyOf(CODE, Math.max(CODE.length * 2, cp + words));
        }
    }

    private static void emit(int w) {
        if (cp == CODE.length) {
            CODE = java.util.Arrays.copyOf(CODE, CODE.length * 2);
        }
        CODE[cp++] = w;
    }

    private static void emitOp(int op) {
        emit(op);
        if (op > 0) {
            immOperands++;
        } else {
            slotOperands++;
        }
    }

    private static int newTemp(boolean dynamic) {
        int t = nextTemp++;
        if (t >= dyn.length) {
            dyn = java.util.Arrays.copyOf(dyn, t * 2);
        }
        dyn[t] = dynamic;
        return t;
    }

    /** May the operand hold a LAM closure at run time? */
    private static boolean dynamic(int op) {
        if (op > 0) {
            return Vals.kindOf(op) == Vals.K_LAM;
        }
        int s = -op;
        return s < n || (s > 2 * n && dyn[s]);
    }

    private static void compileInto(int e, int depth, int dst) {
        int op = compile(e, depth, dst);
        if (op != -dst) {
            opCount[OP_MOV]++;
            emit(OP_MOV | dst << 8);
            emitOp(op);
        }
    }

    /** Emits code for e under cenv[0..depth) and returns the operand holding its value. */
    private static int compile(int e, int depth, int want) {
        if (loose[e] == 0) {
            return Generic.closed(e);
        }
        byte kd = kind[e];
        if (kd == BVAR) {
            return cenv[depth - 1 - ea[e]];
        }
        long key = ((long) scope << 32) | e;
        Integer hit = cse.get(key);
        if (hit != null) {
            cseHits++;
            return hit;
        }
        int r;
        switch (kd) {
            case APP: {
                int h = e, na = 0;
                do {
                    if (argTop == argStack.length) {
                        argStack = java.util.Arrays.copyOf(argStack, argTop * 2);
                    }
                    argStack[argTop++] = eb[h];
                    h = ea[h];
                    na++;
                } while (kind[h] == APP && loose[h] != 0);
                int base = argTop - na;
                int head = compile(h, depth, -1);
                int[] ops = new int[na];
                for (int i = 0; i < na; i++) {
                    ops[i] = compile(argStack[base + na - 1 - i], depth, -1);
                }
                argTop = base;
                boolean d = dynamic(head);
                int dst = want >= 0 ? want : newTemp(d);
                int opc = d ? OP_APPD : na <= 3 ? OP_APP1 + na - 1 : OP_APPN;
                opCount[opc]++;
                emit(opc | dst << 8);
                if (opc == OP_APPN || opc == OP_APPD) {
                    emit(na);
                }
                emitOp(head);
                for (int i = 0; i < na; i++) {
                    emitOp(ops[i]);
                }
                r = -dst;
                break;
            }
            case LAM, PI: {
                long m = mask[e];
                int[] caps = new int[depth];
                int nc = 0;
                long lo = m & ~FAR;
                while (lo != 0) {
                    int j = Long.numberOfTrailingZeros(lo);
                    if (j >= depth) {
                        break;
                    }
                    lo &= lo - 1;
                    caps[nc++] = cenv[depth - 1 - j];
                }
                if (m < 0) {
                    for (int j = 63; j < depth; j++) {
                        caps[nc++] = cenv[depth - 1 - j];
                    }
                }
                int dst = want >= 0 ? want : newTemp(kd == LAM);
                opCount[OP_CLO]++;
                emit(OP_CLO | dst << 8);
                emit((kd == LAM ? Vals.K_LAM : Vals.K_PI) | nc << 4);
                emit(e);
                for (int i = 0; i < nc; i++) {
                    emitOp(caps[i]);
                }
                r = -dst;
                break;
            }
            case LET: {
                int v = compile(eb[e], depth, -1);
                if (depth + 1 >= cenv.length) {
                    cenv = java.util.Arrays.copyOf(cenv, cenv.length * 2);
                }
                int savedSlot = cenv[depth]; // may be the entry of a later parameter
                cenv[depth] = v;
                int savedScope = scope;
                HashMap<Long, Integer> saved = new HashMap<>(cse);
                newScope();
                r = compile(ec[e], depth + 1, -1);
                cenv[depth] = savedSlot;
                scope = savedScope;
                cse.clear();
                cse.putAll(saved);
                break;
            }
            case PROJ: {
                int s = compile(ec[e], depth, -1);
                int dst = want >= 0 ? want : newTemp(false);
                opCount[OP_PROJ]++;
                emit(OP_PROJ | dst << 8);
                emit(Vals.K_PROJ | eb[e] << 4);
                emitOp(s);
                r = -dst;
                break;
            }
            default:
                throw new IllegalStateException();
        }
        cse.put(key, r);
        return r;
    }

    // ------------------------------------------------------------------ match mode

    private static void pemit(int w) {
        if (pp == PAT.length) {
            PAT = java.util.Arrays.copyOf(PAT, PAT.length * 2);
        }
        PAT[pp++] = w;
    }

    private static void pattern(int e, int depth) {
        if (loose[e] == 0) {
            patCount[P_EQ]++;
            pemit(P_EQ);
            pemit(Generic.closed(e));
            return;
        }
        switch (kind[e]) {
            case BVAR:
                patCount[P_EQ]++;
                pemit(P_EQ);
                pemit(cenv[depth - 1 - ea[e]]);
                return;
            case APP: {
                int h = e, na = 0;
                do {
                    if (argTop == argStack.length) {
                        argStack = java.util.Arrays.copyOf(argStack, argTop * 2);
                    }
                    argStack[argTop++] = eb[h];
                    h = ea[h];
                    na++;
                } while (kind[h] == APP && loose[h] != 0);
                int base = argTop - na;
                if (loose[h] != 0 || Vals.kindOf(Generic.closed(h)) == Vals.K_LAM) {
                    argTop = base;
                    build(e, depth); // head is a parameter or a closure: beta may be needed
                    return;
                }
                boolean leaves = true;
                for (int i = 0; i < na; i++) {
                    int a = argStack[base + i];
                    leaves &= loose[a] == 0 || kind[a] == BVAR;
                }
                if (leaves) {
                    patCount[P_APPEQ]++;
                    pemit(P_APPEQ);
                    pemit(na);
                    for (int i = 0; i < na; i++) { // last argument first
                        int a = argStack[base + i];
                        pemit(loose[a] == 0 ? Generic.closed(a) : cenv[depth - 1 - ea[a]]);
                    }
                    pemit(Generic.closed(h));
                    argTop = base;
                    return;
                }
                patCount[P_APP]++;
                pemit(P_APP);
                pemit(na);
                for (int i = 0; i < na; i++) {
                    pattern(argStack[base + i], depth); // last argument first
                }
                argTop = base;
                patCount[P_EQ]++;
                pemit(P_EQ);
                pemit(Generic.closed(h));
                return;
            }
            case PROJ:
                patCount[P_PROJ]++;
                pemit(P_PROJ);
                pemit(Vals.K_PROJ | eb[e] << 4);
                pattern(ec[e], depth);
                return;
            case LAM, PI: {
                long m = mask[e];
                int[] caps = new int[depth];
                int nc = 0;
                long lo = m & ~FAR;
                while (lo != 0) {
                    int j = Long.numberOfTrailingZeros(lo);
                    if (j >= depth) {
                        break;
                    }
                    lo &= lo - 1;
                    caps[nc++] = cenv[depth - 1 - j];
                }
                if (m < 0) {
                    for (int j = 63; j < depth; j++) {
                        caps[nc++] = cenv[depth - 1 - j];
                    }
                }
                patCount[P_CLO]++;
                pemit(P_CLO);
                pemit((kind[e] == LAM ? Vals.K_LAM : Vals.K_PI) | nc << 4);
                pemit(e);
                for (int i = 0; i < nc; i++) {
                    pemit(caps[i]);
                }
                return;
            }
            default:
                build(e, depth);
        }
    }

    /** Fallback inside a pattern: ordinary code for e (placed after the code object's blocks), then an id comparison. */
    private static void build(int e, int depth) {
        int start = cp;
        int op = compile(e, depth, -1);
        if (op > 0 || -op < n) {
            patCount[P_EQ]++;
            pemit(P_EQ);
            pemit(op);
            return;
        }
        patCount[P_BUILD]++;
        pemit(P_BUILD);
        pemit(start);
        pemit(cp);
        pemit(-op);
    }
}

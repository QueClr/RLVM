package rlvm.spike.machine;

import static rlvm.spike.machine.Elab.*;

/** Variant (b): the plain-Java switch stepper for code objects (the ahead-of-time compiled base tier). */
public final class Stepper {
    private Stepper() {
    }

    public static void run(int co, int[] S, int fp, int k) {
        int[] code = CODE;
        int n = code[co];
        int j = k < n ? k : n;
        int sp = fp + code[co + 1];
        exec(code, code[co + 2], code[co + 2 + j], S, fp, sp);
        int r = co + 3 + n + j;
        exec(code, code[r], code[r + 1], S, fp, sp);
    }

    /** Result block only (match mode: the domains were checked against existing values instead). */
    public static void runResult(int co, int[] S, int fp, int k) {
        int[] code = CODE;
        int n = code[co];
        int j = k < n ? k : n;
        int r = co + 3 + n + j;
        exec(code, code[r], code[r + 1], S, fp, fp + code[co + 1]);
    }

    /**
     * Match mode: does the existing value v have the shape of the pattern at pat[pp] under the arguments at
     * S[fp..]? Reads arena words only; no hashing, no interning (except inside a BUILD fallback).
     * Returns the position after the pattern, or -1 on a mismatch (the caller then falls back to building the
     * domain and the full conversion check).
     */
    public static int match(int[] pat, int pp, int v, int[] S, int fp, int sp) {
        switch (pat[pp]) {
            case P_EQ:
                return v == val(pat[pp + 1], S, fp) ? pp + 2 : -1;
            case P_APPEQ: {
                int cnt = pat[pp + 1];
                pp += 2;
                for (int i = 0; i < cnt; i++) {
                    if (v <= 0 || Mem.h(v) != Vals.K_APP || Mem.h(v + 2) != val(pat[pp + i], S, fp)) {
                        return -1;
                    }
                    v = Mem.h(v + 1);
                }
                return v == pat[pp + cnt] ? pp + cnt + 1 : -1;
            }
            case P_APP: {
                int cnt = pat[pp + 1];
                pp += 2;
                for (int i = 0; i < cnt; i++) {
                    if (v <= 0 || Mem.h(v) != Vals.K_APP) {
                        return -1;
                    }
                    pp = match(pat, pp, Mem.h(v + 2), S, fp, sp);
                    if (pp < 0) {
                        return -1;
                    }
                    v = Mem.h(v + 1);
                }
                return match(pat, pp, v, S, fp, sp);
            }
            case P_PROJ:
                if (v <= 0 || Mem.h(v) != pat[pp + 1]) {
                    return -1;
                }
                return match(pat, pp + 2, Mem.h(v + 1), S, fp, sp);
            case P_CLO: {
                int hdr = pat[pp + 1];
                if (v <= 0 || Mem.h(v) != hdr || Mem.h(v + 1) != pat[pp + 2]) {
                    return -1;
                }
                int nc = hdr >>> 4;
                for (int i = 0; i < nc; i++) {
                    if (Mem.h(v + 2 + i) != val(pat[pp + 3 + i], S, fp)) {
                        return -1;
                    }
                }
                return pp + 3 + nc;
            }
            case P_BUILD:
                exec(CODE, pat[pp + 1], pat[pp + 2], S, fp, sp);
                return S[fp + pat[pp + 3]] == v ? pp + 4 : -1;
            default:
                throw new IllegalStateException("bad pattern");
        }
    }

    private static int val(int op, int[] S, int fp) {
        return op > 0 ? op : S[fp - op];
    }

    private static void exec(int[] code, int pc, int end, int[] S, int fp, int sp) {
        while (pc < end) {
            int w = code[pc];
            int dst = fp + (w >>> 8);
            switch (w & 0xff) {
                case OP_MOV:
                    S[dst] = val(code[pc + 1], S, fp);
                    pc += 2;
                    break;
                case OP_APP1:
                    S[dst] = Vals.mkApp(val(code[pc + 1], S, fp), val(code[pc + 2], S, fp));
                    pc += 3;
                    break;
                case OP_APP2: {
                    int f = Vals.mkApp(val(code[pc + 1], S, fp), val(code[pc + 2], S, fp));
                    S[dst] = Vals.mkApp(f, val(code[pc + 3], S, fp));
                    pc += 4;
                    break;
                }
                case OP_APP3: {
                    int f = Vals.mkApp(val(code[pc + 1], S, fp), val(code[pc + 2], S, fp));
                    f = Vals.mkApp(f, val(code[pc + 3], S, fp));
                    S[dst] = Vals.mkApp(f, val(code[pc + 4], S, fp));
                    pc += 5;
                    break;
                }
                case OP_APPN: {
                    int cnt = code[pc + 1];
                    int f = val(code[pc + 2], S, fp);
                    pc += 3;
                    for (int i = 0; i < cnt; i++) {
                        f = Vals.mkApp(f, val(code[pc + i], S, fp));
                    }
                    S[dst] = f;
                    pc += cnt;
                    break;
                }
                case OP_APPD: {
                    int cnt = code[pc + 1];
                    int f = val(code[pc + 2], S, fp);
                    pc += 3;
                    int i = 0;
                    for (; i < cnt; i++) {
                        int x = val(code[pc + i], S, fp);
                        if (f > 0 && Vals.kindOf(f) == Vals.K_LAM) {
                            f = Generic.applyLam(f, x, S, sp);
                        } else {
                            f = Vals.mkApp(f, x);
                            i++;
                            break;
                        }
                    }
                    for (; i < cnt; i++) {
                        f = Vals.mkApp(f, val(code[pc + i], S, fp));
                    }
                    S[dst] = f;
                    pc += cnt;
                    break;
                }
                case OP_PROJ:
                    S[dst] = Vals.mk2i(code[pc + 1], val(code[pc + 2], S, fp));
                    pc += 3;
                    break;
                case OP_CLO: {
                    int hdr = code[pc + 1];
                    int e = code[pc + 2];
                    int nc = hdr >>> 4;
                    pc += 3;
                    for (int i = 0; i < nc; i++) {
                        S[sp + i] = val(code[pc + i], S, fp);
                    }
                    S[dst] = Vals.mkN(hdr, e, S, sp, nc);
                    pc += nc;
                    break;
                }
                default:
                    throw new IllegalStateException("bad opcode");
            }
        }
    }
}

package rlvm.spike.machine;

import static rlvm.spike.machine.Elab.*;

/**
 * Variant (b2): the same code objects and the same semantics as {@link Stepper}, written the way the
 * ahead-of-time compiler needs it (see README, "calibration"): one method, the hash-consing table and the arena
 * held in locals (reloaded after an insert, which may replace them), the APP probe expanded in place instead of
 * a call chain Vals.mkApp -> Vals.find3 -> Mem.t/Mem.h over static fields. Inserts, closures, projections and
 * beta still go through the shared library. int[] arena access only.
 */
public final class Stepper2 {
    private Stepper2() {
    }

    public static void run(int co, int[] S, int fp, int k) {
        if (Mem.MODE != 0) {
            Stepper.run(co, S, fp, k);
            return;
        }
        final int[] code = CODE;
        final int n = code[co];
        final int j = k < n ? k : n;
        final int sp = fp + code[co + 1];
        final int r = co + 3 + n + j;
        int pc = code[co + 2];
        int end = code[co + 2 + j];
        int[] t = Mem.T, h = Mem.H;
        int tm = Vals.tmask;
        for (int part = 0; part < 2; part++) {
            while (pc < end) {
                int w = code[pc];
                int op = w & 0xff;
                int dst = fp + (w >>> 8);
                if (op >= OP_APP1 && op <= OP_APPD) {
                    int cnt, q;
                    if (op <= OP_APP3) {
                        cnt = op - OP_APP1 + 1;
                        q = pc + 1;
                    } else {
                        cnt = code[pc + 1];
                        q = pc + 2;
                    }
                    int f = code[q];
                    if (f <= 0) {
                        f = S[fp - f];
                    }
                    int i = 1;
                    if (op == OP_APPD) {
                        while (i <= cnt && f > 0 && (h[f] & 15) == Vals.K_LAM) {
                            int x = code[q + i];
                            if (x <= 0) {
                                x = S[fp - x];
                            }
                            f = Generic.applyLam(f, x, S, sp);
                            t = Mem.T;
                            h = Mem.H;
                            tm = Vals.tmask;
                            i++;
                        }
                    }
                    for (; i <= cnt; i++) {
                        int a = code[q + i];
                        if (a <= 0) {
                            a = S[fp - a];
                        }
                        int hh = f * 0x9E3779B1 + a; // Vals.hash3(K_APP, f, a)
                        hh = (hh ^ (hh >>> 15)) * 0x85EBCA77;
                        hh ^= Vals.K_APP * 0x27D4EB2F;
                        hh = (hh ^ (hh >>> 13)) * 0xC2B2AE3D;
                        hh ^= hh >>> 16;
                        int slot = hh & tm;
                        for (;;) {
                            int v = t[slot];
                            if (v == 0) {
                                f = Vals.ins3(slot, Vals.K_APP, f, a);
                                t = Mem.T;
                                h = Mem.H;
                                tm = Vals.tmask;
                                break;
                            }
                            if (h[v] == Vals.K_APP && h[v + 1] == f && h[v + 2] == a) {
                                f = v;
                                break;
                            }
                            slot = (slot + 1) & tm;
                        }
                    }
                    S[dst] = f;
                    pc = q + cnt + 1;
                    continue;
                }
                switch (op) {
                    case OP_MOV: {
                        int x = code[pc + 1];
                        S[dst] = x > 0 ? x : S[fp - x];
                        pc += 2;
                        break;
                    }
                    case OP_PROJ: {
                        int x = code[pc + 2];
                        S[dst] = Vals.mk2i(code[pc + 1], x > 0 ? x : S[fp - x]);
                        t = Mem.T;
                        h = Mem.H;
                        tm = Vals.tmask;
                        pc += 3;
                        break;
                    }
                    case OP_CLO: {
                        int hdr = code[pc + 1];
                        int e = code[pc + 2];
                        int nc = hdr >>> 4;
                        pc += 3;
                        for (int i = 0; i < nc; i++) {
                            int x = code[pc + i];
                            S[sp + i] = x > 0 ? x : S[fp - x];
                        }
                        S[dst] = Vals.mkN(hdr, e, S, sp, nc);
                        t = Mem.T;
                        h = Mem.H;
                        tm = Vals.tmask;
                        pc += nc;
                        break;
                    }
                    default:
                        throw new IllegalStateException("bad opcode");
                }
            }
            pc = code[r];
            end = code[r + 1];
        }
    }
}

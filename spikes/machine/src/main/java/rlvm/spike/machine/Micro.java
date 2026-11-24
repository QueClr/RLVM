package rlvm.spike.machine;

import static rlvm.spike.machine.Elab.*;

import java.util.Locale;

/**
 * Calibration tools (never part of a measured experiment):
 * <ul>
 * <li>{@code profile}: the dynamic instruction mix of variant (b) - which code-object instructions the
 * workload executes, computed by decoding the blocks every spine runs;</li>
 * <li>{@code micro WHAT N}: N executions of one primitive, to be measured twice (N and 2N) under perf so that
 * the difference gives instructions per operation ({@code loop}, {@code app-hit}, {@code app-hit-local},
 * {@code app-insert}, {@code clo2-hit}).</li>
 * </ul>
 */
public final class Micro {
    private Micro() {
    }

    public static void profile() {
        Instantiator b = new Instantiator.StepperInst();
        Exp1.drive(b); // elaborates every used constant
        long[] ops = new long[OP_NAMES.length];
        long instrs = 0, words = 0, probes = 0, imm = 0, slot = 0, blocks = 0, emptySpines = 0;
        int[] W = Workload.W;
        for (int p = 0; p < Workload.wlen;) {
            int c = W[p], k = W[p + 1];
            p += 2 + k;
            int co = codeStart[c];
            int n = CODE[co];
            int j = Math.min(k, n);
            long before = probes;
            for (int part = 0; part < 2; part++) {
                int pc = part == 0 ? CODE[co + 2] : CODE[co + 3 + n + j];
                int end = part == 0 ? CODE[co + 2 + j] : CODE[co + 4 + n + j];
                blocks += part == 0 ? j : 1;
                while (pc < end) {
                    int op = CODE[pc] & 0xff;
                    int len, nops, pr;
                    switch (op) {
                        case OP_MOV -> {
                            len = 2;
                            nops = 1;
                            pr = 0;
                        }
                        case OP_APP1, OP_APP2, OP_APP3 -> {
                            pr = op - OP_APP1 + 1;
                            nops = pr + 1;
                            len = 1 + nops;
                        }
                        case OP_APPN, OP_APPD -> {
                            pr = CODE[pc + 1];
                            nops = pr + 1;
                            len = 2 + nops;
                        }
                        case OP_PROJ -> {
                            len = 3;
                            nops = 1;
                            pr = 1;
                        }
                        default -> { // CLO
                            nops = CODE[pc + 1] >>> 4;
                            len = 3 + nops;
                            pr = 1;
                        }
                    }
                    for (int q = pc + len - nops; q < pc + len; q++) {
                        if (CODE[q] > 0) {
                            imm++;
                        } else {
                            slot++;
                        }
                    }
                    ops[op]++;
                    instrs++;
                    words += len;
                    probes += pr;
                    pc += len;
                }
            }
            if (probes == before) {
                emptySpines++;
            }
        }
        double s = Workload.nSpines;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ops.length; i++) {
            sb.append(String.format(Locale.ROOT, " %s=%.3f", OP_NAMES[i], ops[i] / s));
        }
        System.out.printf(Locale.ROOT,
                        "PROFILE per spine: blocks=%.2f instructions=%.2f code_words=%.2f probes=%.2f operands imm=%.2f slot=%.2f | by opcode:%s | spines that need no probe at all: %.1f%%%n",
                        blocks / s, instrs / s, words / s, probes / s, imm / s, slot / s, sb, 100.0 * emptySpines / s);
    }

    public static boolean micro(String what, int n) {
        final int M = 4096;
        long acc = 0;
        int[] S = Machine.S;
        switch (what) {
            case "loop":
                for (int i = 0; i < n; i++) {
                    int x = i & (M - 1);
                    acc += (-x - 1) ^ (-x - 7);
                }
                break;
            case "app-hit":
                for (int i = 0; i < M; i++) {
                    Vals.mkApp(-i - 1, -i - 7);
                }
                for (int i = 0; i < n; i++) {
                    int x = i & (M - 1);
                    acc += Vals.mkApp(-x - 1, -x - 7);
                }
                break;
            case "app-hit-local": {
                // The same probe written the way an AOT compiler likes it: arrays and mask in locals, hash and
                // probe loop in the caller's body, no call. (int[] access mode only.)
                for (int i = 0; i < M; i++) {
                    Vals.mkApp(-i - 1, -i - 7);
                }
                final int[] t = Mem.T, h = Mem.H;
                final int tm = Vals.tmask;
                for (int i = 0; i < n; i++) {
                    int x = i & (M - 1);
                    int f = -x - 1, a = -x - 7;
                    int hh = f * 0x9E3779B1 + a;
                    hh = (hh ^ (hh >>> 15)) * 0x85EBCA77;
                    hh ^= Vals.K_APP * 0x27D4EB2F;
                    hh = (hh ^ (hh >>> 13)) * 0xC2B2AE3D;
                    hh ^= hh >>> 16;
                    int j = hh & tm;
                    for (;;) {
                        int v = t[j];
                        if (v == 0) {
                            acc--;
                            break;
                        }
                        if (h[v] == Vals.K_APP && h[v + 1] == f && h[v + 2] == a) {
                            acc += v;
                            break;
                        }
                        j = (j + 1) & tm;
                    }
                }
                break;
            }
            case "app-insert":
                for (int i = 0; i < n; i++) {
                    acc += Vals.mkApp(-i - 1, -i - 7);
                }
                break;
            case "clo2-hit":
                for (int i = 0; i < n; i++) {
                    int x = i & (M - 1);
                    S[100] = -x - 1;
                    S[101] = -x - 7;
                    acc += Vals.mkN(Vals.K_PI | 2 << 4, 12345, S, 100, 2);
                }
                break;
            default:
                return false;
        }
        System.out.printf(Locale.ROOT, "RESULT micro %s n=%d acc=%d values=%d%n", what, n, acc, Vals.count);
        return true;
    }
}

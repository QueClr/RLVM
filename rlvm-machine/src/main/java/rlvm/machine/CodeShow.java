package rlvm.machine;

import rlvm.store.DeclKind;
import rlvm.store.DeclTable;
import rlvm.store.Kind;
import rlvm.store.Terms;

/**
 * Diagnostics printer of code objects (ISA 3.3, 3.7): the listing of a block in the notation of ISA 3.7.
 * Owner: M7. Never on a checking path (strings, allocation); used by the M7 listing tests and for
 * debugging the elaborator and the stepper.
 *
 * <pre>
 * BODY(instHAdd)  arity 2, LEAF, shape CTORBODY
 *    MKLAM r0, T(..), B1, [A0, A1] ; KCONST r1, HAdd.mk, {u_1,u_1,u_1} ; MKAPP r2, r1, A0, A0, A0, r0 ; RET r2
 *    B1 (2 applied):  KCONST r0, Add.add, {u_1} ; MKAPP r1, r0, C0, C1, A0, A1 ; RET r1
 * </pre>
 * Registers are printed relative to the arity ({@code r0} is the first register after the arguments),
 * labels as {@code L1, L2, ..} in the order of their targets, sub-blocks as {@code B1, B2, ..} in the
 * order of their first mention, a term operand as {@code T(#id)} or, with {@link #terms} set, its
 * S-expression cut to 40 characters.
 */
final class CodeShow {
    private CodeShow() {}

    private static final int PAY = Op.OPND_PAYLOAD_MASK;

    /** One listing in progress: the sub-blocks met so far, in order. */
    private static final class Ctx {
        final Machine m;
        final boolean terms;
        int[] subs = new int[8];
        int subN;

        Ctx(Machine m, boolean terms) {
            this.m = m;
            this.terms = terms;
        }

        int sub(int id) {
            for (int i = 0; i < subN; i++) {
                if (subs[i] == id) {
                    return i + 1;
                }
            }
            if (subN == subs.length) {
                subs = java.util.Arrays.copyOf(subs, subN << 1);
            }
            subs[subN++] = id;
            return subN;
        }
    }

    /**
     * Elaborates role {@code role} of constant {@code row} with a fresh elaborator state and prints it
     * with its sub-blocks (and, for role RULE, the blocks of the rules). "" if the role stays generic.
     * Nothing is published for the role itself; sub-blocks and rule blocks enter the directory as usual.
     */
    static String role(Machine m, int row, int role, boolean terms) {
        Elab.State s = new Elab.State();
        Elab.begin(s);
        int len = Elab.build(m, s, row, role);
        if (len == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        Ctx c = new Ctx(m, terms);
        int[] b = java.util.Arrays.copyOf(s.buf, len);
        if (role == Op.ROLE_RULE) {
            DeclTable decls = m.decls;
            int ne = Code.nentry(b[Op.BLK_HDR2]);
            int fixed = decls.recNumParams(row) + decls.recNumMotives(row) + decls.recNumMinors(row);
            for (int j = 0; j < ne; j++) {
                int from = b[Op.BLK_ENTRIES + j];
                sb.append("RULE(").append(name(m, row)).append(", ").append(name(m, decls.recRuleCtor(row, j))).append(")  ");
                if (from == 0) {
                    sb.append("generic\n");
                    continue;
                }
                int to = len;
                for (int k = 0; k < ne; k++) {
                    int e = b[Op.BLK_ENTRIES + k];
                    if (e > from && e < to) {
                        to = e;
                    }
                }
                sb.append("arity ").append(fixed + decls.recRuleFields(row, j)).append("\n   ");
                code(sb, c, b, 0, from, to);
                sb.append('\n');
            }
            subBlocks(sb, c);
            return sb.toString();
        }
        block(sb, c, b, 0, roleName(role) + "(" + name(m, row) + ")");
        subBlocks(sb, c);
        return sb.toString();
    }

    /** The listing of a published block with its sub-blocks. */
    static String published(Machine m, int blk, boolean terms) {
        CodeStore cs = m.engine.code;
        int[] b = cs.chunkOf(blk);
        if (b == null) {
            return "";
        }
        int o = cs.offsetOf(blk);
        StringBuilder sb = new StringBuilder();
        Ctx c = new Ctx(m, terms);
        int row = b[o + Op.BLK_ROW];
        block(sb, c, b, o, roleName(Code.role(b[o])) + "(" + (row > 0 ? name(m, row) : "#" + b[o + Op.BLK_FACTS]) + ")");
        subBlocks(sb, c);
        return sb.toString();
    }

    private static void subBlocks(StringBuilder sb, Ctx c) {
        CodeStore cs = c.m.engine.code;
        for (int i = 0; i < c.subN; i++) {
            int id = c.subs[i];
            int[] b = cs.chunkOf(id);
            int o = cs.offsetOf(id);
            int ne = Code.nentry(b[o + Op.BLK_HDR2]);
            int len = Code.len(b[o]);
            for (int e = 0; e < ne; e++) {
                int from = b[o + Op.BLK_ENTRIES + e];
                int to = e + 1 < ne ? b[o + Op.BLK_ENTRIES + e + 1] : len;
                sb.append("   B").append(i + 1).append(" (").append(e + 1).append(" applied):  ");
                code(sb, c, b, o, from, to);
                sb.append('\n');
            }
        }
    }

    private static String roleName(int role) {
        switch (role) {
            case Op.ROLE_TELE:
                return "TELE";
            case Op.ROLE_BODY:
                return "BODY";
            case Op.ROLE_RULE:
                return "RULE";
            case Op.ROLE_FIELDS:
                return "FIELDS";
            case Op.ROLE_CONG:
                return "CONG";
            case Op.ROLE_RULES:
                return "RULES";
            default:
                return "ROLE" + role;
        }
    }

    private static String shapeName(int shape) {
        switch (shape) {
            case Layout.SHAPE_PICK:
                return "PICK";
            case Layout.SHAPE_PROJARG:
                return "PROJARG";
            case Layout.SHAPE_CTORBODY:
                return "CTORBODY";
            case Layout.SHAPE_CASESON:
                return "CASESON";
            case Layout.SHAPE_MATCHER:
                return "MATCHER";
            default:
                return "";
        }
    }

    static String name(Machine m, int row) {
        return m.store.names().toString(m.decls.name(row));
    }

    /** Header line and code of one block. */
    private static void block(StringBuilder sb, Ctx c, int[] b, int o, String title) {
        int h0 = b[o];
        int h2 = b[o + Op.BLK_HDR2];
        int role = Code.role(h0);
        int flags = Code.flags(h2);
        int row = b[o + Op.BLK_ROW];
        sb.append(title).append("  ");
        if (role == Op.ROLE_CONG) {
            cong(sb, b, o);
            return;
        }
        sb.append(role == Op.ROLE_TELE ? "n " : "arity ").append(Code.arity(h2));
        if ((flags & Op.BLKF_LEAF) != 0) {
            sb.append(", LEAF");
        }
        String shape = shapeName(flags >>> Code.BLKF_SHAPE_SHIFT & 15);
        if (!shape.isEmpty()) {
            sb.append(", shape ").append(shape);
        }
        if (role == Op.ROLE_BODY && (flags & Code.BLKF_SUB) == 0 && row > 0) {
            unread(sb, c.m, c.m.decls.value(row), Code.arity(h2));
        }
        if (role == Op.ROLE_TELE) {
            reads(sb, c.m, c.m.decls.type(row), Code.arity(h2));
        }
        sb.append("\n   ");
        code(sb, c, b, o, Op.BLK_ENTRIES + Code.nentry(h2), Code.len(h0));
        sb.append('\n');
    }

    /** {@code unread {i, ..}}: the parameters the innermost body does not read (ISA 3.5). */
    private static void unread(StringBuilder sb, Machine m, int value, int arity) {
        Terms terms = m.terms;
        int body = value;
        for (int i = 0; i < arity && value > 0; i++) {
            body = terms.b(body);
        }
        if (value <= 0 || arity > 63) {
            return;
        }
        long mask = terms.fvMask(body);
        boolean any = false;
        for (int i = 0; i < arity; i++) {
            if ((mask >>> (arity - 1 - i) & 1) == 0) {
                sb.append(any ? "," : ", unread {").append(i);
                any = true;
            }
        }
        if (any) {
            sb.append('}');
        }
    }

    /** {@code reads 0b..}: bit i (from the right) = binder i of the type occurs in a later domain or in the result. */
    private static void reads(StringBuilder sb, Machine m, int type, int arity) {
        Terms terms = m.terms;
        StringBuilder bits = new StringBuilder();
        int t = type;
        for (int i = 0; i < arity; i++) {
            int body = terms.b(t);
            bits.insert(0, (terms.fvMask(body) & 1) != 0 ? '1' : '0');
            t = body;
        }
        sb.append(", reads 0b").append(bits);
    }

    private static void cong(StringBuilder sb, int[] b, int o) {
        int n = Cong.positions(b, o);
        int lam = Cong.lamArity(b, o);
        sb.append(n).append(" positions, lamArity ").append(lam);
        if (Cong.propStatic(b, o)) {
            sb.append(", PROP_STATIC");
        }
        sb.append("\n   ");
        for (int i = 0; i < n; i++) {
            int w = Cong.classWord(b, o, i);
            if (i > 0) {
                sb.append(" ; ");
            }
            sb.append(i).append(' ');
            switch (w & Layout.CONG_CLASS_MASK) {
                case Layout.CONG_TYPE:
                    sb.append("TYPE");
                    break;
                case Layout.CONG_PROOF:
                    sb.append("PROOF");
                    break;
                case Layout.CONG_PROOFL:
                    sb.append("PROOFL");
                    break;
                default:
                    sb.append("SAME");
                    break;
            }
            if ((w & Layout.CONG_UNREAD) != 0) {
                sb.append(" / UNREAD");
            }
            sb.append(" dep {");
            long dep = Cong.dep(b, o, i);
            boolean any = false;
            for (int j = 0; j < 64; j++) {
                if ((dep >>> j & 1) != 0) {
                    sb.append(any ? "," : "").append(j);
                    any = true;
                }
            }
            sb.append("} rank ").append(w >>> Layout.CONG_RANK_SHIFT & Layout.CONG_RANK_MASK);
        }
        sb.append('\n');
    }

    /** The instructions {@code b[o + from .. o + to)} on one line, separated by " ; ", jump targets labelled. */
    private static void code(StringBuilder sb, Ctx c, int[] b, int o, int from, int to) {
        // pass 1: label targets, numbered in the order of their pcs (over the whole block, so that the
        // numbering is the same whichever entry is printed)
        int h2 = b[o + Op.BLK_HDR2];
        int len = Code.len(b[o]);
        int start = Op.BLK_ENTRIES + Code.nentry(h2);
        boolean[] target = new boolean[len + 1];
        for (int pc = start; pc < len; pc += 1 + (b[o + pc] >>> 8 & 255)) {
            int w = b[o + pc];
            int n = w >>> 8 & 255;
            for (int i = 1; i <= n; i++) {
                int x = b[o + pc + i];
                if (x >>> Op.OPND_SHIFT == Op.MODE_B && !isBlockOperand(w >>> 24, i)) {
                    target[x & PAY] = true;
                }
            }
        }
        int[] label = new int[len + 1];
        int ln = 0;
        for (int pc = 0; pc <= len; pc++) {
            if (target[pc]) {
                label[pc] = ++ln;
            }
        }
        int arity = Code.arity(h2);
        boolean tele = Code.role(b[o]) == Op.ROLE_TELE;
        boolean first = true;
        for (int pc = from; pc < to; pc += 1 + (b[o + pc] >>> 8 & 255)) {
            if (!first) {
                sb.append(" ; ");
            }
            first = false;
            if (target[pc]) {
                sb.append('L').append(label[pc]).append(": ");
            }
            insn(sb, c, b, o + pc, arity, tele, label);
        }
        if (to <= len && target[to] && to == len) {
            sb.append(" ; L").append(label[to]).append(':');
        }
    }

    private static boolean isBlockOperand(int op, int i) {
        return (op == Op.MKLAM || op == Op.MKPI) && i == 2;
    }

    private static String opName(int op) {
        switch (op) {
            case Op.MKAPP:
                return "MKAPP";
            case Op.KCONST:
                return "KCONST";
            case Op.KSORT:
                return "KSORT";
            case Op.KLIT:
                return "KLIT";
            case Op.KVAL:
                return "KVAL";
            case Op.MKLAM:
                return "MKLAM";
            case Op.MKPI:
                return "MKPI";
            case Op.MKSUSP:
                return "MKSUSP";
            case Op.MKPROJ:
                return "MKPROJ";
            case Op.MOV:
                return "MOV";
            case Op.PEEK:
                return "PEEK";
            case Op.NSUCC:
                return "NSUCC";
            case Op.FIELDS:
                return "FIELDS";
            case Op.JMP:
                return "JMP";
            case Op.BKIND:
                return "BKIND";
            case Op.BEQ:
                return "BEQ";
            case Op.BNE:
                return "BNE";
            case Op.BUNREAD:
                return "BUNREAD";
            case Op.SWITCH:
                return "SWITCH";
            case Op.MATCH:
                return "MATCH";
            case Op.RET:
                return "RET";
            case Op.APPLY:
                return "APPLY";
            case Op.TAILAPPLY:
                return "TAILAPPLY";
            case Op.WHNF:
                return "WHNF";
            case Op.FORCE:
                return "FORCE";
            case Op.TARG:
                return "TARG";
            case Op.TRET:
                return "TRET";
            default:
                return "OP" + Integer.toHexString(op);
        }
    }

    private static void insn(StringBuilder sb, Ctx c, int[] b, int at, int arity, boolean tele, int[] label) {
        int w = b[at];
        int op = w >>> 24;
        int d = w >>> 16 & 255;
        int n = w >>> 8 & 255;
        int x = w & 255;
        String dst = d >= arity ? "r" + (d - arity) : (tele ? "g" : "A") + d;
        switch (op) {
            case Op.RET:
            case Op.TRET:
            case Op.TAILAPPLY:
            case Op.JMP:
                sb.append(opName(op)).append(' ');
                operands(sb, c, b, at, 1, n, arity, tele, label, op);
                return;
            case Op.BKIND:
                sb.append("BKIND ");
                operand(sb, c, b[at + 1], arity, tele, label, op, 1);
                sb.append((b[at + 2] & PAY) == Code.NEUTRAL_KINDS ? ", neutral, " : ", 0x" + Integer.toHexString(b[at + 2] & PAY) + ", ");
                operand(sb, c, b[at + 3], arity, tele, label, op, 3);
                return;
            case Op.BUNREAD:
                sb.append("BUNREAD ");
                operand(sb, c, b[at + 1], arity, tele, label, op, 1);
                sb.append(", ").append(b[at + 2] & PAY).append('/').append(b[at + 3] & PAY).append(", ").append(dst).append(", ");
                operand(sb, c, b[at + 4], arity, tele, label, op, 4);
                return;
            case Op.SWITCH:
            case Op.BEQ:
            case Op.BNE:
                sb.append(opName(op)).append(' ');
                operands(sb, c, b, at, 1, n, arity, tele, label, op);
                return;
            case Op.MATCH:
                sb.append("MATCH ");
                operand(sb, c, b[at + 1], arity, tele, label, op, 1);
                sb.append(", ");
                operand(sb, c, b[at + 2], arity, tele, label, op, 2);
                sb.append(", ");
                operand(sb, c, b[at + 4], arity, tele, label, op, 4);
                return;
            case Op.TARG:
                targ(sb, c, b, at, arity, label, d, x);
                return;
            case Op.MKLAM:
            case Op.MKPI:
            case Op.MKSUSP: {
                int first = op == Op.MKSUSP ? 2 : 3;
                sb.append(opName(op)).append(' ').append(dst).append(", ");
                operands(sb, c, b, at, 1, first - 1, arity, tele, label, op);
                sb.append(", [");
                operands(sb, c, b, at, first, n, arity, tele, label, op);
                sb.append(']');
                if (op == Op.MKSUSP && (x & Code.X_LET) != 0) {
                    sb.append(", LET");
                }
                return;
            }
            case Op.FIELDS:
                sb.append("FIELDS ").append(dst).append(", ");
                operand(sb, c, b[at + 1], arity, tele, label, op, 1);
                sb.append(", ").append(x);
                return;
            case Op.KVAL:
                sb.append("KVAL ").append(dst).append(", ");
                operand(sb, c, b[at + 1], arity, tele, label, op, 1);
                if ((x & Code.KV_ARGV) != 0) {
                    sb.append(", argv");
                }
                return;
            default:
                sb.append(opName(op)).append(' ').append(dst);
                if (n > 0) {
                    sb.append(", ");
                }
                operands(sb, c, b, at, 1, n, arity, tele, label, op);
                return;
        }
    }

    private static void targ(StringBuilder sb, Ctx c, int[] b, int at, int arity, int[] label, int i, int x) {
        switch (x & Code.PH_MASK) {
            case Code.PH_INFER:
                sb.append("TARG ").append(i).append((x & Code.X_READ) != 0 ? "" : " unread");
                return;
            case Code.PH_CONV:
                sb.append("TCONV ").append(i).append(", ");
                operand(sb, c, b[at + 1], arity, true, label, Op.TARG, 1);
                return;
            case Code.PH_BIND:
                sb.append("TBIND ").append(i);
                return;
            default:
                sb.append("TEXPECT ");
                operand(sb, c, b[at + 1], arity, true, label, Op.TARG, 1);
                return;
        }
    }

    private static void operands(StringBuilder sb, Ctx c, int[] b, int at, int from, int to, int arity, boolean tele,
            int[] label, int op) {
        for (int i = from; i <= to; i++) {
            if (i > from) {
                sb.append(", ");
            }
            operand(sb, c, b[at + i], arity, tele, label, op, i);
        }
    }

    private static void operand(StringBuilder sb, Ctx c, int x, int arity, boolean tele, int[] label, int op, int i) {
        int p = x & PAY;
        Machine m = c.m;
        switch (x >>> Op.OPND_SHIFT) {
            case Op.MODE_R:
                if (p >= arity) {
                    sb.append('r').append(p - arity);
                } else {
                    sb.append(tele ? "g" : "A").append(p);
                }
                return;
            case Op.MODE_A:
                sb.append(tele ? "g" : "A").append(p);
                return;
            case Op.MODE_C:
                sb.append('C').append(p);
                return;
            case Op.MODE_I:
                sb.append(p);
                return;
            case Op.MODE_V:
                sb.append(p == Layout.HOLE ? "_" : "NONE");
                return;
            case Op.MODE_K:
            case Op.MODE_T:
                sb.append(x >>> Op.OPND_SHIFT == Op.MODE_K ? "K(" : "T(");
                if (c.terms) {
                    String t = Show.term(m, p);
                    sb.append(t.length() > 40 ? t.substring(0, 38) + ".." : t);
                } else {
                    sb.append(kindName(m.terms.kind(p)));
                }
                sb.append(')');
                return;
            case Op.MODE_L:
                sb.append(m.levels.toString(p));
                return;
            case Op.MODE_LL: {
                sb.append('{');
                int n = m.lists.length(p);
                for (int k = 0; k < n; k++) {
                    sb.append(k > 0 ? "," : "").append(m.levels.toString(m.lists.get(p, k)));
                }
                sb.append('}');
                return;
            }
            case Op.MODE_D:
                sb.append(p > 0 && p <= m.decls.constantCount() ? name(m, p) : "D" + p);
                return;
            case Op.MODE_B:
                if (isBlockOperand(op, i)) {
                    sb.append(p == 0 ? "-" : "B" + c.sub(p));
                } else {
                    sb.append('L').append(p < label.length ? label[p] : -1);
                }
                return;
            default:
                sb.append("?").append(Integer.toHexString(x));
                return;
        }
    }

    private static String kindName(int kind) {
        switch (kind) {
            case Kind.LAM:
                return "fun";
            case Kind.PI:
                return "pi";
            case Kind.APP:
                return "app";
            case Kind.LET:
                return "let";
            case Kind.PROJ:
                return "proj";
            case Kind.NATLIT:
            case Kind.STRLIT:
                return "lit";
            default:
                return "term";
        }
    }

    /** True if constant {@code row} is a definition or theorem whose value is kept as a proof block (never elaborated, ISA 3.3). */
    static boolean blockValue(Machine m, int row) {
        int k = m.decls.kind(row);
        return (k == DeclKind.DEFN || k == DeclKind.THM || k == DeclKind.OPAQUE) && m.decls.value(row) < 0;
    }
}

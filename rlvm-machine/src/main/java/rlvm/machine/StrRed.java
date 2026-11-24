package rlvm.machine;

import static rlvm.machine.Op.SIG;

import rlvm.api.MachineErrors;
import rlvm.store.IntLists;
import rlvm.store.LitPool;
import rlvm.store.Specials;

/**
 * String literals (ISA 2.9, 4.3): {@code STRVIEW}. Owner: M3.
 */
final class StrRed {
    private StrRed() {}

    /**
     * STRVIEW(s) (ISA 4.3): the constructor form {@code String.ofList (List.cons.{0} Char (Char.ofNat c) ..)} of a STR
     * record, one element per Unicode scalar, built iteratively and memoised in CTORF. Official:
     * {@code string_lit_to_constructor} (R02 7.6).
     * @return the value, or FATAL (E_PRELUDE, E_MEM)
     */
    static int strView(Machine m, int s) {
        int v = m.vh[(s << Layout.REC_SHIFT) + Layout.M_CTORF];
        return v != 0 ? v : strViewSlow(m, s);
    }

    /**
     * Builds {@code String.ofList (List.cons.{0} Char (Char.ofNat (lit c1)) (.. (List.nil.{0} Char)))} from the last
     * scalar value to the first (R02 7.6, section 10 row 20: scalar values, not bytes), in a loop: no judgment is
     * called, the applications are folded neutrals. The five constants must be declared with VALID guards
     * (ISA 2.9): otherwise fault(E_PRELUDE), as the reference kernel declines.
     */
    private static int strViewSlow(Machine m, int s) {
        if (s <= Layout.HOLE || (m.vh[s << Layout.REC_SHIFT] >>> Layout.KIND_SHIFT) != Layout.STR) {
            return m.fault(MachineErrors.E_INTERNAL);
        }
        int zl = zeroList(m);
        int charT = special(m, Specials.CHAR, IntLists.EMPTY);
        int ofNat = special(m, Specials.CHAR_OF_NAT, IntLists.EMPTY);
        int nil = special(m, Specials.LIST_NIL, zl);
        int cons = special(m, Specials.LIST_CONS, zl);
        int ofList = special(m, Specials.STRING_OF_LIST, IntLists.EMPTY);
        if (charT >= SIG || ofNat >= SIG || nil >= SIG || cons >= SIG || ofList >= SIG) {
            return m.err == MachineErrors.E_PRELUDE ? m.fault(MachineErrors.E_PRELUDE) : Math.max(charT, Math.max(ofNat, Math.max(nil, Math.max(cons, ofList))));
        }
        if (!m.ensureScratch(3)) {
            return m.fault(MachineErrors.E_MEM);
        }
        LitPool lits = m.lits;
        int idx = m.vh[(s << Layout.REC_SHIFT) + Layout.A];
        int xb = m.xp;
        m.xp = xb + 3;
        m.X[xb] = charT;
        int r = Values.mkApp(m, nil, xb, 1);
        for (int i = lits.stringCodePointCount(idx) - 1; i >= 0 && r < SIG; i--) {
            m.X[xb] = Layout.imm(lits.stringCodePointAt(idx, i));
            int c = Values.mkApp(m, ofNat, xb, 1);
            if (c >= SIG) {
                r = c;
                break;
            }
            int[] x = m.X;
            x[xb] = charT;
            x[xb + 1] = c;
            x[xb + 2] = r;
            r = Values.mkApp(m, cons, xb, 3);
        }
        if (r < SIG) {
            m.X[xb] = r;
            r = Values.mkApp(m, ofList, xb, 1);
        }
        m.xp = xb;
        if (r < SIG) {
            m.vh[(s << Layout.REC_SHIFT) + Layout.M_CTORF] = r;
        }
        return r;
    }

    /** The CONST record of special {@code which} at the given levels; FATAL (E_PRELUDE) unless it is declared and VALID. */
    private static int special(Machine m, int which, int levelList) {
        Specials sp = m.specials;
        int cid = sp.cid(which);
        if (cid == 0 || sp.state(which) != Specials.VALID) {
            return m.fault(MachineErrors.E_PRELUDE);
        }
        return Values.mkConst(m, cid, levelList);
    }

    /** The level list {@code [0]} of the worker's list store, made once per session ({@code Reduce.Ext}). */
    private static int zeroList(Machine m) {
        int zl = m.x3.zeroList;
        if (zl == 0) {
            int[] one = {m.levels.zero()};
            zl = m.lists.intern(one, 0, 1);
            m.x3.zeroList = zl;
        }
        return zl;
    }
}

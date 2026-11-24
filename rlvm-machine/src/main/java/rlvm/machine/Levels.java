package rlvm.machine;

import static rlvm.machine.Op.SIG;

import rlvm.api.LevelOps;
import rlvm.api.MachineErrors;
import rlvm.store.IntLists;

/**
 * Universe levels in the machine (ISA 2.7): substitution ids, {@code LSUB}, {@code LVLEQ}, the import
 * cache {@code LIMP}. Owner: M1.
 *
 * <p>{@code ls} is a substitution id of {@code rlvm-level}: {@code mkSubst(lparams(row), levels)}; 0 is the
 * identity and the empty substitution; -1 stands for a level count that is not the row's (word c of such a
 * CONST record). Level, list and substitution ids are positive ints below {@code Op.SIG}, so an operation
 * here can return a signal in their place.
 */
final class Levels {
    private Levels() {}

    private static final int LSUB_MASK = (1 << Layout.LSUB_LOG2) - 1;
    private static final int LIMP_MASK = (1 << Layout.LIMP_LOG2) - 1;

    /**
     * The substitution id of a CONST record: its word c = {@code mkSubst(lparams(row), levels)} (ISA 2.7, B1);
     * -1 if the record has another number of levels than its row has parameters.
     */
    static int substOf(Machine m, int constRef) {
        return m.vh[(constRef << Layout.REC_SHIFT) + Layout.C];
    }

    /**
     * LSUB(l, ls) (ISA 2.7): {@code LevelOps.instantiate(level, ls)} behind the direct-mapped cache; ls = 0 is the identity.
     * Official: {@code instantiate} of a level with the simplifying {@code mk_max} / {@code mk_imax} (R02 3.3).
     * @return the level id; ABORT (E_LEVEL_ARITY) for ls = -1
     */
    static int lsub(Machine m, int level, int ls) {
        if (ls == 0) {
            return level;
        }
        int[] c = m.lsub;
        int i = (Hash.cell(level, ls) & LSUB_MASK) << Layout.CELL_SHIFT;
        if (c[i + Layout.LSUB_KEY] == level && c[i + Layout.LSUB_LS] == ls && c[i + Layout.LSUB_EPOCH] == m.epoch) {
            return c[i + Layout.LSUB_VALUE];
        }
        if (ls < 0) {
            return m.fail(MachineErrors.E_LEVEL_ARITY);
        }
        int r = m.levels.instantiate(level, ls);
        c[i + Layout.LSUB_KEY] = level;
        c[i + Layout.LSUB_LS] = ls;
        c[i + Layout.LSUB_EPOCH] = m.epoch;
        c[i + Layout.LSUB_VALUE] = r;
        return r;
    }

    /**
     * LSUB on a level list (ISA 2.7): {@code LevelOps.instantiateList(list, ls)} behind its cache.
     * @return the list id; ABORT (E_LEVEL_ARITY) for ls = -1
     */
    static int lsubList(Machine m, int list, int ls) {
        if (ls == 0 || list == 0) {
            return list;
        }
        int[] c = m.lsubList;
        int i = (Hash.cell(list, ls) & LSUB_MASK) << Layout.CELL_SHIFT;
        if (c[i + Layout.LSUB_KEY] == list && c[i + Layout.LSUB_LS] == ls && c[i + Layout.LSUB_EPOCH] == m.epoch) {
            return c[i + Layout.LSUB_VALUE];
        }
        if (ls < 0) {
            return m.fail(MachineErrors.E_LEVEL_ARITY);
        }
        int r = m.levels.instantiateList(list, ls);
        c[i + Layout.LSUB_KEY] = list;
        c[i + Layout.LSUB_LS] = ls;
        c[i + Layout.LSUB_EPOCH] = m.epoch;
        c[i + Layout.LSUB_VALUE] = r;
        return r;
    }

    /**
     * LVLEQ on two levels (ISA 4.4): equal ids, else the official {@code is_equivalent}
     * ({@code l1 == l2 || normalize(l1) == normalize(l2)}, level.cpp; R02 3.4-3.6): identity of the official
     * normal forms, sound and incomplete. Never by id alone.
     *
     * <p>Owner decision of 2026-11-24 (OWNER-NOTES item 11): levels are compared as the official kernel
     * compares them by default, in both engines; the complete relation of {@code rlvm-level}'s canonical forms
     * ({@code LevelOps.isEquiv}, a superset) only with the switch {@code levels-complete}
     * ({@code Op.SW_LEVELS_COMPLETE}; never in strict mode). This mirrors {@code RefKernel.levelEquiv}. The two
     * callers are the official ones: two sorts, and the level lists of two references to one constant
     * ({@link #listsEq}).
     */
    static boolean lvlEq(Machine m, int a, int b) {
        if (a == b) {
            return true;
        }
        return (m.sw & Op.SW_LEVELS_COMPLETE) != 0 ? m.levels.isEquiv(a, b) : m.levels.isEquivOfficial(a, b);
    }

    /**
     * LVLEQ on two level lists: equal length and pairwise {@link #lvlEq} (official {@code is_def_eq(levels,
     * levels)}; {@code RefKernel.levelsEquiv}).
     */
    static boolean listsEq(Machine m, int a, int b) {
        if (a == b) {
            return true;
        }
        IntLists ls = m.lists;
        int na = a == IntLists.EMPTY ? 0 : ls.length(a);
        int nb = b == IntLists.EMPTY ? 0 : ls.length(b);
        if (na != nb) {
            return false;
        }
        if ((m.sw & Op.SW_LEVELS_COMPLETE) != 0) {
            return m.levels.listsEquiv(a, b);
        }
        LevelOps lv = m.levels;
        for (int i = 0; i < na; i++) {
            int x = ls.get(a, i);
            int y = ls.get(b, i);
            if (x != y && !lv.isEquivOfficial(x, y)) {
                return false;
            }
        }
        return true;
    }

    // ---------------------------------------------------------------------------------------------------
    // Structural levels in published records (ISA 3.3; staging step 2)
    // ---------------------------------------------------------------------------------------------------

    /**
     * Writes the postfix encoding of {@code level} (ISA 3.3: {@code ZERO | SUCC | MAX | IMAX | PARAM nameId}) to
     * {@code dst[at ..]}: first a word with the number of words that follow, then the encoding. It mentions no
     * level id, so it may be published (RECONCILIATION B2). Iterative: an explicit stack in scratch X.
     * @return the number of words written (length word included), or -1 if {@code dst} is too short or
     *     scratch is exhausted
     */
    static int encodeLevel(Machine m, int level, int[] dst, int at) {
        return encodeLevelSlow(m, level, dst, at);
    }

    private static int encodeLevelSlow(Machine m, int level, int[] dst, int at) {
        LevelOps lv = m.levels;
        int xb = m.xp;
        if (!m.ensureScratch(2)) {
            return -1;
        }
        // X holds pending work items: a level id to visit (positive) or an operator to emit (~opcode).
        m.X[m.xp++] = level;
        int out = at + 1;
        while (m.xp > xb) {
            int w = m.X[--m.xp];
            if (out + 2 > dst.length) {
                m.xp = xb;
                return -1;
            }
            if (w < 0) {
                dst[out++] = ~w;
                continue;
            }
            int k = lv.kind(w);
            if (k == LevelOps.KIND_ZERO) {
                dst[out++] = Layout.LV_ZERO;
            } else if (k == LevelOps.KIND_PARAM) {
                dst[out++] = Layout.LV_PARAM;
                dst[out++] = lv.paramName(w);
            } else {
                if (!m.ensureScratch(3)) {
                    m.xp = xb;
                    return -1;
                }
                int[] x = m.X;
                if (k == LevelOps.KIND_SUCC) {
                    x[m.xp++] = ~Layout.LV_SUCC;
                    x[m.xp++] = lv.succOf(w);
                } else {
                    x[m.xp++] = ~(k == LevelOps.KIND_MAX ? Layout.LV_MAX : Layout.LV_IMAX);
                    x[m.xp++] = lv.rhs(w);
                    x[m.xp++] = lv.lhs(w);
                }
            }
        }
        dst[at] = out - at - 1;
        return out - at;
    }

    /**
     * The session level of the encoding written by {@link #encodeLevel} at {@code src[at ..]}, built with the
     * plain constructors (the encoding is the level's structure, not a normal form).
     * @return the level id, or FATAL (E_INTERNAL: malformed encoding; E_MEM)
     */
    static int decodeLevel(Machine m, int[] src, int at) {
        return decodeLevelSlow(m, src, at);
    }

    private static int decodeLevelSlow(Machine m, int[] src, int at) {
        LevelOps lv = m.levels;
        int n = src[at];
        int xb = m.xp;
        if (!m.ensureScratch(n + 1)) {
            return m.fault(MachineErrors.E_MEM);
        }
        int[] x = m.X;
        int sp = xb;
        for (int i = at + 1, end = at + 1 + n; i < end; i++) {
            switch (src[i]) {
                case Layout.LV_ZERO:
                    x[sp++] = lv.zero();
                    break;
                case Layout.LV_PARAM:
                    if (++i >= end) {
                        return m.fault(MachineErrors.E_INTERNAL);
                    }
                    x[sp++] = lv.param(src[i]);
                    break;
                case Layout.LV_SUCC:
                    if (sp - xb < 1) {
                        return m.fault(MachineErrors.E_INTERNAL);
                    }
                    x[sp - 1] = lv.succ(x[sp - 1]);
                    break;
                case Layout.LV_MAX:
                case Layout.LV_IMAX:
                    if (sp - xb < 2) {
                        return m.fault(MachineErrors.E_INTERNAL);
                    }
                    sp--;
                    x[sp - 1] = src[i] == Layout.LV_MAX ? lv.max(x[sp - 1], x[sp]) : lv.imax(x[sp - 1], x[sp]);
                    break;
                default:
                    return m.fault(MachineErrors.E_INTERNAL);
            }
        }
        return sp - xb == 1 ? x[xb] : m.fault(MachineErrors.E_INTERNAL);
    }

    /**
     * The session level id of a level ref stored in a fact or code record (ISA 3.3 publication rule): BASE ids as
     * they are, POOL encodings re-interned through the import cache LIMP.
     *
     * <p>Staging step 2. {@code record} is the index of a fact record in the engine's {@code FactStore};
     * {@code slot} is the word offset, inside that record, of the level ref (ISA 3.5: {@code 0 = UNKNOWN |
     * BASE + base level id | POOL + offset}); a POOL offset is relative to the record's first word and points to
     * an encoding written by {@link #encodeLevel}.
     * @return the level id, 0 for UNKNOWN, or FATAL (E_INTERNAL)
     */
    static int importLevel(Machine m, int record, int slot) {
        int[] c = m.limp;
        int i = (Hash.cell(record, slot) & LIMP_MASK) << Layout.CELL_SHIFT;
        if (c[i + Layout.LIMP_RECORD] == record && c[i + Layout.LIMP_SLOT] == slot && c[i + Layout.LIMP_EPOCH] == m.epoch) {
            return c[i + Layout.LIMP_VALUE];
        }
        return importLevelSlow(m, record, slot, i);
    }

    private static int importLevelSlow(Machine m, int record, int slot, int cell) {
        if (m.engine == null || record <= 0) {
            return m.fault(MachineErrors.E_INTERNAL);
        }
        int[] chunk = m.engine.facts.chunkOf(record);
        int base = m.engine.facts.offsetOf(record);
        int ref = chunk[base + slot];
        int r;
        switch (ref & Layout.LREF_TAG_MASK) {
            case Layout.LREF_BASE:
                r = ref & Layout.LREF_PAYLOAD_MASK;
                break;
            case Layout.LREF_POOL:
                r = decodeLevel(m, chunk, base + (ref & Layout.LREF_PAYLOAD_MASK));
                break;
            default:
                r = ref == Layout.LREF_UNKNOWN ? 0 : m.fault(MachineErrors.E_INTERNAL);
                break;
        }
        if (r < SIG) {
            int[] c = m.limp;
            c[cell + Layout.LIMP_RECORD] = record;
            c[cell + Layout.LIMP_SLOT] = slot;
            c[cell + Layout.LIMP_EPOCH] = m.epoch;
            c[cell + Layout.LIMP_VALUE] = r;
        }
        return r;
    }
}

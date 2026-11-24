package rlvm.machine;

import static rlvm.machine.Op.SIG;

import java.util.Arrays;
import rlvm.api.MachineErrors;
import rlvm.nat.Nat;
import rlvm.nat.NatOp;
import rlvm.nat.NatStore;
import rlvm.store.DeclKind;
import rlvm.store.DeclTable;
import rlvm.store.IntLists;
import rlvm.store.Specials;

/**
 * Value records and interning (ISA 2.1-2.6): the constructors of every kind with their canonical forms,
 * flags and tags, {@code CHEAD}, the sort cache. Owner: M1.
 *
 * <p>Table format and hash functions are frozen in {@link Layout} and {@link Hash}; {@link Machine} owns
 * the arrays, growth and reset ({@code addRecordAt}, {@code internRecord} is the reference probe).
 * Invariants I-ID, I-FLAG, I-SESSION of ISA 2.5 are this class's responsibility.
 *
 * <h2>Identity keys (ISA 2.5), as implemented</h2>
 * <pre>
 * SORT   (level)                          LAM / PI / PII  (term, frame run, ls')      ls' = 0 if the term has no
 * NBIG   aux 0: (low 32, high 31)         SUSP            aux 0: (term, frame, ls')   level parameter (2.7)
 *        aux 1: (NatStore id, hash, limbs)                aux 1 = LET: a different record (NOTES-M1 D1)
 * STR    (LitPool string index)           FVAR            (type, k)
 * CONST  aux, (row, level list, subst)    NEU             (head, spine length, argument refs); c = Hash.neu
 * PROJ   (struct value, structure row, field index)       NSUC  (base, k)
 * </pre>
 * Flags are functions of the key (I-FLAG): OPEN is the "or" over the components (frames carry it in their
 * header, {@link Runs#RUN_OPEN}), WHNF / ACTIVE / HCLASS come from the head. No constructor reduces.
 *
 * <p>Every constructor returns a value ref or a signal ({@code >= Op.SIG}; never SUSP); none throws for a
 * property of its operands. A signal passed in as a component (a frame, a level, a level list that a
 * failed operation returned) is passed through, so constructors compose without intermediate tests.
 */
final class Values {
    private Values() {}

    /** The flags an application inherits from its head, or from the NEU it extends (ISA 4.2 MKAPP). */
    static final int W0_INHERIT = Layout.W0_OPEN | Layout.W0_WHNF | Layout.W0_ACTIVE
            | (Layout.FL_HCLASS_MASK << Layout.FLAG_SHIFT);
    private static final int W0_HCLASS_SHIFT = Layout.FLAG_SHIFT + Layout.HCLASS_SHIFT;
    /** aux of a SUSP made by a {@code let} (the flag LET is a function of identity, I-FLAG). */
    static final int SUSP_LET = 1;
    /** Copies of at most this many words are loops; longer ones {@code System.arraycopy} (NOTES-M1 section 5). */
    static final int SMALL_COPY = 8;

    /**
     * Session state private to this package (caches, counters, scratch of its own): add fields here
     * instead of asking for fields in {@link Machine}. One instance per machine, field {@code m.x1}.
     */
    static final class Ext {
        static final int SORT_SLOTS = 64;
        static final int CHEAD_LOG2 = 12;
        static final int LIT_SLOTS = 64;
        /** Sort cache (ISA 2.5): 64 pairs (level id, SORT record), direct-mapped by the level id. */
        final int[] sorts = new int[2 * SORT_SLOTS];
        /** CHEAD (ISA 1.5): cells (row, level list, epoch, CONST record), direct-mapped, stamped. */
        final int[] chead = new int[Layout.CELL_WORDS << CHEAD_LOG2];
        /** KLIT import cache (ISA 2.9): pairs (shared LitPool natural index, value ref). */
        final int[] litImp = new int[2 * LIT_SLOTS];
        /** Limb scratch of the literal import. */
        long[] limbs = new long[8];
        /** Never written ({@code Values.closureAtSlow}). */
        int pin;
        /**
         * The OPEN flags of the records this class made, two bits per record (bit 0: known, bit 1: OPEN), 32
         * records per word: {@link Values#openOf}. A copy of flag bit OPEN that is 128 times denser than the
         * records, so that the "or over the components" of a new frame or application stays in the first
         * cache levels (NOTES-M1 section 3). Records made by other code have no entry and are read from vh.
         */
        long[] openBits = new long[1 << 10];
        /**
         * Words of {@link #openBits} that may be non-zero: [openLo, openTop]. Records are appended, so the word of
         * the last record made is the highest one; the lowest is the word of the session's first record.
         */
        int openLo;
        int openTop = -1;
        static final int SEEN_SLOTS = 256;
        /**
         * The content hashes of the frames that {@code closureAt} looked up last, direct-mapped: a prediction only
         * ("this frame exists, and its table slots are in the cache"), never an answer. Not cleared: a stale
         * word costs one early load or saves none.
         */
        final int[] seen = new int[SEEN_SLOTS];
        static final int REST_SLOTS = 64;
        /** REST chain cache of {@code Runs.restOf}: cells (eb, n, epoch, chain), direct-mapped, verified by content. */
        final int[] restCache = new int[Layout.CELL_WORDS * REST_SLOTS];

        /** Session reset (ISA 1.6): forget everything, in O(used) (three fixed arrays of 128 words; CHEAD by epoch). */
        void reset(Machine m) {
            Arrays.fill(sorts, 0);
            Arrays.fill(litImp, 0);
            if (m.epoch == 1) {
                Arrays.fill(chead, 0); // the epoch counter wrapped: stale stamps could match again
                Arrays.fill(restCache, 0);
            }
            if (openTop >= openLo) {
                Arrays.fill(openBits, openLo, Math.min(openTop + 1, openBits.length), 0L); // O(used / 32)
            }
            if (openBits.length > 1 << 16) {
                openBits = new long[1 << 10]; // back to the base size after a large session
            }
            openLo = Math.min(m.vbase >>> 5, openBits.length); // Machine.reset has set the next session's first record
            openTop = -1;
        }

        /** Records the OPEN flag of the new record {@code r} (made with {@code w0}); records are appended (r grows). */
        void mark(int r, int w0) {
            int w = r >>> 5;
            if (w >= openBits.length) {
                openBits = Arrays.copyOf(openBits, Math.max(openBits.length << 1, w + 1));
            }
            // FLAG_SHIFT is 16 and OPEN is flag bit 0: (w0 >>> 15) & 2 is 2 for an OPEN record
            openBits[w] |= (1L | (w0 >>> (Layout.FLAG_SHIFT - 1) & 2)) << ((r & 31) << 1);
            openTop = w;
        }

        /** Start of a declaration. */
        void beginDecl(Machine m) {}

        /**
         * Bytes in use of this package's own session tables (part of the footprint, ISA 1.6): the used words of
         * the OPEN bit table (1/128 of the records); the fixed caches are working storage and are not counted.
         */
        long bytes() {
            return openTop >= openLo ? 8L * (openTop + 1 - openLo) : 0;
        }
    }

    // ---------------------------------------------------------------------------------------------------
    // The probe (ISA 2.5): one table for all kinds but NEU
    // ---------------------------------------------------------------------------------------------------

    /** {@code Machine.addRecordAt}, and the record's OPEN flag into the dense bit table ({@link Ext#openBits}). */
    private static int add(Machine m, int slot, int hash, int w0, int a, int b, int c) {
        int r = m.addRecordAt(slot, hash, w0, a, b, c);
        if (r < SIG) {
            m.x1.mark(r, w0);
        }
        return r;
    }

    /**
     * {@code Layout.W0_OPEN} if the value {@code v} has flag OPEN, else 0 (an immediate, NONE, HOLE: 0). The answer
     * of the record's own flag (I-FLAG); read from the bit table when this class made the record, from the
     * record otherwise. This is the test behind every "OPEN = or over the components" (ISA 2.3, 4.2).
     */
    static int openOf(Machine m, int v) {
        if (v <= Layout.HOLE) {
            return 0;
        }
        long[] ob = m.x1.openBits;
        int w = v >>> 5;
        if (w < ob.length) {
            int bits = (int) (ob[w] >>> ((v & 31) << 1)) & 3;
            if (bits != 0) {
                return (bits & 2) << (Layout.FLAG_SHIFT - 1);
            }
        }
        return m.vh[v << Layout.REC_SHIFT] & Layout.W0_OPEN;
    }

    /**
     * The record with key {@code (kind and aux of w0, a, b, c)}, made if absent with the flags of {@code w0}
     * (ISA 2.5; the same probe sequence as {@code Machine.internRecord}). A hit allocates nothing.
     * @return the record ref, or FATAL (E_MEM)
     */
    static int rec(Machine m, int w0, int a, int b, int c) {
        int hash = Hash.record(w0, a, b, c);
        int[] tab = m.vtab;
        int[] v = m.vh;
        int mask = tab.length - 1; // == m.vtabMask; written so that the compiler sees slot < tab.length
        int slot = hash & mask;
        int key0 = w0 & Hash.W0_KEY_MASK;
        for (;;) {
            int e = tab[slot];
            if (e == 0) {
                return add(m, slot, hash, w0, a, b, c);
            }
            if (((e ^ hash) >>> Layout.TAB_TAG_SHIFT) == 0) {
                int o = (e & Layout.TAB_INDEX_MASK) << Layout.REC_SHIFT;
                if (v[o + Layout.A] == a && v[o + Layout.B] == b && v[o + Layout.C] == c
                        && (v[o] & Hash.W0_KEY_MASK) == key0) {
                    return e & Layout.TAB_INDEX_MASK;
                }
            }
            slot = (slot + 1) & mask;
        }
    }

    // ---------------------------------------------------------------------------------------------------
    // SORT, CONST
    // ---------------------------------------------------------------------------------------------------

    /**
     * {@code Sort level} (ISA 2.2 kind SORT; a 64-entry cache in front, 2.5). Flags: WHNF.
     * @return the record ref, or FATAL (E_MEM; E_INTERNAL for level id 0); a signal given as the level is passed on
     */
    static int mkSort(Machine m, int level) {
        int[] sc = m.x1.sorts;
        int i = (level & (Ext.SORT_SLOTS - 1)) << 1;
        if (sc[i] == level && level != 0) {
            return sc[i + 1];
        }
        return sortSlow(m, level, i);
    }

    private static int sortSlow(Machine m, int level, int i) {
        if (level >= SIG) {
            return level;
        }
        if (level <= 0) {
            return m.fault(MachineErrors.E_INTERNAL);
        }
        int r = rec(m, Layout.SORT << Layout.KIND_SHIFT | Layout.W0_WHNF, level, 0, 0);
        if (r < SIG) {
            int[] sc = m.x1.sorts;
            sc[i] = level;
            sc[i + 1] = r;
        }
        return r;
    }

    /**
     * {@code CHEAD(row, levels)} (ISA 4.2 KCONST, 1.5 CHEAD): the CONST record; HCLASS, ACTIVE, WHNF, the nat-op code and
     * the hint class come from the row; word c = {@code mkSubst(lparams(row), levels)} (2.7), -1 if the level count
     * is not the row's (such a head never unfolds, R02 section 10 row 14). Official:
     * {@code instantiate_lparams} on a constant (R02 section 4).
     *
     * <p>Head class by declaration kind: axiom, opaque, {@code Quot}, {@code Quot.mk} -> AXIOM (WHNF); definition,
     * theorem -> DEFN (ACTIVE; a theorem has hint class OPAQUE); inductive -> IND (WHNF); constructor -> CTOR
     * (WHNF); recursor -> REC (ACTIVE); {@code Quot.lift}, {@code Quot.ind} -> QUOT (ACTIVE). A constant with the
     * nat-op code of one of the 14 binary functions is ACTIVE and not WHNF whatever its kind. The nat-op code is the
     * {@code rlvm.nat.NatOp} code (SUCC 1 .. SHIFT_RIGHT 15) of a constant that carries the name of one of the 15
     * accelerated functions and has NO levels (the official test is on {@code Const name []}); the prelude guard
     * of the function is NOT part of it and is consulted where the extension fires (2.9).
     * @return the record ref, or FATAL (E_VIS, E_INTERNAL for an unbound row, E_MEM)
     */
    static int mkConst(Machine m, int row, int levelList) {
        int[] c = m.x1.chead;
        // two independent multiplications and the top bits (Hash.cell is two multiplications in sequence + fin)
        int i = ((row * 0x9E3779B1 ^ levelList * 0x85EBCA6B) >>> (32 - Ext.CHEAD_LOG2)) << Layout.CELL_SHIFT;
        if (c[i] == row && c[i + 1] == levelList && c[i + 2] == m.epoch) {
            return c[i + 3];
        }
        return constSlow(m, row, levelList, i);
    }

    private static int constSlow(Machine m, int row, int levelList, int cell) {
        if (levelList >= SIG) {
            return levelList;
        }
        if (row <= 0 || levelList < 0) {
            return m.fault(MachineErrors.E_INTERNAL); // an unbound constant node (a = ~nameId): never a REJECT (ISA 4.2)
        }
        DeclTable d = m.decls;
        if (d.rank(row) >= m.decl) {
            return m.fault(MachineErrors.E_VIS); // I-SESSION: visible when the record is made
        }
        int kind = d.kind(row);
        int hc;
        int fl;
        int aux = 0;
        switch (kind) {
            case DeclKind.AXIOM:
            case DeclKind.OPAQUE:
            case DeclKind.QUOT_TYPE:
            case DeclKind.QUOT_MK:
                hc = Layout.HC_AXIOM;
                fl = Layout.FL_WHNF;
                break;
            case DeclKind.DEFN: {
                int h = d.hint(row);
                aux = (h == DeclKind.HINT_OPAQUE ? Layout.HINT_OPAQUE
                        : h == DeclKind.HINT_ABBREV ? Layout.HINT_ABBREV : Layout.HINT_REGULAR) << Layout.CONST_HINT_SHIFT;
                hc = Layout.HC_DEFN;
                fl = Layout.FL_ACTIVE;
                break;
            }
            case DeclKind.THM:
                hc = Layout.HC_DEFN;
                fl = Layout.FL_ACTIVE;
                break;
            case DeclKind.INDUCT:
                hc = Layout.HC_IND;
                fl = Layout.FL_WHNF;
                break;
            case DeclKind.CTOR:
                hc = Layout.HC_CTOR;
                fl = Layout.FL_WHNF;
                break;
            case DeclKind.REC:
                hc = Layout.HC_REC;
                fl = Layout.FL_ACTIVE;
                break;
            case DeclKind.QUOT_LIFT:
            case DeclKind.QUOT_IND:
                hc = Layout.HC_QUOT;
                fl = Layout.FL_ACTIVE;
                break;
            default:
                return m.fault(MachineErrors.E_INTERNAL);
        }
        int subst = 0;
        if (levelList == IntLists.EMPTY) {
            int sp = m.specials.which(row);
            if (sp >= Specials.NAT_SUCC && sp <= Specials.NAT_SHIFT_RIGHT) {
                aux |= sp - Specials.NAT_SUCC + NatOp.SUCC;
                if (sp != Specials.NAT_SUCC) {
                    // The official reduce_nat matches the 14 binary functions by NAME, before delta and whatever
                    // the constant's kind is (R02 7.6): such a head may reduce, so it is never flagged WHNF.
                    fl = Layout.FL_ACTIVE;
                }
            }
            if (d.numLevelParams(row) != 0) {
                subst = -1;
            }
        } else {
            subst = m.levels.mkSubst(d.levelParams(row), levelList);
        }
        int r = rec(m, Layout.w0(Layout.CONST, fl | hc << Layout.HCLASS_SHIFT, aux), row, levelList, subst);
        if (r < SIG) {
            int[] c = m.x1.chead;
            c[cell] = row;
            c[cell + 1] = levelList;
            c[cell + 2] = m.epoch;
            c[cell + 3] = r;
        }
        return r;
    }

    // ---------------------------------------------------------------------------------------------------
    // Literals (ISA 2.9)
    // ---------------------------------------------------------------------------------------------------

    /**
     * The Nat literal of an {@code rlvm.nat} handle (ISA 2.9): immediate below 2^31, two-word NBIG below 2^63,
     * NBIG with a NatStore id above. The error handles of {@code rlvm.nat} are accepted so that the result of an
     * operation can be passed straight in: {@code TOO_BIG} / {@code COUNT_TOO_BIG} give ABORT (E_NATSIZE: the
     * official kernel throws there, R02 7.6), anything else that is not a number FATAL (E_INTERNAL).
     * @return the value, or ABORT (E_NATSIZE) or FATAL (E_INTERNAL, E_MEM)
     */
    static int mkNat(Machine m, long natHandle) {
        if (natHandle >= 0) {
            if (natHandle < Layout.IMM_LIMIT) {
                return (int) natHandle | Layout.IMM;
            }
            return rec(m, Layout.NBIG << Layout.KIND_SHIFT | Layout.W0_WHNF | Layout.NBIG_INLINE, (int) natHandle,
                    (int) (natHandle >>> 32), 0);
        }
        return natStoreSlow(m, natHandle);
    }

    /**
     * NBIG over a NatStore id. Words b and c hold the content hash and the limb count of the stored number:
     * they make a record whose store id was reissued to another number detectable ({@link Lits#natHandle};
     * NOTES-M1 D3) and are functions of the number, so identity is still the number.
     */
    private static int natStoreSlow(Machine m, long h) {
        if (Nat.isError(h)) {
            return h == Nat.TOO_BIG || h == Nat.COUNT_TOO_BIG ? m.fail(MachineErrors.E_NATSIZE)
                    : m.fault(MachineErrors.E_INTERNAL);
        }
        NatStore s = m.nats;
        if (s == null) {
            return m.fault(MachineErrors.E_INTERNAL);
        }
        int id = Nat.bigId(h);
        return rec(m, Layout.NBIG << Layout.KIND_SHIFT | Layout.W0_WHNF | Layout.NBIG_STORE, id, s.hashOf(id),
                s.limbCount(id));
    }

    /**
     * The STR record of a {@code LitPool} string index (ISA 2.2; export literals only). Flags: WHNF.
     */
    static int mkStr(Machine m, int litIndex) {
        if (litIndex == 0) {
            return m.fault(MachineErrors.E_INTERNAL);
        }
        return rec(m, Layout.STR << Layout.KIND_SHIFT | Layout.W0_WHNF, litIndex, 0, 0);
    }

    // ---------------------------------------------------------------------------------------------------
    // Closures and suspensions
    // ---------------------------------------------------------------------------------------------------

    /**
     * Closure of {@code fun x : A => b} (ISA 2.2 LAM, 4.2 MKLAM): {@code frame} is the frame pruned by the node's mask,
     * {@code ls} is 0 when the term has no level parameter (2.7; enforced here). Official: delayed
     * {@code instantiate} (R02 section 4). Flags: WHNF; OPEN iff the frame holds an open value.
     */
    static int mkLam(Machine m, int term, int frame, int ls) {
        return closure(m, Layout.LAM << Layout.KIND_SHIFT | Layout.W0_WHNF, term, frame, ls);
    }

    /**
     * Closure of {@code (x : A) -> B} (ISA 2.2 PI, 4.2 MKPI).
     */
    static int mkPi(Machine m, int term, int frame, int ls) {
        return closure(m, Layout.PI << Layout.KIND_SHIFT | Layout.W0_WHNF, term, frame, ls);
    }

    /**
     * "The type of this lambda" (ISA 2.2 PII): domain as the LAM, codomain at x = INFER(body, frame + x, ONLY).
     * A different value from the PI over the same term and frame (bugs/pi-closure-context).
     */
    static int mkPii(Machine m, int term, int frame, int ls) {
        return closure(m, Layout.PII << Layout.KIND_SHIFT | Layout.W0_WHNF, term, frame, ls);
    }

    /**
     * The record (kind and flags of w0 | OPEN of the frame, term, frame, ls'); ISA 2.5 I-ID, 2.7. The probe of
     * {@link #rec}, with the flag OPEN read from the frame's header only when the record is made: a hit
     * touches the table and the record, nothing else.
     */
    private static int closure(Machine m, int w0, int term, int frame, int ls) {
        if (frame >= SIG) {
            return frame;
        }
        if (ls != 0 && !m.terms.hasLevelParam(term)) {
            ls = 0;
        }
        return closureProbe(m, w0, term, frame, ls);
    }

    /** The probe of {@link #closure}: {@code frame} is a frame run (or 0), {@code ls} is already canonical (2.7). */
    private static int closureProbe(Machine m, int w0, int term, int frame, int ls) {
        int hash = Hash.record(w0, term, frame, ls);
        int[] tab = m.vtab;
        int[] v = m.vh;
        int mask = tab.length - 1;
        int slot = hash & mask;
        int key0 = w0 & Hash.W0_KEY_MASK;
        if (Runs.Stat.ON) {
            Runs.Stat.cloProbe++;
            Runs.Stat.cloKeptH[Runs.Stat.kept(m, frame)]++;
            Runs.Stat.cloKind[w0 >>> Layout.KIND_SHIFT]++;
        }
        for (;;) {
            int e = tab[slot];
            if (e == 0) {
                if (Runs.Stat.ON) {
                    Runs.Stat.cloNew++;
                    Runs.Stat.cloNewKeptH[Runs.Stat.kept(m, frame)]++;
                    Runs.Stat.cloNewKind[w0 >>> Layout.KIND_SHIFT]++;
                    Runs.Stat.rec(m, 0, hash, m.vp, true);
                }
                return add(m, slot, hash, w0 | (m.rs[frame] & Runs.RUN_OPEN) << Runs.RUN_OPEN_TO_W0, term, frame, ls);
            }
            if (((e ^ hash) >>> Layout.TAB_TAG_SHIFT) == 0) {
                int o = (e & Layout.TAB_INDEX_MASK) << Layout.REC_SHIFT;
                if (v[o + Layout.A] == term && v[o + Layout.B] == frame && v[o + Layout.C] == ls
                        && (v[o] & Hash.W0_KEY_MASK) == key0) {
                    if (Runs.Stat.ON) {
                        Runs.Stat.rec(m, 0, hash, e & Layout.TAB_INDEX_MASK, false);
                    }
                    return e & Layout.TAB_INDEX_MASK;
                }
            }
            slot = (slot + 1) & mask;
        }
    }

    // ---------------------------------------------------------------------------------------------------
    // Closures and suspensions over a working environment: PRUNE and the record in one operation
    // ---------------------------------------------------------------------------------------------------

    /** {@code mkLam(m, term, Runs.prune(m, eb, en, fvMask(term)), ls)} ({@link #closureAt}). */
    static int mkLamAt(Machine m, int term, int eb, int en, int ls) {
        return closureAt(m, Layout.LAM << Layout.KIND_SHIFT | Layout.W0_WHNF, term, eb, en, ls);
    }

    /** {@code mkPi(m, term, Runs.prune(m, eb, en, fvMask(term)), ls)} ({@link #closureAt}). */
    static int mkPiAt(Machine m, int term, int eb, int en, int ls) {
        return closureAt(m, Layout.PI << Layout.KIND_SHIFT | Layout.W0_WHNF, term, eb, en, ls);
    }

    /** {@code suspRecord(m, term, Runs.prune(m, eb, en, fvMask(term)), ls)}: the tabling record ({@link #closureAt}). */
    static int suspRecordAt(Machine m, int term, int eb, int en, int ls) {
        return closureAt(m, Layout.SUSP << Layout.KIND_SHIFT, term, eb, en, ls);
    }

    /** {@code mkSusp(m, term, Runs.prune(m, eb, en, fvMask(term)), ls, false)}: a known VAL is returned in its place. */
    static int mkSuspAt(Machine m, int term, int eb, int en, int ls) {
        int s = closureAt(m, Layout.SUSP << Layout.KIND_SHIFT, term, eb, en, ls);
        if (s >= SIG) {
            return s;
        }
        int val = m.vh[(s << Layout.REC_SHIFT) + Layout.M_VAL];
        return val != 0 ? val : s;
    }

    /**
     * The record {@code (kind and flags of w0, term, PRUNE(env, mask of term), ls')}: exactly
     * {@code closure(m, w0, term, Runs.prune(m, eb, en, m.terms.fvMask(term)), ls)} - the same frame, the same
     * record - with table loads that do not wait for each other (NOTES-M1 section 5).
     *
     * <p>The two probes of the two-step construction depend on each other: the frame in rtab, then the record,
     * whose key holds the frame's id, in vtab. Once a session's tables have outgrown the cache that is two cache
     * misses in a row (reachability: four fifths of PRUNE and of the record probe are the one load of the
     * table slot). Here the vtab slot of the record <em>that a new frame would give</em> is loaded before the
     * rtab probe, so that the two loads overlap: the id of a new frame is known in advance (the top of the run
     * slab), and a record over a frame that did not exist cannot exist, so if the frame is new the record is
     * written at the first free slot from there, without a comparison. If the frame exists the early load was
     * for nothing and the record is probed as usual; to keep that rare, a frame whose content hash was looked
     * up a moment ago ({@link Ext#seen}; it exists and its slots are in the cache) is not speculated on.
     * Measured on reachability, native, one worker: -7% CPU; Init, Std, the Cedar slice unchanged.
     */
    private static int closureAt(Machine m, int w0, int term, int eb, int en, int ls) {
        if ((m.debug & Op.DBG_MASKAUDIT) != 0 && Debug.maskAudit(m, term) != 0) {
            return Op.FATAL; // the mask contract (ISA 2.4, 5.5), audited where the mask is used
        }
        long mask = m.terms.fvMask(term);
        if (mask <= 0) { // a closed term (the empty frame) or a far frame: the two-step way
            return closure(m, w0, term, mask == 0 ? Layout.RUN_EMPTY : Runs.prune(m, eb, en, mask), ls);
        }
        int len = 64 - Long.numberOfLeadingZeros(mask);
        if (len > en) {
            return m.fault(MachineErrors.E_INTERNAL); // as Runs.prune: the mask names an index outside the environment
        }
        if (ls != 0 && !m.terms.hasLevelParam(term)) {
            ls = 0;
        }
        int[] es = m.es;
        int top = eb + en - 1;
        int sum = 0;
        int cnt = 0;
        for (long b = mask; b != 0; b &= b - 1) {
            int i = Long.numberOfTrailingZeros(b);
            int v = es[top - i];
            if (v != Layout.HOLE) {
                sum += Runs.slotHash(v, i);
                cnt++;
            }
        }
        int fh = Runs.runHash(sum, Layout.RUN_FRAME, 0);
        int hdr = Layout.runHeader(len, Layout.RUN_FRAME, false) | (cnt < Runs.CNT_MAX ? cnt : Runs.CNT_MAX) << Runs.CNT_SHIFT;
        int[] seen = m.x1.seen;
        int si = fh & (Ext.SEEN_SLOTS - 1);
        if (seen[si] == fh) { // looked up a moment ago: the frame exists and its table slots are in the cache
            int f = frameProbe(m, fh, hdr, top, len, mask);
            return f >= SIG ? f : closureProbe(m, w0, term, f, ls);
        }
        seen[si] = fh;
        return closureOverNew(m, w0, term, ls, fh, hdr, top, len, mask);
    }

    /**
     * {@link #closureAt} for a frame that is probably new: the slot of the record over a new frame is loaded
     * first, then the frame is probed (and made), then the record is written - or, if the frame existed after
     * all, probed.
     */
    private static int closureOverNew(Machine m, int w0, int term, int ls, int fh, int hdr, int top, int len, long mask) {
        int guess = m.rsp; // the id a new frame gets
        int hg = Hash.record(w0, term, guess, ls);
        int[] vtab = m.vtab;
        int vslot = hg & (vtab.length - 1);
        int ve = vtab[vslot];
        if (ve == SPEC_PIN) {
            m.x1.pin++; // never true (record 0 is not interned); the test keeps this load in front of the rtab probe
        }
        int frame = frameProbe(m, fh, hdr, top, len, mask);
        if (frame != guess) {
            return frame >= SIG ? frame : closureProbe(m, w0, term, frame, ls);
        }
        // a record over a frame that did not exist a moment ago does not exist: the first free slot of its sequence
        while (ve != 0) {
            vslot = (vslot + 1) & (vtab.length - 1);
            ve = vtab[vslot];
        }
        return add(m, vslot, hg, w0 | (m.rs[frame] & Runs.RUN_OPEN) << Runs.RUN_OPEN_TO_W0, term, frame, ls);
    }

    /**
     * The probe of {@code Runs.pruneNear} for a near frame whose content hash {@code fh} and header {@code hdr}
     * are known: the frame of the bindings that {@code mask} keeps below es[top]; a new frame gets the id
     * {@code m.rsp} had.
     */
    private static int frameProbe(Machine m, int fh, int hdr, int top, int len, long mask) {
        int[] es = m.es;
        int[] tab = m.rtab;
        int[] rs = m.rs;
        int tmask = tab.length - 1;
        int slot = fh & tmask;
        for (;;) {
            int e = tab[slot];
            if (e == 0) {
                return Runs.pruneSlow(m, slot, fh, hdr, top - len + 1, len, mask, 0);
            }
            if (((e ^ fh) >>> Layout.TAB_TAG_SHIFT) == 0) {
                int r = e & Layout.TAB_INDEX_MASK;
                if ((rs[r] & ~Runs.RUN_OPEN) == hdr && rs[r + Layout.RUN_HASH] == fh
                        && Runs.sameKept(rs, r + Layout.RUN_H + len - 1, es, top, mask)
                        && ((hdr & Runs.CNT_MASK) != Runs.CNT_MASK || Runs.sameMasked(rs, r + Layout.RUN_H, es, top - len + 1, len, mask))) {
                    return r;
                }
            }
            slot = (slot + 1) & tmask;
        }
    }

    /** A vtab word that no table holds: tag 8 and record 0 (NONE is never interned). */
    private static final int SPEC_PIN = 8 << Layout.TAB_TAG_SHIFT;

    /**
     * Delayed {@code EVAL(term, frame, ls)} (ISA 2.2 SUSP, 4.2 MKSUSP), also the tabling record of that term under that
     * frame (5.1). Without {@code let} and with a known VAL the VAL is returned instead; with {@code let} the record is
     * kept (flag LET: the official hasFVar of a let variable, 2.8). Official: zeta by sharing (R02 6.6, 7.1).
     */
    static int mkSusp(Machine m, int term, int frame, int ls, boolean let) {
        if (let) {
            return closure(m, Layout.SUSP << Layout.KIND_SHIFT | Layout.W0_LET | Layout.W0_OPEN | SUSP_LET, term, frame, ls);
        }
        int s = closure(m, Layout.SUSP << Layout.KIND_SHIFT, term, frame, ls);
        if (s >= SIG) {
            return s;
        }
        int val = m.vh[(s << Layout.REC_SHIFT) + Layout.M_VAL];
        return val != 0 ? val : s;
    }

    /**
     * The plain SUSP record of {@code (term, frame, ls)} itself, made if absent and never replaced by its VAL: the
     * tabling record of EVAL, INFER and CHECK (ISA 5.1). Key {@code (SUSP, aux 0, term, frame, ls')}; the LET
     * suspension of the same triple is another record.
     */
    static int suspRecord(Machine m, int term, int frame, int ls) {
        return closure(m, Layout.SUSP << Layout.KIND_SHIFT, term, frame, ls);
    }

    /**
     * {@code FVAR(type, k)}: the k-th free variable of this type (ISA 2.2, 2.8). Flags OPEN and WHNF, head class FVAR.
     * @return the record ref, or FATAL (E_MEM)
     */
    static int mkFVar(Machine m, int type, int k) {
        return rec(m, Layout.FVAR << Layout.KIND_SHIFT | Layout.W0_OPEN | Layout.W0_WHNF, type, k, 0);
    }

    // ---------------------------------------------------------------------------------------------------
    // Applications
    // ---------------------------------------------------------------------------------------------------

    /**
     * {@code NEU(head, X[xb .. xb + n))}: one probe for head + arguments; the spine run is owned by the record
     * (ISA 2.2 NEU, 2.4 SPINE, 2.5). {@code head} is FVAR, CONST or PROJ; n >= 1 (n = 0 gives the head).
     * No canonical form is applied here ({@link #mkApp} does that).
     * @return the record ref, or ABORT (E_LIMIT: more than 65535 arguments) or FATAL (E_MEM)
     */
    static int mkNeu(Machine m, int head, int xb, int n) {
        if (n <= 0) {
            return head;
        }
        return neu(m, head, xb, n);
    }

    /**
     * MKAPP (ISA 4.2): the folded application of {@code f} (CONST, NEU, FVAR or PROJ) to X[xb .. xb + n); the arguments
     * of a NEU head are extended; {@code Nat.succ} with one argument is {@link #nsucc}. Flags: OPEN = or over head and
     * arguments; WHNF / ACTIVE / HCLASS from the head. No reduction (R02 section 11: folded heads are kept).
     * @return the record ref, or ABORT (E_LIMIT, E_NATSIZE) or FATAL (E_ILLTYPED: the head is a sort, Pi, literal;
     *     E_INTERNAL: the head is a closure or a suspension, which the caller must apply or force; E_PRELUDE; E_MEM)
     */
    static int mkApp(Machine m, int f, int xb, int n) {
        if (n <= 0) {
            return f;
        }
        if (f <= Layout.HOLE || f >= SIG) {
            return appSlow(m, f);
        }
        int[] vh = m.vh;
        int o = f << Layout.REC_SHIFT;
        int w0 = vh[o];
        int kind = w0 >>> Layout.KIND_SHIFT;
        if (kind == Layout.CONST) {
            if (n == 1 && (w0 & Layout.CONST_NATOP_MASK) == NatOp.SUCC) {
                return succApp(m, f, xb);
            }
            return neu(m, f, xb, n);
        }
        if (kind == Layout.NEU) {
            return neuExt(m, vh[o + Layout.A], f, vh[o + Layout.B], w0 & Layout.AUX_MASK, xb, n);
        }
        if (kind == Layout.FVAR || kind == Layout.PROJ) {
            return neu(m, f, xb, n);
        }
        return appSlow(m, f);
    }

    /** MKAPP on something that is not a head (ISA 4.2 "Fails"). */
    private static int appSlow(Machine m, int f) {
        if (f >= SIG) {
            return f;
        }
        if (f < 0) {
            return m.fault(MachineErrors.E_ILLTYPED); // a literal
        }
        if (f <= Layout.HOLE) {
            return m.fault(MachineErrors.E_INTERNAL);
        }
        int kind = m.vh[f << Layout.REC_SHIFT] >>> Layout.KIND_SHIFT;
        // a closure or a suspension must be applied or forced by the caller; a sort, Pi, literal, NSUC is ill-typed
        return m.fault(kind == Layout.LAM || kind == Layout.SUSP ? MachineErrors.E_INTERNAL : MachineErrors.E_ILLTYPED);
    }

    /**
     * {@code Nat.succ x} (ISA 4.2 MKAPP / NSUCC, 2.9): by the guard of the constant named {@code Nat.succ}: VALID ->
     * {@link #nsucc}; INVALID -> fault(E_PRELUDE); ABSENT (not evaluated: hand-built stores) -> the extension does
     * not fire and the application is an ordinary constructor application.
     */
    private static int succApp(Machine m, int f, int xb) {
        int st = m.specials.state(Specials.NAT_SUCC);
        if (st == Specials.VALID) {
            return nsucc(m, m.X[xb]);
        }
        if (st == Specials.INVALID) {
            return m.fault(MachineErrors.E_PRELUDE);
        }
        return neu(m, f, xb, 1);
    }

    /**
     * The NEU of {@code head} applied to X[xb .. xb + n) (ISA 2.5: key (head, len, argument refs), hash in word
     * c): the common case of {@link #neuExt} without a prefix, kept separate so that the hit path is one hash
     * loop, one probe and one comparison loop.
     */
    private static int neu(Machine m, int head, int xb, int n) {
        if (n > Op.SPINE_MAX) {
            return m.fail(MachineErrors.E_LIMIT);
        }
        int[] x = m.X;
        int h = 0;
        for (int i = 0; i < n; i++) {
            h += argHash(x[xb + i], i);
        }
        int hash = neuHash(head, h);
        int[] tab = m.vtab;
        int[] v = m.vh;
        int mask = tab.length - 1;
        int slot = hash & mask;
        int key0 = Layout.NEU << Layout.KIND_SHIFT | n;
        if (Runs.Stat.ON) {
            Runs.Stat.neuProbe++;
            Runs.Stat.neuLenH[Math.min(32, n)]++;
        }
        for (;;) {
            int e = tab[slot];
            if (e == 0) {
                if (Runs.Stat.ON) {
                    Runs.Stat.rec(m, 1, hash, m.vp, true);
                }
                return neuSlow(m, slot, hash, head, head, Layout.RUN_EMPTY, 0, xb, n);
            }
            if (((e ^ hash) >>> Layout.TAB_TAG_SHIFT) == 0) {
                int o = (e & Layout.TAB_INDEX_MASK) << Layout.REC_SHIFT;
                if (v[o + Layout.C] == hash && v[o + Layout.A] == head && (v[o] & Hash.W0_KEY_MASK) == key0
                        && sameArgs(m.rs, v[o + Layout.B] + Layout.RUN_H, x, xb, n)) {
                    if (Runs.Stat.ON) {
                        Runs.Stat.rec(m, 1, hash, e & Layout.TAB_INDEX_MASK, false);
                    }
                    return e & Layout.TAB_INDEX_MASK;
                }
            }
            if (Runs.Stat.ON) {
                Runs.Stat.neuSteps++;
            }
            slot = (slot + 1) & mask;
        }
    }

    /**
     * The contribution of argument {@code v} at position {@code j} to the hash of a NEU: the hash is
     * {@link #neuHash} of the head and the SUM of the contributions, so the multiplications of a spine do not
     * depend on each other (the chained {@code Hash.step} cost one dependent multiplication per argument). The
     * hash of a NEU is private to this class: it is kept in word c of the record, which is what
     * {@code Hash.ofRecord} reads when a table grows or is reset.
     */
    private static int argHash(int v, int j) {
        int t = (v + j * 0x9E3779B1) * 0x85EBCA6B;
        return t ^ (t >>> 15);
    }

    private static int neuHash(int head, int sum) {
        return Hash.fin(sum + head * 0xC2B2AE35 + (Layout.NEU << Layout.KIND_SHIFT));
    }

    private static boolean sameArgs(int[] rs, int sb, int[] x, int xb, int n) {
        for (int i = 0; i < n; i++) {
            if (rs[sb + i] != x[xb + i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * The NEU of {@code head} applied to the {@code pn} slots of spine run {@code pre} followed by X[xb .. xb + n)
     * (ISA 2.5: key (head, len, argument refs), hash in word c). {@code from} is the value whose flags the new
     * record inherits: the head, or the NEU that is being extended (its OPEN covers head and prefix).
     */
    private static int neuExt(Machine m, int head, int from, int pre, int pn, int xb, int n) {
        int total = pn + n;
        if (total > Op.SPINE_MAX) {
            return m.fail(MachineErrors.E_LIMIT);
        }
        int[] rs = m.rs;
        int[] x = m.X;
        int h = 0;
        int pb = pre + Layout.RUN_H;
        for (int i = 0; i < pn; i++) {
            h += argHash(rs[pb + i], i);
        }
        for (int i = 0; i < n; i++) {
            h += argHash(x[xb + i], pn + i);
        }
        int hash = neuHash(head, h);
        int[] tab = m.vtab;
        int[] v = m.vh;
        int mask = tab.length - 1;
        int slot = hash & mask;
        int key0 = Layout.NEU << Layout.KIND_SHIFT | total;
        if (Runs.Stat.ON) {
            Runs.Stat.neuProbe++;
            Runs.Stat.neuExtProbe++;
            Runs.Stat.neuLenH[Math.min(32, total)]++;
        }
        for (;;) {
            int e = tab[slot];
            if (e == 0) {
                return neuSlow(m, slot, hash, head, from, pre, pn, xb, n);
            }
            if (((e ^ hash) >>> Layout.TAB_TAG_SHIFT) == 0) {
                int o = (e & Layout.TAB_INDEX_MASK) << Layout.REC_SHIFT;
                if (v[o + Layout.C] == hash && v[o + Layout.A] == head && (v[o] & Hash.W0_KEY_MASK) == key0
                        && sameSpine(rs, v[o + Layout.B] + Layout.RUN_H, pb, pn, x, xb, n)) {
                    return e & Layout.TAB_INDEX_MASK;
                }
            }
            slot = (slot + 1) & mask;
        }
    }

    private static boolean sameSpine(int[] rs, int sb, int pb, int pn, int[] x, int xb, int n) {
        for (int i = 0; i < pn; i++) {
            if (rs[sb + i] != rs[pb + i]) {
                return false;
            }
        }
        sb += pn;
        for (int i = 0; i < n; i++) {
            if (rs[sb + i] != x[xb + i]) {
                return false;
            }
        }
        return true;
    }

    /** The miss of {@link #neu} and {@link #neuExt}: writes the spine run and the record (ISA 2.4 SPINE, 2.5). */
    private static int neuSlow(Machine m, int slot, int hash, int head, int from, int pre, int pn, int xb, int n) {
        int total = pn + n;
        int[] x = m.X;
        int[] vh = m.vh;
        if (Runs.Stat.ON) {
            Runs.Stat.neuNew++;
            Runs.Stat.neuNewLenH[Math.min(32, total)]++;
            Runs.Stat.wordsSpine += 2 + total;
        }
        int w0 = vh[from << Layout.REC_SHIFT] & W0_INHERIT;
        // OPEN = or over head and arguments: stop at the first open one (each test loads a record header)
        for (int i = n - 1; i >= 0 && (w0 & Layout.W0_OPEN) == 0; i--) {
            w0 |= openOf(m, x[xb + i]);
        }
        int hdr = Layout.runHeader(total, Layout.RUN_SPINE, false) | (w0 & Layout.W0_OPEN) >>> Runs.RUN_OPEN_TO_W0;
        int run = m.addRun(hdr, hash, 0);
        if (run >= SIG) {
            return run;
        }
        int[] rs = m.rs; // addRun may have reallocated the slab
        int sb = run + Layout.RUN_H;
        if (pn != 0) {
            System.arraycopy(rs, pre + Layout.RUN_H, rs, sb, pn);
            sb += pn;
        }
        if (n <= SMALL_COPY) {
            for (int i = 0; i < n; i++) { // a spine has 3 arguments on average: a call of arraycopy costs more than the loop
                rs[sb + i] = x[xb + i];
            }
        } else {
            System.arraycopy(x, xb, rs, sb, n);
        }
        return add(m, slot, hash, Layout.NEU << Layout.KIND_SHIFT | w0 | total, head, run, hash);
    }

    /**
     * MKPROJ (ISA 4.2): {@code v = peek(s)}; a NEU with a CTOR head of the structure and enough arguments yields the
     * field, else the PROJ record. Official: {@code reduce_proj_core} on a syntactic constructor (R02 7.2); the
     * struct is not reduced. Flags of the PROJ record: ACTIVE, head class PROJ, OPEN of the struct.
     */
    static int mkProj(Machine m, int structRow, int field, int s) {
        if (s >= SIG) {
            return s;
        }
        int v = peek(m, s);
        if (v == Layout.NONE || v == Layout.HOLE) {
            return m.fault(MachineErrors.E_INTERNAL);
        }
        int open = 0;
        if (v > 0) {
            int[] vh = m.vh;
            int o = v << Layout.REC_SHIFT;
            int w0 = vh[o];
            open = w0 & Layout.W0_OPEN;
            if ((w0 >>> Layout.KIND_SHIFT) == Layout.NEU && ((w0 >>> W0_HCLASS_SHIFT) & 7) == Layout.HC_CTOR) {
                int f = projField(m, structRow, field, vh[(vh[o + Layout.A] << Layout.REC_SHIFT) + Layout.A],
                        vh[o + Layout.B], w0 & Layout.AUX_MASK);
                if (f != Layout.NONE) {
                    return f;
                }
            }
        }
        return rec(m, Layout.PROJ << Layout.KIND_SHIFT | Layout.W0_ACTIVE | Layout.HC_PROJ << W0_HCLASS_SHIFT | open, v,
                structRow, field);
    }

    /** Field {@code field} of a constructor application (constructor row, spine, length), or NONE (R02 7.2). */
    private static int projField(Machine m, int structRow, int field, int ctorRow, int spine, int len) {
        DeclTable d = m.decls;
        if (d.ctorInduct(ctorRow) != structRow || field < 0) {
            return Layout.NONE;
        }
        int idx = d.ctorNumParams(ctorRow) + field;
        return idx >= 0 && idx < len ? m.rs[spine + Layout.RUN_H + idx] : Layout.NONE;
    }

    // ---------------------------------------------------------------------------------------------------
    // Nat.succ (ISA 2.9, 4.2 NSUCC; RECONCILIATION section 2 "Nat.succ")
    // ---------------------------------------------------------------------------------------------------

    /**
     * NSUCC (ISA 4.2; RECONCILIATION section 2 "Nat.succ"): immediate n -> n + 1 (NBIG at 2^31); NBIG -> + 1 with the
     * size check; CONST Nat.zero -> the literal 1; NSUC(b, k) -> NSUC(b, k + 1); other v -> NSUC(v, 1) with flag WHNF
     * iff v has it. Official: the {@code Nat.succ} row of R02 7.6, offset form of 8.4.
     *
     * <p>Precondition natOk: the caller knows that the guard of {@code Nat.succ} is VALID ({@link #mkApp} tests
     * it). {@code Nat.zero} is the constant of that name without levels; its own guard must be VALID for the
     * literal view (INVALID: fault(E_PRELUDE); ABSENT: it is an ordinary base).
     * @return the value, or ABORT (E_NATSIZE) or FATAL (E_PRELUDE, E_MEM)
     */
    static int nsucc(Machine m, int v) {
        if (v < 0) {
            return v != -1 ? v + 1 : mkNat(m, Layout.IMM_LIMIT);
        }
        if (v >= SIG) {
            return v;
        }
        return nsuccSlow(m, v);
    }

    private static int nsuccSlow(Machine m, int v) {
        if (v <= Layout.HOLE) {
            return m.fault(MachineErrors.E_INTERNAL);
        }
        int[] vh = m.vh;
        int o = v << Layout.REC_SHIFT;
        int w0 = vh[o];
        switch (w0 >>> Layout.KIND_SHIFT) {
            case Layout.NBIG:
                return mkNat(m, Nat.succ(m.nats, Lits.natHandle(m, v)));
            case Layout.NSUC: {
                int k = vh[o + Layout.B];
                if (k == Integer.MAX_VALUE) {
                    return m.fail(MachineErrors.E_LIMIT);
                }
                return rec(m, w0 & (Hash.W0_KEY_MASK | Layout.W0_OPEN | Layout.W0_WHNF), vh[o + Layout.A], k + 1, 0);
            }
            case Layout.CONST:
                if (vh[o + Layout.B] == IntLists.EMPTY && vh[o + Layout.A] == m.specials.cid(Specials.NAT_ZERO)) {
                    int st = m.specials.state(Specials.NAT_ZERO);
                    if (st == Specials.VALID) {
                        return Layout.imm(1);
                    }
                    if (st == Specials.INVALID) {
                        return m.fault(MachineErrors.E_PRELUDE);
                    }
                }
                break;
            default:
                break;
        }
        return rec(m, Layout.NSUC << Layout.KIND_SHIFT | (w0 & (Layout.W0_OPEN | Layout.W0_WHNF)), v, 1, 0);
    }

    /**
     * {@code Nat.succ^k v} for k >= 0 (ISA 2.9, 4.3.4: the literal {@code n + k} of a folded NSUC; 4.4 OFFSET): the
     * canonical form of k applications of {@link #nsucc}, in one step: a literal becomes the literal + k (size
     * check), {@code Nat.zero} the literal k, NSUC(b, j) becomes NSUC(b, j + k), anything else NSUC(v, k).
     * Precondition natOk as for {@link #nsucc}.
     * @return the value, or ABORT (E_NATSIZE, E_LIMIT) or FATAL (E_PRELUDE, E_INTERNAL, E_MEM)
     */
    static int nsuccK(Machine m, int v, int k) {
        if (k <= 1) {
            return k == 1 ? nsucc(m, v) : k == 0 ? v : m.fault(MachineErrors.E_INTERNAL);
        }
        if (v < 0) {
            return mkNat(m, (long) (v & Layout.IMM_MASK) + k);
        }
        if (v <= Layout.HOLE || v >= SIG) {
            return v >= SIG ? v : m.fault(MachineErrors.E_INTERNAL);
        }
        int[] vh = m.vh;
        int o = v << Layout.REC_SHIFT;
        int w0 = vh[o];
        int kind = w0 >>> Layout.KIND_SHIFT;
        if (kind == Layout.NBIG) {
            return mkNat(m, Nat.add(m.nats, Lits.natHandle(m, v), k));
        }
        if (kind == Layout.NSUC) {
            int j = vh[o + Layout.B];
            if (j > Integer.MAX_VALUE - k) {
                return m.fail(MachineErrors.E_LIMIT);
            }
            return rec(m, w0 & (Hash.W0_KEY_MASK | Layout.W0_OPEN | Layout.W0_WHNF), vh[o + Layout.A], j + k, 0);
        }
        int one = nsuccSlow(m, v); // the literal 1 for Nat.zero, else NSUC(v, 1); E_PRELUDE by the guard
        return one >= SIG ? one : nsuccK(m, one, k - 1);
    }

    /**
     * The predecessor view of an NSUC cell (ISA 2.9): {@code NSUC(b, k)} -> {@code k == 1 ? b : NSUC(b, k - 1)}: the
     * field of the successor view in IOTA, the argument of {@code Nat.succ} (R02 7.4, 8.4 {@code is_nat_succ}).
     */
    static int npred(Machine m, int nsuc) {
        return npeel(m, nsuc, 1);
    }

    /**
     * {@code NSUC(b, k)} without its outer {@code j} successors, 1 <= j <= k: {@code j == k ? b : NSUC(b, k - j)}
     * (ISA 4.4 OFFSET peels min(k1, k2) layers at once: k single official steps composed, B10).
     * @return the value, or FATAL (E_INTERNAL: not an NSUC, or j outside 1..k; E_MEM)
     */
    static int npeel(Machine m, int nsuc, int j) {
        if (nsuc <= Layout.HOLE || nsuc >= SIG) {
            return nsuc >= SIG ? nsuc : m.fault(MachineErrors.E_INTERNAL);
        }
        int[] vh = m.vh;
        int o = nsuc << Layout.REC_SHIFT;
        int w0 = vh[o];
        int k = vh[o + Layout.B];
        if ((w0 >>> Layout.KIND_SHIFT) != Layout.NSUC || j < 1 || j > k) {
            return m.fault(MachineErrors.E_INTERNAL);
        }
        if (j == k) {
            return vh[o + Layout.A];
        }
        return rec(m, w0 & (Hash.W0_KEY_MASK | Layout.W0_OPEN | Layout.W0_WHNF), vh[o + Layout.A], k - j, 0);
    }

    /**
     * PEEK (ISA 4.2): a SUSP without flag LET and with a known VAL yields the VAL; anything else itself.
     */
    static int peek(Machine m, int v) {
        if (v <= Layout.HOLE) {
            return v;
        }
        int[] vh = m.vh;
        int o = v << Layout.REC_SHIFT;
        if ((vh[o] & (0xF << Layout.KIND_SHIFT | Layout.W0_LET)) == Layout.SUSP << Layout.KIND_SHIFT) {
            int val = vh[o + Layout.M_VAL];
            return val != 0 ? val : v;
        }
        return v;
    }

    // ---------------------------------------------------------------------------------------------------
    // Accessors
    // ---------------------------------------------------------------------------------------------------

    /**
     * Kind of a value ref: {@code Layout.K_IMM} for an immediate, else the record's kind (ISA 2.2).
     */
    static int kind(Machine m, int v) {
        return v < 0 ? Layout.K_IMM : m.vh[v << Layout.REC_SHIFT] >>> Layout.KIND_SHIFT;
    }

    /**
     * The 12 flag bits of a record ({@code Layout.FL_*}, ISA 2.3). An immediate has WHNF and nothing else.
     */
    static int flags(Machine m, int v) {
        return v < 0 ? Layout.FL_WHNF : (m.vh[v << Layout.REC_SHIFT] >>> Layout.FLAG_SHIFT) & Layout.FLAGS_MASK;
    }

    /**
     * Word a of a record.
     */
    static int a(Machine m, int v) {
        return m.vh[(v << Layout.REC_SHIFT) + Layout.A];
    }

    /**
     * Word b of a record.
     */
    static int b(Machine m, int v) {
        return m.vh[(v << Layout.REC_SHIFT) + Layout.B];
    }

    /**
     * Word c of a record.
     */
    static int c(Machine m, int v) {
        return m.vh[(v << Layout.REC_SHIFT) + Layout.C];
    }

    /**
     * The aux field of a record (ISA 2.2).
     */
    static int aux(Machine m, int v) {
        return m.vh[v << Layout.REC_SHIFT] & Layout.AUX_MASK;
    }

    /**
     * Memo word {@code slot} (0..3) of a record (ISA 2.6); 0 = unknown. MEMOGET.
     */
    static int memo(Machine m, int v, int slot) {
        return m.vh[(v << Layout.REC_SHIFT) + Layout.M0 + slot];
    }

    /**
     * MEMOPUT (ISA 4.1): only by the step that completes the judgment (I-MEMO, 5.4).
     */
    static void setMemo(Machine m, int v, int slot, int x) {
        m.vh[(v << Layout.REC_SHIFT) + Layout.M0 + slot] = x;
    }

    /** Head class ({@code Layout.HC_*}) of a CONST or NEU record (ISA 2.3 bits 9-11); FVAR records read HC_FVAR. */
    static int hclass(Machine m, int v) {
        return (m.vh[v << Layout.REC_SHIFT] >>> W0_HCLASS_SHIFT) & 7;
    }

    /** True if the value may contain a free variable or a let suspension (flag OPEN, ISA 2.3); false for an immediate. */
    static boolean isOpen(Machine m, int v) {
        return v > 0 && (m.vh[v << Layout.REC_SHIFT] & Layout.W0_OPEN) != 0;
    }

    /** Test support: the entry of the OPEN bit table for record {@code v}: 0 = none, 1 = closed, 3 = OPEN. */
    static int openBitsOf(Machine m, int v) {
        long[] ob = m.x1.openBits;
        return (v >>> 5) < ob.length ? (int) (ob[v >>> 5] >>> ((v & 31) << 1)) & 3 : 0;
    }

    /**
     * The {@code rlvm.nat.NatOp} code (1..15) of a CONST record that names an accelerated Nat function and has no
     * levels, else 0 (ISA 2.2 CONST aux). The function's prelude guard is not consulted (see {@link #mkConst}).
     */
    static int natOp(Machine m, int constRef) {
        return m.vh[constRef << Layout.REC_SHIFT] & Layout.CONST_NATOP_MASK;
    }

    /** Hint class ({@code Layout.HINT_*}) of a CONST record: OPAQUE for everything but a definition (ISA 2.2). */
    static int hintClass(Machine m, int constRef) {
        return (m.vh[constRef << Layout.REC_SHIFT] >>> Layout.CONST_HINT_SHIFT) & 3;
    }

    /**
     * The CONST / FVAR / PROJ record at the head of a value: word a of a NEU, the value itself otherwise.
     */
    static int head(Machine m, int v) {
        int[] vh = m.vh;
        int o = v << Layout.REC_SHIFT;
        return (vh[o] >>> Layout.KIND_SHIFT) == Layout.NEU ? vh[o + Layout.A] : v;
    }

    /** Sets the two-bit PROOF memo of a record ({@code Layout.TRI_*}; ISA 2.3 bits 4-5). */
    static void setProof(Machine m, int v, int tri) {
        int o = v << Layout.REC_SHIFT;
        m.vh[o] = m.vh[o] & ~(Layout.FL_PROOF_MASK << Layout.FLAG_SHIFT) | tri << (Layout.FLAG_SHIFT + Layout.PROOF_SHIFT);
    }

    /** Sets the two-bit PROPT memo of a type record ({@code Layout.TRI_*}; ISA 2.3 bits 6-7). */
    static void setPropT(Machine m, int v, int tri) {
        int o = v << Layout.REC_SHIFT;
        m.vh[o] = m.vh[o] & ~(Layout.FL_PROPT_MASK << Layout.FLAG_SHIFT) | tri << (Layout.FLAG_SHIFT + Layout.PROPT_SHIFT);
    }

    /** Sets flag COREFULL: m1 now holds the full {@code whnf_core} result (ISA 2.3 bit 3, 4.3.3). */
    static void setCoreFull(Machine m, int v) {
        m.vh[v << Layout.REC_SHIFT] |= Layout.W0_COREFULL;
    }
}

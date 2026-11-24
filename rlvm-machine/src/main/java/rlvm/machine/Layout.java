package rlvm.machine;

/**
 * Constants only (ISA 13.3, "Layout"): value references, record words, kinds, flags, memo slots, result
 * codes, run headers, the intern-table word, pair entries, direct-mapped cache cells, fact records,
 * comparison-plan classes and the structural level encoding.
 *
 * <p>FROZEN. Owner: work package M6 (shared file). A change is requested through
 * {@code work/status/requests.log} ("Mx -> M6: ...") and recorded in the change log of ISA section 15.
 * Error codes are {@code rlvm.api.MachineErrors}; they are not redefined here.
 *
 * <p>Section references are to {@code docs/design/ISA.md}.
 */
public final class Layout {
    private Layout() {}

    // ---------------------------------------------------------------------------------------------------
    // 2.1 Value references
    // ---------------------------------------------------------------------------------------------------

    /** Bit 31: a small Nat literal {@code n = ref & IMM_MASK}, 0 <= n < 2^31. Such a ref is a negative int. */
    public static final int IMM = 1 << 31;
    public static final int IMM_MASK = 0x7FFF_FFFF;
    /** Largest immediate + 1 (2^31) as a long. */
    public static final long IMM_LIMIT = 1L << 31;
    /** No value (also: "does not unfold", "no rule applies"). */
    public static final int NONE = 0;
    /** The unreadable placeholder of an unread binder; reading it through a variable is {@code fault(E_HOLE)}. */
    public static final int HOLE = 1;
    /** Index of the first real record; records 0 (NONE) and 1 (HOLE) are reserved and never interned. */
    public static final int FIRST_RECORD = 2;
    /** Bits 27..0 of a record ref. */
    public static final int REF_INDEX_MASK = 0x0FFF_FFFF;
    /** Reserved ref bits (section 10): must be 0 in v1. */
    public static final int REF_EPH = 1 << 30;
    public static final int REF_UNQ = 1 << 29;
    /** Record count limit of a session (section 9): 2^28. */
    public static final int RECORD_MAX = 1 << 28;

    /** True for a small Nat literal. */
    public static boolean isImm(int ref) {
        return ref < 0;
    }

    /** The immediate for {@code 0 <= n < 2^31}. */
    public static int imm(int n) {
        return n | IMM;
    }

    /** The value of an immediate. */
    public static int immValue(int ref) {
        return ref & IMM_MASK;
    }

    // ---------------------------------------------------------------------------------------------------
    // 2.2 Records: 8 words at vh[8 * r]
    // ---------------------------------------------------------------------------------------------------

    public static final int REC_WORDS = 8;
    public static final int REC_SHIFT = 3;
    public static final int REC_BYTES = 32;
    /** w0 = kind:4 (31..28) | flags:12 (27..16) | aux:16 (15..0). */
    public static final int W0 = 0;
    public static final int A = 1;
    public static final int B = 2;
    public static final int C = 3;
    public static final int M0 = 4;
    public static final int M1 = 5;
    public static final int M2 = 6;
    public static final int M3 = 7;
    public static final int KIND_SHIFT = 28;
    public static final int FLAG_SHIFT = 16;
    public static final int FLAGS_MASK = 0xFFF;
    public static final int AUX_MASK = 0xFFFF;

    /** {@code kind:4 | flags:12 | aux:16}. */
    public static int w0(int kind, int flags, int aux) {
        return kind << KIND_SHIFT | flags << FLAG_SHIFT | aux;
    }

    public static int kindOf(int w0) {
        return w0 >>> KIND_SHIFT;
    }

    public static int flagsOf(int w0) {
        return (w0 >>> FLAG_SHIFT) & FLAGS_MASK;
    }

    public static int auxOf(int w0) {
        return w0 & AUX_MASK;
    }

    // kinds (0: the K2 derivation slot, unused; 13-15 reserved for packed representations, owner note 8)
    public static final int K_SLOT = 0;
    public static final int SORT = 1;
    public static final int NBIG = 2;
    public static final int STR = 3;
    public static final int LAM = 4;
    public static final int PI = 5;
    public static final int PII = 6;
    public static final int SUSP = 7;
    public static final int FVAR = 8;
    public static final int CONST = 9;
    public static final int NEU = 10;
    public static final int PROJ = 11;
    public static final int NSUC = 12;
    /** Pseudo-kind that {@code Ops.kind} reports for an immediate (never stored in a record). */
    public static final int K_IMM = 16;
    /** Number of kind codes including {@link #K_IMM}. */
    public static final int KIND_COUNT = 17;

    /** NBIG aux: 0 = the value is in words a (low 32 bits) and b (high 31 bits); 1 = a is a NatStore id. */
    public static final int NBIG_INLINE = 0;
    public static final int NBIG_STORE = 1;
    /** CONST aux = nat-op code:5 (bits 4..0) | hint class:2 (bits 6..5). */
    public static final int CONST_NATOP_MASK = 0x1F;
    public static final int CONST_HINT_SHIFT = 5;
    public static final int HINT_OPAQUE = 0;
    public static final int HINT_ABBREV = 1;
    public static final int HINT_REGULAR = 2;

    // ---------------------------------------------------------------------------------------------------
    // 2.3 Flags (bit numbers inside the 12-bit flag field; FL_x is the mask inside that field, W0_x the
    //     mask inside w0)
    // ---------------------------------------------------------------------------------------------------

    public static final int FL_OPEN = 1 << 0;
    public static final int FL_WHNF = 1 << 1;
    public static final int FL_ACTIVE = 1 << 2;
    public static final int FL_COREFULL = 1 << 3;
    /** PROOF, bits 4-5: 0 unknown, 1 not a proof, 2 a proof. */
    public static final int PROOF_SHIFT = 4;
    public static final int FL_PROOF_MASK = 3 << PROOF_SHIFT;
    /** PROPT, bits 6-7: for a type: 0 unknown, 1 not a proposition, 2 a proposition. */
    public static final int PROPT_SHIFT = 6;
    public static final int FL_PROPT_MASK = 3 << PROPT_SHIFT;
    public static final int TRI_UNKNOWN = 0;
    public static final int TRI_NO = 1;
    public static final int TRI_YES = 2;
    /** Bit 8: INSCOPE on an FVAR record, LET on a SUSP record. */
    public static final int FL_INSCOPE = 1 << 8;
    public static final int FL_LET = 1 << 8;
    /** HCLASS, bits 9-11: head class of CONST and NEU. */
    public static final int HCLASS_SHIFT = 9;
    public static final int FL_HCLASS_MASK = 7 << HCLASS_SHIFT;

    public static final int W0_OPEN = FL_OPEN << FLAG_SHIFT;
    public static final int W0_WHNF = FL_WHNF << FLAG_SHIFT;
    public static final int W0_ACTIVE = FL_ACTIVE << FLAG_SHIFT;
    public static final int W0_COREFULL = FL_COREFULL << FLAG_SHIFT;
    public static final int W0_INSCOPE = FL_INSCOPE << FLAG_SHIFT;
    public static final int W0_LET = FL_LET << FLAG_SHIFT;

    // head classes
    public static final int HC_FVAR = 0;
    public static final int HC_AXIOM = 1;
    public static final int HC_IND = 2;
    public static final int HC_CTOR = 3;
    public static final int HC_REC = 4;
    public static final int HC_QUOT = 5;
    public static final int HC_DEFN = 6;
    public static final int HC_PROJ = 7;

    // ---------------------------------------------------------------------------------------------------
    // 2.6 Memo words by kind (the value is the word offset inside the record: M0 + slot)
    // ---------------------------------------------------------------------------------------------------

    // CONST, NEU, PROJ
    public static final int M_WHNF = M0;
    public static final int M_CORE = M1;
    public static final int M_TYPE = M2;
    public static final int M_UNF = M3;
    // LAM (M_TYPE = its PII)
    public static final int M_DOM = M1;
    public static final int M_CODE = M3;
    // PI, PII (M_DOM, M_CODE as LAM)
    public static final int M_COD = M0;
    public static final int M_SLVL = M2;
    // SUSP (M_TYPE = type of the term under the frame)
    public static final int M_VAL = M0;
    public static final int M_TSTAMP = M3;
    // STR
    public static final int M_CTORF = M0;
    // FVAR: scope state, not memos (2.8). M_FV0 is an addition of the skeleton: the record FVAR(type, 0) of
    // this variable's type (0 on that record itself), so that CLOSE needs no probe.
    public static final int M_NEXT = M0;
    public static final int M_OPENCNT = M1;
    public static final int M_TOPV = M2;
    public static final int M_FV0 = M3;

    /** Slot numbers 0..3 as used by the {@code MEMOGET} / {@code MEMOPUT} instructions and {@code Ops.memo}. */
    public static final int SLOT_COUNT = 4;

    // ---------------------------------------------------------------------------------------------------
    // Result codes (register A when it is not a value), 13.3
    // ---------------------------------------------------------------------------------------------------

    public static final int F = 0;
    public static final int T = 1;
    public static final int U = 2;
    public static final int UNDEF = 3;
    /**
     * "No inline answer" of an {@code Ops.xxxFast} method: {@link Op#MISS}, directly below the signals. ISA
     * 13.3 (revision 2) said -1, but -1 is the immediate 2^31 - 1 (bit 31 and all value bits set).
     */
    public static final int MISS = Op.MISS;
    /** M_CODE of a closure whose binder term has no block. */
    public static final int NOCODE = -1;
    /** TSTAMP: 0 = infer-only; PERM = valid in every later declaration; else a check generation. */
    public static final int TSTAMP_ONLY = 0;
    /**
     * Larger than every check generation. ISA 13.3 (revision 2) said 0x7FFFFFFF, which is the signal SUSP of
     * the host convention; a memo word must never look like a signal when it travels through an int result.
     */
    public static final int TSTAMP_PERM = 0x3FFF_FFFF;

    // ---------------------------------------------------------------------------------------------------
    // 1.6 / 2.4 Runs: rs[r] = len:24 (31..8) | count:4 (7..4) | open:1 (3) | kind:2 (2..1) | far:1 (0);
    //                 rs[r + 1] = content hash;  far: rs[r + 2] = id of the REST run;  then the slots.
    //                 Run id = r; 0 = the empty run.
    // ---------------------------------------------------------------------------------------------------

    public static final int RUN_H = 2;
    public static final int RUN_LEN_SHIFT = 8;
    public static final int RUN_FAR = 1;
    /**
     * Header bit 3: some slot of the run, or its REST run, holds a value with flag OPEN (set by
     * {@code Runs}, M1; request of 2026-11-24). A function of the content, not part of the run's identity:
     * closures and suspensions take their OPEN flag from it with one load. {@code runLen}, {@code runKind}
     * and {@code runHeaderWords} ignore it; a comparison of headers masks it out.
     */
    public static final int RUN_OPEN = 1 << 3;
    /**
     * Header bits 4..7 of a FRAME or REST run: the number of slots that are not {@link #HOLE}, saturating at
     * 15 (set by {@code Runs}, M1; announced 2026-11-24). Like {@link #RUN_OPEN} a function of the content,
     * not part of the run's identity; 0 in a SPINE run.
     */
    public static final int RUN_CNT_SHIFT = 4;
    public static final int RUN_CNT_MASK = 0xF;
    /** The header bits that are functions of the content ({@link #RUN_OPEN} and the count): masked out when two headers are compared. */
    public static final int RUN_DERIVED = RUN_OPEN | RUN_CNT_MASK << RUN_CNT_SHIFT;
    public static final int RUN_KIND_SHIFT = 1;
    public static final int RUN_KIND_MASK = 3;
    public static final int RUN_FRAME = 0;
    public static final int RUN_SPINE = 1;
    public static final int RUN_REST = 2;
    public static final int RUN_HASH = 1;
    public static final int RUN_REST_WORD = 2;
    /** The empty run. The run slab starts at word 1 so that no run has id 0. */
    public static final int RUN_EMPTY = 0;
    public static final int FIRST_RUN = 1;
    /** Run slab limit in words (section 9). */
    public static final int RUN_WORDS_MAX = 1 << 28;
    /** Mask bit 63 of a term: some index >= 63 is read (a far frame). */
    public static final long MASK_FAR = 1L << 63;
    public static final int NEAR_SLOTS = 63;

    public static int runHeader(int len, int kind, boolean far) {
        return len << RUN_LEN_SHIFT | kind << RUN_KIND_SHIFT | (far ? RUN_FAR : 0);
    }

    public static int runLen(int hdr) {
        return hdr >>> RUN_LEN_SHIFT;
    }

    public static int runKind(int hdr) {
        return (hdr >>> RUN_KIND_SHIFT) & RUN_KIND_MASK;
    }

    /** Header words of a run: 2, or 3 for a far frame. */
    public static int runHeaderWords(int hdr) {
        return RUN_H + (hdr & RUN_FAR);
    }

    // ---------------------------------------------------------------------------------------------------
    // 1.6 Intern tables vtab / rtab: word = tag:4 (31..28) | index:28; 0 = empty. Linear probing from
    //     slot = hash & (capacity - 1); tag = hash >>> 28 (bits never used for the slot index, since a
    //     table has at most 2^28 slots). Load <= 0.5. For rtab the index is the run id.
    // ---------------------------------------------------------------------------------------------------

    public static final int TAB_INDEX_MASK = 0x0FFF_FFFF;
    public static final int TAB_TAG_SHIFT = 28;

    public static int tabWord(int hash, int index) {
        return (hash & ~TAB_INDEX_MASK) | index;
    }

    // ---------------------------------------------------------------------------------------------------
    // 1.5 / 1.6 Pair table: 4 ints per entry, lo ref | hi ref | status bits | 0; refs ordered as UNSIGNED
    //     32-bit numbers (an immediate has bit 31 set); lo == 0 means empty (NONE is never compared).
    // ---------------------------------------------------------------------------------------------------

    public static final int PAIR_WORDS = 4;
    public static final int PAIR_SHIFT = 2;
    public static final int PAIR_LO = 0;
    public static final int PAIR_HI = 1;
    public static final int PAIR_STATUS = 2;
    public static final int POS = 1;
    public static final int NEG = 2;
    public static final int FAILED = 4;
    /**
     * [eager] ({@code Op.SW_EAGER}) Pair status bit, no memo: the pair is recorded as a mode-dependent point of the
     * plain phase of a declaration that defers its eager comparisons ({@code Machine.erNote}); only stops a
     * second record of the same pair.
     */
    public static final int NOTED = 8;

    // ---------------------------------------------------------------------------------------------------
    // 1.5 Direct-mapped caches. Every cell has 4 words and carries a stamp (the session epoch, or the
    //     declaration rank for UNDEC); a cell whose stamp differs is empty. log2 sizes in cells.
    // ---------------------------------------------------------------------------------------------------

    public static final int CELL_WORDS = 4;
    public static final int CELL_SHIFT = 2;
    /** IC: (site, epoch, key, value); site = block << 12 | pc. */
    public static final int IC_LOG2 = 14;
    public static final int IC_SITE = 0;
    public static final int IC_EPOCH = 1;
    public static final int IC_KEY = 2;
    public static final int IC_VALUE = 3;
    /** LSUB: (level or list id, ls, epoch, result). Lists and levels use separate tables of this shape. */
    public static final int LSUB_LOG2 = 12;
    public static final int LSUB_KEY = 0;
    public static final int LSUB_LS = 1;
    public static final int LSUB_EPOCH = 2;
    public static final int LSUB_VALUE = 3;
    /** LIMP: (fact or code record, pool slot, epoch, session level id). */
    public static final int LIMP_LOG2 = 10;
    public static final int LIMP_RECORD = 0;
    public static final int LIMP_SLOT = 1;
    public static final int LIMP_EPOCH = 2;
    public static final int LIMP_VALUE = 3;
    /** SIGT: (CONST record, epoch, mask low, mask high). */
    public static final int SIGT_LOG2 = 10;
    public static final int SIGT_CONST = 0;
    public static final int SIGT_EPOCH = 1;
    public static final int SIGT_LO = 2;
    public static final int SIGT_HI = 3;
    /** FV0: (type value, epoch, FVAR(type, 0), unused). */
    public static final int FV0_LOG2 = 10;
    public static final int FV0_TYPE = 0;
    public static final int FV0_EPOCH = 1;
    public static final int FV0_VAR = 2;
    /** UNDEC: (lo ref, hi ref, declaration stamp, log2 of the largest budget that ended in U). */
    public static final int UNDEC_LOG2 = 12;
    public static final int UNDEC_LO = 0;
    public static final int UNDEC_HI = 1;
    public static final int UNDEC_STAMP = 2;
    public static final int UNDEC_LOG = 3;

    // ---------------------------------------------------------------------------------------------------
    // 3.5 Fact records (words in the shared chunks)
    // ---------------------------------------------------------------------------------------------------

    /** f0: flags | shape:4 (bits 7..4). */
    public static final int F_FLAGS = 0;
    /** f1: arity:16 (31..16) | lamArity:16 (15..0). */
    public static final int F_ARITY = 1;
    public static final int F_READS = 2;
    public static final int F_UNREAD = 4;
    public static final int F_MANIFEST = 6;
    public static final int F_RLVL = 8;
    public static final int F_SHAPE_OPERAND = 9;
    /** f10..: n = min(arity, 64); slvl[0..n); dep[0..n) two words each; level pool; case tree. */
    public static final int F_SLVL = 10;
    public static final int FACT_POSITIONS = 64;
    /** f0 bit 0: the derivation failed; consumers use no fact of this record. */
    public static final int F_NONE = 1;
    public static final int F_SHAPE_SHIFT = 4;
    public static final int F_SHAPE_MASK = 0xF;
    public static final int SHAPE_NONE = 0;
    public static final int SHAPE_PICK = 1;
    public static final int SHAPE_PROJARG = 2;
    public static final int SHAPE_CTORBODY = 3;
    public static final int SHAPE_CASESON = 4;
    public static final int SHAPE_MATCHER = 5;
    /** Level refs inside fact and code records: 0 = UNKNOWN | BASE + base level id | POOL + pool offset. */
    public static final int LREF_UNKNOWN = 0;
    public static final int LREF_BASE = 1 << 30;
    public static final int LREF_POOL = 2 << 30;
    public static final int LREF_TAG_MASK = 3 << 30;
    public static final int LREF_PAYLOAD_MASK = ~LREF_TAG_MASK;

    // ---------------------------------------------------------------------------------------------------
    // 6.1 Comparison plans CONG(c): per position  class:3 (2..0) | UNREAD (bit 3) | probe rank:6 (9..4)
    // ---------------------------------------------------------------------------------------------------

    public static final int CONG_CLASS_MASK = 7;
    public static final int CONG_SAME = 0;
    public static final int CONG_TYPE = 1;
    public static final int CONG_PROOF = 2;
    public static final int CONG_PROOFL = 3;
    public static final int CONG_UNREAD = 8;
    public static final int CONG_RANK_SHIFT = 4;
    public static final int CONG_RANK_MASK = 0x3F;
    /** Block flag of a CONG block: the prop mask does not depend on ls. */
    public static final int CONG_PROP_STATIC = 1;

    // ---------------------------------------------------------------------------------------------------
    // 3.3 Structural levels in records (postfix encoding in a level pool)
    // ---------------------------------------------------------------------------------------------------

    public static final int LV_ZERO = 0;
    public static final int LV_SUCC = 1;
    public static final int LV_MAX = 2;
    public static final int LV_IMAX = 3;
    /** Followed by one word: the name id. */
    public static final int LV_PARAM = 4;
}

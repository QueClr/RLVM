package rlvm.machine;

/**
 * Constants only (ISA 13.3, "Op"): the signals and the depth bound of the host control convention
 * ({@code docs/design/review/stepper-convention.md}, rules H1-H12), op numbers, families, frame keys and
 * the payloads of the frames the shared control code owns, the instruction encoding of code objects,
 * operand modes, roles, modes, policies, budgets, limits, staging switches and debug modes.
 *
 * <p>FROZEN. Owner: work package M6 (shared file). A change is requested through
 * {@code work/status/requests.log} ("Mx -> M6: ...") and recorded in the change log of ISA section 15.
 *
 * <p><b>Frames</b> (ISA 1.3, convention H6): payload {@code p0 .. p(n-1)}, then the header word
 * {@code key:12 (31..20) | 0:4 | n:16}, {@code key = op << 4 | st}. The payload of {@code J[k]} is the
 * parameter list of the continuation step {@code k_Jk} (without {@code m}, {@code r}, {@code d}); the payload of
 * {@code J[0]} is the operand list of the entry step. Each family class defines the keys of its own
 * resume states as constants, e.g. {@code static final int K_CONV_3 = Op.K_CONV | 3;}; nobody but the
 * owner of a judgment writes or reads its frames.
 */
public final class Op {
    private Op() {}

    // ---------------------------------------------------------------------------------------------------
    // Convention H: signals, depth bound (stepper-convention.md H5, H9)
    // ---------------------------------------------------------------------------------------------------

    /** The judgment just called is suspended; its frames are in the side buffer. Write your frame, return SUSP. */
    public static final int SUSP = 0x7FFF_FFFF;
    /** A recoverable failure (kernel error, E_STEPS, E_LIMIT; {@code m.err} holds the code). Return it; write nothing. */
    public static final int ABORT = 0x7FFF_FFFE;
    /** {@code fault(E)} of ISA 1.4 ({@code m.err} holds the code). Return it; write nothing; no TRY intercepts it. */
    public static final int FATAL = 0x7FFF_FFFD;
    /** {@code r >= SIG} is the one test on the normal path: no value ref, truth value or level id reaches it. */
    public static final int SIG = FATAL;
    /**
     * "No inline answer" of an {@code Ops.xxxFast} method. It lies directly below the signals, so that
     * {@code r >= MISS} separates "a result" from "miss or signal". (ISA 13.3 said -1, which is the
     * immediate 2^31 - 1; see the change log of section 15.)
     */
    public static final int MISS = 0x7FFF_FFFC;

    /**
     * Default host depth bound D (H9). {@code Machine.hostDepth} holds the bound in force; 0 = pure worklist
     * machine. 128, not the 64 of the convention document (changed 2026-11-24 on M8's measurement of real
     * checking: on Init D = 64 writes 185,128 frames for 79.2 M call events = 0.234%, above the 0.1% bound
     * of H12 item 2; D = 128 writes 32,274 = 0.041%, D = 256 13,739 = 0.017%; ticks, call events and
     * outcomes are identical under all three, and CPU time on Init cannot be told apart between 64 and
     * 256 in the arena-shaped container: 15.0 / 17.0 s against 15.9 / 16.6 s, NOTES-M6 F22).
     */
    public static final int HOST_DEPTH = 128;
    /** A bound that is never reached: plain host recursion (test configuration only). */
    public static final int HOST_DEPTH_UNBOUNDED = Integer.MAX_VALUE;

    /** True for SUSP, ABORT, FATAL. */
    public static boolean isSignal(int r) {
        return r >= SIG;
    }

    // ---------------------------------------------------------------------------------------------------
    // Frame header
    // ---------------------------------------------------------------------------------------------------

    public static final int KEY_SHIFT = 20;
    public static final int N_MASK = 0xFFFF;
    public static final int STATES_PER_OP = 16;
    /** Largest payload a frame may have (the side buffer is sized by it, H6). */
    public static final int MAX_PAYLOAD = 24;

    public static int key(int op, int st) {
        return op << 4 | st;
    }

    public static int header(int key, int n) {
        return key << KEY_SHIFT | n;
    }

    public static int keyOf(int hdr) {
        return hdr >>> KEY_SHIFT;
    }

    public static int opOf(int key) {
        return key >>> 4;
    }

    public static int stateOf(int key) {
        return key & 15;
    }

    public static int payloadWords(int hdr) {
        return hdr & N_MASK;
    }

    /** Family of a key = high nibble of its op. */
    public static int familyOf(int key) {
        return key >>> 8;
    }

    // ---------------------------------------------------------------------------------------------------
    // Families and frame ops (ISA 8.2 table, 13.3)
    // ---------------------------------------------------------------------------------------------------

    public static final int FAM_EV = 1;
    public static final int FAM_RD = 2;
    public static final int FAM_CV = 3;
    public static final int FAM_TY = 4;
    public static final int FAM_CODE = 5;
    public static final int FAM_CTL = 6;
    /** Frames of toy judgments in tests and of experiments; resumed through {@code Machine.ext}. */
    public static final int FAM_EXT = 7;

    // EV (class Eval, M2)
    public static final int EVAL = 0x10;
    public static final int FORCE = 0x11;
    public static final int APPLY = 0x12;
    public static final int INST = 0x13;
    /** Additions of the skeleton: ARGV and DOM are judgments of their own under convention H. */
    public static final int ARGV = 0x14;
    public static final int DOM = 0x15;
    // RD (classes Reduce, NatRed, StrRed, Rules; M3)
    public static final int DELTA = 0x20;
    public static final int CORE = 0x21;
    public static final int WHNF = 0x22;
    public static final int IOTA = 0x23;
    public static final int QUOTR = 0x24;
    public static final int RULES = 0x25;
    public static final int NATF = 0x26;
    /** Addition of the skeleton: OFFSET calls CONV on the peeled pair, so it has a frame. */
    public static final int OFFSET_F = 0x27;
    // CV (classes Conv, Cargs; M4)
    public static final int CONV = 0x30;
    /** Second op of the CONV state machine (states c8..rec), for owners who need more than 16 states. */
    public static final int CONV2 = 0x31;
    public static final int CARGS = 0x32;
    public static final int CCLO = 0x33;
    public static final int CBIND = 0x34;
    public static final int LAZYD = 0x35;
    public static final int PROJD = 0x36;
    public static final int PIRR = 0x37;
    public static final int ETA = 0x38;
    public static final int ETAS = 0x39;
    public static final int UNITL = 0x3A;
    /** Addition of the skeleton: STRX calls WHNF and CONV. */
    public static final int STRX_F = 0x3B;
    /**
     * Addition (request of M4, 2026-11-24): one {@code lazy_delta_reduction_step} (R02 8.4), a judgment of
     * its own because both LAZYD and PROJD call it (H2: a frame names one continuation).
     */
    public static final int LSTEP = 0x3C;
    // TY (classes Infer, Decl, Facts; M5)
    public static final int INFER = 0x40;
    public static final int CHECK = 0x41;
    public static final int TWALK = 0x42;
    public static final int VTYPE = 0x43;
    public static final int IPROJ = 0x44;
    public static final int SIGD = 0x45;
    /** Additions of the skeleton: ENSORT, ENPI and ISPROP call WHNF / VTYPE, so they have frames. */
    public static final int ENSORT_F = 0x46;
    public static final int ENPI_F = 0x47;
    public static final int ISPROP_F = 0x48;
    /** Second and third op of INFER / CHECK state machines, for owners who need more than 16 states. */
    public static final int INFER2 = 0x49;
    public static final int CHECK2 = 0x4A;
    public static final int VTYPE2 = 0x4B;
    public static final int IPROJ2 = 0x4C;
    // CODE (class Code, M7)
    public static final int ACT = 0x50;
    /** MATCH is bounded and frame-free in the ISA; the op is reserved for an owner who needs a frame. */
    public static final int MATCH_F = 0x51;
    // CTL (class Frames, M6)
    public static final int TRY = 0x60;
    public static final int ERF = 0x61;
    public static final int DECL = 0x62;
    // EXT: 0x70 .. 0x7F

    /** State-0 keys (the frame of a judgment that was spilled before it started, H4 step 3). */
    public static final int K_EVAL = EVAL << 4;
    public static final int K_FORCE = FORCE << 4;
    public static final int K_APPLY = APPLY << 4;
    public static final int K_INST = INST << 4;
    public static final int K_ARGV = ARGV << 4;
    public static final int K_DOM = DOM << 4;
    public static final int K_DELTA = DELTA << 4;
    public static final int K_CORE = CORE << 4;
    public static final int K_WHNF = WHNF << 4;
    public static final int K_IOTA = IOTA << 4;
    public static final int K_QUOTR = QUOTR << 4;
    public static final int K_RULES = RULES << 4;
    public static final int K_NATF = NATF << 4;
    public static final int K_OFFSET = OFFSET_F << 4;
    public static final int K_CONV = CONV << 4;
    public static final int K_CONV2 = CONV2 << 4;
    public static final int K_CARGS = CARGS << 4;
    public static final int K_CCLO = CCLO << 4;
    public static final int K_CBIND = CBIND << 4;
    public static final int K_LAZYD = LAZYD << 4;
    public static final int K_PROJD = PROJD << 4;
    public static final int K_PIRR = PIRR << 4;
    public static final int K_ETA = ETA << 4;
    public static final int K_ETAS = ETAS << 4;
    public static final int K_UNITL = UNITL << 4;
    public static final int K_STRX = STRX_F << 4;
    public static final int K_LSTEP = LSTEP << 4;
    public static final int K_INFER = INFER << 4;
    public static final int K_CHECK = CHECK << 4;
    public static final int K_TWALK = TWALK << 4;
    public static final int K_VTYPE = VTYPE << 4;
    public static final int K_IPROJ = IPROJ << 4;
    public static final int K_SIGD = SIGD << 4;
    public static final int K_ENSORT = ENSORT_F << 4;
    public static final int K_ENPI = ENPI_F << 4;
    public static final int K_ISPROP = ISPROP_F << 4;
    public static final int K_INFER2 = INFER2 << 4;
    public static final int K_CHECK2 = CHECK2 << 4;
    public static final int K_VTYPE2 = VTYPE2 << 4;
    public static final int K_IPROJ2 = IPROJ2 << 4;
    public static final int K_ACT = ACT << 4;
    public static final int K_MATCH = MATCH_F << 4;
    public static final int K_TRY = TRY << 4;
    public static final int K_ERF = ERF << 4;
    public static final int K_DECL = DECL << 4;
    public static final int K_EXT = 0x70 << 4;

    // ---------------------------------------------------------------------------------------------------
    // Payloads of the frames owned by the shared control code
    // ---------------------------------------------------------------------------------------------------

    /**
     * TRY [savedFuel hi, lo, budget hi, lo, savedSsp, savedXp, savedEp, savedRp, savedER]: written only when
     * the segment is suspended (H11). {@code given = min(budget, savedFuel)} is recomputed. Differs from ISA
     * 1.3 (revision 2): no prevTryp (not a register under H), the budget instead of the given amount,
     * savedRp added (register stack of code activations, H7).
     */
    public static final int TRY_N = 9;
    public static final int TRY_FUEL_HI = 0;
    public static final int TRY_FUEL_LO = 1;
    public static final int TRY_BUDGET_HI = 2;
    public static final int TRY_BUDGET_LO = 3;
    public static final int TRY_SSP = 4;
    public static final int TRY_XP = 5;
    public static final int TRY_EP = 6;
    public static final int TRY_RP = 7;
    public static final int TRY_ER = 8;
    /** ERF [savedER]. */
    public static final int ERF_N = 1;
    /** ACT [blk, pc, dst, argRun, capRun, ls, rbase]: the registers live in the register stack RS (H7). */
    public static final int ACT_N = 7;
    public static final int ACT_BLK = 0;
    public static final int ACT_PC = 1;
    public static final int ACT_DST = 2;
    public static final int ACT_ARGS = 3;
    public static final int ACT_CAPS = 4;
    public static final int ACT_LS = 5;
    public static final int ACT_RBASE = 6;

    // ---------------------------------------------------------------------------------------------------
    // Code-only instructions (never frames), 13.3. A judgment used as a code instruction has the op
    // number of its frame.
    // ---------------------------------------------------------------------------------------------------

    public static final int MOV = 0x80;
    public static final int JMP = 0x81;
    public static final int BEQ = 0x82;
    public static final int BNE = 0x83;
    public static final int BKIND = 0x84;
    public static final int BUNREAD = 0x85;
    public static final int SWITCH = 0x86;
    public static final int FIELDS = 0x87;
    public static final int CALL = 0x88;
    public static final int TAILCALL = 0x89;
    public static final int RET = 0x8A;
    public static final int OPEN = 0x8B;
    public static final int CLOSE = 0x8C;
    public static final int TRYI = 0x8D;
    public static final int REQUIRE = 0x8E;
    public static final int FAIL = 0x8F;
    public static final int FAULT = 0x90;
    public static final int TICK = 0x91;
    public static final int MEMOGET = 0x92;
    public static final int MEMOPUT = 0x93;
    public static final int PAIRGET = 0x94;
    public static final int PAIRPUT = 0x95;
    public static final int KSORT = 0xA0;
    public static final int KCONST = 0xA1;
    public static final int KLIT = 0xA2;
    public static final int KVAL = 0xA3;
    public static final int MKAPP = 0xA4;
    public static final int MKLAM = 0xA5;
    public static final int MKPI = 0xA6;
    public static final int MKSUSP = 0xA7;
    public static final int MKPROJ = 0xA8;
    public static final int NSUCC = 0xA9;
    public static final int PEEK = 0xAA;
    public static final int TAILAPPLY = 0xB0;
    public static final int NATOP = 0xB1;
    public static final int NATFOLD = 0xB2;
    public static final int STRVIEW = 0xB3;
    public static final int LVLEQ = 0xB4;
    public static final int MATCH = 0xB5;
    public static final int OFFSET = 0xB6;
    public static final int STRX = 0xB7;
    public static final int ENSORT = 0xB8;
    public static final int ENPI = 0xB9;
    public static final int ISPROP = 0xBA;
    public static final int TARG = 0xBB;
    public static final int TRET = 0xBC;
    public static final int CHKCONST = 0xBD;
    public static final int CHKLVL = 0xBE;
    public static final int OP_COUNT = 256;

    // ---------------------------------------------------------------------------------------------------
    // 3.3 Block and instruction encoding
    // ---------------------------------------------------------------------------------------------------

    /** Instruction word: op = w >>> 24, d = (w >>> 16) & 255, n = (w >>> 8) & 255, x = w & 255; then n operand words. */
    public static int insn(int op, int d, int n, int x) {
        return op << 24 | d << 16 | n << 8 | x;
    }

    public static int insnOp(int w) {
        return w >>> 24;
    }

    public static int insnD(int w) {
        return (w >>> 16) & 255;
    }

    public static int insnN(int w) {
        return (w >>> 8) & 255;
    }

    public static int insnX(int w) {
        return w & 255;
    }

    /** Operand: mode = o >>> 28, payload = o & 0x0FFFFFFF. */
    public static final int OPND_SHIFT = 28;
    public static final int OPND_PAYLOAD_MASK = 0x0FFF_FFFF;
    public static final int MODE_R = 0;
    public static final int MODE_A = 1;
    public static final int MODE_C = 2;
    public static final int MODE_K = 3;
    public static final int MODE_I = 4;
    public static final int MODE_V = 5;
    public static final int MODE_T = 6;
    public static final int MODE_L = 7;
    public static final int MODE_LL = 8;
    public static final int MODE_D = 9;
    public static final int MODE_B = 10;
    public static final int MODE_M = 11;

    public static int operand(int mode, int payload) {
        return mode << OPND_SHIFT | payload;
    }

    /** Block header: hdr0 = len:20 | nreg:8 | role:4; hdr1 = decl row; hdr2 = arity:12 | flags:12 | nentry:8; hdr3 = facts index. */
    public static final int BLK_HDR0 = 0;
    public static final int BLK_ROW = 1;
    public static final int BLK_HDR2 = 2;
    public static final int BLK_FACTS = 3;
    public static final int BLK_ENTRIES = 4;
    public static final int BLKF_LEAF = 1;
    public static final int BLKF_HAS_FAR = 2;

    // roles
    public static final int ROLE_TELE = 1;
    public static final int ROLE_BODY = 2;
    public static final int ROLE_RULE = 3;
    public static final int ROLE_FIELDS = 4;
    public static final int ROLE_CONG = 5;
    public static final int ROLE_RULES = 6;
    public static final int ROLE_COUNT = 7;

    // ---------------------------------------------------------------------------------------------------
    // Modes, policies
    // ---------------------------------------------------------------------------------------------------

    /** INFER mode. */
    public static final int ONLY = 0;
    public static final int CHECKED = 1;
    /** CORE mode. */
    public static final int FULL = 0;
    public static final int CHEAP = 1;
    /** CARGS policy. */
    public static final int POL_APP = 0;
    public static final int POL_SAMEDEF = 1;

    /**
     * The shortcuts of the fall-through table (ISA 6.3), as indices of the probe-outcome statistics
     * ({@code Machine.probe(B1, outcome)}; ISA 14.5). {@code B1} is row B1, ... {@code B12} row B12.
     */
    public static final int B1 = 1;
    public static final int B2 = 2;
    public static final int B3 = 3;
    public static final int B4 = 4;
    public static final int B5 = 5;
    public static final int B6 = 6;
    public static final int B7 = 7;
    public static final int B8 = 8;
    public static final int B9 = 9;
    public static final int B10 = 10;
    public static final int B11 = 11;
    public static final int B12 = 12;
    public static final int SHORTCUT_COUNT = 13;

    // ---------------------------------------------------------------------------------------------------
    // Budgets (6.2) and limits (section 9)
    // ---------------------------------------------------------------------------------------------------

    public static final long B_SAMEHEAD = 16384;
    public static final long B_SAMEHEAD_ABBREV = 1024;
    public static final long B_PAIR = 256;
    public static final long B_CCLO = 1024;
    public static final long B_SIGD = 65536;
    public static final int MATCH_NODES = 64;
    /** The tick slow path runs whenever {@code (fuel & POLL_MASK) == POLL_MASK} after the decrement. */
    public static final long POLL_MASK = 4095;
    public static final int ELAB_AT = 10;
    /** "No step limit" (6.2: 2^62), so that tick arithmetic cannot overflow. */
    public static final long FUEL_UNLIMITED = 1L << 62;

    public static final int W_MAX = 1 << 28;
    public static final int SPINE_MAX = 65535;
    public static final int BINDERS_MAX = 65535;
    public static final int NREG_MAX = 255;

    // ---------------------------------------------------------------------------------------------------
    // Staging switches (ISA section 11). ALL OFF by default: v0 is the generic tier, the complete
    // procedure, every non-leaf node tabled, session = one declaration. Machine.sw holds the set.
    // ---------------------------------------------------------------------------------------------------

    /** Step 1: tabling gated by the node flags SHARED / BODY / INVARIANT (off: every non-leaf node is tabled). */
    public static final int SW_GATED_TABLING = 1 << 0;
    /** Step 1: shared check facts PCHK. */
    public static final int SW_PCHK = 1 << 1;
    /** Step 1: B9, expected-type checking of lambdas and applications. */
    public static final int SW_EXPECT = 1 << 2;
    /** Step 1: sessions that survive a declaration (off: the facade resets after every declaration). */
    public static final int SW_WARM = 1 << 3;
    /** Step 2: fact records, comparison plans (B3, B4), static PROOF (B7), the ETAS mask. */
    public static final int SW_FACTS = 1 << 4;
    /** Step 3: K1 TELE blocks, MATCH (B5), the IC table. */
    public static final int SW_TELE = 1 << 5;
    /** Step 4: K1 BODY / RULE blocks, LEAF blocks (B11). */
    public static final int SW_CODE = 1 << 6;
    /** Step 4: CASESON / MATCHER case trees (B8). */
    public static final int SW_CASETREES = 1 << 7;
    /** Step 5: speculative probes: TRY, B1, B2, B12, pass B of CARGS, UNDEC. */
    public static final int SW_PROBES = 1 << 8;
    /** Later: K2 derived trees. */
    public static final int SW_K2 = 1 << 9;
    /** H8 item 3: the inert-nest loop of EVAL (a per-platform tuning decision, results identical). */
    public static final int SW_INERT_LOOP = 1 << 10;
    /**
     * Experiment switches, two per package (wave 6; names {@code m1a, m1b, m2a .. m7b}): a package puts a
     * new optimisation that changes behaviour behind one of ITS OWN two bits without asking M6, measures
     * and gates it, and asks M6 for a named switch (or for the default) when it stays. They are never part
     * of {@code all} or of the default set, and {@code rlvm-machine/SWITCHES.md} lists what each one
     * currently guards (the owning package keeps its lines up to date through its NOTES file).
     */
    public static final int SW_M1A = 1 << 11;
    public static final int SW_M1B = 1 << 12;
    public static final int SW_M2A = 1 << 13;
    public static final int SW_M2B = 1 << 14;
    public static final int SW_M3A = 1 << 15;
    public static final int SW_M3B = 1 << 16;
    public static final int SW_M4A = 1 << 17;
    public static final int SW_M4B = 1 << 18;
    public static final int SW_M5A = 1 << 19;
    public static final int SW_M5B = 1 << 20;
    public static final int SW_M7A = 1 << 21;
    public static final int SW_M7B = 1 << 22;
    /**
     * The upper tier of {@code rlvm-truffle} (request of T2, 2026-11-24): K1 blocks that are hot may be run by
     * the tier a {@code Tier.Factory} installs (hook in {@code Code}, M7). Without that module on the class
     * path the switch changes nothing. Not part of {@code all}.
     */
    public static final int SW_T2 = 1 << 23;
    /**
     * Conversion (request of M4, 2026-11-24): when exactly one side of a lazy-delta step unfolds and the other
     * side is weak-head normal, the unfolding side is reduced by one WHNF instead of one DELTA + cheap CORE per
     * LSTEP ({@code Conv.lstep}). Off by default; not part of {@code all}.
     */
    public static final int SW_LAZY_EVAL = 1 << 24;
    /**
     * Universe levels (owner decision of 2026-11-24, OWNER-NOTES item 11): OFF = levels are compared as the
     * official kernel compares them (the default of both engines); ON = the complete relation of
     * {@code rlvm-level}'s canonical forms ({@code Levels.lvlEq / listsEq}, M1). Not part of {@code all};
     * never on in strict mode (like every switch).
     */
    public static final int SW_LEVELS_COMPLETE = 1 << 25;
    /**
     * Names for experiments that their owners consider ready (2026-11-24): the SAME bits as the experiment
     * switches they grew in, so that no code moves; {@code Debug} reads and prints the names. They get bits
     * of their own when they enter the default set.
     * {@code match} = {@code m5a}: MATCH of a telescope domain against the argument's type instead of building
     * the domain (M5; ISA 4.5.4, B5 on the generic tier). {@code joint} = {@code m5b}: joint telescope check
     * of a declaration's type and value (M5). {@code same} = {@code m4a}: the official {@code is_equiv} test
     * at the start of CONV, a bounded structural walk (M4, {@code Same}).
     */
    public static final int SW_MATCH = 1 << 19;
    public static final int SW_JOINT = 1 << 20;
    public static final int SW_SAME = 1 << 17;
    /**
     * Named by the integrator of wave 6 (2026-11-24) on their owners' requests, again the SAME bits:
     * {@code bodies} = {@code m2a}: the body of a beta step / of INST and a telescope's domains and results are
     * evaluated without a tabling record; a body that is a projection of a bound variable is MKPROJ of the
     * argument (M2, NOTES-M2 D21). {@code directrules} = {@code m3a}: a recursor rule of the plain form is ONE
     * APPLY of the minor premise ({@code Reduce.iotaDirect}, M3, NOTES-M3 D18). {@code lazycases} = {@code m7a}:
     * the casesOn tree as the unfolding step of lazy delta ({@code Code.rulesCasesOn}, M7).
     */
    public static final int SW_BODIES = 1 << 13;
    public static final int SW_DIRECT_RULES = 1 << 15;
    public static final int SW_LAZY_CASES = 1 << 21;
    /**
     * Every experiment switch that is still an experiment. The six bits that entered the default set on
     * 2026-11-24 ({@code bodies} = m2a, {@code directrules} = m3a, {@code same} = m4a, {@code match} = m5a,
     * {@code joint} = m5b, {@code lazycases} = m7a) are named switches now and no longer in this mask; their old
     * names are still read.
     */
    public static final int SW_EXPERIMENTS = SW_M1A | SW_M1B | SW_M2B | SW_M3B | SW_M4B | SW_M7B;
    /** The staging switches that {@code all} names: every named switch of ISA section 11 but {@code k2}; no experiment switch. */
    public static final int SW_ALL = (1 << 11) - 1 & ~SW_K2;
    /**
     * The switches a non-strict engine runs with when neither {@code -X machine.switches} nor the system
     * property {@code rlvm.machine.switches} is given ({@code rlvm-machine/SWITCHES.md} has the gate and the
     * measurement for every member). {@code -X machine.switches=none} is v0 (the generic tier, the complete
     * procedure). Since 2026-11-24 19:xxZ (integration of wave 6):
     * {@code warm,gated,expect} (step 1) + {@code bodies} + {@code pchk,match,joint} + {@code code,casetrees,lazycases}
     * (step 4) + {@code directrules} + {@code lazyeval} + {@code same}, with the machine's own session cap
     * ({@code MachineEngine.DEFAULT_SESSION_CAP}). Not in it: {@code facts}, {@code probes}, {@code tele}
     * (no measured gain), {@code k2} (a deviation from RECONCILIATION section 2 that waits for a decision),
     * {@code m2b} (undecided), {@code t2} (the Truffle tier: no gain), {@code levels-complete} (owner note 11).
     * The module's tests run with the system property {@code rlvm.machine.switches=none} (pom.xml), so a test
     * engine is v0 unless the test says otherwise.
     */
    public static final int SW_DEFAULT = SW_WARM | SW_GATED_TABLING | SW_EXPECT | SW_BODIES | SW_PCHK | SW_MATCH | SW_JOINT
            | SW_CODE | SW_CASETREES | SW_LAZY_CASES | SW_DIRECT_RULES | SW_LAZY_EVAL | SW_SAME;

    // ---------------------------------------------------------------------------------------------------
    // Debug modes (5.5). Machine.debug holds the set; a failed audit is fault(E_AUDIT).
    // ---------------------------------------------------------------------------------------------------

    public static final int DBG_MEMOCHECK = 1 << 0;
    public static final int DBG_SHORTCHECK = 1 << 1;
    public static final int DBG_POISON = 1 << 2;
    public static final int DBG_SCOPEAUDIT = 1 << 3;
    public static final int DBG_MASKAUDIT = 1 << 4;
    public static final int DBG_SHARECHECK = 1 << 5;
    public static final int DBG_PUBCHECK = 1 << 6;
    /** Record the frame trace (call and ret events) of 1.3 in {@code Machine.trace}. */
    public static final int DBG_TRACE = 1 << 7;

    /** Printable name of a frame op or instruction (diagnostics, statistics keys). */
    public static String name(int op) {
        switch (op) {
            case EVAL: return "EVAL";
            case FORCE: return "FORCE";
            case APPLY: return "APPLY";
            case INST: return "INST";
            case ARGV: return "ARGV";
            case DOM: return "DOM";
            case DELTA: return "DELTA";
            case CORE: return "CORE";
            case WHNF: return "WHNF";
            case IOTA: return "IOTA";
            case QUOTR: return "QUOTR";
            case RULES: return "RULES";
            case NATF: return "NATF";
            case OFFSET_F: return "OFFSET";
            case CONV: return "CONV";
            case CONV2: return "CONV2";
            case CARGS: return "CARGS";
            case CCLO: return "CCLO";
            case CBIND: return "CBIND";
            case LAZYD: return "LAZYD";
            case PROJD: return "PROJD";
            case PIRR: return "PIRR";
            case ETA: return "ETA";
            case ETAS: return "ETAS";
            case UNITL: return "UNITL";
            case STRX_F: return "STRX";
            case LSTEP: return "LSTEP";
            case INFER: return "INFER";
            case CHECK: return "CHECK";
            case TWALK: return "TWALK";
            case VTYPE: return "VTYPE";
            case IPROJ: return "IPROJ";
            case SIGD: return "SIGD";
            case ENSORT_F: return "ENSORT";
            case ENPI_F: return "ENPI";
            case ISPROP_F: return "ISPROP";
            case INFER2: return "INFER2";
            case CHECK2: return "CHECK2";
            case VTYPE2: return "VTYPE2";
            case IPROJ2: return "IPROJ2";
            case ACT: return "ACT";
            case MATCH_F: return "MATCH";
            case TRY: return "TRY";
            case ERF: return "ERF";
            case DECL: return "DECL";
            default: return op >= 0x70 && op < 0x80 ? "EXT" + (op - 0x70) : "OP" + Integer.toHexString(op);
        }
    }
}

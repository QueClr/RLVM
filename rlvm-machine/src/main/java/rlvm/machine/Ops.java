package rlvm.machine;

/**
 * The operation library (ISA 13.4): the one entry point for generic judgments, the code stepper and
 * tests. Thin static delegates to the classes that own the operations; an instruction's semantics
 * exists once. Shared file, owner M6: signatures change only through M6.
 *
 * <p>All methods take the {@link Machine} first and work on {@code int} refs; {@code xb} / {@code n}
 * denote n refs in scratch {@code X} at {@code xb}; an environment is {@code (eb, en)} in {@code es}.
 *
 * <p>Convention H changes three things against ISA 13.4 as first written (see section 15 there):
 * (1) a judgment is called through its entry step, {@code Ops.whnf(m, v, d + 1)}, whose first lines are
 * the fast path; the {@code ..Fast} methods remain as pure inline queries that answer or return
 * {@link Op#MISS}; (2) an operation that can fail returns a signal ({@code >= Op.SIG}) instead of
 * throwing - {@code open}, {@code tick}, {@code fail}, {@code fault} return an {@code int}; (3) there is
 * no {@code push<Op>} / {@code ret}: frames are written by {@code Machine.sigN} / {@code spillN} only.
 */
public final class Ops {
    private Ops() {}

    public static final int MISS = Op.MISS;

    // ---- values (M1; ISA 2.2, 4.2) ------------------------------------------------------------

    static int mkSort(Machine m, int level) {
        return Values.mkSort(m, level);
    }

    static int mkConst(Machine m, int row, int levelList) {
        return Values.mkConst(m, row, levelList);
    }

    static int mkNat(Machine m, long natHandle) {
        return Values.mkNat(m, natHandle);
    }

    static int mkStr(Machine m, int litIndex) {
        return Values.mkStr(m, litIndex);
    }

    static int mkLam(Machine m, int term, int frame, int ls) {
        return Values.mkLam(m, term, frame, ls);
    }

    static int mkPi(Machine m, int term, int frame, int ls) {
        return Values.mkPi(m, term, frame, ls);
    }

    static int mkPii(Machine m, int term, int frame, int ls) {
        return Values.mkPii(m, term, frame, ls);
    }

    static int mkSusp(Machine m, int term, int frame, int ls, boolean let) {
        return Values.mkSusp(m, term, frame, ls, let);
    }

    static int mkFVar(Machine m, int type, int k) {
        return Values.mkFVar(m, type, k);
    }

    static int mkNeu(Machine m, int head, int xb, int n) {
        return Values.mkNeu(m, head, xb, n);
    }

    static int mkApp(Machine m, int f, int xb, int n) {
        return Values.mkApp(m, f, xb, n);
    }

    static int mkProj(Machine m, int structRow, int field, int s) {
        return Values.mkProj(m, structRow, field, s);
    }

    static int nsucc(Machine m, int v) {
        return Values.nsucc(m, v);
    }

    static int peek(Machine m, int v) {
        return Values.peek(m, v);
    }

    static int kind(Machine m, int v) {
        return Values.kind(m, v);
    }

    static int flags(Machine m, int v) {
        return Values.flags(m, v);
    }

    static int memo(Machine m, int v, int slot) {
        return Values.memo(m, v, slot);
    }

    static void setMemo(Machine m, int v, int slot, int x) {
        Values.setMemo(m, v, slot, x);
    }

    static boolean sameLiteral(Machine m, int a, int b) {
        return Lits.sameContent(m, a, b);
    }


    // ---- runs and environments (M1; ISA 2.4) ----------------------------------------------------

    static int spine(Machine m, int xb, int n) {
        return Runs.spine(m, xb, n);
    }

    static int frame(Machine m, int xb, int n, int rest) {
        return Runs.frame(m, xb, n, rest);
    }

    static int prune(Machine m, int eb, int en, long mask) {
        return Runs.prune(m, eb, en, mask);
    }

    static int arg(Machine m, int run, int j) {
        return Runs.arg(m, run, j);
    }

    static int get(Machine m, int eb, int en, int i) {
        return Runs.get(m, eb, en, i);
    }

    static int push(Machine m, int eb, int en, int v) {
        return Runs.push(m, eb, en, v);
    }

    static int enter(Machine m, int frame) {
        return Runs.enter(m, frame);
    }


    // ---- levels (M1; ISA 2.7) ------------------------------------------------------------------

    static int substOf(Machine m, int constRef) {
        return Levels.substOf(m, constRef);
    }

    static int lsub(Machine m, int level, int ls) {
        return Levels.lsub(m, level, ls);
    }

    static int lsubList(Machine m, int list, int ls) {
        return Levels.lsubList(m, list, ls);
    }

    static boolean lvlEq(Machine m, int a, int b) {
        return Levels.lvlEq(m, a, b);
    }

    static boolean listsEq(Machine m, int a, int b) {
        return Levels.listsEq(m, a, b);
    }

    static int importLevel(Machine m, int record, int slot) {
        return Levels.importLevel(m, record, slot);
    }


    // ---- scope and budgets (M1, M6; ISA 1.4, 2.8, 4.1). open, tick, fail, fault return a signal or 0 / the value ----

    static int open(Machine m, int type) {
        return Scope.open(m, type);
    }

    static void close(Machine m, int n) {
        Scope.close(m, n);
    }

    static int tick(Machine m, int n) {
        return m.tick(n);
    }

    static int fail(Machine m, int err) {
        return m.fail(err);
    }

    static int fault(Machine m, int err) {
        return m.fault(err);
    }


    // ---- judgments: the inline part (answer or Op.MISS; never ticks, never calls a judgment) --------

    static int forceFast(Machine m, int v) {
        return Eval.forceFast(m, v);
    }

    static int whnfFast(Machine m, int v) {
        return Reduce.whnfFast(m, v);
    }

    static int coreFast(Machine m, int v, int mode) {
        return Reduce.coreFast(m, v, mode);
    }

    static int deltaFast(Machine m, int v) {
        return Reduce.deltaFast(m, v);
    }

    static int vtypeFast(Machine m, int v) {
        return Infer.vtypeFast(m, v);
    }

    static int convFast(Machine m, int a, int b) {
        return Conv.convFast(m, a, b);
    }

    static int ispropFast(Machine m, int type) {
        return Infer.ispropFast(m, type);
    }

    static int instFast(Machine m, int pi, int x) {
        return Eval.instFast(m, pi, x);
    }

    static long natop(Machine m, int op, long a, long b) {
        return NatRed.natop(m, op, a, b);
    }

    static int offsetFast(Machine m, int a, int b) {
        return NatRed.offsetFast(m, a, b);
    }

    static int strView(Machine m, int s) {
        return StrRed.strView(m, s);
    }


    // ---- judgments: the entry steps (convention H; d = host depth of the caller + 1). Result or signal ----

    static int eval(Machine m, int t, int eb, int en, int ls, int d) {
        return Eval.eval(m, t, eb, en, ls, d);
    }

    static int force(Machine m, int s, int d) {
        return Eval.force(m, s, d);
    }

    static int apply(Machine m, int f, int xb, int n, int d) {
        return Eval.apply(m, f, xb, n, d);
    }

    static int inst(Machine m, int pi, int x, int d) {
        return Eval.inst(m, pi, x, d);
    }

    static int argv(Machine m, int t, int eb, int en, int ls, int d) {
        return Eval.argv(m, t, eb, en, ls, d);
    }

    static int dom(Machine m, int v, int d) {
        return Eval.dom(m, v, d);
    }

    static int delta(Machine m, int v, int d) {
        return Reduce.delta(m, v, d);
    }

    static int core(Machine m, int v, int mode, int d) {
        return Reduce.core(m, v, mode, d);
    }

    static int whnf(Machine m, int v, int d) {
        return Reduce.whnf(m, v, d);
    }

    static int iota(Machine m, int v, int d) {
        return Reduce.iota(m, v, d);
    }

    static int quotr(Machine m, int v, int d) {
        return Reduce.quotr(m, v, d);
    }

    static int rules(Machine m, int v, int d) {
        return Rules.rules(m, v, d);
    }

    static int natFold(Machine m, int v, int d) {
        return NatRed.natFold(m, v, d);
    }

    static int offset(Machine m, int a, int b, int d) {
        return NatRed.offset(m, a, b, d);
    }

    static int conv(Machine m, int a, int b, int d) {
        return Conv.conv(m, a, b, d);
    }

    static int cargs(Machine m, int a, int b, int policy, int d) {
        return Cargs.cargs(m, a, b, policy, d);
    }

    static int cbind(Machine m, int a, int b, int d) {
        return Conv.cbind(m, a, b, d);
    }

    static int lazyd(Machine m, int a, int b, int d) {
        return Conv.lazyd(m, a, b, d);
    }

    static int infer(Machine m, int t, int eb, int en, int ls, int mode, int d) {
        return Infer.infer(m, t, eb, en, ls, mode, d);
    }

    static int check(Machine m, int t, int eb, int en, int ls, int expected, int d) {
        return Infer.check(m, t, eb, en, ls, expected, d);
    }

    static int vtype(Machine m, int v, int d) {
        return Infer.vtype(m, v, d);
    }

    static int ensort(Machine m, int type, int d) {
        return Infer.ensort(m, type, d);
    }

    static int enpi(Machine m, int type, int d) {
        return Infer.enpi(m, type, d);
    }

    static int isprop(Machine m, int type, int d) {
        return Infer.isprop(m, type, d);
    }


    // ---- pairs (M4; ISA 1.5) -------------------------------------------------------------------

    static int pairGet(Machine m, int a, int b) {
        return Pairs.status(m, a, b);
    }

    static void pairPut(Machine m, int a, int b, int bits) {
        Pairs.add(m, a, b, bits);
    }


    // ---- facts (M5) and plans, code (M7) --------------------------------------------------------

    static int facts(Machine m, int row) {
        return Facts.facts(m, row);
    }

    static long propMask(Machine m, int constRef) {
        return Facts.propMask(m, constRef);
    }

    static long unreadMask(Machine m, int row, int spineLen) {
        return Facts.unreadMask(m, row, spineLen);
    }

    static int staticProof(Machine m, int neu) {
        return Facts.staticProof(m, neu);
    }

    static int codeOf(Machine m, int row, int role) {
        return m.engine == null ? 0 : m.engine.code.blockOf(row, role);
    }

    static int match(Machine m, int pattern, int gb, int gn, int ls, int v) {
        return Match.match(m, pattern, gb, gn, ls, v);
    }
}

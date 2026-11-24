package rlvm.machine;

import rlvm.store.DeclKind;
import rlvm.store.DeclTable;
import rlvm.store.Kind;
import rlvm.store.Terms;

/**
 * Tier equivalence K0 = K1 for the roles of M7 (ISA section 11 "Tier equivalence", decision 6): for a
 * constant and argument values, the generic judgment and the code block must return the same value id.
 * Both run in the same session, so ids are comparable; where the generic walk itself is path-dependent
 * (a suspension whose value became known in between is seen through: ISA 2.1 PEEK) the comparison is up
 * to PEEK ({@code Eval.samePeekedSlow}), and such cases are counted separately.
 *
 * <p>The generic side always runs with every staging switch off (so that it stays K0 when the other
 * packages wire the K1 hooks); the code side calls the entry points of {@link Code} directly.
 */
final class M7Tier {

    static final int K1 = Op.SW_CODE | Op.SW_TELE | Op.SW_CASETREES;

    final Machine m;
    final DeclTable decls;
    final Terms terms;
    final CodeStore cs;
    /** A pool of open free variables (of type Prop), reused as argument values. */
    final int[] pool = new int[160];
    final StringBuilder failures = new StringBuilder();

    long cases;
    long identical;
    long upToPeek;
    long different;
    long k0Failed;
    long k1Failed;
    long skipped;

    M7Tier(Machine m) {
        this.m = m;
        this.decls = m.decls;
        this.terms = m.terms;
        this.cs = m.engine.code;
        int prop = Values.mkSort(m, m.levels.zero());
        for (int i = 0; i < pool.length; i++) {
            pool[i] = Scope.open(m, prop);
            if (pool[i] >= Op.SIG) {
                throw new IllegalStateException("cannot open variable " + i);
            }
        }
    }

    // ---- running a judgment of either tier -------------------------------------------------------------

    /** Completes a judgment called at depth 0; a signal is returned as it is (the stacks are restored by {@link #recover}). */
    int drive(int r) {
        return m.drive(r);
    }

    /** After a signal: put the machine stacks back to the marks (binders above the pool are closed). */
    private void recover(int xp, int ep, int rp, int ssp) {
        m.wp = 0;
        m.sp = m.S.length;
        m.closeBindersAbove(ssp);
        m.xp = xp;
        m.ep = ep;
        m.rp = rp;
        m.ER = 0;
        m.err = 0;
    }

    private String name(int row) {
        return m.store.names().toString(decls.name(row));
    }

    /** Records the outcome of one case: k0 and k1 are results or signals. */
    void compare(String what, int k0, int k1) {
        cases++;
        if (k0 >= Op.SIG || k1 >= Op.SIG) {
            if (k0 >= Op.SIG && k1 >= Op.SIG) {
                skipped++; // both tiers gave up (the arguments are variables of type Prop: not every application is meaningful)
                if (bothNotes < 12) {
                    bothNotes++;
                    both.append(what).append(": both failed, K0 err ").append(k0Err).append(" K1 err ").append(k1Err).append('\n');
                }
                return;
            }
            if (k0 >= Op.SIG) {
                k0Failed++;
            } else {
                k1Failed++;
            }
            note(what + ": K0 " + show(k0) + " K1 " + show(k1));
            return;
        }
        if (k0 == k1) {
            identical++;
        } else if (Eval.samePeekedSlow(m, k0, k1, 24)) {
            upToPeek++;
            if (peekNotes.length() < 4000) {
                peekNotes.append(what).append(": K0 ").append(show(k0)).append(" K1 ").append(show(k1)).append('\n');
            }
        } else {
            different++;
            note(what + ": K0 " + show(k0) + " K1 " + show(k1));
        }
    }

    /** The cases that were equal only up to PEEK (path dependence of the generic walk, ISA 2.1). */
    final StringBuilder peekNotes = new StringBuilder();

    private void note(String s) {
        if (failures.length() < 6000) {
            failures.append(s).append('\n');
        }
    }

    private String show(int v) {
        if (v >= Op.SIG) {
            return "signal " + (v == Op.SUSP ? "SUSP" : v == Op.ABORT ? "ABORT" : "FATAL") + " err " + lastErr;
        }
        String s = Show.value(m, v);
        return "#" + v + " " + (s.length() > 300 ? s.substring(0, 300) + ".." : s);
    }

    private int lastErr;
    private int k0Err;
    private int k1Err;
    private int bothNotes;
    final StringBuilder both = new StringBuilder();

    /** A K0 judgment: all switches off. */
    private int k0(int r, int xp, int ep, int rp, int ssp) {
        r = m.drive(r);
        if (r >= Op.SIG) {
            lastErr = m.err;
            k0Err = m.err;
            recover(xp, ep, rp, ssp);
        }
        return r;
    }

    String summary(String title) {
        return title + ": " + cases + " cases, " + identical + " identical, " + upToPeek + " equal up to PEEK, " + different
                + " different, " + k0Failed + " K0 failed, " + k1Failed + " K1 failed, " + skipped + " failed in both";
    }

    boolean clean() {
        return different == 0 && k1Failed == 0 && k0Failed == 0;
    }

    // ---- levels and constants ---------------------------------------------------------------------------

    /** The level list of a constant's own parameters (identity substitution) or, variant 1, of closed levels 0, 1, 2, .. */
    int levelsOf(int row, int variant) {
        int names = decls.levelParams(row);
        int n = decls.numLevelParams(row);
        int[] ids = new int[n];
        for (int i = 0; i < n; i++) {
            if (variant == 0) {
                ids[i] = m.levels.param(m.store.lists().get(names, i));
            } else {
                int l = m.levels.zero();
                for (int k = 0; k <= i % 3; k++) {
                    l = m.levels.succ(l);
                }
                ids[i] = l;
            }
        }
        return n == 0 ? 0 : m.lists.intern(ids, 0, n);
    }

    private int scratch(int n) {
        if (!m.ensureScratch(n)) {
            throw new IllegalStateException("no scratch");
        }
        int xb = m.xp;
        m.xp = xb + n;
        return xb;
    }

    // ---- BODY: DELTA of c a_1 .. a_n ----------------------------------------------------------------------

    /** Between two cases the machine holds nothing but the pool's binders (I-STACKS). */
    void assertIdle(String where) {
        if (m.rp != 0 || m.xp != 0 || m.ep != 0 || m.wp != 0 || m.ssp != pool.length) {
            throw new AssertionError("machine stacks not empty " + where + ": rp " + m.rp + " xp " + m.xp + " ep " + m.ep + " wp "
                    + m.wp + " ssp " + m.ssp);
        }
    }

    /** For every constant with a BODY block: K0 DELTA against the block (+ APPLY of an extra argument). */
    void bodies(int from, int to) {
        for (int row = from; row <= to; row++) {
            int k = decls.kind(row);
            if (k != DeclKind.DEFN && k != DeclKind.THM) {
                continue;
            }
            int blk = Elab.elaborate(m, row, Op.ROLE_BODY);
            if (blk == 0 || blk >= Op.SIG) {
                continue;
            }
            int arity = Code.arityOf(m, blk);
            for (int variant = 0; variant < 2; variant++) {
                if (variant == 1 && decls.numLevelParams(row) == 0) {
                    continue;
                }
                for (int extra = 0; extra < 2; extra++) {
                    body(row, blk, arity, extra, variant);
                }
            }
        }
    }

    private void body(int row, int blk, int arity, int extra, int variant) {
        int xp = m.xp;
        int ep = m.ep;
        int rp = m.rp;
        int ssp = m.ssp;
        int n = arity + extra;
        if (n > pool.length) {
            return;
        }
        int c = Values.mkConst(m, row, levelsOf(row, variant));
        int xb = scratch(n);
        System.arraycopy(pool, 0, m.X, xb, n);
        int v = Values.mkApp(m, c, xb, n);
        if (c >= Op.SIG || v >= Op.SIG || Values.kind(m, v) != Layout.NEU) {
            recover(xp, ep, rp, ssp);
            return;
        }
        int sw = m.sw;
        m.sw = K1;
        int k1 = m.drive(Code.callRun(m, blk, Levels.substOf(m, c), Values.b(m, v), 0));
        if (k1 < Op.SIG && extra > 0) {
            k1 = m.drive(Eval.apply(m, k1, xb + arity, extra, 0));
        }
        if (k1 >= Op.SIG) {
            lastErr = m.err;
            k1Err = m.err;
            recover(xp, ep, rp, ssp);
        } else if (m.rp != rp || m.xp != xb + n || m.ep != ep) {
            throw new AssertionError("I-STACKS broken by BODY(" + name(row) + "): rp " + m.rp + "/" + rp + " xp " + m.xp + "/"
                    + (xb + n) + " ep " + m.ep + "/" + ep);
        }
        m.sw = 0;
        int k0 = k0(Reduce.delta(m, v, 0), xp, ep, rp, ssp);
        m.sw = sw;
        m.xp = xp;
        compare("BODY(" + name(row) + ") variant " + variant + " extra " + extra, k0, k1);
    }

    // ---- RULE: the right-hand side applied to its arguments -------------------------------------------------

    void rules(int from, int to) {
        for (int row = from; row <= to; row++) {
            if (decls.kind(row) != DeclKind.REC) {
                continue;
            }
            int blk = Elab.elaborate(m, row, Op.ROLE_RULE);
            if (blk == 0 || blk >= Op.SIG) {
                continue;
            }
            int fixed = decls.recNumParams(row) + decls.recNumMotives(row) + decls.recNumMinors(row);
            for (int j = 0; j < decls.recRuleCount(row); j++) {
                if (!Code.hasEntry(m, blk, j)) {
                    continue;
                }
                int n = fixed + decls.recRuleFields(row, j);
                if (n > pool.length) {
                    continue;
                }
                int xp = m.xp;
                int ep = m.ep;
                int rp = m.rp;
                int ssp = m.ssp;
                int c = Values.mkConst(m, row, levelsOf(row, 0));
                int ls = Levels.substOf(m, c);
                int xb = scratch(n);
                System.arraycopy(pool, 0, m.X, xb, n);
                int sw = m.sw;
                m.sw = K1;
                int k1 = m.drive(Code.callRule(m, blk, j, ls, xb, n, 0));
                if (k1 >= Op.SIG) {
                    lastErr = m.err;
                    k1Err = m.err;
                    recover(xp, ep, rp, ssp);
                    m.xp = xb + n;
                } else if (m.rp != rp || m.xp != xb + n) {
                    throw new AssertionError("I-STACKS broken by RULE(" + name(row) + ", " + j + ")");
                }
                m.sw = 0;
                int f = k0(Eval.eval(m, decls.recRuleRhs(row, j), m.ep, 0, ls, 0), xp, ep, rp, ssp);
                m.xp = xb + n;
                int k0 = f >= Op.SIG ? f : k0(Eval.apply(m, f, xb, n, 0), xp, ep, rp, ssp);
                m.sw = sw;
                m.xp = xp;
                compare("RULE(" + name(row) + ", " + j + ")", k0, k1);
            }
        }
    }

    // ---- sub-blocks: APPLY / INST of the closure of a binder node ---------------------------------------------

    /** For every sub-block in the directory: the closure of its binder over variables, applied to j arguments. */
    void closures() {
        int blocks = cs.blockCount();
        for (int id = 1; id <= blocks; id++) {
            int[] c = cs.chunkOf(id);
            int o = cs.offsetOf(id);
            int h2 = c[o + Op.BLK_HDR2];
            if ((Code.flags(h2) & Code.BLKF_SUB) == 0) {
                continue;
            }
            int t = c[o + Op.BLK_FACTS];
            long mask = terms.fvMask(t);
            int len = 64 - Long.numberOfLeadingZeros(mask);
            int arity = Code.arity(h2);
            if (mask < 0 || len + arity + 1 > pool.length) {
                continue;
            }
            int xp = m.xp;
            int ep = m.ep;
            int rp = m.rp;
            int ssp = m.ssp;
            int fb = scratch(len);
            for (int j = 0; j < len; j++) {
                m.X[fb + j] = (mask >>> (len - 1 - j) & 1) != 0 ? pool[j] : Layout.HOLE;
            }
            int frame = Runs.frame(m, fb, len, Layout.RUN_EMPTY);
            boolean lam = terms.kind(t) == Kind.LAM;
            int clo = lam ? Values.mkLam(m, t, frame, 0) : Values.mkPi(m, t, frame, 0);
            if (frame >= Op.SIG || clo >= Op.SIG) {
                recover(xp, ep, rp, ssp);
                continue;
            }
            for (int j = 1; j <= arity; j++) {
                int xb = scratch(j);
                System.arraycopy(pool, len, m.X, xb, j);
                int sw = m.sw;
                m.sw = K1;
                int k1 = m.drive(Code.callClosure(m, id, j, frame, 0, xb, 0));
                if (k1 >= Op.SIG) {
                    lastErr = m.err;
                    k1Err = m.err;
                    recover(xp, ep, rp, ssp);
                    m.xp = xb + j;
                } else if (m.rp != rp || m.xp != xb + j) {
                    throw new AssertionError("I-STACKS broken by sub-block " + id);
                }
                m.sw = 0;
                int k0 = k0(lam ? Eval.apply(m, clo, xb, j, 0) : Eval.inst(m, clo, m.X[xb], 0), xp, ep, rp, ssp);
                m.sw = sw;
                m.xp = xb;
                compare((lam ? "APPLY" : "INST") + " of sub-block " + id + " (term " + t + ", " + j + " applied)", k0, k1);
            }
            m.xp = xp;
        }
    }

    // ---- RULES: WHNF of a case analysis on a constructor ---------------------------------------------------------

    /**
     * For every constant with a case tree: the application to variables in which the major premises that are
     * parameters of the constant (up to four: a tree may switch on several, also through inlined matchers) are
     * replaced by every combination of constructors of their inductive types applied to variables, and by a
     * variable (a stuck major); K0: WHNF by DELTA and IOTA; K1: RULES, then WHNF of the reduct (NONE: the tree
     * declined, which is always correct).
     */
    void caseTrees(int from, int to) {
        for (int row = from; row <= to; row++) {
            if (decls.kind(row) != DeclKind.DEFN) {
                continue;
            }
            int blk = Elab.elaborate(m, row, Op.ROLE_RULES);
            if (blk == 0 || blk >= Op.SIG) {
                continue;
            }
            int[] c = cs.chunkOf(blk);
            int o = cs.offsetOf(blk);
            int len = Code.len(c[o + Op.BLK_HDR0]);
            int arity = Code.arityOf(m, blk);
            // every "WHNF rM, A_i ; SWITCH rM, D(I), .." of the block: argument i is a major premise of type I
            int[] majors = new int[4];
            int[] inds = new int[4];
            int nm = 0;
            for (int pc = c[o + Op.BLK_ENTRIES]; pc < len; pc += 1 + (c[o + pc] >>> 8 & 255)) {
                int w = c[o + pc];
                if (w >>> 24 != Op.WHNF || pc + 4 >= len || c[o + pc + 2] >>> 24 != Op.SWITCH) {
                    continue;
                }
                int opnd = c[o + pc + 1];
                int i = opnd & Op.OPND_PAYLOAD_MASK;
                boolean seen = false;
                for (int k = 0; k < nm; k++) {
                    seen |= majors[k] == i;
                }
                if (opnd >>> Op.OPND_SHIFT == Op.MODE_A && i < arity && !seen && nm < 4) {
                    majors[nm] = i;
                    inds[nm++] = c[o + pc + 4] & Op.OPND_PAYLOAD_MASK;
                }
            }
            treeBlocks++;
            int[] choice = new int[nm];
            int cases = 0;
            do {
                for (int extra = 0; extra < 2; extra++) {
                    caseTree(row, arity, majors, inds, choice, nm, extra);
                }
            } while (++cases < 120 && next(choice, inds, nm));
        }
    }

    /** The next combination of constructor choices (choice[k] == number of constructors: a stuck major); false after the last. */
    private boolean next(int[] choice, int[] inds, int nm) {
        for (int k = 0; k < nm; k++) {
            if (++choice[k] <= decls.inductCtorCount(inds[k])) {
                return true;
            }
            choice[k] = 0;
        }
        return false;
    }

    long treeBlocks;

    private void caseTree(int row, int arity, int[] majors, int[] inds, int[] choice, int nm, int extra) {
        int xp = m.xp;
        int ep = m.ep;
        int rp = m.rp;
        int ssp = m.ssp;
        int n = arity + extra;
        int next = n + 1; // pool variables of the constructor arguments
        int[] mv = new int[nm];
        boolean ok = true;
        for (int k = 0; k < nm; k++) {
            mv[k] = pool[majors[k]];
            if (choice[k] < decls.inductCtorCount(inds[k])) {
                int ctor = decls.inductCtor(inds[k], choice[k]);
                int cn = decls.ctorNumParams(ctor) + decls.ctorNumFields(ctor);
                if (next + cn > pool.length) {
                    return;
                }
                int cc = Values.mkConst(m, ctor, levelsOf(ctor, 1));
                int cb = scratch(cn);
                System.arraycopy(pool, next, m.X, cb, cn);
                next += cn;
                mv[k] = Values.mkApp(m, cc, cb, cn);
                m.xp = xp;
                ok &= cc < Op.SIG && mv[k] < Op.SIG;
            }
        }
        int c = Values.mkConst(m, row, levelsOf(row, 0));
        if (n + 1 > pool.length) {
            return;
        }
        int xb = scratch(n);
        System.arraycopy(pool, 0, m.X, xb, n);
        for (int k = 0; k < nm; k++) {
            m.X[xb + majors[k]] = mv[k];
        }
        int v = Values.mkApp(m, c, xb, n);
        m.xp = xp;
        if (!ok || c >= Op.SIG || v >= Op.SIG || Values.kind(m, v) != Layout.NEU) {
            recover(xp, ep, rp, ssp);
            return;
        }
        // The tree runs first and its reduct is normalised after the K0 run: the K0 WHNF forces suspensions (the base
        // of a Nat.succ, to see whether it is a literal), and a tree that ran after it would be handed their values
        // by MKSUSP where K0 itself still built the suspension (ISA 2.1 PEEK: path dependence, not a tier difference).
        int sw = m.sw;
        m.sw = K1;
        int r = m.drive(Code.rules(m, v, 0));
        if (r >= Op.SIG) {
            lastErr = m.err;
            k1Err = m.err;
            recover(xp, ep, rp, ssp);
        } else if (m.rp != rp || m.xp != xp) {
            throw new AssertionError("I-STACKS broken by RULES(" + name(row) + ")");
        }
        m.sw = 0;
        int k0 = k0(Reduce.whnf(m, v, 0), xp, ep, rp, ssp);
        int k1 = r >= Op.SIG ? r : r == Layout.NONE ? k0 : k0(Reduce.whnf(m, r, 0), xp, ep, rp, ssp);
        m.sw = sw;
        if (r == Layout.NONE) {
            declined++;
        } else if (r < Op.SIG) {
            fired++;
        }
        compare("RULES(" + name(row) + ") constructors " + java.util.Arrays.toString(choice) + " extra " + extra, k0, k1);
    }

    long fired;
    long declined;

    // ---- TELE: the type of c x_0 .. x_(n-1) ------------------------------------------------------------------------

    long matchSites;
    long matchHits;
    long expectCases;
    long foreign;
    long foreignHits;
    private int missNotes;
    /** The last domain values seen: foreign values for the soundness test of MATCH. */
    private final int[] ring = new int[12];

    /**
     * For every constant with a TELE block: the telescope is opened generically (x_i : dom_i), then the
     * type of {@code c x_0 .. x_(k-1)} for k = arity, arity - 1 and 1: INFER of the application term in ONLY
     * and CHECKED mode against {@code Code.tele} on the same site, and VTYPE of the value against
     * {@code Code.teleValues}. The MATCH instructions of the block are tested on the way (the built domain
     * must match its own pattern).
     */
    void telescopes(int from, int to) {
        for (int row = from; row <= to; row++) {
            int blk = Elab.elaborate(m, row, Op.ROLE_TELE);
            if (blk == 0 || blk >= Op.SIG) {
                continue;
            }
            telescope(row, blk);
        }
    }

    private void telescope(int row, int blk) {
        int xp = m.xp;
        int ep = m.ep;
        int rp = m.rp;
        int ssp = m.ssp;
        int n = Code.arityOf(m, blk);
        int levels = levelsOf(row, 0);
        int c = Values.mkConst(m, row, levels);
        int sw = m.sw;
        m.sw = 0;
        // open the telescope with the generic judgments
        int p = k0(Infer.vtype(m, c, 0), xp, ep, rp, ssp);
        int eb = m.ep;
        if (!m.ensureEnv(n)) {
            throw new IllegalStateException("no environment");
        }
        boolean ok = p < Op.SIG;
        for (int i = 0; i < n && ok; i++) {
            p = k0(Infer.enpi(m, p, 0), xp, ep, rp, ssp);
            int dom = p >= Op.SIG ? p : k0(Eval.dom(m, p, 0), xp, ep, rp, ssp);
            int x = dom >= Op.SIG ? dom : Scope.open(m, dom);
            if (x >= Op.SIG) {
                ok = false;
                break;
            }
            m.es[eb + i] = x;
            m.ep = eb + i + 1;
            p = k0(Eval.inst(m, p, x, 0), xp, eb + i + 1, rp, m.ssp);
            ok = p < Op.SIG;
        }
        if (!ok) {
            skipped++;
            recover(xp, ep, rp, ssp);
            m.sw = sw;
            return;
        }
        int envEp = m.ep;
        int envSsp = m.ssp;
        int ls = Levels.substOf(m, c);
        rlvm.store.Overlay ov = m.ctx.overlay;
        int head = ov.mkConst(row, levels);
        m.terms.refresh();
        int[] ks = n == 1 ? new int[] {1} : n == 2 ? new int[] {2, 1} : new int[] {n, n - 1, 1};
        for (int k : ks) {
            int app = head;
            for (int i = 0; i < k; i++) {
                app = ov.mkApp(app, ov.mkBVar(n - 1 - i));
            }
            for (int mode = Op.ONLY; mode <= Op.CHECKED; mode++) {
                int xb = scratch(k);
                for (int i = 0; i < k; i++) {
                    m.X[xb + i] = ov.mkBVar(n - 1 - i);
                }
                m.sw = K1;
                int k1 = m.drive(Code.tele(m, blk, ls, xb, k, eb, n, 0, mode, Layout.NONE, 0));
                if (k1 >= Op.SIG) {
                    lastErr = m.err;
                    k1Err = m.err;
                    recover(xp, envEp, rp, envSsp);
                } else if (m.rp != rp || m.xp != xb + k || m.ep != envEp) {
                    throw new AssertionError("I-STACKS broken by TELE(" + name(row) + ")");
                }
                m.xp = xb;
                m.sw = 0;
                int k0 = k0(Infer.infer(m, app, eb, n, 0, mode, 0), xp, envEp, rp, envSsp);
                compare("TELE(" + name(row) + ") " + k + " of " + n + " arguments, " + (mode == Op.ONLY ? "ONLY" : "CHECKED"), k0, k1);
                if (k == n && mode == Op.CHECKED && k0 < Op.SIG && k1 < Op.SIG) {
                    // the expected-type shortcut of TRET (ISA 4.5.4, B5): with the right expected type the block answers
                    // it (matched or built); with another one it still builds its own result - the caller converts
                    m.sw = K1;
                    for (int i = 0; i < k; i++) {
                        m.X[xb + i] = ov.mkBVar(n - 1 - i); // (the generic INFER above used this scratch)
                    }
                    m.xp = xb + k;
                    int e1 = m.drive(Code.tele(m, blk, ls, xb, k, eb, n, 0, mode, k0, 0));
                    int other = m.es[eb];
                    int e2 = e1 >= Op.SIG ? e1 : m.drive(Code.tele(m, blk, ls, xb, k, eb, n, 0, mode, other, 0));
                    if (e2 >= Op.SIG) {
                        lastErr = m.err;
                        recover(xp, envEp, rp, envSsp);
                    } else if (m.rp != rp || m.xp != xb + k) {
                        throw new AssertionError("I-STACKS broken by TELE with an expected type (" + name(row) + ")");
                    }
                    m.xp = xb;
                    m.sw = 0;
                    expectCases++;
                    if (e1 != k0 || e2 != k0 && !(e2 == other && m.drive(Conv.conv(m, other, k0, 0)) == Layout.T)) {
                        different++;
                        note("TEXPECT of TELE(" + name(row) + "): " + show(e1) + " / " + show(e2) + " for " + show(k0));
                    }
                }
            }
            // VTYPE of the value
            int xb = scratch(k);
            System.arraycopy(m.es, eb, m.X, xb, k);
            int v = Values.mkApp(m, c, xb, k);
            m.xp = xb;
            if (v < Op.SIG && Values.kind(m, v) == Layout.NEU) { // (Nat.succ x is an offset cell: no spine)
                m.sw = K1;
                int k1 = m.drive(Code.teleValues(m, blk, ls, Values.b(m, v), k, 0));
                if (k1 >= Op.SIG) {
                    lastErr = m.err;
                    k1Err = m.err;
                    recover(xp, envEp, rp, envSsp);
                } else if (m.rp != rp || m.xp != xb) {
                    throw new AssertionError("I-STACKS broken by TELE values (" + name(row) + "), " + k + " of " + n + " arguments: rp "
                            + m.rp + " / " + rp + ", xp " + m.xp + " / " + xb + ", result " + show(k1));
                }
                m.sw = 0;
                int k0 = k0(Infer.vtype(m, v, 0), xp, envEp, rp, envSsp);
                compare("TELE(" + name(row) + ") VTYPE of " + k + " arguments", k0, k1);
            }
        }
        matches(row, blk, eb, n, ls);
        m.sw = sw;
        m.closeBindersAbove(ssp);
        m.ep = ep;
        m.xp = xp;
    }

    /** Every MATCH / TEXPECT pattern of the block against the value the generic walk builds for it (B5: T). */
    private void matches(int row, int blk, int eb, int n, int ls) {
        int[] c = cs.chunkOf(blk);
        int o = cs.offsetOf(blk);
        int len = Code.len(c[o]);
        int pc = Op.BLK_ENTRIES + Code.nentry(c[o + Op.BLK_HDR2]);
        while (pc < len) {
            int w = c[o + pc];
            int op = w >>> 24;
            int pattern = 0;
            int gn = 0;
            if (op == Op.MATCH) {
                pattern = c[o + pc + 2] & Op.OPND_PAYLOAD_MASK;
                gn = c[o + pc + 3] & Op.OPND_PAYLOAD_MASK;
            } else if (op == Op.TARG && (w & Code.PH_MASK) == Code.PH_EXPECT) {
                pattern = c[o + pc + 1] & Op.OPND_PAYLOAD_MASK;
                gn = c[o + pc + 2] & Op.OPND_PAYLOAD_MASK;
            }
            if (pattern != 0) {
                int v = m.drive(Eval.eval(m, pattern, eb, gn, ls, 0));
                if (v < Op.SIG) {
                    v = m.drive(Eval.force(m, v, 0));
                }
                if (v < Op.SIG) {
                    matchSites++;
                    int r = Match.matchIn(m, m.es, eb, gn, pattern, ls, v);
                    if (r == Layout.T) {
                        matchHits++;
                    } else if (missNotes++ < 8) {
                        both.append("MATCH of TELE(" + name(row) + ") at pc " + pc + " is unknown on its own value " + show(v) + "\n");
                    }
                    // soundness on sampled foreign values (ISA 4.4.4: T means "convertible"): MATCH = build + CONV
                    for (int k = 0; k < ring.length; k++) {
                        int other = ring[k];
                        if (other == 0 || other == v) {
                            continue;
                        }
                        foreign++;
                        if (Match.matchIn(m, m.es, eb, gn, pattern, ls, other) == Layout.T) {
                            foreignHits++;
                            int cv = m.drive(Conv.conv(m, v, other, 0));
                            if (cv != Layout.T) {
                                different++;
                                note("MATCH of TELE(" + name(row) + ") at pc " + pc + " says T for " + show(other)
                                        + " but CONV with the built value " + show(v) + " is " + cv);
                            }
                        }
                    }
                    ring[(int) (matchSites % ring.length)] = v;
                }
            }
            pc += 1 + (w >>> 8 & 255);
        }
    }
}

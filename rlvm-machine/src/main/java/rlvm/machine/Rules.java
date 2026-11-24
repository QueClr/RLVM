package rlvm.machine;

import static rlvm.machine.Op.SIG;

import rlvm.api.MachineErrors;
import rlvm.store.DeclKind;
import rlvm.store.DeclTable;
import rlvm.store.IntLists;
import rlvm.store.Kind;
import rlvm.store.Terms;

/**
 * Case-tree execution (ISA 3.6): the K1 shapes {@code CASESON} and {@code MATCHER}, and the derived trees K2.
 * Owner: M3. Staging step 4 ({@code Op.SW_CASETREES}) and "later" ({@code Op.SW_K2}); both OFF in v0.
 *
 * <p>The K1 case trees are elaborated and executed by M7 ({@code Elab}, {@code Code.rules}: the complete
 * RULES judgment of ISA 3.6, with over-application); this file is where the reduction family enters them:
 * {@link #unfold} is the delta step of the WHNF loop. With the switches off (v0, strict mode) nothing of it
 * runs. A tree that has no arm for the major ({@code NONE}) hands over to DELTA (ISA 6.3 B8).
 *
 * <h2>K2: derived trees (ISA 3.6 "K2", nano-lean's derived rules R04 section 8; NOTES-M3 D17)</h2>
 * A definition {@code c} without a K1 case tree whose applications the WHNF loop unfolds {@code k2Hot} (32)
 * times in a session gets a rule, session-local and keyed by its CONST record (constant and levels). The rule is a tree over the
 * first {@code arity} arguments (the leading lambdas of the value):
 * <pre>
 * SPLIT x, I   w = WHNF(value bound to x); it must be a constructor application of I with exactly its
 *              parameters and fields (a Nat literal / NSUC is viewed as in IOTA): its arguments are bound to new
 *              variables and the branch of (constructor, levels, argument count) is taken; anything else: NONE
 * LEAF         a straight-line template that rebuilds the value {@code WHNF(c x̄)} had under the path's
 *              constructor forms, with the bound values in the place of the variables
 * </pre>
 * and is grown lazily: a node is derived the first time an application reaches it, from the constructor
 * the actual major has. Deriving a node is one call of the machine's own WHNF on {@code c x̄}, where the
 * variables are free variables of a reserved type no binder can have (a Nat literal used as a type): every
 * judgment treats them as stuck neutrals; a judgment that asks for their type gets an answer of the
 * "no" kind (not a proof, not unit-like, K and structure eta not applicable) or fails, which abandons the
 * node. If the result is stuck on a variable as the major premise of a recursor that is not K-like or as
 * the struct of a projection, the node is a SPLIT on it, else a LEAF.
 *
 * <p>Soundness: the leaf is a reduct of {@code c x̄} under the path's constructor forms, obtained by the
 * official steps; reduction commutes with substitution, and the firing substitutes the actual arguments,
 * whose weak head normal forms are the constructor applications the path assumed (same constructor, same
 * levels, same argument count), so the instantiated leaf is a reduct of the application. Every way out
 * that is not sure (no branch, an unexplored node without derivation budget, a template that would have
 * to force or apply something) answers NONE, and the caller runs the complete procedure (K1 tree, DELTA).
 * The majors a firing normalises are the ones the unfolded definition would normalise on that path, in
 * that order (K-like majors are never split), so nothing is forced that the generic walk leaves alone.
 * Rules are active only inside a declaration that has run 16,384 ticks (light declarations never touch the
 * table). Derivation is capped at 1 / {@code k2Share} (0.2 %) of the session's ticks, and no rule is used or made
 * while a node is being derived, nor under eager reduction ({@code m.ER}: the comparison of the K rule is
 * the only mode-dependent step of WHNF; a rule is derived and used in plain mode only). Lazy delta does not
 * use rules ({@code Reduce.delta}, ISA 3.6).
 *
 * <p>Frames: RULES[0](v), RULES[1](v); K2: RULES[2](v, rule, node, xb), RULES[3](xb).
 */
final class Rules {
    private Rules() {}

    static final int K_RULES_1 = Op.K_RULES | 1;
    static final int K_RULES_2 = Op.K_RULES | 2;
    static final int K_RULES_3 = Op.K_RULES | 3;

    /**
     * RULES(v) (ISA 3.6, 4.3): runs the case tree of the head (CASESON / MATCHER shape) on a spine that covers
     * lamArity. Staging step 4, behind {@code Op.SW_CASETREES} (off in v0). Sound as a finite composition of DELTA and
     * CORE steps. Entry frame: RULES[0](v).
     * @return the reduct, {@code Layout.NONE} if no arm matches, or a signal
     */
    static int rules(Machine m, int v, int d) {
        if ((m.sw & Op.SW_CASETREES) == 0 || m.strict) {
            return Layout.NONE; // v0: no case tree runs; the caller uses DELTA (ISA 6.3 B8)
        }
        return Code.rules(m, v, d);
    }

    /**
     * The delta step of the WHNF loop (ISA 4.3.4): a derived tree of the head first when {@code SW_K2} is on, a
     * K1 case tree when {@code SW_CASETREES} is on (never in v0 or in strict mode), else, and when no arm
     * matches, DELTA. Lazy delta keeps calling {@code Reduce.delta} itself (ISA 3.6: single DELTA steps there).
     * @return the reduct, {@code Layout.NONE} if the head does not unfold, or a signal
     */
    static int unfold(Machine m, int v, int d) {
        int sw = m.sw;
        if ((sw & (Op.SW_CASETREES | Op.SW_K2)) == 0 || m.strict) {
            return Reduce.delta(m, v, d); // v0: the complete procedure (the same judgment, not a new call level)
        }
        if ((sw & Op.SW_K2) != 0) {
            if (m.x3.k2Deriving) {
                if (k2Folded(m, v)) {
                    return Layout.NONE; // an open application of a binary Nat function stays folded in a leaf
                }
            } else {
                int r = k2(m, v, d);
                if (r != Layout.NONE) {
                    return r; // the reduct of a derived rule (or of the fallback it ran itself), or a signal
                }
            }
        }
        return unfoldRest(m, v, d);
    }

    /**
     * While a node is derived: is {@code v} one of the 14 binary Nat functions applied to two arguments, at least
     * one of which is not closed (it holds a derivation variable: nothing else is open there)? Such an application
     * is not unfolded: the leaf keeps it folded, so that the firing meets the literal arithmetic of NATFOLD on the
     * actual operands instead of the unfolded recursion (nano-lean: "open Nat-primitive applications are left
     * folded"). The stuck result is a reduct like any other; its WHNF memo sits on a value with a variable.
     */
    private static boolean k2Folded(Machine m, int v) {
        if (v <= Layout.HOLE) {
            return false;
        }
        int o = v << Layout.REC_SHIFT;
        int w0 = m.vh[o];
        return (w0 & (0xF << Layout.KIND_SHIFT | Layout.AUX_MASK)) == (Layout.NEU << Layout.KIND_SHIFT | 2)
                && (w0 & Layout.W0_OPEN) != 0 && NatRed.binaryOp(m, m.vh[o + Layout.A]) != 0;
    }

    /**
     * The delta step of LAZY delta (Conv.unfoldT / unfoldS; request of M7, requests.log 14:17Z): with
     * {@code casetrees,m7a} the state-exact case tree {@code Code.rulesCasesOn} (blocks of shape CASESON only: for
     * that shape no state of the official loop lies between DELTA and the result) is tried first; NONE, and
     * every other switch set and strict mode: DELTA. No derived rule here (ISA 3.6).
     * @return the reduct, {@code Layout.NONE} if the head does not unfold, or a signal
     */
    static int unfoldLazy(Machine m, int v, int d) {
        if ((m.sw & (Op.SW_CASETREES | Op.SW_M7A)) != (Op.SW_CASETREES | Op.SW_M7A) || m.strict) {
            return Reduce.delta(m, v, d);
        }
        int r = Code.rulesCasesOn(m, v, d);
        return r >= SIG ? m.sig1(r, K_RULES_1, v) : k_rules1(m, v, r, d);
    }

    /** The delta step without derived rules: the K1 case tree if there is one, else DELTA. */
    private static int unfoldRest(Machine m, int v, int d) {
        if ((m.sw & Op.SW_CASETREES) == 0) {
            return Reduce.delta(m, v, d);
        }
        int r = Code.rules(m, v, d); // call RULES(v) => 1 (the tree is entered one level down, by Code)
        return r >= SIG ? m.sig1(r, K_RULES_1, v) : k_rules1(m, v, r, d);
    }

    /** Frame RULES[1](v): r = RULES(v); NONE: the delta step (tail). */
    static int k_rules1(Machine m, int v, int r, int d) {
        return r != Layout.NONE ? r : Reduce.delta(m, v, d + 1);
    }

    // =====================================================================================================
    // K2: derived trees (class comment). Session-local; state in Reduce.Ext (k2*).
    // =====================================================================================================

    /** {@code k2Val} of a head: below this a count of unfoldings; -1 = never; from here on {@code K2_RULE + rule}. */
    static final int K2_RULE = 1 << 24;
    static final int K2_NEVER = -1;

    // rule header
    private static final int R_ARITY = 0;
    private static final int R_HEAD = 1;
    private static final int R_ROOT = 2;
    private static final int R_HITS = 3;
    private static final int R_MISSES = 4;
    private static final int R_EXPLORES = 5;
    private static final int R_SIZE = 6;
    // nodes: word 0 is the kind
    private static final int K2_SPLIT = 1;
    private static final int K2_LEAF = 2;
    private static final int S_BIND = 1;
    private static final int S_IND = 2;
    private static final int S_FIRST = 3;
    private static final int S_PARENT = 4;
    private static final int S_NB = 5;
    private static final int S_SIZE = 6;
    // branch of a split
    private static final int B_CTOR = 0;
    private static final int B_LEVELS = 1;
    private static final int B_NARGS = 2;
    private static final int B_FIRST = 3;
    private static final int B_CHILD = 4;
    private static final int B_NEXT = 5;
    private static final int B_PARENT = 6;
    private static final int B_SPLIT = 7;
    private static final int B_SIZE = 8;
    // leaf: [K2_LEAF, registers, root operand, staging words, pc of a root application or 0, code ..]
    private static final int L_NREG = 1;
    private static final int L_ROOT = 2;
    private static final int L_STAGE = 3;
    private static final int L_ROOTAPP = 4;
    private static final int L_CODE = 5;
    // template instructions: word 0 = op | n << 8 | kind << 24
    private static final int T_NEU = 1;
    private static final int T_APP = 2;
    private static final int T_PROJ = 3;
    private static final int T_NSUC = 4;
    private static final int T_CLOS = 5;
    // operands: a value ref (an immediate, or a record index below 2^29), a bind slot, a register
    private static final int OP_BIND = 1 << 29;
    private static final int OP_REG = 2 << 29;
    private static final int OP_INDEX = (1 << 24) - 1;

    private static final int K2_MAX_ARITY = 16;
    private static final int K2_MAX_DEPTH = 6;
    private static final int K2_MAX_BRANCHES = 16;
    private static final int K2_MAX_EXPLORES = 48;
    private static final int K2_MAX_RULES = 256;
    private static final int K2_MAX_INSTRS = 192;
    private static final int K2_MAX_WORDS = 1536;
    /** Words of rule code per session (4 MiB); beyond it no node is derived. */
    private static final int K2_MAX_CODE = 1 << 20;
    private static final long K2_BUDGET = 4096;
    private static final long K2_MIN_BUDGET = 64;
    /**
     * Rules are counted, looked up and fired only in a declaration that has run this many ticks: the library
     * corpora (95 % of their declarations make at most 100 reduction steps) then never touch the rule table, and
     * a declaration that evaluates loses 16 k of its millions of ticks.
     */
    private static final long K2_ARM_TICKS = 16384;

    /**
     * The derived rule of the head of {@code v}, if the head is hot (class comment): counts the unfolding, makes
     * the rule at the {@code k2Hot}-th, fires it. {@code NONE}: nothing was done (not an application of a
     * definition, UNF known, no rule): the caller continues with the K1 tree / DELTA. Otherwise the reduct (a
     * rule that does not apply has run that fallback itself), or a signal.
     */
    private static int k2(Machine m, int v, int d) {
        int[] vh = m.vh;
        int o = v << Layout.REC_SHIFT;
        int w0 = vh[o];
        if (Reduce.Ext.K2_DUMP) {
            m.x3.k2Dbg[(w0 >>> Layout.KIND_SHIFT) != Layout.NEU ? 0 : vh[o + Layout.M_UNF] != 0 ? 1
                    : ((w0 >>> (Layout.FLAG_SHIFT + Layout.HCLASS_SHIFT)) & 7) != Layout.HC_DEFN ? 2 : 3]++;
        }
        if ((w0 >>> Layout.KIND_SHIFT) != Layout.NEU || vh[o + Layout.M_UNF] != 0
                || ((w0 >>> (Layout.FLAG_SHIFT + Layout.HCLASS_SHIFT)) & 7) != Layout.HC_DEFN) {
            return Layout.NONE;
        }
        Reduce.Ext e = m.x3;
        if (!e.k2Armed) {
            if (e.k2Share != 0 && m.ticks() - e.k2DeclTicks0 < K2_ARM_TICKS) {
                return Layout.NONE; // a light declaration: nothing is counted, looked up or fired (W2 of NOTES-M3)
            }
            e.k2Armed = true;
        }
        if (e.k2Deriving || m.ER != 0) {
            return Layout.NONE; // (under eager reduction K's comparison answers differently: no rule is made or used)
        }
        int i = e.k2Find(vh[o + Layout.A]);
        int st = e.k2Val[i];
        if (st < K2_RULE) {
            if (Reduce.Ext.K2_DUMP) {
                e.k2Dbg[st < 0 ? 4 : 5]++;
            }
            if (st < 0) {
                return Layout.NONE;
            }
            if (st + 1 < e.k2Hot) {
                e.k2Val[i] = st + 1;
                return Layout.NONE;
            }
            st = k2RuleSlow(m, vh[o + Layout.A], i);
            if (st < K2_RULE) {
                return Layout.NONE;
            }
        }
        return k2Fire(m, v, st - K2_RULE, d);
    }

    /**
     * Makes the (empty) rule of the hot head {@code head} (a CONST record): a definition whose value is an export
     * term with 1 .. 16 leading lambdas, at the right number of levels. Anything else is never asked again.
     * @return {@code K2_RULE + rule}, or {@code K2_NEVER}
     */
    private static int k2RuleSlow(Machine m, int head, int slot) {
        Reduce.Ext e = m.x3;
        DeclTable decls = m.decls;
        Terms terms = m.terms;
        int ho = head << Layout.REC_SHIFT;
        int row = m.vh[ho + Layout.A];
        e.k2Val[slot] = K2_NEVER;
        if ((m.vh[ho] >>> Layout.KIND_SHIFT) != Layout.CONST || decls.kind(row) != DeclKind.DEFN
                || (decls.flags(row) & DeclTable.CF_BLOCK_VALUE) != 0 || Levels.substOf(m, head) < 0
                || m.lists.length(m.vh[ho + Layout.B]) != decls.numLevelParams(row) || e.k2Rules >= K2_MAX_RULES
                || NatRed.binaryOp(m, head) != 0) {
            return K2_NEVER; // (a binary Nat function is reduced by NATFOLD, and stays folded in a leaf)
        }
        if ((m.sw & Op.SW_CASETREES) != 0) {
            if (Code.blockFor(m, row, Op.ROLE_RULES) != 0) {
                return K2_NEVER; // the head has a K1 case tree (M7; since 15:10Z it inlines the matchers it calls): the better rule
            }
        }
        int t = decls.value(row);
        int arity = 0;
        while (t > 0 && arity <= K2_MAX_ARITY && terms.kind(t) == Kind.LAM) {
            t = terms.b(t);
            arity++;
        }
        if (t <= 0 || arity == 0 || arity > K2_MAX_ARITY) {
            return K2_NEVER;
        }
        int rule = e.k2Alloc(R_SIZE);
        int[] c = e.k2Code;
        c[rule + R_ARITY] = arity;
        c[rule + R_HEAD] = head;
        e.k2Rules++;
        e.k2Val[slot] = K2_RULE + rule;
        return K2_RULE + rule;
    }

    /** The head of {@code rule} is not asked again in this session. */
    private static void k2RetireSlow(Machine m, int rule) {
        Reduce.Ext e = m.x3;
        e.k2Val[e.k2Find(e.k2Code[rule + R_HEAD])] = K2_NEVER;
    }

    /**
     * Fires {@code rule} on the NEU {@code v} (one tick, call event RULES): the first arity arguments are bound on
     * scratch at {@code xb}, then the tree is walked. A spine shorter than the arity: NONE.
     */
    private static int k2Fire(Machine m, int v, int rule, int d) {
        Reduce.Ext e = m.x3;
        int arity = e.k2Code[rule + R_ARITY];
        if ((m.vh[v << Layout.REC_SHIFT] & Layout.AUX_MASK) < arity
                || (e.k2Code[rule + R_ROOT] == 0 && k2Allowance(m, e) < K2_MIN_BUDGET)) {
            return Layout.NONE; // a partial application; a rule whose root still waits for its derivation
        }
        if ((--m.fuel & Op.POLL_MASK) == Op.POLL_MASK && m.tickSlow()) {
            return m.sig;
        }
        if (m.stats) {
            m.call(Op.RULES);
        }
        if (!m.ensureScratch(arity)) {
            return m.fault(MachineErrors.E_MEM);
        }
        int xb = m.xp;
        Reduce.copyArgs(m, v, 0, arity, xb);
        m.xp = xb + arity;
        return k2Walk(m, v, rule, rule + R_ROOT, xb, d);
    }

    /**
     * The walk of the tree from the node in {@code cell} (0 = not derived yet, -1 = cannot be derived): a SPLIT
     * normalises the value bound to its variable and continues in the branch of its constructor; a LEAF builds
     * the reduct. The bound values are X[xb .. m.xp).
     */
    private static int k2Walk(Machine m, int v, int rule, int cell, int xb, int d) {
        for (;;) {
            int[] c = m.x3.k2Code;
            int node = c[cell];
            if (node <= 0) {
                m.x3.k2Reason = Reduce.Ext.K2_WHY_NODE;
                if (node == 0) {
                    node = k2ExploreSlow(m, rule, cell);
                }
                if (node >= SIG) {
                    return node;
                }
                if (node <= 0) {
                    return k2Miss(m, v, rule, xb, d);
                }
                c = m.x3.k2Code;
            }
            if (c[node] == K2_LEAF) {
                return k2Leaf(m, v, rule, node, xb, d);
            }
            int w = Reduce.whnf(m, m.X[xb + c[node + S_BIND]], d + 1); // call WHNF(major) => 2
            if (w >= SIG) {
                return m.sig4(w, K_RULES_2, v, rule, node, xb);
            }
            cell = k2Branch(m, node, xb, w);
            if (cell >= SIG) {
                return cell;
            }
            if (cell == 0) {
                m.x3.k2Reason = Reduce.Ext.K2_WHY_BRANCH;
                return k2Miss(m, v, rule, xb, d);
            }
        }
    }

    /** Frame RULES[2](v, rule, node, xb): r = WHNF of the value the SPLIT {@code node} looks at. */
    static int k_rules2(Machine m, int v, int rule, int node, int xb, int r, int d) {
        int cell = k2Branch(m, node, xb, r);
        if (cell >= SIG) {
            return cell;
        }
        m.x3.k2Reason = Reduce.Ext.K2_WHY_BRANCH;
        return cell == 0 ? k2Miss(m, v, rule, xb, d) : k2Walk(m, v, rule, cell, xb, d);
    }

    /** The rule does not apply: scratch is released and the complete procedure runs (K1 tree, DELTA). */
    private static int k2Miss(Machine m, int v, int rule, int xb, int d) {
        m.xp = xb;
        Reduce.Ext e = m.x3;
        int[] c = e.k2Code;
        e.k2Missed++;
        e.k2Why[e.k2Reason]++;
        if (e.k2Reason != Reduce.Ext.K2_WHY_ALLOWANCE) { // (a node that waits for its derivation is no miss of the rule)
            int misses = ++c[rule + R_MISSES];
            if (misses >= 64 && misses > 4 * c[rule + R_HITS]) {
                k2RetireSlow(m, rule); // a rule that keeps missing costs more than it saves
            }
        }
        return unfoldRest(m, v, d);
    }

    /**
     * The branch of SPLIT {@code node} for the normalised major {@code w}: its constructor view (as IOTA: a Nat
     * literal is {@code Nat.zero} or {@code Nat.succ} of the predecessor literal, an NSUC is {@code Nat.succ}), the
     * branch of (constructor, levels, argument count), made if it does not exist yet; the constructor's
     * arguments are appended to the bound values.
     * @return the address of the branch's child cell, 0 if {@code w} is no such constructor application, or a signal
     */
    private static int k2Branch(Machine m, int node, int xb, int w) {
        int ctor;
        int levels = IntLists.EMPTY;
        int nargs = 0;
        int one = 0;
        if (w < 0) {
            ctor = NatRed.litCtor(m, w);
            if (w != Layout.IMM) {
                nargs = 1;
                one = w - 1;
            }
        } else if (w <= Layout.HOLE) {
            return 0;
        } else {
            int[] vh = m.vh;
            int o = w << Layout.REC_SHIFT;
            int w0 = vh[o];
            int kind = w0 >>> Layout.KIND_SHIFT;
            if (kind == Layout.NEU || kind == Layout.CONST) {
                if (((w0 >>> (Layout.FLAG_SHIFT + Layout.HCLASS_SHIFT)) & 7) != Layout.HC_CTOR) {
                    return 0;
                }
                int h = kind == Layout.NEU ? vh[o + Layout.A] : w;
                ctor = vh[(h << Layout.REC_SHIFT) + Layout.A];
                levels = vh[(h << Layout.REC_SHIFT) + Layout.B];
                nargs = kind == Layout.NEU ? w0 & Layout.AUX_MASK : 0;
            } else if (kind == Layout.NSUC || kind == Layout.NBIG) {
                ctor = kind == Layout.NSUC ? NatRed.succCtor(m) : NatRed.litCtor(m, w);
                one = kind == Layout.NSUC ? NatRed.unsucc(m, w, 1) : NatRed.litPred(m, w);
                nargs = 1;
                if (one >= SIG) {
                    return one;
                }
            } else {
                return 0; // a string literal, a stuck value: the generic IOTA / projection decides
            }
        }
        if (ctor <= 0 || ctor >= SIG) {
            return ctor >= SIG ? ctor : 0;
        }
        return k2Bind(m, node, xb, w, ctor, levels, nargs, one);
    }

    /** The second half of {@link #k2Branch}: finds or makes the branch and binds the constructor's arguments. */
    private static int k2Bind(Machine m, int node, int xb, int w, int ctor, int levels, int nargs, int one) {
        int[] c = m.x3.k2Code;
        int b = c[node + S_FIRST];
        while (b != 0 && (c[b + B_CTOR] != ctor || c[b + B_LEVELS] != levels || c[b + B_NARGS] != nargs)) {
            b = c[b + B_NEXT];
        }
        if (b == 0) {
            b = k2BranchSlow(m, node, ctor, levels, nargs);
            if (b == 0) {
                return 0;
            }
        }
        if (nargs > 0) {
            if (!m.ensureScratch(nargs)) {
                return m.fault(MachineErrors.E_MEM);
            }
            int at = m.xp;
            if (w > Layout.HOLE && (m.vh[w << Layout.REC_SHIFT] >>> Layout.KIND_SHIFT) == Layout.NEU) {
                Reduce.copyArgs(m, w, 0, nargs, at);
            } else {
                m.X[at] = one;
            }
            m.xp = at + nargs;
        }
        return b + B_CHILD;
    }

    /** A new branch of SPLIT {@code node} for a constructor of its inductive type applied to exactly its parameters and fields; 0 if it is not one. */
    private static int k2BranchSlow(Machine m, int node, int ctor, int levels, int nargs) {
        Reduce.Ext e = m.x3;
        DeclTable decls = m.decls;
        int[] c = e.k2Code;
        if (decls.kind(ctor) != DeclKind.CTOR || decls.ctorInduct(ctor) != c[node + S_IND]
                || nargs != decls.ctorNumParams(ctor) + decls.ctorNumFields(ctor)) {
            return 0;
        }
        int count = 0;
        for (int b = c[node + S_FIRST]; b != 0; b = c[b + B_NEXT]) {
            count++;
        }
        if (count >= K2_MAX_BRANCHES) {
            return 0;
        }
        int parent = c[node + S_PARENT];
        int b = e.k2Alloc(B_SIZE);
        c = e.k2Code;
        c[b + B_CTOR] = ctor;
        c[b + B_LEVELS] = levels;
        c[b + B_NARGS] = nargs;
        c[b + B_FIRST] = c[node + S_NB];
        c[b + B_NEXT] = c[node + S_FIRST];
        c[b + B_PARENT] = parent;
        c[b + B_SPLIT] = c[node + S_BIND];
        c[node + S_FIRST] = b;
        return b;
    }

    // ---- leaves ----------------------------------------------------------------------------------------

    /** The value of a template operand: a value ref, a bound value X[xb + i], a register X[rb + i]. */
    private static int k2Opnd(Machine m, int op, int xb, int rb) {
        if (op < OP_BIND) {
            return op; // an immediate (negative) or a record ref
        }
        return m.X[(op < OP_REG ? xb : rb) + (op & OP_INDEX)];
    }

    /**
     * A LEAF: runs its template (class p constructors only; registers on scratch above the bound values), then
     * {@link #k2Finish}. A template step that is not sure ({@code Op.MISS}) ends the firing with the fallback.
     */
    private static int k2Leaf(Machine m, int v, int rule, int node, int xb, int d) {
        int[] c = m.x3.k2Code;
        int nreg = c[node + L_NREG];
        if (!m.ensureScratch(nreg + c[node + L_STAGE])) {
            return m.fault(MachineErrors.E_MEM);
        }
        int rb = m.xp;
        m.xp = rb + nreg;
        int pc = node + L_CODE;
        for (int k = 0; k < nreg; k++) {
            int w = c[pc];
            int r = k2Instr(m, c, pc, w, xb, rb);
            if (r >= Op.MISS) {
                m.x3.k2Reason = Reduce.Ext.K2_WHY_TEMPLATE;
                return r == Op.MISS ? k2Miss(m, v, rule, xb, d) : r;
            }
            m.X[rb + k] = r;
            int op = w & 255;
            pc += op == T_PROJ ? 4 : op == T_NSUC ? 3 : (w >>> 8 & 0xFFFF) + (op == T_CLOS ? 3 : 2);
        }
        return k2Finish(m, v, rule, node, xb, rb, d);
    }

    /** One template instruction at {@code pc}: the value it builds, {@code Op.MISS}, or a signal. */
    private static int k2Instr(Machine m, int[] c, int pc, int w, int xb, int rb) {
        int n = w >>> 8 & 0xFFFF;
        switch (w & 255) {
            case T_NEU:
                return k2Neu(m, c, pc, n, xb, rb);
            case T_APP:
                return k2App(m, c, pc, n, xb, rb);
            case T_CLOS:
                return k2Clos(m, c, pc, n, w >>> 24, xb, rb);
            case T_PROJ:
                return Values.mkProj(m, c[pc + 1], c[pc + 2], k2Opnd(m, c[pc + 3], xb, rb));
            default:
                return Values.nsuccK(m, k2Opnd(m, c[pc + 2], xb, rb), c[pc + 1]);
        }
    }

    /** The staged operands {@code c[at .. at + n)} at the top of scratch; returns their base (room was ensured). */
    private static int k2Stage(Machine m, int[] c, int at, int n, int xb, int rb) {
        int st = m.xp;
        int[] x = m.X;
        for (int i = 0; i < n; i++) {
            x[st + i] = k2Opnd(m, c[at + i], xb, rb);
        }
        return st;
    }

    /** T_NEU: MKAPP of a closed head (a constant, a closed projection) to n operands. */
    private static int k2Neu(Machine m, int[] c, int pc, int n, int xb, int rb) {
        int st = k2Stage(m, c, pc + 2, n, xb, rb);
        m.xp = st + n;
        int r = Values.mkApp(m, c[pc + 1], st, n);
        m.xp = st;
        return r;
    }

    /**
     * T_APP (not the root): an application whose head was a variable or contained one. A neutral head: MKAPP; a
     * closure: the delayed beta step {@link #k2LazySlow}; anything else (a pending suspension, a literal) is not
     * sure: MISS.
     */
    private static int k2App(Machine m, int[] c, int pc, int n, int xb, int rb) {
        int f = Values.peek(m, k2Opnd(m, c[pc + 1], xb, rb));
        int st = k2Stage(m, c, pc + 2, n, xb, rb);
        if (f <= Layout.HOLE) {
            return Op.MISS;
        }
        int kind = m.vh[f << Layout.REC_SHIFT] >>> Layout.KIND_SHIFT;
        if (kind == Layout.FVAR || kind == Layout.CONST || kind == Layout.NEU || kind == Layout.PROJ) {
            m.xp = st + n;
            int r = Values.mkApp(m, f, st, n);
            m.xp = st;
            return r;
        }
        return kind == Layout.LAM ? k2LazySlow(m, f, st, n) : Op.MISS;
    }

    /**
     * The closure {@code clo} applied to the n values X[st ..] WITHOUT evaluating its body (the generic walk
     * suspends such an application in argument position): {@code SUSP(body_n, PRUNE(frame + arguments))}, the
     * closure of {@code body_n} if it is a lambda again, the bound value if it is a variable. Needs n leading
     * lambdas; else, or for another atom, MISS.
     */
    private static int k2LazySlow(Machine m, int clo, int st, int n) {
        Terms terms = m.terms;
        int o = clo << Layout.REC_SHIFT;
        int t = m.vh[o + Layout.A];
        int j = 0;
        do {
            t = terms.b(t);
            j++;
        } while (j < n && terms.kind(t) == Kind.LAM);
        int kind = terms.kind(t);
        if (j < n || (kind != Kind.LAM && kind != Kind.APP && kind != Kind.BVAR && kind != Kind.PROJ)) {
            return Op.MISS;
        }
        final int ep0 = m.ep;
        int en = Runs.enter(m, m.vh[o + Layout.B]);
        if (en < 0 || !m.ensureEnv(n)) {
            return m.fault(MachineErrors.E_MEM);
        }
        int base = m.envBase;
        int[] es = m.es;
        long mask = terms.fvMask(t);
        for (int i = 0; i < n; i++) {
            int idx = n - 1 - i;
            boolean reads = idx >= Layout.NEAR_SLOTS ? mask < 0 : ((mask >>> idx) & 1L) != 0;
            es[base + en + i] = reads ? m.X[st + i] : Layout.HOLE;
        }
        m.ep = base + en + n;
        int r;
        if (kind == Kind.BVAR) {
            int idx = terms.a(t);
            r = idx < en + n ? es[base + en + n - 1 - idx] : Op.MISS;
            if (r <= Layout.HOLE && r >= 0) {
                r = Op.MISS;
            }
        } else {
            int frame = Runs.prune(m, base, en + n, mask);
            int ls = m.vh[o + Layout.C];
            r = frame >= SIG ? frame : kind == Kind.LAM ? Values.mkLam(m, t, frame, ls) : Values.mkSusp(m, t, frame, ls, false);
        }
        m.ep = ep0;
        return r;
    }

    /** T_CLOS: the closure or suspension of {@code (term, ls)} over the frame of n operands (HOLE stays HOLE). */
    private static int k2Clos(Machine m, int[] c, int pc, int n, int kind, int xb, int rb) {
        int st = k2Stage(m, c, pc + 3, n, xb, rb);
        m.xp = st + n;
        int frame = Runs.frame(m, st, n, 0);
        m.xp = st;
        if (frame >= SIG) {
            return frame;
        }
        int term = c[pc + 1];
        int ls = c[pc + 2];
        switch (kind) {
            case Layout.LAM:
                return Values.mkLam(m, term, frame, ls);
            case Layout.PI:
                return Values.mkPi(m, term, frame, ls);
            case Layout.PII:
                return Values.mkPii(m, term, frame, ls);
            default:
                return Values.mkSusp(m, term, frame, ls, false);
        }
    }

    /**
     * The end of a firing: the root of the leaf, applied (APPLY, the one judgment of a leaf) when it is an
     * application of a bound value and / or the spine of {@code v} has arguments beyond the arity.
     */
    private static int k2Finish(Machine m, int v, int rule, int node, int xb, int rb, int d) {
        Reduce.Ext e = m.x3;
        int[] c = e.k2Code;
        int arity = c[rule + R_ARITY];
        int rest = (m.vh[v << Layout.REC_SHIFT] & Layout.AUX_MASK) - arity;
        int ra = c[node + L_ROOTAPP];
        int n = ra == 0 ? 0 : c[ra] >>> 8 & 0xFFFF;
        c[rule + R_HITS]++;
        e.k2Fired++;
        if (m.stats) {
            m.hits[Op.RULES]++;
        }
        if (n + rest == 0) {
            int r = k2Opnd(m, c[node + L_ROOT], xb, rb);
            m.xp = xb;
            return r;
        }
        if (!m.ensureScratch(n + rest)) {
            return m.fault(MachineErrors.E_MEM);
        }
        int f = k2Opnd(m, ra == 0 ? c[node + L_ROOT] : c[ra + 1], xb, rb);
        int st = k2Stage(m, c, ra + 2, n, xb, rb);
        Reduce.copyArgs(m, v, arity, rest, st + n);
        m.xp = st + n + rest;
        int a = Eval.apply(m, f, st, n + rest, d + 1); // call APPLY(root head, its arguments ++ the rest) => 3
        return a >= SIG ? m.sig1(a, K_RULES_3, xb) : k_rules3(m, xb, a, d);
    }

    /** Frame RULES[3](xb): r = the reduct; the scratch of the firing is released. */
    static int k_rules3(Machine m, int xb, int r, int d) {
        m.xp = xb;
        return r;
    }

    /**
     * The rules of the session as text, hottest first (a tool: {@code -Drlvm.machine.k2Dump=true} prints it at
     * every session reset that has rules): head, arity, firings, misses, nodes derived; then the totals.
     */
    static String k2DumpSlow(Machine m) {
        Reduce.Ext e = m.x3;
        int[] c = e.k2Code;
        StringBuilder sb = new StringBuilder();
        java.util.List<int[]> rules = new java.util.ArrayList<>();
        for (int i = 0; i < e.k2Key.length; i++) {
            if (e.k2Key[i] != 0 && e.k2Val[i] >= K2_RULE) {
                rules.add(new int[] {e.k2Val[i] - K2_RULE, 1});
            } else if (e.k2Key[i] != 0 && e.k2Val[i] == K2_NEVER) {
                rules.add(new int[] {e.k2Key[i], 0});
            }
        }
        rules.sort((a, b) -> a[1] != b[1] ? b[1] - a[1] : a[1] == 0 ? 0 : c[b[0] + R_HITS] + c[b[0] + R_MISSES] - c[a[0] + R_HITS] - c[a[0] + R_MISSES]);
        sb.append("K2 session: ticks ").append(m.ticks() - e.k2Ticks0).append(", derivation ticks ").append(e.k2DeriveTicks)
                .append(", rules ").append(e.k2Rules).append(", nodes derived ").append(e.k2Derivs).append(" (failed ").append(e.k2Aborted)
                .append("), fired ").append(e.k2Fired).append(", missed ").append(e.k2Missed).append(" (allowance ")
                .append(e.k2Why[Reduce.Ext.K2_WHY_ALLOWANCE]).append(", node ").append(e.k2Why[Reduce.Ext.K2_WHY_NODE]).append(", branch ")
                .append(e.k2Why[Reduce.Ext.K2_WHY_BRANCH]).append(", template ").append(e.k2Why[Reduce.Ext.K2_WHY_TEMPLATE]).append(")")
                .append("; unfold calls: not NEU ").append(e.k2Dbg[0]).append(", UNF known ").append(e.k2Dbg[1]).append(", not a definition ")
                .append(e.k2Dbg[2]).append(", definitions ").append(e.k2Dbg[3]).append(" (never ").append(e.k2Dbg[4]).append(", counting ")
                .append(e.k2Dbg[5]).append("), heads ").append(e.k2Used).append("\n");
        int shown = 0;
        for (int[] r : rules) {
            if (shown++ >= 40) {
                break;
            }
            int head = r[1] == 1 ? c[r[0] + R_HEAD] : r[0];
            String name = "row " + m.vh[(head << Layout.REC_SHIFT) + Layout.A];
            if (r[1] == 1) {
                sb.append(String.format("  %-48s arity %d fired %d missed %d nodes %d%n", name, c[r[0] + R_ARITY], c[r[0] + R_HITS],
                        c[r[0] + R_MISSES], c[r[0] + R_EXPLORES]));
            } else {
                sb.append(String.format("  %-48s never%n", name));
            }
        }
        return sb.toString();
    }

    // ---- derivation (cold) ---------------------------------------------------------------------------

    /** Ticks the session may still spend on derivation: 1 / k2Share of its ticks so far, less what was spent. */
    private static long k2Allowance(Machine m, Reduce.Ext e) {
        return e.k2Share == 0 ? K2_BUDGET : (m.ticks() - e.k2Ticks0) / e.k2Share - e.k2DeriveTicks;
    }

    /** The reserved type of the derivation variables: a Nat literal record no binder can have as its type. */
    private static int k2TypeSlow(Machine m) {
        Reduce.Ext e = m.x3;
        if (e.k2Type == 0) {
            int t = Values.mkNat(m, (1L << 40) + 0x4B32);
            if (t >= SIG) {
                return t;
            }
            e.k2Type = t;
        }
        return e.k2Type;
    }

    /**
     * Derives the node of {@code cell} (the root cell of the rule or the child cell of a branch) under the
     * derivation allowance (class comment), as a speculative segment: a budget, and any failure inside is the
     * end of this node only.
     * @return the node, -1 (not derived: the cell says so unless the allowance was the reason), or a signal
     */
    private static int k2ExploreSlow(Machine m, int rule, int cell) {
        Reduce.Ext e = m.x3;
        int[] c = e.k2Code;
        long allow = k2Allowance(m, e);
        if (allow < K2_MIN_BUDGET || m.fuel < 8 * K2_BUDGET) {
            e.k2Reason = Reduce.Ext.K2_WHY_ALLOWANCE;
            return -1; // not now: the session has not run long enough to pay for it
        }
        e.k2Reason = Reduce.Ext.K2_WHY_NODE;
        if (c[rule + R_EXPLORES] >= K2_MAX_EXPLORES || e.k2CodeN > K2_MAX_CODE) {
            c[cell] = -1;
            return -1;
        }
        c[rule + R_EXPLORES]++;
        int br = cell == rule + R_ROOT ? 0 : cell - B_CHILD;
        final int ssp = m.ssp;
        final int xp = m.xp;
        final int ep = m.ep;
        final int rp = m.rp;
        final int er = m.ER;
        final long budget = Math.min(allow, K2_BUDGET);
        final long before = m.ticks();
        final boolean er0 = m.erSeen;
        e.k2Deriving = true;
        e.k2Derivs++;
        long saved = m.tryEnter(budget);
        int node = k2DeriveSlow(m, rule, br);
        e.k2Deriving = false;
        if (node < SIG && node > 0 && m.erSeen != er0) {
            node = -1; // a comparison inside the derivation entered eager reduction: its answers are that mode's
        }
        if (node == Op.FATAL && (m.err == MachineErrors.E_MEM || m.err == MachineErrors.E_CANCEL
                || m.err == MachineErrors.E_AUDIT || m.err == MachineErrors.E_STACK)) {
            return node; // the run no longer wants this unit: not the derivation's business
        }
        if (node == Op.FATAL) {
            // an invariant that does not hold for a derivation variable: this node cannot be derived
            m.closeBindersAbove(ssp);
            m.xp = xp;
            m.ep = ep;
            m.rp = rp;
            m.ER = er;
            m.fuel = saved - (budget - m.fuel);
            m.tickBase += saved - budget;
            m.err = 0;
            node = -1;
        } else {
            int u = Frames.k_try(m, saved, budget, ssp, xp, ep, rp, er, node >= SIG ? node : Layout.T);
            if (u >= SIG) {
                return u;
            }
            if (node >= SIG) {
                node = -1; // a kernel error or the budget inside the segment (U)
            }
        }
        m.closeBindersAbove(ssp);
        m.xp = xp;
        e.k2DeriveTicks += m.ticks() - before;
        if (node < 0) {
            e.k2Aborted++;
        }
        e.k2Code[cell] = node;
        return node;
    }

    /**
     * One derivation step (class comment): the variables of the node (the arity, plus the constructor
     * arguments of the branches on the path) are opened, the split ones stand for their constructor
     * applications, {@code WHNF(c x̄)} runs to completion in a driver of its own, and the result becomes a SPLIT
     * (stuck on a variable) or a LEAF.
     * @return the node, -1, or a signal (binders and scratch are then the caller's to restore)
     */
    private static int k2DeriveSlow(Machine m, int rule, int br) {
        Reduce.Ext e = m.x3;
        int[] c = e.k2Code;
        int arity = c[rule + R_ARITY];
        int nb = br == 0 ? arity : c[br + B_FIRST] + c[br + B_NARGS];
        int depth = 0;
        for (int b = br; b != 0; b = c[b + B_PARENT]) {
            depth++;
        }
        int t0 = k2TypeSlow(m);
        if (t0 >= SIG || !m.ensureScratch(nb + 2 * depth + 2)) {
            return t0 >= SIG ? t0 : m.fault(MachineErrors.E_MEM);
        }
        final int sb = m.xp;
        final int wb = sb + nb;
        m.xp = wb + 2 * depth;
        for (int i = 0; i < nb; i++) {
            int x = Scope.open(m, t0);
            if (x >= SIG) {
                return x;
            }
            if (m.vh[(x << Layout.REC_SHIFT) + Layout.B] != i) {
                return -1; // variables of the reserved type are open already: not expected, not derived
            }
            m.X[sb + i] = x;
        }
        int wn = 0;
        for (int b = br; b != 0; b = c[b + B_PARENT]) { // innermost first: its arguments are plain variables
            int k = Values.mkConst(m, c[b + B_CTOR], c[b + B_LEVELS]);
            if (k >= SIG) {
                return k;
            }
            int app = Values.mkApp(m, k, sb + c[b + B_FIRST], c[b + B_NARGS]);
            if (app >= SIG) {
                return app;
            }
            int[] x = m.X;
            x[sb + c[b + B_SPLIT]] = app;
            x[wb + 2 * wn] = app;
            x[wb + 2 * wn + 1] = c[b + B_SPLIT];
            wn++;
        }
        int root = Values.mkApp(m, c[rule + R_HEAD], sb, arity);
        if (root >= SIG) {
            return root;
        }
        int r = m.drive(Reduce.whnf(m, root, 0));
        if (r >= SIG) {
            return r;
        }
        int node;
        if (r == root) {
            Scope.close(m, nb);
            m.xp = sb;
            return -1; // no step was made: a leaf that is the application itself would not terminate
        }
        int bind = depth < K2_MAX_DEPTH ? k2BlockerSlow(m, r, t0, nb) : -1;
        if (bind >= 0) {
            node = e.k2Alloc(S_SIZE);
            c = e.k2Code;
            c[node] = K2_SPLIT;
            c[node + S_BIND] = bind;
            c[node + S_IND] = e.k2BlockInd;
            c[node + S_PARENT] = br;
            c[node + S_NB] = nb;
        } else {
            node = k2CompileSlow(m, r, t0, nb, wb, wn);
        }
        Scope.close(m, nb);
        m.xp = sb;
        return node;
    }

    /**
     * What the weak head normal form {@code r} is stuck on (nano-lean's "blocker", at most 8 hops): the major
     * premise of a recursor that is not K-like, or the struct of a projection (also as the head of an
     * application), whose own normal form (known by its memo: the reduction just computed it) is a derivation
     * variable. A K-like recursor is never split: the generic IOTA does not normalise its major first.
     * @return the variable's index (the inductive type is left in {@code k2BlockInd}), or -1
     */
    private static int k2BlockerSlow(Machine m, int r, int t0, int nb) {
        DeclTable decls = m.decls;
        for (int hop = 0; hop < 8; hop++) {
            if (r <= Layout.HOLE) {
                return -1;
            }
            int[] vh = m.vh;
            int o = r << Layout.REC_SHIFT;
            int w0 = vh[o];
            int kind = w0 >>> Layout.KIND_SHIFT;
            int major;
            int ind;
            if (kind == Layout.FVAR) {
                return -1;
            }
            int p = kind == Layout.PROJ ? r : 0;
            if (kind == Layout.NEU) {
                int hc = (w0 >>> (Layout.FLAG_SHIFT + Layout.HCLASS_SHIFT)) & 7;
                if (hc == Layout.HC_PROJ) {
                    p = vh[o + Layout.A];
                } else if (hc != Layout.HC_REC) {
                    return -1;
                }
            } else if (kind != Layout.PROJ) {
                return -1;
            }
            if (p != 0) {
                major = vh[(p << Layout.REC_SHIFT) + Layout.A];
                ind = vh[(p << Layout.REC_SHIFT) + Layout.B];
            } else {
                int rec = vh[(vh[o + Layout.A] << Layout.REC_SHIFT) + Layout.A];
                int mi = decls.recMajorIndex(rec);
                if (mi >= (w0 & Layout.AUX_MASK) || (decls.recFlags(rec) & DeclTable.RF_K) != 0) {
                    return -1;
                }
                major = Runs.arg(m, vh[o + Layout.B], mi);
                ind = decls.recMajorInduct(rec);
            }
            int w = Reduce.whnfFast(m, Values.peek(m, major));
            if (w == Op.MISS || w <= Layout.HOLE) {
                return -1;
            }
            int wo = w << Layout.REC_SHIFT;
            if ((vh[wo] >>> Layout.KIND_SHIFT) == Layout.FVAR) {
                if (vh[wo + Layout.A] != t0 || vh[wo + Layout.B] >= nb || ind <= 0 || decls.kind(ind) != DeclKind.INDUCT
                        || decls.inductCtorCount(ind) == 0) {
                    return -1;
                }
                m.x3.k2BlockInd = ind;
                return vh[wo + Layout.B];
            }
            r = w;
        }
        return -1;
    }

    /**
     * The LEAF of the stuck value {@code r}: a post-order walk over its OPEN part. A derivation variable is a
     * bind operand; the constructor application a split variable stands for is that variable's operand (the
     * firing binds it to the ORIGINAL argument, as the generic walk passes it on); a closed value is a constant;
     * NEU, PROJ, NSUC, closures and suspensions over frames with variables are rebuilt by one instruction each.
     * A suspension with a known value stands for it (PEEK). Not a leaf (-1): a foreign free variable, a LET
     * suspension, a far frame, a closure over a term of the worker's overlay, more than 192 instructions.
     */
    private static int k2CompileSlow(Machine m, int r, int t0, int nb, int wb, int wn) {
        Reduce.Ext e = m.x3;
        e.k2TmpN = 0;
        e.k2MemoN = 0;
        e.k2Bad = false;
        e.k2Nreg = 0;
        e.k2Stage = 0;
        e.k2LastPc = -1;
        int root = k2VisitSlow(m, r, t0, nb, wb, wn, 0);
        if (e.k2Bad || e.k2TmpN > K2_MAX_WORDS) {
            return -1;
        }
        int nreg = e.k2Nreg;
        int rootApp = 0;
        int node = e.k2Alloc(L_CODE + e.k2TmpN);
        int[] c = e.k2Code;
        System.arraycopy(e.k2Tmp, 0, c, node + L_CODE, e.k2TmpN);
        if (nreg > 0 && root == (OP_REG | (nreg - 1)) && (c[node + L_CODE + e.k2LastPc] & 255) == T_APP) {
            rootApp = node + L_CODE + e.k2LastPc; // the root is an application of a bound value: APPLY at the end
            nreg--;
        }
        c[node] = K2_LEAF;
        c[node + L_NREG] = nreg;
        c[node + L_ROOT] = root;
        c[node + L_STAGE] = e.k2Stage;
        c[node + L_ROOTAPP] = rootApp;
        return node;
    }

    /** The operand of value {@code v} in the template being compiled (see {@link #k2CompileSlow}); sets {@code k2Bad}. */
    private static int k2VisitSlow(Machine m, int v, int t0, int nb, int wb, int wn, int depth) {
        Reduce.Ext e = m.x3;
        v = Values.peek(m, v);
        if (v <= Layout.HOLE || Values.openOf(m, v) == 0) {
            if (v > Layout.HOLE && v >= OP_BIND) {
                e.k2Bad = true;
            }
            return v;
        }
        for (int j = 0; j < wn; j++) {
            if (m.X[wb + 2 * j] == v) {
                return OP_BIND | m.X[wb + 2 * j + 1];
            }
        }
        for (int j = 0; j < e.k2MemoN; j++) {
            if (e.k2Memo[2 * j] == v) {
                return e.k2Memo[2 * j + 1];
            }
        }
        if (depth > 64 || e.k2Nreg >= K2_MAX_INSTRS || e.k2Bad) {
            e.k2Bad = true;
            return 0;
        }
        int o = v << Layout.REC_SHIFT;
        int w0 = m.vh[o];
        int kind = w0 >>> Layout.KIND_SHIFT;
        int[] ins;
        switch (kind) {
            case Layout.FVAR:
                if (m.vh[o + Layout.A] != t0 || m.vh[o + Layout.B] >= nb) {
                    e.k2Bad = true;
                    return 0;
                }
                return OP_BIND | m.vh[o + Layout.B];
            case Layout.NEU: {
                int n = w0 & Layout.AUX_MASK;
                int run = m.vh[o + Layout.B];
                ins = new int[n + 2];
                ins[1] = k2VisitSlow(m, m.vh[o + Layout.A], t0, nb, wb, wn, depth + 1);
                for (int i = 0; i < n; i++) {
                    ins[2 + i] = k2VisitSlow(m, Runs.arg(m, run, i), t0, nb, wb, wn, depth + 1);
                }
                ins[0] = (ins[1] >= OP_BIND ? T_APP : T_NEU) | n << 8;
                e.k2Stage = Math.max(e.k2Stage, n);
                break;
            }
            case Layout.PROJ:
                ins = new int[] {T_PROJ, m.vh[o + Layout.B], m.vh[o + Layout.C],
                    k2VisitSlow(m, m.vh[o + Layout.A], t0, nb, wb, wn, depth + 1)};
                break;
            case Layout.NSUC:
                ins = new int[] {T_NSUC, m.vh[o + Layout.B], k2VisitSlow(m, m.vh[o + Layout.A], t0, nb, wb, wn, depth + 1)};
                break;
            case Layout.LAM:
            case Layout.PI:
            case Layout.PII:
            case Layout.SUSP: {
                int frame = m.vh[o + Layout.B];
                if ((w0 & Layout.W0_LET) != 0 && kind == Layout.SUSP || Runs.rest(m, frame) != 0 || m.vh[o + Layout.A] <= 0) {
                    e.k2Bad = true; // a let variable of INFER, a far frame, a term of the worker's overlay (a proof block)
                    return 0;
                }
                int n = Runs.len(m, frame);
                ins = new int[n + 3];
                ins[0] = T_CLOS | n << 8 | kind << 24;
                ins[1] = m.vh[o + Layout.A];
                ins[2] = m.vh[o + Layout.C];
                for (int i = 0; i < n; i++) {
                    ins[3 + i] = k2VisitSlow(m, Runs.arg(m, frame, i), t0, nb, wb, wn, depth + 1);
                }
                e.k2Stage = Math.max(e.k2Stage, n);
                break;
            }
            default:
                e.k2Bad = true;
                return 0;
        }
        if (e.k2Bad) {
            return 0;
        }
        e.k2LastPc = e.k2TmpN;
        e.k2Emit(ins);
        int op = OP_REG | e.k2Nreg++;
        e.k2Remember(v, op);
        return op;
    }
}

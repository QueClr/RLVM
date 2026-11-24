package rlvm.admit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import rlvm.api.KernelException;
import rlvm.api.Reason;
import rlvm.api.ResourceException;
import rlvm.inductive.BlockInput;
import rlvm.inductive.GeneratedBlock;
import rlvm.store.DeclKind;
import rlvm.store.DeclTable;
import rlvm.store.Specials;

/**
 * The environment pass with a fake engine and scripted block admission: frontier semantics, what is
 * handed to rlvm-inductive, what is committed, and every decision the pass takes by itself.
 */
class EnvBuilderTest {

    /** A pass over a finished fixture. */
    static final class Pass {
        final Fixture f;
        final FakeOps ops;
        final ScriptedBlockOps blocks = new ScriptedBlockOps();
        final RecordingListener listener = new RecordingListener();
        final EnvBuilder env;

        /** Units in file order (the v1 policy): what the suites below describe. */
        static final Policy FILE_ORDER =
                new Policy(true, Policy.ForwardRefs.DECLINE, Policy.Unconfirmed.DECLINE, true, false);

        Pass(Fixture f) {
            this(f, FILE_ORDER);
        }

        Pass(Fixture f, Policy policy) {
            this.f = f;
            this.ops = new FakeOps(f.ctx);
            this.blocks.script = (in, out) -> derive(f, in, out);
            this.env = new EnvBuilder(f.store, ops, policy, listener, blocks, u -> f.store.decls().claims(u));
        }

        int all() {
            return env.advanceTo(Integer.MAX_VALUE);
        }

        DeclTable decls() {
            return f.store.decls();
        }
    }

    /** What the kernel would derive for a block without indices, fields or nesting: one recursor per type. */
    static void derive(Fixture f, BlockInput in, GeneratedBlock g) {
        int nT = in.typeCount;
        g.numNested = 0;
        g.isRec = false;
        g.isReflexive = false;
        g.elimOnlyAtZero = false;
        g.k = false;
        g.typeNumIndices = new int[nT];
        g.ctorNumFields = new int[in.ctorCount];
        g.ctorIndex = new int[in.ctorCount];
        g.recCount = nT;
        g.recNamePrefix = new int[nT];
        g.recNameLast = new String[nT];
        // a kernel that agrees with the exported universe parameters of the first recursor record
        g.recLevelParams = in.recCount > 0 ? in.recLevelParams[0] : in.levelParams;
        g.recType = new int[nT];
        g.recNumIndices = new int[nT];
        g.recNumMotives = nT;
        g.recNumMinors = in.ctorCount;
        g.recRuleCtor = new int[nT][];
        g.recRuleFields = new int[nT][];
        g.recRuleRhs = new int[nT][];
        g.recMajorInduct = new int[nT];
        for (int t = 0; t < nT; t++) {
            int n = in.typeCtorCount[t];
            g.recNamePrefix[t] = in.typeName[t];
            g.recNameLast[t] = "rec";
            g.recMajorInduct[t] = in.typeCid[t];
            g.recRuleCtor[t] = new int[n];
            g.recRuleFields[t] = new int[n];
            g.recRuleRhs[t] = new int[n];
            for (int x = 0; x < n; x++) {
                int i = in.typeCtorStart[t] + x;
                g.ctorIndex[i] = x;
                g.recRuleCtor[t][x] = in.ctorCid[i];
            }
        }
    }

    /** {@code inductive I : Type | mk : I} with its recursor record; returns the fixture before finish(). */
    static Fixture.Block unitLike(Fixture f, String typeName) {
        int type1 = f.sort(f.succ(f.zero()));
        int u = f.name("u");
        Fixture.Block b = f.block();
        int iName = f.name(typeName);
        int mkName = f.name(typeName + ".mk");
        int recName = f.name(typeName + ".rec");
        int i = b.type(iName, f.names());
        int mk = b.ctor(mkName, f.names());
        int rec = b.rec(recName, f.names(u));
        int refI = f.cnst(iName);
        b.typeInfo(i, type1, 0, f.names(iName), f.names(mkName));
        b.ctorInfo(mk, refI, iName, 0, 0, 0);
        int minor = f.lam(f.sort(f.param(u)), f.lam(f.bvar(0), f.bvar(0)));
        b.recInfo(rec, f.pi(f.sort(f.param(u)), f.bvar(0)), f.names(iName), 0, 0, 1, 1, false,
                new int[] {mkName}, new long[] {0}, new int[] {minor});
        return b;
    }

    @Test
    void frontierStatusesAndCommit() {
        Fixture f = new Fixture();
        int type1 = f.sort(f.succ(f.zero()));
        int a = f.axiom(f.name("A"), f.names(), type1);
        int d = f.def(f.name("d"), f.names(), type1, f.cnst(f.name("A")));
        Fixture.Block b = unitLike(f, "I");
        b.end();
        int e = f.def(f.name("e"), f.names(), type1, f.cnst(f.name("I")));
        f.finish();
        Pass p = new Pass(f);
        DeclTable decls = p.decls();
        int i = f.cid("I");
        int mk = f.cid("I.mk");
        int rec = f.cid("I.rec");

        assertEquals(0, f.store.envUnits());
        assertEquals(2, p.env.advanceTo(2));
        assertEquals(2, f.store.envUnits());
        assertEquals(2, p.env.envUnits());
        assertEquals(DeclKind.ST_ENV_READY, decls.status(decls.unit(a)));
        assertEquals(DeclKind.ST_ENV_READY, decls.status(decls.unit(d)));
        assertEquals(DeclKind.ST_LOADED, decls.status(decls.unit(i)));
        assertEquals(List.of("frontier 2"), p.listener.events);
        assertEquals(0, p.blocks.generates);
        assertEquals(2, p.env.advanceTo(1), "going back is a no-op");

        assertEquals(4, p.all());
        assertEquals(4, f.store.envUnits());
        assertEquals(List.of("frontier 2", "admitted 2", "frontier 4"), p.listener.events);
        assertEquals(DeclKind.ST_ACCEPTED, decls.status(decls.unit(i)));
        assertEquals(DeclKind.ST_ENV_READY, decls.status(decls.unit(e)));
        assertEquals(-1, p.env.failedUnit());
        assertNull(p.env.failureReason());
        assertEquals(1, p.blocks.generates);
        assertEquals(1, p.blocks.compares);
        assertEquals(1, p.ops.begins);
        assertEquals(1, p.ops.releases, "the derived terms of the committed block are given back");
        assertEquals(1, p.env.blockUnits());
        assertEquals(decls.rank(i), p.ops.lastRank);
        assertEquals(f.names(), p.ops.lastLevelParams);

        // derived facts
        assertEquals(0, decls.inductNumParams(i));
        assertEquals(0, decls.inductNumIndices(i));
        assertEquals(1, decls.inductCtorCount(i));
        assertEquals(mk, decls.inductCtor(i, 0));
        assertEquals(1, decls.inductAllCount(i));
        assertEquals(i, decls.inductAllAt(i, 0));
        assertEquals(DeclTable.IF_ONE_CTOR | DeclTable.IF_STRUCT_LIKE | DeclTable.IF_UNIT_LIKE, decls.inductFlags(i));
        assertEquals(0, decls.inductNumNested(i));
        assertEquals(rec, decls.inductRec(i));
        assertEquals(i, decls.ctorInduct(mk));
        assertEquals(0, decls.ctorIndex(mk));
        assertEquals(0, decls.ctorNumParams(mk));
        assertEquals(0, decls.ctorNumFields(mk));
        assertEquals(0, decls.recNumParams(rec));
        assertEquals(1, decls.recNumMotives(rec));
        assertEquals(1, decls.recNumMinors(rec));
        assertEquals(0, decls.recFlags(rec));
        assertEquals(2, decls.recMajorIndex(rec));
        assertEquals(i, decls.recMajorInduct(rec));
        assertEquals(1, decls.recRuleCount(rec));
        assertEquals(mk, decls.recRuleCtor(rec, 0));
        assertEquals(0, decls.recRuleFields(rec, 0));
        assertEquals(p.blocks.lastInput.recRuleRhs[0][0], decls.recRuleRhs(rec, 0), "the exported rule term is committed");
        assertEquals(0, decls.recRuleFor(rec, mk));
        assertNull(decls.claims(decls.unit(i)), "the claims blob is dropped after the comparison");
    }

    @Test
    void derivedFlagsFollowTheKernelsDefinitions() {
        Fixture f = new Fixture();
        unitLike(f, "I").end();
        unitLike(f, "J").end();
        unitLike(f, "K").end();
        f.finish();
        Pass p = new Pass(f);
        int i = f.cid("I");
        int j = f.cid("J");
        int k = f.cid("K");
        p.blocks.script = (in, out) -> {
            derive(f, in, out);
            if (in.typeCid[0] == i) { // a structure with one field: struct-like, not unit-like; K-like recursor
                out.ctorNumFields[0] = 1;
                out.recRuleFields[0][0] = 1;
                out.k = true;
            } else if (in.typeCid[0] == j) { // recursive: neither
                out.isRec = true;
                out.isReflexive = true;
                out.numNested = 2;
            } else { // indexed family: one constructor, but not a structure
                out.typeNumIndices[0] = 1;
                out.recNumIndices[0] = 1;
            }
        };
        // the claims must say the same (they are compared)
        f.patchClaims(i, blob -> {
            blob[Claims.ctorEntry(blob, 0) + Claims.C_NUM_FIELDS] = 1;
            blob[Claims.firstRec(blob) + Claims.REC_WORDS + Claims.RULE_NFIELDS] = 1;
            blob[Claims.firstRec(blob) + Claims.R_FLAGS] |= Claims.F_K;
        });
        f.patchClaims(k, blob -> blob[Claims.firstRec(blob) + Claims.R_NUM_INDICES] = 1);
        assertEquals(3, p.all(), p.env.failureMessage());
        DeclTable decls = p.decls();
        assertEquals(DeclTable.IF_ONE_CTOR | DeclTable.IF_STRUCT_LIKE, decls.inductFlags(i));
        assertEquals(1, decls.ctorNumFields(f.cid("I.mk")));
        assertEquals(DeclTable.RF_K, decls.recFlags(f.cid("I.rec")));
        assertEquals(1, decls.recRuleFields(f.cid("I.rec"), 0));
        assertEquals(DeclTable.IF_ONE_CTOR | DeclTable.IF_REC | DeclTable.IF_REFLEXIVE, decls.inductFlags(j));
        assertEquals(2, decls.inductNumNested(j));
        assertEquals(DeclTable.IF_ONE_CTOR, decls.inductFlags(k));
        assertEquals(1, decls.inductNumIndices(k));
        assertEquals(1, decls.recNumIndices(f.cid("K.rec")));
        assertEquals(3, decls.recMajorIndex(f.cid("K.rec")));
    }

    @Test
    void failureStopsTheFrontierForGood() {
        Fixture f = new Fixture();
        int type1 = f.sort(f.succ(f.zero()));
        f.axiom(f.name("A"), f.names(), type1);
        unitLike(f, "I").end();
        f.axiom(f.name("B"), f.names(), type1);
        unitLike(f, "J").end();
        f.finish();
        Pass p = new Pass(f);
        p.blocks.generateFailure = new KernelException(Reason.INDUCTIVE_INVALID);
        assertEquals(1, p.all());
        assertEquals(1, f.store.envUnits());
        assertEquals(1, p.env.failedUnit());
        assertEquals(Reason.INDUCTIVE_INVALID, p.env.failureReason());
        assertTrue(p.env.failureMessage().contains("'I'"), p.env.failureMessage());
        assertEquals(List.of("frontier 1", "failed 1 INDUCTIVE_INVALID"), p.listener.events);
        assertEquals(DeclKind.ST_ENV_READY, p.decls().status(0));
        assertEquals(DeclKind.ST_REJECTED, p.decls().status(1));
        assertEquals(DeclKind.ST_LOADED, p.decls().status(2));
        assertEquals(DeclKind.ST_LOADED, p.decls().status(3));
        // for good: nothing is retried, nothing later is touched, no further callbacks
        p.blocks.generateFailure = null;
        assertEquals(1, p.all());
        assertEquals(1, p.env.advanceTo(4));
        assertEquals(1, p.blocks.generates);
        assertEquals(2, p.listener.events.size());
        assertEquals(1, f.store.envUnits());
        p.env.run();
        assertEquals(1, f.store.envUnits());
    }

    @Test
    void failureAtTheFirstUnitReportsNoFrontierMove() {
        Fixture f = new Fixture();
        unitLike(f, "I").end();
        f.finish();
        Pass p = new Pass(f);
        p.blocks.compareFailure = new KernelException(Reason.RECORD_MISMATCH);
        assertEquals(0, p.all());
        assertEquals(List.of("failed 0 RECORD_MISMATCH"), p.listener.events);
        assertEquals(DeclKind.ST_REJECTED, p.decls().status(0));
    }

    @Test
    void declinesOfBlockAdmissionAreDeclines() {
        RuntimeException[] failures = {new ResourceException(Reason.BUDGET_STEPS),
            new ResourceException(Reason.STACK_DEPTH), new UnsupportedOperationException("nested inductives"),
            new IllegalStateException("bug")};
        Reason[] expected = {Reason.BUDGET_STEPS, Reason.STACK_DEPTH, Reason.UNSUPPORTED_INPUT, Reason.INTERNAL_ERROR};
        for (int n = 0; n < failures.length; n++) {
            Fixture f = new Fixture();
            unitLike(f, "I").end();
            f.finish();
            Pass p = new Pass(f);
            p.blocks.generateFailure = failures[n];
            assertEquals(0, p.all());
            assertEquals(expected[n], p.env.failureReason());
            assertFalse(p.env.failureReason().isReject());
            assertEquals(DeclKind.ST_DECLINED, p.decls().status(0));
        }
    }

    @Test
    void blockInputFollowsAllAndCtorsLists() {
        Fixture f = new Fixture();
        int type1 = f.sort(f.succ(f.zero()));
        int u = f.name("u");
        int aName = f.name("A");
        int bName = f.name("B");
        int a1 = f.name("A.a1");
        int a2 = f.name("A.a2");
        int b1 = f.name("B.b1");
        int v = f.name("v");
        Fixture.Block blk = f.block();
        // rows in the order B, A; constructors b1, a2, a1; `all` = [A, B]; A.ctors = [a1, a2]
        int b = blk.type(bName, f.names(u));
        int a = blk.type(aName, f.names(u));
        int cb1 = blk.ctor(b1, f.names(u));
        int ca2 = blk.ctor(a2, f.names(u));
        int ca1 = blk.ctor(a1, f.names(u));
        int recA = blk.rec(f.name("A.rec"), f.names(v, u));
        int recB = blk.rec(f.name("B.rec"), f.names(v, u));
        int refA = f.cnst(aName, f.param(u));
        int refB = f.cnst(bName, f.param(u));
        int all = f.names(aName, bName);
        blk.typeInfo(b, type1, 0, all, f.names(b1));
        blk.typeInfo(a, type1, 0, all, f.names(a1, a2));
        blk.ctorInfo(cb1, f.pi(refA, refB), bName, 0, 0, 1);
        blk.ctorInfo(ca2, f.pi(refB, refA), aName, 1, 0, 1);
        blk.ctorInfo(ca1, refA, aName, 0, 0, 0);
        int rhs = f.lam(type1, f.bvar(0));
        blk.recInfo(recA, type1, all, 0, 0, 2, 3, false, new int[] {a1, a2}, new long[] {0, 1},
                new int[] {rhs, rhs});
        blk.recInfo(recB, type1, all, 0, 0, 2, 3, false, new int[] {b1}, new long[] {1}, new int[] {rhs});
        blk.end();
        f.finish();
        Pass p = new Pass(f);
        int recLps = f.names(v, u);
        p.blocks.script = (in, out) -> {
            derive(f, in, out);
            out.isRec = true;
            out.recLevelParams = recLps;
            out.ctorNumFields[1] = 1;
            out.ctorNumFields[2] = 1;
            out.recRuleFields[0][1] = 1;
            out.recRuleFields[1][0] = 1;
        };
        assertEquals(1, p.all(), p.env.failureMessage());
        BlockInput in = p.blocks.lastInput;
        assertTrue(in.consistent);
        assertEquals(0, in.unit);
        assertEquals(p.decls().rank(a), in.rank);
        assertEquals(f.names(u), in.levelParams);
        assertEquals(0, in.numParams);
        assertEquals(2, in.typeCount);
        assertArrayEquals(new int[] {a, b}, in.typeCid);
        assertArrayEquals(new int[] {aName, bName}, in.typeName);
        assertArrayEquals(new int[] {0, 2}, in.typeCtorStart);
        assertArrayEquals(new int[] {2, 1}, in.typeCtorCount);
        assertEquals(3, in.ctorCount);
        assertArrayEquals(new int[] {ca1, ca2, cb1}, in.ctorCid);
        assertArrayEquals(new int[] {a1, a2, b1}, in.ctorName);
        assertEquals(p.decls().type(ca2), in.ctorType[1]);
        assertArrayEquals(new int[] {aName, aName, bName}, in.ctorClaimInduct);
        assertArrayEquals(new long[] {0, 1, 0}, in.ctorClaimCidx);
        assertArrayEquals(new long[] {0, 1, 1}, in.ctorClaimNumFields);
        assertEquals(2, in.recCount);
        assertArrayEquals(new int[] {recA, recB}, in.recCid);
        assertEquals(recLps, in.recLevelParams[0]);
        assertEquals(all, in.recAll[1]);
        assertArrayEquals(new long[] {2, 2}, in.recNumMotives);
        assertArrayEquals(new long[] {3, 3}, in.recNumMinors);
        assertArrayEquals(new int[] {a1, a2}, in.recRuleCtorName[0]);
        assertArrayEquals(new long[] {0, 1}, in.recRuleFields[0]);
        assertArrayEquals(new int[] {rhs}, in.recRuleRhs[1]);
        // commit: both types share the `all` order and each knows its own recursor
        DeclTable decls = p.decls();
        assertEquals(2, decls.inductAllCount(b));
        assertEquals(a, decls.inductAllAt(b, 0));
        assertEquals(b, decls.inductAllAt(b, 1));
        assertEquals(ca1, decls.inductCtor(a, 0));
        assertEquals(ca2, decls.inductCtor(a, 1));
        assertEquals(cb1, decls.inductCtor(b, 0));
        assertEquals(DeclTable.IF_REC, decls.inductFlags(a));
        assertEquals(DeclTable.IF_REC | DeclTable.IF_ONE_CTOR, decls.inductFlags(b));
        assertEquals(recA, decls.inductRec(a));
        assertEquals(recB, decls.inductRec(b));
        assertEquals(1, decls.ctorIndex(ca2));
        assertEquals(a, decls.ctorInduct(ca2));
        assertEquals(b, decls.recMajorInduct(recB));
        assertEquals(2, decls.recRuleCount(recA));
        assertEquals(ca2, decls.recRuleCtor(recA, 1));
    }

    // ---- structural rejections inside a block: no judgment is ever asked for -------------------------

    /** tutorial 048: an inductive type with {@code levelParams = [u, u]}. */
    @Test
    void blockWithDuplicateUniverseParameter() {
        Fixture f = new Fixture();
        int u = f.name("u");
        Fixture.Block b = f.block();
        int name = f.name("inductLevelParam");
        int t = b.type(name, f.names(u, u));
        b.typeInfo(t, f.sort(f.succ(f.zero())), 0, f.names(name), f.names());
        b.end();
        f.finish();
        Pass p = new Pass(f);
        assertEquals(0, p.all());
        assertEquals(Reason.DUPLICATE_LEVEL_PARAM, p.env.failureReason());
        assertEquals(0, p.blocks.generates);
        assertEquals(0, p.ops.begins);
    }

    /** other/inductive-undeclared-universe: {@code inductive U : Sort u} with no universe parameter. */
    @Test
    void blockWithUndeclaredUniverse() {
        Fixture f = new Fixture();
        int u = f.name("u");
        Fixture.Block b = f.block();
        int name = f.name("U");
        int aName = f.name("U.a");
        int t = b.type(name, f.names());
        int a = b.ctor(aName, f.names());
        b.typeInfo(t, f.sort(f.param(u)), 0, f.names(name), f.names(aName));
        b.ctorInfo(a, f.cnst(name), name, 0, 0, 0);
        b.end();
        f.finish();
        Pass p = new Pass(f);
        assertEquals(0, p.all());
        assertEquals(Reason.UNDECLARED_LEVEL_PARAM, p.env.failureReason());
        assertTrue(p.env.failureMessage().contains("'u'"), p.env.failureMessage());
        assertEquals(0, p.blocks.generates);
    }

    @Test
    void blockRootsSeeOnlyWhatTheKernelHasAdded() {
        // a type header that mentions the type itself
        Fixture f1 = new Fixture();
        Fixture.Block b1 = f1.block();
        int n1 = f1.name("I");
        int t1 = b1.type(n1, f1.names());
        b1.typeInfo(t1, f1.pi(f1.cnst(n1), f1.sort(f1.zero())), 0, f1.names(n1), f1.names());
        b1.end();
        f1.finish();
        Pass p1 = new Pass(f1);
        assertEquals(0, p1.all());
        assertEquals(Reason.UNKNOWN_CONSTANT, p1.env.failureReason());

        // a constructor type that mentions a constructor of the block
        Fixture f2 = new Fixture();
        Fixture.Block b2 = f2.block();
        int n2 = f2.name("I");
        int mk = f2.name("I.mk");
        int mk2 = f2.name("I.mk2");
        int t2 = b2.type(n2, f2.names());
        int c1 = b2.ctor(mk, f2.names());
        int c2 = b2.ctor(mk2, f2.names());
        int refI = f2.cnst(n2);
        b2.typeInfo(t2, f2.sort(f2.succ(f2.zero())), 0, f2.names(n2), f2.names(mk, mk2));
        b2.ctorInfo(c1, refI, n2, 0, 0, 0);
        b2.ctorInfo(c2, f2.pi(f2.app(f2.lam(refI, refI), f2.cnst(mk)), refI), n2, 1, 0, 1);
        b2.end();
        f2.finish();
        Pass p2 = new Pass(f2);
        assertEquals(0, p2.all());
        assertEquals(Reason.UNKNOWN_CONSTANT, p2.env.failureReason());
        assertTrue(p2.env.failureMessage().contains("I.mk2"), p2.env.failureMessage());
        assertEquals(0, p2.blocks.generates);

        // a constructor type with a forward reference: declined, the kernel never sees it
        Fixture f3 = new Fixture();
        Fixture.Block b3 = f3.block();
        int n3 = f3.name("I");
        int mk3 = f3.name("I.mk");
        int t3 = b3.type(n3, f3.names());
        int c3 = b3.ctor(mk3, f3.names());
        b3.typeInfo(t3, f3.sort(f3.succ(f3.zero())), 0, f3.names(n3), f3.names(mk3));
        b3.ctorInfo(c3, f3.pi(f3.cnst(f3.name("Later")), f3.cnst(n3)), n3, 0, 0, 1);
        b3.end();
        f3.axiom(f3.name("Later"), f3.names(), f3.sort(f3.succ(f3.zero())));
        f3.finish();
        Pass p3 = new Pass(f3);
        assertEquals(0, p3.all());
        assertEquals(Reason.FORWARD_REFERENCE, p3.env.failureReason());
        assertEquals(0, p3.blocks.generates);
    }

    // ---- item consistency: exact or decline -----------------------------------------------------------

    @Test
    void inconsistentItemsAreDeclined() {
        // type records that disagree on numParams
        Fixture f1 = new Fixture();
        int type1 = f1.sort(f1.succ(f1.zero()));
        Fixture.Block b1 = f1.block();
        int a = f1.name("A");
        int b = f1.name("B");
        int ta = b1.type(a, f1.names());
        int tb = b1.type(b, f1.names());
        b1.typeInfo(ta, type1, 0, f1.names(a, b), f1.names());
        b1.typeInfo(tb, type1, 1, f1.names(a, b), f1.names());
        b1.end();
        f1.finish();
        Pass p1 = new Pass(f1);
        assertEquals(0, p1.all());
        assertEquals(Reason.UNSUPPORTED_INPUT, p1.env.failureReason());

        // `all` that names something else
        Fixture f2 = new Fixture();
        int t2 = f2.sort(f2.succ(f2.zero()));
        f2.axiom(f2.name("X"), f2.names(), t2);
        Fixture.Block b2 = f2.block();
        int a2 = f2.name("A");
        int ta2 = b2.type(a2, f2.names());
        b2.typeInfo(ta2, t2, 0, f2.names(f2.name("X")), f2.names());
        b2.end();
        f2.finish();
        Pass p2 = new Pass(f2);
        assertEquals(1, p2.all());
        assertEquals(Reason.UNSUPPORTED_INPUT, p2.env.failureReason());

        // a constructor name without a record in the item (leanchecker accepts a record in another item)
        Fixture f3 = new Fixture();
        int t3 = f3.sort(f3.succ(f3.zero()));
        Fixture.Block b3 = f3.block();
        int a3 = f3.name("A");
        int ta3 = b3.type(a3, f3.names());
        b3.typeInfo(ta3, t3, 0, f3.names(a3), f3.names(f3.name("A.mk")));
        b3.end();
        f3.finish();
        Pass p3 = new Pass(f3);
        assertEquals(0, p3.all());
        assertEquals(Reason.UNSUPPORTED_INPUT, p3.env.failureReason());
        assertEquals(0, p3.blocks.generates);

        // an unsafe constructor record inside a safe block
        Fixture f4 = new Fixture();
        Fixture.Block b4 = unitLike(f4, "I");
        b4.unsafe(f4.cid("I.mk"));
        b4.end();
        f4.finish();
        Pass p4 = new Pass(f4);
        assertEquals(0, p4.all());
        assertEquals(Reason.UNSUPPORTED_INPUT, p4.env.failureReason());
    }

    /** other/orphan-ctor: a constructor record whose {@code induct} is not declared anywhere. */
    @Test
    void orphanConstructorIsRejectedUnlessAnotherItemMayAdoptIt() {
        Fixture f = new Fixture();
        int prop = f.sort(f.zero());
        Fixture.Block b = f.block();
        int falseName = f.name("False");
        int rogue = f.name("rogue");
        int t = b.type(falseName, f.names());
        int c = b.ctor(rogue, f.names());
        b.typeInfo(t, prop, 0, f.names(falseName), f.names());
        b.ctorInfo(c, f.cnst(falseName), f.name("Orphan"), 0, 0, 0);
        b.end();
        f.finish();
        Pass p = new Pass(f);
        assertEquals(0, p.all());
        assertEquals(Reason.RECORD_MISMATCH, p.env.failureReason());
        assertTrue(p.env.failureMessage().contains("rogue"), p.env.failureMessage());
        assertEquals(0, p.blocks.generates);

        // the same record claiming a type of this very item that does not list it: still a rejection
        Fixture g = new Fixture();
        Fixture.Block gb = g.block();
        int gFalse = g.name("False");
        int gt = gb.type(gFalse, g.names());
        int gc = gb.ctor(g.name("rogue"), g.names());
        gb.typeInfo(gt, g.sort(g.zero()), 0, g.names(gFalse), g.names());
        gb.ctorInfo(gc, g.cnst(gFalse), gFalse, 0, 0, 0);
        gb.end();
        g.finish();
        Pass pg = new Pass(g);
        assertEquals(0, pg.all());
        assertEquals(Reason.RECORD_MISMATCH, pg.env.failureReason());

        // claiming an inductive type of ANOTHER record: the official checker may let that type adopt it
        Fixture h = new Fixture();
        unitLike(h, "T").end();
        Fixture.Block hb = h.block();
        int s = h.name("S");
        int ht = hb.type(s, h.names());
        int hc = hb.ctor(h.name("T.extra"), h.names());
        hb.typeInfo(ht, h.sort(h.zero()), 0, h.names(s), h.names());
        hb.ctorInfo(hc, h.cnst(h.name("T")), h.name("T"), 1, 0, 0);
        hb.end();
        h.finish();
        Pass ph = new Pass(h);
        assertEquals(1, ph.all());
        assertEquals(Reason.UNSUPPORTED_INPUT, ph.env.failureReason());
    }

    /** A record without types: its constructor and recursor records need a type of another record. */
    @Test
    void recordWithoutTypes() {
        Fixture f = new Fixture();
        Fixture.Block b = f.block();
        int c = b.ctor(f.name("rogue"), f.names());
        b.ctorInfo(c, f.sort(f.zero()), f.name("Nowhere"), 0, 0, 0);
        b.end();
        f.finish();
        Pass p = new Pass(f);
        assertEquals(0, p.all());
        assertEquals(Reason.RECORD_MISMATCH, p.env.failureReason());
        assertEquals(0, p.blocks.generates);

        Fixture g = new Fixture();
        Fixture.Block gb = g.block();
        int r = gb.rec(g.name("rogue"), g.names());
        gb.recInfo(r, g.sort(g.zero()), g.names(), 0, 0, 0, 0, false, new int[0], new long[0], new int[0]);
        gb.end();
        g.finish();
        Pass pg = new Pass(g);
        assertEquals(0, pg.all());
        assertEquals(Reason.RECORD_MISMATCH, pg.env.failureReason());

        // leanchecker accepts a constructor record that sits in another record than its type (probe
        // j_cross_item_ctor); v1 declines it
        Fixture h = new Fixture();
        unitLike(h, "T").end();
        Fixture.Block hb = h.block();
        int hc = hb.ctor(h.name("T.extra"), h.names());
        hb.ctorInfo(hc, h.cnst(h.name("T")), h.name("T"), 1, 0, 0);
        hb.end();
        h.finish();
        Pass ph = new Pass(h);
        assertEquals(1, ph.all());
        assertEquals(Reason.UNSUPPORTED_INPUT, ph.env.failureReason());
        assertEquals(1, ph.blocks.generates, "only the first record reached the kernel");
    }

    /** Verified with leanchecker 4.36 (probe q_ctor_twice, r_ctor_shared): both are kernel errors. */
    @Test
    void constructorListedTwiceIsRejected() {
        Fixture f = new Fixture();
        Fixture.Block b = f.block();
        int tName = f.name("T");
        int mk = f.name("T.mk");
        int t = b.type(tName, f.names());
        int c = b.ctor(mk, f.names());
        b.typeInfo(t, f.sort(f.succ(f.zero())), 0, f.names(tName), f.names(mk, mk));
        b.ctorInfo(c, f.cnst(tName), tName, 0, 0, 0);
        b.end();
        f.finish();
        Pass p = new Pass(f);
        assertEquals(0, p.all());
        assertEquals(Reason.DUPLICATE_DECLARATION, p.env.failureReason());
    }

    // ---- after the comparison ------------------------------------------------------------------------

    /** bugs/ctor-num-fields and bugs/rec-k-lie: scalar claims are re-stated against the derived facts. */
    @Test
    void scalarClaimsAreComparedEvenIfRecordCompareIsLax() {
        Fixture f = new Fixture();
        unitLike(f, "I").end();
        f.finish();
        f.patchClaims(f.cid("I"), blob -> blob[Claims.ctorEntry(blob, 0) + Claims.C_NUM_FIELDS] = 1);
        Pass p = new Pass(f);
        assertEquals(0, p.all());
        assertEquals(Reason.RECORD_MISMATCH, p.env.failureReason());
        assertTrue(p.env.failureMessage().contains("I.mk"), p.env.failureMessage());

        Fixture g = new Fixture();
        unitLike(g, "I").end();
        g.finish();
        g.patchClaims(g.cid("I"), blob -> blob[Claims.firstRec(blob) + Claims.R_FLAGS] |= Claims.F_K);
        Pass pg = new Pass(g);
        assertEquals(0, pg.all());
        assertEquals(Reason.RECORD_MISMATCH, pg.env.failureReason());
        assertTrue(pg.env.failureMessage().contains("I.rec"), pg.env.failureMessage());

        Fixture h = new Fixture();
        unitLike(h, "I").end();
        h.finish();
        h.patchClaims(h.cid("I"), blob -> blob[Claims.firstRec(blob) + Claims.R_NUM_MINORS] = 7);
        Pass ph = new Pass(h);
        assertEquals(0, ph.all());
        assertEquals(Reason.RECORD_MISMATCH, ph.env.failureReason());
    }

    /** bugs/orphan-rec, other/extra-rec, tutorial 136: a recursor record nobody derives. */
    @Test
    void recursorWithoutDerivedTwinIsRejected() {
        Fixture f = new Fixture();
        int prop = f.sort(f.zero());
        int u = f.name("u");
        Fixture.Block b = f.block();
        int falseName = f.name("False");
        int t = b.type(falseName, f.names());
        int rec = b.rec(f.name("False.rec"), f.names(u));
        int rogue = b.rec(f.name("rogue"), f.names());
        b.typeInfo(t, prop, 0, f.names(falseName), f.names());
        b.recInfo(rec, prop, f.names(falseName), 0, 0, 1, 0, false, new int[0], new long[0], new int[0]);
        b.recInfo(rogue, f.cnst(falseName), f.names(), 0, 0, 0, 0, false, new int[0], new long[0], new int[0]);
        b.end();
        f.finish();
        Pass p = new Pass(f);
        int recLps = f.names(u);
        p.blocks.script = (in, out) -> {
            derive(f, in, out);
            out.recLevelParams = recLps;
        };
        p.blocks.present = 1; // a lax comparison that did not notice the extra record
        assertEquals(0, p.all());
        assertEquals(Reason.RECORD_MISMATCH, p.env.failureReason());
        assertTrue(p.env.failureMessage().contains("rogue"), p.env.failureMessage());
    }

    @Test
    void recursorRecordOfATypeInAnotherItemIsDeclined() {
        Fixture f = new Fixture();
        unitLike(f, "T").end();
        Fixture.Block b = f.block();
        int s = f.name("S");
        int t = b.type(s, f.names());
        int rec = b.rec(f.name("T.rec_1"), f.names());
        b.typeInfo(t, f.sort(f.zero()), 0, f.names(s), f.names());
        b.recInfo(rec, f.sort(f.zero()), f.names(s), 0, 0, 1, 0, false, new int[0], new long[0], new int[0]);
        b.end();
        f.finish();
        Pass p = new Pass(f);
        assertEquals(1, p.all());
        assertEquals(Reason.UNSUPPORTED_INPUT, p.env.failureReason());
    }

    /** Verified with leanchecker 4.36 (probe k_no_rec_record): accepted officially; v1 has no generated constants. */
    @Test
    void derivedRecursorMissingFromTheExportIsDeclined() {
        Fixture f = new Fixture();
        Fixture.Block b = f.block();
        int tName = f.name("T");
        int t = b.type(tName, f.names());
        b.typeInfo(t, f.sort(f.zero()), 0, f.names(tName), f.names());
        b.end();
        f.finish();
        Pass p = new Pass(f);
        p.blocks.present = 0;
        assertEquals(0, p.all());
        assertEquals(Reason.UNSUPPORTED_INPUT, p.env.failureReason());
        assertTrue(p.env.failureMessage().contains("T.rec"), p.env.failureMessage());
    }

    /** tutorial 137: a definition already owns the name of the derived recursor. */
    @Test
    void derivedRecursorNameTakenByAnotherDeclarationIsRejected() {
        Fixture f = new Fixture();
        int type1 = f.sort(f.succ(f.zero()));
        int tName = f.name("dup_rec_def2");
        f.def(f.name("dup_rec_def2.rec"), f.names(), type1, f.sort(f.zero()));
        Fixture.Block b = f.block();
        int t = b.type(tName, f.names());
        b.typeInfo(t, type1, 0, f.names(tName), f.names());
        b.end();
        f.finish();
        Pass p = new Pass(f);
        p.blocks.present = 0;
        assertEquals(1, p.all());
        assertEquals(Reason.DUPLICATE_DECLARATION, p.env.failureReason());
    }

    // ---- quotient package ---------------------------------------------------------------------------

    /** Eq, then the four quot records in the exporter's order; returns the names Quot, Quot.mk, Quot.lift, Quot.ind. */
    static int[] quotPackage(Fixture f, boolean withEq) {
        int u = f.name("u");
        int v = f.name("v");
        int prop = f.sort(f.zero());
        if (withEq) {
            unitLike(f, "Eq").end();
        }
        int[] names = {f.name("Quot"), f.name("Quot.mk"), f.name("Quot.lift"), f.name("Quot.ind")};
        f.quot(names[0], f.names(u), prop, DeclKind.QUOT_TYPE);
        f.quot(names[1], f.names(u), prop, DeclKind.QUOT_MK);
        f.quot(names[2], f.names(u, v), prop, DeclKind.QUOT_LIFT);
        f.quot(names[3], f.names(u), prop, DeclKind.QUOT_IND);
        return names;
    }

    @Test
    void quotRecordsAreAdmittedInOrderAndBound() {
        Fixture f = new Fixture();
        quotPackage(f, true);
        f.finish();
        Pass p = new Pass(f);
        assertEquals(5, p.all(), p.env.failureMessage());
        assertEquals(4, p.blocks.quots);
        assertEquals(f.cid("Quot.ind"), p.blocks.lastQuotCid);
        assertEquals(List.of("admitted 0", "admitted 1", "admitted 2", "admitted 3", "admitted 4", "frontier 5"),
                p.listener.events);
        Specials specials = f.store.specials();
        for (int w = Specials.QUOT; w <= Specials.QUOT_IND; w++) {
            assertEquals(Specials.VALID, specials.state(w), "special " + w);
        }
        assertEquals(f.cid("Quot.lift"), specials.cid(Specials.QUOT_LIFT));
        assertEquals(DeclKind.ST_ACCEPTED, p.decls().status(4));
        assertEquals(5, p.ops.begins);
    }

    /** bugs/quot-bad-type: the quotient admission reports the mismatch. */
    @Test
    void quotMismatchIsARejection() {
        Fixture f = new Fixture();
        quotPackage(f, true);
        f.finish();
        Pass p = new Pass(f);
        p.blocks.quotFailure = Reason.QUOT_MISMATCH;
        assertEquals(1, p.all());
        assertEquals(Reason.QUOT_MISMATCH, p.env.failureReason());
        assertEquals(DeclKind.ST_REJECTED, p.decls().status(1));
    }

    @Test
    void quotPreChecks() {
        // without Eq: the official checker fails with unknown constant 'Eq' (probe v_quot_no_eq)
        Fixture f1 = new Fixture();
        quotPackage(f1, false);
        f1.finish();
        Pass p1 = new Pass(f1);
        assertEquals(0, p1.all());
        assertEquals(Reason.QUOT_MISMATCH, p1.env.failureReason());
        assertEquals(0, p1.blocks.quots);

        // Eq declared after the quot records: a forward reference, declined
        Fixture f2 = new Fixture();
        quotPackage(f2, false);
        unitLike(f2, "Eq").end();
        f2.finish();
        Pass p2 = new Pass(f2);
        assertEquals(0, p2.all());
        assertEquals(Reason.FORWARD_REFERENCE, p2.env.failureReason());

        // a quot record under a foreign name: accepted officially (probe b_foreign_quot), declined here
        Fixture f3 = new Fixture();
        unitLike(f3, "Eq").end();
        f3.quot(f3.name("Foo"), f3.names(f3.name("u")), f3.sort(f3.zero()), DeclKind.QUOT_TYPE);
        f3.finish();
        Pass p3 = new Pass(f3);
        assertEquals(1, p3.all());
        assertEquals(Reason.UNSUPPORTED_INPUT, p3.env.failureReason());
        assertEquals(0, p3.blocks.quots);

        // Quot.mk before Quot (probe w_quot_order: accepted officially when Quot follows): declined
        Fixture f4 = new Fixture();
        unitLike(f4, "Eq").end();
        f4.quot(f4.name("Quot.mk"), f4.names(f4.name("u")), f4.sort(f4.zero()), DeclKind.QUOT_MK);
        f4.finish();
        Pass p4 = new Pass(f4);
        assertEquals(1, p4.all());
        assertEquals(Reason.UNSUPPORTED_INPUT, p4.env.failureReason());

        // the right name with the wrong kind or the wrong universe parameters, even if the comparison is lax
        Fixture f5 = new Fixture();
        unitLike(f5, "Eq").end();
        f5.quot(f5.name("Quot"), f5.names(f5.name("u")), f5.sort(f5.zero()), DeclKind.QUOT_MK);
        f5.finish();
        Pass p5 = new Pass(f5);
        assertEquals(1, p5.all());
        assertEquals(Reason.QUOT_MISMATCH, p5.env.failureReason());

        Fixture f6 = new Fixture();
        unitLike(f6, "Eq").end();
        f6.quot(f6.name("Quot"), f6.names(f6.name("w")), f6.sort(f6.zero()), DeclKind.QUOT_TYPE);
        f6.finish();
        Pass p6 = new Pass(f6);
        assertEquals(1, p6.all());
        assertEquals(Reason.QUOT_MISMATCH, p6.env.failureReason());
    }

    /** Verified with leanchecker 4.36 (probes a_def_quot_mk, n_unsafe_quot_mk, o_axiom_quot). */
    @Test
    void reservedQuotNamesUnderOtherRecords() {
        Fixture f1 = new Fixture();
        int type1 = f1.sort(f1.succ(f1.zero()));
        f1.axiom(f1.name("A"), f1.names(), type1);
        f1.def(f1.name("Quot.mk"), f1.names(), type1, f1.sort(f1.zero()));
        f1.finish();
        Pass p1 = new Pass(f1);
        assertEquals(1, p1.all());
        assertEquals(Reason.QUOT_MISMATCH, p1.env.failureReason());
        assertEquals(DeclKind.ST_REJECTED, p1.decls().status(1));

        // an axiom named Quot is an ordinary constant
        Fixture f2 = new Fixture();
        f2.axiom(f2.name("Quot"), f2.names(), f2.sort(f2.succ(f2.zero())));
        f2.finish();
        Pass p2 = new Pass(f2);
        assertEquals(1, p2.all());
        assertEquals(Specials.INVALID, f2.store.specials().state(Specials.QUOT));

        // a dropped unsafe record with a reserved name: rejected at the first unit processed
        Fixture f3 = new Fixture();
        f3.axiom(f3.name("A"), f3.names(), f3.sort(f3.succ(f3.zero())));
        f3.dropUnsafe(f3.name("Quot.lift"));
        f3.finish();
        Pass p3 = new Pass(f3);
        assertEquals(0, p3.all());
        assertEquals(Reason.QUOT_MISMATCH, p3.env.failureReason());

        // a member of an inductive record with a reserved name: declined
        Fixture f4 = new Fixture();
        unitLike(f4, "Quot").end();
        f4.finish();
        Pass p4 = new Pass(f4);
        assertEquals(0, p4.all());
        assertEquals(Reason.UNSUPPORTED_INPUT, p4.env.failureReason());
    }

    // ---- specials, guards, policies -------------------------------------------------------------------

    /** {@code inductive Nat | zero | succ : Nat -> Nat} with its recursor record, optionally with the constructors swapped. */
    static void natBlock(Fixture f, boolean swapped) {
        int u = f.name("u");
        int type1 = f.sort(f.succ(f.zero()));
        int natName = f.name("Nat");
        int zeroName = f.name("Nat.zero");
        int succName = f.name("Nat.succ");
        int first = swapped ? succName : zeroName;
        int second = swapped ? zeroName : succName;
        Fixture.Block b = f.block();
        int nat = b.type(natName, f.names());
        int c0 = b.ctor(first, f.names());
        int c1 = b.ctor(second, f.names());
        int rec = b.rec(f.name("Nat.rec"), f.names(u));
        int natRef = f.cnst(natName);
        int succType = f.pi(natRef, natRef);
        b.typeInfo(nat, type1, 0, f.names(natName), f.names(first, second));
        b.ctorInfo(c0, swapped ? succType : natRef, natName, 0, 0, swapped ? 1 : 0);
        b.ctorInfo(c1, swapped ? natRef : succType, natName, 1, 0, swapped ? 0 : 1);
        int rhs = f.lam(type1, f.bvar(0));
        b.recInfo(rec, type1, f.names(natName), 0, 0, 1, 2, false, new int[] {first, second},
                new long[] {swapped ? 1 : 0, swapped ? 0 : 1}, new int[] {rhs, rhs});
        b.end();
    }

    @Test
    void specialsOfABlockAreBoundAfterTheCommit() {
        for (boolean swapped : new boolean[] {false, true}) {
            Fixture f = new Fixture();
            natBlock(f, swapped);
            f.finish();
            Pass p = new Pass(f);
            int recLps = f.names(f.name("u"));
            p.blocks.script = (in, out) -> {
                derive(f, in, out);
                out.isRec = true;
                out.recLevelParams = recLps;
                out.ctorNumFields[swapped ? 0 : 1] = 1;
                out.recRuleFields[0][swapped ? 0 : 1] = 1;
            };
            Specials specials = f.store.specials();
            assertEquals(Specials.ABSENT, specials.state(Specials.NAT));
            assertEquals(1, p.all(), p.env.failureMessage());
            int expected = swapped ? Specials.INVALID : Specials.VALID;
            assertEquals(expected, specials.state(Specials.NAT));
            assertEquals(expected, specials.state(Specials.NAT_ZERO));
            assertEquals(expected, specials.state(Specials.NAT_SUCC));
            assertEquals(f.cid("Nat"), specials.cid(Specials.NAT));
            assertEquals(f.cid("Nat.succ"), specials.cid(Specials.NAT_SUCC));
        }
    }

    @Test
    void guardStatesOfPlainUnits() {
        Fixture f = new Fixture();
        int type1 = f.sort(f.succ(f.zero()));
        int nat = f.name("Nat");
        f.axiom(nat, f.names(), type1);
        int natRef = f.cnst(nat);
        f.def(f.name("Nat.add"), f.names(), f.pi(natRef, f.pi(natRef, natRef)), natRef);
        f.def(f.name("Nat.mul"), f.names(), f.pi(natRef, natRef), natRef);
        f.finish();
        Pass p = new Pass(f);
        assertEquals(3, p.all());
        Specials specials = f.store.specials();
        assertEquals(Specials.INVALID, specials.state(Specials.NAT), "Nat is an axiom");
        assertEquals(Specials.VALID, specials.state(Specials.NAT_ADD));
        assertEquals(Specials.INVALID, specials.state(Specials.NAT_MUL));
        assertEquals(Specials.ABSENT, specials.state(Specials.NAT_SUB));
        assertEquals(f.cid("Nat.add"), specials.cid(Specials.NAT_ADD));

        // level L0 (official): no guards, every bound special is VALID
        Fixture g = new Fixture();
        int gType = g.sort(g.succ(g.zero()));
        g.axiom(g.name("Nat"), g.names(), gType);
        g.finish();
        Policy official = new Policy(true, Policy.ForwardRefs.DECLINE, Policy.Unconfirmed.DECLINE, false, false);
        Pass pg = new Pass(g, official);
        assertEquals(1, pg.all());
        assertEquals(Specials.VALID, g.store.specials().state(Specials.NAT));
    }

    @Test
    void skippedUnitsLoadLimitCancelAndRun() {
        Fixture f = new Fixture();
        int type1 = f.sort(f.succ(f.zero()));
        f.axiom(f.name("A"), f.names(), type1);
        f.dropUnsafe(f.name("unsafeLoop"));
        f.axiom(f.name("B"), f.names(), type1);
        f.axiom(f.name("C"), f.names(), type1);
        f.finish();
        int units = f.store.loadedUnits();
        Pass p = new Pass(f);
        assertEquals(1, p.env.advanceTo(1));
        p.env.run();
        assertEquals(units, f.store.envUnits());
        assertEquals(units, p.env.advanceTo(units + 100), "never beyond the loaded units");

        Fixture g = new Fixture();
        g.axiom(g.name("A"), g.names(), g.sort(g.zero()));
        g.axiom(g.name("B"), g.names(), g.sort(g.zero()));
        g.finish();
        Pass pg = new Pass(g);
        assertEquals(1, pg.env.advanceTo(1));
        pg.env.cancel();
        assertEquals(1, pg.all());
        assertEquals(1, g.store.envUnits());
        assertEquals(-1, pg.env.failedUnit());

        Fixture h = new Fixture();
        h.axiom(h.name("A"), h.names(), h.sort(h.zero()));
        h.finishIncomplete();
        Pass ph = new Pass(h);
        assertThrows(UnsupportedOperationException.class, ph.env::run);
        assertEquals(1, ph.all(), "advanceTo works on whatever is loaded");
    }

    @Test
    void aDeclineWhileTheFileIsLoadingIsNotRecorded() {
        // an inductive record whose constructor type mentions a constant that is not loaded (yet)
        Fixture f = new Fixture();
        int type1 = f.sort(f.succ(f.zero()));
        f.axiom(f.name("A"), f.names(), type1);
        Fixture.Block b = f.block();
        int n = f.name("I");
        int mk = f.name("I.mk");
        int t = b.type(n, f.names());
        int c = b.ctor(mk, f.names());
        b.typeInfo(t, type1, 0, f.names(n), f.names(mk));
        b.ctorInfo(c, f.pi(f.cnst(f.name("Later")), f.cnst(n)), n, 0, 0, 1);
        b.end();
        f.finishIncomplete();
        Pass p = new Pass(f);
        for (int round = 0; round < 2; round++) {
            assertEquals(1, p.all(), "the pass stops in front of the unit");
            assertTrue(p.env.waitingForLoad());
            assertEquals(-1, p.env.failedUnit());
            assertEquals(-1, p.listener.failedUnit);
            assertEquals(DeclKind.ST_LOADED, f.store.decls().status(1));
            assertEquals(1, f.store.envUnits());
        }
        // the end of the file: nothing declares `Later`, and now that is a rejection
        f.completeLoad();
        assertEquals(1, p.all());
        assertTrue(!p.env.waitingForLoad());
        assertEquals(1, p.env.failedUnit());
        assertEquals(Reason.UNKNOWN_CONSTANT, p.env.failureReason());
        assertEquals(1, p.listener.failedUnit);
        assertEquals(DeclKind.ST_REJECTED, f.store.decls().status(1));
    }

    /**
     * Advances a pass that is expected to leave file order: it then asks its engine for judgments,
     * which the fake engine of these tests refuses with an AssertionError.
     */
    private static void leavesFileOrder(Pass p) {
        try {
            p.all();
        } catch (AssertionError expected) {
            assertTrue(String.valueOf(expected.getMessage()).contains("fake engine"), expected.toString());
        }
    }

    /** Unit 0 mentions the constant of unit 1: a file that is not in declaration order. */
    private static Fixture outOfOrder() {
        Fixture f = new Fixture();
        int type1 = f.sort(f.succ(f.zero()));
        f.axiom(f.name("A"), f.names(), f.cnst(f.name("Later")));
        f.axiom(f.name("Later"), f.names(), type1);
        return f;
    }

    @Test
    void dependencyOrderNeedsASessionThatCoversTheFile() {
        Policy reorder = Policy.arena();
        // a pass created after the load (a batch run): the session covers the file
        Fixture batch = outOfOrder();
        batch.finish();
        Pass pb = new Pass(batch, reorder);
        leavesFileOrder(pb);
        assertTrue(pb.env.replayState() != null, "the pass left file order at unit 0");
        assertEquals(0, pb.env.replayState().first);

        // a pass created while the file is loading (a pipelined run): its session sits on a snapshot of the
        // levels and lists of the units loaded so far, so it never reads ahead - not even after the load
        Fixture early = outOfOrder();
        early.finishIncomplete();
        Pass pe = new Pass(early, reorder);
        // while the file is loading the pass does not move past the unit: only the complete file says
        // whether dependency order admits it (a worker could only decline it)
        assertEquals(0, pe.all());
        assertTrue(pe.env.waitingForLoad());
        assertEquals(0, early.store.envUnits());
        early.completeLoad();
        assertEquals(2, pe.all());
        assertTrue(!pe.env.waitingForLoad());
        assertEquals(null, pe.env.replayState());
        assertEquals(-1, pe.env.failedUnit());
        // the caller that replaces the pass after the load gets one that may leave file order
        Fixture replaced = outOfOrder();
        replaced.finishIncomplete();
        Pass first = new Pass(replaced, reorder);
        assertEquals(0, first.all());
        replaced.completeLoad();
        Pass second = new Pass(replaced, reorder);
        leavesFileOrder(second);
        assertTrue(second.env.replayState() != null);
        assertEquals(0, second.env.replayState().first);

        // ... unless the caller says what its session covers
        Fixture told = outOfOrder();
        told.finishIncomplete();
        Pass pt = new Pass(told, reorder);
        int[] covered = {1};
        pt.env.setCoverage(() -> covered[0]);
        told.completeLoad();
        assertEquals(1, pt.env.advanceTo(1));
        assertEquals(null, pt.env.replayState(), "one unit covered: file order");
        covered[0] = Integer.MAX_VALUE; // the caller reset its lane after the end of the load
        assertEquals(2, pt.all());
        assertEquals(null, pt.env.replayState(), "unit 1 is in declaration order");
        Fixture told2 = outOfOrder();
        told2.finishIncomplete();
        Pass pt2 = new Pass(told2, reorder);
        pt2.env.setCoverage(() -> Integer.MAX_VALUE);
        told2.completeLoad();
        leavesFileOrder(pt2);
        assertTrue(pt2.env.replayState() != null);
        assertEquals(0, pt2.env.replayState().first);

        // and a batch pass whose caller says that the session does NOT cover the file stays in file order
        Fixture stale = outOfOrder();
        stale.finish();
        Pass ps = new Pass(stale, reorder);
        ps.env.setCoverage(() -> 1);
        assertEquals(2, ps.all());
        assertEquals(null, ps.env.replayState());
    }

    @Test
    void constructorNeedsKernelOpsAndRejectsReorder() {
        Fixture f = new Fixture();
        f.axiom(f.name("A"), f.names(), f.sort(f.zero()));
        f.finish();
        assertThrows(IllegalArgumentException.class, () -> new EnvBuilder(f.store, null, Policy.arena(), null));
        // admission in dependency order is a policy like any other; on a file in declaration order it changes nothing
        Policy reorder = new Policy(true, Policy.ForwardRefs.REORDER, Policy.Unconfirmed.DECLINE, true, false);
        EnvBuilder ordered = new EnvBuilder(f.store, new FakeOps(f.ctx), reorder, null);
        assertEquals(1, ordered.advanceTo(1));
        assertEquals(null, ordered.replayState());
        // a null listener is fine
        Fixture g = new Fixture();
        g.axiom(g.name("A"), g.names(), g.sort(g.zero()));
        g.finish();
        EnvBuilder env = new EnvBuilder(g.store, new FakeOps(g.ctx), Policy.arena(), null);
        assertEquals(1, env.advanceTo(1));
    }
}

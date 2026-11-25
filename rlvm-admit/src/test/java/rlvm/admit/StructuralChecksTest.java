package rlvm.admit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import rlvm.api.Reason;
import rlvm.store.DeclKind;

/**
 * Structural checks on hand-built stores (*agentic-note*: design-architecture-document, 9.1): every row of the table in 4.3, the
 * exact walk, and the shapes of the arena reject tests whose reason is structural.
 */
class StructuralChecksTest {

    private static StructuralChecks checks(Fixture f) {
        return new StructuralChecks(f.store, f.ctx.terms, f.ctx.lists, Policy.arena());
    }

    @Test
    void wellFormedDefinitionPasses() {
        Fixture f = new Fixture();
        int u = f.name("u");
        int typeU = f.sort(f.succ(f.param(u)));
        int a = f.axiom(f.name("A"), f.names(u), typeU);
        int constA = f.cnst(f.name("A"), f.param(u));
        int b = f.axiom(f.name("b"), f.names(u), constA);
        int d = f.def(f.name("d"), f.names(u), constA, f.cnst(f.name("b"), f.param(u)));
        f.finish();
        StructuralChecks c = checks(f);
        assertNull(c.checkConstant(a));
        assertNull(c.checkConstant(b));
        assertNull(c.checkConstant(d));
        assertNull(c.lastMessage());
    }

    /** bugs/loose-bvar-value: {@code def f.{u} : Sort u -> Sort u := #0}. */
    @Test
    void looseBoundVariableInValue() {
        Fixture f = new Fixture();
        int u = f.name("u");
        int sortU = f.sort(f.param(u));
        int d = f.def(f.name("f"), f.names(u), f.pi(sortU, sortU), f.bvar(0));
        int t = f.axiom(f.name("g"), f.names(u), f.pi(sortU, f.bvar(1)));
        f.finish();
        StructuralChecks c = checks(f);
        assertEquals(Reason.LOOSE_BVAR, c.checkConstant(d));
        assertEquals(Reason.LOOSE_BVAR, c.checkConstant(t));
        assertNotNull(c.lastMessage());
    }

    /** other/inductive-undeclared-universe has the same root defect: {@code Sort u} with no parameters. */
    @Test
    void undeclaredUniverseParameter() {
        Fixture f = new Fixture();
        int u = f.name("u");
        int v = f.name("v");
        int sortU = f.sort(f.param(u));
        int a = f.axiom(f.name("A"), f.names(), sortU);
        int b = f.axiom(f.name("B"), f.names(v), sortU);
        int ok = f.axiom(f.name("C"), f.names(v, u), sortU);
        int d = f.def(f.name("D"), f.names(u), f.sort(f.succ(f.param(u))), f.sort(f.max(f.param(u), f.param(v))));
        f.finish();
        StructuralChecks c = checks(f);
        assertEquals(Reason.UNDECLARED_LEVEL_PARAM, c.checkConstant(a));
        assertTrue(c.lastMessage().contains("'u'"), c.lastMessage());
        assertEquals(Reason.UNDECLARED_LEVEL_PARAM, c.checkConstant(b));
        assertNull(c.checkConstant(ok));
        assertEquals(Reason.UNDECLARED_LEVEL_PARAM, c.checkConstant(d));
        assertTrue(c.lastMessage().contains("'v'"), c.lastMessage());
    }

    /** tutorial 019: {@code levelParams = [u, u]}. */
    @Test
    void duplicateUniverseParameter() {
        Fixture f = new Fixture();
        int u = f.name("u");
        int d = f.def(f.name("tut06_bad01"), f.names(u, u), f.sort(f.succ(f.zero())), f.sort(f.zero()));
        f.finish();
        StructuralChecks c = checks(f);
        assertEquals(Reason.DUPLICATE_LEVEL_PARAM, c.checkConstant(d));
    }

    @Test
    void distinctOnShortAndLongLists() {
        Fixture f = new Fixture();
        int[] many = new int[40];
        for (int i = 0; i < many.length; i++) {
            many[i] = f.name("u" + i);
        }
        int longOk = f.names(many);
        int[] dup = many.clone();
        dup[39] = dup[3];
        int longDup = f.names(dup);
        int shortOk = f.names(many[0], many[1], many[2]);
        int shortDup = f.names(many[0], many[1], many[0]);
        f.finish();
        StructuralChecks c = checks(f);
        assertTrue(c.distinct(f.names()));
        assertTrue(c.distinct(shortOk));
        assertFalse(c.distinct(shortDup));
        assertTrue(c.distinct(longOk));
        assertFalse(c.distinct(longDup));
    }

    /** tutorial 014: a theorem whose value is the theorem itself. */
    @Test
    void selfReferenceIsUnknownConstant() {
        Fixture f = new Fixture();
        int name = f.name("selfProof");
        int prop = f.sort(f.zero());
        int type = f.pi(prop, f.bvar(0));
        int cid = f.open(DeclKind.THM, name, f.names());
        int self = f.cnst(name);
        f.close(cid, type, self);
        f.finish();
        StructuralChecks c = checks(f);
        assertEquals(Reason.UNKNOWN_CONSTANT, c.checkConstant(cid));
        assertTrue(c.lastMessage().contains("selfProof"), c.lastMessage());
    }

    /** tutorial 140 / 141: a theorem whose value is a dropped unsafe (partial) definition. */
    @Test
    void droppedUnsafeConstantIsUnknown() {
        Fixture f = new Fixture();
        int falseName = f.name("False");
        int falseCid = f.axiom(falseName, f.names(), f.sort(f.zero()));
        int loop = f.name("unsafeLoop");
        int loopRef = f.cnst(loop); // written while the name is unclaimed: an unbound node
        f.dropUnsafe(loop);
        int thm = f.thm(f.name("falseFromUnsafe"), f.names(), f.cnst(falseName), loopRef);
        f.finish();
        StructuralChecks c = checks(f);
        assertNull(c.checkConstant(falseCid));
        assertEquals(Reason.UNKNOWN_CONSTANT, c.checkConstant(thm));
        assertTrue(c.lastMessage().contains("unsafeLoop"), c.lastMessage());
        assertTrue(c.lastMessage().contains("dropped"), c.lastMessage());
    }

    @Test
    void undeclaredNameIsUnknownOnceTheFileIsLoaded() {
        Fixture f = new Fixture();
        int prop = f.sort(f.zero());
        int ghost = f.cnst(f.name("ghost"));
        int a = f.axiom(f.name("a"), f.names(), ghost);
        int d = f.def(f.name("d"), f.names(), prop, f.app(f.lam(prop, f.bvar(0)), ghost));
        f.finish();
        StructuralChecks c = checks(f);
        assertEquals(Reason.UNKNOWN_CONSTANT, c.checkConstant(a));
        assertEquals(Reason.UNKNOWN_CONSTANT, c.checkConstant(d));
    }

    /**
     * Verified with leanchecker 4.36 (work/integration/probe/q130-drop-lift, q131-drop-ind, and the mutants
     * tutorial/127, 128, 131 drop_decl): a quot record named {@code Quot} makes the official kernel add
     * all four quotient constants, so a use of {@code Quot.lift} without a record of that name is accepted
     * officially. RLVM has no generated constants: it must decline, never reject.
     */
    @Test
    void quotConstantWithoutARecordIsDeclinedWhenTheKernelGeneratesIt() {
        Fixture f = new Fixture();
        int u = f.name("u");
        int type1 = f.sort(f.succ(f.zero()));
        f.quot(f.name("Quot"), f.names(u), type1, DeclKind.QUOT_TYPE);
        int lift = f.cnst(f.name("Quot.lift"), f.zero(), f.zero());
        int ind = f.cnst(f.name("Quot.ind"), f.zero());
        int a = f.axiom(f.name("usesLift"), f.names(), lift);
        int d = f.def(f.name("usesInd"), f.names(), type1, ind);
        int ghost = f.axiom(f.name("usesGhost"), f.names(), f.app(lift, f.cnst(f.name("ghost"))));
        f.finish();
        StructuralChecks c = checks(f);
        assertEquals(Reason.UNSUPPORTED_INPUT, c.checkConstant(a));
        assertFalse(Reason.UNSUPPORTED_INPUT.isReject());
        assertTrue(c.lastMessage().contains("Quot.lift"), c.lastMessage());
        assertEquals(Reason.UNSUPPORTED_INPUT, c.checkConstant(d));
        // a certain rejection elsewhere in the root still wins
        assertEquals(Reason.UNKNOWN_CONSTANT, c.checkConstant(ghost));
    }

    /** Official (q130-drop-quot): without a replayed quot record nothing generates the names: unknown constant. */
    @Test
    void quotConstantIsUnknownWhenNoQuotRecordCanGenerateIt() {
        Fixture f = new Fixture();
        int u = f.name("u");
        int type1 = f.sort(f.succ(f.zero()));
        // the official checker erases Quot.mk / Quot.lift / Quot.ind from the constants it replays
        f.quot(f.name("Quot.mk"), f.names(u), type1, DeclKind.QUOT_MK);
        int a = f.axiom(f.name("usesLift"), f.names(), f.cnst(f.name("Quot.lift"), f.zero(), f.zero()));
        int q = f.axiom(f.name("usesQuot"), f.names(), f.cnst(f.name("Quot"), f.zero()));
        f.finish();
        StructuralChecks c = checks(f);
        assertEquals(Reason.UNKNOWN_CONSTANT, c.checkConstant(a));
        assertEquals(Reason.UNKNOWN_CONSTANT, c.checkConstant(q));
    }

    /**
     * The official kernel generates {@code I.rec} (and {@code I.rec_N} for nested blocks) itself; a
     * reference to such a name that has no record is not an unknown constant for it.
     */
    @Test
    void recursorWithoutARecordIsDeclinedNotRejected() {
        Fixture f = new Fixture();
        int type1 = f.sort(f.succ(f.zero()));
        int early = f.axiom(f.name("early"), f.names(), f.cnst(f.name("I.rec"))); // before the block
        Fixture.Block blk = f.block();
        int iName = f.name("I");
        int i = blk.reserve(DeclKind.INDUCT, iName, f.names());
        blk.complete(i, type1);
        blk.end(new int[] {1, 0, 0, 0, 8, 0, 0, 0});
        f.axiom(f.name("A"), f.names(), type1);
        int late = f.axiom(f.name("late"), f.names(), f.cnst(f.name("I.rec")));
        int aux = f.axiom(f.name("aux"), f.names(), f.cnst(f.name("I.rec_12")));
        int notAux = f.axiom(f.name("notAux"), f.names(), f.cnst(f.name("I.rec_x")));
        int notRec = f.axiom(f.name("notRec"), f.names(), f.cnst(f.name("I.recOn")));
        int ofAxiom = f.axiom(f.name("ofAxiom"), f.names(), f.cnst(f.name("A.rec")));
        int ofNothing = f.axiom(f.name("ofNothing"), f.names(), f.cnst(f.name("J.rec")));
        int dropped = f.name("I.rec_3");
        int droppedRef = f.cnst(dropped);
        f.dropUnsafe(dropped);
        int ofDropped = f.axiom(f.name("ofDropped"), f.names(), droppedRef);
        f.finish();
        StructuralChecks c = checks(f);
        assertEquals(Reason.UNSUPPORTED_INPUT, c.checkConstant(early));
        assertEquals(Reason.UNSUPPORTED_INPUT, c.checkConstant(late));
        assertTrue(c.lastMessage().contains("I.rec"), c.lastMessage());
        assertEquals(Reason.UNSUPPORTED_INPUT, c.checkConstant(aux));
        assertEquals(Reason.UNSUPPORTED_INPUT, c.checkConstant(ofDropped));
        assertEquals(Reason.UNKNOWN_CONSTANT, c.checkConstant(notAux));
        assertEquals(Reason.UNKNOWN_CONSTANT, c.checkConstant(notRec));
        assertEquals(Reason.UNKNOWN_CONSTANT, c.checkConstant(ofAxiom));
        assertEquals(Reason.UNKNOWN_CONSTANT, c.checkConstant(ofNothing));
    }

    @Test
    void undeclaredNameIsOnlyDeclinedWhileLoading() {
        Fixture f = new Fixture();
        int ghost = f.cnst(f.name("ghost"));
        int a = f.axiom(f.name("a"), f.names(), ghost);
        f.finishIncomplete();
        StructuralChecks c = checks(f);
        assertEquals(Reason.FORWARD_REFERENCE, c.checkConstant(a));
    }

    /** bugs/constlevels: a constant applied to the wrong number of universe levels. */
    @Test
    void wrongNumberOfUniverseLevels() {
        Fixture f = new Fixture();
        int u = f.name("u");
        int typeName = f.name("T");
        f.axiom(typeName, f.names(u), f.sort(f.succ(f.param(u))));
        int tooFew = f.axiom(f.name("few"), f.names(), f.cnst(typeName));
        int tooMany = f.axiom(f.name("many"), f.names(), f.cnst(typeName, f.zero(), f.zero()));
        int right = f.axiom(f.name("right"), f.names(), f.cnst(typeName, f.zero()));
        // deep inside a value, under binders and a let
        int prop = f.sort(f.zero());
        int deep = f.def(f.name("deep"), f.names(), prop,
                f.let(prop, prop, f.app(f.lam(prop, f.bvar(0)), f.app(f.lam(f.cnst(typeName), prop), prop))));
        f.finish();
        StructuralChecks c = checks(f);
        assertEquals(Reason.LEVEL_ARITY_MISMATCH, c.checkConstant(tooFew));
        assertTrue(c.lastMessage().contains("#1 expected, #0 provided"), c.lastMessage());
        assertEquals(Reason.LEVEL_ARITY_MISMATCH, c.checkConstant(tooMany));
        assertNull(c.checkConstant(right));
        assertEquals(Reason.LEVEL_ARITY_MISMATCH, c.checkConstant(deep));
    }

    @Test
    void forwardReferenceIsDeclinedAndLateBoundNamesToo() {
        Fixture f = new Fixture();
        int type1 = f.sort(f.succ(f.zero()));
        int bName = f.name("B");
        int refB = f.cnst(bName); // unbound: B is declared later
        int a = f.def(f.name("A"), f.names(), type1, refB);
        int b = f.def(bName, f.names(), type1, f.sort(f.zero()));
        int c3 = f.def(f.name("C"), f.names(), type1, refB); // reuses the node written before B existed
        int d = f.def(f.name("D"), f.names(), type1, f.cnst(bName)); // a node written after B: bound
        f.finish();
        StructuralChecks c = checks(f);
        assertEquals(Reason.FORWARD_REFERENCE, c.checkConstant(a));
        assertNull(c.checkConstant(b));
        assertEquals(Reason.FORWARD_REFERENCE, c.checkConstant(c3));
        assertTrue(c.lastMessage().contains("late-bound"), c.lastMessage());
        assertNull(c.checkConstant(d));
    }

    /** Verified with leanchecker 4.36 (work/rlvm-admit/probe/h_forward_arity.ndjson): the official checker rejects. */
    @Test
    void rejectWinsOverDecline() {
        Fixture f = new Fixture();
        int type1 = f.sort(f.succ(f.zero()));
        int bName = f.name("B");
        int badB = f.cnst(bName, f.succ(f.zero())); // B has no universe parameter
        int goodB = f.cnst(bName);
        int a = f.def(f.name("A"), f.names(), type1, f.app(f.lam(type1, f.bvar(0)), f.app(f.lam(goodB, badB), goodB)));
        int a2 = f.def(f.name("A2"), f.names(), goodB, f.bvar(3)); // decline in the type, reject in the value
        f.def(bName, f.names(), type1, f.sort(f.zero()));
        f.finish();
        StructuralChecks c = checks(f);
        assertEquals(Reason.LEVEL_ARITY_MISMATCH, c.checkConstant(a));
        assertEquals(Reason.LOOSE_BVAR, c.checkConstant(a2));
    }

    @Test
    void declineInTheTypeIsKeptWhenTheValueIsFine() {
        Fixture f = new Fixture();
        int type1 = f.sort(f.succ(f.zero()));
        int bName = f.name("B");
        int a = f.def(f.name("A"), f.names(), f.cnst(bName), f.sort(f.zero()));
        f.def(bName, f.names(), type1, f.sort(f.zero()));
        f.finish();
        StructuralChecks c = checks(f);
        assertEquals(Reason.FORWARD_REFERENCE, c.checkConstant(a));
        assertTrue(c.lastMessage().contains("'B'"), c.lastMessage());
    }

    @Test
    void ownUnitVisibilityFollowsTheKindMask() {
        Fixture f = new Fixture();
        Fixture.Block blk = f.block();
        int iName = f.name("I");
        int mkName = f.name("I.mk");
        int recName = f.name("I.rec");
        int i = blk.reserve(DeclKind.INDUCT, iName, f.names());
        int mk = blk.reserve(DeclKind.CTOR, mkName, f.names());
        int rec = blk.reserve(DeclKind.REC, recName, f.names());
        int type1 = f.sort(f.succ(f.zero()));
        int refI = f.cnst(iName);
        int refMk = f.cnst(mkName);
        int viaCtor = f.pi(f.app(f.lam(refI, refI), refMk), refI);
        blk.complete(i, type1);
        blk.complete(mk, f.pi(refI, refI));
        blk.complete(rec, type1);
        blk.end(new int[] {1, 0, 0, 0, 8, 0, 0, 0});
        f.finish();
        StructuralChecks c = checks(f);
        int rank = f.store.decls().rank(i);
        int lps = f.names();
        assertNull(c.checkRoot(type1, rank, lps, StructuralChecks.OWN_NONE));
        // a constructor type may mention the block's types ...
        assertEquals(Reason.UNKNOWN_CONSTANT, c.checkRoot(refI, rank, lps, StructuralChecks.OWN_NONE));
        assertNull(c.checkRoot(f.store.decls().type(mk), rank, lps, StructuralChecks.OWN_TYPES));
        // ... but not its constructors (the kernel adds them after checking their types)
        assertEquals(Reason.UNKNOWN_CONSTANT, c.checkRoot(viaCtor, rank, lps, StructuralChecks.OWN_TYPES));
        assertTrue(c.lastMessage().contains("I.mk"), c.lastMessage());
        assertNull(c.checkRoot(viaCtor, rank, lps, StructuralChecks.OWN_ALL));
        assertNull(c.checkRoot(viaCtor, rank, lps, true));
        assertEquals(Reason.UNKNOWN_CONSTANT, c.checkRoot(viaCtor, rank, lps, false));
        // checkConstant picks the mask from the kind of the row
        assertNull(c.checkConstant(i));
        assertNull(c.checkConstant(mk));
        // a later unit sees every member
        assertNull(c.checkRoot(viaCtor, rank + 1, lps, StructuralChecks.OWN_NONE));
    }

    @Test
    void projectionStructureNameIsAReference() {
        Fixture f = new Fixture();
        int type1 = f.sort(f.succ(f.zero()));
        int sName = f.name("S");
        f.axiom(sName, f.names(), type1);
        int x = f.axiom(f.name("x"), f.names(), f.cnst(sName));
        int refX = f.cnst(f.name("x"));
        int good = f.def(f.name("good"), f.names(), type1, f.proj(sName, 0, refX));
        int ghost = f.def(f.name("bad"), f.names(), type1, f.proj(f.name("Ghost"), 0, refX));
        int later = f.def(f.name("fwd"), f.names(), type1, f.proj(f.name("Later"), 0, refX));
        f.axiom(f.name("Later"), f.names(), type1);
        f.finish();
        StructuralChecks c = checks(f);
        assertNull(c.checkConstant(x));
        assertNull(c.checkConstant(good));
        assertEquals(Reason.UNKNOWN_CONSTANT, c.checkConstant(ghost));
        assertEquals(Reason.FORWARD_REFERENCE, c.checkConstant(later));
    }

    /** Verified with leanchecker 4.36 (probe g_lit_axiom_nat, i_lit_before_nat): the official checker accepts both. */
    @Test
    void literalNeedsItsPrerequisitesEarlier() {
        Fixture f = new Fixture();
        int type1 = f.sort(f.succ(f.zero()));
        int five = f.nat(5);
        int early = f.def(f.name("early"), f.names(), type1, five);
        int nat = f.name("Nat");
        f.axiom(nat, f.names(), type1);
        f.axiom(f.name("Nat.zero"), f.names(), f.cnst(nat));
        f.axiom(f.name("Nat.succ"), f.names(), f.pi(f.cnst(nat), f.cnst(nat)));
        int late = f.def(f.name("late"), f.names(), f.cnst(nat), f.nat(7));
        int reuse = f.def(f.name("reuse"), f.names(), f.cnst(nat), five); // a literal node written before Nat existed
        int text = f.def(f.name("text"), f.names(), type1, f.str("abc"));
        f.finish();
        StructuralChecks c = checks(f);
        assertEquals(Reason.FORWARD_REFERENCE, c.checkConstant(early));
        assertNull(c.checkConstant(late));
        assertNull(c.checkConstant(reuse));
        assertEquals(Reason.UNSUPPORTED_PRELUDE, c.checkConstant(text));
        assertTrue(c.lastMessage().contains("String"), c.lastMessage());
    }

    @Test
    void literalWithoutAnyNatIsDeclinedNotRejected() {
        Fixture f = new Fixture();
        int type1 = f.sort(f.succ(f.zero()));
        int d = f.def(f.name("d"), f.names(), type1, f.nat(5));
        f.finish();
        assertEquals(Reason.UNSUPPORTED_PRELUDE, checks(f).checkConstant(d));
    }

    @Test
    void proofBlockValueIsDecodedAndChecked() {
        Fixture f = new Fixture();
        int prop = f.sort(f.zero());
        int p = f.name("P");
        f.axiom(p, f.names(), prop);
        int refP = f.cnst(p);
        int h = f.name("h");
        f.axiom(h, f.names(), refP);
        // theorem ok : P := (fun (x : P) => x) h, with the value in a proof block
        int ok = f.thmBlock(f.name("ok"), f.names(), refP, w -> {
            w.bvar(0);                                             // 0
            w.lam(Fixture.shared(refP), Fixture.local(1));         // 1
            w.constant(f.store.names().constOf(h), 0, h);          // 2
            w.app(Fixture.local(2), Fixture.local(1));             // 3
            return 3;
        });
        // theorem loose : P := #0
        int loose = f.thmBlock(f.name("loose"), f.names(), refP, w -> {
            w.bvar(0);
            return 0;
        });
        // theorem self : P := self, the reference bound to the theorem's own cid
        int selfName = f.name("self");
        int self = f.thmBlock(selfName, f.names(), refP, w -> {
            w.constant(f.store.names().constOf(selfName), 0, selfName);
            return 0;
        });
        // theorem ghost : P := ghost (never declared: unbound in the block)
        int ghostName = f.name("ghost");
        int ghost = f.thmBlock(f.name("usesGhost"), f.names(), refP, w -> {
            w.constant(~ghostName, 0, ghostName);
            return 0;
        });
        f.finish();
        StructuralChecks c = checks(f);
        assertTrue(f.store.decls().value(ok) < 0, "the value is a proof block");
        assertNull(c.checkConstant(ok));
        assertEquals(Reason.LOOSE_BVAR, c.checkConstant(loose));
        assertEquals(Reason.UNKNOWN_CONSTANT, c.checkConstant(self));
        assertEquals(Reason.UNKNOWN_CONSTANT, c.checkConstant(ghost));
    }

    @Test
    void exactWalkIsIterativeAndVisitsSharedNodesOnce() {
        Fixture f = new Fixture();
        int type1 = f.sort(f.succ(f.zero()));
        int later = f.name("later");
        int unbound = f.cnst(later);
        // a chain 200,000 deep: lam (x : Type) => lam ... => later
        int deep = unbound;
        for (int i = 0; i < 200_000; i++) {
            deep = f.lam(type1, deep);
        }
        // a DAG with 2^60 paths: t_{i+1} = app t_i t_i
        int dag = unbound;
        for (int i = 0; i < 60; i++) {
            dag = f.app(dag, dag);
        }
        int a = f.def(f.name("deep"), f.names(), type1, deep);
        int b = f.def(f.name("dag"), f.names(), type1, dag);
        f.axiom(later, f.names(), type1);
        f.finish();
        StructuralChecks c = checks(f);
        assertEquals(Reason.FORWARD_REFERENCE, c.checkConstant(a));
        assertEquals(Reason.FORWARD_REFERENCE, c.checkConstant(b));
        // the scratch state is clean afterwards
        assertNull(c.checkRoot(type1, f.store.decls().rank(a), f.names(), StructuralChecks.OWN_NONE));
    }

    @Test
    void missingRootAndReorderPolicy() {
        Fixture f = new Fixture();
        f.axiom(f.name("A"), f.names(), f.sort(f.zero()));
        f.finish();
        StructuralChecks c = checks(f);
        assertEquals(Reason.INTERNAL_ERROR, c.checkRoot(0, 1, f.names(), StructuralChecks.OWN_NONE));
        // the policy does not change the checks of a root in file order
        Policy reorder = new Policy(true, Policy.ForwardRefs.REORDER, Policy.Unconfirmed.DECLINE, true, false);
        StructuralChecks r = new StructuralChecks(f.store, f.ctx.terms, f.ctx.lists, reorder);
        assertEquals(Reason.INTERNAL_ERROR, r.checkRoot(0, 1, f.names(), StructuralChecks.OWN_NONE));
    }
}

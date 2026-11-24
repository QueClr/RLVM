package rlvm.machine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The K1 hook of family EV (ISA 2.6 CODE, 4.3.1 "CODE(f) ? call its block", 4.3 INST "CODE present"; staging step 4,
 * behind SW_CODE, off in v0): a closure whose binder term has a block runs the block when it is applied, and the
 * value is the one the generic walk gives (decision 6). The blocks come from M7's elaborator.
 */
@Tag("fast")
class M2CodeTest {

    @Test
    void aClosureWithABlockRunsItAndGivesTheValueOfTheWalk() {
        for (int d : new int[] {Op.HOST_DEPTH, 0}) {
            Fixture f = M2.fresh(d);
            Machine m = f.m;
            int add = f.cid("Nat.add");
            int n = f.fvar("n", "Nat");
            int k = f.fvar("k", "Nat");
            // Nat.add n k = Nat.rec (fun x => Nat) n (fun x ih => Nat.succ ih) k, by the generic walk
            int body = f.apply(f.eval(f.store.decls().value(add)), n, k);
            int[] args = f.run2slots(Values.b(m, body));
            int motive = args[0];
            int step = args[2];
            assertEquals(Layout.LAM, Values.kind(m, step));
            int walked = f.apply(step, n, k);
            int walkedPartial = f.apply(step, n);
            int walkedMotive = f.apply(motive, n);
            int[] walkTrace = M2.trace(f, () -> f.apply(step, k, n));
            assertEquals(M2.list(Op.APPLY, Op.EVAL), M2.list(walkTrace));
            assertEquals(0, Values.memo(m, step, Layout.M_CODE - Layout.M0), "v0 never looks for a block");

            // the same closures with SW_CODE: the sub-blocks of BODY(Nat.add) are found through the binder nodes
            f.switches("code");
            int blk = Elab.elaborate(m, add, Op.ROLE_BODY);
            assumeTrue(blk > 0 && blk < Op.SIG, "M7's elaborator made no BODY block for Nat.add");
            assumeTrue(Code.closureBlock(m, Values.a(m, step)) != 0, "no sub-block for the inner lambda");
            int[] codeTrace = M2.trace(f, () -> assertEquals(walked, f.apply(step, n, k), "D = " + m.hostDepth));
            assertEquals(M2.list(Op.APPLY, Op.ACT), M2.list(codeTrace), "the block ran instead of the walk");
            assertTrue(Values.memo(m, step, Layout.M_CODE - Layout.M0) > 0, "CODE memo");
            assertEquals(walkedPartial, f.apply(step, n), "entry for one applied lambda");
            assertEquals(walkedMotive, f.apply(motive, n));
            assertTrue(M2.settled(f));
        }
    }

    @Test
    void instOfAPiClosureWithABlock() {
        Fixture f = Fixture.prelude();
        // the value has an inner Pi that reads its own variable and the outer one: a sub-block of BODY(PiMaker)
        f.def("PiMaker", "", "(-> Type Type)", "(fun (A : Type) (pi (x : A) ((@ Eq 1) A x x)))", Fixture.regular(1));
        f.def("ArrowMaker", "", "(-> Type Type)", "(fun (A : Type) (pi (x : A) ((@ Eq 2) Type A A)))", Fixture.regular(1));
        for (int d : new int[] {Op.HOST_DEPTH, 0}) {
            M2.reset(f, d, "");
            Machine m = f.m;
            int n = f.fvar("n", "Nat");
            int nat = f.eval(f.term("Nat"));
            int pi = f.apply(f.eval(f.store.decls().value(f.cid("PiMaker"))), nat);
            int arrow = f.apply(f.eval(f.store.decls().value(f.cid("ArrowMaker"))), nat);
            assertEquals(Layout.PI, Values.kind(m, pi));
            int walked = f.inst(pi, n);
            assertEquals("(Eq.{1} Nat ?0:Nat ?0:Nat)", f.show(walked));
            String walkedArrow = f.show(f.inst(arrow, n));
            // a fresh session with SW_CODE: the same closures, their codomains by the blocks
            M2.reset(f, d, "code");
            int n2 = f.fvar("n", "Nat");
            assumeTrue(Elab.elaborate(m, f.cid("PiMaker"), Op.ROLE_BODY) > 0 && Elab.elaborate(m, f.cid("ArrowMaker"), Op.ROLE_BODY) > 0,
                    "M7's elaborator made no BODY block");
            int nat2 = f.eval(f.term("Nat"));
            int pi2 = f.apply(f.eval(f.store.decls().value(f.cid("PiMaker"))), nat2);
            int arrow2 = f.apply(f.eval(f.store.decls().value(f.cid("ArrowMaker"))), nat2);
            assumeTrue(Values.kind(m, pi2) == Layout.PI && Code.closureBlock(m, Values.a(m, pi2)) != 0, "no sub-block for the inner Pi");
            int[] trace = M2.trace(f, () -> assertEquals("(Eq.{1} Nat ?0:Nat ?0:Nat)", f.show(f.inst(pi2, n2))));
            assertEquals(M2.list(Op.INST, Op.ACT), M2.list(trace), "the block ran instead of the walk");
            assertEquals(walkedArrow, f.show(f.inst(arrow2, n2)));
            assertEquals(f.inst(arrow2, n2), Values.memo(m, arrow2, 0), "COD is kept for a codomain that does not read the variable");
            assertEquals(f.inst(arrow2, n2), f.inst(arrow2, Layout.HOLE));
            assertTrue(M2.settled(f));
            assertTrue(walked != 0 && n != 0);
        }
        f.close();
    }
}

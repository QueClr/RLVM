package rlvm.machine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import rlvm.store.DeclKind;
import rlvm.store.Node;

/**
 * The K1 elaborator on the hand-built prelude (ISA 3.4): blocks are a function of the store alone, obey
 * the publication rule (3.3), and nothing is elaborated or looked up in v0 (all switches off).
 */
@Tag("fast")
class M7ElabTest {

    private static String listing(Fixture f, String name, int role) {
        int cid = f.cid(name); // seals the fixture
        return CodeShow.role(f.m, cid, role, false).trim();
    }

    @Test
    void bodiesOfThePrelude() {
        Fixture f = Fixture.prelude();
        assertEquals("BODY(id)  arity 2, LEAF, shape PICK, unread {0}\n   RET A1", listing(f, "id", Op.ROLE_BODY));
        // Nat.add n m = Nat.rec (fun x => Nat) n (fun x ih => Nat.succ ih) m: two closures, an inert application
        String add = listing(f, "Nat.add", Op.ROLE_BODY);
        assertTrue(add.startsWith("BODY(Nat.add)  arity 2, LEAF\n"), add);
        assertTrue(add.contains("KCONST r2, Nat.rec, {1} ; MKAPP r3, r2, r0, A0, r1, A1 ; RET r3"), add);
        assertTrue(add.contains("(2 applied):  KCONST r0, Nat.succ, {} ; MKAPP r1, r0, A1 ; RET r1"), add);
        // constants without a leading lambda keep the generic tier (DELTA memoises their value)
        assertEquals("", listing(f, "two", Op.ROLE_BODY));
        // axioms, opaque constants and inductive types have no BODY
        assertEquals("", listing(f, "p1", Op.ROLE_BODY));
        assertEquals("", listing(f, "opaqueTwo", Op.ROLE_BODY));
        assertEquals("", listing(f, "Nat", Op.ROLE_BODY));
    }

    @Test
    void rulesOfRecursors() {
        Fixture f = Fixture.prelude();
        String nat = listing(f, "Nat.rec", Op.ROLE_RULE);
        assertTrue(nat.contains("RULE(Nat.rec, Nat.zero)  arity 3\n   RET A1"), nat);
        assertTrue(nat.contains("RULE(Nat.rec, Nat.succ)  arity 4\n   BUNREAD A2, 1/2, r1, L1 ; KCONST r0, Nat.rec, {u} ; "
                + "MKAPP r1, r0, A0, A1, A2, A3 ; L1: TAILAPPLY A2, A3, r1"), nat);
        // not a recursor: no RULE role
        assertEquals("", listing(f, "Nat.add", Op.ROLE_RULE));
    }

    @Test
    void telescopes() {
        Fixture f = Fixture.prelude();
        String id = listing(f, "id", Op.ROLE_TELE);
        assertTrue(id.startsWith("TELE(id)  n 2, reads 0b01\n   TARG 0 ; KSORT r8, u ; TCONV 0, r8 ; L1: TBIND 0 ; "
                + "L2: TARG 1 unread ; TCONV 1, g0 ; L3: TBIND 1 ; L4: TRET g0"), id);
        // a constant whose type is not a Pi has no telescope
        assertEquals("", listing(f, "two", Op.ROLE_TELE));
        // Eq.refl : (α : Sort u) (a : α) : Eq α a a: the result is a pattern over the telescope variables
        String refl = listing(f, "Eq.refl", Op.ROLE_TELE);
        assertTrue(refl.contains("TEXPECT T(app) ; KCONST r9, Eq, {u} ; MKAPP r10, r9, g0, g1, g1 ; TRET r10"), refl);
    }

    @Test
    void theElaboratorIsAFunctionOfTheStore() {
        Fixture f = Fixture.prelude();
        f.seal();
        int n = f.store.decls().constantCount();
        int blocks = 0;
        for (int row = 1; row <= n; row++) {
            for (int role : new int[] {Op.ROLE_BODY, Op.ROLE_RULE, Op.ROLE_TELE, Op.ROLE_RULES}) {
                Elab.State a = new Elab.State();
                Elab.begin(a);
                int la = Elab.build(f.m, a, row, role);
                Elab.State b = new Elab.State();
                Elab.begin(b);
                int lb = Elab.build(f.m, b, row, role);
                assertEquals(la, lb);
                assertTrue(Arrays.equals(a.buf, 0, la, b.buf, 0, lb), "row " + row + " role " + role);
                if (la != 0) {
                    blocks++;
                    assertEquals(0, Code.pubcheckSlow(f.m, a.buf, 0, la), "pubcheck " + f.nameOf(row) + " role " + role);
                }
            }
        }
        assertTrue(blocks > 40, "blocks: " + blocks);
        // every block that reached the directory (sub-blocks, rule blocks) obeys the rule as well
        CodeStore cs = f.engine.code;
        for (int id = 1; id <= cs.blockCount(); id++) {
            assertEquals(0, Code.pubcheckSlow(f.m, cs.chunkOf(id), cs.offsetOf(id), cs.lengthOf(id)), "block " + id);
        }
    }

    @Test
    void pubcheckFindsEveryKindOfViolation() {
        Fixture f = Fixture.prelude();
        f.seal();
        Elab.State s = new Elab.State();
        Elab.begin(s);
        int len = Elab.build(f.m, s, f.cid("Nat.add"), Op.ROLE_BODY);
        assertTrue(len > 0);
        assertEquals(0, Code.pubcheckSlow(f.m, s.buf, 0, len));
        int[] b = Arrays.copyOf(s.buf, len);
        int code = Op.BLK_ENTRIES + 1;
        // find the operands by mode and break each in turn
        int broken = 0;
        for (int pc = code; pc < len; pc += 1 + (b[pc] >>> 8 & 255)) {
            for (int i = 1; i <= (b[pc] >>> 8 & 255); i++) {
                int x = b[pc + i];
                int[] c = b.clone();
                switch (x >>> Op.OPND_SHIFT) {
                    case Op.MODE_T:
                        c[pc + i] = Op.operand(Op.MODE_T, Node.OVERLAY_BIT >>> 4); // not an export node
                        break;
                    case Op.MODE_D:
                        c[pc + i] = Op.operand(Op.MODE_D, 0x0FFF_FFFF);
                        break;
                    case Op.MODE_LL:
                        c[pc + i] = Op.operand(Op.MODE_LL, 0x0FFF_FFFF); // a list id outside the frozen base
                        break;
                    case Op.MODE_R:
                    case Op.MODE_A:
                        c[pc + i] = Op.operand(Op.MODE_R, 255);
                        break;
                    default:
                        c[pc + i] = Op.operand(Op.MODE_V, 77); // a value ref
                        break;
                }
                assertEquals(pc + i, Code.pubcheckSlow(f.m, c, 0, len), "operand " + i + " of the instruction at " + pc);
                broken++;
            }
        }
        assertTrue(broken >= 8);
        int[] c = b.clone();
        c[code] = Op.insn(0x7E, 0, 0, 0);
        assertEquals(code, Code.pubcheckSlow(f.m, c, 0, len), "unknown opcode");
        c = b.clone();
        c[Op.BLK_ROW] = 0;
        assertNotEquals(0, Code.pubcheckSlow(f.m, c, 0, len), "no owner row");
        assertNotEquals(0, Code.pubcheckSlow(f.m, b, 0, len - 1), "length mismatch");
    }

    @Test
    void overlayAndProofBlockSourcesAreNeverElaborated() {
        Fixture f = Fixture.prelude();
        f.seal();
        int x = f.term("(Nat.succ Nat.zero)");
        assertTrue(Node.isOverlay(x));
        assertFalse(Elab.sourceOk(x), "an overlay term is not a source (ISA 3.3)");
        assertFalse(Elab.sourceOk(-3), "a proof block is not a source");
        assertFalse(Elab.sourceOk(Node.GEN_BASE | 5), "the generated region cannot be named by a 28-bit operand");
        assertTrue(Elab.sourceOk(f.store.decls().value(f.cid("id"))));
        int thm = f.cid("pThm");
        assertEquals(DeclKind.THM, f.store.decls().kind(thm));
        assertEquals("", listing(f, "pThm", Op.ROLE_BODY));
    }

    @Test
    void nothingRunsInV0() {
        Fixture f = Fixture.prelude();
        f.seal();
        int id = f.cid("id");
        // all staging switches are off: the lookups answer "walk the term" and count nothing
        for (int i = 0; i < 3 * Op.ELAB_AT; i++) {
            assertEquals(0, Code.blockFor(f.m, id, Op.ROLE_BODY));
            assertEquals(0, Code.blockFor(f.m, id, Op.ROLE_TELE));
            assertEquals(0, Code.blockFor(f.m, id, Op.ROLE_RULES));
            assertEquals(0, Cong.planOf(f.m, id));
        }
        assertEquals(0, f.engine.code.requests(id, Op.ROLE_BODY));
        assertEquals(0, f.engine.code.blockCount());
        assertEquals(0, Code.closureBlock(f.m, f.store.decls().value(id)));
        assertEquals(0, Ops.codeOf(f.m, id, Op.ROLE_BODY));
    }

    @Test
    void rentToOwnElaboratesAtTheThresholdOfTheRole() {
        Fixture f = Fixture.prelude();
        f.seal();
        f.switches("code,tele");
        int add = f.cid("Nat.add");
        for (int i = 1; i < f.engine.code.elabAt; i++) {
            assertEquals(0, Code.blockFor(f.m, add, Op.ROLE_BODY), "request " + i);
        }
        int b = Code.blockFor(f.m, add, Op.ROLE_BODY);
        assertTrue(b > 0, "the ELAB_AT-th request elaborates");
        for (int i = 1; i < f.engine.code.teleAt; i++) {
            assertEquals(0, Code.blockFor(f.m, add, Op.ROLE_TELE), "TELE request " + i);
        }
        assertTrue(Code.blockFor(f.m, add, Op.ROLE_TELE) > 0, "the tenth request elaborates TELE");
        assertEquals(b, Code.blockFor(f.m, add, Op.ROLE_BODY));
        assertEquals(b, f.engine.code.blockOf(add, Op.ROLE_BODY));
        assertEquals(2, Code.arityOf(f.m, b));
        assertEquals(1, f.m.x7.elaborated[Op.ROLE_BODY]);
        // a role that cannot be elaborated is asked for once and then stays generic without further work
        int two = f.cid("two");
        for (int i = 0; i < 3 * Op.ELAB_AT; i++) {
            assertEquals(0, Code.blockFor(f.m, two, Op.ROLE_BODY));
        }
        assertEquals(1, f.m.x7.stayedGeneric[Op.ROLE_BODY]);
        // strict mode never reads the code store (ISA 14.2)
        f.m.strict = true;
        assertEquals(0, Code.blockFor(f.m, add, Op.ROLE_BODY));
        f.m.strict = false;
        // the hint of an inner lambda is validated against the directory
        int value = f.store.decls().value(add);
        int inner = f.ctx.terms.b(f.ctx.terms.b(value)); // the application under the two lambdas
        int lam = f.ctx.terms.b(f.ctx.terms.a(inner)); // third argument: fun x ih => Nat.succ ih
        int sub = Code.closureBlock(f.m, lam);
        assertTrue(sub > 0, "the inner lambda has a sub-block");
        assertEquals(0, f.engine.code.hint(lam + 1, sub), "a hint is only valid for its own binder");
        assertEquals(0, f.engine.code.hint(lam, b), "a role block is not a sub-block");
    }
}

package rlvm.machine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.util.HashMap;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import rlvm.store.IntLists;

/**
 * Tier equivalence on values ({@link M8Tiers}; ISA section 11, decision 6). The fingerprint separates
 * what value identity separates; the generic tier agrees with itself on generated applications of real
 * constants (the test of the generator and of the determinism of the judgments). K0 against the K1
 * switches is the slow run {@code report.sh tiers NAME} / {@code M8SlowTest#tiersValuesTutorial}: it is
 * not asserted at publication, because the switches of later stages are work in progress behind "off".
 */
@Tag("fast")
class M8TiersTest {

    private static int need(java.util.function.IntSupplier judgment) {
        try {
            return judgment.getAsInt();
        } catch (UnsupportedOperationException e) {
            Assumptions.abort("not published yet: " + e.getMessage());
            return 0;
        }
    }

    @Test
    void theFingerprintIsValueIdentityAcrossSessions() {
        Fixture f = Fixture.prelude0();
        Machine m = f.m;
        int lam = f.term("(fun (z : Nat) (g #1 z))");
        int lam2 = f.term("(fun (z : Nat) (g z #1))");
        long[] first = new long[6];
        long[] second = new long[6];
        for (int session = 0; session < 2; session++) {
            long[] out = session == 0 ? first : second;
            if (session == 1) {
                f.session.reset();
                // other records first, so that the same values get other indices
                need(() -> Values.mkConst(m, f.cid("Bool.true"), IntLists.EMPTY));
                need(() -> Values.mkSort(m, f.level("3")));
            }
            int n0 = need(() -> Values.mkConst(m, f.cid("n0"), IntLists.EMPTY));
            int fc = need(() -> Values.mkConst(m, f.cid("f"), IntLists.EMPTY));
            m.xp = 0;
            m.X[0] = n0;
            int fn0 = need(() -> Values.mkApp(m, fc, 0, 1));
            m.ep = 0;
            m.es[0] = n0;
            m.es[1] = fn0;
            m.ep = 2;
            int fr = need(() -> Runs.prune(m, 0, 2, f.ctx.terms.fvMask(lam)));
            HashMap<Integer, Long> memo = new HashMap<>();
            out[0] = M8Tiers.fingerprint(m, fn0, memo);
            out[1] = M8Tiers.fingerprint(m, need(() -> Values.mkLam(m, lam, fr, 0)), memo);
            out[2] = M8Tiers.fingerprint(m, need(() -> Values.mkLam(m, lam2, fr, 0)), memo);
            out[3] = M8Tiers.fingerprint(m, need(() -> Values.mkPi(m, lam, fr, 0)), memo);
            out[4] = M8Tiers.fingerprint(m, Layout.imm(7), memo);
            out[5] = M8Tiers.fingerprint(m, n0, memo);
            m.ep = 0;
        }
        for (int i = 0; i < 6; i++) {
            assertEquals(first[i], second[i], "the same value in another session, row " + i);
            for (int j = i + 1; j < 6; j++) {
                assertNotEquals(first[i], first[j], "different values " + i + ", " + j);
            }
        }
    }

    @Test
    void theGenericTierAgreesWithItselfOnGeneratedApplications() throws IOException {
        int n = 0;
        for (String file : new String[] {"work/arena-site/tests/good/tutorial/070_nRec.ndjson",
            "work/arena-site/tests/good/tutorial/112_structEta.ndjson"}) {
            if (!Fixture.exists(file)) {
                continue;
            }
            M8Tiers.Result r;
            try {
                r = M8Tiers.values(file, 1, 0, 1);
            } catch (UnsupportedOperationException e) {
                Assumptions.abort("not published yet: " + e.getMessage());
                return;
            }
            assertTrue(r.ok(), file + ":\n" + String.join("\n", r.differences));
            assertTrue(r.constants > 0, file);
            n++;
        }
        assumeTrue(n > 0, "no arena test present");
    }
}

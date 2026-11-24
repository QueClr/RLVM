package rlvm.admit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import rlvm.api.Outcome;

/**
 * Admission in dependency order ({@code Policy.ForwardRefs.REORDER}) through the real loader,
 * {@code rlvm-inductive} and the reference kernel, on exports that are not in declaration order or
 * that only the official replay decides (src/test/resources/replay): hand-made probes
 * ({@code work/rlvm-admit/probe2/gen.py}) and mutants of the tutorial tests. Every expectation carries
 * the verdict of leanchecker 4.36.0 on the same file; RLVM must give that verdict or decline, and the
 * table pins which of the two.
 */
class ReplayTest {

    private static final int A = Outcome.ACCEPT;
    private static final int R = Outcome.REJECT;
    private static final int D = Outcome.DECLINE;

    /** file, official verdict, RLVM outcome. */
    private static final Object[][] CASES = {
        // forward references
        {"L_forward_chain", A, A},                                   // c := a; a := Bool.true; inductive Bool
        {"L2_forward_chain_bad", R, R},                              // the same with an ill-typed first unit
        {"mutant_good_006_betaReduction.reorder_decl.s1.0000", A, A},
        {"mutant_good_013_thmProof.reorder_decl.s1.0000", A, A},     // forward reference inside a theorem's value
        {"mutant_good_038_twoBool.reorder_decl.s1.0001", A, A},      // an inductive block before what its types mention
        {"mutant_good_102_aNatLit.reorder_decl.s1.0000", A, A},      // a Nat literal before Nat
        {"mutant_good_126_quotMkType.reorder_decl.s1.0001", A, A},   // quot records before Eq
        {"mutant_good_126_quotMkType.reorder_decl.s1.0002", A, A},   // Quot.mk before Quot
        {"mutant_bad_010_nonTypeType.reorder_decl.s1.0000", R, R},
        {"mutant_bad_046_inductBadNonSort.reorder_decl.s1.0000", R, R},
        // cycles
        {"E3_cycle3", R, R},                                         // a := b, b := c, c := a
        {"F_cycle_block", R, R},                                     // D := Bool, Bool.false : D
        // records flagged unsafe inside a safe inductive record
        {"A_unsafe_ctor_forged", A, A},                              // unsafe constructor record, forged cidx: not compared
        {"D_unsafe_ctor_alias", A, A},                               // used through a definition that forces the block
        {"mutant_good_037_boolType.forge_flag.s1.0000", A, A},
        {"mutant_good_036_empty.forge_flag.s1.0001", A, A},          // unsafe recursor record identical to the derived one
        {"C_unsafe_rec_forged", A, D},                               // unsafe recursor record that differs: needs a generated constant
        {"Q_unsafe_ctor_lparams", A, D},
        {"P_unsafe_ctor_unlisted", A, D},
        {"P2_unsafe_ctor_unlisted_used", R, D},
        // `all` and constructor lists that do not match the record
        {"H_all_missing", R, R},
        {"H2_all_missing_dummy_declared", R, D},                     // ... in a file that declares _inhabitedExprDummy
        {"K_type_not_in_all", R, D},                                 // official: by replay order
        {"mutant_bad_038_twoBool.swap_name.s1.0002", R, R},          // a recursor record of another type listed as constructor
        {"mutant_bad_038_twoBool.swap_name.s1.0003", R, R},
        {"mutant_bad_039_andType.swap_name.s1.0001", R, R},
        {"mutant_bad_058_predWithTypeField.swap_name.s1.0000", R, R},
        {"mutant_bad_062_emptyRec.swap_name.s1.0002", R, R},
        {"mutant_bad_072_boolPropRec.swap_name.s1.0001", R, R},      // the type listed as its own constructor
        // the quotient package
        {"J_foreign_quot_extra", A, A},                              // a quot record under a foreign name next to the four
        {"J3_foreign_quot_first", A, A},
        {"J2_foreign_quot_used", R, R},                              // ... is never a constant
        {"mutant_bad_127_quotIndType.drop_decl.s1.0004", R, R},      // Quot.mk / lift / ind without anything that adds them
        {"mutant_bad_127_quotIndType.swap_name.s1.0000", R, R},      // a quot record that is not the canonical constant
        // what the official replay order (the hash order of the names) decides: tutorial 130 mentions
        // Quot.lift and Quot.mk but not Quot, and is accepted because `Quot` precedes `quotLiftReduction`
        {"mutant_good_130_quotLiftReduction.reorder_decl.s1.0001", A, A},
        {"h130_late0_fileorder", A, A},                              // the theorem renamed to thm8 (after Quot)
        {"h130_late0_reordered", A, A},                              // ... and the quot records moved behind it
        {"h130_early0_fileorder", R, R},                             // renamed to thm0 (before Quot): in declaration order!
        {"h130_early0_reordered", R, R},
        {"renamed_q130_00", R, R},                                   // random names (work/rlvm-admit/probe3: 120 files,
        {"renamed_q130_01", A, A},                                   //   16 rejected officially, all in declaration order)
        {"renamed_q126_00", A, A},                                   // tutorial 126 mentions Quot itself: any name does
        // what needs generated constants
        {"R_no_rec_record", A, D},
    };

    private static Path resource(String name) {
        URL url = ReplayTest.class.getResource("/replay/" + name + ".ndjson");
        assertNotNull(url, name);
        try {
            return Path.of(url.toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void check(boolean attached) throws IOException {
        List<String> wrong = new ArrayList<>();
        for (Object[] c : CASES) {
            String name = (String) c[0];
            int official = (Integer) c[1];
            int expected = (Integer) c[2];
            ArenaPipelineTest.Result r = ArenaPipelineTest.run(resource(name), Policy.arena(), attached);
            if (r.outcome != D && r.outcome != official) {
                wrong.add(name + ": WRONG VERDICT " + Outcome.name(r.outcome) + ", official " + Outcome.name(official)
                        + " (" + r.message + ")");
            } else if (r.outcome != expected) {
                wrong.add(name + ": " + Outcome.name(r.outcome) + ", expected " + Outcome.name(expected) + " (" + r.message + ")");
            }
        }
        assertTrue(wrong.isEmpty(), String.join("\n", wrong));
    }

    @Test
    void dependencyOrderWithTheWorkerDrivingThePass() throws IOException {
        check(true);
    }

    /** The driver's shape: the pass runs on its own, workers find the recorded outcomes through the store. */
    @Test
    void dependencyOrderWithASeparateAdmission() throws IOException {
        check(false);
    }

    /**
     * A pipelined run: the units of a file in declaration order were passed in file order while the
     * file was loading (here: by a pass whose caller says that its session covers nothing yet), and
     * only then does the pass see the complete file. The official order rejects unit 5 ({@code thm0}
     * of {@code h130_early0_fileorder} mentions {@code Quot.lift} and is added before {@code Quot}),
     * which is behind the frontier: the pass rejects at the frontier.
     */
    @Test
    void officialOrderRejectsBehindTheFrontier() throws IOException {
        ArenaFiles.Loaded l = new ArenaFiles.Loaded(resource("h130_early0_fileorder_tail"));
        try (rlvm.api.Engine engine = rlvm.ref.RefEngine.FACTORY.create(l.store, rlvm.api.EngineConfig.defaults());
                rlvm.api.Session session = engine.newSession(l.ctx)) {
            RecordingListener listener = new RecordingListener();
            EnvBuilder env = new EnvBuilder(l.store, session.ops(), Policy.arena(), listener);
            int[] covered = {0};
            env.setCoverage(() -> covered[0]);
            assertEquals(6, env.advanceTo(6));
            assertEquals(null, env.replayState());
            assertEquals(-1, env.failedUnit());
            covered[0] = Integer.MAX_VALUE;
            assertEquals(6, env.advanceTo(7));
            assertEquals(6, env.failedUnit());
            assertEquals(rlvm.api.Reason.UNKNOWN_CONSTANT, env.failureReason());
            assertTrue(env.failureMessage().contains("thm0") && env.failureMessage().contains("Quot.lift"),
                    env.failureMessage());
            assertEquals(6, listener.failedUnit);
        }
    }

    /** In file order (the v1 policy) none of these files gets a verdict it does not get from the official checker. */
    @Test
    void fileOrderPolicyDeclinesOrAgrees() throws IOException {
        Policy fileOrder = new Policy(true, Policy.ForwardRefs.DECLINE, Policy.Unconfirmed.DECLINE, true, false);
        int declined = 0;
        for (Object[] c : CASES) {
            String name = (String) c[0];
            int official = (Integer) c[1];
            ArenaPipelineTest.Result r = ArenaPipelineTest.run(resource(name), fileOrder, true);
            if (r.outcome == D) {
                declined++;
            } else if (!name.equals("h130_early0_fileorder") && !name.equals("renamed_q130_00")) {
                assertEquals(Outcome.name(official), Outcome.name(r.outcome), name + ": " + r.message);
            } else {
                // the known difference of file order: a file in declaration order that the official
                // replay order rejects (the policy does not look at that order)
                assertEquals(A, r.outcome, name);
            }
        }
        assertTrue(declined >= 20, "file order declines most of these files: " + declined);
    }
}

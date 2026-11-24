package rlvm.admit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import rlvm.api.Reason;
import rlvm.load.MalformedExportException;

/**
 * Acceptance of work package D1 on the real files: every arena reject test whose reason is
 * structural is rejected without any engine, and the engine-free checks neither reject nor decline
 * anything in the accept tests.
 */
class ArenaStructuralTest {

    private static Path bad(String name) {
        Path root = ArenaFiles.testsRoot();
        assumeTrue(root != null, "work/arena-site/tests is not available");
        Path file = root.resolve("bad").resolve(name + ".ndjson");
        assumeTrue(Files.exists(file), "missing " + file);
        return file;
    }

    private static void expectReject(String test, Reason reason, String mention) throws IOException {
        ArenaFiles.Loaded l = new ArenaFiles.Loaded(bad(test));
        ArenaFiles.Verdict v = ArenaFiles.structuralVerdict(l);
        assertEquals(reason, v.reject, test + ": " + v);
        assertTrue(v.rejectMessage != null && v.rejectMessage.contains(mention), test + ": " + v.rejectMessage);
    }

    @Test
    void structuralRejectTestsAreRejectedWithoutAnEngine() throws IOException {
        expectReject("tutorial/014_selfProof", Reason.UNKNOWN_CONSTANT, "selfProof");
        expectReject("tutorial/019_tut06_bad01", Reason.DUPLICATE_LEVEL_PARAM, "tut06_bad01");
        expectReject("tutorial/048_inductLevelParam", Reason.DUPLICATE_LEVEL_PARAM, "inductLevelParam");
        expectReject("tutorial/140_falseFromUnsafe", Reason.UNKNOWN_CONSTANT, "unsafeLoop");
        expectReject("tutorial/141_falseFromPartial", Reason.UNKNOWN_CONSTANT, "partialLoop");
        expectReject("bugs/loose-bvar-value", Reason.LOOSE_BVAR, "loose");
        expectReject("bugs/constlevels", Reason.LEVEL_ARITY_MISMATCH, "universe levels");
        expectReject("other/inductive-undeclared-universe", Reason.UNDECLARED_LEVEL_PARAM, "'u'");
        expectReject("other/orphan-ctor", Reason.RECORD_MISMATCH, "rogue");
    }

    /** Tutorial 132 - 135, 138, 139: one name declared twice; the loader's name claim rejects the file. */
    @Test
    void nameClashesAreRejectedAtLoad() {
        for (String test : new String[] {"tutorial/132_dup_defs", "tutorial/133_dup_ind_def", "tutorial/134_dup_ctor_def",
            "tutorial/135_dup_rec_def", "tutorial/138_dup_ctor_rec", "tutorial/139_DupConCon"}) {
            Path file = bad(test);
            try {
                new ArenaFiles.Loaded(file);
                fail(test + " was loaded");
            } catch (MalformedExportException expected) {
                assertNotNull(expected.getMessage(), test);
            } catch (IOException e) {
                throw new AssertionError(test, e);
            }
        }
    }

    @Test
    void acceptTestsPassEveryEngineFreeCheck() throws IOException {
        List<Path> good = ArenaFiles.files("good");
        assumeTrue(!good.isEmpty(), "work/arena-site/tests is not available");
        int plain = 0;
        int blocks = 0;
        List<String> failures = new ArrayList<>();
        for (Path file : good) {
            ArenaFiles.Loaded l = new ArenaFiles.Loaded(file);
            ArenaFiles.Verdict v = ArenaFiles.structuralVerdict(l);
            if (v.reject != null || v.decline != null) {
                failures.add(file.getFileName() + ": " + v);
            }
            plain += v.plainUnits;
            blocks += v.blocks;
        }
        assertTrue(failures.isEmpty(), failures.toString());
        assertTrue(plain > 100 && blocks > 100, plain + " declarations, " + blocks + " inductive records");
    }

    /**
     * The other reject tests have no structural defect: their rejection is the engine's or
     * rlvm-inductive's. A structural REJECT there would be a wrong reason; a decline would cost the
     * test.
     */
    @Test
    void otherRejectTestsReachTheEngine() throws IOException {
        List<Path> bad = ArenaFiles.files("bad");
        assumeTrue(!bad.isEmpty(), "work/arena-site/tests is not available");
        List<String> structural = List.of("014_selfProof", "019_tut06_bad01", "048_inductLevelParam",
                "140_falseFromUnsafe", "141_falseFromPartial", "loose-bvar-value", "constlevels",
                "inductive-undeclared-universe", "orphan-ctor", "132_dup_defs", "133_dup_ind_def", "134_dup_ctor_def",
                "135_dup_rec_def", "138_dup_ctor_rec", "139_DupConCon");
        List<String> failures = new ArrayList<>();
        int checked = 0;
        for (Path file : bad) {
            String name = file.getFileName().toString().replace(".ndjson", "");
            if (structural.contains(name)) {
                continue;
            }
            ArenaFiles.Loaded l = new ArenaFiles.Loaded(file);
            ArenaFiles.Verdict v = ArenaFiles.structuralVerdict(l);
            // In particular no root has an unbound node: four hand-written files (large-elim-param,
            // level-imax-leq, level-imax-normalization, nat-rec-rules) put every expression record before
            // the first declaration; the loader writes only reachable records and binds the rest on demand.
            if (v.reject != null || v.decline != null) {
                failures.add(name + ": " + v);
            }
            checked++;
        }
        assertTrue(failures.isEmpty(), failures.toString());
        assertEquals(92 - structural.size(), checked);
    }

    /** The prelude of Init (3.8 MB): no structural failure, and the claims of every block are consistent. */
    @Test
    void initPreludePassesEveryEngineFreeCheck() throws IOException {
        Path file = Path.of("..", "work", "exports", "init-prelude.ndjson").toAbsolutePath().normalize();
        assumeTrue(Files.exists(file), "work/exports/init-prelude.ndjson is not available");
        ArenaFiles.Loaded l = new ArenaFiles.Loaded(file);
        ArenaFiles.Verdict v = ArenaFiles.structuralVerdict(l);
        assertNull(v.reject, v.toString());
        assertNull(v.decline, v.toString());
        assertTrue(v.blocks > 50, v.blocks + " inductive records");
    }
}

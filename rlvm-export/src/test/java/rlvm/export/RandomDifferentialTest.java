package rlvm.export;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Randomized differential tests: generated exports (canonical and wild formatting, dense and sparse
 * ids), unchanged and with random damage, must be treated identically - same sink calls, same verdict,
 * same line - by the template fast path, the general path, the buffered and parallel modes, and the
 * independent reference reader.
 *
 * <p>The number of rounds can be raised with {@code -Drlvm.export.rounds=N} for soak runs.
 */
class RandomDifferentialTest {
    private static final int ROUNDS = Integer.getInteger("rlvm.export.rounds", 1500);

    @Test
    void generatedExportsAreReadIdenticallyByAllReaders() {
        Random rnd = new Random(20261123);
        long lines = 0;
        long general = 0;
        for (int round = 0; round < ROUNDS; round++) {
            boolean dense = rnd.nextInt(4) != 0;
            double wild = switch (rnd.nextInt(4)) {
                case 0 -> 0.0;
                case 1 -> 0.05;
                case 2 -> 0.5;
                default -> 1.0;
            };
            byte[] data = Gen.file(rnd, 5 + rnd.nextInt(120), dense, wild);
            T.Run r = T.agree("round " + round, data);
            T.assertOk(r);
            lines += r.result.lines;
            general += r.result.generalPathLines;
            if (dense) {
                assertTrue(r.result.namesDense && r.result.levelsDense && r.result.exprsDense, "round " + round);
            }
        }
        // both paths must have been exercised substantially
        assertTrue(general > lines / 10, general + " of " + lines);
        assertTrue(lines - general > lines / 4, general + " of " + lines);
    }

    @Test
    void damagedExportsAreTreatedIdenticallyByAllReaders() {
        Random rnd = new Random(42);
        int ok = 0;
        int malformed = 0;
        int unsupported = 0;
        for (int round = 0; round < ROUNDS * 4; round++) {
            boolean dense = rnd.nextInt(4) != 0;
            double wild = rnd.nextInt(3) == 0 ? 0.3 : 0.0;
            byte[] data = Gen.file(rnd, 3 + rnd.nextInt(60), dense, wild);
            int damage = 1 + rnd.nextInt(3);
            for (int i = 0; i < damage; i++) {
                data = Gen.mutate(rnd, data);
            }
            T.Run r = T.agree("damaged round " + round, data);
            if (r.ok) {
                ok++;
            } else if (r.kind == ExportException.Kind.MALFORMED) {
                malformed++;
            } else {
                unsupported++;
            }
        }
        // the damage must hit all three outcomes often enough to mean something
        assertTrue(ok > ROUNDS / 20, "ok " + ok);
        assertTrue(malformed > ROUNDS, "malformed " + malformed);
        assertTrue(unsupported > ROUNDS / 100, "unsupported " + unsupported);
    }

    @Test
    void canonicalExportsNeedNoIdTables() {
        Random rnd = new Random(7);
        for (int round = 0; round < 50; round++) {
            byte[] data = Gen.file(rnd, 200, true, 0.0);
            T.Run r = T.fast(data);
            T.assertOk(r);
            assertTrue(r.result.tableFree, "round " + round);
            assertTrue(r.result.exprsDense && r.result.namesDense && r.result.levelsDense);
        }
    }
}

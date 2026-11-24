package rlvm.inductive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Acceptance test of work package I1 / I2: with the real pipeline (export reader, loader, store,
 * reference kernel) every inductive block of {@code init.ndjson} regenerates identically, i.e.
 * {@code generate} succeeds and {@code RecordCompare} finds every exported constructor and recursor
 * record equal to the derived one; the four quot records are the canonical ones.
 */
class InitBlocksTest {

    private static RealEnv run(String file) throws Exception {
        Path f = Data.export(file);
        assumeTrue(f != null && Files.exists(f), "corpus not present: " + file);
        RealEnv e = RealEnv.load(f);
        try {
            e.pass();
        } catch (RuntimeException x) {
            fail("unit " + e.failedUnit + " (" + e.unitName(e.failedUnit) + "): " + x, x);
        }
        System.out.printf("%s: %d inductive units (%d mutual, %d nested with %d auxiliary types, %d K), %d recursors, "
                + "%d quot units; generate %.1f ms, compare %.1f ms wall, %.1f ms thread CPU (slowest block %s: %.1f ms)%n",
                file, e.inductiveUnits, e.mutualBlocks, e.nestedBlocks, e.nestedTypes, e.kBlocks, e.recursors, e.quotUnits,
                e.generateNanos / 1e6, e.compareNanos / 1e6, e.cpuNanos / 1e6, e.unitName(e.slowestUnit), e.slowestNanos / 1e6);
        return e;
    }

    @Test
    void initPrelude() throws Exception {
        RealEnv e = run("init-prelude.ndjson");
        assertTrue(e.inductiveUnits > 0);
    }

    /**
     * Opt-in: {@code -Drlvm.inductive.corpora=std.ndjson,cedar.ndjson,mathlib.ndjson} runs the
     * environment pass over further exports of {@code work/exports} (minutes and gigabytes).
     */
    @Test
    void furtherCorpora() throws Exception {
        String list = System.getProperty("rlvm.inductive.corpora");
        assumeTrue(list != null && !list.isBlank(), "no -Drlvm.inductive.corpora");
        for (String file : list.split(",")) {
            RealEnv e = run(file.trim());
            assertTrue(e.inductiveUnits > 0);
        }
    }

    @Test
    void init() throws Exception {
        RealEnv e = run("init.ndjson");
        assertEquals(605, e.inductiveUnits);
        assertEquals(4, e.quotUnits);
    }
}

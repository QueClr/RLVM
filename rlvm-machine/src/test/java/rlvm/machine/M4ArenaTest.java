package rlvm.machine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The arena tests named in the acceptance list of work package M4 (ISA 13.6), per declaration against the
 * reference engine ({@code Fixture.diffAgainstReference}), under host depth bounds 64 and 0: the unit-like
 * rule compares the types ({@code bugs/unit-rec-rule}), eta only against a lambda
 * ({@code bugs/eta-ctor-alias}), the success cache holds exact pairs only ({@code bugs/rec-missing-ih}),
 * proof irrelevance is definitive in both directions ({@code perf/irrelevance-refutes}), equal
 * projections unfold their structures lazily ({@code perf/proj-lazy-struct}), and the perf tests whose
 * cost is decided by the order of the official procedure. A machine decline is reported, never hidden.
 *
 * <p>Slow group: {@code scripts/machine-sandbox.sh test M4 -Dgroups=slow -DexcludedGroups= -Dtest='M4ArenaTest'}.
 */
@Tag("slow")
class M4ArenaTest {

    static final String T = "work/arena-site/tests/";

    private static void clean(String path, long maxTicks) throws IOException {
        clean(path, maxTicks, "");
    }

    private static void clean(String path, long maxTicks, String switches) throws IOException {
        assumeTrue(Fixture.exists(path), "missing " + path);
        long ticks64 = -1;
        for (int depth : new int[] {Op.HOST_DEPTH, 0}) {
            Fixture g = Fixture.load(path);
            try {
                g.hostDepth(depth);
                if (!switches.isEmpty()) {
                    g.switches(switches);
                }
                long before = g.m.ticks();
                Fixture.DiffReport r = g.diffAgainstReference();
                long ticks = g.m.ticks() - before;
                System.out.println(path + " (D = " + depth + "): " + r + "; " + ticks + " ticks");
                assertEquals(0, r.soundness + r.completeness, "wrong verdicts on " + path + " (D = " + depth + "):\n" + r);
                assertEquals(0, r.declined, "machine declines on " + path + " (D = " + depth + "):\n" + r);
                assertTrue(ticks <= maxTicks, path + ": " + ticks + " ticks, expected at most " + maxTicks
                        + " (the official order makes this test cheap)");
                if (depth == Op.HOST_DEPTH) {
                    ticks64 = ticks;
                } else {
                    assertEquals(ticks64, ticks, "ticks do not depend on the host depth bound: " + path);
                }
            } finally {
                g.close();
            }
        }
    }

    private static void clean(String path) throws IOException {
        clean(path, Long.MAX_VALUE);
    }

    @Test
    void unitRecRule() throws IOException {
        clean(T + "bad/bugs/unit-rec-rule.ndjson");
    }

    @Test
    void etaCtorAlias() throws IOException {
        clean(T + "bad/bugs/eta-ctor-alias.ndjson");
    }

    @Test
    void recMissingIh() throws IOException {
        clean(T + "bad/bugs/rec-missing-ih.ndjson");
    }

    @Test
    void ctorNumFields() throws IOException {
        clean(T + "bad/bugs/ctor-num-fields.ndjson");
    }

    @Test
    void openTermCacheContext() throws IOException {
        clean(T + "bad/bugs/open-term-cache-context.ndjson");
    }

    @Test
    void irrelevanceRefutes() throws IOException {
        clean(T + "good/perf/irrelevance-refutes.ndjson", 2_000_000);
    }

    @Test
    void irrelevanceBeforeEvaluation() throws IOException {
        clean(T + "good/perf/irrelevance-before-evaluation.ndjson", 2_000_000);
    }

    @Test
    void projLazyStruct() throws IOException {
        clean(T + "good/perf/proj-lazy-struct.ndjson", 2_000_000);
    }

    @Test
    void projCheapStruct() throws IOException {
        clean(T + "good/perf/proj-cheap-struct.ndjson", 2_000_000);
    }

    @Test
    void projStuckStruct() throws IOException {
        clean(T + "good/perf/proj-stuck-struct.ndjson", 2_000_000);
    }

    @Test
    void argsBeforeUnfold() throws IOException {
        clean(T + "good/perf/args-before-unfold.ndjson", 2_000_000);
    }

    @Test
    void identicalNesting() throws IOException {
        clean(T + "good/perf/identical-nesting.ndjson", 2_000_000);
    }

    @Test
    void repeatedSubproblem() throws IOException {
        clean(T + "good/perf/repeated-subproblem.ndjson", 2_000_000);
    }

    @Test
    void unrollVersusEvaluate() throws IOException {
        clean(T + "good/perf/unroll-versus-evaluate.ndjson", 2_000_000);
    }

    /**
     * {@code allInK 0 N p} against its one-step unfolding, N = 10^6. Officially both sides are the same term after
     * one unfolding ({@code is_equiv} in {@code quick_is_def_eq}). In the machine the two loop bodies are different
     * closures, so the complete procedure (v0) descends the structural recursion: 45 ticks and about 4 KB per level,
     * E_MEM under the 2 GiB budget of the harness after 487,000 levels (measured 2026-11-24; NOTES-M4 "Measured").
     * With the structural identity walk {@link Same} (switch {@code m4a}, D28) the machine sees what the official
     * kernel sees: 272,629 ticks for the whole file (measured through the launcher).
     */
    @Test
    void loopUnfoldedOnceWithTheIdentityWalk() throws IOException {
        clean(T + "good/perf/loop-unfolded-once.ndjson", 3_000_000, "m4a");
    }

    /** The acceptance files once more with every switch of this package on: the same verdicts. */
    @Test
    void theAcceptanceFilesWithFactsAndProbes() throws IOException {
        for (String f : new String[] {"bad/bugs/unit-rec-rule.ndjson", "bad/bugs/eta-ctor-alias.ndjson",
            "bad/bugs/rec-missing-ih.ndjson", "good/perf/irrelevance-refutes.ndjson", "good/perf/proj-lazy-struct.ndjson",
            "bad/bugs/proj-of-subst-prop.ndjson", "bad/bugs/rec-of-subst-prop.ndjson", "bad/bugs/proj-of-stuck-prop.ndjson"}) {
            clean(T + f, Long.MAX_VALUE, "facts,probes,lazyeval,m4a");
        }
    }

    @Test
    void letFold() throws IOException {
        clean(T + "good/perf/let-fold.ndjson", 2_000_000);
    }

    @Test
    void unitEtaAndStructEtaOfTheTutorial() throws IOException {
        for (String f : new String[] {"good/tutorial/108_unitEta1.ndjson", "good/tutorial/109_unitEta2.ndjson",
            "good/tutorial/110_unitEta3.ndjson"}) {
            if (Fixture.exists(T + f)) {
                clean(T + f);
            }
        }
    }
}

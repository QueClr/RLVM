package rlvm.machine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import rlvm.api.EngineConfig;
import rlvm.ref.RefKernel;
import rlvm.testkit.KernelSampler;

/**
 * The judgment-level differential test ({@link M8Sampler}): the wiring is right (the reference kernel
 * sampled against itself agrees and really compares something), stubs are told apart from differences,
 * and the machine can be sampled at any state of the work. Whether the machine agrees is what the slow
 * runs and the {@code machine-sample-*} reports say; nothing here fails because a judgment of another
 * package is still a stub or still wrong.
 */
@Tag("fast")
class M8SamplerTest {

    static final String T = "work/arena-site/tests/";
    static final String[] FILES = {T + "good/tutorial/070_nRec.ndjson", T + "good/tutorial/112_structEta.ndjson"};

    @Test
    void theReferenceKernelAgreesWithItselfAndIsReallyCompared() throws IOException {
        long problems = 0;
        long compared = 0;
        for (String file : FILES) {
            if (!Fixture.exists(file)) {
                continue;
            }
            M8Sampler.Options o = new M8Sampler.Options();
            o.every = 1;
            o.underTest = lane -> new RefKernel(lane.ctx, EngineConfig.defaults());
            M8Sampler.Result r = M8Sampler.sample(file, o);
            assertTrue(r.ok(), file + ": " + r.differences);
            assertEquals(0, r.notImplemented);
            assertTrue(r.sampledConstants > 0, file);
            problems += r.tally.problems;
            compared += r.tally.infer + r.tally.whnf + r.tally.defEq;
        }
        assumeTrue(problems > 0, "no arena test present");
        assertTrue(compared > problems, "infer, whnf and isDefEq answers were compared: " + compared + " on " + problems + " sub-terms");
    }

    @Test
    void theMachineCanBeSampledAtAnyStateOfTheWork() throws IOException {
        String file = FILES[0];
        assumeTrue(Fixture.exists(file));
        M8Sampler.Options o = new M8Sampler.Options();
        o.every = 1;
        M8Sampler.Result r = M8Sampler.sample(file, o);
        assertTrue(r.sampledConstants > 0);
        // every sub-problem is either answered and compared, or ended in a stub, or was skipped / undecided
        assertTrue(r.tally.problems > 0);
        for (KernelSampler.Difference d : r.differences) {
            System.err.println("M8SamplerTest (not asserted here, see report.sh sample): " + d);
        }
        if (!r.hasData()) {
            assertTrue(r.notImplemented > 0 || r.tally.skipped > 0, "no answer and no stub: what happened to the judgments?");
        }
    }

    @Test
    void stubsAreNotDifferences() {
        assertEquals("M3: Reduce.whnf", M8Sampler.stubOf(diff("CRASH",
                "exception java.lang.UnsupportedOperationException: M3: Reduce.whnf is not implemented yet at rlvm.machine.Reduce.whnf(Reduce.java:70)")));
        assertNull(M8Sampler.stubOf(diff("CRASH", "exception java.lang.IllegalStateException: boom at X.y(X.java:1)")));
        assertNull(M8Sampler.stubOf(diff("INFER", "Nat")));
        assertNotNull(M8Sampler.stubOf(diff("CRASH", "exception java.lang.UnsupportedOperationException: MachineOps needs a Quoter is not implemented yet")));
    }

    private static KernelSampler.Difference diff(String kind, String underTest) {
        try {
            java.lang.reflect.Constructor<?> c = KernelSampler.Difference.class.getDeclaredConstructors()[0];
            c.setAccessible(true);
            return (KernelSampler.Difference) c.newInstance(kind, 1, 0, "c", "value", "", "infer c", underTest, "oracle", 1L);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The reproduction command of the {@code machine-sample-*} reports: {@code -Dm8.file=F [-Dm8.unit=U] [-Dm8.seed=S]}. */
    @Test
    void oneFile() throws IOException {
        String file = System.getProperty("m8.file");
        assumeTrue(file != null, "set -Dm8.file=EXPORT");
        M8Sampler.Options o = new M8Sampler.Options();
        if (o.every == 0) {
            o.every = 1;
        }
        M8Sampler.Result r = M8Sampler.sample(file, o);
        System.out.println("== " + file + ": " + r.sampledConstants + " constants sampled; " + r.tally + "; stubs " + r.notImplemented + " "
                + r.notImplementedBy);
        for (KernelSampler.Difference d : r.differences) {
            System.out.println(d);
        }
        for (String l : r.openCrashLines) {
            System.out.println(l);
        }
        assertTrue(r.ok(), (r.differences.size() + r.openCrashes) + " differences");
    }
}

package rlvm.cli.probe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Truffle runtime compilation on the JVM (the project toolchain is a GraalVM, so the optimizing runtime
 * must be active). The native image is probed by scripts/build.sh, which runs {@code rlvm --truffle-probe}.
 */
class TruffleProbeTest {
    private static boolean onGraalVm() {
        return System.getProperty("java.vendor.version", "").contains("GraalVM");
    }

    @Test
    void optimizingRuntimeIsActive() {
        assumeTrue(onGraalVm(), "not a GraalVM JDK");
        assertFalse(TruffleProbe.isFallbackRuntime(), TruffleProbe.runtimeDescription());
    }

    @Test
    void bothRoutesReachLastTierCode() {
        assumeTrue(onGraalVm(), "not a GraalVM JDK");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        boolean ok = TruffleProbe.run(new PrintStream(bytes, true, StandardCharsets.UTF_8), Map.of("engine.CompilerThreads", "1"));
        String report = bytes.toString(StandardCharsets.UTF_8);
        assertTrue(ok, report);
        assertTrue(report.contains("bare call target: tier=last-tier"), report);
        assertTrue(report.contains("engine.CompilerThreads=1}: tier=last-tier"), report);
    }

    @Test
    void reportParsing() {
        assertTrue(TruffleProbe.reportsLastTier("tier=last-tier calls=10"));
        assertFalse(TruffleProbe.reportsLastTier("tier=first-tier calls=10"));
        assertFalse(TruffleProbe.reportsLastTier("tier=interpreted calls=10"));
        assertFalse(TruffleProbe.reportsLastTier(null));
        assertEquals("com.oracle.truffle.api.impl.DefaultTruffleRuntime", TruffleProbe.FALLBACK_RUNTIME);
    }
}

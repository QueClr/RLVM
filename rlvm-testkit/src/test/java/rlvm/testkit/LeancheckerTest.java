package rlvm.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import rlvm.testkit.Leanchecker.Result;
import rlvm.testkit.Leanchecker.Verdict;

class LeancheckerTest {

    @TempDir
    Path tmp;

    private Path file(String name, String content) throws Exception {
        Path p = tmp.resolve(name);
        Files.write(p, content.getBytes(StandardCharsets.UTF_8));
        return p;
    }

    // ---- interpretation of the official checker's output ------------------------------------------------

    @Test
    void acceptNeedsExitZeroAndTheAcceptLine() {
        Result r = Leanchecker.classify(0, false, "Lean default kernel accepts the solution\n", "", 0.1, 0.1, 100, "ab");
        assertEquals(Verdict.ACCEPT, r.verdict);
        assertEquals(0, r.exitCode);
        assertEquals("", r.message);
        assertTrue(r.decided());
        // exit 0 without the line is not an acceptance
        assertEquals(Verdict.ERROR, Leanchecker.classify(0, false, "", "", 0, 0, 0, "ab").verdict);
    }

    @Test
    void kernelRejectionCarriesTheKernelMessage() {
        String out = "Lean default kernel rejects the solution: while replaying declaration 'badDef':\n"
                + "(kernel) declaration type mismatch, 'badDef' has type\n  Type 1\nbut it is expected to have type\n  Prop\n";
        Result r = Leanchecker.classify(1, false, out, "", 0.1, 0.1, 100, "ab");
        assertEquals(Verdict.REJECT, r.verdict);
        assertEquals("kernel", r.stage);
        assertEquals("while replaying declaration 'badDef': (kernel) declaration type mismatch, 'badDef' has type Type 1 but it is "
                + "expected to have type Prop", r.message);
    }

    @Test
    void readerExceptionIsARejection() {
        Result r = Leanchecker.classify(1, false, "", "uncaught exception: Duplicate declaration: dup_defs\n", 0, 0, 0, "ab");
        assertEquals(Verdict.REJECT, r.verdict);
        assertEquals("reader", r.stage);
        assertEquals("uncaught exception: Duplicate declaration: dup_defs", r.message);
    }

    @Test
    void quotientPostCheckIsARejection() {
        String out = "Lean default kernel accepts the solution\n"
                + "Quotient post-check rejects the solution: Quotient constant mismatch on: Quot.lift\n";
        Result r = Leanchecker.classify(1, false, out, "", 0, 0, 0, "ab");
        assertEquals(Verdict.REJECT, r.verdict);
        assertEquals("post-check", r.stage);
        assertTrue(r.message.contains("Quot.lift"));
    }

    @Test
    void resourceLimitsOfTheKernelAreMarked() {
        Result r = Leanchecker.classify(1, false, "Lean default kernel rejects the solution: (kernel) deep recursion detected\n", "",
                0, 0, 0, "ab");
        assertEquals(Verdict.REJECT, r.verdict);
        assertEquals("resource", r.stage);
    }

    @Test
    void failuresOfTheToolAreNotVerdicts() {
        // exit 1, but nothing was judged
        assertEquals(Verdict.ERROR, Leanchecker.classify(1, false, "",
                "uncaught exception: no such file or directory (error code: 2)\n  file: /nonexistent\n", 0, 0, 0, "ab").verdict);
        assertEquals(Verdict.ERROR, Leanchecker.classify(1, false, "",
                "uncaught exception: failed to read file '/x/Lean.olean', incompatible header\n", 0, 0, 0, "ab").verdict);
        assertEquals(Verdict.ERROR, Leanchecker.classify(1, false, "", "INTERNAL PANIC: out of memory\n", 0, 0, 0, "ab").verdict);
        // signals and other statuses
        assertEquals(Verdict.ERROR, Leanchecker.classify(139, false, "", "", 0, 0, 0, "ab").verdict);
        assertEquals(Verdict.ERROR, Leanchecker.classify(1, true, "Lean default kernel rejects the solution: x", "", 0, 0, 0, "ab").verdict);
        assertEquals(Verdict.ERROR, Leanchecker.classify(0, true, "Lean default kernel accepts the solution", "", 0, 0, 0, "ab").verdict);
        assertFalse(Leanchecker.classify(2, false, "", "", 0, 0, 0, "ab").decided());
    }

    // ---- running and caching, with a fake checker --------------------------------------------------------

    @Test
    void verdictsAreCachedByContent() throws Exception {
        Path tc = Fixtures.fakeToolchain(tmp.resolve("tc"));
        Leanchecker lc = new Leanchecker(tc, tmp.resolve("cache"));
        assertTrue(lc.available());
        Path good = file("good.ndjson", "{\"meta\":{}}\n{\"a\":1}\n");
        Path bad = file("bad.ndjson", "{\"meta\":{}}\n{\"a\":\"FAKE-REJECT\"}\n");

        Result g1 = lc.check(good, Duration.ofSeconds(20));
        Result b1 = lc.check(bad, Duration.ofSeconds(20));
        assertEquals(Verdict.ACCEPT, g1.verdict);
        assertEquals(Verdict.REJECT, b1.verdict);
        assertEquals("kernel", b1.stage);
        assertFalse(g1.cached);
        assertEquals(2, Fixtures.calls(tc));

        Result g2 = lc.check(good, Duration.ofSeconds(20));
        Result b2 = lc.check(bad, Duration.ofSeconds(20));
        assertTrue(g2.cached && b2.cached);
        assertEquals(2, Fixtures.calls(tc), "the second round came from the cache");
        assertEquals(g1.verdict, g2.verdict);
        assertEquals(b1.message, b2.message);
        assertEquals(b1.exitCode, b2.exitCode);
        assertEquals(Leanchecker.sha256(bad), b2.sha256);

        // the same bytes under another name hit the cache; other bytes do not
        Path copy = file("copy.ndjson", "{\"meta\":{}}\n{\"a\":\"FAKE-REJECT\"}\n");
        assertTrue(lc.check(copy, Duration.ofSeconds(20)).cached);
        Path other = file("other.ndjson", "{\"meta\":{}}\n{\"a\":2}\n");
        assertFalse(lc.check(other, Duration.ofSeconds(20)).cached);
        assertEquals(3, Fixtures.calls(tc));

        // a second instance over the same cache directory sees the entries
        assertTrue(new Leanchecker(tc, tmp.resolve("cache")).check(good, Duration.ofSeconds(20)).cached);
        // no cache directory: every call runs
        Leanchecker uncached = new Leanchecker(tc, null);
        assertFalse(uncached.check(good, Duration.ofSeconds(20)).cached);
        assertFalse(uncached.check(good, Duration.ofSeconds(20)).cached);
        assertEquals(5, Fixtures.calls(tc));
    }

    @Test
    void readerAndPostCheckRejectionsThroughAProcess() throws Exception {
        Path tc = Fixtures.fakeToolchain(tmp.resolve("tc"));
        Leanchecker lc = new Leanchecker(tc, null);
        Result reader = lc.check(file("r.ndjson", "FAKE-READER\n"), Duration.ofSeconds(20));
        assertEquals(Verdict.REJECT, reader.verdict);
        assertEquals("reader", reader.stage);
        Result post = lc.check(file("p.ndjson", "FAKE-POSTCHECK\n"), Duration.ofSeconds(20));
        assertEquals(Verdict.REJECT, post.verdict);
        assertEquals("post-check", post.stage);
        Result deep = lc.check(file("d.ndjson", "FAKE-DEEP\n"), Duration.ofSeconds(20));
        assertEquals("resource", deep.stage);
    }

    @Test
    void timeoutKillsTheCheckerAndIsNotAVerdict() throws Exception {
        Path tc = Fixtures.fakeToolchain(tmp.resolve("tc"));
        Leanchecker lc = new Leanchecker(tc, tmp.resolve("cache"));
        Path slow = file("slow.ndjson", "FAKE-SLEEP\n");
        long t0 = System.nanoTime();
        Result r = lc.check(slow, Duration.ofMillis(700));
        double seconds = (System.nanoTime() - t0) / 1e9;
        assertEquals(Verdict.TIMEOUT, r.verdict);
        assertEquals(-1, r.exitCode);
        assertFalse(r.decided());
        assertTrue(seconds < 15, "the checker was not killed: " + seconds + " s");
        // a cached timeout answers a request that would not wait longer ...
        assertTrue(lc.check(slow, Duration.ofMillis(500)).cached);
        // ... but not one that would wait longer
        Result again = lc.check(slow, Duration.ofMillis(1200));
        assertFalse(again.cached);
        assertEquals(Verdict.TIMEOUT, again.verdict);
    }

    @Test
    void aCrashIsAnErrorNotARejection() throws Exception {
        Path tc = Fixtures.fakeToolchain(tmp.resolve("tc"));
        Result r = new Leanchecker(tc, null).check(file("c.ndjson", "FAKE-CRASH\n"), Duration.ofSeconds(20));
        assertEquals(Verdict.ERROR, r.verdict);
        assertFalse(r.decided());
    }

    @Test
    void missingFileAndMissingCheckerAreErrorsAndNeverCached() throws Exception {
        Path tc = Fixtures.fakeToolchain(tmp.resolve("tc"));
        Leanchecker lc = new Leanchecker(tc, tmp.resolve("cache"));
        Result missing = lc.check(tmp.resolve("nope.ndjson"), Duration.ofSeconds(5));
        assertEquals(Verdict.ERROR, missing.verdict);
        assertEquals(0, Fixtures.calls(tc));
        Leanchecker none = new Leanchecker(tmp.resolve("no-toolchain"), tmp.resolve("cache"));
        assertFalse(none.available());
        Result r = none.check(file("x.ndjson", "{}\n"), Duration.ofSeconds(5));
        assertEquals(Verdict.ERROR, r.verdict);
        assertFalse(Files.exists(tmp.resolve("cache")), "errors of the tool must not be cached");
    }

    // ---- the real checker, when the pinned toolchain is installed -----------------------------------------

    @Test
    void theRealCheckerJudgesTheFixtures() throws Exception {
        Leanchecker lc = new Leanchecker(Leanchecker.locate().binary().getParent().getParent(), null);
        Assumptions.assumeTrue(lc.available(), "pinned Lean toolchain not installed");
        Result good = lc.check(Fixtures.copy("001_basicDef", tmp), Duration.ofSeconds(120));
        Result bad = lc.check(Fixtures.copy("002_badDef", tmp), Duration.ofSeconds(120));
        assertEquals(Verdict.ACCEPT, good.verdict, good.toString());
        assertEquals(Verdict.REJECT, bad.verdict, bad.toString());
        assertEquals("kernel", bad.stage);
        assertTrue(bad.message.contains("declaration type mismatch"), bad.message);
        assertTrue(good.wallSeconds > 0);
    }

    /** Classification of mutants is reproducible: two independent runs (no cache) give the same verdict and message. */
    @Test
    void classificationOfMutantsIsReproducible() throws Exception {
        Leanchecker lc = new Leanchecker(Leanchecker.locate().binary().getParent().getParent(), null);
        Assumptions.assumeTrue(lc.available(), "pinned Lean toolchain not installed");
        Path src = Fixtures.copy("070_nRec", tmp.resolve("src"));
        int n = 0;
        int rejected = 0;
        for (ExportMutator.Mutation kind : ExportMutator.Mutation.values()) {
            List<ExportMutator.Mutant> ms = new ExportMutator(1).mutants(src, kind, 2, tmp.resolve("m"));
            for (ExportMutator.Mutant m : ms) {
                Result first = lc.check(m.path, Duration.ofSeconds(120));
                Result second = lc.check(m.path, Duration.ofSeconds(120));
                assertFalse(first.cached || second.cached);
                assertEquals(first.verdict, second.verdict, m.description);
                assertEquals(first.exitCode, second.exitCode, m.description);
                assertEquals(first.stage, second.stage, m.description);
                assertEquals(first.message, second.message, m.description);
                assertTrue(first.decided(), m.description + ": " + first);
                if (kind.verdictPreserving) {
                    assertEquals(Verdict.ACCEPT, first.verdict, kind + " must keep the verdict: " + m.description + " -> " + first);
                }
                if (first.rejected()) {
                    rejected++;
                }
                n++;
            }
        }
        assertTrue(n >= 30, "mutants: " + n);
        assertTrue(rejected >= 10, "rejected: " + rejected);
    }
}

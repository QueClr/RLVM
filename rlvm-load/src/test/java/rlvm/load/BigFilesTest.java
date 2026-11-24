package rlvm.load;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Init and Std through the loader into the in-memory target: exact record counts (the numbers of
 * docs/research/01 section 2.12, which were counted by an independent JSON-parsing script) and the
 * full structural comparison with the reader module's reference sink.
 */
class BigFilesTest {
    static final Path EXPORTS = TestFiles.root().resolve("work/exports");

    private static Oracle check(String name) throws IOException {
        Path file = EXPORTS.resolve(name);
        Assumptions.assumeTrue(Files.isRegularFile(file), name + " not present");
        Oracle o = new Oracle(file).load();
        o.verify();
        o.verifySharingFlags();
        LoadReport r = o.report;
        assertTrue(r.complete);
        assertEquals(Files.size(file), r.inputBytes);
        assertEquals(r.lines, 1 + r.nameRecords + r.levelRecords + r.exprRecords + r.declRecords);
        assertEquals(0, r.droppedUnsafe);
        assertEquals(0, r.unresolvedRefs, "every constant is declared before use in exporter output");
        assertEquals(0, r.trailingExprRecords);
        assertEquals(0, r.exprMData);
        assertTrue(!r.sparseIds);
        System.out.println("[" + name + "] " + r);
        return o;
    }

    @Test
    void init() throws IOException {
        LoadReport r = check("init.ndjson").report;
        assertEquals(6_495_296, r.lines);
        assertEquals(5_228_615, r.exprApp);
        assertEquals(531_743, r.exprLam);
        assertEquals(302_481, r.exprForall);
        assertEquals(57_535, r.exprConst);
        assertEquals(11_380, r.exprLet);
        assertEquals(2_948, r.exprProj);
        assertEquals(1_837, r.exprStrLit);
        assertEquals(285, r.exprNatLit);
        assertEquals(121, r.exprSort);
        assertEquals(147, r.exprBVar);
        assertEquals(174_019, r.nameStrRecords);
        assertEquals(125_066, r.nameNumRecords);
        assertEquals(90, r.levelSuccRecords);
        assertEquals(269, r.levelMaxRecords);
        assertEquals(150, r.levelIMaxRecords);
        assertEquals(62, r.levelParamRecords);
        assertEquals(42_451, r.thmRecords);
        assertEquals(15_239, r.defRecords);
        assertEquals(605, r.inductiveRecords);
        assertEquals(244, r.opaqueRecords);
        assertEquals(4, r.axiomRecords);
        assertEquals(4, r.quotRecords);
        assertEquals(58_547, r.units);
    }

    @Test
    void std() throws IOException {
        LoadReport r = check("std.ndjson").report;
        assertEquals(10_611_507, r.lines);
        assertEquals(8_090_565, r.exprApp);
        assertEquals(1_062_503, r.exprLam);
        assertEquals(639_125, r.exprForall);
        assertEquals(100_998, r.exprConst);
        assertEquals(22_116, r.exprLet);
        assertEquals(9_329, r.exprProj);
        assertEquals(3_055, r.exprStrLit);
        assertEquals(453, r.exprNatLit);
        assertEquals(223, r.exprSort);
        assertEquals(147, r.exprBVar);
        assertEquals(322_169, r.nameStrRecords);
        assertEquals(258_724, r.nameNumRecords);
        assertEquals(146, r.levelSuccRecords);
        assertEquals(493, r.levelMaxRecords);
        assertEquals(181, r.levelIMaxRecords);
        assertEquals(133, r.levelParamRecords);
        assertEquals(71_751, r.thmRecords);
        assertEquals(27_944, r.defRecords);
        assertEquals(1_046, r.inductiveRecords);
        assertEquals(397, r.opaqueRecords);
        assertEquals(4, r.axiomRecords);
        assertEquals(4, r.quotRecords);
    }

    @Test
    void initPreludeWithoutProofBlocks() throws IOException {
        Path file = EXPORTS.resolve("init-prelude.ndjson");
        Assumptions.assumeTrue(Files.isRegularFile(file), "init-prelude.ndjson not present");
        Oracle o = new Oracle(file).load(LoadOptions.batch(Files.size(file)).withProofBlocks(false));
        o.verify();
        o.verifySharingFlags();
        assertEquals(0, o.report.blockValues);
    }
}

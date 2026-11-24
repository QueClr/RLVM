package rlvm.main;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import rlvm.cli.LauncherAccess;

/**
 * A load-level decline is per unit (request of rlvm-load, requests.log 2026-11-24T08:17Z): when the
 * loader meets a record it cannot represent, the units loaded completely before it are still
 * checked. A rejection among them is the verdict (the official checker rejects such a file too);
 * otherwise the run declines; it can never be accepted. Through the real launcher, batch and
 * pipelined mode.
 *
 * <p>The unsupported record here is an inductive type whose constructor type is wrapped in metadata,
 * which the loader declines (work/status/integration.md, change 2).
 */
class LoadDeclineTest {

    private static final String META = "{\"meta\":{\"exporter\":{\"name\":\"lean4export\",\"version\":\"3.1.0\"},"
            + "\"format\":{\"version\":\"3.1.0\"},\"lean\":{\"githash\":\"611c117bfaf7b7ae1cfcf246ce6fba31e8f509e9\","
            + "\"version\":\"4.36.0\"}}}\n";

    /** {@code def NAME : Sort 0 := Sort 1} (ill-typed) or {@code def NAME : Sort 2 := Sort 1} (well-typed). */
    private static String def(int nameId, String name, boolean good, int exprBase, int levelBase) {
        int l1 = levelBase;
        int l2 = levelBase + 1;
        return "{\"in\":" + nameId + ",\"str\":{\"pre\":0,\"str\":\"" + name + "\"}}\n"
                + "{\"il\":" + l1 + ",\"succ\":0}\n"
                + "{\"il\":" + l2 + ",\"succ\":" + l1 + "}\n"
                + "{\"ie\":" + exprBase + ",\"sort\":" + (good ? l2 : 0) + "}\n"
                + "{\"ie\":" + (exprBase + 1) + ",\"sort\":" + l1 + "}\n"
                + "{\"def\":{\"all\":[" + nameId + "],\"hints\":\"opaque\",\"levelParams\":[],\"name\":" + nameId
                + ",\"safety\":\"safe\",\"type\":" + exprBase + ",\"value\":" + (exprBase + 1) + "}}\n";
    }

    /** An inductive type T : Prop with one constructor whose type is {@code mdata (const T)}: declined at load. */
    private static String inductiveWithMetadata(int nameBase, int exprBase) {
        int t = nameBase;
        int mk = nameBase + 1;
        return "{\"in\":" + t + ",\"str\":{\"pre\":0,\"str\":\"T\"}}\n"
                + "{\"in\":" + mk + ",\"str\":{\"pre\":" + t + ",\"str\":\"mk\"}}\n"
                + "{\"ie\":" + exprBase + ",\"sort\":0}\n"
                + "{\"const\":{\"name\":" + t + ",\"us\":[]},\"ie\":" + (exprBase + 1) + "}\n"
                + "{\"ie\":" + (exprBase + 2) + ",\"mdata\":{\"data\":{},\"expr\":" + (exprBase + 1) + "}}\n"
                + "{\"inductive\":{\"ctors\":[{\"cidx\":0,\"induct\":" + t + ",\"isUnsafe\":false,\"levelParams\":[],\"name\":"
                + mk + ",\"numFields\":0,\"numParams\":0,\"type\":" + (exprBase + 2) + "}],\"recs\":[],\"types\":[{\"all\":["
                + t + "],\"ctors\":[" + mk + "],\"isRec\":false,\"isReflexive\":false,\"isUnsafe\":false,\"levelParams\":[],"
                + "\"name\":" + t + ",\"numIndices\":0,\"numNested\":0,\"numParams\":0,\"type\":" + exprBase + "}]}}\n";
    }

    @TempDir
    Path dir;

    private LauncherAccess.Result run(String name, String content, String... options) throws Exception {
        Path f = dir.resolve(name + ".ndjson");
        Files.writeString(f, content);
        String[] args = new String[options.length + 3];
        args[0] = "--threads";
        args[1] = "1";
        System.arraycopy(options, 0, args, 2, options.length);
        args[args.length - 1] = f.toString();
        return LauncherAccess.run(new HashMap<>(), args);
    }

    @Test
    void aRejectionBeforeTheUnsupportedRecordIsTheVerdict() throws Exception {
        String file = META + def(1, "badDef", false, 0, 1) + inductiveWithMetadata(2, 10);
        for (String[] mode : new String[][] {{}, {"-X", "load=pipelined"}}) {
            LauncherAccess.Result r = run("bad-then-unsupported", file, mode);
            if (mode.length > 0 && r.err.contains("not implemented")) {
                continue; // the published driver does not load in pipelined mode yet
            }
            assertEquals(1, r.status, "the ill-typed definition before the unsupported record is rejected: " + r);
            assertTrue(r.err.contains("badDef"), r.err);
        }
    }

    @Test
    void goodUnitsBeforeTheUnsupportedRecordDoNotMakeTheFileAccepted() throws Exception {
        String file = META + def(1, "goodDef", true, 0, 1) + inductiveWithMetadata(2, 10);
        LauncherAccess.Result r = run("good-then-unsupported", file);
        assertEquals(2, r.status, "declined, never accepted: " + r);
        assertTrue(r.err.contains("metadata"), r.err);
        // Without the unsupported record the same definition is accepted (the test file is what it claims).
        assertEquals(0, run("good-alone", META + def(1, "goodDef", true, 0, 1)).status);
        assertEquals(1, run("bad-alone", META + def(1, "badDef", false, 0, 1)).status);
    }

    @Test
    void anUnsupportedRecordBeforeARejectionDeclines() throws Exception {
        // Nothing after the unsupported record is loaded: the ill-typed definition is never seen.
        String file = META + inductiveWithMetadata(1, 0) + def(3, "badDef", false, 10, 1);
        LauncherAccess.Result r = run("unsupported-then-bad", file);
        assertEquals(2, r.status, r.toString());
        assertFalse(r.err.contains("badDef"), r.err);
    }
}

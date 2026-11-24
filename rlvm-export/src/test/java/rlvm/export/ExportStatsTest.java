package rlvm.export;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import rlvm.export.tools.ExportStats;

class ExportStatsTest {
    private static String stats(Path file) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            ExportStats.run(file, new ExportReader.Options(), 10, out);
        }
        return bytes.toString(StandardCharsets.UTF_8);
    }

    private static void has(String out, String regex) {
        assertTrue(java.util.regex.Pattern.compile(regex, java.util.regex.Pattern.MULTILINE).matcher(out).find(),
                "no match for /" + regex + "/ in:\n" + out);
    }

    @Test
    void handWrittenExport(@TempDir Path dir) throws Exception {
        // f a b, f a, g (f a b): spines headed by f: "f a b" (maximal), by g: one; "f a" is only a function
        Path p = dir.resolve("t.ndjson");
        Files.writeString(p, T.META
                + "{\"in\":1,\"str\":{\"pre\":0,\"str\":\"f\"}}\n"
                + "{\"in\":2,\"str\":{\"pre\":0,\"str\":\"g\"}}\n"
                + "{\"in\":3,\"str\":{\"pre\":0,\"str\":\"t\"}}\n"
                + "{\"const\":{\"name\":1,\"us\":[]},\"ie\":0}\n"
                + "{\"const\":{\"name\":2,\"us\":[]},\"ie\":1}\n"
                + "{\"ie\":2,\"sort\":0}\n"
                + "{\"bvar\":0,\"ie\":3}\n"
                + "{\"app\":{\"arg\":2,\"fn\":0},\"ie\":4}\n" // f Prop
                + "{\"app\":{\"arg\":3,\"fn\":4},\"ie\":5}\n" // f Prop #0
                + "{\"app\":{\"arg\":5,\"fn\":1},\"ie\":6}\n" // g (f Prop #0)
                + "{\"ie\":7,\"lam\":{\"binderInfo\":\"default\",\"body\":6,\"name\":0,\"type\":2}}\n"
                + "{\"axiom\":{\"isUnsafe\":false,\"levelParams\":[],\"name\":1,\"type\":2}}\n"
                + "{\"thm\":{\"all\":[3],\"levelParams\":[],\"name\":3,\"type\":2,\"value\":7}}\n");
        String out = stats(p);
        has(out, "^  names +3 ");
        has(out, "^  expressions +8 ");
        has(out, "^    app +3 +37\\.50 %");
        has(out, "^    const +2 ");
        has(out, "^  constants +2 ");
        has(out, "^    thm +1 ");
        has(out, "^  exprs   0 \\.\\. 7  dense, in order");
        has(out, "^  proof-private +7 +87\\.50 %"); // everything but Prop, which is also a type
        has(out, "^  shared +1 ");
        has(out, "^  3 app records; longest spine 2 arguments");
        has(out, "2 maximal spines");
        has(out, "^  2 maximal spines are headed by a constant; 2 references to const records");
        has(out, "^  2 distinct constants head a spine, 2 are referenced, of 2 declared");
        has(out, "^  axiom +1 +50\\.00 % +1 +50\\.00 %");
        has(out, "^  \\(not declared\\) +1 +50\\.00 % +1 +50\\.00 %");
        has(out, "^    1 +50\\.00 % +50\\.00 %");
        has(out, "^       1 +1 +50\\.00 % +50\\.00 % +1  axiom +f$");
        has(out, "^       2 +1 +50\\.00 % +100\\.00 % +1  - +g$");
        has(out, "^  all expression records +5 +62\\.50 %"); // closed: f, g, Prop, f Prop, the lambda
        has(out, "^  deepest binder nesting in a declaration: 1");
    }

    @Test
    void initPrelude() throws Exception {
        Path p = T.TESTS.resolve("good/init-prelude.ndjson");
        assumeTrue(Files.exists(p));
        String out = stats(p);
        // the published record counts of init-prelude
        has(out, "3,811,816 bytes, 65,406 lines");
        has(out, "^  names +7,491 ");
        has(out, "^  levels +113 ");
        has(out, "^  expressions +55,961 ");
        has(out, "^  declaration lines +1,840 ");
        has(out, "^  constants +2,121 ");
        has(out, "^    axiom +3 ");
        has(out, "^    def +1,536 ");
        has(out, "^    thm +161 ");
        has(out, "^    opaque +10 ");
        has(out, "^    quot +4 ");
        has(out, "^    inductive type +126 ");
        has(out, "^  exprs   0 \\.\\. 55,960  dense, in order");
        has(out, "^  id translation tables needed: no");
        has(out, "== HEAT");
        has(out, "the 10 constants that head the most spines");
    }
}

package rlvm.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import org.junit.jupiter.api.Test;

/**
 * Systematic check that the template fast path accepts a line only if it is exactly a template, and
 * then means what the general path means: for one canonical line of every template, every
 * single-byte substitution (from a set of bytes chosen to be nasty), every single-byte deletion and
 * every single-byte insertion is read by the fast path and by the general path with the same result.
 */
class TemplateExhaustiveTest {
    private static final String PRELUDE = T.META
            + "{\"in\":1,\"str\":{\"pre\":0,\"str\":\"a\"}}\n"
            + "{\"in\":2,\"str\":{\"pre\":1,\"str\":\"b\"}}\n"
            + "{\"il\":1,\"succ\":0}\n"
            + "{\"il\":2,\"succ\":1}\n"
            + "{\"bvar\":0,\"ie\":0}\n"
            + "{\"ie\":1,\"sort\":1}\n"
            + "{\"app\":{\"arg\":0,\"fn\":1},\"ie\":2}\n"
            + "{\"app\":{\"arg\":2,\"fn\":1},\"ie\":3}\n"
            + "{\"app\":{\"arg\":3,\"fn\":2},\"ie\":4}\n"
            + "{\"app\":{\"arg\":4,\"fn\":4},\"ie\":5}\n"
            + "{\"app\":{\"arg\":5,\"fn\":0},\"ie\":6}\n"
            + "{\"app\":{\"arg\":6,\"fn\":1},\"ie\":7}\n"
            + "{\"app\":{\"arg\":7,\"fn\":2},\"ie\":8}\n"
            + "{\"app\":{\"arg\":8,\"fn\":3},\"ie\":9}\n"
            + "{\"app\":{\"arg\":9,\"fn\":4},\"ie\":10}\n"
            + "{\"app\":{\"arg\":10,\"fn\":5},\"ie\":11}\n"
            + "{\"app\":{\"arg\":11,\"fn\":6},\"ie\":12}\n";

    /** One canonical line per template (ids continue the prelude: name 3, expression 13). */
    private static final String[] LINES = {
        "{\"app\":{\"arg\":2,\"fn\":1},\"ie\":13}",
        "{\"app\":{\"arg\":12,\"fn\":10},\"ie\":13}",
        "{\"ie\":13,\"lam\":{\"binderInfo\":\"default\",\"body\":2,\"name\":1,\"type\":0}}",
        "{\"ie\":13,\"lam\":{\"binderInfo\":\"implicit\",\"body\":2,\"name\":1,\"type\":0}}",
        "{\"ie\":13,\"lam\":{\"binderInfo\":\"strictImplicit\",\"body\":2,\"name\":1,\"type\":0}}",
        "{\"ie\":13,\"lam\":{\"binderInfo\":\"instImplicit\",\"body\":2,\"name\":1,\"type\":0}}",
        "{\"forallE\":{\"binderInfo\":\"default\",\"body\":2,\"name\":1,\"type\":0},\"ie\":13}",
        "{\"forallE\":{\"binderInfo\":\"instImplicit\",\"body\":2,\"name\":2,\"type\":1},\"ie\":13}",
        "{\"ie\":13,\"letE\":{\"body\":2,\"name\":1,\"nondep\":false,\"type\":0,\"value\":1}}",
        "{\"ie\":13,\"letE\":{\"body\":2,\"name\":1,\"nondep\":true,\"type\":0,\"value\":1}}",
        "{\"ie\":13,\"proj\":{\"idx\":10,\"struct\":2,\"typeName\":1}}",
        "{\"const\":{\"name\":1,\"us\":[]},\"ie\":13}",
        "{\"const\":{\"name\":2,\"us\":[1,0,2]},\"ie\":13}",
        "{\"in\":3,\"str\":{\"pre\":2,\"str\":\"x y\"}}",
        "{\"in\":3,\"str\":{\"pre\":0,\"str\":\"\"}}",
        "{\"in\":3,\"num\":{\"i\":17,\"pre\":2}}",
        "{\"in\":3,\"num\":{\"i\":123456789012,\"pre\":0}}",
        "{\"thm\":{\"all\":[1],\"levelParams\":[],\"name\":1,\"type\":2,\"value\":0}}",
        "{\"thm\":{\"all\":[1,2],\"levelParams\":[2,1],\"name\":2,\"type\":2,\"value\":0}}",
        "{\"def\":{\"all\":[1],\"hints\":\"abbrev\",\"levelParams\":[],\"name\":1,\"safety\":\"safe\",\"type\":2,\"value\":0}}",
        "{\"def\":{\"all\":[],\"hints\":\"opaque\",\"levelParams\":[2],\"name\":1,\"safety\":\"safe\",\"type\":2,\"value\":0}}",
        "{\"def\":{\"all\":[2],\"hints\":{\"regular\":31},\"levelParams\":[1,2],\"name\":1,\"safety\":\"safe\",\"type\":2,\"value\":0}}",
    };

    private static final byte[] SUBSTITUTES = T.latin1("\"\\{}[],:01289 \t\r\n-.eE_/aAzx\u0000\u007f\u0080Ãÿ");

    private static int checked;

    private static void same(String what, byte[] file) {
        T.Run fast = T.fast(file);
        T.Run general = T.general(file);
        T.same(what, fast, general, true);
        checked++;
    }

    private static byte[] file(byte[] line, boolean withFollower) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.writeBytes(T.latin1(PRELUDE));
        o.writeBytes(line);
        o.write('\n');
        if (withFollower) {
            // a line that uses what the line under test should have defined
            o.writeBytes(T.latin1("{\"app\":{\"arg\":0,\"fn\":13},\"ie\":14}\n{\"in\":4,\"str\":{\"pre\":3,\"str\":\"z\"}}\n"));
        }
        return o.toByteArray();
    }

    @Test
    void canonicalLinesAreHandledByTheFastPath() {
        for (String l : LINES) {
            byte[] f = file(T.latin1(l), false);
            T.Run fast = T.fast(f);
            T.assertOk(fast);
            // the prelude has four lines for the general path: two levels, bvar, sort
            assertEquals(4, fast.result.generalPathLines, l);
            same(l, f);
            same(l, file(T.latin1(l), true));
        }
    }

    @Test
    void everySingleByteChangeIsReadAlikeByBothPaths() {
        checked = 0;
        for (String l : LINES) {
            byte[] line = T.latin1(l);
            for (int p = 0; p < line.length; p++) {
                for (byte sub : SUBSTITUTES) {
                    if (line[p] == sub) {
                        continue;
                    }
                    byte[] m = line.clone();
                    m[p] = sub;
                    same(l + " [" + p + "] := " + sub, file(m, sub != '\n'));
                }
                // deletion
                byte[] d = new byte[line.length - 1];
                System.arraycopy(line, 0, d, 0, p);
                System.arraycopy(line, p + 1, d, p, line.length - p - 1);
                same(l + " without [" + p + "]", file(d, true));
            }
            for (int p = 0; p <= line.length; p++) {
                for (byte ins : SUBSTITUTES) {
                    byte[] m = new byte[line.length + 1];
                    System.arraycopy(line, 0, m, 0, p);
                    m[p] = ins;
                    System.arraycopy(line, p, m, p + 1, line.length - p);
                    same(l + " with " + ins + " inserted at " + p, file(m, ins != '\n'));
                }
            }
        }
        assertTrue(checked > 80_000, "checked " + checked);
    }

    @Test
    void numbersOfEveryLengthInEveryField() {
        // ids and references with 1 to 20 digits, with and without a leading zero
        String[] templates = {
            "{\"app\":{\"arg\":#,\"fn\":1},\"ie\":13}",
            "{\"app\":{\"arg\":2,\"fn\":#},\"ie\":13}",
            "{\"app\":{\"arg\":2,\"fn\":1},\"ie\":#}",
            "{\"ie\":#,\"lam\":{\"binderInfo\":\"default\",\"body\":2,\"name\":1,\"type\":0}}",
            "{\"ie\":13,\"lam\":{\"binderInfo\":\"default\",\"body\":#,\"name\":1,\"type\":0}}",
            "{\"ie\":13,\"lam\":{\"binderInfo\":\"default\",\"body\":2,\"name\":#,\"type\":0}}",
            "{\"ie\":13,\"lam\":{\"binderInfo\":\"default\",\"body\":2,\"name\":1,\"type\":#}}",
            "{\"ie\":13,\"proj\":{\"idx\":#,\"struct\":2,\"typeName\":1}}",
            "{\"in\":3,\"num\":{\"i\":#,\"pre\":2}}",
            "{\"in\":#,\"num\":{\"i\":5,\"pre\":2}}",
            "{\"in\":#,\"str\":{\"pre\":2,\"str\":\"q\"}}",
            "{\"const\":{\"name\":1,\"us\":[#]},\"ie\":13}",
            "{\"const\":{\"name\":1,\"us\":[0,#,1]},\"ie\":13}",
            "{\"thm\":{\"all\":[#],\"levelParams\":[],\"name\":1,\"type\":2,\"value\":0}}",
            "{\"def\":{\"all\":[1],\"hints\":{\"regular\":#},\"levelParams\":[],\"name\":1,\"safety\":\"safe\",\"type\":2,\"value\":0}}",
        };
        String digits = "12345678901234567890123";
        for (String t : templates) {
            for (int len = 1; len <= 21; len++) {
                same(t + " " + len, file(T.latin1(t.replace("#", digits.substring(0, len))), false));
                same(t + " 0.." + len, file(T.latin1(t.replace("#", "0" + digits.substring(0, len - 1))), false));
                same(t + " zeros " + len, file(T.latin1(t.replace("#", "0".repeat(len))), false));
                same(t + " nines " + len, file(T.latin1(t.replace("#", "9".repeat(len))), false));
            }
            for (String v : new String[] {"", "2147483647", "2147483648", "4294967295", "4294967296", "999999999", "1000000000",
                "99999999", "100000000", "999999999999999", "1000000000000000", "9223372036854775807", "9223372036854775808"}) {
                same(t + " " + v, file(T.latin1(t.replace("#", v)), false));
            }
        }
    }

    /** Runs of app lines with ids that continue, repeat, jump and share prefixes with the previous id. */
    @Test
    void runsOfAppLinesWithAllKindsOfIdSequences() {
        java.util.Random rnd = new java.util.Random(31);
        long[] starts = {0, 1, 5, 8, 9, 10, 95, 98, 99, 100, 995, 9995, 99995, 999995, 9999995, 99999989, 99999995,
            99999999, 100000000, 999999990, 999999999, 1000000000L, 123456789012L, 9223372036854775800L};
        for (int round = 0; round < 3000; round++) {
            StringBuilder sb = new StringBuilder(T.META);
            sb.append("{\"bvar\":0,\"ie\":0}\n");
            long id = starts[rnd.nextInt(starts.length)] + (rnd.nextBoolean() ? 0 : rnd.nextInt(12));
            long prev = 0;
            int n = 2 + rnd.nextInt(40);
            for (int i = 0; i < n; i++) {
                if (id == 0) {
                    id = 1;
                }
                String text = Long.toString(id);
                switch (rnd.nextInt(40)) {
                    case 0 -> text = "0" + text; // not canonical
                    case 1 -> text = text + "0"; // the expected digits and one more
                    case 2 -> text = text.substring(0, Math.max(1, text.length() - 1)); // a prefix of the expected digits
                    case 3 -> text = text + " ";
                    default -> {
                    }
                }
                sb.append("{\"app\":{\"arg\":").append(rnd.nextInt(4) == 0 ? 0 : prev).append(",\"fn\":").append(prev)
                        .append("},\"ie\":").append(text).append("}\n");
                prev = id;
                // mostly the next id, sometimes something else
                switch (rnd.nextInt(12)) {
                    case 0 -> id += 2;
                    case 1 -> id = Math.max(1, id - 1); // bound twice
                    case 2 -> id = id < Long.MAX_VALUE / 10 ? id * 10 : id; // the same digits and a zero
                    case 3 -> id = Math.max(1, id / 10);
                    case 4 -> {
                        // another record kind between two app lines
                        sb.append("{\"ie\":").append(id + 1).append(",\"lam\":{\"binderInfo\":\"default\",\"body\":").append(prev)
                                .append(",\"name\":0,\"type\":0}}\n");
                        prev = id + 1;
                        id += 2;
                    }
                    default -> id = id == Long.MAX_VALUE ? id : id + 1;
                }
            }
            byte[] file = T.utf8(sb.toString());
            T.Run fast = T.fast(file);
            T.same("round " + round + " general", fast, T.general(file), true);
            T.same("round " + round + " reference", fast, T.ref(file), false);
            T.same("round " + round + " buffer 64", fast, T.smallBuffer(file, 64), true);
            T.same("round " + round + " parallel", fast, T.parallel(file, 2, 300), true);
        }
    }

    @Test
    void longRunOfAppLinesAcrossAllDigitBoundaries() {
        // ids 1 .. 120000 in order (crossing 9/10, 99/100, ... 99999/100000) and then in steps of 7
        StringBuilder sb = new StringBuilder(T.META);
        sb.append("{\"bvar\":0,\"ie\":0}\n");
        for (int i = 1; i <= 120_000; i++) {
            sb.append("{\"app\":{\"arg\":").append(i - 1).append(",\"fn\":").append(i / 3).append("},\"ie\":").append(i).append("}\n");
        }
        for (int i = 120_007; i <= 200_000; i += 7) {
            sb.append("{\"app\":{\"arg\":").append(i / 2 > 120_000 ? 5 : i / 2).append(",\"fn\":0},\"ie\":").append(i).append("}\n");
        }
        byte[] file = T.utf8(sb.toString());
        T.Run fast = T.run(false, sink -> ExportReader.read(file, sink));
        T.assertOk(fast);
        assertEquals(131_428 + 1, fast.result.exprs);
        assertEquals(0, fast.result.generalPathLines - 1); // only the bvar line
        T.same("general", fast, T.run(false, sink -> ExportReader.read(file, sink, T.options(1, 1 << 20, true))), true);
        T.same("parallel", fast, T.run(false, sink -> ExportReader.read(file, sink, T.options(3, 5000, false))), true);
        TraceSink ref = new TraceSink(false);
        assertTrue(RefReader.read(file, ref).ok);
        assertEquals(fast.hash, ref.hash);
    }

    @Test
    void linesAtTheVeryEndOfTheInput() {
        // the scanner reads whole words; nothing after the last line may influence it
        for (String l : LINES) {
            byte[] f = file(T.latin1(l), false);
            // without the final newline, and cut at every position of the last line
            for (int cut = 0; cut <= l.length() + 1; cut++) {
                same(l + " cut " + cut, java.util.Arrays.copyOf(f, f.length - cut));
            }
        }
    }
}

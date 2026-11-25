package rlvm.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * The fast path's own loops for {@code app}, {@code lam} and {@code forallE} lines
 * ({@code Scanner.exprStretch}, and {@code Scanner.appStretch} for {@code app} lines with a number
 * of nine digits) and the stretches they hand over ({@code RecordOut.exprs},
 * {@code ExportSink.exprStretch}): every file is read by all configurations of the reader and by the
 * reference reader ({@link T#agree}: the same outcome, line, message and trace of sink calls, also
 * for a sink that takes the stretches), so a loop takes a line only if it means what the general
 * path says, and an error inside a stretch has its line.
 */
class AppStretchTest {
    private static final String PRE = "{\"il\":1,\"succ\":0}\n"
            + "{\"ie\":0,\"sort\":1}\n"
            + "{\"ie\":1,\"sort\":0}\n";

    private static String app(String id, String fn, String arg) {
        return "{\"app\":{\"arg\":" + arg + ",\"fn\":" + fn + "},\"ie\":" + id + "}\n";
    }

    /** Numbers of one to ten digits, with and without a leading zero, in each of the three places. */
    @Test
    void theNumberOfDigitsOfANumber() {
        String[] numbers = {"0", "1", "9", "10", "1234567", "9999999", "10000000", "12345678", "99999999", "100000000",
            "123456789", "999999999", "1000000000", "1234567890", "9999999999", "12345678901", "00", "01", "00000000",
            "01234567", "000000000", "012345678", "0123456789", "1e3", "-1", "1.0", ""};
        int read = 0;
        for (String n : numbers) {
            for (int place = 0; place < 3; place++) {
                // records 2 and 3 are ordinary; the number under test stands in the fourth line of the stretch
                String line = place == 0 ? app(n, "1", "0") : place == 1 ? app("4", n, "0") : app("4", "1", n);
                String body = PRE + app("2", "1", "0") + app("3", "2", "1") + line + app("5", "3", "2") + app("6", "5", "3");
                T.Run r = T.agree("number " + n + " in place " + place, T.utf8(T.META + body));
                if (r.ok) {
                    read++;
                }
            }
        }
        // the ids 4 .. 999999999 in the first place are sparse ids: read (and "0", "1" in the other places)
        assertTrue(read >= 12, "files read: " + read);
    }

    /** Stretches longer than the scanner's buffer of lines, cut by other lines, by errors and by the end of a read buffer. */
    @Test
    void longStretchesAndWhatEndsThem() {
        Random rnd = new Random(11);
        for (int round = 0; round < 40; round++) {
            StringBuilder b = new StringBuilder(T.META).append(PRE);
            int id = 2;
            int lines = 300 + rnd.nextInt(900);
            int breakAt = round % 4 == 0 ? -1 : rnd.nextInt(lines);
            int kind = round % 4;
            for (int i = 0; i < lines; i++) {
                if (i == breakAt) {
                    switch (kind) {
                        case 1 -> b.append("{\"ie\":").append(id++).append(",\"sort\":0}\n"); // another template
                        case 2 -> b.append(app(Integer.toString(id++), Integer.toString(id + 5), "0")); // a child that is not bound
                        default -> b.append("{ \"app\":{\"arg\":0,\"fn\":1},\"ie\":").append(id++).append("}\n"); // not the template
                    }
                    continue;
                }
                // children: near ones and far ones, so that the numbers have every length up to the id's
                int fn = rnd.nextBoolean() ? id - 1 : rnd.nextInt(id);
                int arg = rnd.nextInt(3) == 0 ? id - 1 - rnd.nextInt(Math.min(id, 3)) : rnd.nextInt(id);
                b.append(app(Integer.toString(id++), Integer.toString(fn), Integer.toString(arg)));
            }
            byte[] data = T.utf8(b.toString());
            T.Run r = T.agree("round " + round, data);
            assertEquals(kind != 2 || breakAt < 0, r.ok, "round " + round + ": " + r);
            if (!r.ok) {
                assertEquals(1 + 3 + breakAt + 1, r.line, "round " + round + ": line of the unbound child");
            }
            // a sink that takes the stretches gets them: more than one, and nearly all app records
            T.Run s = T.struct(data, T.options(1, 1 << 20, false), 1);
            assertEquals(r.ok, s.ok);
        }
    }

    /** Ids that are not the next id: the stretch is offered up to them, they go one by one. */
    @Test
    void idsThatAreNotDense() {
        String body = PRE + app("2", "1", "0") + app("3", "2", "1") + app("7", "3", "2") + app("8", "7", "3")
                + app("5", "8", "7") + app("9", "5", "8") + app("10", "9", "5");
        assertTrue(T.agree("a gap, then an id below", T.utf8(T.META + body)).ok);
        String twice = PRE + app("2", "1", "0") + app("3", "2", "1") + app("3", "2", "1") + app("4", "3", "2");
        T.Run r = T.agree("an id bound twice", T.utf8(T.META + twice));
        assertEquals(false, r.ok);
        assertEquals(7, r.line);
        String self = PRE + app("2", "1", "0") + app("3", "3", "1") + app("4", "3", "2");
        r = T.agree("a record that names itself", T.utf8(T.META + self));
        assertEquals(false, r.ok);
        assertEquals(6, r.line);
    }

    private static final String[] INFOS = {"default", "implicit", "instImplicit", "strictImplicit"};

    private static String lam(String id, String info, String body, String name, String type) {
        return "{\"ie\":" + id + ",\"lam\":{\"binderInfo\":\"" + info + "\",\"body\":" + body + ",\"name\":" + name
                + ",\"type\":" + type + "}}\n";
    }

    private static String pi(String id, String info, String body, String name, String type) {
        return "{\"forallE\":{\"binderInfo\":\"" + info + "\",\"body\":" + body + ",\"name\":" + name + ",\"type\":"
                + type + "},\"ie\":" + id + "}\n";
    }

    /** Three names (ids 1 .. 3), then {@link #PRE}. */
    private static final String NAMES = "{\"in\":1,\"str\":{\"pre\":0,\"str\":\"x\"}}\n"
            + "{\"in\":2,\"str\":{\"pre\":1,\"str\":\"y\"}}\n" + "{\"in\":3,\"num\":{\"i\":7,\"pre\":2}}\n" + PRE;

    /**
     * lam and forallE lines inside a stretch: numbers of one to ten digits, with and without a leading
     * zero, in each of the four places, under each binder info and under what is not one.
     */
    @Test
    void theNumbersAndTheBinderInfoOfABinderLine() {
        String[] numbers = {"0", "1", "3", "9", "10", "1234567", "12345678", "99999999", "100000000", "123456789",
            "1000000000", "00", "01", "00000000", "012345678", "1e3", "-1", ""};
        String[] infos = {"default", "implicit", "instImplicit", "strictImplicit", "Default", "default ", "implicitx",
            "instImplicit\\u0020", "strictImplici", "inst", ""};
        int read = 0;
        int files = 0;
        for (int binder = 0; binder < 2; binder++) {
            for (String n : numbers) {
                for (int place = 0; place < 4; place++) {
                    for (String info : place == 0 ? INFOS : new String[] {"default", "instImplicit"}) {
                        String id = place == 0 ? n : "4";
                        String body = place == 1 ? n : "3";
                        String name = place == 2 ? n : "2";
                        String type = place == 3 ? n : "1";
                        String line = binder == 0 ? lam(id, info, body, name, type) : pi(id, info, body, name, type);
                        String body2 = NAMES + app("2", "1", "0") + app("3", "2", "1") + line + app("5", "3", "2")
                                + lam("6", "implicit", "5", "1", "3") + pi("7", "default", "6", "3", "5") + app("8", "7", "6");
                        T.Run r = T.agree((binder == 0 ? "lam" : "forallE") + ", number " + n + " in place " + place + ", " + info,
                                T.utf8(T.META + body2));
                        files++;
                        if (r.ok) {
                            read++;
                        }
                    }
                }
            }
            for (String info : infos) {
                String line = binder == 0 ? lam("4", info, "3", "2", "1") : pi("4", info, "3", "2", "1");
                String body2 = NAMES + app("2", "1", "0") + app("3", "2", "1") + line + app("5", "3", "2");
                T.Run r = T.agree((binder == 0 ? "lam" : "forallE") + " with binder info '" + info + "'", T.utf8(T.META + body2));
                files++;
                assertEquals(java.util.Arrays.asList(INFOS).contains(info), r.ok, "binder info '" + info + "': " + r);
            }
        }
        assertTrue(read >= 40 && read < files, "files read: " + read + " of " + files);
    }

    /**
     * Stretches of app, lam and forallE lines in every mixture, longer than the scanner's buffer of
     * lines, cut by other lines, by a binder name or a child that is not bound, by bytes that are not
     * the template (a blank, a carriage return, a control byte, a second newline) and by the end of a
     * read buffer; with ids of up to nine digits (a file whose first expression id is large).
     */
    @Test
    void mixedStretchesAndWhatEndsThem() {
        Random rnd = new Random(23);
        for (int round = 0; round < 48; round++) {
            StringBuilder b = new StringBuilder(T.META);
            // names 1 .. names; ids are dense, so they stay bound in order
            int names = 1 + rnd.nextInt(40);
            for (int i = 1; i <= names; i++) {
                b.append("{\"in\":").append(i).append(",\"str\":{\"pre\":").append(rnd.nextInt(i)).append(",\"str\":\"n")
                        .append(i).append("\"}}\n");
            }
            b.append("{\"il\":1,\"succ\":0}\n");
            int headLines = 1 + names + 1;
            // expression ids start at 0, or high up (sparse ids: no stretch is taken, the same trace)
            int first = round % 6 == 5 ? 99_999_990 : 0;
            b.append("{\"ie\":").append(first).append(",\"sort\":1}\n");
            b.append("{\"ie\":").append(first + 1).append(",\"sort\":0}\n");
            headLines += 2;
            int id = first + 2;
            int lines = 300 + rnd.nextInt(700);
            int kind = round % 8;
            int breakAt = kind == 0 ? -1 : rnd.nextInt(lines);
            boolean fails = false;
            for (int i = 0; i < lines; i++) {
                int fn = rnd.nextBoolean() ? id - 1 : first + rnd.nextInt(id - first);
                int arg = rnd.nextInt(3) == 0 ? id - 1 - rnd.nextInt(Math.min(id - first, 3)) : first + rnd.nextInt(id - first);
                String name = Integer.toString(rnd.nextInt(names + 1));
                String info = INFOS[rnd.nextInt(10) == 0 ? 3 : rnd.nextInt(3)];
                int what = rnd.nextInt(10);
                String line = what < 6 ? app(Integer.toString(id), Integer.toString(fn), Integer.toString(arg))
                        : what < 8 ? lam(Integer.toString(id), info, Integer.toString(fn), name, Integer.toString(arg))
                        : pi(Integer.toString(id), info, Integer.toString(fn), name, Integer.toString(arg));
                if (i == breakAt) {
                    switch (kind) {
                        case 1 -> line = "{\"ie\":" + id + ",\"sort\":0}\n"; // another template
                        case 2 -> { // a binder name that is not bound
                            line = lam(Integer.toString(id), "default", Integer.toString(fn), Integer.toString(names + 1 + rnd.nextInt(3)),
                                    Integer.toString(arg));
                            fails = true;
                        }
                        case 3 -> { // a child that is not bound
                            line = pi(Integer.toString(id), "implicit", Integer.toString(id + rnd.nextInt(3)), name, Integer.toString(arg));
                            fails = true;
                        }
                        case 4 -> line = line.replace("\"name\":", "\"name\": ").replace("\"fn\":", "\"fn\": "); // a blank: the general path
                        case 5 -> line = line.substring(0, line.length() - 1) + "\r\n"; // CR before LF is whitespace
                        case 6 -> { // a control byte inside the line
                            line = line.substring(0, line.length() - 2) + "\u0001}\n";
                            fails = true;
                        }
                        default -> { // an empty line
                            line = line + "\n";
                            fails = true;
                        }
                    }
                }
                b.append(line);
                id++;
            }
            byte[] data = T.utf8(b.toString());
            T.Run r = T.agree("round " + round, data);
            assertEquals(!fails, r.ok, "round " + round + " (kind " + kind + "): " + r);
            if (!r.ok) {
                assertEquals(headLines + breakAt + (kind == 7 ? 2 : 1), r.line, "round " + round + ": line of the failure");
            }
            // a sink that takes the stretches gets them, binder records included
            StructSink sink = new StructSink(1, round);
            try {
                ExportReader.read(data, sink, T.options(1, 1 << 20, false));
            } catch (ExportException e) {
                assertTrue(fails, "round " + round + ": " + e);
            }
            if (first == 0 && !fails) {
                assertTrue(sink.appStretchRecords > lines / 2, "round " + round + ": records in stretches: " + sink.appStretchRecords);
                assertTrue(sink.stretchBinders > 0, "round " + round + ": binder records in stretches");
            }
        }
    }

    /** A line of the three templates that starts in the last bytes of a read buffer is left to the other paths. */
    @Test
    void linesAtTheEndOfTheBuffer() {
        StringBuilder b = new StringBuilder(T.META).append(NAMES);
        int id = 2;
        for (int i = 0; i < 400; i++) {
            String a = Integer.toString(id - 1);
            String c = Integer.toString(id - 2);
            b.append(i % 3 == 0 ? app(Integer.toString(id), a, c) : i % 3 == 1 ? lam(Integer.toString(id), "default", a, "3", c)
                    : pi(Integer.toString(id), "instImplicit", a, "1", c));
            id++;
        }
        byte[] data = T.utf8(b.toString());
        T.Run f = T.fast(data);
        assertTrue(f.ok, f.toString());
        for (int buffer = 64; buffer < 64 + 3 * 130; buffer += 7) {
            T.same("buffer " + buffer, f, T.smallBuffer(data, buffer), true);
        }
    }
}

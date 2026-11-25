package rlvm.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * The fast path's own loop for {@code app} lines ({@code Scanner.appStretch}) and the stretches it
 * hands over ({@code RecordOut.apps}, {@code ExportSink.exprApps}): every file is read by all
 * configurations of the reader and by the reference reader ({@link T#agree}: the same outcome, line,
 * message and trace of sink calls, also for a sink that takes the stretches), so the loop takes a
 * line only if it means what the general path says, and an error inside a stretch has its line.
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
}

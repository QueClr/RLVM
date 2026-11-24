package rlvm.machine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The guard of every publication: <b>no wrong verdict</b> on a fixed sample of the gate corpora (every
 * eighth tutorial test, every fifth {@code bugs/} test; good and bad). A decline is honest and passes; a
 * declaration the machine accepts although the reference engine rejects it (SOUNDNESS), or rejects
 * although the reference accepts it (COMPLETENESS), fails the publication of whoever introduced it
 * (WORK-PACKAGES section 6: "soundness + completeness must be 0 at every commit"). The complete corpora
 * are the slow runs: {@code report.sh tutorial | bugs | all | init}.
 */
@Tag("fast")
class M8GateTest {

    @Test
    void noWrongVerdictOnTheSampleOfTheTutorialAndBugsTests() {
        List<String> sample = new ArrayList<>();
        List<String> tutorial = Diff.exports(Diff.corpus("tutorial"));
        List<String> bugs = Diff.exports(Diff.corpus("bugs"));
        for (int i = 0; i < tutorial.size(); i += 8) {
            sample.add(tutorial.get(i));
        }
        for (int i = 0; i < bugs.size(); i += 5) {
            sample.add(bugs.get(i));
        }
        assumeTrue(!sample.isEmpty(), "no arena test present");
        Diff.Options o = new Diff.Options();
        o.progress = false;
        o.config.checkMillis = 20_000;
        o.fileMillis = 60_000;
        StringBuilder wrong = new StringBuilder();
        int n = 0;
        for (String file : sample) {
            Diff.FileResult r = Diff.compare(file, o);
            if (r.wrong()) {
                n += r.soundness + r.completeness;
                for (String line : r.lines) {
                    if (!line.startsWith("declined")) {
                        wrong.append('\n').append(file).append(": ").append(line);
                    }
                }
                wrong.append("\n    reproduce: ").append(Diff.reproduce(file, r.firstUnit));
            }
        }
        assertEquals(0, n, "WRONG VERDICTS of the machine against the reference engine:" + wrong);
    }
}

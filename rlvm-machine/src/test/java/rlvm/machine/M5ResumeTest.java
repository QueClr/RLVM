package rlvm.machine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Stepper-convention H12 item 4 for family TY: every frame key of {@code Infer} and {@code Facts} has exactly
 * one {@code resume} case, no two keys have the same value, and every key lies in an op of the family. A key
 * without a case would fault only when its frame is actually written, i.e. on deep terms.
 */
@Tag("fast")
class M5ResumeTest {

    static List<Field> keys(Class<?> c) {
        List<Field> out = new ArrayList<>();
        for (Field f : c.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers()) && f.getType() == int.class && f.getName().startsWith("K_")) {
                f.setAccessible(true);
                out.add(f);
            }
        }
        return out;
    }

    @Test
    void everyFrameKeyHasExactlyOneResumeCase() throws IOException, IllegalAccessException {
        Path dir = Path.of("src/main/java/rlvm/machine");
        assumeTrue(Files.isDirectory(dir), "the sources are not next to the test");
        Map<Integer, String> byValue = new HashMap<>();
        int n = 0;
        for (Class<?> c : new Class<?>[] {Infer.class, Facts.class}) {
            String src = Files.readString(dir.resolve(c.getSimpleName() + ".java"), StandardCharsets.UTF_8);
            for (Field f : keys(c)) {
                int key = f.getInt(null);
                String name = c.getSimpleName() + "." + f.getName();
                assertEquals(Op.FAM_TY, Op.familyOf(key), name + " is a key of family TY");
                assertTrue(Op.stateOf(key) != 0, name + " is a continuation state");
                String other = byValue.put(key, name);
                assertEquals(null, other, name + " has the value of " + other);
                String label = "case " + f.getName() + ":";
                int first = src.indexOf(label);
                assertTrue(first >= 0, name + " has no resume case");
                assertEquals(-1, src.indexOf(label, first + 1), name + " has more than one resume case");
                // the step it resumes is a continuation step of the convention (prefix k_)
                int ret = src.indexOf("return ", first);
                assertTrue(src.startsWith("k_", ret + 7), name + " does not resume a continuation step");
                n++;
            }
        }
        assertTrue(n >= 70, "frame keys found: " + n);
        // the state-0 keys of the family are dispatched too
        String infer = Files.readString(dir.resolve("Infer.java"), StandardCharsets.UTF_8);
        for (String k : new String[] {"Op.K_INFER", "Op.K_CHECK", "Op.K_VTYPE", "Op.K_ENSORT", "Op.K_ENPI", "Op.K_ISPROP"}) {
            assertTrue(infer.contains("case " + k + ":"), k);
        }
        assertTrue(Files.readString(dir.resolve("Facts.java"), StandardCharsets.UTF_8).contains("case Op.K_SIGD:"));
    }
}

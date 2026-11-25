package rlvm.machine;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.attribute.CodeAttribute;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The static check of *agentic-note*: rep-stepper-convention, H12 item 4 over the class files of the judgment
 * classes: method sizes within the limits of H10.
 *
 * <ul>
 *   <li>a continuation step ({@code k_*}): at most 150 bytecodes;
 *   <li>any other method of a judgment class (entry steps, loop methods, helpers): at most 325 - the size
 *       C2 still inlines at a hot call site and both JITs compile well;
 *   <li>exempt: the per-family {@code resume*} switches (about 40 bytecodes per case, at most 50 cases per
 *       method) and methods whose name ends in {@code Slow} (out-of-line slow parts); both at most 2,500;
 *   <li>nothing may reach 8,000 (HotSpot never compiles such a method).
 * </ul>
 * A step that grows beyond its limit is split at a sub-judgment call (a new state) or sheds its slow
 * part into a {@code ..Slow} method.
 */
@Tag("fast")
class StepSizeTest {

    static final Class<?>[] JUDGMENT_CLASSES = {Eval.class, Reduce.class, NatRed.class, StrRed.class, Rules.class,
        Conv.class, Cargs.class, Infer.class, Decl.class, Facts.class, Code.class, Match.class, Frames.class, Scope.class,
        Values.class, Runs.class, Lits.class, Levels.class, Pairs.class};

    @Test
    void stepMethodsStayWithinTheSizeLimitsOfTheConvention() throws IOException {
        List<String> tooBig = new ArrayList<>();
        int methods = 0;
        for (Class<?> c : JUDGMENT_CLASSES) {
            String res = "/" + c.getName().replace('.', '/') + ".class";
            byte[] bytes;
            try (InputStream in = c.getResourceAsStream(res)) {
                bytes = in.readAllBytes();
            }
            ClassModel cm = ClassFile.of().parse(bytes);
            for (MethodModel mm : cm.methods()) {
                String name = mm.methodName().stringValue();
                int size = mm.findAttribute(java.lang.classfile.Attributes.code()).map(CodeAttribute::codeLength).orElse(0);
                methods++;
                int limit;
                if (name.startsWith("resume") || name.endsWith("Slow") || name.equals("<clinit>")) {
                    limit = 2500;
                } else if (name.startsWith("k_")) {
                    limit = 150;
                } else {
                    limit = 325;
                }
                if (size > limit) {
                    tooBig.add(c.getSimpleName() + "." + name + ": " + size + " bytecodes (limit " + limit + ")");
                }
            }
        }
        assertTrue(methods > 100, "parsed " + methods + " methods");
        assertTrue(tooBig.isEmpty(), "methods over the size limits of *agentic-note*: rep-stepper-convention, H10:\n" + String.join("\n", tooBig));
    }

    /** The driver and the hot shared primitives are small too (sigN / spillN are never inlined, H10 rule 4). */
    @Test
    void theSharedControlCodeIsSmall() throws IOException {
        byte[] bytes;
        try (InputStream in = Machine.class.getResourceAsStream("/rlvm/machine/Machine.class")) {
            bytes = in.readAllBytes();
        }
        for (MethodModel mm : ClassFile.of().parse(bytes).methods()) {
            String name = mm.methodName().stringValue();
            int size = mm.findAttribute(java.lang.classfile.Attributes.code()).map(CodeAttribute::codeLength).orElse(0);
            if (name.equals("drive") || name.equals("tickSlow") || name.equals("addRecordAt") || name.equals("internRecord")) {
                assertTrue(size <= 325, "Machine." + name + " has " + size + " bytecodes");
            }
        }
    }
}

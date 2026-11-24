package rlvm.machine;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeElement;
import java.lang.classfile.CodeModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.Opcode;
import java.lang.classfile.instruction.ConstantInstruction;
import java.lang.classfile.instruction.FieldInstruction;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Static guards of rules that every package must keep and that only a look at ALL class files can check.
 * Owner: M6. In the fast group on purpose: a publication that breaks one of them is refused.
 */
@Tag("fast")
class M6GuardTest {

    /**
     * NOTES-M6 F26: eager reduction is entered through {@code Machine.enterER()} only. A plain
     * {@code m.ER = 1} elsewhere would run a comparison under ER without the facade learning of it, and the
     * machine would again answer what the official kernel does not (findings M6-F26, S-F4, S-F5). Restoring a
     * saved value ({@code m.ER = saved}) and clearing ({@code m.ER = 0}) are fine.
     */
    @Test
    void noClassButMachineSetsEagerReductionDirectly() throws IOException, URISyntaxException {
        Path dir = Path.of(Machine.class.getProtectionDomain().getCodeSource().getLocation().toURI()).resolve("rlvm/machine");
        List<String> offenders = new ArrayList<>();
        List<String> inMachine = new ArrayList<>(); // the positive control: the scan must find Machine.enterER
        int classes = 0;
        try (Stream<Path> files = Files.list(dir)) {
            for (Path p : (Iterable<Path>) files.filter(f -> f.toString().endsWith(".class"))::iterator) {
                ClassModel cm = ClassFile.of().parse(Files.readAllBytes(p));
                String cls = cm.thisClass().asInternalName();
                classes++;
                List<String> found = cls.equals("rlvm/machine/Machine") ? inMachine : offenders;
                for (MethodModel mm : cm.methods()) {
                    CodeModel code = mm.code().orElse(null);
                    if (code == null) {
                        continue;
                    }
                    CodeElement previous = null;
                    for (CodeElement e : code) {
                        if (!(e instanceof java.lang.classfile.Instruction)) {
                            continue; // labels, line numbers
                        }
                        if (e instanceof FieldInstruction f && f.opcode() == Opcode.PUTFIELD && f.name().stringValue().equals("ER")
                                && f.owner().asInternalName().equals("rlvm/machine/Machine")
                                && previous instanceof ConstantInstruction c && !Integer.valueOf(0).equals(c.constantValue())) {
                            found.add(cls.substring(cls.lastIndexOf('/') + 1) + "." + mm.methodName().stringValue() + ": m.ER = " + c.constantValue());
                        }
                        previous = e;
                    }
                }
            }
        }
        assertTrue(classes > 20, "the class files of the package were found: " + classes);
        assertTrue(inMachine.equals(List.of("Machine.enterER: m.ER = 1")), "the scan finds the one legitimate assignment: " + inMachine);
        assertTrue(offenders.isEmpty(), "eager reduction must be entered with m.enterER(), never by assigning a constant to Machine.ER: " + offenders);
    }
}

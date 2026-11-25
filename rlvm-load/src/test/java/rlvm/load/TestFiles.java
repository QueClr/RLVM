package rlvm.load;

import java.nio.file.Files;
import java.nio.file.Path;

/** Locates the project root (the directory that holds {@code pom.xml} and {@code env.sh}) from the module directory. */
final class TestFiles {
    private TestFiles() {}

    static Path root() {
        Path p = Path.of("").toAbsolutePath();
        for (int i = 0; i < 4 && p != null; i++, p = p.getParent()) {
            if (Files.isDirectory(p.resolve("rlvm-load")) && Files.exists(p.resolve("pom.xml")) && Files.exists(p.resolve("env.sh"))) {
                return p;
            }
        }
        return Path.of("").toAbsolutePath();
    }
}

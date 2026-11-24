package rlvm.inductive;

import java.nio.file.Files;
import java.nio.file.Path;

/** Locations of the corpora used by the integration tests (absent data = skipped test). */
final class Data {
    private Data() {}

    /** The project root: {@code -Drlvm.root}, or the nearest ancestor of the working directory that has {@code work/}. */
    static Path root() {
        String p = System.getProperty("rlvm.root");
        if (p != null) {
            return Path.of(p);
        }
        Path d = Path.of("").toAbsolutePath();
        while (d != null && !Files.isDirectory(d.resolve("work").resolve("exports"))) {
            d = d.getParent();
        }
        return d;
    }

    static Path export(String name) {
        Path r = root();
        return r == null ? null : r.resolve("work").resolve("exports").resolve(name);
    }

    static Path arenaTests() {
        Path r = root();
        return r == null ? null : r.resolve("work").resolve("arena-site").resolve("tests");
    }
}

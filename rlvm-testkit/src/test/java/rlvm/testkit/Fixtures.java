package rlvm.testkit;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/** Shared test helpers: fixture exports, the arena's tutorial tests when present, a fake official checker. */
final class Fixtures {
    private Fixtures() {}

    static final String[] NAMES = {"001_basicDef", "002_badDef", "070_nRec", "103_natLitEq", "synthetic_literals"};

    static String resource(String name) throws IOException {
        try (InputStream in = Fixtures.class.getResourceAsStream("/exports/" + name + ".ndjson")) {
            if (in == null) {
                throw new IOException("missing test resource " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** Copies a fixture into {@code dir} and returns its path. */
    static Path copy(String name, Path dir) throws IOException {
        Files.createDirectories(dir);
        Path p = dir.resolve(name + ".ndjson");
        Files.write(p, resource(name).getBytes(StandardCharsets.UTF_8));
        return p;
    }

    /** The project root when the tests run inside the project tree, else null. */
    static Path projectRoot() {
        Path p = Paths.get("").toAbsolutePath();
        while (p != null) {
            if (Files.isRegularFile(p.resolve("env.sh")) && Files.isRegularFile(p.resolve("pom.xml"))) {
                return p;
            }
            p = p.getParent();
        }
        return null;
    }

    /** The published arena tests ({@code work/arena-site/tests}) below {@code sub}, or an empty list when they are not there. */
    static List<Path> arenaTests(String sub) throws IOException {
        List<Path> files = new ArrayList<>();
        Path root = projectRoot();
        if (root == null) {
            return files;
        }
        for (String bucket : new String[] {"good", "bad"}) {
            Path dir = root.resolve("work/arena-site/tests").resolve(bucket).resolve(sub);
            if (Files.isDirectory(dir)) {
                try (Stream<Path> s = Files.list(dir)) {
                    s.filter(f -> f.getFileName().toString().endsWith(".ndjson")).forEach(files::add);
                }
            }
        }
        files.sort(null);
        return files;
    }

    /**
     * A fake toolchain whose {@code bin/leanchecker} is a shell script that decides by the file's
     * content: a line containing {@code FAKE-REJECT} gives a kernel rejection, {@code FAKE-READER} a
     * reader exception, {@code FAKE-POSTCHECK} a quotient post-check failure, {@code FAKE-SLEEP} a
     * 30-second sleep, {@code FAKE-CRASH} a segmentation fault, {@code FAKE-DEEP} a "deep recursion"
     * kernel error; anything else is accepted, unless a file {@code by-checksum} exists in the toolchain
     * directory: then files with an odd POSIX checksum are rejected (a verdict that depends on the
     * bytes alone, for corpus tests). Every run appends a line to {@code calls} in the toolchain directory.
     */
    static Path fakeToolchain(Path dir) throws IOException {
        Path bin = dir.resolve("bin");
        Files.createDirectories(bin);
        String script = "#!/bin/sh\n"
                + "here=\"$(dirname \"$0\")\"\n"
                + "echo \"$2\" >> \"$here/../calls\"\n"
                + "[ \"$1\" = --from-export ] || { echo 'usage' >&2; exit 64; }\n"
                + "[ -r \"$2\" ] || { echo \"uncaught exception: no such file or directory (error code: 2)\" >&2; exit 1; }\n"
                + "if grep -q FAKE-SLEEP \"$2\"; then sleep 30; fi\n"
                + "if grep -q FAKE-CRASH \"$2\"; then kill -SEGV $$; fi\n"
                + "if grep -q FAKE-REJECT \"$2\"; then\n"
                + "  echo \"Lean default kernel rejects the solution: while replaying declaration 'x':\"\n"
                + "  echo \"(kernel) declaration type mismatch\"\n"
                + "  exit 1\n"
                + "fi\n"
                + "if grep -q FAKE-DEEP \"$2\"; then\n"
                + "  echo \"Lean default kernel rejects the solution: (kernel) deep recursion detected\"\n"
                + "  exit 1\n"
                + "fi\n"
                + "if grep -q FAKE-READER \"$2\"; then echo 'uncaught exception: Level not found 7' >&2; exit 1; fi\n"
                + "if [ -f \"$here/../by-checksum\" ]; then\n"
                + "  sum=$(cksum < \"$2\" | cut -d' ' -f1)\n"
                + "  if [ $((sum % 2)) -eq 1 ]; then\n"
                + "    echo \"Lean default kernel rejects the solution: (kernel) checksum $sum is odd\"\n"
                + "    exit 1\n"
                + "  fi\n"
                + "fi\n"
                + "echo 'Lean default kernel accepts the solution'\n"
                + "if grep -q FAKE-POSTCHECK \"$2\"; then\n"
                + "  echo 'Quotient post-check rejects the solution: Quotient constant mismatch on: Quot.lift'\n"
                + "  exit 1\n"
                + "fi\n"
                + "exit 0\n";
        Path exe = bin.resolve("leanchecker");
        Files.write(exe, script.getBytes(StandardCharsets.UTF_8));
        Files.setPosixFilePermissions(exe, PosixFilePermissions.fromString("rwxr-xr-x"));
        return dir;
    }

    static int calls(Path toolchain) throws IOException {
        Path f = toolchain.resolve("calls");
        return Files.isRegularFile(f) ? Files.readAllLines(f).size() : 0;
    }
}

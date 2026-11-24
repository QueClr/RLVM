import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.StringWriter;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/**
 * "Large Java application" proxy for JIT warm-up economics: runs javac (a ~0.5 MLOC tree-walking,
 * allocation-heavy program, structurally similar to a type checker) in-process on a fixed source
 * set N times. Unlike a micro-benchmark, thousands of methods are warm, so the cost of interpreting
 * + C1 + C2/Graal compilation is realistic.
 *
 * <pre>
 * java JavacBench.java &lt;sourceDir&gt; &lt;classpath&gt; &lt;iterations&gt;
 * </pre>
 *
 * Class output is discarded in memory; annotation processing is off.
 */
public final class JavacBench {
    static final class NullOutput extends SimpleJavaFileObject {
        NullOutput(String name, Kind kind) {
            super(URI.create("mem:///" + name.replace('.', '/') + kind.extension), kind);
        }

        @Override
        public OutputStream openOutputStream() {
            return OutputStream.nullOutputStream();
        }
    }

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args[0]);
        String cp = args[1];
        int iterations = Integer.parseInt(args[2]);
        List<File> files = new ArrayList<>();
        try (Stream<Path> s = Files.walk(dir)) {
            s.filter(p -> p.toString().endsWith(".java")).forEach(p -> files.add(p.toFile()));
        }
        long lines = 0;
        for (File f : files) {
            try (Stream<String> l = Files.lines(f.toPath())) {
                lines += l.count();
            }
        }
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        StringBuilder times = new StringBuilder();
        long total = 0;
        long best = Long.MAX_VALUE;
        for (int i = 0; i < iterations; i++) {
            long t0 = System.nanoTime();
            StringWriter out = new StringWriter();
            try (StandardJavaFileManager std = javac.getStandardFileManager(null, null, null)) {
                JavaFileManager fm = new ForwardingJavaFileManager<>(std) {
                    @Override
                    public JavaFileObject getJavaFileForOutput(Location location, String className, JavaFileObject.Kind kind, FileObject sibling) throws IOException {
                        return new NullOutput(className, kind);
                    }
                };
                boolean ok = javac.getTask(out, fm, null, List.of("-proc:none", "-nowarn", "-Xlint:none", "-cp", cp), null, std.getJavaFileObjectsFromFiles(files)).call();
                if (!ok) {
                    System.out.println(out);
                    throw new IllegalStateException("compilation failed");
                }
            }
            long dt = System.nanoTime() - t0;
            total += dt;
            best = Math.min(best, dt);
            if (i < 10 || i == iterations - 1) {
                times.append(dt / 1_000_000).append(' ');
            } else if (i == 10) {
                times.append("... ");
            }
        }
        long threadCpu = ManagementFactory.getThreadMXBean().getCurrentThreadCpuTime() / 1_000_000;
        long processCpu = ((com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean()).getProcessCpuTime() / 1_000_000;
        long comp = ManagementFactory.getCompilationMXBean().getTotalCompilationTime();
        System.out.printf("javac x%d over %d files / %d lines | iter ms: %s| best %.0f | wall %d ms | main-thread cpu %d ms | process cpu %d ms | JIT time (CompilationMXBean) %d ms%n",
                        iterations, files.size(), lines, times, best / 1e6, total / 1_000_000, threadCpu, processCpu, comp);
    }
}

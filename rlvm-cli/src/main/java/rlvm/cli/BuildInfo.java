package rlvm.cli;

import java.io.InputStream;
import java.util.Properties;

/**
 * Version data written by Maven into {@code rlvm/cli/build.properties}. This class is initialised at
 * image build time (see {@code META-INF/native-image/rlvm/rlvm-cli/native-image.properties}), so a
 * native image carries the values as constants and needs no resource at run time. It must therefore
 * not look at anything that differs between build and run (environment, processors, time).
 */
final class BuildInfo {
    /** Project version, e.g. {@code 0.1.0-SNAPSHOT}. */
    static final String VERSION;
    /** Version of the Truffle artifacts the code was compiled against; must equal the GraalVM's own version. */
    static final String TRUFFLE_VERSION;
    /**
     * How the executable was built, as {@code scripts/build.sh} says it: the compiler ("GraalVM CE" or
     * "Oracle GraalVM, profile-guided"), the optimisation level and the memory settings that are baked
     * in. It is the system property {@link #BUILD_PROPERTY} of the image BUILD ({@code native-image
     * -Drlvm.build=...}): this class is initialised at image build time, so the text is a constant of the
     * image. Null on a JVM (the development launcher), where nothing is baked in.
     */
    static final String BUILD;
    /** Name of the build-time property behind {@link #BUILD}. */
    static final String BUILD_PROPERTY = "rlvm.build";

    static {
        Properties p = new Properties();
        try (InputStream in = BuildInfo.class.getResourceAsStream("build.properties")) {
            if (in != null) {
                p.load(in);
            }
        } catch (Exception e) {
            // Fall through to "unknown": version text is never worth failing for.
        }
        VERSION = clean(p.getProperty("version"));
        TRUFFLE_VERSION = clean(p.getProperty("truffle.version"));
        String build = null;
        try {
            build = System.getProperty(BUILD_PROPERTY);
        } catch (RuntimeException e) {
            // As above: never worth failing for.
        }
        BUILD = build == null || build.isBlank() ? null : build.strip();
    }

    private BuildInfo() {
    }

    private static String clean(String value) {
        // An unfiltered resource (classes used straight from an IDE) still has the ${...} placeholder.
        return value == null || value.isEmpty() || value.startsWith("${") ? "unknown" : value;
    }

    /** True inside a native image at run time. Checked through the property so that no SVM class is needed. */
    static boolean isNativeImage() {
        return "runtime".equals(System.getProperty("org.graalvm.nativeimage.imagecode"));
    }

    /** "native" or "jvm". */
    static String mode() {
        return isNativeImage() ? "native" : "jvm";
    }

    /**
     * The line of {@code --version} that says what this executable is: the baked-in description of a
     * native image ({@link #BUILD}), or that the jars run on a JVM.
     */
    static String build() {
        if (BUILD != null) {
            return BUILD;
        }
        return isNativeImage() ? "native image (the build did not say how it was made)"
                        : "jars on a JVM (development launcher; the released executable is the native image)";
    }

    /** Human-readable description of the running VM. */
    static String runtime() {
        String vm = System.getProperty("java.vm.name", "?");
        String vendorVersion = System.getProperty("java.vendor.version", "");
        String version = System.getProperty("java.runtime.version", System.getProperty("java.version", "?"));
        return mode() + " (" + vm + (vendorVersion.isEmpty() ? "" : ", " + vendorVersion) + ", Java " + version + ", "
                        + System.getProperty("os.arch", "?") + ")";
    }
}

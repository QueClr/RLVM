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

    /** Human-readable description of the running VM. */
    static String runtime() {
        String vm = System.getProperty("java.vm.name", "?");
        String vendorVersion = System.getProperty("java.vendor.version", "");
        String version = System.getProperty("java.runtime.version", System.getProperty("java.version", "?"));
        return mode() + " (" + vm + (vendorVersion.isEmpty() ? "" : ", " + vendorVersion) + ", Java " + version + ", "
                        + System.getProperty("os.arch", "?") + ")";
    }
}

package rlvm.ref;

import java.util.Map;

/**
 * Settings of the reference engine beyond {@link rlvm.api.EngineConfig}. Immutable.
 *
 * <p>From the launcher: {@code -X levels=complete|official} (default complete) and
 * {@code -X ref.imports=off|on|verify} (default off: with the overlay of wave 4, whose hash-consing
 * index follows its content, keeping imports costs more CPU time than it saves; see the README)
 * ({@link RefEngine.Provider#optionKeys()}); the same two are read from the system properties
 * {@code rlvm.levels} and {@code rlvm.ref.imports} when no {@code -X} setting names them (tools,
 * tests, a launcher that predates the {@code -X} keys). Tuning knobs that are system properties
 * only: {@code rlvm.ref.importBytes} (bytes of overlay a session may spend on imports that outlive
 * a declaration, default 4 MiB; 0 = none) and {@code rlvm.ref.importMinUses} (default 8). All are
 * read when the engine is created, never in a static initialiser, so they also work in a native
 * image.
 */
public final class RefOptions {

    /** {@code -X} keys of the reference engine. */
    public static final String KEY_LEVELS = "levels";
    public static final String KEY_IMPORTS = "ref.imports";

    static final long DEFAULT_IMPORT_BYTES = 4L << 20;
    static final int DEFAULT_IMPORT_MIN_USES = 8;

    /**
     * How universe levels are compared in {@code is_def_eq} (sorts, level lists of constants):
     * false = the complete relation ({@code LevelOps.isEquiv}), true = the official kernel's
     * normal-form test ({@code LevelOps.isEquivOfficial}), which answers "different" for some levels
     * that are equal under every assignment.
     */
    public final boolean officialLevels;
    /**
     * Bytes of overlay a session may spend on imported constant terms that outlive a declaration;
     * 0 = the session keeps none (the default from {@link #parse}).
     */
    public final long importBytes;
    /** A term is kept for the session once this many declarations had to import it. */
    public final int importMinUses;
    /** Recompute every import that is served from the session cache and fail on a difference (tests). */
    public final boolean importVerify;

    public RefOptions(boolean officialLevels, long importBytes, int importMinUses, boolean importVerify) {
        this.officialLevels = officialLevels;
        this.importBytes = Math.max(0L, importBytes);
        this.importMinUses = Math.max(1, importMinUses);
        this.importVerify = importVerify;
    }

    /** The defaults, as modified by system properties. */
    public static RefOptions fromSystemProperties() {
        return parse(Map.of());
    }

    /**
     * The settings of a run.
     *
     * @param options {@code -X KEY=VALUE} settings; keys that are not the reference engine's are
     *     ignored; a key that is absent falls back to its system property, then to the default
     * @throws IllegalArgumentException a value of one of this engine's keys (or properties) is not understood
     */
    public static RefOptions parse(Map<String, String> options) {
        String levels = setting(options, KEY_LEVELS, "rlvm.levels", "complete");
        boolean official;
        switch (levels) {
            case "complete":
                official = false;
                break;
            case "official":
                official = true;
                break;
            default:
                throw new IllegalArgumentException("levels=" + levels + ": expected complete or official");
        }
        String imports = setting(options, KEY_IMPORTS, "rlvm.ref.imports", "off");
        long bytes = number("rlvm.ref.importBytes", DEFAULT_IMPORT_BYTES);
        boolean verify = false;
        switch (imports) {
            case "on":
            case "true":
                break;
            case "off":
            case "false":
                bytes = 0L;
                break;
            case "verify":
                verify = true;
                break;
            default:
                throw new IllegalArgumentException("ref.imports=" + imports + ": expected on, off or verify");
        }
        long minUses = number("rlvm.ref.importMinUses", DEFAULT_IMPORT_MIN_USES);
        return new RefOptions(official, bytes, (int) Math.min(minUses, Integer.MAX_VALUE), verify);
    }

    private static String setting(Map<String, String> options, String key, String property, String fallback) {
        String v = options == null ? null : options.get(key);
        if (v == null) {
            v = System.getProperty(property);
        }
        return v == null || v.isEmpty() ? fallback : v;
    }

    private static long number(String property, long fallback) {
        String v = System.getProperty(property);
        if (v == null || v.isEmpty()) {
            return fallback;
        }
        try {
            long n = Long.parseLong(v);
            if (n < 0) {
                throw new NumberFormatException();
            }
            return n;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(property + "=" + v + ": expected a non-negative number");
        }
    }

    public RefOptions withOfficialLevels(boolean value) {
        return new RefOptions(value, importBytes, importMinUses, importVerify);
    }

    public RefOptions withImportBytes(long value) {
        return new RefOptions(officialLevels, value, importMinUses, importVerify);
    }

    public RefOptions withImportMinUses(int value) {
        return new RefOptions(officialLevels, importBytes, value, importVerify);
    }

    public RefOptions withImportVerify(boolean value) {
        return new RefOptions(officialLevels, importBytes, importMinUses, value);
    }

    @Override
    public String toString() {
        return "RefOptions[levels=" + (officialLevels ? "official" : "complete") + ", importBytes=" + importBytes
                + ", importMinUses=" + importMinUses + (importVerify ? ", verify" : "") + "]";
    }
}

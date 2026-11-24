package rlvm.ref;

import java.util.Map;

/**
 * Settings of the reference engine beyond {@link rlvm.api.EngineConfig}. Immutable.
 *
 * <p>From the launcher: {@code -X levels=official|complete} (default official: universe levels are
 * compared as the official kernel compares them, the project owner's decision of 2026-11-24,
 * OWNER-NOTES item 11; {@code complete} is the opt-in) and
 * {@code -X ref.imports=off|on|verify} (default off: with the overlay of wave 4, whose hash-consing
 * index follows its content, keeping imports costs more CPU time than it saves; see the README) and
 * {@code -X ref.identity=decline|same|other} (default decline: no verdict where the official one
 * depends on object identity; the other two values are for experiments)
 * ({@link RefEngine.Provider#optionKeys()}); the same three are read from the system properties
 * {@code rlvm.levels}, {@code rlvm.ref.imports} and {@code rlvm.ref.identity} when no {@code -X} setting names them (tools,
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
    public static final String KEY_IDENTITY = "ref.identity";

    /**
     * What the kernel does where the official answer depends on OBJECT identity (the pointer test
     * after the second {@code whnf_core} of {@code is_def_eq_core}; README, "Object identity"), which
     * a hash-consed term store does not have.
     */
    public static final int IDENTITY_DECLINE = 0;
    /** Experiments only: assume the two occurrences are one object (the engine before 2026-11-24). */
    public static final int IDENTITY_SAME = 1;
    /** Experiments only: assume they are two objects (the official second pass runs). */
    public static final int IDENTITY_OTHER = 2;

    static final long DEFAULT_IMPORT_BYTES = 4L << 20;
    static final int DEFAULT_IMPORT_MIN_USES = 8;

    /** The value of {@code -X levels=} when nothing is said: the official kernel's comparison. */
    public static final String DEFAULT_LEVELS = "official";

    /**
     * How universe levels are compared in {@code is_def_eq} (sorts, level lists of constants):
     * true (the default) = the official kernel's {@code is_equivalent}, identity of the official
     * normal forms ({@code LevelOps.isEquivOfficial}), which answers "different" for some levels
     * that are equal under every assignment ({@code imax u 1} and {@code max u 1},
     * {@code imax u v} and {@code imax (imax u v) v}); false ({@code -X levels=complete}) = the
     * complete relation ({@code LevelOps.isEquiv}), which accepts a superset.
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
    /**
     * {@link #IDENTITY_DECLINE} (the default: no verdict where the official one depends on object
     * identity), {@link #IDENTITY_SAME} or {@link #IDENTITY_OTHER} ({@code -X ref.identity=}).
     */
    public final int identity;

    public RefOptions(boolean officialLevels, long importBytes, int importMinUses, boolean importVerify) {
        this(officialLevels, importBytes, importMinUses, importVerify, IDENTITY_DECLINE);
    }

    public RefOptions(boolean officialLevels, long importBytes, int importMinUses, boolean importVerify,
            int identity) {
        if (identity < IDENTITY_DECLINE || identity > IDENTITY_OTHER) {
            throw new IllegalArgumentException("identity " + identity);
        }
        this.officialLevels = officialLevels;
        this.importBytes = Math.max(0L, importBytes);
        this.importMinUses = Math.max(1, importMinUses);
        this.importVerify = importVerify;
        this.identity = identity;
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
        String levels = setting(options, KEY_LEVELS, "rlvm.levels", DEFAULT_LEVELS);
        boolean official;
        switch (levels) {
            case "official":
                official = true;
                break;
            case "complete":
                official = false;
                break;
            default:
                throw new IllegalArgumentException("levels=" + levels + ": expected official or complete");
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
        String id = setting(options, KEY_IDENTITY, "rlvm.ref.identity", "decline");
        int identity;
        switch (id) {
            case "decline":
                identity = IDENTITY_DECLINE;
                break;
            case "same":
                identity = IDENTITY_SAME;
                break;
            case "other":
                identity = IDENTITY_OTHER;
                break;
            default:
                throw new IllegalArgumentException("ref.identity=" + id + ": expected decline, same or other");
        }
        return new RefOptions(official, bytes, (int) Math.min(minUses, Integer.MAX_VALUE), verify, identity);
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
        return new RefOptions(value, importBytes, importMinUses, importVerify, identity);
    }

    public RefOptions withImportBytes(long value) {
        return new RefOptions(officialLevels, value, importMinUses, importVerify, identity);
    }

    public RefOptions withImportMinUses(int value) {
        return new RefOptions(officialLevels, importBytes, value, importVerify, identity);
    }

    public RefOptions withImportVerify(boolean value) {
        return new RefOptions(officialLevels, importBytes, importMinUses, value, identity);
    }

    public RefOptions withIdentity(int value) {
        return new RefOptions(officialLevels, importBytes, importMinUses, importVerify, value);
    }

    @Override
    public String toString() {
        return "RefOptions[levels=" + (officialLevels ? "official" : "complete") + ", importBytes=" + importBytes
                + ", importMinUses=" + importMinUses + (importVerify ? ", verify" : "")
                + (identity == IDENTITY_DECLINE ? "" : identity == IDENTITY_SAME ? ", identity=same" : ", identity=other")
                + "]";
    }
}

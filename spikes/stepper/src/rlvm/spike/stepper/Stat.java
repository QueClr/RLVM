package rlvm.spike.stepper;

/**
 * Counters, maintained only when {@link Vals#STATS} is set (a constant: the JIT removes the updates otherwise,
 * and the native image is built without it).
 */
public final class Stat {
    private Stat() {
    }

    /** Judgments started under R: term nodes evaluated, values read back, value pairs compared. */
    public static long evals, rbs, convs;
    /** Frames written to the worklist W, their words, and the judgments a variant answered without a frame. */
    public static long frames, frameWords, inline;
    /** Fresh judgment frames among {@link #frames} (the rest are continuations of a suspended judgment). */
    public static long freshFrames;
    /** H: materialisation events (the host stack was flushed into W). */
    public static long spills;
    /** Abort a root when the judgment count passes this (the "heavy" command). */
    public static long limit = Long.MAX_VALUE;

    public static void reset() {
        evals = rbs = convs = frames = frameWords = inline = freshFrames = spills = 0;
    }

    public static final class Exceeded extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Exceeded() {
            super(null, null, false, false);
        }
    }

    private static final Exceeded EXCEEDED = new Exceeded();

    static void check() {
        if (evals + rbs + convs > limit) {
            throw EXCEEDED;
        }
    }
}

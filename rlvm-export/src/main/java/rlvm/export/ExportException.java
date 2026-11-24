package rlvm.export;

/**
 * An export that cannot be turned into sink calls.
 *
 * <p>The {@link #kind} tells a checker what verdict the failure maps to:
 * <ul>
 *   <li>{@link Kind#MALFORMED}: the official reader ({@code leanchecker --from-export}) rejects this
 *       input as well (exit status 1). A checker must <em>reject</em>.</li>
 *   <li>{@link Kind#UNSUPPORTED}: the input is outside what this reader (or the sink) can represent
 *       faithfully, while the official reader may accept it (ids of 2^63 and above, ill-formed UTF-8,
 *       more than 2^31 records, ...). A checker must <em>decline</em>, never guess.</li>
 * </ul>
 * I/O failures are reported as {@link java.io.IOException}, not as this exception.
 */
public final class ExportException extends Exception {
    private static final long serialVersionUID = 1L;

    /** What a failure means for the verdict. */
    public enum Kind {
        /** The official reader rejects the input too: reject. */
        MALFORMED,
        /** Not representable here although possibly valid: decline. */
        UNSUPPORTED
    }

    private final Kind kind;
    private final String reason;
    private long line;

    public ExportException(Kind kind, String reason) {
        this(kind, 0, reason);
    }

    public ExportException(Kind kind, long line, String reason) {
        super(reason, null, false, false);
        this.kind = kind;
        this.reason = reason;
        this.line = line;
    }

    public static ExportException malformed(String reason) {
        return new ExportException(Kind.MALFORMED, reason);
    }

    public static ExportException unsupported(String reason) {
        return new ExportException(Kind.UNSUPPORTED, reason);
    }

    public Kind kind() {
        return kind;
    }

    /** 1-based line number of the offending line, or 0 if unknown. */
    public long line() {
        return line;
    }

    /** The reason without the line prefix. */
    public String reason() {
        return reason;
    }

    /** Sets the line number if none was recorded yet (used by the reader for errors raised by a sink). */
    public ExportException atLine(long lineNumber) {
        if (line == 0) {
            line = lineNumber;
        }
        return this;
    }

    @Override
    public String getMessage() {
        String k = kind == Kind.MALFORMED ? "malformed export" : "unsupported export";
        return line > 0 ? k + ", line " + line + ": " + reason : k + ": " + reason;
    }
}

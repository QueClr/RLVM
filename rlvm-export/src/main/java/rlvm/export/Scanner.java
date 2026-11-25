package rlvm.export;

import java.lang.invoke.VarHandle;

/**
 * The template fast path.
 *
 * <p>lean4export writes every record with a fixed key order and no whitespace, so the common record
 * kinds have one exact byte shape each (docs/research/01, section 2.3). {@link #scan} matches a line
 * against these shapes with 8-byte loads compared to packed literals and parses decimal numbers eight
 * digits at a time. It accepts a line <em>only</em> if every byte of it, including the final
 * {@code '\n'}, is the template's; any other line is handed, untouched, to {@link RecordOut#slow}
 * (the general JSON path). The fast path therefore never decides that a line is invalid, and it never
 * interprets a line differently from the general path (this equivalence is what the differential
 * tests check).
 *
 * <p>Shapes handled here (more than 99.9 % of the lines of a real export): {@code app}, {@code lam},
 * {@code forallE}, {@code letE}, {@code proj}, {@code const}, names ({@code str} with plain ASCII,
 * {@code num}), {@code thm} and safe {@code def}. Numbers on this path are canonical decimals below
 * 10^9 (10^15 for a numeric name component).
 *
 * <p>Memory discipline: a load never starts beyond the first byte that has not been verified to belong
 * to the current line, and lines end with {@code '\n'}; so no load starts at or after {@code end}.
 * The caller guarantees that the buffer extends {@link #PAD} bytes beyond {@code end}.
 *
 * <p>Word loads are written as {@code (long) LE64.get(s, q)} at their place, not as a call of
 * {@link Mem#u64}: the access through the handle is expanded where it stands by every compiler,
 * while the ahead-of-time compiler of the native image, which has no profile, left the call of the
 * one-line method (and the calls of {@link #num}) in the scan loop: six calls for an {@code app}
 * line, each with a stack check, a frame and a safepoint poll, about twenty instructions for one
 * load (*agentic-note*: rep-OPT-B). For the same reason the {@code app} lines, six lines of seven,
 * have a loop of their own ({@link #appStretch}) in which the three numbers are converted in place.
 */
final class Scanner {
    private Scanner() {}

    /** Unaligned little-endian 64-bit loads from the scan buffer: {@link Mem#LE64}. */
    private static final VarHandle LE64 = Mem.LE64;

    /** Bytes that must be readable after the {@code end} passed to {@link #scan}. */
    static final int PAD = 16;

    /** Number of {@code app} lines that {@link #scan} collects before it hands them over. */
    private static final int APPS = 256;

    /** Lines longer than this are not supported (a line must fit one buffer). */
    static final int MAX_LINE = 1 << 30;

    private static final long ONES = 0x0101010101010101L;
    private static final long HIGH = 0x8080808080808080L;
    private static final long ZEROS = 0x3030303030303030L;
    private static final long NEWLINES = 0x0A0A0A0A0A0A0A0AL;
    private static final long QUOTES = 0x2222222222222222L;
    private static final long BACKSLASHES = 0x5C5C5C5C5C5C5C5CL;
    private static final long SPACES = 0x2020202020202020L;
    /** Added to a word xored with {@link #ZEROS}: the high bit of every byte above 9 gets set. */
    private static final long NOT_DIGIT = 0x7676767676767676L;

    private static final long M1 = 0xFFL;
    private static final long M2 = 0xFFFFL;
    private static final long M3 = 0xFF_FFFFL;
    private static final long M4 = 0xFFFF_FFFFL;
    private static final long M6 = 0xFFFF_FFFF_FFFFL;
    private static final long M7 = 0xFF_FFFF_FFFF_FFFFL;

    /** The first up to 8 characters of {@code s}, packed little-endian (first character lowest). */
    static long lit(String s) {
        if (s.length() > 8) {
            throw new IllegalArgumentException(s);
        }
        long w = 0;
        for (int i = s.length() - 1; i >= 0; i--) {
            w = (w << 8) | (s.charAt(i) & 0xFF);
        }
        return w;
    }

    // {"app":{"arg":A,"fn":F},"ie":N}\n
    private static final long APP_0 = lit("{\"app\":{");
    private static final long APP_6 = lit(":{\"arg\":");
    private static final long FN6 = lit(",\"fn\":");
    private static final long IE7 = lit("},\"ie\":");
    private static final long END2 = lit("}\n");
    private static final long END3 = lit("}}\n");

    // {"ie":N,"lam":{"binderInfo":"default","body":B,"name":M,"type":T}}\n
    private static final long IE6 = lit("{\"ie\":");
    private static final long LAM8 = lit(",\"lam\":{");
    private static final long BI_0 = lit("\"binderI");
    private static final long BI_6 = lit("rInfo\":\"");
    private static final long BI_DEFAULT = lit("default\"");
    private static final long BI_IMPLICIT = lit("implicit");
    private static final long BI_STRICT_0 = lit("strictIm");
    private static final long BI_STRICT_8 = lit("plicit");
    private static final long BI_INST_0 = lit("instImpl");
    private static final long BI_INST_8 = lit("icit");
    private static final long QBODY_0 = lit("\",\"body\"");
    private static final long QBODY_1 = lit(",\"body\":");
    private static final long NAME8 = lit(",\"name\":");
    private static final long TYPE8 = lit(",\"type\":");

    // {"forallE":{"binderInfo":"default","body":B,"name":M,"type":T},"ie":N}\n
    private static final long FORALL_0 = lit("{\"forall");
    private static final long FORALL_8 = lit("E\":{\"bin");
    private static final long FORALL_16 = lit("derInfo\"");
    private static final long FORALL_24 = lit(":\"");

    // {"ie":N,"letE":{"body":B,"name":M,"nondep":false,"type":T,"value":V}}\n
    private static final long LETE8 = lit(",\"letE\":");
    private static final long LBODY8 = lit("{\"body\":");
    private static final long NONDEP_0 = lit(",\"nondep");
    private static final long NONDEP_FALSE = lit("\":false");
    private static final long NONDEP_TRUE = lit("\":true");
    private static final long VALUE_0 = lit(",\"value\"");
    private static final long VALUE_1 = lit("\"value\":");

    // {"ie":N,"proj":{"idx":I,"struct":E,"typeName":P}}\n
    private static final long PROJ8 = lit(",\"proj\":");
    private static final long IDX7 = lit("{\"idx\":");
    private static final long STRUCT_0 = lit(",\"struct");
    private static final long STRUCT_8 = lit("\":");
    private static final long TYPENAME_0 = lit(",\"typeNa");
    private static final long TYPENAME_8 = lit("me\":");

    // {"in":N,"str":{"pre":P,"str":"..."}}\n   {"in":N,"num":{"i":I,"pre":P}}\n
    private static final long IN6 = lit("{\"in\":");
    private static final long STR8 = lit(",\"str\":{");
    private static final long PRE6 = lit("\"pre\":");
    private static final long STRQ8 = lit(",\"str\":\"");
    private static final long QEND4 = lit("\"}}\n");
    private static final long NUM8 = lit(",\"num\":{");
    private static final long I4 = lit("\"i\":");
    private static final long PRE7 = lit(",\"pre\":");

    // {"const":{"name":P,"us":[L,...]},"ie":N}\n
    private static final long CONST_0 = lit("{\"const\"");
    private static final long CONST_8 = lit(":{\"name\"");
    private static final long US7 = lit(",\"us\":[");
    private static final long CONST_END8 = lit("]},\"ie\":");

    // {"thm":{"all":[L],"levelParams":[L],"name":N,"type":T,"value":V}}\n
    private static final long THM_0 = lit("{\"thm\":{");
    private static final long ALL7 = lit("\"all\":[");
    private static final long LEVELPARAMS_0 = lit(",\"levelP");
    private static final long LEVELPARAMS_8 = lit("arams\":[");

    // {"def":{"all":[L],"hints":H,"levelParams":[L],"name":N,"safety":"safe","type":T,"value":V}}\n
    private static final long DEF_0 = lit("{\"def\":{");
    private static final long HINTS_0 = lit(",\"hints\"");
    private static final long HINT_ABBREV = lit("\"abbrev\"");
    private static final long HINT_OPAQUE = lit("\"opaque\"");
    private static final long HINT_REGULAR_0 = lit("{\"regula");
    private static final long HINT_REGULAR_8 = lit("r\":");
    private static final long SAFETY_0 = lit(",\"safety");
    private static final long SAFETY_8 = lit("\":\"safe\"");

    private static final long[] POW10 = {
        1L, 10L, 100L, 1000L, 10000L, 100000L, 1000000L, 10000000L, 100000000L
    };

    private static long conv8(long t) {
        t = ((t * 2561) >>> 8) & 0x00FF00FF00FF00FFL;
        t = ((t * 6553601) >>> 16) & 0x0000FFFF0000FFFFL;
        return (t * 42949672960001L) >>> 32;
    }

    /**
     * A canonical decimal number (no leading zero unless it is "0") of 1 to 9 digits at {@code q},
     * followed by a non-digit: {@code (value << 5) | digits}. Anything else: -1.
     */
    static long num(byte[] s, int q) {
        long t = (long) LE64.get(s, q) ^ ZEROS;
        // high bit set in every byte that is not a digit (exact for the lowest such byte)
        long nd = ((t + 0x7676767676767676L) | t) & HIGH;
        if (nd != 0) {
            int bits = Long.numberOfTrailingZeros(nd) - 7; // 8 * number of digits
            if (bits == 0 || ((t & M1) == 0 && bits != 8)) {
                return -1;
            }
            return (conv8(t << (64 - bits)) << 5) | (bits >>> 3);
        }
        // eight digits and more
        if ((t & M1) == 0) {
            return -1;
        }
        int c = Mem.u8(s, q + 8) - '0';
        if (c < 0 || c > 9) {
            return (conv8(t) << 5) | 8;
        }
        int c2 = Mem.u8(s, q + 9) - '0';
        if (c2 >= 0 && c2 <= 9) {
            return -1;
        }
        return ((conv8(t) * 10 + c) << 5) | 9;
    }

    /** Like {@link #num} for 1 to 15 digits. */
    static long numWide(byte[] s, int q) {
        long t = (long) LE64.get(s, q) ^ ZEROS;
        long nd = ((t + 0x7676767676767676L) | t) & HIGH;
        if (nd != 0) {
            int bits = Long.numberOfTrailingZeros(nd) - 7;
            if (bits == 0 || ((t & M1) == 0 && bits != 8)) {
                return -1;
            }
            return (conv8(t << (64 - bits)) << 5) | (bits >>> 3);
        }
        if ((t & M1) == 0) {
            return -1;
        }
        long t2 = (long) LE64.get(s, q + 8) ^ ZEROS;
        long nd2 = ((t2 + 0x7676767676767676L) | t2) & HIGH;
        if (nd2 == 0) {
            return -1; // 16 digits or more
        }
        int bits2 = Long.numberOfTrailingZeros(nd2) - 7;
        if (bits2 == 0) {
            return (conv8(t) << 5) | 8;
        }
        int k = bits2 >>> 3;
        return ((conv8(t) * POW10[k] + conv8(t2 << (64 - bits2))) << 5) | (8 + k);
    }

    /**
     * Length of the run of plain string bytes at {@code q} ({@code 0x20 .. 0x7F} except {@code '"'}
     * and {@code '\\'}) if the run is ended by {@code '"'}; -1 otherwise (escape, control character,
     * non-ASCII byte: the general path decides).
     */
    static int plainString(byte[] s, int q) {
        int p = q;
        while (true) {
            long w = (long) LE64.get(s, p);
            long x = w ^ QUOTES;
            long y = w ^ BACKSLASHES;
            long m = (((x - ONES) & ~x) | ((y - ONES) & ~y) | ((w - SPACES) & ~w) | w) & HIGH;
            if (m != 0) {
                int k = Long.numberOfTrailingZeros(m) >>> 3;
                if (((w >>> (k << 3)) & M1) != '"') {
                    return -1;
                }
                return p + k - q;
            }
            p += 8;
        }
    }

    /** Index of the first {@code '\n'} at or after {@code p}, which must exist before {@code end}. */
    static int newline(byte[] s, int p, int end) {
        while (p < end) {
            long x = (long) LE64.get(s, p) ^ NEWLINES;
            long m = (x - ONES) & ~x & HIGH;
            if (m != 0) {
                int r = p + (Long.numberOfTrailingZeros(m) >>> 3);
                if (r < end) {
                    return r;
                }
                break;
            }
            p += 8;
        }
        throw new IllegalStateException("scan range does not end with a newline");
    }

    /**
     * Scans the complete lines in {@code [pos, end)}; {@code end} must be the position after a
     * {@code '\n'} (or equal to {@code pos}).
     *
     * @param s the buffer; {@code s.length >= end + PAD}
     * @param list scratch for universe level lists
     */
    static void scan(byte[] s, int pos, int end, RecordOut out, int[] list) throws ExportException {
        // Measured and rejected: comparing the id of an app line with the text of the expected next id
        // instead of parsing it (the trick of the Susz packer). Under C2 it made the scan 16 % faster
        // in time but cost 12 % more instructions, and instructions are what the arena counts.
        final int[] apps = new int[3 * APPS + 1];
        while (pos < end) {
            // {"app":{"arg":A,"fn":F},"ie":N}\n - six lines of seven, in long stretches. A stretch is
            // scanned by appStretch (no call in its loop) and handed over as a whole.
            final int na = appStretch(s, pos, end, apps);
            if (na != 0) {
                pos = apps[3 * na];
                out.apps(apps, na);
                if (na == APPS) {
                    continue;
                }
            }
            if (pos >= end) {
                break;
            }
            // one line of another kind, or an app line that is not the template
            long w = (long) LE64.get(s, pos);
            if (w == APP_0) {
                if ((long) LE64.get(s, pos + 6) == APP_6) {
                    int q = pos + 14;
                    long r = num(s, q);
                    if (r >= 0) {
                        long arg = r >>> 5;
                        q += (int) r & 31;
                        if (((long) LE64.get(s, q) & M6) == FN6) {
                            q += 6;
                            r = num(s, q);
                            if (r >= 0) {
                                long fn = r >>> 5;
                                q += (int) r & 31;
                                if (((long) LE64.get(s, q) & M7) == IE7) {
                                    q += 7;
                                    r = num(s, q);
                                    if (r >= 0) {
                                        q += (int) r & 31;
                                        if (((long) LE64.get(s, q) & M2) == END2) {
                                            out.app(r >>> 5, fn, arg);
                                            pos = q + 2;
                                            continue;
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                int np = other(s, pos, w, out, list);
                if (np >= 0) {
                    pos = np;
                    continue;
                }
            }
            int nl = newline(s, pos, end);
            out.slow(pos, nl + 1 - pos);
            pos = nl + 1;
        }
    }

    /**
     * Scans the {@code app} lines from {@code pos} on, at most {@link #APPS} of them, up to the first
     * line that is not the template (or to {@code end}): id, fn and arg of line {@code i} go to
     * {@code apps[3 * i ..]}, the position behind the last line taken to {@code apps[3 * n]}. Returns
     * the number {@code n} of lines taken. It takes a line only if {@link #scan}'s own test of the
     * template, with {@link #num}, takes it, and reads the same numbers.
     *
     * <p>The loop is written for a compiler without a profile (the native image; see the class
     * comment): it contains no call - a call inside a loop, even one that is never made, makes that
     * compiler keep the loop's values in the stack frame - and no branch on the number of digits of
     * a number, which cannot be predicted. {@code num} is written out: {@code bits} is 8 * (the
     * digits among the eight bytes loaded), 64 for eight, when the shift is by 0; a ninth digit is
     * added by arithmetic ({@code nine} is 1 if there is one); a tenth ends the stretch. The byte
     * behind the eight is read whatever {@code bits} is: it lies within {@link #PAD} of the line.
     */
    private static int appStretch(byte[] s, int pos, int end, int[] apps) {
        int n = 0;
        while (n < APPS && pos < end) {
            if ((long) LE64.get(s, pos) != APP_0 || (long) LE64.get(s, pos + 6) != APP_6) {
                break;
            }
            int q = pos + 14;
            long t = (long) LE64.get(s, q) ^ ZEROS;
            int bits = Long.numberOfTrailingZeros((((t + NOT_DIGIT) | t) & HIGH) >>> 7);
            int c = s[q + 8] - '0';
            int nine = (bits >>> 6) & ~((c | (9 - c)) >> 31);
            if (bits == 0 || ((t & M1) == 0 && bits != 8)) {
                break;
            }
            long arg = conv8(t << (64 - bits));
            arg += nine * (arg * 9 + c);
            q += (bits >>> 3) + nine;
            if ((nine != 0 && !notDigit(s[q])) || ((long) LE64.get(s, q) & M6) != FN6) {
                break;
            }
            q += 6;
            t = (long) LE64.get(s, q) ^ ZEROS;
            bits = Long.numberOfTrailingZeros((((t + NOT_DIGIT) | t) & HIGH) >>> 7);
            c = s[q + 8] - '0';
            nine = (bits >>> 6) & ~((c | (9 - c)) >> 31);
            if (bits == 0 || ((t & M1) == 0 && bits != 8)) {
                break;
            }
            long fn = conv8(t << (64 - bits));
            fn += nine * (fn * 9 + c);
            q += (bits >>> 3) + nine;
            if ((nine != 0 && !notDigit(s[q])) || ((long) LE64.get(s, q) & M7) != IE7) {
                break;
            }
            q += 7;
            t = (long) LE64.get(s, q) ^ ZEROS;
            bits = Long.numberOfTrailingZeros((((t + NOT_DIGIT) | t) & HIGH) >>> 7);
            c = s[q + 8] - '0';
            nine = (bits >>> 6) & ~((c | (9 - c)) >> 31);
            if (bits == 0 || ((t & M1) == 0 && bits != 8)) {
                break;
            }
            long id = conv8(t << (64 - bits));
            id += nine * (id * 9 + c);
            q += (bits >>> 3) + nine;
            if ((nine != 0 && !notDigit(s[q])) || ((long) LE64.get(s, q) & M2) != END2) {
                break;
            }
            // the numbers have at most nine digits
            apps[3 * n] = (int) id;
            apps[3 * n + 1] = (int) fn;
            apps[3 * n + 2] = (int) arg;
            n++;
            pos = q + 2;
        }
        apps[3 * n] = pos;
        return n;
    }

    /** Whether {@code b} is not an ASCII digit. */
    private static boolean notDigit(byte b) {
        return b < '0' || b > '9';
    }

    /** Position after the line at {@code p} if it matched a template (and was emitted), else -1. */
    private static int other(byte[] s, int p, long w, RecordOut out, int[] list)
            throws ExportException {
        if ((w & M6) == IE6) {
            return ieFirst(s, p, out);
        }
        if (w == FORALL_0) {
            return forall(s, p, out);
        }
        if ((w & M6) == IN6) {
            return name(s, p, out);
        }
        if (w == CONST_0) {
            return constant(s, p, out, list);
        }
        if (w == THM_0) {
            return thm(s, p, out, list);
        }
        if (w == DEF_0) {
            return def(s, p, out, list);
        }
        return -1;
    }

    /**
     * A list {@code N,N,...]} or {@code ]} at {@code q} (just after the opening bracket): appends the
     * numbers to {@code list} from index {@code n}; returns {@code (position after ']' << 32) | new n},
     * or -1.
     */
    private static long idList(byte[] s, int q, int[] list, int n) {
        if (Mem.u8(s, q) != ']') {
            while (true) {
                long r = num(s, q);
                if (r < 0 || n == list.length) {
                    return -1;
                }
                list[n++] = (int) (r >>> 5);
                q += (int) r & 31;
                byte c = Mem.u8(s, q);
                if (c == ',') {
                    q++;
                } else if (c == ']') {
                    break;
                } else {
                    return -1;
                }
            }
        }
        return ((long) (q + 1) << 32) | n;
    }

    private static int thm(byte[] s, int p, RecordOut out, int[] list) throws ExportException {
        if (((long) LE64.get(s, p + 8) & M7) != ALL7) {
            return -1;
        }
        long l = idList(s, p + 15, list, 0);
        if (l < 0) {
            return -1;
        }
        int numAll = (int) l;
        int q = (int) (l >>> 32);
        if ((long) LE64.get(s, q) != LEVELPARAMS_0 || (long) LE64.get(s, q + 8) != LEVELPARAMS_8) {
            return -1;
        }
        l = idList(s, q + 16, list, numAll);
        if (l < 0) {
            return -1;
        }
        int numParams = (int) l - numAll;
        q = (int) (l >>> 32);
        if ((long) LE64.get(s, q) != NAME8) {
            return -1;
        }
        q += 8;
        long r = num(s, q);
        if (r < 0) {
            return -1;
        }
        long name = r >>> 5;
        q += (int) r & 31;
        if ((long) LE64.get(s, q) != TYPE8) {
            return -1;
        }
        q += 8;
        r = num(s, q);
        if (r < 0) {
            return -1;
        }
        long type = r >>> 5;
        q += (int) r & 31;
        if ((long) LE64.get(s, q) != VALUE_0 || (long) LE64.get(s, q + 1) != VALUE_1) {
            return -1;
        }
        q += 9;
        r = num(s, q);
        if (r < 0) {
            return -1;
        }
        q += (int) r & 31;
        if (((long) LE64.get(s, q) & M3) != END3) {
            return -1;
        }
        out.thm(name, type, r >>> 5, list, numAll, numParams);
        return q + 3;
    }

    private static int def(byte[] s, int p, RecordOut out, int[] list) throws ExportException {
        if (((long) LE64.get(s, p + 8) & M7) != ALL7) {
            return -1;
        }
        long l = idList(s, p + 15, list, 0);
        if (l < 0) {
            return -1;
        }
        int numAll = (int) l;
        int q = (int) (l >>> 32);
        if ((long) LE64.get(s, q) != HINTS_0 || ((long) LE64.get(s, q + 8) & M1) != ':') {
            return -1;
        }
        q += 9;
        long h = (long) LE64.get(s, q);
        int hintKind;
        long height = 0;
        if (h == HINT_ABBREV) {
            hintKind = ExportSink.HINT_ABBREV;
            q += 8;
        } else if (h == HINT_REGULAR_0) {
            if (((long) LE64.get(s, q + 8) & M3) != HINT_REGULAR_8) {
                return -1;
            }
            q += 11;
            long r = num(s, q);
            if (r < 0) {
                return -1;
            }
            height = r >>> 5;
            q += (int) r & 31;
            if (Mem.u8(s, q) != '}') {
                return -1;
            }
            q++;
            hintKind = ExportSink.HINT_REGULAR;
        } else if (h == HINT_OPAQUE) {
            hintKind = ExportSink.HINT_OPAQUE;
            q += 8;
        } else {
            return -1;
        }
        if ((long) LE64.get(s, q) != LEVELPARAMS_0 || (long) LE64.get(s, q + 8) != LEVELPARAMS_8) {
            return -1;
        }
        l = idList(s, q + 16, list, numAll);
        if (l < 0) {
            return -1;
        }
        int numParams = (int) l - numAll;
        q = (int) (l >>> 32);
        if ((long) LE64.get(s, q) != NAME8) {
            return -1;
        }
        q += 8;
        long r = num(s, q);
        if (r < 0) {
            return -1;
        }
        long name = r >>> 5;
        q += (int) r & 31;
        if ((long) LE64.get(s, q) != SAFETY_0 || (long) LE64.get(s, q + 8) != SAFETY_8 || (long) LE64.get(s, q + 16) != TYPE8) {
            return -1;
        }
        q += 24;
        r = num(s, q);
        if (r < 0) {
            return -1;
        }
        long type = r >>> 5;
        q += (int) r & 31;
        if ((long) LE64.get(s, q) != VALUE_0 || (long) LE64.get(s, q + 1) != VALUE_1) {
            return -1;
        }
        q += 9;
        r = num(s, q);
        if (r < 0) {
            return -1;
        }
        q += (int) r & 31;
        if (((long) LE64.get(s, q) & M3) != END3) {
            return -1;
        }
        out.def(name, type, r >>> 5, hintKind, height, list, numAll, numParams);
        return q + 3;
    }

    /** At the value of {@code "binderInfo":"}: {@code (length up to the closing quote << 2) | info}, or -1. */
    private static int binderInfo(byte[] s, int q) {
        long w = (long) LE64.get(s, q);
        if (w == BI_DEFAULT) {
            return (7 << 2) | ExportSink.BINDER_DEFAULT;
        }
        if (w == BI_IMPLICIT) {
            return (8 << 2) | ExportSink.BINDER_IMPLICIT;
        }
        if (w == BI_INST_0) {
            return ((long) LE64.get(s, q + 8) & M4) == BI_INST_8 ? (12 << 2) | ExportSink.BINDER_INST_IMPLICIT : -1;
        }
        if (w == BI_STRICT_0) {
            return ((long) LE64.get(s, q + 8) & M6) == BI_STRICT_8 ? (14 << 2) | ExportSink.BINDER_STRICT_IMPLICIT : -1;
        }
        return -1;
    }

    private static int ieFirst(byte[] s, int p, RecordOut out) throws ExportException {
        int q = p + 6;
        long r = num(s, q);
        if (r < 0) {
            return -1;
        }
        long id = r >>> 5;
        q += (int) r & 31;
        long k = (long) LE64.get(s, q);
        if (k == LAM8) {
            q += 8;
            if ((long) LE64.get(s, q) != BI_0 || (long) LE64.get(s, q + 6) != BI_6) {
                return -1;
            }
            q += 14;
            int b = binderInfo(s, q);
            if (b < 0) {
                return -1;
            }
            q += b >>> 2;
            if ((long) LE64.get(s, q) != QBODY_0 || (long) LE64.get(s, q + 1) != QBODY_1) {
                return -1;
            }
            q += 9;
            r = num(s, q);
            if (r < 0) {
                return -1;
            }
            long body = r >>> 5;
            q += (int) r & 31;
            if ((long) LE64.get(s, q) != NAME8) {
                return -1;
            }
            q += 8;
            r = num(s, q);
            if (r < 0) {
                return -1;
            }
            long name = r >>> 5;
            q += (int) r & 31;
            if ((long) LE64.get(s, q) != TYPE8) {
                return -1;
            }
            q += 8;
            r = num(s, q);
            if (r < 0) {
                return -1;
            }
            q += (int) r & 31;
            if (((long) LE64.get(s, q) & M3) != END3) {
                return -1;
            }
            out.lam(id, b & 3, name, r >>> 5, body);
            return q + 3;
        }
        if (k == LETE8) {
            q += 8;
            if ((long) LE64.get(s, q) != LBODY8) {
                return -1;
            }
            q += 8;
            r = num(s, q);
            if (r < 0) {
                return -1;
            }
            long body = r >>> 5;
            q += (int) r & 31;
            if ((long) LE64.get(s, q) != NAME8) {
                return -1;
            }
            q += 8;
            r = num(s, q);
            if (r < 0) {
                return -1;
            }
            long name = r >>> 5;
            q += (int) r & 31;
            if ((long) LE64.get(s, q) != NONDEP_0) {
                return -1;
            }
            long v = (long) LE64.get(s, q + 8);
            boolean nondep;
            if ((v & M7) == NONDEP_FALSE) {
                nondep = false;
                q += 15;
            } else if ((v & M6) == NONDEP_TRUE) {
                nondep = true;
                q += 14;
            } else {
                return -1;
            }
            if ((long) LE64.get(s, q) != TYPE8) {
                return -1;
            }
            q += 8;
            r = num(s, q);
            if (r < 0) {
                return -1;
            }
            long type = r >>> 5;
            q += (int) r & 31;
            if ((long) LE64.get(s, q) != VALUE_0 || (long) LE64.get(s, q + 1) != VALUE_1) {
                return -1;
            }
            q += 9;
            r = num(s, q);
            if (r < 0) {
                return -1;
            }
            q += (int) r & 31;
            if (((long) LE64.get(s, q) & M3) != END3) {
                return -1;
            }
            out.let(id, name, nondep, type, r >>> 5, body);
            return q + 3;
        }
        if (k == PROJ8) {
            q += 8;
            if (((long) LE64.get(s, q) & M7) != IDX7) {
                return -1;
            }
            q += 7;
            r = num(s, q);
            if (r < 0) {
                return -1;
            }
            long idx = r >>> 5;
            q += (int) r & 31;
            if ((long) LE64.get(s, q) != STRUCT_0 || ((long) LE64.get(s, q + 8) & M2) != STRUCT_8) {
                return -1;
            }
            q += 10;
            r = num(s, q);
            if (r < 0) {
                return -1;
            }
            long struct = r >>> 5;
            q += (int) r & 31;
            if ((long) LE64.get(s, q) != TYPENAME_0 || ((long) LE64.get(s, q + 8) & M4) != TYPENAME_8) {
                return -1;
            }
            q += 12;
            r = num(s, q);
            if (r < 0) {
                return -1;
            }
            q += (int) r & 31;
            if (((long) LE64.get(s, q) & M3) != END3) {
                return -1;
            }
            out.proj(id, idx, struct, r >>> 5);
            return q + 3;
        }
        return -1;
    }

    private static int forall(byte[] s, int p, RecordOut out) throws ExportException {
        if ((long) LE64.get(s, p + 8) != FORALL_8
                || (long) LE64.get(s, p + 16) != FORALL_16
                || ((long) LE64.get(s, p + 24) & M2) != FORALL_24) {
            return -1;
        }
        int q = p + 26;
        int b = binderInfo(s, q);
        if (b < 0) {
            return -1;
        }
        q += b >>> 2;
        if ((long) LE64.get(s, q) != QBODY_0 || (long) LE64.get(s, q + 1) != QBODY_1) {
            return -1;
        }
        q += 9;
        long r = num(s, q);
        if (r < 0) {
            return -1;
        }
        long body = r >>> 5;
        q += (int) r & 31;
        if ((long) LE64.get(s, q) != NAME8) {
            return -1;
        }
        q += 8;
        r = num(s, q);
        if (r < 0) {
            return -1;
        }
        long name = r >>> 5;
        q += (int) r & 31;
        if ((long) LE64.get(s, q) != TYPE8) {
            return -1;
        }
        q += 8;
        r = num(s, q);
        if (r < 0) {
            return -1;
        }
        long type = r >>> 5;
        q += (int) r & 31;
        if (((long) LE64.get(s, q) & M7) != IE7) {
            return -1;
        }
        q += 7;
        r = num(s, q);
        if (r < 0) {
            return -1;
        }
        q += (int) r & 31;
        if (((long) LE64.get(s, q) & M2) != END2) {
            return -1;
        }
        out.pi(r >>> 5, b & 3, name, type, body);
        return q + 2;
    }

    private static int name(byte[] s, int p, RecordOut out) throws ExportException {
        int q = p + 6;
        long r = num(s, q);
        if (r < 0) {
            return -1;
        }
        long id = r >>> 5;
        q += (int) r & 31;
        long k = (long) LE64.get(s, q);
        if (k == STR8) {
            q += 8;
            if (((long) LE64.get(s, q) & M6) != PRE6) {
                return -1;
            }
            q += 6;
            r = num(s, q);
            if (r < 0) {
                return -1;
            }
            q += (int) r & 31;
            if ((long) LE64.get(s, q) != STRQ8) {
                return -1;
            }
            q += 8;
            int len = plainString(s, q);
            if (len < 0) {
                return -1;
            }
            int strOff = q;
            q += len;
            if (((long) LE64.get(s, q) & M4) != QEND4) {
                return -1;
            }
            out.nameStr(id, r >>> 5, strOff, len);
            return q + 4;
        }
        if (k == NUM8) {
            q += 8;
            if (((long) LE64.get(s, q) & M4) != I4) {
                return -1;
            }
            q += 4;
            r = numWide(s, q);
            if (r < 0) {
                return -1;
            }
            long i = r >>> 5;
            q += (int) r & 31;
            if (((long) LE64.get(s, q) & M7) != PRE7) {
                return -1;
            }
            q += 7;
            r = num(s, q);
            if (r < 0) {
                return -1;
            }
            q += (int) r & 31;
            if (((long) LE64.get(s, q) & M3) != END3) {
                return -1;
            }
            out.nameNum(id, r >>> 5, i);
            return q + 3;
        }
        return -1;
    }

    private static int constant(byte[] s, int p, RecordOut out, int[] list) throws ExportException {
        if ((long) LE64.get(s, p + 8) != CONST_8 || ((long) LE64.get(s, p + 16) & M1) != ':') {
            return -1;
        }
        int q = p + 17;
        long r = num(s, q);
        if (r < 0) {
            return -1;
        }
        long name = r >>> 5;
        q += (int) r & 31;
        if (((long) LE64.get(s, q) & M7) != US7) {
            return -1;
        }
        q += 7;
        int n = 0;
        if (Mem.u8(s, q) != ']') {
            while (true) {
                r = num(s, q);
                if (r < 0 || n == list.length) {
                    return -1;
                }
                list[n++] = (int) (r >>> 5);
                q += (int) r & 31;
                byte c = Mem.u8(s, q);
                if (c == ',') {
                    q++;
                } else if (c == ']') {
                    break;
                } else {
                    return -1;
                }
            }
        }
        if ((long) LE64.get(s, q) != CONST_END8) {
            return -1;
        }
        q += 8;
        r = num(s, q);
        if (r < 0) {
            return -1;
        }
        q += (int) r & 31;
        if (((long) LE64.get(s, q) & M2) != END2) {
            return -1;
        }
        out.constant(r >>> 5, name, list, 0, n);
        return q + 2;
    }
}

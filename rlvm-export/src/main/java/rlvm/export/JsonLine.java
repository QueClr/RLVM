package rlvm.export;

import java.util.Arrays;

/**
 * Strict JSON parser for one line: the general path.
 *
 * <p>It accepts exactly the texts that Lean's {@code Json.parse} accepts (the parser behind the
 * official reader; {@code src/Lean/Data/Json/Parser.lean} of Lean v4.36.0), and produces the same
 * values:
 * <ul>
 *   <li>whitespace is space, tab, CR, LF, allowed around every token, before and after the value;</li>
 *   <li>objects with a repeated key are an error, at any depth;</li>
 *   <li>strings: raw characters from U+0020 up; escapes {@code \\ \" \/ \b \f \n \r \t \}{@code uXXXX};
 *       a high surrogate escape followed by a low surrogate escape is one character; any other
 *       surrogate escape is U+FFFD (not an error);</li>
 *   <li>numbers: {@code -?(0|[1-9][0-9]*)(\.[0-9]+)?([eE][+-]?[0-9]+)?} with a positive exponent of at
 *       most 100000; the value is kept as mantissa and exponent the way Lean's {@code JsonNumber}
 *       does, which decides whether a number is "a natural number" for the reader: {@code 1e2},
 *       {@code 1.5e1} and {@code -0} are, {@code 1.0}, {@code 10e-1} and {@code -1} are not.</li>
 * </ul>
 * Raw text that is not well-formed UTF-8 is {@link ExportException.Kind#UNSUPPORTED}: the official
 * reader does not validate its input and its behaviour on such bytes is not specified.
 *
 * <p>The result is a tree in flat arrays (no allocation per line once the arrays have grown): node 0 is
 * the root. Not recursive: nesting depth is limited by memory only, like the official parser's.
 */
final class JsonLine {
    static final int OBJ = 1;
    static final int ARR = 2;
    static final int STR = 3;
    static final int NUM = 4;
    static final int TRUE = 5;
    static final int FALSE = 6;
    static final int NULL = 7;

    /** {@link #natural}: the number is a natural of 2^63 or more; its digits are in {@link #text}. */
    static final long BIG = -1;
    /** {@link #natural}: the number is not a natural number. */
    static final long NOT_NAT = -2;

    private static final int MAX_POSITIVE_EXPONENT = 100_000;
    /** Objects with more members than this use the hash set for the duplicate-key check. */
    private static final int LINEAR_KEYS = 12;

    // ---- nodes
    int[] type = new int[64];
    /** Next sibling, -1 at the end. */
    int[] next = new int[64];
    /** First child of an object or array, -1 if empty. */
    int[] child = new int[64];
    /** Number of children. */
    int[] size = new int[64];
    /** Member key (decoded) in {@link #text}; -1 for array elements and the root. */
    int[] keyOff = new int[64];
    int[] keyLen = new int[64];
    /** STR: decoded string in {@link #text}. NUM with {@link #BIG}: canonical digits in {@link #text}. */
    int[] valOff = new int[64];
    int[] valLen = new int[64];
    /** NUM: the natural number value, {@link #BIG} or {@link #NOT_NAT}. */
    long[] natural = new long[64];
    private int[] last = new int[64];
    private int nodes;

    /** Decoded strings and big-number digits. */
    byte[] text = new byte[256];
    private int textLen;

    private int[] stack = new int[32];
    private int depth;

    // ---- duplicate-key hash set for wide objects: (object node, key) -> present
    private int[] dupTable = new int[0];
    private int dupUsed;

    private byte[] src;
    private int start;
    private int end;

    /** Parses {@code b[from .. n)} as one JSON value surrounded by optional whitespace; the root is node 0. */
    void parse(byte[] b, int from, int n) throws ExportException {
        src = b;
        start = from;
        end = n;
        nodes = 0;
        textLen = 0;
        depth = 0;
        if (dupUsed != 0) {
            Arrays.fill(dupTable, -1);
            dupUsed = 0;
        }
        int p = ws(from);
        int pendingKeyOff = -1;
        int pendingKeyLen = 0;
        boolean expectKey = false;
        while (true) {
            if (expectKey) {
                // objectCore: '"' key ws ':' ws
                if (p >= n) {
                    throw eof();
                }
                if (b[p] != '"') {
                    throw bad(p, "expected \"");
                }
                int ko = textLen;
                p = string(p + 1);
                int kl = textLen - ko;
                p = ws(p);
                checkDuplicate(stack[depth - 1], ko, kl, p);
                if (p >= n) {
                    throw eof();
                }
                if (b[p] != ':') {
                    throw bad(p, "expected :");
                }
                p = ws(p + 1);
                pendingKeyOff = ko;
                pendingKeyLen = kl;
                expectKey = false;
            }
            // anyCore
            if (p >= n) {
                throw eof();
            }
            int c = b[p];
            int node = newNode(pendingKeyOff, pendingKeyLen);
            pendingKeyOff = -1;
            boolean opened = false;
            if (c == '[') {
                type[node] = ARR;
                p = ws(p + 1);
                if (p >= n) {
                    throw eof();
                }
                if (b[p] == ']') {
                    p = ws(p + 1);
                } else {
                    push(node);
                    opened = true;
                }
            } else if (c == '{') {
                type[node] = OBJ;
                p = ws(p + 1);
                if (p >= n) {
                    throw eof();
                }
                if (b[p] == '}') {
                    p = ws(p + 1);
                } else {
                    push(node);
                    opened = true;
                    expectKey = true;
                }
            } else if (c == '"') {
                type[node] = STR;
                valOff[node] = textLen;
                p = string(p + 1);
                valLen[node] = textLen - valOff[node];
                p = ws(p);
            } else if (c == 'f') {
                type[node] = FALSE;
                p = ws(literal(p, "false"));
            } else if (c == 't') {
                type[node] = TRUE;
                p = ws(literal(p, "true"));
            } else if (c == 'n') {
                type[node] = NULL;
                p = ws(literal(p, "null"));
            } else if (c == '-' || (c >= '0' && c <= '9')) {
                type[node] = NUM;
                p = ws(number(p, node));
            } else {
                throw bad(p, "unexpected input");
            }
            if (opened) {
                continue;
            }
            // a value is complete: close containers / continue with the next element
            while (true) {
                if (depth == 0) {
                    if (p != n) {
                        throw bad(p, "expected end of input");
                    }
                    return;
                }
                if (p >= n) {
                    throw eof();
                }
                int top = stack[depth - 1];
                c = b[p];
                if (type[top] == ARR) {
                    if (c == ']') {
                        depth--;
                        p = ws(p + 1);
                    } else if (c == ',') {
                        p = ws(p + 1);
                        break;
                    } else {
                        throw bad(p, "unexpected character in array");
                    }
                } else {
                    if (c == '}') {
                        depth--;
                        p = ws(p + 1);
                    } else if (c == ',') {
                        p = ws(p + 1);
                        expectKey = true;
                        break;
                    } else {
                        throw bad(p, "unexpected character in object");
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------------ access

    /** The member of object {@code obj} whose key is {@code key}, or -1. */
    int member(int obj, byte[] key) {
        byte[] t = text;
        int kl = key.length;
        for (int c = child[obj]; c >= 0; c = next[c]) {
            if (keyLen[c] == kl) {
                int o = keyOff[c];
                int i = 0;
                while (i < kl && t[o + i] == key[i]) {
                    i++;
                }
                if (i == kl) {
                    return c;
                }
            }
        }
        return -1;
    }

    boolean keyIs(int node, byte[] key) {
        if (keyLen[node] != key.length) {
            return false;
        }
        int o = keyOff[node];
        for (int i = 0; i < key.length; i++) {
            if (text[o + i] != key[i]) {
                return false;
            }
        }
        return true;
    }

    boolean stringIs(int node, byte[] s) {
        if (valLen[node] != s.length) {
            return false;
        }
        int o = valOff[node];
        for (int i = 0; i < s.length; i++) {
            if (text[o + i] != s[i]) {
                return false;
            }
        }
        return true;
    }

    /** Whether {@code node} is a number that Lean reads as a {@code Nat} ({@code .num (n : Nat)}). */
    boolean isNat(int node) {
        return type[node] == NUM && natural[node] != NOT_NAT;
    }

    String keysForMessage(int obj) {
        StringBuilder sb = new StringBuilder("[");
        int shown = 0;
        for (int c = child[obj]; c >= 0; c = next[c]) {
            if (shown == 8) {
                sb.append(", ...");
                break;
            }
            if (shown++ > 0) {
                sb.append(", ");
            }
            sb.append(new String(text, keyOff[c], Math.min(keyLen[c], 40), java.nio.charset.StandardCharsets.UTF_8));
        }
        return sb.append(']').toString();
    }

    // ------------------------------------------------------------------------------------------- nodes

    private int newNode(int ko, int kl) {
        int i = nodes;
        if (i == type.length) {
            growNodes();
        }
        nodes = i + 1;
        next[i] = -1;
        child[i] = -1;
        size[i] = 0;
        keyOff[i] = ko;
        keyLen[i] = kl;
        if (depth > 0) {
            int parent = stack[depth - 1];
            if (child[parent] < 0) {
                child[parent] = i;
            } else {
                next[last[parent]] = i;
            }
            last[parent] = i;
            size[parent]++;
        }
        return i;
    }

    private void growNodes() {
        int cap = type.length * 2;
        type = Arrays.copyOf(type, cap);
        next = Arrays.copyOf(next, cap);
        child = Arrays.copyOf(child, cap);
        size = Arrays.copyOf(size, cap);
        keyOff = Arrays.copyOf(keyOff, cap);
        keyLen = Arrays.copyOf(keyLen, cap);
        valOff = Arrays.copyOf(valOff, cap);
        valLen = Arrays.copyOf(valLen, cap);
        natural = Arrays.copyOf(natural, cap);
        last = Arrays.copyOf(last, cap);
    }

    private void push(int node) {
        if (depth == stack.length) {
            stack = Arrays.copyOf(stack, depth * 2);
        }
        stack[depth++] = node;
    }

    private void checkDuplicate(int obj, int ko, int kl, int errPos) throws ExportException {
        byte[] t = text;
        if (size[obj] <= LINEAR_KEYS) {
            for (int c = child[obj]; c >= 0; c = next[c]) {
                if (keyLen[c] == kl && equalText(t, keyOff[c], ko, kl)) {
                    throw duplicate(ko, kl, errPos);
                }
            }
            if (size[obj] < LINEAR_KEYS) {
                return;
            }
            // the object becomes wide: move its keys into the hash set
            for (int c = child[obj]; c >= 0; c = next[c]) {
                dupInsert(obj, keyOff[c], keyLen[c]);
            }
        } else if (dupContains(obj, ko, kl)) {
            throw duplicate(ko, kl, errPos);
        }
        dupInsert(obj, ko, kl);
    }

    private ExportException duplicate(int ko, int kl, int errPos) {
        return bad(errPos, "duplicate object key \""
                + new String(text, ko, Math.min(kl, 60), java.nio.charset.StandardCharsets.UTF_8) + "\"");
    }

    private static boolean equalText(byte[] t, int a, int b, int len) {
        for (int i = 0; i < len; i++) {
            if (t[a + i] != t[b + i]) {
                return false;
            }
        }
        return true;
    }

    private int keyHash(int obj, int ko, int kl) {
        int h = obj * 0x9E3779B9 + kl;
        byte[] t = text;
        for (int i = 0; i < kl; i++) {
            h = (h ^ t[ko + i]) * 0x01000193;
        }
        return h ^ (h >>> 15);
    }

    // dupTable holds triples (object node, key offset, key length); object node -1 = empty slot
    private boolean dupContains(int obj, int ko, int kl) {
        int slots = dupTable.length / 3;
        if (slots == 0) {
            return false;
        }
        int i = keyHash(obj, ko, kl) & (slots - 1);
        while (true) {
            int o = dupTable[3 * i];
            if (o < 0) {
                return false;
            }
            if (o == obj && dupTable[3 * i + 2] == kl && equalText(text, dupTable[3 * i + 1], ko, kl)) {
                return true;
            }
            i = (i + 1) & (slots - 1);
        }
    }

    private void dupInsert(int obj, int ko, int kl) {
        int slots = dupTable.length / 3;
        if ((dupUsed + 1) * 2 > slots) {
            int[] old = dupTable;
            int newSlots = Math.max(64, slots * 2);
            dupTable = new int[newSlots * 3];
            Arrays.fill(dupTable, -1);
            for (int j = 0; j < slots; j++) {
                if (old[3 * j] >= 0) {
                    dupPut(old[3 * j], old[3 * j + 1], old[3 * j + 2]);
                }
            }
        }
        dupPut(obj, ko, kl);
        dupUsed++;
    }

    private void dupPut(int obj, int ko, int kl) {
        int slots = dupTable.length / 3;
        int i = keyHash(obj, ko, kl) & (slots - 1);
        while (dupTable[3 * i] >= 0) {
            i = (i + 1) & (slots - 1);
        }
        dupTable[3 * i] = obj;
        dupTable[3 * i + 1] = ko;
        dupTable[3 * i + 2] = kl;
    }

    // ------------------------------------------------------------------------------------------ tokens

    private int ws(int p) {
        byte[] b = src;
        int n = end;
        while (p < n) {
            int c = b[p];
            if (c != ' ' && c != '\n' && c != '\r' && c != '\t') {
                break;
            }
            p++;
        }
        return p;
    }

    private int literal(int p, String word) throws ExportException {
        int len = word.length();
        for (int i = 0; i < len; i++) {
            if (p + i >= end) {
                throw eof();
            }
            if (src[p + i] != word.charAt(i)) {
                throw bad(p + i, "expected: " + word);
            }
        }
        return p + len;
    }

    private void ensureText(int extra) {
        if (textLen + extra > text.length) {
            text = Arrays.copyOf(text, Math.max(text.length * 2, textLen + extra + 64));
        }
    }

    /** Parses string content starting after the opening quote; appends the decoded bytes to {@link #text}. */
    private int string(int p) throws ExportException {
        byte[] b = src;
        int n = end;
        while (true) {
            // copy a run of plain ASCII
            int q = p;
            while (q < n) {
                int c = b[q];
                if (c < 0x20 || c == '"' || c == '\\') { // also stops at bytes >= 0x80 (negative)
                    break;
                }
                q++;
            }
            int run = q - p;
            ensureText(run + 8);
            System.arraycopy(b, p, text, textLen, run);
            textLen += run;
            p = q;
            if (p >= n) {
                throw eof();
            }
            int c = b[p];
            if (c == '"') {
                return p + 1;
            }
            if (c == '\\') {
                p = escape(p + 1);
            } else if (c < 0) {
                int len = Utf8.sequenceLength(b, p, n);
                if (len == 0) {
                    throw new ExportException(ExportException.Kind.UNSUPPORTED,
                            "ill-formed UTF-8 at byte " + (p - start) + " of the line");
                }
                text[textLen++] = b[p];
                text[textLen++] = b[p + 1];
                if (len > 2) {
                    text[textLen++] = b[p + 2];
                    if (len > 3) {
                        text[textLen++] = b[p + 3];
                    }
                }
                p += len;
            } else {
                throw bad(p, "unexpected character in string");
            }
        }
    }

    private int hex(int p) throws ExportException {
        if (p >= end) {
            throw eof();
        }
        int c = src[p];
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'a' && c <= 'f') {
            return c - 'a' + 10;
        }
        if (c >= 'A' && c <= 'F') {
            return c - 'A' + 10;
        }
        throw bad(p, "invalid hex character");
    }

    /** Hex digit value or -1, without failing (for the optional second half of a surrogate pair). */
    private int hexOpt(int p) {
        if (p >= end) {
            return -1;
        }
        int c = src[p];
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'a' && c <= 'f') {
            return c - 'a' + 10;
        }
        if (c >= 'A' && c <= 'F') {
            return c - 'A' + 10;
        }
        return -1;
    }

    /** At the character after a backslash; appends the escaped character, returns the next position. */
    private int escape(int p) throws ExportException {
        if (p >= end) {
            throw eof();
        }
        byte[] b = src;
        int cp;
        switch (b[p]) {
            case '\\' -> cp = '\\';
            case '"' -> cp = '"';
            case '/' -> cp = '/';
            case 'b' -> cp = 0x08;
            case 'f' -> cp = 0x0C;
            case 'n' -> cp = '\n';
            case 'r' -> cp = 0x0D;
            case 't' -> cp = '\t';
            case 'u' -> {
                int v = (hex(p + 1) << 12) | (hex(p + 2) << 8) | (hex(p + 3) << 4) | hex(p + 4);
                p += 4;
                if (v < 0xD800 || v >= 0xE000) {
                    cp = v;
                } else if (v < 0xDC00) {
                    // first half of a pair: combine if a second escape "ud[c-f]xx" follows, else U+FFFD
                    cp = 0xFFFD;
                    if (p + 3 < end && b[p + 1] == '\\' && b[p + 2] == 'u' && (b[p + 3] == 'd' || b[p + 3] == 'D')) {
                        int h2 = hexOpt(p + 4);
                        int h3 = hexOpt(p + 5);
                        int h4 = hexOpt(p + 6);
                        if (h2 >= 0xC && h3 >= 0 && h4 >= 0) {
                            int lowBits = ((h2 << 8) | (h3 << 4) | h4) & 0x3FF;
                            cp = (((v & 0x3FF) << 10) | lowBits) + 0x10000;
                            p += 6;
                        }
                    }
                } else {
                    cp = 0xFFFD;
                }
            }
            default -> throw bad(p, "illegal \\u escape");
        }
        ensureText(4);
        textLen = Utf8.encode(cp, text, textLen);
        return p + 1;
    }

    /**
     * Parses a number at {@code p} and classifies it the way {@code Lean.JsonNumber} does; returns the
     * position after it.
     */
    private int number(int p, int node) throws ExportException {
        byte[] b = src;
        int n = end;
        boolean negative = false;
        if (b[p] == '-') {
            negative = true;
            p++;
            if (p >= n) {
                throw eof();
            }
        }
        // whole part: "0" or a non-zero digit followed by digits
        int wholeStart = p;
        int c = b[p];
        if (c == '0') {
            p++;
        } else if (c >= '1' && c <= '9') {
            p++;
            while (p < n && b[p] >= '0' && b[p] <= '9') {
                p++;
            }
        } else {
            throw bad(p, "expected 1-9");
        }
        int wholeEnd = p;
        int fracStart = p;
        int fracEnd = p;
        if (p < n && b[p] == '.') {
            p++;
            fracStart = p;
            while (p < n && b[p] >= '0' && b[p] <= '9') {
                p++;
            }
            fracEnd = p;
            if (fracEnd == fracStart) {
                throw p >= n ? eof() : bad(p, "expected digit");
            }
        }
        // exponent
        boolean hasExp = false;
        boolean expNegative = false;
        long exp = 0; // saturates above MAX_POSITIVE_EXPONENT
        if (p < n && (b[p] == 'e' || b[p] == 'E')) {
            hasExp = true;
            p++;
            if (p >= n) {
                throw eof();
            }
            if (b[p] == '-') {
                expNegative = true;
                p++;
            } else if (b[p] == '+') {
                p++;
            }
            int expStart = p;
            while (p < n && b[p] >= '0' && b[p] <= '9') {
                if (exp <= MAX_POSITIVE_EXPONENT) {
                    exp = exp * 10 + (b[p] - '0');
                }
                p++;
            }
            if (p == expStart) {
                throw p >= n ? eof() : bad(p, "expected 0-9");
            }
            if (!expNegative && exp > MAX_POSITIVE_EXPONENT) {
                throw bad(expStart, "exponent too large");
            }
        }
        // JsonNumber: mantissa = sign * digits(whole ++ frac), exponent e = number of fraction digits;
        //   e-N: exponent e + N;   e+N / eN: mantissa * 10^(N - e) and exponent e - N (truncated at 0).
        // It is a Nat iff the final exponent is 0 and the mantissa is not negative.
        int fracDigits = fracEnd - fracStart;
        long zeros; // zeros to append to the mantissa digits
        if (!hasExp) {
            if (fracDigits != 0) {
                natural[node] = NOT_NAT;
                return p;
            }
            zeros = 0;
        } else if (expNegative) {
            if (fracDigits != 0 || exp != 0) {
                natural[node] = NOT_NAT;
                return p;
            }
            zeros = 0;
        } else {
            if (exp < fracDigits) {
                natural[node] = NOT_NAT;
                return p;
            }
            zeros = exp - fracDigits;
        }
        // strip leading zeros of whole ++ frac
        int i = wholeStart;
        boolean inFrac = false;
        if (i == wholeEnd) {
            i = fracStart;
            inFrac = true;
        }
        while (true) {
            if (!inFrac && i == wholeEnd) {
                i = fracStart;
                inFrac = true;
            }
            if (inFrac && i == fracEnd) {
                break;
            }
            if (b[i] != '0') {
                break;
            }
            i++;
        }
        boolean isZero = inFrac && i == fracEnd;
        if (isZero) {
            natural[node] = 0; // also "-0": the integer 0
            return p;
        }
        if (negative) {
            natural[node] = NOT_NAT;
            return p;
        }
        long sig = (inFrac ? fracEnd - i : (wholeEnd - i) + fracDigits) + zeros; // significant digits
        if (sig <= 18) {
            long v = 0;
            int j = i;
            boolean jFrac = inFrac;
            while (true) {
                if (!jFrac && j == wholeEnd) {
                    j = fracStart;
                    jFrac = true;
                }
                if (jFrac && j == fracEnd) {
                    break;
                }
                v = v * 10 + (b[j] - '0');
                j++;
            }
            for (long z = 0; z < zeros; z++) {
                v *= 10;
            }
            natural[node] = v;
            return p;
        }
        // 19 digits or more: canonical digits into the text buffer, then decide whether it fits a long
        if (sig > Integer.MAX_VALUE - 64) {
            throw ExportException.unsupported("number with more than 2^31 digits");
        }
        ensureText((int) sig);
        int start = textLen;
        int j = i;
        boolean jFrac = inFrac;
        while (true) {
            if (!jFrac && j == wholeEnd) {
                j = fracStart;
                jFrac = true;
            }
            if (jFrac && j == fracEnd) {
                break;
            }
            text[textLen++] = b[j++];
        }
        for (long z = 0; z < zeros; z++) {
            text[textLen++] = '0';
        }
        valOff[node] = start;
        valLen[node] = (int) sig;
        natural[node] = sig == 19 ? parseLong19(text, start) : BIG;
        return p;
    }

    /** Value of 19 canonical digits if below 2^63, else {@link #BIG}. */
    static long parseLong19(byte[] t, int off) {
        long v = 0;
        for (int i = 0; i < 18; i++) {
            v = v * 10 + (t[off + i] - '0');
        }
        int d = t[off + 18] - '0';
        // v < 10^18; v * 10 + d must stay below 2^63 = 9223372036854775808
        if (v > 922337203685477580L || (v == 922337203685477580L && d > 7)) {
            return BIG;
        }
        return v * 10 + d;
    }

    private ExportException eof() {
        return ExportException.malformed("Invalid JSON: unexpected end of input");
    }

    private ExportException bad(int p, String what) {
        return ExportException.malformed("Invalid JSON: offset " + (p - start) + ": " + what);
    }
}

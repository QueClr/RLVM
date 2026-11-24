package rlvm.testkit;

import java.util.ArrayList;
import java.util.List;

/**
 * A minimal JSON tree for the test tools (export lines, verdict caches, corpus indexes). Not used by
 * the checker itself, so clarity wins over speed.
 *
 * <p>Design points that matter for the mutator:
 * <ul>
 *   <li>Objects keep their keys in the order read (or added); the writer emits that order.
 *   <li>Numbers are kept as their source text (naturals of any length survive unchanged).
 *   <li>{@link #write(Json)} produces exactly the bytes of lean4export for a line of lean4export:
 *       compact, and strings escaped as Lean's {@code Json.compress} does ({@code \"}, {@code \\},
 *       {@code \n}, {@code \r}, other code points below U+0020 as {@code \\u00xx}, everything else raw).
 *       Hence {@code write(parse(line)).equals(line)} for every canonical line, and a mutant differs
 *       from its source only where the mutation edited the tree.
 * </ul>
 */
public final class Json {

    public enum Kind { OBJECT, ARRAY, STRING, NUMBER, BOOL, NULL }

    public final Kind kind;
    /** NUMBER: source text. STRING: decoded value. BOOL: "true" / "false". */
    private String text;
    private final List<String> keys;
    private final List<Json> values;

    private Json(Kind kind, String text) {
        this.kind = kind;
        this.text = text;
        boolean container = kind == Kind.OBJECT || kind == Kind.ARRAY;
        this.keys = kind == Kind.OBJECT ? new ArrayList<>() : null;
        this.values = container ? new ArrayList<>() : null;
    }

    // ---- construction ------------------------------------------------------------------------------

    public static Json object() {
        return new Json(Kind.OBJECT, null);
    }

    public static Json array() {
        return new Json(Kind.ARRAY, null);
    }

    public static Json string(String value) {
        return new Json(Kind.STRING, value);
    }

    public static Json number(long value) {
        return new Json(Kind.NUMBER, Long.toString(value));
    }

    public static Json number(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return nul();
        }
        if (value == Math.rint(value) && Math.abs(value) < 1e15) {
            return new Json(Kind.NUMBER, Long.toString((long) value));
        }
        return new Json(Kind.NUMBER, Double.toString(value));
    }

    /** A number given as source text (e.g. a natural with hundreds of digits). */
    public static Json numberText(String digits) {
        return new Json(Kind.NUMBER, digits);
    }

    public static Json bool(boolean value) {
        return new Json(Kind.BOOL, value ? "true" : "false");
    }

    public static Json nul() {
        return new Json(Kind.NULL, "null");
    }

    // ---- access -------------------------------------------------------------------------------------

    public boolean isObject() {
        return kind == Kind.OBJECT;
    }

    public boolean isArray() {
        return kind == Kind.ARRAY;
    }

    public boolean isString() {
        return kind == Kind.STRING;
    }

    public boolean isNumber() {
        return kind == Kind.NUMBER;
    }

    public boolean isBool() {
        return kind == Kind.BOOL;
    }

    public boolean isNull() {
        return kind == Kind.NULL;
    }

    /** STRING value, NUMBER source text, or "true"/"false"/"null". */
    public String text() {
        if (text == null) {
            throw new IllegalStateException("not a scalar: " + kind);
        }
        return text;
    }

    public boolean asBool() {
        if (kind != Kind.BOOL) {
            throw new IllegalStateException("not a boolean: " + kind);
        }
        return text.equals("true");
    }

    /** True for a NUMBER that is a plain natural (digits only). */
    public boolean isNatural() {
        if (kind != Kind.NUMBER || text.isEmpty()) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    /** Value of a natural that fits in a long; -1 if it is not a natural or does not fit. */
    public long asLong() {
        if (!isNatural() || text.length() > 18) {
            return -1;
        }
        return Long.parseLong(text);
    }

    public double asDouble() {
        if (kind != Kind.NUMBER) {
            throw new IllegalStateException("not a number: " + kind);
        }
        return Double.parseDouble(text);
    }

    /** Number of members (OBJECT) or elements (ARRAY). */
    public int size() {
        return values.size();
    }

    public String key(int i) {
        return keys.get(i);
    }

    public Json at(int i) {
        return values.get(i);
    }

    public boolean has(String key) {
        return kind == Kind.OBJECT && keys.contains(key);
    }

    /** Member {@code key} of an OBJECT, or null. */
    public Json get(String key) {
        if (kind != Kind.OBJECT) {
            return null;
        }
        int i = keys.indexOf(key);
        return i < 0 ? null : values.get(i);
    }

    /** Text of a scalar member, or {@code fallback} when it is absent or not a scalar. */
    public String getText(String key, String fallback) {
        Json v = get(key);
        return v == null || v.text == null || v.kind == Kind.NULL ? fallback : v.text;
    }

    public long getLong(String key, long fallback) {
        Json v = get(key);
        if (v == null || v.kind != Kind.NUMBER) {
            return fallback;
        }
        long n = v.asLong();
        return n >= 0 ? n : (long) v.asDouble();
    }

    public double getDouble(String key, double fallback) {
        Json v = get(key);
        return v == null || v.kind != Kind.NUMBER ? fallback : v.asDouble();
    }

    // ---- mutation ------------------------------------------------------------------------------------

    /** Sets (or appends) a member of an OBJECT; returns this. */
    public Json put(String key, Json value) {
        if (kind != Kind.OBJECT) {
            throw new IllegalStateException("not an object");
        }
        int i = keys.indexOf(key);
        if (i < 0) {
            keys.add(key);
            values.add(value);
        } else {
            values.set(i, value);
        }
        return this;
    }

    public Json put(String key, String value) {
        return put(key, value == null ? nul() : string(value));
    }

    public Json put(String key, long value) {
        return put(key, number(value));
    }

    public Json put(String key, double value) {
        return put(key, number(value));
    }

    public Json put(String key, boolean value) {
        return put(key, bool(value));
    }

    /** Appends to an ARRAY; returns this. */
    public Json add(Json value) {
        if (kind != Kind.ARRAY) {
            throw new IllegalStateException("not an array");
        }
        values.add(value);
        return this;
    }

    public void set(int i, Json value) {
        values.set(i, value);
    }

    public void removeAt(int i) {
        values.remove(i);
        if (keys != null) {
            keys.remove(i);
        }
    }

    public void insertAt(int i, Json value) {
        if (kind != Kind.ARRAY) {
            throw new IllegalStateException("not an array");
        }
        values.add(i, value);
    }

    public boolean remove(String key) {
        int i = kind == Kind.OBJECT ? keys.indexOf(key) : -1;
        if (i < 0) {
            return false;
        }
        removeAt(i);
        return true;
    }

    /** Replaces the scalar content in place (same kind). */
    public void setText(String value) {
        if (text == null) {
            throw new IllegalStateException("not a scalar: " + kind);
        }
        text = value;
    }

    /** Reorders the members of an OBJECT: member {@code order[i]} becomes member {@code i}. */
    public void permute(int[] order) {
        if (kind != Kind.OBJECT || order.length != keys.size()) {
            throw new IllegalArgumentException("permutation does not fit");
        }
        List<String> k = new ArrayList<>(keys);
        List<Json> v = new ArrayList<>(values);
        for (int i = 0; i < order.length; i++) {
            keys.set(i, k.get(order[i]));
            values.set(i, v.get(order[i]));
        }
    }

    public Json copy() {
        Json c = new Json(kind, text);
        if (values != null) {
            for (int i = 0; i < values.size(); i++) {
                if (keys != null) {
                    c.keys.add(keys.get(i));
                }
                c.values.add(values.get(i).copy());
            }
        }
        return c;
    }

    // ---- parsing -------------------------------------------------------------------------------------

    /** Thrown for text that is not one JSON value. */
    public static final class ParseException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public ParseException(String message) {
            super(message);
        }
    }

    /** Parses exactly one JSON value (surrounding whitespace allowed). */
    public static Json parse(String s) {
        Parser p = new Parser(s);
        p.skipWs();
        Json v = p.value(0);
        p.skipWs();
        if (p.pos != s.length()) {
            throw new ParseException("trailing characters at offset " + p.pos);
        }
        return v;
    }

    /** True if {@code s} is one JSON value. */
    public static boolean isValid(String s) {
        try {
            parse(s);
            return true;
        } catch (ParseException e) {
            return false;
        }
    }

    private static final class Parser {
        final String s;
        int pos;

        Parser(String s) {
            this.s = s;
        }

        void skipWs() {
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    pos++;
                } else {
                    break;
                }
            }
        }

        Json value(int depth) {
            if (depth > 2000) {
                throw new ParseException("nesting too deep");
            }
            if (pos >= s.length()) {
                throw new ParseException("unexpected end of input");
            }
            char c = s.charAt(pos);
            switch (c) {
                case '{': {
                    pos++;
                    Json o = object();
                    skipWs();
                    if (peek() == '}') {
                        pos++;
                        return o;
                    }
                    while (true) {
                        skipWs();
                        if (peek() != '"') {
                            throw new ParseException("object key expected at offset " + pos);
                        }
                        String k = string();
                        skipWs();
                        expect(':');
                        skipWs();
                        Json v = value(depth + 1);
                        // duplicate keys are kept as written (the writer reproduces them)
                        o.keys.add(k);
                        o.values.add(v);
                        skipWs();
                        char d = peek();
                        pos++;
                        if (d == '}') {
                            return o;
                        }
                        if (d != ',') {
                            throw new ParseException("',' or '}' expected at offset " + (pos - 1));
                        }
                    }
                }
                case '[': {
                    pos++;
                    Json a = array();
                    skipWs();
                    if (peek() == ']') {
                        pos++;
                        return a;
                    }
                    while (true) {
                        skipWs();
                        a.values.add(value(depth + 1));
                        skipWs();
                        char d = peek();
                        pos++;
                        if (d == ']') {
                            return a;
                        }
                        if (d != ',') {
                            throw new ParseException("',' or ']' expected at offset " + (pos - 1));
                        }
                    }
                }
                case '"':
                    return Json.string(string());
                case 't':
                    literal("true");
                    return bool(true);
                case 'f':
                    literal("false");
                    return bool(false);
                case 'n':
                    literal("null");
                    return nul();
                default:
                    return number();
            }
        }

        char peek() {
            if (pos >= s.length()) {
                throw new ParseException("unexpected end of input");
            }
            return s.charAt(pos);
        }

        void expect(char c) {
            if (peek() != c) {
                throw new ParseException("'" + c + "' expected at offset " + pos);
            }
            pos++;
        }

        void literal(String word) {
            if (!s.startsWith(word, pos)) {
                throw new ParseException("invalid literal at offset " + pos);
            }
            pos += word.length();
        }

        Json number() {
            int start = pos;
            if (pos < s.length() && s.charAt(pos) == '-') {
                pos++;
            }
            int digits = pos;
            while (pos < s.length() && isDigit(s.charAt(pos))) {
                pos++;
            }
            if (pos == digits) {
                throw new ParseException("value expected at offset " + start);
            }
            if (s.charAt(digits) == '0' && pos - digits > 1) {
                throw new ParseException("leading zero at offset " + digits);
            }
            if (pos < s.length() && s.charAt(pos) == '.') {
                pos++;
                int frac = pos;
                while (pos < s.length() && isDigit(s.charAt(pos))) {
                    pos++;
                }
                if (pos == frac) {
                    throw new ParseException("digits expected at offset " + pos);
                }
            }
            if (pos < s.length() && (s.charAt(pos) == 'e' || s.charAt(pos) == 'E')) {
                pos++;
                if (pos < s.length() && (s.charAt(pos) == '+' || s.charAt(pos) == '-')) {
                    pos++;
                }
                int exp = pos;
                while (pos < s.length() && isDigit(s.charAt(pos))) {
                    pos++;
                }
                if (pos == exp) {
                    throw new ParseException("digits expected at offset " + pos);
                }
            }
            return new Json(Kind.NUMBER, s.substring(start, pos));
        }

        static boolean isDigit(char c) {
            return c >= '0' && c <= '9';
        }

        String string() {
            pos++; // opening quote
            StringBuilder sb = null;
            int start = pos;
            while (true) {
                if (pos >= s.length()) {
                    throw new ParseException("unterminated string");
                }
                char c = s.charAt(pos);
                if (c == '"') {
                    String out = sb == null ? s.substring(start, pos) : sb.append(s, start, pos).toString();
                    pos++;
                    return out;
                }
                if (c < 0x20) {
                    throw new ParseException("control character in string at offset " + pos);
                }
                if (c != '\\') {
                    pos++;
                    continue;
                }
                if (sb == null) {
                    sb = new StringBuilder();
                }
                sb.append(s, start, pos);
                pos++;
                if (pos >= s.length()) {
                    throw new ParseException("unterminated escape");
                }
                char e = s.charAt(pos++);
                switch (e) {
                    case '"':
                        sb.append('"');
                        break;
                    case '\\':
                        sb.append('\\');
                        break;
                    case '/':
                        sb.append('/');
                        break;
                    case 'b':
                        sb.append('\b');
                        break;
                    case 'f':
                        sb.append('\f');
                        break;
                    case 'n':
                        sb.append('\n');
                        break;
                    case 'r':
                        sb.append('\r');
                        break;
                    case 't':
                        sb.append('\t');
                        break;
                    case 'u': {
                        if (pos + 4 > s.length()) {
                            throw new ParseException("truncated \\u escape");
                        }
                        int v = 0;
                        for (int i = 0; i < 4; i++) {
                            int d = Character.digit(s.charAt(pos + i), 16);
                            if (d < 0) {
                                throw new ParseException("invalid \\u escape at offset " + pos);
                            }
                            v = v * 16 + d;
                        }
                        pos += 4;
                        sb.append((char) v);
                        break;
                    }
                    default:
                        throw new ParseException("invalid escape \\" + e);
                }
                start = pos;
            }
        }
    }

    // ---- writing -------------------------------------------------------------------------------------

    /** Compact text, escaped like lean4export (see the class comment). */
    public static String write(Json v) {
        StringBuilder sb = new StringBuilder();
        write(v, sb);
        return sb.toString();
    }

    public static void write(Json v, StringBuilder sb) {
        switch (v.kind) {
            case OBJECT:
                sb.append('{');
                for (int i = 0; i < v.values.size(); i++) {
                    if (i > 0) {
                        sb.append(',');
                    }
                    quote(v.keys.get(i), sb);
                    sb.append(':');
                    write(v.values.get(i), sb);
                }
                sb.append('}');
                break;
            case ARRAY:
                sb.append('[');
                for (int i = 0; i < v.values.size(); i++) {
                    if (i > 0) {
                        sb.append(',');
                    }
                    write(v.values.get(i), sb);
                }
                sb.append(']');
                break;
            case STRING:
                quote(v.text, sb);
                break;
            default:
                sb.append(v.text);
        }
    }

    /** Indented text for files meant to be read by people (two spaces per level). */
    public static String pretty(Json v) {
        StringBuilder sb = new StringBuilder();
        pretty(v, sb, 0);
        return sb.append('\n').toString();
    }

    private static void pretty(Json v, StringBuilder sb, int indent) {
        boolean container = v.kind == Kind.OBJECT || v.kind == Kind.ARRAY;
        if (!container || v.values.isEmpty() || allScalar(v)) {
            write(v, sb);
            return;
        }
        sb.append(v.kind == Kind.OBJECT ? '{' : '[');
        for (int i = 0; i < v.values.size(); i++) {
            sb.append(i == 0 ? "\n" : ",\n");
            for (int k = 0; k <= indent; k++) {
                sb.append("  ");
            }
            if (v.kind == Kind.OBJECT) {
                quote(v.keys.get(i), sb);
                sb.append(": ");
            }
            pretty(v.values.get(i), sb, indent + 1);
        }
        sb.append('\n');
        for (int k = 0; k < indent; k++) {
            sb.append("  ");
        }
        sb.append(v.kind == Kind.OBJECT ? '}' : ']');
    }

    private static boolean allScalar(Json v) {
        if (v.kind != Kind.ARRAY || v.values.size() > 16) {
            return false;
        }
        for (Json e : v.values) {
            if (e.values != null) {
                return false;
            }
        }
        return true;
    }

    /** Appends {@code s} as a JSON string, escaped as Lean's {@code Json.compress} escapes. */
    public static void quote(String s, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append("\\u00").append(Character.forDigit(c >> 4, 16)).append(Character.forDigit(c & 15, 16));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }

    @Override
    public String toString() {
        return write(this);
    }
}

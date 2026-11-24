package rlvm.export;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tiny independent JSON parser for the tests: recursive descent over a {@code String}, producing plain
 * Java objects ({@code Map}, {@code List}, {@code String}, {@link Num}, {@code Boolean}, {@link #NULL}).
 * Written from the grammar of Lean's {@code Json.parse} (not from {@link JsonLine}), so that the two
 * can be compared.
 */
final class MiniJson {
    static final Object NULL = new Object() {
        @Override
        public String toString() {
            return "null";
        }
    };

    /** Lean's JsonNumber: mantissa * 10^-exponent. */
    record Num(BigInteger mantissa, BigInteger exponent) {
        boolean isNat() {
            return exponent.signum() == 0 && mantissa.signum() >= 0;
        }
    }

    static final class Bad extends Exception {
        private static final long serialVersionUID = 1L;

        Bad(String m) {
            super(m);
        }
    }

    private final String s;
    private int p;

    private MiniJson(String s) {
        this.s = s;
    }

    static Object parse(String text) throws Bad {
        MiniJson j = new MiniJson(text);
        j.ws();
        Object v = j.value();
        if (j.p != text.length()) {
            throw new Bad("trailing input");
        }
        return v;
    }

    private char peek() throws Bad {
        if (p >= s.length()) {
            throw new Bad("eof");
        }
        return s.charAt(p);
    }

    private void ws() {
        while (p < s.length()) {
            char c = s.charAt(p);
            if (c == ' ' || c == '\t' || c == '\r' || c == '\n') {
                p++;
            } else {
                break;
            }
        }
    }

    private void word(String w) throws Bad {
        if (!s.startsWith(w, p)) {
            throw new Bad("expected " + w);
        }
        p += w.length();
    }

    private Object value() throws Bad {
        char c = peek();
        if (c == '[') {
            p++;
            ws();
            List<Object> list = new ArrayList<>();
            if (peek() == ']') {
                p++;
                ws();
                return list;
            }
            while (true) {
                list.add(value());
                char d = peek();
                p++;
                if (d == ']') {
                    ws();
                    return list;
                }
                if (d != ',') {
                    throw new Bad("array");
                }
                ws();
            }
        }
        if (c == '{') {
            p++;
            ws();
            Map<String, Object> map = new LinkedHashMap<>();
            if (peek() == '}') {
                p++;
                ws();
                return map;
            }
            while (true) {
                if (peek() != '"') {
                    throw new Bad("key");
                }
                p++;
                String k = string();
                ws();
                if (map.containsKey(k)) {
                    throw new Bad("duplicate key");
                }
                if (peek() != ':') {
                    throw new Bad("colon");
                }
                p++;
                ws();
                map.put(k, value());
                char d = peek();
                p++;
                if (d == '}') {
                    ws();
                    return map;
                }
                if (d != ',') {
                    throw new Bad("object");
                }
                ws();
            }
        }
        if (c == '"') {
            p++;
            String str = string();
            ws();
            return str;
        }
        if (c == 'f') {
            word("false");
            ws();
            return Boolean.FALSE;
        }
        if (c == 't') {
            word("true");
            ws();
            return Boolean.TRUE;
        }
        if (c == 'n') {
            word("null");
            ws();
            return NULL;
        }
        if (c == '-' || (c >= '0' && c <= '9')) {
            Num n = number();
            ws();
            return n;
        }
        throw new Bad("unexpected input");
    }

    private int hex() throws Bad {
        char c = peek();
        p++;
        int v = Character.digit(c, 16);
        if (v < 0 || c > 'f') {
            throw new Bad("hex");
        }
        return v;
    }

    private String string() throws Bad {
        StringBuilder sb = new StringBuilder();
        while (true) {
            char c = peek();
            p++;
            if (c == '"') {
                return sb.toString();
            }
            if (c == '\\') {
                char e = peek();
                p++;
                switch (e) {
                    case '\\' -> sb.append('\\');
                    case '"' -> sb.append('"');
                    case '/' -> sb.append('/');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        int v = hex() << 12 | hex() << 8 | hex() << 4 | hex();
                        if (v < 0xD800 || v >= 0xE000) {
                            sb.append((char) v);
                        } else if (v < 0xDC00 && pairFollows()) {
                            int low = Integer.parseInt(s.substring(p + 2, p + 6), 16);
                            p += 6;
                            sb.appendCodePoint(Character.toCodePoint((char) v, (char) low));
                        } else {
                            sb.append('�');
                        }
                    }
                    default -> throw new Bad("escape");
                }
            } else if (c >= 0x20) {
                sb.append(c);
            } else {
                throw new Bad("control character");
            }
        }
    }

    /** Whether "\\uDC00".."\\uDFFF" follows at the current position. */
    private boolean pairFollows() {
        if (p + 6 > s.length() || s.charAt(p) != '\\' || s.charAt(p + 1) != 'u') {
            return false;
        }
        char d = s.charAt(p + 2);
        if (d != 'd' && d != 'D') {
            return false;
        }
        for (int i = 3; i < 6; i++) {
            char h = s.charAt(p + i);
            if (Character.digit(h, 16) < 0 || h > 'f') {
                return false;
            }
        }
        return Character.digit(s.charAt(p + 3), 16) >= 0xC;
    }

    private String digits() {
        int start = p;
        while (p < s.length() && s.charAt(p) >= '0' && s.charAt(p) <= '9') {
            p++;
        }
        return s.substring(start, p);
    }

    private Num number() throws Bad {
        boolean neg = false;
        if (peek() == '-') {
            neg = true;
            p++;
        }
        String whole;
        if (peek() == '0') {
            p++;
            whole = "0";
        } else {
            whole = digits();
            if (whole.isEmpty()) {
                throw new Bad("digit expected");
            }
        }
        BigInteger mantissa = new BigInteger(whole);
        BigInteger exponent = BigInteger.ZERO;
        if (p < s.length() && s.charAt(p) == '.') {
            p++;
            String frac = digits();
            if (frac.isEmpty()) {
                throw new Bad("fraction digits expected");
            }
            mantissa = new BigInteger(whole + frac);
            exponent = BigInteger.valueOf(frac.length());
        }
        if (neg) {
            mantissa = mantissa.negate();
        }
        if (p < s.length() && (s.charAt(p) == 'e' || s.charAt(p) == 'E')) {
            p++;
            char c = peek();
            if (c == '-') {
                p++;
                String d = digits();
                if (d.isEmpty()) {
                    throw new Bad("exponent digits expected");
                }
                exponent = exponent.add(new BigInteger(d)); // shiftr
            } else {
                if (c == '+') {
                    p++;
                }
                String d = digits();
                if (d.isEmpty()) {
                    throw new Bad("exponent digits expected");
                }
                BigInteger n = new BigInteger(d);
                if (n.compareTo(BigInteger.valueOf(100_000)) > 0) {
                    throw new Bad("exponent too large");
                }
                // shiftl: mantissa * 10^(n - e), exponent e - n (both truncated at 0)
                BigInteger up = n.subtract(exponent).max(BigInteger.ZERO);
                mantissa = mantissa.multiply(BigInteger.TEN.pow(up.intValueExact()));
                exponent = exponent.subtract(n).max(BigInteger.ZERO);
            }
        }
        return new Num(mantissa, exponent);
    }
}

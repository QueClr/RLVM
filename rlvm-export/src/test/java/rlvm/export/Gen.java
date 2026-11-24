package rlvm.export;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Random generator of well-formed exports for the differential tests.
 *
 * <p>Every generated file parses (ids are defined before use and bound once, all shapes are valid);
 * the kernel would of course reject the content. Lines are written either exactly like lean4export
 * writes them (so that the template fast path is exercised) or "wild": other key order, whitespace,
 * escapes, alternative number spellings, extra ignored members. Ids are dense and in order, or sparse
 * and out of order.
 */
final class Gen {
    private final Random rnd;
    private final StringBuilder out = new StringBuilder();
    private final List<String> names = new ArrayList<>();
    private final List<String> levels = new ArrayList<>();
    private final List<String> exprs = new ArrayList<>();
    private final Set<String> usedNames = new HashSet<>();
    private final Set<String> usedLevels = new HashSet<>();
    private final Set<String> usedExprs = new HashSet<>();
    private final boolean dense;
    private final double wild;
    private long nextName = 1;
    private long nextLevel = 1;
    private long nextExpr;
    private boolean declarations = true;
    int lines = 1;

    /**
     * @param dense ids 0, 1, 2, ... in order (like the exporter) or arbitrary
     * @param wild probability that a line is written in a non-canonical way
     */
    Gen(Random rnd, boolean dense, double wild) {
        this.rnd = rnd;
        this.dense = dense;
        this.wild = wild;
        names.add("0");
        usedNames.add("0");
        levels.add("0");
        usedLevels.add("0");
        out.append(T.META);
    }

    static byte[] file(Random rnd, int records, boolean dense, double wild) {
        return file(rnd, records, dense, wild, true);
    }

    /** @param declarations whether to generate declaration lines (their names are random, so they repeat) */
    static byte[] file(Random rnd, int records, boolean dense, double wild, boolean declarations) {
        Gen g = new Gen(rnd, dense, wild);
        g.declarations = declarations;
        for (int i = 0; i < records; i++) {
            g.record();
        }
        return g.out.toString().getBytes(StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------------------------------- ids

    private String fresh(Set<String> used, long next) {
        if (dense) {
            return Long.toString(next);
        }
        while (true) {
            long v = switch (rnd.nextInt(6)) {
                case 0 -> rnd.nextInt(50);
                case 1 -> rnd.nextInt(5000);
                case 2 -> 1_000_000_000L + rnd.nextInt(1000);
                case 3 -> Long.MAX_VALUE - rnd.nextInt(100);
                case 4 -> Math.abs(rnd.nextLong() % 1_000_000_000_000L);
                default -> next + rnd.nextInt(3);
            };
            String s = Long.toString(v);
            if (!used.contains(s)) {
                return s;
            }
        }
    }

    private String newName() {
        String id = fresh(usedNames, nextName++);
        usedNames.add(id);
        return id;
    }

    private String newLevel() {
        String id = fresh(usedLevels, nextLevel++);
        usedLevels.add(id);
        return id;
    }

    private String newExpr() {
        String id = fresh(usedExprs, nextExpr++);
        usedExprs.add(id);
        return id;
    }

    private String pick(List<String> ids) {
        // prefer recent ids, like real exports
        int n = ids.size();
        int i = rnd.nextBoolean() ? n - 1 - rnd.nextInt(Math.min(n, 4)) : rnd.nextInt(n);
        return ids.get(i);
    }

    private String nameList(int max) {
        int n = rnd.nextInt(max + 1);
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < n; i++) {
            sb.append(i == 0 ? "" : ",").append(pick(names));
        }
        return sb.append("]").toString();
    }

    // --------------------------------------------------------------------------------------- strings

    private static final String[] WORDS = {
        "a", "Nat", "succ", "_@", "_hyg", "motive", "α", "β₁", "x.y", "", "inst", "😀", "a b", "é€",
        "quote\"d", "back\\slash", "new\nline", "cr\rlf", "tab\there", "\u0001ctl", "del\u007f", "/slash"
    };

    private String text() {
        if (rnd.nextInt(10) == 0) {
            StringBuilder sb = new StringBuilder();
            int n = rnd.nextInt(40);
            for (int i = 0; i < n; i++) {
                sb.append((char) ('a' + rnd.nextInt(26)));
            }
            return sb.toString();
        }
        return WORDS[rnd.nextInt(WORDS.length)];
    }

    /** A JSON string literal the way lean4export writes it. */
    static String canonicalString(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    private String wildString(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            int r = rnd.nextInt(6);
            if (c == '"' || c == '\\' || c < 0x20 || r == 0) {
                switch (c) {
                    case '"' -> sb.append(r < 3 ? "\\\"" : "\\u0022");
                    case '\\' -> sb.append(r < 3 ? "\\\\" : "\\u005C");
                    case '\n' -> sb.append(r < 3 ? "\\n" : "\\u000a");
                    case '\r' -> sb.append(r < 3 ? "\\r" : "\\u000D");
                    case '\t' -> sb.append(r < 3 ? "\\t" : "\\u0009");
                    case '/' -> sb.append("\\/");
                    default -> sb.append(String.format(rnd.nextBoolean() ? "\\u%04x" : "\\u%04X", (int) c));
                }
            } else {
                sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    private String str(String s, boolean w) {
        return w ? wildString(s) : canonicalString(s);
    }

    // ---------------------------------------------------------------------------------------- output

    private String ws(boolean w) {
        if (!w || rnd.nextInt(3) != 0) {
            return "";
        }
        return switch (rnd.nextInt(4)) {
            case 0 -> " ";
            case 1 -> "\t";
            case 2 -> "\r";
            default -> "  ";
        };
    }

    /** A natural number, canonical or in one of the spellings Lean's JSON parser reads as the same Nat. */
    private String num(String n, boolean w) {
        if (!w || rnd.nextInt(4) != 0) {
            return n;
        }
        switch (rnd.nextInt(5)) {
            case 0:
                return n + "e0";
            case 1:
                return n + "E+0";
            case 2:
                return n.equals("0") ? "-0" : n + "e-0";
            case 3:
                // d.ddd e k
                if (n.length() > 1 && n.length() < 30) {
                    return n.charAt(0) + "." + n.substring(1) + "e" + (n.length() - 1);
                }
                return n;
            default:
                if (n.endsWith("0") && !n.equals("0")) {
                    return n.substring(0, n.length() - 1) + "e1";
                }
                return n;
        }
    }

    private String junk() {
        return switch (rnd.nextInt(6)) {
            case 0 -> "null";
            case 1 -> "[1,2.5,-3e-2,\"x\",true,false]";
            case 2 -> "{\"k\":{\"k\":[]}}";
            case 3 -> "\"\\ud83d\\ude00 \\ud800\"";
            case 4 -> "-12.5E+3";
            default -> "{}";
        };
    }

    /**
     * An object. {@code kv} holds key/value pairs with the keys in the exporter's (sorted) order;
     * values are already rendered.
     */
    private String obj(boolean w, boolean nested, String... kv) {
        int n = kv.length / 2;
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            order.add(i);
        }
        boolean extra = w && nested && rnd.nextInt(8) == 0;
        if (w && rnd.nextInt(3) == 0) {
            Collections.shuffle(order, rnd);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("{").append(ws(w));
        int extraAt = extra ? rnd.nextInt(n + 1) : -1;
        boolean first = true;
        for (int j = 0; j <= n; j++) {
            if (j == extraAt) {
                sb.append(first ? "" : ",").append(ws(w)).append("\"zz").append(rnd.nextInt(3)).append("\":").append(junk());
                first = false;
            }
            if (j == n) {
                break;
            }
            int i = order.get(j);
            String key = kv[2 * i];
            sb.append(first ? "" : ",").append(ws(w));
            sb.append(w && rnd.nextInt(10) == 0 ? wildString(key) : "\"" + key + "\"");
            sb.append(ws(w)).append(":").append(ws(w)).append(kv[2 * i + 1]).append(ws(w));
            first = false;
        }
        return sb.append("}").toString();
    }

    private void line(String text, boolean w) {
        out.append(w && rnd.nextInt(6) == 0 ? " " : "").append(text).append(w && rnd.nextInt(6) == 0 ? "\r" : "").append('\n');
        lines++;
    }

    private String list(String canonical, boolean w) {
        if (!w) {
            return canonical;
        }
        // "[1,2]" with optional whitespace and number spellings
        String inner = canonical.substring(1, canonical.length() - 1);
        if (inner.isEmpty()) {
            return "[" + ws(true) + "]";
        }
        StringBuilder sb = new StringBuilder("[");
        String[] parts = inner.split(",");
        for (int i = 0; i < parts.length; i++) {
            sb.append(i == 0 ? "" : ",").append(ws(true)).append(num(parts[i], true)).append(ws(true));
        }
        return sb.append("]").toString();
    }

    // --------------------------------------------------------------------------------------- records

    void record() {
        boolean w = rnd.nextDouble() < wild;
        int k = rnd.nextInt(100);
        if (exprs.isEmpty() || k < 3) {
            if (rnd.nextBoolean()) {
                String id = newExpr();
                line(obj(w, false, "bvar", num(Integer.toString(rnd.nextInt(5) == 0 ? rnd.nextInt((1 << 20) - 1) : rnd.nextInt(8)), w), "ie", num(id, w)), w);
                exprs.add(id);
            } else {
                String id = newExpr();
                line(obj(w, false, "ie", num(id, w), "sort", num(pick(levels), w)), w);
                exprs.add(id);
            }
        } else if (k < 50) {
            String id = newExpr();
            line(obj(w, false, "app", obj(w, true, "arg", num(pick(exprs), w), "fn", num(pick(exprs), w)), "ie", num(id, w)), w);
            exprs.add(id);
        } else if (k < 58) {
            String id = newExpr();
            line(obj(w, false, "ie", num(id, w), "lam", binder(w)), w);
            exprs.add(id);
        } else if (k < 64) {
            String id = newExpr();
            line(obj(w, false, "forallE", binder(w), "ie", num(id, w)), w);
            exprs.add(id);
        } else if (k < 67) {
            String id = newExpr();
            line(obj(w, false, "ie", num(id, w), "letE", obj(w, true, "body", num(pick(exprs), w), "name", num(pick(names), w),
                    "nondep", rnd.nextBoolean() ? "true" : "false", "type", num(pick(exprs), w), "value", num(pick(exprs), w))), w);
            exprs.add(id);
        } else if (k < 69) {
            String id = newExpr();
            String idx = rnd.nextInt(8) == 0 ? Long.toString(rnd.nextLong() & Long.MAX_VALUE) : Integer.toString(rnd.nextInt(5));
            line(obj(w, false, "ie", num(id, w), "proj", obj(w, true, "idx", num(idx, w), "struct", num(pick(exprs), w),
                    "typeName", num(pick(names), w))), w);
            exprs.add(id);
        } else if (k < 73) {
            String id = newExpr();
            int n = rnd.nextInt(4);
            StringBuilder us = new StringBuilder("[");
            for (int i = 0; i < n; i++) {
                us.append(i == 0 ? "" : ",").append(pick(levels));
            }
            us.append("]");
            line(obj(w, false, "const", obj(w, true, "name", num(pick(names), w), "us", list(us.toString(), w)), "ie", num(id, w)), w);
            exprs.add(id);
        } else if (k < 75) {
            String id = newExpr();
            String digits = switch (rnd.nextInt(4)) {
                case 0 -> "0";
                case 1 -> Integer.toString(rnd.nextInt(1000));
                case 2 -> "18446744073709551616";
                default -> "9".repeat(1 + rnd.nextInt(60));
            };
            if (w && rnd.nextBoolean()) {
                digits = (rnd.nextBoolean() ? "00" : "") + digits.charAt(0) + (digits.length() > 1 ? "_" + digits.substring(1) : "");
            }
            line(obj(w, false, "ie", num(id, w), "natVal", "\"" + digits + "\""), w);
            exprs.add(id);
        } else if (k < 77) {
            String id = newExpr();
            line(obj(w, false, "ie", num(id, w), "strVal", str(text(), w)), w);
            exprs.add(id);
        } else if (k < 78 && w) {
            String id = newExpr();
            line(obj(true, false, "ie", num(id, true), "mdata", obj(true, true, "data", "{" + (rnd.nextBoolean() ? "\"a\":[1,{}]" : "") + "}",
                    "expr", num(pick(exprs), true))), true);
            exprs.add(id);
        } else if (k < 86) {
            String id = newName();
            if (rnd.nextInt(3) == 0) {
                String i = switch (rnd.nextInt(5)) {
                    case 0 -> Integer.toString(rnd.nextInt(100));
                    case 1 -> "4283319217";
                    case 2 -> "999999999999999";
                    case 3 -> "1234567890123456";
                    default -> rnd.nextBoolean() ? "9223372036854775807" : "9223372036854775808";
                };
                line(obj(w, false, "in", num(id, w), "num", obj(w, true, "i", num(i, w), "pre", num(pick(names), w))), w);
            } else {
                line(obj(w, false, "in", num(id, w), "str", obj(w, true, "pre", num(pick(names), w), "str", str(text(), w))), w);
            }
            names.add(id);
        } else if (k < 90) {
            String id = newLevel();
            String body = switch (rnd.nextInt(4)) {
                case 0 -> obj(w, false, "il", num(id, w), "succ", num(pick(levels), w));
                case 1 -> obj(w, false, "il", num(id, w), "max", list("[" + pick(levels) + "," + pick(levels) + "]", w));
                case 2 -> obj(w, false, "il", num(id, w), "imax", list("[" + pick(levels) + "," + pick(levels) + "]", w));
                default -> obj(w, false, "il", num(id, w), "param", num(pick(names), w));
            };
            line(body, w);
            levels.add(id);
        } else if (declarations) {
            decl(w);
        } else {
            String id = newExpr();
            line(obj(w, false, "app", obj(w, true, "arg", num(pick(exprs), w), "fn", num(pick(exprs), w)), "ie", num(id, w)), w);
            exprs.add(id);
        }
    }

    private String binder(boolean w) {
        String[] infos = {"default", "implicit", "strictImplicit", "instImplicit"};
        return obj(w, true, "binderInfo", str(infos[rnd.nextInt(4)], w), "body", num(pick(exprs), w), "name", num(pick(names), w),
                "type", num(pick(exprs), w));
    }

    private String bool() {
        return rnd.nextBoolean() ? "true" : "false";
    }

    private String count(boolean w) {
        return num(rnd.nextInt(20) == 0 ? "9223372036854775807" : Integer.toString(rnd.nextInt(4)), w);
    }

    private void decl(boolean w) {
        switch (rnd.nextInt(12)) {
            case 0 -> line(obj(w, false, "axiom", obj(w, true, "isUnsafe", bool(), "levelParams", list(nameList(2), w), "name", num(pick(names), w),
                    "type", num(pick(exprs), w))), w);
            case 1, 2, 3 -> {
                String hints = switch (rnd.nextInt(3)) {
                    case 0 -> "\"abbrev\"";
                    case 1 -> "\"opaque\"";
                    default -> obj(w, true, "regular", num(rnd.nextInt(6) == 0 ? "4294967297" : Integer.toString(rnd.nextInt(40)), w));
                };
                String safety = rnd.nextInt(6) == 0 ? (rnd.nextBoolean() ? "unsafe" : "partial") : "safe";
                line(obj(w, false, "def", obj(w, true, "all", list(nameList(2), w), "hints", hints, "levelParams", list(nameList(3), w),
                        "name", num(pick(names), w), "safety", str(safety, w), "type", num(pick(exprs), w), "value", num(pick(exprs), w))), w);
            }
            case 4, 5, 6, 7 -> line(obj(w, false, "thm", obj(w, true, "all", list(nameList(2), w), "levelParams", list(nameList(3), w),
                    "name", num(pick(names), w), "type", num(pick(exprs), w), "value", num(pick(exprs), w))), w);
            case 8 -> {
                if (rnd.nextBoolean()) {
                    line(obj(w, false, "opaque", obj(w, true, "all", list(nameList(2), w), "isUnsafe", bool(), "levelParams", list(nameList(2), w),
                            "name", num(pick(names), w), "type", num(pick(exprs), w), "value", num(pick(exprs), w))), w);
                } else {
                    line(obj(w, false, "opaque", obj(w, true, "all", list(nameList(2), w), "levelParams", list(nameList(2), w),
                            "name", num(pick(names), w), "type", num(pick(exprs), w), "value", num(pick(exprs), w))), w);
                }
            }
            case 9 -> {
                String[] kinds = {"type", "ctor", "lift", "ind"};
                line(obj(w, false, "quot", obj(w, true, "kind", str(kinds[rnd.nextInt(4)], w), "levelParams", list(nameList(2), w),
                        "name", num(pick(names), w), "type", num(pick(exprs), w))), w);
            }
            default -> {
                StringBuilder ctors = new StringBuilder("[");
                int nc = rnd.nextInt(3);
                for (int i = 0; i < nc; i++) {
                    ctors.append(i == 0 ? "" : ",").append(obj(w, true, "cidx", count(w), "induct", num(pick(names), w), "isUnsafe", bool(),
                            "levelParams", list(nameList(2), w), "name", num(pick(names), w), "numFields", count(w), "numParams", count(w),
                            "type", num(pick(exprs), w)));
                }
                ctors.append("]");
                StringBuilder recs = new StringBuilder("[");
                int nr = rnd.nextInt(3);
                for (int i = 0; i < nr; i++) {
                    StringBuilder rules = new StringBuilder("[");
                    int nrules = rnd.nextInt(3);
                    for (int j = 0; j < nrules; j++) {
                        rules.append(j == 0 ? "" : ",").append(obj(w, true, "ctor", num(pick(names), w), "nfields", count(w),
                                "rhs", num(pick(exprs), w)));
                    }
                    rules.append("]");
                    recs.append(i == 0 ? "" : ",").append(obj(w, true, "all", list(nameList(2), w), "isUnsafe", bool(), "k", bool(),
                            "levelParams", list(nameList(2), w), "name", num(pick(names), w), "numIndices", count(w), "numMinors", count(w),
                            "numMotives", count(w), "numParams", count(w), "rules", rules.toString(), "type", num(pick(exprs), w)));
                }
                recs.append("]");
                StringBuilder types = new StringBuilder("[");
                int nt = rnd.nextInt(3);
                for (int i = 0; i < nt; i++) {
                    types.append(i == 0 ? "" : ",").append(obj(w, true, "all", list(nameList(2), w), "ctors", list(nameList(3), w), "isRec", bool(),
                            "isReflexive", bool(), "isUnsafe", bool(), "levelParams", list(nameList(2), w), "name", num(pick(names), w),
                            "numIndices", count(w), "numNested", count(w), "numParams", count(w), "type", num(pick(exprs), w)));
                }
                types.append("]");
                line(obj(w, false, "inductive", obj(w, true, "ctors", ctors.toString(), "recs", recs.toString(), "types", types.toString())), w);
            }
        }
    }

    // ------------------------------------------------------------------------------------- mutations

    private static final byte[] INTERESTING = {
        '"', '\\', '{', '}', '[', ']', ',', ':', '0', '1', '9', '\n', ' ', '\r', '\t', 'e', '.', '-', '_', 'u', 'a',
        (byte) 0xFF, (byte) 0xC3, (byte) 0x80, 0, 0x7F
    };

    /** A copy of {@code data} with one random small change. */
    static byte[] mutate(Random rnd, byte[] data) {
        if (data.length == 0) {
            return data;
        }
        int at = rnd.nextInt(data.length);
        switch (rnd.nextInt(9)) {
            case 0: { // replace a byte
                byte[] r = data.clone();
                r[at] = INTERESTING[rnd.nextInt(INTERESTING.length)];
                return r;
            }
            case 1: { // delete a byte
                byte[] r = new byte[data.length - 1];
                System.arraycopy(data, 0, r, 0, at);
                System.arraycopy(data, at + 1, r, at, data.length - at - 1);
                return r;
            }
            case 2: { // insert a byte
                byte[] r = new byte[data.length + 1];
                System.arraycopy(data, 0, r, 0, at);
                r[at] = INTERESTING[rnd.nextInt(INTERESTING.length)];
                System.arraycopy(data, at, r, at + 1, data.length - at);
                return r;
            }
            case 3: { // truncate
                return java.util.Arrays.copyOf(data, at);
            }
            case 4: { // change a digit
                for (int i = at; i < data.length; i++) {
                    if (data[i] >= '0' && data[i] <= '9') {
                        byte[] r = data.clone();
                        r[i] = (byte) ('0' + rnd.nextInt(10));
                        return r;
                    }
                }
                return data;
            }
            default: { // line operations: duplicate, delete, swap with the next
                int ls = at;
                while (ls > 0 && data[ls - 1] != '\n') {
                    ls--;
                }
                int le = at;
                while (le < data.length && data[le] != '\n') {
                    le++;
                }
                le = Math.min(data.length, le + 1);
                int op = rnd.nextInt(3);
                java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
                o.write(data, 0, ls);
                if (op == 0) {
                    o.write(data, ls, le - ls);
                    o.write(data, ls, le - ls);
                    o.write(data, le, data.length - le);
                } else if (op == 1) {
                    o.write(data, le, data.length - le);
                } else {
                    int ne = le;
                    while (ne < data.length && data[ne] != '\n') {
                        ne++;
                    }
                    ne = Math.min(data.length, ne + 1);
                    o.write(data, le, ne - le);
                    o.write(data, ls, le - ls);
                    o.write(data, ne, data.length - ne);
                }
                return o.toByteArray();
            }
        }
    }
}

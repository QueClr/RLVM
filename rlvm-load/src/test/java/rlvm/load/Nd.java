package rlvm.load;

import java.nio.charset.StandardCharsets;

/** Writes small lean4export NDJSON files for tests, in the exporter's canonical byte shapes. */
final class Nd {
    static final String META =
            "{\"meta\":{\"exporter\":{\"name\":\"lean4export\",\"version\":\"3.1.0\"},\"format\":{\"version\":\"3.1.0\"},"
                    + "\"lean\":{\"githash\":\"test\",\"version\":\"4.36.0\"}}}";

    final StringBuilder sb = new StringBuilder();
    int names;
    int levels;
    int exprs = -1;

    Nd() {
        this(META);
    }

    /** {@code header == null}: no header line at all. */
    Nd(String header) {
        if (header != null) {
            sb.append(header).append('\n');
        }
    }

    byte[] bytes() {
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    Nd raw(String line) {
        sb.append(line).append('\n');
        return this;
    }

    private static String list(int... xs) {
        StringBuilder s = new StringBuilder("[");
        for (int i = 0; i < xs.length; i++) {
            if (i > 0) {
                s.append(',');
            }
            s.append(xs[i]);
        }
        return s.append(']').toString();
    }

    private static String quote(String s) {
        StringBuilder q = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> q.append("\\\"");
                case '\\' -> q.append("\\\\");
                case '\n' -> q.append("\\n");
                case '\r' -> q.append("\\r");
                default -> {
                    if (c < 0x20) {
                        q.append(String.format("\\u%04x", (int) c));
                    } else {
                        q.append(c);
                    }
                }
            }
        }
        return q.append('"').toString();
    }

    // ---- names, levels -------------------------------------------------------------------------------

    int str(int pre, String s) {
        int id = ++names;
        raw("{\"in\":" + id + ",\"str\":{\"pre\":" + pre + ",\"str\":" + quote(s) + "}}");
        return id;
    }

    /** A dotted name made of string components; returns the id of the full name. */
    int name(String dotted) {
        int id = 0;
        for (String part : dotted.split("\\.")) {
            id = str(id, part);
        }
        return id;
    }

    int num(int pre, String i) {
        int id = ++names;
        raw("{\"in\":" + id + ",\"num\":{\"i\":" + i + ",\"pre\":" + pre + "}}");
        return id;
    }

    int succ(int l) {
        int id = ++levels;
        raw("{\"il\":" + id + ",\"succ\":" + l + "}");
        return id;
    }

    int max(int a, int b) {
        int id = ++levels;
        raw("{\"il\":" + id + ",\"max\":[" + a + "," + b + "]}");
        return id;
    }

    int imax(int a, int b) {
        int id = ++levels;
        raw("{\"il\":" + id + ",\"imax\":[" + a + "," + b + "]}");
        return id;
    }

    int param(int name) {
        int id = ++levels;
        raw("{\"il\":" + id + ",\"param\":" + name + "}");
        return id;
    }

    // ---- expressions ---------------------------------------------------------------------------------

    int bvar(int i) {
        int id = ++exprs;
        raw("{\"bvar\":" + i + ",\"ie\":" + id + "}");
        return id;
    }

    int sort(int level) {
        int id = ++exprs;
        raw("{\"ie\":" + id + ",\"sort\":" + level + "}");
        return id;
    }

    int cnst(int name, int... us) {
        int id = ++exprs;
        raw("{\"const\":{\"name\":" + name + ",\"us\":" + list(us) + "},\"ie\":" + id + "}");
        return id;
    }

    int app(int fn, int arg) {
        int id = ++exprs;
        raw("{\"app\":{\"arg\":" + arg + ",\"fn\":" + fn + "},\"ie\":" + id + "}");
        return id;
    }

    int lam(int type, int body) {
        int id = ++exprs;
        raw("{\"ie\":" + id + ",\"lam\":{\"binderInfo\":\"default\",\"body\":" + body + ",\"name\":0,\"type\":" + type + "}}");
        return id;
    }

    int pi(int type, int body) {
        int id = ++exprs;
        raw("{\"forallE\":{\"binderInfo\":\"default\",\"body\":" + body + ",\"name\":0,\"type\":" + type + "},\"ie\":" + id + "}");
        return id;
    }

    int let(int type, int value, int body) {
        return let(type, value, body, false);
    }

    int let(int type, int value, int body, boolean nondep) {
        int id = ++exprs;
        raw("{\"ie\":" + id + ",\"letE\":{\"body\":" + body + ",\"name\":0,\"nondep\":" + nondep + ",\"type\":" + type
                + ",\"value\":" + value + "}}");
        return id;
    }

    int proj(int typeName, String idx, int struct) {
        int id = ++exprs;
        raw("{\"ie\":" + id + ",\"proj\":{\"idx\":" + idx + ",\"struct\":" + struct + ",\"typeName\":" + typeName + "}}");
        return id;
    }

    int nat(String digits) {
        int id = ++exprs;
        raw("{\"ie\":" + id + ",\"natVal\":\"" + digits + "\"}");
        return id;
    }

    int strLit(String s) {
        int id = ++exprs;
        raw("{\"ie\":" + id + ",\"strVal\":" + quote(s) + "}");
        return id;
    }

    int mdata(int expr) {
        int id = ++exprs;
        raw("{\"ie\":" + id + ",\"mdata\":{\"data\":{},\"expr\":" + expr + "}}");
        return id;
    }

    // ---- declarations --------------------------------------------------------------------------------

    Nd axiom(int name, int type, boolean isUnsafe, int... lps) {
        return raw("{\"axiom\":{\"isUnsafe\":" + isUnsafe + ",\"levelParams\":" + list(lps) + ",\"name\":" + name + ",\"type\":"
                + type + "}}");
    }

    Nd axiom(int name, int type, int... lps) {
        return axiom(name, type, false, lps);
    }

    /** @param hints JSON of the hint: {@code "\"abbrev\""}, {@code "\"opaque\""}, {@code "{\"regular\":3}"} */
    Nd def(int name, int type, int value, String hints, String safety, int... lps) {
        return raw("{\"def\":{\"all\":[" + name + "],\"hints\":" + hints + ",\"levelParams\":" + list(lps) + ",\"name\":" + name
                + ",\"safety\":\"" + safety + "\",\"type\":" + type + ",\"value\":" + value + "}}");
    }

    Nd def(int name, int type, int value, int... lps) {
        return def(name, type, value, "{\"regular\":1}", "safe", lps);
    }

    Nd thm(int name, int type, int value, int... lps) {
        return raw("{\"thm\":{\"all\":[" + name + "],\"levelParams\":" + list(lps) + ",\"name\":" + name + ",\"type\":" + type
                + ",\"value\":" + value + "}}");
    }

    Nd opaque(int name, int type, int value, boolean isUnsafe, int... lps) {
        return raw("{\"opaque\":{\"all\":[" + name + "],\"isUnsafe\":" + isUnsafe + ",\"levelParams\":" + list(lps) + ",\"name\":"
                + name + ",\"type\":" + type + ",\"value\":" + value + "}}");
    }

    Nd quot(int name, int type, String kind, int... lps) {
        return raw("{\"quot\":{\"kind\":\"" + kind + "\",\"levelParams\":" + list(lps) + ",\"name\":" + name + ",\"type\":" + type
                + "}}");
    }

    static String type(int name, int type, int numParams, int numIndices, int[] all, int[] ctors, boolean isUnsafe,
            int... lps) {
        return "{\"all\":" + list(all) + ",\"ctors\":" + list(ctors) + ",\"isRec\":false,\"isReflexive\":false,\"isUnsafe\":"
                + isUnsafe + ",\"levelParams\":" + list(lps) + ",\"name\":" + name + ",\"numIndices\":" + numIndices
                + ",\"numNested\":0,\"numParams\":" + numParams + ",\"type\":" + type + "}";
    }

    static String ctor(int name, int type, int induct, int cidx, int numParams, int numFields, boolean isUnsafe, int... lps) {
        return "{\"cidx\":" + cidx + ",\"induct\":" + induct + ",\"isUnsafe\":" + isUnsafe + ",\"levelParams\":" + list(lps)
                + ",\"name\":" + name + ",\"numFields\":" + numFields + ",\"numParams\":" + numParams + ",\"type\":" + type + "}";
    }

    /** @param rules triples (ctor name, nfields, rhs) */
    static String rec(int name, int type, int[] all, int numParams, int numIndices, int numMotives, int numMinors, boolean k,
            boolean isUnsafe, int[] rules, int... lps) {
        StringBuilder r = new StringBuilder("[");
        for (int i = 0; i < rules.length; i += 3) {
            if (i > 0) {
                r.append(',');
            }
            r.append("{\"ctor\":").append(rules[i]).append(",\"nfields\":").append(rules[i + 1]).append(",\"rhs\":")
                    .append(rules[i + 2]).append('}');
        }
        r.append(']');
        return "{\"all\":" + list(all) + ",\"isUnsafe\":" + isUnsafe + ",\"k\":" + k + ",\"levelParams\":" + list(lps)
                + ",\"name\":" + name + ",\"numIndices\":" + numIndices + ",\"numMinors\":" + numMinors + ",\"numMotives\":"
                + numMotives + ",\"numParams\":" + numParams + ",\"rules\":" + r + ",\"type\":" + type + "}";
    }

    Nd inductive(String[] types, String[] ctors, String[] recs) {
        return raw("{\"inductive\":{\"ctors\":[" + String.join(",", ctors) + "],\"recs\":[" + String.join(",", recs)
                + "],\"types\":[" + String.join(",", types) + "]}}");
    }

    static int[] ints(int... xs) {
        return xs;
    }

    static String[] strs(String... xs) {
        return xs;
    }
}

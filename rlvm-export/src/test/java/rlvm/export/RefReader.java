package rlvm.export;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Slow reference reader for the tests: a direct transliteration of the official reader
 * ({@code LeanExport/Parse.lean}, Lean v4.36.0) on top of {@link MiniJson}, with objects, strings,
 * {@code BigInteger}s and hash maps everywhere. It shares no code with {@link ExportReader}.
 *
 * <p>It makes the same sink calls in the same order as {@link ExportReader} and fails at the same
 * line with the same {@link ExportException.Kind}.
 */
final class RefReader {
    static final class Outcome {
        boolean ok;
        ExportException.Kind kind;
        long line;
        String message;

        @Override
        public String toString() {
            return ok ? "ok" : kind + " at line " + line + ": " + message;
        }
    }

    private static final class Fail extends Exception {
        private static final long serialVersionUID = 1L;
        final ExportException.Kind kind;

        Fail(ExportException.Kind kind, String m) {
            super(m);
            this.kind = kind;
        }
    }

    private static final BigInteger TWO63 = BigInteger.ONE.shiftLeft(63);

    private final ExportSink sink;
    private final Map<BigInteger, Integer> names = new HashMap<>();
    private final Map<BigInteger, Integer> levels = new HashMap<>();
    private final Map<BigInteger, Integer> exprs = new HashMap<>();

    private RefReader(ExportSink sink) {
        this.sink = sink;
    }

    static Outcome read(byte[] data, ExportSink sink) {
        Outcome out = new Outcome();
        RefReader r = new RefReader(sink);
        long line = 0;
        try {
            r.names.put(BigInteger.ZERO, sink.nameAnonymous());
            r.levels.put(BigInteger.ZERO, sink.levelZero());
            List<byte[]> lines = split(data);
            if (lines.isEmpty()) {
                sink.meta(false, null, null, null, null, null);
            }
            for (byte[] l : lines) {
                line++;
                if (line == 1) {
                    r.meta(l);
                } else {
                    r.item(l);
                }
            }
            out.ok = true;
        } catch (Fail f) {
            out.kind = f.kind;
            out.line = line;
            out.message = f.getMessage();
        } catch (ExportException e) {
            out.kind = e.kind();
            out.line = line;
            out.message = e.reason();
        }
        return out;
    }

    private static List<byte[]> split(byte[] data) {
        List<byte[]> lines = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < data.length; i++) {
            if (data[i] == '\n') {
                lines.add(java.util.Arrays.copyOfRange(data, start, i + 1));
                start = i + 1;
            }
        }
        if (start < data.length) {
            lines.add(java.util.Arrays.copyOfRange(data, start, data.length));
        }
        return lines;
    }

    private static String decode(byte[] line) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(line)).toString();
    }

    private static Fail bad(String m) {
        return new Fail(ExportException.Kind.MALFORMED, m);
    }

    private static Fail unsupported(String m) {
        return new Fail(ExportException.Kind.UNSUPPORTED, m);
    }

    // ---------------------------------------------------------------------------------------- header

    @SuppressWarnings("unchecked")
    private void meta(byte[] line) throws ExportException {
        try {
            Object v = MiniJson.parse(decode(line));
            if (v instanceof Map<?, ?> root && root.get("meta") instanceof Map<?, ?> meta) {
                String[] s = new String[5];
                if (meta.get("exporter") instanceof Map<?, ?> e) {
                    s[0] = e.get("name") instanceof String x ? x : null;
                    s[1] = e.get("version") instanceof String x ? x : null;
                }
                if (meta.get("format") instanceof Map<?, ?> f) {
                    s[2] = f.get("version") instanceof String x ? x : null;
                }
                if (meta.get("lean") instanceof Map<?, ?> l) {
                    s[3] = l.get("githash") instanceof String x ? x : null;
                    s[4] = l.get("version") instanceof String x ? x : null;
                }
                sink.meta(true, s[0], s[1], s[2], s[3], s[4]);
                return;
            }
        } catch (MiniJson.Bad | CharacterCodingException e) {
            // not well-formed
        }
        sink.meta(false, null, null, null, null, null);
    }

    // ----------------------------------------------------------------------------------------- items

    private static BigInteger nat(Object o, String err) throws Fail {
        if (o instanceof MiniJson.Num n && n.isNat()) {
            return n.mantissa();
        }
        throw bad(err);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> obj(Object o, String err) throws Fail {
        if (o instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        throw bad(err);
    }

    @SuppressWarnings("unchecked")
    private static List<Object> arr(Object o, String err) throws Fail {
        if (o instanceof List<?> l) {
            return (List<Object>) l;
        }
        throw bad(err);
    }

    private static String str(Object o, String err) throws Fail {
        if (o instanceof String s) {
            return s;
        }
        throw bad(err);
    }

    private static boolean bool(Object o, String err) throws Fail {
        if (o instanceof Boolean b) {
            return b;
        }
        throw bad(err);
    }

    private static int get(Map<BigInteger, Integer> map, BigInteger id, String what) throws Fail {
        Integer v = map.get(id);
        if (v == null) {
            throw bad(what + " not found " + id);
        }
        return v;
    }

    private static void unbound(Map<BigInteger, Integer> map, BigInteger id, String what) throws Fail {
        if (map.containsKey(id)) {
            throw bad(what + " index " + id + " bound twice");
        }
    }

    private static void bind(Map<BigInteger, Integer> map, BigInteger id, int v, String what) throws Fail {
        if (map.containsKey(id)) {
            throw bad(what + " index " + id + " bound twice");
        }
        map.put(id, v);
    }

    private static long count(BigInteger v) {
        return v.compareTo(TWO63) >= 0 ? -1 : v.longValueExact();
    }

    private int[] nameList(List<Object> l) throws Fail {
        int[] out = new int[l.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = get(names, nat(l.get(i), "failed to convert to name idx"), "Name");
        }
        return out;
    }

    private void item(byte[] line) throws Fail, ExportException {
        String text;
        try {
            text = decode(line);
        } catch (CharacterCodingException e) {
            throw unsupported("ill-formed UTF-8");
        }
        Object v;
        try {
            v = MiniJson.parse(text);
        } catch (MiniJson.Bad e) {
            throw bad("Invalid JSON: " + e.getMessage());
        }
        Map<String, Object> o = obj(v, "Expected JSON object");
        if (o.size() == 2) {
            for (String idKey : new String[] {"in", "il", "ie"}) {
                if (o.containsKey(idKey)) {
                    Object idv = o.get(idKey);
                    if (!(idv instanceof MiniJson.Num n) || !n.isNat()) {
                        break;
                    }
                    BigInteger id = n.mantissa();
                    String other = null;
                    for (String k : o.keySet()) {
                        if (!k.equals(idKey)) {
                            other = k;
                        }
                    }
                    Object data = o.get(other);
                    boolean handled = switch (idKey) {
                        case "in" -> name(id, other, data);
                        case "il" -> level(id, other, data);
                        default -> expr(id, other, data);
                    };
                    if (handled) {
                        return;
                    }
                    break;
                }
            }
        } else if (o.size() == 1) {
            String k = o.keySet().iterator().next();
            if (o.get(k) instanceof Map<?, ?>) {
                Map<String, Object> d = obj(o.get(k), "");
                switch (k) {
                    case "axiom" -> {
                        axiom(d);
                        return;
                    }
                    case "def" -> {
                        def(d);
                        return;
                    }
                    case "thm" -> {
                        thm(d);
                        return;
                    }
                    case "opaque" -> {
                        opaque(d);
                        return;
                    }
                    case "quot" -> {
                        quot(d);
                        return;
                    }
                    case "inductive" -> {
                        inductive(d);
                        return;
                    }
                    default -> {
                    }
                }
            }
        }
        throw bad("Unknown export object with keys " + o.keySet());
    }

    private boolean name(BigInteger id, String key, Object data) throws Fail, ExportException {
        if (key.equals("str")) {
            unbound(names, id, "Name");
            Map<String, Object> d = obj(data, "Name.str invalid");
            BigInteger pre = nat(d.get("pre"), "Name.str invalid");
            byte[] s = str(d.get("str"), "Name.str invalid").getBytes(StandardCharsets.UTF_8);
            int p = get(names, pre, "Name");
            bind(names, id, sink.nameStr(p, s, 0, s.length), "Name");
            return true;
        }
        if (key.equals("num")) {
            unbound(names, id, "Name");
            Map<String, Object> d = obj(data, "Name.num invalid");
            BigInteger pre = nat(d.get("pre"), "Name.num invalid");
            BigInteger i = nat(d.get("i"), "Name.num invalid");
            int p = get(names, pre, "Name");
            int r;
            if (i.compareTo(TWO63) < 0) {
                r = sink.nameNum(p, i.longValueExact());
            } else {
                byte[] digits = i.toString().getBytes(StandardCharsets.US_ASCII);
                r = sink.nameNumBig(p, digits, 0, digits.length);
            }
            bind(names, id, r, "Name");
            return true;
        }
        return false;
    }

    private boolean level(BigInteger id, String key, Object data) throws Fail, ExportException {
        int r;
        switch (key) {
            case "succ" -> {
                unbound(levels, id, "Level");
                r = sink.levelSucc(get(levels, nat(data, "Level.succ invalid"), "Level"));
            }
            case "max", "imax" -> {
                unbound(levels, id, "Level");
                String err = "Level." + key + " invalid";
                List<Object> l = arr(data, err);
                if (l.size() != 2) {
                    throw bad(err);
                }
                BigInteger a = nat(l.get(0), err);
                BigInteger b = nat(l.get(1), err);
                int x = get(levels, a, "Level");
                int y = get(levels, b, "Level");
                r = key.equals("max") ? sink.levelMax(x, y) : sink.levelIMax(x, y);
            }
            case "param" -> {
                unbound(levels, id, "Level");
                r = sink.levelParam(get(names, nat(data, "Level.param invalid"), "Name"));
            }
            default -> {
                return false;
            }
        }
        bind(levels, id, r, "Level");
        return true;
    }

    private static int binderInfo(String s) throws Fail {
        return switch (s) {
            case "default" -> ExportSink.BINDER_DEFAULT;
            case "implicit" -> ExportSink.BINDER_IMPLICIT;
            case "strictImplicit" -> ExportSink.BINDER_STRICT_IMPLICIT;
            case "instImplicit" -> ExportSink.BINDER_INST_IMPLICIT;
            default -> throw bad("Invalid binder info: " + s);
        };
    }

    private boolean expr(BigInteger id, String key, Object data) throws Fail, ExportException {
        int r;
        switch (key) {
            case "app" -> {
                unbound(exprs, id, "Expr");
                Map<String, Object> d = obj(data, "Expr.app invalid");
                BigInteger fn = nat(d.get("fn"), "Expr.app invalid");
                BigInteger arg = nat(d.get("arg"), "Expr.app invalid");
                int f = get(exprs, fn, "Expr");
                int a = get(exprs, arg, "Expr");
                r = sink.exprApp(f, a);
            }
            case "lam", "forallE" -> {
                unbound(exprs, id, "Expr");
                String err = "Expr." + key + " invalid";
                Map<String, Object> d = obj(data, err);
                BigInteger name = nat(d.get("name"), err);
                BigInteger type = nat(d.get("type"), err);
                BigInteger body = nat(d.get("body"), err);
                String bi = str(d.get("binderInfo"), err);
                int n = get(names, name, "Name");
                int t = get(exprs, type, "Expr");
                int b = get(exprs, body, "Expr");
                int info = binderInfo(bi);
                r = key.equals("lam") ? sink.exprLam(n, info, t, b) : sink.exprForall(n, info, t, b);
            }
            case "const" -> {
                unbound(exprs, id, "Expr");
                Map<String, Object> d = obj(data, "Expr.const invalid");
                BigInteger name = nat(d.get("name"), "Expr.const invalid");
                List<Object> us = arr(d.get("us"), "Expr.const invalid");
                int n = get(names, name, "Name");
                int[] ls = new int[us.size()];
                for (int i = 0; i < ls.length; i++) {
                    ls[i] = get(levels, nat(us.get(i), "Expr.const invalid"), "Level");
                }
                r = sink.exprConst(n, ls, 0, ls.length);
            }
            case "letE" -> {
                unbound(exprs, id, "Expr");
                Map<String, Object> d = obj(data, "Expr.letE invalid");
                BigInteger name = nat(d.get("name"), "Expr.letE invalid");
                BigInteger type = nat(d.get("type"), "Expr.letE invalid");
                BigInteger value = nat(d.get("value"), "Expr.letE invalid");
                BigInteger body = nat(d.get("body"), "Expr.letE invalid");
                boolean nondep = bool(d.get("nondep"), "Expr.letE invalid");
                int n = get(names, name, "Name");
                int t = get(exprs, type, "Expr");
                int v = get(exprs, value, "Expr");
                int b = get(exprs, body, "Expr");
                r = sink.exprLet(n, t, v, b, nondep);
            }
            case "proj" -> {
                unbound(exprs, id, "Expr");
                Map<String, Object> d = obj(data, "Expr.proj invalid");
                BigInteger typeName = nat(d.get("typeName"), "Expr.proj invalid");
                BigInteger idx = nat(d.get("idx"), "Expr.proj invalid");
                BigInteger struct = nat(d.get("struct"), "Expr.proj invalid");
                int n = get(names, typeName, "Name");
                int s = get(exprs, struct, "Expr");
                if (idx.compareTo(TWO63) >= 0) {
                    throw unsupported("proj index");
                }
                r = sink.exprProj(n, idx.longValueExact(), s);
            }
            case "bvar" -> {
                unbound(exprs, id, "Expr");
                BigInteger idx = nat(data, "Expr.bvar invalid");
                if (idx.compareTo(BigInteger.valueOf((1 << 20) - 2)) > 0) {
                    throw bad("too many bound variables");
                }
                r = sink.exprBVar(idx.intValueExact());
            }
            case "sort" -> {
                unbound(exprs, id, "Expr");
                r = sink.exprSort(get(levels, nat(data, "Expr.sort invalid"), "Level"));
            }
            case "natVal" -> {
                unbound(exprs, id, "Expr");
                String s = str(data, "Expr.lit natVal invalid");
                // String.toNat?: digits with single underscores between/after digits, ending in a digit
                if (!s.matches("[0-9](_?[0-9])*")) {
                    throw bad("Expr.lit natVal invalid");
                }
                byte[] digits = new BigInteger(s.replace("_", "")).toString().getBytes(StandardCharsets.US_ASCII);
                r = sink.exprNatLit(digits, 0, digits.length);
            }
            case "strVal" -> {
                unbound(exprs, id, "Expr");
                byte[] s = str(data, "Expr.lit strVal invalid").getBytes(StandardCharsets.UTF_8);
                r = sink.exprStrLit(s, 0, s.length);
            }
            case "mdata" -> {
                unbound(exprs, id, "Expr");
                Map<String, Object> d = obj(data, "Expr.mdata invalid");
                BigInteger inner = nat(d.get("expr"), "Expr.mdata invalid");
                obj(d.get("data"), "Expr.mdata invalid");
                r = sink.exprMData(get(exprs, inner, "Expr"));
            }
            default -> {
                return false;
            }
        }
        bind(exprs, id, r, "Expr");
        return true;
    }

    // ---------------------------------------------------------------------------------- declarations

    private static int[] concat(int[]... lists) {
        int n = 0;
        for (int[] l : lists) {
            n += l.length;
        }
        int[] out = new int[n + 3];
        int p = 3; // an arbitrary offset: sinks must honour the offsets they are given
        for (int[] l : lists) {
            System.arraycopy(l, 0, out, p, l.length);
            p += l.length;
        }
        return out;
    }

    private void axiom(Map<String, Object> d) throws Fail, ExportException {
        BigInteger name = nat(d.get("name"), "axiomInfo invalid");
        List<Object> lp = arr(d.get("levelParams"), "axiomInfo invalid");
        BigInteger type = nat(d.get("type"), "axiomInfo invalid");
        boolean isUnsafe = bool(d.get("isUnsafe"), "axiomInfo invalid");
        int n = get(names, name, "Name");
        int[] lps = nameList(lp);
        int t = get(exprs, type, "Expr");
        sink.declAxiom(n, concat(lps), 3, lps.length, t, isUnsafe);
    }

    private void def(Map<String, Object> d) throws Fail, ExportException {
        BigInteger name = nat(d.get("name"), "defnInfo invalid");
        List<Object> lp = arr(d.get("levelParams"), "defnInfo invalid");
        BigInteger type = nat(d.get("type"), "defnInfo invalid");
        BigInteger value = nat(d.get("value"), "defnInfo invalid");
        if (!d.containsKey("hints")) {
            throw bad("defnInfo invalid");
        }
        Object hints = d.get("hints");
        String safetyStr = str(d.get("safety"), "defnInfo invalid");
        List<Object> all = arr(d.get("all"), "defnInfo invalid");
        int n = get(names, name, "Name");
        int[] lps = nameList(lp);
        int t = get(exprs, type, "Expr");
        int v = get(exprs, value, "Expr");
        int hintKind;
        int height = 0;
        if ("opaque".equals(hints)) {
            hintKind = ExportSink.HINT_OPAQUE;
        } else if ("abbrev".equals(hints)) {
            hintKind = ExportSink.HINT_ABBREV;
        } else if (hints instanceof Map<?, ?> h) {
            hintKind = ExportSink.HINT_REGULAR;
            height = nat(h.get("regular"), "defnInfo invalid").intValue(); // low 32 bits
        } else {
            throw bad("defnInfo invalid");
        }
        int safety = switch (safetyStr) {
            case "unsafe" -> ExportSink.SAFETY_UNSAFE;
            case "safe" -> ExportSink.SAFETY_SAFE;
            case "partial" -> ExportSink.SAFETY_PARTIAL;
            default -> throw bad("Unknown safety parameter: " + safetyStr);
        };
        int[] alls = nameList(all);
        sink.declDef(n, concat(lps, alls), 3, lps.length, t, v, hintKind, height, safety, 3 + lps.length, alls.length);
    }

    private void thm(Map<String, Object> d) throws Fail, ExportException {
        BigInteger name = nat(d.get("name"), "thmInfo invalid");
        List<Object> lp = arr(d.get("levelParams"), "thmInfo invalid");
        BigInteger type = nat(d.get("type"), "thmInfo invalid");
        BigInteger value = nat(d.get("value"), "thmInfo invalid");
        List<Object> all = arr(d.get("all"), "thmInfo invalid");
        int n = get(names, name, "Name");
        int[] lps = nameList(lp);
        int t = get(exprs, type, "Expr");
        int v = get(exprs, value, "Expr");
        int[] alls = nameList(all);
        sink.declThm(n, concat(lps, alls), 3, lps.length, t, v, 3 + lps.length, alls.length);
    }

    private void opaque(Map<String, Object> d) throws Fail, ExportException {
        BigInteger name = nat(d.get("name"), "opaqueInfo invalid");
        List<Object> lp = arr(d.get("levelParams"), "opaqueInfo invalid");
        BigInteger type = nat(d.get("type"), "opaqueInfo invalid");
        BigInteger value = nat(d.get("value"), "opaqueInfo invalid");
        boolean isUnsafe = d.containsKey("isUnsafe") ? bool(d.get("isUnsafe"), "opaqueInfo invalid") : false;
        List<Object> all = arr(d.get("all"), "opaqueInfo invalid");
        int n = get(names, name, "Name");
        int[] lps = nameList(lp);
        int t = get(exprs, type, "Expr");
        int v = get(exprs, value, "Expr");
        int[] alls = nameList(all);
        sink.declOpaque(n, concat(lps, alls), 3, lps.length, t, v, isUnsafe, 3 + lps.length, alls.length);
    }

    private void quot(Map<String, Object> d) throws Fail, ExportException {
        BigInteger name = nat(d.get("name"), "quotInfo invalid");
        List<Object> lp = arr(d.get("levelParams"), "quotInfo invalid");
        BigInteger type = nat(d.get("type"), "quotInfo invalid");
        String kindStr = str(d.get("kind"), "quotInfo invalid");
        int n = get(names, name, "Name");
        int[] lps = nameList(lp);
        int t = get(exprs, type, "Expr");
        int kind = switch (kindStr) {
            case "type" -> ExportSink.QUOT_TYPE;
            case "ctor" -> ExportSink.QUOT_CTOR;
            case "lift" -> ExportSink.QUOT_LIFT;
            case "ind" -> ExportSink.QUOT_IND;
            default -> throw bad("unknown quot kind: " + kindStr);
        };
        sink.declQuot(n, concat(lps), 3, lps.length, t, kind);
    }

    private void inductive(Map<String, Object> d) throws Fail, ExportException {
        List<Object> types = arr(d.get("types"), "Inductive invalid, no `types`");
        List<Object> ctors = arr(d.get("ctors"), "Inductive invalid, no `ctors`");
        List<Object> recs = arr(d.get("recs"), "Inductive invalid, no `recs`");
        sink.inductBegin(types.size(), ctors.size(), recs.size());
        for (Object t : types) {
            inductType(obj(t, "inductInfo invalid: Expected JSON object"));
        }
        for (Object c : ctors) {
            inductCtor(obj(c, "ctorInfo invalid"));
        }
        for (Object r : recs) {
            inductRec(obj(r, "recInfo invalid"));
        }
        sink.inductEnd();
    }

    private void inductType(Map<String, Object> d) throws Fail, ExportException {
        String err = "inductInfo invalid";
        BigInteger name = nat(d.get("name"), err);
        List<Object> lp = arr(d.get("levelParams"), err);
        BigInteger type = nat(d.get("type"), err);
        BigInteger numParams = nat(d.get("numParams"), err);
        BigInteger numIndices = nat(d.get("numIndices"), err);
        List<Object> all = arr(d.get("all"), err);
        List<Object> ctors = arr(d.get("ctors"), err);
        BigInteger numNested = nat(d.get("numNested"), err);
        boolean isRec = bool(d.get("isRec"), err);
        boolean isUnsafe = bool(d.get("isUnsafe"), err);
        boolean isReflexive = bool(d.get("isReflexive"), err);
        int n = get(names, name, "Name");
        int[] lps = nameList(lp);
        int t = get(exprs, type, "Expr");
        int[] alls = nameList(all);
        int[] cs = nameList(ctors);
        if (count(numParams) < 0 || count(numIndices) < 0 || count(numNested) < 0) {
            throw unsupported("count");
        }
        sink.inductType(n, concat(lps, alls, cs), 3, lps.length, t, count(numParams), count(numIndices),
                3 + lps.length, alls.length, 3 + lps.length + alls.length, cs.length, count(numNested), isRec,
                isUnsafe, isReflexive);
    }

    private void inductCtor(Map<String, Object> d) throws Fail, ExportException {
        String err = "ctorInfo invalid";
        BigInteger name = nat(d.get("name"), err);
        List<Object> lp = arr(d.get("levelParams"), err);
        BigInteger type = nat(d.get("type"), err);
        BigInteger induct = nat(d.get("induct"), err);
        BigInteger cidx = nat(d.get("cidx"), err);
        BigInteger numParams = nat(d.get("numParams"), err);
        BigInteger numFields = nat(d.get("numFields"), err);
        boolean isUnsafe = bool(d.get("isUnsafe"), err);
        int n = get(names, name, "Name");
        int[] lps = nameList(lp);
        int t = get(exprs, type, "Expr");
        int ind = get(names, induct, "Name");
        if (count(cidx) < 0 || count(numParams) < 0 || count(numFields) < 0) {
            throw unsupported("count");
        }
        sink.inductCtor(n, concat(lps), 3, lps.length, t, ind, count(cidx), count(numParams), count(numFields),
                isUnsafe);
    }

    private void inductRec(Map<String, Object> d) throws Fail, ExportException {
        String err = "recInfo invalid";
        BigInteger name = nat(d.get("name"), err);
        List<Object> lp = arr(d.get("levelParams"), err);
        BigInteger type = nat(d.get("type"), err);
        List<Object> all = arr(d.get("all"), err);
        BigInteger numParams = nat(d.get("numParams"), err);
        BigInteger numIndices = nat(d.get("numIndices"), err);
        BigInteger numMotives = nat(d.get("numMotives"), err);
        BigInteger numMinors = nat(d.get("numMinors"), err);
        boolean k = bool(d.get("k"), err);
        List<Object> rules = arr(d.get("rules"), err);
        boolean isUnsafe = bool(d.get("isUnsafe"), err);
        int n = get(names, name, "Name");
        int[] lps = nameList(lp);
        int t = get(exprs, type, "Expr");
        int[] alls = nameList(all);
        boolean big = count(numParams) < 0 || count(numIndices) < 0 || count(numMotives) < 0 || count(numMinors) < 0;
        int[] ctor = new int[rules.size()];
        long[] nf = new long[rules.size()];
        int[] rhs = new int[rules.size()];
        for (int i = 0; i < ctor.length; i++) {
            Map<String, Object> r = obj(rules.get(i), err);
            BigInteger c = nat(r.get("ctor"), err);
            BigInteger f = nat(r.get("nfields"), err);
            BigInteger e = nat(r.get("rhs"), err);
            ctor[i] = get(names, c, "Name");
            rhs[i] = get(exprs, e, "Expr");
            nf[i] = count(f);
            big |= nf[i] < 0;
        }
        if (big) {
            throw unsupported("count");
        }
        sink.inductRec(n, concat(lps, alls), 3, lps.length, t, 3 + lps.length, alls.length, count(numParams),
                count(numIndices), count(numMotives), count(numMinors), k, isUnsafe, ctor.length);
        for (int i = 0; i < ctor.length; i++) {
            sink.inductRecRule(ctor[i], nf[i], rhs[i]);
        }
    }
}

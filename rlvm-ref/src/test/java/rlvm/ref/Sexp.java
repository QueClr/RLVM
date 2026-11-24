package rlvm.ref;

import java.util.ArrayList;
import java.util.List;

/**
 * A tiny term language for hand-made fixtures. It is parsed into nodes through a {@link Sink}, so the
 * same text can be written into the shared region (declarations) or into a worker's overlay (ad-hoc
 * test terms).
 *
 * <pre>
 * term  ::= x                      a bound name in scope, else the constant x without levels
 *         | #n                     raw de Bruijn index
 *         | $name                  a term bound by the test (a free variable, usually)
 *         | Prop | Type            Sort 0, Sort 1
 *         | (sort l)
 *         | (@ c l1 .. ln)         constant with universe levels
 *         | (fun (x : T) .. body)  | (pi (x : T) .. body) | (-> A B .. R)
 *         | (let (x : T := v) body)
 *         | (proj S i e)
 *         | (lit n) | (str "..")
 *         | (f a1 .. an)           application
 * level ::= n | u | (succ l) | (max l l) | (imax l l)
 * </pre>
 */
final class Sexp {

    /** Receiver of parsed nodes; every method returns the id of the node it made. */
    interface Sink {
        int bvar(int index);

        int sort(int level);

        int constant(String name, int[] levels);

        int app(int fn, int arg);

        int lam(int domain, int body);

        int pi(int domain, int body);

        int let(int type, int value, int body);

        int proj(String structName, int index, int struct);

        int natLit(String decimal);

        int strLit(String value);

        int levelZero();

        int levelSucc(int l);

        int levelMax(int a, int b);

        int levelIMax(int a, int b);

        int levelParam(String name);

        /** A term bound outside the text, written {@code $name}. */
        int ref(String name);
    }

    private final String src;
    private int pos;
    private final Sink sink;
    private final List<String> scope = new ArrayList<>();

    private Sexp(String src, Sink sink) {
        this.src = src;
        this.sink = sink;
    }

    static int term(String src, Sink sink) {
        Sexp p = new Sexp(src, sink);
        int t = p.parseTerm();
        p.skipWs();
        if (p.pos != src.length()) {
            throw p.error("trailing input");
        }
        return t;
    }

    static int level(String src, Sink sink) {
        Sexp p = new Sexp(src, sink);
        int l = p.parseLevel();
        p.skipWs();
        if (p.pos != src.length()) {
            throw p.error("trailing input");
        }
        return l;
    }

    private IllegalArgumentException error(String msg) {
        return new IllegalArgumentException(msg + " at " + pos + " in: " + src);
    }

    private void skipWs() {
        while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) {
            pos++;
        }
    }

    private char peek() {
        skipWs();
        if (pos >= src.length()) {
            throw error("unexpected end");
        }
        return src.charAt(pos);
    }

    private void expect(char c) {
        if (peek() != c) {
            throw error("expected '" + c + "'");
        }
        pos++;
    }

    private boolean tryChar(char c) {
        if (peek() == c) {
            pos++;
            return true;
        }
        return false;
    }

    private String atom() {
        skipWs();
        int start = pos;
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (Character.isWhitespace(c) || c == '(' || c == ')') {
                break;
            }
            pos++;
        }
        if (start == pos) {
            throw error("expected an atom");
        }
        return src.substring(start, pos);
    }

    private int parseLevel() {
        if (tryChar('(')) {
            String head = atom();
            int r;
            switch (head) {
                case "succ":
                    r = sink.levelSucc(parseLevel());
                    break;
                case "max": {
                    int a = parseLevel();
                    r = sink.levelMax(a, parseLevel());
                    break;
                }
                case "imax": {
                    int a = parseLevel();
                    r = sink.levelIMax(a, parseLevel());
                    break;
                }
                default:
                    throw error("unknown level form " + head);
            }
            expect(')');
            return r;
        }
        String a = atom();
        if (Character.isDigit(a.charAt(0))) {
            int n = Integer.parseInt(a);
            int l = sink.levelZero();
            for (int i = 0; i < n; i++) {
                l = sink.levelSucc(l);
            }
            return l;
        }
        return sink.levelParam(a);
    }

    private int lookup(String name) {
        for (int i = scope.size() - 1; i >= 0; i--) {
            if (scope.get(i).equals(name)) {
                return scope.size() - 1 - i;
            }
        }
        return -1;
    }

    private int parseTerm() {
        char c = peek();
        if (c != '(') {
            String a = atom();
            if (a.charAt(0) == '#') {
                return sink.bvar(Integer.parseInt(a.substring(1)));
            }
            if (a.charAt(0) == '$') {
                return sink.ref(a.substring(1));
            }
            if (a.equals("Prop")) {
                return sink.sort(sink.levelZero());
            }
            if (a.equals("Type")) {
                return sink.sort(sink.levelSucc(sink.levelZero()));
            }
            int idx = lookup(a);
            return idx >= 0 ? sink.bvar(idx) : sink.constant(a, new int[0]);
        }
        pos++;
        skipWs();
        int save = pos;
        String head = peek() == '(' ? "" : atom();
        int r;
        switch (head) {
            case "sort":
                r = sink.sort(parseLevel());
                break;
            case "@": {
                String name = atom();
                List<Integer> ls = new ArrayList<>();
                while (peek() != ')') {
                    ls.add(parseLevel());
                }
                int[] arr = new int[ls.size()];
                for (int i = 0; i < arr.length; i++) {
                    arr[i] = ls.get(i);
                }
                r = sink.constant(name, arr);
                break;
            }
            case "fun":
            case "pi":
                r = parseBinders(head.equals("fun"));
                break;
            case "->": {
                List<Integer> parts = new ArrayList<>();
                int depth = scope.size();
                while (peek() != ')') {
                    parts.add(parseTerm());
                    scope.add("_");
                }
                while (scope.size() > depth) {
                    scope.remove(scope.size() - 1);
                }
                r = parts.get(parts.size() - 1);
                for (int i = parts.size() - 2; i >= 0; i--) {
                    r = sink.pi(parts.get(i), r);
                }
                break;
            }
            case "let": {
                expect('(');
                String x = atom();
                expect(':');
                int type = parseTerm();
                skipWs();
                if (!src.startsWith(":=", pos)) {
                    throw error("expected :=");
                }
                pos += 2;
                int value = parseTerm();
                expect(')');
                scope.add(x);
                int body = parseTerm();
                scope.remove(scope.size() - 1);
                r = sink.let(type, value, body);
                break;
            }
            case "proj": {
                String s = atom();
                int i = Integer.parseInt(atom());
                r = sink.proj(s, i, parseTerm());
                break;
            }
            case "lit":
                r = sink.natLit(atom());
                break;
            case "str": {
                expect('"');
                int start = pos;
                while (src.charAt(pos) != '"') {
                    pos++;
                }
                r = sink.strLit(src.substring(start, pos));
                pos++;
                break;
            }
            default: {
                pos = save;
                r = parseTerm();
                while (peek() != ')') {
                    r = sink.app(r, parseTerm());
                }
            }
        }
        expect(')');
        return r;
    }

    private int parseBinders(boolean lambda) {
        List<Integer> domains = new ArrayList<>();
        int depth = scope.size();
        for (;;) {
            skipWs();
            int save = pos;
            if (peek() != '(') {
                break;
            }
            pos++;
            skipWs();
            // A binder group is "(x : T)"; anything else is the body.
            int nameStart = pos;
            while (pos < src.length() && !Character.isWhitespace(src.charAt(pos)) && src.charAt(pos) != '('
                    && src.charAt(pos) != ')') {
                pos++;
            }
            String x = src.substring(nameStart, pos);
            skipWs();
            if (x.isEmpty() || pos >= src.length() || src.charAt(pos) != ':' || src.startsWith(":=", pos)) {
                pos = save;
                break;
            }
            pos++;
            domains.add(parseTerm());
            expect(')');
            scope.add(x);
        }
        int body = parseTerm();
        while (scope.size() > depth) {
            scope.remove(scope.size() - 1);
        }
        for (int i = domains.size() - 1; i >= 0; i--) {
            body = lambda ? sink.lam(domains.get(i), body) : sink.pi(domains.get(i), body);
        }
        return body;
    }
}

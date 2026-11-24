package rlvm.inductive;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import rlvm.api.LevelOps;
import rlvm.store.IntLists;
import rlvm.store.NameTable;

/**
 * Test-only reader of a small Lean-like term notation with named binders, so that fixtures and
 * expected recursors can be written the way {@code docs/research/03} section 6.14 prints them.
 *
 * <pre>
 * expr   := ('∀' | 'λ') group+ ',' expr | app ('→' expr)?
 * group  := '(' ident+ ':' expr ')'
 * app    := atom+
 * atom   := '(' expr ')' | 'Prop' | 'Type' | 'Type' latom | 'Sort' latom | '#' digits
 *         | ident ('.{' level (',' level)* '}')?
 * level  := latom ('+' digits)?
 * latom  := digits | ident | '(' ('max' | 'imax') latom latom ')' | '(' level ')'
 * </pre>
 * An identifier is a bound variable if a binder of that name is in scope, else a constant name
 * (dotted). {@code #i} is a raw de Bruijn index. Binder groups may bind several names.
 */
final class TermParser {

    /** Where the terms are built: the shared region (fixtures) or an overlay (expected outputs). */
    interface Sink {
        int bvar(int index);

        int sort(int level);

        int constant(int name, int levelList);

        int app(int fn, int arg);

        int lam(int domain, int body);

        int pi(int domain, int body);
    }

    private final NameTable names;
    private final LevelOps levels;
    private final IntLists lists;
    private final Sink sink;

    private String src;
    private int pos;
    private final ArrayList<String> scope = new ArrayList<>();

    TermParser(NameTable names, LevelOps levels, IntLists lists, Sink sink) {
        this.names = names;
        this.levels = levels;
        this.lists = lists;
        this.sink = sink;
    }

    int parse(String text) {
        src = text;
        pos = 0;
        scope.clear();
        int t = expr();
        skipWs();
        if (pos != src.length()) {
            throw err("trailing input");
        }
        return t;
    }

    int name(String dotted) {
        return internName(names, dotted);
    }

    static int internName(NameTable names, String dotted) {
        int id = NameTable.ANONYMOUS;
        for (String part : dotted.split("\\.")) {
            byte[] b = part.getBytes(StandardCharsets.UTF_8);
            id = names.internStr(id, b, 0, b.length);
        }
        return id;
    }

    int nameList(String... dotted) {
        int[] ids = new int[dotted.length];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = name(dotted[i]);
        }
        return lists.intern(ids, 0, ids.length);
    }

    int level(String text) {
        src = text;
        pos = 0;
        int l = levelExpr();
        skipWs();
        if (pos != src.length()) {
            throw err("trailing input");
        }
        return l;
    }

    // ---- lexer -------------------------------------------------------------------------------------

    private IllegalArgumentException err(String msg) {
        return new IllegalArgumentException(msg + " at " + pos + " in: " + src);
    }

    private void skipWs() {
        while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) {
            pos++;
        }
    }

    private boolean peek(String s) {
        skipWs();
        return src.startsWith(s, pos);
    }

    private boolean eat(String s) {
        if (peek(s)) {
            pos += s.length();
            return true;
        }
        return false;
    }

    private void expect(String s) {
        if (!eat(s)) {
            throw err("expected '" + s + "'");
        }
    }

    private static boolean identStart(char c) {
        return Character.isLetter(c) || c == '_';
    }

    private static boolean identPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '\'' || c == '.';
    }

    /** Next identifier or null (position unchanged). Stops before ".{". */
    private String ident() {
        skipWs();
        int p = pos;
        if (p >= src.length() || !identStart(src.charAt(p)) || src.charAt(p) == 'λ') {
            return null;
        }
        while (p < src.length() && identPart(src.charAt(p))) {
            if (src.charAt(p) == '.' && (p + 1 >= src.length() || !identStart(src.charAt(p + 1)))) {
                break;
            }
            p++;
        }
        String s = src.substring(pos, p);
        pos = p;
        return s;
    }

    private int digits() {
        skipWs();
        int p = pos;
        while (p < src.length() && Character.isDigit(src.charAt(p))) {
            p++;
        }
        if (p == pos) {
            throw err("expected a number");
        }
        int v = Integer.parseInt(src.substring(pos, p));
        pos = p;
        return v;
    }

    // ---- levels ------------------------------------------------------------------------------------

    private int levelExpr() {
        int l = levelAtom();
        if (eat("+")) {
            int n = digits();
            for (int i = 0; i < n; i++) {
                l = levels.succ(l);
            }
        }
        return l;
    }

    private int levelAtom() {
        skipWs();
        if (pos < src.length() && Character.isDigit(src.charAt(pos))) {
            int n = digits();
            int l = levels.zero();
            for (int i = 0; i < n; i++) {
                l = levels.succ(l);
            }
            return l;
        }
        if (eat("(")) {
            int save = pos;
            String kw = ident();
            int l;
            if ("max".equals(kw)) {
                int a = levelAtom();
                l = levels.max(a, levelAtom());
            } else if ("imax".equals(kw)) {
                int a = levelAtom();
                l = levels.imax(a, levelAtom());
            } else {
                pos = save;
                l = levelExpr();
            }
            expect(")");
            return l;
        }
        String id = ident();
        if (id == null) {
            throw err("expected a level");
        }
        return levels.param(name(id));
    }

    // ---- terms -------------------------------------------------------------------------------------

    private int expr() {
        skipWs();
        boolean forall = eat("∀");
        boolean lambda = !forall && eat("λ");
        if (forall || lambda) {
            return binders(lambda);
        }
        int lhs = app();
        if (eat("→")) {
            scope.add("");
            int rhs = expr();
            scope.remove(scope.size() - 1);
            return sink.pi(lhs, rhs);
        }
        return lhs;
    }

    /** One or more binder groups, then ',' and the body. */
    private int binders(boolean lambda) {
        ArrayList<Integer> domains = new ArrayList<>();
        int bound = 0;
        while (eat("(")) {
            ArrayList<String> ids = new ArrayList<>();
            String id;
            while ((id = ident()) != null) {
                ids.add(id);
            }
            if (ids.isEmpty()) {
                throw err("expected a binder name");
            }
            expect(":");
            int save = pos;
            for (String x : ids) {
                // the domain is re-read for each name: its bound variables shift
                pos = save;
                domains.add(expr());
                scope.add(x);
                bound++;
            }
            expect(")");
        }
        if (bound == 0) {
            throw err("expected a binder group");
        }
        expect(",");
        int body = expr();
        for (int i = bound - 1; i >= 0; i--) {
            scope.remove(scope.size() - 1);
            body = lambda ? sink.lam(domains.get(i), body) : sink.pi(domains.get(i), body);
        }
        return body;
    }

    private int app() {
        int f = atom();
        if (f == 0) {
            throw err("expected a term");
        }
        while (true) {
            int a = atom();
            if (a == 0) {
                return f;
            }
            f = sink.app(f, a);
        }
    }

    /** An atom, or 0 if none starts here. */
    private int atom() {
        skipWs();
        if (pos >= src.length()) {
            return 0;
        }
        char c = src.charAt(pos);
        if (c == '(') {
            pos++;
            int t = expr();
            expect(")");
            return t;
        }
        if (c == '#') {
            pos++;
            return sink.bvar(digits());
        }
        if (c == '∀' || c == 'λ' || c == '→') {
            return 0;
        }
        int save = pos;
        String id = ident();
        if (id == null) {
            return 0;
        }
        switch (id) {
            case "Prop":
                return sink.sort(levels.zero());
            case "Type": {
                skipWs();
                int l = levels.zero();
                if (pos < src.length() && (Character.isDigit(src.charAt(pos)) || startsLevelIdent())) {
                    l = levelAtom();
                } else if (peek("(") && (src.startsWith("(max", pos) || src.startsWith("(imax", pos))) {
                    l = levelAtom();
                }
                return sink.sort(levels.succ(l));
            }
            case "Sort":
                return sink.sort(levelAtom());
            default:
                break;
        }
        for (int i = scope.size() - 1; i >= 0; i--) {
            if (scope.get(i).equals(id)) {
                return sink.bvar(scope.size() - 1 - i);
            }
        }
        int[] ls = new int[8];
        int n = 0;
        if (src.startsWith(".{", pos)) {
            pos += 2;
            do {
                ls[n++] = levelExpr();
            } while (eat(","));
            expect("}");
        }
        if (id.isEmpty()) {
            pos = save;
            return 0;
        }
        return sink.constant(name(id), lists.intern(ls, 0, n));
    }

    /** True if a universe-parameter name (a single lower-case letter, optionally with _digits) follows "Type". */
    private boolean startsLevelIdent() {
        int p = pos;
        if (p >= src.length() || !Character.isLowerCase(src.charAt(p)) || src.charAt(p) > 'z') {
            return false;
        }
        p++;
        while (p < src.length() && (Character.isDigit(src.charAt(p)) || src.charAt(p) == '_')) {
            p++;
        }
        // a level name must end here: "Type u" but not "Type unit"
        return p >= src.length() || !identPart(src.charAt(p));
    }
}

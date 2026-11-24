package rlvm.spike.lc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import rlvm.spike.lc.Expr.App;
import rlvm.spike.lc.Expr.Arg;
import rlvm.spike.lc.Expr.Cap;
import rlvm.spike.lc.Expr.GlobalRef;
import rlvm.spike.lc.Expr.If;
import rlvm.spike.lc.Expr.Lam;
import rlvm.spike.lc.Expr.Let;
import rlvm.spike.lc.Expr.Lit;
import rlvm.spike.lc.Expr.Local;
import rlvm.spike.lc.Expr.Prim;
import rlvm.spike.lc.Expr.Program;

/**
 * S-expression front-end.
 *
 * <pre>
 * program ::= (def name e)* e
 * e ::= integer | name | (lam (x ...) e) | (let x e e) | (if e e e) | (+|-|*|&lt;|= e e) | (e e ...)
 * </pre>
 *
 * Lambdas are n-ary and applications must match the arity exactly (curried functions are written as
 * nested unary lambdas). Lines starting with ';' are comments.
 */
public final class Parser {
    private final Map<String, Global> globals;
    private final String src;
    private int pos;

    private static final class Scope {
        final Scope parent;
        final String name;
        final List<String> names = new ArrayList<>();
        final List<Expr> access = new ArrayList<>();
        final List<Expr> captures = new ArrayList<>();
        int numLocals;
        int liveLocals;

        Scope(Scope parent, String name) {
            this.parent = parent;
            this.name = name;
        }
    }

    public Parser(Map<String, Global> globals, String src) {
        this.globals = globals;
        this.src = src;
    }

    public static Program parse(String src) {
        return new Parser(new HashMap<>(), src).parseProgram();
    }

    public Program parseProgram() {
        List<Global> gs = new ArrayList<>();
        List<Lam> inits = new ArrayList<>();
        Lam main = null;
        while (true) {
            skipWs();
            if (pos >= src.length()) {
                break;
            }
            int save = pos;
            if (peekIsDef()) {
                expect('(');
                token(); // def
                String name = token();
                Global g = globals.computeIfAbsent(name, Global::new);
                Scope s = new Scope(null, name);
                Expr body = expr(s, name);
                expect(')');
                gs.add(g);
                inits.add(new Lam("<init " + name + ">", 0, s.numLocals, new Expr[0], body));
            } else {
                pos = save;
                Scope s = new Scope(null, "<main>");
                Expr body = expr(s, "<main>");
                main = new Lam("<main>", 0, s.numLocals, new Expr[0], body);
            }
        }
        if (main == null) {
            throw new IllegalArgumentException("program has no result expression");
        }
        return new Program(gs.toArray(new Global[0]), inits.toArray(new Lam[0]), main);
    }

    private boolean peekIsDef() {
        int save = pos;
        try {
            if (src.charAt(pos) != '(') {
                return false;
            }
            pos++;
            skipWs();
            return src.startsWith("def", pos) && Character.isWhitespace(src.charAt(pos + 3));
        } finally {
            pos = save;
        }
    }

    private Expr expr(Scope scope, String nameHint) {
        skipWs();
        char c = src.charAt(pos);
        if (c != '(') {
            String t = token();
            if (Character.isDigit(t.charAt(0)) || (t.length() > 1 && t.charAt(0) == '-' && Character.isDigit(t.charAt(1)))) {
                return new Lit(Long.parseLong(t));
            }
            return resolve(t, scope);
        }
        expect('(');
        skipWs();
        int save = pos;
        String head = src.charAt(pos) == '(' ? "" : token();
        Expr result;
        switch (head) {
            case "lam" -> {
                Scope inner = new Scope(scope, nameHint);
                expect('(');
                int arity = 0;
                while (true) {
                    skipWs();
                    if (src.charAt(pos) == ')') {
                        pos++;
                        break;
                    }
                    inner.names.add(token());
                    inner.access.add(new Arg(arity++));
                }
                Expr body = expr(inner, nameHint + "\\");
                result = new Lam(nameHint, arity, inner.numLocals, inner.captures.toArray(new Expr[0]), body);
            }
            case "let" -> {
                String name = token();
                Expr init = expr(scope, name);
                int slot = scope.liveLocals++;
                scope.numLocals = Math.max(scope.numLocals, scope.liveLocals);
                scope.names.add(name);
                scope.access.add(new Local(slot));
                Expr body = expr(scope, nameHint);
                scope.names.remove(scope.names.size() - 1);
                scope.access.remove(scope.access.size() - 1);
                scope.liveLocals--;
                result = new Let(slot, init, body);
            }
            case "if" -> result = new If(expr(scope, nameHint), expr(scope, nameHint), expr(scope, nameHint));
            case "+", "-", "*", "<", "=" -> result = new Prim(head.charAt(0), expr(scope, nameHint), expr(scope, nameHint));
            default -> {
                pos = save;
                Expr fn = expr(scope, nameHint);
                List<Expr> args = new ArrayList<>();
                while (true) {
                    skipWs();
                    if (src.charAt(pos) == ')') {
                        break;
                    }
                    args.add(expr(scope, nameHint));
                }
                result = new App(fn, args.toArray(new Expr[0]));
            }
        }
        expect(')');
        return result;
    }

    private Expr resolve(String name, Scope scope) {
        for (int i = scope.names.size() - 1; i >= 0; i--) {
            if (scope.names.get(i).equals(name)) {
                return scope.access.get(i);
            }
        }
        if (scope.parent != null) {
            Expr outer = resolve(name, scope.parent);
            if (outer instanceof GlobalRef) {
                return outer;
            }
            int index = scope.captures.size();
            scope.captures.add(outer);
            Cap cap = new Cap(index);
            // Register at the front so later lookups reuse the capture; locals/params shadow it.
            scope.names.add(0, name);
            scope.access.add(0, cap);
            return cap;
        }
        Global g = globals.get(name);
        if (g == null) {
            throw new IllegalArgumentException("unbound variable " + name + " at offset " + pos);
        }
        return new GlobalRef(g);
    }

    private void skipWs() {
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c == ';') {
                while (pos < src.length() && src.charAt(pos) != '\n') {
                    pos++;
                }
            } else if (Character.isWhitespace(c)) {
                pos++;
            } else {
                break;
            }
        }
    }

    private void expect(char c) {
        skipWs();
        if (pos >= src.length() || src.charAt(pos) != c) {
            throw new IllegalArgumentException("expected '" + c + "' at offset " + pos);
        }
        pos++;
    }

    private String token() {
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
            throw new IllegalArgumentException("expected token at offset " + pos);
        }
        return src.substring(start, pos);
    }
}

package rlvm.level;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** Test helpers: names, conversion between {@link RefLevel} trees and store ids, parsing, random levels, semantics. */
final class Lv {
    private Lv() {}

    /**
     * Name oracle over single-component string names, ordered as Lean orders such names (byte order).
     * Ids are handed out in registration order, which the tests make different from the name order.
     * Safe for concurrent readers once all names are registered.
     */
    static final class Names implements NameOracle {
        private final Map<String, Integer> ids = new HashMap<>();
        private final List<String> strings = new ArrayList<>();

        Names(String... inOrder) {
            strings.add("<anonymous>");
            for (String s : inOrder) {
                id(s);
            }
        }

        int id(String s) {
            Integer i = ids.get(s);
            if (i == null) {
                i = strings.size();
                strings.add(s);
                ids.put(s, i);
            }
            return i;
        }

        String name(int id) {
            return strings.get(id);
        }

        @Override
        public int compareNames(int x, int y) {
            return strings.get(x).compareTo(strings.get(y));
        }

        @Override
        public void appendName(StringBuilder sb, int nameId) {
            sb.append(strings.get(nameId));
        }
    }

    /** Builds {@code l} in the store with the raw constructors (exactly this structure). */
    static int toStore(LevelStore s, RefLevel l, Names n) {
        switch (l.kind) {
            case RefLevel.ZERO:
                return LevelStore.ZERO;
            case RefLevel.SUCC:
                return s.succ(toStore(s, l.a, n));
            case RefLevel.MAX:
                return s.max(toStore(s, l.a, n), toStore(s, l.b, n));
            case RefLevel.IMAX:
                return s.imax(toStore(s, l.a, n), toStore(s, l.b, n));
            default:
                return s.param(n.id(l.name));
        }
    }

    static RefLevel fromStore(LevelStore s, int id, Names n) {
        switch (s.kind(id)) {
            case LevelStore.KIND_ZERO:
                return RefLevel.Z;
            case LevelStore.KIND_SUCC:
                return RefLevel.succ(fromStore(s, s.succOf(id), n));
            case LevelStore.KIND_MAX:
                return RefLevel.max(fromStore(s, s.lhs(id), n), fromStore(s, s.rhs(id), n));
            case LevelStore.KIND_IMAX:
                return RefLevel.imax(fromStore(s, s.lhs(id), n), fromStore(s, s.rhs(id), n));
            default:
                return RefLevel.param(n.name(s.paramName(id)));
        }
    }

    // ------------------------------------------------------------------ text

    /**
     * Parses {@code 0}, a decimal numeral, a parameter name, {@code (s X)}, {@code (m X Y)},
     * {@code (i X Y)}; also {@code (+ X k)} for k successors.
     */
    static RefLevel parse(String text) {
        int[] pos = {0};
        RefLevel r = parse(text, pos);
        skipWs(text, pos);
        if (pos[0] != text.length()) {
            throw new IllegalArgumentException("trailing input in level: " + text);
        }
        return r;
    }

    private static void skipWs(String t, int[] pos) {
        while (pos[0] < t.length() && t.charAt(pos[0]) == ' ') {
            pos[0]++;
        }
    }

    private static RefLevel parse(String t, int[] pos) {
        skipWs(t, pos);
        char c = t.charAt(pos[0]);
        if (c == '(') {
            pos[0]++;
            char op = t.charAt(pos[0]++);
            RefLevel r;
            if (op == 's') {
                r = RefLevel.succ(parse(t, pos));
            } else if (op == 'm') {
                RefLevel x = parse(t, pos);
                r = RefLevel.max(x, parse(t, pos));
            } else if (op == 'i') {
                RefLevel x = parse(t, pos);
                r = RefLevel.imax(x, parse(t, pos));
            } else if (op == '+') {
                RefLevel x = parse(t, pos);
                skipWs(t, pos);
                int st = pos[0];
                while (Character.isDigit(t.charAt(pos[0]))) {
                    pos[0]++;
                }
                r = RefLevel.succN(x, Integer.parseInt(t.substring(st, pos[0])));
            } else {
                throw new IllegalArgumentException("bad operator in level: " + t);
            }
            skipWs(t, pos);
            if (t.charAt(pos[0]) != ')') {
                throw new IllegalArgumentException("missing ) in level: " + t);
            }
            pos[0]++;
            return r;
        }
        int st = pos[0];
        while (pos[0] < t.length() && t.charAt(pos[0]) != ' ' && t.charAt(pos[0]) != ')' && t.charAt(pos[0]) != '(') {
            pos[0]++;
        }
        String tok = t.substring(st, pos[0]);
        if (tok.isEmpty()) {
            throw new IllegalArgumentException("empty token in level: " + t);
        }
        if (Character.isDigit(tok.charAt(0))) {
            return RefLevel.ofNat(Integer.parseInt(tok));
        }
        return RefLevel.param(tok);
    }

    // ------------------------------------------------------------------ random levels

    static final String[] PARAMS = {"u", "v", "w", "x"};

    /** A random level of height at most {@code depth} over the first {@code nparams} of u, v, w, x. */
    static RefLevel random(Random rnd, int depth, int nparams) {
        int r = rnd.nextInt(100);
        if (depth <= 0 || r < 22) {
            if (rnd.nextInt(100) < 25 || nparams == 0) {
                return rnd.nextInt(4) == 0 ? RefLevel.ONE : RefLevel.Z;
            }
            return RefLevel.param(PARAMS[rnd.nextInt(nparams)]);
        }
        if (r < 44) {
            return RefLevel.succ(random(rnd, depth - 1, nparams));
        }
        if (r < 72) {
            return RefLevel.max(random(rnd, depth - 1, nparams), random(rnd, depth - 1, nparams));
        }
        return RefLevel.imax(random(rnd, depth - 1, nparams), random(rnd, depth - 1, nparams));
    }

    // ------------------------------------------------------------------ brute-force semantics

    /**
     * Calls {@code f} for every assignment of the parameters to {@code 0..bound}. With
     * {@code bound >= depth + 2} for all levels involved, equality and ordering on this grid are
     * equality and ordering on all naturals (see the comment on the canonical form in LevelStore:
     * the witnesses are 0, 1 and one value above every constant).
     */
    interface Assignment {
        boolean test(Map<String, Integer> env);
    }

    static boolean forAll(List<String> params, int bound, Assignment f) {
        Map<String, Integer> env = new HashMap<>();
        return forAll(params, 0, bound, env, f);
    }

    private static boolean forAll(List<String> params, int i, int bound, Map<String, Integer> env, Assignment f) {
        if (i == params.size()) {
            return f.test(env);
        }
        for (int v = 0; v <= bound; v++) {
            env.put(params.get(i), v);
            if (!forAll(params, i + 1, bound, env, f)) {
                return false;
            }
        }
        return true;
    }

    static List<String> paramsOf(RefLevel... ls) {
        List<String> ps = new ArrayList<>();
        for (RefLevel l : ls) {
            RefLevel.collectParams(l, ps);
        }
        return ps;
    }

    private static int bound(RefLevel... ls) {
        int d = 0;
        for (RefLevel l : ls) {
            d = Math.max(d, l.depth);
        }
        return d + 2;
    }

    /** Semantic equality: equal values under every assignment. */
    static boolean semEq(RefLevel x, RefLevel y) {
        return forAll(paramsOf(x, y), bound(x, y), env -> RefLevel.eval(x, env) == RefLevel.eval(y, env));
    }

    /** Semantic {@code x >= y} under every assignment. */
    static boolean semGeq(RefLevel x, RefLevel y) {
        return forAll(paramsOf(x, y), bound(x, y), env -> RefLevel.eval(x, env) >= RefLevel.eval(y, env));
    }

    /**
     * Semantic equality decided on the assignments with values in {0, 1, N}, N = depth + 2: exact
     * (those are the only witnesses needed) and cheap enough for levels with many parameters.
     */
    static boolean semEqReduced(RefLevel x, RefLevel y) {
        List<String> ps = paramsOf(x, y);
        int[] vals = {0, 1, bound(x, y)};
        if (ps.size() > 7) {
            return sampled(ps, vals, e -> RefLevel.eval(x, e) == RefLevel.eval(y, e));
        }
        Map<String, Integer> env = new HashMap<>();
        return reduced(ps, 0, vals, env, e -> RefLevel.eval(x, e) == RefLevel.eval(y, e));
    }

    static boolean semGeqReduced(RefLevel x, RefLevel y) {
        List<String> ps = paramsOf(x, y);
        int[] vals = {0, 1, bound(x, y)};
        if (ps.size() > 7) {
            return sampled(ps, vals, e -> RefLevel.eval(x, e) >= RefLevel.eval(y, e));
        }
        Map<String, Integer> env = new HashMap<>();
        return reduced(ps, 0, vals, env, e -> RefLevel.eval(x, e) >= RefLevel.eval(y, e));
    }

    /**
     * More than 7 parameters (3^n assignments is too many): the witnesses of the canonical-form
     * argument (one parameter large, a random subset at 1, the rest 0) plus random assignments. Not
     * exhaustive.
     */
    private static boolean sampled(List<String> params, int[] vals, Assignment f) {
        Random rnd = new Random(params.size() * 31L + vals[2]);
        Map<String, Integer> env = new HashMap<>();
        for (int it = 0; it < 3000; it++) {
            int big = it % (params.size() + 1);
            int density = 1 + rnd.nextInt(4);
            for (int i = 0; i < params.size(); i++) {
                int v;
                if (it < 2000) {
                    v = i == big ? vals[2] : (rnd.nextInt(4) < density ? 1 : 0);
                } else {
                    v = vals[rnd.nextInt(3)];
                }
                env.put(params.get(i), v);
            }
            if (!f.test(env)) {
                return false;
            }
        }
        return true;
    }

    private static boolean reduced(List<String> params, int i, int[] vals, Map<String, Integer> env, Assignment f) {
        if (i == params.size()) {
            return f.test(env);
        }
        for (int v : vals) {
            env.put(params.get(i), v);
            if (!reduced(params, i + 1, vals, env, f)) {
                return false;
            }
        }
        return true;
    }

    static boolean semNeverZero(RefLevel x) {
        return forAll(paramsOf(x), bound(x), env -> RefLevel.eval(x, env) != 0);
    }

    static boolean semAlwaysZero(RefLevel x) {
        return forAll(paramsOf(x), bound(x), env -> RefLevel.eval(x, env) == 0);
    }
}

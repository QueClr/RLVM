package rlvm.inductive;

import java.util.HashMap;
import rlvm.level.LevelStore;
import rlvm.store.DeclKind;
import rlvm.store.Kind;

/**
 * Test-only writer of {@code lean4export} NDJSON (format 3.1.0) from a fixture {@link Env}: the
 * fixture's declarations in file order, with the recursor records of every inductive block taken
 * from what this module DERIVED. Fed to the official checker ({@code leanchecker --from-export}),
 * which regenerates the recursors itself and requires the exported records to be identical: an
 * accepted file proves that the derived facts, types and rules are the official ones.
 */
final class Ndjson {

    private final Env e;
    private final StringBuilder out = new StringBuilder();
    private final HashMap<Integer, Integer> names = new HashMap<>();
    private final HashMap<Integer, Integer> levels = new HashMap<>();
    private final HashMap<Integer, Integer> exprs = new HashMap<>();
    private int nextExpr;
    private int binderName;

    Ndjson(Env e) {
        this.e = e;
        out.append("{\"meta\":{\"exporter\":{\"name\":\"lean4export\",\"version\":\"3.1.0\"},\"format\":{\"version\":\"3.1.0\"},")
                .append("\"lean\":{\"githash\":\"0000000000000000000000000000000000000000\",\"version\":\"4.36.0\"}}}\n");
        names.put(0, 0);
        levels.put(LevelStore.ZERO, 0);
        binderName = name(TermParser.internName(e.names, "x"));
    }

    private static String quote(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') {
                b.append('\\').append(c);
            } else if (c < 0x20) {
                b.append(String.format("\\u%04x", (int) c));
            } else {
                b.append(c);
            }
        }
        return b.append('"').toString();
    }

    int name(int id) {
        Integer k = names.get(id);
        if (k != null) {
            return k;
        }
        int prefix = e.names.prefix(id);
        int p = name(prefix);
        int idx = names.size();
        if (e.names.isNumeric(id)) {
            out.append("{\"in\":").append(idx).append(",\"num\":{\"i\":").append(e.names.numericValue(id)).append(",\"pre\":")
                    .append(p).append("}}\n");
        } else {
            String full = e.names.toString(id);
            String comp = prefix == 0 ? full : full.substring(e.names.toString(prefix).length() + 1);
            out.append("{\"in\":").append(idx).append(",\"str\":{\"pre\":").append(p).append(",\"str\":").append(quote(comp))
                    .append("}}\n");
        }
        names.put(id, idx);
        return idx;
    }

    private String nameList(int list) {
        StringBuilder b = new StringBuilder("[");
        int n = e.levels.length(list);
        for (int i = 0; i < n; i++) {
            b.append(i > 0 ? "," : "").append(name(e.levels.get(list, i)));
        }
        return b.append(']').toString();
    }

    int level(int l) {
        Integer k = levels.get(l);
        if (k != null) {
            return k;
        }
        LevelStore ls = e.levels.store();
        String body;
        switch (ls.kind(l)) {
            case LevelStore.KIND_SUCC:
                body = "\"succ\":" + level(ls.succOf(l));
                break;
            case LevelStore.KIND_MAX:
                body = "\"max\":[" + level(ls.lhs(l)) + "," + level(ls.rhs(l)) + "]";
                break;
            case LevelStore.KIND_IMAX:
                body = "\"imax\":[" + level(ls.lhs(l)) + "," + level(ls.rhs(l)) + "]";
                break;
            case LevelStore.KIND_PARAM:
                body = "\"param\":" + name(ls.paramName(l));
                break;
            default:
                throw new IllegalStateException("level kind " + ls.kind(l));
        }
        int idx = levels.size();
        out.append("{\"il\":").append(idx).append(',').append(body).append("}\n");
        levels.put(l, idx);
        return idx;
    }

    int expr(int t) {
        Integer k = exprs.get(t);
        if (k != null) {
            return k;
        }
        String body;
        switch (e.terms.kind(t)) {
            case Kind.BVAR:
                body = "\"bvar\":" + e.terms.a(t);
                break;
            case Kind.SORT:
                body = "\"sort\":" + level(e.terms.a(t));
                break;
            case Kind.CONST: {
                StringBuilder us = new StringBuilder("[");
                int l = e.terms.b(t);
                int n = e.levels.length(l);
                for (int i = 0; i < n; i++) {
                    us.append(i > 0 ? "," : "").append(level(e.levels.get(l, i)));
                }
                body = "\"const\":{\"name\":" + name(e.terms.c(t)) + ",\"us\":" + us + "]}";
                break;
            }
            case Kind.APP:
                body = "\"app\":{\"arg\":" + expr(e.terms.b(t)) + ",\"fn\":" + expr(e.terms.a(t)) + "}";
                break;
            case Kind.LAM:
            case Kind.PI: {
                int d = expr(e.terms.a(t));
                int b = expr(e.terms.b(t));
                body = (e.terms.kind(t) == Kind.LAM ? "\"lam\"" : "\"forallE\"") + ":{\"binderInfo\":\"default\",\"body\":" + b
                        + ",\"name\":" + binderName + ",\"type\":" + d + "}";
                break;
            }
            case Kind.LET: {
                int ty = expr(e.terms.a(t));
                int v = expr(e.terms.b(t));
                int b = expr(e.terms.c(t));
                body = "\"letE\":{\"body\":" + b + ",\"name\":" + binderName + ",\"nondep\":false,\"type\":" + ty + ",\"value\":" + v
                        + "}";
                break;
            }
            default:
                throw new IllegalStateException("node kind " + e.terms.kind(t));
        }
        int idx = nextExpr++;
        out.append("{\"ie\":").append(idx).append(',').append(body).append("}\n");
        exprs.put(t, idx);
        return idx;
    }

    /** A single-constant unit (definition, axiom, quot). */
    void constant(int cid) {
        int kind = e.decls.kind(cid);
        int n = name(e.decls.name(cid));
        String lps = nameList(e.decls.levelParams(cid));
        int type = expr(e.decls.type(cid));
        switch (kind) {
            case DeclKind.DEFN: {
                int value = expr(e.decls.value(cid));
                out.append("{\"def\":{\"all\":[").append(n).append("],\"hints\":\"abbrev\",\"levelParams\":").append(lps)
                        .append(",\"name\":").append(n).append(",\"safety\":\"safe\",\"type\":").append(type).append(",\"value\":")
                        .append(value).append("}}\n");
                break;
            }
            case DeclKind.AXIOM:
                out.append("{\"axiom\":{\"isUnsafe\":false,\"levelParams\":").append(lps).append(",\"name\":").append(n)
                        .append(",\"type\":").append(type).append("}}\n");
                break;
            case DeclKind.QUOT_TYPE:
            case DeclKind.QUOT_MK:
            case DeclKind.QUOT_LIFT:
            case DeclKind.QUOT_IND: {
                String k = kind == DeclKind.QUOT_TYPE ? "type" : kind == DeclKind.QUOT_MK ? "ctor"
                        : kind == DeclKind.QUOT_LIFT ? "lift" : "ind";
                out.append("{\"quot\":{\"kind\":\"").append(k).append("\",\"levelParams\":").append(lps).append(",\"name\":")
                        .append(n).append(",\"type\":").append(type).append("}}\n");
                break;
            }
            default:
                throw new IllegalStateException("kind " + kind);
        }
    }

    /**
     * An inductive item. With {@code g != null} the constructor and recursor records carry the derived
     * values; with {@code g == null} (a block this module rejects) the records are placeholders.
     */
    void inductive(Env.Block b, GeneratedBlock g) {
        BlockInput in = b.in;
        String lps = nameList(in.levelParams);
        StringBuilder all = new StringBuilder("[");
        for (int k = 0; k < in.typeCount; k++) {
            all.append(k > 0 ? "," : "").append(name(in.typeName[k]));
        }
        all.append(']');
        StringBuilder types = new StringBuilder();
        StringBuilder ctors = new StringBuilder();
        for (int k = 0; k < in.typeCount; k++) {
            int type = expr(in.typeType[k]);
            StringBuilder cs = new StringBuilder("[");
            for (int i = 0; i < in.typeCtorCount[k]; i++) {
                int c = in.typeCtorStart[k] + i;
                cs.append(i > 0 ? "," : "").append(name(in.ctorName[c]));
                int ct = expr(in.ctorType[c]);
                int arity = 0;
                for (int t = in.ctorType[c]; e.terms.kind(t) == Kind.PI; t = e.terms.b(t)) {
                    arity++;
                }
                int nf = g != null ? g.ctorNumFields[c] : Math.max(0, arity - in.numParams);
                ctors.append(ctors.length() > 0 ? "," : "").append("{\"cidx\":").append(i).append(",\"induct\":")
                        .append(name(in.typeName[k])).append(",\"isUnsafe\":false,\"levelParams\":").append(lps).append(",\"name\":")
                        .append(name(in.ctorName[c])).append(",\"numFields\":").append(nf).append(",\"numParams\":")
                        .append(in.numParams).append(",\"type\":").append(ct).append('}');
            }
            cs.append(']');
            types.append(k > 0 ? "," : "").append("{\"all\":").append(all).append(",\"ctors\":").append(cs).append(",\"isRec\":")
                    .append(g != null && g.isRec).append(",\"isReflexive\":").append(g != null && g.isReflexive)
                    .append(",\"isUnsafe\":false,\"levelParams\":").append(lps).append(",\"name\":").append(name(in.typeName[k]))
                    .append(",\"numIndices\":").append(g != null ? g.typeNumIndices[k] : 0).append(",\"numNested\":")
                    .append(g != null ? g.numNested : 0).append(",\"numParams\":").append(in.numParams).append(",\"type\":")
                    .append(type).append('}');
        }
        StringBuilder recs = new StringBuilder();
        if (g != null) {
            for (int j = 0; j < g.recCount; j++) {
                int rname = e.names.internStr(g.recNamePrefix[j], g.recNameLast[j].getBytes(), 0, g.recNameLast[j].length());
                StringBuilder rules = new StringBuilder("[");
                for (int i = 0; i < g.recRuleCount[j]; i++) {
                    rules.append(i > 0 ? "," : "").append("{\"ctor\":").append(name(e.decls.name(g.recRuleCtor[j][i])))
                            .append(",\"nfields\":").append(g.recRuleFields[j][i]).append(",\"rhs\":")
                            .append(expr(g.recRuleRhs[j][i])).append('}');
                }
                rules.append(']');
                recs.append(j > 0 ? "," : "").append("{\"all\":").append(all).append(",\"isUnsafe\":false,\"k\":").append(g.k)
                        .append(",\"levelParams\":").append(nameList(g.recLevelParams)).append(",\"name\":").append(name(rname))
                        .append(",\"numIndices\":").append(g.recNumIndices[j]).append(",\"numMinors\":").append(g.recNumMinors)
                        .append(",\"numMotives\":").append(g.recNumMotives).append(",\"numParams\":").append(in.numParams)
                        .append(",\"rules\":").append(rules).append(",\"type\":").append(expr(g.recType[j])).append('}');
            }
        } else {
            for (int r = 0; r < in.recCount; r++) {
                recs.append(r > 0 ? "," : "").append("{\"all\":").append(all).append(",\"isUnsafe\":false,\"k\":false")
                        .append(",\"levelParams\":").append(nameList(in.recLevelParams[r])).append(",\"name\":")
                        .append(name(in.recName[r])).append(",\"numIndices\":0,\"numMinors\":0,\"numMotives\":").append(in.typeCount)
                        .append(",\"numParams\":").append(in.numParams).append(",\"rules\":[],\"type\":").append(expr(in.recType[r]))
                        .append('}');
            }
        }
        out.append("{\"inductive\":{\"ctors\":[").append(ctors).append("],\"recs\":[").append(recs).append("],\"types\":[")
                .append(types).append("]}}\n");
    }

    String text() {
        return out.toString();
    }
}

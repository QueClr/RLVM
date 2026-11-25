package rlvm.testkit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * An export file as the mutator sees it: its lines, each parsed as JSON, and for every line the
 * roles of its fields according to the lean4export 3.1.0 schema
 * (*agentic-note*: study-arena-and-export-format, section 2.3). Only syntax is modelled; nothing
 * here knows what a term means.
 */
final class ExportModel {

    /** What a line is. */
    enum RecordKind { META, NAME, LEVEL, EXPR, DECL, UNKNOWN }

    /** The three id spaces of an export. */
    enum Space { NAME, LEVEL, EXPR }

    /** What a field of a record is. */
    enum Role {
        /** The id a record defines ({@code in}, {@code il}, {@code ie}). */
        DEF_NAME, DEF_LEVEL, DEF_EXPR,
        /** A reference into one of the id spaces. */
        REF_NAME, REF_LEVEL, REF_EXPR,
        /** Binder name of lam / forallE / letE (a name reference that no checker should care about). */
        BINDER_NAME,
        /** A derived number of an inductive record: numFields, numParams, cidx, numIndices, numMinors, numMotives, numNested, nfields. */
        COUNT,
        /** A number that is part of a term: de Bruijn index, projection field index. */
        INDEX,
        /** A boolean: k, isRec, isReflexive, isUnsafe. */
        FLAG,
        /** {@code safety} of a definition. */
        SAFETY,
        /** {@code hints} of a definition. */
        HINT,
        NAT_LIT, STR_LIT, BINDER_INFO, NONDEP,
        /** Component of a name record: the string, or the number. */
        NAME_STR, NAME_NUM,
        /** {@code kind} of a quot record. */
        QUOT_KIND,
        /** The {@code rules} array of a recursor. */
        RULES
    }

    /** One field of one record: a slot in the JSON tree with its role. */
    static final class Field {
        final Role role;
        final Json parent;
        /** Member name when the slot is an object member, else null. */
        final String key;
        /** Element index when the slot is an array element. */
        final int index;
        /** Unique path within the record, e.g. {@code app.fn}, {@code const.us[1]}, {@code inductive.recs[0].rules[1].rhs}. */
        final String path;

        Field(Role role, Json parent, String key, int index, String path) {
            this.role = role;
            this.parent = parent;
            this.key = key;
            this.index = index;
            this.path = path;
        }

        Json value() {
            return key != null ? parent.get(key) : parent.at(index);
        }

        void set(Json v) {
            if (key != null) {
                parent.put(key, v);
            } else {
                parent.set(index, v);
            }
        }

        Space space() {
            switch (role) {
                case DEF_NAME:
                case REF_NAME:
                case BINDER_NAME:
                    return Space.NAME;
                case DEF_LEVEL:
                case REF_LEVEL:
                    return Space.LEVEL;
                case DEF_EXPR:
                case REF_EXPR:
                    return Space.EXPR;
                default:
                    return null;
            }
        }
    }

    /** The analysis of one line. */
    static final class Rec {
        RecordKind kind = RecordKind.UNKNOWN;
        /** NAME / LEVEL / EXPR: the id defined; else -1. */
        long id = -1;
        /** DECL: axiom, def, thm, opaque, quot, inductive. EXPR: app, lam, ... Else null. */
        String tag;
        /** False if the record has members the schema does not know (then RENUMBER_IDS is not applied to the file). */
        boolean strict = true;
        final List<Field> fields = new ArrayList<>();

        Field find(String path) {
            for (Field f : fields) {
                if (f.path.equals(path)) {
                    return f;
                }
            }
            return null;
        }
    }

    final List<String> lines;
    final boolean finalNewline;
    /** Parsed lines; null where a line is not JSON. */
    final Json[] json;
    final Rec[] recs;
    /** Per space: ids in file order and the line (0-based) that defines each. */
    private final long[][] ids = new long[3][];
    private final int[][] idLines = new int[3][];
    /** 0-based indexes of declaration lines, in file order. */
    final int[] declLines;

    private ExportModel(List<String> lines, boolean finalNewline) {
        this.lines = lines;
        this.finalNewline = finalNewline;
        int n = lines.size();
        json = new Json[n];
        recs = new Rec[n];
        List<List<long[]>> defs = new ArrayList<>();
        for (int s = 0; s < 3; s++) {
            defs.add(new ArrayList<>());
        }
        List<Integer> decls = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            try {
                json[i] = Json.parse(lines.get(i));
            } catch (Json.ParseException e) {
                json[i] = null;
            }
            recs[i] = analyse(json[i], i == 0);
            switch (recs[i].kind) {
                case NAME:
                    defs.get(0).add(new long[] {recs[i].id, i});
                    break;
                case LEVEL:
                    defs.get(1).add(new long[] {recs[i].id, i});
                    break;
                case EXPR:
                    defs.get(2).add(new long[] {recs[i].id, i});
                    break;
                case DECL:
                    decls.add(i);
                    break;
                default:
                    break;
            }
        }
        for (int s = 0; s < 3; s++) {
            List<long[]> d = defs.get(s);
            ids[s] = new long[d.size()];
            idLines[s] = new int[d.size()];
            for (int k = 0; k < d.size(); k++) {
                ids[s][k] = d.get(k)[0];
                idLines[s][k] = (int) d.get(k)[1];
            }
        }
        declLines = new int[decls.size()];
        for (int k = 0; k < declLines.length; k++) {
            declLines[k] = decls.get(k);
        }
    }

    static ExportModel read(Path file) throws IOException {
        return parse(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
    }

    static ExportModel parse(String content) {
        List<String> lines = new ArrayList<>();
        int start = 0;
        while (start < content.length()) {
            int nl = content.indexOf('\n', start);
            if (nl < 0) {
                lines.add(content.substring(start));
                start = content.length();
            } else {
                lines.add(content.substring(start, nl));
                start = nl + 1;
            }
        }
        boolean finalNewline = content.isEmpty() || content.charAt(content.length() - 1) == '\n';
        return new ExportModel(lines, finalNewline);
    }

    String content() {
        return join(lines, "\n", finalNewline);
    }

    static String join(List<String> lines, String separator, boolean finalNewline) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            sb.append(lines.get(i));
            if (i + 1 < lines.size() || finalNewline) {
                sb.append(separator);
            }
        }
        return sb.toString();
    }

    /** Number of ids of {@code space} defined on lines before {@code line}. */
    int definedBefore(Space space, int line) {
        int[] l = idLines[space.ordinal()];
        int lo = 0;
        int hi = l.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (l[mid] < line) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    /** The {@code k}-th id of {@code space} in file order. */
    long idAt(Space space, int k) {
        return ids[space.ordinal()][k];
    }

    int idCount(Space space) {
        return ids[space.ordinal()].length;
    }

    /** True if every line was recognised completely. */
    boolean strict() {
        for (int i = 1; i < recs.length; i++) {
            if (recs[i].kind == RecordKind.UNKNOWN || !recs[i].strict) {
                return false;
            }
        }
        return true;
    }

    // ---- schema ---------------------------------------------------------------------------------------

    /** Classifies one parsed line and lists its fields. {@code first}: line 1, which is the meta line by position. */
    static Rec analyse(Json o, boolean first) {
        Rec r = new Rec();
        if (first) {
            r.kind = RecordKind.META;
            return r;
        }
        if (o == null || !o.isObject()) {
            return r;
        }
        if (o.has("in")) {
            nameRecord(o, r);
        } else if (o.has("il")) {
            levelRecord(o, r);
        } else if (o.has("ie")) {
            exprRecord(o, r);
        } else {
            declRecord(o, r);
        }
        if (r.kind != RecordKind.DECL && r.kind != RecordKind.UNKNOWN && r.id < 0) {
            r.kind = RecordKind.UNKNOWN;
        }
        return r;
    }

    private static long idOf(Json o, String key) {
        Json v = o.get(key);
        return v == null ? -1 : v.asLong();
    }

    private static void nameRecord(Json o, Rec r) {
        r.kind = RecordKind.NAME;
        r.id = idOf(o, "in");
        r.fields.add(new Field(Role.DEF_NAME, o, "in", -1, "in"));
        for (int i = 0; i < o.size(); i++) {
            String k = o.key(i);
            Json v = o.at(i);
            if (k.equals("in")) {
                continue;
            }
            if (k.equals("str") && v.isObject()) {
                r.tag = "str";
                member(r, v, "pre", Role.REF_NAME, "str.pre", true);
                memberStr(r, v, "str", Role.NAME_STR, "str.str");
                unknownMembers(r, v, "pre", "str");
            } else if (k.equals("num") && v.isObject()) {
                r.tag = "num";
                member(r, v, "pre", Role.REF_NAME, "num.pre", true);
                member(r, v, "i", Role.NAME_NUM, "num.i", false);
                unknownMembers(r, v, "pre", "i");
            } else {
                r.strict = false;
            }
        }
        if (r.tag == null) {
            r.kind = RecordKind.UNKNOWN;
        }
    }

    private static void levelRecord(Json o, Rec r) {
        r.kind = RecordKind.LEVEL;
        r.id = idOf(o, "il");
        r.fields.add(new Field(Role.DEF_LEVEL, o, "il", -1, "il"));
        for (int i = 0; i < o.size(); i++) {
            String k = o.key(i);
            Json v = o.at(i);
            if (k.equals("il")) {
                continue;
            }
            if (k.equals("succ") && v.isNatural()) {
                r.tag = k;
                r.fields.add(new Field(Role.REF_LEVEL, o, "succ", -1, "succ"));
            } else if ((k.equals("max") || k.equals("imax")) && v.isArray()) {
                r.tag = k;
                refArray(r, v, Role.REF_LEVEL, k);
            } else if (k.equals("param") && v.isNatural()) {
                r.tag = k;
                r.fields.add(new Field(Role.REF_NAME, o, "param", -1, "param"));
            } else {
                r.strict = false;
            }
        }
        if (r.tag == null) {
            r.kind = RecordKind.UNKNOWN;
        }
    }

    private static void exprRecord(Json o, Rec r) {
        r.kind = RecordKind.EXPR;
        r.id = idOf(o, "ie");
        r.fields.add(new Field(Role.DEF_EXPR, o, "ie", -1, "ie"));
        for (int i = 0; i < o.size(); i++) {
            String k = o.key(i);
            Json v = o.at(i);
            if (k.equals("ie")) {
                continue;
            }
            switch (k) {
                case "bvar":
                    if (v.isNatural()) {
                        r.tag = k;
                        r.fields.add(new Field(Role.INDEX, o, k, -1, "bvar"));
                    } else {
                        r.strict = false;
                    }
                    break;
                case "sort":
                    if (v.isNatural()) {
                        r.tag = k;
                        r.fields.add(new Field(Role.REF_LEVEL, o, k, -1, "sort"));
                    } else {
                        r.strict = false;
                    }
                    break;
                case "natVal":
                    r.tag = k;
                    if (v.isString()) {
                        r.fields.add(new Field(Role.NAT_LIT, o, k, -1, "natVal"));
                    } else {
                        r.strict = false;
                    }
                    break;
                case "strVal":
                    r.tag = k;
                    if (v.isString()) {
                        r.fields.add(new Field(Role.STR_LIT, o, k, -1, "strVal"));
                    } else {
                        r.strict = false;
                    }
                    break;
                case "const":
                    if (v.isObject()) {
                        r.tag = k;
                        member(r, v, "name", Role.REF_NAME, "const.name", true);
                        Json us = v.get("us");
                        if (us != null && us.isArray()) {
                            refArray(r, us, Role.REF_LEVEL, "const.us");
                        } else {
                            r.strict = false;
                        }
                        unknownMembers(r, v, "name", "us");
                    } else {
                        r.strict = false;
                    }
                    break;
                case "app":
                    if (v.isObject()) {
                        r.tag = k;
                        member(r, v, "fn", Role.REF_EXPR, "app.fn", true);
                        member(r, v, "arg", Role.REF_EXPR, "app.arg", true);
                        unknownMembers(r, v, "fn", "arg");
                    } else {
                        r.strict = false;
                    }
                    break;
                case "lam":
                case "forallE":
                    if (v.isObject()) {
                        r.tag = k;
                        member(r, v, "type", Role.REF_EXPR, k + ".type", true);
                        member(r, v, "body", Role.REF_EXPR, k + ".body", true);
                        member(r, v, "name", Role.BINDER_NAME, k + ".name", true);
                        memberStr(r, v, "binderInfo", Role.BINDER_INFO, k + ".binderInfo");
                        unknownMembers(r, v, "type", "body", "name", "binderInfo");
                    } else {
                        r.strict = false;
                    }
                    break;
                case "letE":
                    if (v.isObject()) {
                        r.tag = k;
                        member(r, v, "type", Role.REF_EXPR, "letE.type", true);
                        member(r, v, "value", Role.REF_EXPR, "letE.value", true);
                        member(r, v, "body", Role.REF_EXPR, "letE.body", true);
                        member(r, v, "name", Role.BINDER_NAME, "letE.name", true);
                        memberBool(r, v, "nondep", Role.NONDEP, "letE.nondep");
                        unknownMembers(r, v, "type", "value", "body", "name", "nondep");
                    } else {
                        r.strict = false;
                    }
                    break;
                case "proj":
                    if (v.isObject()) {
                        r.tag = k;
                        member(r, v, "struct", Role.REF_EXPR, "proj.struct", true);
                        member(r, v, "typeName", Role.REF_NAME, "proj.typeName", true);
                        member(r, v, "idx", Role.INDEX, "proj.idx", false);
                        unknownMembers(r, v, "struct", "typeName", "idx");
                    } else {
                        r.strict = false;
                    }
                    break;
                case "mdata":
                    if (v.isObject()) {
                        r.tag = k;
                        member(r, v, "expr", Role.REF_EXPR, "mdata.expr", true);
                        // the metadata itself is opaque: a renumbering cannot be applied safely
                        r.strict = false;
                    } else {
                        r.strict = false;
                    }
                    break;
                default:
                    r.strict = false;
            }
        }
        if (r.tag == null) {
            r.kind = RecordKind.UNKNOWN;
        }
    }

    private static void declRecord(Json o, Rec r) {
        if (o.size() != 1) {
            return;
        }
        String k = o.key(0);
        Json v = o.at(0);
        if (!v.isObject()) {
            return;
        }
        switch (k) {
            case "axiom":
                r.kind = RecordKind.DECL;
                r.tag = k;
                memberBool(r, v, "isUnsafe", Role.FLAG, "axiom.isUnsafe");
                declCommon(r, v, k, false, false);
                unknownMembers(r, v, "isUnsafe", "levelParams", "name", "type");
                break;
            case "def":
                r.kind = RecordKind.DECL;
                r.tag = k;
                declCommon(r, v, k, true, true);
                if (v.has("hints")) {
                    r.fields.add(new Field(Role.HINT, v, "hints", -1, "def.hints"));
                } else {
                    r.strict = false;
                }
                memberStr(r, v, "safety", Role.SAFETY, "def.safety");
                unknownMembers(r, v, "all", "hints", "levelParams", "name", "safety", "type", "value");
                break;
            case "thm":
                r.kind = RecordKind.DECL;
                r.tag = k;
                declCommon(r, v, k, true, true);
                unknownMembers(r, v, "all", "levelParams", "name", "type", "value");
                break;
            case "opaque":
                r.kind = RecordKind.DECL;
                r.tag = k;
                declCommon(r, v, k, true, true);
                if (v.has("isUnsafe")) {
                    memberBool(r, v, "isUnsafe", Role.FLAG, "opaque.isUnsafe");
                }
                unknownMembers(r, v, "all", "isUnsafe", "levelParams", "name", "type", "value");
                break;
            case "quot":
                r.kind = RecordKind.DECL;
                r.tag = k;
                declCommon(r, v, k, false, false);
                memberStr(r, v, "kind", Role.QUOT_KIND, "quot.kind");
                unknownMembers(r, v, "kind", "levelParams", "name", "type");
                break;
            case "inductive":
                r.kind = RecordKind.DECL;
                r.tag = k;
                inductive(r, v);
                break;
            default:
                break;
        }
    }

    /** name, levelParams, type and optionally value, all of a declaration-like object. */
    private static void declCommon(Rec r, Json v, String prefix, boolean hasValue, boolean hasAll) {
        member(r, v, "name", Role.REF_NAME, prefix + ".name", true);
        nameArray(r, v, "levelParams", prefix + ".levelParams");
        member(r, v, "type", Role.REF_EXPR, prefix + ".type", true);
        if (hasValue) {
            member(r, v, "value", Role.REF_EXPR, prefix + ".value", true);
        }
        if (hasAll) {
            nameArray(r, v, "all", prefix + ".all");
        }
    }

    private static void inductive(Rec r, Json v) {
        Json types = v.get("types");
        Json ctors = v.get("ctors");
        Json recsArr = v.get("recs");
        if (types == null || !types.isArray() || ctors == null || !ctors.isArray() || recsArr == null || !recsArr.isArray()) {
            r.strict = false;
        }
        unknownMembers(r, v, "types", "ctors", "recs");
        if (types != null && types.isArray()) {
            for (int i = 0; i < types.size(); i++) {
                Json t = types.at(i);
                String p = "inductive.types[" + i + "]";
                if (!t.isObject()) {
                    r.strict = false;
                    continue;
                }
                declCommon(r, t, p, false, true);
                nameArray(r, t, "ctors", p + ".ctors");
                memberBool(r, t, "isRec", Role.FLAG, p + ".isRec");
                memberBool(r, t, "isReflexive", Role.FLAG, p + ".isReflexive");
                memberBool(r, t, "isUnsafe", Role.FLAG, p + ".isUnsafe");
                member(r, t, "numIndices", Role.COUNT, p + ".numIndices", false);
                member(r, t, "numNested", Role.COUNT, p + ".numNested", false);
                member(r, t, "numParams", Role.COUNT, p + ".numParams", false);
                unknownMembers(r, t, "all", "ctors", "isRec", "isReflexive", "isUnsafe", "levelParams", "name", "numIndices",
                        "numNested", "numParams", "type");
            }
        }
        if (ctors != null && ctors.isArray()) {
            for (int i = 0; i < ctors.size(); i++) {
                Json c = ctors.at(i);
                String p = "inductive.ctors[" + i + "]";
                if (!c.isObject()) {
                    r.strict = false;
                    continue;
                }
                declCommon(r, c, p, false, false);
                member(r, c, "induct", Role.REF_NAME, p + ".induct", true);
                memberBool(r, c, "isUnsafe", Role.FLAG, p + ".isUnsafe");
                member(r, c, "cidx", Role.COUNT, p + ".cidx", false);
                member(r, c, "numFields", Role.COUNT, p + ".numFields", false);
                member(r, c, "numParams", Role.COUNT, p + ".numParams", false);
                unknownMembers(r, c, "cidx", "induct", "isUnsafe", "levelParams", "name", "numFields", "numParams", "type");
            }
        }
        if (recsArr != null && recsArr.isArray()) {
            for (int i = 0; i < recsArr.size(); i++) {
                Json c = recsArr.at(i);
                String p = "inductive.recs[" + i + "]";
                if (!c.isObject()) {
                    r.strict = false;
                    continue;
                }
                declCommon(r, c, p, false, true);
                memberBool(r, c, "isUnsafe", Role.FLAG, p + ".isUnsafe");
                memberBool(r, c, "k", Role.FLAG, p + ".k");
                member(r, c, "numIndices", Role.COUNT, p + ".numIndices", false);
                member(r, c, "numMinors", Role.COUNT, p + ".numMinors", false);
                member(r, c, "numMotives", Role.COUNT, p + ".numMotives", false);
                member(r, c, "numParams", Role.COUNT, p + ".numParams", false);
                Json rules = c.get("rules");
                if (rules != null && rules.isArray()) {
                    r.fields.add(new Field(Role.RULES, c, "rules", -1, p + ".rules"));
                    for (int k = 0; k < rules.size(); k++) {
                        Json rule = rules.at(k);
                        String q = p + ".rules[" + k + "]";
                        if (!rule.isObject()) {
                            r.strict = false;
                            continue;
                        }
                        member(r, rule, "ctor", Role.REF_NAME, q + ".ctor", true);
                        member(r, rule, "nfields", Role.COUNT, q + ".nfields", false);
                        member(r, rule, "rhs", Role.REF_EXPR, q + ".rhs", true);
                        unknownMembers(r, rule, "ctor", "nfields", "rhs");
                    }
                } else {
                    r.strict = false;
                }
                unknownMembers(r, c, "all", "isUnsafe", "k", "levelParams", "name", "numIndices", "numMinors", "numMotives",
                        "numParams", "rules", "type");
            }
        }
    }

    private static void member(Rec r, Json obj, String key, Role role, String path, boolean reference) {
        Json v = obj.get(key);
        if (v != null && v.isNatural() && (!reference || v.asLong() >= 0)) {
            r.fields.add(new Field(role, obj, key, -1, path));
        } else {
            r.strict = false;
        }
    }

    private static void memberStr(Rec r, Json obj, String key, Role role, String path) {
        Json v = obj.get(key);
        if (v != null && v.isString()) {
            r.fields.add(new Field(role, obj, key, -1, path));
        } else {
            r.strict = false;
        }
    }

    private static void memberBool(Rec r, Json obj, String key, Role role, String path) {
        Json v = obj.get(key);
        if (v != null && v.isBool()) {
            r.fields.add(new Field(role, obj, key, -1, path));
        } else {
            r.strict = false;
        }
    }

    private static void nameArray(Rec r, Json obj, String key, String path) {
        Json v = obj.get(key);
        if (v != null && v.isArray()) {
            refArray(r, v, Role.REF_NAME, path);
        } else {
            r.strict = false;
        }
    }

    private static void refArray(Rec r, Json array, Role role, String path) {
        for (int i = 0; i < array.size(); i++) {
            Json e = array.at(i);
            if (e.isNatural() && e.asLong() >= 0) {
                r.fields.add(new Field(role, array, null, i, path + "[" + i + "]"));
            } else {
                r.strict = false;
            }
        }
    }

    private static void unknownMembers(Rec r, Json obj, String... known) {
        outer:
        for (int i = 0; i < obj.size(); i++) {
            String k = obj.key(i);
            for (String s : known) {
                if (s.equals(k)) {
                    continue outer;
                }
            }
            r.strict = false;
        }
    }
}

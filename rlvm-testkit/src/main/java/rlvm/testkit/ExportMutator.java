package rlvm.testkit;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import rlvm.testkit.ExportModel.Field;
import rlvm.testkit.ExportModel.Rec;
import rlvm.testkit.ExportModel.RecordKind;
import rlvm.testkit.ExportModel.Role;
import rlvm.testkit.ExportModel.Space;

/**
 * Produces variants of an export file for differential testing against the official checker. A
 * mutation is a small, seeded edit (usually of one line); the mutated file is syntactically valid
 * NDJSON (every line is one JSON value), so that the interesting question is the verdict.
 *
 * <p>Determinism: mutant number {@code i} of {@code (seed, file name of the source, kind)} depends on
 * nothing else, so a campaign can be regenerated bit for bit, and the first {@code n} mutants of a
 * larger request equal a request for {@code n}.
 *
 * <p>A line the mutator edits is re-serialised in lean4export's canonical form (compact, raw UTF-8);
 * for exporter output that changes nothing but the edit, for the few hand-formatted test files it
 * also normalises the spacing of that one line. All other lines are copied byte for byte.
 */
public final class ExportMutator {

    /** Kinds of edit. */
    public enum Mutation {
        /** Replace a child id of an expression record by another earlier id. */
        SWAP_CHILD(false),
        /** Replace a universe level reference by another earlier level id. */
        SWAP_LEVEL(false),
        /** Change a derived number of an inductive record (numFields, nfields, numIndices, numMotives, ...). */
        FORGE_COUNT(false),
        /** Flip a derived flag (k, isRec, isReflexive, isUnsafe) or change the safety of a definition. */
        FORGE_FLAG(false),
        /** Redirect a recursor rule's right-hand side to another expression, change its constructor, or drop, duplicate or swap rules. */
        FORGE_RULE(false),
        /** Change the reducibility hint of a definition (must NOT change the verdict). */
        CHANGE_HINT(true),
        /** Rename ids to a sparse, out-of-order numbering (must NOT change the verdict). */
        RENUMBER_IDS(true),
        /** Re-serialise lines with other key order, whitespace and JSON unicode escapes (must NOT change the verdict). */
        REFORMAT_JSON(true),
        /**
         * Move a declaration line behind a later declaration that uses it. The official checker replays
         * by dependency, so its verdict must not change; a checker that works in file order may decline.
         */
        REORDER_DECL(true),
        /** Duplicate a declaration line. */
        DUPLICATE_DECL(false),
        // ---- additions to the first design ---------------------------------------------------------
        /** Replace the binder type of a lam / forallE / letE record by another earlier expression. */
        CHANGE_BINDER_TYPE(false),
        /** Replace a name reference (constant, projection type, level parameter, declared name, induct, ctors, all, rule constructor, name prefix). */
        SWAP_NAME(false),
        /** Replace the type or the value of a declaration (also of a constructor, recursor or inductive type) by another earlier expression. */
        SWAP_DECL_ROOT(false),
        /** Change a de Bruijn index or a projection field index. */
        CHANGE_INDEX(false),
        /** Change a Nat or String literal. */
        CHANGE_LITERAL(false),
        /** Turn a def / thm / opaque record into another kind (or into an axiom, dropping the value); change the kind of a quot record. */
        CHANGE_DECL_KIND(false),
        /** Change binder info, binder name or the nondep flag of a let (must NOT change the verdict). */
        CHANGE_BINDER_INFO(true),
        /** Drop a name, level or expression record. */
        DROP_RECORD(false),
        /** Drop a declaration line. */
        DROP_DECL(false),
        /** Duplicate a name, level or expression record (an id bound twice). */
        DUPLICATE_RECORD(false),
        /** Drop, replace, duplicate, move or edit the meta line. */
        CHANGE_META(false),
        /**
         * Wrap one expression reference (a child of an expression record, a declaration root or a rule
         * right-hand side) in a new {@code mdata} record with empty metadata. Metadata is invisible to
         * type checking, but the official checker compares inductive and quot records with the
         * kernel's own declarations including metadata and tests their spines syntactically: a checker
         * that drops metadata at load must not accept what that comparison rejects (added by the
         * integrator after four hand-written files of this kind were wrongly accepted).
         */
        WRAP_MDATA(false);

        /**
         * True if the official checker's verdict on a mutant of this kind must equal its verdict on
         * the source. The corpus builder reports every exception as an anomaly.
         */
        public final boolean verdictPreserving;

        Mutation(boolean verdictPreserving) {
            this.verdictPreserving = verdictPreserving;
        }
    }

    /** One written mutant. */
    public static final class Mutant {
        public final Path path;
        public final Path source;
        public final Mutation kind;
        public final long seed;
        /** Number of this mutant among the mutants of (seed, source, kind). */
        public final int index;
        /** 1-based line of the source that was edited; 0 for an edit of the whole file. */
        public final int line;
        /** What was changed, e.g. {@code line 7 app.fn: 3 -> 1}. */
        public final String description;
        public final String sha256;
        public final long size;

        Mutant(Path path, Path source, Mutation kind, long seed, int index, int line, String description, String sha256,
                long size) {
            this.path = path;
            this.source = source;
            this.kind = kind;
            this.seed = seed;
            this.index = index;
            this.line = line;
            this.description = description;
            this.sha256 = sha256;
            this.size = size;
        }
    }

    /** The result of applying one mutation in memory. */
    static final class Edit {
        final List<String> lines;
        final String separator;
        final boolean finalNewline;
        final int line;
        final String description;

        Edit(List<String> lines, String separator, boolean finalNewline, int line, String description) {
            this.lines = lines;
            this.separator = separator;
            this.finalNewline = finalNewline;
            this.line = line;
            this.description = description;
        }

        String content() {
            return ExportModel.join(lines, separator, finalNewline);
        }
    }

    /** SplitMix64: small, fast, and the same on every JDK. */
    static final class Rng {
        private long state;

        Rng(long seed) {
            this.state = seed;
        }

        long next() {
            long z = (state += 0x9E3779B97F4A7C15L);
            z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
            z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
            return z ^ (z >>> 31);
        }

        /** Uniform in [0, n). */
        int below(int n) {
            return (int) Long.remainderUnsigned(next(), n);
        }

        boolean chance(int percent) {
            return below(100) < percent;
        }
    }

    private final long seed;

    public ExportMutator(long seed) {
        this.seed = seed;
    }

    /**
     * Writes {@code count} mutants of {@code source} into {@code outDir}; returns their paths. Each
     * mutant differs from the source by one mutation of the given kind. Fewer than {@code count}
     * paths are returned when the source does not offer that many distinct mutants of the kind
     * (none at all, for example, for FORGE_RULE on a file without recursors).
     */
    public List<Path> mutate(Path source, Mutation kind, int count, Path outDir) throws IOException {
        List<Path> paths = new ArrayList<>();
        for (Mutant m : mutants(source, kind, count, outDir)) {
            paths.add(m.path);
        }
        return paths;
    }

    /** As {@link #mutate}, with the description of every mutant. File names: {@code <source stem>.<kind>.s<seed>.<index>.ndjson}. */
    public List<Mutant> mutants(Path source, Mutation kind, int count, Path outDir) throws IOException {
        ExportModel model = ExportModel.read(source);
        String fileName = source.getFileName().toString();
        String stem = fileName.endsWith(".ndjson") ? fileName.substring(0, fileName.length() - ".ndjson".length()) : fileName;
        Files.createDirectories(outDir);
        List<Mutant> result = new ArrayList<>();
        List<Edit> edits = edits(model, stem, kind, count);
        for (Edit e : edits) {
            byte[] bytes = e.content().getBytes(StandardCharsets.UTF_8);
            int index = result.size();
            Path p = outDir.resolve(String.format(Locale.ROOT, "%s.%s.s%d.%04d.ndjson", stem, kind.name().toLowerCase(Locale.ROOT),
                    seed, index));
            Files.write(p, bytes);
            result.add(new Mutant(p, source, kind, seed, index, e.line, e.description, Leanchecker.sha256(bytes), bytes.length));
        }
        return result;
    }

    /** Up to {@code count} distinct edits of {@code model}, none equal to the source; in memory. */
    List<Edit> edits(ExportModel model, String sourceStem, Mutation kind, int count) {
        Sites sites = new Sites(model);
        List<Edit> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        seen.add(model.content());
        int attempts = count * 8 + 16;
        int misses = 0;
        for (int attempt = 0; attempt < attempts && result.size() < count; attempt++) {
            Rng rng = new Rng(mix(seed, fnv1a(sourceStem), kind.ordinal(), attempt));
            Edit e = apply(model, sites, kind, rng);
            if (e == null) {
                if (++misses >= 24 && result.isEmpty()) {
                    break; // the kind does not apply to this file
                }
                continue;
            }
            if (seen.add(e.content())) {
                result.add(e);
            }
        }
        return result;
    }

    static long fnv1a(String s) {
        long h = 0xcbf29ce484222325L;
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            h ^= b & 0xff;
            h *= 0x100000001b3L;
        }
        return h;
    }

    static long mix(long seed, long nameHash, int kind, int attempt) {
        Rng r = new Rng(seed ^ nameHash);
        long a = r.next();
        Rng q = new Rng(a ^ (0x51ED270B0D1F4A3DL * (kind + 1)) ^ (0x2545F4914F6CDD1DL * (attempt + 1)));
        return q.next();
    }

    // ---- sites ----------------------------------------------------------------------------------------

    /** A field of a line, addressed so that it can be found again in a copy of the line. */
    private static final class Site {
        final int line;
        final String path;
        final Role role;

        Site(int line, String path, Role role) {
            this.line = line;
            this.path = path;
            this.role = role;
        }
    }

    /** Site lists per mutation kind, computed once per source. */
    private static final class Sites {
        final EnumMap<Mutation, List<Site>> byKind = new EnumMap<>(Mutation.class);
        final List<Integer> recordLines = new ArrayList<>();
        final List<Integer> convertibleDecls = new ArrayList<>();
        final List<Site> ruleRhs = new ArrayList<>();
        /** Every expression reference of the file (children, declaration roots, rule right-hand sides). */
        final List<Site> exprRefs = new ArrayList<>();
        final List<Site> ruleCtor = new ArrayList<>();
        final List<Site> rules = new ArrayList<>();

        Sites(ExportModel m) {
            for (Mutation k : Mutation.values()) {
                byKind.put(k, new ArrayList<>());
            }
            for (int i = 1; i < m.recs.length; i++) {
                Rec r = m.recs[i];
                if (r.kind == RecordKind.NAME || r.kind == RecordKind.LEVEL || r.kind == RecordKind.EXPR) {
                    recordLines.add(i);
                }
                if (r.kind == RecordKind.DECL && (r.tag.equals("def") || r.tag.equals("thm") || r.tag.equals("opaque")
                        || r.tag.equals("quot"))) {
                    convertibleDecls.add(i);
                }
                for (Field f : r.fields) {
                    Site s = new Site(i, f.path, f.role);
                    boolean inRule = f.path.contains(".rules[");
                    switch (f.role) {
                        case REF_EXPR:
                            exprRefs.add(s);
                            if (r.kind == RecordKind.EXPR) {
                                byKind.get(Mutation.SWAP_CHILD).add(s);
                                if (f.path.endsWith(".type")) {
                                    byKind.get(Mutation.CHANGE_BINDER_TYPE).add(s);
                                }
                            } else if (inRule) {
                                ruleRhs.add(s);
                            } else {
                                byKind.get(Mutation.SWAP_DECL_ROOT).add(s);
                            }
                            break;
                        case REF_LEVEL:
                            byKind.get(Mutation.SWAP_LEVEL).add(s);
                            break;
                        case REF_NAME:
                            if (inRule) {
                                ruleCtor.add(s);
                            } else {
                                byKind.get(Mutation.SWAP_NAME).add(s);
                            }
                            break;
                        case COUNT:
                            byKind.get(Mutation.FORGE_COUNT).add(s);
                            break;
                        case FLAG:
                        case SAFETY:
                            byKind.get(Mutation.FORGE_FLAG).add(s);
                            break;
                        case RULES:
                            rules.add(s);
                            break;
                        case HINT:
                            byKind.get(Mutation.CHANGE_HINT).add(s);
                            break;
                        case INDEX:
                            byKind.get(Mutation.CHANGE_INDEX).add(s);
                            break;
                        case NAT_LIT:
                        case STR_LIT:
                            byKind.get(Mutation.CHANGE_LITERAL).add(s);
                            break;
                        case BINDER_INFO:
                        case NONDEP:
                        case BINDER_NAME:
                            byKind.get(Mutation.CHANGE_BINDER_INFO).add(s);
                            break;
                        default:
                            break;
                    }
                }
            }
        }
    }

    // ---- dispatch --------------------------------------------------------------------------------------

    /** One attempt at a mutation; null if this attempt found nothing to change. */
    private Edit apply(ExportModel m, Sites sites, Mutation kind, Rng rng) {
        switch (kind) {
            case SWAP_CHILD:
            case CHANGE_BINDER_TYPE:
            case SWAP_DECL_ROOT:
                return swapReference(m, sites.byKind.get(kind), rng);
            case SWAP_LEVEL:
            case SWAP_NAME:
                return swapReference(m, sites.byKind.get(kind), rng);
            case FORGE_COUNT:
            case CHANGE_INDEX:
                return changeNumber(m, sites.byKind.get(kind), rng);
            case FORGE_FLAG:
                return forgeFlag(m, sites.byKind.get(kind), rng);
            case FORGE_RULE:
                return forgeRule(m, sites, rng);
            case CHANGE_HINT:
                return changeHint(m, sites.byKind.get(kind), rng);
            case RENUMBER_IDS:
                return renumber(m, rng);
            case REFORMAT_JSON:
                return reformat(m, rng);
            case REORDER_DECL:
                return reorderDecl(m, rng);
            case DUPLICATE_DECL:
                return duplicateLine(m, declLineList(m), rng, "declaration");
            case CHANGE_LITERAL:
                return changeLiteral(m, sites.byKind.get(kind), rng);
            case CHANGE_DECL_KIND:
                return changeDeclKind(m, sites.convertibleDecls, rng);
            case CHANGE_BINDER_INFO:
                return changeBinderInfo(m, sites.byKind.get(kind), rng);
            case DROP_RECORD:
                return dropLine(m, sites.recordLines, rng, "record");
            case DROP_DECL:
                return dropLine(m, declLineList(m), rng, "declaration");
            case DUPLICATE_RECORD:
                return duplicateLine(m, sites.recordLines, rng, "record");
            case CHANGE_META:
                return changeMeta(m, rng);
            case WRAP_MDATA:
                return wrapMData(m, sites, rng);
            default:
                throw new IllegalArgumentException(String.valueOf(kind));
        }
    }

    private static List<Integer> declLineList(ExportModel m) {
        List<Integer> l = new ArrayList<>(m.declLines.length);
        for (int d : m.declLines) {
            l.add(d);
        }
        return l;
    }

    /** A copy of line {@code line} with the field at {@code path} located in the copy. */
    private static final class Target {
        final Json tree;
        final Field field;

        Target(ExportModel m, int line, String path) {
            tree = m.json[line].copy();
            field = ExportModel.analyse(tree, false).find(path);
        }
    }

    private static Edit replaceLine(ExportModel m, int line, Json tree, String description) {
        List<String> lines = new ArrayList<>(m.lines);
        lines.set(line, Json.write(tree));
        return new Edit(lines, "\n", m.finalNewline, line + 1, "line " + (line + 1) + " " + description);
    }

    // ---- reference swaps ---------------------------------------------------------------------------------

    private Edit swapReference(ExportModel m, List<Site> sites, Rng rng) {
        if (sites.isEmpty()) {
            return null;
        }
        Site s = sites.get(rng.below(sites.size()));
        Target t = new Target(m, s.line, s.path);
        if (t.field == null) {
            return null;
        }
        long old = t.field.value().asLong();
        long repl = earlierId(m, t.field.space(), s.line, old, rng);
        if (repl < 0) {
            return null;
        }
        t.field.set(Json.number(repl));
        return replaceLine(m, s.line, t.tree, s.path + ": " + old + " -> " + repl);
    }

    /**
     * Inserts {@code {"ie":NEW,"mdata":{"data":{},"expr":OLD}}} before the line of one expression
     * reference and points that reference at NEW (a fresh id, one above the largest in the file). Two
     * references in three are taken from declaration lines (roots and rule right-hand sides), where the
     * official checker's structural comparisons see the metadata.
     */
    private Edit wrapMData(ExportModel m, Sites sites, Rng rng) {
        List<Site> roots = new ArrayList<>(sites.byKind.get(Mutation.SWAP_DECL_ROOT));
        roots.addAll(sites.ruleRhs);
        List<Site> from = !roots.isEmpty() && rng.below(3) != 0 ? roots : sites.exprRefs;
        if (from.isEmpty()) {
            return null;
        }
        Site s = from.get(rng.below(from.size()));
        Target t = new Target(m, s.line, s.path);
        if (t.field == null) {
            return null;
        }
        long old = t.field.value().asLong();
        long fresh = 0;
        for (int k = 0, n = m.idCount(Space.EXPR); k < n; k++) {
            fresh = Math.max(fresh, m.idAt(Space.EXPR, k));
        }
        if (fresh == Long.MAX_VALUE) {
            return null;
        }
        fresh++;
        t.field.set(Json.number(fresh));
        List<String> lines = new ArrayList<>(m.lines);
        lines.set(s.line, Json.write(t.tree));
        lines.add(s.line, "{\"ie\":" + fresh + ",\"mdata\":{\"data\":{},\"expr\":" + old + "}}");
        return new Edit(lines, "\n", m.finalNewline, s.line + 1,
                "line " + (s.line + 1) + " " + s.path + ": " + old + " -> mdata record " + fresh + " wrapping " + old);
    }

    /** An id of {@code space} defined before {@code line} (or the implicit id 0 of names and levels), different from {@code not}; -1 if none. */
    private static long earlierId(ExportModel m, Space space, int line, long not, Rng rng) {
        int defined = m.definedBefore(space, line);
        int implicit = space == Space.EXPR ? 0 : 1;
        int n = defined + implicit;
        if (n == 0) {
            return -1;
        }
        for (int tries = 0; tries < 12; tries++) {
            int k = rng.below(n);
            long id = k < implicit ? 0 : m.idAt(space, k - implicit);
            if (id != not) {
                return id;
            }
        }
        return -1;
    }

    // ---- numbers, flags, hints ---------------------------------------------------------------------------

    private Edit changeNumber(ExportModel m, List<Site> sites, Rng rng) {
        if (sites.isEmpty()) {
            return null;
        }
        Site s = sites.get(rng.below(sites.size()));
        Target t = new Target(m, s.line, s.path);
        if (t.field == null) {
            return null;
        }
        long old = t.field.value().asLong();
        if (old < 0) {
            return null;
        }
        long repl;
        switch (rng.below(4)) {
            case 0:
                repl = old + 1;
                break;
            case 1:
                repl = old > 0 ? old - 1 : old + 1;
                break;
            case 2:
                repl = old != 0 ? 0 : 2;
                break;
            default:
                repl = old + 2 + rng.below(5);
        }
        t.field.set(Json.number(repl));
        return replaceLine(m, s.line, t.tree, s.path + ": " + old + " -> " + repl);
    }

    private Edit forgeFlag(ExportModel m, List<Site> sites, Rng rng) {
        if (sites.isEmpty()) {
            return null;
        }
        Site s = sites.get(rng.below(sites.size()));
        Target t = new Target(m, s.line, s.path);
        if (t.field == null) {
            return null;
        }
        Json v = t.field.value();
        if (s.role == Role.SAFETY) {
            String old = v.text();
            String[] all = {"safe", "unsafe", "partial"};
            String repl = all[rng.below(3)];
            if (repl.equals(old)) {
                repl = old.equals("safe") ? "unsafe" : "safe";
            }
            t.field.set(Json.string(repl));
            return replaceLine(m, s.line, t.tree, s.path + ": " + old + " -> " + repl);
        }
        boolean old = v.asBool();
        t.field.set(Json.bool(!old));
        return replaceLine(m, s.line, t.tree, s.path + ": " + old + " -> " + !old);
    }

    private Edit changeHint(ExportModel m, List<Site> sites, Rng rng) {
        if (sites.isEmpty()) {
            return null;
        }
        Site s = sites.get(rng.below(sites.size()));
        Target t = new Target(m, s.line, s.path);
        if (t.field == null) {
            return null;
        }
        String old = Json.write(t.field.value());
        Json repl;
        long h = -1;
        Json oldV = t.field.value();
        if (oldV.isObject() && oldV.get("regular") != null) {
            h = oldV.get("regular").asLong();
        }
        switch (rng.below(7)) {
            case 0:
                repl = Json.string("opaque");
                break;
            case 1:
                repl = Json.string("abbrev");
                break;
            case 2:
                repl = Json.object().put("regular", 0);
                break;
            case 3:
                repl = Json.object().put("regular", h >= 0 ? h + 1 : 1);
                break;
            case 4:
                repl = Json.object().put("regular", h > 0 ? h - 1 : 2);
                break;
            case 5:
                repl = Json.object().put("regular", 1000);
                break;
            default:
                repl = Json.object().put("regular", 4294967295L);
        }
        if (Json.write(repl).equals(old)) {
            return null;
        }
        t.field.set(repl);
        return replaceLine(m, s.line, t.tree, s.path + ": " + old + " -> " + Json.write(repl));
    }

    // ---- recursor rules ----------------------------------------------------------------------------------

    private Edit forgeRule(ExportModel m, Sites sites, Rng rng) {
        if (sites.rules.isEmpty()) {
            return null;
        }
        int op = rng.below(6);
        if (op <= 1) {
            return swapReference(m, sites.ruleRhs, rng); // twice as likely as the others: the classic forgery
        }
        if (op == 2) {
            return swapReference(m, sites.ruleCtor, rng);
        }
        Site s = sites.rules.get(rng.below(sites.rules.size()));
        Target t = new Target(m, s.line, s.path);
        if (t.field == null) {
            return null;
        }
        Json rules = t.field.value();
        int n = rules.size();
        if (op == 3) {
            if (n == 0) {
                return null;
            }
            int k = rng.below(n);
            rules.removeAt(k);
            return replaceLine(m, s.line, t.tree, s.path + ": rule " + k + " dropped");
        }
        if (op == 4) {
            if (n == 0) {
                return null;
            }
            int k = rng.below(n);
            rules.insertAt(k, rules.at(k).copy());
            return replaceLine(m, s.line, t.tree, s.path + ": rule " + k + " duplicated");
        }
        if (n < 2) {
            return null;
        }
        int a = rng.below(n);
        int b = rng.below(n - 1);
        if (b >= a) {
            b++;
        }
        Json ra = rules.at(a);
        rules.set(a, rules.at(b));
        rules.set(b, ra);
        return replaceLine(m, s.line, t.tree, s.path + ": rules " + Math.min(a, b) + " and " + Math.max(a, b) + " swapped");
    }

    // ---- literals ------------------------------------------------------------------------------------------

    private Edit changeLiteral(ExportModel m, List<Site> sites, Rng rng) {
        if (sites.isEmpty()) {
            return null;
        }
        Site s = sites.get(rng.below(sites.size()));
        Target t = new Target(m, s.line, s.path);
        if (t.field == null) {
            return null;
        }
        String old = t.field.value().text();
        String repl;
        if (s.role == Role.NAT_LIT) {
            if (old.isEmpty() || !old.chars().allMatch(c -> c >= '0' && c <= '9')) {
                return null;
            }
            BigInteger v = new BigInteger(old);
            switch (rng.below(8)) {
                case 0:
                    repl = v.add(BigInteger.ONE).toString();
                    break;
                case 1:
                    repl = v.signum() > 0 ? v.subtract(BigInteger.ONE).toString() : "2";
                    break;
                case 2:
                    repl = v.signum() != 0 ? "0" : "1";
                    break;
                case 3:
                    repl = old + (char) ('0' + rng.below(10));
                    break;
                case 4:
                    repl = "18446744073709551616";
                    break;
                case 5:
                    repl = "18446744073709551615";
                    break;
                case 6:
                    repl = "0" + old; // the same number with a leading zero
                    break;
                default: {
                    StringBuilder sb = new StringBuilder();
                    sb.append((char) ('1' + rng.below(9)));
                    for (int i = 0; i < 39; i++) {
                        sb.append((char) ('0' + rng.below(10)));
                    }
                    repl = sb.toString();
                }
            }
        } else {
            switch (rng.below(7)) {
                case 0:
                    repl = old + "x";
                    break;
                case 1:
                    repl = old.isEmpty() ? "a" : old.substring(0, old.offsetByCodePoints(old.length(), -1));
                    break;
                case 2: {
                    if (old.isEmpty()) {
                        repl = "Z";
                    } else {
                        int cp = old.codePointAt(0);
                        repl = (cp == 'Z' ? "Y" : "Z") + old.substring(Character.charCount(cp));
                    }
                    break;
                }
                case 3:
                    repl = old.isEmpty() ? " " : "";
                    break;
                case 4:
                    repl = old + "é";
                    break;
                case 5:
                    repl = old + new String(Character.toChars(0x1F600));
                    break;
                default:
                    repl = old + "\n";
            }
        }
        if (repl.equals(old)) {
            return null;
        }
        t.field.set(Json.string(repl));
        return replaceLine(m, s.line, t.tree, s.path + ": " + abbreviate(old) + " -> " + abbreviate(repl));
    }

    private static String abbreviate(String s) {
        StringBuilder sb = new StringBuilder();
        Json.quote(s.length() > 48 ? s.substring(0, 45) + "..." : s, sb);
        return sb.toString();
    }

    // ---- declaration kind ------------------------------------------------------------------------------------

    private Edit changeDeclKind(ExportModel m, List<Integer> decls, Rng rng) {
        if (decls.isEmpty()) {
            return null;
        }
        int line = decls.get(rng.below(decls.size()));
        Json tree = m.json[line].copy();
        String tag = tree.key(0);
        Json body = tree.at(0);
        if (tag.equals("quot")) {
            String old = body.getText("kind", "");
            String[] kinds = {"type", "ctor", "lift", "ind"};
            String repl = kinds[rng.below(4)];
            if (repl.equals(old)) {
                return null;
            }
            body.put("kind", repl);
            return replaceLine(m, line, tree, "quot.kind: " + old + " -> " + repl);
        }
        String[] targets;
        switch (tag) {
            case "def":
                targets = new String[] {"thm", "opaque", "axiom"};
                break;
            case "thm":
                targets = new String[] {"def", "opaque", "axiom"};
                break;
            default:
                targets = new String[] {"def", "thm", "axiom"};
        }
        String to = targets[rng.below(targets.length)];
        Json name = body.get("name");
        Json lps = body.get("levelParams");
        Json type = body.get("type");
        Json value = body.get("value");
        Json all = body.get("all");
        if (name == null || lps == null || type == null || value == null) {
            return null;
        }
        if (all == null) {
            all = Json.array().add(name.copy());
        }
        // Members in lean4export's (sorted) order.
        Json nb = Json.object();
        switch (to) {
            case "thm":
                nb.put("all", all).put("levelParams", lps).put("name", name).put("type", type).put("value", value);
                break;
            case "opaque":
                nb.put("all", all).put("isUnsafe", false).put("levelParams", lps).put("name", name).put("type", type)
                        .put("value", value);
                break;
            case "axiom":
                nb.put("isUnsafe", false).put("levelParams", lps).put("name", name).put("type", type);
                break;
            default: {
                Json hints;
                switch (rng.below(3)) {
                    case 0:
                        hints = Json.string("opaque");
                        break;
                    case 1:
                        hints = Json.string("abbrev");
                        break;
                    default:
                        hints = Json.object().put("regular", 1);
                }
                nb.put("all", all).put("hints", hints).put("levelParams", lps).put("name", name).put("safety", "safe")
                        .put("type", type).put("value", value);
            }
        }
        Json out = Json.object().put(to, nb);
        return replaceLine(m, line, out, "declaration kind: " + tag + " -> " + to
                + (to.equals("def") ? " (hints " + Json.write(nb.get("hints")) + ")" : ""));
    }

    // ---- binder annotations ------------------------------------------------------------------------------------

    private Edit changeBinderInfo(ExportModel m, List<Site> sites, Rng rng) {
        if (sites.isEmpty()) {
            return null;
        }
        Site s = sites.get(rng.below(sites.size()));
        if (s.role == Role.BINDER_NAME) {
            List<Site> one = new ArrayList<>();
            one.add(s);
            return swapReference(m, one, rng);
        }
        Target t = new Target(m, s.line, s.path);
        if (t.field == null) {
            return null;
        }
        if (s.role == Role.NONDEP) {
            boolean old = t.field.value().asBool();
            t.field.set(Json.bool(!old));
            return replaceLine(m, s.line, t.tree, s.path + ": " + old + " -> " + !old);
        }
        String old = t.field.value().text();
        String[] infos = {"default", "implicit", "strictImplicit", "instImplicit"};
        String repl = infos[rng.below(4)];
        if (repl.equals(old)) {
            return null;
        }
        t.field.set(Json.string(repl));
        return replaceLine(m, s.line, t.tree, s.path + ": " + old + " -> " + repl);
    }

    // ---- whole lines ---------------------------------------------------------------------------------------------

    private Edit dropLine(ExportModel m, List<Integer> candidates, Rng rng, String what) {
        if (candidates.isEmpty()) {
            return null;
        }
        int line = candidates.get(rng.below(candidates.size()));
        List<String> lines = new ArrayList<>(m.lines);
        lines.remove(line);
        return new Edit(lines, "\n", m.finalNewline, line + 1, "line " + (line + 1) + " " + what + " dropped: " + shortLine(m, line));
    }

    private Edit duplicateLine(ExportModel m, List<Integer> candidates, Rng rng, String what) {
        if (candidates.isEmpty()) {
            return null;
        }
        int line = candidates.get(rng.below(candidates.size()));
        List<String> lines = new ArrayList<>(m.lines);
        boolean atEnd = rng.chance(40);
        if (atEnd) {
            lines.add(m.lines.get(line));
        } else {
            lines.add(line + 1, m.lines.get(line));
        }
        return new Edit(lines, "\n", m.finalNewline, line + 1, "line " + (line + 1) + " " + what + " duplicated "
                + (atEnd ? "at the end of the file" : "in place") + ": " + shortLine(m, line));
    }

    private static String shortLine(ExportModel m, int line) {
        String s = m.lines.get(line);
        return s.length() > 80 ? s.substring(0, 77) + "..." : s;
    }

    // ---- declaration order ---------------------------------------------------------------------------------------

    private Edit reorderDecl(ExportModel m, Rng rng) {
        int n = m.declLines.length;
        if (n < 2) {
            return null;
        }
        int a = rng.below(n - 1);
        int lineA = m.declLines[a];
        Set<Long> declared = declaredNames(m.recs[lineA]);
        // The first later declaration that uses A, among a bounded number of candidates.
        int user = -1;
        int limit = Math.min(n, a + 1 + 64);
        for (int b = a + 1; b < limit; b++) {
            if (uses(m, m.declLines[b], declared)) {
                user = b;
                break;
            }
        }
        int b;
        String how;
        if (user >= 0 && !rng.chance(20)) {
            b = user;
            how = "behind line " + (m.declLines[b] + 1) + ", a declaration that uses it";
        } else {
            b = a + 1 + rng.below(n - 1 - a);
            how = "behind the later declaration of line " + (m.declLines[b] + 1);
        }
        int lineB = m.declLines[b];
        List<String> lines = new ArrayList<>(m.lines.size());
        for (int i = 0; i < m.lines.size(); i++) {
            if (i == lineA) {
                continue;
            }
            lines.add(m.lines.get(i));
            if (i == lineB) {
                lines.add(m.lines.get(lineA));
            }
        }
        return new Edit(lines, "\n", m.finalNewline, lineA + 1, "line " + (lineA + 1) + " declaration moved " + how);
    }

    private static Set<Long> declaredNames(Rec r) {
        Set<Long> names = new HashSet<>();
        for (Field f : r.fields) {
            if (f.role == Role.REF_NAME && f.path.endsWith(".name")) {
                names.add(f.value().asLong());
            }
        }
        return names;
    }

    /** True if the declaration on {@code line} refers to a constant or projection type among {@code names} (iterative walk of its term DAG). */
    private static boolean uses(ExportModel m, int line, Set<Long> names) {
        Map<Long, Integer> exprLine = exprLines(m);
        ArrayDeque<Long> stack = new ArrayDeque<>();
        Set<Long> seen = new HashSet<>();
        for (Field f : m.recs[line].fields) {
            if (f.role == Role.REF_EXPR) {
                long id = f.value().asLong();
                if (seen.add(id)) {
                    stack.push(id);
                }
            }
        }
        int budget = 200_000;
        while (!stack.isEmpty() && budget-- > 0) {
            Integer l = exprLine.get(stack.pop());
            if (l == null) {
                continue;
            }
            for (Field f : m.recs[l].fields) {
                if (f.role == Role.REF_NAME && names.contains(f.value().asLong())) {
                    return true;
                }
                if (f.role == Role.REF_EXPR) {
                    long id = f.value().asLong();
                    if (seen.add(id)) {
                        stack.push(id);
                    }
                }
            }
        }
        return false;
    }

    private static final Map<ExportModel, Map<Long, Integer>> EXPR_LINES = new java.util.WeakHashMap<>();

    private static synchronized Map<Long, Integer> exprLines(ExportModel m) {
        Map<Long, Integer> map = EXPR_LINES.get(m);
        if (map == null) {
            map = new HashMap<>();
            for (int i = 1; i < m.recs.length; i++) {
                if (m.recs[i].kind == RecordKind.EXPR) {
                    map.putIfAbsent(m.recs[i].id, i);
                }
            }
            EXPR_LINES.put(m, map);
        }
        return map;
    }

    // ---- meta line ---------------------------------------------------------------------------------------------------

    private Edit changeMeta(ExportModel m, Rng rng) {
        if (m.lines.isEmpty()) {
            return null;
        }
        List<String> lines = new ArrayList<>(m.lines);
        Json meta = m.json[0] != null && m.json[0].isObject() && m.json[0].get("meta") != null && m.json[0].get("meta").isObject()
                ? m.json[0].copy() : null;
        int op = rng.below(meta == null ? 4 : 7);
        switch (op) {
            case 0:
                lines.remove(0);
                return new Edit(lines, "\n", m.finalNewline, 1, "line 1 meta line dropped");
            case 1:
                lines.set(0, "{\"garbage\":\"not a meta record\"}");
                return new Edit(lines, "\n", m.finalNewline, 1, "line 1 meta line replaced by another JSON object");
            case 2:
                lines.add(1, m.lines.get(0));
                return new Edit(lines, "\n", m.finalNewline, 1, "line 1 meta line duplicated");
            case 3:
                lines.add(lines.remove(0));
                return new Edit(lines, "\n", m.finalNewline, 1, "line 1 meta line moved to the end of the file");
            case 4: {
                Json format = meta.get("meta").get("format");
                if (format == null || !format.isObject()) {
                    return null;
                }
                format.put("version", "9.9.9");
                lines.set(0, Json.write(meta));
                return new Edit(lines, "\n", m.finalNewline, 1, "line 1 meta.format.version -> 9.9.9");
            }
            case 5: {
                Json lean = meta.get("meta").get("lean");
                if (lean == null || !lean.isObject()) {
                    return null;
                }
                lean.put("version", "0.0.0");
                lines.set(0, Json.write(meta));
                return new Edit(lines, "\n", m.finalNewline, 1, "line 1 meta.lean.version -> 0.0.0");
            }
            default: {
                Json exporter = meta.get("meta").get("exporter");
                if (exporter == null || !exporter.isObject()) {
                    return null;
                }
                exporter.put("name", "mutant");
                lines.set(0, Json.write(meta));
                return new Edit(lines, "\n", m.finalNewline, 1, "line 1 meta.exporter.name -> mutant");
            }
        }
    }

    // ---- renumbering ---------------------------------------------------------------------------------------------------

    private Edit renumber(ExportModel m, Rng rng) {
        if (!m.strict()) {
            return null; // a reference we do not recognise would be left behind
        }
        List<Map<Long, String>> maps = new ArrayList<>();
        int style = rng.below(10);
        String how;
        for (Space space : Space.values()) {
            int n = m.idCount(space);
            int[] perm = new int[n];
            for (int i = 0; i < n; i++) {
                perm[i] = i;
            }
            for (int i = n - 1; i > 0; i--) {
                int j = rng.below(i + 1);
                int tmp = perm[i];
                perm[i] = perm[j];
                perm[j] = tmp;
            }
            Map<Long, String> map = new HashMap<>();
            int stride = 1 + rng.below(9);
            int base = (space == Space.EXPR ? 0 : 1) + rng.below(50);
            for (int i = 0; i < n; i++) {
                long id = m.idAt(space, i);
                if (map.containsKey(id)) {
                    return null; // an id bound twice in the source: keep such files as they are
                }
                String repl;
                if (style == 0) {
                    // beyond 32 bits: the official reader takes any natural
                    repl = Long.toString(5_000_000_000L + (long) perm[i] * stride + base);
                } else if (style == 1) {
                    // beyond 64 bits
                    repl = new BigInteger("36893488147419103232").add(BigInteger.valueOf((long) perm[i] * stride + base)).toString();
                } else {
                    repl = Long.toString((long) perm[i] * stride + base);
                }
                map.put(id, repl);
            }
            if (space != Space.EXPR && map.containsKey(0L)) {
                return null; // a record that rebinds the implicit id 0
            }
            maps.add(map);
        }
        how = style == 0 ? "sparse, shuffled, above 2^32" : style == 1 ? "sparse, shuffled, above 2^64" : "sparse and shuffled";
        List<String> lines = new ArrayList<>(m.lines.size());
        lines.add(m.lines.get(0));
        for (int i = 1; i < m.lines.size(); i++) {
            Json tree = m.json[i].copy();
            Rec rec = ExportModel.analyse(tree, false);
            for (Field f : rec.fields) {
                Space space = f.space();
                if (space == null) {
                    continue;
                }
                long old = f.value().asLong();
                String repl = maps.get(space.ordinal()).get(old);
                if (repl == null) {
                    if (old == 0 && space != Space.EXPR) {
                        continue; // anonymous name, level zero
                    }
                    return null; // dangling reference in the source
                }
                f.set(Json.numberText(repl));
            }
            lines.add(Json.write(tree));
        }
        return new Edit(lines, "\n", m.finalNewline, 0, "all ids renumbered (" + how + ")");
    }

    // ---- reformatting --------------------------------------------------------------------------------------------------

    private Edit reformat(ExportModel m, Rng rng) {
        for (Json j : m.json) {
            if (j == null) {
                return null;
            }
        }
        int keyOrder = rng.below(3); // 0 keep, 1 reverse, 2 shuffle
        int spaces = rng.below(3); // 0 none, 1 ": " and ", ", 2 random
        int escapes = rng.below(3); // 0 none, 1 non-ASCII as \\uXXXX, 2 also some ASCII letters
        boolean crlf = rng.chance(15);
        boolean dropFinalNewline = rng.chance(15);
        int share = rng.chance(50) ? 100 : 35; // percentage of lines that are restyled
        if (keyOrder == 0 && spaces == 0 && escapes == 0 && !crlf && !dropFinalNewline) {
            keyOrder = 2;
        }
        List<String> lines = new ArrayList<>(m.lines.size());
        boolean changed = false;
        for (int i = 0; i < m.lines.size(); i++) {
            if (!rng.chance(share)) {
                lines.add(m.lines.get(i));
                continue;
            }
            StringBuilder sb = new StringBuilder();
            if (spaces == 2 && rng.chance(30)) {
                sb.append(' ');
            }
            styled(m.json[i], sb, keyOrder, spaces, escapes, rng);
            if (spaces == 2 && rng.chance(30)) {
                sb.append(rng.chance(50) ? " " : "\t");
            }
            String s = sb.toString();
            changed |= !s.equals(m.lines.get(i));
            lines.add(s);
        }
        if (!changed && !crlf && !dropFinalNewline) {
            return null;
        }
        String description = "reformatted: keys " + (keyOrder == 0 ? "as written" : keyOrder == 1 ? "reversed" : "shuffled")
                + ", spacing " + (spaces == 0 ? "compact" : spaces == 1 ? "after ':' and ','" : "random")
                + ", escapes " + (escapes == 0 ? "raw" : escapes == 1 ? "\\u for non-ASCII" : "\\u for non-ASCII and some ASCII")
                + (crlf ? ", CRLF line ends" : "") + (dropFinalNewline ? ", no final newline" : "") + ", " + share + "% of the lines";
        return new Edit(lines, crlf ? "\r\n" : "\n", m.finalNewline && !dropFinalNewline, 0, description);
    }

    private static void styled(Json v, StringBuilder sb, int keyOrder, int spaces, int escapes, Rng rng) {
        switch (v.kind) {
            case OBJECT: {
                int n = v.size();
                int[] order = new int[n];
                for (int i = 0; i < n; i++) {
                    order[i] = keyOrder == 1 ? n - 1 - i : i;
                }
                if (keyOrder == 2) {
                    for (int i = n - 1; i > 0; i--) {
                        int j = rng.below(i + 1);
                        int tmp = order[i];
                        order[i] = order[j];
                        order[j] = tmp;
                    }
                }
                sb.append('{');
                pad(sb, spaces, rng, false);
                for (int i = 0; i < n; i++) {
                    if (i > 0) {
                        sb.append(',');
                        pad(sb, spaces, rng, true);
                    }
                    styledString(v.key(order[i]), sb, escapes == 2 ? 3 : 0, rng);
                    pad(sb, spaces, rng, false);
                    sb.append(':');
                    pad(sb, spaces, rng, true);
                    styled(v.at(order[i]), sb, keyOrder, spaces, escapes, rng);
                }
                pad(sb, spaces, rng, false);
                sb.append('}');
                break;
            }
            case ARRAY:
                sb.append('[');
                for (int i = 0; i < v.size(); i++) {
                    if (i > 0) {
                        sb.append(',');
                        pad(sb, spaces, rng, true);
                    }
                    styled(v.at(i), sb, keyOrder, spaces, escapes, rng);
                }
                sb.append(']');
                break;
            case STRING:
                styledString(v.text(), sb, escapes, rng);
                break;
            default:
                sb.append(v.text());
        }
    }

    private static void pad(StringBuilder sb, int spaces, Rng rng, boolean afterSeparator) {
        if (spaces == 1 && afterSeparator) {
            sb.append(' ');
        } else if (spaces == 2 && rng.chance(afterSeparator ? 50 : 15)) {
            sb.append(rng.chance(80) ? " " : "  ");
        }
    }

    /**
     * A JSON string with optional {@code \\uXXXX} escapes. {@code escapes}: 0 = as lean4export writes;
     * 1 = non-ASCII characters of the basic plane escaped; 2 = additionally one ASCII letter in five;
     * 3 = one ASCII letter in five only (keys). Characters outside the basic plane always stay raw: Lean's
     * JSON reader does not combine surrogate escapes, so escaping them would change the string.
     */
    private static void styledString(String s, StringBuilder sb, int escapes, Rng rng) {
        if (escapes == 0) {
            Json.quote(s, sb);
            return;
        }
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean surrogate = Character.isSurrogate(c);
            boolean nonAscii = c >= 0x80 && !surrogate && escapes != 3;
            boolean letter = escapes >= 2 && ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) && rng.chance(20);
            if (nonAscii || letter) {
                sb.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
            } else if (c == '"') {
                sb.append("\\\"");
            } else if (c == '\\') {
                sb.append("\\\\");
            } else if (c == '\n') {
                sb.append("\\n");
            } else if (c == '\r') {
                sb.append("\\r");
            } else if (c < 0x20) {
                sb.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
            } else {
                sb.append(c);
            }
        }
        sb.append('"');
    }
}

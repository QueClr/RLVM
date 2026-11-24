package rlvm.admit;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.ToIntFunction;

import rlvm.api.LevelOps;
import rlvm.api.RunSignals;
import rlvm.api.WorkerContext;
import rlvm.api.bind.Bindings;
import rlvm.api.bind.LevelBinding;
import rlvm.store.DeclKind;
import rlvm.store.DeclTable;
import rlvm.store.IntLists;
import rlvm.store.LevelInfo;
import rlvm.store.ProofBlocks;
import rlvm.store.Store;
import rlvm.store.StoreBuilder;

/**
 * A hand-built store for the tests of this module: the real {@code rlvm-store} and the real level
 * bindings of {@code rlvm-api}, written through {@code StoreBuilder} the way the loader does (names
 * claimed before the terms of a unit are written), then frozen and wrapped in a worker context.
 */
final class Fixture {

    final Bindings bindings = new Bindings(64);
    final LevelBinding base = bindings.baseLevels();
    final Store store;
    final StoreBuilder b;
    WorkerContext ctx;

    Fixture() {
        store = new Store(1 << 16, bindings.baseLists(), levelInfo(base));
        bindings.bindNames(store.names());
        b = store.builder();
    }

    /** The store's view of the level algebra, over any {@code LevelOps}. */
    static LevelInfo levelInfo(LevelOps levels) {
        if (levels instanceof LevelInfo) {
            return (LevelInfo) levels;
        }
        return new LevelInfo() {
            @Override
            public boolean hasParam(int level) {
                return levels.hasParam(level);
            }

            @Override
            public int paramName(int level) {
                return levels.kind(level) == LevelOps.KIND_PARAM ? levels.paramName(level) : -1;
            }

            @Override
            public int lhs(int level) {
                int kind = levels.kind(level);
                return kind == LevelOps.KIND_SUCC ? levels.succOf(level)
                        : kind == LevelOps.KIND_MAX || kind == LevelOps.KIND_IMAX ? levels.lhs(level) : 0;
            }

            @Override
            public int rhs(int level) {
                int kind = levels.kind(level);
                return kind == LevelOps.KIND_MAX || kind == LevelOps.KIND_IMAX ? levels.rhs(level) : 0;
            }

            @Override
            public int instantiate(int level, int paramNames, int levelList) {
                return levels.instantiate(level, paramNames, levelList);
            }

            @Override
            public int instantiateList(int levelList, int paramNames, int levelsOfParams) {
                return levels.instantiateList(levelList, paramNames, levelsOfParams);
            }
        };
    }

    // ---- names, levels, lists --------------------------------------------------------------------------

    /** Interns a dotted name ("Nat.succ"); components are plain strings. */
    int name(String dotted) {
        int id = 0;
        for (String part : dotted.split("\\.")) {
            byte[] utf8 = part.getBytes(StandardCharsets.UTF_8);
            id = store.names().internStr(id, utf8, 0, utf8.length);
        }
        return id;
    }

    /** Constant declared under a dotted name (0 if none). */
    int cid(String dotted) {
        return store.names().constOf(name(dotted));
    }

    private LevelOps levelOps() {
        return ctx != null ? ctx.levels : base;
    }

    private IntLists listOps() {
        return ctx != null ? ctx.lists : base;
    }

    int zero() {
        return levelOps().zero();
    }

    int succ(int level) {
        return levelOps().succ(level);
    }

    int param(int name) {
        return levelOps().param(name);
    }

    int max(int a, int c) {
        return levelOps().max(a, c);
    }

    /** A list of name ids (universe parameters, `all`, `ctors`). */
    int names(int... ids) {
        return ids.length == 0 ? IntLists.EMPTY : listOps().intern(ids, 0, ids.length);
    }

    // ---- shared terms ----------------------------------------------------------------------------------

    int bvar(int index) {
        return b.bvar(index);
    }

    int sort(int level) {
        return b.sort(level);
    }

    int cnst(int name, int... levels) {
        return b.constant(name, levels.length == 0 ? IntLists.EMPTY : base.intern(levels, 0, levels.length));
    }

    int app(int fn, int arg) {
        return b.app(fn, arg);
    }

    int lam(int domain, int body) {
        return b.lam(domain, body);
    }

    int pi(int domain, int body) {
        return b.pi(domain, body);
    }

    int let(int type, int value, int body) {
        return b.let(type, value, body);
    }

    int proj(int structName, int index, int struct) {
        return b.proj(structName, index, struct);
    }

    int nat(long value) {
        return b.natSmall(value);
    }

    int str(String text) {
        byte[] utf8 = text.getBytes(StandardCharsets.UTF_8);
        return b.str(store.lits().addString(utf8, 0, utf8.length));
    }

    // ---- declarations ----------------------------------------------------------------------------------

    /** Claims the name of a single-constant unit; its terms may now refer to it. */
    int open(int kind, int name, int lparams) {
        int cid = b.beginDeclaration(kind, name, lparams);
        if (cid == 0) {
            throw new IllegalStateException("duplicate declaration " + store.names().toString(name));
        }
        return cid;
    }

    void close(int cid, int type, int value) {
        b.endDeclaration(cid, type, value, 0);
    }

    int declare(int kind, int name, int lparams, int type, int value) {
        int cid = open(kind, name, lparams);
        close(cid, type, value);
        return cid;
    }

    int axiom(int name, int lparams, int type) {
        return declare(DeclKind.AXIOM, name, lparams, type, 0);
    }

    int def(int name, int lparams, int type, int value) {
        return declare(DeclKind.DEFN, name, lparams, type, value);
    }

    int thm(int name, int lparams, int type, int value) {
        return declare(DeclKind.THM, name, lparams, type, value);
    }

    int opaque(int name, int lparams, int type, int value) {
        return declare(DeclKind.OPAQUE, name, lparams, type, value);
    }

    /** A quot record of kind {@code DeclKind.QUOT_*}. */
    int quot(int name, int lparams, int type, int kind) {
        return declare(kind, name, lparams, type, 0);
    }

    /** A theorem whose value is a proof block written by {@code body}, which returns the root record. */
    int thmBlock(int name, int lparams, int type, ToIntFunction<ProofBlocks.Writer> body) {
        int cid = open(DeclKind.THM, name, lparams);
        ProofBlocks.Writer w = b.blockWriter();
        int root = body.applyAsInt(w);
        int block = w.finish(root);
        close(cid, type, -(block + 1));
        return cid;
    }

    static int shared(int term) {
        return ProofBlocks.Writer.shared(term);
    }

    static int local(int backDistance) {
        return ProofBlocks.Writer.local(backDistance);
    }

    void dropUnsafe(int name) {
        b.dropUnsafe(name);
    }

    /** Writes the derived facts of a one-type block directly, as the environment pass would. */
    void commitInductive(int typeCid, int numParams, int numIndices, boolean isRec, int... ctors) {
        DeclTable decls = store.decls();
        int flags = isRec ? DeclTable.IF_REC : 0;
        if (ctors.length == 1) {
            flags |= DeclTable.IF_ONE_CTOR;
        }
        StoreAccess.setInductive(decls, typeCid, numParams, numIndices, ctors, ctors.length, new int[] {typeCid}, 1,
                flags, 0, 0);
        for (int i = 0; i < ctors.length; i++) {
            decls.setConstructor(ctors[i], typeCid, i, numParams, 0);
        }
    }

    /** Edits the claims blob of the unit of constant {@code cid} in place. */
    void patchClaims(int cid, Consumer<int[]> edit) {
        edit.accept(store.decls().claims(store.decls().unit(cid)));
    }

    // ---- end of the "file" -----------------------------------------------------------------------------

    /** Publishes everything as a completely loaded file and creates the worker context. */
    void finish() {
        b.finish();
        freeze();
    }

    /** Publishes the units written so far without the end-of-file mark. */
    void finishIncomplete() {
        b.publish();
        freeze();
    }

    /** After {@link #finishIncomplete()}: the end-of-file mark arrives (nothing more was declared). */
    void completeLoad() {
        b.finish();
    }

    private void freeze() {
        bindings.freeze();
        ctx = bindings.newWorkerContext(0, store, RunSignals.NONE, 1L << 28);
    }

    // ---- inductive blocks ------------------------------------------------------------------------------

    Block block() {
        return new Block();
    }

    /** One {@code inductive} record: members are claimed first, their rows and claims filled afterwards. */
    final class Block {
        private final List<int[]> types = new ArrayList<>();
        private final List<int[]> ctors = new ArrayList<>();
        private final List<int[]> recs = new ArrayList<>();
        private final List<Integer> typeNames = new ArrayList<>();
        private final List<Integer> ctorNames = new ArrayList<>();

        Block() {
            b.beginBlock();
        }

        int reserve(int kind, int name, int lparams) {
            int cid = b.claimMember(kind, name, lparams);
            if (cid == 0) {
                throw new IllegalStateException("duplicate declaration " + store.names().toString(name));
            }
            if (kind == DeclKind.INDUCT) {
                types.add(new int[] {cid});
                typeNames.add(name);
            } else if (kind == DeclKind.CTOR) {
                ctors.add(new int[] {cid});
                ctorNames.add(name);
            } else {
                recs.add(new int[] {cid});
            }
            return cid;
        }

        int type(int name, int lparams) {
            return reserve(DeclKind.INDUCT, name, lparams);
        }

        int ctor(int name, int lparams) {
            return reserve(DeclKind.CTOR, name, lparams);
        }

        int rec(int name, int lparams) {
            return reserve(DeclKind.REC, name, lparams);
        }

        /** Sets the type of a member; its claims stay the defaults of {@link #end()}. */
        void complete(int cid, int type) {
            b.setMemberType(cid, type);
        }

        void typeInfo(int cid, int type, long numParams, int allList, int ctorsList) {
            b.setMemberType(cid, type);
            int[] e = new int[Claims.TYPE_WORDS];
            e[Claims.T_CID] = cid;
            putLong(e, Claims.T_NUM_PARAMS, numParams);
            e[Claims.T_ALL] = allList;
            e[Claims.T_CTORS] = ctorsList;
            replace(types, cid, e);
        }

        void ctorInfo(int cid, int type, int inductName, long cidx, long numParams, long numFields) {
            b.setMemberType(cid, type);
            int[] e = new int[Claims.CTOR_WORDS];
            e[Claims.C_CID] = cid;
            e[Claims.C_INDUCT] = inductName;
            putLong(e, Claims.C_CIDX, cidx);
            putLong(e, Claims.C_NUM_PARAMS, numParams);
            putLong(e, Claims.C_NUM_FIELDS, numFields);
            replace(ctors, cid, e);
        }

        void recInfo(int cid, int type, int allList, long numParams, long numIndices, long numMotives, long numMinors,
                boolean k, int[] ruleCtorNames, long[] ruleFields, int[] ruleRhs) {
            b.setMemberType(cid, type);
            int rules = ruleCtorNames.length;
            int[] e = new int[Claims.REC_WORDS + Claims.RULE_WORDS * rules];
            e[Claims.R_CID] = cid;
            e[Claims.R_ALL] = allList;
            putLong(e, Claims.R_NUM_PARAMS, numParams);
            putLong(e, Claims.R_NUM_INDICES, numIndices);
            putLong(e, Claims.R_NUM_MOTIVES, numMotives);
            putLong(e, Claims.R_NUM_MINORS, numMinors);
            e[Claims.R_FLAGS] = k ? Claims.F_K : 0;
            e[Claims.R_NUM_RULES] = rules;
            for (int r = 0; r < rules; r++) {
                int at = Claims.REC_WORDS + Claims.RULE_WORDS * r;
                e[at + Claims.RULE_CTOR] = ruleCtorNames[r];
                putLong(e, at + Claims.RULE_NFIELDS, ruleFields[r]);
                e[at + Claims.RULE_RHS] = ruleRhs[r];
            }
            replace(recs, cid, e);
        }

        /** Flags the claims entry of a member as {@code isUnsafe}. */
        void unsafe(int cid) {
            for (int[] e : types) {
                if (e[0] == cid) {
                    defaults();
                    find(types, cid)[Claims.T_FLAGS] |= Claims.F_UNSAFE;
                    return;
                }
            }
            for (int[] e : ctors) {
                if (e[0] == cid) {
                    defaults();
                    find(ctors, cid)[Claims.C_FLAGS] |= Claims.F_UNSAFE;
                    return;
                }
            }
            defaults();
            find(recs, cid)[Claims.R_FLAGS] |= Claims.F_UNSAFE;
        }

        /** Closes the block with the claims given so far (members without explicit claims get plain defaults). */
        void end() {
            defaults();
            int n = Claims.HEADER_WORDS + Claims.TYPE_WORDS * types.size() + Claims.CTOR_WORDS * ctors.size();
            int recOffset = n;
            for (int[] e : recs) {
                n += e.length;
            }
            int[] blob = new int[n];
            blob[Claims.H_VERSION] = Claims.VERSION;
            blob[Claims.H_TYPES] = types.size();
            blob[Claims.H_CTORS] = ctors.size();
            blob[Claims.H_RECS] = recs.size();
            blob[Claims.H_REC_OFFSET] = recOffset;
            int at = Claims.HEADER_WORDS;
            for (int[] e : types) {
                System.arraycopy(e, 0, blob, at, e.length);
                at += e.length;
            }
            for (int[] e : ctors) {
                System.arraycopy(e, 0, blob, at, e.length);
                at += e.length;
            }
            for (int[] e : recs) {
                System.arraycopy(e, 0, blob, at, e.length);
                at += e.length;
            }
            end(blob);
        }

        /** Closes the block with an explicit claims blob. */
        void end(int[] blob) {
            b.blockClaims(blob, blob.length);
            b.endBlock();
        }

        /** Claims of members that got none: no parameters, the first type owns every constructor, no rules. */
        private void defaults() {
            int[] allNames = typeNames.stream().mapToInt(Integer::intValue).toArray();
            int all = names(allNames);
            for (int i = 0; i < types.size(); i++) {
                if (types.get(i).length == 1) {
                    int[] e = new int[Claims.TYPE_WORDS];
                    e[Claims.T_CID] = types.get(i)[0];
                    e[Claims.T_ALL] = all;
                    e[Claims.T_CTORS] = i == 0 ? names(ctorNames.stream().mapToInt(Integer::intValue).toArray())
                            : IntLists.EMPTY;
                    types.set(i, e);
                }
            }
            for (int i = 0; i < ctors.size(); i++) {
                if (ctors.get(i).length == 1) {
                    int[] e = new int[Claims.CTOR_WORDS];
                    e[Claims.C_CID] = ctors.get(i)[0];
                    e[Claims.C_INDUCT] = typeNames.isEmpty() ? 0 : typeNames.get(0);
                    putLong(e, Claims.C_CIDX, i);
                    ctors.set(i, e);
                }
            }
            for (int i = 0; i < recs.size(); i++) {
                if (recs.get(i).length == 1) {
                    int[] e = new int[Claims.REC_WORDS];
                    e[Claims.R_CID] = recs.get(i)[0];
                    e[Claims.R_ALL] = all;
                    recs.set(i, e);
                }
            }
        }

        private int[] find(List<int[]> list, int cid) {
            for (int[] e : list) {
                if (e[0] == cid) {
                    return e;
                }
            }
            throw new IllegalArgumentException("not a member: " + cid);
        }

        private void replace(List<int[]> list, int cid, int[] entry) {
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i)[0] == cid) {
                    list.set(i, entry);
                    return;
                }
            }
            throw new IllegalArgumentException("not a member: " + cid + " in " + Arrays.toString(entry));
        }

        private void putLong(int[] e, int at, long value) {
            e[at] = (int) value;
            e[at + 1] = (int) (value >>> 32);
        }
    }
}

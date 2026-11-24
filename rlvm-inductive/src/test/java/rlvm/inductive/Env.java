package rlvm.inductive;

import java.util.ArrayList;
import rlvm.api.EngineConfig;
import rlvm.api.KernelOps;
import rlvm.api.RunSignals;
import rlvm.api.WorkerContext;
import rlvm.api.bind.Bindings;
import rlvm.api.bind.LevelBinding;
import rlvm.store.DeclKind;
import rlvm.store.DeclTable;
import rlvm.store.IntLists;
import rlvm.store.NameTable;
import rlvm.store.Overlay;
import rlvm.store.Store;
import rlvm.store.StoreBuilder;
import rlvm.store.TermOps;
import rlvm.store.Terms;

/**
 * Test fixture: a real store filled through {@link StoreBuilder} from the notation of
 * {@link TermParser}, a worker view of it, the {@link FakeKernel}, and a miniature environment pass
 * (generate, then commit the derived facts so that later blocks can nest through earlier ones).
 */
final class Env {

    /** True: admission runs on {@code rlvm.ref.RefKernel} instead of the {@link FakeKernel}. */
    static final boolean USE_REF = "ref".equals(System.getProperty("rlvm.inductive.kernel"));

    final Bindings bindings = new Bindings(64);
    final Store store;
    final StoreBuilder sb;
    final NameTable names;
    final DeclTable decls;
    /** Loader-side levels and lists (until {@link #freeze()}). */
    final LevelBinding base;
    final TermParser shared;
    final ArrayList<Block> blocks = new ArrayList<>();

    // worker view, after freeze()
    LevelBinding levels;
    Overlay ov;
    Terms terms;
    TermOps tops;
    /** The fake kernel (always built; tests read its counters). */
    FakeKernel kernel;
    /** The engine admission runs on: {@link #kernel}, or the reference kernel with {@code -Drlvm.inductive.kernel=ref}. */
    KernelOps ops;
    TermParser local;
    InductiveAdmission admission;
    RecordCompare compare;

    /** True: admission runs on the reference kernel. */
    final boolean useRef;

    Env() {
        this(USE_REF);
    }

    Env(boolean useRef) {
        this.useRef = useRef;
        base = bindings.baseLevels();
        store = new Store(0, bindings.baseLists(), bindings.baseLevelInfo());
        bindings.bindNames(store.names());
        sb = store.builder();
        names = store.names();
        decls = store.decls();
        shared = new TermParser(names, base, base, new TermParser.Sink() {
            @Override
            public int bvar(int index) {
                return sb.bvar(index);
            }

            @Override
            public int sort(int level) {
                return sb.sort(level);
            }

            @Override
            public int constant(int name, int levelList) {
                return sb.constant(name, levelList);
            }

            @Override
            public int app(int fn, int arg) {
                return sb.app(fn, arg);
            }

            @Override
            public int lam(int domain, int body) {
                return sb.lam(domain, body);
            }

            @Override
            public int pi(int domain, int body) {
                return sb.pi(domain, body);
            }
        });
    }

    private static String[] words(String s) {
        s = s.trim();
        return s.isEmpty() ? new String[0] : s.split("\\s+");
    }

    // ---- declarations ------------------------------------------------------------------------------

    /** A definition {@code name.{lparams} : type := value}. */
    int def(String name, String lparams, String type, String value) {
        int cid = sb.beginDeclaration(DeclKind.DEFN, shared.name(name), shared.nameList(words(lparams)));
        if (cid == 0) {
            throw new IllegalStateException("duplicate " + name);
        }
        sb.endDeclaration(cid, shared.parse(type), shared.parse(value), DeclKind.HINT_ABBREV);
        return cid;
    }

    int axiom(String name, String lparams, String type) {
        int cid = sb.beginDeclaration(DeclKind.AXIOM, shared.name(name), shared.nameList(words(lparams)));
        if (cid == 0) {
            throw new IllegalStateException("duplicate " + name);
        }
        sb.endDeclaration(cid, shared.parse(type), 0, 0);
        return cid;
    }

    /** A quot record with the given kind; its own unit. */
    int quot(int kind, String name, String lparams, String type) {
        int cid = sb.beginDeclaration(kind, shared.name(name), shared.nameList(words(lparams)));
        if (cid == 0) {
            throw new IllegalStateException("duplicate " + name);
        }
        sb.endDeclaration(cid, shared.parse(type), 0, 0);
        return cid;
    }

    Block inductive(String lparams, int numParams) {
        Block b = new Block(this, words(lparams), numParams);
        blocks.add(b);
        return b;
    }

    /** One inductive block under construction / built. */
    static final class Block {
        final Env env;
        final String[] lparams;
        final int numParams;
        final ArrayList<String> typeNames = new ArrayList<>();
        final ArrayList<String> typeTexts = new ArrayList<>();
        final ArrayList<ArrayList<String[]>> ctors = new ArrayList<>();
        final ArrayList<String[]> recs = new ArrayList<>();
        final BlockInput in = new BlockInput();
        boolean built;

        Block(Env env, String[] lparams, int numParams) {
            this.env = env;
            this.lparams = lparams;
            this.numParams = numParams;
        }

        Block type(String name, String type) {
            typeNames.add(name);
            typeTexts.add(type);
            ctors.add(new ArrayList<>());
            return this;
        }

        /** A constructor of the last type. */
        Block ctor(String name, String type) {
            ctors.get(ctors.size() - 1).add(new String[] {name, type});
            return this;
        }

        /** A constructor whose type is built by hand (e.g. with a shared subterm) once the names are claimed. */
        Block ctorBuilt(String name, java.util.function.ToIntFunction<Env> type) {
            ctors.get(ctors.size() - 1).add(new String[] {name, null});
            builders.put(name, type);
            return this;
        }

        final java.util.HashMap<String, java.util.function.ToIntFunction<Env>> builders = new java.util.HashMap<>();

        /** An exported recursor record: only its name and universe parameters matter to generate(). */
        Block rec(String name, String lparams) {
            recs.add(new String[] {name, lparams});
            return this;
        }

        /** Writes the block into the store and fills {@link #in}. */
        Block end() {
            TermParser p = env.shared;
            StoreBuilder sb = env.sb;
            int lp = p.nameList(lparams);
            in.unit = sb.beginBlock();
            in.rank = in.unit + 1;
            in.levelParams = lp;
            in.numParams = numParams;
            int nt = typeNames.size();
            in.typeCount = nt;
            in.typeCid = new int[nt];
            in.typeName = new int[nt];
            in.typeType = new int[nt];
            in.typeCtorStart = new int[nt];
            in.typeCtorCount = new int[nt];
            int nc = 0;
            for (ArrayList<String[]> l : ctors) {
                nc += l.size();
            }
            in.ctorCount = nc;
            in.ctorCid = new int[nc];
            in.ctorName = new int[nc];
            in.ctorType = new int[nc];
            in.ctorClaimLevelParams = new int[nc];
            in.ctorClaimInduct = new int[nc];
            in.ctorClaimCidx = new long[nc];
            in.ctorClaimNumParams = new long[nc];
            in.ctorClaimNumFields = new long[nc];
            // claim every name first, as the loader does
            for (int k = 0; k < nt; k++) {
                in.typeName[k] = p.name(typeNames.get(k));
                in.typeCid[k] = claim(DeclKind.INDUCT, in.typeName[k], lp, typeNames.get(k));
            }
            int c = 0;
            for (int k = 0; k < nt; k++) {
                in.typeCtorStart[k] = c;
                in.typeCtorCount[k] = ctors.get(k).size();
                for (String[] ct : ctors.get(k)) {
                    in.ctorName[c] = p.name(ct[0]);
                    in.ctorCid[c] = claim(DeclKind.CTOR, in.ctorName[c], lp, ct[0]);
                    c++;
                }
            }
            int nr = recs.size();
            in.recCount = nr;
            in.recCid = new int[nr];
            in.recName = new int[nr];
            in.recType = new int[nr];
            in.recLevelParams = new int[nr];
            in.recAll = new int[nr];
            in.recNumParams = new long[nr];
            in.recNumIndices = new long[nr];
            in.recNumMotives = new long[nr];
            in.recNumMinors = new long[nr];
            in.recK = new boolean[nr];
            in.recRuleCount = new int[nr];
            in.recRuleCtorName = new int[nr][];
            in.recRuleFields = new long[nr][];
            in.recRuleRhs = new int[nr][];
            for (int r = 0; r < nr; r++) {
                in.recName[r] = p.name(recs.get(r)[0]);
                in.recLevelParams[r] = p.nameList(words(recs.get(r)[1]));
                in.recCid[r] = claim(DeclKind.REC, in.recName[r], in.recLevelParams[r], recs.get(r)[0]);
            }
            // then the terms
            for (int k = 0; k < nt; k++) {
                in.typeType[k] = p.parse(typeTexts.get(k));
                sb.setMemberType(in.typeCid[k], in.typeType[k]);
            }
            c = 0;
            for (int k = 0; k < nt; k++) {
                for (String[] ct : ctors.get(k)) {
                    in.ctorType[c] = ct[1] != null ? p.parse(ct[1]) : builders.get(ct[0]).applyAsInt(env);
                    sb.setMemberType(in.ctorCid[c], in.ctorType[c]);
                    c++;
                }
            }
            int dummy = p.parse("Prop");
            for (int r = 0; r < nr; r++) {
                in.recType[r] = dummy;
                sb.setMemberType(in.recCid[r], dummy);
            }
            sb.endBlock();
            in.consistent = true;
            built = true;
            return this;
        }

        private int claim(int kind, int name, int lp, String what) {
            int cid = env.sb.claimMember(kind, name, lp);
            if (cid == 0) {
                throw new IllegalStateException("duplicate " + what);
            }
            return cid;
        }

        int ctorIndex(String name) {
            int n = env.names.constOf(TermParser.internName(env.names, name));
            for (int i = 0; i < in.ctorCount; i++) {
                if (in.ctorCid[i] == n) {
                    return i;
                }
            }
            throw new IllegalArgumentException(name);
        }
    }

    // ---- worker view -------------------------------------------------------------------------------

    void freeze() {
        sb.finish();
        bindings.freeze();
        // the worker view the shipped pipeline builds (rlvm.api.bind.Bindings)
        WorkerContext ctx = bindings.newWorkerContext(0, store, RunSignals.NONE, 1L << 30);
        levels = (LevelBinding) ctx.levels;
        ov = ctx.overlay;
        terms = ctx.terms;
        tops = new TermOps(terms, levels, levels);
        kernel = new FakeKernel(store, terms, tops, levels);
        ops = kernel;
        if (useRef) {
            ops = new rlvm.ref.RefKernel(ctx, EngineConfig.defaults());
            tops = ops.termOps();
        }
        admission = new InductiveAdmission(store, ops);
        compare = new RecordCompare(store, ops);
        local = new TermParser(names, levels, levels, new TermParser.Sink() {
            @Override
            public int bvar(int index) {
                return ov.mkBVar(index);
            }

            @Override
            public int sort(int level) {
                return ov.mkSort(level);
            }

            @Override
            public int constant(int name, int levelList) {
                return ov.mkConstByName(name, levelList);
            }

            @Override
            public int app(int fn, int arg) {
                return ov.mkApp(fn, arg);
            }

            @Override
            public int lam(int domain, int body) {
                return ov.mkLam(domain, body);
            }

            @Override
            public int pi(int domain, int body) {
                return ov.mkPi(domain, body);
            }
        });
    }

    /** generate() only. */
    GeneratedBlock generate(Block b) {
        GeneratedBlock gen = new GeneratedBlock();
        ops.begin(b.in.rank, b.in.levelParams);
        admission.generate(b.in, gen);
        return gen;
    }

    /** generate() and commit of the derived facts of types and constructors (what later blocks read). */
    GeneratedBlock admit(Block b) {
        GeneratedBlock gen = generate(b);
        commit(b, gen);
        return gen;
    }

    void commit(Block b, GeneratedBlock gen) {
        BlockInput in = b.in;
        for (int k = 0; k < in.typeCount; k++) {
            int n = in.typeCtorCount[k];
            int[] cs = new int[n];
            System.arraycopy(in.ctorCid, in.typeCtorStart[k], cs, 0, n);
            int flags = (gen.isRec ? DeclTable.IF_REC : 0) | (gen.isReflexive ? DeclTable.IF_REFLEXIVE : 0);
            if (n == 1) {
                flags |= DeclTable.IF_ONE_CTOR;
                if (gen.typeNumIndices[k] == 0 && !gen.isRec) {
                    flags |= DeclTable.IF_STRUCT_LIKE;
                }
            }
            decls.setInductive(in.typeCid[k], in.numParams, gen.typeNumIndices[k], cs, n, in.typeCid, in.typeCount,
                    flags, gen.numNested, 0);
            for (int i = 0; i < n; i++) {
                int c = in.typeCtorStart[k] + i;
                decls.setConstructor(in.ctorCid[c], in.typeCid[k], i, in.numParams, gen.ctorNumFields[c]);
            }
        }
    }

    /** Admits every block declared so far, in order. */
    void admitAll() {
        for (Block b : blocks) {
            admit(b);
        }
    }

    int expect(String text) {
        return local.parse(text);
    }

    // ---- printing (diagnostics) --------------------------------------------------------------------

    String show(int t) {
        StringBuilder sb2 = new StringBuilder();
        show(t, sb2, 0);
        return sb2.toString();
    }

    private void show(int t, StringBuilder out, int depth) {
        if (depth > 200) {
            out.append("...");
            return;
        }
        IntLists ls = levels;
        switch (terms.kind(t)) {
            case rlvm.store.Kind.BVAR:
                out.append('#').append(terms.a(t));
                break;
            case rlvm.store.Kind.SORT:
                out.append("Sort ").append(levels.store().toString(terms.a(t)));
                break;
            case rlvm.store.Kind.CONST: {
                out.append(names.toString(terms.c(t)));
                int l = terms.b(t);
                int n = ls.length(l);
                if (n > 0) {
                    out.append(".{");
                    for (int i = 0; i < n; i++) {
                        out.append(i > 0 ? "," : "").append(levels.store().toString(ls.get(l, i)));
                    }
                    out.append('}');
                }
                break;
            }
            case rlvm.store.Kind.APP:
                out.append('(');
                show(terms.a(t), out, depth + 1);
                out.append(' ');
                show(terms.b(t), out, depth + 1);
                out.append(')');
                break;
            case rlvm.store.Kind.LAM:
            case rlvm.store.Kind.PI:
                out.append(terms.kind(t) == rlvm.store.Kind.LAM ? "(λ " : "(Π ");
                show(terms.a(t), out, depth + 1);
                out.append(", ");
                show(terms.b(t), out, depth + 1);
                out.append(')');
                break;
            case rlvm.store.Kind.FVAR:
                out.append("?fv").append(terms.a(t));
                break;
            default:
                out.append("<kind ").append(terms.kind(t)).append('>');
        }
    }
}

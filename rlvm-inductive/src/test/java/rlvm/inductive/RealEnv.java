package rlvm.inductive;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import rlvm.api.EngineConfig;
import rlvm.api.KernelOps;
import rlvm.api.RunSignals;
import rlvm.api.WorkerContext;
import rlvm.api.bind.Bindings;
import rlvm.api.bind.LevelBinding;
import rlvm.load.BlockClaims;
import rlvm.load.ExportLoader;
import rlvm.load.LevelStoreInterner;
import rlvm.load.LoadOptions;
import rlvm.load.Loader;
import rlvm.load.NatStoreDecoder;
import rlvm.ref.RefKernel;
import rlvm.store.DeclKind;
import rlvm.store.DeclTable;
import rlvm.store.IntLists;
import rlvm.store.NameTable;
import rlvm.store.Overlay;
import rlvm.store.Specials;
import rlvm.store.Store;
import rlvm.store.Terms;

/**
 * Integration fixture: an export file loaded by the real pipeline ({@code rlvm-export} reader,
 * {@code rlvm-load} loader, {@code rlvm-store}), the reference kernel as {@link KernelOps}, and a
 * miniature environment pass: for every inductive unit fill {@link BlockInput} from the rows and the
 * claims blob, {@code generate}, {@code compare}, commit the derived facts; for every quot unit
 * {@link QuotAdmission}. Declarations of other kinds are not checked (their rows are inputs).
 *
 * <p>This is what {@code rlvm-admit}'s {@code EnvBuilder} does for real; the copy here keeps the
 * module's acceptance tests independent of that module.
 */
final class RealEnv {

    final Bindings bindings = new Bindings();
    final Store store;
    final DeclTable decls;
    final NameTable names;
    LevelBinding levels;
    IntLists lists;
    Overlay ov;
    Terms terms;
    KernelOps ops;
    InductiveAdmission admission;
    RecordCompare compare;
    QuotAdmission quot;

    final BlockInput in = new BlockInput();
    final GeneratedBlock gen = new GeneratedBlock();

    // statistics of the last pass
    int inductiveUnits;
    int quotUnits;
    int nestedBlocks;
    int nestedTypes;
    int mutualBlocks;
    int recursors;
    int kBlocks;
    /** Blocks with a level the official kernel respells when it refers to the block's own constants (L-F1). */
    int respelledBlocks;
    final java.util.ArrayList<String> respelledNames = new java.util.ArrayList<>();
    int failedUnit = -1;
    long generateNanos;
    long compareNanos;
    /** CPU time of the calling thread in generate + compare (the engine's judgments included). */
    long cpuNanos;
    private static final java.lang.management.ThreadMXBean CPU = java.lang.management.ManagementFactory.getThreadMXBean();
    long slowestNanos;
    int slowestUnit = -1;

    private RealEnv(long bytes) {
        store = new Store(bytes, bindings.baseLists(), bindings.baseLevelInfo());
        bindings.bindNames(store.names());
        decls = store.decls();
        names = store.names();
    }

    /** Loads the file; the loader's exceptions (malformed / unsupported input) pass through. */
    static RealEnv load(Path file) throws IOException {
        long bytes = Files.size(file);
        RealEnv e = new RealEnv(bytes);
        try {
            Loader loader = new Loader(e.store, LoadOptions.batch(bytes),
                    new LevelStoreInterner(e.bindings.levelBase(), false), new NatStoreDecoder());
            ExportLoader.read(file, loader);
            e.bindings.freeze();
            e.worker();
        } catch (UnsupportedOperationException x) {
            // a skeleton upstream (loader not bound to the store, reference kernel not published)
            org.junit.jupiter.api.Assumptions.abort("the real pipeline is not published yet: " + x.getMessage());
        }
        return e;
    }

    private void worker() {
        // the worker view the shipped pipeline builds (rlvm.api.bind.Bindings)
        WorkerContext ctx = bindings.newWorkerContext(0, store, RunSignals.NONE, 4L << 30);
        levels = (LevelBinding) ctx.levels;
        lists = ctx.lists;
        ov = ctx.overlay;
        terms = ctx.terms;
        ops = new RefKernel(ctx, EngineConfig.defaults());
        admission = new InductiveAdmission(store, ops);
        compare = new RecordCompare(store, ops);
        quot = new QuotAdmission(store, ops);
    }

    /**
     * The environment pass over all units. Stops at the first unit that throws ({@link #failedUnit});
     * the exception passes through.
     */
    void pass() {
        int n = decls.unitCount();
        Specials specials = store.specials();
        for (int u = 0; u < n; u++) {
            int size = decls.unitSize(u);
            if (size == 0) {
                continue; // dropped record
            }
            int first = decls.unitFirstCid(u);
            int kind = decls.unitKind(u);
            failedUnit = u;
            if (kind == DeclKind.INDUCT) {
                fill(u);
                // the overlay only holds garbage from earlier blocks
                ov.reset();
                ops.begin(in.rank, in.levelParams);
                long c0 = CPU.getCurrentThreadCpuTime();
                long t0 = System.nanoTime();
                admission.generate(in, gen);
                long t1 = System.nanoTime();
                compare.compare(in, gen);
                long t2 = System.nanoTime();
                cpuNanos += CPU.getCurrentThreadCpuTime() - c0;
                generateNanos += t1 - t0;
                compareNanos += t2 - t1;
                if (t2 - t0 > slowestNanos) {
                    slowestNanos = t2 - t0;
                    slowestUnit = u;
                }
                commit();
                inductiveUnits++;
                recursors += gen.recCount;
                if (gen.numNested > 0) {
                    nestedBlocks++;
                    nestedTypes += gen.numNested;
                }
                if (in.typeCount > 1) {
                    mutualBlocks++;
                }
                if (gen.k) {
                    kBlocks++;
                }
                if (admission.lastRebuildChanged()) {
                    respelledBlocks++;
                    respelledNames.add(unitName(u));
                }
            } else if (kind >= DeclKind.QUOT_TYPE && kind <= DeclKind.QUOT_IND) {
                ops.begin(decls.rank(first), decls.levelParams(first));
                quot.admit(first);
                quotUnits++;
            }
            // what rlvm-admit's PreludeGuards decide; the files used here have official preludes
            for (int cid = first; cid < first + size; cid++) {
                int sp = decls.special(cid);
                if (sp >= 0) {
                    specials.bind(sp, cid, Specials.VALID);
                }
            }
            failedUnit = -1;
        }
    }

    private boolean sameList(int a, int b) {
        IntLists l = store.lists();
        if (a == b) {
            return true;
        }
        int n = l.length(a);
        if (n != l.length(b)) {
            return false;
        }
        for (int i = 0; i < n; i++) {
            if (l.get(a, i) != l.get(b, i)) {
                return false;
            }
        }
        return true;
    }

    /** BlockInput from the rows and the claims blob (checklist item 25). */
    void fill(int unit) {
        IntLists l = store.lists();
        int[] blob = decls.claims(unit);
        int nt = blob[BlockClaims.H_NUM_TYPES];
        int nc = blob[BlockClaims.H_NUM_CTORS];
        int nr = blob[BlockClaims.H_NUM_RECS];
        int t0 = BlockClaims.HEADER;
        int c0 = t0 + nt * BlockClaims.TYPE_WORDS;
        boolean ok = nt > 0;
        unsafeMember = false;
        in.unit = unit;
        in.rank = decls.rank(decls.unitFirstCid(unit));
        in.typeCount = 0;
        in.ctorCount = nc;
        in.recCount = nr;
        if (!ok) {
            in.consistent = false;
            return;
        }
        int cid0 = blob[t0 + BlockClaims.T_CID];
        int all = blob[t0 + BlockClaims.T_ALL];
        long np = BlockClaims.getLong(blob, t0 + BlockClaims.T_NUM_PARAMS);
        in.levelParams = decls.levelParams(cid0);
        in.numParams = np > Integer.MAX_VALUE ? -1 : (int) np;
        int nall = l.length(all);
        ok &= nall == nt;
        in.typeCount = nall;
        in.typeCid = new int[nall];
        in.typeName = new int[nall];
        in.typeType = new int[nall];
        in.typeCtorStart = new int[nall];
        in.typeCtorCount = new int[nall];
        in.ctorCid = new int[nc];
        in.ctorName = new int[nc];
        in.ctorType = new int[nc];
        in.ctorClaimLevelParams = new int[nc];
        in.ctorClaimInduct = new int[nc];
        in.ctorClaimCidx = new long[nc];
        in.ctorClaimNumParams = new long[nc];
        in.ctorClaimNumFields = new long[nc];
        boolean[] used = new boolean[nc];
        int out = 0;
        for (int k = 0; k < nall && ok; k++) {
            int name = l.get(all, k);
            int at = -1;
            for (int i = 0; i < nt; i++) {
                if (decls.name(blob[t0 + i * BlockClaims.TYPE_WORDS + BlockClaims.T_CID]) == name) {
                    at = t0 + i * BlockClaims.TYPE_WORDS;
                }
            }
            if (at < 0) {
                ok = false;
                break;
            }
            int cid = blob[at + BlockClaims.T_CID];
            ok &= sameList(decls.levelParams(cid), in.levelParams);
            ok &= BlockClaims.getLong(blob, at + BlockClaims.T_NUM_PARAMS) == np;
            ok &= sameList(blob[at + BlockClaims.T_ALL], all);
            ok &= (blob[at + BlockClaims.T_FLAGS] & BlockClaims.F_UNSAFE) == 0;
            in.typeCid[k] = cid;
            in.typeName[k] = name;
            in.typeType[k] = decls.type(cid);
            int ctors = blob[at + BlockClaims.T_CTORS];
            int n = l.length(ctors);
            in.typeCtorStart[k] = out;
            in.typeCtorCount[k] = 0;
            for (int j = 0; j < n && ok; j++) {
                int cname = l.get(ctors, j);
                int found = -1;
                for (int i = 0; i < nc; i++) {
                    if (decls.name(blob[c0 + i * BlockClaims.CTOR_WORDS + BlockClaims.C_CID]) == cname) {
                        found = i;
                    }
                }
                if (found < 0 || used[found] || out >= nc) {
                    ok = false;
                    break;
                }
                used[found] = true;
                putCtor(blob, c0 + found * BlockClaims.CTOR_WORDS, out++);
                in.typeCtorCount[k]++;
            }
        }
        for (int i = 0; i < nc && ok; i++) {
            if (!used[i]) {
                putCtor(blob, c0 + i * BlockClaims.CTOR_WORDS, out++);
            }
        }
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
        int at = blob[BlockClaims.H_RECS_OFFSET];
        for (int r = 0; r < nr; r++) {
            int cid = blob[at + BlockClaims.R_CID];
            in.recCid[r] = cid;
            in.recName[r] = decls.name(cid);
            in.recType[r] = decls.type(cid);
            in.recLevelParams[r] = decls.levelParams(cid);
            in.recAll[r] = blob[at + BlockClaims.R_ALL];
            in.recNumParams[r] = BlockClaims.getLong(blob, at + BlockClaims.R_NUM_PARAMS);
            in.recNumIndices[r] = BlockClaims.getLong(blob, at + BlockClaims.R_NUM_INDICES);
            in.recNumMotives[r] = BlockClaims.getLong(blob, at + BlockClaims.R_NUM_MOTIVES);
            in.recNumMinors[r] = BlockClaims.getLong(blob, at + BlockClaims.R_NUM_MINORS);
            in.recK[r] = (blob[at + BlockClaims.R_FLAGS] & BlockClaims.F_K) != 0;
            ok &= (blob[at + BlockClaims.R_FLAGS] & BlockClaims.F_UNSAFE) == 0;
            int n = blob[at + BlockClaims.R_NUM_RULES];
            in.recRuleCount[r] = n;
            in.recRuleCtorName[r] = new int[n];
            in.recRuleFields[r] = new long[n];
            in.recRuleRhs[r] = new int[n];
            at += BlockClaims.REC_WORDS;
            for (int i = 0; i < n; i++) {
                in.recRuleCtorName[r][i] = blob[at + BlockClaims.RULE_CTOR];
                in.recRuleFields[r][i] = BlockClaims.getLong(blob, at + BlockClaims.RULE_NFIELDS);
                in.recRuleRhs[r][i] = blob[at + BlockClaims.RULE_RHS];
                at += BlockClaims.RULE_WORDS;
            }
        }
        in.consistent = ok && !unsafeMember;
    }

    private void putCtor(int[] blob, int at, int i) {
        int cid = blob[at + BlockClaims.C_CID];
        in.ctorCid[i] = cid;
        in.ctorName[i] = decls.name(cid);
        in.ctorType[i] = decls.type(cid);
        in.ctorClaimLevelParams[i] = decls.levelParams(cid);
        in.ctorClaimInduct[i] = blob[at + BlockClaims.C_INDUCT];
        in.ctorClaimCidx[i] = BlockClaims.getLong(blob, at + BlockClaims.C_CIDX);
        in.ctorClaimNumParams[i] = BlockClaims.getLong(blob, at + BlockClaims.C_NUM_PARAMS);
        in.ctorClaimNumFields[i] = BlockClaims.getLong(blob, at + BlockClaims.C_NUM_FIELDS);
        if ((blob[at + BlockClaims.C_FLAGS] & BlockClaims.F_UNSAFE) != 0) {
            unsafeMember = true;
        }
    }

    private boolean unsafeMember;

    /** Checklist item 52: the derived facts enter the side tables (exported terms for the rules). */
    void commit() {
        for (int k = 0; k < in.typeCount; k++) {
            int n = in.typeCtorCount[k];
            int[] cs = new int[n];
            System.arraycopy(in.ctorCid, in.typeCtorStart[k], cs, 0, n);
            int flags = (gen.isRec ? DeclTable.IF_REC : 0) | (gen.isReflexive ? DeclTable.IF_REFLEXIVE : 0);
            if (n == 1) {
                flags |= DeclTable.IF_ONE_CTOR;
                if (gen.typeNumIndices[k] == 0 && !gen.isRec) {
                    flags |= DeclTable.IF_STRUCT_LIKE;
                    if (gen.ctorNumFields[in.typeCtorStart[k]] == 0) {
                        flags |= DeclTable.IF_UNIT_LIKE;
                    }
                }
            }
            int recCid = 0;
            for (int r = 0; r < in.recCount; r++) {
                if (names.prefix(in.recName[r]) == in.typeName[k] && names.lastComponentIs(in.recName[r], "rec")) {
                    recCid = in.recCid[r];
                }
            }
            decls.setInductive(in.typeCid[k], in.numParams, gen.typeNumIndices[k], cs, n, in.typeCid, in.typeCount,
                    flags, gen.numNested, recCid);
            for (int i = 0; i < n; i++) {
                int c = in.typeCtorStart[k] + i;
                decls.setConstructor(in.ctorCid[c], in.typeCid[k], i, in.numParams, gen.ctorNumFields[c]);
            }
        }
        for (int r = 0; r < in.recCount; r++) {
            int j = -1;
            for (int q = 0; q < gen.recCount; q++) {
                if (names.prefix(in.recName[r]) == gen.recNamePrefix[q]
                        && names.lastComponentIs(in.recName[r], gen.recNameLast[q])) {
                    j = q;
                }
            }
            int n = gen.recRuleCount[j];
            int[] rules = new int[3 * n];
            for (int i = 0; i < n; i++) {
                rules[3 * i] = gen.recRuleCtor[j][i];
                rules[3 * i + 1] = gen.recRuleFields[j][i];
                rules[3 * i + 2] = in.recRuleRhs[r][i]; // identical to the derived one: no copy needed
            }
            decls.setRecursor(in.recCid[r], in.numParams, gen.recNumIndices[j], gen.recNumMotives, gen.recNumMinors,
                    gen.k ? DeclTable.RF_K : 0, rules, n, gen.recMajorInduct[j]);
        }
    }

    String unitName(int unit) {
        return unit < 0 ? "-" : names.toString(decls.name(decls.unitFirstCid(unit)));
    }
}

package rlvm.load;

import java.util.Arrays;

import rlvm.level.IntListStore;
import rlvm.level.LevelStore;
import rlvm.store.DeclKind;
import rlvm.store.DeclTable;
import rlvm.store.Kind;
import rlvm.store.LitPool;
import rlvm.store.NameTable;
import rlvm.store.Node;
import rlvm.store.Overlay;
import rlvm.store.ProofBlocks;
import rlvm.store.Store;
import rlvm.store.Terms;

/**
 * A structural 64-bit digest of everything a worker can reach from one unit of a store: the unit row,
 * the rows of its constants, their names and universe parameters, the type and value DAGs with every
 * word of every node (header with all flags, payload, free-variable mask, depMax, lpSet), the decoded
 * proof block of a block value, the literals behind literal nodes, and the claims blob.
 *
 * <p>It reads exactly like a worker: rows from {@link DeclTable}, shared nodes through {@link Terms},
 * blocks through {@link Overlay#blockRoot} into a private overlay, levels and lists through the
 * reader's own level store (a worker overlay of a snapshot, or the frozen base), literals through the
 * shared pool. Names, levels, lists and literals enter the digest by content, never by id, so the
 * digest of a unit is the same in a batch load and in a pipelined load (whose level ids differ) and
 * is independent of when the unit is read. Term ids, constant ids and name ids of the unit's own
 * rows enter raw: they must be identical in both modes.
 *
 * <p>One instance per thread.
 */
final class StoreDigest {
    private final Store store;
    private final DeclTable decls;
    private final NameTable names;
    private final LitPool lits;
    private final ProofBlocks blocks;
    private LevelStore levels;
    private IntListStore lists;
    private Overlay overlay;
    private Terms terms;

    private long[] termMemo = new long[1 << 12];
    private long[] ovMemo = new long[1 << 10];
    private int ovUsed;
    private long[] nameMemo = new long[1 << 10];
    private long[] levelMemo = new long[1 << 8];
    private int[] stack = new int[1 << 10];
    private long[] limbs = new long[4];
    private byte[] bytes = new byte[64];
    /** Nodes and block records digested so far (work done, for the test's report). */
    long nodesRead;

    StoreDigest(Store store) {
        this.store = store;
        this.decls = store.decls();
        this.names = store.names();
        this.lits = store.lits();
        this.blocks = store.blocks();
    }

    /** Gives the reader a level store of its own; rebuilds the term overlay over it. */
    void bind(LevelStore mine) {
        levels = mine;
        lists = mine.lists();
        overlay = new Overlay(store, LevelStoreViews.lists(mine), LevelStoreViews.info(mine), new LitPool(store.lits()),
                1L << 33);
        terms = new Terms(store.terms(), overlay);
        Arrays.fill(ovMemo, 0, Math.min(ovUsed, ovMemo.length), 0L);
        ovUsed = 0;
    }

    static long mix(long h, long v) {
        h ^= v + 0x9E3779B97F4A7C15L + (h << 6) + (h >>> 2);
        h *= 0xBF58476D1CE4E5B9L;
        return h ^ (h >>> 31);
    }

    // ---- names, levels, lists, literals --------------------------------------------------------------

    private long name(int id) {
        if (id >= nameMemo.length) {
            nameMemo = Arrays.copyOf(nameMemo, Math.max(id + 1, nameMemo.length * 2));
        }
        long h = nameMemo[id];
        if (h == 0) {
            long p = id == NameTable.ANONYMOUS ? 17 : name(names.prefix(id));
            String s = names.toString(id);
            h = mix(mix(p, s.hashCode()), s.length()) | 1;
            nameMemo[id] = h;
        }
        return h;
    }

    private long level(int id) {
        if (id >= levelMemo.length) {
            levelMemo = Arrays.copyOf(levelMemo, Math.max(id + 1, levelMemo.length * 2));
        }
        long h = levelMemo[id];
        if (h == 0) {
            LevelStore ls = levels;
            switch (ls.kind(id)) {
                case LevelStore.KIND_ZERO -> h = 2;
                case LevelStore.KIND_SUCC -> h = mix(3, level(ls.succOf(id)));
                case LevelStore.KIND_MAX -> h = mix(mix(4, level(ls.lhs(id))), level(ls.rhs(id)));
                case LevelStore.KIND_IMAX -> h = mix(mix(5, level(ls.lhs(id))), level(ls.rhs(id)));
                default -> h = mix(6, name(ls.paramName(id)));
            }
            h |= 1;
            if (id >= levelMemo.length) {
                levelMemo = Arrays.copyOf(levelMemo, Math.max(id + 1, levelMemo.length * 2));
            }
            levelMemo[id] = h;
        }
        return h;
    }

    private long nameList(int list) {
        int n = lists.length(list);
        long h = mix(7, n);
        for (int i = 0; i < n; i++) {
            h = mix(h, name(lists.get(list, i)));
        }
        return h;
    }

    private long levelList(int list) {
        int n = lists.length(list);
        long h = mix(8, n);
        for (int i = 0; i < n; i++) {
            h = mix(h, level(lists.get(list, i)));
        }
        return h;
    }

    private long natBig(int index) {
        int n = lits.natLimbCount(index);
        if (limbs.length < n) {
            limbs = new long[n];
        }
        lits.natLimbs(index, limbs, 0);
        long h = mix(9, n);
        for (int i = 0; i < n; i++) {
            h = mix(h, limbs[i]);
        }
        return h;
    }

    private long string(int index) {
        int n = lits.stringByteLength(index);
        if (bytes.length < n) {
            bytes = new byte[n];
        }
        lits.stringBytes(index, bytes, 0);
        long h = mix(10, n);
        for (int i = 0; i < n; i++) {
            h = mix(h, bytes[i]);
        }
        return h;
    }

    // ---- terms ---------------------------------------------------------------------------------------

    private long memo(int id) {
        if (id >= Node.OVERLAY_BIT) {
            int i = id & Node.INDEX_MASK;
            return i < ovMemo.length ? ovMemo[i] : 0;
        }
        return id < termMemo.length ? termMemo[id] : 0;
    }

    private void setMemo(int id, long h) {
        if (id >= Node.OVERLAY_BIT) {
            int i = id & Node.INDEX_MASK;
            if (i >= ovMemo.length) {
                ovMemo = Arrays.copyOf(ovMemo, Math.max(i + 1, ovMemo.length * 2));
            }
            ovMemo[i] = h;
            ovUsed = Math.max(ovUsed, i + 1);
        } else {
            if (id >= termMemo.length) {
                termMemo = Arrays.copyOf(termMemo, Math.max(id + 1, termMemo.length * 2));
            }
            termMemo[id] = h;
        }
    }

    private void push(int sp, int id) {
        if (sp == stack.length) {
            stack = Arrays.copyOf(stack, sp * 2);
        }
        stack[sp] = id;
    }

    /** Digest of the DAG below {@code root} (a shared id or a decoded block node); iterative. */
    private long term(int root) {
        long done = memo(root);
        if (done != 0) {
            return done;
        }
        final Terms t = terms;
        int sp = 0;
        push(sp++, root);
        while (sp > 0) {
            int id = stack[sp - 1];
            if (memo(id) != 0) {
                sp--;
                continue;
            }
            int header = t.header(id);
            int kind = Node.kind(header);
            int a = t.a(id);
            int b = t.b(id);
            int c = t.c(id);
            int c1 = 0;
            int c2 = 0;
            int c3 = 0;
            switch (kind) {
                case Kind.APP, Kind.LAM, Kind.PI -> {
                    c1 = a;
                    c2 = b;
                }
                case Kind.LET -> {
                    c1 = a;
                    c2 = b;
                    c3 = c;
                }
                case Kind.PROJ -> c1 = a;
                default -> {
                }
            }
            int before = sp;
            if (c1 != 0 && memo(c1) == 0) {
                push(sp++, c1);
            }
            if (c2 != 0 && memo(c2) == 0) {
                push(sp++, c2);
            }
            if (c3 != 0 && memo(c3) == 0) {
                push(sp++, c3);
            }
            if (sp != before) {
                continue;
            }
            long h = mix(header, t.fvMask(id));
            h = mix(h, t.depMax(id));
            h = mix(h, nameList(t.lpSet(id)));
            switch (kind) {
                case Kind.BVAR -> h = mix(h, a);
                case Kind.SORT -> h = mix(h, level(a));
                case Kind.CONST -> h = mix(mix(mix(h, a > 0 ? a : -1), name(c)), levelList(b));
                case Kind.APP, Kind.LAM, Kind.PI -> h = mix(mix(h, memo(a)), memo(b));
                case Kind.LET -> h = mix(mix(mix(h, memo(a)), memo(b)), memo(c));
                case Kind.PROJ -> h = mix(mix(mix(h, memo(a)), b), c > 0 ? c : name(~c));
                case Kind.NATLIT -> h = (header & Node.F_NAT_BIG) != 0 ? mix(h, natBig(a)) : mix(mix(h, a), b);
                case Kind.STRLIT -> h = mix(mix(h, string(a)), b);
                default -> throw new IllegalStateException("node " + id + " has kind " + kind);
            }
            setMemo(id, h | 1);
            nodesRead++;
            sp--;
        }
        return memo(root);
    }

    // ---- units ---------------------------------------------------------------------------------------

    /** Digest of unit {@code u}; the unit must be published to the calling thread. */
    long unit(int u) {
        terms.refresh();
        int first = decls.unitFirstCid(u);
        int n = decls.unitSize(u);
        long h = mix(mix(mix(11, decls.unitKind(u)), n), decls.unitFirstTerm(u));
        h = mix(h, first);
        boolean decoded = false;
        for (int cid = first; cid < first + n; cid++) {
            int kind = decls.kind(cid);
            h = mix(mix(mix(h, kind), decls.flags(cid)), decls.numLevelParams(cid));
            h = mix(mix(mix(h, decls.unit(cid)), decls.rank(cid)), decls.special(cid));
            int nm = decls.name(cid);
            h = mix(mix(h, nm), name(nm));
            h = mix(h, nameList(decls.levelParams(cid)));
            int type = decls.type(cid);
            h = mix(mix(h, type), term(type));
            int value = decls.value(cid);
            h = mix(h, value);
            if (value > 0) {
                h = mix(h, term(value));
            } else if (value < 0) {
                int block = -value - 1;
                int root = overlay.blockRoot(block);
                terms.refresh();
                decoded = true;
                h = mix(mix(h, blocks.nodeCount(block)), blocks.rootRecord(block));
                h = mix(h, root == 0 ? 0 : term(root));
                nodesRead += blocks.nodeCount(block);
            }
            if (kind == DeclKind.DEFN) {
                h = mix(h, decls.hint(cid));
            }
        }
        int[] blob = decls.claims(u);
        if (blob != null) {
            h = mix(h, claims(blob, first, n));
        }
        if (decoded) {
            overlay.reset();
            terms.refresh();
            Arrays.fill(ovMemo, 0, Math.min(ovUsed, ovMemo.length), 0L);
            ovUsed = 0;
        }
        return h;
    }

    /** The claims blob by content: names and name lists as names, rule right-hand sides as terms, the rest raw. */
    private long claims(int[] blob, int firstCid, int count) {
        long h = mix(12, blob.length);
        int nt = BlockClaims.numTypes(blob);
        int nc = blob[BlockClaims.H_NUM_CTORS];
        int nr = BlockClaims.numRecs(blob);
        h = mix(mix(mix(mix(h, blob[BlockClaims.H_VERSION]), nt), nc), nr);
        int q = BlockClaims.HEADER;
        for (int i = 0; i < nt; i++, q += BlockClaims.TYPE_WORDS) {
            for (int w = 0; w < BlockClaims.TYPE_WORDS; w++) {
                int v = blob[q + w];
                h = mix(h, w == BlockClaims.T_ALL || w == BlockClaims.T_CTORS ? nameList(v) : v);
            }
        }
        for (int i = 0; i < nc; i++, q += BlockClaims.CTOR_WORDS) {
            for (int w = 0; w < BlockClaims.CTOR_WORDS; w++) {
                int v = blob[q + w];
                h = mix(h, w == BlockClaims.C_INDUCT ? name(v) : v);
            }
        }
        if (q != BlockClaims.firstRec(blob)) {
            throw new IllegalStateException("claims blob: recursor offset " + BlockClaims.firstRec(blob) + ", expected " + q);
        }
        for (int i = 0; i < nr; i++) {
            for (int w = 0; w < BlockClaims.REC_WORDS; w++) {
                int v = blob[q + w];
                h = mix(h, w == BlockClaims.R_ALL ? nameList(v) : v);
            }
            int rules = blob[q + BlockClaims.R_NUM_RULES];
            for (int r = 0; r < rules; r++) {
                int at = BlockClaims.ruleAt(q, r);
                for (int w = 0; w < BlockClaims.RULE_WORDS; w++) {
                    int v = blob[at + w];
                    h = mix(h, w == BlockClaims.RULE_CTOR ? name(v) : v);
                }
                h = mix(h, term(blob[at + BlockClaims.RULE_RHS]));
            }
            q = BlockClaims.nextRec(blob, q);
        }
        return h;
    }
}

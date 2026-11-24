package rlvm.store;

import java.util.IdentityHashMap;
import java.util.List;

import rlvm.store.Model.M;

/**
 * Test support: a miniature of the loader's declaration sweep (ARCHITECTURE.md 3.3). A segment is a
 * list of model nodes, children first; the nodes reachable from the type root become shared terms,
 * the others go into one proof block; nodes of earlier segments are referenced as shared terms and
 * promoted on demand.
 */
final class Segs {
    final Model fx;
    /** Where each model node lives: a shared id (&gt; 0) or -(global block record + 1). */
    final IdentityHashMap<M, Integer> loc = new IdentityHashMap<>();
    /** Global block record number of every node that was written into a block (never updated by promotion). */
    final IdentityHashMap<M, Integer> record = new IdentityHashMap<>();
    boolean proofBlocks = true;

    Segs(Model fx) {
        this.fx = fx;
    }

    /** One written segment. */
    static final class Seg {
        List<M> nodes;
        boolean[] shared;
        /** Shared id, or local block index, per node. */
        int[] at;
        int block = -1;
        int cid;
        int unit;
        int typeId;
        int valueWord;
        int firstTerm;
    }

    private int sharedOf(M child) {
        int l = loc.get(child);
        if (l > 0) {
            return l;
        }
        int s = fx.builder.promote(-l - 1);
        loc.put(child, s);
        return s;
    }

    /**
     * Writes one theorem (or, with {@code kind} DEFN, a definition whose records are all shared).
     * {@code nodes} are the new records of the segment; they may reference nodes of earlier segments.
     */
    Seg write(int kind, int name, int lparams, List<M> nodes, M type, M value) {
        StoreBuilder b = fx.builder;
        Seg s = new Seg();
        s.nodes = nodes;
        s.firstTerm = fx.store.terms().segmentStart();
        s.cid = b.beginDeclaration(kind, name, lparams);
        if (s.cid == 0) {
            throw new IllegalStateException("duplicate name");
        }
        int n = nodes.size();
        IdentityHashMap<M, Integer> index = new IdentityHashMap<>();
        for (int i = 0; i < n; i++) {
            index.put(nodes.get(i), i);
        }
        s.shared = new boolean[n];
        boolean blocks = proofBlocks && (kind == DeclKind.THM || kind == DeclKind.OPAQUE);
        if (!blocks) {
            java.util.Arrays.fill(s.shared, true);
        } else if (index.containsKey(type)) {
            s.shared[index.get(type)] = true;
            for (int i = n - 1; i >= 0; i--) {
                if (!s.shared[i]) {
                    continue;
                }
                M m = nodes.get(i);
                for (M c : new M[] {m.a, m.b, m.c}) {
                    if (c != null && index.containsKey(c)) {
                        s.shared[index.get(c)] = true;
                    }
                }
            }
        }
        s.at = new int[n];
        ProofBlocks.Writer w = b.blockWriter();
        int local = 0;
        int firstRecord = fx.store.blocks().recordCount();
        for (int i = 0; i < n; i++) {
            M m = nodes.get(i);
            if (s.shared[i]) {
                int id;
                switch (m.kind) {
                    case Kind.BVAR:
                        id = b.bvar(m.x);
                        break;
                    case Kind.SORT:
                        id = b.sort(m.x);
                        break;
                    case Kind.CONST:
                        id = b.constant(m.x, m.y);
                        break;
                    case Kind.APP:
                        id = b.app(sharedOf(m.a), sharedOf(m.b));
                        break;
                    case Kind.LAM:
                        id = b.lam(sharedOf(m.a), sharedOf(m.b));
                        break;
                    case Kind.PI:
                        id = b.pi(sharedOf(m.a), sharedOf(m.b));
                        break;
                    case Kind.LET:
                        id = b.let(sharedOf(m.a), sharedOf(m.b), sharedOf(m.c));
                        break;
                    case Kind.PROJ:
                        id = b.proj(m.x, m.y, sharedOf(m.a));
                        break;
                    case Kind.NATLIT:
                        id = m.y == 1 ? b.natBig(m.x) : b.natSmall(m.v);
                        break;
                    default:
                        id = b.str(m.x);
                        break;
                }
                s.at[i] = id;
                loc.put(m, id);
            } else {
                switch (m.kind) {
                    case Kind.BVAR:
                        w.bvar(m.x);
                        break;
                    case Kind.SORT:
                        w.sort(m.x);
                        break;
                    case Kind.CONST:
                        w.constant(b.bindName(m.x), m.y, m.x);
                        break;
                    case Kind.APP:
                        w.app(ref(s, index, local, m.a), ref(s, index, local, m.b));
                        break;
                    case Kind.LAM:
                        w.lam(ref(s, index, local, m.a), ref(s, index, local, m.b));
                        break;
                    case Kind.PI:
                        w.pi(ref(s, index, local, m.a), ref(s, index, local, m.b));
                        break;
                    case Kind.LET:
                        w.let(ref(s, index, local, m.a), ref(s, index, local, m.b), ref(s, index, local, m.c));
                        break;
                    case Kind.PROJ:
                        w.proj(b.bindName(m.x), m.y, ref(s, index, local, m.a));
                        break;
                    case Kind.NATLIT:
                        if (m.y == 1) {
                            w.natBig(m.x);
                        } else {
                            w.natSmall(m.v);
                        }
                        break;
                    default:
                        w.str(m.x);
                        break;
                }
                s.at[i] = local;
                loc.put(m, -(firstRecord + local) - 1);
                record.put(m, firstRecord + local);
                local++;
            }
        }
        if (blocks) {
            Integer vi = index.get(value);
            s.block = w.finish(vi != null && !s.shared[vi] ? s.at[vi] : -1);
        }
        s.typeId = sharedOf(type);
        if (value == null) {
            s.valueWord = 0;
        } else {
            int l = loc.get(value);
            s.valueWord = l > 0 ? l : (index.containsKey(value) ? -(s.block + 1) : sharedOf(value));
        }
        b.endDeclaration(s.cid, s.typeId, s.valueWord, 0);
        s.unit = fx.store.decls().unit(s.cid);
        return s;
    }

    private int ref(Seg s, IdentityHashMap<M, Integer> index, int local, M child) {
        Integer ci = index.get(child);
        if (ci != null && !s.shared[ci]) {
            return ProofBlocks.Writer.local(local - s.at[ci]);
        }
        return ProofBlocks.Writer.shared(sharedOf(child));
    }
}

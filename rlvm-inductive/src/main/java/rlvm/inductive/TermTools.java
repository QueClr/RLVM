package rlvm.inductive;

import rlvm.store.Kind;
import rlvm.store.Node;
import rlvm.store.Overlay;
import rlvm.store.Terms;

/**
 * Iterative term traversals for admission: the official {@code find} and {@code replace}
 * ({@code refs/lean4/src/kernel/find_fn.h}, {@code replace_fn.cpp}) over term ids. Explicit stacks
 * only; depth of a term is attacker-controlled.
 *
 * <p>{@link #replace} is organised by application spines: the hook is asked once per maximal
 * application (and once per atom), not once per partial application. For the three official uses
 * (nested-occurrence elimination, restoration, renaming of atoms) this visits exactly the nodes on
 * which the official function can answer "replace" (a partial application of a spine whose maximal
 * application was not replaced is never replaced either: it has the same head and a prefix of the
 * arguments, see the comments at the hooks in {@link InductiveAdmission}). Children are visited in
 * the official order: function before argument, binder domain before body.
 */
final class TermTools {

    /** A predicate over CONST, FVAR and PROJ nodes with a pruning test on node headers. */
    abstract static class Probe {
        /** True if no node below a node with this header can be a hit. */
        abstract boolean skip(int header);

        /** Called for CONST, FVAR and PROJ nodes. */
        abstract boolean hit(int node, int kind);
    }

    /** A rewriting function; results are memoised in {@link #memo} until the owner clears it. */
    abstract static class Rewriter {
        final IntIntMap memo = new IntIntMap(256);

        /** True if no node below a node with this header can be rewritten. */
        abstract boolean skip(int header);

        /**
         * Maximal application {@code head args[0..n)}, {@code n >= 1}, visited before its children.
         * Returns the replacement, or 0 to descend. Must not keep {@code args}.
         */
        int app(int node, int head, int[] args, int n) {
            return 0;
        }

        /** CONST or FVAR node (also when it is the head of an application that was not replaced). */
        int leaf(int node, int kind) {
            return 0;
        }
    }

    private final Terms terms;
    private final Overlay ov;

    private int[] scanStack = new int[256];
    private final IntIntMap scanSeen = new IntIntMap(256);

    private int[] rwStack = new int[512];
    private int[] rwArgs = new int[64];

    TermTools(Terms terms) {
        this.terms = terms;
        this.ov = terms.overlay();
    }

    // ---- find ------------------------------------------------------------------------------------------

    /** True if some CONST / FVAR / PROJ node reachable from {@code root} is a hit of {@code p}. */
    boolean any(int root, Probe p) {
        int sp = 0;
        scanSeen.clear();
        scanStack[sp++] = root;
        while (sp > 0) {
            int node = scanStack[--sp];
            int h = terms.header(node);
            if (p.skip(h)) {
                continue;
            }
            int kind = Node.kind(h);
            switch (kind) {
                case Kind.CONST:
                case Kind.FVAR:
                    if (p.hit(node, kind)) {
                        return true;
                    }
                    continue;
                case Kind.APP:
                case Kind.LAM:
                case Kind.PI:
                    if (!scanSeen.add(node)) {
                        continue;
                    }
                    if (sp + 2 > scanStack.length) {
                        scanStack = java.util.Arrays.copyOf(scanStack, scanStack.length * 2);
                    }
                    scanStack[sp++] = terms.b(node);
                    scanStack[sp++] = terms.a(node);
                    continue;
                case Kind.LET:
                    if (!scanSeen.add(node)) {
                        continue;
                    }
                    if (sp + 3 > scanStack.length) {
                        scanStack = java.util.Arrays.copyOf(scanStack, scanStack.length * 2);
                    }
                    scanStack[sp++] = terms.c(node);
                    scanStack[sp++] = terms.b(node);
                    scanStack[sp++] = terms.a(node);
                    continue;
                case Kind.PROJ:
                    if (p.hit(node, kind)) {
                        return true;
                    }
                    if (!scanSeen.add(node)) {
                        continue;
                    }
                    if (sp + 1 > scanStack.length) {
                        scanStack = java.util.Arrays.copyOf(scanStack, scanStack.length * 2);
                    }
                    scanStack[sp++] = terms.a(node);
                    continue;
                default:
                    continue;
            }
        }
        return false;
    }

    // ---- replace ---------------------------------------------------------------------------------------

    /** Copies the spine arguments of APP node {@code node} into the shared buffer; returns the head. */
    private int unroll(int node, int n) {
        if (n > rwArgs.length) {
            rwArgs = new int[Math.max(n, rwArgs.length * 2)];
        }
        return terms.spine(node, rwArgs, 0);
    }

    private void push(int node, int phase) {
        if (rwSp + 2 > rwStack.length) {
            rwStack = java.util.Arrays.copyOf(rwStack, rwStack.length * 2);
        }
        rwStack[rwSp++] = node;
        rwStack[rwSp++] = phase;
    }

    private int rwSp;

    /**
     * The official {@code replace(e, f)}: pre-order; a node for which the hook answers is replaced and
     * not descended into; unchanged nodes keep their ids. Not re-entrant.
     */
    int replace(int root, Rewriter rw) {
        IntIntMap memo = rw.memo;
        int r0 = memo.get(root);
        if (r0 != 0) {
            return r0;
        }
        rwSp = 0;
        push(root, 0);
        while (rwSp > 0) {
            int phase = rwStack[--rwSp];
            int node = rwStack[--rwSp];
            int h = terms.header(node);
            int kind = Node.kind(h);
            if (phase == 0) {
                if (memo.contains(node)) {
                    continue;
                }
                if (rw.skip(h)) {
                    memo.put(node, node);
                    continue;
                }
                switch (kind) {
                    case Kind.CONST:
                    case Kind.FVAR: {
                        int r = rw.leaf(node, kind);
                        memo.put(node, r != 0 ? r : node);
                        break;
                    }
                    case Kind.APP: {
                        int n = terms.spineLength(node);
                        int head = unroll(node, n);
                        int r = rw.app(node, head, rwArgs, n);
                        if (r != 0) {
                            memo.put(node, r);
                            break;
                        }
                        push(node, 1);
                        for (int i = n - 1; i >= 0; i--) {
                            push(rwArgs[i], 0);
                        }
                        push(head, 0);
                        break;
                    }
                    case Kind.LAM:
                    case Kind.PI:
                        push(node, 1);
                        push(terms.b(node), 0);
                        push(terms.a(node), 0);
                        break;
                    case Kind.LET:
                        push(node, 1);
                        push(terms.c(node), 0);
                        push(terms.b(node), 0);
                        push(terms.a(node), 0);
                        break;
                    case Kind.PROJ:
                        push(node, 1);
                        push(terms.a(node), 0);
                        break;
                    default:
                        memo.put(node, node);
                        break;
                }
            } else {
                int result;
                switch (kind) {
                    case Kind.APP: {
                        int n = terms.spineLength(node);
                        int head = unroll(node, n);
                        int nh = memo.get(head);
                        boolean changed = nh != head;
                        for (int i = 0; i < n; i++) {
                            int na = memo.get(rwArgs[i]);
                            if (na != rwArgs[i]) {
                                changed = true;
                                rwArgs[i] = na;
                            }
                        }
                        result = changed ? ov.mkApps(nh, rwArgs, 0, n) : node;
                        break;
                    }
                    case Kind.LAM: {
                        int a = terms.a(node);
                        int b = terms.b(node);
                        int na = memo.get(a);
                        int nb = memo.get(b);
                        result = (na == a && nb == b) ? node : ov.mkLam(na, nb);
                        break;
                    }
                    case Kind.PI: {
                        int a = terms.a(node);
                        int b = terms.b(node);
                        int na = memo.get(a);
                        int nb = memo.get(b);
                        result = (na == a && nb == b) ? node : ov.mkPi(na, nb);
                        break;
                    }
                    case Kind.LET: {
                        int a = terms.a(node);
                        int b = terms.b(node);
                        int c = terms.c(node);
                        int na = memo.get(a);
                        int nb = memo.get(b);
                        int nc = memo.get(c);
                        result = (na == a && nb == b && nc == c) ? node : ov.mkLet(na, nb, nc);
                        break;
                    }
                    case Kind.PROJ: {
                        int a = terms.a(node);
                        int na = memo.get(a);
                        result = na == a ? node : ov.mkProj(terms.c(node), terms.b(node), na);
                        break;
                    }
                    default:
                        throw new IllegalStateException("replace: unexpected node kind " + kind);
                }
                memo.put(node, result);
            }
        }
        return memo.get(root);
    }
}

package rlvm.admit;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import rlvm.store.DeclKind;
import rlvm.store.DeclTable;
import rlvm.store.IntLists;
import rlvm.store.Kind;
import rlvm.store.NameTable;
import rlvm.store.Node;
import rlvm.store.Overlay;
import rlvm.store.Specials;
import rlvm.store.Store;
import rlvm.store.Terms;

/**
 * The order in which leanchecker 4.36 adds the declarations of a completely loaded file whose
 * inductive records are consistent: a simulation of {@code Lean.Replay}
 * (refs/lean4-replay/src/Lean/Replay.lean) at the level of names, without any type checking:
 *
 * <pre>
 * remaining := the safe records, without Quot.mk / Quot.lift / Quot.ind
 * for n in remaining, ascending in Name.quickCmp:  visit(n)
 * visit(n): if n is not in remaining: return;  move n to pending
 *           for m in used(n), ascending:  visit(m)          -- the constants of type and value; an inductive
 *           if n is still pending: act(n); drop n           --   type: + its constructors; a constructor: + itself;
 *                                                           --   a recursor: + the types of its `all`
 * act(axiom / def / thm / opaque) = add it
 * act(inductive type T)           = take every type of T's `all` out of remaining and pending; visit used(c)
 *                                   for every constructor c of every one of them; add the block
 * act(constructor / recursor)     = postpone (compared at the end)
 * act(quot record)                = visit(Eq); add the quotient package (once)
 * </pre>
 * {@code Name.quickCmp} compares {@code Name.hash} first: 1723 for the anonymous name,
 * {@code mixHash(prefix.hash, component.hash)} otherwise, a string component hashed with
 * MurmurHash64A (seed 11) over its UTF-8 bytes, a numeric component by its value (17 from 2^64 on);
 * {@code mixHash} is {@code hash(h, k)} of the Lean runtime ({@code src/runtime/hash.h}).
 *
 * <p>What the simulation answers: {@link #actTime(int)} of a unit, and {@link #firstAbsent()}, the
 * first unit (in official order) that is added while something its kernel-checked roots mention is
 * not in the environment yet - where the official checker stops with "unknown constant" unless
 * something failed before. It assumes every earlier addition succeeded.
 *
 * <p>{@link #available()} is false when the file is outside the model (an inductive record whose
 * lists are not its own members or that has a member named {@code Quot.mk / lift / ind}, a tie of
 * two hashes, a name that is not well-formed Unicode) or larger than the caller's budget of node
 * visits.
 *
 * <p>Verified against leanchecker 4.36.0 ({@code work/rlvm-admit/probe2}: {@code order_*},
 * {@code xorder_*} - 84 files that differ only in names, with string, numeric, non-ASCII and nested
 * components; the 3,678 tutorial tests and mutants that load and that the official checker accepts:
 * no declaration is ever reported missing; the 13 rejected mutants in which one is: the official
 * checker fails at that very declaration).
 */
final class OfficialOrder {

    private static final long M = 0xc6a4a7935bd1e995L;
    private static final int R = 47;

    private final Store store;
    private final DeclTable decls;
    private final NameTable names;
    private final Specials specials;
    private final Terms terms;
    private final IntLists lists;
    private final EnvBuilder.ClaimsSource claims;

    private boolean available = true;
    private String why;

    private final long[] hash;
    private final boolean[] hashed;

    /** Per constant: 0 not eligible, 1 remaining, 2 pending, 3 done. */
    private final byte[] state;
    /** Per constant: flagged unsafe member. */
    private final boolean[] unsafeMember;
    /** Per unit: position of its addition in the official order (1-based), 0 = never added. */
    private final int[] time;
    private int clock;
    private int quotTime;
    /** Per inductive unit: its types in `all` order, then per type its constructor cids. */
    private final int[][] blockTypes;
    private final int[][][] blockCtors;
    /** Per recursor constant: the cids of the types of its `all`, or null. */
    private final int[][] recAll;

    private int absentUnit = -1;
    private int absentCid;
    /** Node visits left. */
    private long budget;

    // walk scratch
    private int[] stack = new int[64];
    private int[] seen = new int[256];
    private int[] seenSlots = new int[64];
    private int seenCount;
    private int[] found = new int[16];
    private int nFound;

    /**
     * @param rs the units already admitted in dependency order (their unsafe member records), or null
     * @param maxVisits bound on the term nodes looked at; beyond it the model is unavailable
     */
    OfficialOrder(Store store, Terms terms, IntLists lists, EnvBuilder.ClaimsSource claims, ReplayState rs,
            long maxVisits) {
        this.budget = maxVisits;
        this.store = store;
        this.decls = store.decls();
        this.names = store.names();
        this.specials = store.specials();
        this.terms = terms;
        this.lists = lists;
        this.claims = claims;
        int nNames = names.size();
        this.hash = new long[nNames];
        this.hashed = new boolean[nNames];
        int nConst = decls.constantCount();
        int nUnits = store.loadedUnits();
        this.state = new byte[nConst + 1];
        this.unsafeMember = new boolean[nConst + 1];
        this.time = new int[nUnits];
        this.blockTypes = new int[nUnits][];
        this.blockCtors = new int[nUnits][][];
        this.recAll = new int[nConst + 1][];
        if (!store.loadComplete()) {
            unavailable("the file is not completely loaded");
            return;
        }
        try {
            prepare(rs);
            if (available) {
                run();
            }
        } catch (RuntimeException e) {
            unavailable("exception: " + e);
        }
    }

    boolean available() {
        return available;
    }

    String whyUnavailable() {
        return why;
    }

    /** Position of the unit's addition in the official order (1-based); 0 if it is never added. */
    int actTime(int unit) {
        return time[unit];
    }

    /** True if the declaration of unit {@code provider} is in the official environment when {@code user} is added. */
    boolean before(int provider, int user) {
        return time[provider] != 0 && time[user] != 0 && time[provider] < time[user];
    }

    /**
     * True if constant {@code cid} is in the official environment when unit {@code user} is added:
     * its unit was added before, or, for one of the four quotient constants, the package was.
     */
    boolean providedBefore(int cid, int user) {
        if (time[user] == 0) {
            return false;
        }
        int k = decls.kind(cid);
        if (k >= DeclKind.QUOT_TYPE && k <= DeclKind.QUOT_IND) {
            int which = specials.indexOfName(decls.name(cid));
            return which >= Specials.QUOT && which <= Specials.QUOT_IND && quotTime != 0 && quotTime < time[user];
        }
        return before(decls.unit(cid), user);
    }

    /** The first unit, in official order, added while a constant it mentions is missing; -1 if none. */
    int firstAbsent() {
        return absentUnit;
    }

    /** The missing constant of {@link #firstAbsent()}. */
    int absentConstant() {
        return absentCid;
    }

    private void unavailable(String reason) {
        if (available) {
            available = false;
            why = reason;
        }
    }

    // ---- Name.hash ---------------------------------------------------------------------------------------

    static long mixHash(long h, long k) {
        k *= M;
        k ^= k >>> R;
        k ^= M;
        h ^= k;
        h *= M;
        return h;
    }

    static long stringHash(byte[] data) {
        int len = data.length;
        long h = 11L ^ (len * M);
        int end = len & ~7;
        for (int i = 0; i < end; i += 8) {
            long k = 0;
            for (int b = 7; b >= 0; b--) {
                k = (k << 8) | (data[i + b] & 0xFFL);
            }
            k *= M;
            k ^= k >>> R;
            k *= M;
            h ^= k;
            h *= M;
        }
        int tail = len & 7;
        if (tail != 0) {
            for (int b = tail - 1; b >= 0; b--) {
                h ^= (data[end + b] & 0xFFL) << (8 * b);
            }
            h *= M;
        }
        h ^= h >>> R;
        h *= M;
        h ^= h >>> R;
        return h;
    }

    long nameHash(int name) {
        if (name == NameTable.ANONYMOUS) {
            return 1723L;
        }
        if (hashed[name]) {
            return hash[name];
        }
        // iterative over the prefix chain
        int depth = names.depth(name);
        int[] chain = new int[depth];
        int n = name;
        for (int i = depth - 1; i >= 0; i--) {
            chain[i] = n;
            n = names.prefix(n);
        }
        long h = 1723L;
        for (int i = 0; i < depth; i++) {
            int c = chain[i];
            if (hashed[c]) {
                h = hash[c];
                continue;
            }
            long k;
            if (names.isNumeric(c)) {
                k = names.isBigNumeric(c) ? 17L : names.numericValue(c);
            } else {
                String full = names.toString(c);
                int p = names.prefix(c);
                String component = p == NameTable.ANONYMOUS ? full : full.substring(names.toString(p).length() + 1);
                byte[] utf8 = component.getBytes(StandardCharsets.UTF_8);
                if (!new String(utf8, StandardCharsets.UTF_8).equals(component)) {
                    unavailable("a name component is not well-formed Unicode");
                }
                k = stringHash(utf8);
            }
            h = mixHash(h, k);
            hash[c] = h;
            hashed[c] = true;
        }
        return h;
    }

    // ---- preparation: who is replayed, what the blocks are ---------------------------------------------

    private boolean quotSetAside(int name) {
        int which = specials.indexOfName(name);
        return which == Specials.QUOT_MK || which == Specials.QUOT_LIFT || which == Specials.QUOT_IND;
    }

    private void prepare(ReplayState rs) {
        int nUnits = store.loadedUnits();
        for (int u = 0; u < nUnits && available; u++) {
            int size = decls.unitSize(u);
            if (size == 0 || decls.status(u) == DeclKind.ST_SKIPPED) {
                continue;
            }
            int first = decls.unitFirstCid(u);
            int kind = decls.unitKind(u);
            if (kind == DeclKind.INDUCT) {
                prepareBlock(u, first, size, rs);
            } else if (!quotSetAside(decls.name(first))) {
                state[first] = 1;
            }
        }
    }

    private void prepareBlock(int u, int first, int size, ReplayState rs) {
        for (int cid = first; cid < first + size; cid++) {
            if (quotSetAside(decls.name(cid))) {
                // LeanChecker takes the record out of the map the replay looks names up in
                unavailable("an inductive record with a member named Quot.mk / Quot.lift / Quot.ind");
                return;
            }
        }
        int[] blob = claims.claims(u);
        if (blob == null) {
            // admitted: the side tables hold the block, and it had no unsafe member unless recorded
            int nT = 0;
            int t0 = 0;
            for (int cid = first; cid < first + size; cid++) {
                if (decls.kind(cid) == DeclKind.INDUCT) {
                    if (t0 == 0) {
                        t0 = cid;
                    }
                    nT++;
                }
            }
            if (t0 == 0 || decls.inductAllCount(t0) != nT) {
                unavailable("an admitted inductive record without its facts");
                return;
            }
            int[] types = new int[nT];
            int[][] ctors = new int[nT][];
            for (int k = 0; k < nT; k++) {
                types[k] = decls.inductAllAt(t0, k);
                int n = decls.inductCtorCount(types[k]);
                ctors[k] = new int[n];
                for (int x = 0; x < n; x++) {
                    ctors[k][x] = decls.inductCtor(types[k], x);
                }
            }
            blockTypes[u] = types;
            blockCtors[u] = ctors;
            for (int cid = first; cid < first + size; cid++) {
                boolean unsafe = rs != null && rs.isUnsafeMember(cid);
                unsafeMember[cid] = unsafe;
                if (decls.kind(cid) == DeclKind.REC) {
                    recAll[cid] = types;
                }
                if (!unsafe && !quotSetAside(decls.name(cid))) {
                    state[cid] = 1;
                }
            }
            return;
        }
        if (Claims.validate(blob) != null) {
            unavailable("an inductive record with unreadable claims");
            return;
        }
        int nT = blob[Claims.H_TYPES];
        if (nT == 0) {
            unavailable("an inductive record without types");
            return;
        }
        int[] types = new int[nT];
        int[][] ctors = new int[nT][];
        int all0 = blob[Claims.typeEntry(0) + Claims.T_ALL];
        if (lists.length(all0) != nT) {
            unavailable("an inductive record whose `all` is not its types");
            return;
        }
        for (int i = 0; i < nT; i++) {
            int e = Claims.typeEntry(i);
            if ((blob[e + Claims.T_FLAGS] & Claims.F_UNSAFE) != 0) {
                unavailable("an unsafe type record");
                return;
            }
            int list = blob[e + Claims.T_ALL];
            if (lists.length(list) != nT) {
                unavailable("an inductive record whose `all` is not its types");
                return;
            }
            for (int k = 0; k < nT; k++) {
                if (lists.get(list, k) != lists.get(all0, k)) {
                    unavailable("type records that disagree on `all`");
                    return;
                }
            }
        }
        for (int k = 0; k < nT; k++) {
            int c = names.constOf(lists.get(all0, k));
            if (c < first || c >= first + size || decls.kind(c) != DeclKind.INDUCT) {
                unavailable("an inductive record whose `all` is not its types");
                return;
            }
            for (int j = 0; j < k; j++) {
                if (types[j] == c) {
                    unavailable("an inductive record whose `all` repeats a type");
                    return;
                }
            }
            types[k] = c;
        }
        for (int i = 0; i < nT; i++) {
            int e = Claims.typeEntry(i);
            int cid = blob[e + Claims.T_CID];
            int k = -1;
            for (int j = 0; j < nT; j++) {
                if (types[j] == cid) {
                    k = j;
                }
            }
            if (k < 0) {
                unavailable("an inductive record whose `all` is not its types");
                return;
            }
            int list = blob[e + Claims.T_CTORS];
            int n = lists.length(list);
            ctors[k] = new int[n];
            for (int x = 0; x < n; x++) {
                int c = names.constOf(lists.get(list, x));
                if (c < first || c >= first + size || decls.kind(c) != DeclKind.CTOR) {
                    unavailable("a constructor list that names something else than a constructor record of the unit");
                    return;
                }
                ctors[k][x] = c;
            }
        }
        blockTypes[u] = types;
        blockCtors[u] = ctors;
        for (int k = 0; k < nT; k++) {
            state[types[k]] = 1;
        }
        for (int j = 0, nC = blob[Claims.H_CTORS]; j < nC; j++) {
            int e = Claims.ctorEntry(blob, j);
            int cid = blob[e + Claims.C_CID];
            unsafeMember[cid] = (blob[e + Claims.C_FLAGS] & Claims.F_UNSAFE) != 0;
            if (!unsafeMember[cid] && !quotSetAside(decls.name(cid))) {
                state[cid] = 1;
            }
        }
        int at = Claims.firstRec(blob);
        for (int j = 0, nR = blob[Claims.H_RECS]; j < nR; j++, at = Claims.nextRec(blob, at)) {
            int cid = blob[at + Claims.R_CID];
            unsafeMember[cid] = (blob[at + Claims.R_FLAGS] & Claims.F_UNSAFE) != 0;
            int list = blob[at + Claims.R_ALL];
            int n = lists.length(list);
            int[] all = new int[n];
            int m = 0;
            for (int x = 0; x < n; x++) {
                int c = names.constOf(lists.get(list, x));
                if (c > 0) {
                    all[m++] = c;
                }
            }
            recAll[cid] = Arrays.copyOf(all, m);
            if (!unsafeMember[cid] && !quotSetAside(decls.name(cid))) {
                state[cid] = 1;
            }
        }
    }

    // ---- the replay ------------------------------------------------------------------------------------

    /** The constants a constant's record makes the replay visit first, ascending in Name.quickCmp. */
    private int[] used(int cid) {
        nFound = 0;
        int kind = decls.kind(cid);
        mentions(decls.type(cid));
        switch (kind) {
            case DeclKind.DEFN:
            case DeclKind.THM:
            case DeclKind.OPAQUE: {
                int v = decls.value(cid);
                if (v > 0) {
                    mentions(v);
                } else if (v < 0) {
                    Overlay overlay = terms.overlay();
                    if (overlay == null) {
                        unavailable("no overlay to decode a proof block");
                    } else {
                        int root = overlay.blockRoot(-(v + 1));
                        terms.refresh();
                        mentions(root);
                    }
                }
                break;
            }
            case DeclKind.INDUCT: {
                int u = decls.unit(cid);
                int[] types = blockTypes[u];
                for (int k = 0; types != null && k < types.length; k++) {
                    if (types[k] == cid) {
                        for (int c : blockCtors[u][k]) {
                            add(c);
                        }
                    }
                }
                break;
            }
            case DeclKind.CTOR:
                add(cid);
                break;
            case DeclKind.REC:
                if (recAll[cid] != null) {
                    for (int c : recAll[cid]) {
                        add(c);
                    }
                }
                break;
            default:
                break;
        }
        return sorted();
    }

    private void add(int cid) {
        for (int i = 0; i < nFound; i++) {
            if (found[i] == cid) {
                return;
            }
        }
        if (nFound == found.length) {
            found = Arrays.copyOf(found, nFound * 2);
        }
        found[nFound++] = cid;
    }

    /** Sorts {@link #found} by the hash of the names (unsigned), a tie makes the model unavailable. */
    private int[] sorted() {
        int n = nFound;
        int[] out = Arrays.copyOf(found, n);
        long[] key = new long[n];
        for (int i = 0; i < n; i++) {
            key[i] = nameHash(decls.name(out[i]));
        }
        // insertion sort: the sets are small; larger ones go through a boxed sort
        if (n <= 64) {
            for (int i = 1; i < n; i++) {
                int c = out[i];
                long k = key[i];
                int j = i - 1;
                while (j >= 0 && Long.compareUnsigned(key[j], k) > 0) {
                    out[j + 1] = out[j];
                    key[j + 1] = key[j];
                    j--;
                }
                out[j + 1] = c;
                key[j + 1] = k;
            }
        } else {
            Integer[] idx = new Integer[n];
            for (int i = 0; i < n; i++) {
                idx[i] = i;
            }
            final long[] kk = key;
            Arrays.sort(idx, (a, b) -> Long.compareUnsigned(kk[a], kk[b]));
            int[] o2 = new int[n];
            long[] k2 = new long[n];
            for (int i = 0; i < n; i++) {
                o2[i] = out[idx[i]];
                k2[i] = key[idx[i]];
            }
            out = o2;
            key = k2;
        }
        for (int i = 1; i < n; i++) {
            if (key[i] == key[i - 1]) {
                unavailable("two names with the same hash");
            }
        }
        return out;
    }

    /** Adds to {@link #found} the constant every CONST / PROJ below {@code root} names (if it has a record). */
    private void mentions(int root) {
        if (root <= 0) {
            return;
        }
        int sp = 0;
        stack[sp++] = root;
        while (sp > 0) {
            int t = stack[--sp];
            int header = terms.header(t);
            if ((header & (Node.F_HAS_CONST | Node.F_UNRESOLVED)) == 0) {
                continue;
            }
            if (!markSeen(t)) {
                continue;
            }
            if (--budget < 0) {
                unavailable("the file is too large for the simulation of the official order");
                break;
            }
            if (sp + 3 > stack.length) {
                stack = Arrays.copyOf(stack, stack.length * 2);
            }
            switch (Node.kind(header)) {
                case Kind.CONST:
                    resolve(terms.a(t));
                    break;
                case Kind.PROJ:
                    resolve(terms.c(t));
                    stack[sp++] = terms.a(t);
                    break;
                case Kind.APP:
                case Kind.LAM:
                case Kind.PI:
                    stack[sp++] = terms.b(t);
                    stack[sp++] = terms.a(t);
                    break;
                case Kind.LET:
                    stack[sp++] = terms.c(t);
                    stack[sp++] = terms.b(t);
                    stack[sp++] = terms.a(t);
                    break;
                default:
                    break;
            }
        }
        for (int k = 0; k < seenCount; k++) {
            seen[seenSlots[k]] = 0;
        }
        seenCount = 0;
    }

    private void resolve(int ref) {
        int cid = ref > 0 ? ref : ref == 0 ? 0 : names.constOf(~ref);
        if (cid > 0) {
            add(cid);
        }
    }

    private boolean markSeen(int term) {
        if ((seenCount + 1) * 2 > seen.length) {
            int[] old = seen;
            seen = new int[old.length * 2];
            int n = seenCount;
            seenCount = 0;
            int[] slots = seenSlots;
            seenSlots = new int[Math.max(slots.length, n * 2)];
            for (int k = 0; k < n; k++) {
                insert(old[slots[k]]);
            }
        }
        int mask = seen.length - 1;
        int i = (term * 0x9E3779B1) >>> 8 & mask;
        while (seen[i] != 0) {
            if (seen[i] == term) {
                return false;
            }
            i = (i + 1) & mask;
        }
        insert(term);
        return true;
    }

    private void insert(int term) {
        int mask = seen.length - 1;
        int i = (term * 0x9E3779B1) >>> 8 & mask;
        while (seen[i] != 0) {
            i = (i + 1) & mask;
        }
        seen[i] = term;
        if (seenCount == seenSlots.length) {
            seenSlots = Arrays.copyOf(seenSlots, seenCount * 2);
        }
        seenSlots[seenCount++] = i;
    }

    private void run() {
        // the main loop: every replayed name, ascending
        int nConst = decls.constantCount();
        nFound = 0;
        for (int cid = 1; cid <= nConst; cid++) {
            if (state[cid] == 1) {
                if (nFound == found.length) {
                    found = Arrays.copyOf(found, nFound * 2);
                }
                found[nFound++] = cid;
            }
        }
        int[] order = sorted();
        // explicit stack of frames: constant, its sorted dependencies, position, phase
        int cap = 64;
        int[] fCid = new int[cap];
        int[][] fDeps = new int[cap][];
        int[] fPos = new int[cap];
        int[] fPhase = new int[cap];
        for (int root : order) {
            if (!available) {
                return;
            }
            if (state[root] != 1) {
                continue;
            }
            int sp = 0;
            state[root] = 2;
            fCid[0] = root;
            fDeps[0] = used(root);
            fPos[0] = 0;
            fPhase[0] = 0;
            sp = 1;
            while (sp > 0) {
                if (!available) {
                    return;
                }
                int f = sp - 1;
                int cid = fCid[f];
                int[] deps = fDeps[f];
                if (fPos[f] < deps.length) {
                    int d = deps[fPos[f]++];
                    if (state[d] == 1) {
                        if (sp == cap) {
                            cap *= 2;
                            fCid = Arrays.copyOf(fCid, cap);
                            fDeps = Arrays.copyOf(fDeps, cap);
                            fPos = Arrays.copyOf(fPos, cap);
                            fPhase = Arrays.copyOf(fPhase, cap);
                        }
                        state[d] = 2;
                        fCid[sp] = d;
                        fDeps[sp] = used(d);
                        fPos[sp] = 0;
                        fPhase[sp] = 0;
                        sp++;
                    }
                    continue;
                }
                // dependencies of this phase done
                int kind = decls.kind(cid);
                int u = decls.unit(cid);
                if (fPhase[f] == 0) {
                    if (state[cid] != 2) {
                        sp--; // a mutual block took care of it
                        continue;
                    }
                    if (kind == DeclKind.INDUCT) {
                        // erase the block's types, then replay what the constructors' types mention
                        int[] types = blockTypes[u];
                        nFound = 0;
                        int[] all = new int[0];
                        for (int k = 0; k < types.length; k++) {
                            state[types[k]] = 3;
                        }
                        int total = 0;
                        int[][] parts = new int[64][];
                        int nParts = 0;
                        for (int k = 0; k < types.length; k++) {
                            for (int c : blockCtors[u][k]) {
                                int[] part = used(c);
                                if (nParts == parts.length) {
                                    parts = Arrays.copyOf(parts, nParts * 2);
                                }
                                parts[nParts++] = part;
                                total += part.length;
                            }
                        }
                        all = new int[total];
                        int at = 0;
                        for (int k = 0; k < nParts; k++) {
                            System.arraycopy(parts[k], 0, all, at, parts[k].length);
                            at += parts[k].length;
                        }
                        fDeps[f] = all;
                        fPos[f] = 0;
                        fPhase[f] = 1;
                        continue;
                    }
                    if (kind >= DeclKind.QUOT_TYPE && kind <= DeclKind.QUOT_IND) {
                        int eqName = specials.nameOf(Specials.EQ);
                        int eq = eqName == 0 ? 0 : names.constOf(eqName);
                        fDeps[f] = eq > 0 ? new int[] {eq} : new int[0];
                        fPos[f] = 0;
                        fPhase[f] = 1;
                        continue;
                    }
                    if (kind == DeclKind.AXIOM || kind == DeclKind.DEFN || kind == DeclKind.THM
                            || kind == DeclKind.OPAQUE) {
                        added(u);
                    }
                    // constructor, recursor: postponed
                    state[cid] = 3;
                    sp--;
                    continue;
                }
                // phase 1 finished: the block / the quotient package is added
                if (kind == DeclKind.INDUCT) {
                    added(u);
                } else {
                    if (quotTime == 0) {
                        quotTime = ++clock;
                    }
                    time[u] = quotTime;
                    state[cid] = 3;
                }
                sp--;
            }
        }
    }

    /** Unit {@code u} is added now: is everything its kernel-checked roots mention there? */
    private void added(int u) {
        time[u] = ++clock;
        if (absentUnit >= 0) {
            return;
        }
        int first = decls.unitFirstCid(u);
        int size = decls.unitSize(u);
        int kind = decls.unitKind(u);
        nFound = 0;
        if (kind == DeclKind.INDUCT) {
            for (int cid = first; cid < first + size; cid++) {
                int k = decls.kind(cid);
                if (k == DeclKind.INDUCT || k == DeclKind.CTOR) {
                    mentions(decls.type(cid));
                }
            }
        } else {
            mentions(decls.type(first));
            int v = kind == DeclKind.AXIOM ? 0 : decls.value(first);
            if (v > 0) {
                mentions(v);
            } else if (v < 0 && terms.overlay() != null) {
                int root = terms.overlay().blockRoot(-(v + 1));
                terms.refresh();
                mentions(root);
            }
        }
        for (int i = 0; i < nFound; i++) {
            int c = found[i];
            int p = decls.unit(c);
            if (p == u) {
                continue;
            }
            int k = decls.kind(c);
            boolean present;
            if (k >= DeclKind.QUOT_TYPE && k <= DeclKind.QUOT_IND) {
                int which = specials.indexOfName(decls.name(c));
                present = which >= Specials.QUOT && which <= Specials.QUOT_IND && quotTime != 0;
            } else {
                present = time[p] != 0;
            }
            if (!present) {
                absentUnit = u;
                absentCid = c;
                return;
            }
        }
    }
}

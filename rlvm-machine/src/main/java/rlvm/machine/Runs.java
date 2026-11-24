package rlvm.machine;

import static rlvm.machine.Op.SIG;

import rlvm.api.MachineErrors;

/**
 * Runs and working environments (ISA 2.4): interned spines and frames in the run slab, {@code PRUNE},
 * and the non-interned environment slab. Owner: M1.
 *
 * <h2>Runs</h2>
 * <pre>
 * rs[r]      len:24 (31..8) | 0:4 | OPEN (bit 3) | kind:2 (2..1) | far:1 (0)        (Layout; OPEN: NOTES-M1 D2)
 * rs[r + 1]  content hash (FRAME, REST: Hash.run; SPINE: the hash of its NEU)
 * rs[r + 2]  far frames only: the id of the REST run
 * then the slots; slot 0 is the outermost binding, the first argument
 * </pre>
 * A FRAME is interned by {@code (slots, REST run)}, a REST run by {@code (slots, parent REST run)}; a SPINE
 * is owned by the NEU record whose probe compares it. Run 0 is the empty frame and the empty spine. A far
 * frame has exactly {@link Layout#NEAR_SLOTS} near slots (de Bruijn indices 0..62) and a REST run that holds
 * the bindings of the indices 63 and above, unpruned.
 *
 * <p>The REST bindings are stored as a <b>chain of chunks</b> (NOTES-M1 D14): the outer bindings
 * {@code e[0 .. n)} are cut, from the outermost, into runs of {@link #REST_CHUNK} slots and one shorter
 * tail; every chunk but the first has the far bit and names its predecessor in the REST word. The id of the
 * last chunk is the id of the whole sequence (equal sequences give equal chains, by induction over the
 * chunks), and it is what a far frame holds. Environments that grow by one binding per level share all
 * complete chunks, so a binder nest of depth N costs O(N) run words for its REST runs, not O(N^2).
 *
 * <p>Bit 3 of the header, {@link #RUN_OPEN}, says that some slot (or the REST run) holds a value with flag
 * OPEN; it is a function of the content, written when the run is made, and is where closures and
 * suspensions take their OPEN flag from (one load instead of a walk over the frame).
 *
 * <h2>Environments</h2>
 * {@code (eb, en)}: en value refs at {@code es[eb ..]}, outermost first; working storage, never interned,
 * never pointed to by a value. {@link #push} and {@link #enter} leave the base in {@code m.envBase}; the
 * judgment that pushed or entered truncates {@code m.ep} when it completes (I-STACKS).
 */
final class Runs {
    private Runs() {}

    /**
     * Run header bit 3 ({@link Layout#RUN_OPEN}): some slot of the run, or its REST run, holds an OPEN value. Not
     * part of the run's identity (it is a function of it).
     */
    static final int RUN_OPEN = Layout.RUN_OPEN;
    /** {@code (hdr & RUN_OPEN) << RUN_OPEN_TO_W0 == Layout.W0_OPEN} when the bit is set. */
    static final int RUN_OPEN_TO_W0 = Layout.FLAG_SHIFT - 3;
    /** Longest run: the length field of the header has 24 bits. */
    static final int RUN_LEN_MAX = (1 << 24) - 1;
    /** Slots per chunk of a REST chain (the last chunk of a chain may be shorter). */
    static final int REST_CHUNK = 64;

    /**
     * SPINE(buf, n) (ISA 2.4): the run of X[xb .. xb + n), owned by the NEU record that is being interned.
     * {@code Values.mkNeu} / {@code mkApp} make the spine of a NEU themselves, in the same probe; this is the
     * stand-alone form (an argument run that is not a value's spine). Not interned.
     * @return the run id (0 for n = 0), or ABORT (E_LIMIT) or FATAL (E_MEM)
     */
    static int spine(Machine m, int xb, int n) {
        if (n <= 0) {
            return Layout.RUN_EMPTY;
        }
        if (n > RUN_LEN_MAX) {
            return m.fail(MachineErrors.E_LIMIT);
        }
        int[] x = m.X;
        int h = Hash.seed(n);
        for (int i = 0; i < n; i++) {
            h = Hash.step(h, x[xb + i]);
        }
        int hdr = Layout.runHeader(n, Layout.RUN_SPINE, false) | openOf(m, x, xb, n);
        int run = m.addRun(hdr, h, 0);
        if (run < SIG) {
            System.arraycopy(x, xb, m.rs, run + Layout.RUN_H, n);
        }
        return run;
    }

    /**
     * FRAME(buf, n, rest) (ISA 2.4): the canonical frame of X[xb .. xb + n), interned by content in rtab; the key is
     * (slots, rest) (RECONCILIATION section 2: runRest is part of the interning key). {@code rest} = 0 for a near
     * frame; a far frame has n = 63.
     * @return the run id (0 for the empty frame), or ABORT (E_LIMIT) or FATAL (E_MEM)
     */
    static int frame(Machine m, int xb, int n, int rest) {
        if (rest >= SIG) {
            return rest;
        }
        if (n <= 0 && rest == 0) {
            return Layout.RUN_EMPTY;
        }
        return internRun(m, m.X, xb, n, Layout.RUN_FRAME, rest);
    }

    /**
     * The REST run of X[xb .. xb + n) (ISA 2.4): the bindings of a far frame beyond its 63 near slots, interned by
     * content as a chain of chunks; the result is the id of the last chunk (0 for n = 0).
     */
    static int restRun(Machine m, int xb, int n) {
        return restChain(m, m.X, xb, n);
    }

    /**
     * The chain of REST chunks of {@code src[sb .. sb + n)} (class comment): each chunk is interned by its slots
     * and the id of its predecessor, so the last id identifies the sequence.
     */
    private static int restChain(Machine m, int[] src, int sb, int n) {
        int parent = 0;
        for (int i = 0; i < n; i += REST_CHUNK) {
            parent = internRun(m, src, sb + i, Math.min(REST_CHUNK, n - i), Layout.RUN_REST, parent);
            if (parent >= SIG) {
                return parent;
            }
        }
        return parent;
    }

    /**
     * PRUNE(e, mask) (ISA 2.4): the FRAME that agrees with environment (eb, en) on the indices in {@code mask}
     * (bits 0..62), HOLE elsewhere, length = highest set bit + 1; bit 63: the REST run holds the environment
     * without its 63 innermost slots. The mask never under-approximates (mask contract, R05 6.1).
     *
     * <p>The frame is found without building it: the hash is folded over the environment under the mask and the
     * probe compares against the environment. Bit 63 on an environment of at most 63 slots is dropped (no
     * index >= 63 can be read there), so the frame is a near one.
     * @return the run id (0 for mask 0), or FATAL (E_INTERNAL: the mask names an index outside the environment;
     *     E_MEM) or ABORT (E_LIMIT)
     */
    static int prune(Machine m, int eb, int en, long mask) {
        if (mask == 0) {
            return Layout.RUN_EMPTY;
        }
        if (mask < 0) {
            return pruneFar(m, eb, en, mask);
        }
        int len = 64 - Long.numberOfLeadingZeros(mask);
        if (len > en) {
            return m.fault(MachineErrors.E_INTERNAL);
        }
        return pruneNear(m, eb + en - len, len, mask, 0);
    }

    /** Mask bit 63 (ISA 2.4): 63 near slots and the REST run of the outer bindings. */
    private static int pruneFar(Machine m, int eb, int en, long mask) {
        long near = mask & ~Layout.MASK_FAR;
        if (en <= Layout.NEAR_SLOTS) {
            return near == 0 ? Layout.RUN_EMPTY : prune(m, eb, en, near);
        }
        int rest = restChain(m, m.es, eb, en - Layout.NEAR_SLOTS);
        if (rest >= SIG) {
            return rest;
        }
        return pruneNear(m, eb + en - Layout.NEAR_SLOTS, Layout.NEAR_SLOTS, near, rest);
    }

    /**
     * The frame of the {@code len} environment slots at es[base ..] under {@code mask} (bit i = de Bruijn index i =
     * slot len - 1 - i), with REST run {@code rest}.
     */
    private static int pruneNear(Machine m, int base, int len, long mask, int rest) {
        int[] es = m.es;
        int h = Hash.seed(len);
        for (int j = 0, i = len - 1; j < len; j++, i--) {
            h = Hash.step(h, (mask >>> i & 1) != 0 ? es[base + j] : Layout.HOLE);
        }
        int hash = Hash.run(h, Layout.RUN_FRAME, rest);
        int hdr = Layout.runHeader(len, Layout.RUN_FRAME, rest != 0);
        int[] tab = m.rtab;
        int[] rs = m.rs;
        int tmask = tab.length - 1; // == m.rtabMask
        int slot = hash & tmask;
        for (;;) {
            int e = tab[slot];
            if (e == 0) {
                return pruneSlow(m, slot, hash, hdr, base, len, mask, rest);
            }
            if (((e ^ hash) >>> Layout.TAB_TAG_SHIFT) == 0) {
                int r = e & Layout.TAB_INDEX_MASK;
                if ((rs[r] & ~RUN_OPEN) == hdr && rs[r + Layout.RUN_HASH] == hash
                        && (rest == 0 || rs[r + Layout.RUN_REST_WORD] == rest)
                        && sameMasked(rs, r + Layout.RUN_H + (hdr & Layout.RUN_FAR), es, base, len, mask)) {
                    return r;
                }
            }
            slot = (slot + 1) & tmask;
        }
    }

    private static boolean sameMasked(int[] rs, int sb, int[] es, int base, int len, long mask) {
        for (int j = 0, i = len - 1; j < len; j++, i--) {
            if (rs[sb + j] != ((mask >>> i & 1) != 0 ? es[base + j] : Layout.HOLE)) {
                return false;
            }
        }
        return true;
    }

    /** The miss of {@link #pruneNear}: writes the frame. */
    private static int pruneSlow(Machine m, int slot, int hash, int hdr, int base, int len, long mask, int rest) {
        int open = rest != 0 && (m.rs[rest] & RUN_OPEN) != 0 ? RUN_OPEN : openMasked(m, base, len, mask);
        int run = m.addRun(hdr | open, hash, rest);
        if (run >= SIG) {
            return run;
        }
        int[] es = m.es;
        int[] rs = m.rs;
        int sb = run + Layout.RUN_H + (hdr & Layout.RUN_FAR);
        for (int j = 0, i = len - 1; j < len; j++, i--) {
            rs[sb + j] = (mask >>> i & 1) != 0 ? es[base + j] : Layout.HOLE;
        }
        m.putRunAt(slot, hash, run);
        return run;
    }

    /**
     * {@link #RUN_OPEN} if a slot that the mask keeps holds an OPEN value, else 0. Only the kept slots are looked
     * at, the innermost binding first (under open binders that is almost always a free variable), and the walk
     * stops at the first OPEN one. The test is {@link Values#openOf}: the dense bit table, not the record (the
     * load of every kept slot's record header was the hottest line of the whole machine on Init, NOTES-M1
     * section 3).
     */
    private static int openMasked(Machine m, int base, int len, long mask) {
        int[] es = m.es;
        int top = base + len - 1;
        for (long b = mask; b != 0; b &= b - 1) {
            if (Values.openOf(m, es[top - Long.numberOfTrailingZeros(b)]) != 0) {
                return RUN_OPEN;
            }
        }
        return 0;
    }

    /**
     * The FRAME or REST run of {@code src[sb .. sb + n)} with REST run {@code rest}, interned by content
     * (ISA 2.4, 2.5 I-ID: for a far frame the components are the near slots and the id of the REST run).
     */
    private static int internRun(Machine m, int[] src, int sb, int n, int kind, int rest) {
        if (n > RUN_LEN_MAX) {
            return m.fail(MachineErrors.E_LIMIT);
        }
        int h = Hash.seed(n);
        for (int i = 0; i < n; i++) {
            h = Hash.step(h, src[sb + i]);
        }
        int hash = Hash.run(h, kind, rest);
        int hdr = Layout.runHeader(n, kind, rest != 0);
        int[] tab = m.rtab;
        int[] rs = m.rs;
        int tmask = tab.length - 1; // == m.rtabMask
        int slot = hash & tmask;
        for (;;) {
            int e = tab[slot];
            if (e == 0) {
                return internRunSlow(m, slot, hash, hdr, src, sb, n, rest);
            }
            if (((e ^ hash) >>> Layout.TAB_TAG_SHIFT) == 0) {
                int r = e & Layout.TAB_INDEX_MASK;
                if ((rs[r] & ~RUN_OPEN) == hdr && rs[r + Layout.RUN_HASH] == hash
                        && (rest == 0 || rs[r + Layout.RUN_REST_WORD] == rest)
                        && same(rs, r + Layout.RUN_H + (hdr & Layout.RUN_FAR), src, sb, n)) {
                    return r;
                }
            }
            slot = (slot + 1) & tmask;
        }
    }

    private static boolean same(int[] rs, int rb, int[] src, int sb, int n) {
        for (int i = 0; i < n; i++) {
            if (rs[rb + i] != src[sb + i]) {
                return false;
            }
        }
        return true;
    }

    private static int internRunSlow(Machine m, int slot, int hash, int hdr, int[] src, int sb, int n, int rest) {
        int open = rest != 0 && (m.rs[rest] & RUN_OPEN) != 0 ? RUN_OPEN : openOf(m, src, sb, n);
        int run = m.addRun(hdr | open, hash, rest);
        if (run >= SIG) {
            return run;
        }
        System.arraycopy(src, sb, m.rs, run + Layout.RUN_H + (hdr & Layout.RUN_FAR), n);
        m.putRunAt(slot, hash, run);
        return run;
    }

    /** {@link #RUN_OPEN} if one of the n refs at src[sb ..] is a record with flag OPEN, else 0. */
    private static int openOf(Machine m, int[] src, int sb, int n) {
        for (int i = n - 1; i >= 0; i--) {
            if (Values.openOf(m, src[sb + i]) != 0) {
                return RUN_OPEN;
            }
        }
        return 0;
    }

    /**
     * Length of a run; 0 for the empty run. For a far frame: its near slots (63).
     */
    static int len(Machine m, int run) {
        return m.rs[run] >>> Layout.RUN_LEN_SHIFT;
    }

    /**
     * The REST run of a far frame (the last chunk of its chain), 0 for a near frame (ISA 2.4). For a REST chunk:
     * its predecessor in the chain, 0 for the first chunk.
     */
    static int rest(Machine m, int run) {
        int[] rs = m.rs;
        return (rs[run] & Layout.RUN_FAR) != 0 ? rs[run + Layout.RUN_REST_WORD] : 0;
    }

    /** Number of bindings in the REST chain that ends in chunk {@code rest} (0 for 0). */
    static int restLen(Machine m, int rest) {
        int[] rs = m.rs;
        int n = 0;
        while (rest != 0) {
            int hdr = rs[rest];
            n += hdr >>> Layout.RUN_LEN_SHIFT;
            rest = (hdr & Layout.RUN_FAR) != 0 ? rs[rest + Layout.RUN_REST_WORD] : 0;
        }
        return n;
    }

    /** True if some slot of the run (or its REST run) holds an OPEN value: the OPEN flag of a closure over it. */
    static boolean isOpen(Machine m, int run) {
        return (m.rs[run] & RUN_OPEN) != 0;
    }

    /** Number of bindings {@link #enter} produces for a frame: its near slots plus the slots of its REST run. */
    static int envLen(Machine m, int frame) {
        int[] rs = m.rs;
        int hdr = rs[frame];
        int n = hdr >>> Layout.RUN_LEN_SHIFT;
        return (hdr & Layout.RUN_FAR) != 0 ? n + restLen(m, rs[frame + Layout.RUN_REST_WORD]) : n;
    }

    /**
     * ARG(r, j) (ISA 2.4): the j-th argument of a spine, the j-th captured slot of a frame.
     */
    static int arg(Machine m, int run, int j) {
        int[] rs = m.rs;
        return rs[run + Layout.RUN_H + (rs[run] & Layout.RUN_FAR) + j];
    }

    /**
     * FGET(r, i) (ISA 2.4): de Bruijn index i of a frame; slot 0 is the outermost. Near slots only.
     */
    static int fget(Machine m, int run, int i) {
        int[] rs = m.rs;
        int hdr = rs[run];
        return rs[run + Layout.RUN_H + (hdr & Layout.RUN_FAR) + (hdr >>> Layout.RUN_LEN_SHIFT) - 1 - i];
    }

    /**
     * GET(eb, en, i) (ISA 2.4): de Bruijn index i of a working environment; bounds-checked in every build.
     * @return the value; FATAL (E_INTERNAL) out of bounds; FATAL (E_HOLE) if the slot holds HOLE (ISA 2.1)
     */
    static int get(Machine m, int eb, int en, int i) {
        if (i < 0 || i >= en) {
            return m.fault(MachineErrors.E_INTERNAL);
        }
        int v = m.es[eb + en - 1 - i];
        if (v == Layout.HOLE) {
            return m.fault(MachineErrors.E_HOLE);
        }
        if ((m.debug & Op.DBG_SCOPEAUDIT) != 0 && Debug.scopeAudit(m, v) != 0) {
            return Op.FATAL; // I-FV3 audited at every GET (ISA 2.8, 5.5)
        }
        return v;
    }

    /**
     * PUSH(e, v) (ISA 2.4): the environment extended by v; in place when (eb, en) ends at the top of es, else
     * the environment is copied to the top first.
     * @return the new length; {@code m.envBase} holds the base; -1 if es is full (the caller faults E_MEM)
     */
    static int push(Machine m, int eb, int en, int v) {
        int ep = m.ep;
        if (eb + en == ep && ep < m.es.length) {
            m.es[ep] = v;
            m.ep = ep + 1;
            m.envBase = eb;
            return en + 1;
        }
        return pushSlow(m, eb, en, v);
    }

    private static int pushSlow(Machine m, int eb, int en, int v) {
        int ep = m.ep;
        if (eb + en == ep) {
            if (!m.ensureEnv(1)) {
                return -1;
            }
            m.es[ep] = v;
            m.ep = ep + 1;
            m.envBase = eb;
            return en + 1;
        }
        if (!m.ensureEnv(en + 1)) {
            return -1;
        }
        int[] es = m.es;
        System.arraycopy(es, eb, es, ep, en);
        es[ep + en] = v;
        m.ep = ep + en + 1;
        m.envBase = ep;
        return en + 1;
    }

    /**
     * ENTER(fr) (ISA 2.4): the frame's slots copied to the top of es (a far frame copies its REST run first).
     * @return the length; {@code m.envBase} holds the base; -1 if es is full
     */
    static int enter(Machine m, int frame) {
        int[] rs = m.rs;
        int hdr = rs[frame];
        int n = hdr >>> Layout.RUN_LEN_SHIFT;
        int ep = m.ep;
        if ((hdr & Layout.RUN_FAR) != 0 || ep + n > m.es.length) {
            return enterSlow(m, frame);
        }
        System.arraycopy(rs, frame + Layout.RUN_H, m.es, ep, n);
        m.ep = ep + n;
        m.envBase = ep;
        return n;
    }

    private static int enterSlow(Machine m, int frame) {
        int[] rs = m.rs;
        int hdr = rs[frame];
        int n = hdr >>> Layout.RUN_LEN_SHIFT;
        int rest = (hdr & Layout.RUN_FAR) != 0 ? rs[frame + Layout.RUN_REST_WORD] : 0;
        int rn = restLen(m, rest);
        if (!m.ensureEnv(rn + n)) {
            return -1;
        }
        int ep = m.ep;
        int[] es = m.es;
        // the chain is walked from its last chunk (the innermost of the outer bindings) back to the first
        int pos = ep + rn;
        while (rest != 0) {
            int h = rs[rest];
            int len = h >>> Layout.RUN_LEN_SHIFT;
            pos -= len;
            System.arraycopy(rs, rest + Layout.RUN_H + (h & Layout.RUN_FAR), es, pos, len);
            rest = (h & Layout.RUN_FAR) != 0 ? rs[rest + Layout.RUN_REST_WORD] : 0;
        }
        System.arraycopy(rs, frame + Layout.RUN_H + (hdr & Layout.RUN_FAR), es, ep + rn, n);
        m.ep = ep + rn + n;
        m.envBase = ep;
        return rn + n;
    }
}

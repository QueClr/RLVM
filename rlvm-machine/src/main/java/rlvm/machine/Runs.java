package rlvm.machine;

import static rlvm.machine.Op.SIG;

import rlvm.api.MachineErrors;

/**
 * Runs and working environments (ISA 2.4): interned spines and frames in the run slab, {@code PRUNE},
 * and the non-interned environment slab. Owner: M1.
 *
 * <h2>Runs</h2>
 * <pre>
 * rs[r]      len:24 (31..8) | count:4 (7..4) | OPEN (bit 3) | kind:2 (2..1) | far:1 (0)
 *            (Layout; OPEN: NOTES-M1 D2; count: FRAME and REST only, the number of slots that are not HOLE,
 *            saturating at 15: NOTES-M1 D16)
 * rs[r + 1]  content hash (FRAME, REST: {@link #runHash} of the sum of {@link #slotHash}; SPINE: the hash of its NEU,
 *            0 for a stand-alone spine)
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
     * Counters of an instrumented build: the distributions of NOTES-M1 section 5 (frame lengths against kept
     * slots, hits against new frames, spine lengths, closures by frame size, probe steps, run words by kind).
     * {@link #ON} is a compile-time constant, so a published build contains none of the hooks; an instrumented
     * build sets it to {@code Boolean.getBoolean("rlvm.m1.stat")} and runs one worker with
     * {@code -Drlvm.m1.stat=true [-Drlvm.m1.stat.out=FILE]} and {@code RLVM_EXIT=exit}.
     */
    static final class Stat {
        static final boolean ON = false;
        static long pruneCalls, pruneHit, pruneNew, pruneFar, sameAsEntered, enteredPlus, pruneSteps;
        static long lenSum, keptSum, newLenSum, newKeptSum;
        static final long[] lenH = new long[65], keptH = new long[65], newLenH = new long[65], newKeptH = new long[65];
        static final long[] ratioH = new long[16];
        static long restCalls, restSlots, restNew, restHitChunks, restChunks;
        static long enterCalls, enterSlots, enterFar, pushCalls, pushCopy;
        static long cloProbe, cloNew;
        static final long[] cloKeptH = new long[65], cloNewKeptH = new long[65], cloKind = new long[16], cloNewKind = new long[16];
        static long neuProbe, neuNew, neuExtProbe, neuSteps;
        static final long[] neuLenH = new long[33], neuNewLenH = new long[33];
        static long wordsFrame, wordsSpine, wordsRest;
        static int lastBase = -1, lastN, lastFrame;
        // simulated direct-mapped (top, mask) caches
        static final int[] SIM = {6, 8, 10, 12};
        static final long[][] simMask = new long[SIM.length][];
        static final int[][] simTop = new int[SIM.length][], simVal = new int[SIM.length][], simBase = new int[SIM.length][];
        static final long[] simHit = new long[SIM.length], simStale = new long[SIM.length];
        static long simEpoch;
        static final long[][] simEp = new long[SIM.length][];
        static {
            for (int i = 0; i < SIM.length; i++) {
                simMask[i] = new long[1 << SIM[i]];
                simTop[i] = new int[1 << SIM[i]];
                simVal[i] = new int[1 << SIM[i]];
                simBase[i] = new int[1 << SIM[i]];
                simEp[i] = new long[1 << SIM[i]];
            }
            if (ON) {
                Runtime.getRuntime().addShutdownHook(new Thread(Stat::dump));
            }
        }

        // simulated direct-mapped front caches of records: [kind class][size] hits; cells (epoch << 32 | record)
        static final int[] RSIM = {6, 8, 10, 12, 14};
        static final long[][][] rsimCell = new long[3][RSIM.length][];
        static final long[][] rsimHit = new long[3][RSIM.length];
        static final long[] rsimProbe = new long[3], rsimOld = new long[3];
        static {
            for (int k = 0; k < 3; k++) {
                for (int i = 0; i < RSIM.length; i++) {
                    rsimCell[k][i] = new long[1 << RSIM[i]];
                }
            }
        }

        /** A record probe of class k (0 closure / suspension, 1 NEU, 2 other) with hash h ended in record r (isNew: made now). */
        static void rec(Machine m, int k, int h, int r, boolean isNew) {
            rsimProbe[k]++;
            if (!isNew) {
                rsimOld[k]++;
            }
            long key = (long) m.epoch << 32 | r;
            for (int i = 0; i < RSIM.length; i++) {
                int ix = h & ((1 << RSIM[i]) - 1);
                if (!isNew && rsimCell[k][i][ix] == key) {
                    rsimHit[k][i]++;
                }
                rsimCell[k][i][ix] = key;
            }
        }

        static int kept(Machine m, int frame) {
            int[] rs = m.rs;
            int hdr = rs[frame];
            int n = hdr >>> Layout.RUN_LEN_SHIFT;
            int b = frame + Layout.RUN_H + (hdr & Layout.RUN_FAR);
            int k = 0;
            for (int i = 0; i < n; i++) {
                if (rs[b + i] != Layout.HOLE) {
                    k++;
                }
            }
            return (hdr & Layout.RUN_FAR) != 0 ? 64 : k;
        }

        static void prune(Machine m, int base, int len, long mask, int rest, int result, boolean isNew) {
            pruneCalls++;
            int kept = Long.bitCount(mask);
            lenSum += len;
            keptSum += kept;
            lenH[len]++;
            keptH[kept]++;
            ratioH[Math.min(15, len / Math.max(1, kept))]++;
            if (rest != 0) {
                pruneFar++;
            }
            if (isNew) {
                pruneNew++;
                newLenSum += len;
                newKeptSum += kept;
                newLenH[len]++;
                newKeptH[kept]++;
                wordsFrame += 2 + len + (rest != 0 ? 1 : 0);
            } else {
                pruneHit++;
            }
            if (result == lastFrame && base == lastBase && len == lastN) {
                sameAsEntered++;
            } else if (result == lastFrame) {
                enteredPlus++;
            }
            int top = base + len;
            for (int i = 0; i < SIM.length; i++) {
                int ix = Hash.fin((int) (mask * 0x9E3779B97F4A7C15L >>> 32) + top * 0x85EBCA6B) & ((1 << SIM[i]) - 1);
                if (simEp[i][ix] == simEpoch && simTop[i][ix] == top && simMask[i][ix] == mask && rest == 0) {
                    if (simVal[i][ix] == result) {
                        simHit[i]++;
                    } else {
                        simStale[i]++;
                    }
                }
                if (rest == 0) {
                    simEp[i][ix] = simEpoch;
                    simTop[i][ix] = top;
                    simMask[i][ix] = mask;
                    simVal[i][ix] = result;
                }
            }
        }

        static String h(long[] a) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < a.length; i++) {
                if (a[i] != 0) {
                    sb.append(' ').append(i).append(':').append(a[i]);
                }
            }
            return sb.toString();
        }

        static void dump() {
            StringBuilder sb = new StringBuilder();
            sb.append("M1STAT prune calls ").append(pruneCalls).append(" hit ").append(pruneHit).append(" new ").append(pruneNew)
                    .append(" far ").append(pruneFar).append(" sameAsEntered ").append(sameAsEntered).append(" enteredPlus ").append(enteredPlus).append(" steps ").append(pruneSteps).append('\n');
            sb.append("M1STAT prune lenSum ").append(lenSum).append(" keptSum ").append(keptSum).append(" newLenSum ").append(newLenSum)
                    .append(" newKeptSum ").append(newKeptSum).append('\n');
            sb.append("M1STAT lenH").append(h(lenH)).append('\n');
            sb.append("M1STAT keptH").append(h(keptH)).append('\n');
            sb.append("M1STAT newLenH").append(h(newLenH)).append('\n');
            sb.append("M1STAT newKeptH").append(h(newKeptH)).append('\n');
            sb.append("M1STAT ratioH").append(h(ratioH)).append('\n');
            for (int i = 0; i < SIM.length; i++) {
                sb.append("M1STAT sim 2^").append(SIM[i]).append(" hit ").append(simHit[i]).append(" stale ").append(simStale[i]).append('\n');
            }
            sb.append("M1STAT rest calls ").append(restCalls).append(" slots ").append(restSlots).append(" chunks ").append(restChunks)
                    .append(" newChunks ").append(restNew).append('\n');
            sb.append("M1STAT enter calls ").append(enterCalls).append(" slots ").append(enterSlots).append(" far ").append(enterFar)
                    .append(" push ").append(pushCalls).append(" pushCopy ").append(pushCopy).append('\n');
            sb.append("M1STAT clo probe ").append(cloProbe).append(" new ").append(cloNew).append('\n');
            sb.append("M1STAT cloKeptH").append(h(cloKeptH)).append('\n');
            sb.append("M1STAT cloNewKeptH").append(h(cloNewKeptH)).append('\n');
            sb.append("M1STAT cloKind").append(h(cloKind)).append('\n');
            sb.append("M1STAT cloNewKind").append(h(cloNewKind)).append('\n');
            sb.append("M1STAT neu probe ").append(neuProbe).append(" ext ").append(neuExtProbe).append(" new ").append(neuNew).append(" steps ").append(neuSteps).append('\n');
            sb.append("M1STAT neuLenH").append(h(neuLenH)).append('\n');
            sb.append("M1STAT neuNewLenH").append(h(neuNewLenH)).append('\n');
            for (int k = 0; k < 3; k++) {
                sb.append("M1STAT recsim ").append(k == 0 ? "closure" : k == 1 ? "neu" : "other").append(" probes ").append(rsimProbe[k])
                        .append(" found ").append(rsimOld[k]);
                for (int i = 0; i < RSIM.length; i++) {
                    sb.append(" 2^").append(RSIM[i]).append(':').append(rsimHit[k][i]);
                }
                sb.append('\n');
            }
            sb.append("M1STAT words frame ").append(wordsFrame).append(" spine ").append(wordsSpine).append(" rest ").append(wordsRest).append('\n');
            System.err.print(sb);
            String f = System.getProperty("rlvm.m1.stat.out");
            if (f != null) {
                try {
                    java.nio.file.Files.writeString(java.nio.file.Path.of(f), sb.toString());
                } catch (java.io.IOException e) {
                    // ignore
                }
            }
        }
    }

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
     * Run header bits 4..7 of a FRAME or REST run: the number of slots that are not HOLE, saturating at
     * {@link #CNT_MAX} (NOTES-M1 D16). A function of the content like {@link #RUN_OPEN}; it lets the probe of
     * PRUNE compare the kept slots only: a candidate of the same length whose kept positions agree and whose count
     * is the number of kept bindings can have nothing but HOLE elsewhere. A saturated count proves nothing: such
     * a candidate is compared slot by slot.
     */
    static final int CNT_SHIFT = 4;
    static final int CNT_MAX = 15;
    static final int CNT_MASK = CNT_MAX << CNT_SHIFT;
    /** Multipliers of the content hash of a FRAME or REST run ({@link #slotHash}). */
    private static final int P1 = 0x9E3779B1;
    private static final int P2 = 0x85EBCA6B;
    private static final int P3 = 0xC2B2AE35;

    /**
     * The contribution of the binding {@code v} (not HOLE) at de Bruijn index {@code i} to the content hash of a
     * FRAME or REST run. The hash of a run is {@link #runHash} of the SUM of the contributions of its slots that
     * are not HOLE: a function of the content that PRUNE can compute from the kept slots alone, and whose
     * multiplications do not depend on each other (the chained {@code Hash.step} of ISA 13.3 cost one dependent
     * multiplication per slot of the frame's length: the hottest loop of the machine; NOTES-M1 section 5).
     */
    static int slotHash(int v, int i) {
        int t = (v + i * P1) * P2;
        return t ^ (t >>> 15);
    }

    /** The content hash of a run from the sum of its {@link #slotHash}es, its kind and its REST run (0 if not far). */
    static int runHash(int sum, int kind, int rest) {
        return Hash.fin(sum + rest * P3 + kind);
    }

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
        int hdr = Layout.runHeader(n, Layout.RUN_SPINE, false) | openOf(m, x, xb, n);
        int run = m.addRun(hdr, 0, 0); // not interned: the hash word of a stand-alone spine is unused
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
            if (Stat.ON) {
                Stat.restChunks++;
            }
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
        if (Stat.ON) {
            Stat.restCalls++;
            Stat.restSlots += en - Layout.NEAR_SLOTS;
        }
        int rest = restOf(m, eb, en - Layout.NEAR_SLOTS);
        if (rest >= SIG) {
            return rest;
        }
        return pruneNear(m, eb + en - Layout.NEAR_SLOTS, Layout.NEAR_SLOTS, near, rest);
    }

    /**
     * The REST chain of the environment slots es[eb .. eb + n) through a small direct-mapped cache
     * {@code (eb, n, epoch) -> chain} (NOTES-M1 section 5): every far PRUNE under one deep environment needs the
     * same chain, and finding it by content hashed and compared every outer binding again. A cell is a hint
     * only: it is used after the chain it names was compared with the environment slot by slot (no hashing, no
     * probe), so a stale cell - the slab was cut and rewritten - costs a comparison and nothing else.
     */
    private static int restOf(Machine m, int eb, int n) {
        int[] c = m.x1.restCache;
        int i = ((eb + n * 7) & (Values.Ext.REST_SLOTS - 1)) << Layout.CELL_SHIFT;
        if (c[i] == eb && c[i + 1] == n && c[i + 2] == m.epoch) {
            int r = c[i + 3];
            if (sameChain(m, r, eb, n)) {
                return r;
            }
        }
        int r = restChain(m, m.es, eb, n);
        if (r < SIG) {
            c = m.x1.restCache;
            c[i] = eb;
            c[i + 1] = n;
            c[i + 2] = m.epoch;
            c[i + 3] = r;
        }
        return r;
    }

    /** True if the REST chain that ends in chunk {@code rest} holds exactly the n bindings es[eb .. eb + n). */
    private static boolean sameChain(Machine m, int rest, int eb, int n) {
        int[] rs = m.rs;
        int[] es = m.es;
        int pos = eb + n;
        while (rest != 0) {
            int hdr = rs[rest];
            int len = hdr >>> Layout.RUN_LEN_SHIFT;
            pos -= len;
            if (pos < eb) {
                return false;
            }
            int b = rest + Layout.RUN_H + (hdr & Layout.RUN_FAR);
            if (!java.util.Arrays.equals(rs, b, b + len, es, pos, pos + len)) { // 44 slots on average on the Cedar slice
                return false;
            }
            rest = (hdr & Layout.RUN_FAR) != 0 ? rs[rest + Layout.RUN_REST_WORD] : 0;
        }
        return pos == eb;
    }

    /**
     * The frame of the {@code len} environment slots at es[base ..] under {@code mask} (bit i = de Bruijn index i =
     * slot len - 1 - i), with REST run {@code rest}. Only the kept slots are read: the hash is the sum of their
     * {@link #slotHash}es and a candidate is compared at the kept positions; its header count settles the rest
     * (class comment of {@link #CNT_SHIFT}).
     */
    private static int pruneNear(Machine m, int base, int len, long mask, int rest) {
        int[] es = m.es;
        int top = base + len - 1;
        int sum = 0;
        int cnt = 0;
        for (long b = mask; b != 0; b &= b - 1) {
            int i = Long.numberOfTrailingZeros(b);
            int v = es[top - i];
            if (v != Layout.HOLE) { // a kept binding may itself be the placeholder of an unread binder
                sum += slotHash(v, i);
                cnt++;
            }
        }
        int hash = runHash(sum, Layout.RUN_FRAME, rest);
        int hdr = Layout.runHeader(len, Layout.RUN_FRAME, rest != 0) | (cnt < CNT_MAX ? cnt : CNT_MAX) << CNT_SHIFT;
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
                        && sameKept(rs, r + Layout.RUN_H + (hdr & Layout.RUN_FAR) + len - 1, es, top, mask)
                        && (cnt < CNT_MAX || sameMasked(rs, r + Layout.RUN_H + (hdr & Layout.RUN_FAR), es, base, len, mask))) {
                    if (Stat.ON) {
                        Stat.prune(m, base, len, mask, rest, r, false);
                    }
                    return r;
                }
            }
            if (Stat.ON) {
                Stat.pruneSteps++;
            }
            slot = (slot + 1) & tmask;
        }
    }

    /** The kept positions of a candidate agree with the environment: rs[s0 - i] == es[top - i] for every bit i of the mask. */
    static boolean sameKept(int[] rs, int s0, int[] es, int top, long mask) {
        for (long b = mask; b != 0; b &= b - 1) {
            int i = Long.numberOfTrailingZeros(b);
            if (rs[s0 - i] != es[top - i]) {
                return false;
            }
        }
        return true;
    }

    /** Slot by slot: the candidate is the environment under the mask, HOLE elsewhere (candidates with a saturated count). */
    static boolean sameMasked(int[] rs, int sb, int[] es, int base, int len, long mask) {
        for (int j = 0, i = len - 1; j < len; j++, i--) {
            if (rs[sb + j] != ((mask >>> i & 1) != 0 ? es[base + j] : Layout.HOLE)) {
                return false;
            }
        }
        return true;
    }

    /** The miss of {@link #pruneNear}: writes the frame (HOLE everywhere, then the kept slots). */
    static int pruneSlow(Machine m, int slot, int hash, int hdr, int base, int len, long mask, int rest) {
        int open = rest != 0 && (m.rs[rest] & RUN_OPEN) != 0 ? RUN_OPEN : openMasked(m, base, len, mask);
        int run = m.addRun(hdr | open, hash, rest);
        if (run >= SIG) {
            return run;
        }
        int[] es = m.es;
        int[] rs = m.rs;
        int sb = run + Layout.RUN_H + (hdr & Layout.RUN_FAR);
        for (int j = 0; j < len; j++) {
            rs[sb + j] = Layout.HOLE;
        }
        int s0 = sb + len - 1;
        int top = base + len - 1;
        for (long b = mask; b != 0; b &= b - 1) {
            int i = Long.numberOfTrailingZeros(b);
            rs[s0 - i] = es[top - i];
        }
        m.putRunAt(slot, hash, run);
        if (Stat.ON) {
            Stat.prune(m, base, len, mask, rest, run, true);
        }
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
        int sum = 0;
        int cnt = 0;
        for (int j = 0, i = n - 1; j < n; j++, i--) {
            int v = src[sb + j];
            if (v != Layout.HOLE) {
                sum += slotHash(v, i);
                cnt++;
            }
        }
        int hash = runHash(sum, kind, rest);
        int hdr = Layout.runHeader(n, kind, rest != 0) | (cnt < CNT_MAX ? cnt : CNT_MAX) << CNT_SHIFT;
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
        if (Stat.ON && Layout.runKind(hdr) == Layout.RUN_REST) {
            Stat.restNew++;
            Stat.wordsRest += 2 + n + (hdr & Layout.RUN_FAR);
        }
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
        if (Stat.ON) {
            Stat.pushCalls++;
            Stat.simEpoch++;
            if (eb + en != ep) {
                Stat.pushCopy++;
            }
        }
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
        if (Stat.ON) {
            Stat.enterCalls++;
            Stat.enterSlots += n;
            Stat.simEpoch++;
            Stat.lastBase = ep;
            Stat.lastN = n;
            Stat.lastFrame = frame;
            if ((hdr & Layout.RUN_FAR) != 0) {
                Stat.enterFar++;
                Stat.lastBase = -1;
            }
        }
        if ((hdr & Layout.RUN_FAR) != 0 || ep + n > m.es.length) {
            return enterSlow(m, frame);
        }
        if (n <= Values.SMALL_COPY) {
            int[] es = m.es;
            for (int j = 0; j < n; j++) { // 2 slots on average on Init: cheaper than a call of arraycopy
                es[ep + j] = rs[frame + Layout.RUN_H + j];
            }
        } else {
            System.arraycopy(rs, frame + Layout.RUN_H, m.es, ep, n);
        }
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

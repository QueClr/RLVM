package rlvm.machine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.ServiceLoader;
import rlvm.api.Engine;

/**
 * The tier hook (ISA 8.1 row T2, 8.3 heat, 8.4, 8.5, 8.6): how an upper tier that lives outside this
 * module (the Truffle experiment, {@code rlvm-truffle}) takes over activations of code blocks, and how the
 * heat of the blocks is reported. Owner: M7; the hand-over part has the shape T2 proposed
 * (requests.log 2026-11-24T13:30Z).
 *
 * <p>{@code rlvm-machine} has no dependency on any such tier and makes no concession to it (ISA 8.4): a
 * block is the same {@code int[]} with or without a tier and the stepper's dispatch loop is unchanged.
 * A machine without a tier and without heat accounting pays one field load and test per activation.
 * Nothing here may change a result: a tier computes what {@link #interpret} computes (same value ids,
 * same frames, same signals); the differential and tier-equivalence gates apply to it as to any tier.
 *
 * <h2>Hand-over</h2>
 * One {@code Tier} object per {@link Machine} (the subclass holds worker-local state). It is made by the
 * {@link Factory} found through {@code ServiceLoader} (service file
 * {@code META-INF/services/rlvm.machine.Tier$Factory}), once per machine, at its first declaration, and only
 * if the machine runs with staging switch {@code t2} ({@code Op.SW_T2}; never in strict mode, ISA 14.2).
 * The stepper offers every activation to it, at entry ({@code Code.go}, after the depth test: an activation
 * that is too deep becomes a frame first and is offered when it is resumed) and at every resume
 * ({@code Code.run}). {@link #run} either runs the activation to its result or signal with exactly the
 * effects of the stepper, or answers {@link Op#MISS} before it changed anything, and the stepper runs it.
 *
 * <p>What {@code run(m, blk, pc, rb, d)} is given: the activation's window is on the register stack
 * (NOTES-M7 D1): {@code reg(m, rb, i)} for {@code i < arity} are the arguments, then the registers,
 * {@link #cap} / {@link #ls} the captured frame and the level substitution. {@code pc} is the instruction
 * to start with, relative to the block start: an entry of the block's entry table, or the resume pc of the
 * frame {@code ACT [blk, pc, dst, 0, capRun, ls, rbase]} after a suspension - the stepper has then already
 * stored the awaited result into the window word the frame named ({@code Code.resume}), so the window is
 * the complete state of the activation and a tier reloads whatever it keeps in locals from it. What it
 * must return: the block's result with the window popped ({@link #leave}), or a signal ({@code >= Op.SIG})
 * exactly as an operation returned it, at once, without touching the machine any further (SUSP: its frame
 * was written by {@link #suspend} or by the instruction body called through {@link #step}; ABORT / FATAL:
 * the worklist was cut, ISA 8.4 "cut of W").
 *
 * <p>Three levels of taking over, all with identical results:
 * <ol>
 *   <li>{@link #interpret}: the stepper itself from {@code pc} (the fallback that is always there);
 *   <li>{@link #step}: one instruction through the stepper's own instruction body (call threading: dispatch,
 *       pc and operand words are compile-time constants of the caller);
 *   <li>own code per instruction over {@link #reg} / {@link #setReg} / {@link #operand} and the operations
 *       of the machine, with {@link #suspend} for the H3 protocol of a class-j instruction and
 *       {@link #leave} for RET. A tier that ships a bridge class in package {@code rlvm.machine} reaches
 *       the instruction bodies' helpers directly: {@code Code.val}, {@code raw}, {@code isNat},
 *       {@code ctorIndex}, {@code icGet}, {@code susp} are package-private and their signatures are part
 *       of this hook (M7 announces a change in requests.log before publishing it).
 * </ol>
 *
 * <h2>Heat (ISA 8.3, 8.5)</h2>
 * Independent of a tier: {@code Tier.heat(engine)} switches the accounting on for the sessions of a machine
 * engine (from their next declaration). Per session and block: {@code entries} (every activation entered),
 * and a sampled time: one entry in {@code timing} (default 64) is timed with {@code System.nanoTime} from
 * its start to its return or suspension, as are the blocks entered inside it, so that the time of a block
 * splits into {@code nanos} (inclusive) and {@code selfNanos} (without the blocks it entered; the generic
 * judgments it called stay inside). The readers of {@link Heat} sum the sessions with racy reads;
 * {@code spent(b) = entries(b) * selfNanos(b) / timed(b)} is the estimate of ISA 8.5. The counters are
 * per session: no shared word is written on a block entry.
 */
public abstract class Tier {

    /** Makes the tier of a machine; registered as a service ({@code META-INF/services/rlvm.machine.Tier$Factory}). */
    public interface Factory {
        /**
         * The tier of machine {@code m}, or null (the tier is switched off, the optimising runtime is missing,
         * ...). Called once per machine that runs with switch {@code t2}, before its first activation.
         */
        Tier create(Machine m);
    }

    private static final class Holder {
        static final Factory FACTORY = load();

        private static Factory load() {
            try {
                for (Factory f : ServiceLoader.load(Factory.class)) {
                    return f;
                }
            } catch (Throwable t) {
                // fail-safe: a broken tier module must not take the checker down (ISA 8.6)
            }
            return null;
        }
    }

    /** The factory found on the class path, or null. */
    public static Factory factory() {
        return Holder.FACTORY;
    }

    protected Tier() {}

    /**
     * Entry or resume of an activation: block {@code blk} from {@code pc} on the window at {@code rb}
     * (header and arguments in place), host depth {@code d} (pass {@code d + 1} to judgments).
     *
     * @return the result or a signal, exactly as {@code Code.resumeAt} (register stack popped on a result;
     *     the frame {@code ACT [blk, pc, dst, 0, capRun, ls, rbase]} written on a suspension, with every
     *     register the rest of the block reads in the window), or {@link Op#MISS}: not taken, nothing changed
     */
    public abstract int run(Machine m, int blk, int pc, int rb, int d);

    /** The tier of machine {@code m}, or null. */
    public static Tier of(Machine m) {
        return m.x7.tier;
    }

    /**
     * Gives machine {@code m} a tier directly (tests, an engine wrapper that does not use the service
     * loader); null takes it away. Call it between declarations, on the thread that owns the machine.
     */
    public static void set(Machine m, Tier t) {
        Code.Ext x = m.x7;
        x.tier = t;
        x.tierAsked = true;
        x.hooked = t != null || x.heat != null;
    }

    // ---------------------------------------------------------------------------------------------------
    // Blocks (read-only; any thread)
    // ---------------------------------------------------------------------------------------------------

    /** Number of blocks the engine of {@code m} has published so far; block ids are {@code 1 .. blockCount}. */
    public static int blockCount(Machine m) {
        return m.engine == null ? 0 : m.engine.code.blockCount();
    }

    /**
     * A copy of the words of block {@code blk} (immutable once published; layout ISA 3.3 and NOTES-M7
     * section 2: header, entry table, instructions), null if there is no such block.
     */
    public static int[] block(Machine m, int blk) {
        return m.engine == null ? null : copy(m.engine.code, blk);
    }

    private static int[] copy(CodeStore cs, int blk) {
        int a = cs.address(blk);
        if (a == 0) {
            return null;
        }
        int[] c = cs.chunkAt(a);
        int o = CodeStore.offsetAt(a);
        return Arrays.copyOfRange(c, o, o + Code.len(c[o + Op.BLK_HDR0]));
    }

    /** One line about a block for reports: id, role, constant (or "sub-block of term t"), arity, registers, words. */
    public static String describe(Machine m, int blk) {
        return m.engine == null ? "block " + blk + ": no engine" : describe(m.engine.code, blk);
    }

    private static String describe(CodeStore cs, int blk) {
        int a = cs.address(blk);
        if (a == 0) {
            return "block " + blk + ": none";
        }
        int[] c = cs.chunkAt(a);
        int o = CodeStore.offsetAt(a);
        int h0 = c[o + Op.BLK_HDR0];
        int h2 = c[o + Op.BLK_HDR2];
        int row = c[o + Op.BLK_ROW];
        String[] roles = {"?", "TELE", "BODY", "RULE", "FIELDS", "CONG", "RULES"};
        String who = row > 0 ? cs.store().names().toString(cs.store().decls().name(row)) : "sub-block of term " + c[o + Op.BLK_FACTS];
        return "block " + blk + " " + roles[Code.role(h0) < roles.length ? Code.role(h0) : 0] + "(" + who + ") arity " + Code.arity(h2)
                + " nreg " + Code.nreg(h0) + " words " + Code.len(h0) + ((Code.flags(h2) & Op.BLKF_LEAF) != 0 ? " LEAF" : "");
    }

    // ---------------------------------------------------------------------------------------------------
    // What a tier may call from run (on the worker thread that owns m)
    // ---------------------------------------------------------------------------------------------------

    /** {@link #step}: the activation ended (returned, suspended or failed); {@link #result} is what {@code run} returns. */
    public static final int DONE = -1;

    /**
     * The stepper itself: runs the activation at window {@code rb} from {@code pc} to its return or suspension.
     * {@code code} is the block as {@link #block} returned it.
     *
     * @return the result, or a signal
     */
    public static int interpret(Machine m, int[] code, int blk, int pc, int rb, int d) {
        return Code.resumeAt(m, code, 0, blk, pc, rb, d);
    }

    /**
     * Executes the one instruction at {@code pc} through the stepper's instruction body.
     *
     * @return the pc of the next instruction, or {@link #DONE}: the activation returned, was suspended or failed,
     *     and {@link #result} is what {@link #run} must return
     */
    public static int step(Machine m, int[] code, int blk, int pc, int rb, int d) {
        return Code.resumeOne(m, code, 0, blk, pc, rb, d);
    }

    /** After {@link #step} returned {@link #DONE}: the result or signal of the activation. */
    public static int result(Machine m) {
        return m.x7.tierResult;
    }

    /** True for SUSP, ABORT, FATAL: return it at once. */
    public static boolean isSignal(int r) {
        return r >= Op.SIG;
    }

    /** Window word {@code i} of the activation at {@code rb}: an argument ({@code i < arity}) or a register. */
    public static int reg(Machine m, int rb, int i) {
        return m.RS[rb + i];
    }

    public static void setReg(Machine m, int rb, int i, int v) {
        m.RS[rb + i] = v;
    }

    /**
     * The value of operand word {@code o} of an instruction ({@code mode:4 | payload:28}, ISA 3.3) as the
     * stepper reads it ({@code Code.val}: PEEK of a variable, HOLE faults), or a signal.
     */
    public static int operand(Machine m, int rb, int o) {
        return Code.val(m, rb, o);
    }

    /** The captured frame run of the activation (0 for BODY / RULE / TELE / RULES blocks). */
    public static int cap(Machine m, int rb) {
        return m.RS[rb - Code.W_CAP];
    }

    /** The level substitution of the activation (ISA 2.7). */
    public static int ls(Machine m, int rb) {
        return m.RS[rb - Code.W_LS];
    }

    /**
     * The H3 protocol of a class-j instruction whose judgment returned signal {@code sig}: writes the frame
     * {@code ACT [blk, pc, dst, ..]} of this activation ({@code pc}: where to continue; {@code dst}: the window
     * index that receives the awaited result, or one of {@code Code.DST_*}) and returns the signal to return.
     * The registers must be in the window before the call.
     */
    public static int suspend(Machine m, int sig, int blk, int pc, int dst, int rb) {
        return Code.susp(m, sig, blk, pc, dst, rb);
    }

    /** RET: pops the window of the activation and returns {@code v}, the result of the block. */
    public static int leave(Machine m, int rb, int v) {
        if (v < Op.SIG) {
            m.rp = rb - Code.HDR;
        }
        return v;
    }

    // ---------------------------------------------------------------------------------------------------
    // Heat
    // ---------------------------------------------------------------------------------------------------

    /** Receives the promotion trigger of ISA 8.3: called on the worker thread, every {@code period} entries of a block by that worker. */
    public interface Listener {
        void hot(Heat heat, Machine m, int blk);
    }

    /**
     * Switches heat accounting on for the sessions of a machine engine (from their next declaration) and
     * returns its readers; the same object at every call. Null for an engine that is not the machine and
     * for a strict engine.
     */
    public static Heat heat(Engine engine) {
        if (!(engine instanceof MachineEngine)) {
            return null;
        }
        MachineEngine e = (MachineEngine) engine;
        return e.config.strict ? null : e.code.heat(true);
    }

    /** The counters of one session; read by other threads without synchronisation (statistics). */
    static final class Counters {
        volatile int[] entries = new int[256];
        volatile long[] nanos = new long[256];
        volatile long[] self = new long[256];
        volatile int[] timed = new int[256];

        void grow(int blk) {
            int n = Math.max(entries.length << 1, blk + 1);
            entries = Arrays.copyOf(entries, n);
            nanos = Arrays.copyOf(nanos, n);
            self = Arrays.copyOf(self, n);
            timed = Arrays.copyOf(timed, n);
        }
    }

    /** The heat of the blocks of one engine: racy sums over its sessions (ISA 8.3). */
    public static final class Heat {
        private final CodeStore cs;
        private final ArrayList<Counters> sessions = new ArrayList<>();
        volatile Listener listener;
        volatile int periodMask = 4095;
        volatile int timingMask = 63;

        Heat(CodeStore cs) {
            this.cs = cs;
        }

        Counters register() {
            Counters c = new Counters();
            synchronized (sessions) {
                sessions.add(c);
            }
            return c;
        }

        /** The heat listener and its period in entries per worker and block (rounded up to a power of two; ISA 8.3: 4096). */
        public void listen(Listener l, int period) {
            int p = Integer.highestOneBit(Math.max(1, period));
            periodMask = (p < period ? p << 1 : p) - 1;
            listener = l;
        }

        /** Time one block entry in {@code every} (rounded up to a power of two); 0 switches timing off. Default 64. */
        public void timing(int every) {
            if (every <= 0) {
                timingMask = -1;
                return;
            }
            int p = Integer.highestOneBit(every);
            timingMask = (p < every ? p << 1 : p) - 1;
        }

        /** Number of blocks published so far; block ids are {@code 1 .. blockCount()}. */
        public int blockCount() {
            return cs.blockCount();
        }

        public String describe(int blk) {
            return Tier.describe(cs, blk);
        }

        /** A copy of the words of block {@code blk}, null if there is none. */
        public int[] block(int blk) {
            return copy(cs, blk);
        }

        /** Entries of block {@code blk} by all sessions since the accounting was switched on. */
        public long entries(int blk) {
            long s = 0;
            synchronized (sessions) {
                for (Counters h : sessions) {
                    int[] e = h.entries;
                    s += blk >= 0 && blk < e.length ? e[blk] : 0;
                }
            }
            return s;
        }

        /** Nanoseconds of the timed entries of {@code blk}, inclusive of everything they called. */
        public long nanos(int blk) {
            long s = 0;
            synchronized (sessions) {
                for (Counters h : sessions) {
                    long[] e = h.nanos;
                    s += blk >= 0 && blk < e.length ? e[blk] : 0;
                }
            }
            return s;
        }

        /** As {@link #nanos} without the time of the blocks entered inside (generic judgments stay inside). */
        public long selfNanos(int blk) {
            long s = 0;
            synchronized (sessions) {
                for (Counters h : sessions) {
                    long[] e = h.self;
                    s += blk >= 0 && blk < e.length ? e[blk] : 0;
                }
            }
            return s;
        }

        /** Number of timed entries (and timed resumed parts) of {@code blk}. */
        public long timed(int blk) {
            long s = 0;
            synchronized (sessions) {
                for (Counters h : sessions) {
                    int[] e = h.timed;
                    s += blk >= 0 && blk < e.length ? e[blk] : 0;
                }
            }
            return s;
        }

        /**
         * The estimate of ISA 8.5: {@code spent(b) = entries(b) * unit(b)}, {@code unit(b)} = the mean time of a
         * timed entry without the blocks it entered, in nanoseconds; 0 while nothing of the block was timed.
         */
        public long spentNanos(int blk) {
            long n = timed(blk);
            return n == 0 ? 0 : (long) ((double) entries(blk) * selfNanos(blk) / n);
        }

        /** The {@code top} blocks by {@link #spentNanos}, one line each (entries, estimated time, mean per entry, description). */
        public String report(int top) {
            int n = blockCount();
            long[] key = new long[n + 1];
            long[] ent = new long[n + 1];
            Integer[] ids = new Integer[n];
            long total = 0;
            long totalEntries = 0;
            int used = 0;
            for (int b = 1; b <= n; b++) {
                key[b] = spentNanos(b);
                ent[b] = entries(b);
                total += key[b];
                totalEntries += ent[b];
                used += ent[b] != 0 ? 1 : 0;
                ids[b - 1] = b;
            }
            Arrays.sort(ids, (x, y) -> key[y] != key[x] ? Long.compare(key[y], key[x]) : Long.compare(ent[y], ent[x]));
            StringBuilder sb = new StringBuilder();
            sb.append("blocks ").append(n).append(" (entered: ").append(used).append("), code ").append(cs.bytes() >> 10)
                    .append(" KiB, entries ").append(totalEntries).append(", estimated self time ").append(total / 1_000_000)
                    .append(" ms (sampled)\n");
            long cum = 0;
            for (int i = 0; i < n && i < top; i++) {
                int b = ids[i];
                if (ent[b] == 0) {
                    break;
                }
                long t = timed(b);
                cum += ent[b];
                sb.append(String.format("%10d entries (cum %5.1f%%) %9.2f ms self %7.0f ns/entry (incl. %7.0f)  %s%n", ent[b],
                        100.0 * cum / Math.max(1, totalEntries), key[b] / 1e6, t == 0 ? 0.0 : (double) selfNanos(b) / t,
                        t == 0 ? 0.0 : (double) nanos(b) / t, describe(b)));
            }
            return sb.toString();
        }
    }

    // ---------------------------------------------------------------------------------------------------
    // The stepper's side (package-private): the path of an activation of a machine with a tier or with heat
    // ---------------------------------------------------------------------------------------------------

    /** Start of a declaration ({@code Code.Ext.beginDecl}): the machine's tier (asked for once) and the engine's heat accounting. */
    static void beginDecl(Machine m, Code.Ext x) {
        if (m.engine == null || m.strict) {
            return;
        }
        if (!x.tierAsked) {
            x.tierAsked = true;
            Factory f = m.on(Op.SW_T2) ? factory() : null;
            if (f != null) {
                x.tier = f.create(m);
            }
        }
        Heat h = m.engine.code.heat(false);
        if (h != x.heatOf) {
            x.heatOf = h;
            x.heat = h == null ? null : h.register();
            x.timing = 0;
            x.childNanos = 0;
        }
        x.hooked = x.tier != null || x.heat != null;
    }

    /** Counts an entry of {@code blk} by the session of {@code x} and fires the listener every period entries. */
    static void count(Machine m, Code.Ext x, int blk) {
        Counters h = x.heat;
        if (h == null) {
            return;
        }
        if (blk >= h.entries.length) {
            h.grow(blk);
        }
        int n = ++h.entries[blk];
        Heat heat = x.heatOf;
        if ((n & heat.periodMask) == 0) {
            Listener l = heat.listener;
            if (l != null) {
                l.hot(heat, m, blk);
            }
        }
    }

    /** The activation from {@code pc}: offered to the machine's tier, else the stepper. */
    private static int offer(Machine m, Tier t, int[] code, int base, int blk, int pc, int rb, int d) {
        if (t != null) {
            int r = t.run(m, blk, pc, rb, d);
            if (r != Op.MISS) {
                return r;
            }
        }
        return Code.resumeAt(m, code, base, blk, pc, rb, d);
    }

    /**
     * Entry ({@code Code.go}) or resume ({@code Code.run}) of an activation of a machine with a tier or heat
     * accounting: timed if this entry is a sample or lies inside a timed one.
     */
    static int exec(Machine m, int[] code, int base, int blk, int pc, int rb, int d) {
        Code.Ext x = m.x7;
        Counters h = x.heat;
        if (h == null) {
            return offer(m, x.tier, code, base, blk, pc, rb, d);
        }
        int tm = x.heatOf.timingMask;
        if (x.timing == 0 && (tm < 0 || (++x.sample & tm) != 0)) {
            return offer(m, x.tier, code, base, blk, pc, rb, d);
        }
        long outer = x.childNanos;
        x.childNanos = 0;
        x.timing++;
        long t0 = System.nanoTime();
        int r = offer(m, x.tier, code, base, blk, pc, rb, d);
        long dt = System.nanoTime() - t0;
        x.timing--;
        if (blk >= h.entries.length) {
            h.grow(blk);
        }
        h.nanos[blk] += dt;
        h.self[blk] += dt - x.childNanos;
        h.timed[blk]++;
        x.childNanos = outer + dt;
        return r;
    }
}

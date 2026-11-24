package rlvm.api.bind;

import java.math.BigInteger;

import rlvm.api.KernelException;
import rlvm.api.NatOps;
import rlvm.api.Reason;
import rlvm.api.ResourceException;
import rlvm.nat.Nat;
import rlvm.nat.NatStore;

/**
 * {@link NatOps} over {@code rlvm-nat}: the Nat-literal operations of the official kernel (v4.36)
 * on NATLIT term ids, and string-literal access. One instance per worker; not thread-safe.
 *
 * <h2>Where numbers live</h2>
 * A literal below 2^64 is a word literal of the term store. Every larger number - an export literal
 * or a computed one - lives in the literal pools of the store ({@link LitAccess}); the result of an
 * operation is interned there by content and gets its NATLIT node in the worker's overlay. This
 * worker's {@code NatStore} is only where the arithmetic runs: a <em>cache</em> of pool contents
 * (operands are imported on first use, results stay as long as the cache does) that is emptied
 * whenever it holds more than {@link #setCacheLimitBytes} (default 32 MiB) and at every
 * {@link #reset()}. No term refers to a {@code NatStore} id, so emptying the cache is always safe.
 *
 * <h2>Identity</h2>
 * Equal pool references are equal numbers. {@link #natEquals} never answers "different" from ids
 * alone: word against word compares values, two big literals with different references are compared
 * by content, and a word against a big literal is different by representation (a big literal is 2^64
 * or more) unless the pool entry turns out to be a non-canonical small one, which is compared by value.
 *
 * <h2>Lifetime</h2>
 * {@link #reset()} empties the cache; it must be called whenever the worker's literal pool is reset
 * (as {@code WorkerContext.reset()} does), because cache entries are keyed by pool references, which
 * the pool reissues.
 *
 * <p>The same holds for a <em>truncation</em> of the pool ({@code LitPool.truncateTo}, which
 * {@code Overlay.resetTo} may perform): depending on the store's release it retires the released
 * references or reissues them for other numbers. The binding protects itself: before it consults or
 * fills the cache it compares the pool's entry count with the count after its own last addition
 * ({@link LitAccess#workerNatCount()}) and empties the cache when they differ. That is exact as long
 * as this binding is the only writer of the worker's pool between two of its calls, which is the case
 * for every caller that builds literals through {@code NatOps} (the reference kernel, admission). Code
 * that adds entries to a worker pool itself <em>and</em> truncates it must call {@link #reset()} after
 * the truncation. With assertions enabled (every test run) each cache hit is also compared with the
 * pool's content, limb by limb, so that a violation of this rule fails a test instead of computing
 * with a stale number.
 *
 * <h2>Errors</h2>
 * Where the official kernel throws ({@code check_nat_size}, {@code get_count_arg}, the pow guard):
 * {@link KernelException} with {@link Reason#NAT_LITERAL_TOO_BIG}. Where this worker's own limit ends
 * ({@link #setWorkLimitBytes}): {@link ResourceException}, a decline. The official guards are always
 * evaluated first: a rejection is never produced by an own limit, and what the official kernel
 * rejects is rejected whatever the own limit is.
 */
public final class NatBinding implements NatOps {

    private static final int IMPORT_CACHE = 256;

    private final LitAccess acc;
    private final NatStore nats;
    /** Limit of the official guards (LEAN_NAT_MAX_SIZE; 128 MiB). */
    private final long officialLimit;
    /** Own limit on the estimated size of one result; operations above it decline. */
    private long workLimit = Long.MAX_VALUE;
    /** The NatStore is emptied when it holds more than this many bytes of limbs. */
    private long cacheLimit = 32L << 20;

    private final int[] impKey = new int[IMPORT_CACHE];
    private final long[] impVal = new long[IMPORT_CACHE];
    private final int[] impStamp = new int[IMPORT_CACHE];
    private int epoch = 1;
    private long[] limbScratch = new long[8];
    private long imports;
    /** {@link LitAccess#workerNatCount()} as of this binding's last look at the worker's pool. */
    private int poolCount;

    /**
     * @param access the bridge to the worker's literal terms and pools
     * @param nats a writable {@code NatStore} view owned by this worker (an overlay of the run's
     *     frozen, empty base; its size limit is the official one unless created otherwise)
     */
    public NatBinding(LitAccess access, NatStore nats) {
        if (access == null || nats == null) {
            throw new NullPointerException();
        }
        if (nats.isFrozen()) {
            throw new IllegalArgumentException("NatBinding needs a writable NatStore (an overlay)");
        }
        this.acc = access;
        this.nats = nats;
        this.officialLimit = Math.min(nats.maxBytes(), Nat.OFFICIAL_MAX_BYTES);
        this.poolCount = access.workerNatCount();
    }

    /**
     * Empties the cache if the worker's pool changed since this binding last looked at it (see
     * "Lifetime" in the class comment). Must not be called while a handle of the cache is in use.
     *
     * @return true if the cache was emptied
     */
    private boolean syncWithPool() {
        int c = acc.workerNatCount();
        if (c == poolCount) {
            return false;
        }
        poolCount = c;
        dropCache();
        return true;
    }

    /** Assertion helper: the cached number of {@code ref} is what the pool holds under {@code ref}. */
    private boolean cacheAgreesWithPool(int ref, long handle) {
        int n = acc.bigLimbCount(ref);
        long[] limbs = new long[Math.max(1, n)];
        acc.bigLimbs(ref, limbs, 0);
        while (n > 1 && limbs[n - 1] == 0) {
            n--;
        }
        if (handle >= 0) {
            return n == 1 && limbs[0] == handle;
        }
        int id = Nat.bigId(handle);
        if (nats.limbCount(id) != n) {
            return false;
        }
        for (int i = 0; i < n; i++) {
            if (nats.limb(id, i) != limbs[i]) {
                return false;
            }
        }
        return true;
    }

    /** The number store of this worker (its content is a cache; see the class comment). */
    public NatStore store() {
        return nats;
    }

    /**
     * Declines ({@link Reason#UNSUPPORTED_INPUT}) operations whose result is estimated above
     * {@code bytes}, after the official guards have passed. Default (and {@code bytes <= 0}): no own
     * limit.
     */
    public void setWorkLimitBytes(long bytes) {
        workLimit = bytes <= 0 ? Long.MAX_VALUE : bytes;
    }

    /**
     * Bound on the limbs kept in this worker's {@code NatStore} between operations. It is enforced
     * after an operation has delivered its result, so one operation's operands and result always fit.
     */
    public void setCacheLimitBytes(long bytes) {
        cacheLimit = Math.max(0, bytes);
    }

    /** Number of pool entries imported into the cache so far (statistics, tests). */
    public long imports() {
        return imports;
    }

    // ---- literal <-> handle ----------------------------------------------------------------------------

    /**
     * The {@code rlvm.nat} handle of NATLIT term {@code lit} in this worker's store. Valid until the
     * next call that returns a term (which may empty the cache) or {@link #reset()}.
     */
    public long handleOf(int lit) {
        if (acc.natIsWord(lit)) {
            long w = acc.natWord(lit);
            return w >= 0 ? w : Nat.ofUnsignedLong(nats, w);
        }
        int ref = acc.natRef(lit);
        if (ref == 0) {
            throw new IllegalStateException("NATLIT " + lit + " has no value reference");
        }
        if (ref < 0) {
            // A reference into the worker's own pool: only valid while the pool is as this binding left it.
            syncWithPool();
        }
        int slot = (ref * 0x9E3779B1) >>> 24;
        if (impKey[slot] == ref && impStamp[slot] == epoch) {
            assert cacheAgreesWithPool(ref, impVal[slot]) : "stale number cache: pool reference " + ref
                    + " names another number than when it was cached (the pool was truncated or reset without "
                    + "NatOps.reset())";
            return impVal[slot];
        }
        long h = importPool(ref);
        remember(ref, h);
        return h;
    }

    private void remember(int ref, long handle) {
        int slot = (ref * 0x9E3779B1) >>> 24;
        impKey[slot] = ref;
        impVal[slot] = handle;
        impStamp[slot] = epoch;
    }

    private long importPool(int ref) {
        int n = acc.bigLimbCount(ref);
        if (n <= 0) {
            throw new IllegalStateException("literal pool entry " + ref + " has no limbs");
        }
        imports++;
        if (limbScratch.length < n) {
            limbScratch = new long[Math.max(n, limbScratch.length * 2)];
        }
        long[] limbs = limbScratch;
        acc.bigLimbs(ref, limbs, 0);
        while (n > 1 && limbs[n - 1] == 0) {
            n--;
        }
        if (n == 1) {
            return Nat.ofUnsignedLong(nats, limbs[0]);
        }
        // rlvm-nat has no raw-limb import yet (requested): go through a big-endian magnitude.
        byte[] mag = new byte[n * 8];
        for (int i = 0; i < n; i++) {
            long w = limbs[i];
            int base = (n - 1 - i) * 8;
            for (int k = 7; k >= 0; k--) {
                mag[base + k] = (byte) w;
                w >>>= 8;
            }
        }
        long h = Nat.ofBigInteger(nats, new BigInteger(1, mag));
        if (Nat.isError(h)) {
            // Larger than the store can hold: larger than the official limit as well.
            throw new KernelException(Reason.NAT_LITERAL_TOO_BIG);
        }
        return h;
    }

    /**
     * The NATLIT term (in the worker's overlay) of a number handle of this worker's store. A big
     * number is interned in the literal pool; afterwards the cache is emptied if it is over its
     * limit, so no handle may be used after this call.
     */
    public int termOf(long handle) {
        if (handle >= 0) {
            return acc.mkNatWord(handle);
        }
        if (Nat.isError(handle)) {
            throw new IllegalArgumentException("not a number: " + Nat.describe(nats, handle));
        }
        int id = Nat.bigId(handle);
        int n = nats.limbCount(id);
        if (n == 1) {
            return acc.mkNatWord(nats.limb(id, 0));
        }
        if (limbScratch.length < n) {
            limbScratch = new long[Math.max(n, limbScratch.length * 2)];
        }
        long[] limbs = limbScratch;
        for (int i = 0; i < n; i++) {
            limbs[i] = nats.limb(id, i);
        }
        // The limbs are in the scratch array now: the handle is not needed for the pool entry, so the
        // cache may be emptied here if the pool was truncated since this binding last looked.
        boolean emptied = syncWithPool();
        int term = acc.mkNatBig(limbs, 0, n);
        poolCount = acc.workerNatCount();
        if (emptied) {
            // The handle died with the cache; the next use of the term imports it from the pool.
            return term;
        }
        if (nats.bytesUsed() > cacheLimit) {
            dropCache();
        } else {
            remember(acc.natRef(term), handle);
        }
        return term;
    }

    private void dropCache() {
        nats.reset();
        if (++epoch == Integer.MAX_VALUE) {
            java.util.Arrays.fill(impStamp, 0);
            epoch = 1;
        }
    }

    // ---- NatOps: arithmetic ----------------------------------------------------------------------------

    @Override
    public int binary(int op, int litA, int litB) {
        long a = handleOf(litA);
        long b = handleOf(litB);
        long r;
        switch (op) {
            case ADD:
                declineAbove(Math.max(size(a), size(b)) + 8);
                r = Nat.add(nats, a, b);
                break;
            case SUB:
                r = Nat.sub(nats, a, b);
                break;
            case MUL:
                if (a != 0 && b != 0) {
                    declineAbove(size(a) + size(b));
                }
                r = Nat.mul(nats, a, b);
                break;
            case POW: {
                long g = Nat.powGuard(nats, a, b, officialLimit);
                if (g != Nat.OK) {
                    throw tooBig(litA, litB);
                }
                if (workLimit != Long.MAX_VALUE && Nat.powGuard(nats, a, b, workLimit) != Nat.OK) {
                    throw new ResourceException(Reason.UNSUPPORTED_INPUT);
                }
                r = Nat.pow(nats, a, b, officialLimit);
                break;
            }
            case GCD:
                r = Nat.gcd(nats, a, b);
                break;
            case MOD:
                r = Nat.mod(nats, a, b);
                break;
            case DIV:
                r = Nat.div(nats, a, b);
                break;
            case LAND:
                r = Nat.land(nats, a, b);
                break;
            case LOR:
                r = Nat.lor(nats, a, b);
                break;
            case XOR:
                r = Nat.xor(nats, a, b);
                break;
            case SHIFT_LEFT: {
                long g = Nat.shiftLeftGuard(nats, a, b, officialLimit);
                if (g != Nat.OK) {
                    throw tooBig(litA, litB);
                }
                if (workLimit != Long.MAX_VALUE && Nat.shiftLeftGuard(nats, a, b, workLimit) != Nat.OK) {
                    throw new ResourceException(Reason.UNSUPPORTED_INPUT);
                }
                r = Nat.shiftLeft(nats, a, b, officialLimit);
                break;
            }
            case SHIFT_RIGHT:
                r = Nat.shiftRight(nats, a, b);
                break;
            case BEQ:
            case BLE:
                throw new IllegalArgumentException("Nat.beq / Nat.ble are predicates: use test()");
            default:
                throw new IllegalArgumentException("not a Nat operation code: " + op);
        }
        if (r < 0 && Nat.isError(r)) {
            if (r == Nat.TOO_BIG || r == Nat.COUNT_TOO_BIG) {
                throw tooBig(litA, litB);
            }
            throw new IllegalStateException("rlvm-nat returned " + Nat.describe(nats, r) + " for operation " + op);
        }
        return termOf(r);
    }

    private static KernelException tooBig(int litA, int litB) {
        return new KernelException(Reason.NAT_LITERAL_TOO_BIG, litA, litB);
    }

    private long size(long h) {
        return h >= 0 ? 8 : Nat.sizeInBytes(nats, h);
    }

    private void declineAbove(long estimatedBytes) {
        if (estimatedBytes > workLimit && estimatedBytes <= officialLimit) {
            throw new ResourceException(Reason.UNSUPPORTED_INPUT);
        }
    }

    @Override
    public boolean test(int op, int litA, int litB) {
        long a = handleOf(litA);
        long b = handleOf(litB);
        switch (op) {
            case BEQ:
                return a == b; // handles are canonical within one store view
            case BLE:
                return Nat.ble(nats, a, b);
            default:
                throw new IllegalArgumentException("not a Nat predicate code: " + op);
        }
    }

    @Override
    public int succ(int lit) {
        long r = Nat.succ(nats, handleOf(lit));
        if (Nat.isError(r)) {
            throw new KernelException(Reason.NAT_LITERAL_TOO_BIG, lit, 0);
        }
        return termOf(r);
    }

    @Override
    public int pred(int lit) {
        long h = handleOf(lit);
        if (h == 0) {
            throw new IllegalArgumentException("pred of the literal 0");
        }
        return termOf(Nat.pred(nats, h));
    }

    @Override
    public boolean isZero(int lit) {
        return acc.natIsWord(lit) && acc.natWord(lit) == 0;
    }

    @Override
    public boolean natEquals(int litA, int litB) {
        if (litA == litB) {
            return true;
        }
        boolean wa = acc.natIsWord(litA);
        boolean wb = acc.natIsWord(litB);
        if (wa && wb) {
            return acc.natWord(litA) == acc.natWord(litB);
        }
        if (wa != wb) {
            // A big literal is 2^64 or more by construction, so it differs from every word literal;
            // a single-limb pool entry (a writer that did not canonicalise) is compared by value.
            int ref = acc.natRef(wa ? litB : litA);
            return acc.bigLimbCount(ref) == 1 && handleOf(litA) == handleOf(litB);
        }
        int ra = acc.natRef(litA);
        int rb = acc.natRef(litB);
        return ra == rb || acc.bigEquals(ra, rb);
    }

    @Override
    public void checkLiteralSize(int lit) {
        if (acc.natIsWord(lit)) {
            return;
        }
        if (8L * acc.bigLimbCount(acc.natRef(lit)) > officialLimit) {
            throw new KernelException(Reason.NAT_LITERAL_TOO_BIG, lit, 0);
        }
    }

    @Override
    public int literal(long unsignedValue) {
        return acc.mkNatWord(unsignedValue);
    }

    @Override
    public boolean isSmall(int lit) {
        return acc.natIsWord(lit) && acc.natWord(lit) >= 0;
    }

    @Override
    public long smallValue(int lit) {
        return acc.natWord(lit);
    }

    @Override
    public String natToString(int lit) {
        long h = handleOf(lit);
        return h >= 0 ? Long.toString(h) : Nat.describe(nats, h);
    }

    // ---- NatOps: strings -------------------------------------------------------------------------------

    @Override
    public int stringLength(int strLit) {
        return acc.strLength(strLit);
    }

    @Override
    public int stringCodePoint(int strLit, int index) {
        return acc.strCodePoint(strLit, index);
    }

    @Override
    public boolean stringEquals(int strLitA, int strLitB) {
        return strLitA == strLitB || acc.strEquals(strLitA, strLitB);
    }

    // ---- lifetime ----------------------------------------------------------------------------------------

    /** Empties the number cache. The literal pool and the term overlay are reset by their owner ({@code WorkerContext}). */
    @Override
    public void reset() {
        dropCache();
        poolCount = acc.workerNatCount();
    }

    /** Bytes of limbs held by the cache (the numbers themselves are accounted for by the literal pool). */
    @Override
    public long footprintBytes() {
        return nats.bytesUsed();
    }
}

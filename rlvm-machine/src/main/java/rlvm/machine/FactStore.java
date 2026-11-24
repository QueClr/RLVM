package rlvm.machine;

import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;
import rlvm.store.Store;

/**
 * The engine-wide fact table (ISA 3.5, 12 R6): one immutable record per constant in shared
 * {@code int[]} chunks, published by a single release store of its index into a per-row slot, read with
 * acquire semantics; index 0 = no facts. Obeys the publication rule (ISA 3.3, RECONCILIATION B2).
 * Owner: M5. Staging step 2; in v0 no record is ever published and every reader sees 0.
 *
 * <h2>Layout</h2>
 * <pre>
 * data     chunks of 2^16 ints behind a directory; a record lies inside one chunk:
 *              [length] f0 f1 .. f(length-1)          index = global word offset of f0 (never 0)
 * slots    chunks of 2^12 ints behind a directory, one int per constant row: the index of its record, 0 = none
 * </pre>
 * Both directories are {@link AtomicReferenceArray}s whose chunks are installed by compare-and-set, so the
 * table grows while the loader still adds constants. A record is written completely into words nobody
 * can reach, then published by ONE compare-and-set of its row's slot (a volatile write, the "release
 * store" of ISA 3.3); a reader loads the slot (volatile read, "acquire") and from then on reads plain,
 * immutable words. If two workers build the record of one row, the first compare-and-set wins and the
 * loser's words stay unreachable; the contents are equal, because a record is a function of the store
 * (only "the derivation ran out of budget", F_NONE, can differ between sessions).
 *
 * <p>Thread-safe; no lock on any path. The {@code java.util.concurrent.atomic} classes are the only
 * objects of this table besides the arrays.
 */
final class FactStore {
    private static final int CHUNK_LOG2 = 16;
    private static final int CHUNK_WORDS = 1 << CHUNK_LOG2;
    private static final int CHUNK_MASK = CHUNK_WORDS - 1;
    /** The cap of ISA 1.6 on shared facts (0.4 GB of code and facts): 1,024 chunks = 256 MiB of facts. */
    private static final int MAX_CHUNKS = 1024;
    private static final int SLOT_LOG2 = 12;
    private static final int SLOT_WORDS = 1 << SLOT_LOG2;
    private static final int SLOT_MASK = SLOT_WORDS - 1;
    private static final int MAX_SLOT_CHUNKS = 1 << (28 - SLOT_LOG2);
    /** Longest record (length word excluded): header, 64 positions, a level pool. */
    static final int MAX_RECORD = 4096;

    private final Store store;
    private final AtomicReferenceArray<int[]> chunks = new AtomicReferenceArray<>(MAX_CHUNKS);
    private final AtomicReferenceArray<AtomicIntegerArray> slots = new AtomicReferenceArray<>(MAX_SLOT_CHUNKS);
    /** Next free global word offset; word 0 is never used, so that index 0 means "none". */
    private final AtomicLong top = new AtomicLong(1);
    /** Statistics (racy reads are fine): records published, publications that lost the race, of which with other words. */
    private final AtomicLong published = new AtomicLong();
    private final AtomicLong lost = new AtomicLong();
    private final AtomicLong lostDifferent = new AtomicLong();
    private final AtomicLong none = new AtomicLong();

    FactStore(Store store) {
        this.store = store;
    }

    /** The published record index of a constant, 0 = none. Safe from any thread. */
    int indexOf(int row) {
        if (row <= 0) {
            return 0;
        }
        AtomicIntegerArray s = slots.get(row >>> SLOT_LOG2);
        return s == null ? 0 : s.get(row & SLOT_MASK);
    }

    /** The chunk that holds record {@code index}; its words are at {@link #offsetOf}. */
    int[] chunkOf(int index) {
        return chunks.get(index >>> CHUNK_LOG2);
    }

    int offsetOf(int index) {
        return index & CHUNK_MASK;
    }

    /** Number of words of record {@code index} (f0 .. ; the word before f0). */
    int lengthOf(int index) {
        return chunkOf(index)[offsetOf(index) - 1];
    }

    /**
     * Copies a complete record into the shared chunks and publishes it for {@code row} with one release
     * store. Two workers may publish equal records; either index may win.
     *
     * @return the index readers will see; 0 if the table is full (the cap of ISA 1.6: the constant simply
     *     has no facts) or the record is longer than {@link #MAX_RECORD}
     */
    int publish(int row, int[] record, int len) {
        if (row <= 0 || len <= 0 || len > MAX_RECORD) {
            return 0;
        }
        AtomicIntegerArray slot = slotChunk(row >>> SLOT_LOG2);
        if (slot == null) {
            return 0;
        }
        int i = row & SLOT_MASK;
        int seen = slot.get(i);
        if (seen != 0) {
            raceLost(seen, record, len);
            return seen;
        }
        int index = allocate(len + 1);
        if (index == 0) {
            return 0;
        }
        int[] chunk = chunks.get(index >>> CHUNK_LOG2);
        int at = index & CHUNK_MASK;
        chunk[at] = len;
        System.arraycopy(record, 0, chunk, at + 1, len);
        if (slot.compareAndSet(i, 0, index + 1)) { // the one release store of this record
            published.incrementAndGet();
            if ((record[Layout.F_FLAGS] & Layout.F_NONE) != 0) {
                none.incrementAndGet();
            }
            return index + 1;
        }
        seen = slot.get(i);
        raceLost(seen, record, len);
        return seen;
    }

    private void raceLost(int winner, int[] record, int len) {
        lost.incrementAndGet();
        int[] chunk = chunkOf(winner);
        int at = offsetOf(winner);
        boolean same = chunk[at - 1] == len;
        for (int k = 0; same && k < len; k++) {
            same = chunk[at + k] == record[k];
        }
        if (!same && ((chunk[at + Layout.F_FLAGS] | record[Layout.F_FLAGS]) & Layout.F_NONE) == 0) {
            lostDifferent.incrementAndGet(); // two complete derivations disagree: a bug (pubcheck, tests)
        }
    }

    /** {@code n} consecutive words inside one chunk: the global offset of the first, or 0 at the cap. */
    private int allocate(int n) {
        for (;;) {
            long t = top.get();
            long start = t;
            if ((start & CHUNK_MASK) + n > CHUNK_WORDS) {
                start = (start | CHUNK_MASK) + 1; // the record does not fit into the rest of this chunk
            }
            long end = start + n;
            if ((end - 1) >>> CHUNK_LOG2 >= MAX_CHUNKS) {
                return 0;
            }
            if (top.compareAndSet(t, end)) {
                int c = (int) (start >>> CHUNK_LOG2);
                if (chunks.get(c) == null) {
                    chunks.compareAndSet(c, null, new int[CHUNK_WORDS]);
                }
                return (int) start;
            }
        }
    }

    private AtomicIntegerArray slotChunk(int c) {
        if (c >= MAX_SLOT_CHUNKS) {
            return null;
        }
        AtomicIntegerArray s = slots.get(c);
        if (s == null) {
            slots.compareAndSet(c, null, new AtomicIntegerArray(SLOT_WORDS));
            s = slots.get(c);
        }
        return s;
    }

    /** Bytes held (statistics; capped together with the code store at 0.4 GB, ISA 1.6). */
    long bytes() {
        return 4L * top.get();
    }

    long publishedRecords() {
        return published.get();
    }

    /** Publications that found a record of the row already there. */
    long lostRaces() {
        return lost.get();
    }

    /** Lost publications whose words differed from the winner's although both derivations were complete. Must be 0. */
    long lostDifferent() {
        return lostDifferent.get();
    }

    /** Records published with F_NONE. */
    long noneRecords() {
        return none.get();
    }

    Store store() {
        return store;
    }
}

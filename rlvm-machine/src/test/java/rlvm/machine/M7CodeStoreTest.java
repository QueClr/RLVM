package rlvm.machine;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The engine-wide code store (ISA 3.3, 12 R6): directory, row slots, publication by one release store,
 * the rent-to-own counter (ISA 3.1), concurrent writers.
 */
@Tag("fast")
class M7CodeStoreTest {

    /** A well-formed block of {@code len} words whose payload words carry {@code seed}. */
    private static int[] block(int role, int row, int len, int seed) {
        int[] b = new int[len];
        b[Op.BLK_HDR0] = Code.hdr0(len, 0, role);
        b[Op.BLK_ROW] = row;
        b[Op.BLK_HDR2] = Code.hdr2(0, 0, 0);
        for (int i = Op.BLK_ENTRIES; i < len; i++) {
            b[i] = seed * 31 + i;
        }
        return b;
    }

    private static int[] words(CodeStore cs, int id) {
        int[] c = cs.chunkOf(id);
        int o = cs.offsetOf(id);
        return Arrays.copyOfRange(c, o, o + cs.lengthOf(id));
    }

    @Test
    void emptyStoreAnswersNone() {
        CodeStore cs = new CodeStore(null);
        assertEquals(0, cs.blockOf(7, Op.ROLE_BODY));
        assertEquals(0, cs.blockOf(1 << 20, Op.ROLE_TELE));
        assertNull(cs.chunkOf(1));
        assertFalse(cs.exists(1));
        assertFalse(cs.exists(0));
        assertFalse(cs.exists(-5));
        assertEquals(0, cs.bytes());
        assertEquals(0, cs.blockCount());
    }

    @Test
    void publishOnceFirstWins() {
        CodeStore cs = new CodeStore(null);
        int[] a = block(Op.ROLE_BODY, 5, 9, 1);
        int id = cs.publish(5, Op.ROLE_BODY, a, 9);
        assertTrue(id > 0);
        assertEquals(id, cs.blockOf(5, Op.ROLE_BODY));
        assertEquals(0, cs.blockOf(5, Op.ROLE_TELE));
        assertEquals(0, cs.blockOf(4, Op.ROLE_BODY));
        assertArrayEquals(a, words(cs, id));
        // a second publication of the slot stores nothing and returns the published id
        int[] other = block(Op.ROLE_BODY, 5, 12, 2);
        assertEquals(id, cs.publish(5, Op.ROLE_BODY, other, 12));
        assertArrayEquals(a, words(cs, id));
        assertEquals(1, cs.blockCount());
        assertEquals(9 * 4, cs.bytes());
        // the published words are a copy: the worker's buffer may be reused
        a[5] = -1;
        assertNotEquals(-1, words(cs, id)[5]);
    }

    @Test
    void blocksNeverStraddleChunksAndLargeBlocksGetTheirOwn() {
        CodeStore cs = new CodeStore(null);
        int n = 0;
        int[] ids = new int[600];
        int[][] src = new int[600][];
        for (int i = 0; i < 600; i++) {
            int len = 5 + (i * 37) % 700;
            src[i] = block(Op.ROLE_RULE, i + 1, len, i);
            ids[i] = cs.add(src[i], len);
            assertTrue(ids[i] > 0);
            n += len;
        }
        int[] big = block(Op.ROLE_BODY, 1, CodeStore.CHUNK_WORDS + 10, 77);
        int bigId = cs.add(big, big.length);
        assertTrue(bigId > 0);
        for (int i = 0; i < 600; i++) {
            assertArrayEquals(src[i], words(cs, ids[i]), "block " + i);
            assertTrue(cs.offsetOf(ids[i]) + src[i].length <= cs.chunkOf(ids[i]).length);
        }
        assertArrayEquals(big, words(cs, bigId));
        assertEquals(4L * (n + big.length), cs.bytes());
        // malformed requests are refused
        assertEquals(0, cs.add(new int[2], 2));
        assertEquals(0, cs.publish(3, 0, src[0], src[0].length));
        assertEquals(0, cs.publish(3, Op.ROLE_COUNT, src[0], src[0].length));
    }

    @Test
    void rentToOwnCounterFiresExactlyOnceAtElabAt() {
        CodeStore cs = new CodeStore(null);
        int fired = 0;
        for (int i = 1; i <= 40; i++) {
            boolean f = cs.request(123, Op.ROLE_BODY);
            if (f) {
                fired++;
                assertEquals(Op.ELAB_AT, i, "fires at the ELAB_AT-th request");
            }
        }
        assertEquals(1, fired);
        assertEquals(15, cs.requests(123, Op.ROLE_BODY), "saturating 4-bit counter");
        // roles and rows are counted separately
        assertEquals(0, cs.requests(123, Op.ROLE_TELE));
        assertEquals(0, cs.requests(124, Op.ROLE_BODY));
        for (int i = 1; i < Op.ELAB_AT; i++) {
            assertFalse(cs.request(123, Op.ROLE_TELE));
        }
        assertTrue(cs.request(123, Op.ROLE_TELE));
        assertEquals(15, cs.requests(123, Op.ROLE_BODY));
        // a row far beyond the first chunk
        for (int i = 1; i < Op.ELAB_AT; i++) {
            assertFalse(cs.request(2_000_000, Op.ROLE_RULES));
        }
        assertTrue(cs.request(2_000_000, Op.ROLE_RULES));
        // rows beyond the tables stay generic for good
        assertFalse(cs.request(1 << 30, Op.ROLE_BODY));
        assertEquals(0, cs.blockOf(1 << 30, Op.ROLE_BODY));
        assertEquals(0, cs.publish(1 << 30, Op.ROLE_BODY, block(Op.ROLE_BODY, 1, 8, 1), 8));
    }

    @Test
    void concurrentPublishersOfOneRoleAgreeAndReadersSeeCompleteBlocks() throws Exception {
        CodeStore cs = new CodeStore(null);
        int threads = 8;
        int rows = 2000;
        int[][] seen = new int[threads][rows + 1];
        AtomicInteger torn = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        Thread[] ts = new Thread[threads];
        for (int t = 0; t < threads; t++) {
            int id = t;
            ts[t] = new Thread(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    return;
                }
                for (int k = 0; k < rows; k++) {
                    int row = 1 + ((id & 1) == 0 ? k : rows - 1 - k);
                    int len = 6 + row % 50;
                    // every worker builds the same block for a row (a function of the store alone)
                    int b = cs.publish(row, Op.ROLE_TELE, block(Op.ROLE_TELE, row, len, row), len);
                    seen[id][row] = b;
                    // read what any worker published so far: a block is complete as soon as its id is visible
                    int other = cs.blockOf(1 + (row * 7919) % rows, Op.ROLE_TELE);
                    if (other != 0) {
                        int[] c = cs.chunkOf(other);
                        int o = cs.offsetOf(other);
                        int l = Code.len(c[o]);
                        int r = c[o + Op.BLK_ROW];
                        if (l != 6 + r % 50 || c[o + l - 1] != r * 31 + l - 1) {
                            torn.incrementAndGet();
                        }
                    }
                }
            });
            ts[t].start();
        }
        start.countDown();
        for (Thread t : ts) {
            t.join();
        }
        assertEquals(0, torn.get(), "a reader saw an incomplete block");
        for (int row = 1; row <= rows; row++) {
            int b = cs.blockOf(row, Op.ROLE_TELE);
            assertTrue(b > 0);
            for (int t = 0; t < threads; t++) {
                assertEquals(b, seen[t][row], "row " + row + " thread " + t);
            }
            assertArrayEquals(block(Op.ROLE_TELE, row, 6 + row % 50, row), words(cs, b));
        }
        assertEquals(rows, cs.blockCount(), "one block per role, whoever won");
    }
}

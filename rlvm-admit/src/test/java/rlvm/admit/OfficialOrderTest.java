package rlvm.admit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/**
 * The simulation of the official replay order ({@link OfficialOrder}) against verdicts of
 * leanchecker 4.36.0 recorded for files that differ only in three names
 * (src/test/resources/order; generators {@code work/rlvm-admit/probe2/gen2.py}, {@code gen3.py}):
 * a mutual block {@code A : Type}, {@code B : D -> Type} with {@code A.mk : A}, and an axiom
 * {@code D : Type}. Only the header of {@code B} mentions {@code D}, and the official replay
 * declares the block from the first of its types it finishes: the file is rejected ("unknown
 * constant D") exactly when {@code A} or {@code A.mk} is the smallest of the four names in
 * {@code Name.quickCmp} order.
 */
class OfficialOrderTest {

    /** file, rejected by the official checker. */
    private static final Object[][] CASES = {
        {"order_00_Aa_Bb_Dd", false},
        {"order_05_Aa_Bb_alpha", false},
        {"order_08_Aa_Dd_foo", true},
        {"order_09_Aa_Dd_Quux", true},
        {"order_19_Aa_foo_Dd", true},
        {"order_21_Aa_foo_Quux", true},
        // numeric components (also 2^64 - 1 and beyond), non-ASCII, long and nested names
        {"xorder_00", true},
        {"xorder_01", false},
        {"xorder_02", false},
        {"xorder_03", true},
        {"xorder_04", false},
        {"xorder_05", false},
        {"xorder_06", false},
        {"xorder_07", false},
        {"xorder_08", false},
        {"xorder_09", false},
        {"xorder_10", false},
        {"xorder_11", false},
        {"xorder_13", true},
        {"xorder_14", true},
        {"xorder_15", true},
        {"xorder_17", true},
    };

    private static OfficialOrder simulate(String name) throws IOException {
        URL url = OfficialOrderTest.class.getResource("/order/" + name + ".ndjson");
        assertNotNull(url, name);
        Path file;
        try {
            file = Path.of(url.toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
        ArenaFiles.Loaded l = new ArenaFiles.Loaded(file);
        return new OfficialOrder(l.store, l.ctx.terms, l.ctx.lists, u -> l.store.decls().claims(u), null, 1L << 20);
    }

    @Test
    void hashesOfTheLeanRuntime() {
        // Name.hash of single-component names: mixHash(1723, MurmurHash64A(utf8, seed 11))
        assertEquals(0xc8a0636f74fa7f5bL,
                OfficialOrder.mixHash(1723L, OfficialOrder.stringHash("Quot".getBytes(StandardCharsets.UTF_8))));
        assertEquals(0xebe2c6e3528ffd64L, OfficialOrder.mixHash(1723L,
                OfficialOrder.stringHash("quotLiftReduction".getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    void theSimulatedOrderDecidesWhatLeancheckerDecides() throws IOException {
        for (Object[] c : CASES) {
            String name = (String) c[0];
            OfficialOrder o = simulate(name);
            assertTrue(o.available(), name + ": " + o.whyUnavailable());
            assertEquals((Boolean) c[1], o.firstAbsent() >= 0, name);
        }
    }

    @Test
    void filesOutsideTheModelAreNotSimulated() throws IOException {
        // z.(2^64) and z.(2^128) have the same hash (a numeric component of 2^64 or more hashes as 17)
        OfficialOrder tie = simulate("xorder_57");
        assertFalse(tie.available());
        // an inductive type named Quot with the constructor Quot.mk: LeanChecker sets that record aside
        OfficialOrder quot = simulate("xorder_16");
        assertFalse(quot.available());
    }
}

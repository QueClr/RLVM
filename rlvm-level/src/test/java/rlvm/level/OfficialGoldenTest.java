package rlvm.level;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Differential test against the real official kernel. {@code official-golden.tsv} holds the answers of
 * {@code leanchecker --from-export} (Lean v4.36.0) to about 11,000 level questions, produced by
 * {@code bench/modules/rlvm-level/gen_golden.py} (one tiny export per question). The official
 * operations of {@link LevelStore} and of the reference port must give exactly these answers; the
 * complete operations must accept at least what the official kernel accepts and agree with the
 * brute-force semantics.
 */
class OfficialGoldenTest {

    private static List<String[]> load() throws IOException {
        List<String[]> rows = new ArrayList<>();
        try (InputStream in = OfficialGoldenTest.class.getResourceAsStream("official-golden.tsv")) {
            assertNotNull(in, "official-golden.tsv missing from the test resources");
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = r.readLine()) != null) {
                if (!line.isEmpty() && line.charAt(0) != '#') {
                    rows.add(line.split("\t"));
                }
            }
        }
        return rows;
    }

    @Test
    void officialOperationsReproduceTheOfficialKernel() throws IOException {
        // name ids in the opposite order of the names: the normal form must use the name order
        Lv.Names n = new Lv.Names("w", "v", "u");
        LevelStore s = new LevelStore(n);
        int[] buf = {n.id("u"), n.id("v"), n.id("w")};
        int uvw = s.internList(buf, 0, 3);
        int nE = 0;
        int nG = 0;
        int nI = 0;
        int completeFindsMoreE = 0;
        int completeFindsMoreG = 0;
        for (String[] row : load()) {
            boolean expected = row[row.length - 1].equals("1");
            String what = String.join(" ", row);
            switch (row[0]) {
                case "E": {
                    RefLevel x = Lv.parse(row[1]);
                    RefLevel y = Lv.parse(row[2]);
                    int ix = Lv.toStore(s, x, n);
                    int iy = Lv.toStore(s, y, n);
                    assertEquals(expected, s.isEquivOfficial(ix, iy), what);
                    assertEquals(expected, s.isEquivOfficial(iy, ix), what);
                    assertEquals(expected, RefLevel.isEquivalent(x, y), "reference: " + what);
                    boolean sem = Lv.semEq(x, y);
                    assertEquals(sem, s.isEquiv(ix, iy), "complete: " + what);
                    if (expected) {
                        assertTrue(sem, "official kernel unsound?! " + what);
                    } else if (sem) {
                        completeFindsMoreE++;
                    }
                    nE++;
                    break;
                }
                case "G": {
                    RefLevel x = Lv.parse(row[1]);
                    RefLevel y = Lv.parse(row[2]);
                    int ix = Lv.toStore(s, x, n);
                    int iy = Lv.toStore(s, y, n);
                    assertEquals(expected, s.isGeqOfficial(ix, iy) || s.isAlwaysZero(ix), what);
                    assertEquals(expected, RefLevel.isGeq(x, y) || RefLevel.normalizesToZero(x), "reference: " + what);
                    boolean sem = Lv.semGeq(x, y);
                    assertEquals(sem, s.isGeq(ix, iy), "complete: " + what);
                    if (expected && !s.isAlwaysZero(ix)) {
                        assertTrue(sem, "official kernel unsound?! " + what);
                    } else if (!expected && sem) {
                        completeFindsMoreG++;
                    }
                    nG++;
                    break;
                }
                case "I": {
                    RefLevel l = Lv.parse(row[1]);
                    RefLevel[] sub = {Lv.parse(row[2]), Lv.parse(row[3]), Lv.parse(row[4])};
                    RefLevel m = Lv.parse(row[5]);
                    int il = Lv.toStore(s, l, n);
                    int[] args = {Lv.toStore(s, sub[0], n), Lv.toStore(s, sub[1], n), Lv.toStore(s, sub[2], n)};
                    int subst = s.mkSubst(uvw, s.internList(args, 0, 3));
                    int im = Lv.toStore(s, m, n);
                    int inst = s.instantiate(il, subst);
                    assertEquals(expected, s.isEquivOfficial(inst, im), what);
                    RefLevel rinst = RefLevel.instantiate(l, List.of("u", "v", "w"), List.of(sub));
                    assertEquals(expected, RefLevel.isEquivalent(rinst, m), "reference: " + what);
                    if (expected) {
                        assertTrue(s.isEquiv(inst, im), "complete: " + what);
                    }
                    nI++;
                    break;
                }
                default:
                    throw new AssertionError("bad golden row: " + what);
            }
        }
        assertTrue(nE > 4000 && nG > 3500 && nI > 2500, nE + " " + nG + " " + nI);
        // The golden set contains questions where the official kernel is incomplete.
        assertTrue(completeFindsMoreE > 100, "E " + completeFindsMoreE);
        assertTrue(completeFindsMoreG > 10, "G " + completeFindsMoreG);
        System.out.println("official golden: E=" + nE + " G=" + nG + " I=" + nI + "; semantically true but rejected by the"
                + " official kernel: E " + completeFindsMoreE + ", G " + completeFindsMoreG);
    }

    @Test
    void answersWithWrongNameOrder() throws IOException {
        // Informational: with a parameter order that is not Lean's name order the official port may
        // deviate from the official kernel (never unsoundly); count how often on the golden set.
        Lv.Names n = new Lv.Names("w", "v", "u");
        NameOracle reversed = new NameOracle() {
            @Override
            public int compareNames(int x, int y) {
                return -n.compareNames(x, y);
            }

            @Override
            public void appendName(StringBuilder sb, int nameId) {
                n.appendName(sb, nameId);
            }
        };
        LevelStore s = new LevelStore(reversed);
        int total = 0;
        int differ = 0;
        for (String[] row : load()) {
            if (!row[0].equals("E") && !row[0].equals("G")) {
                continue;
            }
            boolean expected = row[row.length - 1].equals("1");
            RefLevel x = Lv.parse(row[1]);
            RefLevel y = Lv.parse(row[2]);
            int ix = Lv.toStore(s, x, n);
            int iy = Lv.toStore(s, y, n);
            boolean got = row[0].equals("E") ? s.isEquivOfficial(ix, iy) : s.isGeqOfficial(ix, iy) || s.isAlwaysZero(ix);
            if (got) {
                // sound under any order
                assertTrue(row[0].equals("E") ? Lv.semEq(x, y) : Lv.semGeq(x, y) || s.isAlwaysZero(ix), String.join(" ", row));
            }
            total++;
            differ += got != expected ? 1 : 0;
        }
        System.out.println("official golden with reversed name order: " + differ + " of " + total + " answers differ");
    }
}

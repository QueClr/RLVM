package rlvm.level;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The one place where the official kernel's verdict depends on <em>pointer</em> equality of levels,
 * pinned against the real kernel ({@code official-golden-rebuild.tsv}: 1,600 answers of
 * {@code leanchecker --from-export}, Lean v4.36.0).
 *
 * <p>The official {@code instantiate} keeps a level node when its operands come back pointer-equal and
 * rebuilds it through {@code mk_max} / {@code mk_imax} otherwise. For levels read from an export
 * "pointer-equal" is "structurally equal", which is what {@link LevelStore#instantiate} implements.
 * {@code add_inductive} is different: it refers to the types, constructors and recursors of the block
 * with parameter objects it makes itself ({@code lparams_to_levels}), so when it type-checks the
 * recursor it generated ({@code check_recursors}), the type of every such reference is the declared
 * type with <em>every level node above a parameter rebuilt</em>, and the kernel compares that with
 * the declared spelling ({@code is_equivalent}). The rebuilt spelling is not always equivalent for
 * the official normal form: {@code imax u 1} is rebuilt into {@code max u 1}, and the official kernel
 * rejects an inductive type with a parameter of sort {@code imax u 1} ("application type mismatch").
 *
 * <p>The test states the rule with the public operations of {@link LevelStore} ({@link #rebuild}) and
 * checks it on every golden answer: the official kernel accepts the declaration iff
 * {@code isEquivOfficial(L, rebuild(L))}. A kernel that wants the official verdict on such
 * declarations compares {@code L} with {@code rebuild(L)} wherever {@code add_inductive} applies one
 * of the block's own constants; one that keeps the level as it is (this module's {@code instantiate}
 * under the identity substitution) accepts a superset.
 */
class OfficialRebuildGoldenTest {

    /**
     * The official {@code instantiate(l, ps, ls)} when {@code ls} are fresh objects for the same
     * parameters: a level without parameters is returned as it is, every other node is rebuilt.
     */
    static int rebuild(LevelStore s, int l) {
        if (!s.hasParam(l)) {
            return l;
        }
        switch (s.kind(l)) {
            case LevelStore.KIND_PARAM:
                return l;
            case LevelStore.KIND_SUCC:
                return s.succ(rebuild(s, s.succOf(l)));
            case LevelStore.KIND_MAX:
                return s.mkMax(rebuild(s, s.lhs(l)), rebuild(s, s.rhs(l)));
            default:
                return s.mkIMax(rebuild(s, s.lhs(l)), rebuild(s, s.rhs(l)));
        }
    }

    /** The same on the reference port: {@code instantiate} never sees an operand come back unchanged. */
    static RefLevel rebuild(RefLevel l) {
        if (!l.hasParam) {
            return l;
        }
        switch (l.kind) {
            case RefLevel.PARAM:
                return l;
            case RefLevel.SUCC:
                return RefLevel.succ(rebuild(l.a));
            case RefLevel.MAX:
                return RefLevel.mkMax(rebuild(l.a), rebuild(l.b));
            default:
                return RefLevel.mkIMax(rebuild(l.a), rebuild(l.b));
        }
    }

    @Test
    void theOfficialKernelAcceptsAnInductiveTypeIffItsLevelsSurviveTheRebuild() throws IOException {
        Lv.Names n = new Lv.Names("w", "v", "u");
        LevelStore s = new LevelStore(n);
        int[] buf = {n.id("u"), n.id("v"), n.id("w")};
        int uvw = s.internList(buf, 0, 3);
        int identity = s.mkSubst(uvw, s.internList(new int[] {s.param(n.id("u")), s.param(n.id("v")), s.param(n.id("w"))}, 0, 3));
        int rows = 0;
        int rejected = 0;
        int changed = 0;
        try (InputStream in = OfficialRebuildGoldenTest.class.getResourceAsStream("official-golden-rebuild.tsv")) {
            assertNotNull(in, "official-golden-rebuild.tsv missing from the test resources");
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isEmpty() || line.charAt(0) == '#') {
                    continue;
                }
                String[] row = line.split("\t");
                assertTrue(row[0].equals("R") || row[0].equals("R2"), line);
                boolean accepted = row[2].equals("1");
                RefLevel x = Lv.parse(row[1]);
                int l = Lv.toStore(s, x, n);
                int rebuilt = rebuild(s, l);
                assertEquals(accepted, s.isEquivOfficial(l, rebuilt), line + " (rebuilt: " + s.toString(rebuilt) + ")");
                assertEquals(accepted, RefLevel.isEquivalent(x, rebuild(x)), "reference: " + line);
                // the rebuilt level is the same level: only its spelling changed
                assertTrue(s.isEquiv(l, rebuilt), "complete: " + line);
                // this module's instantiate keeps the declared spelling under the identity substitution
                assertEquals(l, s.instantiate(l, identity), line);
                assertEquals(l, s.instantiate(l, 0), line);
                rows++;
                rejected += accepted ? 0 : 1;
                changed += rebuilt != l ? 1 : 0;
            }
        }
        assertTrue(rows >= 1600, "rows " + rows);
        assertTrue(rejected >= 150, "rejected " + rejected);
        assertTrue(changed > rejected, "levels with another spelling after the rebuild: " + changed);
        System.out.println("official rebuild golden: " + rows + " declarations, " + rejected + " rejected by the official"
                + " kernel, " + changed + " with a level the rebuild spells differently");
    }

    @Test
    void theCuratedCases() {
        Lv.Names n = new Lv.Names("u", "v");
        LevelStore s = new LevelStore(n);
        int u = s.param(n.id("u"));
        int v = s.param(n.id("v"));
        int one = LevelStore.ONE;
        // imax u 1: rebuilt into max u 1, which the official normal form does not identify with it
        int imaxU1 = s.imax(u, one);
        assertEquals(s.max(u, one), rebuild(s, imaxU1));
        assertTrue(!s.isEquivOfficial(imaxU1, rebuild(s, imaxU1)));
        // imax u (v+1): rebuilt into max u (v+1), and the two are equivalent for the official kernel
        int imaxUSv = s.imax(u, s.succ(v));
        assertEquals(s.max(u, s.succ(v)), rebuild(s, imaxUSv));
        assertTrue(s.isEquivOfficial(imaxUSv, rebuild(s, imaxUSv)));
        // spellings the smart constructors keep
        for (int l : List.of(u, s.succ(u), s.max(u, v), s.imax(u, v), s.max(s.succ(u), v), s.ofNat(3))) {
            assertEquals(l, rebuild(s, l), s.toString(l));
        }
    }
}

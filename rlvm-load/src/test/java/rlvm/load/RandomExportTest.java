package rlvm.load;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.Random;

import org.junit.jupiter.api.Test;

/**
 * Random exports that no exporter would write: roots anywhere (earlier segments, proof blocks, the
 * same record as type and value), dropped declarations between admitted ones, garbage records,
 * inductive blocks with arbitrary members. Every file goes through the structural oracle and the
 * naive recount of the sharing flags, with and without proof blocks.
 */
class RandomExportTest {

    static byte[] generate(long seed) {
        Random rnd = new Random(seed);
        Nd nd = new Nd();
        int[] levelIds = new int[64];
        int nLevels = 1; // level 0
        int u = nd.name("u");
        levelIds[nLevels++] = nd.param(u);
        int nameCounter = 0;
        int base = nd.name("c");
        int[] declared = new int[512];
        int nDeclared = 0;
        int decls = 1 + rnd.nextInt(40);
        int exprs = 0;
        // one starting record so that every reference has a target
        nd.sort(0);
        exprs++;
        for (int d = 0; d < decls; d++) {
            int burst = rnd.nextInt(4) == 0 ? rnd.nextInt(120) : rnd.nextInt(14);
            for (int i = 0; i < burst; i++) {
                int far = rnd.nextInt(exprs);
                int near = exprs - 1 - rnd.nextInt(Math.min(exprs, 6));
                int a = rnd.nextBoolean() ? near : far;
                int b = rnd.nextInt(3) == 0 ? rnd.nextInt(exprs) : exprs - 1 - rnd.nextInt(Math.min(exprs, 3));
                int c = rnd.nextInt(exprs);
                switch (rnd.nextInt(16)) {
                    case 0 -> nd.bvar(rnd.nextInt(5));
                    case 1 -> {
                        if (nLevels < levelIds.length && rnd.nextBoolean()) {
                            int x = levelIds[rnd.nextInt(nLevels)];
                            int y = levelIds[rnd.nextInt(nLevels)];
                            levelIds[nLevels++] = switch (rnd.nextInt(3)) {
                                case 0 -> nd.succ(x);
                                case 1 -> nd.max(x, y);
                                default -> nd.imax(x, y);
                            };
                        }
                        nd.sort(levelIds[rnd.nextInt(nLevels)]);
                    }
                    case 2 -> {
                        int name = nDeclared > 0 && rnd.nextInt(4) != 0 ? declared[rnd.nextInt(nDeclared)] : nd.num(base, "" + rnd.nextInt(50));
                        int[] us = new int[rnd.nextInt(3)];
                        for (int k = 0; k < us.length; k++) {
                            us[k] = levelIds[rnd.nextInt(nLevels)];
                        }
                        nd.cnst(name, us);
                    }
                    case 3, 4, 5, 6, 7, 8 -> nd.app(a, b);
                    case 9, 10 -> nd.lam(a, b);
                    case 11, 12 -> nd.pi(a, b);
                    case 13 -> nd.let(a, b, c);
                    case 14 -> nd.proj(base, "" + rnd.nextInt(4), a);
                    default -> {
                        if (rnd.nextBoolean()) {
                            nd.nat(rnd.nextBoolean() ? "" + rnd.nextInt(1000) : "340282366920938463463374607431768211456");
                        } else {
                            nd.strLit("s" + rnd.nextInt(5));
                        }
                    }
                }
                exprs++;
            }
            int name = nd.num(base, "" + (1000 + nameCounter++));
            int type = pick(rnd, exprs);
            int value = rnd.nextInt(8) == 0 ? type : pick(rnd, exprs);
            int[] lps = rnd.nextInt(4) == 0 ? new int[] {u} : new int[0];
            boolean admitted = true;
            switch (rnd.nextInt(12)) {
                case 0 -> {
                    boolean unsafe = rnd.nextInt(4) == 0;
                    nd.axiom(name, type, unsafe, lps);
                    admitted = !unsafe;
                }
                case 1, 2, 3 -> {
                    String safety = rnd.nextInt(5) == 0 ? (rnd.nextBoolean() ? "unsafe" : "partial") : "safe";
                    String hint = switch (rnd.nextInt(3)) {
                        case 0 -> "\"opaque\"";
                        case 1 -> "\"abbrev\"";
                        default -> "{\"regular\":" + rnd.nextInt(100) + "}";
                    };
                    nd.def(name, type, value, hint, safety, lps);
                    admitted = safety.equals("safe");
                }
                case 4, 5, 6, 7 -> nd.thm(name, type, value, lps);
                case 8 -> {
                    boolean unsafe = rnd.nextInt(4) == 0;
                    nd.opaque(name, type, value, unsafe, lps);
                    admitted = !unsafe;
                }
                case 9 -> nd.quot(name, type, new String[] {"type", "ctor", "lift", "ind"}[rnd.nextInt(4)], lps);
                default -> {
                    boolean unsafe = rnd.nextInt(5) == 0;
                    int nt = rnd.nextInt(3);
                    int nc = rnd.nextInt(4);
                    int nr = rnd.nextInt(3);
                    String[] types = new String[nt];
                    String[] ctors = new String[nc];
                    String[] recs = new String[nr];
                    int[] all = new int[nt];
                    if (nt > 0) {
                        all[0] = name;
                    }
                    for (int k = 1; k < nt; k++) {
                        all[k] = nd.num(base, "" + (1000 + nameCounter++));
                    }
                    int[] ctorNames = new int[nc];
                    for (int k = 0; k < nc; k++) {
                        ctorNames[k] = nd.num(base, "" + (1000 + nameCounter++));
                    }
                    for (int k = 0; k < nt; k++) {
                        types[k] = Nd.type(all[k], pick(rnd, exprs), rnd.nextInt(3), rnd.nextInt(3), all,
                                k == 0 ? ctorNames : new int[0], unsafe, lps);
                    }
                    for (int k = 0; k < nc; k++) {
                        ctors[k] = Nd.ctor(ctorNames[k], pick(rnd, exprs), name, k, rnd.nextInt(3), rnd.nextInt(3), unsafe, lps);
                    }
                    for (int k = 0; k < nr; k++) {
                        int[] rules = new int[3 * rnd.nextInt(3)];
                        for (int q = 0; q < rules.length; q += 3) {
                            rules[q] = name;
                            rules[q + 1] = rnd.nextInt(4);
                            rules[q + 2] = pick(rnd, exprs);
                        }
                        recs[k] = Nd.rec(nd.num(base, "" + (1000 + nameCounter++)), pick(rnd, exprs), all, 1, 2, 3, 4,
                                rnd.nextBoolean(), unsafe, rules, lps);
                    }
                    nd.inductive(types, ctors, recs);
                    admitted = !unsafe && nt > 0;
                }
            }
            if (admitted && nDeclared < declared.length) {
                declared[nDeclared++] = name;
            }
        }
        return nd.bytes();
    }

    private static int pick(Random rnd, int exprs) {
        return rnd.nextInt(3) == 0 ? rnd.nextInt(exprs) : exprs - 1 - rnd.nextInt(Math.min(exprs, 4));
    }

    @Test
    void randomExportsLoadFaithfully() throws IOException {
        int loaded = 0;
        int declined = 0;
        long promotions = 0;
        long blocks = 0;
        for (long seed = 1; seed <= 1500; seed++) {
            byte[] data = generate(seed);
            for (boolean proofBlocks : new boolean[] {true, false}) {
                Oracle o = new Oracle(data);
                try {
                    o.load(LoadOptions.batch(data.length).withProofBlocks(proofBlocks));
                } catch (UnsupportedExportException e) {
                    // only the two declined inductive mixtures can occur
                    assertTrue(e.getMessage().contains("unsafe"), "seed " + seed + ": " + e.getMessage());
                    declined++;
                    continue;
                }
                try {
                    o.verify();
                    o.verifySharingFlags();
                } catch (AssertionError | RuntimeException e) {
                    throw new AssertionError("seed " + seed + " proofBlocks=" + proofBlocks + ": " + e, e);
                }
                loaded++;
                promotions += o.report.promotions;
                blocks += o.report.blocks;
            }
        }
        assertTrue(loaded > 2000, "loaded " + loaded + ", declined " + declined);
        assertTrue(promotions > 1000 && blocks > 1000, promotions + " promotions, " + blocks + " blocks");
    }
}

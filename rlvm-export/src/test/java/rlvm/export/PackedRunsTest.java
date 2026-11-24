package rlvm.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;

import org.junit.jupiter.api.Test;

/**
 * Parallel packing ({@link ExportReader.Options#runs}, {@link RunPacker}): on a file with the shape
 * lean4export gives its output the scanner threads offer the proof records as packed runs; a sink that
 * takes them sees exactly the records of the single-threaded mode, one that refuses them sees exactly
 * its calls. Files that leave the shape (sparse ids, metadata, a theorem whose type comes late, a
 * declaration in another spelling, damaged lines) are read as before, with the same error.
 */
class PackedRunsTest {

    /** Writes an export the way lean4export does: records of a declaration right before its line, type first. */
    private static final class Exp {
        final StringBuilder sb = new StringBuilder(T.META);
        final Random rnd;
        int names = 1;
        int exprs;
        int structures;
        int levelOne;

        Exp(long seed) {
            rnd = new Random(seed);
            sb.append("{\"il\":1,\"succ\":0}\n");
            levelOne = 1;
        }

        int name(String s) {
            sb.append("{\"in\":").append(names).append(",\"str\":{\"pre\":0,\"str\":\"").append(s).append("\"}}\n");
            return names++;
        }

        int sort() {
            sb.append("{\"ie\":").append(exprs).append(",\"sort\":").append(rnd.nextInt(2)).append("}\n");
            return exprs++;
        }

        int bvar() {
            sb.append("{\"bvar\":").append(rnd.nextInt(3)).append(",\"ie\":").append(exprs).append("}\n");
            return exprs++;
        }

        int cnst(int name) {
            sb.append("{\"const\":{\"name\":").append(name).append(",\"us\":[]},\"ie\":").append(exprs).append("}\n");
            return exprs++;
        }

        int app(int fn, int arg) {
            sb.append("{\"app\":{\"arg\":").append(arg).append(",\"fn\":").append(fn).append("},\"ie\":").append(exprs).append("}\n");
            structures++;
            return exprs++;
        }

        int lam(int name, int type, int body) {
            sb.append("{\"ie\":").append(exprs).append(",\"lam\":{\"binderInfo\":\"").append(rnd.nextBoolean() ? "default" : "implicit")
                    .append("\",\"body\":").append(body).append(",\"name\":").append(name).append(",\"type\":").append(type).append("}}\n");
            structures++;
            return exprs++;
        }

        int pi(int name, int type, int body) {
            sb.append("{\"forallE\":{\"binderInfo\":\"default\",\"body\":").append(body).append(",\"name\":").append(name)
                    .append(",\"type\":").append(type).append("},\"ie\":").append(exprs).append("}\n");
            structures++;
            return exprs++;
        }

        int let(int name, int type, int value, int body) {
            sb.append("{\"ie\":").append(exprs).append(",\"letE\":{\"body\":").append(body).append(",\"name\":").append(name)
                    .append(",\"nondep\":").append(rnd.nextBoolean()).append(",\"type\":").append(type).append(",\"value\":")
                    .append(value).append("}}\n");
            structures++;
            return exprs++;
        }

        int proj(int typeName, int struct) {
            sb.append("{\"ie\":").append(exprs).append(",\"proj\":{\"idx\":0,\"struct\":").append(struct).append(",\"typeName\":")
                    .append(typeName).append("}}\n");
            return exprs++;
        }

        void thm(int name, int type, int value) {
            sb.append("{\"thm\":{\"all\":[").append(name).append("],\"levelParams\":[],\"name\":").append(name).append(",\"type\":")
                    .append(type).append(",\"value\":").append(value).append("}}\n");
        }

        void def(int name, int type, int value) {
            sb.append("{\"def\":{\"all\":[").append(name).append("],\"hints\":\"abbrev\",\"levelParams\":[],\"name\":").append(name)
                    .append(",\"safety\":\"safe\",\"type\":").append(type).append(",\"value\":").append(value).append("}}\n");
        }

        void axiom(int name, int type) {
            sb.append("{\"axiom\":{\"isUnsafe\":false,\"levelParams\":[],\"name\":").append(name).append(",\"type\":").append(type)
                    .append("}}\n");
        }

        /** Some record with an id below {@code exprs}, near or far. */
        int any() {
            return rnd.nextInt(4) == 0 ? rnd.nextInt(exprs) : exprs - 1 - rnd.nextInt(Math.min(exprs, 6));
        }

        /** {@code n} records of a term over what exists; returns the id of the last. */
        int term(int n, int binder) {
            int last = exprs - 1;
            for (int i = 0; i < n; i++) {
                int k = rnd.nextInt(40);
                if (k < 26) {
                    last = app(any(), any());
                } else if (k < 30) {
                    last = lam(binder, any(), any());
                } else if (k < 33) {
                    last = pi(binder, any(), any());
                } else if (k < 35) {
                    last = let(binder, any(), any(), any());
                } else if (k == 35) {
                    last = bvar();
                } else if (k == 36) {
                    last = cnst(binder);
                } else if (k == 37) {
                    last = proj(binder, any());
                } else if (k == 38) {
                    binder = name("x" + names); // a name line in the middle of the records
                } else {
                    last = sort();
                }
            }
            return last;
        }

        byte[] bytes() {
            return T.utf8(sb.toString());
        }
    }

    /** {@code decls} declarations, mostly theorems, with proofs of about {@code size} records. */
    private static Exp exporterLike(long seed, int decls, int size) {
        Exp x = new Exp(seed);
        int binder = x.name("a");
        x.sort();
        x.bvar();
        for (int d = 0; d < decls; d++) {
            int n = x.name("d" + d);
            int kind = x.rnd.nextInt(10);
            int type = x.term(2 + x.rnd.nextInt(6), binder);
            if (kind == 0) {
                x.axiom(n, type);
            } else if (kind == 1) {
                x.def(n, type, x.term(1 + x.rnd.nextInt(size), binder));
            } else if (kind == 2) {
                // a theorem whose type is an earlier record: the whole segment is its proof
                x.thm(n, x.rnd.nextInt(type), x.term(1 + x.rnd.nextInt(size), binder));
            } else {
                x.thm(n, type, x.term(1 + x.rnd.nextInt(size), binder));
            }
        }
        return x;
    }

    @Test
    void exporterShapedFilesAreReadThroughRuns() {
        long runs = 0;
        long inRuns = 0;
        long structures = 0;
        long early = 0;
        for (int seed = 0; seed < 40; seed++) {
            Exp x = exporterLike(seed, 30 + seed, 10 + 12 * (seed % 7));
            byte[] data = x.bytes();
            T.Run f = T.agree("seed " + seed, data);
            T.assertOk(f);
            T.Run s = T.struct(data, T.options(1, 1 << 20, false), 0);
            for (int chunk : new int[] {256, 777, 4096, 1 << 16}) {
                if (data.length < 2 * chunk) {
                    continue;
                }
                T.Run p = T.struct(data, T.packing(2 + seed % 3, chunk), 1);
                T.same("seed " + seed + ", chunk " + chunk, s, p, true);
                assertEquals(x.exprs, p.result.exprs);
                assertEquals(0, p.earlyChildren);
                // with the sink's table of finished expressions: complete, or with holes
                T.Run e = T.struct(data, T.packingEarly(2 + seed % 3, chunk, 1 + seed % 4), 1);
                T.same("seed " + seed + ", chunk " + chunk + ", early", s, e, true);
                assertEquals(p.runRecords, e.runRecords);
                if (chunk == 4096) {
                    runs += p.runs;
                    inRuns += p.runRecords;
                    structures += x.structures;
                    early += e.earlyChildren;
                }
            }
        }
        // most app / lam / forallE / letE records belong to proofs and stand in stretches of two and more
        assertTrue(runs > 500, "runs taken: " + runs);
        assertTrue(inRuns * 2 > structures, inRuns + " of " + structures + " structure records were read through runs");
        assertTrue(early > 1000, "children encoded from the table of finished expressions: " + early);
    }

    @Test
    void aLargeFileWithTheDefaultChunkSize() {
        Exp x = exporterLike(99, 9000, 60);
        byte[] data = x.bytes();
        assertTrue(data.length > 12 << 20, "bytes " + data.length);
        T.Run s = T.struct(data, T.options(1, 1 << 20, false), 0);
        T.assertOk(s);
        ExportReader.Options o = new ExportReader.Options();
        o.threads = 3;
        o.runs = T.RUNS;
        T.Run p = T.struct(data, o, 1);
        T.same("default chunks", s, p, true);
        assertTrue(p.runRecords * 10 > x.structures * 6, p.runRecords + " of " + x.structures);
        // and refused: the complete trace of the single-threaded mode
        T.same("refused", T.run(false, sink -> ExportReader.read(data, sink)),
                T.run(false, sink -> ExportReader.read(data, sink, o)), true);
    }

    @Test
    void filesThatLeaveTheShapeAreReadAsBefore() {
        for (int seed = 0; seed < 60; seed++) {
            Exp x = exporterLike(1000 + seed, 25, 40);
            String text = x.sb.toString();
            String[] lines = text.split("\n", -1);
            Random rnd = new Random(seed);
            int at = 3 + rnd.nextInt(lines.length - 5);
            String what;
            switch (seed % 6) {
                case 0 -> {
                    what = "a line dropped";
                    lines[at] = null;
                }
                case 1 -> {
                    what = "a line twice";
                    lines[at] = lines[at] + "\n" + lines[at];
                }
                case 2 -> {
                    what = "two lines swapped";
                    String t = lines[at];
                    lines[at] = lines[at + 1];
                    lines[at + 1] = t;
                }
                case 3 -> {
                    what = "metadata around an earlier record";
                    lines[at] = "{\"ie\":900000,\"mdata\":{\"data\":{},\"expr\":0}}\n" + lines[at];
                }
                case 4 -> {
                    what = "a declaration in another spelling";
                    lines[at] = "{ \"axiom\":{\"isUnsafe\":false,\"levelParams\":[],\"name\":1,\"type\":0}}\n" + lines[at];
                }
                default -> {
                    what = "white space in a record";
                    lines[at] = lines[at].replace(":", ": ");
                }
            }
            StringBuilder sb = new StringBuilder();
            for (String l : lines) {
                if (l != null && !l.isEmpty()) {
                    sb.append(l).append('\n');
                }
            }
            byte[] data = T.utf8(sb.toString());
            // every configuration, the reference reader included, must agree on outcome and trace
            T.agree("seed " + seed + ": " + what + " at line " + (at + 1), data);
        }
    }
}

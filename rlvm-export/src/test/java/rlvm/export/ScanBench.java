package rlvm.export;

import java.nio.file.Files;
import java.nio.file.Path;

/** Development benchmark: the scanner alone, with a counting RecordOut. Not a test. */
public final class ScanBench {
    static final class Count implements RecordOut {
        long apps, lams, pis, lets, projs, consts, strs, nums, slows, decls, sum;

        public void app(long id, long fn, long arg) {
            apps++;
            sum += fn + arg;
        }

        public void lam(long id, int binderInfo, long name, long type, long body) {
            lams++;
        }

        public void pi(long id, int binderInfo, long name, long type, long body) {
            pis++;
        }

        public void let(long id, long name, boolean nondep, long type, long value, long body) {
            lets++;
        }

        public void proj(long id, long idx, long struct, long typeName) {
            projs++;
        }

        public void constant(long id, long name, int[] us, int off, int n) {
            consts++;
        }

        public void nameStr(long id, long pre, int off, int len) {
            strs++;
        }

        public void nameNum(long id, long pre, long i) {
            nums++;
        }

        public void slow(int off, int len) {
            slows++;
        }

        public void thm(long name, long type, long value, int[] ids, int numAll, int numParams) {
            decls++;
        }

        public void def(long name, long type, long value, int hintKind, long hintHeight, int[] ids, int numAll,
                int numParams) {
            decls++;
        }
    }

    public static void main(String[] args) throws Exception {
        Path file = Path.of(args[0]);
        int repeat = Integer.parseInt(args[1]);
        String mode = args.length > 2 ? args[2] : "count";
        byte[] data = Files.readAllBytes(file);
        int size = data.length;
        byte[] buf = new byte[(1 << 20) + Scanner.PAD];
        int[] list = new int[64];
        for (int r = 0; r < repeat; r++) {
            Count c = new Count();
            Tape tape = new Tape(1 << 18);
            long t0 = System.nanoTime();
            int pos = 0;
            while (pos < size) {
                int n = Math.min(1 << 20, size - pos);
                System.arraycopy(data, pos, buf, 0, n);
                int lastNl = n - 1;
                while (buf[lastNl] != '\n') {
                    lastNl--;
                }
                if (mode.equals("count")) {
                    Scanner.scan(buf, 0, lastNl + 1, c, list);
                } else {
                    tape.reset();
                    Scanner.scan(buf, 0, lastNl + 1, tape, list);
                    tape.replay(c);
                }
                pos += lastNl + 1;
            }
            double s = (System.nanoTime() - t0) / 1e9;
            System.out.printf("apps=%d lams=%d pis=%d lets=%d projs=%d consts=%d strs=%d nums=%d decls=%d slows=%d sum=%d  %.3f s%n",
                    c.apps, c.lams, c.pis, c.lets, c.projs, c.consts, c.strs, c.nums, c.decls, c.slows, c.sum, s);
        }
    }
}

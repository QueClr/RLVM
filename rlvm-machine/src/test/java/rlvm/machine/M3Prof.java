package rlvm.machine;

/**
 * M3's profiling entry point (not a test): the machine alone over every unit of an export, in-process, so
 * that a JFR recording or a timer sees the judgments without the launcher. Arguments: FILE [repeat].
 * <pre>
 * java -XX:StartFlightRecording=filename=x.jfr,settings=profile -cp CP -Drlvm.root=ROOT rlvm.machine.M3Prof work/exports/reachability.ndjson 2
 * </pre>
 */
final class M3Prof {
    private M3Prof() {}

    public static void main(String[] args) throws Exception {
        int repeat = args.length > 1 ? Integer.parseInt(args[1]) : 1;
        for (int i = 0; i < repeat; i++) {
            Fixture g = Fixture.load(args[0]);
            g.maxSteps = Long.MAX_VALUE;
            g.m.stats = Boolean.getBoolean("m3.stats");
            long t0 = System.nanoTime();
            long ticks0 = g.m.ticks();
            Fixture.Verdict v = g.checkAll();
            long ms = (System.nanoTime() - t0) / 1_000_000;
            System.out.println("pass " + i + ": " + v + " units " + v.units + ", " + ms + " ms, ticks " + (g.m.ticks() - ticks0)
                    + ", frames " + g.m.framesWritten + ", resets " + g.m.resets);
            {
                StringBuilder sb = new StringBuilder("spills by op: ");
                try {
                    long[] sp = (long[]) Machine.class.getDeclaredField("spillsByOp").get(g.m);
                    for (int op = 0; op < Op.OP_COUNT; op++) {
                        if (sp[op] != 0) {
                            sb.append(Op.name(op)).append('=').append(sp[op]).append(' ');
                        }
                    }
                    System.out.println(sb);
                } catch (ReflectiveOperationException e) {
                    // the published Machine has no per-op spill counters
                }
            }
            System.out.println("nests: roots " + g.m.x3.nestRoots + ", fallbacks " + g.m.x3.nestFallbacks + ", scratch resets " + g.m.x3.scratchResets
                    + ", scratch bytes " + g.m.x3.bytes() + ", session numbers " + (g.m.nats == null ? 0 : g.m.nats.bytesUsed()) + " bytes, records " + g.m.vp);
            if (g.m.stats) {
                StringBuilder sb = new StringBuilder();
                for (int op = 0; op < Op.OP_COUNT; op++) {
                    if (g.m.calls[op] != 0) {
                        sb.append(Op.name(op)).append('=').append(g.m.calls[op]).append('/').append(g.m.hits[op]).append(' ');
                    }
                }
                System.out.println("calls/hits: " + sb);
            }
            g.close();
        }
    }
}

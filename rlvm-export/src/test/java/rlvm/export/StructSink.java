package rlvm.export;

import java.util.Random;

/**
 * A {@link TraceSink} that records the STRUCTURE of the binder records only (no binder name, binder
 * info or nondep flag: the packed form of a run does not carry them) and can take packed runs
 * ({@link ExportSink#exprRun}), which it decodes into the events the single records give. A read with
 * runs taken must leave the same trace as a read without.
 */
final class StructSink extends TraceSink {
    private final int take;
    private final Random rnd;
    long runs;
    long runRecords;
    /** letE records with nondep = true that arrived as single records (none may be hidden in a run). */
    long nondepLets;
    long earlyChildren;

    /** @param take 0 = refuse every run, 1 = take every run, 2 = take or refuse at random */
    StructSink(int take, long seed) {
        super(true);
        this.take = take;
        this.rnd = new Random(seed);
    }

    @Override
    public int exprLam(int binderName, int binderInfo, int type, int body) {
        ev("lam", type, body, exprs + 1);
        return ++exprs;
    }

    @Override
    public int exprForall(int binderName, int binderInfo, int type, int body) {
        ev("pi", type, body, exprs + 1);
        return ++exprs;
    }

    @Override
    public int exprLet(int binderName, int type, int value, int body, boolean nondep) {
        if (nondep) {
            nondepLets++;
        }
        ev("let", type, value, body, exprs + 1);
        return ++exprs;
    }

    @Override
    public int exprRun(int firstFileId, int count, int delta, int minLocal, byte[] packed, int off, int len, byte[] lengths,
            int lengthsOff, int[] open, int openOff, int openCount, int numLam, int numPi, int numLet, int numEarly,
            int maxEarly) {
        if (take == 0 || (take == 2 && rnd.nextBoolean())) {
            return RUN_REFUSED;
        }
        if (firstFileId + delta != exprs + 1 || count < T.RUNS.minRecords) {
            throw new AssertionError("run of " + count + " from file id " + firstFileId + ", delta " + delta + ", after "
                    + exprs + " expressions");
        }
        int first = exprs + 1;
        int p = off;
        int e = openOff;
        final int openEnd = openOff + 2 * openCount;
        int lam = 0;
        int pi = 0;
        int let = 0;
        int early = 0;
        int[] c = new int[3];
        for (int i = 0; i < count; i++) {
            int id = firstFileId + i;
            int recordEnd = p + (lengths[lengthsOff + i] & 0xFF);
            int tag = packed[p++];
            int kids = tag == T.RUNS.let ? 3 : 2;
            for (int k = 0; k < kids; k++) {
                boolean body = k == kids - 1 && tag != T.RUNS.app;
                if (e < openEnd && (open[e] & 0x7FFF_FFFF) == p - off) {
                    // OPEN: the next entry of the list stands at this offset
                    if ((open[e] < 0) != body) {
                        throw new AssertionError("body flag of child " + k + " of " + id);
                    }
                    int child = open[e + 1];
                    e += 2;
                    if (child >= minLocal || child < 0) {
                        throw new AssertionError("open child " + child + " of " + id + " is not below " + minLocal);
                    }
                    c[k] = child + delta;
                    continue;
                }
                int v = 0;
                int shift = 0;
                int b;
                do {
                    b = packed[p++];
                    v |= (b & 0x7F) << shift;
                    shift += 7;
                } while (b < 0);
                if ((v & 1) == 0) {
                    // LOCAL
                    int child = id - (v >>> 1);
                    if (v == 0 || child < minLocal) {
                        throw new AssertionError("local child " + child + " of " + id + " is below " + minLocal);
                    }
                    c[k] = child + delta;
                } else {
                    // EARLY: the tests' tables hold the sink id of the expression itself
                    int r = v >>> 1;
                    if (r > maxEarly || r >= minLocal + delta || r < 1) {
                        throw new AssertionError("early child " + r + " of " + id + ", largest announced " + maxEarly);
                    }
                    early++;
                    c[k] = r;
                }
            }
            if (p != recordEnd) {
                throw new AssertionError("record " + id + " ends at " + p + ", its length says " + recordEnd);
            }
            if (tag == T.RUNS.app) {
                ev("app", c[0], c[1], exprs + 1);
            } else if (tag == T.RUNS.lam) {
                ev("lam", c[0], c[1], exprs + 1);
                lam++;
            } else if (tag == T.RUNS.pi) {
                ev("pi", c[0], c[1], exprs + 1);
                pi++;
            } else if (tag == T.RUNS.let) {
                ev("let", c[0], c[1], c[2], exprs + 1);
                let++;
            } else {
                throw new AssertionError("tag " + tag);
            }
            exprs++;
        }
        if (p != off + len || e != openEnd || lam != numLam || pi != numPi || let != numLet || early != numEarly) {
            throw new AssertionError("run of " + count + ": " + (p - off) + " of " + len + " bytes, " + (e - openOff) / 2 + " of "
                    + openCount + " open children, " + lam + "/" + pi + "/" + let + " binders against " + numLam + "/"
                    + numPi + "/" + numLet + ", " + early + " early children against " + numEarly);
        }
        runs++;
        runRecords += count;
        earlyChildren += early;
        return first;
    }
}

package rlvm.admit;

import rlvm.api.KernelOps;
import rlvm.api.LevelOps;
import rlvm.api.WorkerContext;
import rlvm.store.TermOps;
import rlvm.store.Terms;

/**
 * A {@code KernelOps} without a kernel: only what the environment pass itself touches (the reader,
 * the level operations, {@code begin}). Every judgment throws: the tests of this module must never
 * need an engine.
 */
final class FakeOps implements KernelOps {
    private final WorkerContext ctx;
    int begins;
    int lastRank = -1;
    int lastLevelParams = -1;

    FakeOps(WorkerContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public void begin(int rank, int levelParams) {
        begins++;
        lastRank = rank;
        lastLevelParams = levelParams;
    }

    @Override
    public void exposeUnitMembers(int kindMask) {}

    @Override
    public int infer(int term) {
        throw noEngine();
    }

    @Override
    public int check(int term) {
        throw noEngine();
    }

    @Override
    public int whnf(int term) {
        throw noEngine();
    }

    @Override
    public int whnfCore(int term) {
        throw noEngine();
    }

    @Override
    public boolean isDefEq(int a, int b) {
        throw noEngine();
    }

    @Override
    public int ensureSort(int term) {
        throw noEngine();
    }

    @Override
    public int ensurePi(int term) {
        throw noEngine();
    }

    @Override
    public boolean isProp(int type) {
        throw noEngine();
    }

    @Override
    public int mkLocal(int type) {
        throw noEngine();
    }

    @Override
    public Terms terms() {
        return ctx.terms;
    }

    @Override
    public TermOps termOps() {
        throw noEngine();
    }

    @Override
    public LevelOps levels() {
        return ctx.levels;
    }

    @Override
    public boolean whnfIsSyntactic() {
        return true;
    }

    @Override
    public long steps() {
        return 0;
    }

    private static AssertionError noEngine() {
        return new AssertionError("a test of rlvm-admit asked the fake engine for a judgment");
    }
}

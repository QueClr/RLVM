package rlvm.testkit;

import rlvm.api.KernelOps;
import rlvm.api.Session;
import rlvm.api.WorkerContext;

/**
 * Supplies the {@link KernelOps} of an engine whose sessions offer none: the machine's
 * {@code Session.ops()} is null in its release configuration, and its test tree has a
 * {@code MachineOps} (*agentic-note*: design-isa-document, 14.6). The {@link KernelSampler} of a differential run calls
 * {@link #open} once per session of the engine under test.
 *
 * <p>An implementation named by {@code rlvm.diff.ops} (or {@code testkit diff --ops CLASS}) needs a
 * public no-argument constructor.
 */
@FunctionalInterface
public interface OpsFactory {

    /**
     * @param session a session of the engine under test
     * @param ctx the context that session works on; the returned judgments must use it too, so that
     *     term ids mean the same to both engines
     * @return the judgments, or null if this session cannot offer any (the sampler is then off)
     */
    KernelOps open(Session session, WorkerContext ctx);
}

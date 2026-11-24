package rlvm.admit;

import java.nio.file.Path;

import rlvm.api.Engine;
import rlvm.api.EngineConfig;
import rlvm.api.Session;
import rlvm.load.MalformedExportException;
import rlvm.load.UnsupportedExportException;
import rlvm.ref.RefEngine;

/**
 * Development aid: runs only the environment pass over each export given and says whether it left
 * file order (and at which unit). {@code java -cp $(cat work/rlvm-admit/cp.txt) rlvm.admit.ReplayProbeMain FILE...}
 */
public final class ReplayProbeMain {
    private ReplayProbeMain() {}

    public static void main(String[] args) throws Exception {
        int replayed = 0;
        for (String arg : args) {
            ArenaFiles.Loaded l;
            try {
                l = new ArenaFiles.Loaded(Path.of(arg));
            } catch (MalformedExportException | UnsupportedExportException e) {
                continue;
            }
            try (Engine engine = RefEngine.FACTORY.create(l.store, EngineConfig.defaults());
                    Session session = engine.newSession(l.ctx)) {
                EnvBuilder env = new EnvBuilder(l.store, session.ops(), Policy.arena(), null);
                env.run();
                ReplayState rs = env.replayState();
                if (rs != null) {
                    replayed++;
                    System.out.println("dependency order from unit " + rs.first + " of " + rs.total + ": " + arg
                            + (env.failedUnit() >= 0 ? " (pass failed at " + env.failedUnit() + ": " + env.failureReason() + ")" : ""));
                }
            }
        }
        System.out.println(replayed + " of " + args.length + " files left file order");
    }
}

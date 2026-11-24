package rlvm.admit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import rlvm.api.Reason;
import rlvm.api.RunSignals;
import rlvm.api.WorkerContext;
import rlvm.api.bind.Bindings;
import rlvm.load.ExportLoader;
import rlvm.load.LevelStoreInterner;
import rlvm.load.NatStoreDecoder;
import rlvm.store.DeclKind;
import rlvm.store.DeclTable;
import rlvm.store.Store;

/**
 * The published arena tests, loaded with the real loader ({@code rlvm-load}, test scope) into a real
 * store, and the engine-free part of admission run over every unit.
 */
final class ArenaFiles {
    private ArenaFiles() {}

    /** {@code work/arena-site/tests} of the project (the tests run in the module directory). */
    static Path testsRoot() {
        Path root = Path.of("..", "work", "arena-site", "tests").toAbsolutePath().normalize();
        return Files.isDirectory(root) ? root : null;
    }

    static List<Path> files(String outcome) throws IOException {
        Path root = testsRoot();
        List<Path> out = new ArrayList<>();
        if (root == null) {
            return out;
        }
        try (Stream<Path> walk = Files.walk(root.resolve(outcome))) {
            walk.filter(p -> p.toString().endsWith(".ndjson")).sorted().forEach(out::add);
        }
        return out;
    }

    /** A loaded export with a worker context. */
    static final class Loaded {
        final Bindings bindings = new Bindings(256);
        final Store store;
        final WorkerContext ctx;

        Loaded(Path file) throws IOException {
            store = new Store(Files.size(file), bindings.baseLists(), Fixture.levelInfo(bindings.baseLevels()));
            bindings.bindNames(store.names());
            try {
                ExportLoader.load(file, store, new LevelStoreInterner(bindings.levelBase(), false),
                        new NatStoreDecoder());
            } catch (UnsupportedOperationException e) {
                // rlvm-load publishes its store binding after rlvm-store: until then these tests are skipped
                org.junit.jupiter.api.Assumptions.assumeTrue(false, "loader not usable yet: " + e.getMessage());
            }
            bindings.freeze();
            ctx = bindings.newWorkerContext(0, store, RunSignals.NONE, 1L << 30);
        }
    }

    /** Result of the engine-free checks over a whole file. */
    static final class Verdict {
        /** First unit with a structural rejection, or -1. */
        int rejectUnit = -1;
        Reason reject;
        String rejectMessage;
        /** First unit a structural check declined, or -1. */
        int declineUnit = -1;
        Reason decline;
        String declineMessage;
        int plainUnits;
        int blocks;
        int quots;

        @Override
        public String toString() {
            return "reject " + reject + " at " + rejectUnit + " (" + rejectMessage + "), decline " + decline + " at "
                    + declineUnit + " (" + declineMessage + ")";
        }
    }

    /**
     * Runs, for every unit in file order, what admission does before any judgment: the structural
     * checks of axioms, definitions, theorems and opaques, and for inductive records the claims
     * parsing, record consistency and root checks. No engine, no rlvm-inductive.
     */
    static Verdict structuralVerdict(Loaded l) {
        Store store = l.store;
        DeclTable decls = store.decls();
        StructuralChecks structural = new StructuralChecks(store, l.ctx.terms, l.ctx.lists, Policy.arena());
        ScriptedBlockOps never = new ScriptedBlockOps();
        EnvBuilder env = new EnvBuilder(store, new FakeOps(l.ctx), Policy.arena(), null, never,
                u -> store.decls().claims(u));
        Verdict v = new Verdict();
        int units = store.loadedUnits();
        for (int u = 0; u < units; u++) {
            if (decls.unitSize(u) == 0) {
                continue;
            }
            Reason r;
            String message;
            int kind = decls.unitKind(u);
            if (kind == DeclKind.INDUCT) {
                v.blocks++;
                r = env.checkBlockStructure(u);
                message = env.lastDetail();
            } else if (kind == DeclKind.AXIOM || kind == DeclKind.DEFN || kind == DeclKind.THM
                    || kind == DeclKind.OPAQUE) {
                v.plainUnits++;
                r = structural.checkConstant(decls.unitFirstCid(u));
                message = structural.lastMessage();
            } else {
                v.quots++;
                continue;
            }
            if (r == null) {
                continue;
            }
            if (r.isReject()) {
                v.rejectUnit = u;
                v.reject = r;
                v.rejectMessage = message;
                return v;
            }
            if (v.decline == null) {
                v.declineUnit = u;
                v.decline = r;
                v.declineMessage = message;
            }
        }
        return v;
    }
}

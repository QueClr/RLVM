package rlvm.ref;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import rlvm.api.Budget;
import rlvm.api.EngineConfig;
import rlvm.api.EngineFactory;
import rlvm.api.Outcome;
import rlvm.api.Reason;
import rlvm.api.Session;

/**
 * The switch {@code -X levels=complete|official}: how {@code is_def_eq} compares universe levels
 * (sorts and the level lists of constants). The declarations are those of the arena's corner cases
 * {@code imax-right-successor} and {@code imax-nested-absorption} (outcome "either": the official
 * kernel rejects them, a complete comparison accepts them) plus equations both relations decide.
 */
class LevelsTest {

    private static final String[] ONLY_COMPLETE = {"imaxRightOne", "imaxRightSucc", "imaxAbsorbRight", "imaxAbsorbLeft",
        "constLevels"};
    private static final String[] BOTH = {"maxComm", "maxAssoc", "maxIdem", "maxAbsorb", "imaxZero", "imaxSame",
        "constLevelsComm"};
    private static final String[] NEITHER = {"wrongLevel", "wrongConstLevel"};

    private Fixture fx;

    @BeforeEach
    void setUp() {
        fx = Fixture.standard(f -> {
            // corner-cases/imax-right-successor
            f.def("imaxRightOne", "u", "(sort (succ (max u 1)))", "(sort (imax u 1))", Fixture.OPAQUE_HINT);
            f.def("imaxRightSucc", "u v", "(sort (succ (max u (succ v))))", "(sort (imax u (succ v)))",
                    Fixture.OPAQUE_HINT);
            // corner-cases/imax-nested-absorption
            f.def("imaxAbsorbRight", "u v", "(sort (succ (imax (imax u v) v)))", "(sort (imax u v))", Fixture.OPAQUE_HINT);
            f.def("imaxAbsorbLeft", "u v", "(sort (succ (imax v (imax u v))))", "(sort (imax u v))", Fixture.OPAQUE_HINT);
            // what the official normal form decides as well (tutorial: levelMaxComm, ...)
            f.def("maxComm", "u v", "(sort (succ (max u v)))", "(sort (max v u))", Fixture.OPAQUE_HINT);
            f.def("maxAssoc", "u v w", "(sort (succ (max (max u v) w)))", "(sort (max u (max v w)))", Fixture.OPAQUE_HINT);
            f.def("maxIdem", "u", "(sort (succ (max u u)))", "(sort u)", Fixture.OPAQUE_HINT);
            f.def("maxAbsorb", "u v", "(sort (succ (max u (max u v))))", "(sort (max u v))", Fixture.OPAQUE_HINT);
            f.def("imaxZero", "u", "(sort (succ (imax u 0)))", "Prop", Fixture.OPAQUE_HINT);
            f.def("imaxSame", "u", "(sort (succ (imax u u)))", "(sort u)", Fixture.OPAQUE_HINT);
            // never equal
            f.def("wrongLevel", "u v", "(sort (succ (max u v)))", "(sort u)", Fixture.OPAQUE_HINT);
            // the level lists of two references to the same constant
            f.axiom("F", "u", "Type");
            f.axiom("fInh", "u", "(@ F u)");
            f.def("constLevels", "u", "(@ F (max u 1))", "(@ fInh (imax u 1))", Fixture.OPAQUE_HINT);
            f.def("constLevelsComm", "u v", "(@ F (max u v))", "(@ fInh (max v u))", Fixture.OPAQUE_HINT);
            f.def("wrongConstLevel", "u v", "(@ F (max u v))", "(@ fInh u)", Fixture.OPAQUE_HINT);
        });
    }

    private int check(EngineFactory factory, String name) {
        RefEngine engine = (RefEngine) factory.create(fx.store, EngineConfig.defaults());
        Session s = engine.newSession(fx.ctx);
        try {
            int out = s.check(fx.cid(name), Budget.cold(1L << 30));
            if (out == Outcome.REJECT) {
                assertEquals(Reason.DECL_TYPE_MISMATCH, s.lastReason(), () -> name + ": " + s.lastMessage());
            }
            return out;
        } finally {
            s.close();
            engine.close();
        }
    }

    @Test
    void theCompleteRelationIsTheDefault() {
        RefOptions defaults = RefOptions.fromSystemProperties();
        assertFalse(defaults.officialLevels);
        assertFalse(((RefEngine) RefEngine.FACTORY.create(fx.store, EngineConfig.defaults())).options().officialLevels);
        for (String name : ONLY_COMPLETE) {
            assertEquals(Outcome.ACCEPT, check(RefEngine.FACTORY, name), name);
        }
        for (String name : BOTH) {
            assertEquals(Outcome.ACCEPT, check(RefEngine.FACTORY, name), name);
        }
        for (String name : NEITHER) {
            assertEquals(Outcome.REJECT, check(RefEngine.FACTORY, name), name);
        }
    }

    @Test
    void completeAcceptsTheEquationsThatHoldForEveryAssignment() {
        EngineFactory complete = RefEngine.factory(RefOptions.fromSystemProperties().withOfficialLevels(false));
        for (String name : ONLY_COMPLETE) {
            assertEquals(Outcome.ACCEPT, check(complete, name), name);
        }
        for (String name : BOTH) {
            assertEquals(Outcome.ACCEPT, check(complete, name), name);
        }
        for (String name : NEITHER) {
            assertEquals(Outcome.REJECT, check(complete, name), name);
        }
    }

    @Test
    void officialRejectsWhatTheOfficialNormalFormDoesNotIdentify() {
        EngineFactory official = RefEngine.factory(RefOptions.fromSystemProperties().withOfficialLevels(true));
        for (String name : ONLY_COMPLETE) {
            assertEquals(Outcome.REJECT, check(official, name), name);
        }
        for (String name : BOTH) {
            assertEquals(Outcome.ACCEPT, check(official, name), name);
        }
        for (String name : NEITHER) {
            assertEquals(Outcome.REJECT, check(official, name), name);
        }
    }

    @Test
    void theKernelInterfaceFollowsTheSwitch() {
        RefKernel complete = new RefKernel(fx.ctx, EngineConfig.defaults(),
                RefOptions.fromSystemProperties().withOfficialLevels(false));
        RefKernel official = new RefKernel(fx.ctx, EngineConfig.defaults(),
                RefOptions.fromSystemProperties().withOfficialLevels(true));
        String[][] pairs = {
            {"(sort (imax u 1))", "(sort (max u 1))"},
            {"(sort (imax u v))", "(sort (imax (imax u v) v))"},
            {"(@ F (imax u 1))", "(@ F (max u 1))"},
            {"(@ fInh (imax u v))", "(@ fInh (imax v (imax u v)))"},
        };
        for (String[] p : pairs) {
            complete.begin(Integer.MAX_VALUE, fx.names("u v"));
            assertTrue(complete.isDefEq(fx.t(p[0]), fx.t(p[1])), p[0] + " =?= " + p[1]);
            official.begin(Integer.MAX_VALUE, fx.names("u v"));
            assertFalse(official.isDefEq(fx.t(p[0]), fx.t(p[1])), p[0] + " =?= " + p[1]);
        }
        String[][] same = {
            {"(sort (max u v))", "(sort (max v u))"},
            {"(sort (imax u 0))", "Prop"},
            {"(@ F (max u (max u v)))", "(@ F (max u v))"},
            {"(sort (succ u))", "(sort (succ u))"},
        };
        for (String[] p : same) {
            complete.begin(Integer.MAX_VALUE, fx.names("u v"));
            assertTrue(complete.isDefEq(fx.t(p[0]), fx.t(p[1])), p[0] + " =?= " + p[1]);
            official.begin(Integer.MAX_VALUE, fx.names("u v"));
            assertTrue(official.isDefEq(fx.t(p[0]), fx.t(p[1])), p[0] + " =?= " + p[1]);
        }
    }

    @Test
    void theLauncherSettingReachesTheEngineThroughTheProvider() {
        RefEngine.Provider provider = new RefEngine.Provider();
        assertTrue(provider.optionKeys().contains("levels"));
        assertTrue(provider.optionKeys().contains("ref.imports"));
        RefEngine official = (RefEngine) provider.factory(Map.of("levels", "official")).create(fx.store,
                EngineConfig.defaults());
        assertTrue(official.options().officialLevels);
        RefEngine complete = (RefEngine) provider.factory(Map.of("levels", "complete")).create(fx.store,
                EngineConfig.defaults());
        assertFalse(complete.options().officialLevels);
        // no setting, and settings of other engines: the default
        assertFalse(((RefEngine) provider.factory(Map.of()).create(fx.store, EngineConfig.defaults())).options()
                .officialLevels);
        assertFalse(((RefEngine) provider.factory(Map.of("machine.tier", "k1")).create(fx.store, EngineConfig.defaults()))
                .options().officialLevels);
        assertEquals(Outcome.REJECT, check(provider.factory(Map.of("levels", "official")), "imaxRightOne"));
        assertEquals(Outcome.ACCEPT, check(provider.factory(Map.of("levels", "complete")), "imaxRightOne"));
        assertEquals(Outcome.ACCEPT, check(provider.factory(Map.of()), "imaxRightOne"));
        // a value that is not understood is refused before anything runs
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> provider.factory(Map.of("levels", "lean4lean")));
        assertTrue(e.getMessage().contains("levels=lean4lean"), e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> provider.factory(Map.of("ref.imports", "maybe")));
        // strict engines (re-checks, the environment pass) follow the switch as well
        RefEngine strict = (RefEngine) provider.factory(Map.of("levels", "official")).create(fx.store,
                EngineConfig.defaults().withStrict(true));
        Session s = strict.newSession(fx.ctx);
        assertEquals(Outcome.REJECT, s.check(fx.cid("imaxAbsorbLeft"), Budget.cold(1L << 30)));
        assertEquals(Outcome.ACCEPT, s.check(fx.cid("maxComm"), Budget.cold(1L << 30)), () -> s.lastMessage());
        s.close();
        strict.close();
    }
}

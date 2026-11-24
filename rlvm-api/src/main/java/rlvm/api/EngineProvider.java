package rlvm.api;

import java.util.Map;
import java.util.Set;

/**
 * Service-provider interface through which an engine module makes itself known
 * ({@code META-INF/services/rlvm.api.EngineProvider}): the wiring module then needs no compile-time
 * dependency on any engine. Implementations have a public no-argument constructor and no state.
 *
 * <p><b>Engine settings.</b> An engine that has settings of its own names their keys in
 * {@link #optionKeys()} and receives them through {@link #factory(Map)}: {@code rlvm -X KEY=VALUE}
 * with a declared key reaches the engine without a change in the launcher, the wiring or the driver.
 * A key nobody declares is a usage error (exit status 64) and so is a value the engine refuses.
 */
public interface EngineProvider {

    /** Short lower-case name: "machine", "reference". */
    String name();

    /** The non-reference provider with the highest priority is the primary engine by default. */
    int priority();

    /**
     * True for the reference kernel: the engine used for the environment pass, for re-checks and as
     * the differential oracle. Exactly one provider on the class path returns true.
     */
    boolean isReference();

    /** The engine with its default settings. */
    EngineFactory factory();

    /**
     * The {@code -X} keys this engine understands, exactly as typed on the command line (for example
     * {@code "levels"}, or {@code "machine.tier"}). The wiring accepts {@code -X KEY[=VALUE]} for
     * every key some provider on the class path declares and hands it to {@link #factory(Map)} of the
     * engines of the run. Keys of the wiring itself ({@code engine}, {@code load}, {@code order},
     * {@code session-mib}, ...; the list is in {@code rlvm-main/README.md}) cannot be declared: the
     * wiring's meaning wins. Default: no settings.
     */
    default Set<String> optionKeys() {
        return Set.of();
    }

    /**
     * The engine configured by {@code -X} settings.
     *
     * @param options every {@code -X KEY=VALUE} of the command line whose key some provider declares
     *     (not only this provider's: ignore keys you do not know); a bare {@code -X KEY} has the value
     *     {@code "true"}; never null, possibly empty; unmodifiable
     * @return the factory; the default implementation ignores the settings and returns {@link #factory()}
     * @throws IllegalArgumentException a value of one of this provider's keys cannot be parsed; the
     *     message is shown to the user and the run ends with the usage status before anything is read
     */
    default EngineFactory factory(Map<String, String> options) {
        return factory();
    }

    /**
     * Run settings this engine prefers <b>when it is the primary engine of the run</b>: keys of the
     * wiring or of the driver exactly as typed after {@code -X} (for example {@code "session-mib"} ->
     * {@code "4"}: the session reset threshold that suits the engine's warm sessions). The wiring
     * applies them as if they had been typed first; an explicit {@code -X KEY=VALUE} of the command
     * line always wins, and so the user's setting is never changed. They are settings of the run,
     * not of the engine: the engine's own keys ({@link #optionKeys()}) and {@code engine},
     * {@code engine.*} cannot be defaulted here (the wiring refuses them: a broken provider is an
     * internal error, never a verdict). Not consulted for the reference kernel when it only
     * re-checks, nor for an engine that is merely on the class path.
     *
     * @param options the engine settings of the run, as for {@link #factory(Map)} (the preferred
     *     defaults may depend on them, e.g. on a switch that turns warm sessions on); never null
     * @return key -> value, never null; default: none
     */
    default Map<String, String> runDefaults(Map<String, String> options) {
        return Map.of();
    }
}

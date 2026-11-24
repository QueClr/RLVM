package rlvm.level;

/**
 * What the level algebra needs to know about universe parameter names. Names are opaque {@code int}
 * ids owned by the name store; the level store only stores them.
 *
 * <p>Requirements on the ids handed to {@link LevelStore#param(int)}: two ids are equal iff they
 * denote the same Lean name (names must be interned structurally by the caller; in hand-written
 * export files two export name ids may denote the same name).
 *
 * <p>Implementations must be safe for concurrent calls: one oracle is shared by a frozen base store
 * and all its overlays.
 */
public interface NameOracle {

    /**
     * Total order on names. To reproduce the structure of the official normal form
     * ({@link LevelStore#normalize(int)}) exactly this must be Lean's {@code name::cmp}
     * ({@code refs/lean4/src/util/name.cpp:126}): compare the components from the root; a numeric
     * component is smaller than a string component; strings by byte order; a proper prefix is
     * smaller. Must return 0 only for equal ids.
     *
     * <p>Only called while normalising a {@code max} that has two different parameters among its
     * arguments (results are memoised per level), never by the complete comparison
     * ({@link LevelStore#isEquiv(int, int)}), whose answers do not depend on this order.
     */
    int compareNames(int nameIdA, int nameIdB);

    /** Appends a printable form of the name to {@code sb} (diagnostics only). */
    void appendName(StringBuilder sb, int nameId);

    /** Orders names by id and prints them as {@code p<id>}; for tests and tools without a name store. */
    NameOracle BY_ID = new ById();

    /** See {@link #BY_ID}. */
    final class ById implements NameOracle {
        private ById() {}

        @Override
        public int compareNames(int nameIdA, int nameIdB) {
            return Integer.compare(nameIdA, nameIdB);
        }

        @Override
        public void appendName(StringBuilder sb, int nameId) {
            sb.append('p').append(nameId);
        }
    }
}

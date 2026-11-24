package rlvm.api.bind;

import rlvm.level.NameOracle;
import rlvm.store.NameTable;

/**
 * {@code rlvm.level.NameOracle} implementations over the store's {@link NameTable}: Lean's
 * {@code name::cmp} (needed for the official level normal form) and name printing.
 */
public final class NameOracles {
    private NameOracles() {}

    /** The oracle of a name table. Safe for concurrent calls as far as the name table's readers are. */
    public static NameOracle of(NameTable names) {
        if (names == null) {
            throw new NullPointerException("names");
        }
        return new TableOracle(names);
    }

    /**
     * An oracle whose name table is supplied later ({@link Late#bind}): the level store must exist
     * before the store (the store's list store lives in it), the name table is created by the store.
     * Until bound it orders names by id, which is only reached when a {@code max} of two different
     * parameters is normalised - never while records are merely interned by the loader.
     */
    public static Late late() {
        return new Late();
    }

    private static final class TableOracle implements NameOracle {
        private final NameTable names;

        TableOracle(NameTable names) {
            this.names = names;
        }

        @Override
        public int compareNames(int nameIdA, int nameIdB) {
            return nameIdA == nameIdB ? 0 : names.compare(nameIdA, nameIdB);
        }

        @Override
        public void appendName(StringBuilder sb, int nameId) {
            names.appendTo(sb, nameId);
        }
    }

    /** See {@link NameOracles#late()}. */
    public static final class Late implements NameOracle {
        private volatile NameOracle target;
        private volatile boolean usedUnbound;

        Late() {}

        /** Supplies the name table. May be called once. */
        public void bind(NameTable table) {
            bind(NameOracles.of(table));
        }

        /** Supplies the order directly (tools and tests without a name table). May be called once. */
        public void bind(NameOracle oracle) {
            if (oracle == null) {
                throw new NullPointerException("oracle");
            }
            if (target != null) {
                throw new IllegalStateException("name oracle is already bound");
            }
            target = oracle;
        }

        public boolean isBound() {
            return target != null;
        }

        /**
         * True if a comparison was answered before the table was bound: normal forms computed then
         * may be ordered differently from the official ones (sound, but a wiring error).
         */
        public boolean usedUnbound() {
            return usedUnbound;
        }

        @Override
        public int compareNames(int nameIdA, int nameIdB) {
            if (nameIdA == nameIdB) {
                return 0;
            }
            NameOracle t = target;
            if (t == null) {
                usedUnbound = true;
                return Integer.compare(nameIdA, nameIdB);
            }
            return t.compareNames(nameIdA, nameIdB);
        }

        @Override
        public void appendName(StringBuilder sb, int nameId) {
            NameOracle t = target;
            if (t == null) {
                sb.append('p').append(nameId);
            } else {
                t.appendName(sb, nameId);
            }
        }
    }
}

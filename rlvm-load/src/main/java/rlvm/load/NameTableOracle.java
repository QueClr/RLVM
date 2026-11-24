package rlvm.load;

import rlvm.level.NameOracle;
import rlvm.store.NameTable;

/** {@link NameOracle} over the store's {@link NameTable}: Lean's {@code name::cmp} and name printing. */
public final class NameTableOracle implements NameOracle {
    private final NameTable names;

    public NameTableOracle(NameTable names) {
        this.names = names;
    }

    @Override
    public int compareNames(int nameIdA, int nameIdB) {
        return names.compare(nameIdA, nameIdB);
    }

    @Override
    public void appendName(StringBuilder sb, int nameId) {
        names.appendTo(sb, nameId);
    }
}

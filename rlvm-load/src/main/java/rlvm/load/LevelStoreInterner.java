package rlvm.load;

import rlvm.level.LevelLimitException;
import rlvm.level.LevelStore;

/**
 * {@link LevelInterner} over the run's base {@link LevelStore}: the raw constructors (an export level
 * is stored exactly as written) and its list store.
 *
 * <p>A level deeper than the official kernel's limit (2^24 - 1) is declined, not rejected: the
 * official reader only prints a panic message there, and what the official checker then answers has
 * not been established.
 */
public final class LevelStoreInterner implements LevelInterner {
    private final LevelStore store;
    private final boolean freezeOnPublish;

    /** @param freezeOnPublish whether {@link #publish()} freezes the store (batch mode: yes, unless the caller freezes it itself) */
    public LevelStoreInterner(LevelStore store, boolean freezeOnPublish) {
        if (store.isFrozen() || store.isOverlay()) {
            throw new IllegalArgumentException("the loader needs the unfrozen base level store");
        }
        this.store = store;
        this.freezeOnPublish = freezeOnPublish;
    }

    public LevelStoreInterner(LevelStore store) {
        this(store, true);
    }

    public LevelStore store() {
        return store;
    }

    @Override
    public int zero() {
        return LevelStore.ZERO;
    }

    @Override
    public int succ(int level) {
        try {
            return store.succ(level);
        } catch (LevelLimitException e) {
            throw new UnsupportedExportException("universe level too deep: " + e.getMessage());
        }
    }

    @Override
    public int max(int a, int b) {
        try {
            return store.max(a, b);
        } catch (LevelLimitException e) {
            throw new UnsupportedExportException("universe level too deep: " + e.getMessage());
        }
    }

    @Override
    public int imax(int a, int b) {
        try {
            return store.imax(a, b);
        } catch (LevelLimitException e) {
            throw new UnsupportedExportException("universe level too deep: " + e.getMessage());
        }
    }

    @Override
    public int param(int nameId) {
        return store.param(nameId);
    }

    @Override
    public int internList(int[] buf, int from, int len) {
        return store.internList(buf, from, len);
    }

    @Override
    public boolean hasDuplicates(int nameList) {
        return store.listHasDuplicates(nameList);
    }

    @Override
    public void publish() {
        if (freezeOnPublish && !store.isFrozen()) {
            store.freeze();
        }
    }

    @Override
    public LevelStore structure() {
        return store;
    }
}

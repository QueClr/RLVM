package rlvm.load;

import rlvm.level.IntListStore;
import rlvm.level.LevelStore;
import rlvm.store.IntLists;
import rlvm.store.LevelInfo;

/**
 * The two adapters {@code rlvm.store.Store} needs from the level store of the run (the store itself
 * depends on nothing): its list store as {@link IntLists} and a structural view of its levels as
 * {@link LevelInfo}. For the loader thread, over the unfrozen base {@link LevelStore}; used by this
 * module's tools and tests. ({@code rlvm.api.bind} has the run's own bindings, including the
 * per-worker ones.)
 */
public final class LevelStoreViews {
    private LevelStoreViews() {}

    public static IntLists lists(LevelStore levels) {
        return new Lists(levels);
    }

    public static LevelInfo info(LevelStore levels) {
        return new Info(levels);
    }

    private static final class Lists implements IntLists {
        private final LevelStore levels;
        private final IntListStore lists;

        Lists(LevelStore levels) {
            this.levels = levels;
            this.lists = levels.lists();
        }

        @Override
        public int intern(int[] buf, int from, int len) {
            return levels.internList(buf, from, len);
        }

        @Override
        public int length(int list) {
            return lists.length(list);
        }

        @Override
        public int get(int list, int index) {
            return lists.get(list, index);
        }

        @Override
        public void copyTo(int list, int[] dst, int dstOff) {
            lists.copyTo(list, dst, dstOff);
        }
    }

    private static final class Info implements LevelInfo {
        private final LevelStore levels;

        Info(LevelStore levels) {
            this.levels = levels;
        }

        @Override
        public boolean hasParam(int level) {
            return levels.hasParam(level);
        }

        @Override
        public int paramName(int level) {
            return levels.kind(level) == LevelStore.KIND_PARAM ? levels.paramName(level) : -1;
        }

        @Override
        public int lhs(int level) {
            switch (levels.kind(level)) {
                case LevelStore.KIND_SUCC:
                    return levels.succOf(level);
                case LevelStore.KIND_MAX:
                case LevelStore.KIND_IMAX:
                    return levels.lhs(level);
                default:
                    return 0;
            }
        }

        @Override
        public int rhs(int level) {
            int k = levels.kind(level);
            return k == LevelStore.KIND_MAX || k == LevelStore.KIND_IMAX ? levels.rhs(level) : 0;
        }

        @Override
        public int instantiate(int level, int paramNames, int levelList) {
            return levels.instantiate(level, paramNames, levelList);
        }

        @Override
        public int instantiateList(int list, int paramNames, int levelList) {
            int subst = levels.mkSubst(paramNames, levelList);
            if (subst < 0) {
                throw new IllegalArgumentException("wrong number of universe levels");
            }
            return levels.instantiateList(list, subst);
        }
    }
}

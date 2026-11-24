package rlvm.store;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * A tiny hash-consed level table for tests: the fake behind {@link LevelInfo}. Level 1 is zero.
 * {@link #instantiate} substitutes with the plain constructors (no simplification): the store only
 * needs "some function from levels to levels" to test its own plumbing.
 */
final class TestLevels implements LevelInfo {
    static final int ZERO = 1;
    private static final int K_ZERO = 0;
    private static final int K_SUCC = 1;
    private static final int K_MAX = 2;
    private static final int K_IMAX = 3;
    private static final int K_PARAM = 4;

    private final IntLists lists;
    private final List<int[]> nodes = new ArrayList<>();
    private final Map<Long, Integer> index = new HashMap<>();
    int instantiateCalls;

    TestLevels(IntLists lists) {
        this.lists = lists;
        nodes.add(null);
        nodes.add(new int[] {K_ZERO, 0, 0});
    }

    private int mk(int kind, int x, int y) {
        long key = ((long) kind << 58) ^ ((long) x << 29) ^ y;
        Integer id = index.get(key);
        if (id != null) {
            return id;
        }
        nodes.add(new int[] {kind, x, y});
        index.put(key, nodes.size() - 1);
        return nodes.size() - 1;
    }

    int succ(int l) {
        return mk(K_SUCC, l, 0);
    }

    int max(int a, int b) {
        return mk(K_MAX, a, b);
    }

    int imax(int a, int b) {
        return mk(K_IMAX, a, b);
    }

    int param(int name) {
        return mk(K_PARAM, name, 0);
    }

    int size() {
        return nodes.size();
    }

    /** Exact parameter names of a level (naive recursion). */
    TreeSet<Integer> params(int l) {
        TreeSet<Integer> out = new TreeSet<>();
        collect(l, out);
        return out;
    }

    private void collect(int l, TreeSet<Integer> out) {
        int[] n = nodes.get(l);
        switch (n[0]) {
            case K_PARAM:
                out.add(n[1]);
                break;
            case K_SUCC:
                collect(n[1], out);
                break;
            case K_MAX:
            case K_IMAX:
                collect(n[1], out);
                collect(n[2], out);
                break;
            default:
                break;
        }
    }

    TreeSet<Integer> paramsOfList(int list) {
        TreeSet<Integer> out = new TreeSet<>();
        for (int i = 0; i < lists.length(list); i++) {
            collect(lists.get(list, i), out);
        }
        return out;
    }

    /** Levels with an id below this are "shared" (the fake of a frozen base with worker overlays on top). */
    int sharedBelow = Integer.MAX_VALUE;

    @Override
    public boolean isSharedLevel(int level) {
        return level > 0 && level < sharedBelow && level < nodes.size();
    }

    @Override
    public boolean hasParam(int level) {
        int[] n = nodes.get(level);
        switch (n[0]) {
            case K_PARAM:
                return true;
            case K_SUCC:
                return hasParam(n[1]);
            case K_MAX:
            case K_IMAX:
                return hasParam(n[1]) || hasParam(n[2]);
            default:
                return false;
        }
    }

    @Override
    public int paramName(int level) {
        int[] n = nodes.get(level);
        return n[0] == K_PARAM ? n[1] : -1;
    }

    @Override
    public int lhs(int level) {
        int[] n = nodes.get(level);
        return n[0] == K_SUCC || n[0] == K_MAX || n[0] == K_IMAX ? n[1] : 0;
    }

    @Override
    public int rhs(int level) {
        int[] n = nodes.get(level);
        return n[0] == K_MAX || n[0] == K_IMAX ? n[2] : 0;
    }

    @Override
    public int instantiate(int level, int paramNames, int levels) {
        instantiateCalls++;
        int[] n = nodes.get(level);
        switch (n[0]) {
            case K_PARAM:
                for (int i = 0; i < lists.length(paramNames); i++) {
                    if (lists.get(paramNames, i) == n[1]) {
                        return lists.get(levels, i);
                    }
                }
                return level;
            case K_SUCC:
                return succ(instantiate(n[1], paramNames, levels));
            case K_MAX:
                return max(instantiate(n[1], paramNames, levels), instantiate(n[2], paramNames, levels));
            case K_IMAX:
                return imax(instantiate(n[1], paramNames, levels), instantiate(n[2], paramNames, levels));
            default:
                return level;
        }
    }

    @Override
    public int instantiateList(int levelList, int paramNames, int levels) {
        int n = lists.length(levelList);
        int[] out = new int[n];
        for (int i = 0; i < n; i++) {
            out[i] = instantiate(lists.get(levelList, i), paramNames, levels);
        }
        return lists.intern(out, 0, n);
    }
}

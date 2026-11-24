package rlvm.store;

/** Set operations on {@link IntLists} ids, written on top of {@code intern / length / get}. */
public final class IntListOps {
    private IntListOps() {}

    /** Union of two ascending duplicate-free lists; interns the result in {@code lists}. */
    public static int unionSorted(IntLists lists, int setA, int setB) {
        if (setA == setB || setB == IntLists.EMPTY) {
            return setA;
        }
        if (setA == IntLists.EMPTY) {
            return setB;
        }
        int na = lists.length(setA);
        int nb = lists.length(setB);
        int[] out = new int[na + nb];
        int i = 0;
        int j = 0;
        int n = 0;
        while (i < na && j < nb) {
            int x = lists.get(setA, i);
            int y = lists.get(setB, j);
            if (x == y) {
                out[n++] = x;
                i++;
                j++;
            } else if (x < y) {
                out[n++] = x;
                i++;
            } else {
                out[n++] = y;
                j++;
            }
        }
        while (i < na) {
            out[n++] = lists.get(setA, i++);
        }
        while (j < nb) {
            out[n++] = lists.get(setB, j++);
        }
        if (n == na) {
            return setA;
        }
        if (n == nb) {
            return setB;
        }
        return lists.intern(out, 0, n);
    }

    /** True if every element of {@code sub} occurs in {@code list} (which need not be sorted). */
    public static boolean subsetOf(IntLists lists, int sub, int list) {
        if (sub == IntLists.EMPTY || sub == list) {
            return true;
        }
        int ns = lists.length(sub);
        int nl = lists.length(list);
        for (int i = 0; i < ns; i++) {
            int x = lists.get(sub, i);
            boolean found = false;
            for (int j = 0; j < nl; j++) {
                if (lists.get(list, j) == x) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                return false;
            }
        }
        return true;
    }

    /** Sorts {@code buf[0..n)} ascending, removes duplicates, and interns the result. */
    public static int internSet(IntLists lists, int[] buf, int n) {
        if (n == 0) {
            return IntLists.EMPTY;
        }
        if (n <= 16) {
            for (int i = 1; i < n; i++) {
                int v = buf[i];
                int j = i - 1;
                while (j >= 0 && buf[j] > v) {
                    buf[j + 1] = buf[j];
                    j--;
                }
                buf[j + 1] = v;
            }
        } else {
            java.util.Arrays.sort(buf, 0, n);
        }
        int m = 1;
        for (int i = 1; i < n; i++) {
            if (buf[i] != buf[m - 1]) {
                buf[m++] = buf[i];
            }
        }
        return lists.intern(buf, 0, m);
    }
}

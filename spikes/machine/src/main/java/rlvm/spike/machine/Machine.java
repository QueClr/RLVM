package rlvm.spike.machine;

/** The machine's value stack and the registers a promoted (Truffle) code object reads on entry. */
public final class Machine {
    private Machine() {
    }

    public static int[] S;

    public static void init() {
        S = new int[1 << 20];
    }
    public static int fp;
    public static int k;
}

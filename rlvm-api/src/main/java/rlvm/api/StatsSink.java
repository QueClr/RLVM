package rlvm.api;

/**
 * Receiver of statistics: flat, named values that the driver writes as one JSON object
 * ({@code --stats FILE}). Keys are dotted paths, e.g. {@code machine.instr.APPLY.count}.
 */
public interface StatsSink {

    void put(String key, long value);

    void put(String key, double value);

    void put(String key, String value);

    /** A table of rows, e.g. the hottest constants: {@code columns} names the fields of each row. */
    void table(String key, String[] columns, long[][] rows, String[] rowLabels);
}

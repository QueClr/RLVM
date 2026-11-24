package rlvm.store;

/** Expression node kinds: bits 0..3 of the node header ({@link Node}). */
public final class Kind {
    private Kind() {}

    /** Not a node (the header of id 0 and of unwritten slots). */
    public static final int NONE = 0;
    /** {@code a} = de Bruijn index. */
    public static final int BVAR = 1;
    /** {@code a} = level id. */
    public static final int SORT = 2;
    /** {@code a} = constant id (or {@code ~nameId} when unresolved), {@code b} = level-list id, {@code c} = name id. */
    public static final int CONST = 3;
    /** {@code a} = function, {@code b} = argument, {@code c} = head of the application spine. */
    public static final int APP = 4;
    /** {@code a} = binder domain, {@code b} = body. Binder name and binder info are not stored. */
    public static final int LAM = 5;
    /** {@code a} = binder domain, {@code b} = body. */
    public static final int PI = 6;
    /** {@code a} = type, {@code b} = value, {@code c} = body. The {@code nondep} flag is not stored. */
    public static final int LET = 7;
    /** {@code a} = structure expression, {@code b} = field index, {@code c} = constant id of the structure type (or {@code ~nameId}). */
    public static final int PROJ = 8;
    /** Value below 2^64: {@code a} = low 32 bits, {@code b} = high 32 bits. With {@link Node#F_NAT_BIG}: {@code a} = {@link LitPool} index. */
    public static final int NATLIT = 9;
    /** {@code a} = {@link LitPool} string index, {@code b} = number of Unicode scalar values. */
    public static final int STRLIT = 10;
    /** Overlay only (reference kernel, admission): {@code a} = serial number, {@code b} = type term. */
    public static final int FVAR = 11;
}

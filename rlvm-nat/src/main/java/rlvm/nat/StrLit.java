package rlvm.nat;

/**
 * What the kernel needs to know about string literals and characters (Lean 4 v4.36).
 *
 * <h2>Constructor form of a string literal</h2>
 * {@code string_lit_to_constructor} ({@code kernel/inductive.cpp:1356-1368}) maps the literal with the scalar
 * values {@code c1 .. cn} (its UTF-8 decoding, one element per scalar value, <em>not</em> per byte) to
 * <pre>
 *   App (Const String.ofList [])
 *       (cons c1 (cons c2 ( ... (cons cn nil))))
 *   cons c l = App (App (App (Const List.cons [Level.zero]) (Const Char [])) (App (Const Char.ofNat []) (Lit (natVal c)))) l
 *   nil      = App (Const List.nil [Level.zero]) (Const Char [])
 * </pre>
 * The head is the <em>definition</em> {@code String.ofList : List Char -> String} (in v4.36 {@code String}
 * is a structure over {@code ByteArray} with constructor {@code String.ofByteArray}; books and older
 * kernels say {@code String.mk}). The kernel uses this form in three places and always normalises it with
 * {@code whnf} first: a string literal as the major premise of a recursor, as the structure of a
 * projection, and in def-eq when one side is a string literal and the other an application of
 * {@code String.ofList} to exactly one argument. Two string literals are compared by value (their bytes).
 * The kernel never checks that these constants exist or have the expected types; see the module README for
 * what RLVM should verify before relying on the expansion.
 *
 * <p>To build the list from its tail without a temporary array:
 * <pre>
 *   int list = nil;
 *   for (int pos = to; pos &gt; from; ) {
 *       int start = Utf8.startOfPrevious(bytes, from, pos);
 *       int c = Utf8.codePointAt(bytes, start, pos);          // &gt;= 0 on validated text
 *       list = cons(c, list);
 *       pos = start;
 *   }
 * </pre>
 *
 * <h2>Characters</h2>
 * {@code Char} is a {@code UInt32} with a proof of {@code Nat.isValidChar}: below 0xD800, or above 0xDFFF and
 * below 0x110000. {@code Char.ofNat n} is that character if {@code n} is valid and the character 0 otherwise.
 */
public final class StrLit {
    private StrLit() {}

    /** Name of the type of string literals. */
    public static final String STRING = "String";
    /** Head of the constructor form (a definition, no universe levels). */
    public static final String STRING_OF_LIST = "String.ofList";
    /** List constructors, instantiated with the universe level zero and applied to {@link #CHAR}. */
    public static final String LIST_CONS = "List.cons";
    public static final String LIST_NIL = "List.nil";
    public static final String CHAR = "Char";
    /** Applied to a Nat literal for each scalar value (no universe levels). */
    public static final String CHAR_OF_NAT = "Char.ofNat";

    /** {@code Nat.isValidChar} on a Nat handle: a Unicode scalar value. Big and error handles are not valid. */
    public static boolean isValidChar(long natHandle) {
        return (natHandle >= 0 && natHandle < 0xD800) || (natHandle > 0xDFFF && natHandle < 0x110000);
    }

    /**
     * The numeric value of {@code Char.ofNat n} for a Nat <em>value</em> handle {@code n}: {@code n} itself if
     * it is a valid character, 0 otherwise (also for every number of 2^63 and more).
     */
    public static int charOfNat(long natHandle) {
        if (Nat.isError(natHandle)) {
            throw new IllegalArgumentException("operand is not a number");
        }
        return isValidChar(natHandle) ? (int) natHandle : 0;
    }

    /**
     * Number of characters of a literal (the length of its {@code List Char}), or a negative number if the
     * bytes are not well-formed UTF-8; same contract as {@link Utf8#validate}.
     */
    public static int length(byte[] b, int from, int to) {
        return Utf8.validate(b, from, to);
    }

    /**
     * The scalar values of a literal, in order; same contract as {@link Utf8#decode}. Element {@code i} is the
     * Nat literal under the {@code i}-th {@code Char.ofNat} of the constructor form.
     */
    public static int chars(byte[] b, int from, int to, int[] dst, int dstOff) {
        return Utf8.decode(b, from, to, dst, dstOff);
    }

    /** Equality of two literals (validated UTF-8 ranges): the kernel's literal-vs-literal test. */
    public static boolean equal(byte[] a, int aFrom, int aTo, byte[] b, int bFrom, int bTo) {
        return Utf8.equal(a, aFrom, aTo, b, bFrom, bTo);
    }
}

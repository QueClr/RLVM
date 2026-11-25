package rlvm.store;

import java.util.Arrays;

/**
 * Facts about shared terms that any worker may record and any worker may use: one byte per node of
 * the export region, written with plain (racy, idempotent) stores. A lost or late write only loses a
 * cache entry; byte array elements are written independently (no word tearing).
 *
 * <p>Bit {@link #CHECKED}: the closed shared term passed type checking in check mode. This is a fact
 * about the term alone, because everything the check depended on is covered by the node summaries:
 * it may be used while checking a unit of rank {@code r} with universe parameters {@code L} iff
 * {@code depMax(t) < r} and {@code lpSet(t)} is a subset of {@code L}, which holds for every term
 * reachable from the roots of a unit that passed {@code StructuralChecks}. It is the machine's
 * {@code PCHK} table (*agentic-note*: design-isa-document, 1.5). Never read in strict mode.
 *
 * <p>Sound under the optimistic environment: a fact recorded while an earlier declaration was still
 * unchecked can only matter in a run whose verdict is already "not accepted".
 *
 * <p>The byte array is allocated at the first write (the reference engine never writes) and covers
 * the export region as it is then; it is extended when a fact about a later term is recorded. Two
 * racing extensions, or a write racing with an extension, can lose facts, never invent them.
 */
public final class SharedFacts {

    public static final int CHECKED = 1;
    /** Bits 1..7 are free for the machine's static per-term facts (*agentic-note*: design-isa-document). */
    public static final int MACHINE_MASK = 0xFE;

    private static final byte[] NONE = new byte[0];

    private final TermStore terms;
    private volatile byte[] facts = NONE;

    public SharedFacts(TermStore terms) {
        this.terms = terms;
    }

    public boolean isChecked(int sharedTerm) {
        return (get(sharedTerm) & CHECKED) != 0;
    }

    public void setChecked(int sharedTerm) {
        or(sharedTerm, CHECKED);
    }

    /** Raw byte of a shared term (0 for ids beyond the current array, and for ids outside the export region). */
    public int get(int sharedTerm) {
        byte[] f = facts;
        return sharedTerm > 0 && sharedTerm < f.length ? f[sharedTerm] & 0xFF : 0;
    }

    /** Ors {@code bits} into the byte of a shared term (racy, idempotent). Ignored outside the export region. */
    public void or(int sharedTerm, int bits) {
        if (sharedTerm <= 0 || sharedTerm >= Node.GEN_BASE) {
            return;
        }
        byte[] f = facts;
        if (sharedTerm >= f.length) {
            f = extend(sharedTerm);
        }
        f[sharedTerm] |= (byte) bits;
    }

    private synchronized byte[] extend(int id) {
        byte[] f = facts;
        if (id < f.length) {
            return f;
        }
        int want = Math.max(Math.max(terms.exportArray().length >>> 3, id + 1), f.length + (f.length >> 2));
        f = Arrays.copyOf(f, want);
        facts = f;
        return f;
    }

    public long bytes() {
        return facts.length;
    }
}

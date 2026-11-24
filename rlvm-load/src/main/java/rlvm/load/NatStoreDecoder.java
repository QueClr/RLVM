package rlvm.load;

import rlvm.nat.Nat;
import rlvm.nat.NatStore;

/**
 * {@link NatDecoder} over {@code rlvm-nat}: decimal digits to little-endian 64-bit limbs through a
 * private scratch {@link NatStore} (linear for ordinary literals, sub-quadratic for long ones). The
 * scratch store is emptied after every conversion.
 *
 * <p>A numeral beyond the store's hard limit (128 MiB, the official kernel's {@code LEAN_NAT_MAX_SIZE})
 * is declined: the official reader parses it, and the official kernel fails only if the literal is
 * ever type-checked.
 */
public final class NatStoreDecoder implements NatDecoder {
    private final NatStore scratch = NatStore.newBase();

    @Override
    public int decode(byte[] digits, int off, int len, Limbs dst) {
        NatStore s = scratch;
        int mark = s.mark();
        long h = Nat.parseDecimal(s, digits, off, off + len);
        if (!Nat.isBig(h) || Nat.bigId(h) == 0) {
            if (h >= 0) {
                // below 2^63: one limb (the loader never asks for these, but the contract allows it)
                dst.ensure(1);
                dst.words[0] = h;
                return h == 0 ? 0 : 1;
            }
            if (h == Nat.TOO_BIG) {
                throw new UnsupportedExportException("Nat literal of " + len + " digits exceeds the size limit");
            }
            throw new IllegalArgumentException("not a canonical decimal numeral");
        }
        int id = Nat.bigId(h);
        int n = s.limbCount(id);
        dst.ensure(n);
        long[] w = dst.words;
        for (int i = 0; i < n; i++) {
            w[i] = s.limb(id, i);
        }
        s.truncate(mark);
        return n;
    }
}

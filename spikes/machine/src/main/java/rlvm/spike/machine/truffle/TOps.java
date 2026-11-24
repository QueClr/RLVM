package rlvm.spike.machine.truffle;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

import rlvm.spike.machine.Generic;
import rlvm.spike.machine.Machine;
import rlvm.spike.machine.Vals;

/**
 * How promoted code reaches the static operation library. The operations themselves live in {@link Vals} and
 * {@link Generic} (one copy of the semantics); this class only decides where partial evaluation stops:
 * <ul>
 * <li>{@code inline = true}: the hash-consing probe ({@code Vals.findK}) is inlined into the compiled code object,
 * only the insert/grow slow path is a call;</li>
 * <li>{@code inline = false}: every operation is one call to the ahead-of-time compiled library
 * ("call-threaded" code: only dispatch and operand decoding are compiled away).</li>
 * </ul>
 */
final class TOps {
    private TOps() {
    }

    static int app(boolean inline, int f, int a) {
        if (inline) {
            int r = Vals.find3(Vals.K_APP, f, a);
            return r > 0 ? r : ins3(-1 - r, f, a);
        }
        return appB(f, a);
    }

    @TruffleBoundary(allowInlining = false)
    private static int ins3(int slot, int f, int a) {
        return Vals.ins3(slot, Vals.K_APP, f, a);
    }

    @TruffleBoundary(allowInlining = false)
    private static int appB(int f, int a) {
        return Vals.mkApp(f, a);
    }

    static int proj(boolean inline, int hdr, int s) {
        if (inline) {
            int r = Vals.find2(hdr, s);
            return r > 0 ? r : ins2(-1 - r, hdr, s);
        }
        return projB(hdr, s);
    }

    @TruffleBoundary(allowInlining = false)
    private static int ins2(int slot, int hdr, int s) {
        return Vals.ins2(slot, hdr, s);
    }

    @TruffleBoundary(allowInlining = false)
    private static int projB(int hdr, int s) {
        return Vals.mk2i(hdr, s);
    }

    static int clo(boolean inline, int hdr, int e, int[] S, int off, int n) {
        if (inline) {
            int r = Vals.findN(hdr, e, S, off, n);
            return r > 0 ? r : insN(-1 - r, hdr, e, S, off, n);
        }
        return cloB(hdr, e, S, off, n);
    }

    @TruffleBoundary(allowInlining = false)
    private static int insN(int slot, int hdr, int e, int[] S, int off, int n) {
        return Vals.insN(slot, hdr, e, S, off, n);
    }

    @TruffleBoundary(allowInlining = false)
    private static int cloB(int hdr, int e, int[] S, int off, int n) {
        return Vals.mkN(hdr, e, S, off, n);
    }

    /** Beta: always the complete generic procedure. */
    @TruffleBoundary(allowInlining = false)
    static int applyLam(int f, int x, int sp) {
        return Generic.applyLam(f, x, Machine.S, sp);
    }
}

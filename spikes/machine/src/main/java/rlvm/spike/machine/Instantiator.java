package rlvm.spike.machine;

/**
 * The one interface of experiment 1. The caller has pushed the k argument values at S[fp .. fp+k); the callee
 * writes the value of the domain of parameter i (i &lt; min(k, n)) to S[fp+n+i] and the result type to S[fp+2n],
 * where n is the telescope length of constant c, and may use S above fp+2n as scratch.
 */
public interface Instantiator {
    void inst(int c, int[] S, int fp, int k);

    /** Called once after the last spine (e.g. to wait for background compilations). */
    default void finish() {
    }

    /** Extra information for the result line. */
    default String info() {
        return "";
    }

    final class Null implements Instantiator {
        @Override
        public void inst(int c, int[] S, int fp, int k) {
        }
    }

    /** (a) generic interpretation. */
    final class GenericInst implements Instantiator {
        @Override
        public void inst(int c, int[] S, int fp, int k) {
            Generic.instantiate(c, S, fp, k);
        }

        @Override
        public String info() {
            return "betas " + Generic.betas + " lets " + Generic.lets;
        }
    }

    /** (b) code objects, plain stepper; elaboration happens at the first instantiation of a constant. */
    final class StepperInst implements Instantiator {
        @Override
        public void inst(int c, int[] S, int fp, int k) {
            int co = Elab.codeStart[c];
            if (co == 0) {
                co = Elab.elaborate(c);
            }
            Stepper.run(co, S, fp, k);
        }

        @Override
        public String info() {
            return Exp1.elabInfo();
        }
    }

    /** (b2) the same code objects, stepper written for the ahead-of-time compiler (probe expanded in place). */
    final class Stepper2Inst implements Instantiator {
        @Override
        public void inst(int c, int[] S, int fp, int k) {
            int co = Elab.codeStart[c];
            if (co == 0) {
                co = Elab.elaborate(c);
            }
            Stepper2.run(co, S, fp, k);
        }

        @Override
        public String info() {
            return Exp1.elabInfo();
        }
    }

    /** Elaboration only: what (b) pays before the first instruction is stepped. */
    final class ElabOnly implements Instantiator {
        @Override
        public void inst(int c, int[] S, int fp, int k) {
            if (Elab.codeStart[c] == 0) {
                Elab.elaborate(c);
            }
        }

        @Override
        public String info() {
            return Exp1.elabInfo();
        }
    }
}

package rlvm.machine;

import java.util.Arrays;

/**
 * Command-line entry of the M8 reports (ISA section 11; WORK-PACKAGES M8): the same code the slow test
 * classes run, without surefire, so that the heap and the corpus can be chosen freely. Started by
 * {@code rlvm-machine/src/test/resources/m8/report.sh}, which compiles a copy of the main tree first.
 * <pre>
 * M8Main diff NAME [FILE_OR_DIR..]   machine against the reference engine, per declaration
 *                                    NAME = tutorial | bugs | all | init | std | cedar | reachability, or any label with roots
 *                                    -> work/status/machine-diff-NAME.txt            exit 1 on a wrong verdict
 * M8Main conv NAME [FILE_OR_DIR..]   convention equivalence D = Op.HOST_DEPTH / 64 / 0 / unbounded (H12 item 1)
 *                                    -> work/status/machine-conv-NAME.txt            exit 1 on a difference
 * M8Main frames NAME [FILE_OR_DIR..] call events and frames written under the bound in force (H12 item 2; 0.1%)
 *                                    -> work/status/machine-frames-NAME.txt          exit 1 above the bound
 * M8Main sample NAME [FILE_OR_DIR..] whnf / infer of the machine read back and compared by the reference kernel
 *                                    -> work/status/machine-sample-NAME.txt          exit 1 on a difference
 * M8Main gate NAME|- [CORPUS..]      THE GATE for the switch set -Dm8.switches (M8Gate): differential, tier and convention
 *                                    equivalence, debug audits, the pipeline -> work/status/machine-gate-NAME.md
 * M8Main mutants -                   the mutant corpora with the machine (M8Mutants; -Dm8.mutants.sets="v0;warm,gated,expect")
 *                                    -> work/status/machine-mutants.md
 * M8Main tiers NAME [FILE_OR_DIR..]  K0 against the tier -Dm8.k1=facts,tele,code,casetrees: DELTA, WHNF, VTYPE, CONV on
 *                                    generated applications of every constant (ISA section 11 "tier equivalence")
 *                                    -> work/status/machine-tiers-NAME.txt           exit 1 on a difference
 * </pre>
 * Options are system properties: {@code -Dm8.hostDepth=N}, {@code -Dm8.switches=warm,gated}, {@code -Dm8.debug=..},
 * {@code -Dm8.maxSteps=N}, {@code -Dm8.checkSeconds=N} (deadline of one declaration), {@code -Dm8.fileSeconds=N},
 * {@code -Dm8.suffix=-d0} (appended to the report name), {@code -Dm8.seed=N}, {@code -Dm8.perConstant=N},
 * {@code -Dm8.every=N} (sample every N-th constant).
 */
final class M8Main {
    private M8Main() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            usage();
            return;
        }
        String cmd = args[0];
        String label = args[1];
        String[] roots = args.length > 2 ? Arrays.copyOfRange(args, 2, args.length) : Diff.corpus(label);
        if (roots == null && !cmd.equals("gate") && !cmd.equals("mutants")) {
            System.err.println("M8Main: unknown corpus '" + label + "' and no file or directory given");
            System.exit(2);
        }
        int status;
        switch (cmd) {
            case "diff": {
                Diff.Report rep = Diff.report(label, roots);
                if (rep == null) {
                    System.err.println("M8Main: no export below " + String.join(", ", roots));
                    status = 2;
                } else {
                    status = rep.sound() ? 0 : 1;
                }
                break;
            }
            case "conv":
                status = TierEq.conventionReport(label, roots) ? 0 : 1;
                break;
            case "frames":
                status = TierEq.framesReport(label, roots) ? 0 : 1;
                break;
            case "sample":
                status = M8Sampler.report(label, roots) ? 0 : 1;
                break;
            case "tiers":
                status = M8Tiers.report(label, roots) ? 0 : 1;
                break;
            case "mutants":
                status = M8Mutants.report() ? 0 : 1;
                break;
            case "gate": {
                // label = the report's name; the corpora, switches and parts come from system properties
                M8Gate.Plan plan = M8Gate.planFromProperties();
                if (!label.equals("-")) {
                    plan.name = Diff.reportName(label);
                }
                if (args.length > 2) {
                    plan.corpora = new java.util.ArrayList<>(Arrays.asList(Arrays.copyOfRange(args, 2, args.length)));
                }
                plan.command = System.getProperty("m8.command", "M8Main " + String.join(" ", args));
                status = M8Gate.report(plan).pass() ? 0 : 1;
                break;
            }
            default:
                usage();
                return;
        }
        System.out.flush();
        // A judgment that did not react to a cancellation may still be running in a daemon thread.
        System.exit(status);
    }

    private static void usage() {
        System.err.println("usage: M8Main diff|conv|frames|sample|tiers|gate|mutants NAME [FILE_OR_DIR..]   (see the class comment; report.sh --help)");
        System.exit(2);
    }
}

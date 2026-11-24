# rlvm-machine/h10/hotspot-inline.cmd - the HotSpot inlining directives of the host control convention
# (docs/design/review/stepper-convention.md H10, rule 3). Owner: M6.
#
#   java -XX:CompileCommandFile=rlvm-machine/h10/hotspot-inline.cmd ...        (C2 and the Graal JIT honour it)
#   work/dist/bin/rlvm-jvm -XX:CompileCommandFile=$PWD/rlvm-machine/h10/hotspot-inline.cmd -X engine=machine FILE
#
# Continuation steps (the methods named k_*) are inlined into the step before them; the frame writers
# and every out-of-line slow part stay out of line. Entry steps are never forced (they are recursive).
# This is tuning, not correctness: the convention measured 0 to 3 points of CPU time on the Graal JIT and
# nothing on C2 (spikes/stepper); it has NOT been measured on the real machine yet.
quiet
inline rlvm/machine/*.k_*
dontinline rlvm/machine/Machine.sig*
dontinline rlvm/machine/Machine.spill*
dontinline rlvm/machine/Machine.tickSlow
dontinline rlvm/machine/*.*Slow

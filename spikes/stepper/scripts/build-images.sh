#!/usr/bin/env bash
# The two native images of the campaign (GraalVM CE 25, -O2, serial GC):
#   stepper-ce      default inlining
#   stepper-ce-fi   the continuation steps of H (every step method that is not the entry of a judgment) are
#                   force-inlined into their callers, the cold materialisation helpers are kept out of line
set -euo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/../env.sh"
P='rlvm.spike.stepper.Hyb*'
STEPS="evalApp1 apply evalLet1 rbApp1 rbB1 rbB2 rbB3 convApp1 convB1 convB2 convB3 convB4 convB5"
COLD="sig1 sig2 sig3 sig4 save1 save2 save3 save4 spill2 spill3"
FI=$(for m in $STEPS; do printf '%s.%s,' "$P" "$m"; done | sed 's/,$//')
NI=$(for m in $COLD; do printf '%s.%s,' "$P" "$m"; done | sed 's/,$//')
"$SPIKE/scripts/build-native.sh" stepper-ce
"$SPIKE/scripts/build-native.sh" stepper-ce-fi -H:+UnlockExperimentalVMOptions "-H:PriorityForceInline=$FI" "-H:PriorityNeverInline=$NI"

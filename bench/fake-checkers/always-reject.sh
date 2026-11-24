#!/bin/sh
# Fake checker: rejects everything (after checking that $IN is readable).
[ -r "$IN" ] || { echo "always-reject: cannot read IN='$IN'" >&2; exit 3; }
echo "always-reject: rejecting $(basename "$IN")" >&2
exit 1

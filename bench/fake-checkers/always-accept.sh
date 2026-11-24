#!/bin/sh
# Fake checker: accepts everything (after checking that $IN is readable).
[ -r "$IN" ] || { echo "always-accept: cannot read IN='$IN'" >&2; exit 3; }
exit 0

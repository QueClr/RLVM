#!/bin/sh
# bench/track/fake-burner.sh N [EXIT] - a fake checker for the tracker's self-test: burns CPU
# proportional to N (about 50 ns per unit) and exits with EXIT (default 0 = accept).
[ -r "$IN" ] || { echo "fake-burner: cannot read IN='$IN'" >&2; exit 3; }
python3 -I -c "
s = 0
for i in range(${1:?usage: fake-burner.sh N [EXIT]}):
    s += i
"
exit "${2:-0}"

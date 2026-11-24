#!/usr/bin/env python3
"""Deterministic input vectors for OfficialNatVectors.lean.  Usage: gen_nat_inputs.py full|sized > file"""
import random, sys

mode = sys.argv[1] if len(sys.argv) > 1 else "full"
rnd = random.Random(20261123)

def tok(n):
    """Compact token for powers of two and neighbours, decimal otherwise."""
    if n >= 1 << 70:
        bl = n.bit_length()
        if n == 1 << (bl - 1): return "p%d" % (bl - 1)
        if n == (1 << bl) - 1: return "m%d" % bl
        if n == (1 << (bl - 1)) + 1: return "q%d" % (bl - 1)
    return str(n)

B = [0, 1, 2, 3, 7, 10, 63, 64, 255, 2**31 - 1, 2**31, 2**32 - 1, 2**32, 2**32 + 1, 3037000499, 3037000500,
     2**62, 2**63 - 2, 2**63 - 1, 2**63, 2**63 + 1, 2**64 - 2, 2**64 - 1, 2**64, 2**64 + 1, 2**65 + 12345,
     2**127, 2**128 - 1, 2**128, 2**128 + 1, 10**40, 2**192 - 1, 2**256]

def rand_nat(maxbits):
    bits = rnd.choice([0, 1, 5, 31, 32, 33, 62, 63, 64, 65, 100, 127, 128, 129, 190, 200, maxbits])
    bits = rnd.randint(0, bits)
    n = rnd.getrandbits(bits) if bits else 0
    r = rnd.random()
    if r < 0.08 and bits: n = (1 << bits) - 1
    elif r < 0.16 and bits: n = 1 << (bits - 1)
    elif r < 0.20: n = n >> (n.bit_length() // 2) << (n.bit_length() // 2)   # zero low half
    return n

out = []
SIZED = ["add", "sub", "mul"]
PLAIN = ["gcd", "mod", "div", "beq", "ble", "land", "lor", "xor"]
ops = SIZED + (PLAIN if mode == "full" else [])

for a in B:
    out.append("succ %s" % tok(a))
for op in ops:
    for a in B:
        for b in B:
            out.append("%s %s %s" % (op, tok(a), tok(b)))
    n = 250 if mode == "full" else 120
    for _ in range(n):
        out.append("%s %s %s" % (op, tok(rand_nat(260)), tok(rand_nat(260))))
    if mode == "full":
        for _ in range(60):
            out.append("%s %s %s" % (op, tok(rand_nat(6000)), tok(rand_nat(6000))))
        for _ in range(20):   # equal or nearly equal big operands
            a = rand_nat(700)
            out.append("%s %s %s" % (op, tok(a), tok(a + rnd.choice([0, 0, 1]))))
for _ in range(120):
    out.append("succ %s" % tok(rand_nat(260)))

# pow
PB = [0, 1, 2, 3, 4, 7, 10, 255, 2**31, 2**32 - 1, 2**32, 3037000499, 2**62, 2**63 - 1, 2**63, 2**64 - 1, 2**64,
      2**64 + 1, 2**128 - 1, 2**128, 10**40]
PE = [0, 1, 2, 3, 4, 5, 7, 8, 9, 15, 16, 17, 31, 32, 33, 62, 63, 64, 65, 100, 127, 128, 255, 256, 257, 511, 512, 513,
      1023, 1024, 2047, 2048, 4095, 4096, 4097, 2**16, 2**20, 2**23, 2**23 + 1, 2**24 - 1, 2**24, 2**24 + 1,
      2**27, 2**31 - 1, 2**31, 2**32 - 2, 2**32 - 1, 2**32, 2**32 + 1, 2**63 - 1, 2**63, 2**64 - 1, 2**64, 2**64 + 1, 2**130]
HEAVY = 1 << 28    # do not ask the official kernel (or us) for results above 2^28 bits unless the base is a power of 2
for a in PB:
    for e in PE:
        est = a.bit_length() * e
        pow2 = a != 0 and a & (a - 1) == 0
        if a > 1 and e <= 2**24 and est > (1 << 22) and not pow2:
            continue     # passes the official guard but costs minutes without GMP-class multiplication
        if a > 1 and pow2 and est > HEAVY and e <= 2**24 and not (a == 2**64 and e == 2**23):
            continue
        out.append("pow %s %s" % (tok(a), tok(e)))
for _ in range(300):
    a = rand_nat(140)
    e = rnd.choice([rnd.randint(0, 12), rnd.randint(0, 70), rnd.randint(0, 600)])
    out.append("pow %s %s" % (tok(a), e))
out.append("pow 3 1048576")
out.append("pow 1000003 32000")

# shiftLeft
SA = [0, 1, 2, 3, 255, 2**31, 2**32 - 1, 2**62, 2**62 + 1, 2**63 - 1, 2**63, 2**64 - 1, 2**64, 2**64 + 1, 2**128 - 1, 10**40]
SK = [0, 1, 2, 7, 8, 9, 31, 32, 33, 61, 62, 63, 64, 65, 66, 127, 128, 129, 191, 192, 193, 1000, 4096, 32760, 32767, 32768,
      65536, 2**24, 2**32 - 1, 2**32, 2**32 + 1, 2**63 - 1, 2**63, 2**64 - 1, 2**64, 2**64 + 1, 2**130]
GIANT = [1073741751, 1073741752, 1073741759, 1073741760, 1073741761, 1073741695, 1073741696, 2**30, 2**31 - 1, 2**31]
for a in SA:
    for k in SK:
        out.append("shiftLeft %s %s" % (tok(a), tok(k)))
for a in [0, 1, 2**63 - 1, 2**64 - 1, 2**64]:
    for k in GIANT:
        out.append("shiftLeft %s %s" % (tok(a), tok(k)))
# small-MAX boundaries: bytes(a) + k/8 + 1 > MAX for MAX in 8, 16, 20, 64, 100, 4096
for a in [1, 2**63 - 1, 2**63, 2**64, 2**128]:
    for mx in [8, 16, 20, 64, 100, 4096]:
        base = (mx - 1 - 8 * max(1, (a.bit_length() + 63) // 64)) * 8
        for d in range(-9, 10):
            k = base + d
            if k >= 0:
                out.append("shiftLeft %s %d" % (tok(a), k))
for _ in range(300):
    out.append("shiftLeft %s %d" % (tok(rand_nat(260)), rnd.choice([rnd.randint(0, 70), rnd.randint(0, 700)])))

# shiftRight
RK = [0, 1, 7, 8, 31, 32, 33, 62, 63, 64, 65, 127, 128, 129, 191, 192, 193, 255, 256, 257, 1000, 2**32 - 1, 2**32,
      2**32 + 1, 2**63 - 1, 2**63, 2**64 - 1, 2**64, 2**64 + 1, 2**200]
for a in B + [42]:
    for k in RK:
        out.append("shiftRight %s %s" % (tok(a), tok(k)))
for _ in range(300):
    out.append("shiftRight %s %d" % (tok(rand_nat(700)), rnd.choice([rnd.randint(0, 70), rnd.randint(0, 800)])))

seen = set()
for line in out:
    if line not in seen:
        seen.add(line)
        print(line)

#!/usr/bin/env python3
"""Deterministic input vectors for OfficialStrVectors.lean: UTF-8 hex, its scalar values, a wrong list."""
import random
rnd = random.Random(20261124)
EDGE = [0, 1, 0x7F, 0x80, 0x7FF, 0x800, 0xFFF, 0x1000, 0xD7FF, 0xE000, 0xFFFD, 0xFFFE, 0xFFFF, 0x10000, 0x1F600, 0x10FFFF,
        0xE9, 0x20AC, 0x22, 0x5C, 0x0A]

def scalar():
    r = rnd.random()
    if r < 0.25: return rnd.choice(EDGE)
    if r < 0.5: return rnd.randint(0, 0x7F)
    if r < 0.65: return rnd.randint(0x80, 0x7FF)
    if r < 0.85:
        c = rnd.randint(0x800, 0xFFFF)
        return c if not 0xD800 <= c <= 0xDFFF else 0xD7FF
    return rnd.randint(0x10000, 0x10FFFF)

def enc(cs):
    return "".join(chr(c) for c in cs).encode("utf-8")

def fmt(cs):
    return ",".join(str(c) for c in cs) if cs else "-"

def wrong(cs):
    b = list(enc(cs))
    k = rnd.randint(0, 5)
    if k == 0 or not cs:
        return cs + [rnd.choice(EDGE)]                      # one element too many
    if k == 1:
        return cs[:-1]                                      # one too few
    if k == 2 and b != cs:
        return b                                            # the bytes instead of the scalar values
    if k == 3:
        i = rnd.randrange(len(cs)); d = list(cs); d[i] = d[i] + 1 if d[i] not in (0xD7FF, 0x10FFFF) else d[i] - 1
        return d                                            # one element off by one
    if k == 4 and any(c > 0xFFFF for c in cs):
        d = []                                              # UTF-16 code units instead of scalar values
        for c in cs:
            if c > 0xFFFF:
                c -= 0x10000; d += [0xD800 + (c >> 10), 0xDC00 + (c & 0x3FF)]
            else:
                d.append(c)
        return d
    d = list(reversed(cs))
    return d if d != cs else cs + [0]

cases = [[], [0], [0x7F], [0x80], [0x7FF], [0x800], [0xD7FF], [0xE000], [0xFFFF], [0x10000], [0x10FFFF],
         [0xE9, 0x20AC, 0x1F600], [0x41] * 40, [0x10FFFF] * 5, [0, 0, 0]]
for c in EDGE:
    cases.append([c]); cases.append([0x61, c, 0x62])
for _ in range(400):
    cases.append([scalar() for _ in range(rnd.choice([1, 2, 3, 5, 8, 20]))])
for cs in cases:
    print(enc(cs).hex() or "-", fmt(cs), fmt(wrong(cs)))
# surrogate and out-of-range elements: Char.ofNat maps them to the character 0
print(enc([0]).hex(), fmt([0]), fmt([0xD800]))
print(enc([0]).hex(), fmt([0]), fmt([0xDFFF]))
print(enc([0]).hex(), fmt([0]), fmt([0x110000]))
print(enc([0]).hex(), fmt([0]), fmt([2**64]))
print(enc([0x41]).hex(), fmt([0x41]), fmt([0x41 + 2**32]))

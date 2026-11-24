#!/usr/bin/env python3
"""Formats the JSON written by natbench.py --out as the markdown tables of rlvm-nat/README.md:

    python3 -I bench/modules/rlvm-nat/tables.py work/rlvm-nat/natbench-full.json
"""
import json, sys
rows = {r["case"]: r for r in json.load(open(sys.argv[1]))["rows"]}
base = rows["baseline"]

def f(x):
    if x >= 100000: return f"{x/1000:.0f}k"
    if x >= 10000: return f"{x/1000:.1f}k"
    if x >= 100: return f"{x:.0f}"
    return f"{x:.1f}"

print("Long fast paths (both operands and the result below 2^63), net of the empty loop "
      f"({base['instr_per_op']:.1f} instructions, {base['cpu_ns_per_op']:.2f} ns per iteration):\n")
print("| operation | instructions | CPU ns |")
print("|---|---|---|")
names = {"shl": "shiftLeft", "shr": "shiftRight", "mulw": "mul (one operand >= 2^31)"}
for op in ["add", "sub", "mul", "mulw", "div", "mod", "gcd", "land", "lor", "xor", "shl", "shr", "beq", "ble", "succ", "pow", "log2"]:
    r = rows.get(f"nat.{op}.small")
    if not r: continue
    note = {"gcd": " (62-bit operands, Euclid)", "pow": " (bases 2..10, exponents 0..18)"}.get(op, "")
    print(f"| `{names.get(op, op)}`{note} | {r['instr_per_op'] - base['instr_per_op']:.1f} | {max(0.0, r['cpu_ns_per_op'] - base['cpu_ns_per_op']):.2f} |")

shapes = [("ovf", "word-sized operands, result >= 2^63"), ("u64", "128-bit value against 2^64"),
          ("256s", "256-bit against 30-bit"), ("256", "256-bit operands (div, mod: 256 / 128 bits)"),
          ("4096", "4096-bit operands (div, mod: 4096 / 2048 bits)"), ("gf3", "609,961-bit operands (gf3-matrix-product; mul: sparse x 781 bits)")]
print("\nBig paths, this module (`nat`) against the `BigInteger` side table (`big`); instructions and CPU ns per operation:\n")
print("| operands | operation | nat instr | big instr | big / nat | nat ns | big ns |")
print("|---|---|---|---|---|---|---|")
for shape, desc in shapes:
    first = True
    for op in ["add", "sub", "mul", "div", "mod", "gcd", "land", "lor", "xor", "shl", "shr", "succ", "pow", "beq", "ble"]:
        n = rows.get(f"nat.{op}.{shape}"); b = rows.get(f"big.{op}.{shape}")
        if not n or not b: continue
        ratio = b["instr_per_op"] / n["instr_per_op"]
        print(f"| {desc if first else ''} | `{names.get(op, op)}` | {f(n['instr_per_op'])} | {f(b['instr_per_op'])} | {ratio:.2f} | {f(n['cpu_ns_per_op'])} | {f(b['cpu_ns_per_op'])} |")
        first = False
print("\nBare `BigInteger` operations without any store (`jdk.*`): " + ", ".join(
    f"{c.split('.',1)[1]} {f(rows[c]['instr_per_op'])}" for c in rows if c.startswith("jdk.")) + " instructions.")

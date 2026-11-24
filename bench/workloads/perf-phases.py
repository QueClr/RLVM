#!/usr/bin/env python3
"""Aggregate `perf script -F ip,sym` output of an nl-fast run (frame-pointer build, event instructions:u)
into (a) self instructions by function group and (b) instructions by the innermost enclosing phase
(inference / conversion / reduction / import / inductive), where helpers such as eval, apply and the
interning functions are charged to the phase function nearest to them on the stack.

    perf script -i perf.data -F ip,sym | python3 -I bench/workloads/perf-phases.py NAME > NAME.phases.txt
"""
import re
import sys
from collections import Counter

GROUPS = [
    # generic helpers first: identical-code folding makes their type parameters meaningless
    ("hash tables (memo/intern rehash, probes)", re.compile(r"hashbrown|value_checker::tables|FxBuildHasher|Logged|Memo<")),
    ("allocator/memcpy", re.compile(r"^_?mi_|mi_|memcpy|memmove|memset|malloc|free|bumpalo|alloc::|smallvec")),
    ("bignum", re.compile(r"num_bigint|nano_lean::checker::nat|num_integer")),
    ("import", re.compile(r"nano_lean::import|serde_json|Importer|nl_fast::load|memchr|olean|blean|reorder")),
    ("inductive+term core", re.compile(r"nano_lean::checker::|nano_lean::kernel::|unbound::|nano_lean::syntax")),
    ("conversion", re.compile(r"Vc>::(def_eq\w*|quick|args_eq|lazy_delta|probe_args|same\w*|eta\w*|proof_irrel|relevant|prop_type|met_past|heads_eq|unit_like|string_expand|offset|conv_local|unfold_core|statically_not_proof|is_nat_zero|nat_pred)\b")),
    ("reduction", re.compile(r"Vc>::(whnf\w*|unfold\w*|reduce_\w+|nat_to_ctor|str_to_ctor|expose_\w+|derive|explore|blocker|resolve|fire|guard_match|nat_val|delta_hint|rec_induct|structure_like|quot_major)\b")),
    ("inference", re.compile(r"Vc>::(infer\w*|type_of|proj_type|check\w*|inst_pi|ensure_sort|is_prop|literal_type|const_type|admit|within|nat_typed)\b")),
    ("substitution (zeta/rule templates)", re.compile(r"Vc>::(zeta\w*|subst)\b")),
    ("evaluation (eval/apply/trim)", re.compile(r"Vc>::(eval\w*|apply|inst|trim|collapse|proj|slots|support_\w+|projects|force|uses_arg|reads_arg|binder_local|restoring_depth|local|fresh_local|konst0)\b|value_checker::eval::")),
    ("construction+interning", re.compile(r"Vc>::(neu\w*|mk\w*|frame\w*|push\w*|lazy|nat|memo_of|memo)\b|value_checker::intern|Ptrs")),
    ("levels/ctx", re.compile(r"nano_lean::term::|nano_lean::level")),
]
PHASES = ["import", "inductive+term core", "conversion", "reduction", "inference"]


def group(sym):
    for name, rx in GROUPS:
        if rx.search(sym):
            return name
    return "other"


def main():
    name = sys.argv[1] if len(sys.argv) > 1 else "?"
    folded_out = sys.argv[2] if len(sys.argv) > 2 else None   # write collapsed stacks here
    from_folded = name == "--folded"                          # perf-phases.py --folded NAME < file.folded
    if from_folded:
        name = sys.argv[2]
        folded_out = None
    self_c, phase_c, sym_c = Counter(), Counter(), Counter()
    cross = Counter()
    folded = Counter()
    stack = []
    n = 0

    def flush(w=1):
        nonlocal n
        if not stack:
            return
        n += w
        if folded_out:
            # keep the frames from the leaf out to the first phase frame (enough to re-aggregate)
            keep = []
            for s in stack:
                keep.append(s)
                if group(s) in PHASES:
                    break
            folded["\x1f".join(keep)] += 1
        leaf = stack[0]
        g = group(leaf)
        self_c[g] += w
        sym_c[leaf] += w
        ph = "other (driver, threads)"
        for s in stack:
            gg = group(s)
            if gg in PHASES:
                ph = gg
                break
        phase_c[ph] += w
        cross[(ph, g)] += w

    if from_folded:
        for line in sys.stdin:
            k, _, c = line.rstrip("\n").rpartition("\t")
            stack = k.split("\x1f")
            flush(int(c))
        stack = []
    for line in sys.stdin:
        line = line.rstrip("\n")
        if not line.strip():
            flush()
            stack = []
            continue
        p = line.strip().split(None, 1)
        stack.append(p[1] if len(p) > 1 else "[unknown]")
    flush()
    if folded_out:
        with open(folded_out, "w") as f:
            for k, c in folded.items():
                f.write(f"{k}\t{c}\n")
    print(f"# {name}: {n} samples (each = the sampling period in user instructions)")
    print("\n## by innermost enclosing phase")
    for k, v in phase_c.most_common():
        print(f"{k}\t{v}\t{100 * v / n:.1f}%")
    print("\n## self, by function group")
    for k, v in self_c.most_common():
        print(f"{k}\t{v}\t{100 * v / n:.1f}%")
    print("\n## phase x self group (share of all samples)")
    for (ph, g), v in sorted(cross.items(), key=lambda kv: -kv[1])[:40]:
        print(f"{ph}\t{g}\t{100 * v / n:.1f}%")
    print("\n## top 40 symbols (self)")
    for k, v in sym_c.most_common(40):
        print(f"{100 * v / n:5.1f}%\t{k[:150]}")


if __name__ == "__main__":
    main()

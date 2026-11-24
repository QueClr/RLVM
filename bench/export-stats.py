#!/usr/bin/env python3
"""Streaming statistics of lean4export NDJSON files (format 3.x).

    bench/export-stats.py [-j N] [--out-dir DIR] [--md FILE] [--top N] [NAME=]FILE.ndjson ...
    bench/export-stats.py --md FILE --render DIR        # only render DIR/*.stats.json again

One pass per file, constant memory apart from the table of names (needed to
print the names of the largest declarations) and one integer per declaration.
For every file: size, lines, the header, the number of records of every kind
(names, levels, expressions, declarations), the largest ids, facts about
literals and binders, and the size of each declaration.

"Size of a declaration" is its *incremental* size: the bytes of all records
from the end of the previous declaration record to the end of its own. The
exporter writes a name, level or expression the first time it is needed, and
the constants a declaration depends on before the declaration's own
expressions, so this is what the declaration adds to the file: its own new
expression nodes (plus new names and levels), not the nodes it shares with
earlier declarations.

Records are classified by their first key (lean4export writes keys sorted, so
that is "ie"/"il"/"in" or the kind itself); everything that does not match
that fast path is parsed as JSON, so files with another key order (the
hand-written arena tests) are handled as well, just more slowly.

Reads data only; nothing in the files is executed. Run it with `python3 -I`.
"""
import argparse
import array
import heapq
import json
import os
import sys
import time
from concurrent.futures import ProcessPoolExecutor

NAME_KINDS = ("str", "num")
LEVEL_KINDS = ("succ", "max", "imax", "param")
EXPR_KINDS = ("bvar", "sort", "const", "app", "lam", "forallE", "letE", "proj",
              "natVal", "strVal", "mdata")
DECL_KINDS = ("axiom", "def", "opaque", "thm", "quot", "inductive")
# Expression kinds that sort before "ie" and therefore start the line
EXPR_FIRST = (b"app", b"bvar", b"const", b"forallE")


class Stats:
    """Everything the slow path (JSON-parsed records) updates."""

    def __init__(self, top_n: int):
        self.top_n = top_n
        self.meta = None
        self.names = {k: 0 for k in NAME_KINDS}
        self.levels = {k: 0 for k in LEVEL_KINDS}
        self.exprs = {k: 0 for k in EXPR_KINDS}
        self.decls = {k: 0 for k in DECL_KINDS}
        self.unknown = {}
        self.max_in = self.max_il = self.max_ie = -1
        self.bytes_names = self.bytes_levels = self.bytes_exprs = self.bytes_decls = 0
        # names table for printing: id -> (prefix id, component)
        self.name_table = {}
        # literals
        self.nat_max_digits = 0
        self.nat_ge_2_64 = 0
        self.nat_digits_total = 0
        self.str_bytes_total = 0
        self.str_max_bytes = 0
        self.max_bvar = -1
        # declarations
        self.def_hints = {"opaque": 0, "abbrev": 0, "regular": 0}
        self.def_safety = {}
        self.max_regular_height = 0
        self.unsafe_decls = 0
        self.max_level_params = 0
        self.ind_types = self.ind_ctors = self.ind_recs = self.ind_rules = 0
        self.ind_mutual_blocks = 0      # blocks with more than one type
        self.ind_nested_types = 0       # types with numNested > 0
        self.ind_rec_types = 0
        self.ind_reflexive_types = 0
        self.rec_k = 0
        self.max_ctors_per_type = 0
        self.max_types_per_block = 0
        self.max_fields = 0
        self.quot_kinds = []
        # per-declaration incremental size
        self.decl_bytes = array.array("Q")
        self.decl_bytes_by_kind = {k: 0 for k in DECL_KINDS}
        self.decl_records_max = 0
        self.top = []                   # min-heap of (bytes, seq, kind, name id, records)
        self.seq = 0

    # -- slow path -----------------------------------------------------------
    def record(self, line: bytes, cur_bytes: int, cur_records: int) -> bool:
        """Account for one JSON-parsed record. Returns True for a declaration."""
        try:
            obj = json.loads(line)
        except ValueError:
            self.unknown["<not JSON>"] = self.unknown.get("<not JSON>", 0) + 1
            return False
        if not isinstance(obj, dict):
            self.unknown["<not an object>"] = self.unknown.get("<not an object>", 0) + 1
            return False
        size = len(line)
        if "in" in obj:
            kind = next((k for k in obj if k != "in"), "?")
            if kind not in self.names:
                self.unknown["name:" + kind] = self.unknown.get("name:" + kind, 0) + 1
                return False
            self.names[kind] += 1
            self.bytes_names += size
            idx = obj["in"]
            if idx > self.max_in:
                self.max_in = idx
            body = obj[kind]
            self.name_table[idx] = (body.get("pre", 0), body.get("str") if kind == "str" else body.get("i"))
            return False
        if "il" in obj:
            kind = next((k for k in obj if k != "il"), "?")
            if kind not in self.levels:
                self.unknown["level:" + kind] = self.unknown.get("level:" + kind, 0) + 1
                return False
            self.levels[kind] += 1
            self.bytes_levels += size
            if obj["il"] > self.max_il:
                self.max_il = obj["il"]
            return False
        if "ie" in obj:
            kind = next((k for k in obj if k != "ie"), "?")
            if kind not in self.exprs:
                self.unknown["expr:" + kind] = self.unknown.get("expr:" + kind, 0) + 1
                return False
            self.exprs[kind] += 1
            self.bytes_exprs += size
            if obj["ie"] > self.max_ie:
                self.max_ie = obj["ie"]
            value = obj[kind]
            if kind == "natVal":
                digits = len(str(value))
                self.nat_digits_total += digits
                if digits > self.nat_max_digits:
                    self.nat_max_digits = digits
                if digits >= 20 and int(value) >= 1 << 64:
                    self.nat_ge_2_64 += 1
            elif kind == "strVal":
                length = len(str(value).encode("utf-8", "surrogatepass"))
                self.str_bytes_total += length
                if length > self.str_max_bytes:
                    self.str_max_bytes = length
            elif kind == "bvar":
                if isinstance(value, int) and value > self.max_bvar:
                    self.max_bvar = value
            return False
        if "meta" in obj:
            if self.meta is None:
                self.meta = obj["meta"]
            return False
        kind = next(iter(obj), "?")
        if kind not in self.decls:
            self.unknown[kind] = self.unknown.get(kind, 0) + 1
            return False
        self.decls[kind] += 1
        self.bytes_decls += size
        body = obj[kind]
        name_id = None
        if kind == "inductive":
            types = body.get("types", [])
            ctors = body.get("ctors", [])
            recs = body.get("recs", [])
            self.ind_types += len(types)
            self.ind_ctors += len(ctors)
            self.ind_recs += len(recs)
            if len(types) > 1:
                self.ind_mutual_blocks += 1
            if len(types) > self.max_types_per_block:
                self.max_types_per_block = len(types)
            for t in types:
                if name_id is None:
                    name_id = t.get("name")
                if t.get("numNested", 0) > 0:
                    self.ind_nested_types += 1
                if t.get("isRec"):
                    self.ind_rec_types += 1
                if t.get("isReflexive"):
                    self.ind_reflexive_types += 1
                if t.get("isUnsafe"):
                    self.unsafe_decls += 1
                n = len(t.get("ctors", []))
                if n > self.max_ctors_per_type:
                    self.max_ctors_per_type = n
                n = len(t.get("levelParams", []))
                if n > self.max_level_params:
                    self.max_level_params = n
            for c in ctors:
                n = c.get("numFields", 0)
                if n > self.max_fields:
                    self.max_fields = n
            for r in recs:
                self.ind_rules += len(r.get("rules", []))
                if r.get("k"):
                    self.rec_k += 1
        else:
            name_id = body.get("name")
            n = len(body.get("levelParams", []))
            if n > self.max_level_params:
                self.max_level_params = n
            if kind == "def":
                hints = body.get("hints")
                if isinstance(hints, dict):
                    self.def_hints["regular"] += 1
                    height = hints.get("regular", 0)
                    if height > self.max_regular_height:
                        self.max_regular_height = height
                elif hints in self.def_hints:
                    self.def_hints[hints] += 1
                safety = body.get("safety", "?")
                self.def_safety[safety] = self.def_safety.get(safety, 0) + 1
                if safety == "unsafe":
                    self.unsafe_decls += 1
            elif kind == "quot":
                self.quot_kinds.append(body.get("kind"))
            if body.get("isUnsafe"):
                self.unsafe_decls += 1
        # incremental size of this declaration
        self.decl_bytes.append(cur_bytes)
        self.decl_bytes_by_kind[kind] += cur_bytes
        if cur_records > self.decl_records_max:
            self.decl_records_max = cur_records
        self.seq += 1
        item = (cur_bytes, self.seq, kind, name_id, cur_records)
        if len(self.top) < self.top_n:
            heapq.heappush(self.top, item)
        elif item > self.top[0]:
            heapq.heapreplace(self.top, item)
        return True

    def name(self, idx) -> str:
        parts = []
        guard = 0
        while idx and idx in self.name_table and guard < 1000:
            pre, comp = self.name_table[idx]
            parts.append(str(comp))
            idx = pre
            guard += 1
        return ".".join(reversed(parts)) if parts else f"<name {idx}>"


def scan(path: str, top_n: int = 100) -> dict:
    started = time.time()
    st = Stats(top_n)
    record = st.record
    # fast-path counters, keyed by the kind as bytes
    ec = {k.encode(): 0 for k in EXPR_KINDS}
    lc = {k.encode(): 0 for k in LEVEL_KINDS}
    expr_first = frozenset(EXPR_FIRST)
    max_ie = max_il = max_bvar = -1
    bytes_exprs = bytes_levels = 0
    lines = total = max_line = 0
    cur_bytes = cur_records = 0
    fallback = 0

    with open(path, "rb", buffering=1 << 20) as handle:
        for line in handle:
            size = len(line)
            lines += 1
            total += size
            if size > max_line:
                max_line = size
            cur_bytes += size
            cur_records += 1
            head = line[:6]
            if head == b'{"ie":':
                i = line.find(b',"', 6)
                kind = line[i + 2:line.find(b'"', i + 2)]
                if kind in ec and kind != b"natVal" and kind != b"strVal":
                    try:
                        idx = int(line[6:i])
                    except ValueError:
                        idx = None
                    if idx is not None:
                        ec[kind] += 1
                        bytes_exprs += size
                        if idx > max_ie:
                            max_ie = idx
                        continue
            elif head == b'{"il":':
                i = line.find(b',"', 6)
                kind = line[i + 2:line.find(b'"', i + 2)]
                if kind in lc:
                    try:
                        idx = int(line[6:i])
                    except ValueError:
                        idx = None
                    if idx is not None:
                        lc[kind] += 1
                        bytes_levels += size
                        if idx > max_il:
                            max_il = idx
                        continue
            elif head != b'{"in":':
                kind = line[2:line.find(b'"', 2)]
                if kind in expr_first:
                    i = line.rfind(b',"ie":')
                    try:
                        idx = int(line[i + 6:line.rindex(b"}")])
                        if kind == b"bvar":
                            bvar = int(line[8:i])
                            if bvar > max_bvar:
                                max_bvar = bvar
                    except ValueError:
                        idx = None
                    if idx is not None and i > 0:
                        ec[kind] += 1
                        bytes_exprs += size
                        if idx > max_ie:
                            max_ie = idx
                        continue
            # names, literals, declarations, the header, and whatever the fast
            # path did not recognise
            if not line.strip():
                cur_bytes -= size
                cur_records -= 1
                continue
            fallback += 1
            if record(line, cur_bytes, cur_records):
                cur_bytes = cur_records = 0

    for kind, count in ec.items():
        st.exprs[kind.decode()] += count
    for kind, count in lc.items():
        st.levels[kind.decode()] += count
    st.max_ie = max(st.max_ie, max_ie)
    st.max_il = max(st.max_il, max_il)
    st.max_bvar = max(st.max_bvar, max_bvar)
    st.bytes_exprs += bytes_exprs
    st.bytes_levels += bytes_levels

    sizes = sorted(st.decl_bytes)
    n = len(sizes)

    def quantile(q: float) -> int:
        return sizes[min(n - 1, int(q * n))] if n else 0

    top = sorted(st.top, reverse=True)
    meta = st.meta or {}
    constants = (st.decls["axiom"] + st.decls["def"] + st.decls["opaque"] + st.decls["thm"]
                 + st.decls["quot"] + st.ind_types + st.ind_ctors + st.ind_recs)
    return {
        "file": os.path.abspath(path),
        "size": total,
        "lines": lines,
        "max_line_bytes": max_line,
        "trailing_bytes_after_last_declaration": cur_bytes,
        "header": {
            "lean_version": (meta.get("lean") or {}).get("version"),
            "lean_githash": (meta.get("lean") or {}).get("githash"),
            "exporter": (meta.get("exporter") or {}).get("name"),
            "exporter_version": (meta.get("exporter") or {}).get("version"),
            "format_version": (meta.get("format") or {}).get("version"),
        },
        "names": {**st.names, "total": sum(st.names.values()), "max_id": st.max_in},
        "levels": {**st.levels, "total": sum(st.levels.values()), "max_id": st.max_il},
        "exprs": {**st.exprs, "total": sum(st.exprs.values()), "max_id": st.max_ie},
        "decl_records": {**st.decls, "total": sum(st.decls.values())},
        "constants": {
            "total": constants,
            "axiom": st.decls["axiom"], "def": st.decls["def"], "opaque": st.decls["opaque"],
            "thm": st.decls["thm"], "quot": st.decls["quot"],
            "inductive_types": st.ind_types, "constructors": st.ind_ctors,
            "recursors": st.ind_recs,
        },
        "definitions": {
            "hints": st.def_hints, "safety": st.def_safety,
            "max_regular_height": st.max_regular_height,
        },
        "inductives": {
            "blocks": st.decls["inductive"], "types": st.ind_types, "constructors": st.ind_ctors,
            "recursors": st.ind_recs, "recursor_rules": st.ind_rules,
            "mutual_blocks": st.ind_mutual_blocks, "max_types_per_block": st.max_types_per_block,
            "nested_types": st.ind_nested_types, "recursive_types": st.ind_rec_types,
            "reflexive_types": st.ind_reflexive_types, "k_recursors": st.rec_k,
            "max_ctors_per_type": st.max_ctors_per_type, "max_ctor_fields": st.max_fields,
        },
        "quot_kinds": st.quot_kinds,
        "unsafe_declarations": st.unsafe_decls,
        "max_level_params": st.max_level_params,
        "literals": {
            "natVal": st.exprs["natVal"], "nat_max_digits": st.nat_max_digits,
            "nat_ge_2_64": st.nat_ge_2_64, "nat_digits_total": st.nat_digits_total,
            "strVal": st.exprs["strVal"], "str_bytes_total": st.str_bytes_total,
            "str_max_bytes": st.str_max_bytes,
        },
        "max_bvar_index": st.max_bvar,
        "bytes": {
            "names": st.bytes_names, "levels": st.bytes_levels, "exprs": st.bytes_exprs,
            "decl_records": st.bytes_decls,
        },
        "decl_size": {
            "count": n,
            "by_kind_bytes": st.decl_bytes_by_kind,
            "median": quantile(0.5), "p90": quantile(0.9), "p99": quantile(0.99),
            "p999": quantile(0.999), "max": sizes[-1] if n else 0,
            "max_records": st.decl_records_max,
            "top_n": len(top),
            "top_n_bytes": sum(item[0] for item in top),
            "top_10_bytes": sum(item[0] for item in top[:10]),
            "top": [{"bytes": b, "records": r, "kind": k, "name": st.name(nid), "seq": s}
                    for (b, s, k, nid, r) in top],
        },
        "unknown_records": st.unknown,
        "json_parsed_records": fallback,
        "scan_seconds": round(time.time() - started, 1),
    }


# ------------------------------------------------------------- rendering ----
def num(value) -> str:
    return f"{value:,}" if isinstance(value, int) else str(value)


def mib(value: int) -> str:
    if value >= 1 << 30:
        return f"{value / (1 << 30):.2f} GiB"
    if value >= 1 << 20:
        return f"{value / (1 << 20):.1f} MiB"
    if value >= 1 << 10:
        return f"{value / (1 << 10):.1f} KiB"
    return f"{value} B"


def table(header: list[str], rows: list[list[str]], left: int = 1) -> list[str]:
    """Markdown table; the first `left` columns are left-aligned, the rest right-aligned."""
    out = ["| " + " | ".join(header) + " |",
           "|" + "|".join("---" if i < left else "---:" for i in range(len(header))) + "|"]
    out += ["| " + " | ".join(row) + " |" for row in rows]
    return out


def render(stats: dict[str, dict], top_show: int = 15) -> str:
    names = list(stats)
    out = ["# Export statistics", "",
           "Generated by `bench/export-stats.py` (see `bench/README.md`, section "
           "\"The test corpus\"); raw numbers in `stats/*.stats.json` next to this file.", "",
           "Ids are dense and start at 1 for names and levels (0 is the anonymous name and "
           "level zero, which have no record) and at 0 for expressions, so `max id` is also "
           "the size of the table a checker needs.", ""]

    out.append("## Overview")
    out.append("")
    out.extend(table(
        ["export", "bytes", "lines", "names", "levels", "expressions", "constants",
         "theorems", "definitions", "inductive types", "largest declaration"],
        [[name, num(s["size"]), num(s["lines"]), num(s["names"]["total"]), num(s["levels"]["total"]),
          num(s["exprs"]["total"]), num(s["constants"]["total"]), num(s["constants"]["thm"]),
          num(s["constants"]["def"]), num(s["inductives"]["types"]), mib(s["decl_size"]["max"])]
         for name, s in stats.items()]))
    out.append("")

    def section(title: str, rows: list[tuple[str, callable]]) -> None:
        out.append(f"## {title}")
        out.append("")
        out.extend(table([""] + names, [[label] + [fn(stats[n]) for n in names] for label, fn in rows]))
        out.append("")

    section("Files", [
        ("size (bytes)", lambda s: num(s["size"])),
        ("size", lambda s: mib(s["size"])),
        ("lines", lambda s: num(s["lines"])),
        ("longest line (bytes)", lambda s: num(s["max_line_bytes"])),
        ("Lean version", lambda s: str(s["header"]["lean_version"])),
        ("Lean githash", lambda s: str(s["header"]["lean_githash"])[:12]),
        ("exporter", lambda s: f'{s["header"]["exporter"]} {s["header"]["exporter_version"]}'),
        ("format version", lambda s: str(s["header"]["format_version"])),
        ("sha256", lambda s: (s.get("sha256") or "")[:16] + ("…" if s.get("sha256") else "")),
        ("same sha256 as the arena's export", lambda s: {True: "yes", False: "NO", None: "n/a"}[s.get("sha256_matches_arena")]),
    ])
    section("Records", [
        ("names (total)", lambda s: num(s["names"]["total"])),
        ("  Name.str", lambda s: num(s["names"]["str"])),
        ("  Name.num", lambda s: num(s["names"]["num"])),
        ("  max name id", lambda s: num(s["names"]["max_id"])),
        ("levels (total)", lambda s: num(s["levels"]["total"])),
        ("  succ", lambda s: num(s["levels"]["succ"])),
        ("  max", lambda s: num(s["levels"]["max"])),
        ("  imax", lambda s: num(s["levels"]["imax"])),
        ("  param", lambda s: num(s["levels"]["param"])),
        ("  max level id", lambda s: num(s["levels"]["max_id"])),
        ("expressions (total)", lambda s: num(s["exprs"]["total"])),
    ] + [("  " + k, (lambda k: lambda s: num(s["exprs"][k]))(k)) for k in EXPR_KINDS] + [
        ("  max expr id", lambda s: num(s["exprs"]["max_id"])),
        ("declaration records (total)", lambda s: num(s["decl_records"]["total"])),
        ("bytes in name records", lambda s: mib(s["bytes"]["names"])),
        ("bytes in level records", lambda s: mib(s["bytes"]["levels"])),
        ("bytes in expression records", lambda s: mib(s["bytes"]["exprs"])),
        ("bytes in declaration records", lambda s: mib(s["bytes"]["decl_records"])),
    ])
    section("Expression mix (% of expression records)", [
        (k, (lambda k: lambda s: f'{100 * s["exprs"][k] / max(1, s["exprs"]["total"]):.2f}')(k))
        for k in EXPR_KINDS
    ])
    section("Declarations", [
        ("constants (total)", lambda s: num(s["constants"]["total"])),
        ("theorems (`thm`)", lambda s: num(s["constants"]["thm"])),
        ("definitions (`def`)", lambda s: num(s["constants"]["def"])),
        ("  hints: regular", lambda s: num(s["definitions"]["hints"]["regular"])),
        ("  hints: abbrev", lambda s: num(s["definitions"]["hints"]["abbrev"])),
        ("  hints: opaque", lambda s: num(s["definitions"]["hints"]["opaque"])),
        ("  max regular height", lambda s: num(s["definitions"]["max_regular_height"])),
        ("  safety", lambda s: ", ".join(f"{k} {num(v)}" for k, v in sorted(s["definitions"]["safety"].items())) or "-"),
        ("opaques (`opaque`)", lambda s: num(s["constants"]["opaque"])),
        ("axioms (`axiom`)", lambda s: num(s["constants"]["axiom"])),
        ("quotient constants (`quot`)", lambda s: num(s["constants"]["quot"])),
        ("inductive blocks (`inductive` records)", lambda s: num(s["inductives"]["blocks"])),
        ("  inductive types", lambda s: num(s["inductives"]["types"])),
        ("  constructors", lambda s: num(s["inductives"]["constructors"])),
        ("  recursors", lambda s: num(s["inductives"]["recursors"])),
        ("  recursor rules", lambda s: num(s["inductives"]["recursor_rules"])),
        ("  mutual blocks (>1 type)", lambda s: num(s["inductives"]["mutual_blocks"])),
        ("  max types in a block", lambda s: num(s["inductives"]["max_types_per_block"])),
        ("  nested types (numNested > 0)", lambda s: num(s["inductives"]["nested_types"])),
        ("  recursive types", lambda s: num(s["inductives"]["recursive_types"])),
        ("  reflexive types", lambda s: num(s["inductives"]["reflexive_types"])),
        ("  recursors with K", lambda s: num(s["inductives"]["k_recursors"])),
        ("  max constructors of a type", lambda s: num(s["inductives"]["max_ctors_per_type"])),
        ("  max fields of a constructor", lambda s: num(s["inductives"]["max_ctor_fields"])),
        ("unsafe declarations", lambda s: num(s["unsafe_declarations"])),
        ("max universe parameters", lambda s: num(s["max_level_params"])),
        ("theorems : definitions", lambda s: f'{s["constants"]["thm"] / max(1, s["constants"]["def"]):.2f}'),
    ])
    section("Literals and binders", [
        ("natVal literals", lambda s: num(s["literals"]["natVal"])),
        ("  longest (decimal digits)", lambda s: num(s["literals"]["nat_max_digits"])),
        ("  literals >= 2^64", lambda s: num(s["literals"]["nat_ge_2_64"])),
        ("strVal literals", lambda s: num(s["literals"]["strVal"])),
        ("  total UTF-8 bytes", lambda s: num(s["literals"]["str_bytes_total"])),
        ("  longest (bytes)", lambda s: num(s["literals"]["str_max_bytes"])),
        ("largest bvar index", lambda s: num(s["max_bvar_index"])),
    ])
    section("Incremental size of declarations (bytes of new records per declaration record)", [
        ("declaration records", lambda s: num(s["decl_size"]["count"])),
        ("median", lambda s: num(s["decl_size"]["median"])),
        ("90th percentile", lambda s: num(s["decl_size"]["p90"])),
        ("99th percentile", lambda s: num(s["decl_size"]["p99"])),
        ("99.9th percentile", lambda s: num(s["decl_size"]["p999"])),
        ("largest", lambda s: mib(s["decl_size"]["max"])),
        ("largest (records)", lambda s: num(s["decl_size"]["max_records"])),
        ("top 10, total", lambda s: mib(s["decl_size"]["top_10_bytes"])),
        ("top 100, total", lambda s: f'{mib(s["decl_size"]["top_n_bytes"])}' if s["decl_size"]["top_n"] >= 100
            else f'{mib(s["decl_size"]["top_n_bytes"])} (only {s["decl_size"]["top_n"]})'),
        ("top 100, share of file", lambda s: f'{100 * s["decl_size"]["top_n_bytes"] / max(1, s["size"]):.1f} %'),
        ("in theorems", lambda s: mib(s["decl_size"]["by_kind_bytes"]["thm"])),
        ("in definitions", lambda s: mib(s["decl_size"]["by_kind_bytes"]["def"])),
        ("in inductive blocks", lambda s: mib(s["decl_size"]["by_kind_bytes"]["inductive"])),
        ("in opaques, axioms, quot", lambda s: mib(sum(s["decl_size"]["by_kind_bytes"][k] for k in ("opaque", "axiom", "quot")))),
    ])
    out.append("## Largest declarations")
    out.append("")
    for name in names:
        s = stats[name]
        out.append(f"### {name}")
        out.append("")
        rows = [[str(i + 1), "`" + t["name"].replace("|", "\\|").replace("`", "'") + "`", t["kind"],
                 num(t["bytes"]), num(t["records"])]
                for i, t in enumerate(s["decl_size"]["top"][:top_show])]
        out.extend(table(["#", "declaration", "kind", "bytes", "records"], rows, left=3))
        out.append("")
    odd = {n: s["unknown_records"] for n, s in stats.items() if s["unknown_records"]}
    if odd:
        out.append("## Unrecognised records")
        out.append("")
        for name, unknown in odd.items():
            out.append(f"- {name}: {unknown}")
        out.append("")
    return "\n".join(out)


# ------------------------------------------------------------------ main ----
def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("files", nargs="*", metavar="[NAME=]FILE",
                        help="exports to scan; NAME defaults to the file name without .ndjson")
    parser.add_argument("-j", "--jobs", type=int, default=4, help="files scanned in parallel (default 4)")
    parser.add_argument("--out-dir", metavar="DIR",
                        help="write DIR/NAME.stats.json for every file (and skip files whose "
                             "statistics are already there and newer than the file, unless --force)")
    parser.add_argument("--force", action="store_true", help="scan again even if up to date")
    parser.add_argument("--render", metavar="DIR", action="append", default=[],
                        help="also render the statistics already in DIR/*.stats.json")
    parser.add_argument("--order", metavar="NAMES",
                        help="comma-separated order of the columns (default: by file size)")
    parser.add_argument("--md", metavar="FILE", help="write a Markdown report to FILE ('-' = stdout)")
    parser.add_argument("--top", type=int, default=100, help="number of largest declarations kept (default 100)")
    parser.add_argument("--annotate", metavar="MANIFEST",
                        help="manifest.json of collect-exports.py: adds sha256 and whether it "
                             "equals the arena's to the statistics of files listed there")
    args = parser.parse_args()

    stats: dict[str, dict] = {}
    todo = []
    for spec in args.files:
        name, sep, path = spec.partition("=")
        if not sep or "/" in name:
            path = spec
            name = os.path.basename(spec)
            name = name[:-len(".ndjson")] if name.endswith(".ndjson") else name
        if not os.path.isfile(path):
            print(f"export-stats: {path}: no such file", file=sys.stderr)
            return 2
        cached = os.path.join(args.out_dir, name.replace("/", "__") + ".stats.json") if args.out_dir else None
        if (cached and not args.force and os.path.isfile(cached)
                and os.path.getmtime(cached) >= os.path.getmtime(os.path.realpath(path))):
            with open(cached) as handle:
                stats[name] = json.load(handle)
            continue
        todo.append((name, path, cached))

    if todo:
        if args.out_dir:
            os.makedirs(args.out_dir, exist_ok=True)
        with ProcessPoolExecutor(max_workers=max(1, min(args.jobs, len(todo)))) as pool:
            futures = {name: pool.submit(scan, path, args.top) for name, path, _ in todo}
            for name, path, cached in todo:
                result = futures[name].result()
                result["name"] = name
                stats[name] = result
                print(f"export-stats: {name}: {result['lines']:,} lines, {result['size']:,} bytes, "
                      f"{result['scan_seconds']} s", file=sys.stderr)
                if cached:
                    with open(cached + ".tmp", "w") as handle:
                        json.dump(result, handle, indent=1)
                    os.replace(cached + ".tmp", cached)

    for directory in args.render:
        for filename in sorted(os.listdir(directory)):
            if filename.endswith(".stats.json"):
                with open(os.path.join(directory, filename)) as handle:
                    loaded = json.load(handle)
                stats.setdefault(loaded.get("name", filename[:-len(".stats.json")]), loaded)

    if args.annotate:
        with open(args.annotate) as handle:
            manifest = {t["name"]: t for t in json.load(handle).get("tests", [])}
        for name, entry in stats.items():
            test = manifest.get(name)
            if test:
                entry["sha256"] = test.get("sha256")
                entry["sha256_matches_arena"] = test.get("sha256_matches_arena")

    if args.order:
        order = [n.strip() for n in args.order.split(",") if n.strip()]
        rest = sorted((n for n in stats if n not in order), key=lambda n: stats[n]["size"])
        stats = {n: stats[n] for n in order + rest if n in stats}
    else:
        stats = dict(sorted(stats.items(), key=lambda kv: kv[1]["size"]))

    if args.md:
        text = render(stats)
        if args.md == "-":
            print(text)
        else:
            with open(args.md + ".tmp", "w") as handle:
                handle.write(text + "\n")
            os.replace(args.md + ".tmp", args.md)
            print(f"export-stats: wrote {args.md}", file=sys.stderr)
    elif not args.out_dir:
        json.dump(stats, sys.stdout, indent=1)
        print()
    return 0


if __name__ == "__main__":
    sys.exit(main())

#!/usr/bin/env bash
# bench/export-slice.sh - export a part of an already built Lean project with
# the toolchain's own `leanexport`, the way lka.py exports a test
# (`lake env leanexport MODULE... [-- DECL...] > OUT`), to get mid-size
# exports for fast iteration.
#
#   bench/arena-docker.sh --no-network bench/export-slice.sh [options] MODULE... [-- DECL...]
#
#   MODULE...     modules to import (e.g. Mathlib.Data.Nat.Basic). Without
#                 DECLs, every constant of these modules and of everything they
#                 import is exported (so always at least all of Init).
#   -- DECL...    export only these constants and what they depend on (the
#                 arena's `export-decls:`), e.g. -- Nat.add_comm
#
# Options:
#   -m            "module cone": export only the constants declared in the
#                 MODULEs themselves, and what they depend on (types, values
#                 and proofs, transitively), instead of everything the modules
#                 import. This is `-- DECL...` with the DECLs listed by
#                 bench/lean/ListModuleDecls.lean, and gives much smaller
#                 files: the part of the library that the module really uses.
#   -p DIR        the built project (default: the arena's mathlib checkout,
#                 work/arena-run/_build/tests/work/mathlib/src, which exists
#                 after `lka.py build-test mathlib`)
#   -n NAME       name of the slice (default: the modules joined by "+")
#   -o DIR        output directory (default: work/exports/slices); the export
#                 goes to DIR/good/NAME.ndjson (expected outcome: accept, so
#                 `bench/run-suite.py --tests DIR` runs all slices) and a
#                 description of how it was made to DIR/NAME.info.json
#
# Runs lake and leanexport of a third-party project: container only.
set -euo pipefail

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
ROOT="$(cd "$SELF_DIR/.." && pwd -P)"

project="$ROOT/work/arena-run/_build/tests/work/mathlib/src"
out_dir="$ROOT/work/exports/slices"
name=""
cone=0
while getopts "p:n:o:mh" opt; do
  case "$opt" in
    m) cone=1 ;;
    p) project="$OPTARG" ;;
    n) name="$OPTARG" ;;
    o) out_dir="$OPTARG" ;;
    h) sed -n '2,/^set -euo/p' "$0" | sed '$d; s/^# \{0,1\}//'; exit 0 ;;
    *) exit 2 ;;
  esac
done
shift $((OPTIND - 1))

modules=()
decls=()
seen_sep=0
for arg in "$@"; do
  if [ "$seen_sep" = 0 ] && [ "$arg" = "--" ]; then seen_sep=1; continue; fi
  if [ "$seen_sep" = 0 ]; then modules+=("$arg"); else decls+=("$arg"); fi
done
[ "${#modules[@]}" -gt 0 ] || { echo "export-slice.sh: no module given (see -h)" >&2; exit 2; }
[ -f /.dockerenv ] || { echo "export-slice.sh: run me inside the container (bench/arena-docker.sh)" >&2; exit 2; }
[ -d "$project" ] || { echo "export-slice.sh: project $project not found" >&2; exit 2; }
if [ -z "$name" ]; then
  name="$(IFS=+; echo "${modules[*]}")"
  if [ "$cone" = 1 ]; then name="$name.cone"; fi
fi

out_dir="$(realpath -m "$out_dir")"
project="$(realpath -m "$project")"
mkdir -p "$out_dir/good"
out="$out_dir/good/$name.ndjson"
cd "$project"
exporter="$(elan which leanexport)"
if [ "$cone" = 1 ]; then
  [ "${#decls[@]}" -eq 0 ] || { echo "export-slice.sh: -m and -- DECL... exclude each other" >&2; exit 2; }
  list="$(mktemp)"
  lake env lean --run "$SELF_DIR/lean/ListModuleDecls.lean" "${modules[@]}" > "$list"
  mapfile -t decls < "$list"
  rm -f "$list"
  [ "${#decls[@]}" -gt 0 ] || { echo "export-slice.sh: ${modules[*]} declare no constants" >&2; exit 1; }
fi
cmd=(lake env "$exporter" "${modules[@]}")
if [ "${#decls[@]}" -gt 0 ]; then cmd+=(-- "${decls[@]}"); fi

start=$(date +%s.%N)
"${cmd[@]}" > "$out.tmp"
mv "$out.tmp" "$out"
end=$(date +%s.%N)

python3 -I - "$out" "$out_dir/$name.info.json" "$name" "$project" "$ROOT" "$start" "$end" "$cone" \
    "${#modules[@]}" "${modules[@]}" "${decls[@]}" <<'EOF'
import hashlib, json, os, sys
out, info_path, name, project, root, start, end, cone, n_modules = sys.argv[1:10]
rest = sys.argv[10:]
modules, decls = rest[:int(n_modules)], rest[int(n_modules):]
digest = hashlib.sha256()
lines = 0
with open(out, "rb") as handle:
    header = json.loads(handle.readline())
    handle.seek(0)
    while chunk := handle.read(1 << 20):
        digest.update(chunk)
        lines += chunk.count(b"\n")
toolchain = open(os.path.join(project, "lean-toolchain")).read().strip()
info = {
    "name": name,
    "file": os.path.relpath(out, root),
    "outcome": "accept",
    "project": os.path.relpath(project, root),
    "toolchain": toolchain,
    "modules": modules,
    "module_cone": cone == "1",
    "export_decls": decls,
    "command": "lake env leanexport " + " ".join(modules)
               + (" -- <the %d constants declared in these modules>" % len(decls) if cone == "1"
                  else " -- " + " ".join(decls) if decls else ""),
    "size": os.path.getsize(out),
    "lines": lines,
    "sha256": digest.hexdigest(),
    "lean_version": header.get("meta", {}).get("lean", {}).get("version"),
    "lean4export_version": header.get("meta", {}).get("exporter", {}).get("version"),
    "export_seconds": round(float(end) - float(start), 1),
}
with open(info_path, "w") as handle:
    json.dump(info, handle, indent=1)
print(f"export-slice: {name}: {info['size']:,} bytes, {lines:,} lines, {info['export_seconds']} s -> {info['file']}")
EOF

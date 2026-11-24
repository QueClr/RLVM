/-
Print the constants declared in the given modules (not in what they import),
one per line, in the form `leanexport` accepts after `--`.

    lake env lean --run bench/lean/ListModuleDecls.lean Mathlib.Data.Nat.Basic ...

Used by bench/export-slice.sh -m. Like leanexport itself, it leaves out
internal names; they are exported anyway when something depends on them.
-/
import Lean
open Lean

def main (args : List String) : IO UInt32 := do
  initSearchPath (← findSysroot)
  let mods := args.map String.toName
  let env ← importModules (mods.toArray.map fun m => { module := m }) {}
  let mut idxs : Array ModuleIdx := #[]
  for m in mods do
    match env.getModuleIdx? m with
    | some i => idxs := idxs.push i
    | none => IO.eprintln s!"ListModuleDecls: module {m} not found"; return 1
  for (c, _) in env.constants.toList do
    if !c.isInternal then
      if let some i := env.getModuleIdxFor? c then
        if idxs.contains i then
          -- only names that survive the round trip through leanexport's parser
          if (Syntax.decodeNameLit ("`" ++ toString c)) == some c then
            IO.println c
  return 0

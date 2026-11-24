/-
Golden-vector generator for rlvm-nat string literals: asks the OFFICIAL kernel (`Lean.Kernel.isDefEq`)
whether a string literal is definitionally equal to `String.ofList [Char.ofNat c1, ..., Char.ofNat cn]`,
i.e. exercises `string_lit_to_constructor` / `try_string_lit_expansion`.

Usage:  lean --run OfficialStrVectors.lean <sysroot> <input-file>
Input line:  `<hex of the UTF-8 bytes|-> <c1,c2,...|-> <d1,d2,...|->`   (`-` is the empty string / list)
Output line: `<hex> <c list> <d list> <kernel verdict for c> <kernel verdict for d>`
-/
import Lean
open Lean

def hexVal (c : Char) : Nat :=
  if c.isDigit then c.toNat - '0'.toNat else c.toNat - 'a'.toNat + 10

def bytesOfHex (s : String) : ByteArray := Id.run do
  let cs := if s == "-" then #[] else s.toList.toArray
  let mut out := ByteArray.empty
  for i in [0:cs.size / 2] do
    out := out.push (UInt8.ofNat (hexVal cs[2 * i]! * 16 + hexVal cs[2 * i + 1]!))
  return out

def parseList (s : String) : List Nat :=
  if s == "-" then [] else (s.splitOn ",").map String.toNat!

def mkCharList (cs : List Nat) : Expr :=
  let char := mkConst ``Char
  cs.foldr (fun c acc => mkApp3 (mkConst ``List.cons [Level.zero]) char (mkApp (mkConst ``Char.ofNat) (mkRawNatLit c)) acc)
    (mkApp (mkConst ``List.nil [Level.zero]) char)

def verdict (env : Environment) (s : String) (cs : List Nat) : IO String := do
  let e := mkApp (mkConst ``String.ofList) (mkCharList cs)
  match Kernel.isDefEq env {} (mkStrLit s) e with
  | .ok b => pure (toString b)
  | .error ex => do pure s!"error:{← (ex.toMessageData {}).toString}"

unsafe def main (args : List String) : IO Unit := do
  initSearchPath args[0]!
  let env ← importModules #[{module := `Init}] {} (loadExts := false)
  for line in (← IO.FS.lines args[1]!) do
    match (line.splitOn " ").filter (· ≠ "") with
    | [hex, cs, ds] =>
      let some s := String.fromUTF8? (bytesOfHex hex) | throw (IO.userError s!"not UTF-8: {hex}")
      IO.println s!"{hex} {cs} {ds} {← verdict env s (parseList cs)} {← verdict env s (parseList ds)}"
    | _ => pure ()

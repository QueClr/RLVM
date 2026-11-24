/-
Golden-vector generator for rlvm-nat: evaluates Nat literal operations with the OFFICIAL kernel
(`Lean.Kernel.whnf`, i.e. `type_checker::reduce_nat` of the toolchain that runs this script) and
prints one result line per input line.

Usage (see gen-official-vectors.sh):
  LEAN_NAT_MAX_SIZE=<bytes> lean --run OfficialNatVectors.lean <sysroot> <label> <input-file>

Input line:  `<op> <a> [<b>]`   op in succ add sub mul pow gcd mod div beq ble land lor xor shiftLeft
             shiftRight; an operand is a decimal numeral or pN (= 2^N), mN (= 2^N - 1), qN (= 2^N + 1).
Output line: `<label> <op> <a> [<b>] <result>` with result one of
  =<decimal>            numeral (below 2^4096)
  =#<bitlen>:<r>        numeral of <bitlen> bits, r = value mod (2^61 - 1)
  =true / =false        Bool.true / Bool.false
  !count                kernel exception "second argument does not fit in a 32-bit unsigned integer"
  !size                 kernel exception about the maximum numeral size
  !other:<message>      any other kernel exception
  ?stuck                whnf returned something that is neither a literal nor Bool.true/false
-/
import Lean
open Lean

def p61 : Nat := 2305843009213693951

def parseArg (s : String) : Nat :=
  if s.startsWith "p" then 2 ^ (s.drop 1).toNat!
  else if s.startsWith "m" then 2 ^ (s.drop 1).toNat! - 1
  else if s.startsWith "q" then 2 ^ (s.drop 1).toNat! + 1
  else s.toNat!

def showNat (n : Nat) : String :=
  if n.log2 < 4096 then s!"={n}" else s!"=#{n.log2 + 1}:{n % p61}"

def containsSub (s sub : String) : Bool := (s.splitOn sub).length > 1

def evalLine (env : Environment) (label line : String) : IO Unit := do
  let toks := (line.splitOn " ").filter (· ≠ "")
  let e? : Option Expr := match toks with
    | [op, a] => some (mkApp (mkConst (Name.str `Nat op)) (mkRawNatLit (parseArg a)))
    | [op, a, b] => some (mkApp2 (mkConst (Name.str `Nat op)) (mkRawNatLit (parseArg a)) (mkRawNatLit (parseArg b)))
    | _ => none
  let some e := e? | return
  let res ← match Kernel.whnf env {} e with
    | .ok (.lit (.natVal n)) => pure (showNat n)
    | .ok (.const c _) =>
      pure (if c == ``Bool.true then "=true" else if c == ``Bool.false then "=false" else "?stuck")
    | .ok _ => pure "?stuck"
    | .error ex => do
      let msg ← (ex.toMessageData {}).toString
      pure (if containsSub msg "32-bit" then "!count"
            else if containsSub msg "maximum" then "!size"
            else s!"!other:{msg}")
  IO.println s!"{label} {line} {res}"

unsafe def main (args : List String) : IO Unit := do
  initSearchPath args[0]!
  let env ← importModules #[{module := `Init}] {} (loadExts := false)
  let lines ← IO.FS.lines args[2]!
  for line in lines do
    evalLine env args[1]! line

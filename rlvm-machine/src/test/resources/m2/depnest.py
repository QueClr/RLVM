#!/usr/bin/env python3
"""depnest.py N OUT [open]: axiom T : Type; axiom z : T; def G : T -> Type := fun _ => T; axiom c : (x : T) -> G x;
def big : T := c (c (.. z)) with N applications. Every application is dependent, so INFER evaluates each argument."""
import sys, json
n=int(sys.argv[1]); out=sys.argv[2]; opn=len(sys.argv)>3
L=[]
L.append({"meta":{"exporter":{"name":"lean4export","version":"3.1.0"},"format":{"version":"3.1.0"},"lean":{"githash":"611c117bfaf7b7ae1cfcf246ce6fba31e8f509e9","version":"4.36.0"}}})
for i,nm in enumerate(["T","z","G","c","big","x"],1):
    L.append({"in":i,"str":{"pre":0,"str":nm}})
L.append({"il":1,"succ":0})
L.append({"il":2,"succ":1})
L.append({"ie":0,"sort":1})
L.append({"axiom":{"isUnsafe":False,"levelParams":[],"name":1,"type":0}})
L.append({"const":{"name":1,"us":[]},"ie":1})
L.append({"axiom":{"isUnsafe":False,"levelParams":[],"name":2,"type":1}})
L.append({"forallE":{"binderInfo":"default","body":0,"name":6,"type":1},"ie":2})   # T -> Type
L.append({"lam":{"binderInfo":"default","body":1,"name":6,"type":1},"ie":3})        # fun _ => T
L.append({"def":{"all":[3],"hints":{"regular":1},"levelParams":[],"name":3,"safety":"safe","type":2,"value":3}})
L.append({"const":{"name":3,"us":[]},"ie":4})
L.append({"bvar":0,"ie":5})
L.append({"app":{"arg":5,"fn":4},"ie":6})                                           # G #0
L.append({"forallE":{"binderInfo":"default","body":6,"name":6,"type":1},"ie":7})   # (x : T) -> G x
L.append({"axiom":{"isUnsafe":False,"levelParams":[],"name":4,"type":7}})
L.append({"const":{"name":2,"us":[]},"ie":8})
L.append({"const":{"name":4,"us":[]},"ie":9})
e=10; t=5 if opn else 8
for i in range(n):
    L.append({"app":{"arg":t,"fn":9},"ie":e}); t=e; e+=1
ty=1
if opn:  # def big : T -> T := fun x => c (c (.. x))
    L.append({"lam":{"binderInfo":"default","body":t,"name":6,"type":1},"ie":e}); t=e; e+=1
    L.append({"forallE":{"binderInfo":"default","body":1,"name":6,"type":1},"ie":e}); ty=e; e+=1
L.append({"def":{"all":[5],"hints":"opaque","levelParams":[],"name":5,"safety":"safe","type":ty,"value":t}})
with open(out,"w") as fh:
    for l in L: fh.write(json.dumps(l,separators=(",",":"))+"\n")

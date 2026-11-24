
### Experiment 1, whole Init - JVM mode (GraalVM CE 25 JDK, HotSpot + Graal JIT, SerialGC), 2088996 spines

| variant | process instr (G) | spread over reps (G) | net of `null` (G) | instr / spine | first pass wall (ms) | process wall (s) | max RSS (MB) |
|---|---|---|---|---|---|---|---|
| none: setup only: load cache, derive masks, find spines | 6.190 | 0.315 | - | - | - | 0.64 | 669 |
| null: driver loop with an empty instantiator | 6.676 | 0.081 | - | - | 23 | 0.63 | 669 |
| a0: (a) generic interpretation | 32.348 | 1.771 | 25.672 | 12289 | 1055 | 1.69 | 714 |
| a1: (a) generic + closed-term memo | 27.847 | 2.261 | 21.171 | 10135 | 894 | 1.52 | 722 |
| elab: elaboration of all used constants, nothing executed | 11.530 | 0.518 | 4.854 | 2324 | 131 | 0.66 | 710 |
| b: (b) code objects, plain stepper (includes elaboration) | 27.684 | 3.844 | 21.008 | 10057 | 866 | 1.50 | 735 |
| c.never: (c) promotion machinery present, threshold never reached | 26.571 | 7.426 | 19.895 | 9524 | 819 | 1.52 | 738 |
| c.T1000.i2: (c) promote at heat 1000, probes inlined | 97.161 | 3.081 | 90.485 | 43315 | 1503 | 2.64 | 843 |
| c.T1000.i0: (c) promote at heat 1000, call-threaded | 38.461 | 5.690 | 31.785 | 15215 | 1302 | 1.97 | 837 |
| c.T10000.i2: (c) promote at heat 10000, probes inlined | 48.486 | 11.487 | 41.810 | 20014 | 1109 | 1.84 | 850 |
| c.T10000.i0: (c) promote at heat 10000, call-threaded | 34.360 | 2.916 | 27.684 | 13252 | 1226 | 1.89 | 820 |
| c.T100000.i2: (c) promote at heat 100000, probes inlined | 35.872 | 9.418 | 29.196 | 13976 | 1125 | 1.83 | 811 |
| c.T100000.i0: (c) promote at heat 100000, call-threaded | 32.125 | 5.941 | 25.449 | 12182 | 1181 | 1.76 | 798 |
| c.T1000000.i2: (c) promote at heat 1000000, probes inlined | 31.557 | 0.566 | 24.881 | 11911 | 834 | 1.60 | 743 |
| c.T1000000.i0: (c) promote at heat 1000000, call-threaded | 29.771 | 0.649 | 23.095 | 11056 | 839 | 1.49 | 741 |

Derived: driver = 233 instr/spine; elaboration = 4.854 G (122012 instr per elaborated constant); (b) without elaboration = 7733 instr/spine; (a0)/(b) = 1.22x, (a1)/(b) = 1.01x, (a1)/(b without elaboration) = 1.31x.
Steady state (passes 2 and 3: every value already interned): (a1) 4520, (b) 2833 instr/spine net of the driver.
Match mode: one pass that matches every domain against an existing value and builds only the result type = 5911 instr/spine net of the driver (build pass in steady state: 2833).

| threshold | inlining | roots compiled | spines run in compiled code | process instr (G) | extra over (b) (G) | compile-only run `cc` (G) | compile cost per root (M instr) | saved by using the code (G) |
|---|---|---|---|---|---|---|---|---|
| 1000 | probes inlined | 184 | 1155867 (55.3%) | 97.161 | +69.477 | 99.545 | 397 | 2.384 |
| 1000 | call-threaded | 184 | 1398232 (66.9%) | 38.461 | +10.777 | 39.506 | 70 | 1.045 |
| 10000 | probes inlined | 33 | 804226 (38.5%) | 48.486 | +20.802 | 53.296 | 810 | 4.810 |
| 10000 | call-threaded | 33 | 842064 (40.3%) | 34.360 | +6.676 | 33.515 | 210 | -0.845 |
| 100000 | probes inlined | 2 | 127049 (6.1%) | 35.872 | +8.188 | 30.435 | 1932 | -5.437 |
| 100000 | call-threaded | 2 | 131997 (6.3%) | 32.125 | +4.441 | 30.081 | 1755 | -2.044 |
| 1000000 | probes inlined | 0 | 0 (0.0%) | 31.557 | +3.873 | 26.418 | - | -5.139 |
| 1000000 | call-threaded | 0 | 0 (0.0%) | 29.771 | +2.087 | 31.477 | - | 1.706 |

### Experiment 1, whole Init - native image, GraalVM CE 25.5.5.0.3, 2088996 spines

| variant | process instr (G) | spread over reps (G) | net of `null` (G) | instr / spine | first pass wall (ms) | process wall (s) | max RSS (MB) |
|---|---|---|---|---|---|---|---|
| none: setup only: load cache, derive masks, find spines | 2.708 | 0.000 | - | - | - | 0.32 | 269 |
| null: driver loop with an empty instantiator | 3.178 | 0.000 | - | - | 22 | 0.33 | 269 |
| a0: (a) generic interpretation | 9.723 | 0.000 | 6.545 | 3133 | 980 | 1.29 | 344 |
| a1: (a) generic + closed-term memo | 7.592 | 0.000 | 4.414 | 2113 | 857 | 1.17 | 355 |
| elab: elaboration of all used constants, nothing executed | 3.800 | 0.000 | 0.622 | 298 | 142 | 0.44 | 352 |
| b: (b) code objects, plain stepper (includes elaboration) | 6.377 | 0.000 | 3.199 | 1531 | 799 | 1.13 | 392 |
| c.never: (c) promotion machinery present, threshold never reached | 6.494 | 0.000 | 3.316 | 1587 | 766 | 1.07 | 395 |
| c.T1000.i2: (c) promote at heat 1000, probes inlined | 68.876 | 0.018 | 65.698 | 31450 | 753 | 1.65 | 484 |
| c.T1000.i0: (c) promote at heat 1000, call-threaded | 15.122 | 0.001 | 11.944 | 5718 | 748 | 1.06 | 464 |
| c.T10000.i2: (c) promote at heat 10000, probes inlined | 21.678 | 0.016 | 18.500 | 8856 | 696 | 1.00 | 474 |
| c.T10000.i0: (c) promote at heat 10000, call-threaded | 8.255 | 0.000 | 5.077 | 2430 | 768 | 1.07 | 465 |
| c.T100000.i2: (c) promote at heat 100000, probes inlined | 7.355 | 0.000 | 4.177 | 2000 | 748 | 1.08 | 458 |
| c.T100000.i0: (c) promote at heat 100000, call-threaded | 6.627 | 0.000 | 3.449 | 1651 | 760 | 1.06 | 422 |
| c.T1000000.i2: (c) promote at heat 1000000, probes inlined | 6.494 | 0.000 | 3.316 | 1587 | 729 | 1.02 | 396 |
| c.T1000000.i0: (c) promote at heat 1000000, call-threaded | 6.494 | 0.000 | 3.316 | 1587 | 762 | 1.07 | 396 |

Derived: driver = 225 instr/spine; elaboration = 0.622 G (15635 instr per elaborated constant); (b) without elaboration = 1234 instr/spine; (a0)/(b) = 2.05x, (a1)/(b) = 1.38x, (a1)/(b without elaboration) = 1.71x.
Steady state (passes 2 and 3: every value already interned): (a1) 2030, (b) 1167 instr/spine net of the driver.
Match mode: one pass that matches every domain against an existing value and builds only the result type = 1124 instr/spine net of the driver (build pass in steady state: 1167).

| threshold | inlining | roots compiled | spines run in compiled code | process instr (G) | extra over (b) (G) | compile-only run `cc` (G) | compile cost per root (M instr) | saved by using the code (G) |
|---|---|---|---|---|---|---|---|---|
| 1000 | probes inlined | 184 | 1036389 (49.6%) | 68.876 | +62.499 | 69.284 | 341 | 0.407 |
| 1000 | call-threaded | 184 | 1400074 (67.0%) | 15.122 | +8.745 | 15.368 | 48 | 0.245 |
| 10000 | probes inlined | 33 | 800250 (38.3%) | 21.678 | +15.301 | 21.919 | 467 | 0.241 |
| 10000 | call-threaded | 33 | 845625 (40.5%) | 8.255 | +1.878 | 8.396 | 58 | 0.141 |
| 100000 | probes inlined | 2 | 129736 (6.2%) | 7.355 | +0.978 | 7.369 | 437 | 0.013 |
| 100000 | call-threaded | 2 | 137515 (6.6%) | 6.627 | +0.250 | 6.640 | 73 | 0.013 |
| 1000000 | probes inlined | 0 | 0 (0.0%) | 6.494 | +0.117 | 6.494 | - | 0.000 |
| 1000000 | call-threaded | 0 | 0 (0.0%) | 6.494 | +0.117 | 6.494 | - | 0.000 |

### Experiment 1, whole Init - native image, Oracle GraalVM 25.0.4, 2088996 spines

| variant | process instr (G) | spread over reps (G) | net of `null` (G) | instr / spine | first pass wall (ms) | process wall (s) | max RSS (MB) |
|---|---|---|---|---|---|---|---|
| none: setup only: load cache, derive masks, find spines | 1.750 | 0.000 | - | - | - | 0.23 | 306 |
| null: driver loop with an empty instantiator | 2.189 | 0.000 | - | - | 20 | 0.24 | 306 |
| a0: (a) generic interpretation | 8.053 | 0.000 | 5.864 | 2807 | 792 | 1.02 | 356 |
| a1: (a) generic + closed-term memo | 6.149 | 0.000 | 3.960 | 1896 | 738 | 0.98 | 368 |
| elab: elaboration of all used constants, nothing executed | 2.863 | 0.000 | 0.674 | 323 | 134 | 0.36 | 359 |
| b: (b) code objects, plain stepper (includes elaboration) | 5.172 | 0.000 | 2.983 | 1428 | 692 | 0.94 | 402 |
| c.never: (c) promotion machinery present, threshold never reached | 5.288 | 0.000 | 3.099 | 1483 | 696 | 0.94 | 403 |
| c.T1000.i2: (c) promote at heat 1000, probes inlined | 58.105 | 0.051 | 55.916 | 26767 | 749 | 1.27 | 600 |
| c.T1000.i0: (c) promote at heat 1000, call-threaded | 13.771 | 0.009 | 11.582 | 5544 | 830 | 1.07 | 477 |
| c.T10000.i2: (c) promote at heat 10000, probes inlined | 17.951 | 0.013 | 15.762 | 7545 | 816 | 1.06 | 577 |
| c.T10000.i0: (c) promote at heat 10000, call-threaded | 7.014 | 0.001 | 4.825 | 2310 | 726 | 0.96 | 473 |
| c.T100000.i2: (c) promote at heat 100000, probes inlined | 6.117 | 0.000 | 3.928 | 1880 | 708 | 0.97 | 489 |
| c.T100000.i0: (c) promote at heat 100000, call-threaded | 5.425 | 0.000 | 3.236 | 1549 | 679 | 0.93 | 450 |
| c.T1000000.i2: (c) promote at heat 1000000, probes inlined | 5.288 | 0.000 | 3.099 | 1483 | 702 | 0.95 | 403 |
| c.T1000000.i0: (c) promote at heat 1000000, call-threaded | 5.288 | 0.000 | 3.099 | 1483 | 686 | 0.94 | 403 |

Derived: driver = 210 instr/spine; elaboration = 0.674 G (16942 instr per elaborated constant); (b) without elaboration = 1105 instr/spine; (a0)/(b) = 1.97x, (a1)/(b) = 1.33x, (a1)/(b without elaboration) = 1.72x.
Steady state (passes 2 and 3: every value already interned): (a1) 1843, (b) 1090 instr/spine net of the driver.
Match mode: one pass that matches every domain against an existing value and builds only the result type = 1099 instr/spine net of the driver (build pass in steady state: 1090).

| threshold | inlining | roots compiled | spines run in compiled code | process instr (G) | extra over (b) (G) | compile-only run `cc` (G) | compile cost per root (M instr) | saved by using the code (G) |
|---|---|---|---|---|---|---|---|---|
| 1000 | probes inlined | 184 | 1109777 (53.1%) | 58.105 | +52.934 | 58.354 | 288 | 0.248 |
| 1000 | call-threaded | 184 | 1412268 (67.6%) | 13.771 | +8.599 | 14.075 | 48 | 0.305 |
| 10000 | probes inlined | 33 | 800992 (38.3%) | 17.951 | +12.779 | 18.132 | 389 | 0.181 |
| 10000 | call-threaded | 33 | 845263 (40.5%) | 7.014 | +1.843 | 7.182 | 57 | 0.168 |
| 100000 | probes inlined | 2 | 129060 (6.2%) | 6.117 | +0.945 | 6.128 | 420 | 0.011 |
| 100000 | call-threaded | 2 | 136884 (6.6%) | 5.425 | +0.253 | 5.438 | 75 | 0.013 |
| 1000000 | probes inlined | 0 | 0 (0.0%) | 5.288 | +0.116 | 5.288 | - | 0.000 |
| 1000000 | call-threaded | 0 | 0 (0.0%) | 5.288 | +0.116 | 5.288 | - | 0.000 |

### JVM mode, steady state: (21 passes - 11 passes) / 10, instructions per spine net of the driver

One process pair per row. Unreliable to about +-40% (see the long-run table below): the two processes do not JIT-compile alike.

Driver: 103 instr/spine (whole Init), 116 (hot subset).

| whole Init (2,088,996 spines) | instr / spine |
|---|---|
| (a) generic | 1711 |
| (a) generic + closed-term memo | 1517 |
| (b) stepper | 975 |
| (b) match mode: domains matched, result built | 122 |
| (b) stepper, arena access = int[] | 1123 |
| (b) stepper, arena access = sun.misc.Unsafe | 539 |
| (b) stepper, arena access = MemorySegment.getAtIndex(JAVA_INT) | 763 |
| (b) stepper, arena access = MemorySegment.get(JAVA_INT_UNALIGNED) | 743 |

| hot subset (184 constants, 1,619,861 spines) | instr / spine | saving vs (b) | compile cost per root (M instr, from the hot-jvm batch) | break-even heat |
|---|---|---|---|---|
| (a1) generic + memo | 1411 | - | - | - |
| (b) stepper | 1099 | - | - | - |
| (c) call-threaded | 488 | 611 | 165 | 269679 |
| (c) APP probes inlined | 848 | 251 | 346 | 1376538 |
| (c) all probes inlined | 648 | 451 | 449 | 997475 |
| (c) all probes inlined, callIndirect | 443 | 656 | 449 | 685037 |

| experiment 2, JVM steady state | recursive (G / pass) | worklist `wl` | worklist `wl2` | wl / rec | wl2 / rec |
|---|---|---|---|---|---|
| hash-consed | 1.097 | 1.433 | 1.522 | **1.306** | **1.388** |
| bump-allocated, no interning | 1.037 | 1.238 | 0.569 | **1.194** | **0.549** |

### JVM mode, steady state from long runs: (longer run - shorter run) / extra passes, instr/spine INCLUDING the driver

Each estimate compares two separate JVM processes (21 vs 41 or 41 vs 81 passes). The spread between estimates is JIT non-determinism, not measurement error.

| variant | estimates | min | max |
|---|---|---|---|
| driver | 127, 124 | 124 | 127 |
| (a) generic + memo | 1273, 1686 | 1273 | 1686 |
| (b) stepper | 742, 926, 767, 894 | 742 | 926 |
| (b2) stepper with the probe expanded in place | 665, 991, 718 | 665 | 991 |
| (b) match mode pass | 760, 823 | 760 | 823 |
| (b) stepper, Unsafe arena | 989 | 989 | 989 |

### JVM mode with C2 as host JIT and no tiered compilation (-XX:-UseJVMCICompiler -XX:-TieredCompilation), whole Init

| variant | process instr (G), median of 3 | min - max | net of `null` (G) | first pass wall (ms) |
|---|---|---|---|---|
| none | 3.704 | 3.623 - 3.773 | - | - |
| null | 3.996 | 3.783 - 4.126 | - | 19 |
| a0 | 9.619 | 9.611 - 9.795 | 5.623 | 932 |
| a1 | 7.997 | 7.916 - 8.289 | 4.001 | 810 |
| elab | 5.851 | 5.737 - 6.076 | 1.855 | 161 |
| b | 8.864 | 8.851 - 8.936 | 4.868 | 734 |
| b2 | 9.000 | 8.922 - 9.673 | 5.004 | 736 |
| c.never | 9.504 | 9.313 - 9.777 | 5.508 | 762 |
| c.T100000.i0 | 11.787 | 11.461 - 12.398 | 7.791 | 1007 |
| c.T100000.i2 | 12.971 | 12.377 - 13.158 | 8.975 | 1049 |
| c.T10000.i0 | 13.805 | 13.749 - 13.853 | 9.809 | 976 |
| c.T10000.i2 | 29.090 | 28.767 - 29.519 | 25.094 | 1080 |

Steady state, (41 passes - 21 passes) / 20, instr/spine INCLUDING the driver; one estimate per repetition:

| variant | estimates |
|---|---|
| driver | 119 |
| (a) generic + memo | 1501, 1472 |
| (b) stepper | 1076, 1033 |
| (b2) stepper, probe expanded in place | 1134, 1161 |
| (b) match-mode pass | 986 |

| experiment 2, steady state (21 passes - 11 passes) / 10 | recursive (G / pass) | wl / rec | wl2 / rec |
|---|---|---|---|
| hash-consed | 1.052 | **2.452** | **1.564** |
| bump-allocated | 0.853 | **2.528** | **1.428** |

### Base tier in different native images (plain images, whole Init)

| image | none (G) | driver instr/spine | (a1) first pass, instr/spine | (b) first pass incl. elaboration | (a1) steady | (b) steady | (b2) first pass | (b2) steady | match mode pass |
|---|---|---|---|---|---|---|---|---|---|
| GraalVM CE, default (-O2) | 2.699 | 225 | 2104 | 1531 | 2021 | 1167 | 1314 | 927 | 923 |
| GraalVM CE, -O3 | 2.226 | 209 | 1919 | 1344 | 1874 | 1066 | - | - | - |
| Oracle GraalVM, default (-O2, ML-inferred profile) | 1.749 | 210 | 1896 | 1415 | 1842 | 1085 | 1345 | 998 | 880 |
| Oracle GraalVM, --pgo with a profile of this very workload | 1.465 | 217 | 1987 | 1264 | 1941 | 996 | - | - | - |

### Calibration: instructions per primitive operation ((2N run - N run) / N, minus the empty loop)

| runtime | empty loop iteration | mkApp, value exists (hit) | the same probe hand-inlined, arrays in locals | mkApp, new value (insert) | closure with 2 captures (hit) |
|---|---|---|---|---|---|
| jvm | 9.6 | 38 | 33 | n/a (run too short for the JVM) | 48 |
| nce | 11.0 | 110 | 45 | 156 | 192 |
| nee | 11.0 | 96 | 50 | 125 | 187 |

### Hot subset - JVM mode (GraalVM CE 25 JDK, HotSpot + Graal JIT, SerialGC)

| configuration | 1 pass (G) | 3 passes (G) | first pass, instr/spine net of driver | steady state, instr/spine net of driver | compile cost per root (M instr) | saving per spine vs (b), steady | break-even heat |
|---|---|---|---|---|---|---|---|
| (a1) generic + memo | 18.279 | 39.753 | 6939 | 6396 | - | - | - |
| (b) stepper | 16.172 | 35.487 | 5638 | 5730 | - | - | - |
| (c) call-threaded | 32.151 | 37.705 | -3204 | 1482 | 165 | 4248 | 38769 |
| (c) APP probes inlined | 71.885 | 82.585 | 774 | 3070 | 346 | 2659 | 129962 |
| (c) all probes inlined | 84.082 | 94.291 | -3492 | 2919 | 449 | 2811 | 159899 |
| (c) all probes inlined, entered with callIndirect | 81.561 | 88.783 | -5049 | 1997 | 449 | 3733 | 120404 |
| (c) all probes inlined, first-tier compilation requested | 61.080 | 81.703 | 6690 | 6133 | 235 | -404 | never |

### Hot subset - native image, GraalVM CE 25.5.5.0.3

| configuration | 1 pass (G) | 3 passes (G) | first pass, instr/spine net of driver | steady state, instr/spine net of driver | compile cost per root (M instr) | saving per spine vs (b), steady | break-even heat |
|---|---|---|---|---|---|---|---|
| (a1) generic + memo | 6.413 | 12.777 | 1810 | 1739 | - | - | - |
| (b) stepper | 5.253 | 9.297 | 1094 | 1023 | - | - | - |
| (c) call-threaded | 13.605 | 17.136 | 920 | 865 | 47 | 158 | 296287 |
| (c) APP probes inlined | 52.552 | 55.373 | 750 | 646 | 260 | 377 | 689196 |
| (c) all probes inlined | 64.900 | 67.589 | 735 | 605 | 327 | 418 | 782861 |
| (c) all probes inlined, entered with callIndirect | 64.820 | 67.391 | 686 | 569 | 327 | 455 | 720126 |
| (c) all probes inlined, first-tier compilation requested | 44.218 | 60.195 | 9614 | 4707 | 137 | -3684 | never |

### Hot subset - native image, Oracle GraalVM 25.0.4

| configuration | 1 pass (G) | 3 passes (G) | first pass, instr/spine net of driver | steady state, instr/spine net of driver | compile cost per root (M instr) | saving per spine vs (b), steady | break-even heat |
|---|---|---|---|---|---|---|---|
| (a1) generic + memo | 5.006 | 10.763 | 1610 | 1567 | - | - | - |
| (b) stepper | 4.026 | 7.820 | 1005 | 961 | - | - | - |
| (c) call-threaded | 12.264 | 15.412 | 790 | 762 | 47 | 199 | 234187 |
| (c) APP probes inlined | 44.606 | 47.516 | 766 | 688 | 223 | 273 | 815465 |
| (c) all probes inlined | 55.127 | 57.947 | 756 | 660 | 280 | 301 | 931471 |
| (c) all probes inlined, entered with callIndirect | 54.986 | 57.607 | 670 | 599 | 280 | 362 | 772720 |
| (c) all probes inlined, first-tier compilation requested | 38.726 | 53.059 | 8122 | 4214 | 126 | -3253 | never |

### Experiment 2 - recursive vs worklist NbE (59,979 declaration types of Init, one pass)

| runtime | values | none (G) | recursive (G) | worklist `wl` (G) | worklist `wl2` (G) | recursive net (G) | wl / recursive | wl2 / recursive | pass wall rec / wl / wl2 (ms) |
|---|---|---|---|---|---|---|---|---|---|
| jvm | hash-consed | 6.177 | 8.304 | 10.920 | 12.200 | 2.127 | **2.230** | **2.832** | 236 / 284 / 419 |
| jvm | bump-allocated, no interning | 6.227 | 7.611 | 9.358 | 13.066 | 1.384 | **2.262** | **4.941** | 75 / 102 / 237 |
| nce | hash-consed | 2.651 | 4.516 | 5.111 | 4.850 | 1.865 | **1.319** | **1.179** | 432 / 453 / 372 |
| nce | bump-allocated, no interning | 2.651 | 3.821 | 4.416 | 4.155 | 1.170 | **1.509** | **1.285** | 74 / 87 / 79 |
| nee | hash-consed | 1.720 | 3.485 | 4.185 | 3.874 | 1.765 | **1.397** | **1.220** | 355 / 326 / 362 |
| nee | bump-allocated, no interning | 1.720 | 2.912 | 3.607 | 3.297 | 1.192 | **1.583** | **1.323** | 66 / 89 / 77 |

### Experiment 3 - arena access (whole Init, arena and table pre-sized)

| runtime | access | none (G) | (b) (G) | (b) net (G) | vs int[] | (a1) net (G) | vs int[] | (b) pass wall (ms) |
|---|---|---|---|---|---|---|---|---|
| jvm | int[] (bounds-checked) | 6.222 | 26.687 | 20.465 | 1.00x | 20.207 | 1.00x | 752 |
| jvm | sun.misc.Unsafe | 6.241 | 20.709 | 14.468 | 0.71x | 18.363 | 0.91x | 761 |
| jvm | MemorySegment.getAtIndex(JAVA_INT) | 6.293 | 18.836 | 12.543 | 0.61x | 21.504 | 1.06x | 771 |
| jvm | MemorySegment.get(JAVA_INT_UNALIGNED) | 6.250 | 18.699 | 12.449 | 0.61x | 21.003 | 1.04x | 772 |
| nce | int[] (bounds-checked) | 2.706 | 6.374 | 3.668 | 1.00x | 4.866 | 1.00x | 793 |
| nce | sun.misc.Unsafe | 2.662 | 6.385 | 3.723 | 1.01x | 4.869 | 1.00x | 756 |
| nce | MemorySegment.getAtIndex(JAVA_INT) | 2.768 | 8.292 | 5.524 | 1.51x | 6.725 | 1.38x | 950 |
| nce | MemorySegment.get(JAVA_INT_UNALIGNED) | 2.768 | 8.157 | 5.389 | 1.47x | 6.588 | 1.35x | 938 |
| nee | int[] (bounds-checked) | 1.749 | 5.143 | 3.394 | 1.00x | 4.399 | 1.00x | 736 |

### Experiment 4, whole Std - native image, GraalVM CE 25.5.5.0.3, 3115533 spines

| variant | process instr (G) | spread over reps (G) | net of `null` (G) | instr / spine | first pass wall (ms) | process wall (s) | max RSS (MB) |
|---|---|---|---|---|---|---|---|
| none: setup only: load cache, derive masks, find spines | 4.558 | 0.000 | - | - | - | 0.52 | 600 |
| null: driver loop with an empty instantiator | 5.270 | 0.000 | - | - | 33 | 0.56 | 600 |
| a0: (a) generic interpretation | 22.122 | 0.000 | 16.852 | 5409 | 1932 | 2.46 | 600 |
| a1: (a) generic + closed-term memo | 17.193 | 0.000 | 11.923 | 3827 | 1690 | 2.23 | 617 |
| elab: elaboration of all used constants, nothing executed | 6.797 | 0.000 | 1.527 | 490 | 314 | 0.88 | 601 |
| b: (b) code objects, plain stepper (includes elaboration) | 10.981 | 0.000 | 5.711 | 1833 | 1398 | 1.96 | 676 |
| c.never: (c) promotion machinery present, threshold never reached | 11.157 | 0.000 | 5.887 | 1890 | 1412 | 1.97 | 677 |
| c.T1000.i2: (c) promote at heat 1000, probes inlined | 109.789 | 0.164 | 104.519 | 33548 | 1560 | 3.01 | 779 |
| c.T1000.i0: (c) promote at heat 1000, call-threaded | 24.044 | 0.002 | 18.774 | 6026 | 1509 | 2.08 | 746 |
| c.T10000.i2: (c) promote at heat 10000, probes inlined | 31.227 | 0.015 | 25.957 | 8331 | 1498 | 2.13 | 750 |
| c.T10000.i0: (c) promote at heat 10000, call-threaded | 13.698 | 0.000 | 8.428 | 2705 | 1455 | 2.05 | 747 |
| c.T100000.i2: (c) promote at heat 100000, probes inlined | 12.692 | 0.001 | 7.422 | 2382 | 1439 | 2.06 | 746 |
| c.T100000.i0: (c) promote at heat 100000, call-threaded | 11.346 | 0.000 | 6.076 | 1950 | 1386 | 1.94 | 707 |
| c.T1000000.i2: (c) promote at heat 1000000, probes inlined | 11.157 | 0.000 | 5.887 | 1890 | 1376 | 1.93 | 677 |
| c.T1000000.i0: (c) promote at heat 1000000, call-threaded | 11.157 | 0.000 | 5.887 | 1890 | 1408 | 1.96 | 677 |

Derived: driver = 229 instr/spine; elaboration = 1.527 G (21904 instr per elaborated constant); (b) without elaboration = 1343 instr/spine; (a0)/(b) = 2.95x, (a1)/(b) = 2.09x, (a1)/(b without elaboration) = 2.85x.

| threshold | inlining | roots compiled | spines run in compiled code | process instr (G) | extra over (b) (G) | compile-only run `cc` (G) | compile cost per root (M instr) | saved by using the code (G) |
|---|---|---|---|---|---|---|---|---|
| 1000 | probes inlined | 255 | 1603945 (51.5%) | 109.789 | +98.808 | 110.404 | 389 | 0.615 |
| 1000 | call-threaded | 255 | 2073185 (66.5%) | 24.044 | +13.063 | 24.393 | 52 | 0.349 |
| 10000 | probes inlined | 50 | 1258262 (40.4%) | 31.227 | +20.245 | 31.630 | 409 | 0.404 |
| 10000 | call-threaded | 50 | 1292993 (41.5%) | 13.698 | +2.717 | 13.909 | 55 | 0.211 |
| 100000 | probes inlined | 3 | 280240 (9.0%) | 12.692 | +1.711 | 12.735 | 526 | 0.043 |
| 100000 | call-threaded | 3 | 288217 (9.3%) | 11.346 | +0.365 | 11.377 | 73 | 0.031 |
| 1000000 | probes inlined | 0 | 0 (0.0%) | 11.157 | +0.176 | 11.157 | - | 0.000 |
| 1000000 | call-threaded | 0 | 0 (0.0%) | 11.157 | +0.176 | 11.157 | - | 0.000 |

### Experiment 4, whole Std - JVM mode (GraalVM CE 25 JDK, HotSpot + Graal JIT, SerialGC), 3115533 spines

| variant | process instr (G) | spread over reps (G) | net of `null` (G) | instr / spine | first pass wall (ms) | process wall (s) | max RSS (MB) |
|---|---|---|---|---|---|---|---|
| none: setup only: load cache, derive masks, find spines | 7.608 | 0.143 | - | - | - | 0.69 | 932 |
| null: driver loop with an empty instantiator | 8.803 | 0.780 | - | - | 29 | 0.74 | 927 |
| a0: (a) generic interpretation | 39.564 | 3.497 | 30.761 | 9873 | 1618 | 2.30 | 995 |
| a1: (a) generic + closed-term memo | 39.663 | 7.069 | 30.860 | 9905 | 1349 | 2.01 | 1007 |
| elab: elaboration of all used constants, nothing executed | 15.422 | 1.828 | 6.619 | 2125 | 209 | 0.95 | 1029 |
| b: (b) code objects, plain stepper (includes elaboration) | 34.163 | 4.417 | 25.360 | 8140 | 1131 | 1.82 | 1043 |
| c.never: (c) promotion machinery present, threshold never reached | 36.711 | 5.227 | 27.908 | 8958 | 1135 | 1.91 | 1066 |
| c.T1000.i0: (c) promote at heat 1000, call-threaded | 47.317 | 6.186 | 38.514 | 12362 | 1424 | 2.12 | 1156 |
| c.T10000.i0: (c) promote at heat 10000, call-threaded | 37.149 | 5.608 | 28.346 | 9098 | 1523 | 2.27 | 1144 |

Derived: driver = 384 instr/spine; elaboration = 6.619 G (94948 instr per elaborated constant); (b) without elaboration = 6015 instr/spine; (a0)/(b) = 1.21x, (a1)/(b) = 1.22x, (a1)/(b without elaboration) = 1.65x.

| threshold | inlining | roots compiled | spines run in compiled code | process instr (G) | extra over (b) (G) | compile-only run `cc` (G) | compile cost per root (M instr) | saved by using the code (G) |
|---|---|---|---|---|---|---|---|---|
| 1000 | call-threaded | 255 | 2068165 (66.4%) | 47.317 | +13.154 | 53.340 | 65 | 6.023 |
| 10000 | call-threaded | 50 | 1285180 (41.3%) | 37.149 | +2.986 | 42.778 | 121 | 5.629 |

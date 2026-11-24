#!/bin/sh
# bench/baselines/tenet-jvm-c2.sh EXPORT.ndjson      (inside the rlvm-arena container)
#
# Variant of tenet-jvm.sh (written by hand, not generated): the same checker
# and the same launcher, but with HotSpot's C2 as the optimising JIT compiler
# instead of GraalVM's Graal compiler.
#
# Why: the arena runs tenet-jvm on the `jdk` of its nix flake, a stock OpenJDK,
# whose top-tier compiler is C2. The container's `java` is the project's
# GraalVM CE 25, which uses the Graal compiler (libgraal) by default
# (UseJVMCICompiler=true). -XX:-UseJVMCICompiler makes the same JVM compile
# with C2, which is as close to the arena's setup as this JDK gets.
#
# bin/tenet-jvm takes its JVM options from JAVA_OPTS and falls back to
# "-XX:+UseParallelGC -XX:MaxRAMPercentage=75"; those two are kept.
JAVA_OPTS="-XX:+UseParallelGC -XX:MaxRAMPercentage=75 -XX:-UseJVMCICompiler"
export JAVA_OPTS
case "$0" in */*) here="${0%/*}" ;; *) here=. ;; esac
exec "$here/tenet-jvm.sh" "$@"

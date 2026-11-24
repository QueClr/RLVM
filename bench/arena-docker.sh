#!/usr/bin/env bash
# bench/arena-docker.sh - run a command inside the rlvm-arena measurement image.
#
#   bench/arena-docker.sh [options] [--] [command [args...]]
#
# The project root is mounted at the same absolute path as on the host, the
# command runs as the calling uid:gid (no root-owned files are left behind),
# JAVA_HOME points at .toolchain/graalvm-ce-25, the Rust/Lean toolchain homes
# persist in work/cache/, and the container gets exactly the privilege that
# `perf stat -e instructions:u` needs (CAP_PERFMON). See bench/README.md.
set -euo pipefail

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
ROOT="$(cd "$SELF_DIR/.." && pwd -P)"
IMAGE="${RLVM_ARENA_IMAGE:-rlvm-arena:latest}"
LABEL="rlvm-arena=1"

usage() {
  cat <<EOF
Usage: bench/arena-docker.sh [options] [--] [command [args...]]

Runs COMMAND (default: an interactive bash) in a fresh container of $IMAGE.

Image:
  --build [--no-cache]    build the image from bench/docker/ and exit

Mounts (default: project root read-only, work/ read-write):
  --rw                    mount the whole project root read-write
  --mount SRC[:DST][:ro]  additional bind mount (DST defaults to SRC)

Privileges:
  --perf LEVEL            perfmon (default) | seccomp | sysadmin | privileged | none
                          perfmon:    all capabilities dropped except CAP_PERFMON,
                                      no-new-privileges; enough for perf stat
                          seccomp:    perfmon + seccomp=unconfined
                          sysadmin:   seccomp + CAP_SYS_ADMIN
                          privileged: docker --privileged
                          none:       no perf at all (hardware counters fail)
  --root                  stay root inside the container (image debugging,
                          apt-get); files it creates under the writable
                          mounts are chowned back to the caller on exit
  --no-network            run with --network none

Resources:
  --arena-limits          8 CPUs (performance cores) and 16 GB RAM, the size
                          of the arena runner; affects JVM ergonomics
  --cpus N | --cpuset-cpus LIST | --memory SIZE
                          passed to docker run

Batch jobs:
  -d, --detach LOG        run in the background; stdout+stderr go to LOG (must
                          be below a writable mount, e.g. work/logs/x.log), the
                          exit status to LOG.exit, the container name to
                          LOG.container
  --wait LOG              wait for the job started with --detach LOG and exit
                          with its exit status
  --name NAME             container name (default: rlvm-arena-<pid>-<random>)
  --ps                    list containers started by this script
  --kill-all              kill ALL containers started by this script, including
                          those of other users of it on this machine; to stop
                          one job use: docker kill NAME

Other:
  -e, --env VAR[=VALUE]   pass an environment variable
  -w, --workdir DIR       working directory (default: \$PWD if it is inside the
                          project root, else the project root)
  --docker-arg ARG        pass ARG to docker run verbatim (repeatable)
  --print                 print the docker command instead of running it
  -h, --help

Environment: RLVM_ARENA_IMAGE (image name), RLVM_ARENA_PERF (default for --perf).
EOF
}

die() { echo "arena-docker.sh: $*" >&2; exit 125; }

# The first N CPUs of the fastest core type (the performance cores on a
# big.LITTLE host, told apart by their maximum frequency). All of them belong
# to one PMU, so a workload pinned to them is counted by a single counter.
big_cpus() {
  local n="$1" f best=0 freq list=()
  for f in /sys/devices/system/cpu/cpu[0-9]*/cpufreq/cpuinfo_max_freq; do
    [ -r "$f" ] || continue
    freq="$(cat "$f")"
    if [ "$freq" -gt "$best" ]; then best="$freq"; fi
  done
  if [ "$best" -gt 0 ]; then
    for f in /sys/devices/system/cpu/cpu[0-9]*/cpufreq/cpuinfo_max_freq; do
      if [ "$(cat "$f")" = "$best" ]; then
        f="${f%/cpufreq/cpuinfo_max_freq}"; list+=("${f##*/cpu}")
      fi
    done
  else
    local i; for ((i = 0; i < $(nproc); i++)); do list+=("$i"); done
  fi
  printf '%s\n' "${list[@]}" | sort -n | head -n "$n" | paste -sd, -
}

perf_level="${RLVM_ARENA_PERF:-perfmon}"
mount_rw=0
stay_root=0
network=1
detach_log=""
name=""
workdir=""
print_only=0
extra_args=()
extra_mounts=()
env_args=()

while [ "$#" -gt 0 ]; do
  case "$1" in
    --build)
      shift
      build_args=()
      if [ "${1:-}" = "--no-cache" ]; then build_args+=(--no-cache); shift; fi
      exec docker build --platform linux/arm64 "${build_args[@]}" -t "$IMAGE" "$SELF_DIR/docker"
      ;;
    --ps)
      exec docker ps -a --filter "label=$LABEL" --format 'table {{.Names}}\t{{.Status}}\t{{.RunningFor}}\t{{.Command}}'
      ;;
    --kill-all)
      ids="$(docker ps -aq --filter "label=$LABEL")"
      if [ -n "$ids" ]; then
        # shellcheck disable=SC2086
        docker rm -f $ids
      fi
      exit 0
      ;;
    --wait)
      [ "$#" -ge 2 ] || die "--wait needs the log file given to --detach"
      log="$(realpath -m "$2")"
      [ -f "$log.container" ] || die "no such job: $log.container not found"
      cname="$(cat "$log.container")"
      # Blocks until the container exits; it is gone already if it finished.
      docker wait "$cname" >/dev/null 2>&1 || true
      # --rm removes the container a moment after the exit status is written.
      for _ in 1 2 3 4 5 6 7 8 9 10; do
        [ -f "$log.exit" ] && break
        sleep 0.5
      done
      [ -f "$log.exit" ] || die "job $cname ended without writing $log.exit (killed?)"
      rc="$(cat "$log.exit")"
      echo "job $cname finished with exit status $rc (log: $log)"
      exit "$rc"
      ;;
    --rw) mount_rw=1; shift ;;
    --root) stay_root=1; shift ;;
    --no-network) network=0; shift ;;
    --perf) perf_level="${2:?--perf needs a level}"; shift 2 ;;
    --arena-limits)
      extra_args+=(--cpuset-cpus "$(big_cpus 8)" --memory 16g --memory-swap 16g); shift ;;
    --cpus|--cpuset-cpus|--memory)
      extra_args+=("$1" "${2:?$1 needs a value}"); shift 2 ;;
    -d|--detach) detach_log="${2:?--detach needs a log file}"; shift 2 ;;
    --name) name="${2:?--name needs a value}"; shift 2 ;;
    -e|--env) env_args+=(-e "${2:?--env needs VAR[=VALUE]}"); shift 2 ;;
    -w|--workdir) workdir="${2:?--workdir needs a directory}"; shift 2 ;;
    --mount) extra_mounts+=("${2:?--mount needs SRC[:DST][:ro]}"); shift 2 ;;
    --docker-arg) extra_args+=("${2:?--docker-arg needs a value}"); shift 2 ;;
    --print) print_only=1; shift ;;
    -h|--help) usage; exit 0 ;;
    --) shift; break ;;
    -*) die "unknown option: $1 (see --help)" ;;
    *) break ;;
  esac
done

docker image inspect "$IMAGE" >/dev/null 2>&1 \
  || die "image $IMAGE not found; build it with: bench/arena-docker.sh --build"

uid="$(id -u)"
gid="$(id -g)"
[ -n "$name" ] || name="rlvm-arena-$$-$RANDOM"

if [ -z "$workdir" ]; then
  case "$PWD/" in
    "$ROOT"/*) workdir="$PWD" ;;
    *) workdir="$ROOT" ;;
  esac
fi

mkdir -p "$ROOT/work/cache"

args=(run --rm --init --platform linux/arm64
      --label "$LABEL" --name "$name" --hostname rlvm-arena
      -e "HOST_UID=$uid" -e "HOST_GID=$gid" -e "RLVM_ROOT=$ROOT"
      -w "$workdir")
# Started inside a bench/quiet.py window: mark the container as the measurement's own, so that the
# wrapper leaves it on the measurement cores (every other container is moved off them meanwhile).
if [ -n "${RLVM_QUIET_NAME:-}" ]; then args+=(--label "rlvm-quiet=$RLVM_QUIET_NAME"); fi

# --- mounts ------------------------------------------------------------------
writable=()
if [ "$mount_rw" = 1 ]; then
  args+=(-v "$ROOT:$ROOT:rw")
  writable+=("$ROOT")
else
  args+=(-v "$ROOT:$ROOT:ro" -v "$ROOT/work:$ROOT/work:rw")
  writable+=("$ROOT/work")
fi
for m in "${extra_mounts[@]}"; do
  IFS=':' read -r src dst mode <<< "$m"
  if [ "$dst" = "ro" ] || [ "$dst" = "rw" ]; then mode="$dst"; dst=""; fi
  src="$(realpath -m "$src")"
  [ -e "$src" ] || die "--mount: $src does not exist"
  dst="${dst:-$src}"
  mode="${mode:-rw}"
  args+=(-v "$src:$dst:$mode")
  if [ "$mode" = "rw" ]; then writable+=("$dst"); fi
done

# --- privileges --------------------------------------------------------------
# Phase 1 of the entrypoint needs SETUID/SETGID to become the host user and
# SETPCAP to shrink the bounding set; after that the command is left with only
# the capabilities listed in RLVM_AMBIENT_CAPS. KILL is for docker's init
# (tini, pid 1, still root), which forwards SIGTERM/SIGINT to the command.
drop=(--cap-drop ALL --cap-add SETUID --cap-add SETGID --cap-add SETPCAP --cap-add KILL
      --security-opt no-new-privileges)
case "$perf_level" in
  none)
    args+=("${drop[@]}") ;;
  perfmon)
    args+=("${drop[@]}" --cap-add PERFMON -e RLVM_AMBIENT_CAPS=perfmon) ;;
  seccomp)
    args+=("${drop[@]}" --cap-add PERFMON --security-opt seccomp=unconfined
           -e RLVM_AMBIENT_CAPS=perfmon) ;;
  sysadmin)
    args+=("${drop[@]}" --cap-add PERFMON --cap-add SYS_ADMIN
           --security-opt seccomp=unconfined -e RLVM_AMBIENT_CAPS=perfmon,sys_admin) ;;
  privileged)
    args+=(--privileged -e RLVM_AMBIENT_CAPS=perfmon,sys_admin) ;;
  *) die "unknown --perf level: $perf_level" ;;
esac
if [ "$stay_root" = 1 ]; then
  # Root needs the default capability set back (chown, dac_override, ...).
  filtered=()
  skip=0
  for a in "${args[@]}"; do
    if [ "$skip" = 1 ]; then skip=0; continue; fi
    case "$a" in
      --cap-drop) skip=1; continue ;;
      no-new-privileges) unset 'filtered[${#filtered[@]}-1]'; continue ;;
    esac
    filtered+=("$a")
  done
  args=("${filtered[@]}")
  chown_paths="$(IFS=:; echo "${writable[*]}")"
  args+=(-e RLVM_STAY_ROOT=1 -e "RLVM_CHOWN_PATHS=$chown_paths")
fi

[ "$network" = 1 ] || args+=(--network none)
args+=("${env_args[@]}" "${extra_args[@]}")

# --- run ---------------------------------------------------------------------
if [ -n "$detach_log" ]; then
  log="$(realpath -m "$detach_log")"
  ok=0
  for w in "${writable[@]}"; do
    case "$log" in "$w"/*) ok=1 ;; esac
  done
  [ "$ok" = 1 ] || die "--detach: $log is not below a writable mount (${writable[*]})"
  mkdir -p "$(dirname "$log")"
  : > "$log"
  rm -f "$log.exit"
  echo "$name" > "$log.container"
  args+=(-d -e "RLVM_LOG_FILE=$log")
  if [ "$print_only" = 1 ]; then printf '%q ' docker "${args[@]}" "$IMAGE" "$@"; echo; exit 0; fi
  docker "${args[@]}" "$IMAGE" "$@" >/dev/null
  echo "started container $name"
  echo "  log:         $log"
  echo "  exit status: $log.exit (written when the command ends)"
  echo "  wait:        bench/arena-docker.sh --wait $log"
  echo "  kill:        docker kill $name"
  exit 0
fi

args+=(-i)
if [ -t 0 ] && [ -t 1 ]; then args+=(-t); fi
if [ "$print_only" = 1 ]; then printf '%q ' docker "${args[@]}" "$IMAGE" "$@"; echo; exit 0; fi
exec docker "${args[@]}" "$IMAGE" "$@"

#!/bin/bash
# Entrypoint of the rlvm-arena image (installed as /usr/local/bin/arena-entrypoint).
# Normally driven by bench/arena-docker.sh; all inputs are environment variables:
#
#   HOST_UID, HOST_GID   uid:gid to run the command as. Without them (or with
#                        RLVM_STAY_ROOT=1) the command runs as the container's
#                        current user.
#   RLVM_AMBIENT_CAPS    comma-separated capabilities to keep for the command
#                        after dropping root, e.g. "perfmon". Only those that
#                        the container was actually started with are kept.
#   RLVM_ROOT            project root (mounted at the same path as on the host).
#                        Enables JAVA_HOME and the persistent caches.
#   RLVM_CACHE           cache directory (default $RLVM_ROOT/work/cache); holds
#                        rustup/ cargo/ elan/ home/.
#   RLVM_LOG_FILE        if set: stdout+stderr of the command go to this file
#                        and its exit status to "$RLVM_LOG_FILE.exit"
#                        (used for detached batch jobs).
#   RLVM_STAY_ROOT=1     keep running as root; on exit, everything owned by
#                        root below RLVM_CHOWN_PATHS (colon-separated) is
#                        chowned to HOST_UID:HOST_GID.
#
# Phase 1 runs as root and drops to the host uid:gid; phase 2 (re-exec of this
# script as that user) prepares the environment and runs the command.
set -euo pipefail

log() { printf 'arena-entrypoint: %s\n' "$*" >&2; }

# ---------------------------------------------------------------- phase 1 ----
if [ -z "${RLVM_ENTRY_PHASE2:-}" ] && [ "$(id -u)" = 0 ] \
   && [ -n "${HOST_UID:-}" ] && [ "${HOST_UID}" != 0 ] && [ "${RLVM_STAY_ROOT:-0}" != 1 ]; then
  host_gid="${HOST_GID:-$HOST_UID}"
  home="/tmp"
  if [ -n "${RLVM_ROOT:-}" ]; then home="${RLVM_CACHE:-$RLVM_ROOT/work/cache}/home"; fi
  # Tools such as git and ssh want a passwd entry for the current uid.
  getent group "$host_gid" >/dev/null || echo "arena:x:${host_gid}:" >> /etc/group
  getent passwd "$HOST_UID" >/dev/null || echo "arena:x:${HOST_UID}:${host_gid}:arena:${home}:/bin/bash" >> /etc/passwd

  # Keep only the requested capabilities that are in our bounding set, as
  # ambient capabilities: they survive the uid change and every later exec,
  # without making any binary "secure-exec" (so LD_LIBRARY_PATH, TMPDIR and
  # GLIBC_TUNABLES reach the measured command untouched, unlike with file
  # capabilities on perf).
  capbnd="$(sed -n 's/^CapBnd:[[:space:]]*//p' /proc/self/status)"
  keep=""
  IFS=',' read -r -a wanted <<< "${RLVM_AMBIENT_CAPS:-}"
  for cap in "${wanted[@]}"; do
    [ -n "$cap" ] || continue
    case "$cap" in
      perfmon) bit=38 ;;
      sys_admin) bit=21 ;;
      sys_ptrace) bit=19 ;;
      *) log "unsupported capability in RLVM_AMBIENT_CAPS: $cap"; exit 125 ;;
    esac
    if (( (0x$capbnd >> bit) & 1 )); then
      keep="${keep:+$keep,}+$cap"
    else
      log "capability $cap not granted to this container; continuing without it"
    fi
  done
  export RLVM_ENTRY_PHASE2=1
  if [ -n "$keep" ]; then
    exec setpriv --reuid "$HOST_UID" --regid "$host_gid" --clear-groups \
      --inh-caps "$keep" --ambient-caps "$keep" --bounding-set "-all,$keep" \
      -- "$0" "$@"
  else
    exec setpriv --reuid "$HOST_UID" --regid "$host_gid" --clear-groups \
      --inh-caps=-all --bounding-set=-all \
      -- "$0" "$@"
  fi
fi

# ---------------------------------------------------------------- phase 2 ----
unset RLVM_ENTRY_PHASE2
umask 022

if [ -n "${RLVM_ROOT:-}" ]; then
  cache="${RLVM_CACHE:-$RLVM_ROOT/work/cache}"
  if mkdir -p "$cache/home" 2>/dev/null && [ -w "$cache" ]; then
    # Seed the persistent toolchain homes from the image once. Serialised with
    # a lock so that containers started in parallel do not race.
    (
      flock 9
      for d in rustup cargo elan; do
        if [ ! -d "$cache/$d" ]; then
          rm -rf "$cache/.$d.partial"
          cp -R --preserve=mode,timestamps,links "/opt/seed/$d" "$cache/.$d.partial"
          cp /opt/seed/VERSION "$cache/.$d.partial/.seeded-from-image"
          mv "$cache/.$d.partial" "$cache/$d"
          log "seeded $cache/$d from the image"
        fi
      done
    ) 9>"$cache/.seed.lock"
    export RUSTUP_HOME="$cache/rustup" CARGO_HOME="$cache/cargo" ELAN_HOME="$cache/elan"
    export HOME="$cache/home"
  else
    log "cache directory $cache is not writable; using the read-only toolchains in /opt/seed"
    export HOME=/tmp
  fi
  if [ -z "${JAVA_HOME:-}" ] && [ -x "$RLVM_ROOT/.toolchain/graalvm-ce-25/bin/java" ]; then
    export JAVA_HOME="$RLVM_ROOT/.toolchain/graalvm-ce-25"
  fi
fi

# Without a project root (plain `docker run`) the image's HOME may belong to root.
if [ ! -w "${HOME:-/nonexistent}" ]; then export HOME=/tmp; fi

export PATH="${CARGO_HOME}/bin:${ELAN_HOME}/bin:${JAVA_HOME:+$JAVA_HOME/bin:}/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
USER="$(id -un 2>/dev/null || echo arena)"
export USER LOGNAME="$USER"

if [ "$#" -eq 0 ]; then set -- bash; fi

finish() {
  # Only reached in the modes that do not exec the command.
  local rc=$1
  if [ "$(id -u)" = 0 ] && [ -n "${HOST_UID:-}" ] && [ -n "${RLVM_CHOWN_PATHS:-}" ]; then
    local paths p
    IFS=':' read -r -a paths <<< "$RLVM_CHOWN_PATHS"
    for p in "${paths[@]}"; do
      [ -e "$p" ] || continue
      find "$p" -xdev \( -uid 0 -o -gid 0 \) -exec chown -h "${HOST_UID}:${HOST_GID:-$HOST_UID}" {} + 2>/dev/null || true
    done
  fi
  if [ -n "${RLVM_LOG_FILE:-}" ]; then echo "$rc" > "${RLVM_LOG_FILE}.exit" || true; fi
  exit "$rc"
}

if [ -n "${RLVM_LOG_FILE:-}" ]; then
  exec >>"$RLVM_LOG_FILE" 2>&1 </dev/null
  set +e
  "$@"
  finish $?
elif [ "$(id -u)" = 0 ] && [ -n "${RLVM_CHOWN_PATHS:-}" ]; then
  set +e
  "$@"
  finish $?
fi
exec "$@"

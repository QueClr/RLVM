# rlvm-arena: measurement environment for the Lean Kernel Arena, linux/arm64.
#
# Build (from the project root):
#   bench/arena-docker.sh --build
# which is
#   docker build --platform linux/arm64 -t rlvm-arena:latest bench/docker
#
# The image is uid-agnostic: bench/docker/entrypoint.sh drops from root to the
# host uid:gid at container start (see bench/arena-docker.sh).
#
# Tool list follows refs/lean-kernel-arena/flake.nix. Left out on purpose
# (only needed by checkers we do not build): ocaml/opam, ghc, dotnet, zig,
# nodejs, binaryen, monolith, texlive. The JDK is the project's own GraalVM,
# mounted from .toolchain/ at run time.

FROM ubuntu:24.04

ARG DEBIAN_FRONTEND=noninteractive
ARG TARGETARCH
RUN test "${TARGETARCH:-arm64}" = "arm64" || { echo "this Dockerfile pins linux/arm64 artifacts" >&2; exit 1; }

ENV LANG=C.UTF-8

# --- distribution packages ---------------------------------------------------
# python3-{jinja2,yaml,jsonschema,markdown}: the dependencies of the arena's lka.py
# time: GNU time (max RSS, as used by lka.py)
# linux-tools-generic-hwe-24.04: perf 7.0, matching the host kernel series (7.0)
# util-linux (setpriv), libcap2-bin (capsh/getpcaps): privilege drop that keeps CAP_PERFMON
# clang/lld/mold: linkers some Rust checkers select; zlib1g-dev: GraalVM native-image
RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      build-essential git curl wget ca-certificates \
      python3 python3-jinja2 python3-yaml python3-jsonschema python3-markdown \
      time cmake ninja-build pkg-config \
      libgmp-dev libffi-dev zlib1g-dev libssl-dev libzstd-dev \
      jq zstd unzip xz-utils bzip2 file less patch rsync bc \
      clang lld mold \
      util-linux libcap2-bin procps psmisc strace hyperfine \
      linux-tools-generic-hwe-24.04 \
 && rm -rf /var/lib/apt/lists/*

# /usr/bin/perf from linux-tools-common is a wrapper that looks for a perf built
# for `uname -r` (the host kernel, which has no package in this image). Put the
# real binary (/usr/lib/linux-hwe-7.0-tools-<abi>/perf) first in PATH instead;
# perf stat does not need an exact kernel match.
RUN set -eux; \
    perf_bin="$(find /usr/lib -maxdepth 2 -path '*tools*' -name perf -type f | sort -V | tail -n 1)"; \
    test -x "$perf_bin"; \
    ln -s "$perf_bin" /usr/local/bin/perf; \
    /usr/local/bin/perf --version

# --- just (Ubuntu's 1.21 is too old for e.g. rpylean's `set dotenv-required`) --
ARG JUST_VERSION=1.59.0
ARG JUST_SHA256=23871b1702bfd550fb5f901778fd4290ecf2ee034a45a889fbe96d89b188086d
RUN set -eux; \
    curl -sSfL -o /tmp/just.tar.gz "https://github.com/casey/just/releases/download/${JUST_VERSION}/just-${JUST_VERSION}-aarch64-unknown-linux-musl.tar.gz"; \
    echo "${JUST_SHA256}  /tmp/just.tar.gz" | sha256sum -c -; \
    tar -xzf /tmp/just.tar.gz -C /usr/local/bin just; \
    rm /tmp/just.tar.gz; \
    just --version

# --- PyPy 2.7 (runs RPython, to translate rpylean) ---------------------------
ARG PYPY2_VERSION=7.3.23
ARG PYPY2_SHA256=b0bec20c16b6ab2bd46bd4f5d6049b6070a22a53eaed437ee9ac36d842ceda74
RUN set -eux; \
    curl -sSfL -o /tmp/pypy2.tar.bz2 "https://downloads.python.org/pypy/pypy2.7-v${PYPY2_VERSION}-aarch64.tar.bz2"; \
    echo "${PYPY2_SHA256}  /tmp/pypy2.tar.bz2" | sha256sum -c -; \
    mkdir -p /opt/pypy2; \
    tar -xjf /tmp/pypy2.tar.bz2 -C /opt/pypy2 --strip-components=1; \
    rm /tmp/pypy2.tar.bz2; \
    # The tarball ships lib/ (libtinfow.so.6 and friends) as mode 0750: without
    # this, pypy starts only as root and fails for the host uid with
    # "libtinfow.so.6: cannot open shared object file".
    chmod -R a+rX /opt/pypy2; \
    ln -s /opt/pypy2/bin/pypy /usr/local/bin/pypy; \
    ln -s /opt/pypy2/bin/pypy /usr/local/bin/pypy2; \
    pypy --version; \
    setpriv --reuid 65534 --regid 65534 --clear-groups pypy --version

# --- Rust (rustup, stable, llvm-tools) and elan ------------------------------
# Installed under /opt/seed. At run time the entrypoint copies /opt/seed/* once
# into the persistent cache (work/cache/{rustup,cargo,elan}) and points
# RUSTUP_HOME / CARGO_HOME / ELAN_HOME there, so toolchains that checkers pull
# in later (rust-toolchain.toml, lean-toolchain) persist between runs.
ENV RUSTUP_HOME=/opt/seed/rustup \
    CARGO_HOME=/opt/seed/cargo \
    ELAN_HOME=/opt/seed/elan

ARG RUSTUP_VERSION=1.29.3
ARG RUSTUP_INIT_SHA256=9f608c142e0e38a78957d0661ba1dfaf0b6fb37184cfaa4b9a0aa57af6184282
ARG RUST_TOOLCHAIN=stable
RUN set -eux; \
    curl -sSfL -o /tmp/rustup-init "https://static.rust-lang.org/rustup/archive/${RUSTUP_VERSION}/aarch64-unknown-linux-gnu/rustup-init"; \
    echo "${RUSTUP_INIT_SHA256}  /tmp/rustup-init" | sha256sum -c -; \
    chmod +x /tmp/rustup-init; \
    /tmp/rustup-init -y --no-modify-path --profile minimal \
        --default-toolchain "${RUST_TOOLCHAIN}" \
        --component llvm-tools --component rustfmt --component clippy; \
    rm /tmp/rustup-init; \
    /opt/seed/cargo/bin/rustc --version; \
    /opt/seed/cargo/bin/cargo --version

ARG ELAN_VERSION=v4.4.0
ARG ELAN_SHA256=45cde3006f82f98804c94cf2ca4949d117060a5f3cc8afa62f523759947ee21f
RUN set -eux; \
    curl -sSfL -o /tmp/elan.tar.gz "https://github.com/leanprover/elan/releases/download/${ELAN_VERSION}/elan-aarch64-unknown-linux-gnu.tar.gz"; \
    echo "${ELAN_SHA256}  /tmp/elan.tar.gz" | sha256sum -c -; \
    mkdir /tmp/elan; \
    tar -xzf /tmp/elan.tar.gz -C /tmp/elan; \
    /tmp/elan/elan-init -y --no-modify-path --default-toolchain none; \
    rm -rf /tmp/elan /tmp/elan.tar.gz; \
    /opt/seed/elan/bin/elan --version

ENV PATH=/opt/seed/cargo/bin:/opt/seed/elan/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin

# llvm-profdata (and friends) must match the LLVM of the rustc in use, so
# resolve them through the active toolchain's llvm-tools component instead of
# shipping a distribution LLVM. PGO build scripts just call `llvm-profdata`.
RUN set -eux; \
    for tool in llvm-profdata llvm-cov llvm-objdump llvm-nm llvm-size llvm-readobj llvm-objcopy llvm-ar llvm-strip; do \
      printf '%s\n' \
        '#!/bin/sh' \
        '# Dispatch to the llvm-tools component of the active rustup toolchain.' \
        'sysroot="$(rustc --print sysroot)" || exit 127' \
        'host="$(rustc -vV | sed -n "s/^host: //p")"' \
        "exec \"\$sysroot/lib/rustlib/\$host/bin/$tool\" \"\$@\"" \
        > "/usr/local/bin/$tool"; \
      chmod +x "/usr/local/bin/$tool"; \
    done; \
    llvm-profdata --version

# Record what was baked in; the entrypoint stamps the cache with this so a
# rebuilt image can tell that the cache was seeded from an older one.
RUN set -eux; \
    { echo "built=$(date -u +%Y-%m-%dT%H:%M:%SZ)"; \
      echo "rustc=$(rustc --version)"; \
      echo "cargo=$(cargo --version)"; \
      echo "rustup=$(rustup --version 2>/dev/null | head -n 1)"; \
      echo "elan=$(elan --version)"; \
      echo "perf=$(perf --version)"; \
      echo "just=$(just --version)"; \
      echo "pypy=$(pypy --version 2>&1 | tr '\n' ' ')"; \
      echo "python3=$(python3 --version)"; \
      echo "gcc=$(gcc --version | head -n 1)"; \
      echo "time=$(/usr/bin/time --version 2>&1 | head -n 1)"; \
    } > /opt/seed/VERSION; \
    cat /opt/seed/VERSION

COPY entrypoint.sh /usr/local/bin/arena-entrypoint
RUN chmod 0755 /usr/local/bin/arena-entrypoint

ENTRYPOINT ["/usr/local/bin/arena-entrypoint"]
CMD ["bash"]

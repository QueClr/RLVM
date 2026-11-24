#!/usr/bin/env python3
"""Run the arena's lka.py with bash (invoked as `sh`) as its shell.

    bench/arena-docker.sh -w $PWD/work/arena-run $PWD/bench/arena-lka.py [lka.py arguments...]

lka.py runs the `pre-build:` and `run:` commands of a test with
subprocess(shell=True), i.e. `/bin/sh -c`. On the arena, lka.py runs under the
Python of nixpkgs, whose subprocess module uses bash's `sh` instead of
`/bin/sh`, so test definitions may use bash builtins: tests/cedar.yaml starts
its pre-build with `shopt -s dotglob`. In our Ubuntu image /bin/sh is dash and
that command fails with "shopt: not found". This wrapper gives lka.py the same
shell as on the arena without touching the image or lka.py: every shell=True
command is run by /bin/bash under the name `sh` (bash's POSIX mode, exactly
what `${bash}/bin/sh` is).

The lka.py that is run is the one in the current directory, or $LKA if set.
Only meant to be run inside the container (it runs third-party code).
"""
import os
import runpy
import subprocess
import sys
import tempfile


def main() -> None:
    lka = os.path.abspath(os.environ.get("LKA", "lka.py"))
    if not os.path.isfile(lka):
        sys.exit(f"arena-lka.py: {lka} not found (cd to a checkout of lean-kernel-arena, or set LKA)")
    bash = "/bin/bash"
    if not os.access(bash, os.X_OK):
        sys.exit(f"arena-lka.py: {bash} not found")

    shell_dir = tempfile.mkdtemp(prefix="arena-lka-sh-")
    shell = os.path.join(shell_dir, "sh")
    os.symlink(bash, shell)

    original_init = subprocess.Popen.__init__

    def init(self, args, *rest, **kwargs):
        # Popen replaces argv[0] of ["/bin/sh", "-c", cmd] by `executable`
        if kwargs.get("shell") and not kwargs.get("executable"):
            kwargs["executable"] = shell
        original_init(self, args, *rest, **kwargs)

    subprocess.Popen.__init__ = init

    sys.argv = [lka] + sys.argv[1:]
    try:
        runpy.run_path(lka, run_name="__main__")
    finally:
        try:
            os.unlink(shell)
            os.rmdir(shell_dir)
        except OSError:
            pass


if __name__ == "__main__":
    main()

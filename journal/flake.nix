{
  description = "RLVM agentic journal: a Forester forest of design notes, decisions, insights, changelog entries and open problems";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";

    flake-utils.url = "github:numtide/flake-utils";

    # Forester 6.0 bundles its theme, so the forest needs no theme submodule.
    # Forester is not in nixpkgs; its flake builds it with opam-nix against
    # its own nixpkgs pin, which is kept so that the compiler comes from the
    # NixOS binary cache.
    forester.url = "sourcehut:~jonsterling/ocaml-forester/6.0";
  };

  outputs = { self, nixpkgs, flake-utils, forester }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        pkgs = nixpkgs.legacyPackages.${system};
        lib = pkgs.lib;

        # The glibc build. The flake's default package is a static musl
        # build that compiles its own OCaml and runs the compiler test suite,
        # which is slow and does not pass in every sandbox.
        foresterPkg = forester.legacyPackages.${system}.forester;

        # The path component of `url` in forest.toml. Links in the rendered
        # forest are absolute, so the site root is this directory of the
        # build output.
        sitePath = "RLVM";

        # Sources that determine the rendered site.
        forestSrc = lib.fileset.toSource {
          root = ./.;
          fileset = lib.fileset.unions [
            ./forest.toml
            ./trees
            ./assets
          ];
        };

        # What counts as a diagnostic in Forester's output.
        diagnostics = "(error|warning)";

        # The rendered forest. The tree below $out mirrors `sitePath`.
        site = pkgs.stdenvNoCC.mkDerivation {
          name = "rlvm-agentic-journal-site";
          src = forestSrc;
          nativeBuildInputs = [ foresterPkg ];
          buildPhase = ''
            runHook preBuild
            forester build forest.toml 2>&1 | tee build.log
            if grep -qiE '${diagnostics}' build.log; then
              echo "forester reported diagnostics; failing the build" >&2
              exit 1
            fi
            test -f output/${sitePath}/index/index.html || test -f output/${sitePath}/index.html || {
              echo "no rendered home page under output/${sitePath}" >&2
              exit 1
            }
            runHook postBuild
          '';
          installPhase = ''
            runHook preInstall
            cp -r output $out
            runHook postInstall
          '';
        };

        # Forester copies its bundled theme out of the Nix store and keeps the
        # store's read-only permissions, so a second build cannot overwrite
        # the first. Make a previous output writable before building.
        thaw = ''
          if [ -d output ]; then chmod -R u+w output; fi
        '';

        mkApp = drv: {
          type = "app";
          program = lib.getExe drv;
        };

        build = pkgs.writeShellApplication {
          name = "journal-build";
          runtimeInputs = [ foresterPkg ];
          text = thaw + ''
            exec forester build forest.toml "$@"
          '';
        };

        # journal-check: build a scratch copy of the forest and fail on any
        # diagnostic. It does not touch `output/`, so several agents can run
        # it at the same time in one working copy.
        check = pkgs.writeShellApplication {
          name = "journal-check";
          runtimeInputs = [ foresterPkg pkgs.coreutils pkgs.gnugrep ];
          text = ''
            scratch="$(mktemp -d)"
            trap 'chmod -R u+w "$scratch"; rm -rf "$scratch"' EXIT
            cp -r forest.toml trees assets "$scratch/"
            status=0
            (cd "$scratch" && forester build forest.toml) > "$scratch/build.log" 2>&1 || status=$?
            cat "$scratch/build.log"
            if [ "$status" -ne 0 ] || grep -qiE '${diagnostics}' "$scratch/build.log"; then
              echo "journal-check: forester reported diagnostics" >&2
              exit 1
            fi
            echo "journal-check: no diagnostics"
          '';
        };

        serve = pkgs.writeShellApplication {
          name = "journal-serve";
          runtimeInputs = [ foresterPkg pkgs.python3 ];
          text = thaw + ''
            forester build forest.toml
            port="''${1:-1313}"
            echo "serving on http://localhost:$port/${sitePath}/"
            exec python3 -m http.server "$port" -d output
          '';
        };

        # journal-new <kind>: allocate a fresh random address for a tree of
        # the given kind and print the path of the new file.
        new = pkgs.writeShellApplication {
          name = "journal-new";
          runtimeInputs = [ foresterPkg pkgs.coreutils pkgs.findutils pkgs.gnugrep ];
          text = ''
            kind="''${1:-}"
            case "$kind" in
              design)     prefix="design"; dir="design" ;;
              decision)   prefix="dec"; dir="decision" ;;
              problem)    prefix="prob"; dir="problem" ;;
              log)        prefix="log"; dir="log" ;;
              example)    prefix="ex"; dir="example" ;;
              motivation) prefix="mot"; dir="motivation" ;;
              insight)    prefix="ins"; dir="insight" ;;
              session)    prefix="ses"; dir="session" ;;
              *)
                echo "usage: journal-new <design|decision|problem|log|example|motivation|insight|session>" >&2
                exit 2
                ;;
            esac
            mkdir -p "trees/$dir"
            if forester new forest.toml --random --prefix "$prefix" --dest "trees/$dir" 2>/dev/null; then
              exit 0
            fi
            # Forester parses the whole forest to allocate an address, and
            # fails while any tree has a syntax error, for example one that
            # another agent is in the middle of writing. Allocate the same
            # form of address without it.
            chars="ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
            while :; do
              id=""
              for _ in 1 2 3 4; do
                id+="''${chars:RANDOM % 36:1}"
              done
              if find trees -name "$prefix-$id.tree" | grep -q .; then
                continue
              fi
              file="trees/$dir/$prefix-$id.tree"
              if (set -o noclobber; printf '\\date{%s}\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" > "$file") 2>/dev/null; then
                echo "./$file"
                exit 0
              fi
            done
          '';
        };

        # The python used by scripts/md2tree.py.
        python = pkgs.python3.withPackages (ps: [ ps.markdown-it-py ]);

        # All commands in one place, for `nix build .#tools -o result-tools`:
        # a working copy shared by several agents then calls
        # `result-tools/bin/journal-new` without evaluating the flake again.
        tools = pkgs.symlinkJoin {
          name = "rlvm-agentic-journal-tools";
          paths = [ foresterPkg build check serve new ];
        };
      in
      {
        packages = {
          inherit site tools;
          default = site;
          forester = foresterPkg;
        };

        checks.site = site;

        apps = {
          build = mkApp build;
          check = mkApp check;
          serve = mkApp serve;
          new = mkApp new;
        };

        devShells.default = pkgs.mkShell {
          name = "rlvm-journal";
          packages = [
            foresterPkg
            pkgs.git
            python
            pkgs.ripgrep
            build
            check
            serve
            new
          ];
        };
      });
}

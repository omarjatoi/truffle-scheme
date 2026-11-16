{
  description = "R7RS-small on GraalVM Truffle: dev shell";

  inputs = {
    # Determinate Systems FlakeHub, rolling (unstable) channel: the first one
    # with GraalVM 25.5. The stable channel (`/0`) still ships 25.0.
    nixpkgs.url = "https://flakehub.com/f/NixOS/nixpkgs/0.1";
    flake-utils.url = "github:numtide/flake-utils";
    # mx itself is a Python script in a git repo; pin it here.
    mx = {
      url = "github:graalvm/mx";
      flake = false;
    };
  };

  outputs = { self, nixpkgs, flake-utils, mx }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        pkgs = import nixpkgs { inherit system; };
        # The JDK and JIT. Its version must equal the Truffle library version
        # in mx.r7rs/suite.py; mx_r7rs.py checks this on every mx run.
        graalvm = pkgs.graalvmPackages.graalvm-ce;
        packages = [
          pkgs.python3
          pkgs.git
          pkgs.gh           # session protocol in AGENTS.md
          pkgs.chibi        # differential testing (mx r7rs-diff, Bazel's @chibi), benchmark report
          # Bazel, at the version in .bazelversion (bazelisk downloads it), as `bazel`.
          # Bazel brings its own GraalVM (bazel/graalvm.bzl); the one below is mx's.
          pkgs.bazelisk
          (pkgs.writeShellScriptBin "bazel" ''exec ${pkgs.bazelisk}/bin/bazelisk "$@"'')
          graalvm
        ];
        # mx creates directories inside its own checkout, so it can't run
        # from the read-only store. Copy the pinned revision into a writable
        # cache dir keyed by its store path.
        shellHook = ''
          mxdir="''${XDG_CACHE_HOME:-$HOME/.cache}/truffle-scheme/mx-$(basename ${mx})"
          if [ ! -x "$mxdir/mx" ]; then
            mkdir -p "$(dirname "$mxdir")"
            rm -rf "$mxdir.tmp"
            cp -R ${mx} "$mxdir.tmp" && chmod -R u+w "$mxdir.tmp"
            mv "$mxdir.tmp" "$mxdir"
          fi
          export PATH="$mxdir:$PATH"
          export JAVA_HOME="${graalvm}"
        '';
      in {
        devShells.default = pkgs.mkShell { inherit packages shellHook; };

        # The default shell plus the other R7RS implementations that
        # `mx r7rs-bench-report` runs ecraven's benchmarks on (plan.md M5-02,
        # bench/README.md), next to chibi-scheme. A shell of its own,
        # `nix develop .#bench`, so that the default one, which every CI job
        # downloads, stays small: Racket alone is most of a gigabyte.
        devShells.bench = pkgs.mkShell {
          inherit shellHook;
          packages = packages ++ [
            pkgs.chez                              # scheme
            pkgs.chez-srfi                         # (srfi :9), which ecraven's Chez R7RS libraries import
            pkgs.gauche                            # gosh
            pkgs.chicken                           # csc (compiles to C: needs the shell's C compiler)
            pkgs.chickenPackages.chickenEggs.r7rs  # csc -R r7rs -X r7rs
            pkgs.guile                             # guile, guild
            pkgs.racket                            # racket, raco (its r7rs package is installed at run time)
          ];
          # Where Chez finds chez-srfi's libraries.
          CHEZSCHEMELIBDIRS = "${pkgs.chez-srfi}/lib/csv${pkgs.lib.versions.majorMinor pkgs.chez.version}-site";
        };
      });
}

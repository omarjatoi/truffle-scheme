{
  description = "R7RS-small on GraalVM Truffle: dev shell";

  inputs = {
    # Determinate Systems FlakeHub, rolling (unstable) channel: the first one
    # with GraalVM 25.5. The stable channel (`/0`) still ships 25.0.
    nixpkgs.url = "https://flakehub.com/f/NixOS/nixpkgs/0.1";
    flake-utils.url = "github:numtide/flake-utils";
    # Determinate Nix for CI's agent image (nix/image.nix). Only `nix build .#ciImage`
    # fetches it, so the CI shell doesn't.
    determinate.url = "https://flakehub.com/f/DeterminateSystems/determinate/3.24.2";
  };

  outputs = { self, nixpkgs, flake-utils, determinate }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        pkgs = import nixpkgs { inherit system; };
        # A JDK for running and embedding the language outside Bazel (docs/usage.md,
        # docs/embedding.md): the GraalVM release Bazel pins too (bazel/graalvm.bzl).
        graalvm = pkgs.graalvmPackages.graalvm-ce;
        packages = [
          pkgs.bazelisk     # Bazel, at .bazelversion
          (pkgs.writeShellScriptBin "bazel" ''exec ${pkgs.bazelisk}/bin/bazelisk "$@"'')
          pkgs.python3
          pkgs.git
          pkgs.gh           # session protocol in AGENTS.md
          pkgs.chibi        # running programs under chibi, benchmark report
          graalvm
        ];
        shellHook = ''
          export JAVA_HOME="${graalvm}"
        '';
      in {
        devShells.default = pkgs.mkShell { inherit packages shellHook; };

        # What CI's steps run (.buildkite/pipeline.yml): only Bazel. It brings the JDK, Python and
        # chibi-scheme itself, and executes remotely, so this shell is a small download.
        devShells.ci = pkgs.mkShellNoCC {
          packages = [
            pkgs.bazelisk
            (pkgs.writeShellScriptBin "bazel" ''exec ${pkgs.bazelisk}/bin/bazelisk "$@"'')
          ];
        };

        # The default shell plus the other R7RS implementations that the
        # benchmark report runs ecraven's benchmarks on (bench/README.md), next to
        # chibi-scheme. A shell of its own, `nix develop .#bench`, so that the
        # default one stays small: Racket alone is most of a gigabyte.
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
      }) // {
        # CI's agent image: Determinate Nix with the `.#ci` shell already in its store
        # (nix/image.nix; `.buildkite/push-image.sh` builds and pushes it).
        packages.x86_64-linux.ciImage = import ./nix/image.nix {
          pkgs = nixpkgs.legacyPackages.x86_64-linux;
          nix = determinate.inputs.nix;
          ciShell = self.devShells.x86_64-linux.ci;
          flakeInputs = [ nixpkgs flake-utils flake-utils.inputs.systems ];
          flakeLock = ./flake.lock;
        };
      };
}

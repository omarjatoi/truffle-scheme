{
  description = "R7RS-small on GraalVM Truffle: dev shell";

  inputs = {
    # Determinate Systems FlakeHub, rolling (unstable) channel. Its graalvm-ce
    # is 25.5; the dev shell's GraalVM is the release below, built with
    # nixpkgs' own builder (design.md D11, "Nix").
    nixpkgs.url = "https://flakehub.com/f/NixOS/nixpkgs/0.1";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = { self, nixpkgs, flake-utils }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        pkgs = import nixpkgs { inherit system; };
        # A JDK for running and embedding the language outside Bazel (docs/usage.md,
        # docs/embedding.md): the GraalVM release Bazel pins too (bazel/graalvm.bzl), the same
        # archives at the same sha256s, unpacked by the builder of nixpkgs' graalvm-ce
        # (`buildGraalvm`). nixpkgs' graalvm-ce is still 25.5 (design.md D11, "Nix"); once
        # it ships this release, this goes back to `pkgs.graalvmPackages.graalvm-ce`.
        graalvmVersion = "25.6.5.1";
        graalvmArchives = {
          "x86_64-linux" = { suffix = "linux-x64"; sha256 = "d792848db26f1602f0a5cf2cd9279d9a652d32ab450d891ce616c9ca67e359d0"; };
          "aarch64-linux" = { suffix = "linux-aarch64"; sha256 = "d8e6edf0009bb5d70c6a670eb030d36aa804795427306434c0f7f63c01435307"; };
          "aarch64-darwin" = { suffix = "macos-aarch64"; sha256 = "19ef5175f18fe2a957a5ec4cc3dd263a164e88ae621cf80ee1c715ede72469f0"; };
        };
        graalvm = pkgs.graalvmPackages.buildGraalvm {
          version = graalvmVersion;
          src = pkgs.fetchurl {
            url = "https://github.com/graalvm/graalvm-ce-builds/releases/download/graal-${graalvmVersion}/graalvm-community-jdk-25i6-25.0.5.1_${graalvmArchives.${system}.suffix}_bin.tar.gz";
            inherit (graalvmArchives.${system}) sha256;
          };
          meta.platforms = builtins.attrNames graalvmArchives;
        };
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
      });
}

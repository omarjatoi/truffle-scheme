{
  description = "R7RS-small on GraalVM Truffle: dev shell, and the launchers as packages";

  inputs = {
    # Determinate Systems FlakeHub, rolling (unstable) channel. Its graalvm-ce
    # is 25.5; the dev shell's GraalVM is the release below, built with
    # nixpkgs' own builder (design.md D11, "Nix").
    nixpkgs.url = "https://flakehub.com/f/NixOS/nixpkgs/0.1";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = { self, nixpkgs, flake-utils }:
    let
      # What the packages are built from (nix/scheme.nix), the GraalVM release among them.
      pins = builtins.fromJSON (builtins.readFile ./nix/packages.json);
    in
    # The platforms GraalVM has a release archive for, which bazel/graalvm.bzl pins too.
    flake-utils.lib.eachSystem (builtins.attrNames pins.graalvm.archives) (system:
      let
        pkgs = import nixpkgs { inherit system; };
        # A JDK for running and embedding the language outside Bazel (docs/usage.md,
        # docs/embedding.md), and the packages': the GraalVM release Bazel pins too
        # (bazel/graalvm.bzl; //tests:nix checks it), the same archives at the same sha256s,
        # unpacked by the builder of nixpkgs' graalvm-ce (`buildGraalvm`). nixpkgs' graalvm-ce is
        # still 25.5 (design.md D11, "Nix"); once it ships this release, this goes back to
        # `pkgs.graalvmPackages.graalvm-ce`.
        graalvm = pkgs.graalvmPackages.buildGraalvm {
          inherit (pins.graalvm) version;
          src = pkgs.fetchurl {
            url = "${pins.graalvm.url_prefix}${pins.graalvm.archives.${system}.suffix}_bin.tar.gz";
            inherit (pins.graalvm.archives.${system}) sha256;
          };
          meta.platforms = builtins.attrNames pins.graalvm.archives;
        };
        # The launcher and the native launcher, built without Bazel (design.md D11, "Nix packages").
        scheme = pkgs.callPackage ./nix/scheme.nix { inherit graalvm pins; };
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
        # `nix build .#scheme`, `nix run .#scheme -- file.scm`, `nix profile install .#scheme`
        # (docs/usage.md, "Nix packages"); the same for `scheme-native`.
        packages = {
          inherit (scheme) scheme scheme-native;
          default = scheme.scheme;
        };

        # `nix flake check`: docs/usage.md's Nix blocks, and a conformance section, through both.
        checks = import ./nix/checks.nix { inherit pkgs scheme; };

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

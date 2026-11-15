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
      in {
        devShells.default = pkgs.mkShell {
          packages = [
            pkgs.python3
            pkgs.git
            pkgs.gh           # session protocol in AGENTS.md
            pkgs.chibi        # differential testing (mx r7rs-diff)
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
        };
      });
}

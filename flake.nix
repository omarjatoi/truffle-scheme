{
  description = "R7RS-small on GraalVM Truffle: dev shell";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
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
      in {
        devShells.default = pkgs.mkShell {
          packages = [
            pkgs.python3
            pkgs.git
            pkgs.gh           # session protocol in AGENTS.md
            pkgs.chibi        # differential testing (mx r7rs-diff)
          ];
          # The JDK is labsjdk via `mx fetch-jdk labsjdk-ce-latest`; see AGENTS.md.
          shellHook = ''
            export PATH="${mx}:$PATH"
          '';
        };
      });
}

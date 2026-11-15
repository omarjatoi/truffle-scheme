{
  description = "R7RS-small on GraalVM Truffle";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
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
            pkgs.chibi
          ];
          shellHook = ''
            export PATH="${mx}:$PATH"
          '';
        };
      });
}

# CI's agent image (flake.nix `packages.x86_64-linux.ciImage`, pushed by .buildkite/push-image.sh):
# what the hosted agents' jobs run in instead of Buildkite's default Ubuntu image. It has
# Determinate Nix, the release the hook's installer installs, and every store path that
# `nix print-dev-env .#ci` reads or builds (the flake's locked inputs, nixpkgs's 37 MiB source
# among them, and the shell's environment), registered in the image's Nix database. So the hook
# (.buildkite/hooks/pre-command) installs nothing and downloads nothing: it evaluates the flake
# and finds everything valid. When flake.lock or the shell changes, Nix fetches what the store
# lacks, as on the default image, and the hook says the image is stale.
#
# Buildkite's requirements for a custom image (docs/design.md D11): bash, git and CA certificates;
# the agent brings `buildkite-agent` and `docker`, and jobs run as root.
{ pkgs, nix, ciShell, flakeInputs, flakeLock }:
let
  inherit (pkgs) lib dockerTools;
  nixPackage = nix.packages.${pkgs.stdenv.hostPlatform.system}.default;

  # The derivation `nix print-dev-env .#ci` builds and then reads: the shell's derivation run
  # by Nix's get-env.sh (`getDerivationEnvironment`, src/nix/develop.cc), named `<name>-env`.
  # The same script, from the same Nix release, added as the same text file (the CLI embeds it
  # with a leading newline), and the shell's own inputs (its builder scripts) kept as the CLI
  # keeps them, so the derivations are identical and the CLI finds this output valid instead of
  # building it. The hook checks that the paths match.
  getEnvSh = builtins.toFile "get-env.sh" ("\n" + builtins.readFile "${nix}/src/nix/get-env.sh");
  shellArgs = builtins.concatStringsSep " " (map (a: "${a}") ciShell.drvAttrs.args);
  shellEnv = derivation (ciShell.drvAttrs // {
    name = "${ciShell.name}-env";
    args = [ (builtins.appendContext "${getEnvSh}" (builtins.getContext shellArgs)) ];
  });

  # The image's /etc and its record of what it holds. Jobs run as root; no build users, and
  # no sandbox, as Determinate's container installs (`--init none`, `sandbox = false`).
  etc = pkgs.runCommand "ci-image-etc" { } ''
    mkdir -p $out/etc/nix $out/etc/ci-image/inputs
    cat > $out/etc/nix/nix.conf <<'EOF'
    build-users-group =
    sandbox = false
    max-jobs = auto
    trusted-users = root
    EOF
    echo 'root:x:0:0:root:/root:/bin/bash' > $out/etc/passwd
    echo 'nobody:x:65534:65534:nobody:/var/empty:/bin/false' >> $out/etc/passwd
    printf 'root:x:0:\nnobody:x:65534:\n' > $out/etc/group
    echo 'hosts: files dns' > $out/etc/nsswitch.conf
    ln -s ${shellEnv} $out/etc/ci-image/shell-env
    cp ${flakeLock} $out/etc/ci-image/flake.lock
    ${lib.concatMapStringsSep "\n" (i: "ln -s ${i} $out/etc/ci-image/inputs/") flakeInputs}
  '';

  # Programs that weren't built by Nix (Bazel, as bazelisk downloads it, and the Python and
  # JDKs Bazel's repository rules download) find their loader and libraries through nix-ld.
  ldLibraries = [ pkgs.stdenv.cc.cc.lib pkgs.zlib ];

  contents = [
    etc
    nixPackage
    pkgs.bashInteractive
    pkgs.coreutils
    pkgs.findutils
    pkgs.gnugrep
    pkgs.gnused
    pkgs.gawk
    pkgs.gnutar
    pkgs.gzip
    pkgs.xz
    pkgs.which
    pkgs.curl
    pkgs.git
    dockerTools.caCertificates
    dockerTools.usrBinEnv
    dockerTools.binSh
  ];
  roots = contents ++ [ shellEnv ] ++ flakeInputs;
  registration = pkgs.closureInfo { rootPaths = roots; };
in
dockerTools.buildLayeredImage {
  name = "truffle-scheme-ci";
  tag = "latest";
  inherit contents;
  # The Nix database, written by the image's own Nix, so that it holds every path in the image.
  extraCommands = ''
    mkdir -p tmp root var/empty lib64 nix/var/nix/gcroots
    ln -s ${pkgs.nix-ld}/libexec/nix-ld lib64/ld-linux-x86-64.so.2
    NIX_REMOTE="local?root=$PWD" USER=root \
      ${nixPackage}/bin/nix-store --load-db < ${registration}/registration
    ${pkgs.sqlite}/bin/sqlite3 nix/var/nix/db/db.sqlite \
      "UPDATE ValidPaths SET registrationTime = $SOURCE_DATE_EPOCH"
    ln -s /etc/ci-image nix/var/nix/gcroots/ci-image
  '';
  fakeRootCommands = ''
    chmod 1777 tmp
  '';
  config = {
    Cmd = [ "/bin/bash" ];
    Env = [
      "PATH=/root/.nix-profile/bin:/nix/var/nix/profiles/default/bin:/bin:/usr/bin"
      "HOME=/root"
      "USER=root"
      "LANG=C.UTF-8"
      "SSL_CERT_FILE=/etc/ssl/certs/ca-bundle.crt"
      "NIX_SSL_CERT_FILE=/etc/ssl/certs/ca-bundle.crt"
      "GIT_SSL_CAINFO=/etc/ssl/certs/ca-bundle.crt"
      "NIX_LD=${pkgs.stdenv.cc.bintools.dynamicLinker}"
      "NIX_LD_LIBRARY_PATH=${lib.makeLibraryPath ldLibraries}"
    ];
  };
}

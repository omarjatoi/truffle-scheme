# `nix flake check` (design.md D11, "Nix packages"): docs/usage.md's Nix blocks, as written, with
# `nix` a shim that runs the packages (tests/harness/readme.py's `run nix` blocks, which
# //tests:readme leaves to this check); and a conformance section of chibi-scheme's suite through
# each launcher, every one of its tests passing.
{ pkgs, scheme }:

let
  inherit (pkgs) lib;

  src = lib.fileset.toSource {
    root = ../.;
    fileset = lib.fileset.unions [
      ../docs/usage.md
      ../docs/embedding.md
      ../docs/examples
      ../tests/harness/harness.py
      ../tests/harness/readme.py
      ../tests/lib
      ../tests/r7rs
    ];
  };

  # `nix` as the documents run it, for the packages this check depends on: `nix build`, `nix
  # profile add` and `nix flake check` (this check) have nothing left to do, and `nix run
  # .#<package> -- ...` runs the package.
  nixShim = pkgs.writeShellScriptBin "nix" ''
    case "$1 $2" in
    "build "* | "profile add" | "flake check") exit 0 ;;
    "run .#scheme") shift 3; exec ${scheme.scheme}/bin/scheme "$@" ;;
    "run .#scheme-native") shift 3; exec ${scheme.scheme-native}/bin/scheme-native "$@" ;;
    esac
    echo "the flake's readme check runs only \`nix build\`, \`nix profile add\`, \`nix flake check\` and \`nix run .#<package> --\`, not: nix $*" >&2
    exit 1
  '';

  check = name: script: pkgs.runCommand "scheme-check-${name}" {
    nativeBuildInputs = [ pkgs.python3 nixShim scheme.scheme scheme.scheme-native ];
  } ''
    # Java takes its user.home from the password database, not $HOME: the sandbox user's has no
    # cache directory, where Truffle unpacks its resources (the optimizing runtime's attach library).
    export HOME="$TMPDIR" JAVA_TOOL_OPTIONS="-Duser.home=$TMPDIR"
    cp -r ${src} source && chmod -R u+w source && cd source
    ${script}
    touch "$out"
  '';

  # One section of chibi-scheme's suite (tests/r7rs) through a launcher: complete, and every test
  # passing, as tests/r7rs/sections.tsv counts them.
  section = launcher: id: ''
    ${launcher} -I tests/lib tests/r7rs/${id}.scm > ${id}.out 2>&1 || true
    python3 -c '
    import sys; sys.path.insert(0, "tests/harness")
    from harness import count_results
    total = [int(row.split("\t")[2]) for row in open("tests/r7rs/sections.tsv") if row.startswith(sys.argv[2] + "\t")][0]
    counts = count_results(open(sys.argv[2] + ".out").read())
    if not counts["complete"] or counts["pass"] != total:
        sys.exit(f"{sys.argv[1]}: section {sys.argv[2]}: {counts}, of {total} tests:\n" + open(sys.argv[2] + ".out").read())
    print(f"{sys.argv[1]}: section {sys.argv[2]}: {total} of {total} tests pass")
    ' ${launcher} ${id}
  '';
in
{
  readme = check "readme" ''
    python3 -c '
    import sys; sys.path.insert(0, "tests/harness")
    import readme
    problems = readme.run(".", nix=True)
    if problems:
        sys.exit("readme: failed:\n" + "\n".join(problems))
    '
  '';

  conformance = check "conformance" ''
    ${section "scheme" "6.10"}
    ${section "scheme-native" "6.10"}
  '';
}

# The flake's packages (design.md D11, "Nix packages"): the launcher (`scheme`, bazel-bin/scheme's
# equivalent) and the native launcher (`scheme-native`, bazel-bin/scheme-native's), built in Nix's
# sandbox without Bazel. Each step repeats one of Bazel's, on the flake's GraalVM, which is the
# release bazel/graalvm.bzl pins: the Maven jars are maven_install.json's, at its sha256s; the
# libraries are compiled as their BUILD.bazel files say; the module jars are made by
# bazel/ModuleJar.java with BUILD.bazel's descriptors (nix/packages.json, which //tests:nix
# checks against the Bazel build).
{ lib, stdenv, fetchurl, runCommand, python3, graalvm, pins }:

let
  root = ../.;

  # The sources the packages are built from, alone, so that an edit elsewhere (docs, tests) builds
  # nothing again.
  src = lib.fileset.toSource {
    inherit root;
    fileset = lib.fileset.unions [
      ../src/builtins
      ../src/compiler
      ../src/launcher
      ../src/prelude
      ../src/reader
      ../src/runtime
      ../bazel/ModuleJar.java
      ../bazel/aot-training.scm
    ];
  };

  # The Python launcher bazel-bin/scheme runs (//:scheme_py).
  launcherSrc = lib.fileset.toSource {
    inherit root;
    fileset = lib.fileset.unions [ ../tests/harness/scheme.py ../tests/harness/harness.py ];
  };

  # Every Maven jar the packages use, from the lock file Bazel's rules_jvm_external reads: its URL
  # (the lock file's repository, in Maven's layout) and its sha256.
  lock = lib.importJSON ../maven_install.json;
  repository = builtins.head (builtins.attrNames lock.repositories);
  jar = coordinate:
    let
      parts = lib.splitString ":" coordinate;
      group = builtins.elemAt parts 0;
      artifact = builtins.elemAt parts 1;
      inherit (lock.artifacts.${coordinate}) version;
      file = "${artifact}-${version}.jar";
    in
    fetchurl {
      url = "${repository}${lib.replaceStrings [ "." ] [ "/" ] group}/${artifact}/${version}/${file}";
      sha256 = lock.artifacts.${coordinate}.shasums.jar;
      name = file;
    };
  jars = map jar;
  truffleJars = jars pins.maven.truffle_modules;
  launcherJars = jars pins.maven.launcher_modules;
  toolsJars = jars pins.maven.tools_modules;
  processorJar = jar pins.maven.processor;
  processors = lib.concatMapStringsSep "," (p: "com.oracle.truffle.dsl.processor.${p}") pins.maven.processors;

  path = lib.concatStringsSep ":";
  moduleArgs = name: lib.escapeShellArgs (
    [ "--name" name ]
    ++ lib.concatMap (r: [ "--requires" r ]) pins.modules.${name}.requires
    ++ lib.concatMap (e: [ "--exports" e ]) pins.modules.${name}.exports
    ++ lib.concatMap (u: [ "--uses" u ]) pins.modules.${name}.uses
  ) + " " + toString (map (l: "${l}.jar") pins.modules.${name}.libs);

  # The module jars, scheme.jar and scheme.launcher.jar (BUILD.bazel's //:scheme_module and
  # //:launcher_module), with the standard libraries' prelude image (//:prelude) in scheme.jar; in
  # share/scheme/jars with the other jars of the launcher's module path (//:scheme_jars), and
  # GraalVM's tools in share/scheme/tools.
  modules = stdenv.mkDerivation {
    pname = "scheme-modules";
    version = pins.graalvm.version;
    inherit src;
    nativeBuildInputs = [ graalvm ];
    dontConfigure = true;
    buildPhase = ''
      runHook preBuild
      # Java takes its user.home from the password database, not $HOME: the sandbox user's has no
      # cache directory, where Truffle unpacks its resources (the optimizing runtime's attach library).
      export HOME="$TMPDIR" JAVA_TOOL_OPTIONS="-Duser.home=$TMPDIR"
      cp=${path (truffleJars ++ launcherJars)}
      # One java_library (src/<name>/BUILD.bazel): its sources' directories, with the Truffle DSL
      # processor or none, compiled against the jars and the libraries before it.
      library() {
        local name="$1" processor="$2"; shift 2
        mkdir -p "classes/$name"
        find "$@" -maxdepth 1 -name '*.java' | sort > "$name.sources"
        javac -encoding UTF-8 -g -parameters -nowarn -XDsuppressNotes -d "classes/$name" \
          -cp "$cp$(printf ':%s' classes/*)" $processor "@$name.sources"
      }
      # Its resources: the files of src/<name>/<dir> but its sources, under <dir> (resource_strip_prefix).
      resources() {
        (cd "src/$1" && find "$2" -type f ! -name '*.java' -exec cp --parents {} "$OLDPWD/classes/$1/" \;)
      }
      dsl="-processorpath ${processorJar} -processor ${processors}"
      ${lib.concatMapStrings (l: ''
        library ${l.name} ${if l.dsl then ''"$dsl"'' else "-proc:none"} ${toString (map (d: "src/${l.name}/${d}") l.sources)}
        ${lib.concatMapStrings (r: "resources ${l.name} ${r}\n") l.resources}
        jar --create --file ${l.name}.jar -C classes/${l.name} .
      '') pins.libraries}

      # The prelude image (bazel/java_module.bzl, prelude_image).
      : > empty.scm
      java --enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow \
        -Dpolyglot.engine.AllowExperimentalOptions=true -Dpolyglot.engine.Compilation=false \
        -Dpolyglot.scheme.PreludeImageWrite=prelude.image \
        -cp "$cp:${path (map (l: "${l.name}.jar") pins.libraries)}" \
        scheme.launcher.SchemeMain empty.scm

      # The module jars (java_module_jar).
      java bazel/ModuleJar.java --out scheme.jar ${moduleArgs "scheme"} \
        --module-path ${path truffleJars} --resource scheme/prelude/prelude.image=prelude.image
      java bazel/ModuleJar.java --out scheme.launcher.jar ${moduleArgs "scheme.launcher"} \
        --module-path "scheme.jar:${path (launcherJars ++ truffleJars)}"
      runHook postBuild
    '';
    installPhase = ''
      runHook preInstall
      mkdir -p "$out/share/scheme/jars" "$out/share/scheme/tools"
      cp scheme.jar scheme.launcher.jar "$out/share/scheme/jars/"
      for j in ${toString (truffleJars ++ launcherJars)}; do ln -s "$j" "$out/share/scheme/jars/''${j#*-}"; done
      for j in ${toString toolsJars}; do ln -s "$j" "$out/share/scheme/tools/''${j#*-}"; done
      runHook postInstall
    '';
  };

  modulePath = dir: "${modules}/share/scheme/${dir}";

in
{
  inherit modules;

  # `scheme [option...] [-I dir]... [file.scm [arg...]]`: bazel-bin/scheme's Python launcher
  # (tests/harness/scheme.py) with the manifest of //:scheme_runtime, whose AOT cache it trains
  # here as Bazel does (bazel/java_module.bzl, _AOT_COMMAND), on the store paths it runs from.
  # The store gives every file the modification time 1, which the cache and its stamp record.
  scheme = stdenv.mkDerivation {
    pname = "scheme";
    version = pins.graalvm.version;
    src = launcherSrc;
    nativeBuildInputs = [ graalvm ];
    dontConfigure = true;
    buildPhase = ''
      runHook preBuild
      # Java takes its user.home from the password database, not $HOME: the sandbox user's has no
      # cache directory, where Truffle unpacks its resources (the optimizing runtime's attach library).
      export HOME="$TMPDIR" JAVA_TOOL_OPTIONS="-Duser.home=$TMPDIR"
      share="$out/share/scheme"
      mkdir -p "$share/_main" "$out/bin"
      cp tests/harness/scheme.py tests/harness/harness.py "$share/"
      jars="$(find ${modulePath "jars"} ${modulePath "tools"} -name '*.jar' | sort)"
      sh ${../bazel/aot-cache.sh} ${graalvm} "$share/scheme_runtime.aot" "$share/scheme_runtime.aot.stamp" \
        ${../bazel/aot-training.scm} scheme.launcher/scheme.launcher.SchemeMain $jars
      ${python3.interpreter} - "$share" ${graalvm} $jars <<'EOF'
      import json, os, sys
      share, java_home, jars = sys.argv[1], sys.argv[2], sys.argv[3:]
      with open(os.path.join(share, '_main', 'scheme_runtime.json'), 'w', encoding='utf-8') as f:
          json.dump({'java': java_home + '/bin/java', 'release': java_home + '/release', 'module_path': jars,
                     'aot_cache': share + '/scheme_runtime.aot', 'aot_stamp': share + '/scheme_runtime.aot.stamp'},
                    f, indent=4)
      EOF
      cat > "$out/bin/scheme" <<EOF
      #!${stdenv.shell}
      RUNFILES_DIR="$share" exec ${python3.interpreter} -E -s "$share/scheme.py" "\$@"
      EOF
      chmod +x "$out/bin/scheme"
      runHook postBuild
    '';
    dontInstall = true;
    meta = {
      description = "R7RS-small Scheme on GraalVM Truffle: the launcher, on the JVM";
      mainProgram = "scheme";
      platforms = builtins.attrNames pins.graalvm.archives;
    };
  };

  # `scheme-native`: bazel-bin/scheme-native, GraalVM's thin launcher (launcher.cc, from the
  # pinned release's tag) and beside it the launcher's module as a shared library, by
  # native-image, as //:scheme-native and //:schemevm build them (BUILD.bazel,
  # bazel/java_module.bzl's native_library), with the C compiler of Nix's stdenv.
  scheme-native = stdenv.mkDerivation {
    pname = "scheme-native";
    version = pins.graalvm.version;
    src = fetchurl {
      url = "https://raw.githubusercontent.com/oracle/graal/${pins.graalvm.launcher_cc.tag}/sdk/src/org.graalvm.launcher.native/src/launcher.cc";
      inherit (pins.graalvm.launcher_cc) sha256;
    };
    dontUnpack = true;
    nativeBuildInputs = [ graalvm ];
    dontConfigure = true;
    buildPhase =
      let
        linux = stdenv.hostPlatform.isLinux;
        library = if linux then "libschemevm.so" else "libschemevm.dylib";
        options = pins.native_options
          ++ lib.optionals (stdenv.hostPlatform.system == "x86_64-linux") pins.native_options_x86_64_linux;
        defines = [
          "CP_SEP=:"
          "DIR_SEP=/"
          "GRAALVM_VERSION=${pins.graalvm.version}"
          "LAUNCHER_CLASS=scheme.launcher.SchemeMain"
          "LAUNCHER_MAIN_MODULE=scheme.launcher"
          "LIBLANG_RELPATH=${library}"
          "LIBJVM_RELPATH=${library}"
        ] ++ lib.optional (!linux) "LIBJLI_RELPATH=${library}";
      in
      ''
        runHook preBuild
        # Java takes its user.home from the password database, not $HOME: the sandbox user's has no
        # cache directory, where Truffle unpacks its resources (the optimizing runtime's attach library).
        export HOME="$TMPDIR" JAVA_TOOL_OPTIONS="-Duser.home=$TMPDIR"
        # The release's native-image, as Bazel runs it, not nixpkgs' wrapper of it: on Linux the
        # wrapper adds glibc's static libraries to -H:CLibraryPath, and 25.6's native-image wants a
        # symbol manifest beside each static library there ("Missing static library symbol manifest
        # .../libutil.a.symbols"). The wrapper's other part is kept: the image builder gets the
        # NIX_ variables that stdenv's C compiler wrapper reads (-E). The image builder's JVM gets
        # neither JAVA_TOOL_OPTIONS nor the rest of the environment: its user.home is given (-J).
        ${graalvm}/lib/svm/bin/native-image $(env | sed -n 's/^\(NIX_[A-Za-z0-9_]*\)=.*/-E\1/p') \
          "-J-Duser.home=$TMPDIR" \
          -p "$(find ${modulePath "jars"} -name '*.jar' | sort | paste -sd: -)" \
          --add-modules=scheme.launcher -o libschemevm \
          --shared -Dorg.graalvm.launcher.class=scheme.launcher.SchemeMain \
          -H:+UnlockExperimentalVMOptions -H:-InitializeVM -H:+InstallExitHandlers \
          -H:+EnforceMaxRuntimeCompileMethods -H:-UnlockExperimentalVMOptions \
          -R:+EnableSignalHandling -R:+InstallSegfaultHandler --gc=serial \
          ${lib.escapeShellArgs options}
        $CXX -std=c++17 -O3 -Werror=undef ${if linux then "-include memory" else "-ObjC++"} \
          ${lib.concatMapStringsSep " " (d: "-D" + lib.escapeShellArg d) defines} \
          -I${graalvm}/include -I${graalvm}/include/${if linux then "linux" else "darwin"} \
          -o scheme-native $src -ldl -pthread ${if linux then "-static-libstdc++" else "-framework Foundation"}
        runHook postBuild
      '';
    installPhase = ''
      runHook preInstall
      mkdir -p "$out/libexec/scheme-native" "$out/bin"
      cp scheme-native libschemevm.* "$out/libexec/scheme-native/"
      rm -f "$out/libexec/scheme-native/"*.h
      ln -s ../libexec/scheme-native/scheme-native "$out/bin/scheme-native"
      runHook postInstall
    '';
    meta = {
      description = "R7RS-small Scheme on GraalVM Truffle: the native launcher";
      mainProgram = "scheme-native";
      platforms = builtins.attrNames pins.graalvm.archives;
    };
  };
}

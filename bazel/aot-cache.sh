#!/bin/sh
# aot-cache.sh JAVA_HOME AOT STAMP TRAINING MAIN JAR...: the launcher's AOT cache (design.md D17,
# "The AOT cache"), for bazel/java_module.bzl's scheme_runtime and the flake's `scheme` package
# (nix/scheme.nix). A run of the training program with -XX:AOTCacheOutput, on the JVM options the
# cache needs to match at run time (the collector, the modules, native access;
# tests/harness/harness.py has the same: LAUNCHER_GC_VM_ARGS, AOT_VM_ARGS, TRUFFLE_VM_ARGS), its
# logging off. The module path is the jars' real paths: the JVM compares the run's module path with
# the paths the cache was made with, resolved where it runs (a relative one against the run's
# directory, a sandbox's after the sandbox is gone). Then the stamp: the size, modification time
# and real path of each file the JVM checks the cache against (the JDK's modules image and the
# module path), a line each, which the launcher compares first.
java_home="$1" aot="$2" stamp="$3" training="$4" main="$5"; shift 5
mp=""
for f in "$@"; do mp="$mp${mp:+:}$(realpath "$f")"; done
log="$(mktemp)"
"$java_home/bin/java" -XX:+UseSerialGC --add-modules=jdk.internal.vm.ci -Xlog:aot=off -Xlog:cds=off \
    -XX:AOTCacheOutput="$aot" -p "$mp" --add-modules ALL-MODULE-PATH \
    --enable-native-access=org.graalvm.truffle,org.graalvm.shadowed.jline --sun-misc-unsafe-memory-access=allow \
    -m "$main" "$training" >"$log" 2>&1 && [ -s "$aot" ] || { cat "$log"; exit 1; }
for f in "$java_home/lib/modules" "$@"; do
    echo "$(stat -L -c '%s %Y' "$f" 2>/dev/null || stat -L -f '%z %m' "$f") $(realpath "$f")"
done >"$stamp"

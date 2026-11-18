"""The language as Java modules, and what runs them.

java_module_jar  java_library outputs merged into one named module's jar, with a generated
                 module-info.class (`provides` from META-INF/services): scheme, scheme.launcher,
                 scheme.test.
prelude_image    the standard libraries expanded and compiled at build time (design.md D13): a
                 context of the language writes prelude.image as it starts.
scheme_runtime   the GraalVM `java` and a module path, as runfiles and a JSON manifest of their
                 runfiles paths (tests/harness/harness.py: Runtime).
module_dir       a module path's jars in one directory, for running or embedding outside Bazel.
native_library   a launcher class's module as a shared library, for GraalVM's thin launcher
                 (design.md D17), on the target's C toolchain's execution platform.
native_setting   a string flag native_library reads: its collector, its GraalVM distribution.
polyglot_isolate the language as a polyglot isolate library, in a jar that carries it as an
                 internal resource of the engine (design.md D10, "ISOLATED and UNTRUSTED").
"""

load("@rules_cc//cc:find_cc_toolchain.bzl", "use_cc_toolchain")
load("@rules_java//java/common:java_common.bzl", "java_common")
load("@rules_java//java/common:java_info.bzl", "JavaInfo")

JavaModuleInfo = provider(
    doc = "A module jar and the module path it runs with (itself included).",
    fields = {
        "jar": "File: the module jar",
        "module_path": "depset of Files: the jar and the jars of every module it needs",
    },
)

_JDK = attr.label(default = "@bazel_tools//tools/jdk:current_java_runtime")

# The JDK of the machine an action runs on (with --config=remote, an executor's), for actions that
# run Java; _JDK is the target's, for what `bazel run` and the tests run.
EXEC_JDK = attr.label(default = "@bazel_tools//tools/jdk:current_host_java_runtime", cfg = "exec")

def _module_path(targets):
    return depset(transitive = [
        t[JavaModuleInfo].module_path if JavaModuleInfo in t else t[JavaInfo].transitive_runtime_jars
        for t in targets
    ])

def _java_module_jar_impl(ctx):
    out = ctx.actions.declare_file((ctx.attr.jar_name or ctx.attr.module_name) + ".jar")
    module_path = _module_path(ctx.attr.deps)
    class_jars = [o.class_jar for lib in ctx.attr.libs for o in lib[JavaInfo].java_outputs]
    args = ctx.actions.args()
    args.add("--out", out)
    args.add("--name", ctx.attr.module_name)
    args.add_joined("--module-path", module_path, join_with = ctx.configuration.host_path_separator)
    args.add_all(ctx.attr.requires, before_each = "--requires")
    args.add_all(ctx.attr.exports, before_each = "--exports")
    args.add_all(ctx.attr.opens, before_each = "--opens")
    args.add_all(ctx.attr.uses, before_each = "--uses")
    resources = []
    for target, path in ctx.attr.resources.items():
        for f in target.files.to_list():
            args.add("--resource", "%s=%s" % (path, f.path))
            resources.append(f)
    args.add_all(class_jars)
    ctx.actions.run(
        executable = ctx.executable._tool,
        arguments = [args],
        inputs = depset(class_jars + resources, transitive = [module_path]),
        outputs = [out],
        mnemonic = "JavaModuleJar",
        progress_message = "Building module %s" % ctx.attr.module_name,
    )
    return [
        DefaultInfo(files = depset([out])),
        JavaModuleInfo(jar = out, module_path = depset([out], transitive = [module_path])),
    ]

java_module_jar = rule(
    implementation = _java_module_jar_impl,
    doc = "`<module_name>.jar`: `libs` merged, with a module-info generated from the attributes.",
    attrs = {
        "deps": attr.label_list(doc = "Modules it needs: java_module_jar or Java targets.", providers = [[JavaModuleInfo], [JavaInfo]]),
        "exports": attr.string_list(doc = "`exports` clauses, e.g. `scheme.test to junit`."),
        "libs": attr.label_list(doc = "The java_library targets it is made of.", providers = [JavaInfo]),
        "module_name": attr.string(mandatory = True),
        "jar_name": attr.string(doc = "The jar's file name without `.jar`; the module name by default."),
        "opens": attr.string_list(),
        "requires": attr.string_list(),
        "resources": attr.label_keyed_string_dict(allow_files = True, doc = "More files: {file: path in the jar}."),
        "uses": attr.string_list(doc = "Service interfaces it loads."),
        "_tool": attr.label(default = "//bazel:module_jar", executable = True, cfg = "exec"),
    },
)

def _prelude_image_impl(ctx):
    jdk = ctx.attr._jdk[java_common.JavaRuntimeInfo]
    out = ctx.actions.declare_file(ctx.label.name + ".image")
    empty = ctx.actions.declare_file(ctx.label.name + ".empty.scm")
    ctx.actions.write(empty, "")
    classpath = depset(transitive = [lib[JavaInfo].transitive_runtime_jars for lib in ctx.attr.libs])
    args = ctx.actions.args()
    args.add_all([
        "--enable-native-access=ALL-UNNAMED",
        "--sun-misc-unsafe-memory-access=allow",
        "-Dpolyglot.engine.AllowExperimentalOptions=true",
        "-Dpolyglot.engine.Compilation=false",
        "-Dpolyglot.scheme.PreludeImageWrite=" + out.path,
    ])
    args.add_joined("-cp", classpath, join_with = ctx.configuration.host_path_separator)
    args.add_all(["scheme.launcher.SchemeMain", empty])
    ctx.actions.run(
        executable = jdk.java_executable_exec_path,
        arguments = [args],
        inputs = depset([empty], transitive = [classpath, jdk.files]),
        outputs = [out],
        mnemonic = "PreludeImage",
        progress_message = "Building the prelude image",
    )
    return [DefaultInfo(files = depset([out]))]

prelude_image = rule(
    implementation = _prelude_image_impl,
    doc = "`<name>.image`: what a context of `libs` (on the class path) writes with the constant option scheme.PreludeImageWrite.",
    attrs = {"libs": attr.label_list(providers = [JavaInfo]), "_jdk": EXEC_JDK},
)

def module_path(targets):
    """The module path of java_module_jar (or Java) targets: their jars and every jar they need."""
    return _module_path(targets)

def rlocation(ctx, f):
    """A file's runfiles path."""
    return _rlocation(ctx, f)

def _rlocation(ctx, f):
    path = f.short_path
    return path[3:] if path.startswith("../") else ctx.workspace_name + "/" + path

def _jdk_tree_impl(ctx):
    jdk = ctx.attr._jdk[java_common.JavaRuntimeInfo]
    out = ctx.actions.declare_directory(ctx.label.name)
    files = [f for f in jdk.files.to_list() if f.path.startswith(jdk.java_home + "/") and
             not f.path.startswith(jdk.java_home + "/lib/svm/")]
    args = ctx.actions.args()
    args.add(jdk.java_home)
    args.add(out.path)
    args.add_all(files)
    linux = ctx.target_platform_has_constraint(ctx.attr._linux[platform_common.ConstraintValueInfo])
    ctx.actions.run_shell(
        command = 'home="$1" out="$2"; shift 2; for f in "$@"; do rel="${f#"$home"/}"; ' +
                  'mkdir -p "$out/$(dirname "$rel")" && cp -pL "$f" "$out/$rel"; done',
        arguments = [args],
        inputs = files,
        outputs = [out],
        mnemonic = "JdkTree",
        progress_message = "Copying the JDK into one directory",
        # For a target that isn't Linux (this machine), copied here, so the files keep the release's
        # modification times however often Bazel makes the tree again (after a switch of
        # configuration, between --config=local and the remote one) while the AOT cache's action is still
        # up to date: the cache's stamp records them (design.md D17, "Where it is valid"). From the
        # remote cache, they would be the time of each download.
        execution_requirements = {} if linux else {"no-remote": "1"},
    )
    return [DefaultInfo(files = depset([out]))]

jdk_tree = rule(
    implementation = _jdk_tree_impl,
    doc = "`<name>/`: the target's JDK as one directory (tree artifact), without native-image's toolchain. " +
          "Tests take it as one input instead of ~2000 files, which remote execution stages much faster.",
    attrs = {"_jdk": _JDK, "_linux": attr.label(default = "@platforms//os:linux")},
)

# The launcher's AOT cache (design.md D17, "The AOT cache"): a run of the training program with
# -XX:AOTCacheOutput, on the JVM options the cache needs to match at run time (the collector, the
# modules, native access; tests/harness/harness.py has the same: LAUNCHER_GC_VM_ARGS,
# AOT_VM_ARGS, TRUFFLE_VM_ARGS), its logging off. The module path is the jars' real paths: the JVM
# compares the run's module path with the paths the cache was made with, resolved where it runs (a
# relative one against the run's directory, a sandbox's after the sandbox is gone). Then the
# stamp: the size, modification time and real path of each file the JVM checks the cache against
# (the JDK's modules image and the module path), a line each, which the launcher compares first.
_AOT_COMMAND = """
java_home="$1" aot="$2" stamp="$3" training="$4" main="$5"; shift 5
mp=""
for f in "$@"; do mp="$mp${mp:+:}$(realpath "$f")"; done
log="$(mktemp)"
"$java_home/bin/java" -XX:+UseSerialGC --add-modules=jdk.internal.vm.ci -Xlog:aot=off -Xlog:cds=off \\
    -XX:AOTCacheOutput="$aot" -p "$mp" --add-modules ALL-MODULE-PATH \\
    --enable-native-access=org.graalvm.truffle,org.graalvm.shadowed.jline --sun-misc-unsafe-memory-access=allow \\
    -m "$main" "$training" >"$log" 2>&1 && [ -s "$aot" ] || { cat "$log"; exit 1; }
for f in "$java_home/lib/modules" "$@"; do
    echo "$(stat -L -c '%s %Y' "$f" 2>/dev/null || stat -L -f '%z %m' "$f") $(realpath "$f")"
done >"$stamp"
"""

def _aot_cache(ctx, jdk, module_path):
    """`<name>.aot` and `<name>.aot.stamp`, by the target's JDK: here for this machine, where the
    cache must be built (it names the files' real paths, so the launcher can use it only where it
    was made), on the executors for a Linux target (the tests'), where it costs one action and
    the launcher finds it stale."""
    aot = ctx.actions.declare_file(ctx.label.name + ".aot")
    stamp = ctx.actions.declare_file(ctx.label.name + ".aot.stamp")
    args = ctx.actions.args()
    args.add_all([jdk, aot, stamp, ctx.file.aot_training], expand_directories = False)
    args.add("scheme.launcher/scheme.launcher.SchemeMain")
    args.add_all(module_path)
    linux = ctx.target_platform_has_constraint(ctx.attr._linux[platform_common.ConstraintValueInfo])
    ctx.actions.run_shell(
        command = _AOT_COMMAND,
        arguments = [args],
        inputs = [jdk, ctx.file.aot_training] + module_path,
        outputs = [aot, stamp],
        mnemonic = "AotCache",
        progress_message = "Training the launcher's AOT cache %{label}",
        # Neither from nor to the remote cache: a cache names the paths it was made with.
        execution_requirements = {} if linux else {"no-remote": "1"},
    )
    return aot, stamp

def _scheme_runtime_impl(ctx):
    jdk = ctx.file._jdk_tree
    module_path = _module_path([ctx.attr.module] + ctx.attr.extra).to_list()
    manifest = ctx.actions.declare_file(ctx.label.name + ".json")
    files = [manifest, jdk] + module_path
    data = {
        "java": _rlocation(ctx, jdk) + "/bin/java",
        "release": _rlocation(ctx, jdk) + "/release",
        "module_path": [_rlocation(ctx, f) for f in module_path],
    }
    if ctx.file.aot_training:
        aot, stamp = _aot_cache(ctx, jdk, module_path)
        data["aot_cache"] = _rlocation(ctx, aot)
        data["aot_stamp"] = _rlocation(ctx, stamp)
        files += [aot, stamp]
    ctx.actions.write(manifest, json.encode_indent(data))
    return [DefaultInfo(files = depset([manifest]), runfiles = ctx.runfiles(files = files))]

scheme_runtime = rule(
    implementation = _scheme_runtime_impl,
    doc = "The JDK and a module path (`module` and `extra`), with a JSON manifest `<name>.json`; " +
          "with `aot_training`, the launcher's AOT cache for them.",
    attrs = {
        "aot_training": attr.label(
            allow_single_file = [".scm"],
            doc = "A program whose run with the launcher trains its AOT cache (design.md D17).",
        ),
        "extra": attr.label_list(doc = "More modules nothing requires, e.g. the tools.", providers = [JavaInfo]),
        "module": attr.label(mandatory = True, providers = [JavaModuleInfo]),
        "_jdk_tree": attr.label(default = "//bazel:jdk", allow_single_file = True),
        "_linux": attr.label(default = "@platforms//os:linux"),
    },
)

def _module_dir_impl(ctx):
    jars = []
    for f in _module_path([ctx.attr.module] + ctx.attr.extra).to_list():
        jar = ctx.actions.declare_file("%s/%s" % (ctx.label.name, f.basename))
        ctx.actions.symlink(output = jar, target_file = f)
        jars.append(jar)
    return [DefaultInfo(files = depset(jars))]

module_dir = rule(
    implementation = _module_dir_impl,
    doc = "`bazel-bin/<package>/<name>/`: the module path's jars, for `java -p` outside Bazel.",
    attrs = {
        "extra": attr.label_list(providers = [JavaInfo]),
        "module": attr.label(mandatory = True, providers = [JavaModuleInfo]),
    },
)

NativeSettingInfo = provider(
    doc = "A native_setting's value.",
    fields = {"value": "string"},
)

def _flag(label):
    return "--//%s:%s" % (label.package, label.name)

def _native_setting_impl(ctx):
    value = ctx.build_setting_value
    if value not in ctx.attr.values:
        fail("%s=%s: expected one of %s" % (_flag(ctx.label), value, ", ".join(ctx.attr.values)))
    return [NativeSettingInfo(value = value)]

native_setting = rule(
    implementation = _native_setting_impl,
    doc = "A string flag that configures native_library (`--//:native_gc=...`), one of `values`.",
    build_setting = config.string(flag = True),
    attrs = {"values": attr.string_list(mandatory = True)},
)

def _native_library_impl(ctx):
    jdk = ctx.attr.jdk[java_common.JavaRuntimeInfo]
    gc = ctx.attr.gc[NativeSettingInfo].value
    graalvm = ctx.attr.graalvm[NativeSettingInfo].value
    if gc == "G1" and graalvm != "oracle":
        # GraalVM CE's native-image says only "'G1' is not an accepted value. Accepted values
        # are 'epsilon', 'serial'."
        fail(("%s=G1: the G1 collector is in Oracle GraalVM's native-image only, not GraalVM CE's. " +
              "Add %s=oracle (docs/usage.md, \"Choosing a garbage collector\").") %
             (_flag(ctx.attr.gc.label), _flag(ctx.attr.graalvm.label)))
    linux = ctx.target_platform_has_constraint(ctx.attr._linux[platform_common.ConstraintValueInfo])
    module_name, launcher_class = ctx.attr.main.split("/")

    # The image as a shared library, as GraalVM builds a language library with launchers
    # (mx_sdk_vm_impl.py, NativeImageProperties.contents; design.md D17, "VM options"): the
    # framework creates the launcher class it names, runs the startup hooks once the options are
    # processed (so not as the isolate is created: -H:-InitializeVM), and handles signals as an
    # executable does by default. Not -H:-ParseRuntimeOptions, which would ignore the options the
    # thin launcher creates the isolate with. The bound on runtime-compiled methods that the jars'
    # native-image.properties add up (-H:MaxRuntimeCompileMethods: Truffle's runtime's and the
    # language's) fails the build only when enforced, as GraalVM's gates enforce it (design.md D17,
    # "Runtime-compiled methods").
    if ctx.attr.should_fail:
        out = ctx.actions.declare_file(ctx.label.name + ".log")
    else:
        out = ctx.actions.declare_file("lib%s.%s" % (ctx.label.name, "so" if linux else "dylib"))
    module_path = _module_path([ctx.attr.module])
    args = ctx.actions.args()
    args.add(jdk.java_home)
    args.add(out)
    args.add_joined(module_path, join_with = ctx.configuration.host_path_separator)
    args.add(module_name)
    args.add("lib" + ctx.label.name)
    args.add_all([
        "--shared",
        "-Dorg.graalvm.launcher.class=" + launcher_class,
        "-H:+UnlockExperimentalVMOptions",
        "-H:-InitializeVM",
        "-H:+InstallExitHandlers",
        "-H:+EnforceMaxRuntimeCompileMethods",
    ])

    # Reachability metadata (reflection for the classes host interop reaches) from a directory, as an
    # embedder's jar gives it in `META-INF/native-image/` (design.md D20, "The launchers").
    if ctx.file.metadata:
        args.add("-H:ConfigurationFileDirectories=" + ctx.file.metadata.dirname)
    args.add_all([
        "-H:-UnlockExperimentalVMOptions",
        "-R:+EnableSignalHandling",
        "-R:+InstallSegfaultHandler",
        "--gc=" + gc,
    ])
    args.add_all(ctx.attr.options)
    if gc == "serial":
        args.add_all(ctx.attr.serial_options)

    # The image's working files (its C headers among them) go to a temporary directory; only the
    # library is the output. native-image compiles and links with the C compiler on the action's
    # PATH, on the execution platform the target's C++ toolchain chose (below). With
    # `should_fail`, the output is instead native-image's output and exit status, whatever it is.
    command = ('home="$1" out="$2" mp="$3" module="$4" name="$5"; shift 5; tmp="$(mktemp -d)"; ' +
               '"$home/bin/native-image" -p "$mp" --add-modules="$module" -o "$tmp/$name" "$@"')
    if ctx.attr.should_fail:
        command = '{ %s; echo "native-image exit status: $?"; } > "$2" 2>&1' % command
    else:
        command += ' && cp "$tmp/$(basename "$out")" "$out"'
    ctx.actions.run_shell(
        command = command,
        arguments = [args],
        inputs = depset(ctx.files.metadata, transitive = [module_path, jdk.files]),
        outputs = [out],
        mnemonic = "NativeImage",
        progress_message = "Building the native launcher's library %{label}",
        use_default_shell_env = True,
    )
    return [DefaultInfo(files = depset([out]))]

native_library = rule(
    implementation = _native_library_impl,
    doc = "`lib<name>.{so,dylib}`: a launcher class's module as a shared library, by the pinned " +
          "GraalVM's native-image, with the collector `gc` names (`--gc=`), for GraalVM's thin " +
          "launcher (design.md D17). It needs the target's C++ toolchain, so it runs on that " +
          "toolchain's execution platform: the remote executors for Linux, this Mac for macOS " +
          "(design.md D11, \"Platforms\").",
    attrs = {
        "gc": attr.label(mandatory = True, providers = [NativeSettingInfo], doc = "serial, epsilon or G1"),
        "graalvm": attr.label(mandatory = True, providers = [NativeSettingInfo], doc = "ce or oracle: which `jdk` is"),
        "jdk": attr.label(mandatory = True, providers = [java_common.JavaRuntimeInfo], doc = "whose native-image builds it"),
        "main": attr.string(mandatory = True, doc = "module/class: the launcher (an AbstractLanguageLauncher)"),
        "metadata": attr.label(allow_single_file = [".json"], doc = "a `reachability-metadata.json` to build the image " +
                                                                    "with, as an embedder's jar has it"),
        "module": attr.label(mandatory = True, providers = [JavaModuleInfo]),
        "options": attr.string_list(),
        "serial_options": attr.string_list(doc = "more options, with the serial collector only"),
        "should_fail": attr.bool(doc = "for a build that should fail: the target is then " +
                                       "`<name>.log`, native-image's output and exit status, " +
                                       "instead of the library"),
        "_linux": attr.label(default = "@platforms//os:linux"),
    },
    toolchains = use_cc_toolchain(mandatory = True),
)

def _polyglot_isolate_impl(ctx):
    jdk = ctx.attr.jdk[java_common.JavaRuntimeInfo]
    has = lambda attr: ctx.target_platform_has_constraint(attr[platform_common.ConstraintValueInfo])
    os = "linux" if has(ctx.attr._linux) else "darwin"
    arch = "amd64" if has(ctx.attr._x86_64) else "aarch64"
    platform = "%s_%s" % (os, arch)
    if platform not in ctx.attr.resources:
        fail("no polyglot isolate resource for %s: add one to `resources`" % platform)

    # The library, as mx builds a language's isolate (truffle/mx.truffle/mx_truffle.py,
    # PolyglotIsolateProject): the guest side of polyglot isolates (PolyglotIsolateGuestFeature,
    # from native-image's truffle-svm macro, which truffle-runtime's native-image.properties
    # enables), its entry points' prefix, and an isolate the host initializes (-H:-InitializeVM).
    # Runtime options stay parsed: the host passes engine.MaxIsolateMemory and UNTRUSTED's
    # mitigations as the isolate's arguments. The bound on runtime-compiled methods is enforced, as
    # in native_library (design.md D17, "Runtime-compiled methods").
    name = "libpolyglotisolate"
    library = ctx.actions.declare_file("%s/%s.%s" % (ctx.label.name, name, "so" if os == "linux" else "dylib"))
    module_path = _module_path([ctx.attr.module])
    args = ctx.actions.args()
    args.add(jdk.java_home)
    args.add(library)
    args.add_joined(module_path, join_with = ctx.configuration.host_path_separator)
    args.add_joined(ctx.attr.add_modules, join_with = ",")
    args.add(name)
    args.add_all([
        "--shared",
        "--features=com.oracle.svm.truffle.PolyglotIsolateGuestFeature",
        "-H:APIFunctionPrefix=truffle_isolate_",
        "-H:+UnlockExperimentalVMOptions",
        "-H:+CopyLanguageResources",
        "-H:-InitializeVM",
        "-H:+EnforceMaxRuntimeCompileMethods",
        "-H:-UnlockExperimentalVMOptions",
    ])
    args.add_all(ctx.attr.options)
    ctx.actions.run_shell(
        # As native_library's: the image's working files go to a temporary directory, and
        # native-image compiles and links with the C compiler on the action's PATH, on the
        # execution platform the target's C++ toolchain chose. The module path is a list of jars:
        # given a directory instead, native-image reads none of their native-image.properties.
        command = 'home="$1" out="$2" mp="$3" module="$4" name="$5"; shift 5; tmp="$(mktemp -d)"; ' +
                  '"$home/bin/native-image" -p "$mp" --add-modules="$module" -o "$tmp/$name" "$@" && ' +
                  'cp "$tmp/$(basename "$out")" "$out"',
        arguments = [args],
        inputs = depset(transitive = [module_path, jdk.files]),
        outputs = [library],
        mnemonic = "NativeImage",
        progress_message = "Building the polyglot isolate library %{label}",
        use_default_shell_env = True,
    )

    # The jar: the library as an internal resource (scheme.isolate.IsolateResource), its digest,
    # which names the version Truffle unpacks, and the files to unpack, a java.util.Properties of
    # path = permissions (InternalResource.Env.unpackResourceFiles).
    base = "META-INF/resources/engine/scheme-isolate-%s-%s/libvm/" % (os, arch)
    digest = ctx.actions.declare_file("%s/sha256" % ctx.label.name)
    ctx.actions.run_shell(
        command = '{ sha256sum "$1" 2>/dev/null || shasum -a 256 "$1"; } | cut -c1-64 > "$2"',
        arguments = [library.path, digest.path],
        inputs = [library],
        outputs = [digest],
        mnemonic = "Digest",
        progress_message = "Digesting %{input}",
    )
    files = ctx.actions.declare_file("%s/files" % ctx.label.name)
    ctx.actions.write(files, "%s%s = rwxr-xr-x\n" % (base, library.basename))
    jar = ctx.actions.declare_file(ctx.label.name + ".jar")
    deps_path = _module_path(ctx.attr.deps)
    class_jars = [o.class_jar for lib in ctx.attr.libs + [ctx.attr.resources[platform]] for o in lib[JavaInfo].java_outputs]
    jar_args = ctx.actions.args()
    jar_args.add("--out", jar)
    jar_args.add("--name", ctx.attr.module_name)
    jar_args.add_joined("--module-path", deps_path, join_with = ctx.configuration.host_path_separator)
    jar_args.add_all(ctx.attr.requires, before_each = "--requires")
    for f in [library, digest, files]:
        jar_args.add("--resource", "%s%s=%s" % (base, f.basename, f.path))
    jar_args.add_all(class_jars)
    ctx.actions.run(
        executable = ctx.executable._tool,
        arguments = [jar_args],
        inputs = depset(class_jars + [library, digest, files], transitive = [deps_path]),
        outputs = [jar],
        mnemonic = "JavaModuleJar",
        progress_message = "Building module %s" % ctx.attr.module_name,
    )
    return [
        DefaultInfo(files = depset([jar])),
        JavaModuleInfo(jar = jar, module_path = depset([jar], transitive = [deps_path])),
        OutputGroupInfo(library = depset([library])),
    ]

polyglot_isolate = rule(
    implementation = _polyglot_isolate_impl,
    doc = "`<name>.jar`, the module `module_name`: `module`, built by the pinned GraalVM's " +
          "native-image as a polyglot isolate library for the target platform " +
          "(`<name>/libpolyglotisolate.{so,dylib}`), carried as an internal resource of the " +
          "engine, as GraalVM's `-isolate` jars carry their languages' (design.md D10, " +
          "\"ISOLATED and UNTRUSTED\"). Its module path is the jar and `deps`'. Like " +
          "native_library, it runs on the execution platform of the target's C++ toolchain.",
    attrs = {
        "add_modules": attr.string_list(mandatory = True, doc = "the modules native-image builds: the language's, and what it needs added"),
        "deps": attr.label_list(doc = "The modules the jar needs.", providers = [[JavaModuleInfo], [JavaInfo]]),
        "jdk": attr.label(mandatory = True, providers = [java_common.JavaRuntimeInfo], doc = "whose native-image builds it"),
        "libs": attr.label_list(providers = [JavaInfo], doc = "The jar's classes, on every platform."),
        "module": attr.label(mandatory = True, providers = [JavaModuleInfo], doc = "the language's module, and its module path"),
        "module_name": attr.string(mandatory = True),
        "options": attr.string_list(doc = "more native-image options"),
        "requires": attr.string_list(),
        "resources": attr.string_keyed_label_dict(
            providers = [JavaInfo],
            doc = "{platform: the classes that say the library is for it}, platforms as `linux_amd64`",
        ),
        "_linux": attr.label(default = "@platforms//os:linux"),
        "_tool": attr.label(default = "//bazel:module_jar", executable = True, cfg = "exec"),
        "_x86_64": attr.label(default = "@platforms//cpu:x86_64"),
    },
    toolchains = use_cc_toolchain(mandatory = True),
)

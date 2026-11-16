"""The language as Java modules, and what runs them.

java_module_jar  java_library outputs merged into one named module's jar, with a generated
                 module-info.class (`provides` from META-INF/services): scheme, scheme.launcher,
                 scheme.test.
prelude_image    the standard libraries expanded and compiled at build time (design.md D13): a
                 context of the language writes prelude.image as it starts.
scheme_runtime   the GraalVM `java` and a module path, as runfiles and a JSON manifest of their
                 runfiles paths (tests/harness/harness.py: Runtime).
module_dir       a module path's jars in one directory, for running or embedding outside Bazel.
native_image     a module's main class as a native executable (design.md D17).
"""

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
    out = ctx.actions.declare_file(ctx.attr.module_name + ".jar")
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
        "-Dscheme.preludeImage.write=" + out.path,
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
    doc = "`<name>.image`: what a context of `libs` (on the class path) writes with -Dscheme.preludeImage.write.",
    attrs = {"libs": attr.label_list(providers = [JavaInfo]), "_jdk": EXEC_JDK},
)

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
    ctx.actions.run_shell(
        command = 'home="$1" out="$2"; shift 2; for f in "$@"; do rel="${f#"$home"/}"; ' +
                  'mkdir -p "$out/$(dirname "$rel")" && cp -pL "$f" "$out/$rel"; done',
        arguments = [args],
        inputs = files,
        outputs = [out],
        mnemonic = "JdkTree",
        progress_message = "Copying the JDK into one directory",
    )
    return [DefaultInfo(files = depset([out]))]

jdk_tree = rule(
    implementation = _jdk_tree_impl,
    doc = "`<name>/`: the target's JDK as one directory (tree artifact), without native-image's toolchain. " +
          "Tests take it as one input instead of ~2000 files, which remote execution stages much faster.",
    attrs = {"_jdk": _JDK},
)

def _scheme_runtime_impl(ctx):
    jdk = ctx.file._jdk_tree
    module_path = _module_path([ctx.attr.module] + ctx.attr.extra).to_list()
    manifest = ctx.actions.declare_file(ctx.label.name + ".json")
    ctx.actions.write(manifest, json.encode_indent({
        "java": _rlocation(ctx, jdk) + "/bin/java",
        "release": _rlocation(ctx, jdk) + "/release",
        "module_path": [_rlocation(ctx, f) for f in module_path],
    }))
    return [DefaultInfo(files = depset([manifest]), runfiles = ctx.runfiles(files = [manifest, jdk] + module_path))]

scheme_runtime = rule(
    implementation = _scheme_runtime_impl,
    doc = "The JDK and a module path (`module` and `extra`), with a JSON manifest `<name>.json`.",
    attrs = {
        "extra": attr.label_list(doc = "More modules nothing requires, e.g. the tools.", providers = [JavaInfo]),
        "module": attr.label(mandatory = True, providers = [JavaModuleInfo]),
        "_jdk_tree": attr.label(default = "//bazel:jdk", allow_single_file = True),
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

def _native_image_impl(ctx):
    jdk = ctx.attr._jdk[java_common.JavaRuntimeInfo]
    module_path = _module_path([ctx.attr.module])
    out = ctx.actions.declare_file(ctx.label.name)
    args = ctx.actions.args()
    args.add(jdk.java_home)
    args.add(out)
    args.add_joined(module_path, join_with = ctx.configuration.host_path_separator)
    args.add(ctx.attr.main)
    args.add_all(ctx.attr.options)
    linux = ctx.target_platform_has_constraint(ctx.attr._linux[platform_common.ConstraintValueInfo])
    ctx.actions.run_shell(
        # The image's working files go to a temporary directory; only the executable is the output.
        command = 'home="$1" out="$2" mp="$3" main="$4"; shift 4; tmp="$(mktemp -d)"; ' +
                  '"$home/bin/native-image" -p "$mp" -m "$main" -o "$tmp/image" "$@" && cp "$tmp/image" "$out"',
        arguments = [args],
        inputs = depset(transitive = [module_path, jdk.files]),
        outputs = [out],
        mnemonic = "NativeImage",
        progress_message = "Building the native launcher %{label}",
        # The target's native-image and C toolchain: on the executors for the tests (they target
        # Linux), here for this machine's `bazel-bin/scheme-native`.
        use_default_shell_env = True,
        execution_requirements = {} if linux else {"no-remote-exec": "1"},
    )
    return [DefaultInfo(files = depset([out]), executable = out)]

native_image = rule(
    implementation = _native_image_impl,
    doc = "`<name>`: a module's main class as a native executable, by the pinned GraalVM's native-image " +
          "and the host's C toolchain (design.md D17).",
    attrs = {
        "main": attr.string(mandatory = True, doc = "module/class, as `java -m` takes it"),
        "module": attr.label(mandatory = True, providers = [JavaModuleInfo]),
        "options": attr.string_list(),
        "_jdk": _JDK,
        "_linux": attr.label(default = "@platforms//os:linux"),
    },
    executable = True,
)

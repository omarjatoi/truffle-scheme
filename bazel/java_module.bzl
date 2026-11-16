"""Named Java modules (mx distributions with "moduleInfo"), and the runtime that runs them.

java_module_jar  merges java_library outputs into one module jar with a generated
                 module-info.class, like mx's SCHEME, SCHEME_LAUNCHER and SCHEME_TEST.
scheme_runtime   the GraalVM `java` and a module path (a module jar, everything it needs), as
                 runfiles plus a JSON manifest of their runfiles paths, for the test drivers.
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

def _module_path(deps):
    paths = []
    for dep in deps:
        if JavaModuleInfo in dep:
            paths.append(dep[JavaModuleInfo].module_path)
        else:
            paths.append(dep[JavaInfo].transitive_runtime_jars)
    return depset(transitive = paths)

def _java_module_jar_impl(ctx):
    out = ctx.actions.declare_file(ctx.label.name + ".jar")
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
    args.add_all(class_jars)
    ctx.actions.run(
        executable = ctx.executable._tool,
        arguments = [args],
        inputs = depset(class_jars, transitive = [module_path]),
        outputs = [out],
        mnemonic = "JavaModuleJar",
        progress_message = "Building module %s (%s)" % (ctx.attr.module_name, out.short_path),
    )
    return [
        DefaultInfo(files = depset([out])),
        JavaModuleInfo(jar = out, module_path = depset([out], transitive = [module_path])),
    ]

java_module_jar = rule(
    implementation = _java_module_jar_impl,
    doc = "A named module's jar: `libs` merged, with a module-info generated from the attributes.",
    attrs = {
        "deps": attr.label_list(
            doc = "Modules this one needs at compile and run time: java_module_jar or Java targets.",
            providers = [[JavaModuleInfo], [JavaInfo]],
        ),
        "exports": attr.string_list(doc = "`exports` clauses, e.g. `scheme.test to junit`."),
        "libs": attr.label_list(doc = "java_library targets whose classes and resources form the module.", providers = [JavaInfo]),
        "module_name": attr.string(mandatory = True),
        "opens": attr.string_list(doc = "`opens` clauses."),
        "requires": attr.string_list(doc = "Module names this one requires."),
        "uses": attr.string_list(doc = "Service interfaces it loads (`uses`). `provides` comes from META-INF/services."),
        "_tool": attr.label(default = "//bazel:module_jar", executable = True, cfg = "exec"),
    },
)

def _rlocation(ctx, f):
    path = f.short_path
    return path[3:] if path.startswith("../") else ctx.workspace_name + "/" + path

def _scheme_runtime_impl(ctx):
    java_runtime = ctx.attr._jdk[java_common.JavaRuntimeInfo]
    files = java_runtime.files.to_list()
    java = [f for f in files if f.path == java_runtime.java_executable_exec_path]
    release = [f for f in files if f.basename == "release" and f.dirname == java_runtime.java_home]
    if len(java) != 1:
        fail("scheme_runtime: cannot find bin/java in the Java runtime %s" % java_runtime.java_home)
    module_path = ctx.attr.module[JavaModuleInfo].module_path.to_list()
    manifest = ctx.actions.declare_file(ctx.label.name + ".json")
    ctx.actions.write(manifest, json.encode_indent({
        "java": _rlocation(ctx, java[0]),
        "release": _rlocation(ctx, release[0]) if release else None,
        "module_path": [_rlocation(ctx, f) for f in module_path],
    }))
    return [DefaultInfo(
        files = depset([manifest]),
        runfiles = ctx.runfiles(files = [manifest] + module_path, transitive_files = java_runtime.files),
    )]

scheme_runtime = rule(
    implementation = _scheme_runtime_impl,
    doc = "The test JDK and a module path, with a JSON manifest (`java`, `release`, `module_path`) of their runfiles paths.",
    attrs = {
        "module": attr.label(mandatory = True, providers = [JavaModuleInfo]),
        "_jdk": attr.label(default = "@bazel_tools//tools/jdk:current_java_runtime"),
    },
)

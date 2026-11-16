"""Static checks of a java_library, as tests tagged `lint`. java_lint(name, lib, srcs) makes:

  <name>_format      the Eclipse formatter (GraalVM's settings) would change nothing
  <name>_checkstyle  Checkstyle with checkstyle.xml
  <name>_ecj         ECJ compiles it with GraalVM's JDT settings, warnings are errors
  <name>_spotbugs    SpotBugs, skipping DSL-generated classes; not for test code

javac with -Werror is the build itself (bazel/java.bzl).
"""

load("@rules_java//java/common:java_info.bzl", "JavaInfo")
load("//tests/harness:defs.bzl", "harness_test")

def _rlocation(ctx, f):
    path = f.short_path
    return path[3:] if path.startswith("../") else ctx.workspace_name + "/" + path

def _lint_inputs_impl(ctx):
    info = ctx.attr.lib[JavaInfo]
    class_jars = [o.class_jar for o in info.java_outputs]
    generated = [o.generated_source_jar for o in info.java_outputs if o.generated_source_jar]
    classpath = info.compilation_info.compilation_classpath
    lists = {
        "srcs": ctx.files.srcs,
        "classpath": classpath.to_list(),
        "runtime_classpath": info.transitive_runtime_jars.to_list(),
        "class_jars": class_jars,
        "generated_sources": generated,
        "processor_path": ctx.files.processor_path,
    }
    manifest = ctx.actions.declare_file(ctx.label.name + ".json")
    ctx.actions.write(manifest, json.encode({k: [_rlocation(ctx, f) for f in v] for k, v in lists.items()}))
    return [DefaultInfo(files = depset([manifest]), runfiles = ctx.runfiles(
        files = [manifest] + ctx.files.srcs + class_jars + generated + ctx.files.processor_path,
        transitive_files = depset(transitive = [classpath, info.transitive_runtime_jars]),
    ))]

_lint_inputs = rule(
    implementation = _lint_inputs_impl,
    doc = "A java_library's sources, class jars, generated sources and classpaths, as runfiles and a JSON manifest.",
    attrs = {
        "lib": attr.label(mandatory = True, providers = [JavaInfo]),
        "processor_path": attr.label_list(allow_files = [".jar"]),
        "srcs": attr.label_list(allow_files = [".java"]),
    },
)

def _rloc(label):
    return "$(rlocationpath //bazel/lint:%s)" % label

# check: (tool, its configuration or None, size)
_CHECKS = {
    "format": ("eclipse_format", "org.eclipse.jdt.core.prefs", "small"),
    "checkstyle": ("checkstyle", "checkstyle.xml", "small"),
    "ecj": ("ecj", "org.eclipse.jdt.core.prefs", "medium"),
    "spotbugs": ("spotbugs", None, "medium"),
}

def java_lint(name, lib, srcs, truffle_dsl = False, spotbugs = True):
    inputs = name + "_lint_inputs"
    _lint_inputs(
        name = inputs,
        lib = lib,
        processor_path = ["@jvm//:org_graalvm_truffle_truffle_dsl_processor"] if truffle_dsl else [],
        srcs = srcs,
    )
    for check, (tool, config, size) in _CHECKS.items():
        if check == "spotbugs" and not spotbugs:
            continue
        harness_test(
            name = "%s_%s" % (name, check),
            args = ["lint", check, "--inputs=$(rlocationpath :%s)" % inputs, "--tool=" + _rloc(tool)] +
                   (["--prefs=" + _rloc(config) if config.endswith(".prefs") else "--config=" + _rloc(config)] if config else []),
            data = [":" + inputs, "//bazel/lint:" + tool] + (["//bazel/lint:" + config] if config else []),
            size = size,
            tags = ["lint"],
        )

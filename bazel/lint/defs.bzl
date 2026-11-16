"""Static checks of a java_library, as tests tagged `lint` (what `mx checks` ran on each project).

java_lint(name, lib, srcs) makes, for the java_library `lib`:

  <name>_format      the Eclipse formatter would change nothing (mx checks format)
  <name>_checkstyle  Checkstyle with mx.r7rs/projects/runtime/.checkstyle_checks.xml (mx checks checkstyle)
  <name>_ecj         ECJ compiles it with GraalVM's JDT settings, warnings are errors (mx checks ecj)
  <name>_spotbugs    SpotBugs, as mx runs it, skipping DSL-generated classes (mx checks spotbugs);
                     not for test code, as in mx

javac with -Werror (mx checks build) is the build itself (bazel/java.bzl).
"""

load("@rules_java//java/common:java_info.bzl", "JavaInfo")
load("@rules_python//python:defs.bzl", "py_test")

def _rlocation(ctx, f):
    path = f.short_path
    return path[3:] if path.startswith("../") else ctx.workspace_name + "/" + path

def _lint_inputs_impl(ctx):
    info = ctx.attr.lib[JavaInfo]
    outputs = info.java_outputs
    class_jars = [o.class_jar for o in outputs]
    generated = [o.generated_source_jar for o in outputs if o.generated_source_jar]
    classpath = info.compilation_info.compilation_classpath.to_list()
    runtime_classpath = info.transitive_runtime_jars.to_list()
    processor_path = ctx.files.processor_path
    manifest = ctx.actions.declare_file(ctx.label.name + ".json")
    ctx.actions.write(manifest, json.encode_indent({
        "srcs": [_rlocation(ctx, f) for f in ctx.files.srcs],
        "classpath": [_rlocation(ctx, f) for f in classpath],
        "runtime_classpath": [_rlocation(ctx, f) for f in runtime_classpath],
        "class_jars": [_rlocation(ctx, f) for f in class_jars],
        "generated_sources": [_rlocation(ctx, f) for f in generated],
        "processor_path": [_rlocation(ctx, f) for f in processor_path],
    }))
    files = [manifest] + ctx.files.srcs + class_jars + generated + processor_path
    return [DefaultInfo(
        files = depset([manifest]),
        runfiles = ctx.runfiles(files = files, transitive_files = depset(transitive = [
            info.compilation_info.compilation_classpath,
            info.transitive_runtime_jars,
        ])),
    )]

_lint_inputs = rule(
    implementation = _lint_inputs_impl,
    doc = "A java_library's sources, class jars, generated sources and classpaths, as runfiles and a JSON manifest.",
    attrs = {
        "lib": attr.label(mandatory = True, providers = [JavaInfo]),
        "processor_path": attr.label_list(allow_files = [".jar"]),
        "srcs": attr.label_list(allow_files = [".java"]),
    },
)

_LINT = "//bazel/lint"

def _lint_test(name, check, inputs, tools, args = [], size = "small"):
    py_test(
        name = name,
        size = size,
        srcs = [_LINT + ":lint_test.py"],
        main = _LINT + ":lint_test.py",
        args = [check, "--inputs=$(rlocationpath %s)" % inputs] + args,
        data = [inputs] + tools,
        tags = ["lint"],
        deps = ["//bazel/r7rs:r7rs"],
    )

def java_lint(name, lib, srcs, truffle_dsl = False, spotbugs = True):
    """Lint tests for the java_library `lib` (see the module docstring).

    Args:
      name: prefix of the tests.
      lib: the java_library.
      srcs: its Java sources.
      truffle_dsl: it runs the Truffle DSL processor (ECJ runs it too).
      spotbugs: run SpotBugs (mx runs it on non-test projects only).
    """
    inputs = name + "_lint_inputs"
    _lint_inputs(
        name = inputs,
        lib = lib,
        processor_path = ["@jvm//:org_graalvm_truffle_truffle_dsl_processor"] if truffle_dsl else [],
        srcs = srcs,
        tags = ["manual"],
    )
    _lint_test(
        name = name + "_format",
        check = "format",
        inputs = inputs,
        tools = [_LINT + ":eclipse_format", _LINT + ":org.eclipse.jdt.core.prefs"],
        args = [
            "--tool=$(rlocationpath %s:eclipse_format)" % _LINT,
            "--prefs=$(rlocationpath %s:org.eclipse.jdt.core.prefs)" % _LINT,
        ],
    )
    _lint_test(
        name = name + "_checkstyle",
        check = "checkstyle",
        inputs = inputs,
        tools = [_LINT + ":checkstyle", _LINT + ":checkstyle_config"],
        args = [
            "--tool=$(rlocationpath %s:checkstyle)" % _LINT,
            "--config=$(rlocationpath %s:checkstyle_config)" % _LINT,
        ],
    )
    _lint_test(
        name = name + "_ecj",
        check = "ecj",
        inputs = inputs,
        tools = [_LINT + ":ecj", _LINT + ":org.eclipse.jdt.core.prefs"],
        args = [
            "--tool=$(rlocationpath %s:ecj)" % _LINT,
            "--prefs=$(rlocationpath %s:org.eclipse.jdt.core.prefs)" % _LINT,
        ],
        size = "medium",
    )
    if spotbugs:
        _lint_test(
            name = name + "_spotbugs",
            check = "spotbugs",
            inputs = inputs,
            tools = [_LINT + ":spotbugs"],
            args = ["--tool=$(rlocationpath %s:spotbugs)" % _LINT],
            size = "medium",
        )

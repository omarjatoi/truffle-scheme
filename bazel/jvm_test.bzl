"""Tests that run in the pinned JDK (//bazel:jdk), without Python.

junit_test        one JUnit class, with a module (and every module it needs) on the module path
java_driver_test  one of tests/driver's mains, which runs the language in a child JVM
"""

load("@rules_java//java/common:java_info.bzl", "JavaInfo")
load("//bazel:java_module.bzl", "module_path", "rlocation")

# Truffle's JVM options on JDK 25 (tests/harness/harness.py TRUFFLE_VM_ARGS).
_TRUFFLE_VM_ARGS = [
    "--enable-native-access=org.graalvm.truffle,org.graalvm.shadowed.jline",
    "--sun-misc-unsafe-memory-access=allow",
]

_PROCESSOR = "@jvm//:org_graalvm_truffle_truffle_dsl_processor"

def _junit_test_impl(ctx):
    jdk = ctx.file._jdk
    jars = module_path([ctx.attr.module]).to_list()
    processor = ctx.file._processor
    script = ctx.actions.declare_file(ctx.label.name + ".sh")
    path = lambda f: '"$TEST_SRCDIR/%s"' % rlocation(ctx, f)
    ctx.actions.write(script, "\n".join([
        "#!/bin/sh",
        "# JUnit, on the module path, in the pinned JDK (bazel/jvm_test.bzl junit_test).",
        "exec %s/bin/java \\" % path(jdk),
        '  -Djava.io.tmpdir="$TEST_TMPDIR" -ea -esa -Dr7rs.truffle.dslProcessor=%s \\' % path(processor),
        "  %s \\" % " ".join(ctx.attr.jvm_flags + _TRUFFLE_VM_ARGS),
        "  -p %s --add-modules ALL-MODULE-PATH \\" % ":".join([path(f) for f in jars]),
        "  -m junit/org.junit.runner.JUnitCore %s" % ctx.attr.test_class,
        "",
    ]), is_executable = True)
    return [DefaultInfo(executable = script, runfiles = ctx.runfiles(files = [jdk, processor] + jars))]

junit_test = rule(
    implementation = _junit_test_impl,
    doc = "One JUnit class in its own JVM, with `module` (and every module it needs) on the module path.",
    attrs = {
        "jvm_flags": attr.string_list(),
        "module": attr.label(mandatory = True),
        "test_class": attr.string(mandatory = True),
        "_jdk": attr.label(default = "//bazel:jdk", allow_single_file = True),
        "_processor": attr.label(default = _PROCESSOR, allow_single_file = True),
    },
    test = True,
)

def _java_driver_test_impl(ctx):
    jdk = ctx.file._jdk
    driver = ctx.attr._driver[JavaInfo].transitive_runtime_jars.to_list()
    jars = module_path([ctx.attr.module]).to_list()
    script = ctx.actions.declare_file(ctx.label.name + ".sh")
    path = lambda f: '"$TEST_SRCDIR/%s"' % rlocation(ctx, f)
    ctx.actions.write(script, "\n".join([
        "#!/bin/sh",
        "# A test driver (tests/driver) in the pinned JDK (bazel/jvm_test.bzl java_driver_test).",
        'exec %s/bin/java -Djava.io.tmpdir="$TEST_TMPDIR" -cp %s \\' % (path(jdk), ":".join([path(f) for f in driver])),
        "  scheme.driver.%s --module-path=%s \"$@\"" % (ctx.attr.main, ":".join([rlocation(ctx, f) for f in jars])),
        "",
    ]), is_executable = True)
    runfiles = ctx.runfiles(files = [jdk] + driver + jars + ctx.files.data)
    return [DefaultInfo(executable = script, runfiles = runfiles)]

java_driver_test = rule(
    implementation = _java_driver_test_impl,
    doc = "`scheme.driver.<main>` with `module`'s module path (the language it runs) and `args`.",
    attrs = {
        "data": attr.label_list(allow_files = True),
        "main": attr.string(mandatory = True),
        "module": attr.label(mandatory = True),
        "_driver": attr.label(default = "//tests:driver"),
        "_jdk": attr.label(default = "//bazel:jdk", allow_single_file = True),
    },
    test = True,
)

"""Tests that run in the pinned JDK (//bazel:jdk), without Python.

junit_test        one JUnit class, with modules (and every module they need) on the module path
java_driver_test  one of tests/driver's mains, which runs the language in a child JVM

Each writes its results to $XML_OUTPUT_FILE as JUnit XML: without the file, Bazel 9 runs a second
spawn per test to generate it (design.md D11, "Bazel 9").
"""

load("@rules_java//java/common:java_info.bzl", "JavaInfo")
load("//bazel:java_module.bzl", "module_path", "rlocation")

# Truffle's JVM options on JDK 25 (tests/harness/harness.py TRUFFLE_VM_ARGS).
_TRUFFLE_VM_ARGS = [
    "--enable-native-access=org.graalvm.truffle,org.graalvm.shadowed.jline",
    "--sun-misc-unsafe-memory-access=allow",
]

_PROCESSOR = "@jvm//:org_graalvm_truffle_truffle_dsl_processor"

# After the test's command: a JUnit XML of one test case, the target, failed unless it exited with
# 0, as Bazel's generated one has (bazel_tools' generate-xml.sh). Then the test's exit status.
_TARGET_XML = """status=$?
if [ -n "$XML_OUTPUT_FILE" ]; then
  name=$(printf '%s' "$TEST_TARGET" | sed -e 's/&/\\&amp;/g' -e 's/</\\&lt;/g' -e 's/>/\\&gt;/g' -e 's/"/\\&quot;/g')
  time=$(($(date +%s) - start))
  if [ "$status" -eq 0 ]; then failures=0 failure=""; else failures=1 failure="<failure message='exited with status $status'/>"; fi
  cat > "$XML_OUTPUT_FILE" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<testsuites>
  <testsuite name="$name" tests="1" failures="$failures" errors="0" time="$time">
    <testcase name="$name" classname="$name" time="$time">$failure</testcase>
  </testsuite>
</testsuites>
EOF
fi
exit $status
"""

def _junit_test_impl(ctx):
    jdk = ctx.file._jdk
    jars = module_path([ctx.attr.module] + ctx.attr.modules).to_list()
    processor = ctx.file._processor
    runner = ctx.attr._runner[JavaInfo].runtime_output_jars
    script = ctx.actions.declare_file(ctx.label.name + ".sh")
    path = lambda f: '"$TEST_SRCDIR/%s"' % rlocation(ctx, f)
    ctx.actions.write(script, "\n".join([
        "#!/bin/sh",
        "# JUnit, on the module path, in the pinned JDK (bazel/jvm_test.bzl junit_test); the runner, which",
        "# writes $XML_OUTPUT_FILE, on the class path.",
        "exec %s/bin/java \\" % path(jdk),
        '  -Djava.io.tmpdir="$TEST_TMPDIR" -ea -esa -Dr7rs.truffle.dslProcessor=%s \\' % path(processor),
        "  %s \\" % " ".join(ctx.attr.jvm_flags + _TRUFFLE_VM_ARGS),
        "  -p %s --add-modules ALL-MODULE-PATH \\" % ":".join([path(f) for f in jars]),
        "  -cp %s scheme.driver.junit.XmlRunner '%s'" % (":".join([path(f) for f in runner]), ctx.attr.test_class),
        "",
    ]), is_executable = True)
    return [DefaultInfo(executable = script, runfiles = ctx.runfiles(files = [jdk, processor] + jars + runner))]

junit_test = rule(
    implementation = _junit_test_impl,
    doc = "One JUnit class in its own JVM, with `module` and `modules` (and every module they need) on the module path.",
    attrs = {
        "jvm_flags": attr.string_list(),
        "module": attr.label(mandatory = True),
        "modules": attr.label_list(doc = "more modules for the module path"),
        "test_class": attr.string(mandatory = True),
        "_jdk": attr.label(default = "//bazel:jdk", allow_single_file = True),
        "_processor": attr.label(default = _PROCESSOR, allow_single_file = True),
        "_runner": attr.label(default = "//tests:junit_runner", providers = [JavaInfo]),
    },
    test = True,
)

def _java_driver_test_impl(ctx):
    jdk = ctx.file._jdk
    driver = ctx.attr._driver[JavaInfo].transitive_runtime_jars.to_list()
    jars = module_path([ctx.attr.module]).to_list() if ctx.attr.module else []
    tool = ctx.attr.tool[JavaInfo].transitive_runtime_jars.to_list() if ctx.attr.tool else []
    script = ctx.actions.declare_file(ctx.label.name + ".sh")
    path = lambda f: '"$TEST_SRCDIR/%s"' % rlocation(ctx, f)
    ctx.actions.write(script, "\n".join([
        "#!/bin/sh",
        "# A test driver (tests/driver) in the pinned JDK (bazel/jvm_test.bzl java_driver_test), and its",
        "# result as JUnit XML.",
        "start=$(date +%s)",
        '%s/bin/java -Djava.io.tmpdir="$TEST_TMPDIR" -cp %s \\' % (path(jdk), ":".join([path(f) for f in driver])),
        "  scheme.driver.%s --module-path=%s --tool-classpath=%s \"$@\"" % (
            ctx.attr.main,
            ":".join([rlocation(ctx, f) for f in jars]),
            ":".join([rlocation(ctx, f) for f in tool]),
        ),
        _TARGET_XML,
    ]), is_executable = True)
    runfiles = ctx.runfiles(files = [jdk] + driver + jars + tool + ctx.files.data).merge_all(
        [d[DefaultInfo].default_runfiles for d in ctx.attr.data],
    )
    return [DefaultInfo(executable = script, runfiles = runfiles)]

java_driver_test = rule(
    implementation = _java_driver_test_impl,
    doc = "`scheme.driver.<main>` with `module`'s module path (the language it runs), `tool`'s class " +
          "path (a tool it runs, such as a lint check's) and `args`.",
    attrs = {
        "data": attr.label_list(allow_files = True),
        "main": attr.string(mandatory = True),
        "module": attr.label(),
        "tool": attr.label(providers = [JavaInfo]),
        "_driver": attr.label(default = "//tests:driver"),
        "_jdk": attr.label(default = "//bazel:jdk", allow_single_file = True),
    },
    test = True,
)

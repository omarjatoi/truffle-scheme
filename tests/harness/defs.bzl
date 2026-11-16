"""Test targets driven by tests/harness/main.py. tests/BUILD.bazel and bazel/lint use these.

A target per JUnit class, program and spec case: each reruns only when its inputs change, and Bazel
spreads the JVMs over the machine's cores.
"""

load("@rules_java//java/common:java_common.bzl", "java_common")
load("@rules_python//python:defs.bzl", "py_test")
load("//bazel:java_module.bzl", "JavaModuleInfo")

_MAIN = "//tests/harness:main.py"

def harness_test(name, args, data, size = "medium", tags = []):
    """A py_test running main.py with these arguments (its command first)."""
    py_test(
        name = name,
        size = size,
        srcs = [_MAIN],
        main = _MAIN,
        args = args,
        data = data,
        tags = tags,
        deps = ["//tests/harness"],
    )

def _runtime(runtime):
    return "--runtime=$(rlocationpath %s)" % runtime

# mode: (size, tags). The JIT's background compiler threads take a second core; compiling
# immediately is the slowest.
_UNIT_MODES = {
    "jit": ("medium", ["cpu:2"]),
    "compile": ("large", []),
    "torture": ("medium", ["cpu:2"]),
}

_PROCESSOR = "@jvm//:org_graalvm_truffle_truffle_dsl_processor"

def unit_tests(name, srcs, runtime):
    """`<name>_<mode>_<Class>` for each *Test.java in each mode, and a test_suite per mode."""
    for mode, (size, tags) in _UNIT_MODES.items():
        tests = []
        for src in srcs:
            path = src.removesuffix(".java")
            test = "%s_%s_%s" % (name, mode, path.rsplit("/", 1)[-1])
            harness_test(
                name = test,
                args = ["unit", _runtime(runtime), "--mode=" + mode, "--processor=$(rlocationpath %s)" % _PROCESSOR,
                        path.replace("/", ".")],
                data = [runtime, _PROCESSOR],
                size = size,
                tags = tags,
            )
            tests.append(test)
        native.test_suite(name = "%s_%s" % (name, mode), tests = tests)
    native.test_suite(name = name, tests = ["%s_%s" % (name, mode) for mode in _UNIT_MODES])

def diff_tests(name, srcs, runtime, chibi):
    """`<name>_<program>` and `<name>_torture_<program>`: the same stdout and exit status as chibi-scheme."""
    tests = []
    for src in srcs:
        base = src.rsplit("/", 1)[-1].removesuffix(".scm")
        for torture in ("", "torture_"):
            harness_test(
                name = name + "_" + torture + base,
                args = ["diff", _runtime(runtime), "--chibi=$(rlocationpath %s)" % chibi, "$(rlocationpath %s)" % src] +
                       (["--torture"] if torture else []),
                data = [runtime, src, chibi],
                # chibi-scheme itself takes about 90 s on escape-callcc.scm (ctak with real continuations).
                size = "large",
            )
            tests.append(name + "_" + torture + base)
    native.test_suite(name = name, tests = tests)

def spec_tests(name, srcs, runtime):
    """`<name>_<case>`: tests/spec cases, interpreted and with the JIT, under their JVM limits."""
    tests = []
    for src in srcs:
        test = "%s_%s" % (name, src.rsplit("/", 1)[-1].removesuffix(".scm"))
        harness_test(
            name = test,
            args = ["program", "spec", _runtime(runtime), "$(rlocationpath %s)" % src],
            data = [runtime, src, src.removesuffix(".scm") + ".out"],
            size = "large",
            tags = ["cpu:2"],
        )
        tests.append(test)
    native.test_suite(name = name, tests = tests)

def space_test(name, src, expect, limits, runtime):
    """A program that must print `expect` under the JVM `limits`, interpreted and with the JIT."""
    harness_test(
        name = name,
        # Quoted: Bazel splits `args` like a shell.
        args = ["program", "space", _runtime(runtime), "$(rlocationpath %s)" % src, "--expect=" + expect,
                "--limits='%s'" % limits],
        data = [runtime, src],
        size = "large",
        tags = ["cpu:2"],
    )

def tools_tests(name, runtime, tools_runtime, programs):
    """`<name>_<test>` per smoke test of tools.py, with and without the tools on the module path."""
    tests = ["help", "dap", "inspect", "cpusampler", "memtracer", "terminal"]
    for test in tests:
        harness_test(
            name = "%s_%s" % (name, test),
            args = ["tools", _runtime(runtime), "--tools-runtime=$(rlocationpath %s)" % tools_runtime,
                    "--programs=$(rlocationpath %s)" % programs[0], test],
            data = [runtime, tools_runtime] + programs,
            # The DAP and Chrome DevTools clients talk to the launcher over 127.0.0.1.
            tags = ["cpu:2", "requires-network"],
        )
    native.test_suite(name = name, tests = ["%s_%s" % (name, t) for t in tests])

def _conformance_results_impl(ctx):
    jdk = ctx.attr._jdk[java_common.JavaRuntimeInfo]
    module_path = ctx.attr.module[JavaModuleInfo].module_path
    args = ctx.actions.args()
    args.add_all(["conformance-results", "--suite", ctx.attr.suite, "--java", jdk.java_executable_exec_path])
    args.add_all("--module-path", module_path)
    args.add_all(["--json", ctx.outputs.json, "--block", ctx.outputs.block])
    ctx.actions.run(
        executable = ctx.executable._tool,
        arguments = [args],
        inputs = depset(ctx.files.srcs, transitive = [module_path, jdk.files]),
        outputs = [ctx.outputs.json, ctx.outputs.block],
        mnemonic = "R7rsConformance",
        progress_message = "Running the %s conformance suite" % ctx.attr.suite,
        # 6.14 checks that PATH is set: --incompatible_strict_action_env's fixed one.
        use_default_shell_env = True,
        resource_set = _four_cpus,
    )

def _four_cpus(_os, _inputs):
    return {"cpu": 4, "memory": 2048}  # a JVM per section, four at a time

conformance_results = rule(
    implementation = _conformance_results_impl,
    doc = "One suite, interpreted only, as a cached build action: `<name>.md` (its part of the status block) and `<name>.json`.",
    attrs = {
        "module": attr.label(mandatory = True, providers = [JavaModuleInfo]),
        "srcs": attr.label_list(allow_files = True),
        "suite": attr.string(mandatory = True, values = ["chibi", "larceny"]),
        "_jdk": attr.label(default = "@bazel_tools//tools/jdk:current_java_runtime"),
        "_tool": attr.label(default = "//tests/harness:action", executable = True, cfg = "exec"),
    },
    outputs = {"block": "%{name}.md", "json": "%{name}.json"},
)

def conformance_tests(name, module, runtime, suites, plan):
    """`<name>_results_<suite>` (build actions), `<name>_torture_<suite>`, and `<name>_status`: the
    status block in docs/plan.md is what the suites give. Editing plan.md reruns only the latter."""
    for suite, files in suites.items():
        conformance_results(name = "%s_results_%s" % (name, suite), module = module, srcs = [files], suite = suite)
        harness_test(
            name = "%s_torture_%s" % (name, suite),
            args = ["torture", _runtime(runtime), "--suite=" + suite],
            data = [runtime, files],
            size = "large",
            tags = ["cpu:4"],  # two JVMs at a time normally and two under torture
        )
    blocks = [":%s_results_%s.md" % (name, suite) for suite in suites]
    harness_test(
        name = name + "_status",
        args = ["conformance-status", "$(rlocationpath %s)" % plan] + ["$(rlocationpath %s)" % b for b in blocks],
        data = [plan] + blocks,
        size = "small",
    )
    native.test_suite(name = name, tests = [name + "_status"] + ["%s_torture_%s" % (name, s) for s in suites])

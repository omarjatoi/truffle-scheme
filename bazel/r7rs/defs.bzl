"""Test targets for the drivers in this package. tests/BUILD.bazel uses these.

Every target is an ordinary Bazel test: it reruns only when its inputs change (the language
modules, the program, the expected output), and otherwise comes from the cache. A target per
test class, program or spec case, rather than shards of one big run, gives that granularity and
lets Bazel spread the JVMs over the machine's cores.
"""

load("@rules_java//java/common:java_common.bzl", "java_common")
load("@rules_python//python:defs.bzl", "py_test")
load("//bazel:java_module.bzl", "JavaModuleInfo")

_DRIVERS = "//bazel/r7rs"

def _driver(name, script, args, data, size = "medium", tags = [], **kwargs):
    py_test(
        name = name,
        size = size,
        srcs = [_DRIVERS + ":" + script],
        main = _DRIVERS + ":" + script,
        args = args,
        data = data,
        tags = tags,
        deps = [_DRIVERS + ":r7rs"],
        **kwargs
    )

# mode: (size, tags). Compile-immediately runs compile every call target synchronously, so they
# are the slowest; the Graal JIT's background compiler threads take a second core.
UNIT_MODES = {
    "jit": ("medium", ["cpu:2"]),
    "compile": ("large", []),
    "torture": ("medium", ["cpu:2"]),
}

def unit_tests(name, srcs, runtime):
    """One test per JUnit class and mode: `<name>_<mode>_<Class>`, and a test_suite per mode.

    Args:
      name: prefix of the targets, and the name of the test_suite of all of them.
      srcs: the test classes' sources (`*Test.java` under scheme/test/).
      runtime: a scheme_runtime whose module is the test module.
    """
    suites = []
    for mode, (size, tags) in UNIT_MODES.items():
        tests = []
        for src in srcs:
            cls = src.rsplit("/", 1)[-1].removesuffix(".java")
            test = "%s_%s_%s" % (name, mode, cls)
            _driver(
                name = test,
                script = "unit_test.py",
                args = [
                    "--runtime=$(rlocationpath %s)" % runtime,
                    "--mode=" + mode,
                    "scheme.test." + cls,
                ],
                data = [runtime],
                size = size,
                tags = tags,
            )
            tests.append(test)
        native.test_suite(name = "%s_%s" % (name, mode), tests = tests)
        suites.append("%s_%s" % (name, mode))
    native.test_suite(name = name, tests = suites)

def diff_tests(name, srcs, runtime, chibi):
    """tests/diff: each program against chibi-scheme, normally and under torture."""
    tests = []
    for src in srcs:
        base = src.rsplit("/", 1)[-1].removesuffix(".scm")
        for torture in (False, True):
            test = "%s_%s%s" % (name, "torture_" if torture else "", base)
            _driver(
                name = test,
                script = "program_test.py",
                args = [
                    "--runtime=$(rlocationpath %s)" % runtime,
                    "diff",
                    "$(rlocationpath %s)" % src,
                    "--chibi=$(rlocationpath %s)" % chibi,
                ] + (["--torture"] if torture else []),
                data = [runtime, src, chibi, "@chibi//:version"],
                # chibi-scheme itself takes about 90 s on escape-callcc.scm (ctak with real
                # continuations): leave room on a slow CI runner.
                size = "large",
            )
            tests.append(test)
    native.test_suite(name = name, tests = tests)

def spec_tests(name, srcs, runtime):
    """tests/spec: each case (<case>.scm with <case>.out), interpreted and with the JIT."""
    tests = []
    for src in srcs:
        case = src.removesuffix(".scm")
        test = "%s_%s" % (name, case.rsplit("/", 1)[-1])
        _driver(
            name = test,
            script = "program_test.py",
            args = [
                "--runtime=$(rlocationpath %s)" % runtime,
                "spec",
                "$(rlocationpath %s)" % src,
            ],
            data = [runtime, src, case + ".out"],
            size = "large",
            tags = ["cpu:2"],
        )
        tests.append(test)
    native.test_suite(name = name, tests = tests)

def space_test(name, src, expect, limits, runtime):
    """A program that must print `expect` under the JVM `limits`, interpreted and with the JIT."""
    _driver(
        name = name,
        script = "program_test.py",
        args = [
            "--runtime=$(rlocationpath %s)" % runtime,
            "space",
            "$(rlocationpath %s)" % src,
            "--expect=" + expect,
            # Quoted: Bazel splits `args` like a shell.
            "--limits='%s'" % limits,
        ],
        data = [runtime, src],
        size = "large",
        tags = ["cpu:2"],
    )

def _conformance_results_impl(ctx):
    jdk = ctx.attr._jdk[java_common.JavaRuntimeInfo]
    module_path = ctx.attr.module[JavaModuleInfo].module_path
    args = ctx.actions.args()
    args.add("--suite", ctx.attr.suite)
    args.add("--java", jdk.java_executable_exec_path)
    args.add_all("--module-path", module_path)
    args.add("--json", ctx.outputs.json)
    args.add("--block", ctx.outputs.block)
    ctx.actions.run(
        executable = ctx.executable._tool,
        arguments = [args],
        inputs = depset(ctx.files.srcs, transitive = [module_path, jdk.files]),
        outputs = [ctx.outputs.json, ctx.outputs.block],
        mnemonic = "R7rsConformance",
        progress_message = "Running the %s conformance suite (%%{label})" % ctx.attr.suite,
        # 6.14 checks that PATH is set: give the action --incompatible_strict_action_env's fixed one.
        use_default_shell_env = True,
        resource_set = _four_cpus,
    )
    return [DefaultInfo(files = depset([ctx.outputs.json, ctx.outputs.block]))]

def _four_cpus(_os, _inputs):
    # conformance_results.py runs a JVM per section, four at a time.
    return {"cpu": 4, "memory": 2048}

conformance_results = rule(
    implementation = _conformance_results_impl,
    doc = """One conformance suite, interpreted only, run as a cached build action.

    Outputs `<name>.md`, the suite's part of the docs/plan.md status block, and `<name>.json`,
    each section's passing count (bazel/r7rs/conformance_results.py).""",
    attrs = {
        "module": attr.label(mandatory = True, providers = [JavaModuleInfo], doc = "The launcher module."),
        "srcs": attr.label_list(allow_files = True, doc = "The suite's files and tests/lib."),
        "suite": attr.string(mandatory = True, values = ["chibi", "larceny"]),
        "_jdk": attr.label(default = "@bazel_tools//tools/jdk:current_java_runtime"),
        "_tool": attr.label(default = _DRIVERS + ":conformance_results", executable = True, cfg = "exec"),
    },
    outputs = {"block": "%{name}.md", "json": "%{name}.json"},
)

def conformance_tests(name, module, runtime, suites, plan):
    """Per suite, its results (`<name>_results_<suite>`, a build action) and its torture test
    (`<name>_torture_<suite>`); and the status block check (`<name>_status`).

    Args:
      name: prefix of the targets, and the name of the test_suite of the tests.
      module: the launcher module.
      runtime: a scheme_runtime of the launcher module.
      suites: {suite name: its files}, in the status block's order.
      plan: docs/plan.md.
    """
    for suite, files in suites.items():
        conformance_results(
            name = "%s_results_%s" % (name, suite),
            module = module,
            srcs = [files],
            suite = suite,
            # Built when a test needs it, not by `bazel build //...`.
            tags = ["manual"],
        )
        _driver(
            name = "%s_torture_%s" % (name, suite),
            script = "conformance_test.py",
            args = [
                "torture",
                "--runtime=$(rlocationpath %s)" % runtime,
                "--suite=" + suite,
            ],
            data = [runtime, files],
            size = "large",
            # Up to four JVMs: a section each, two at a time normally and two under torture.
            tags = ["cpu:4"],
        )
    blocks = [":%s_results_%s.md" % (name, suite) for suite in suites]
    _driver(
        name = name + "_status",
        script = "conformance_test.py",
        args = ["status", "$(rlocationpath %s)" % plan] + ["$(rlocationpath %s)" % b for b in blocks],
        data = [plan] + blocks,
        size = "small",
    )
    native.test_suite(name = name, tests = [name + "_status"] + ["%s_torture_%s" % (name, s) for s in suites])

# mx_r7rs_tools.SMOKE_TESTS, in its order.
TOOLS_SMOKE_TESTS = ["help", "dap", "inspect", "cpusampler", "memtracer"]

def tools_tests(name, runtime, tools_runtime, programs):
    """GraalVM's tools against the launcher (mx r7rs-tools): `<name>_<test>` per smoke test.

    Args:
      name: prefix of the tests, and the name of their test_suite.
      runtime: a scheme_runtime of the launcher module alone.
      tools_runtime: the same with the tools (mx: SCHEME_TOOLS) on the module path.
      programs: tests/tools/*.scm; the first one locates the directory.
    """
    tests = []
    for test in TOOLS_SMOKE_TESTS:
        _driver(
            name = "%s_%s" % (name, test),
            script = "tools_test.py",
            args = [
                "--runtime=$(rlocationpath %s)" % runtime,
                "--tools-runtime=$(rlocationpath %s)" % tools_runtime,
                "--driver=$(rlocationpath //:mx.r7rs/mx_r7rs_tools.py)",
                "--programs=$(rlocationpath %s)" % programs[0],
                test,
            ],
            data = [runtime, tools_runtime, "//:mx.r7rs/mx_r7rs_tools.py"] + programs,
            # The DAP and Chrome DevTools clients talk to the launcher over 127.0.0.1.
            tags = ["cpu:2", "requires-network"],
        )
        tests.append("%s_%s" % (name, test))
    native.test_suite(name = name, tests = tests)

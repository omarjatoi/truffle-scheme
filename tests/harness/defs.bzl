"""Test targets driven by tests/harness/main.py. tests/BUILD.bazel and bazel/lint use these.

A target per JUnit class, program and spec case: each reruns only when its inputs change, and Bazel
spreads the JVMs over the machine's cores.
"""

load("@rules_python//python:defs.bzl", "py_test")

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

# mode: (prefix of the test's name, size, tags).
_CONFORMANCE_MODES = {
    "interpreter": ("", "medium", []),
    "compile": ("compile_", "large", ["cpu:2"]),
    "torture": ("torture_", "large", ["cpu:2"]),
}

def conformance_tests(name, runtime, suites, plan):
    """For each suite (`{suite: (files, [section id, ...])}`), each section and each mode, a test:
    `<name>_<suite>_<section>` (interpreted), `<name>_compile_<suite>_<section>` and
    `<name>_torture_<suite>_<section>`. Each passes when every test of the section passes but the
    suite's listed exceptions, which must fail. `<name>_status`: docs/plan.md's status block is what
    they check (editing plan.md reruns only it)."""
    tests = []
    for suite, (files, sections) in suites.items():
        for section in sections:
            for mode, (prefix, size, tags) in _CONFORMANCE_MODES.items():
                test = "%s_%s%s_%s" % (name, prefix, suite, section)
                harness_test(
                    name = test,
                    args = ["conformance", _runtime(runtime), "--suite=" + suite, "--section=" + section, "--mode=" + mode],
                    data = [runtime, files],
                    size = size,
                    tags = tags,
                )
                tests.append(test)
    harness_test(
        name = name + "_status",
        args = ["conformance-status", "$(rlocationpath %s)" % plan],
        data = [plan] + [files for files, _ in suites.values()],
        size = "small",
    )
    native.test_suite(name = name, tests = [name + "_status"] + tests)

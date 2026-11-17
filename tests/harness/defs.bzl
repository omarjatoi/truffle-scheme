"""Test targets: Java drivers (tests/driver) for programs and conformance sections, and the few
checks still in Python (tests/harness/check_*.py). tests/BUILD.bazel uses these.

A target per JUnit class, program and spec case: each reruns only when its inputs change, and Bazel
spreads the JVMs over the machine's cores.
"""

load("@rules_python//python:defs.bzl", "py_test")
load("//bazel:jvm_test.bzl", "java_driver_test")

def harness_test(name, check, args, data, deps = [], size = "medium", tags = []):
    """A py_test running tests/harness/check_<check>.py with these arguments; `deps`: the harness
    libraries it imports besides harness.py."""
    script = "//tests/harness:check_%s.py" % check
    py_test(
        name = name,
        size = size,
        srcs = [script],
        main = script,
        args = args,
        data = data,
        tags = tags,
        deps = ["//tests/harness"] + ["//tests/harness:" + d for d in deps],
    )

def _runtime(runtime):
    return "--runtime=$(rlocationpath %s)" % runtime

def _chibi_output_impl(ctx):
    chibi = ctx.attr.chibi[DefaultInfo]
    ctx.actions.run_shell(
        inputs = depset([ctx.file.src], transitive = [chibi.default_runfiles.files]),
        tools = [chibi.files_to_run],
        outputs = [ctx.outputs.out, ctx.outputs.err, ctx.outputs.status],
        command = '"$1" "$2" < /dev/null > "$3" 2> "$4"; echo $? > "$5"',
        arguments = [chibi.files_to_run.executable.path, ctx.file.src.path, ctx.outputs.out.path, ctx.outputs.err.path,
                     ctx.outputs.status.path],
        mnemonic = "ChibiOutput",
        progress_message = "Running %{input} under chibi-scheme",
    )

chibi_output = rule(
    implementation = _chibi_output_impl,
    doc = "A program under chibi-scheme, as a cached build action: `<name>.out`, `.err` and `.status`.",
    attrs = {
        "chibi": attr.label(mandatory = True, executable = True, cfg = "exec"),
        "src": attr.label(mandatory = True, allow_single_file = [".scm"]),
    },
    outputs = {"err": "%{name}.err", "out": "%{name}.out", "status": "%{name}.status"},
)

def diff_tests(name, srcs, module, chibi):
    """`<name>_<program>`, `<name>_torture_<program>` and `<name>_torture_owned_<program>`: the same
    stdout and exit status as chibi-scheme, whose output for each program is a build action
    (`<name>_chibi_<program>`), so it reruns only when the program or chibi-scheme changes. The tests
    run tests/driver's Diff, in the pinned JDK."""
    tests = []
    for src in srcs:
        base = src.rsplit("/", 1)[-1].removesuffix(".scm")
        expected = "%s_chibi_%s" % (name, base)
        chibi_output(name = expected, src = src, chibi = chibi)
        for torture in ("", "torture_", "torture_owned_"):
            java_driver_test(
                name = name + "_" + torture + base,
                args = ["--file=$(rlocationpath %s)" % src] +
                       ["--chibi-%s=$(rlocationpath :%s.%s)" % (part, expected, part) for part in ("out", "err", "status")] +
                       (["--torture=" + torture.removesuffix("_")] if torture else []),
                data = [src] + [":%s.%s" % (expected, part) for part in ("out", "err", "status")],
                main = "Diff",
                module = module,
                size = "large",
            )
            tests.append(name + "_" + torture + base)
    native.test_suite(name = name, tests = tests)

def spec_tests(name, srcs, module):
    """`<name>_<case>`: tests/spec cases, interpreted and with the JIT, under their JVM limits."""
    tests = []
    for src in srcs:
        test = "%s_%s" % (name, src.rsplit("/", 1)[-1].removesuffix(".scm"))
        java_driver_test(
            name = test,
            args = ["--kind=spec", "--file=$(rlocationpath %s)" % src],
            data = [src, src.removesuffix(".scm") + ".out"],
            main = "Program",
            module = module,
            size = "large",
            tags = ["cpu:2"],
        )
        tests.append(test)
    native.test_suite(name = name, tests = tests)

def space_test(name, src, expect, limits, module, all_modes = False):
    """A program that must print `expect` under the JVM `limits`, interpreted and with the JIT, and
    with `all_modes`, compiled at once and under both kinds of torture too."""
    java_driver_test(
        name = name,
        # Quoted: Bazel splits `args` like a shell.
        args = ["--kind=space", "--file=$(rlocationpath %s)" % src, "--expect=" + expect, "'--limits=%s'" % limits] +
               (["--all-modes=true"] if all_modes else []),
        data = [src],
        main = "Program",
        module = module,
        size = "large",
        tags = ["cpu:2"],
    )

def tools_tests(name, runtime, tools_runtime, programs):
    """`<name>_<test>` per smoke test of tools.py, with and without the tools on the module path."""
    tests = ["help", "dap", "inspect", "cpusampler", "memtracer", "terminal"]
    for test in tests:
        harness_test(
            name = "%s_%s" % (name, test),
            check = "tools",
            deps = ["tools"],
            args = [_runtime(runtime), "--tools-runtime=$(rlocationpath %s)" % tools_runtime,
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
    "torture_owned": ("torture_owned_", "large", ["cpu:2"]),
}

def conformance_tests(name, module, suites, plan):
    """For each suite (`{suite: (files, directory, [section id, ...])}`), each section and each mode,
    a test: `<name>_<suite>_<section>` (interpreted), `<name>_compile_<suite>_<section>`,
    `<name>_torture_<suite>_<section>` and `<name>_torture_owned_<suite>_<section>` (torture whose
    captures stay owned), tests/driver's Conformance in the pinned JDK. Each passes when
    every test of the section passes but the suite's listed exceptions, which must fail.
    `<name>_status`: docs/plan.md's status block is what they check (editing plan.md reruns only it)."""
    tests = []
    package = native.package_name()
    for suite, (files, directory, sections) in suites.items():
        for section in sections:
            for mode, (prefix, size, tags) in _CONFORMANCE_MODES.items():
                test = "%s_%s%s_%s" % (name, prefix, suite, section)
                java_driver_test(
                    name = test,
                    args = ["--suite=" + suite, "--suite-dir=_main/%s/%s" % (package, directory), "--lib=_main/%s/lib" % package,
                            "--section=" + section, "--mode=" + mode],
                    data = [files],
                    main = "Conformance",
                    module = module,
                    size = size,
                    tags = tags,
                )
                tests.append(test)
    harness_test(
        name = name + "_status",
        check = "status",
        args = ["plan", "$(rlocationpath %s)" % plan],
        data = [plan] + [files for files, _, _ in suites.values()],
        size = "small",
    )
    native.test_suite(name = name, tests = [name + "_status"] + tests)

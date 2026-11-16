"""JUnit tests in the pinned JDK, without Python: `unit_tests` makes a library, a module and a test
per mode for each class (bazel/jvm_test.bzl junit_test)."""

load("@rules_java//java:defs.bzl", "java_library")
load("//bazel:java.bzl", "SCHEME_JAVACOPTS")
load("//bazel:java_module.bzl", "java_module_jar")
load("//bazel:jvm_test.bzl", "junit_test")

# mode: (JVM options, size, tags). The JIT's background compiler threads take a second core.
_UNIT_MODES = {
    "jit": ([], "medium", ["cpu:2"]),
    "compile": ([
        "-Dpolyglot.engine.AllowExperimentalOptions=true",
        "-Dpolyglot.engine.CompileImmediately=true",
        "-Dpolyglot.engine.BackgroundCompilation=false",
        "-Dpolyglot.engine.CompilationFailureAction=Throw",
    ], "large", []),
    "torture": (["-Dscheme.torture=true"], "medium", ["cpu:2"]),
}

def unit_tests(name, tests, support, kinds, resources = {}, extra_srcs = {}):
    """For each test class (a file of `tests`), its library, its module (scheme.test, or
    scheme.test.upstream) and a test per mode, `<name>_<mode>_<Class>`, so editing one test class
    reruns only its tests. `kinds[Class]` is what it needs, "full" by default:

      full      the language and the launcher (//:launcher_module), and `support` (in its module)
      core      only the runtime and the reader (:core_module)
      upstream  only Truffle (tests/scheme/test/upstream)

    `resources[Class]`: files the class reads as resources (from the `tests/` root);
    `extra_srcs[Class]`: other test classes it uses."""
    kinds_deps = {
        "core": (["//src/reader", "//src/runtime"], [":core_module"], ["org.graalvm.polyglot", "org.graalvm.truffle", "scheme"]),
        "full": ([":" + support, "//src/compiler", "//src/launcher", "//src/reader", "//src/runtime"], ["//:launcher_module"],
                 ["org.graalvm.polyglot", "org.graalvm.truffle", "scheme", "scheme.launcher"]),
        "upstream": ([], ["//bazel:truffle_modules"], ["org.graalvm.polyglot", "org.graalvm.truffle"]),
    }
    by_mode = {mode: [] for mode in _UNIT_MODES}
    for src in tests:
        cls = src.removesuffix(".java").rsplit("/", 1)[-1]
        kind = kinds.get(cls, "full")
        package = src.removesuffix(".java").rsplit("/", 1)[0].replace("/", ".")
        deps, module_deps, requires = kinds_deps[kind]
        srcs = [src] + extra_srcs.get(cls, []) + ([f for f in native.glob(["scheme/test/upstream/*.java"]) if not f.endswith("Test.java")] if kind == "upstream" else [])
        java_library(
            name = "%s_lib_%s" % (name, cls),
            srcs = srcs,
            javacopts = SCHEME_JAVACOPTS,
            resource_strip_prefix = native.package_name() if cls in resources else None,
            resources = resources.get(cls, []),
            deps = deps + [
                "//bazel:truffle_dsl_processor",
                "@jvm//:junit_junit",
                "@jvm//:org_graalvm_polyglot_polyglot",
                "@jvm//:org_graalvm_truffle_truffle_api",
            ],
        )
        java_module_jar(
            name = "%s_module_%s" % (name, cls),
            exports = [package + " to junit"],
            jar_name = "%s_module_%s" % (name, cls),
            libs = [":%s_lib_%s" % (name, cls)] + ([":" + support] if kind == "full" else []),
            module_name = "scheme.test",
            opens = [package + " to junit"],
            requires = ["junit"] + requires,
            deps = module_deps + ["@jvm//:junit_junit"],
        )
        for mode, (flags, size, tags) in _UNIT_MODES.items():
            test = "%s_%s_%s" % (name, mode, cls)
            junit_test(
                name = test,
                jvm_flags = flags,
                module = ":%s_module_%s" % (name, cls),
                size = size,
                tags = tags,
                test_class = "%s.%s" % (package, cls),
            )
            by_mode[mode].append(test)
    for mode, tests_of_mode in by_mode.items():
        native.test_suite(name = "%s_%s" % (name, mode), tests = tests_of_mode)
    native.test_suite(name = name, tests = ["%s_%s" % (name, mode) for mode in _UNIT_MODES])

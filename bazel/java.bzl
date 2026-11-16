"""java_library with this repo's compiler settings (mx: `mx build --warning-as-error`) and checks."""

load("@rules_java//java:defs.bzl", "java_library")
load("//bazel/lint:defs.bzl", "java_lint")

# mx's javac flags with --warning-as-error (mx.py JavacCompiler.prepareJavacLike), so the build
# fails on the same warnings `mx checks build` does. Error Prone (which Bazel's JavaBuilder runs)
# keeps its ERROR-level checks; its WARNING-level ones are off, since -Werror would make every
# one of them fatal and mx never ran them.
SCHEME_JAVACOPTS = [
    "-Xlint:all,-auxiliaryclass,-processing,-removal,-options",
    "-Werror",
    "-encoding",
    "UTF-8",
    "-XepDisableAllWarnings",
]

def scheme_java_library(name, srcs, truffle_dsl = False, spotbugs = True, deps = [], javacopts = [], **kwargs):
    """A java_library compiled like an mx project of this suite, and its static checks.

    Besides the library `name`, makes the lint tests `name_format`, `name_checkstyle`,
    `name_ecj` and (unless spotbugs is False) `name_spotbugs` (bazel/lint/defs.bzl).

    Args:
      name: the target name.
      srcs: its Java sources.
      truffle_dsl: run the Truffle DSL annotation processor (mx: "annotationProcessors").
      spotbugs: run SpotBugs on it (mx: on every non-test project).
      deps: as java_library.
      javacopts: added to SCHEME_JAVACOPTS.
      **kwargs: as java_library.
    """
    java_library(
        name = name,
        srcs = srcs,
        deps = deps + (["//bazel:truffle_dsl_processor"] if truffle_dsl else []),
        javacopts = SCHEME_JAVACOPTS + javacopts,
        **kwargs
    )
    java_lint(
        name = name,
        lib = ":" + name,
        srcs = srcs,
        spotbugs = spotbugs,
        truffle_dsl = truffle_dsl,
    )

"""java_library with this repo's compiler settings (mx: `mx build --warning-as-error`)."""

load("@rules_java//java:defs.bzl", "java_library")

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

def scheme_java_library(name, truffle_dsl = False, deps = [], javacopts = [], **kwargs):
    """A java_library compiled like an mx project of this suite.

    Args:
      name: the target name.
      truffle_dsl: run the Truffle DSL annotation processor (mx: "annotationProcessors").
      deps: as java_library.
      javacopts: added to SCHEME_JAVACOPTS.
      **kwargs: as java_library.
    """
    java_library(
        name = name,
        deps = deps + (["//bazel:truffle_dsl_processor"] if truffle_dsl else []),
        javacopts = SCHEME_JAVACOPTS + javacopts,
        **kwargs
    )

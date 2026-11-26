"""java_library with this repo's compiler settings, and its static checks (bazel/lint)."""

load("@rules_java//java:defs.bzl", "java_library")
load("//bazel/lint:defs.bzl", "java_lint")

# javac's lint warnings are errors, as in GraalVM's projects. Error Prone (which Bazel's JavaBuilder
# runs) keeps its ERROR-level checks; its warnings are off, since -Werror would make each one fatal.
SCHEME_JAVACOPTS = [
    "-Xlint:all,-auxiliaryclass,-processing,-options",
    "-Werror",
    "-encoding",
    "UTF-8",
    "-XepDisableAllWarnings",
]

# For a library with a Bytecode DSL root (@GenerateBytecode): no removal warnings, and no note that
# they're off. Truffle's processor (25.5.5.0.3, 25.6.5.1) writes `throwable instanceof java.lang.ThreadDeath`
# (deprecated for removal) into every generated root's exception handling, and suppresses only
# "deprecation" on the class (docs/upstream.md, "ThreadDeath in generated roots").
BYTECODE_ROOT_JAVACOPTS = SCHEME_JAVACOPTS + ["-Xlint:-removal", "-XDsuppressNotes"]

def scheme_java_library(name, srcs, truffle_dsl = False, bytecode_root = False, spotbugs = True, deps = [], **kwargs):
    """A java_library, and its lint tests `<name>_format`, `_checkstyle`, `_ecj` and `_spotbugs`.

    Args:
      name: the target name.
      srcs: its Java sources.
      truffle_dsl: run the Truffle DSL annotation processor.
      bytecode_root: it has a Bytecode DSL root, so it compiles with BYTECODE_ROOT_JAVACOPTS.
      spotbugs: run SpotBugs on it (not on test code).
      deps: as java_library.
      **kwargs: as java_library.
    """
    java_library(
        name = name,
        srcs = srcs,
        deps = deps + (["//bazel:truffle_dsl_processor"] if truffle_dsl else []),
        javacopts = BYTECODE_ROOT_JAVACOPTS if bytecode_root else SCHEME_JAVACOPTS,
        **kwargs
    )
    java_lint(name = name, lib = ":" + name, srcs = srcs, spotbugs = spotbugs, truffle_dsl = truffle_dsl)

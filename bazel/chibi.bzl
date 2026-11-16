"""chibi-scheme, built from its pinned source (MODULE.bazel `@chibi_src`) for the differential tests.

The action runs chibi's own Makefile with the host's `make` and C compiler into a tree artifact,
and `chibi-scheme` is a script that runs the build from there, uninstalled, as chibi's `make test`
does: its libraries from the tree, none from the system.
"""

_WRAPPER = """#!/bin/sh
d="$(cd "$(dirname "$0")" && pwd)/{tree}"
CHIBI_IGNORE_SYSTEM_PATH=1 CHIBI_MODULE_PATH="$d/lib" LD_LIBRARY_PATH="$d" DYLD_LIBRARY_PATH="$d" \\
  exec "$d/chibi-scheme" "$@"
"""

def _chibi_scheme_impl(ctx):
    tree = ctx.actions.declare_directory(ctx.label.name + ".build")
    makefile = ctx.file.makefile
    ctx.actions.run_shell(
        inputs = ctx.files.srcs,
        outputs = [tree],
        command = """
set -e
cp -RL "{src}/." "{out}/"
cd "{out}"
# Without -g (chibi's default flags have it), so the build's absolute paths stay out of the
# binaries and the tree is the same on every machine of a platform (and caches across them).
log="$(mktemp)"
make -j4 XCFLAGS="-Wall -O2" > "$log" 2>&1 || {{ tail -50 "$log" >&2; exit 1; }}
rm -f "$log"
find . -name '*.o' -delete
# A tree artifact holds files: replace the library's version symlinks by copies.
find . -type l | while read -r link; do cp -L "$link" "$link.tmp" && mv "$link.tmp" "$link"; done
""".format(src = makefile.dirname, out = tree.path),
        mnemonic = "ChibiMake",
        progress_message = "Building chibi-scheme",
        use_default_shell_env = True,
        # A chibi-scheme for this machine (`bazel run`) builds here; one for the tests, which target
        # the remote executors' platform, builds there (.bazelrc --config=remote).
        execution_requirements = {} if ctx.target_platform_has_constraint(ctx.attr._linux[platform_common.ConstraintValueInfo]) else {"no-remote-exec": "1"},
    )
    wrapper = ctx.actions.declare_file(ctx.label.name)
    ctx.actions.write(wrapper, _WRAPPER.format(tree = tree.basename), is_executable = True)
    return [DefaultInfo(
        files = depset([wrapper]),
        executable = wrapper,
        runfiles = ctx.runfiles([wrapper, tree]),
    )]

chibi_scheme = rule(
    implementation = _chibi_scheme_impl,
    attrs = {
        "makefile": attr.label(allow_single_file = True, mandatory = True),
        "srcs": attr.label_list(allow_files = True, mandatory = True),
        "_linux": attr.label(default = "@platforms//os:linux"),
    },
    executable = True,
)

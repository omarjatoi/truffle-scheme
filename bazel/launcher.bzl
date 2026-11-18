"""launcher: `bazel-bin/<name>`, a shell script in front of a launcher (a py_binary) that first checks
that it runs on the platform it was built for and that its JDK is there.

`bazel-bin` follows the last bazel command's platform, so on a Mac, after `bazel test`, it is the
executors' Linux directory, and its launcher the Linux one (design.md D11, "An output directory per
platform"). There, or with its JDK gone (the output base pruned, say), the launcher would end in a
Python traceback; this script says instead, in one line, what to run (//tests:launcher).
"""

_SCRIPT = """#!/bin/sh
# //{package}:{name} for {platform} (bazel/launcher.bzl): checks that it runs on {platform} and that
# its JDK is there, or says in one line what to run; then runs the launcher.
fail() {{
    echo "$0 $1; run \\`bazel build //{package}:{name}\\`" >&2
    exit 1
}}
case "$(uname -s) $(uname -m)" in
"{uname}") ;;
*) fail "is for {platform}, not this machine (bazel-bin follows the last bazel command's platform)" ;;
esac
# Its runfiles, where the launcher looks for them: RUNFILES_DIR if it has the launcher (when another
# binary has this one among its data), else <name>.runfiles beside it, through symlinks to it.
if [ -z "$RUNFILES_DIR" ] || [ ! -e "$RUNFILES_DIR/{launcher}" ]; then
    self="$0"
    while [ ! -d "$self.runfiles" ] && [ -L "$self" ]; do
        link="$(readlink "$self")"
        case "$link" in
        /*) self="$link" ;;
        *) self="$(dirname "$self")/$link" ;;
        esac
    done
    [ -d "$self.runfiles" ] || fail "has no runfiles ($self.runfiles)"
    RUNFILES_DIR="$self.runfiles"
fi
[ -x "$RUNFILES_DIR/{java}" ] || fail "has no JDK ($RUNFILES_DIR/{jdk})"
export RUNFILES_DIR
exec "$RUNFILES_DIR/{launcher}" "$@"
"""

def _runfiles_path(ctx, f):
    return ctx.workspace_name + "/" + f.short_path

def _has(ctx, constraint):
    return ctx.target_platform_has_constraint(constraint[platform_common.ConstraintValueInfo])

def _launcher_impl(ctx):
    linux, macos = _has(ctx, ctx.attr._linux), _has(ctx, ctx.attr._macos)
    x86_64, aarch64 = _has(ctx, ctx.attr._x86_64), _has(ctx, ctx.attr._aarch64)
    if not (linux or macos) or not (x86_64 or aarch64):
        fail("%s: only for Linux and macOS, on x86-64 or arm64" % ctx.label)
    launcher = ctx.attr.launcher[DefaultInfo]
    script = ctx.actions.declare_file(ctx.label.name)
    ctx.actions.write(
        script,
        _SCRIPT.format(
            package = ctx.label.package,
            name = ctx.label.name,
            platform = ("Linux" if linux else "macOS") + (" x86-64" if x86_64 else " arm64"),
            # What `uname -s` and `uname -m` print there.
            uname = ("Linux" if linux else "Darwin") + (" x86_64" if x86_64 else " aarch64" if linux else " arm64"),
            launcher = _runfiles_path(ctx, launcher.files_to_run.executable),
            jdk = _runfiles_path(ctx, ctx.file.jdk),
            java = _runfiles_path(ctx, ctx.file.jdk) + "/bin/java",
        ),
        is_executable = True,
    )
    runfiles = ctx.runfiles(files = [launcher.files_to_run.executable]).merge(launcher.default_runfiles)
    return [DefaultInfo(executable = script, runfiles = runfiles)]

launcher = rule(
    implementation = _launcher_impl,
    doc = "`<name>`: runs `launcher`, once it has checked the platform and that `jdk` is there.",
    executable = True,
    attrs = {
        "jdk": attr.label(mandatory = True, allow_single_file = True, doc = "The JDK the launcher runs (a jdk_tree)."),
        "launcher": attr.label(mandatory = True, executable = True, cfg = "target"),
        "_aarch64": attr.label(default = "@platforms//cpu:aarch64"),
        "_linux": attr.label(default = "@platforms//os:linux"),
        "_macos": attr.label(default = "@platforms//os:macos"),
        "_x86_64": attr.label(default = "@platforms//cpu:x86_64"),
    },
)

"""Tools that come from the Nix dev shell rather than from Bazel: chibi-scheme.

`@chibi//:chibi-scheme` is the chibi-scheme on PATH (inside `nix develop`, a /nix/store path).
`@chibi//:version` holds that binary's resolved path, which names its Nix store hash: tests
that run chibi depend on it, so their cache keys change when flake.lock moves chibi, even if
the binary's own bytes don't (its libraries live next to it in the store).

Without chibi-scheme on PATH the repository still exists, and `:chibi-scheme` is a script that
fails with instructions, so only the differential tests fail.
"""

def _chibi_impl(rctx):
    found = rctx.which("chibi-scheme")
    if found:
        # Resolve the PATH symlinks (Nix profile, dev shell) to the store path itself.
        real = rctx.execute(["realpath", str(found)]).stdout.strip() or str(found)
        rctx.symlink(real, "chibi-scheme")
        rctx.file("version", real + "\n")
    else:
        rctx.file(
            "chibi-scheme",
            "#!/bin/sh\necho 'chibi-scheme is not on PATH: run bazel inside `nix develop`' >&2\nexit 127\n",
            executable = True,
        )
        rctx.file("version", "missing\n")
    rctx.file("BUILD.bazel", 'exports_files(["chibi-scheme", "version"], visibility = ["//visibility:public"])\n')

_chibi = repository_rule(
    implementation = _chibi_impl,
    # Re-resolved when PATH changes, e.g. after a flake.lock update or outside `nix develop`.
    environ = ["PATH"],
    local = True,
)

def _host_tools_impl(mctx):
    _chibi(name = "chibi")
    return mctx.extension_metadata(reproducible = False)

host_tools = module_extension(implementation = _host_tools_impl)

"""GraalVM CE, downloaded and pinned, as the Java runtime and compiler JDK (design.md D11).

The release is chosen by the Truffle version in MODULE.bazel (`graalvm.jdk(version = ...)`), so
the JDK and the Truffle libraries can't drift apart: a Truffle version with no pinned release
here fails the build. The archives are the same files nixpkgs fetches for
`graalvmPackages.graalvm-ce` (the sha256s match flake.lock's nixpkgs), so Bazel and the Nix dev
shell run byte-identical JDKs.

Upgrading: add the new release's URLs and sha256s (published next to each archive as
`<archive>.sha256`) below, then bump TRUFFLE_VERSION in MODULE.bazel and run
`bazel run @jvm//:pin` (see AGENTS.md).

Repositories:
  @graalvm_<platform>   one per platform: the unpacked JDK and its `java_runtime` (`:jdk`)
  @graalvm              the runtime toolchains (`--java_runtime_version=graalvm_25`), an alias
                        `:jdk` for the host platform's JDK, and `:version` (the GraalVM version)
"""

load("@bazel_tools//tools/build_defs/repo:http.bzl", "http_archive")
load("@rules_java//toolchains:jdk_build_file.bzl", "JDK_BUILD_TEMPLATE")

# Java feature version of the pinned releases: `--java_runtime_version=graalvm_25`.
JAVA_VERSION = "25"

_PLATFORMS = {
    # name: (os, cpu, release archive suffix, path of the JDK inside the archive)
    "linux_x64": ("linux", "x86_64", "linux-x64", ""),
    "linux_aarch64": ("linux", "aarch64", "linux-aarch64", ""),
    "macos_aarch64": ("macos", "aarch64", "macos-aarch64", "/Contents/Home"),
}

# GraalVM version -> (release tag's archive prefix, top directory, {platform: sha256}).
_RELEASES = {
    "25.5.5.0.3": (
        "https://github.com/graalvm/graalvm-ce-builds/releases/download/graal-25.5.5.0.3/graalvm-community-jdk-25i5-25.0.5.0.3_",
        "graalvm-community-25.5.5.0.3+1.1",
        {
            "linux_x64": "c6438dee6c3ade2220d40b7ccdcad24b512d30e233e61bb4fc70519522de34ef",
            "linux_aarch64": "41741c0650ae7564a78335f9e4fc353a902e6ff1d4140f2e4fabd1c55d5cce66",
            "macos_aarch64": "54369d4a5931a5a2101c7c52444a4e7e6647a3a4e041335053cda6fd22295e0c",
        },
    ),
}

def _hub_impl(rctx):
    lines = [
        "package(default_visibility = [\"//visibility:public\"])",
        "",
        "config_setting(",
        "    name = \"runtime_version\",",
        "    values = {\"java_runtime_version\": \"graalvm_%s\"}," % JAVA_VERSION,
        ")",
        "",
    ]
    for name, (os, cpu, _, _) in _PLATFORMS.items():
        constraints = "[\"@platforms//os:%s\", \"@platforms//cpu:%s\"]" % (os, cpu)
        lines += [
            "config_setting(name = \"is_%s\", constraint_values = %s)" % (name, constraints),
            "",
            "toolchain(",
            "    name = \"runtime_%s\"," % name,
            "    target_compatible_with = %s," % constraints,
            "    target_settings = [\":runtime_version\"],",
            "    toolchain = \"@graalvm_%s//:jdk\"," % name,
            "    toolchain_type = \"@bazel_tools//tools/jdk:runtime_toolchain_type\",",
            ")",
            "",
            "toolchain(",
            "    name = \"bootstrap_runtime_%s\"," % name,
            "    exec_compatible_with = %s," % constraints,
            "    target_settings = [\":runtime_version\"],",
            "    toolchain = \"@graalvm_%s//:jdk\"," % name,
            "    toolchain_type = \"@bazel_tools//tools/jdk:bootstrap_runtime_toolchain_type\",",
            ")",
            "",
        ]
    lines += [
        "# The JDK of the platform Bazel runs on: the runtime of the Java compilation toolchain.",
        "alias(",
        "    name = \"jdk\",",
        "    actual = select({",
    ] + [
        "        \":is_%s\": \"@graalvm_%s//:jdk\"," % (name, name)
        for name in _PLATFORMS
    ] + [
        "    }),",
        ")",
        "",
        "exports_files([\"version\"])",
    ]
    rctx.file("BUILD.bazel", "\n".join(lines) + "\n")
    rctx.file("version", rctx.attr.version + "\n")

_hub = repository_rule(
    implementation = _hub_impl,
    attrs = {"version": attr.string(mandatory = True)},
)

def _graalvm_impl(mctx):
    versions = {tag.version: True for mod in mctx.modules for tag in mod.tags.jdk}
    if len(versions) != 1:
        fail("graalvm.jdk: expected exactly one version, got %s" % sorted(versions))
    version = versions.keys()[0]
    if version not in _RELEASES:
        fail(("MODULE.bazel pins Truffle %s, but bazel/graalvm.bzl has no GraalVM CE %s release to " +
              "match it (design.md D11). Add the release's URLs and sha256s there.") % (version, version))
    url_prefix, top, sha256s = _RELEASES[version]
    for name, (_, _, suffix, home) in _PLATFORMS.items():
        http_archive(
            name = "graalvm_" + name,
            urls = [url_prefix + suffix + "_bin.tar.gz"],
            sha256 = sha256s[name],
            strip_prefix = top + home,
            build_file_content = JDK_BUILD_TEMPLATE.format(RUNTIME_VERSION = JAVA_VERSION),
        )
    _hub(name = "graalvm", version = version)
    return mctx.extension_metadata(reproducible = True)

graalvm = module_extension(
    implementation = _graalvm_impl,
    tag_classes = {"jdk": tag_class(attrs = {"version": attr.string(mandatory = True)})},
)

"""GraalVM CE, downloaded and pinned, as the Java runtime and compiler JDK (design.md D11).

The release is chosen by the Truffle version in MODULE.bazel (`graalvm.jdk(version = ...)`), so
the JDK and the Truffle libraries can't drift apart: a Truffle version with no pinned release
here fails the build. The archives are the same files nixpkgs fetches for
`graalvmPackages.graalvm-ce` (the sha256s match flake.lock's nixpkgs), so Bazel and the Nix dev
shell run byte-identical JDKs.

Upgrading: add the new release's URLs and sha256s (published next to each archive as
`<archive>.sha256`) below, and its source tag and launcher.cc's sha256 (_LAUNCHER_SOURCES), then bump TRUFFLE_VERSION in MODULE.bazel and run
`bazel run @jvm//:pin` (see AGENTS.md).

Oracle GraalVM at the same version is pinned too, for `//:scheme-native` alone, and only when
`--//:native_graalvm=oracle` asks for it (design.md D17, "The native image's collector"): its
native-image also has the G1 collector. Bazel downloads it only then. It is released under the
GraalVM Free Terms and Conditions (its LICENSE.txt), which allow using it, and redistributing it
unmodified at no charge; its download asks for no license acceptance. Oracle publishes no checksum
next to these archives: the sha256s are of the archives as downloaded from these URLs on
2026-11-25 (25.6.5.1). An upgrade adds the new version's Oracle release too.

Repositories:
  @graalvm_<platform>   one per platform: the unpacked JDK, its `java_runtime` (`:jdk`) and its
                        JNI headers (`:jni`, a cc_library)
  @oracle_graalvm_<platform>   the same for Oracle GraalVM, fetched only when used
  @graalvm_launcher_cc  the release's thin launcher source (`//file`: launcher.cc), at its tag,
                        which //:scheme-native compiles (design.md D17, "VM options")
  @graalvm              the runtime toolchains (`--java_runtime_version=graalvm_25`), an alias
                        `:jdk` for the host platform's JDK, `:oracle_jdk` for the target
                        platform's Oracle GraalVM, `:jni` for the target platform's JNI headers,
                        and the GraalVM version as `:version` and in `:version.bzl` (`VERSION`)
"""

load("@bazel_tools//tools/build_defs/repo:http.bzl", "http_archive", "http_file")
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
    "25.6.5.1": (
        "https://github.com/graalvm/graalvm-ce-builds/releases/download/graal-25.6.5.1/graalvm-community-jdk-25i6-25.0.5.1_",
        "graalvm-community-25.6.5.1+2.1",
        {
            "linux_x64": "d792848db26f1602f0a5cf2cd9279d9a652d32ab450d891ce616c9ca67e359d0",
            "linux_aarch64": "d8e6edf0009bb5d70c6a670eb030d36aa804795427306434c0f7f63c01435307",
            "macos_aarch64": "19ef5175f18fe2a957a5ec4cc3dd263a164e88ae621cf80ee1c715ede72469f0",
        },
    ),
}

# The same for Oracle GraalVM (https://www.graalvm.org/downloads/, its "script-friendly URLs").
_ORACLE_RELEASES = {
    "25.6.5.1": (
        "https://gds.oracle.com/download/graal/25i6/archive/graalvm-jdk-25i6-25.0.5.1_",
        "graalvm-25.6.5.1+4.1",
        {
            "linux_x64": "c258365739c022ee2189bc83dac72c3627f34cb0b02668e4bd3969380ed54b69",
            "linux_aarch64": "aa02942ba8a8860e330737919d5b77c28489fc56661d500bdb1436ab96d11799",
            "macos_aarch64": "5c0182f80bff02da9266a9928606e65ea2ac53a8cdb195fb3680972378e4e192",
        },
    ),
}

# GraalVM version -> (the release's source tag, sha256 of its thin launcher's C++ source,
# sdk/src/org.graalvm.launcher.native/src/launcher.cc): //:scheme-native's executable (design.md
# D17, "VM options"). The release notes name the tag ("Based on the commit").
_LAUNCHER_SOURCES = {
    "25.6.5.1": ("graal-25.6.5.1", "9c8b76ae842fc6f6552fa4c1b523463c4817e7d0d52bd041f9f1dd0e274a9951"),
}

# The JDK's JNI headers as a C library, for the native launcher (//:scheme-native), which includes
# jni.h. {os} is the JDK's include directory for its platform's jni_md.h.
_JNI_BUILD = """
cc_library(
    name = "jni",
    hdrs = ["include/jni.h", "include/{os}/jni_md.h"],
    includes = ["include", "include/{os}"],
    visibility = ["//visibility:public"],
)
"""

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
        "# The target platform's JNI headers (jni.h, jni_md.h): the native launcher's (//:scheme-native).",
        "alias(",
        "    name = \"jni\",",
        "    actual = select({",
    ] + [
        "        \":is_%s\": \"@graalvm_%s//:jni\"," % (name, name)
        for name in _PLATFORMS
    ] + [
        "    }),",
        ")",
        "",
        "# The target platform's Oracle GraalVM: //:scheme-native's with --//:native_graalvm=oracle.",
        "alias(",
        "    name = \"oracle_jdk\",",
        "    actual = select({",
    ] + [
        "        \":is_%s\": \"@oracle_graalvm_%s//:jdk\"," % (name, name)
        for name in _PLATFORMS
    ] + [
        "    }),",
        ")",
        "",
        "exports_files([\"version\"])",
    ]
    rctx.file("BUILD.bazel", "\n".join(lines) + "\n")
    rctx.file("version", rctx.attr.version + "\n")
    rctx.file("version.bzl", "VERSION = \"%s\"\n" % rctx.attr.version)

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
    if version not in _ORACLE_RELEASES:
        fail(("MODULE.bazel pins Truffle %s, but bazel/graalvm.bzl has no Oracle GraalVM %s release " +
              "to match it (design.md D11). Add the release's URLs and sha256s there.") % (version, version))
    if version not in _LAUNCHER_SOURCES:
        fail(("MODULE.bazel pins Truffle %s, but bazel/graalvm.bzl has no source of GraalVM %s's thin " +
              "launcher to match it (design.md D11, D17). Add its tag and sha256 there.") % (version, version))
    tag, launcher_sha256 = _LAUNCHER_SOURCES[version]
    http_file(
        name = "graalvm_launcher_cc",
        urls = ["https://raw.githubusercontent.com/oracle/graal/%s/sdk/src/org.graalvm.launcher.native/src/launcher.cc" % tag],
        sha256 = launcher_sha256,
        downloaded_file_path = "launcher.cc",
    )
    for prefix, (url_prefix, top, sha256s) in [("graalvm_", _RELEASES[version]), ("oracle_graalvm_", _ORACLE_RELEASES[version])]:
        for name, (os, _, suffix, home) in _PLATFORMS.items():
            http_archive(
                name = prefix + name,
                urls = [url_prefix + suffix + "_bin.tar.gz"],
                sha256 = sha256s[name],
                strip_prefix = top + home,
                build_file_content = 'load("@rules_cc//cc:defs.bzl", "cc_library")\n' +
                                     JDK_BUILD_TEMPLATE.format(RUNTIME_VERSION = JAVA_VERSION) + _JNI_BUILD.format(
                    os = "darwin" if os == "macos" else os,
                ),
            )
    _hub(name = "graalvm", version = version)
    return mctx.extension_metadata(reproducible = True)

graalvm = module_extension(
    implementation = _graalvm_impl,
    tag_classes = {"jdk": tag_class(attrs = {"version": attr.string(mandatory = True)})},
)

# Truffle comes from Maven Central at exactly the version of the GraalVM JDK that
# flake.nix puts on JAVA_HOME (design.md D11). mx_r7rs.py refuses to run if the
# two differ. Upgrading is a human-made change: bump nixpkgs in flake.lock, then
# every Truffle "version" and "digest" below, together.

suite = {
    "mxversion": "7.88.3",
    "name": "r7rs",
    "url": "https://github.com/omarjatoi/truffle-scheme",
    "groupId": "org.r7rs",
    # SpotBugs on every non-test Java project (GraalVM's linter; `mx checks spotbugs`).
    "spotbugs": True,
    "developer": {
        "name": "truffle-scheme contributors",
    },

    "licenses": {
        "UPL": {
            "name": "Universal Permissive License, Version 1.0",
            "url": "http://opensource.org/licenses/UPL",
        },
    },

    "libraries": {
        "COLLECTIONS": {
            "digest": "sha512:2dac40fad67ca28409d824a3ae142e9ab3d605394dc485735410f2dd552e16cd5d04ce31dd083a48ff39e28fad16888d33a9acfd6f62be4e0d79a8dac3e71897",
            "maven": {"groupId": "org.graalvm.sdk", "artifactId": "collections", "version": "25.5.5.0.3"},
            "moduleName": "org.graalvm.collections",
            "dependencies": [],
            "license": "UPL",
        },
        "WORD": {
            "digest": "sha512:a22633251c3a660a0271a03a3d438dffbfa08f68138ed75bddb136a02733d006d62d20b1cfae4423b62414edb5a43dbdd8509a60c82ab106ee8ded91f5752e40",
            "maven": {"groupId": "org.graalvm.sdk", "artifactId": "word", "version": "25.5.5.0.3"},
            "moduleName": "org.graalvm.word",
            "dependencies": [],
            "license": "UPL",
        },
        "NATIVEIMAGE": {
            "digest": "sha512:ea80a3bb34554dc129d84c881fe2bf6c46121e360104076769713849d64b19b07ddffbe6b324fcd9fc78e2466c46ea8016efaf9c2f536630b91aff112e6fbbc8",
            "maven": {"groupId": "org.graalvm.sdk", "artifactId": "nativeimage", "version": "25.5.5.0.3"},
            "moduleName": "org.graalvm.nativeimage",
            "dependencies": ["WORD"],
            "license": "UPL",
        },
        "JNIUTILS": {
            "digest": "sha512:79c91a40dc153447be722f29bdfcc01fb032098383feefd4824d2be8e4d099cf396ddbbf7d119b85d133daf8478450105c4a34423b4baa031512a289f1cc1d52",
            "maven": {"groupId": "org.graalvm.sdk", "artifactId": "jniutils", "version": "25.5.5.0.3"},
            "moduleName": "org.graalvm.jniutils",
            "dependencies": ["COLLECTIONS", "NATIVEIMAGE"],
            "license": "UPL",
        },
        "NATIVEBRIDGE": {
            "digest": "sha512:6a3ede729b7dc164c635c32827c55575689d223f98885aa746733a05f8f52072b3133baf7f2bc97c6f5282f13686d9c14ed158242edc3c1d6822edfe7aaed44b",
            "maven": {"groupId": "org.graalvm.sdk", "artifactId": "nativebridge", "version": "25.5.5.0.3"},
            "moduleName": "org.graalvm.nativebridge",
            "dependencies": ["JNIUTILS"],
            "license": "UPL",
        },
        "POLYGLOT": {
            "digest": "sha512:064db413e0666ffa578dde28b4be16f11d03e973ac8db0f0d2f3d31c23a2ac8c31b77040f604d3ffdf8eb4368285cad6f6c2c6a49b3fe2875c7230e58137f83c",
            "maven": {"groupId": "org.graalvm.polyglot", "artifactId": "polyglot", "version": "25.5.5.0.3"},
            "moduleName": "org.graalvm.polyglot",
            "dependencies": ["COLLECTIONS", "NATIVEIMAGE"],
            "license": "UPL",
        },
        "TRUFFLE_API": {
            "digest": "sha512:960162e58a7adbb0698cd14bb09669fd45e7d17d21dc0317f8db10aba344c5d927155f9ced48e0774cd1f035acb09cad6e24e23bbceb826737af136ce5b71662",
            "maven": {"groupId": "org.graalvm.truffle", "artifactId": "truffle-api", "version": "25.5.5.0.3"},
            "moduleName": "org.graalvm.truffle",
            "dependencies": ["COLLECTIONS", "JNIUTILS", "NATIVEBRIDGE", "POLYGLOT"],
            "license": "UPL",
        },
        "TRUFFLE_COMPILER": {
            "digest": "sha512:559ab4df1ad0ca25a9e0594a493e302d1938d95ba7ff265908135068097e15a3e2a4c0d0fc3926c37e038287f6b3f2ad05788f905147303e1d9f93b950fa6bf0",
            "maven": {"groupId": "org.graalvm.truffle", "artifactId": "truffle-compiler", "version": "25.5.5.0.3"},
            "moduleName": "org.graalvm.truffle.compiler",
            "dependencies": [],
            "license": "UPL",
        },
        "TRUFFLE_RUNTIME": {
            "digest": "sha512:67b1d0b78682d2a35dac94c773dd3c552a0222c01f4d2176dc99d7471422b0440bab2aec102c492f5cb7f9a1f36ddc0c8db0e39acf2645aa9697982fc301af3a",
            "maven": {"groupId": "org.graalvm.truffle", "artifactId": "truffle-runtime", "version": "25.5.5.0.3"},
            "moduleName": "org.graalvm.truffle.runtime",
            "dependencies": ["TRUFFLE_API", "TRUFFLE_COMPILER"],
            "license": "UPL",
        },
        "TRUFFLE_DSL_PROCESSOR": {
            "digest": "sha512:172b3035371ba99147feb48731fb22f28dc8b453071d31dcf406a26908c1797107b0df541c7a679c39f6db1ca390d31e25d46637bf74bb7f0cee32f942b1dadc",
            "maven": {"groupId": "org.graalvm.truffle", "artifactId": "truffle-dsl-processor", "version": "25.5.5.0.3"},
            "dependencies": [],
            "license": "UPL",
        },
    },

    # Each project's mx directory (IDE files, checkstyle config) lives under
    # mx.r7rs/projects/<name>, and its sources live in src/<name> (tests: tests/).
    # A source root must not be the project directory itself: mx copies every
    # non-Java file under a source root into the jar, including the IDE files
    # that `mx ideinit` writes into the project directory.
    "projects": {
        "scheme.reader": {
            "dir": "mx.r7rs/projects/reader",
            "sourceDirs": ["../../../src/reader"],
            "dependencies": ["scheme.runtime"],
            "javaCompliance": "25+",
            "checkstyle": "scheme.runtime",
        },
        "scheme.runtime": {
            "dir": "mx.r7rs/projects/runtime",
            "sourceDirs": ["../../../src/runtime"],
            "dependencies": ["TRUFFLE_API"],
            "annotationProcessors": ["TRUFFLE_DSL_PROCESSOR"],
            "javaCompliance": "25+",
            "checkstyleVersion": "10.21.0",
        },
        "scheme.builtins": {
            "dir": "mx.r7rs/projects/builtins",
            "sourceDirs": ["../../../src/builtins"],
            "dependencies": ["scheme.runtime"],
            "annotationProcessors": ["TRUFFLE_DSL_PROCESSOR"],
            "javaCompliance": "25+",
            "checkstyle": "scheme.runtime",
        },
        "scheme.compiler": {
            "dir": "mx.r7rs/projects/compiler",
            "sourceDirs": ["../../../src/compiler"],
            "dependencies": ["scheme.reader"],
            "javaCompliance": "25+",
            "checkstyle": "scheme.runtime",
        },
        "scheme.prelude": {
            # Standard libraries (.sld) and prelude Scheme code, shipped as resources.
            "dir": "mx.r7rs/projects/prelude",
            "sourceDirs": ["../../../src/prelude"],
            "dependencies": [],
            "javaCompliance": "25+",
            "checkstyle": "scheme.runtime",
        },
        "scheme.launcher": {
            # Host-side tools that embed the language: the conformance runner (M1-01)
            # and SchemeMain, the program runner that mx r7rs-diff uses (M1-02).
            "dir": "mx.r7rs/projects/launcher",
            "sourceDirs": ["../../../src/launcher"],
            "dependencies": [
                "POLYGLOT",
                "TRUFFLE_RUNTIME",
            ],
            "javaCompliance": "25+",
            "checkstyle": "scheme.runtime",
        },
        "scheme.test": {
            "dir": "mx.r7rs/projects/test",
            "sourceDirs": ["../../../tests"],
            # Tests drive the language through the polyglot API and the launcher;
            # SCHEME reaches the module path via SCHEME_TEST's distDependencies.
            "dependencies": [
                "scheme.reader",
                "scheme.launcher",
                "mx:JUNIT",
                "POLYGLOT",
                # The optimizing runtime: without it Truffle falls back to an
                # interpreter-only runtime and the Graal JIT is never used.
                "TRUFFLE_RUNTIME",
            ],
            "javaCompliance": "25+",
            "checkstyle": "scheme.runtime",
            "testProject": True,
        },
    },

    "distributions": {
        "SCHEME": {
            "moduleInfo": {
                "name": "scheme",
                # The front end and the primitives are services, so the runtime (which
                # holds the language class) needn't depend on them (scheme.runtime.SchemeCompiler).
                "uses": [
                    "scheme.runtime.SchemeCompiler",
                    "scheme.runtime.BuiltinLibrary",
                ],
                "exports": [
                    "scheme.reader",
                    "scheme.runtime",
                    "scheme.runtime.printer",
                    "scheme.runtime.value",
                ],
            },
            # Run as a module, with Truffle on the module path, as embedders will.
            "useModulePath": True,
            "dependencies": [
                "scheme.reader",
                "scheme.runtime",
                "scheme.builtins",
                "scheme.compiler",
                "scheme.prelude",
            ],
            "distDependencies": [],
            # Truffle jars stay separate modules on the module path.
            "exclude": [
                "COLLECTIONS",
                "WORD",
                "NATIVEIMAGE",
                "JNIUTILS",
                "NATIVEBRIDGE",
                "POLYGLOT",
                "TRUFFLE_API",
            ],
            "description": "R7RS-small Scheme on GraalVM Truffle",
            "maven": False,
        },
        "SCHEME_LAUNCHER": {
            "moduleInfo": {
                "name": "scheme.launcher",
                "exports": ["scheme.launcher"],
            },
            "useModulePath": True,
            "dependencies": ["scheme.launcher"],
            "distDependencies": ["SCHEME"],
            "exclude": [
                "COLLECTIONS",
                "WORD",
                "NATIVEIMAGE",
                "JNIUTILS",
                "NATIVEBRIDGE",
                "POLYGLOT",
                "TRUFFLE_API",
                "TRUFFLE_COMPILER",
                "TRUFFLE_RUNTIME",
            ],
            "maven": False,
        },
        "SCHEME_TEST": {
            "moduleInfo": {
                "name": "scheme.test",
                "exports": ["scheme.test to junit"],
                "opens": ["scheme.test to junit"],
            },
            "useModulePath": True,
            "dependencies": ["scheme.test"],
            "distDependencies": ["SCHEME", "SCHEME_LAUNCHER"],
            "exclude": [
                "mx:JUNIT",
                "COLLECTIONS",
                "WORD",
                "NATIVEIMAGE",
                "JNIUTILS",
                "NATIVEBRIDGE",
                "POLYGLOT",
                "TRUFFLE_API",
                "TRUFFLE_COMPILER",
                "TRUFFLE_RUNTIME",
            ],
            "testDistribution": True,
            "unittestConfig": "r7rs",
            "maven": False,
        },
    },
}

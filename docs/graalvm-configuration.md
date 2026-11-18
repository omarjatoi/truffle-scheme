# GraalVM configuration audit

An audit of how the language is configured, packaged and embedded, against
GraalVM's guidance for a Truffle language: the `@Option` and
`TruffleLanguage.Registration` Javadoc, the [Embedding
Languages](https://www.graalvm.org/latest/reference-manual/embed-languages/)
reference, the [Sandboxing](https://www.graalvm.org/latest/security-guide/sandboxing/)
guide, the [module
migration](https://www.graalvm.org/latest/graalvm-as-a-platform/language-implementation-framework/migration-to-java-modules/)
guide and Native Image's [build
configuration](https://www.graalvm.org/latest/reference-manual/native-image/overview/BuildConfiguration/)
and [reachability metadata](https://www.graalvm.org/latest/reference-manual/native-image/metadata/)
pages. For each recommendation: what GraalVM says, what we do, and whether we
**follow** it, deviate **deliberately** (with design.md's evidence), or
deviated **by mistake** (and the PR that fixed it). It is the companion of
[truffle-guidance.md](truffle-guidance.md), which audits the runtime against
the partial-evaluation guidance, and has its format. Written November 2026,
against GraalVM CE 25.5 / Truffle 25.5.5.0.3 (design.md D11).

## Versions

"latest" on graalvm.org is 25.5's documentation (its version selector lists
25.5 first). The pages are files of oracle/graal at the tag `vm-25.5.5.0.3`,
quoted from there: `docs/reference-manual/embedding/embed-languages.md`
(abbreviated 25.5:embed-languages.md below), `docs/security/polyglot-sandbox.md`,
`truffle/docs/ModuleMigration.md`,
`docs/reference-manual/native-image/BuildConfiguration.md` and
`ReachabilityMetadata.md`. Every API claim was checked against 25.5's sources at
that tag (`TruffleLanguage.java`, `Option.java`, `SandboxPolicy.java`,
`OptionType.java`, `ConstantOptionKey.java`, `InternalResource.java`, the
launcher framework's `AbstractLanguageLauncher.java` and `Launcher.java`, the
sandbox instrument's `SandboxInstrument.java`), its changelogs
(`sdk/CHANGELOG.md`, `truffle/CHANGELOG.md`), or by running 25.5. 25.1-25.5
changed four things this audit depends on (sdk/CHANGELOG.md, 25.1.3):

- *"GR-73792: Sandbox resource limits are now available in GraalVM Community
  Edition."* and *"`SandboxPolicy.ISOLATED` and `SandboxPolicy.UNTRUSTED` are
  now supported in GraalVM Community Edition."* (the `sandbox.*` options are
  in our runtime's `--help:all`);
- *"GR-71940: `Engine.getVersion()` now return the Truffle API version rather
  than the GraalVM version."*, which a language's `version = "inherit"` is;
- *"GR-73872: Added constant-option support with `ConstantOptionKey<T>`"*;
- and 25.5's *"GR-79961: Auxiliary engine caching ... is now available in both
  Oracle GraalVM and GraalVM Community Edition."*

## What we ran

A Java program against `bazel-bin/scheme_jars` (the embedding guide's module
path), building contexts each way a recommendation names, on `main` before the
fixes:

| Configuration | Result |
|---|---|
| `Context.newBuilder("scheme").sandbox(SandboxPolicy.CONSTRAINED)`, streams redirected | Refused: *"The language scheme can only be used up to the TRUSTED sandbox policy."* (fixed, below) |
| `ResourceLimits.newBuilder().statementLimit(100000, null)`, an endless loop | Cancelled: *"Statement count limit of 100000 exceeded."* (`isResourceExhausted()`): each expression of a body and each root's body is a statement (`CodeGenerator.tags`, D13) |
| `sandbox.MaxCPUTime=200ms`, an endless loop | Cancelled after 203 ms |
| `sandbox.MaxOutputStreamSize=10KB`, `display` in a loop | Cancelled at 16,368 bytes: standard output reaches the limit's stream in 8 KB writes (D10, "Standard output is buffered") |
| `sandbox.MaxStackFrames=40`, a recursion 50 deep | Cancelled with 41 frames; but with `scheme.SpillDepth=20` it ran (below; fixed in #240) |
| `sandbox.MaxASTDepth=2`, nested arithmetic 6 deep | Ran: not enforced (below) |
| `Context.interrupt` from another thread, an endless loop | Stopped (`isInterrupted()`), and the context went on |
| `Context.close(true)` from another thread, an endless loop | Cancelled (`isCancelled()`) |
| `IOAccess.NONE`: `open-input-file`, `file-exists?` | Scheme errors that `file-error?` answers, which `guard` catches |
| `IOAccess.NONE`: `(import (foo bar))` from `scheme.LibraryPath` | A guest error, *"cannot read foo/bar.sld: Operation is not allowed for: /tmp/foo/bar.sld"* |
| `EnvironmentAccess.NONE` (the default) | `(get-environment-variable "HOME")` is `#f`, and there are none |
| `scheme.SpillDepth=-5` | Accepted (fixed, below) |
| `scheme.SpillDepth=x` | *"For input string: "x""*, without the option's name (fixed, below) |
| `scheme.StackSize=foo`, `scheme.BufferOutput=maybe`, `scheme.NoSuch=1` | Refused, naming the option or the value |
| `Source.findLanguage(new File("x.scm"))` | `null` (fixed, below) |
| `Engine.create("scheme")` shared by two contexts | Works; each context compiles its own code (below) |

## Language options

Sources: `Option.java`'s Javadoc (25.5), `OptionType.java`, and
`TruffleLanguage.java` (`getOptionDescriptors`, `areOptionsCompatible`).

| Recommendation | GraalVM says | What we do | Status |
|---|---|---|---|
| Declare options with `@Option`, a category and a stability | `category()`: USER, EXPERT or INTERNAL; *"`stability()` ... The default value is `OptionStability#EXPERIMENTAL`."* | `scheme.LibraryPath` and `scheme.BufferOutput` USER and STABLE, `scheme.StackSize` EXPERT and STABLE, `scheme.PreludeImage` INTERNAL and STABLE, `scheme.SpillDepth` INTERNAL and EXPERIMENTAL (tests only: it needs `allowExperimentalOptions`). | Follow |
| The default at the end of the help | *"Include the default value for the option and the end of the text, e.g. `"Enable or disable the option (default: true)."`"* | None did: `scheme.StackSize` said *"(0: unknown, measured when needed)"*, `scheme.BufferOutput` *"(false: each write at once)"*. | Mistake, fixed in #218: each ends with `(default: ...)`. |
| A usage syntax | `usageSyntax()`: *"This value is used when generating help messages"*: `true\|false`, `<unit>`, ranges such as `[0, inf)`, lists as `<a>,<a>,...` | Only `scheme.StackSize` had one (`<size>`): `--help:languages` showed `--scheme.LibraryPath=` and `--scheme.SpillDepth=` with nothing after them. | Mistake, fixed in #218: `<dir>:<dir>:...`, `true\|false`, `[0, inf)`. |
| Validate values: an option type with a converter and a validator | `OptionType(String, Function<String,T>, Consumer<T>)`: the converter and the validator *"Throws `IllegalArgumentException` if the value is invalid"* | `scheme.StackSize` has its own type (`StackBudget.parseStackSize`), whose error names the option. `scheme.SpillDepth` used the default `Integer` type: a negative depth was accepted, and *"For input string"* named nothing. | Mistake, fixed in #218: `scheme.SpillDepth` has a type that takes 0 or more and names itself. |
| Structured values: `OptionMap` | `OptionType.defaultType` makes an `OptionMap` type for `key=value` groups | No option is a map. | Follow (not needed) |
| Deprecation | `deprecated()`, `deprecationMessage()` | No option has been renamed or dropped. | Follow (not needed) |
| `areOptionsCompatible` | *"If the context policy of a language is set to exclusive (default behavior) then `areOptionsCompatible` will never be invoked as `TruffleLanguage` instances will not be shared for multiple contexts."* | Not overridden; the policy is `EXCLUSIVE` (Registration, below). The pre-initialized context's `patchContext` declines a context whose `scheme.PreludeImage` is false (D17). | Follow |
| Options reach a context from its builder, its engine, system properties and the launcher | `Context.Builder.option`, `Engine.Builder.option`; `Engine.Builder.useSystemProperties` (*"polyglot."* properties, on by default); `--<id>.<option>` in a launcher | All four. `LanguageOptionsTest` checks the first three: an option of the engine is each of its contexts' default, a context's own comes first, `-Dpolyglot.scheme.LibraryPath` is read as an engine is created. The launcher's `--scheme.*` is M10-04's. | Follow (tests added in #218) |
| Constant options for values fixed before the runtime starts | 25.1's `@Option(constant = true)` and `ConstantOptionKey`: *"The value is read from the system property `-Dpolyglot.<option-name>=<value>` during class initialization ... eligible for constant folding"*, and *"In a GraalVM native image, the property is read during image building"* | Five host system properties of our own, read into `static final` fields: `scheme.torture` and `scheme.torture.owned` (`SchemeRootNode`), `scheme.preludeImage`, `.write` and `.file` (`PreludeImage`); none is listed by `--help`. | Deliberate for now: they predate 25.1's mechanism, which gives the same build-time semantics in a native image (D17). A follow-up: make them INTERNAL constant options. |
| Options safe under a sandbox | `Option.sandbox()`: *"Specifies the most strict sandbox policy in which the option can be used."* (default TRUSTED) | All TRUSTED (the default). | Mistake (with the language's own policy), fixed in #226: the options an embedder sets are CONSTRAINED; `scheme.SpillDepth` stays TRUSTED. |

## The language's registration

Source: `TruffleLanguage.Registration` at 25.5, and
25.5:embed-languages.md, "Managing Internal Resources".

| Attribute | GraalVM says | What we do | Status |
|---|---|---|---|
| `id`, `name` | *"Unique name of your language. This name will be exposed to users"* | `scheme`, `R7RS Scheme`. | Follow |
| `version` | *"It inherits from `Engine#getVersion()` by default."* (since 25.1 the Truffle API's version) | The default: `scheme --version` prints `R7RS Scheme (GraalVM CE JVM 25.5.5.0.3)`, and `Language.getVersion()` is Truffle's. | Deliberate: the project has no releases of its own yet (packaging is deferred). A follow-up for when it has. |
| `implementationName`, `website` | *"Unique name of your language implementation."*; the website *"Will be shown in the help text of GraalVM launchers."* | Neither: no separate implementation name, no public website. 25.5's launcher framework doesn't show a language's website in fact: `Launcher.runLauncherAction` ends its help with a fixed *"See http://www.graalvm.org for more information."* | Follow (not needed) |
| `characterMimeTypes`, `defaultMimeType` | *"The default MIME type allows embedders and other language or instruments to find out how content is interpreted if no MIME type was specified."* | `application/x-scheme`, character-based. | Follow |
| `fileTypeDetectors` | *"Declarative list of `TruffleFile.FileTypeDetector` classes provided by this language."*; `Source.findLanguage(File)` and `findMimeType` use them | None: `Source.findLanguage(new File("x.scm"))` was `null`. | Mistake, fixed in #226: `.scm` and `.sld` files are `application/x-scheme`. |
| `contextPolicy` | *"Every language is encouraged to try to support a context policy that is as permissive as possible"* | `EXCLUSIVE` (the default). | Deliberate: compiled code embeds a context's global cells as constants (D7, D13); D13 rejects `SHARED` (globals looked up per context instead of constant cells). The cost is below ("Engines"). |
| `sandbox` | *"Specifies the most strict sandbox policy in which the language can be used."* (default TRUSTED) | The default: no `CONSTRAINED` context could use Scheme, though the language reaches no host resource except through the Env (D10). | Mistake, fixed in #226: `CONSTRAINED`. |
| `dependentLanguages` | For languages a language uses | None: Scheme uses no other language (D10). | Follow (not needed) |
| `internalResources` | *"files that are part of the language implementation ... made available on disk when the implementation needs them as regular files"* (25.5:embed-languages.md) | None: the prelude, the standard libraries and the prelude image are module resources read through the class loader (`SourceDir`, `PreludeImage`), never as files; native-image puts them in the image (reachability metadata, below). | Follow (not needed): an internal resource would be unpacked to a cache directory at first use, for nothing. |
| `interactive` | default true | The default: the REPL. | Follow |

## Sandboxing

Sources: 25.5's `SandboxPolicy` Javadoc and the
[Sandboxing](https://www.graalvm.org/latest/security-guide/sandboxing/) guide
(`docs/security/polyglot-sandbox.md`).

| Recommendation | GraalVM says | What we do | Status |
|---|---|---|---|
| `CONSTRAINED`: no host access but through the embedder's configuration | *"The CONSTRAINED sandboxing policy is intended for trusted applications whose access to host resources should be regulated."*: standard streams redirected, no host file or socket access (*"Only custom polyglot file system implementations are allowed."*), no environment access, no system exit, no native access, no process creation, restricted host access | Every file goes through the Env's `TruffleFile`s, the console ports through the Env's streams, environment variables through the Env (D10, AGENTS.md "Truffle conventions"); `exit` and `emergency-exit` are Truffle's hard exit (D9), never `System.exit`; no native access, no processes; Scheme never reaches host objects (D10). | Follow, now declared (#226). `SandboxTest` runs a context under it: output to the given streams, files refused as Scheme file errors, libraries read through an embedder's file system, no environment variables, `exit` as an exit exception. |
| A language checks the policy where it must | `Env.getSandboxPolicy()`: *"A language can use the returned sandbox policy to make language-specific verifications that the sandbox requirements are met."* | Nothing to check: no language-specific resource is outside the Env. The one thread the runtime starts (`StackBudget`'s stack probe, on the first deep recursion when the stack size is unknown) runs no guest code and touches nothing outside the JVM. | Follow |
| `ISOLATED`, `UNTRUSTED` | *"In this mode, guest and host application execute on separate virtual machine instances."*; CE needs the language's `-isolate-community` artifact | Not declared: running in a polyglot isolate needs the language built into an isolate library (packaging, below), which nothing builds. | Follow-up |
| Resource limits | *"The ISOLATED and UNTRUSTED sandbox policies require setting certain resource limits for a context."*; statements, CPU time, output, stack frames, AST depth, heap, threads | Statement limits (`ResourceLimits` and `sandbox.MaxStatements`), CPU time, output size, interrupts and cancelling work (above; `SandboxTest` checks the first three and `close(true)`). | Follow |
| `sandbox.MaxStackFrames` | *"Specifies the maximum number of frames a context can push onto the stack."*; counted per thread at the enter and return events of `RootTag` nodes (`SandboxInstrument.FrameCounterNode`), whose `onYield` and `onResume` are `ExecutionEventNode`'s defaults, *"Delegates to `onReturnValue`"* and *"Delegates to `onEnter`"* | Counted Java frames: the Bytecode DSL reports a yield and a resume around every `@Yield` (`tag.yieldNull`, `tag.resume`), so a frame suspended on the heap left the count. Past the spill depth a recursion never reached the limit (with `scheme.SpillDepth=20`, a limit of 40 let a recursion 50 deep run). The count didn't drift: yields and resumes balance (the audit's guess that resumed frames return without entering was wrong; measured: the same deepest recursion after 1,000 re-entries). | Mistake, fixed in #240: each prompt driver reports its pending frames as enters and returns of an internal `RootTag` node (`PendingFramesNode`), so the limit counts the program's frames, on the heap too (design.md D10, "Sandboxing"). |
| `sandbox.MaxASTDepth` | *"A limit on the maximum expression depth of a guest language function. Only instrumentable nodes count towards the limit."* | Not enforced: the instrument measures a root's depth by walking its child nodes (`SandboxInstrument.checkAstDepth`, `NodeUtil.forEachChild`), and a Bytecode DSL root's only nested nodes are the tags some instrument has asked for; the sandbox asks only for `RootTag`. | Upstream: it affects every Bytecode DSL language. docs/upstream.md, ["MaxASTDepth on Bytecode DSL roots"](upstream.md#maxastdepth-on-bytecode-dsl-roots): `UpstreamAstDepthTest` and the issue text, not filed |

## Embedding

Source: 25.5:embed-languages.md.

| Recommendation | GraalVM says | What we do | Status |
|---|---|---|---|
| Close contexts and engines | *"Explicitly close contexts and engines as soon as they are no longer needed, preferably by using a try-with-resources statement."* | Every sample in docs/embedding.md does; a closed or collected context closes the file ports it left open (`disposeContext`, D10). | Follow |
| An explicit engine for code caching | *"By default, code is cached within a single context instance only. An explicit engine needs to be specified to enable code caching between multiple contexts."* | An explicit engine works (its options, instruments and compiler are shared), but no code is: `EXCLUSIVE` (above). Each context restores the prelude from the prebuilt image (D13) and compiles its own code. docs/embedding.md said nothing of it. | Deliberate (D13); documented in #218 ("Options and engines", with a sample). |
| Modules and the module path | *"We recommend configuring polyglot embeddings using modules and the module path whenever possible."* | docs/embedding.md runs on the module path (`bazel-bin/scheme_jars`). Its two JVM flags are Truffle's own needs on JDK 25: without them, the JDK warns of `System::load` from `org.graalvm.truffle` and `sun.misc.Unsafe` from `truffle-runtime`. | Follow |
| `allowIO` / `IOAccess` | (Sandboxing) only custom file systems under `CONSTRAINED` | All file access is the Env's; without IO, opening a file is a Scheme file error and a library a guest error (above). | Follow |
| `allowHostAccess` / `HostAccess` | `HostAccess.CONSTRAINED` is preset under `CONSTRAINED` | Scheme code never reaches the host (D10): a host object is a foreign value it can store and compare. Values from Java become Scheme values by interop, whatever the host access. | Follow |
| `allowExperimentalOptions` | experimental options need it | Only the internal `scheme.SpillDepth` is experimental. | Follow |
| `ResourceLimits` | statement limits count `StatementTag`s | Each expression of a body is a statement (D13's tags), so the limit applies (above). | Follow |
| `Context.interrupt`, `close(true)` | interrupt stops at a safepoint; `close(true)` cancels | Both stop an endless loop (D17, "Interrupts": Bytecode DSL roots poll at back-edges, Truffle when a root returns). `InterruptTest`, `SandboxTest`. | Follow |
| `Value` interop | `InteropLibrary` messages for guest values | D10, "Interop for embedders"; `EmbeddingTest`. | Follow |
| Internal resources | `polyglot.engine.userResourceCache`, `Engine.copyResources` | None (above), so nothing to unpack or copy. | Follow (not needed) |

## Packaging

Sources: `truffle/docs/ModuleMigration.md`, 25.5:embed-languages.md
("Dependency Setup"), Native Image's `BuildConfiguration.md` and
`ReachabilityMetadata.md`, and GraalVM's own language jars at the tag (the
SimpleLanguage's, TRegex's, Sulong's `native-image.properties`).

| Recommendation | GraalVM says | What we do | Status |
|---|---|---|---|
| A named module that provides the language | *"Define a named Java module that requires the `org.graalvm.truffle` module."*; *"register a language provider using the `provides TruffleLanguageProvider with <LanguageClass>Provider` directive"* | `scheme.jar` is the module `scheme`: `requires org.graalvm.truffle`, `provides com.oracle.truffle.api.provider.TruffleLanguageProvider with scheme.runtime.SchemeLanguageProvider` (`jar --describe-module`). | Follow |
| No JDK or third-party services | *"Languages or instruments must not provide JDK services or services from third party libraries in the module descriptor."* | It provides only its own (`scheme.runtime.BuiltinLibrary`, `scheme.runtime.SchemeCompiler`, D11). | Follow |
| Encapsulation | *"If the language or instrument does not expose any API, it is recommended to keep the module as encapsulated as possible and avoid exporting any packages."*; *"In the case of internal APIs that are exclusively utilized by known modules, it is advised to use qualified exports"* | It exported five packages (`scheme.compiler`, `scheme.reader`, `scheme.runtime`, `.printer`, `.value`) to everyone, for the unit tests' modules; the launcher uses none of them. | Mistake, fixed in #254: each only `to scheme.test`, as the SimpleLanguage's go `to org.graalvm.sl.test`, and `scheme.launcher` only `to org.graalvm.launcher` (the native launcher makes `SchemeMain` by reflection) and `scheme.test`. One class is exported to everyone: the Static Object Model generates records' classes in unnamed modules, which extend `SRecord`, so `SRecord` is alone in `scheme.runtime.staticobject`, as Espresso exports its static objects' package (*"Workaround GR-48132"*; design.md D11, "Modules"). |
| `native-image.properties` in the jar, by group and artifact | *"We recommend that you provide the configuration for the native-image builder by embedding a native-image.properties file into a project JAR file."*, in *"META-INF/native-image/groupID/artifactID"* | `META-INF/native-image/org.r7rs/scheme/native-image.properties` in `scheme.jar`, so an embedder's image gets it: build-time initialization of the language's packages, as the SimpleLanguage (`--initialize-at-build-time=com.oracle.truffle.sl,...`), TRegex and Sulong do (D17). | Follow |
| Reachability metadata beside it | *"Place one or more reachability-metadata.json files in the META-INF/native-image/<groupId>/<artifactId>/ directory"* | `reachability-metadata.json` there: the prelude's and the standard libraries' resources, by module and glob. No reflection (D10). | Follow |
| A bound on runtime-compiled methods | GraalVM's language jars add `-H:MaxRuntimeCompileMethods=<n>` (SimpleLanguage 250, TRegex 900, Sulong 10000), which fails an image whose partial evaluation reaches more | Not set. | Mistake, fixed in M11-09: the language's share is 300, which makes `//:scheme-native`'s bound 3,000 (Truffle's runtime adds 2,700) against its 2,703 methods, and its build enforces it (`-H:+EnforceMaxRuntimeCompileMethods`; design.md D17, "Runtime-compiled methods"). |
| Maven artifacts | *"Artifacts relevant to embedders can be found in the Maven dependency group `org.graalvm.polyglot`"*; a language is a `pom` artifact (`org.graalvm.polyglot:js-community` → `org.graalvm.js:js` → `js-language` and `org.graalvm.truffle:truffle-runtime`) | Nothing is published: `bazel build //:scheme_jars` (docs/embedding.md). | Deferred (project tooling): a follow-up |
| Isolate artifacts | *"For polyglot isolate artifacts, use ... `-isolate-community` on GraalVM Community Edition 25.1 or later"* | None. | Deferred: a follow-up (`ISOLATED`, above) |
| No uber jars | *"While we do support creating single uber JAR files ... we do not recommend it."* | A directory for the module path. | Follow |
| A standalone | The SimpleLanguage's `standalone` (a JVM standalone, and a native one with `-Pnative`) | `bazel-bin/scheme` and `bazel-bin/scheme-native`, the latter GraalVM's thin launcher in front of the image as a shared library, as GraalVM's standalones ship (D17, M10-01). | Follow |

## Engine and compiler configuration we set

| What | GraalVM says | What we do | Status |
|---|---|---|---|
| Engine defaults | 25.5:Options.md lists the engine and compiler options and their defaults | Neither launcher sets an `engine.*` or `compiler.*` option. D17 measured and rejected `OSRCompilationThreshold`, `FirstTierCompilationThreshold`, `CompilerThreads`, `MultiTier=false` and `Mode=latency` as launcher defaults. | Follow |
| The launcher's context | `AbstractLanguageLauncher` builds it with `allowAllAccess(true)` | `SchemeMain` adds `allowIO(IOAccess.ALL)`, `allowEnvironmentAccess(INHERIT)`, the library path, and `scheme.StackSize` and `scheme.BufferOutput` as D17 and D10 say; a standalone is trusted. | Follow |
| The warm-up context | (an explicit engine shares code; a separate one doesn't) | A context of its own engine, no I/O, no options of the command line (D17, "The JIT warm-up"). | Follow |
| Pre-initialized contexts | `--engine.PreinitializeContexts` (25.5:Options.md); 25.5's name at image build time, `polyglot.image-build-time.PreinitializeContexts` | `-Dpolyglot.image-build-time.PreinitializeContexts=scheme`, an option of `//:scheme-native`, not of the jar (D17, M8-07). | Follow |
| The library's build options | `mx_sdk_vm_impl.py`'s options for a language library | `--shared`, `-H:-InitializeVM`, `-R:+EnableSignalHandling`, `-R:+InstallSegfaultHandler`, `-H:+InstallExitHandlers`, as mx builds GraalVM's; without `-H:-ParseRuntimeOptions` (D17, "VM options", measured). | Follow (D17) |
| JVM options of the JVM launcher | (JVM options, not GraalVM's) | The serial collector, 4 host compiler threads, the AOT cache, each given way to a user's choice (D17, measured). | Deliberate (D17) |
| System properties we read | (above, "Constant options") | The runtime: `scheme.torture*`, `scheme.preludeImage*` (tests and the build). The launcher resolves an option given on its command line or as a `polyglot.<name>` system property, for the warm-up (`engine.Compilation`), the terminal's buffering (`scheme.BufferOutput`) and the library path, and sets `polyglot.engine.TraceTransferToInterpreter` for the framework's first engine (M10-04, #219: design.md D17, "Options"). | Follow-up (constant options); the launcher's: follow (#219) |

## Fixes

| PR | Change | Evidence |
|---|---|---|
| #218 | Each option's help ends with its default, each has a usage syntax, `scheme.SpillDepth` takes only 0 or more and names itself in its error; docs/embedding.md's "Options and engines" | `LanguageOptionsTest`: every option's help and syntax, a bad value of each refused naming it, an experimental one needing `allowExperimentalOptions`, an option from a context's builder, its engine's (each context's default, the context's own first) and a system property. `EmbeddingGuideTest.engine`, docs/embedding.md's sample. |
| #226 | `sandbox = CONSTRAINED` on the language and on its options but `scheme.SpillDepth`; a file type detector for `.scm` and `.sld`; docs/embedding.md's "Sandboxing and resource limits", design.md D10's "Sandboxing" | `SandboxTest` (above), in every test mode; `FileTypeDetectorTest`: `Source.findLanguage` and `findMimeType` of `.scm` and `.sld` files; `EmbeddingGuideTest.limits` |
| #240 | `sandbox.MaxStackFrames` counts the frames prompt drivers hold on the heap (`PendingFramesNode`); design.md D10, docs/embedding.md | `SandboxTest`, in every test mode: with frames spilled every 1, 5 or 20 calls or as the stack needs, a recursion as deep as the limit allows runs and one a frame deeper is stopped; a million-iteration loop runs under a limit of 10; after re-entering, escaping from, raising through, reinstating and aborting deep continuations 200 times each, and 100 errors unwinding 60 frames, the deepest recursion allowed is the same |
| #254 | Every export of `scheme` qualified `to scheme.test` but `scheme.runtime.staticobject` (`SRecord` alone, for the Static Object Model's generated classes); `scheme.launcher` exported `to org.graalvm.launcher, scheme.test` | `//tests:module_exports` reads the built jars' descriptors and fails on an unqualified export or opens but that one, and on any other class in its package; the unit tests, docs/embedding.md's samples (`//tests:readme`) and the native launcher (`//tests:readme_native`, `//tests:conformance_native`) pass |

## Follow-ups

- **Constant options** for the test and build switches (`scheme.torture`,
  `scheme.torture.owned`, `scheme.preludeImage`, `.write`, `.file`): 25.1's
  `@Option(constant = true)` with `ConstantOptionKey`, as INTERNAL options
  `polyglot.scheme.*` that `--help:internal` lists and that are validated.
  Needs the test macros, the harness and the native build changed together,
  and a check that a native image still bakes them in at build time.
- **`ISOLATED` and `UNTRUSTED`**: building the language as a polyglot isolate
  library (`-isolate-community`), and checking every limit those policies
  require (`sandbox.MaxASTDepth`, `MaxHeapMemory`, `MaxThreads`).
- **`sandbox.MaxASTDepth`** isn't enforced on Bytecode DSL roots (its
  instrument walks a root's child nodes, which hold only the tags some
  instrument asked for): reproduced without Scheme in docs/upstream.md,
  ["MaxASTDepth on Bytecode DSL roots"](upstream.md#maxastdepth-on-bytecode-dsl-roots)
  (`UpstreamAstDepthTest`), with the issue text ready; filing it is a
  human's call.
- **A version of our own** (`Registration.version`) when the project releases.
- **Maven artifacts** in GraalVM's layout (a `pom` artifact depending on the
  language jar and `truffle-runtime`), when packaging is no longer deferred.
- **Auxiliary engine caching**, in CE since 25.5: it persists an engine's
  ASTs and compiled code, which needs code that doesn't depend on a context
  (*"This requires language implementations to disable context-related
  optimizations"*): not with `EXCLUSIVE` and constant cells (D13).

# Truffle guidance audit

An audit of the runtime against GraalVM's own guidance for Truffle language
implementations: the [Partial
Evaluation](https://www.graalvm.org/latest/graalvm-as-a-platform/language-implementation-framework/PartialEvaluation/)
page, every item of its "Further reading" (four Truffle guides and three
papers), and the Truffle guides that cover the same ground (the Bytecode DSL
guides, inlining, splitting, on-stack replacement, options). For each
recommendation: what GraalVM says, what we do, and whether we **follow** it,
**deviate deliberately** (with the design.md decision and its evidence), or
deviated **by mistake** (and the PR that fixed it). Written November 2026,
against GraalVM CE 25.5 / Truffle 25.5.5.0.3 (design.md D11).

Its companion, [graalvm-configuration.md](graalvm-configuration.md), audits
how the language is configured, packaged and embedded (options, the
language's registration, sandboxing, embedding, native-image configuration).

## Versions

"latest" on graalvm.org is the 25.5 documentation (its version selector lists
25.5 first). The pages are the files under `truffle/docs` of oracle/graal; we
compared each at the `vm-25.5.5.0.3` tag with `master` (25.6-dev). These are
identical: `PartialEvaluation.md`, `Optimizing.md`, `HostCompilation.md`,
`DeoptCyclePatterns.md`, `DSLGuidelines.md`, `Inlining.md`,
`OnStackReplacement.md`, `OneCompilationPerBytecodeHandler.md`,
`bytecode_dsl/RuntimeCompilation.md`, `splitting/ReportingPolymorphism.md`,
`TruffleStrings.md`, `StaticObjectModel.md`. These differ, and we quote the
25.5 tag: `bytecode_dsl/UserGuide.md`, `bytecode_dsl/Optimization.md` (master
adds a quickening section), `splitting/Splitting.md` (master describes 25.6's
splitting at compilation preparation, GR-79412), `Options.md` (one option).
Links below are to the 25.5 tag, e.g.
`https://github.com/oracle/graal/blob/vm-25.5.5.0.3/truffle/docs/PartialEvaluation.md`,
abbreviated `25.5:PartialEvaluation.md`. Every API claim we acted on was
checked against 25.5's sources at that tag (`CompilerDirectives.java`,
`GenerateBytecode.java`) or by running 25.5.

## What we ran

ecraven's `fib`, `tak`, `ctak`, `cpstak`, `puzzle`, `scheme`, `nboyer`,
`earley` (2-3 iterations each, some at a tenth of their counts) and a deep
recursion (`count` 300,000 deep and a list built and `map`ped 1,000,000 deep,
4 times: 1.20 s, then 0.42, 0.25, 0.20 s), through `bazel-bin/scheme` with:

```
--experimental-options --compiler.TracePerformanceWarnings=all --engine.TraceCompilation \
  --engine.CompilationFailureAction=Print --engine.TraceSplittingSummary
```

and, as JVM system properties (below; options since M10-04), `engine.TraceTransferToInterpreter`
and `engine.TraceAssumptions`; the interpreter's host compilation with
`-Djdk.graal.Log=HostInliningPhase,~CanonicalizerPhase,~GraphBuilderPhase`
(`engine.Compilation=false`, `fib`, `scheme`, `puzzle`).

| Program | Perf warnings | Compilations | Failed | Deopts | Invalidations | Splits |
|---|--:|--:|--:|--:|--:|--:|
| `fib` | 0 | 2 | 0 | 0 | 0 | 0 |
| `tak` | 0 | 2 | 0 | 0 | 0 | 0 |
| `ctak` | 0 | 12 | 0 | 0 | 0 | 0 |
| `cpstak` | 0 | 12 | 0 | 1 | 2 (OSR loop, `validRootAssumption`) | 0 |
| `puzzle` (count 100) | 0 | 35 | 0 | 10 | 1 (`Profiled Return Type`) | 0 |
| `nboyer` | 0 | 43 | 0 | 1 | 7 (local tags, 1 return type) | 0 |
| `earley` | 0 | 21 | 0 | 1 (OSR, not invalidating) | 0 | 0 |
| `scheme` (count 10,000) | 30 | 160 | 0 | 24 | 0 | 66 |
| deep recursion | 0 | 14 | 0 | 2 (`map`'s loop, OSR, once) | 0 | 0 |

- **No compilation failed, and nothing deoptimizes in a cycle**: Truffle 25's
  deoptimization-cycle detection (on by default, `CompilationFailureAction=Print`
  prints what it finds) reported nothing. Every invalidation is a profile
  stabilizing once: a local's boxing-elimination tag, a return-type or
  argument-type profile, the OSR loop of the trampoline.
- `puzzle`'s 10 deoptimizations are one invalidation of `%call/ec`'s receiver
  (its return-type profile) and then its compiled frames still on the stack,
  each deoptimizing as it is returned to: the pattern design.md describes for
  a first descent ("Stack-depth spill", M9-01), here in `trial`'s recursion,
  which is shallow (9 frames).
- `scheme`'s 30 performance warnings are all one kind, in the I/O
  primitives: *"Partial evaluation could not inline the virtual runtime call
  Virtual to `SPort.isInput()`"* (and `isBinary`, `isOutput`, `flush`), from
  `IoPrimitives.port`, `textualOutput` and `Flush.invoke`, where the port is
  not a constant. Fixed in #208 (Fixes, below): none in `scheme`, `cat`,
  `wc`, `read1` or `tail` since.
- `scheme`'s deoptimizations: each of 8 splits of one closure
  (`gen-combination-2`'s lambda) deoptimizes once after its first compilation
  (an uncommon trap, not an explicit `transferToInterpreter`, so
  `TraceTransferToInterpreter` doesn't show it), and its callers' frames on
  the stack deoptimize with it ("Invalidated false"). It stops after the
  first iterations: the third has none. Found and fixed in M10-06: a null
  check that Graal's speculative guard movement hoisted out of `string<?`'s
  loop, which a split, with a speculation log of its own, failed again
  (design.md D1, "Representations chosen outside loops").
- The host compiler's inlining of the bytecode interpreter
  (`SchemeRootNodeGen$CachedBytecodeNode.continueAt`, the method the Bytecode
  DSL marks `@BytecodeInterpreterSwitch`) never ran out of budget and never
  cut a subtree as "too complex": its graph grew from 434 to 4,365 for `fib`,
  and to at most 7,898 for `puzzle`, against the default budget of 100,000
  (`-Djdk.graal.TruffleHostInliningByteCodeInterpreterBudget`). The cut-offs
  are slow paths (`dominated by transferToInterpreter()`, `protected by
  inInterpreter()`), `@TruffleBoundary` methods, `@InliningCutoff` (Truffle's
  own), and the virtual `PrimitiveNode.invoke`, which is polymorphic by
  design.

## Partial evaluation

Source: [Partial Evaluation](https://www.graalvm.org/latest/graalvm-as-a-platform/language-implementation-framework/PartialEvaluation/)
(25.5:PartialEvaluation.md).

| Recommendation | GraalVM says | What we do | Status |
|---|---|---|---|
| Stable structure in `final`, `@Child`, `@Children` | *"Use `final` for fields whose values are fixed at construction time."* *"Use `@Child` and `@Children` for AST child nodes whose structure should be stable during PE."* | Bytecode DSL roots: the DSL generates the node structure. Hand-written nodes (`Trampoline`, `PrimitiveRootNode`, `PrimitiveNode`s, the prompt roots) keep children in `@Child` fields and their configuration in `final` ones. | Follow |
| `@CompilationFinal` for profiled state, invalidated before it changes | *"Invalidate compiled code before changing a `@CompilationFinal` value that compiled code may depend on."* | Two mutable `@CompilationFinal` fields: `Trampoline.TailCallLoop.slot` (written after `transferToInterpreterAndInvalidate`) and `SchemeLanguage.interopCall` (written once, before any code runs). Everything else profiled is the DSL's. | Follow |
| `@CompilationFinal(dimensions = ...)` explicit | *"it is recommended to declare the dimensions explicitly"* | `SClosure.captured`, `SRecordType.properties`, the record constructor's property arrays: all `dimensions = 1`. | Follow |
| Assumptions for state other call targets change | *"If some call target makes an assumption about a stable value, but some other call target can modify that value, the call targets should use an `Assumption`."* | One `Assumption` per global cell (D7), the allocation reporter's (D13). `GlobalRef` reads a cell as a constant under its assumption. | Follow |
| Only runtime code behind `@TruffleBoundary` | *"Keep the core guest-language execution path in interpreter code that PE can see and simplify."* *"Put code that is not suitable for PE behind `@TruffleBoundary` as runtime code."* | About 300 boundaries: errors' messages, printing, bignums and rationals, strings' and ports' Java code, the reader and expander. | Follow, except: **`set!` of a global was a boundary as a whole**, a runtime call on every assignment in compiled code, though only invalidating the cell's assumption (once) is runtime code. Fixed in #201: a compiled loop of two `set!`s 0.57 times as long. (Mistake, fixed) |
| `@ExplodeLoop` only over PE-constant bounds, asserted | *"Use `CompilerAsserts.partialEvaluationConstant(...)` to assert that loop variables influencing unrolling are PE constant."* (and 25.5:bytecode_dsl/RuntimeCompilation.md: *"if your interpreter relies on a value being PE constant (e.g., to unroll a loop) it is a good idea to assert the value"*) | Three exploded loops, each over a constant: `BindArguments` (its constant operand's length), `Assoc` (its cached keys), the record constructor (its final arrays). None asserted it. | Mistake, fixed in #204: each asserts its bounds, so a non-constant one fails the compilation (which `//tests:unit_compile` treats as an error) instead of unrolling without bound. |
| Don't explode what doesn't fold | *"Loop explosion is most useful when the explosion reveals stable constants..."* | Each exploded loop exposes constants (a parameter's slot and whether it is boxed; a key; a field's property). Loops over data (variadic `+`, `vector`, `string<?`) aren't exploded. | Follow |
| `MERGE_EXPLODE` for bytecode dispatch | *"intended for use with bytecode interpreters"* | The Bytecode DSL generates the dispatch loop with it. | Follow (the DSL's) |
| Slow paths: `transferToInterpreterAndInvalidate` locally, `SlowPathException` to a caller | *"Use `CompilerDirectives.transferToInterpreterAndInvalidate()` when the slow-path check and fallback are local."* | DSL specializations; `PrimitiveRootNode`'s lazily made body; the trampoline's slot. No `SlowPathException`: no slow path aborts to a caller. | Follow |
| `VirtualFrame` stays virtual | *"Do not pass a `VirtualFrame` through polymorphic or `@TruffleBoundary` calls unless it is deliberately materialized."* | The trampoline takes the root's frame, inlined; a frame is materialized only by the yield (`SuspendFrame`, D12) and for a debugger's evaluation (`FrameEvalNode`), each deliberately. | Follow |
| `@CompilationFinal` fields fold only on constant receivers | *"partial evaluation can only fold loads of a PE-constant field if its receiver is also a PE constant"* | `SClosure.captured` folds for a constant closure (a global under its assumption, a fixed letrec* variable), and is an ordinary array read otherwise; design.md D2 says so. | Follow |

## Deoptimization cycle patterns

Source: [Deoptimization Cycle Patterns](https://www.graalvm.org/latest/graalvm-as-a-platform/language-implementation-framework/DeoptCyclePatterns/)
(25.5:DeoptCyclePatterns.md). None of our traces showed a cycle (above).

| Pattern | GraalVM says | What we do | Status |
|---|---|---|---|
| Non-stabilizing profiles | *"The number of possible deoptimizations ... caused by the condition involving a compilation final field should be small."* | Profiles are the DSL's (bounded by specializations and `limit`s). The trampoline's caches are bounded (4 and 8). | Follow |
| Call targets initialized lazily at a dispatch | *"initialize the call target at parse time or when the function is looked up for the first time"* | `SClosure` holds its `RootCallTarget`, made when the root is (`MakeClosure` asks a constant root). | Follow |
| Skipped exceptions in compiled code | *"ensure that skipped exceptions are never thrown from compiled code"*: `ArithmeticException`, `ClassCastException`, `IndexOutOfBoundsException`, `IllegalStateException`, ... | Fixnum overflow is the DSL's `rewriteOn = ArithmeticException` (one deoptimization, then the specialization is excluded); bounds and types are guards; errors are `SchemeError`, a Truffle exception (D8); `IllegalStateException` only for an internal error. | Follow |
| Stabilizing late | *"ensure that the optimizations stabilize more quickly"* | A first descent's compilation is declined until a call has returned (M9-01): Truffle retries; nothing deoptimizes. | Follow |

## Truffle DSL guidelines

Source: [Truffle DSL Guidelines](https://www.graalvm.org/latest/graalvm-as-a-platform/language-implementation-framework/DSLGuidelines/)
(25.5:DSLGuidelines.md).

| Recommendation | GraalVM says | What we do | Status |
|---|---|---|---|
| Avoid duplicated specializations | *"Avoid specializations with (almost) the same method body."* | `Call.doDirect` and `doIndirect` share a body but the call; `doIndirect` `replaces` `doDirect`, so partial evaluation sees one of them. Host code has both: `Call`'s node's `execute` is a host graph of 234 (`fib`'s host-inlining log). | Follow (PE); small in host code |
| Avoid duplicated calls to helpers | *"The PE process has to explore each call separately"*; *"common-out the calls if possible"* | `Intrinsics.IntrinsicNode.generic` called `primitive.invoke` in both branches of `if (tail)`, a constant operand. | Mistake (host code), fixed in #204: one call. |
| Don't mix `@Shared` and exclusive inline profiles in one specialization | *"inline nodes used in one `@Specialization` should be either all shared or all exclusive"* | `Call`'s two inline branch profiles are both `@Shared`. | Follow |
| Avoid unused large inline nodes | *"Avoid inlining large nodes that are used only on rarely executed code-paths."* | No large inline nodes; the rare spill path is behind a branch profile (M7-01). | Follow |

## Host compilation

Source: [Host Compilation for Interpreter Java code](https://www.graalvm.org/latest/graalvm-as-a-platform/language-implementation-framework/HostOptimization/)
(25.5:HostCompilation.md).

| Recommendation | GraalVM says | What we do | Status |
|---|---|---|---|
| `@BytecodeInterpreterSwitch` | *"Truffle host inlining is applied when compiling a method annotated with `@HostCompilerDirectives.BytecodeInterpreterSwitch`."* | The Bytecode DSL generates it on `continueAt`. | Follow (the DSL's) |
| Stay within the budget | *"If there are methods that exceed the budget limit, then the recommendation is to optimize such nodes by adding more PE boundaries."* | Measured (above): at most 7,898 of 100,000, no "too complex" cut. | Follow |
| `@InliningCutoff` for complex generic paths | *"a cutoff can be specified manually using the `@InliningCutoff` annotation"* | Not used: no path was cut for size. design.md M9-03 measured it on a call path that inlined a whole call (an experiment not kept). | Follow (not needed) |
| Deduplicate calls from branches that fold in PE | *"During host optimization, the `negate` field is not compilation final, and the compiler would either inline the code twice or decide not to inline"* | `IntrinsicNode.generic`'s two `invoke`s (above). | Mistake, fixed in #204 |
| Extract slow-path code into methods | *"It is therefore recommended to extract a single method for the slow-path part of the code"* | Errors are made in boundary helpers (`notAProcedure`, `arityError`, `unbound`, the areas' `error` helpers); 4 of the 44 `new SchemeError(...)` in the runtime and the builtins concatenate a message in place, in cold primitives (`expt`, `define-record-type`, a radix, `scheme-report-environment`). | Follow |

## The Bytecode DSL

Sources: 25.5:bytecode_dsl/UserGuide.md, 25.5:bytecode_dsl/Optimization.md,
25.5:bytecode_dsl/RuntimeCompilation.md, `GenerateBytecode`'s Javadoc at 25.5.

| Recommendation | GraalVM says | What we do | Status |
|---|---|---|---|
| Boxing elimination | *"A major source of overhead in interpreted code ... is boxing."* | `boxingEliminationTypes = {long, double, boolean}` (M5-06, M7-03). | Follow |
| ... but not for `boolean` | *"Note that `boolean` boxing elimination is supported, but is generally not worth the overhead of the additional instructions it produces."* (25.5:bytecode_dsl/Optimization.md) | `boolean` is in the set: predicates' operations return `boolean`, and an `if` on one branches without `Truthy` (M7-03); M8-04's `LoopTest` takes it. M7-03 measured the set, not `boolean` alone. | Deliberate, now measured: no difference either way (below); kept |
| `@ConstantOperand` for values that guide PE | *"Operands whose values guide partial evaluation (e.g., loop counters used to unroll loops) should be declared using `@ConstantOperand`."* | Global cells, local accessors and ranges, arity counts, the closure's root, `Assoc`'s alist. | Follow |
| Variadic operands are PE-constant up to 8 | *"a variadic operand only has a PE constant length up to a certain length (8)"* | Nothing unrolls over a variadic operand's length: `BindArguments` unrolls over its constant operand, not the array. | Follow |
| Uncached interpreter | *"It is strongly recommended to enable uncached execution, because it can reduce the footprint of your language and improve start-up times."* | Off. | Deliberate: design.md "No uncached interpreter (M8-04)": measured both ways; with roots uncached at first, splitting broke (a call site in the uncached tier has no call node), and with only run-once code uncached, nothing changed measurably. |
| `storeBytecodeIndexInFrame` only when needed | *"this feature should only be enabled for fast-path bci access outside of the current operation (e.g., for closures or frame introspection). Storing the bci in the frame increases frame size and requires additional frame writes, so it can negatively affect performance."* (`GenerateBytecode.storeBytecodeIndexInFrame`) | On, with each operation saying whether it stores (`storeBytecodeIndex`): calls and operations that can fail store it, the rest don't. | Deliberate: design.md D13 (locations exact across yields and in compiled code, for guest stack traces of suspended frames, M6-04). Its cost was never measured alone: a follow-up. |
| Source information and reparsing | *"Source information is designed to have no performance overhead until it is requested"* | Roots are parsed `WITH_SOURCE` (source sections from the start, for errors' locations, D13); tag instructions are added by reparsing when a tool attaches. | Follow |
| Serialization | *"allows a language to implement bytecode caching"* | The prelude image (D13, M5-03, M7-05). | Follow |
| Continuations | *"single-method continuations, whereby a root node is suspended and can be resumed"* | A custom `@Yield` at each call site (D12); frames copied for multi-shot. | Follow (extended: D12) |
| Builtins without call semantics | The Builtins tutorial's inlined builtins | Primitives run in place (M5-09), the hottest are operations (M7-03). | Follow |
| Threaded switch, quickening, instruction rewriting | Defaults on (`enableThreadedSwitch`, `enableQuickening`, `enableInstructionRewriting`) | Defaults. | Follow |
| Tail call handlers | 25.5:OneCompilationPerBytecodeHandler.md: register pressure in large dispatch methods; *"Tail call threading"* | Off (default). | Not measured: a follow-up for the native launcher (below) |

### `boolean` boxing elimination, measured

The same build with and without `boolean` in `boxingEliminationTypes`
(`main` with #204; everything else equal), interleaved fresh JVMs, the
launcher's options, an Apple M3 Max shared with other agents (load 3-6); the
median first iteration and the median of the later iterations' medians:

| Program | Mode, rounds | With `boolean` | Without |
|---|---|--:|--:|
| `fib` 30 | interpreter, 6 | 0.223 s, then 0.234 s | 0.233 s, then 0.232 s |
| `tak` 18 12 6 ×100 | interpreter, 6 | 0.595, then 0.453 | 0.591, then 0.480 |
| `nqueens` 10 | interpreter, 6 | 0.132, then 0.136 | 0.128, then 0.128 |
| `deriv` ×30,000 | interpreter, 6 | 0.271, then 0.234 | 0.264, then 0.228 |
| `puzzle` ×3 | interpreter, 6 | 0.594, then 0.111 | 0.587, then 0.114 |
| `fib` 40 | JIT, 4 | 0.759, then 0.607 | 0.771, then 0.615 |
| `tak` 40 20 11 | JIT, 4 | 1.948, then 1.823 | 1.948, then 1.823 |
| `puzzle` ×100 | JIT, 4 | 0.360, then 0.166 | 0.344, then 0.166 |
| `nqueens` ×10 | JIT, 4 | 4.666, then 4.474 | 4.766, then 4.502 |

Within 6% both ways in the interpreter (`tak` 6% slower without it,
`nqueens` 5% faster) and noise with the JIT: GraalVM's caution doesn't show
on these programs, and neither does a gain. `boolean` stays (the
predicates' operations and `LoopTest` are written for it); a measurement over
the report's interpreter subset, and of `continueAt`'s host compilation time
(the extra instructions' cost GraalVM means), would decide a change.

## Inlining, splitting, on-stack replacement

| Recommendation | GraalVM says | What we do | Status |
|---|---|---|---|
| Language-agnostic inlining, budgets | [Inlining](https://www.graalvm.org/latest/graalvm-as-a-platform/language-implementation-framework/Inlining/): inlining by *"the number of Graal nodes ... after partial evaluation"*, with exploration and inlining budgets | Truffle's defaults; no `DirectCallNode.forceInlining`. Halving the inlining budget and recursive inlining depth 0 were measured and rejected (design.md M5-04, "Not cut at the receiver"). | Follow |
| Report polymorphism, so Truffle splits | [Reporting Polymorphism](https://www.graalvm.org/latest/graalvm-as-a-platform/language-implementation-framework/splitting/ReportingPolymorphism/): `@ReportPolymorphism`, or `Node.reportPolymorphicSpecialize()`; *"Reporting only on Megamorphic Cases"* | `reportPolymorphicSpecialize()` when a call site's cache, or its first tail call's, goes megamorphic (M5-10, M7-01). | Deliberate: `@ReportPolymorphism.Megamorphic` on the operation doesn't compile in 25.5's Bytecode DSL (docs/upstream.md, `UpstreamReportPolymorphismTest`); the cache initializer reports instead. Reporting only megamorphic sites: measured in M7-01. |
| Splitting needs `isCloningAllowed` | 25.5:splitting/Splitting.md: a target is split *"provided there are no outstanding factors preventing it such as the root node not being allowed to be split"* | Bytecode DSL roots allow cloning; `PrimitiveRootNode` doesn't (the default), as call sites run primitives in place. | Follow |
| OSR for loops | [OSR](https://www.graalvm.org/latest/graalvm-as-a-platform/language-implementation-framework/OnStackReplacement/): *"Languages using standard Truffle APIs get OSR for free"*; `LoopNode` for AST loops | Bytecode loops: the DSL's bytecode OSR. The trampoline's tail calls: a `LoopNode` (#167), whose iterations also count toward its root (`LoopNode.reportLoopCount`). | Follow |
| `OSRCompilationThreshold` | Default 100,352 | Default; a lower one as the launcher's default was measured and rejected (D17, "The JIT warm-up"). | Follow |

## Other Truffle features

| Feature | GraalVM says | What we do | Status |
|---|---|---|---|
| Static Object Model | [Static Object Model](https://www.graalvm.org/latest/graalvm-as-a-platform/language-implementation-framework/StaticObjectModel/) | Records (D1, M7-04). | Follow |
| `TruffleString` | [Truffle Strings](https://www.graalvm.org/latest/graalvm-as-a-platform/language-implementation-framework/TruffleStrings/) | Symbol names. Strings are `SString` (Latin-1 or UTF-32 arrays, mutable). | Deliberate: D1 rejects `MutableTruffleString` (no compaction, conversion on every operation). |
| Profiles: inlined | `InlinedBranchProfile` and friends with `@Cached` | `InlinedBranchProfile` in `Call`; no non-inlined profiles anywhere. | Follow |
| `@TruffleBoundary(transferToInterpreterOnException)` | 25.5 `CompilerDirectives.TruffleBoundary`: default true, *"the caller's compiled code is invalidated and will not transfer to the interpreter on exceptions for this method again"* | The default: a boundary that throws a Scheme error invalidates its caller once. | Follow |
| Single-context code | `ContextReference`, `contextPolicy` | `EXCLUSIVE` (the default): a context is a compilation constant, and roots embed cells (D13). | Follow |

## Tools: what 25.5 calls the options

- Performance warnings: `--compiler.TracePerformanceWarnings=all` (or
  `call`, `instanceof`, `store`, `frame_merge`, `trivial`); 25.5 still takes
  `--engine.TracePerformanceWarnings` but warns that it is deprecated. With
  `--compiler.TreatPerformanceWarningsAsErrors` they fail the compilation,
  as `engine.CompilationFailureAction` says.
- `--engine.TraceTransferToInterpreter` is a VM-wide option on HotSpot: the
  process's first engine fixes it. That was the launcher framework's own, so
  `bazel-bin/scheme` ignored it with *"it was previously initialized with
  'false'"* until M10-04, which sets its system property first (design.md
  D17, "Options"): `--experimental-options --engine.TraceTransferToInterpreter`
  works in both launchers. `engine.TraceAssumptions` is the engine's own, and
  always worked as an option.
  On HotSpot it shows only explicit `transferToInterpreter` calls
  (25.5:Optimizing.md: *"prints a stack trace only for explicit
  deoptimizations"*).
- `--engine.CompilationFailureAction=Print` shows failed compilations and
  deoptimization cycles (*"Compilation failures are not printed by
  default"*).
- Host inlining: `-Djdk.graal.Log=HostInliningPhase,~CanonicalizerPhase,~GraphBuilderPhase -Djdk.graal.LogFile=FILE`
  in `JDK_JAVA_OPTIONS`, best with `--engine.Compilation=false`. It works
  with the pinned GraalVM's libgraal.
- `--compiler.InstrumentBoundaries` printed nothing through the launcher
  (to standard output or `--log.file`): libgraal fails every compilation it
  instruments (docs/upstream.md). Since M10-04 `bazel-bin/scheme` runs Graal
  compiled for HotSpot (`-XX:-UseJVMCINativeLibrary`) with it, and prints the
  boundaries' profile at exit.

docs/performance-research.md §5 lists the others.

## Further reading

The Partial Evaluation page's "Further reading", in order: what each is, what
it says for an implementation like ours, and where we stand.

1. **[Optimizing Truffle Interpreters](https://www.graalvm.org/latest/graalvm-as-a-platform/language-implementation-framework/Optimizing/)**
   (25.5:Optimizing.md). The workflow: profile with a sampling delay, look
   for deoptimizations, *"Enable performance warnings and list boundary
   calls"*, dump graphs at "After TruffleTier", look for `Invoke` nodes and
   control-flow splits; the tracing options; automatic deoptimization-cycle
   detection (since 25); compiler-level hot metrics
   (`-Djdk.graal.ReportHotMetrics`). Takeaway: run the warnings and the
   cycle detection on representative programs, which this audit did, and
   keep performance-warning checks as tests (#195's
   `PrimitivesInPlaceTest`). Status: follow; the tracing table in
   performance-research.md §5 now uses 25.5's names.
2. **[Deoptimization Cycle Patterns](https://www.graalvm.org/latest/graalvm-as-a-platform/language-implementation-framework/DeoptCyclePatterns/)**.
   Five patterns that deoptimize without end (above). Status: none found.
3. **[Truffle DSL Guidelines](https://www.graalvm.org/latest/graalvm-as-a-platform/language-implementation-framework/DSLGuidelines/)**.
   Minimize code duplication as PE sees it, and code size for native image.
   Status: one duplicated call fixed (#204).
4. **[Host Compilation for Interpreter Java code](https://www.graalvm.org/latest/graalvm-as-a-platform/language-implementation-framework/HostOptimization/)**.
   Host inlining of the bytecode switch within a budget; slow paths cut at
   `transferToInterpreter`, `inInterpreter`, boundaries; `@InliningCutoff`;
   deduplicate calls; outline slow paths. Status: within budget (above);
   one duplicated call fixed (#204).
5. **[Bytecode DSL runtime compilation](https://github.com/oracle/graal/blob/vm-25.5.5.0.3/truffle/docs/bytecode_dsl/RuntimeCompilation.md)**.
   `LoadConstant` isn't a PE constant, `@ConstantOperand` is; variadic
   lengths are constant up to 8; assert what must be constant; source
   information in compiled code. Status: follow; asserts added (#204).
6. **Würthinger, Wimmer, Humer, Wöß, Stadler, Seaton, Duboscq, Simon,
   Grimmer, [Practical Partial Evaluation for High-Performance Dynamic
   Language Runtimes](https://chrisseaton.com/truffleruby/pldi17-truffle/pldi17-truffle.pdf)**,
   PLDI 2017 (15 pages, read in full). The core primitives that make partial
   evaluation of an interpreter work: initiating PE per function (§3.1),
   method-internal invalidation with `PEFinal` fields and
   `transferToInterpreter` (§3.2, Figures 3-4), method-external invalidation
   with `Assumption`, which *"the compiler intrinsifies ... to a no-op"*
   (§3.3, Figure 5), explicit boundaries (§3.4; §5.1: *"all code that was not explicitly
   designed for PE should be behind a PE boundary"*),
   `inInterpreter` (§3.5), frames written from outside (§3.6); built on them,
   polymorphic inline caches as chains of PE-final entries (§4.1), typed
   locals made virtual by escape analysis (§4.2), loops compiled by OSR
   from the interpreter (§4.3). The compiler: escape analysis is *"the most
   important optimization"* (§5.3; without it, 4.4 to 36.9 times slower on average
   by language, Figure 11), assumptions *"about 2-3x"* (§7.3); specializations must
   stabilize (§7.4: 95% of functions after 10 invocations). It notes tail
   calls in Truffle are made by exceptions that the compiler turns into
   jumps (§6), and continuations by threads (§6). What it means for us, and
   where we stand: every primitive is what we use through the DSL (`final`
   and `@CompilationFinal`, `transferToInterpreterAndInvalidate`,
   `Assumption`, `@TruffleBoundary`, `inInterpreter`, inline caches as DSL
   specializations with `limit`s); escape analysis is why the `TailCall`
   and argument arrays must stay virtual (M5-10's caches, the argument
   hand-off's measured 2.4 times on `fib`, design.md); Figure 5's pattern is
   what `set!` of a global now follows (#201). Tail calls are ours: a
   `TailCall` returned to a trampoline (design.md "Calls and tail calls"),
   not exceptions, since an exception through host-compiled code deoptimizes
   it when its profile lacks the edge (design.md, M9-04's measurements).
   Continuations: Bytecode DSL yields and a segmented stack (D12), not
   threads. Predates the Bytecode DSL: the DSL generates the dispatch, the
   frame, OSR and the inline caches the paper writes by hand.
7. **Futamura, [Partial Evaluation of Computation Process—An Approach to a
   Compiler-Compiler](https://fi.ftmr.info/PE-Museum/PE-Revised1999.pdf)**,
   Higher-Order and Symbolic Computation 12 (1999), 381-391, the revised
   version of the 1971 paper (scanned; pages 381-390 read in full from
   rendered images, 391 is the references). Defines partial evaluation α(π, c′)(r′) = π(c′, r′) (p. 383, Eq.
   1), the first projection: specializing an interpreter `int` to a source
   program gives an object program, α(int, s′) (p. 383, Eq. 2), and the
   second, α(α, int), a compiler (p. 384, Eq. 3). Specializing pays when
   *"k/(2a + m) < l"* (p. 383): the cost of specializing must be repaid by
   the runs that use it. Its algorithm α₁ evaluates the branches it can,
   keeps those it can't, and stops when it reaches a branch with the same
   static values it has already specialized (operation 5, p. 386): *"In
   partially evaluating an interpreter with respect to a source program which
   contains loops or recursive calls, the above case occurs. Therefore,
   operations (2) and (5) are essentially important"*; *"if we describe an
   interpreter carefully, we can avoid such a meaningless loop"* (p. 388).
   For us: the first projection is what Truffle does per call target; the
   pay-off condition is Truffle's compilation thresholds and our choices not
   to compile code that runs once (D17's warm-up, M9-01's first descents,
   M8-04's rejected uncached tier, whose argument was the same trade-off);
   operation (5) is what `MERGE_EXPLODE` does for the generated dispatch
   loop. Status: provided by Truffle and the Bytecode DSL; nothing for us to
   implement.
8. **Jones, Gomard, Sestoft, [Partial Evaluation and Automatic Program
   Generation](https://pages.cs.wisc.edu/~horwitz/CS704-NOTES/PAPERS/JonesPartialEvaluation.pdf)**
   (Prentice Hall, 1993; 425 pages). Read: the preface, the contents, chapter 1
   (introduction and critical assessment), 6.1 (its end) to 6.3 (speedup), 7.1-7.2
   (online and offline), 10.3-10.5 (Similix, a partial evaluator for Scheme:
   avoiding duplication, call unfolding, continuation-based reduction), 12
   (binding-time improvements), 13.2 (when PE is of benefit) and 14.1
   (termination of online PE); not read: the rest (Parts I-III's mini-languages,
   flow-chart and first-order specializers, self-application, the
   lambda-calculus, Prolog and C specializers, Part V's analyses), which by
   the contents and chapter 1 are about building offline and self-applicable
   specializers, which Truffle is not.
   What applies:
   - *"the efficiency of mix-generated target programs depend crucially on
     how the interpreter is written. For example, if the interpreter uses
     dynamic name binding, then generated target programs will have run time
     variable name searches"* (p. 16). We resolve names at expansion time:
     locals are frame slots, globals cells that compiled code reads as
     constants under their assumption (D7). Follow.
   - Online versus offline (ch. 7): an online specializer decides from the
     values it sees; *"Online partial evaluators can thus perform some
     static computations which offline strategies would rule dynamic"*
     (p. 147). Truffle is online (PE constants are values, profiles are
     observed) with an offline element, the boundaries the implementer
     annotates; the PLDI paper (§5.1) says it *"follows the standard
     approach for online PE"*.
   - "The Trick", bounded static variation (§12.2, p. 266): when a dynamic
     value is one of a finite set of static ones, compare it with each and
     continue with the static one. That is an inline cache: our call sites'
     4-entry caches on call targets, the trampoline's, the DSL's guards with
     `limit`s, `Assoc`'s and `ConstantMember`'s quoted datums (M8-05, M9-05).
     Follow (the DSL provides it).
   - Avoid duplication (§10.3, §13.2: *"well-written interpreters do not
     contain call duplication. ... A poorly written interpreter might
     contain two calls to evaluate E or to perform Command, giving target
     code duplication"*, p. 293). The same rule as the DSL guidelines'; we
     had one such pair in host code (#204), and design.md M7-03 rejected
     emitting an argument's code twice for this reason.
   - CPS as a binding-time improvement, and its risk (§12.3, p. 270:
     *"indiscriminate CPS conversion is to be avoided"*; accumulating
     continuations make PE loop, p. 272). We don't CPS-convert: continuations are captured from
     direct-style frames (D12, which rejects full CPS conversion because it
     gives up frame virtualization).
   - Termination of online PE (§14.1: *"Unfolding too liberally can cause
     infinite specialization-time loops"*, p. 298): Truffle's inlining
     budgets and recursion depth, and `MERGE_EXPLODE`'s merging. Not ours to
     tune (design.md measured the budgets, M5-04).
   - Speedup is linear (§6.2): PE removes interpretation overhead by a
     constant factor; it doesn't change a program's complexity. So a
     benchmark's speedup over the interpreter is bounded by how much of its
     time is interpretation, which is what the M5 research found (the
     interpreter about 15-20 times slower than compiled code on `fib`, performance-research.md §2).
   Status: the techniques that apply are what Truffle and the Bytecode DSL
   provide; none calls for code of ours beyond what this audit fixed.

The page also links, in its body, [the DSL guidance](https://www.graalvm.org/latest/graalvm-as-a-platform/language-implementation-framework/DSLGuidelines/) and
[Truffle Libraries](https://www.graalvm.org/latest/graalvm-as-a-platform/language-implementation-framework/TruffleLibraries/)
(*"refer to their Javadoc, the DSL guidance, and the Truffle Libraries
documentation"*). Truffle Libraries are for dispatch over receiver types
across languages (interop): we export `InteropLibrary` messages for Scheme
values (D10) and use no libraries of our own, since our values' dispatch is
DSL specializations on their Java classes. Follow.

## Fixes

| PR | Change | Evidence |
|---|---|---|
| #201 | `set!` of a global: a store, with only the first invalidation behind a boundary | A loop of two `set!`s: 0.124 s → 0.071 s an iteration; `puzzle`, `triangl`, `browse` unchanged; `bench-check` within thresholds |
| #204 | `CompilerAsserts.partialEvaluationConstant` on the three exploded loops' bounds; one `primitive.invoke` call in `IntrinsicNode.generic` | Every unit test under `//tests:unit_compile` (compile immediately, bailouts are errors) |
| #208 | A port's direction and kind are `final` fields of `SPort`, and each of its operations a `final` method behind a boundary that calls the kind of port's own (design.md D10, "Ports in compiled code") | Performance warnings, `main` → this: ecraven's `scheme` 30 → 0, `cat` 25 → 0, `wc` 18 → 0, `tail` 24 → 0, `read1` 2 → 0 (`IoTest.portOperationsCompileWithoutPerformanceWarnings`: none for every port operation on every kind of port). Later iterations, interleaved: `cat` 0.84 times as long, `wc` 0.83, `read1` 0.94, `scheme`, `string`, `tail` unchanged; `read-char` from a string 0.57 |

## Follow-ups

- **Splits that deoptimize once** (`scheme`): each of 8 splits of one closure
  deoptimized once after its first compilation, by an uncommon trap that
  isn't an explicit `transferToInterpreter`. Done in M10-06 (design.md D1,
  "Representations chosen outside loops", which also says how to read the
  graph dump without IGV).
- **`boolean` boxing elimination**: measured inconclusive here (above); the
  report's interpreter subset and `continueAt`'s host compilation time would
  decide whether to follow GraalVM's advice and drop it.
- **`storeBytecodeIndexInFrame`**: measure the interpreter with it off where
  nothing needs it, against D13's locations of suspended frames.
- **Tail call handlers** (`enableTailCallHandlers`) for the native launcher's
  interpreter: measure natively.

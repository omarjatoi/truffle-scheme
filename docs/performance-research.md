# Performance research: GraalVM 25.5 and Truffle

Research for M5, written before the M5-01 baseline lands. It lists what in
GraalVM and Truffle could make the interpreter and the JIT faster, how each
idea maps onto our code, and what each risks for R7RS semantics. Rankings are
provisional until M5-01 and M5-02 produce numbers. Re-rank against the
baseline before scheduling anything.

The version is pinned (design.md D11): GraalVM CE 25.5, Truffle
25.5.5.0.3. The online Truffle docs track `master`, which is 25.6. Where a
feature exists only in 25.6, this document says so. It is not available to
us without a version bump, which is a separate decision.

Nothing here changes a decision in design.md. Items that would touch a
decision (D1, D2, D5, D13) or an AGENTS.md rule say which one.

---

## 1. How we execute today

Read from the code on `main` at `6f341f3`:

- **Every procedure is its own Bytecode DSL root** (`SchemeRootNode`). The
  config is `storeBytecodeIndexInFrame = true` and
  `enableTagInstrumentation = true`. Nothing else is enabled: no boxing
  elimination, no uncached tier, no serialization. The generated
  interpreter already uses the threaded switch (default since 25.1) and
  supports bytecode OSR on backward branches (`CachedBytecodeNode implements
  BytecodeOSRNode` in `SchemeRootNodeGen`). It allows cloning
  (`isCloningAllowed() == true`).
- **Non-tail call** (`CallSiteEmitter`): `Call(proc, args…)` → `IsCapture` →
  `SuspendFrame` (`@Yield`). `Call.doDirect` is a 4-entry inline cache on
  `callTarget` with a `DirectCallNode`, and `doIndirect` handles everything
  else. Per call it also does the following:
  - increments and decrements `context.callDepth` (spill check, threshold 50)
  - copies the arguments: `@Variadic` array → `withProcedure` copy
  - catches `SchemeError` for `raiseHere`
  - runs the **trampoline**, which performs every `TailCall` that comes back
    through an **`IndirectCallNode`** (`SchemeRootNode.trampoline`)
- **Tail call**: `Return(MakeTailCall(proc, args…))` allocates a `TailCall`
  and an argument array. The caller's trampoline performs it. A self tail
  call with matching arity becomes a `While` loop instead (`IsSame` check).
- **Primitives** (`car`, `+`, `vector-ref`, …) are `SClosure`s over a
  `PrimitiveRootNode` call target that wraps a `PrimitiveNode`. A call to a
  primitive is an ordinary Scheme call: the full `Call` path, a Truffle
  call, a frame, and a third argument copy (`Arrays.copyOfRange` in
  `PrimitiveRootNode.execute`). There is **one `PrimitiveNode` per
  primitive per context**, shared by every call site. Its type profile, for
  example `AddNode`'s, is global.
- **Globals**: `GlobalRef` folds to a constant under the cell's
  `Assumption` (D7). This works as intended.
- **Closures**: `SClosure { RootCallTarget callTarget; Object[] captured }`.
  `captured` is a plain array, so reads from it never constant-fold, even
  when the closure itself is a compilation constant.
- **Continuations**: the yield path and frame copies (`PromptDriver.copy`)
  are only paid on capture. M0-03 showed that the call → `IsCapture` →
  `SuspendFrame` sequence compiles to the same code as a plain call when the
  callee's return type profile is monomorphic.

## 2. Preliminary measurements (not the baseline)

These are ad hoc runs on one development machine (macOS, Apple silicon) with
the JIT on and default engine options. Each program times 15 in-process
iterations, and the table shows the steady-state range. The programs are
in the appendix. They exist to test hypotheses, not to report performance.

| Program | Ours (JIT) | Ours (interpreter only) | chibi-scheme |
|---|---:|---:|---:|
| `fib 27`, `+` in tail position (normal code) | 14–16 ms | 220–300 ms | 16–19 ms |
| `fib 27`, `+` moved out of tail position | **9 ms** | | 41 ms |
| `fib 27` (non-tail `+`) after **one** fixnum overflow elsewhere | **24–29 ms** | | |
| 3M mutual tail calls (`even?`/`odd?`) | 31–39 ms | | 57 ms |
| build a 200 000-element list by non-tail recursion | 27–45 ms | | 7–14 ms |
| same, spill depth 1 000 000 and `-Xss2g` (no spills) | 27–45 ms | | |
| `hello` (JVM start to exit) | 0.46 s | | |

What the runs and the compilation traces show:

1. **Tail calls to primitives are never inlined.**
   `--compiler.TraceInlining` for `fib` shows `<` and `-` inlined in tier 2,
   but `+` doesn't appear in the call tree at all. `+` is in tail position,
   so `fib` returns a `TailCall` and the *caller's* trampoline performs it
   through an `IndirectCallNode`. Moving the `+` out of tail position makes
   `fib` 1.6× faster (15 → 9 ms). In Scheme, the last call of a procedure body
   is very often a primitive (`+`, `cons`, `not`, `car`, …), so this
   pattern is everywhere.
2. **Tier 1 inlines nothing.** Tier-1 `fib` reports `Inlined 0Y 5N`.
   Tier 1's default inlining policy is `TrivialOnly`, and no root of ours
   says it is trivial.
3. **One fixnum overflow anywhere makes fixnum arithmetic slow everywhere.**
   `AddNode.doLong` uses `rewriteOn = ArithmeticException`. The generated
   code (`ArithmeticNodesFactory`) sets `SpecializationExcluded` for good on
   the first overflow, and from then on every `long + long` goes to
   `doGeneric`, through `Arithmetic.apply` behind a `@TruffleBoundary`.
   Because the node is shared by the whole context (§1), a single
   `(+ big big)` slows `fib` down 2.7× (9 → 25 ms).
4. **The interpreter is about 15× slower than chibi's** on `fib` (250 ms
   against 17 ms). Each `fib` call makes five Truffle calls: itself plus
   four primitive calls, each with a frame and three argument-array copies.
5. **The stack-depth spill is not a bottleneck at 200 000 frames.** Turning
   it off (huge threshold, huge stack) changed nothing measurable, so
   retuning `SPILL_DEPTH` is low priority.

## 3. Ranked opportunities

Impact is a guess, to be confirmed by the baseline. **S/M/L** effort means
roughly a day, a few days, or a week or more for one agent.

| # | Opportunity | Impact | Effort | Semantic risk |
|---:|---|---|---|---|
| 1 | Fixnum overflow without losing the `long` fast path | high, after any overflow | S | none |
| 2 | Run leaf primitives in place in tail position | high (1.6× on `fib`) | S–M | low; rule 2 wording |
| 3 | Primitives as per-call-site nodes inside `Call`, not call targets | very high in interpreter, high JIT | M | low; D5/D13 notes |
| 4 | Inline cache in the trampoline (direct calls for `TailCall` targets) | high for tail-call-heavy code | S–M | none |
| 5 | Report polymorphism so Truffle splits higher-order prelude procedures | high for `map`/`for-each`/`apply` code | S–M | none |
| 6 | Drop the argument-array copies (`@Variadic(startOffset = 1)`) | medium (interpreter), low–medium (JIT) | S | none |
| 7 | `SClosure.captured` as `@CompilationFinal(dimensions = 1)` | medium (records, prelude closures) | S | low (immutability) |
| 8 | `isTrivial()` on leaf `PrimitiveRootNode`s (stopgap for 3) | medium in tier 1 | S | none |
| 9 | Boxing elimination: `boxingEliminationTypes = {long, double, boolean}` | medium (interpreter) | S to enable, M to exploit | low (frame copies) |
| 10 | Uncached interpreter (`enableUncachedInterpreter`) | startup and footprint | M | low |
| 11 | Fixed-arity fast paths and `@ExplodeLoop` in variadic primitives | low–medium | S | none |
| 12 | Engine tuning (`InliningRecursionDepth`, budgets, `Mode`) | unknown, benchmark-specific | S | none |

Already planned, with notes below: M5-03 serialization, M5-04
escape-only `call/cc`, M5-05 Fixing Letrec and closure-free `let`, M5-06
vector strategies, M5-07 `equal?`.

### 1. Fixnum overflow without losing the fast path

- **What.** Keep `doLong` for the common case, and add a specialization
  that handles overflow inline instead of excluding `long` for good. For
  example, a `doLongOverflow(long, long)` that `replaces` `doLong` and
  calls `Math.addExact`, promoting to `SBigInteger` in a `@TruffleBoundary`
  only on the overflow branch. The same applies to `SubNode` and `MulNode`,
  and to any other `rewriteOn = ArithmeticException` (grep for it).
- **Evidence.** Table row 3 (2.7×). The generated `executeAndSpecialize`
  sets `SpecializationExcluded`. Truffle DSL boxing-overload detection
  (24.2, GR-32682) lets a `long`-returning and an `Object`-returning
  specialization share state.
- **Where.** `src/builtins/scheme/builtins/ArithmeticNodes.java`.
- **Risk.** None. D1's canonical forms are unchanged (overflow promotes,
  results that fit are demoted).
- **Why first.** It's tiny, and until it lands, any benchmark that
  overflows once (`pi`, `chudnovsky`, factorial-style warm-ups, the test
  harness) measures the slow path for all fixnum arithmetic and hides every
  other improvement.

### 2. Leaf primitives in tail position run in place

- **What.** When a tail call's procedure is (at run time) a primitive, call
  it right there and return its result, instead of allocating a `TailCall`
  for the caller's trampoline. A specialization on `MakeTailCall` (or a new
  tail-call operation the code generator emits instead) guarded by "the
  callee's root is a `PrimitiveRootNode`" calls it through a cached
  `DirectCallNode`, or runs the node directly once item 3 lands, and returns
  whatever it returns.
- **Why it's safe.** Primitives are leaves: they never call Scheme (rule 1,
  D5), so running one in the callee's frame cannot grow the Scheme stack
  without bound (rule 5 holds). Whatever they return is already a valid
  frame result:
  - a value
  - a `TailCall` (from `apply`, `call-with-values`, …): the caller's
    trampoline performs it, as today
  - a `Capture` (from `call/cc`): the caller's `IsCapture` suspends the
    caller, and this frame is gone, as it is today
  
  A `SchemeError` thrown in place propagates out of this frame to the
  caller's `Call`, which raises it there (`raiseHere`, D8). That is the same
  frame that raises it today, because today the call happens from the
  caller's trampoline.
- **Evidence.** `fib` table rows 1–2 (1.6×) and `TraceInlining` (`+`
  missing from the call tree).
- **Where.** `SchemeRootNode.MakeTailCall`, `CodeGenerator.call`.
- **Rules.** Rule 2 ("never emit a raw call operation") is about non-tail
  call sites. This is a tail site whose result is returned. The PR should
  say so and, if reviewers want, add one sentence to AGENTS.md rule 2 and
  design.md "Calls and tail calls". Torture mode is unaffected: primitives
  are not tortured.

### 3. Primitives as per-call-site nodes (no call target)

- **What.** Add a specialization to `Call` (and to item 2's tail path):
  `guards = "procedure == cachedPrimitive"`, with a cached `PrimitiveNode`
  created from the primitive's `PrimitiveSpec` and adopted by the
  operation. It checks arity, executes the node on the variadic array, and
  runs the trampoline only when the result is a `TailCall`. The result
  goes through the call site's `IsCapture` as before, because `call/cc`
  returns a `Capture`. The guard holds the primitive's closure.
  Redefining `car` in the interaction environment fails the guard and falls
  back to a normal call, so D7 is unchanged. When `GlobalRef` folds the
  procedure to a constant, the guard folds too.
- **Evidence.**
  - The Bytecode DSL Builtins tutorial shows exactly this
    (`InlineBuiltin`, "use builtins directly in the code without call
    semantics"), noting the one trade-off: the builtin doesn't appear in
    stack traces.
  - Table row 4: five Truffle calls per `fib` call, about 15× slower than
    chibi interpreted.
  - Per-site nodes also fix **profile pollution**: today one `AddNode`
    serves every `+` in the program and the prelude, so a single flonum
    `+` anywhere makes every site polymorphic. Truffle can't split it
    either, because `PrimitiveRootNode.isCloningAllowed()` is `false` (the
    `RootNode` default) and nothing reports polymorphism.
- **Where.** `SchemeRootNode.Call`, `PrimitiveRootNode`, `PrimitiveNode`,
  `PrimitiveSpec`, and `CallSiteEmitter` (unchanged shape).
- **Risk.**
  - Primitive frames vanish from guest stack traces. Errors already name
    the primitive and are raised in the caller's frame (D8), so D13's
    locations are unchanged.
  - Rule 1 is unaffected: the nodes still don't call Scheme.
  - D5 says builtins return `TailCall` requests to "their call site's
    trampoline", which is still what happens.
- **Effort.** M. `PrimitiveNode`s are hand-written (not DSL). Moving the
  hottest ones (`car`, `cdr`, `cons`, `+`, `-`, `<`, `=`, `eq?`,
  `vector-ref`, `null?`, `pair?`, `not`) to DSL nodes with typed
  specializations is a natural follow-up, and it is what makes item 9 pay
  off.

### 4. Inline cache in the trampoline

- **What.** `trampoline()` performs every `TailCall` through one
  `IndirectCallNode`, so a procedure reached by a tail call is never
  inlined into anything. That covers mutual recursion, CPS code, state
  machines, and any `(define (f x) (g …))`. Its `TailCall` and argument
  array are also never scalar-replaced. Give the trampoline a small
  polymorphic cache (for example a DSL node with `doDirect(callTarget ==
  cached, limit = 4)` → `DirectCallNode`, falling back to indirect), owned
  by the `Call` operation. That makes it per call site. Do the same for
  `PromptDriver.callWithProcedure`, a colder path.
- **Evidence.**
  - The Truffle inlining docs: an *Indirect* call "cannot be inlined".
  - Our `TraceInlining` output: tail-called targets never appear.
  - Prior art: Mumbler (a Lisp on Truffle) runs its tail-call retry loop
    at the call site and re-dispatches each tail call through an inline
    cache of `DirectDispatchNode`s, falling back to an `IndirectCallNode`
    only past the cache limit.
- **Where.** `SchemeRootNode.trampoline`, `Call`, `PromptDriver`.
- **Risk.** None: same calls, same order, same stack depth. A self-loop in
  the trampoline (`f` tail-calls `f`, already a `While` when the arity
  matches) is unaffected.
- **Also consider.** `LoopNode.reportLoopCount` in the trampoline loop,
  so a root that runs a long tail-call chain gets compiled. Today the
  trampoline is a plain Java loop inside an operation, invisible to OSR.

### 5. Splitting for higher-order prelude procedures

- **What.** `map`, `for-each`, `vector-map`, `string-for-each`, `apply`,
  `dynamic-wind`, `call-with-values` and friends are Scheme (D5). Each has
  one `Call` cache for its `f` across every caller in the program. Past 4
  targets the cache goes megamorphic (`doIndirect`), and no caller's
  lambda is inlined into the loop again. Truffle fixes this by
  **splitting** the callee per call site, but only when a node *reports
  polymorphism*. The generated `SchemeRootNodeGen` contains no
  `reportPolymorphicSpecialize` calls, so we never split. Add
  `@ReportPolymorphism` (or `@ReportPolymorphism.Megamorphic`) to `Call`.
  Check that the Bytecode DSL honours it on an operation class; if not,
  call `node.reportPolymorphicSpecialize()` when `doIndirect` first
  activates. Then confirm with `--engine.TraceSplitting`.
- **Evidence.** The Truffle splitting guide (polymorphism reporting is the
  only trigger) and the generated code. This matters more for us than for
  most Truffle languages, because our whole library layer is Scheme.
- **Note.** 25.6 reworks splitting (GR-79412: clone during compilation
  preparation, and the guide now mentions "calls cached by Bytecode DSL
  operations"). On 25.5, verify with `TraceSplitting` that `DirectCallNode`s
  cached inside DSL operations are counted as known callers.
- **Risk.** None for semantics. Splitting grows the code (bounded by
  `SplittingGrowthLimit`), so watch compile time.

### 6. Fewer argument copies

- **What.** Today a call copies its arguments up to three times:
  1. the `@Variadic` array
  2. `withProcedure` (prepending the closure)
  3. `Arrays.copyOfRange` in `PrimitiveRootNode`

  `@Variadic(startOffset = 1)` (25.0, GR-57063) reserves slot 0 for the
  procedure, so `Call` and `MakeTailCall` can fill it in instead of
  copying. Primitives can read from index 1 instead of copying. Item 3
  removes the third copy anyway.
- **Evidence.** The Bytecode DSL changelog for `startOffset`. In compiled
  code escape analysis removes these arrays when the callee is inlined,
  but not across an indirect or non-inlined call. The runtime compilation
  guide notes that a variadic operand's length is a PE constant only up to
  8 elements.
- **Where.** `SchemeRootNode.Call`, `MakeTailCall`, `withProcedure`,
  `PrimitiveRootNode.execute`.

### 7. Constant-folding closure contents

- **What.** Annotate `SClosure.captured` with
  `@CompilationFinal(dimensions = 1)`. When the closure is a PE constant
  (any global procedure under its assumption, every record accessor from
  `define-record-type`, prelude closures), reads of its captured values
  fold. The record type and field index in `%record-ref type i record`
  become constants.
- **Risk.** Correct only if a captured array is never written after
  `MakeClosure`. That holds today: mutable variables live in `Box`es (D2),
  and the box reference itself is what's captured. A unit test should pin
  that down.
- **Where.** `src/runtime/scheme/runtime/value/SClosure.java`.

### 8. `isTrivial()` for leaf primitives

- **What.** Override `RootNode.isTrivial()` to return `true` in
  `PrimitiveRootNode` for primitives whose node is small and never
  returns `TailCall`/`Capture`. Trivial roots are force-inlined, also in
  tier 1, whose default policy is `TrivialOnly`.
- **Evidence.** Tier-1 `fib` inlined nothing (`0Y 5N`).
- **Note.** This is a one-line stopgap. Item 3 makes it moot for call
  sites that see a primitive.

### 9. Boxing elimination

- **What.** `@GenerateBytecode(boxingEliminationTypes = {long.class,
  double.class, boolean.class})`. Locals, constants, `Truthy` → branch and
  typed operations then pass primitives unboxed in the interpreter, through
  automatic quickening. SimpleLanguage's Bytecode DSL interpreter uses
  `{long.class, boolean.class}`.
- **Evidence.** The Bytecode DSL Optimization guide: boxing is "a major
  source of overhead in interpreted code", and BE "can also improve
  compiled performance, because Graal is not always able to remove
  box-unbox sequences".
- **Risk.** Frames copied on resume (D2, `PromptDriver.copy`) now hold
  primitive slots with tags. `Frame.copyTo` copies both, and torture mode
  resumes every segment twice from copies, so run `bazel run //:r7rs -- test --torture`
  and the M0-02 tests under BE before merging. D1's `eq?` on fixnums is
  already by value.
- **Effort.** Enabling it is S. It pays off once arithmetic and predicates
  are typed operations (item 3), so schedule it after item 3.

### 10. Uncached interpreter

- **What.** `enableUncachedInterpreter = true`: code starts in an uncached
  tier with no profiling allocation, and moves to cached after
  `defaultUncachedThreshold` calls or loop iterations (16 by default in
  the tutorial). The Bytecode DSL guide "strongly recommend[s]" it for
  footprint and startup.
- **Where it helps.** Library bodies and prelude code that run once per
  context, so context creation, the unit tests (thousands of contexts), and
  short programs. It complements M5-03 (serialization).
- **Cost.** Every operation must support uncached execution. The DSL
  checks this. `Call.doDirect` (with a `limit`) is excluded from uncached
  by default, and `doIndirect` covers it. `SuspendFrame` and the
  continuation path work in the uncached tier: the generated code already
  has a branch for continuation roots in `UNCACHED`. Run torture mode.

### 11. Fixed-arity primitive fast paths

`Add`, `Sub`, `Mul` and `Compare` loop over a variadic array. Once they are
per-site nodes (item 3), cache the argument count and use `@ExplodeLoop`
(the runtime compilation guide's `cachedLength` pattern), or add
two-argument specializations. This mostly helps the interpreter.

### 12. Engine tuning (measure, don't ship blind)

Candidates for benchmark runs, not for default changes without data:

- `--compiler.InliningRecursionDepth` (default 2). Recursive Scheme
  procedures (`fib`, `tak`, tree walks) hit it.
- `--compiler.InliningExpansionBudget` and `--compiler.InliningInliningBudget`
  (12000 each).
- `--engine.Mode=throughput` and `--engine.MultiTier=false`, for the peak
  numbers in M5-02.

Since 25.4 (GR-79418), guest inlining uses Graal IR call-site frequencies
instead of call counters, so wrong branch probabilities now cost peak
performance. Don't add `injectBranchProbability` without evidence.

### The planned M5 tasks, from this angle

- **M5-03 serialization**: it pairs with item 10. Measure context creation
  with both.
- **M5-04 escape-only `call/cc`**: `unwindExceptions` (25.5, GR-79562) is
  the right mechanism. It bypasses guest `TryCatch` handlers, runs
  `finally` cleanup, and keeps exception identity across suspension.
- **M5-05 Fixing Letrec and closure-free `let`**: today a named `let`
  allocates a closure and a `Box` per entry, and every iteration reads the
  box for `IsSame`. After M5-05 these loops are plain `While` loops, which
  the generated interpreter already OSR-compiles. `or` (`let` + `if`) also
  gets cheaper. A `@ShortCircuitOperation` for `or`/`and` adds little
  after that.
- **M5-06 vector strategies**: these need typed vector operations, which
  are easiest after item 3.
- **M5-07 `equal?`**: no Truffle-specific angle beyond keeping it behind a
  boundary and iterative (rule 4).

## 4. Checked and not recommended now

- **Stack-depth spill tuning** (`SPILL_DEPTH = 50`). No measurable gain at
  200 000 frames (§2). The per-call `callDepth` increment and decrement is
  a context-field write in compiled code. Revisit only if a profile
  points at it.
- **`enableTailCallHandlers`**. Despite the name, this is about how the
  host-compiled interpreter dispatches its own handlers (design.md says
  so), and tail-call threading pays off on native image (One Compilation
  per Bytecode Handler guide). Low value on HotSpot.
- **Threaded switch and OSR.** Both are already on by default in the
  generated interpreter. Nothing to do.
- **Static Object Model for records** (D1 deferral). With item 7, a record
  access is a type check plus an array load at a constant index. SOM would
  remove one indirection. Revisit if a record-heavy benchmark profiles hot
  there. (Done in M7-04: design.md D1, "Records".)
- **`TruffleString` for strings.** D1 rejects `MutableTruffleString` for
  good reasons, and the current `SString` already has O(1) indexing and
  Latin-1 compaction. Symbols already use `TruffleString` names.
- **Bytecode index storage.** `storeBytecodeIndexInFrame = true` plus
  `storeBytecodeIndex` on `Call` writes the BCI into the frame per call. In
  compiled code the frame is virtual, so it costs little. 25.6 adds
  `BytecodeIndexMode` (`MANUAL`/`NONE`) and explicit-BCI call overloads
  (GR-72833), which a future version bump could adopt. Not available on
  25.5.
- **Auxiliary engine caching.** Oracle GraalVM and native image only. Not
  applicable to CE on HotSpot.
- **Native image + PGO.** D10 leaves native image to M6 (optional). It is
  the big lever for startup (0.46 s for `hello` now), not for peak
  performance.
- **Superinstructions and tracing-driven quickening.** The Optimization
  guide marks both "not yet supported" in the Bytecode DSL.

## 5. Tooling

The `scheme` launcher (`bazel-bin/scheme`, D17, M6-01) takes engine and compiler
options directly: `bazel-bin/scheme --experimental-options --engine.TraceCompilation file.scm`.
The commands that run `SchemeMain` themselves (`r7rs-diff`, benchmarks) take
them as JVM flags: pass `-Dpolyglot.engine.AllowExperimentalOptions=true` to the
JVM, then any engine or compiler option as `-Dpolyglot.<option>=…`:

| Question | Option |
|---|---|
| What compiles, when, how big; deopts and invalidations | `engine.TraceCompilation=true` (`opt done`, `opt deopt`, `opt inv.`) |
| What got inlined and why not | `compiler.TraceInlining=true` (`Inlined`/`Expanded`/`Cutoff`/`Indirect`) |
| Where code is still generic | `engine.TraceCompilationPolymorphism=true` |
| Calls, `instanceof`, stores PE couldn't resolve | `engine.TracePerformanceWarnings=all` |
| Who invalidated an assumption (global redefinition) | `engine.TraceAssumptions=true` |
| Deopt loops | `engine.TraceTransferToInterpreter=true`, `engine.TraceBytecodeTransition=transferToInterpreter` (25.1) |
| Splitting | `engine.TraceSplitting=true` |
| Boundaries hit from compiled code | `engine.InstrumentBoundaries=true` |
| Interpreter instruction mix | `engine.BytecodeHistogram=true` (25.1; the place to look before BE or quickening) |
| Hot guest procedures | `--cpusampler --cpusampler.Delay=<ms> --cpusampler.ShowTiers=true` (needs the tools on the module path, D17) |
| Compiled graphs | `-Djdk.graal.Dump=Truffle:1` (`:2` for every phase), then open `graal_dumps/` in IGV and look at "After TruffleTier" |
| Interpreter only | `engine.Compilation=false` (what `bazel run //:r7rs -- test` uses) |

Host-level profilers (JFR, async-profiler) are useful for interpreter
time. Marr's VTune study shows how much of a Truffle bytecode interpreter's
time goes to dispatch plumbing rather than instruction semantics.

## 6. Hill-climbing method

1. **Baseline first.** Take M5-01's `benchmark --json` output on `main` (JIT and
   interpreter-only), on a quiet machine. Record the GraalVM version and
   the JVM flags.
2. **A working subset.** Pick 10–15 programs from the two suites, one or
   two per shape:
   - call-heavy: `fib`, `tak`, `ack`
   - tail calls and CPS: `cpstak`, `ntakl`
   - continuations: `ctak`, `fibc`
   - higher-order and lists: `deriv`, `destruc`, `nqueens`
   - flonum: `fibfp`, `mbrot`, `sumfp`
   - bignum: `pi`, `chudnovsky`
   - strings and I/O: `string`, `cat`
   - vectors: `puzzle`, `quicksort`
   - allocation and GC: `nboyer`, `gcbench`

   These are ecraven's names; add Larceny's where they differ.

   Iterate on the subset and confirm on the full set before merging.
3. **One change per PR.** It should cite its item here and report before
   and after on the subset (and the full set in the PR), for both JIT and
   interpreter.
4. **Interleave runs** (A B A B), at least 5 measured iterations after
   warm-up per run. Fix `-Xms`/`-Xmx`. Compare means with confidence
   intervals and treat anything inside the noise as no change. These
   ad hoc runs showed ±30 % jitter on single iterations.
5. **Explain every win.** Check `TraceCompilation` (fewer deopts, smaller
   code), `TraceInlining` (the expected callee now `Inlined`) or IGV.
   Then the next change builds on a mechanism, not on luck.
6. **Semantics gate.** Run `bazel test //...` plus
   `bazel run //:r7rs -- test --compile` and `--torture` for anything in `src/runtime`
   or a DSL operation (AGENTS.md). Items 2, 3, 4 and 9 change the call
   path and need torture runs in particular.
7. **Ratchet.** M5-08 turns the final numbers into CI thresholds.

## Appendix: the programs behind §2

Each was timed with `(current-jiffy)` around one call, 15 times in one
process:

- `fib`:
  `(define (fib n) (if (< n 2) n (+ (fib (- n 1)) (fib (- n 2)))))`,
  `(fib 27)`.
- `fib` with `+` out of tail position:
  `(define (fib n) (define r (if (< n 2) n (+ (fib (- n 1)) (fib (- n 2))))) r)`.
- The overflow variant prints `(+ 4611686018427387904 4611686018427387904)`
  once before the timing loop.
- Mutual tail calls:
  `(define (my-even? n) (if (= n 0) #t (my-odd? (- n 1))))` and its
  mirror, `(my-even? 3000000)`.
- Deep recursion:
  `(define (build n) (if (= n 0) '() (cons n (build (- n 1)))))`,
  `(build 200000)`.

## Sources

Truffle and the Bytecode DSL (the GitHub copies of the docs track
`master`, that is 25.6, so check version tags):

- Bytecode DSL user guide:
  https://github.com/oracle/graal/blob/master/truffle/docs/bytecode_dsl/UserGuide.md
- Bytecode DSL optimization guide (quickening, boxing elimination):
  https://github.com/oracle/graal/blob/master/truffle/docs/bytecode_dsl/Optimization.md
- Bytecode DSL runtime compilation guide (PE constants, `@Variadic`
  limits): https://github.com/oracle/graal/blob/master/truffle/docs/bytecode_dsl/RuntimeCompilation.md
- Short-circuit operations:
  https://github.com/oracle/graal/blob/master/truffle/docs/bytecode_dsl/ShortCircuitOperations.md
- Builtins tutorial (inlined builtins):
  https://github.com/oracle/graal/blob/master/truffle/src/com.oracle.truffle.api.bytecode.test/src/com/oracle/truffle/api/bytecode/test/examples/BuiltinsTutorial.java
- Serialization tutorial:
  https://github.com/oracle/graal/blob/master/truffle/src/com.oracle.truffle.api.bytecode.test/src/com/oracle/truffle/api/bytecode/test/examples/SerializationTutorial.java
- Continuations tutorial:
  https://github.com/oracle/graal/blob/master/truffle/src/com.oracle.truffle.api.bytecode.test/src/com/oracle/truffle/api/bytecode/test/examples/ContinuationsTutorial.java
- SimpleLanguage's Bytecode DSL root (a reference configuration):
  https://github.com/oracle/graal/blob/master/truffle/src/com.oracle.truffle.sl/src/com/oracle/truffle/sl/bytecode/SLBytecodeRootNode.java
- Truffle changelog, 24.0–25.6 (GR-32682, GR-57063, GR-61292, GR-61293,
  GR-68993, GR-69649, GR-72022, GR-72833, GR-79412, GR-79418, GR-79562):
  https://github.com/oracle/graal/blob/master/truffle/CHANGELOG.md
- Inlining: https://github.com/oracle/graal/blob/master/truffle/docs/Inlining.md
- Splitting: https://github.com/oracle/graal/blob/master/truffle/docs/splitting/Splitting.md
- On-stack replacement: https://github.com/oracle/graal/blob/master/truffle/docs/OnStackReplacement.md
- Optimizing (tracing options, IGV):
  https://github.com/oracle/graal/blob/master/truffle/docs/Optimizing.md
- Profiling (CPU sampler, tiers):
  https://github.com/oracle/graal/blob/master/truffle/docs/Profiling.md
- Engine and compiler options:
  https://github.com/oracle/graal/blob/master/truffle/docs/Options.md
- Host compilation:
  https://github.com/oracle/graal/blob/master/truffle/docs/HostCompilation.md
- One compilation per bytecode handler:
  https://github.com/oracle/graal/blob/master/truffle/docs/OneCompilationPerBytecodeHandler.md
- Static Object Model:
  https://github.com/oracle/graal/blob/master/truffle/docs/StaticObjectModel.md
- Auxiliary engine caching:
  https://github.com/oracle/graal/blob/master/truffle/docs/AuxiliaryEngineCaching.md

Interpreter performance and tail calls on Truffle:

- S. Marr, "Why Are My Bytecode Interpreters Slow? Hunting Truffles with
  VTune" (2024):
  https://stefan-marr.de/2024/02/why-are-my-bytecode-interpreters-slow/
- O. Larose et al., "AST vs. Bytecode: Interpreters in the Age of
  Meta-Compilation", OOPSLA 2023: https://doi.org/10.1145/3622808
- C. Esquivias, "Writing a Language in Truffle. Part 4" (tail calls in
  Mumbler):
  https://cesquivias.github.io/blog/2015/01/15/writing-a-language-in-truffle-part-4-adding-features-the-truffle-way
- graal-dev, "Tail Call Optimization" thread (2015):
  https://mail.openjdk.org/pipermail/graal-dev/2015-January/002872.html

Benchmarks:

- ecraven's R7RS benchmarks: https://ecraven.github.io/r7rs-benchmarks/

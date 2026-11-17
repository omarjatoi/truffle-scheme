# Design

R7RS-small on GraalVM Truffle, built on the Bytecode DSL. Target: GraalVM CE
25.5 (Truffle 25.5.5.0.3), pinned by Bazel (D11).
Hard requirement: full R7RS-small conformance, including re-entrant multi-shot
continuations and proper tail calls.

Agents may change this document, decisions included, in a PR of its own that
says why (AGENTS.md). Use what GraalVM and Truffle provide wherever it fits;
full R7RS-small conformance comes first.

---

## Architecture

### Calls and tail calls

- Every procedure is a Bytecode DSL root. Frame argument 0 is the closure;
  arguments follow it, and a rest argument is packed in the callee prologue.
- **Tail calls** are ours to implement, because Truffle has no guest tail
  calls. (`@GenerateBytecode(enableTailCallHandlers)` is about how the
  interpreter dispatches its own bytecode handlers, not about guest calls.)
  A tail call returns a `TailCall(procedure, args)` marker. Every non-tail
  call site runs a trampoline loop: call, and while the result is a
  `TailCall`, perform it. Tail calls never grow the Java stack.
- **Primitives run in place (M5-09).** A call, in tail position or not, whose
  procedure is a leaf primitive that returns a value runs the primitive at the
  call site, with a node of the site's own and no Truffle call, `TailCall` or
  argument copy; its errors are raised in the calling frame (D8). Only the
  primitives that transfer control (`apply`, `call/cc`, `eval`, `load`, and
  the continuation internals) are still called.
- **The hottest primitives are operations of their own (M7-03).** A call of
  `car`, `cdr`, `cons`, `null?`, `pair?`, `eq?`, `not`, `+`, `-`, `*`, `/`,
  `quotient`, `remainder`, `modulo`, `=`, `<`, `>`, `<=`, `>=`, `zero?`,
  `vector-ref`, `vector-set!` or `vector-length` with the number of arguments
  the operation takes (two for the arithmetic and the comparisons) compiles
  to a Bytecode DSL operation
  (`Intrinsics`) that takes the arguments as operands: no argument array, no
  `Call`, no capture check after it. Its specializations return `long`,
  `double` or `boolean`, and the root has the DSL's boxing elimination for
  those types, so a fixnum or flonum stays unboxed from one operation to the
  next, in locals (a loop's variables), and out of a `long[]` or `double[]`
  vector (M5-06); an `if` whose test is a predicate's operation branches on
  its boolean without `Truthy`. A case a fast path doesn't take (a bignum or
  rational, mixed exactness, an overflow after the first, a store that
  generalizes a vector, an error) goes to the primitive's own node, as a call
  runs it in place, so the primitive alone defines the semantics.
  - Only a call of the primitive's own variable is compiled this way: one of
    the primitives' cells (`GlobalCell.isFixed`), which every library and
    program that uses `car` imports. Its value can't change: assigning an
    imported variable is a syntax error, and a definition makes a new
    variable (D7, D15), so `(define (car x) ...)` in a program or at the REPL
    binds a variable of its own, which is called. So the operations need no
    guard and no way back to a call; defining or assigning a fixed cell is an
    internal error. A variable of the program's that holds a primitive
    (`(define first car)`) is called, and its primitive run in place.
  - An error is raised in the operation's frame (D8): its generic path stores
    the bytecode index, so the frame's location is the expression, and calls
    `raise` as a call site would. `raise` never returns (§6.11), so when it
    comes back with a capture (a handler invoked or captured a continuation,
    torture, a spill), the frame can't and needn't suspend in the middle of an
    expression: the operation throws `AbandonedFrame` and the root returns
    the capture (`interceptControlFlowException`), with no segment of the
    frame, which nothing could resume. In tail position the error leaves the
    frame for the call site that called it, as from a primitive a tail call
    runs in place (M5-09).
  - *Rejected:* guarding each operation on the cell's assumption with a
    generic call to fall back to, which needs the arguments twice (evaluated
    into locals, or the code emitted twice) and a branch per call; keeping the
    `CallSite` capture check after each operation, which loses boxing
    elimination at every one and costs three instructions (on `fib` and `tak`
    in the interpreter, both measured 13-38% slower than a bare operation);
    a `TryCatch` around each root's body whose handler raises the error, since
    every exception that passes through is then rethrown from the handler,
    whose index becomes the frame's location in the stack trace.
- **`case` compares in place (M8-05).** `case` expands to `memv` of each
  clause's quoted datums (prelude `syntax.scm`). A call of `memv` or `memq`
  (the primitives' own cells, as above) with a quoted proper list of at most
  16 datums compiles to `eq?`'s operation against each datum in turn, and its
  value is the primitive's: the tail of the list whose car matched, or #f
  (`ConstantMember`). For `memv` every datum must be one that `eq?` compares
  as `eqv?` does, so not a bignum, a ratio or an exact complex number. Calling
  the primitive ran its loop behind a boundary with `eqv?`'s generic
  comparison, once per clause: 57% of `lattice`'s time, which this halved.
  A call of `assq` or `assv` with a quoted proper alist of at most 16 pairs
  (for `assv`, no bignum, ratio or exact complex key) is compiled the same way
  (M9-05), but to one operation, `Assoc`, rather than a chain of `Eq`s: its
  constant operand is the alist, and its node keeps the keys and entries in
  compilation-final arrays, so compiled code compares one after another, and
  the interpreter runs the loop in Java. A chain of `Eq`s, a few instructions
  an entry, made the interpreter slower than calling the primitive: on a
  table lookup of 7-8 entries per iteration, 1.10× the time with a chain,
  0.88× with the operation; JIT, first iteration 0.52× and steady 0.33×.
  No benchmark program calls either with a quoted alist.
- **Inline caches for tail calls, and splitting (M5-10).** Each non-tail call
  site (and each prompt) has a trampoline node of its own, `Trampoline`, with
  a 4-entry `DirectCallNode` cache keyed on the tail-called procedure's call
  target, so a procedure reached by a tail call can be inlined into the site
  and its `TailCall` scalar-replaced. The first tail call that comes back and
  the ones after it have separate caches (M7-01), so a chain of tail calls
  (`map` → its loop → `reverse`) is compiled once per site, not twice. A call
  site that goes megamorphic (its own procedure, or the first tail call)
  reports polymorphism, so Truffle splits the procedure it is in per caller;
  the cache of the tail calls after the first holds 8 (M8-05; it never
  reports, so it causes no splits): continuation-passing code returns every
  call of a continuation to one site's trampoline, and `cpstak`'s five
  procedures there were all called indirectly, with Truffle's argument
  profiling on each call, past a cache of 4;
  up to the cache limit the site inlines what it has seen and doesn't report,
  since each split is another compilation (25.5's Bytecode DSL can't compile
  `@ReportPolymorphism` on an operation, so the cache initializer reports).
- **Rare paths stay out of compiled code (M7-01).** The spill at the depth
  limit (below) is behind a branch profile: a site that has never spilled
  compiles without it. It was about two thirds of every non-tail call site's
  machine code, and on GitHub's 4-CPU runners (two compiler threads) the
  benchmarks' compilations didn't finish within the runs meant to measure
  compiled code.
- Self tail calls in named `let` and `do` compile to loops (back-edges, OSR).
  A root has the `While` they loop in only if its body has a tail call that
  can be one (M7-02): it costs two instructions per call.
- **A loop branches once per iteration (M8-04).** When a loop's body (a
  root's with self tail calls, or a contified loop's) is an `if` whose jumps
  back are all in one branch, the `if`'s test is the `While`'s condition
  (`LoopTest`, which also does `LoopHead`'s work), that branch its body, and
  the other branch follows the loop: `(if (< i n) (loop (+ i 1)) acc)` is
  `while (i < n) { i = i + 1 } return acc`. An iteration, and a call that
  returns from the other branch at once (`tak`'s leaves), branch once instead
  of twice. A statement's tags go around the test, so a breakpoint on the `if`
  is hit once per iteration, as before. An `if` whose test is `not`'s operation
  branches on its operand, with the branches swapped.
  It is worth less than the instruction count suggests: in the interpreter
  (`engine.Compilation=false`, the report's interpreter counts, a fresh JVM
  per run, interleaved), the six programs that run longest took 0.985 times as
  long (`cpstak` 0.96, `takl` 0.975, `tak` 1.005, one run each) and 45 shorter
  ones 0.996 times (2 rounds, noise); with the JIT, 20 programs' first
  iterations 1.006 times and steady states 1.002 (2 rounds, noise). `tak`'s
  leaves run 8 instructions instead of 11 and take as long: an instruction the
  host compiler predicts well costs next to nothing beside the call.
- **Loops of the root that enters them (M7-02).** A named `let`, a `do`, or a
  body's one internal procedure, entered in tail position, whose variable is
  only ever called, in tail position of its own body, is *contified*
  (`Closures`): its parameters are locals of the root it is in, bound as a
  `let`'s are, and its body is a `While` there. Entering it and looping make no
  closure, no `TailCall` and no call; a boxed parameter gets a fresh box each
  iteration, as a call would give it. A loop whose body makes a non-tail call of
  a procedure it can't know (a parameter, a computed procedure, or `apply`)
  stays a procedure of its own: that call site is the one that goes
  megamorphic, and as a small root of its own Truffle can split it for each
  caller, where the root that enters it would often be past the splitting limit
  (`map`'s and `for-each`'s loops, which are named after them so traces show
  them as `map` and `for-each`).
  A call of an escape-only `k` (`%call/ec`'s, "Continuations" below) doesn't
  count (M9-05): it always runs the same escape procedure, which never returns
  to the loop. `EscapeAnalysis` needs the contified loops, so `Closures`
  contifies these after it, and resolves the unit again; contifying a loop
  that refers to `k` keeps `k` escape-only, since such a loop's variable is
  only called. `puzzle`'s and `quicksort`'s `do` loops, which call `return`,
  are now code of the receiver. A fresh JVM per run, interleaved with the
  code before (Apple M3 Max, shared, load 6-10), medians of 5: `puzzle`
  (1000, 5 iterations) first iteration 2.32 to 2.64 s (1.14×, 13-22% in each
  of 5 pairs, and 7-17% in 10 more runs of 3 iterations), the steady state
  (best of iterations 2-5) 1.42 to 1.35 s (0.95×), the interpreter (count
  100) 0.93×; `quicksort` within noise (JIT
  first 1.02×, steady 0.99×, interpreter 1.00×). `puzzle`'s first iteration
  pays for compiling more, as with M5-04's escape (below): the receiver, with
  the loop in it, is what `trial`'s recursion inlines, and its tier-2
  compilation is 22,700 nodes and 1.29 s against the separate loop's 12,900
  and 0.78 s (`%call/ec`'s 21,700 and 0.92 s against 12,400 and 0.57 s),
  finishing 2.6 s into the run against 0.9 s; more compiler threads
  (`engine.CompilerThreads=6`) don't change that. Over 5 iterations the two
  take as long (medians 8.07 s against 8.10 s). Kept, as M5-04's escape was:
  the steady state and the interpreter gain, and the first iteration pays.
- **The trampoline reports loop counts (M7-02).** The tail calls a site performs
  count as iterations of a loop of the root it is in (`LoopNode.reportLoopCount`),
  as a self tail call's jump does, so a procedure called a few times whose sites
  perform many tail calls is compiled.
- **The trampoline's loop is a Truffle loop.** The tail calls after the first run
  in a `LoopNode` of the site's (`Trampoline.TailCallLoop`), whose state, the
  next tail call, is in an auxiliary slot of the frame (a slot the root's
  trampolines share: one runs at a time in a frame). So a long loop in a
  procedure entered once, such as a benchmark harness's loop, which performs
  every tail call of a CPS program, is compiled by Truffle's on-stack
  replacement once it passes `OSRCompilationThreshold` (100,352 iterations),
  with the site's caches inlined, and its iterations count toward compiling the
  root as before. A call outside the site's own (`raise` for an error) still
  trampolines in a Java loop.
  - *Why.* As a Java loop in the call operation, it ran in host code for as long
    as the procedure did, as fast as HotSpot had compiled it, and that depended
    on the host profile. The JIT warm-up (D17) ran `Trampoline.run` thousands of
    times with no tail call ever coming back, so HotSpot compiled it with the
    loop pruned; in `cpstak`, whose first iteration performs its 815 million
    tail calls in one call of the harness's trampoline, which started in
    HotSpot's interpreter, every OSR compilation of the loop HotSpot then asked
    for failed ("OSR entry point wasn't parsed", 505,507 times in 67 s), and the
    loop stayed interpreted: the first iteration took 72 s instead of 15.
  - *What it gives.* `cpstak` (its own count, 3 iterations in a fresh JVM,
    `-Xmx2g`, the serial collector, an Apple M3 Max; 2 interleaved rounds):
    11.2-11.4, 10.7-10.8 and 10.5 s an iteration, 33 s in all, against 71-89,
    18.4-18.5 and 10.1 s, 100-118 s in all (15.2 s first without the warm-up).
    The second iteration was still `main`'s host-run loop, after the warm-up's
    profile, and is compiled now. Before the later tail calls' cache held 8
    (M8-05), the compiled loop called `cpstak`'s five procedures indirectly at
    16-19 s an iteration. On the 24 programs of M8-03 with `takl` and `graphs`
    (the report's counts, G1, 3 interleaved rounds, medians), with that cache at
    8 on both sides, the first iteration's geometric mean was 1.013 times, the
    steady state's 0.997, whole processes' 1.004 (at 4: 0.980, 0.995, 0.994):
    noise. In the interpreter (7 programs, 5 rounds), 1.002 times: a root whose
    trampolines loop has an auxiliary slot, an array more in each of its frames.
- **No uncached interpreter (M8-04).** *Rejected:* the Bytecode DSL's uncached
  tier (`enableUncachedInterpreter`), which runs a root without allocating the
  cached tier's nodes or quickening until it has been entered or looped 16 times.
  Every operation can run uncached (a call through `IndirectCallNode.getUncached()`,
  a primitive in place with its root's own node, tail calls in a loop of uncached
  calls), so it was measured both ways. With every root uncached at first, the
  prelude's library bodies took 6 ms instead of 11 when a context starts (of
  about 385 ms for `hello.scm`), but splitting broke: a call site in the
  uncached interpreter has no call node, and Truffle decides once, from the call
  nodes a procedure's callers have when it goes polymorphic, whether to split it,
  so `map`'s loop, megamorphic while its callers were still uncached, was never
  split for them (`CallSiteTest`, `StableWarmupTest`, `TailCallCacheTest`). With
  only code that runs once uncached (a program's top level, library bodies, REPL
  inputs, `eval`) and procedures cached from their first call, nothing changed
  measurably: the bodies still took 11 ms, `hello.scm` 386 ms against 385, and
  the interpreter's first runs of 40 benchmarks were within 1-2% (noise).
- **One instruction binds the parameters (M8-04).** A procedure whose
  parameters are all required starts with `BindArguments`: it checks the arity
  and stores each argument in its parameter's local (a `LocalRangeAccessor`),
  boxed if the parameter is assigned, through the local's boxing-elimination
  profile, so a fixnum parameter is an unboxed `long` as with a `StoreLocal`.
  It replaces `CheckArity` and a load and a store per parameter. A rest
  parameter, or more than 31 parameters, keeps those.
- **Tail calls keep nothing alive.** A tail call replaces the frame it is made
  from (R7RS §3.5), so nothing of that frame may stay reachable while the call
  runs: not its variables, nor the arguments it was called with. A tail loop
  over a lazily built list (a stream of promises, SRFI 45's traversal) would
  otherwise keep every element it has forced, and run out of heap. What kept
  them, and what doesn't any more (`tests/space/tail-lazy-*`, under a 32 MB
  heap, interpreted, compiled and under both tortures):
  - *The first call's arguments.* A procedure's argument array is its
    caller's: a call site's variadic array, a trampoline's or a driver's
    `TailCall`, which their Java frames hold until the procedure returns, and a
    loop (a self tail call's jump, a contified loop) never does. The prologue
    clears each argument once bound (`BindArguments`, `ReleaseArguments`); only
    the closure, argument 0, is read after it, and tools read the parameters'
    locals. Compiled code works on a copy of the array (Truffle's argument type
    profile), so an interpreted call site or trampoline also hands the array
    over in `SchemeContext.callArguments`, and a compiled prologue clears that
    one too if its arguments were copied from it. Only the prologue at the
    root of a compilation does (`CompilerDirectives.inCompilationRoot()`): an
    interpreted call enters compiled code there, and a prologue inlined into
    compiled code was called from compiled code. Checking the hand-off in every
    inlined prologue too, a load and a branch on every call that the compiler
    can't remove, made `quicksort` 1.36 times, `triangl` 1.21 times and
    `fibfp` 1.15 times slower than without it (plan.md M9-02). A compiled caller's frame
    keeps nothing it no longer uses, and storing the array from compiled code
    would make it escape where it can otherwise stay virtual (that made `fib`
    2.4 times slower); an interpreted prologue's array is the caller's. A
    control primitive that returns a `TailCall` (`apply`'s list) clears its
    arguments the same way. In the interpreter alone, this costs `fib` about 5%.
  - *The variables of the lets a call is in tail position of.* In
    `(display (let ((h (from 0))) (walk h n)))`, `(walk h n)` is a non-tail call
    of the root but a tail call of the let's body, made from inside the let's
    block: its last operand clears `h` once evaluated (`ThenClear`). Entering a
    contified loop, whose `While` runs inside the blocks of the lets around it,
    clears their variables, and the root's parameters, that the loop's body
    doesn't use. (A jump back to a loop's start leaves the blocks of the lets
    it is in, and the Bytecode DSL clears a block's locals when it is left, so
    an earlier iteration's are gone even if no later one binds them again:
    `tail-lazy-paths`.) A cleared variable is gone from the debugger's view of
    the frame, as it is dead.
  - *The frame on-stack replacement started from.* OSR runs a loop in a copy of
    the interpreter's frame, which Truffle keeps, unchanged, until the loop
    ends. The loop's condition, `LoopHead`, clears it on the compiled loop's
    first iteration: the OSR frame's arguments end with it. (OSR runs in the
    frame itself once the frame has been materialized, as torture does.)
  - *What a prompt's driver holds.* A capture's value is taken from it
    (`Capture.takeValue`), and its segment list let go once handed over: a
    suspended frame's stale operand slot may hold the capture. A resumed frame
    gets its value in a `Resumption` that its `Resumed` empties: the driver's
    Java frames and the continuation root's arguments stay on the stack while
    the program goes on.
  - *The trampoline's loop.* The next tail call waits in the frame's auxiliary
    slot ("The trampoline's loop is a Truffle loop", above), which the loop
    clears as it makes the call, and `Trampoline.run` lets go of it once it is
    there.
  - Not covered: an operand already on the operand stack (the procedure of a
    call that runs) stays there until the call returns.

### Continuations: a segmented stack

- `call/cc` returns a `Capture` marker. At each non-tail call site,
  `CallSiteEmitter` emits `CallSite(Call(...), Resumed(SuspendFrame()))`:
  `CallSite` is a value-returning short circuit (`@ShortCircuitOperation`,
  `OR_RETURN_VALUE`) whose converter, `IsValue`, is false only for a
  `Capture`, so the call's value is the site's unless it is a capture; then
  `SuspendFrame`, a custom `@Yield` operation (Truffle 25.1+), runs. It
  `@Bind`s its `ContinuationRootNode` and `MaterializedFrame`, adds
  `Segment(ContinuationRootNode, MaterializedFrame)` to the capture, and
  returns the marker to its own caller, which does the same. The short circuit
  drops the operand it converted, so `IsValue` hands the capture to
  `SuspendFrame` through the context (`SchemeContext.suspending`, set and
  cleared with no Scheme code between). After the call that is five
  instructions (`dup`, `IsValue`, the branch, and on the capture path the yield
  and `Resumed`), with no temporary: M7-02 cut it from ten (a store and loads of
  a local, `IsCapture`, a branch, `SuspendFrame`, `Resumed`, a branch and a
  clear), which were most of the size by which Truffle decides whether to split
  a procedure (the bytecode's length over 6, against `SplittingMaxCalleeSize`,
  100), and work the interpreter did at every call.
- Capture stops at the nearest **prompt** whose tag matches (see D4).
  `call/cc` uses the program's root prompt.
- **Invoking a continuation** resumes segments from the innermost outward, each
  on a fresh Java frame: `resume(copy(frame), value)`. If a capture happens
  during a resume, the segments not yet resumed are added to the new capture.
  Re-entry therefore costs O(1) Java stack depth, and captures share segments
  that are already on the heap.
- **Invoking a full continuation returns; it doesn't throw.** The prelude's
  `%continue` rewinds, then `%apply-continuation` returns an APPLY `Capture`
  (the continuation and its values), which travels up like any capture:
  each frame it passes suspends with nothing kept (`Capture.addSegment` drops
  the frame), every other prompt passes it on as it is, and the root prompt's
  driver replaces its pending segments with the continuation's and resumes
  them. It was an `InvokeContinuation` exception thrown to the root prompt.
  - *Why.* An exception that passes the host-compiled interpreter
    (`continueAt`, with `Call`, the trampoline and `OptimizedDirectCallNode`
    inlined into it) needs the exception edges of the inlined calls, and Graal
    leaves out an edge whose profile says nothing was thrown there. In
    `puzzle`, whose `k` is invoked from inside a `do` loop (so it is a full
    continuation: the loop is a lambda), the throw left through the
    trampoline's Truffle loop (#167), and every throw then deoptimized
    `continueAt` (`not_compiled_exception_handler` at
    `OptimizedDirectCallNode.call`, bci 27, its `handleException`) and it was
    compiled again without the edge, about every 250 ms for as long as the
    program ran (16 times in a 4 s run), although every invoke on the way had
    recorded the exception in its profile by then (`-XX:+PrintMethodData`).
    Before #167 the same deoptimization happened once or twice, and the
    recompiled code kept the edge. Lower host compile thresholds
    (`-XX:CompileThresholdScaling=0.05`) or `-Djdk.graal.UseExceptionProbability=false`
    also end it, but they are the JVM's settings, not ours. Returning keeps
    the transfer on paths the host compiler compiles as taken. In the
    interpreter (medians of 3 interleaved runs, a tenth of the programs'
    counts): `puzzle` 7.5 s → 5.2 s (4.8 s before #167), `maze` 3.6 s →
    3.3 s; the 24 programs of M8-03 and `puzzle` at the report's interpreter
    counts 0.966 times (`puzzle` 0.48, `maze` 0.76, the rest within noise).
    With the JIT nothing changes beyond noise (24 programs, first iteration
    1.004 times, steady state 1.004), except that `puzzle`'s second and third
    iterations at a tenth of its count take about 10% longer (a transient:
    its later iterations, and every iteration at its own count, are as
    before).
  - *Escape-only `k` returns too (M9-04).* Invoking `%call/ec`'s `k` (below)
    returns an ESCAPE `Capture` for its prompt's tag, which travels up the
    same way: the frames it passes are abandoned, the prompt it is for
    returns its value, a driver that holds that prompt flattened into a
    marker drops its pending segments up to it, and every other prompt
    passes it on. It was an `EscapeException` thrown to the prompt. Since
    #173 made `puzzle`'s `return` escape-only, `puzzle` threw it about
    24,000 times at a tenth of its count; a `puzzle` whose `return` is
    reached through two tail calls (so the throw leaves through the
    trampoline's loop, as #172's did) deoptimized `continueAt` 36 times in
    the interpreter (`not_compiled_exception_handler` at
    `OptimizedDirectCallNode.call`, bci 27, in 4 to 5 recompilations), against
    one with the capture. `HostJitPitfallsTest` checks that such a program
    throws nothing. Measured against throwing, on the code after #178
    (which contified `puzzle`'s loop; a fresh JVM per run, interleaved,
    medians, an Apple M3 Max shared with other agents, load 7-12): in the
    interpreter, at a tenth of the counts, `puzzle` 0.93, `quicksort` 0.98,
    `maze` 0.98, `ctak` 0.99, `fibc` 0.97. With the JIT, at a tenth of the
    counts, first iteration and best of the next two: `puzzle` 0.33 and
    1.20, `quicksort` 0.86 and 1.07, `maze` 1.00 and 0.88, `ctak` and `fibc`
    within noise; the 24 programs of M8-03, 1.012 and 0.972 (noise: programs
    that never escape moved as much). At its own count (5 iterations, 3
    runs) `puzzle`'s first iteration is 0.68 times (2.72 s to 1.84 s) and its
    later ones about 1.06 times (best 1.32 s to 1.40 s; `quicksort`'s 1.00):
    a trade-off. Before #178 it was 0.79 and 1.12. It is the compiled shape,
    not the cost of an escape: throwing from compiled code and returning only
    in the interpreter (`CompilerDirectives.inInterpreter`) measured the
    same, and so did returning at once from the driver when the procedure
    it calls escapes to it. `bench-check`: `puzzle` 1.07 times its baseline,
    `quicksort`, `ctak` and `fibc` 0.99-1.01.
- **Segments are immutable once shared.** Each resume of a segment that a
  continuation can reach copies the frame. This is correct only because of
  assignment conversion (D2): the frame holds no mutable Scheme variables,
  only references to shared boxes. A segment only its prompt driver can
  reach is resumed in place instead (Owned segments, below).
- **Copying a frame:**
  `Truffle.getRuntime().createMaterializedFrame(frame.getArguments().clone(), descriptor)`,
  then `frame.copyTo(0, copy, 0, descriptor.getNumberOfSlots())`. This copies
  the arguments and every frame slot, operand stack included. It's the same
  copy `BytecodeFrame.copy()` makes internally, but `BytecodeFrame` doesn't
  expose its frame, so it can't be used directly. The copy is passed to the
  segment's `ContinuationRootNode` call target as `(copy, value)`, through a
  `DirectCallNode` inline cache keyed on the `ContinuationRootNode`.
- **Never resume a shared original frame.** Truffle documents a
  `ContinuationResult` as resumable only once. In M0-02, resuming an original
  frame a second time crashed with `FrameSlotTypeException`. A frame that a
  continuation can reach is only resumed from fresh copies, and that is what
  makes multi-shot possible.
- **Owned segments are resumed in place, once.** The frames a stack-depth
  spill suspends are reachable from nothing but the root prompt's pending
  list until something captures them, and the driver resumes each of them
  once and drops it. Such a segment is resumed from its original frame, with
  no copy: that is the Bytecode DSL's own one-shot resume (its continuation
  root runs on the frame it is given, and suspends that same frame again at
  the next yield), so the frame is never resumed twice from one suspension.
  - *Ownership.* A spill's segments share one owner token, which the root
    prompt's driver stamps with the context's sharing epoch when it takes
    them (`Capture.Segment`). A segment is owned while its stamp is the
    current epoch. Every event that may let a continuation reach a segment
    bumps the epoch, so every segment that exists then becomes shared, for
    good: creating a `Continuation` (`call/cc`, a delimited capture, so
    `guard` and `raise` handlers, generators, and continuations an embedder
    re-enters through interop), and a torture-mode capture (so torture
    checks every resume as before; not under owned torture, below). A segment that a capture re-links from
    an inner prompt's pending list into another (`Capture.addOuter`, the
    flattening of `%call-with-prompt`, `guard` and `%call/ec` prompts) is
    made shared too. Every other segment (from a `call/cc` or delimited
    capture, plain torture, a composable continuation's frames) is shared
    from the start. Tools only read pending frames (M6-04), and an error that unwinds
    through pending frames resumes copies, so neither changes ownership.
  - *Checked by two torture modes.* Torture (`-Dscheme.torture=true`)
    shares every capture, so every resume is from a copy, checked against
    the original (AGENTS.md rule 3). Owned torture (with
    `-Dscheme.torture.owned=true` too) keeps a torture capture's segments
    owned, as a spill's (the driver claims them, and doesn't bump the epoch),
    so every non-tail call's return resumes frames in place, and whatever a
    continuation then reaches is copied and checked. In both, a segment
    resumed in place that is resumed again, in place or from a copy, is an
    internal error: the sign of a continuation that reached an owned segment.
    Both run the unit tests, every conformance section and the `tests/diff`
    programs. With the epoch bump taken out of `Continuation.capture`, owned
    torture fails 14 targets (conformance chibi 6.10 and Larceny's base, two
    diff programs, ten unit test classes), plain torture none, and the JIT
    and compiled modes only the tests that spill (`OwnedSegmentsTest`,
    `SpillTest`, `SpilledFramesTest`, `tests/spec/cont-deep`).
  - *Why:* a spill of a recursion deeper than the stack (`ack(3,12)`) copies
    nearly every frame once, about 1 KB and 240 ns each (M8-01), and nothing
    can ever see the original again. Resuming in place cut `ack(3,10)` by
    about a third in M8-01's experiment.
  - *Considered:* a `shared` flag on each segment, set on every segment a
    capture can reach (a walk of the pending list at each `call/cc`: O(depth)
    per capture, which `fibc` and generators over a deep recursion would pay
    at every call); a walk that stops at the first shared segment, assuming
    the owned ones are always a prefix of the pending list (false once a
    composable continuation is reinstated, whose shared segments go in front
    of owned ones); and a generation per prompt (prompts nest and flatten,
    so a capture would bump every prompt it crosses). The context-wide epoch
    is O(1) at every capture and every resume, and conservative: a capture
    anywhere makes every existing segment shared, even one it can't reach.
    That costs nothing where the gain is, deep recursion without
    continuations; a program that captures pays the copy as before.
- **Stack-depth spill.** A depth counter forces a capture past a threshold,
  then execution resumes on a fresh stack. Deep non-tail recursion never
  overflows the Java stack.
  - *The threshold is the Java stack the thread has (M8-01).* The counter
    estimates bytes (`StackBudget`): each non-tail call adds its caller's
    frame, 2.5 KB from the interpreter, and from compiled code 128 bytes plus
    32 per slot of the caller's frame (measured: 0.5 to 2.2 KB, and 165 to
    870 bytes). Until a thread needs more, the limit is 50 interpreted calls,
    what fits in a 256 KB stack; the first time a call passes it, the
    thread's remaining stack is measured, and the limit becomes it less an
    eighth or 256 KB, whichever is more. Java can't tell how much stack is
    left, so a probe recurses until it overflows, here and on two threads
    with stacks of known sizes, which give the bytes per probe frame; the
    probes repeat until two rounds agree, since a JIT compiles the probe as
    it runs, and if none do, the smallest any round gave counts (a JIT only
    makes the probe's frames smaller, so with the smaller of the figures for
    a frame measured before and after the depth here, a round
    underestimates). A reference thread can run on a larger stack than it
    asks for: glibc gives a new thread the cached stack of an exited one up
    to 4 times its size, so on CI's Linux executors a 512 KB thread ran on a
    1 MB one, as deep as the larger reference, and no round counted. So a
    frame's figure is also at most the larger reference's size, less the
    256 KB of a thread's own overhead (below), over its depth, which a
    larger stack only makes smaller. Each root prompt takes its thread's limit. On a 2 MB stack
    (macOS's default) about 3,300 compiled calls fit before a spill, instead
    of 50; with a larger `-Xss`, more (the probe looks at no more than 64 MB).
    The `scheme` launcher evaluates on a thread with a 256 MB stack unless
    it is given a stack size (D17, "The evaluation thread").
    The option `scheme.SpillDepth` fixes the old count of nested calls
    instead, for tests of spilled frames.
  - *What the count costs a call (M9-02).* Every non-tail call adds its
    caller's cost to the stack use and compares it with the thread's limit,
    a field and not a constant, then puts the old use back when the call
    returns. That replaced incrementing a count, comparing it with the
    constant 50, and decrementing it. In compiled code it costs a recursion
    that does little else a few percent: `fib` 1.06 times and `fibfp`
    1.04 times the code before M8-01 (`c458546` against `de2f55c`). The
    benchmark is `bench-check`'s: fresh JVMs, the median of the last 10 of
    25 iterations, `-XX:+UseSerialGC -Xmx2g`, 7 interleaved rounds. That is
    all of what `fib` and `fibfp` still lose against M7-03 at M9-02 (`main`
    at `47fc603`: 1.06 and 1.04 times). We accept it for what M8-01 gives: a
    deep recursion runs about 3,300 compiled calls deep before a spill
    instead of 50, thousands of times deeper with the launcher's stack, and
    the limit follows the stack the thread really has. With the launcher's
    evaluation thread (D17), that took `ack` from 37.9 to 2.4 times the
    fastest implementation in the report. Tried without gain: the costs as
    `@CompilationFinal` fields, constants in compiled code, made `fib`
    1.12 times slower again. Taking the cost off the use instead of keeping
    the old use live across the call, or comparing with a constant instead
    of the limit (both unsafe, measured only), were slower or within the
    noise.
  - *A stack size the embedder knows isn't measured.* The probe touches
    what it measures, up to 64 MB, which stays resident (60 MB more peak
    RSS for a small program), and overflowing a 16 to 64 MB stack took 0.3
    to 0.6 s. The option `scheme.StackSize` gives the stack size of the
    thread the context is created on (Truffle creates it on the thread that
    first uses it, or, pre-initialized in a native image, the one that
    builds it): on that thread the limit is what a measurement would give,
    and nothing is measured. That is the size less 256 KB (the VM's guard
    zones and the thread's first frames, 118 KB on HotSpot, and the frames
    below the root prompt), at most 64 MB of it as for a measurement, less
    the same reserve: 56 MB of the launcher's 256 MB. The context records that thread, since the option is per
    context and a stack size per thread: any other thread that enters the
    context is measured as before. The launcher passes the size it created
    its evaluation thread with (D17), and nothing when it evaluates on its
    own thread, whose size it doesn't know (`-Xss`, `--stack-size=default`).
    Where the probe still runs, the deepest frame that can catch the
    `StackOverflowError` does, and the frames above it return normally:
    unwinding the error through millions of compiled probe frames took
    three quarters of the time (a 16 MB stack: 331 to 75 ms).
  - *In a native image, calls also check the stack's real depth.* The costs
    were measured on HotSpot, and they aren't upper bounds in a native
    image: there, code Truffle compiled at its first tier, which inlines no
    call, runs each level of a recursion through `apply` as a compiled frame
    plus the frames of a call between call targets, 530 to 590 bytes where
    the costs count 420 to 450 (read from the stack pointer: 1.27 to 1.32
    times). That is more than the reserve's eighth, so such a recursion,
    reaching the first tier while it runs, overflowed a 64 MB stack (limit
    56 MB, 74 MB used) and the native launcher's 8 MB main thread; on the
    JVM, and natively interpreted only (half what the costs count) or once
    the second tier has inlined the recursion, it fit. A native image can
    read where its stack is (an address `StackValue` gives in a
    `@TruffleBoundary` method), so there each root prompt notes where its
    stack starts, and past half the limit, a call that takes the stack use
    across a multiple of 64 KB also spills if the stack below it is deeper
    than the limit: an `xor` and compare on those calls, and a boundary
    call on one in a hundred or more. Between two checks the stack grows by
    what 64 KB of costs stand for, which the 256 KB reserve covers 4 times
    over, whatever the frames are (compiled at either tier, interpreted,
    OSR, primitives or the trampoline between them). Below half the limit,
    a call only compares with it, as on HotSpot (where the half is the limit
    itself), and the costs would have to undercount by more than 2 times to
    overflow there. Checking from the root prompt on, every 16 KB, made
    `fib` (40 calls deep) 25% slower natively, and checking past a quarter
    of the limit, every 16 KB, made the first `ack(3,12)` 40% slower. On
    HotSpot, Java code can't see its stack's depth; the costs, measured
    there, bound it, and the stress matrix (`//tests:space`,
    `StackBudgetTest`) checks they still do. `//tests:space_native` runs the deep recursions through the native
    launcher on stacks from 256 KB to 256 MB. The limit still counts at most
    64 MB of a stack, natively too (next item).
  - *Natively, the limit doesn't use more of a known stack.* Since a native
    image reads its stack's depth, its limit could be most of a known stack,
    on the real depth (the launcher's 256 MB less a reserve, 224 MB), and the
    costs would only say when to read it. Tried and measured (native
    launcher, Apple M3 Max): a frame that stays on the Java stack costs
    more than one that spills, unless the whole recursion fits and its code
    is already stable. A recursion's first deep run is compiled while it
    descends, before any call has returned, so the first return invalidates
    the code, and each compiled frame of it still on the stack deoptimizes
    when it is returned to (`--engine.TraceCompilation` logs an `opt deopt`
    per frame): 25 to 40 µs a frame, where a spilled frame, which resumes
    into new code, costs about 1 µs. And every collection scans the whole
    Java stack, so a deeper stack makes each one slower. Medians of the
    iterations in one process; the first iteration, then the steady ones:

    | | 56 MB counted (kept) | 224 MB real |
    |---|---:|---:|
    | `ack(3,12)` ×4 | 1.20 s, then 1.16 s; no spill; 92 MB | the same |
    | `divrec`, `primes` (M8-01's counts) | unchanged; no spill | the same |
    | `count` 300,000 deep ×6 | 0.1 to 5.3 s, then 25 to 150 ms; 13, then 2 to 5 spills | 10 s, then 3 ms; no spill |
    | build a list and `map` it, 1,000,000 deep, ×6 | 17.7 s, then 0.26 to 0.57 s; 12 to 19 spills; 1.0 GB | 58 to 60 s, then 0.16 to 0.28 s; 0 to 3 spills; 0.9 to 1.1 GB |
    | `count` 10,000,000 deep ×3 | 15.9 s, then 2.3 to 2.8 s; 65 to 69 spills; 4.3 GB | 72 s, then 3.4 s; 3 to 7 spills; 4.9 GB |

    The list's first iteration, traced: 480,000 deoptimized frames and 6.3 s
    of collections (308) with the counted limit, 1,530,000 and 19.7 s (314)
    with the real one. Only a steady recursion that fits the larger stack
    gains, and every whole run measured took longer; the native launcher
    mostly runs a program once. So the limit stays the costs' 56 MB (about
    28 MB of compiled frames, really). HotSpot has no equivalent of
    `StackValue` short of a native call, so the JVM couldn't do it either.
  - *Spilling is still per frame.* A frame that doesn't fit is materialized
    when it suspends, and until M8's owned segments was also copied when it
    resumed, about 1 KB allocated and 240 ns per frame (`ack`, M8-01);
    recursion much deeper than the stack pays the materialization for nearly
    every frame. A capture links its segments into the
    list the driver takes as the frames suspend (no array to grow and copy).
    Making the driver's loop a Truffle `LoopNode`, so that the program's root
    prompt (which runs once, so is never compiled) is compiled on the stack,
    gained about 3% on `ack` for compilations of 130 to 240 KB that kept
    being invalidated: not done.
  - *The driver's loop and the host profile (M9-04).* The root prompt's
    driver loop is a Java loop in a root that runs once, as the trampoline's
    was before #167, so it runs as fast as HotSpot compiles
    `PromptDriver.run`, whose profile every other prompt's driver also makes
    (an escape-only `call/cc` runs one, which returns at once). Checked with
    `-XX:+PrintCompilation`, interpreter and JIT, on a deep recursion's return
    path (1,000,000 frames resumed, before and after 300,000 escape-only
    calls, and with an escape-only `call/cc` in every frame resumed), under
    torture (`fib` with and without one in every call), and on a generator
    of full continuations: HotSpot OSR-compiled the loop every time, with 3
    to 6 recompilations and no skipped compilation, and the later rounds took
    as long as in a run without the escapes. Unlike the trampoline's loop
    (`while (result instanceof TailCall)`, which the warm-up never entered),
    every call enters the driver's loop and most take its back-edges, so no
    profile prunes it. `HostJitPitfallsTest` keeps the check.
  - *Rejected:* catching `StackOverflowError` and spilling then: the error can
    come from anywhere (a specialization half done, class loading), and the
    frames it unwound are gone; and a fixed larger count, which a 256 KB
    stack can't hold in the interpreter.
- **Escape-only fast path (M5-04).** When `EscapeAnalysis` proves that a
  `call/cc`'s `k` is never `set!`, and is only called or passed to a known
  procedure (a literal lambda, a `let`-bound lambda or an internal
  definition, or in an isolated program a once-defined global) whose
  parameter qualifies in turn, the call compiles to `%call/ec`: a prompt with
  a fresh tag.
  - *k in lambdas that are only called.* A reference to `k` inside a nested
    lambda qualifies when that lambda is itself only called in the same way:
    a `do` or named `let` loop (`puzzle`'s and `quicksort`'s `return`, whose
    non-tail call kept the loop from being contified before M9-05), a helper bound by
    `let`, `letrec*` or an internal definition, or a lambda passed to a known
    procedure's parameter that qualifies (`maze`'s `vector-for-each-rev`).
    The analysis decides which values (variables, and lambdas' closures) are
    *contained*, used only in the extent of the call that binds or makes
    them, as one greatest fixed point: a value is contained unless it is used
    other than as a call's operator, a qualifying argument or a `let`'s init,
    or is assigned, or is referred to from inside a lambda (between the
    reference and the variable's own root) that isn't contained. So a lambda
    stored, returned, or passed to `map`, `for-each`, `apply` or any other
    procedure of the prelude keeps every variable it refers to from outside
    a full capture, and so does a helper such a lambda calls.
    Every call of a contained closure happens in the extent of the call that
    made it, so every invocation of `k` happens before `%call/ec` returns.
    It runs after contification (M7-02): a contified loop is code of its
    root, not a closure, and needs no rule.
  - *A `call/cc` receiver is only called (M9-05).* `call/cc` calls its
    receiver once, in its own extent, so a receiver is contained when the
    `call/cc`'s own `k` is: an outer `k` used in a nested `call/cc`'s
    receiver is escape-only, and both calls become `%call/ec`. It is one more
    edge of the same fixed point (the receiver depends on its `k`), so `k`s
    that depend on each other, through a helper both are passed to, are
    decided together. A nested receiver whose `k` escapes keeps the outer `k`
    a full capture too: with a full capture there the receiver still runs in
    the extent, and invoking the outer `k` from it would work (the prompt
    marker), but the rule stays with escapes nested in escapes. No benchmark
    program has the shape (`ctak`'s and `fibc`'s nested receivers use their
    own `k`), so none changes.
  - *What it gains.* `puzzle`, `quicksort` and `maze` now escape (`ctak` and
    `fibc` already did). A fresh JVM per run, interleaved with the code
    before, medians of 3 (Apple M3 Max, shared); interpreter
    (`engine.Compilation=false`) at a tenth of the counts: `puzzle` 0.91,
    `quicksort` 1.03, `maze` 1.11, `ctak` and `fibc` 1.01. JIT, at a tenth of
    the counts, first iteration and best of the next two: `puzzle` 2.20 and
    0.85, `quicksort` 0.98 and 0.98, `maze` 0.93 and 0.87, `ctak` and `fibc`
    1.00. `puzzle` at its own count (1000), 2 runs: first iteration 1.85 to
    2.23 s, then 1.50 to 1.35 s.
  - *`puzzle`'s first iteration pays for compiling more.* A full `call/cc`
    ends Truffle's inlining (the receiver runs from the prompt driver after
    the capture), but `%call/ec`'s root calls the receiver directly, so
    `trial`'s recursion, `trial` → `%call/ec` → receiver → loop → `trial`, is
    inlined: the loop's tier-2 compilation takes 0.73 s (39 targets inlined)
    and `%call/ec`'s 0.54 s, against 0.19 s for the largest before, and they
    finish at the end of the first iteration (no deoptimization loop: 81
    host deoptimizations in the run against 62). `ctak` has the same shape
    and compiles as much (two tier-2 compilations of 0.72 s, 89 and 42
    targets inlined), but they pay for themselves within its first
    iteration.
  - *Not cut at the receiver.* Making `%call/ec`'s calls non-inlinable was
    measured and rejected: every way of doing it moves the cost from
    `puzzle`'s first iteration to `ctak`'s and `fibc`'s steady state.
    Truffle has no per-call-site "don't inline" (`DirectCallNode` can only
    force inlining), so a cut is an `IndirectCallNode` or a
    `@TruffleBoundary`, and an `IndirectCallNode` whose target is a
    partial-evaluation constant is inlined like a direct call (a forwarding
    root that called `%call/ec`'s root that way didn't help `puzzle`). Fresh JVMs,
    interleaved with `main`, medians of 2; first iteration and best of the
    next two, at a tenth of the counts unless said:
    - *The calls the driver makes after the receiver's (what it tail-calls)
      indirect:* this cuts the cycle, since `puzzle`'s receiver tail-calls
      the loop. `puzzle` 0.43 and 0.96 (0.72 and 0.95 at its own count),
      `ctak` 1.01 and 1.12-1.15, `fibc` 0.95 and 1.11 (1.06 and 1.17 at its
      own count); `quicksort` and `maze` unchanged; the 24 programs of
      M8-03, first 0.998 and steady 1.017.
    - *The receiver's call indirect:* `ctak` 1.00 and 1.06, `fibc` 1.04 and
      1.12 at its own count. `puzzle`'s 0.44 and 0.99 (0.74 and 0.98 at its
      own count) isn't a cut: the loop's compilation happened not to inline
      `%call/ec`, but the driver still inlines the loop, and the cycle is
      compiled later, in a 13,000-node compilation of `%call/ec`.
    - *Every call indirect:* `ctak` 1.15 and 1.31, `fibc` 1.01 and 1.28.
      *Only where `%call/ec` is inlined into another compilation*
      (`CompilerDirectives.inCompilationRoot()`): `ctak` 1.41 and 1.66,
      `fibc` 1.10 and 1.67. *A direct call behind a boundary:* `ctak` 1.21
      and 1.35.
    - *One call site in the driver for the hot calls*, so that its cache's
      targets are inlined once rather than at each of the driver's call
      sites: `fibc`'s steady state 1.44 (the receiver's call, out of the
      loop, is specialized to the caller's receiver), `puzzle` unchanged.

    What the cut costs `ctak` is the indirect call, not the lost inlining:
    with `engine.ArgumentTypeSpeculation=false` (one run) its steady state
    is 1.24 s both ways. Truffle's own size-aware limit is global: half its
    inlining budget (`compiler.InliningInliningBudget=6000`) gave `puzzle`
    0.79 and 0.97, `ctak` 0.90 and 0.91, `fibc` 0.97 and 1.10, but a
    geometric mean of 1.055 over the 24 programs' first iteration (`sumfp`
    1.88, `fft` 1.33); no recursive inlining
    (`compiler.InliningRecursionDepth=0`, one run) made `tak` 1.2 times
    slower.
  - *Invoking `k`.* Calling `k` returns an ESCAPE `Capture` for its
    prompt, which travels up like any capture (M9-04, "Invoking a full
    continuation returns", above); a `k` tail-called back to its own prompt
    returns with no capture. An escape across a change of dynamic
    environment goes through the prelude's `%escape`, which rewinds first.
    Captures flatten the prompt into a marker like any other, so `k` works
    from re-entered copies too. `unwindExceptions` isn't needed: guard is
    prompt-based (D4), so there are no guest exception handlers for an
    escape to bypass.
- **Spilled frames in tools (M6-04).** Pending segments are frames waiting
  for a value on the heap: past the spill depth, or in a resumed
  continuation. Only the running segment's frames are on the Java stack, so
  Truffle's stack walks see a few frames and then the prompt. Two mechanisms
  put the others back:
  - *Uncaught errors unwind through them.* When a non-raisable error leaves a
    prompt driver with pending segments, the driver resumes each one, from a
    copy, with the error as its value. The `Resumed` operation, which follows
    every `SuspendFrame`, throws it from the call site. The error leaves
    through each frame's call target, so its guest stack trace has every
    frame, in order, with its call's line. Each frame is called with the next
    one's bytecode as its location, since a trace element's location is the
    node that called the frame inside it. A hidden internal root goes first,
    so the first pending frame gets a location too.
  - *Prompts report them as their asynchronous stack.* A driver keeps its
    pending list in a slot of its root's frame whenever it calls out, and the
    prompt roots override `RootNode.findAsynchronousFrames`. Each element is
    the procedure's root, the segment's frame and its resume location, so a
    debugger shows the name, the line and the variables
    (`SuspendedEvent.getAsynchronousStacks()`). The CPU sampler splices the
    same elements after the prompt in each sample (its `GatherAsyncStackTrace`
    option is on by default; `//tests:tools_cpusampler` checks it on `tests/tools/spillme.scm`). Prompt roots report no instrumentable call node,
    so the debugger lists them: it skips frames whose call node isn't
    instrumented code, and it looks for asynchronous stacks only on frames it
    lists.
  - Both stop after 10,000 pending frames, as a JVM limits trace depth. Tools
    only pay when they ask. On the normal path the costs are the slot write
    (virtual in compiled code) and `Resumed`'s type check on each resume.
  - *Not done:* making `getStackFrames()` itself include them. Truffle builds
    it from a walk of the Java stack, and the spill exists precisely so that
    those frames aren't there.

> **Spike results (M0, Truffle 25.5): go.**
> - **M0-02 (#7):** a capture across 3 frames, resumed 2 and 3 times from
>   copies (above), gives independent executions. Each result equals a fresh
>   run: no leakage of locals, pending operand-stack values, or block-scoped
>   slots, and the capture comes from inside a nested scope with
>   `enableBlockScoping` on. A `Box` is shared across resumes. This holds both
>   interpreted and compiled. Resuming the original frame twice crashes.
> - **M0-03 (#11):** with call → `IsCapture` → `SuspendFrame` at every call
>   site, `fib(30)` and `tak(24,16,8)` compile to exactly the same IR and
>   machine-code size as plain calls, and run within noise (about 0–2%).
>   Return-type profiling folds `instanceof Capture`, and the `SuspendFrame`
>   branch and the temporary local disappear. That's the best case: call
>   sites whose callees return several types keep one type check per call.

### Dynamic environment

- One immutable record, `DynEnv { winders, parameterization, handlers }`,
  held in a per-context slot. Every continuation captures the current
  `DynEnv`.
- **Every** change of control, whether invoking a continuation, an escape, a
  prompt return, or `exit`, goes through a single `rewind(from, to)`. It runs
  the after-thunks and before-thunks along the path through the common
  ancestor, each in its own dynamic environment. Nothing else is allowed to
  change `DynEnv.winders`.

---

## Decisions

Each entry: the decision, why, and what was rejected.

### D1. Value representation

| Scheme | Java |
|---|---|
| exact integer | `long`; overflows into `SBigInteger` (wrapping `BigInteger`). Results that fit in a `long` are always demoted back, so each value has exactly one representation. |
| exact rational | `SRational` (normalized `BigInteger` numerator and denominator) |
| inexact real | `double` |
| complex | `SComplex { real, imag }`. Both parts are real numbers of the **same exactness**: exact (`long`, `SBigInteger` or `SRational`) or inexact (`double`). Mixing exactness makes both parts inexact. |
| boolean | `boolean` |
| char | `SChar` (`int` code point), with a cache for 0–255 |
| string | `SString`: Latin-1 `byte[]` until a character above 255 is stored, then UTF-32 `int[]`. Indexing and `string-set!` are O(1). Literals carry an immutable flag. `substring`, `string-append` and `string-copy!` copy between strings of one storage with an array copy (M8-04). |
| symbol | `SSymbol`, interned per context (held weakly, so a symbol nothing refers to is collected; M8-02), name is a `TruffleString` |
| pair | `SPair`, mutable `car`/`cdr` fields |
| vector / bytevector | `SVector` (`long[]` while every element is a fixnum, `double[]` while every element is a flonum, otherwise `Object[]`, generalizing for good on the first store that doesn't fit; M5-06) / `SBytevector` (`byte[]`), each with an immutable flag for literals |
| empty list, unspecified, eof, default object | singletons |
| procedure | `SClosure { RootCallTarget, Object[] captured }` |
| multiple values | a single value is returned as itself; zero or 2+ values are a `MultipleValues` (`Object[]`) |
| record | `SRecord { SRecordType }`, a static object of Truffle's Static Object Model whose fields are its record fields (M7-04) |
| promise | `SPromise { State }`, `State { done, value }`: R7RS §7.3's promise and the state a forced `delay-force` chain shares (D9) |

- `eq?` compares `long`, `double` (as `eqv?` does), `SChar` and `boolean` by
  value and everything else by identity, so boxing never leaks into results: a
  flonum in a local or in a `double[]` vector is unboxed (M5-06, M7-03), and
  boxed anew each time it is read as an object.
- **Full numeric tower.** Exact and inexact integers, rationals, reals and
  complex numbers, with `(scheme complex)` and `(scheme inexact)`.
  - **Canonical forms.** Every arithmetic result is normalized: a big result
    that fits in a `long` is demoted, a rational with denominator 1 becomes an
    integer, and an exact complex with an exact zero imaginary part becomes a
    real. An inexact zero imaginary part stays complex, so
    `(real? -2.5+0.0i)` is `#f` and `(real? -2.5+0i)` is `#t`, as R7RS §6.2.6
    shows.
  - **Exactness.** `exact` on a `double` gives the exactly equal rational (via
    `BigDecimal`), never an approximation.
  - **The reader** handles every R7RS number syntax: radix and exactness
    prefixes, `n/d`, decimals with exponents, `+inf.0` and `+nan.0`,
    rectangular `a+bi`, and polar `r@θ`.
  - **Transcendental functions** on complex numbers follow the branch cuts in
    R7RS §6.2.6.
  - **Dispatch.** Arithmetic operations specialize on `long` and `double` as
    fast paths, with overflow checks on the `long` path. `SBigInteger`,
    `SRational` and `SComplex` go through one generic path marked
    `@TruffleBoundary`, so compiled code stays small for the common cases.
- **Records (M7-04)** are static objects (Truffle's Static Object Model):
  their fields are fields of the object itself, so a record is one
  allocation and a field access is a field load. A language has one
  `StaticShape` per number of fields, every field an `Object`, shared by all
  the record types with that many fields (`RecordShapes`); the record's type
  is a field of `SRecord`. On HotSpot a shape is a class the model generates;
  in a native image it is an array-based layout, built at run time without
  reflection configuration. Each evaluation of a `define-record-type` makes
  a new `SRecordType`, and a constructor, predicate, accessors and modifiers
  that are primitives made for that type (`%record-constructor`,
  `%record-accessor`, ...), named by their bindings: their nodes hold the
  type and the fields' properties as constants, so call sites run them in
  place (M5-09), and an accessor compiles to a type check and a field load.
  Interop and the debugger see the fields as read-only members (D10).
- *Rejected:* `MutableTruffleString` for strings (no compaction, conversion
  costs on every operation); Java `String` (UTF-16, immutable); inexact-only
  complex numbers (the chibi suite assumes the full tower); complex numbers
  with mixed-exactness parts (adds cases to every operation for almost no
  real use); records as an `Object[]` of fields with accessors that are
  closures over a generic `%record-ref` (two allocations per record, and a
  Truffle call, index unboxing and an array bounds check per access: what
  M7-04 replaced); a shape per record type (a generated class per evaluation
  of a definition, so a definition in a loop or in `eval` makes classes
  without bound; the type check it would save is one field load); promises
  as pairs whose car is a unique tag (what they were until promises got a
  type: `pair?` held for them, and `car` and `set-car!` took them apart);
  promises as a record type of the prelude's (they would write as
  `#<record promise>` and show interop their state as members, for no gain
  in speed over `SPromise`).

### D2. Closures: flat closures with assignment conversion

- The compiler computes each lambda's free variables and copies their values
  into `SClosure.captured`. The array is never written once the closure
  exists, so it is `@CompilationFinal(dimensions = 1)` (M5-10): a closure
  that is a compilation constant has constant captured values. The one
  exception is Fixing Letrec (M5-05, below): `PatchCaptured` writes a slot of
  a closure the same `letrec*` made moments before, and only between making
  the closures of a run of lambda inits and running any other code, so no
  call, no other closure and no compiled code can have read the slot yet.
- **Any variable that is the target of `set!` lives in a heap `Box`.** The
  frame holds the box, never the value. `letrec*` and internal `define`
  variables are boxed when a closure captures them, unless they are *fixed*
  ("Fixing Letrec", Waddell, Sarkar and Dybvig 2005; M5-05,
  `scheme.compiler.Closures`): never assigned, bound to a lambda, and not
  mentioned by any init before the last non-lambda init that precedes their
  own. Then the only closures that can capture a fixed variable before it is
  initialized are the lambdas of the run of lambda inits it ends; the code
  generator patches its value into them right after making its own closure.
  In its own lambda's root, a fixed variable is the running closure (frame
  argument 0), so it takes no captured slot, and a tail call of it is a
  plain loop.
- `((lambda (x ...) body) arg ...)` with as many arguments as parameters,
  which `let`, `or` and `case` expand to, makes no closure (M5-05): its
  variables are locals of the root that runs it, the body runs in that root
  (in tail position if the call was), and a boxed variable gets its box once
  every argument has returned, so re-entering an argument's continuation
  binds a fresh one, as a call would.
- Why: Scheme continuations capture control, not variable values. Every
  re-entry must see the same storage locations. Copying a frame on resume is
  correct only if the frame holds no mutable variables. Flat closures also
  keep frames virtual, so Graal's escape analysis works.
- *Rejected:* `enableMaterializedLocalAccesses`, where closures point at the
  outer frame. It forces outer frames onto the heap, and with frame copying it
  gives **wrong semantics**: a closure would keep writing to the original
  frame after a resume has switched to a copy.
- **The M0 spike must include a `set!`'d variable shared across two resumes.**

### D3. Expander: Java, sets of scopes

- The expander is written in Java and uses sets-of-scopes hygiene (Flatt
  2016). It produces a core AST, which the compiler turns into Bytecode DSL
  builder calls.
- Core forms: `quote`, `lambda`, `if`, `set!`, `define`, `begin`, `letrec*`,
  `define-syntax`, `let-syntax`, `letrec-syntax`, `syntax-rules`,
  `include`, `include-ci`, `cond-expand`, `define-library`, `import`,
  `quasiquote` (with `unquote` and `unquote-splicing`), plus the internal form
  `%prompt`.
- All other derived syntax comes from the `syntax-rules` definitions in R7RS
  §7.3, placed in the prelude. Exceptions: `guard`, which is a prelude macro
  over the prompt-based `%guard` procedure (D4) rather than §7.3's
  definition, and anything a profile later shows needs a core form.
- Why: Java is fast at startup, has no bootstrapping problem, and agents can
  unit-test it. Sets of scopes is the most robust hygiene model, and it
  extends to `syntax-case` (R7RS-large, Sable) without a rewrite.
- *Rejected:* a self-hosted expander in Scheme (needs bootstrapping, slow
  warm-up); explicit renaming or syntactic closures (harder to get right with
  nested definitions and `define-library`).

### D4. Delimited capture internally; `guard` uses a prompt

- The runtime supports prompts with tags. A capture stops at the nearest
  prompt with a matching tag.
- `guard` is a prelude macro over the procedure `%guard`, which takes the
  body as a thunk and the clauses as a procedure. It installs a prompt (with
  a fresh tag) and a handler.
  On `raise`, the handler captures up to the guard's prompt (only the frames
  between `raise` and `guard`), returns to the guard frame, rewinds `DynEnv`,
  and evaluates the clauses there, in the guard's own continuation as the spec
  requires. If no clause matches, it reinstates the captured segment, under a
  new prompt with the same tag, and re-raises with `raise-continuable` in the
  original dynamic environment.
- It needs no expander support: `%prompt` is `%call-with-prompt` with a thunk,
  and the capture is `%capture-to-prompt` (M2-03), so `%guard` is ordinary
  prelude Scheme.
- Why: the §7.3 reference `guard` captures a full continuation on every
  entry, which is too slow. A prompt gives exact R7RS semantics at a cost
  proportional to the distance between `raise` and `guard`.
- Prompts are not exposed to Scheme code in v1. SRFI-226 can be added later.

### D5. Java builtins never call Scheme

- Java builtins are leaf operations. A builtin that must invoke a procedure
  (`apply`, `call/cc`, `call-with-values`, `eval`, `load`) does it **only
  by returning a `TailCall` request** to its call site's trampoline.
- Anything that needs a non-tail call (`map`, `for-each`, `vector-map`,
  `string-for-each`, `dynamic-wind`, `raise`, `with-exception-handler`,
  parameter converters, `call-with-port`) is written in the Scheme prelude on
  top of `%`-primitives.
- Why: Java frames can't be captured. If no Java frame ever sits between two
  Scheme frames, every continuation is capturable, and R7RS's rule that
  `map` must not change earlier results on re-entry holds automatically.

### D6. Top-level programs and the REPL

- A program, or a library body, compiles into **one root** with the program's
  prompt around it. Top-level definitions store into global cells. Re-entering
  a continuation captured in one top-level form therefore carries on through
  the forms that follow it.
- Each REPL input is its own root and its own prompt.
- `eval` and `load` compile, then tail-call the resulting root (D5).

### D7. Libraries and globals

- Each library is instantiated once per context, on first import. Its globals
  are cells, and exports map names to cells. Assigning to an imported binding
  is an error.
- Every global cell has an `Assumption` that is invalidated on redefinition.
- Standard libraries are `.sld` files under `src/prelude/`, built on an
  internal primitive library `(r7rs %primitives)`. User libraries are found on
  a search path (`-I dir`).
- `environment` returns a fresh environment importing the given libraries.
  `interaction-environment` is the mutable REPL environment.
- **Exports and imports name one binding each (§4–5 audit).** R7RS §5.6.1:
  an export spec's identifier *"names a single binding defined within or
  imported into the library"*, so exporting a name the library neither
  defines nor imports is a syntax error when the library loads, and so are
  two export specs with one external name and different bindings. R7RS
  §5.2: *"In a program or library declaration, it is an error to import the
  same identifier more than once with different bindings ... However, a REPL
  should permit these actions"*: a syntax error in a program or a library,
  while in the interaction environment the last import wins. Importing one
  binding twice (from two libraries that re-export it, or under two names)
  is fine. Redefining an imported name with `define` in a program shadows
  the import (D15), which R7RS also calls an error but which is cheap to
  allow and deliberate when written.
- **Auxiliary syntax is bound.** `else`, `=>`, `...` and `_` (R7RS §4.2,
  §4.3.2, Appendix A) are core-form keywords of `(r7rs %primitives)` that
  are a syntax error wherever they're expanded; `syntax-rules` literals
  match them by binding (`free-identifier=?`) as before, and `...` and `_`
  in patterns by name. They used to be unbound cells, so `else` as an
  expression was an "unbound variable" when it ran, and the libraries that
  export them exported variables nothing defined.

### D8. Errors

- Any Scheme object can be raised. `raise`, `raise-continuable` and
  `with-exception-handler` live in the prelude and work through
  `DynEnv.handlers`. Calling a handler does not unwind the stack.
- Leaf Java code reports errors such as `(car 1)`, an out-of-range index, or
  a missing file by throwing `SchemeError` (an `AbstractTruffleException`)
  carrying an `SErrorObject { message, irritants, kind }`. The operation that
  called the builtin catches it and calls Scheme `raise` **in the same frame**.
  The error therefore reaches Scheme handlers exactly as if `raise` had been
  called there.
- `kind` is one of `FILE`, `READ` or `OTHER`, and is what `file-error?` and
  `read-error?` test.
- Internal Java exceptions (`NullPointerException`, `ClassCastException`,
  assertion failures) are **never** converted. They crash as internal errors,
  so bugs can't hide behind Scheme handlers.
- An uncaught `raise` at top level prints a message and exits with a nonzero
  status. Differential tests compare stdout and the exit status, never the
  wording of error messages.
- **An uncaught error runs the after-thunks it skips (M7-06).** When an
  error no handler catches leaves a root prompt (a program, a REPL input, a
  library body, an embedder's call, D10), the prompt first runs the
  after-thunks of every `dynamic-wind` it leaves, innermost first, each in
  its own dynamic environment, through the prelude's `%unwind-root` (the one
  `%rewind`, as `exit` does; D5: the prompt is not a builtin, and the
  thunks are Scheme). Then the error goes on to the launcher or the
  embedder. R7RS §6.10: *"before is called whenever execution enters the
  dynamic extent of the call to thunk and after is called whenever it exits
  that dynamic extent"*; R7RS doesn't say what an uncaught error does
  (§6.11 only says how handlers are found), and the context goes on after
  it (the next REPL input, the embedder's next call), so control has left
  those extents, as it does when `exit` runs them (§6.14). Before M7-06 they
  didn't run; chibi-scheme doesn't run them for a program either, so no
  differential test covers it.
  - An after-thunk that raises an uncaught error replaces the error: the new
    one leaves the after-thunks outside it in turn, so they still run. One
    that transfers control (calls a continuation of the prompt, exits) ends
    the unwinding there, as an escape from an after-thunk during a jump
    does: the prompt goes on from where it went, and returns its value.

### D9. Spec traps decided up front

- `equal?` must terminate on circular structures (R7RS §6.1). Use the
  union-find algorithm with a fast path for small trees (Adams & Dybvig).
- `write` uses datum labels for cycles, `write-shared` labels all shared
  structure, and `write-simple` uses no labels. `display` must also terminate
  on cycles.
- `list?` returns `#f` for circular lists (tortoise and hare).
- **Circular lists are not lists, and list procedures raise on them (M7-06).**
  R7RS §6.4: *"By definition, all lists have finite length and are
  terminated by the empty list"*, so passing a circular list where a list is
  expected is an error (§1.3.2: *"it is an error for a procedure to be
  passed an argument of a type that the procedure is not explicitly
  specified to handle ... Implementations may signal an error"*). We signal
  `"<who>: not a proper list"` with the list as the irritant (which `write`
  prints with datum labels), instead of looping forever, from `length`,
  `reverse`, `list-copy`, `append` (all but the last argument),
  `list->vector`, `list->string`, `apply`, and `memq`, `memv`, `member`,
  `assq`, `assv` and `assoc` when the list doesn't hold the object (one that
  does is found as in any list). They check with Floyd's tortoise and hare
  in the walk itself (a pointer at half speed, which meets the walk only on
  a cycle), and `member`'s and `assoc`'s Scheme loop with a predicate with
  Brent's (a pair moved up after 1, 2, 4, ... steps), so a proper list costs
  one extra pointer move every other pair. `map` and `for-each` keep R7RS
  §6.10's rule: *"The lists can be circular, but it is an error if all of
  them are circular"*: they stop at the shortest list, and raise up front
  only when every list is circular. `list-tail`, `list-ref` and `list-set!`
  walk a bounded number of pairs and need no check; `equal?`, `write` and
  `display` already terminate (above).
- **Circular code is a syntax error.** R7RS §2.4: *"It is an error for a
  program or library to include circular references except in literals. In
  particular, it is an error for quasiquote (section 4.2.8) to contain
  them."* The expander walks code as a tree, so a form that contains itself
  would expand forever (as `(eval '#0=(car #0#) env)` did until the §4–5
  audit). `eval`'s datum, and every datum the reader makes with a datum
  label that refers into itself (program text, libraries, `include`d and
  `load`ed files), are checked first (`CircularCode`): a cycle that isn't
  inside a `(quote datum)` or a vector (self-evaluating, §4.1.2) is a syntax
  error. Inside `quasiquote` and `syntax-rules`, whose templates the
  expander walks, nothing may be circular. The forms are recognized by name
  before expansion, so a cycle handed to another macro is an error even if
  that macro would quote it (§2.4 allows cycles only in literals). Shared
  structure that isn't circular is fine. Data read by `read` is not checked.
- **§6's "it is an error" cases (the §6 audit).** Where a standard procedure
  is given what R7RS §6 calls an error, the primitive signals one when the
  check is cheap, and the R7RS errata decide what counts as an error:
  - `boolean=?` and `symbol=?` signal on non-booleans and non-symbols
    (errata 3 and 29: *"it is an error to apply boolean=? to
    non-booleans"*). `list-ref` signals when k isn't less than the length
    (erratum 20: *"(list-ref '(x) 1) is an error"*), as do `list-tail` past
    the end and `list-set!`.
  - So do a bad index, length or range (start after end), a byte outside
    0–255, a radix other than 2, 8, 10 or 16, a non-scalar value for
    `integer->char`, an argument of the wrong type, and mutating a literal
    or `symbol->string`'s result (§6.5); `exact` of an infinity or a NaN,
    `exact-integer-sqrt` of anything but a non-negative exact integer, and
    integer division (`floor/`, `truncate/`, `quotient`, `modulo`, ...) by
    any zero; `utf8->string` of invalid UTF-8 (§6.9: *"It is an error for
    bytevector to contain invalid UTF-8 byte sequences"*).
  - Not signalled, on purpose: `/` with an exact zero divisor and an inexact
    argument gives IEEE's result (`(/ 5. 0)` is `+inf.0`; §6.2.6: *"It is an
    error if any argument of / other than the first is an exact zero"*), so
    the typed flonum paths (M7-03) need no extra test. `(expt 0 z)` with a
    negative real part returns a number when z is inexact (`(expt 0 -1.)` is
    `0.0`; §6.2.6 allows *"either an error is signalled or an unspecified
    number is returned"*). `memq`, `memv`, `member`, `assq`, `assv` and
    `assoc` return `#f` at an improper list's tail, and `map` and `for-each`
    stop there (a non-list argument is an empty list). `number->string` of
    `-0.0` in radix 2, 8 or 16, which no string reads back as (§6.2.7: *"It
    is an error if no possible result makes this expression true"*), is
    `"#i-0"`. A parameter object called with one argument sets its value, as
    chibi's do; R7RS's take none (§4.2.6).
- `exit` runs all outstanding after-thunks through `rewind`, then exits the
  context through Truffle. `emergency-exit` skips the after-thunks.
- **Exit statuses.** R7RS §6.14: *"If obj is #f, the exit procedure should
  communicate to the operating system that the program exited abnormally.
  Otherwise, exit should translate obj into an appropriate exit value for
  the operating system, if possible."* No argument and `#t` are 0, an exact
  integer from 0 to 255 is itself, and everything else is 1: `#f`, other
  objects, and exact integers outside 0–255, which a process status can't
  hold (POSIX keeps its low 8 bits, so `(exit 256)` would otherwise succeed
  with 0, and `(exit -1)` or `(exit 2³²)` would wrap). Embedders see the same
  status (`PolyglotException.getExitStatus()`, D13) as the process does.
  *Rejected:* 255 for out-of-range integers (it reads as a status the
  program chose; 1 is the generic failure `#f` already gives).
- `delay`, `delay-force` and `make-promise` use the space-safe reference
  implementation from §7.3. A promise is a type of its own (`SPromise`, D1;
  R7RS §3.2 doesn't list promises among the disjoint types, but nothing says
  a promise may be a pair, and one that is lets `car` and `set-car!` take
  it apart): its state, done and the value or not done and the thunk, is an
  object the promises of a forced `delay-force` chain share, so `force`
  (lazy.scm) loops in constant space. A promise forced again while it is
  being forced keeps the value of the forcing that completes first, and
  `make-promise` of a promise is that promise (§4.2.5).
- Unicode: use the JDK's tables. Character procedures use simple case
  mapping, string procedures use full case mapping (`(string-upcase "ß")` is
  `"SS"`), and everything uses `Locale.ROOT`, never the default locale.
- Floating-point output: shortest round-trip digits and always a decimal
  point (R7RS §6.2.7: *"the result contains a decimal point and is expressed
  using the minimum number of digits (exclusive of exponent and trailing
  zeroes) needed"*; the digits are the JDK 21+ `Double.toString`'s, or one
  where that is enough and the JDK writes two, as for `5.0e-324`), laid out
  as chibi-scheme does (C's `%g`: `1.5e-07`, `100.0`), except that a
  one-digit mantissa gets its `.0` (`1.0e+21`, where chibi writes `1e+21`),
  in R7RS syntax (`+inf.0`, `-inf.0`, `+nan.0`). Complex numbers print in
  rectangular form, `a+bi`.

### D10. Platform scope for v1

- **Single-threaded contexts.** `isThreadAccessAllowed` returns
  `singleThreaded`.
- **All I/O goes through `TruffleLanguage.Env`**: `TruffleFile` and the Env's
  streams, never `java.io` or `java.nio` directly.
- **Interop for embedders (M6-02).** Scheme values answer Truffle's
  `InteropLibrary` as what they are, each with the language `scheme`, its
  `write` syntax as display string (`Value.toString()`), and a meta object:
  - Numbers: fixnums and flonums are `long` and `double`. A bignum is a
    number that fits in a `BigInteger` (never a `long`: it would have been
    demoted, D1), and in a `double` or `float` only when the conversion is
    exact. An exact rational is a number that fits in no integer type, and
    in a `double` or `float` only when exact (`1/2` yes, `1/3` no: Java reads
    it as a string). A non-real complex number is not an interop number
    (interop's numbers are real); its display string is `1+2i`.
  - Strings, and characters as one-character strings (`asString` copies).
    Symbols are not strings: their name is their display string, so a
    symbol never turns into a string by crossing and back. Booleans are
    `boolean`.
  - Vectors and bytevectors are arrays (bytevector elements are integers
    0–255); literal ones are read-only (R7RS §3.4). Zero or several values
    are a read-only array. Lists are iterables (`hasIterator`), walked lazily
    up to the first non-pair; `'()` is null *and* an empty iterable, the
    unspecified value is null. A pair that doesn't start a proper list (its
    cdr chain ends in an atom other than `'()`, or is circular) is also an
    object with the members `car` and `cdr` (`hasMembers` *and*
    `hasIterator`), so Java sees an improper list's last cdr, which the
    iterator leaves out. A proper list's pairs have no members: an object
    with members converts to a `Map` where the host asks for an `Object`
    (polyglot prefers members to iterators), and a list must stay an
    `Iterable` there. `hasMembers` walks the cdr chain each time interop asks
    (Floyd's cycle detection; nothing is cached on `SPair`, and `set-cdr!`
    can change the answer). The members are writable, since `set-car!` and
    `set-cdr!` can write any pair: pairs carry no literal flag (D1). Records are objects with their fields as
    read-only members.
  - Procedures are executables. Each `execute` runs the procedure under a
    root prompt of its own, as a program or REPL input runs (D6), so tail
    calls, captures and depth spills stay inside. A continuation captured
    during one call is delimited by that call's prompt: invoked during a
    later call (from Java or from Scheme), it runs the rest of the call that
    captured it, and the later call returns what that rest returns, as with
    REPL inputs. Segments are immutable (copied on resume), so any number of
    such re-entries leave both calls intact. A wrong number of arguments is
    a Scheme error, not an interop `ArityException`, so primitives,
    variadic procedures and `case-lambda` behave alike.
  - Meta objects are R7RS §3.2's disjoint types, named as their predicates
    without `?` (`boolean`, `number`, `char`, `string`, `symbol`, `pair`,
    `null`, `vector`, `bytevector`, `procedure`, `port`, `eof-object`), plus
    `error-object`, `environment`, `promise`, `record-type`,
    `unspecified` and `values`; a record's meta object is its record type. `long`, `double`
    and `boolean` get theirs, and their Scheme display strings (`#t`,
    `+inf.0`), through `getLanguageView`.
  - Errors and exit: an error a call doesn't handle surfaces as a
    `PolyglotException` guest error whose message is the error object's
    message and irritants (or `uncaught exception: <obj>` for a raised
    non-condition), after the after-thunks it skips (D8, M7-06); `exit`
    surfaces as an exit with its status, after the after-thunks (D9). Either
    way, the dynamic environment (parameters, handlers, winders) is the
    caller's again afterwards.
  - **The guest object is the raised object (M7-06).** R7RS §6.11: `raise`
    *"raises an exception by invoking the current exception handler on
    obj"*, any object, and `error` raises *"a newly allocated
    implementation-defined object which encapsulates the information
    provided by message, as well as any objs, known as the irritants"*. So
    `PolyglotException.getGuestObject()` is that object: the exception
    (`SchemeError`) answers the exception messages itself and delegates
    every other interop message to it (`delegateTo`, as GraalJS's thrown
    values do), so it is an exception and also, say, the number 42 or the
    symbol `boom`. An error object (from `error`, a primitive, or a syntax
    error) has its `message` and `irritants` as read-only members, what
    `error-object-message` and `error-object-irritants` return, and its meta
    object is `error-object`. Passed back into Scheme, it is the raised
    object itself (`eq?` to it), so a Scheme procedure can examine it. Its
    guest stack trace is the exception's (`PolyglotException`'s frames),
    not an interop array (`hasExceptionStackTrace` is false).
  - **Values from outside** (arguments, array elements, bindings) become the
    one Scheme representation of each: interop's integers are exact
    integers, floats and doubles flonums, a `char` a character, a string a
    fresh mutable string, and foreign numbers, strings and booleans (a host
    `BigInteger`) likewise. Anything else, Java `null` included, stays a
    **foreign value**: Scheme can store it, return it (Java gets the same
    object back) and compare it with `eq?`, `eqv?` and `equal?` (identity),
    `write` shows it as `#<foreign ...>`, and every type predicate is false,
    so a primitive given one raises a Scheme error (D8) and calling one is a
    "not a procedure" error. Truffle wraps a Java object afresh at each
    crossing, so the context keeps one wrapper per Java object, weakly, and
    the same object is `eq?` to itself across calls.
  - `context.getBindings("scheme")` (`getScope`) is the interaction
    environment (D15): its bound variables, imported or defined, are members;
    syntactic keywords are not, and `%`-names are readable but not listed.
    `putMember` is `(define name value)` there: it defines, redefines, or
    shadows an import as the REPL would; a keyword can't be redefined that
    way. Programs (D15) keep their own top level and don't appear in it.
  - Scheme code does not reach into other languages or the host in v1, so
    no host frame ever sits between two Scheme frames (D5): a call from Java
    is always the outermost prompt.
- **Out of scope:** Scheme-side access to other languages and the host,
  threads, and SRFIs. Avoid reflection and dynamic class loading: the native
  launcher (D17) is built without any reflection configuration.
- **Deferred to M5:** escape-only `call/cc` (done, M5-04), Fixing Letrec,
  vector storage strategies (done, M5-06), and the Static Object Model for
  records (done, M7-04, D1).

### D11. Build and tooling

- **Bazel builds and tests everything** (`MODULE.bazel`, Bazel 8 with
  bzlmod, `.bazelversion`). Each build step and test is cached on its
  inputs, so a change reruns only what depends on it.
- **Remote-first.** Actions and tests execute on BuildBuddy's Linux x86_64
  executors, with their shared cache, from CI and from a laptop alike
  (`--config=remote`, the default; `--config=local` offline). Builds target
  the machine Bazel runs on, so `bazel-bin/` and `bazel run` work on a Mac,
  while their actions run remotely (Java runs on the exec platform's
  GraalVM); tests target the executors' platform. Everything an action runs
  comes from Bazel (the pinned GraalVM, rules_python's Python, chibi-scheme
  built from source), plus a shell and a C toolchain from the executor image
  (`toolchains_buildbuddy`); nothing is tagged to run locally.
  `bazel test //...` is the whole pre-PR check; the repository's other
  commands are `bazel run //:r7rs -- <command>` (`tests/harness/main.py`).
- **Released GraalVM, pinned by Bazel.** The JDK that compiles, runs the
  tools and runs the tests is GraalVM CE 25.5, downloaded at a pinned sha256
  (`bazel/graalvm.bzl`; the archives nixpkgs ships), and its Graal compiler
  is the JIT. Truffle comes from Maven Central (`rules_jvm_external`, locked
  in `maven_install.json`) at **exactly** that version (25.5.5.0.3): one
  `TRUFFLE_VERSION` in `MODULE.bazel` picks both, and `//tests:versions`
  checks the JDK's `release` file against the lock file.
- **Upgrading** is a human-made change: add the release to
  `bazel/graalvm.bzl`, bump `TRUFFLE_VERSION`, `bazel run @jvm//:pin`, and
  bump nixpkgs in `flake.lock` (the dev shell's JDK) together.
- **Modules.** `java_module_jar` (`bazel/java_module.bzl`) builds the
  `scheme`, `scheme.launcher` and `scheme.test` modules, and everything runs
  with Truffle on the module path, as embedders run it.
- **Nix** (`flake.nix`) provides the dev shell: `bazel` (bazelisk), Python,
  `gh`, chibi-scheme and a GraalVM JDK for running and embedding outside
  Bazel. Bazel doesn't need it: it pins its own JDK and Python, and builds
  the differential tests' chibi-scheme from the pinned source of the same
  release (`bazel/chibi.bzl`, with the host's C compiler and `make`), so
  every action can run on another machine. `nix develop .#bench`
  adds the other R7RS implementations for the comparative benchmark report;
  CI's steps use `nix develop .#ci`, which has only `bazel`.
- **CI runs only what runs on every commit**, on Buildkite
  (`.buildkite/pipeline.yml`, hosted `linux-small` agents, the Nix
  `.#ci` shell): the lint tests and the other tests. The required check is the
  build (`buildkite/truffle-scheme`); the pipeline says which steps can
  fail it. Conformance is a test per suite section and mode (interpreted,
  compiled, under torture and owned torture), which passes when every test of the section
  passes but the suite's listed exceptions, and those fail; docs/plan.md's
  status block is what they check, and the per-section table is a report
  of their results.
  Benchmarks, the threshold check (`bench/thresholds.json`, measured on the
  maintainer's Mac) and the native image are local commands, by the
  maintainer's choice.
- **Measured** when Bazel replaced mx (same commit; Apple M3 Max, 16 cores,
  shared with other builds at load 15–25; GitHub's `ubuntu-26.04` runners,
  CI's until Buildkite replaced GitHub Actions in #113):

  | | mx | Bazel |
  |---|---:|---:|
  | Local, from clean (downloads cached) | 7.2 min | 5.7 min (110 s build + 234 s tests) |
  | Local, again with no change | 6.7 min | 1 s |
  | Local, after a code change in `src/builtins` | 6.7 min | 4.3 min (178 of 202 tests rerun) |
  | Local, after editing `docs/plan.md` or one `tests/diff` program | 6.7 min | 1 s (1 or 3 tests rerun) |
  | CI, no cache | 4.9 min on 7 runners (17 runner-min) | 10.3 min on 2 runners (13.7 runner-min) |
  | CI, cache from an earlier run of the same change | same | 1.7 min (all cached) |

- *Rejected:* mx, GraalVM's build tool, which this project used until the
  measurements above: it reran every check and test on every run and
  needed CI sharding to stay fast. The Nix GraalVM as Bazel's JDK: a local
  path can't run on remote executors, and the pinned download is the same
  file. Importing the `truffle` suite from a pinned graal master commit with
  a matching labsjdk: a ~1.5 GB graal checkout plus a labsjdk whose JVMCI
  version must track the graal commit; revisit only if we need an unreleased
  Truffle fix. Maven or Gradle (no test caching across machines); Buck2
  (thinner open-source JVM support). Splitting `src/runtime` into libraries
  (values and printer below the rest): values need the context and the
  language for interop by design (D10), so the split needs hooks, and it
  reruns no fewer tests (every test loads the whole language) for a small
  compile-time gain.

### D12. Continuation strategy

- Re-entrant continuations come from Bytecode DSL yields at call sites, the
  segmented stack described above.
- *Rejected:* Loom's `jdk.internal.vm.Continuation` as the base (one-shot,
  can't be cloned, internal API); full CPS conversion (gives up Truffle's frame
  virtualization on every call); escape-only `call/cc` alone (not
  conformant).
- **Status: go** (M0-04). The M0 spike confirmed both open questions. See
  "Spike results" under "Continuations".
- Segments that only a prompt driver can reach (a stack-depth spill's) are
  resumed in place, once; any segment a continuation may reach is copied on
  every resume ("Owned segments" under "Continuations"). Copying every
  resume, as before, cost a spill-heavy recursion a third of its time.

### D13. Source positions, errors and tools come from Truffle

- **Source sections.** Every syntax object has a `SourceSection` (M3-01).
  The expander keeps it on the core AST, and the code generator wraps the
  operations it emits for a form in Bytecode DSL source sections
  (`beginSourceSection`). Truffle then maps every bytecode index to a file,
  line and column, so guest stack traces read `even?(lib.scm:12:3)` instead
  of `even?(Unknown)`. `storeBytecodeIndexInFrame` keeps the location exact
  across yields (continuation capture) and in compiled code.
- **Errors are Truffle exceptions.** `SchemeError` stays an
  `AbstractTruffleException` (D8). Through interop it reports its exception
  type: syntax errors from the reader and expander are `PARSE_ERROR`, and
  input that merely stops early is incomplete source, so a REPL can ask for
  more; `exit` is `EXIT`; everything else is `RUNTIME_ERROR`. Each has its
  source location. Syntax errors name the file as well as the line and
  column. Message wording stays outside conformance (D8).
- **Tools.** The generated bytecode carries Truffle's standard
  instrumentation tags (root, root body, statement, call, expression) with
  `enableTagInstrumentation`, so the debugger (DAP), the Chrome inspector and
  other instruments work on Scheme code, breakpoints and stepping included.
  Instrumentation is off until a tool attaches, so it costs nothing
  otherwise.
- **Allocations (M7-07).** The language reports the Scheme objects it makes
  to the context's `AllocationReporter`, which the memory tracer
  (`--memtracer`) listens to: closures and rest-argument lists where the
  bytecode makes them, and the results of the primitives whose
  `PrimitiveSpec` says they allocate (`allocating()`, or `allocatingList()`
  for a list of new pairs), wrapped in a reporting node. Each report has an
  estimate of the object's size (`Allocations.sizeOf`). Until a listener is
  active in some context, a report is one check of a language-wide
  assumption that the first active listener invalidates, so compiled code
  pays nothing. The reporter is the engine's, so each context removes the
  listener it added when it is disposed (M8-02): else a shared engine kept
  every closed context's language instance and its code. Boxes aren't reported: an allocation listener takes only
  interop values. An object is reported once it exists, so `onEnter` and
  `onReturnValue` follow each other. Frames on the heap reach error traces, the debugger and the
  sampler too: see "Spilled frames in tools" above.
- **Scopes, names and evaluation in a frame (M6-05).** A frame's scope
  (`tagTreeNodeLibrary`, `FrameScope`) shows the variables Scheme code there
  can refer to: the locals live at that bytecode index (a let's and a
  letrec*'s locals are scoped to their block, so an inner binding hides an
  outer one), the running closure's captured variables (their names are
  root fields, `capturedNames`), and a fixed letrec* variable's own closure.
  A `Box` shows its contents. Compiler temporaries and the variables of a
  macro's template (bound by an identifier with an introduction scope, which
  no code the user wrote can refer to) are unnamed locals, so they don't
  show. Only boxed variables are writable, from the scope or by `set!` in
  evaluated code: every other variable is immutable as Scheme sees it, and
  its frame slot or captured value is a copy that closures and continuation
  segments (immutable, AGENTS.md rule 3) share. `DebugStackFrame.eval`
  compiles the code (`parse(InlineParsingRequest)`) as a body whose
  parameters are the frame's variables, and passes a boxed variable as its
  Box. Its global names resolve in the frame's own top level (M7-07): every
  root of an isolated program (D15) or of a library (D7) records the unit's
  top-level scope and namespace (`SchemeRootNode.getTopLevel`, transient, so
  not in the prelude image), and code evaluated in its frames sees exactly
  the unit's imports and definitions, exported or not; elsewhere (the
  standard libraries restored from the image among them), the interaction
  environment. Evaluated code may not `set!` an isolated program's own
  variables: the program was compiled as a closed unit, assuming only its
  own code assigns them (`EscapeAnalysis`). A library isn't closed (a
  macro it exports may assign its variables from another unit), so code
  evaluated in its frames may. A procedure's frames are named by its
  binding (`define`, `let`, `letrec*`, `set!`, through a let body), else by
  its location, `<lambda@file.scm:3:5>`; a record type's procedures are
  primitives named by their bindings (D1), which have no frame when run in
  place;
  `write` still shows only binding names. A `TailCall` (what a procedure
  ending in a tail call returns, so a debugger's return value after stepping
  out of it) shows as the call it stands for, `#<tail call (g 2)>`.
- Why: Truffle already does this well; positions and tags are data we have
  (syntax objects) handed to machinery we don't have to write.
- *Rejected:* the Bytecode DSL's default scope (raw slots: temporaries as
  `local3`, boxes as objects, no captured variables, and every slot
  writable, including a heap segment's); a parent scope with the globals
  (the debugger already shows the top scope, thousands of names, by itself).
- **The standard libraries come from a prelude image (M5-03, M7-05).** A
  `PreludeImage` holds the library bodies serialized with the Bytecode DSL
  (`enableSerialization`), and the expander's state (scopes, namespaces and
  their cells, syntactic bindings, macros as their `syntax-rules` forms,
  library exports) as records that refer to each other by index. A context
  rebuilds that state with its own symbols, scopes and cells, and
  deserializes and runs the bodies: nothing is read or expanded, and a
  macro is compiled again only when it is first used. Every value in the
  image is bytes (numbers, characters, strings, symbols, pairs, vectors with
  their storage, syntax objects, source sections); a new kind of constant
  needs a record kind and a new format number (`PreludeImage.FORMAT`).
  - **Built at build time (M7-05).** The build runs a context of the
    language's classes (`prelude_image` in `bazel/java_module.bzl`), which
    writes the image as it initializes (`-Dscheme.preludeImage.write`), into
    `scheme.jar` beside the prelude (`scheme/prelude/prelude.image`). Bazel
    builds it again whenever a class or prelude file it is made from changes. So the first context of a
    process restores too.
  - **Staleness.** The image records the text of every prelude file the
    standard libraries read (and the ones they looked for and didn't find),
    the primitives' names and the core forms, and ends with a CRC32 of the
    rest. The language reads the bundled image once per JVM and uses it only
    if each of those files is byte for byte the one it bundles, the core
    forms and a context's primitives are the same, and the checksum matches;
    otherwise it logs why (`--log.scheme.preludeImage.level=FINE`) and
    expands. The comparison costs one read of the prelude files, which the
    prelude's `Source`s are then made from. Changes to the compiler itself
    are the build's to catch: the image is rebuilt with every build of the
    classes it depends on.
  - **Per JVM, as a fallback (M5-03).** Without a usable build-time image,
    the second context of a JVM that instantiates the libraries the normal
    way builds an image in memory, and later contexts restore from it. The
    first builds nothing, so a program run once pays nothing for it.
  - `-Dscheme.preludeImage=false` turns both off, and the internal option
    `scheme.PreludeImage=false` turns them off for one context (it then
    expands, and takes no part in building an image). `-Dscheme.preludeImage.file`
    names an image file to use instead of the bundled one (tests).
  - Numbers (M7-05) on an Apple M3 Max shared with other jobs (load 10 to
    20), medians of 15 (JVM) and 30 (native) fresh processes, the two
    variants interleaved; "expanding" is the same build with the image off
    (`-Dscheme.preludeImage=false`, given to native-image for the native
    launcher), which is what the first context did before:

    | | Expanding | Restoring |
    |---|---:|---:|
    | JVM: `Context` create and initialize, cold | 309 ms | 254 ms |
    | JVM: a hello-world program (the launcher's `java` command) | 380 ms | 348 ms |
    | Native launcher: a hello-world program | 16.9 ms, 27 MB | 14.4 ms, 22 MB |

    On the cold JVM, restoring costs about 70 ms against expanding's 95
    (most of it the Bytecode DSL replaying the builder calls, which is
    interpreted Java there); the rest of a cold context is the engine and the
    primitives. The image is 350 KB.
- Why per context: symbols, cells and scopes belong to a context (D1, D7),
  and compiled code embeds cells as constants, so roots can't be shared
  between contexts, and with `Context.create` each context has its own
  engine anyway. Why at build time as well as per JVM: the first context is
  the one a program run once pays for (the launcher, a script, a test
  process), and only a prebuilt image helps it; the staleness check makes
  the build step safe to forget.
- *Rejected:* sharing roots between the contexts of an engine (needs
  `ContextPolicy.SHARED` and globals looked up per context instead of
  constant cells, and doesn't help a context per engine); a hash of the
  prelude files instead of their text (needs a digest provider at startup
  for no saving: the files are read either way); checking the compiler's
  class files at run time (reading them costs more than the image saves,
  and the build already rebuilds the image with them).
- *Rejected:* hand-rolled position tracking in our own frames or exceptions
  (duplicates Truffle and doesn't reach tools).

### D14. Bodies may mix definitions and expressions

- R7RS §5.3.2 puts a body's definitions before its expressions and calls
  anything else "an error", which leaves the behaviour to the implementation.
  We accept definitions after expressions, with letrec* semantics over the
  whole body: every definition's variable is bound throughout, and the forms
  run in order (an expression before a definition is an anonymous binding).
- Why: real code does it (chibi, Gauche and Chicken accept it), it costs the
  expander nothing (partial expansion just keeps going past expressions), and
  a program that R7RS accepts means the same.
- *Rejected:* rejecting it (pedantic, breaks portable-in-practice code).

### D15. Programs are isolated; the interaction environment is not

- A program (R7RS §5.1: text whose first form is `import`, run by the
  launcher or evaluated as a file) gets its own top level: it sees exactly
  the bindings its import sets give it, its own definitions, and nothing
  else. Assigning to an imported binding is an error (D7).
- Text without a leading `import` (REPL input, `Context.eval` snippets, the
  prelude's own environment) runs in the interaction environment (D7), which
  imports every standard library and persists across inputs.
- Core forms are bindings like any other: `(r7rs %primitives)` exports them,
  the standard libraries re-export them, and the expander's by-name
  fallback for core keywords goes away outside the interaction environment.
- Why: §5.1 and §5.2 define a program's environment by its imports;
  isolation is what makes `(import (only (scheme base) car))` mean something,
  and it is how libraries already work (M3-04).

### D16. Macro-introduced top-level definitions are hygienic

- A top-level `define` (in a program, a library or the interaction
  environment) whose identifier was introduced by a macro (its scopes include
  scopes beyond the unit's top-level scope) binds a fresh cell, visible only
  to identifiers with those scopes, instead of the cell named by its symbol.
  A library exports it only if the export spec names it with the same scopes.
- Why: this is what sets of scopes (D3) already does inside bodies; without
  it, two expansions of a macro that defines a helper at top level (as
  `define-values` does) silently share one global.
- *Rejected:* by-name top-level definitions (the M3-02 shortcut).

### D17. The launcher is a GraalVM polyglot launcher

- `scheme` is built on GraalVM's launcher framework
  (`org.graalvm.launcher.AbstractLanguageLauncher`, the `launcher-common`
  library at the Truffle version, D11). That gives the standard command line
  for free: `--engine.*` and `--compiler.*` options, `--log.*`, `--inspect`
  (Chrome DevTools), `--dap` (debug adapter), `--cpusampler` and
  `--memtracer` when those tools are on the module path, `--help`, and
  `--version`.
- With a file, it runs it as a program (D15) with the remaining arguments as
  `command-line`; `-I dir` adds to the library path; with no file, it starts
  a REPL in the interaction environment that reads until a datum is complete
  (D13's incomplete-source signal) and prints each input's values. REPL
  input is an interactive source: it runs in the interaction environment
  even when it starts with `import` (which then adds to that environment),
  and the language writes its values itself, as `write` does, one per line
  (`isVisible` is false, so Truffle doesn't print them again).
  - **Every form's values (M7-07).** The expander wraps each top-level form
    of an interactive source in a call to `%repl-write`, which writes the
    form's values as it completes and returns them, so `1 2 (values 3 4)`
    writes 1, 2, 3 and 4. A form a top-level `begin` splices counts as one;
    a definition writes nothing. The input is still one program: an error
    in one form skips the forms after it.
  - **One input (M7-07).** The REPL reads its lines with `%read-line` from
    the console input port (the initial `current-input-port`), through
    interop, so it and the Scheme code it runs share one input and its
    buffering: `(read-line)` typed at the REPL reads the next line, and the
    REPL goes on with what Scheme code leaves. Two readers each buffering
    the process's stdin would race for its bytes.
  - **Line editing (M7-07).** When stdin and stdout are a terminal, the
    context's standard input is a stream of lines read with JLine (the
    shadowed JLine 3 that `launcher-common` already needs), with editing,
    the `> ` and continuation prompts, and a history kept in
    `~/.scheme_history` (or `$SCHEME_HISTORY`; empty for none). Lines are
    read only when a reader needs one; the lines Scheme code reads have no
    prompt and stay out of the history. Ctrl-C drops the unfinished input.
    JLine's shell-like parsing is off (quotes, escapes, `!` history
    expansion), so Scheme text is taken as typed. Without a terminal, the
    path is the one tests and pipes use: no JLine, no prompts. JLine
    reaches the terminal through the FFM API, so the JVM launcher gets
    `--enable-native-access=org.graalvm.shadowed.jline`; JLine's own
    native-image configuration registers the downcalls for the native
    launcher, whose build takes the same `--enable-native-access` (M8-07:
    without it, the executable printed the JDK's restricted-method warning
    before the first prompt). The `terminal` tools test fails on any
    warning there, for either launcher.
    The REPL adds each input to the history itself once it is complete, as
    one entry however many lines it took (M8-06); JLine adds none, and a
    recalled entry shows its later lines with the continuation prompt.
  - **Interrupts (M8-06).** On a terminal, Ctrl-C while an input runs calls
    `Context.interrupt` (`Interrupter`, in the launcher), which stops the
    evaluation at its next Truffle safepoint; the REPL prints `Interrupted`
    and reads the next input in the same context. The handler is the
    terminal's (`Terminal.handle(Signal.INT, ...)`), which JLine replaces with
    its own while it reads a line, and gives back after. JLine's native
    signals are off: with them, each of those swaps unregistered the
    process's handler before registering the next, and a Ctrl-C in between
    got the JVM's default handler and ended the process (the pty test did,
    one run in three, when Ctrl-C came as `read-line` started). Instead the
    launcher registers SIGINT, SIGWINCH and SIGCONT once, through JLine's
    `Signals` (`sun.misc.Signal` by reflection, which already worked in the
    native launcher), and passes each to the terminal's handler of the
    moment. While JLine reads a line, the terminal is in raw mode (ISIG off),
    and Ctrl-C is a byte that JLine's keymap binds to its `interrupt` widget;
    JLine's "software signals", which also raise INT for that byte as its
    input thread reads it, are off (a system property the launcher sets),
    since that second interrupt of one key ended the next line read too, now
    and then (the prompt shown twice). Ctrl-C while JLine reads a line for the REPL drops the line as before,
    and while it reads one for Scheme code it interrupts the evaluation: the
    read returns an empty line once the interrupt has been submitted (its
    thread waits for the evaluation), so the evaluation stops at its next
    safepoint, at the latest when the read's primitive returns, instead of
    possibly ending first. The interrupt runs on its own thread (it waits
    for the evaluation to stop), and the REPL waits for it before reading
    again, so it never reaches the REPL's own reads. Without a terminal, SIGINT ends the
    process, as it does for a program.
    - The runtime needs nothing for it: Bytecode DSL roots poll at
      back-edges, and Truffle polls when a root returns, so a loop (a
      self tail call is a back-edge), the trampoline's tail calls, a
      recursion spilled to the heap, and `call/cc` all stop. Primitive loops
      don't poll, and needn't: each walks data the program built (cycles
      are detected, D9), so it ends in time proportional to that.
      Truffle's interrupt exception isn't a `SchemeError`, so no Scheme
      handler (`guard`, `with-exception-handler`) catches it.
    - **The after-thunks an interrupt skips don't run**, unlike an uncaught
      error's (D8, M7-06). R7RS doesn't define interrupts. While an
      interrupt is in progress, Truffle throws it again at every safepoint
      the thread polls, so Scheme code can't run until the evaluation has
      left the context: an after-thunk would itself be interrupted at its
      first call or loop. And an interrupt is for code that doesn't stop,
      which an after-thunk can be. The root prompt still puts the dynamic
      environment back in its Java `finally` (parameters, handlers, the
      current ports), as after an error, and the spill depth is restored the
      same way, so the next input runs as the one before it did.
    - A Ctrl-C while an interrupt has waited a second or more (the
      evaluation is in host code that never polls, such as opening a FIFO)
      saves the history, the running input in it, and ends the process with status 130, as SIGINT's
      default action would; a quicker second Ctrl-C does nothing more.
    - With assertions on, an interrupt that lands at a Bytecode DSL root's
      return fails an assertion in Truffle (docs/upstream.md); the launcher
      runs without them.
- **The evaluation thread.** The launcher creates, uses and closes its
  context on a thread of its own with a 256 MB Java stack
  (`EvaluationThread`), and the launcher's thread waits for it and takes
  over what it throws (an exit status, as the framework's
  `AbortException`). Deep non-tail recursion stays on the Java stack until
  it has used most of the thread's (the stack-depth spill, M8-01), and a
  spilled frame costs about 1 KB and 240 ns, so a thread with a 1 or 2 MB
  stack (a JVM's default) spilled most of a recursion tens of thousands
  deep: `ack(3,12)` (32,765 deep) steady went from 24.6 s to 2.4 s. Everything
  around evaluation runs on that thread: the REPL and its reads (JLine's
  terminal reads, which Ctrl-C interrupts, are that thread's), `exit`
  statuses, stdin, the tools, and the native launcher's pre-initialized
  context, which `Context.Builder.build` takes over there.
  - *Unless a size is given.* A stack size given to the VM (`-Xss` or
    `-XX:ThreadStackSize`, also through `JDK_JAVA_OPTIONS` or
    `JAVA_TOOL_OPTIONS`; the native executable's `-Xss` or
    `-XX:StackSize`), read from `RuntimeMXBean.getInputArguments`, keeps
    evaluation on the launcher's own thread, which has that stack, as before:
    the spec and space tests run the launcher with `-Xss256k`, so that
    their deep recursions still spill (rule 4). `--stack-size=<size>` (or
    the native launcher's `--vm.Xss<size>`) sets the evaluation thread's
    stack, and `--stack-size=default` evaluates on the launcher's thread
    (the JVM's default stack; the native launcher's is the process's main
    thread, 8 MB). If the system won't reserve the stack (a limit on
    address space), the launcher evaluates on its own thread. `SchemeMain.run`
    (in-process, for tests) evaluates on its caller's thread.
  - *The runtime is told the size.* On its own thread the launcher passes
    the size it created the thread with as `scheme.StackSize`, so the
    runtime doesn't measure the stack ("Stack-depth spill"); on the
    launcher's thread (`-Xss`, `--stack-size=default`), whose size it
    doesn't know, it passes nothing, and the runtime measures it.
  - *Why 256 MB.* A stack's address space is reserved when its thread starts,
    and only the pages a recursion reaches take memory. The spill limit
    uses no more than 64 MB of a thread's stack (56 MB, less the reserve),
    so a larger stack gains nothing today (256 MB and 1 GB measured alike).
    The rest is margin. (It was what kept a recursion through `apply` from
    overflowing the native launcher's stack while it ran at Truffle's first
    tier, until native calls checked the stack's real depth, "Stack-depth
    spill".) 256 MB is the size M8-01 measured `ack` with.
  - *What it costs.* Until the launcher passed the size, the stack's
    measurement touched its 64 MB the first time a recursion was
    more than about 50 interpreted calls deep, and they stayed resident.
    Medians of interleaved runs on an Apple M3 Max shared with other jobs
    (wall time, peak RSS; "before" is `main`, or for the native launcher
    `--stack-size=default`, which runs as before), when the launcher first
    evaluated on its own thread:

    | | Before | After |
    |---|---:|---:|
    | A hello-world program, native (30 runs) | 11 ms, 20 MB | 11 ms, 20 MB |
    | A hello-world program, JVM (20 runs) | 387 ms, 354 MB | 402 ms, 360 MB |
    | A program that recurses 100 deep, native (10 runs) | 41 ms, 29 MB | 55 ms, 85 MB |
    | A program that recurses 100 deep, JVM (20 runs) | 422 ms, 363 MB | 467 ms, 442 MB |

    ecraven's programs at M8-01's counts, 3 processes each (`divrec` 6; `ack` 3
    iterations, the others 5), the steady iterations' median, and peak RSS:

    | | JVM before | JVM after | Native before | Native after |
    |---|---:|---:|---:|---:|
    | `ack` (1, `ack(3,12)`) | 24.6 s, 4.5 GB | 2.4 s, 1.5 GB | 20.4 s, 381 MB | 1.8 s, 146 MB |
    | `divrec` (100000) | 356 ms | 360 ms | 354 ms | 338 ms |
    | `primes` (1000) | 115 ms | 116 ms | 81 ms | 86 ms |

    `divrec` (500 deep) and `primes` (about 1,000) already fit in the old
    stacks, so only their RSS changed (60 to 90 MB more, the measurement).
    With the size passed, nothing is measured, and the RSS is back. Medians
    of 21 interleaved runs (15 for `ack(3,12)`, three iterations each, the
    steady ones' median); "before" measures (`main` after #139), "after" is
    the same code with the size passed; machine load 10 to 12. The spill
    limit is the same either way (56 MB), so beyond the measurement's few
    tens of ms, `ack`'s difference in time is noise:

    | | Native before | Native after | JVM before | JVM after |
    |---|---:|---:|---:|---:|
    | A hello-world program | 16 ms, 20 MB | 15 ms, 20 MB | 438 ms, 351 MB | 448 ms, 374 MB |
    | A program that recurses 100 deep | 56 ms, 89 MB | 14 ms, 21 MB | 469 ms, 458 MB | 400 ms, 376 MB |
    | `ack(3,12)`: run, peak RSS | 5.6 s, 184 MB | 4.6 s, 73 MB | 8.9 s, 1.59 GB | 7.5 s, 1.69 GB |
    | `ack(3,12)`: steady iteration | 1.86 s | 1.48 s | 2.59 s | 2.17 s |

    The JVM's RSS is mostly its heap, and varies by tens of MB from run to
    run. Where the measurement still runs, it is faster (the probe catches
    its overflow at the bottom, "Stack-depth spill"): the 100-deep program
    with `-Xss64m` took 987 ms on the JVM before, 558 ms after; with
    `-Xss16m`, 702 and 593 ms; in the native launcher, 40 to 43 ms before,
    20 to 23 ms after.
  - Native image honours `Thread`'s stack size argument: on a 256 KB
    thread the native launcher spills as the JVM does.
- **The JIT warm-up (M8-03).** With a program file and the JIT on, on the
  JVM, with 4 or more processors, the launcher starts a daemon thread
  (`scheme-warm-up`) that creates a context of its own (its own engine, no
  I/O, none of the command line's options) and runs `SchemeMain.WARM_UP`: a
  procedure of the common operations (calls, a closure, fixnum and flonum
  arithmetic and comparisons, pairs, vectors), called 2,000 times, so that
  it gets compiled. It starts once the program's context is initialized and
  runs on another core while the evaluation thread reads, expands and runs
  the program; the JVM doesn't wait for it. Starting after the program's
  context keeps that the process's first context: the one that takes a
  pre-initialized context (M8-07) and reports on its error stream what
  became of the build-time prelude image (M7-05), which a warm-up started
  with the launcher took. `--engine.Compilation=false` (as an option or a
  `polyglot.engine.Compilation` system property), the native launcher (whose
  compiler's graphs are built into the image) and the REPL go without.
  - *Why.* A process's first Truffle compilations are slow: 30-140 ms each
    for procedures that take 2-3 ms once a few others have been compiled (the
    compiler parses and caches, once per process, the interpreter's methods
    it partially evaluates), and the interpreter's own host code starts in
    HotSpot's interpreter. A benchmark's first iteration ran in the
    interpreter while that happened: in the M7 report the first run was 2.4
    times the steady state (geometric mean). Compilation traces of the first
    iteration of 24 programs showed few invalidations (0 to 13 a program:
    argument and return type profiles, local tags, splits), so deopts aren't
    the gap; the time until compiled code runs is.
  - *What it gives.* On 24 of ecraven's programs at the report's counts
    (0.1 times their own, `-Xmx2g`, 3 iterations in a fresh JVM, as
    `bench-report` runs them; 3 interleaved rounds, the medians; an Apple M3
    Max shared with other agents, load 9-11, runs above 10 dropped), against
    `main`: the first iteration's geometric mean 0.918 times (`sumfp` 0.48,
    `fft` 0.63, `chudnovsky` 0.74, `string` 0.80, `maze` 0.85, `parsing`
    0.86), the steady state's 0.993 times, and the whole process's wall time
    0.969 times. Started with the launcher instead (before the program's
    context), it measured 0.905, 0.991 and 0.957. A hello-world program
    takes about 10 ms longer (455 ms against 444, medians of 41 interleaved
    runs), the warm-up's start as it ends. The second context costs memory
    while it lives: a program that runs 2 s peaks about 80 MB higher (487 MB
    against 407), a hello-world program no higher.
  - *What startup pays for the evaluation thread and the warm-up
    (M9-02).* M8's threshold re-measure found `bench-check`'s `startup`
    1.08 times its M7-03 baseline (412.8 ms against 380.7). Those were two
    measures made on different days. Interleaved on one day it is 1.035
    times. The setup was `bench-check`'s way: 9 launcher runs a JVM, the
    median wall time, Bazel's GraalVM, 7-8 rounds, load 4-6. M7-03's jars
    with G1, the collector then, took 363 ms, and `main` at `47fc603` with
    the serial one took 376 ms. With the same collector for both it is
    1.04 times (360 against 376 ms, serial). Bisected (serial, 8 rounds,
    load 5-9, single runs spread up to 450 ms), the steps are the
    evaluation thread (#139 and #142, together about 13 ms, above) and this
    warm-up (#143, about 10 ms, as measured here). The serial collector
    (#157) gave a few ms back. Both are accepted for what they give: deep
    recursion on a large stack, and the first iteration's 0.92 times.
  - *Rejected:* running the warm-up on the evaluation thread before the
    program: the first run fell further (0.84-0.87 times), but by moving
    work before the harness's timer: it took 85-140 ms of that thread, a
    hello-world program went from about 370 to 480 ms, and whole processes
    took as long as before or longer. Truffle engine options as launcher
    defaults: `OSRCompilationThreshold` 10,240 instead of 100,352 (a long loop
    in a procedure entered once is OSR-compiled sooner: `sumfp` 0.61 times,
    `fft` 0.83, the geometric mean 0.985), with `FirstTierCompilationThreshold`
    100 (0.975): small, and they are experimental options, which the
    launcher could set only by accepting every experimental option without
    `--experimental-options`. `CompilerThreads=12` (1.14 times, on a shared
    machine), `MultiTier=false` (1.22 times first, 1.37 steady),
    `Mode=latency` (first tier only, no splitting: it gives up the steady
    state). A larger warm-up (20,000 calls of two procedures, to reach the
    second tier too) did less (0.955 times against 0.925): it holds a core
    longer.
  - *Its host profile.* The warm-up runs the interpreter's host code in the
    program's JVM, so HotSpot can compile a method from the warm-up's profile
    before the program uses it its own way. A Java loop that the warm-up never
    iterated, entered once by the program for a long time, then couldn't be
    OSR-compiled: the trampoline's loop, which made `cpstak`'s first iteration
    five times as slow (72 s instead of 15) until it became a Truffle loop
    ("Calls and tail calls"). It was the only program found: across 31 others
    (the 24 above, `takl`, `graphs`, `ack`, `divrec`, `primes`, `nqueens`,
    `destruc`; 2 interleaved rounds, the warm-up on against off), none had a
    slower first iteration beyond noise, and the warm-up's geometric means were
    0.947 times (first iteration), 1.006 (steady state) and 0.975 (whole
    process).
- **The collector (M8-05).** The JVM launcher (`bazel-bin/scheme`, and so the
  benchmark harness, which runs it) starts the JVM with the serial collector,
  `-XX:+UseSerialGC`, unless its JVM options already choose one (`tests/harness/harness.py`,
  `launcher_gc_vm_args`: the JVM refuses to start with two; `JDK_JAVA_OPTIONS`
  counts). The native launcher has always used it: it is native-image's
  default (CE has no other but Epsilon). An embedder chooses its own JVM's.
  - *Why.* Measured on 24 of ecraven's programs at the report's counts (0.1
    times their own, `-Xmx2g`, 3 iterations per JVM; 2 interleaved rounds,
    the medians; an Apple M3 Max shared with other agents, runs whose load
    passed 10 dropped), against G1, the JVM's default:

    | Collector | First iteration | Steady state | Peak RSS |
    |---|--:|--:|--:|
    | Serial | 0.82 | 0.82 | 0.70 |
    | Serial, compact object headers | 0.82 | 0.78 | 0.69 |
    | Parallel (25 programs, its own rounds) | 0.94 | 1.00 | 0.71 |
    | G1, compact object headers | 0.97 | 1.08 | 0.74 |

    (geometric means of the ratios). With the serial collector `paraffins` took
    0.30 times as long, `tak` 0.42, `mperm` 0.49, `graphs` 0.54, `lattice`
    0.68, `nqueens` 0.73 and `mazefun` 0.75; `nboyer` 1.73 times (its young
    collections copy hundreds of MB of survivors on one thread: 6.4 s of
    pauses against G1's 0.7) and `earley` 1.06; the rest within 10%. Most of
    the gain isn't in the pauses: `tak` and `graphs` spent 36 ms and 600 ms
    collecting with either, and ran 2.0 against 4.9 s and 2.1 against 3.8 s;
    the parallel collector, whose write barrier is the serial one's, ran them as slowly as G1 (5.0 s, 3.9 s), so what makes the
    difference is something of the serial collector's own that these
    measurements didn't isolate (its TLABs, GC threads, compressed-oop and
    class-pointer modes and the CDS mapping were ruled out). `paraffins`
    collected for 115 ms against G1's 1.5 s: the serial young generation, a
    third of the heap, holds its radicals until they are promoted, where G1
    and the parallel collector resized theirs and copied them repeatedly.
    A hello-world program starts in the same time (476 against 448 ms on a
    loaded machine, 7 runs each).
  - *Rejected:* compact object headers (`-XX:+UseCompactObjectHeaders`,
    which make a pair 16 bytes instead of 24): with G1 they were slower
    (`paraffins` 2.0 times, `peval` 1.5, `sboyer` 1.3, `nboyer` 1.3), with
    the serial collector 5% faster overall but slower on `nboyer` and
    `earley`, and the JDK's class-data-sharing archive is built without
    them, so the JVM starts without it: a hello-world program took 682 ms
    against 476. The parallel collector: as fast as G1, 30% less memory,
    none of the serial collector's gains. G1 stays what an embedder's JVM
    uses unless it chooses otherwise, and a program with a large live
    heap and a high allocation rate (`nboyer`) can choose it back
    (`JDK_JAVA_OPTIONS=-XX:+UseG1GC`).
- The Chrome inspector, DAP and profiler tools are optional modules
  (`//bazel:tools_modules`), which `bazel-bin/scheme` puts on the module
  path, and CI runs a smoke test of each against a Scheme program
  (`//tests:tools`; D13's tags and source sections are what they consume).
- **Native image (M6-06).** `bazel build //:scheme-native` builds `scheme` as
  a native executable with the pinned GraalVM CE's `native-image` and the
  target's C toolchain (`native_image` in `bazel/java_module.bzl`: on the
  executors for a Linux target, here for this machine), from the launcher's
  module path without the tools. Truffle's runtime on that path
  makes native-image add Truffle's runtime compilation, so the executable
  has the Graal JIT. The language's jar (`scheme.jar`) carries its own configuration
  (`src/runtime/META-INF/native-image/org.r7rs/scheme`), so any embedder's
  image picks it up: the prelude's resources, and build-time initialization
  of the language's packages, as GraalVM's own languages have (the language
  provider and the DSL-generated library exports live in the image heap).
  Static state then keeps its build-time value: `-Dscheme.torture` (and
  `.owned`) and
  `-Dscheme.preludeImage` (and its `.file`) have no effect in a native image,
  so torture mode needs the JVM. No reflection configuration is needed (D10).
  - The build-time prelude image (D13, M7-05) is read and checked when
    `PreludeImage` is initialized, so at image build time: the checked image,
    its prelude `Source`s included, is in the image heap, the executable
    doesn't read it (nor, when it restores, the prelude files), and the
    resources the executable carries leave it out.
  - **A pre-initialized context (M8-07).** The image is built with
    `-Dpolyglot.image-build-time.PreinitializeContexts=scheme` (25.5's name
    for the deprecated `--engine.PreinitializeContexts`), an option of the
    `//:scheme-native` target, not of the language's jar: native-image
    creates a context at build time, whose `initializeContext` restores the
    prelude image and runs the library bodies, so the standard libraries,
    the interaction environment and the primitives' cells are in the image
    heap. The launcher's context, the first one of the process with the
    default engine, takes it over: Truffle calls
    `SchemeLanguage.patchContext` with the run's Env, which rebinds what
    the context took from the build's:
    - the Env itself (`SchemeContext.patch`), from which the command line,
      the environment variables, files and the current directory, `exit`
      and the options are read when they are used;
    - the console ports, which keep their identity (the prelude's
      `current-output-port` and the other two parameters hold them) and
      write and read the new Env's stdout, stderr and stdin through
      streams that the patch points at them (pre-initialization reads and
      writes none, so nothing is left buffered);
    - the library path (`-I`, `scheme.LibraryPath`), which the compiler's
      `Libraries` keeps, with the Env it resolves directories in;
    - the context's `AllocationReporter`.

    Nothing else comes from the build: `current-jiffy` is `System.nanoTime`
    with no saved epoch, `current-second` reads the clock when it's called,
    and initialization reads no environment variable, file or clock (the
    executable's strings name no build path but the linker's object files,
    as without pre-initialization). `ContextPreinitializationTest` checks
    each of these against a pre-initialized context on HotSpot, where
    Truffle pre-initializes one when the same system property is set
    before the polyglot API is first used: through the Context API, and
    through the launcher, a program and the REPL, in child JVMs. A context
    whose `scheme.PreludeImage` is false declines the patch and gets a new
    context. `areOptionsCompatible` has no part in it: it decides whether
    contexts may share code, and this language's don't (`EXCLUSIVE`, no
    sharing layer). Only the default engine's first context is the
    pre-initialized one; an embedder's explicit `Engine`, a second context,
    or an image built without the option starts a context as before.
  - Code that partial evaluation reaches must not reach a method
    native-image blocklists for runtime compilation. On HotSpot such a path
    only costs code size, but native-image refuses to build: a call through
    a field of a JDK functional interface (it may reach every implementation
    in the image), a root's `getName`, and string concatenation outside a
    `@TruffleBoundary` all failed the first build.
  - Numbers on an Apple M3 Max (16 cores, macOS), median of 10 runs; the
    JVM launcher is the `java` command `bazel-bin/scheme` runs (M6-06;
    since M7-05 the first context restores the prelude image, which D13
    measures against expanding; the native startup rows are M8-07's, below):

    | | Native | JVM |
    |---|---:|---:|
    | `scheme --version` | 10 ms, 15 MB | 228 ms, 251 MB |
    | A hello-world program | 11 ms, 19 MB | 614 ms, 326 MB |
    | ecraven's `fib` (40, 5 runs), steady iteration | 12.4 s | 13.3 s |
    | ecraven's `tak`, steady iteration | 4.7 s | 6.0 s |
    | Peak RSS over 5 iterations of `fib` / `tak` | 149 / 176 MB | 1505 / 1558 MB |

    Native startup with and without the pre-initialized context (M8-07):
    the executable before and after that task, the median of 41 runs
    each, interleaved, wall time and peak RSS:

    | | Without | With |
    |---|---:|---:|
    | `scheme --version` (no context) | 9.8 ms, 14.7 MB | 9.7 ms, 14.8 MB |
    | A hello-world program | 13.1 ms, 22.5 MB | 11.0 ms, 19.4 MB |
    | The REPL, `(+ 1 2)` then end of input | 13.9 ms, 22.8 MB | 12.0 ms, 19.4 MB |

    What a program costs over `--version` drops from 3.3 ms to 1.3 ms
    (restoring the prelude image and running the library bodies is the
    part that goes), and 3 MB of RSS; the executable grows by 1 MB.

    The build takes 50–90 s and about 3 GB on that machine; the
    executable is 55 MB. The JIT compiles in the native launcher as on the
    JVM (`--engine.TraceCompilation` shows the tier-1 compilations), and
    the first `fib` iteration (17.8 s against the JVM's 13.6 s) is the
    only one that is slower.
  - `bazel run //:r7rs -- test --native FILE` runs each conformance section as
    a process of the native launcher, as `ConformanceRunner` runs it, and
    passes what the JVM does: chibi's suite 1225/1225 and Larceny's
    2162/2166, interpreted and with `--compile`. The test
    `//tests:conformance_native` fails if it passes anything other than the
    status block.
  - **`--end-status=<n>` (M7-06).** A program that ends exits with 0 by
    default, like one that calls `(exit 0)`, so a process's status alone
    can't tell them apart, and Larceny's two exit-0 sections pass their exit
    test only if the program calls `exit`. `ConformanceRunner` sees the
    difference (`Context.eval` returns, or throws an exit); the launcher
    takes `--end-status=<n>`, the status when the program (or the REPL's
    input) ends without `exit`, and the native runner passes
    `--end-status=99`, so its results mean what the JVM runner's do. R7RS
    §6.14 says only what `exit` communicates (*"If no argument is supplied,
    or if obj is #t, the exit procedure should communicate to the operating
    system that the program exited normally"*), not what a program that
    ends does, so the default (0, as chibi-scheme) and the option are ours
    to choose.
  - CI doesn't build it (D11): the build takes a minute and gigabytes. The
    target and its tests are `manual`; a PR that changes the native-image
    configuration runs `//tests:readme_native` and
    `//tests:conformance_native`.
  - On x86-64 Linux the image targets the baseline CPU
    (`-march=compatibility`): with the default target (AVX2) the image
    uses the JDK's `libsimdsort`, whose libm dependency nixpkgs'
    native-image resolves to glibc's static `libm.a`, and linking that into
    a dynamically linked executable fails. The baseline target is also what
    nixpkgs' own native-image check builds with, and makes the executable
    run on any x86-64 machine.
  - Limits: the tools (inspector, DAP, profilers) are only on the JVM
    launcher (`bazel-bin/scheme`); the executable is for the platform that built
    it; and there is no auxiliary engine cache in the image (only the first
    context is pre-initialized: any other restores the prelude image and
    runs the library bodies).
- Why: Truffle languages get debugging, profiling and engine tuning from
  the platform; a hand-written launcher would have to re-implement or forgo
  all of it.


### D18. Syntax errors for R7RS §4–5's "it is an error" cases

Where R7RS §4–5 says a program "is an error" and the expander can tell at
expansion time, cheaply, it is a syntax error; otherwise the program does
what its expansion does. Found in the §4–5 audit, against the spec and its
errata (r7rs.org):

| R7RS | "It is an error ..." | Here |
|---|---|---|
| §2.4 | circular references in code, except in literals | syntax error (D9) |
| §4.1.4, §5.3 | a variable more than once in `lambda`'s or `define-values`' formals | syntax error, at the top level too (`define-values` receives its values with a `lambda` of its formals) |
| §5.3.2 | defining the same identifier more than once in a body | syntax error |
| §5.5 | the same identifier as two field names, or as two accessor/modifier names | syntax error, at the top level too (the type's definition holds a never-called `lambda` of each) |
| §5.5 | a constructor field that isn't a field name | error when the definition runs |
| §4.3.2 | the same pattern variable twice in a pattern | syntax error |
| §5.2 | `set!` of an imported binding | syntax error (D7) |
| §5.4 | a definition that changes the meaning of the forms that determined it (`(define define 3)`, `(begin (define begin list))` in a body) | accepted: the definition binds the identifier for the whole body, the forms already classified keep their meaning |
| §4.2.2, §5.3.2 | referring to a `letrec`/`letrec*`/internal-definition variable before its init | not checked: the value is the unspecified "undefined" object |

- Why: an error at expansion costs nothing at run time and points at the
  mistake; the cases left alone would need a run-time check on every
  reference (`letrec` restriction) or a second classification pass (§5.4).

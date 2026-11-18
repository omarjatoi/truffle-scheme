# Design

R7RS-small on GraalVM Truffle, built on the Bytecode DSL. Target: GraalVM CE
25.5 (Truffle 25.5.5.0.3), pinned by Bazel (D11).
Hard requirement: full R7RS-small conformance, including re-entrant multi-shot
continuations and proper tail calls.

Agents may change this document, decisions included, in a PR of its own that
says why (AGENTS.md). Use what GraalVM and Truffle provide wherever it fits;
full R7RS-small conformance comes first.

docs/truffle-guidance.md checks the runtime against GraalVM's own guidance
(the Partial Evaluation page, its further reading, and the Bytecode DSL,
inlining, splitting and OSR guides): what each recommends, and whether we
follow it or deviate, with the decision below that says why.

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
  the continuation internals) are still called. A trampoline's caches run
  them in place too (M10-03): the tail call that `apply` returns, and one
  that a tail site past its own cache returns, which the trampoline used to
  call through the primitive's root. An entry of the trampoline's cache holds
  either a call node or, for such a primitive, a node of its own, so a tail
  call of a closure checks no more entries than before. Only a site that
  has cached four primitives, or a trampoline past its cache, calls the
  primitive's root. That root doesn't say it is trivial
  (`RootNode.isTrivial`): many primitives loop
  (`+` and `vector` over their arguments, `string<?` over the characters),
  and Graal prints a performance warning on the program's standard error each
  time it inlines a root that says so but has a loop: about 200 in a run of
  ecraven's `scheme`.
  - *Why in the trampoline (M10-03).* Truffle defines a trivial root as one
    that *"never contains loops"* (`RootNode.isTrivial`'s Javadoc), so the
    flag was wrong for those primitives (#195 removed it). But Graal's
    inlining policy expands and inlines a trivial callee outside both its
    exploration and inlining budgets (`DefaultInliningPolicy`), and tier 1
    inlines only those; without the flag, the trampoline's direct calls of
    `string<?` in ecraven's `scheme` (its interpreter's `((oper rte) a b)`,
    past `MakeTailCall`'s cache) competed for the budget: in a run's tier-2
    compilations, `string<?` was cut off 251 times and inlined 68 (with the
    flag, 214 inlined, none cut off), so each cut-off one was a Truffle call
    from compiled code. Run in place, it is no call at all: the call trees
    have no `string<?` node, and a traced run's tier-2 code was 1.7 MB
    against 3.4 MB (101 compilations against 129). Interleaved, 5 rounds of
    10 iterations at the report's counts (load 5-8, medians): `scheme`'s
    iterations 6-10 took 368 ms, against 414 before M10-03 and 391 before
    #195 (`e70adbb`), and its iterations 2-3 488 against 571 and 545;
    `compiler`, `peval` and `conform` within 1% of before M10-03; a loop of
    `(apply + args)` and `(apply vector args)` the same once compiled, its
    iterations 2-3 1.06 times (as before #195).
    *Rejected:* trivial for loop-free primitives only (a `PrimitiveSpec`
    flag): `string<?` loops, so `scheme` gains nothing; `forceInlining()` on
    the trampoline's calls of primitive roots (#195): it restored the
    inlining but not the time.
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
  machine code, and on GitHub's 4-CPU runners (CI's then; two compiler threads) the
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
- **A loop's jump back stores in place (M9-03).** A self tail call that loops
  (a contified loop's, a fixed letrec* variable's, or a possible one through a
  global, whose procedure is still compared first) evaluates each argument
  straight into its parameter when no later argument refers to or assigns that
  parameter (in lambdas in it too), the parameter isn't boxed, and the argument
  isn't a call; the others go through temporaries, as every argument did
  before. In `(loop (+ i 1) (cons i acc))` only `i` waits; in `(loop (+ j 1)
  (- j 1))` neither does. Each argument still sees the parameters as they
  were. A temporary was a frame slot and a store,
  a load and a clear on every iteration. A boxed parameter keeps its temporary
  so that its fresh box is made once every argument is evaluated: a
  continuation captured in a later argument and resumed twice gives each
  resumption a box of its own, as a `let` does. A call's value doesn't go
  straight in either: it comes from either of two instructions (the call, or
  the resumption of a suspended frame), and the Bytecode DSL never quickens a
  store of such a value, so the parameter's local would turn generic and every
  use of a fixnum parameter boxed (`tak`'s, 4% slower); through the temporary,
  the parameter's store unboxes it. `tests/diff/loop-direct-stores.scm`.
  In the interpreter (Apple M3 Max shared with other work, load 3-15, a fresh
  JVM per run, interleaved with the code before): a micro-benchmark of three
  loops 0.88 times; the report's interpreter subset at its scale (54 programs,
  2 rounds, best of each) 0.97 times: `sum` and `sumfp` 0.67, `puzzle` 0.76,
  `ray` 0.87, `wc` 0.88, the longest programs 0.98, `tak` 1.00, and a few short
  first runs 1.05-1.15 (`diviter`, `mbrot`), whose later iterations are as fast
  or faster: a first run there is the host compiler's (below). With the JIT
  (24 programs, 3 iterations, 2 rounds) the first iteration 1.01 times, the
  steady state 1.00: noise.
- **What else an interpreted call costs (M9-03).** Measured and not kept, in
  the interpreter, against the code before (`fib 32` and 400 runs of `tak 18
  12 6`, 3 interleaved runs, Apple M3 Max):
  - *Calls with their arguments as operands* (`Call1`-`Call4`, no
    `create.variadic` first): `fib` 0.92 times, `tak` 0.96. But an operand is
    read into a Java local of the call's handler, which stays on the stack for
    as long as the call runs, and the interpreter's and C1's frames keep their
    locals alive: a lazy stream's head passed to a tail loop stayed reachable,
    and every `tail-lazy-*` space test ran out of heap. The variadic array is
    the only thing that holds the arguments, and the callee's prologue clears
    it ("Tail calls keep nothing alive", below). The same operations for tail
    calls (`MakeTailCall0`-`4`), whose handler returns at once, are safe:
    `cpstak`'s loop 0.97 times, `tak` 0.99, not worth the instructions (below).
  - *A `TryCatch` at each call site* (the call stores its value in a local of
    the root's; a capture is thrown, as an `AbstractTruffleException` that
    `SchemeError`, an `unwindExceptions` type, doesn't pass for, and the
    handler suspends): `fib` 0.95 times, `tak` 0.93, but a recursion 1,000,000
    deep, which spills, 1.40 times, and a `call/cc` loop 1.04: every frame a
    spill or a capture suspends unwinds by a Java exception instead of a
    return. The capture check stays a return value.
  - *A default local value* (`defaultLocalValue = "null"`, so a frame's tags
    needn't be filled when it is made): 1-2% on `fib`, but compiled code then
    keeps cleared locals (`tail-lazy-*` ran out of heap) and failed to compile
    some roots (a frame materialized).
  - *An operation for a self tail call through a global* (`IsSelf`, comparing
    the cell's value with the running closure, after the arguments, so no
    temporary for the procedure): `tak` 1.04-1.07 times, with the same
    bytecode otherwise.
  - *The host compiler.* A first run at the report's scale is mostly HotSpot
    (with Graal as its JIT) compiling the bytecode interpreter's
    `continueAt` for that program's instructions, and recompiling it when a
    new one is reached ("made not entrant: uncommon trap" a few times a run).
    The handlers of the instructions a program runs are inlined into it, so an
    operation whose handler inlines a call's whole path costs every program
    that runs it: with `Call1`-`Call4`, `pnpoly`'s `continueAt` took 212 ms to
    compile instead of 131, and its first run 1.15 times as long though it
    makes almost no calls. `@HostCompilerDirectives.InliningCutoff` on the call
    path brought it back (152 ms).
- **The interpreter's first runs (M10-02).** What a first interpreted run
  pays for, and the directions measured against it. Unless said otherwise:
  ecraven's programs at the report's interpreter scale (`bench-report
  --scale 0.1`, the interpreter at 0.1 of that, a fresh JVM per run, the
  launcher's options and `-Xmx2g`), 2 interleaved rounds, the medians; the
  54 programs that finish within 60 s, or the 44 of them that take under
  20 s; an Apple M3 Max shared with other agents, no run started at a
  1-minute load above 10. The noise floor: a build whose JVM code is
  identical (#229's, which only adds a native-image interpreter) measured
  0.96-0.98 times by the geometric mean over the 44, single programs
  ±30% and `tak` ±10%.
  - *Where it goes.* HotSpot compiles `continueAt` (with libgraal) once a
    program has run it enough, with the handlers of the instructions it has
    seen inlined. When the program reaches an instruction the profile hadn't
    seen (a new one, or a quickened variant of one it had), the compiled
    code hits an uncommon trap at the dispatch `switch` (JFR's
    `jdk.Deoptimization`: `unreached`, action `reinterpret`), and the method
    runs as C1's profiling code until libgraal has compiled it again. Over
    the first iteration of 12 programs: 2-9 compilations of `continueAt` and
    2-8 traps a program, each compilation 75-245 ms (`browse` 4 of them,
    532 ms in all, in a first iteration of 0.78 s; `dynamic` 6, 1,227 ms, in
    1.58 s). The first iteration is 1.2-3 times the later ones (`browse`
    0.65 s, then 0.40, 0.22). So a first run's cost follows how many times
    `continueAt` is compiled and how large each compilation is.
  - *Kept: a call's path cut from the host's inlining.* `Call.doDirect`,
    `doIndirect` (the stack budget, the first descent's bookkeeping,
    Truffle's call and argument profile, the trampoline), `raiseHere` and the
    typed operations' `generic` path are `@InliningCutoff`s: methods
    compiled on their own, called from `continueAt` (`HostInliningCutoffTest`).
    The host graph of `browse`'s `continueAt` after inlining went from
    4,364-5,286 to 3,737-5,005, each compilation took 8-50% less
    (`deriv` 112 → 76 ms, `parsing`'s largest 159 → 104, `dynamic`'s 242 →
    120, with the call path alone cut), and the 54 programs' first runs 0.94
    times as long (0.935-0.950 in two runs of 2 rounds; `nqueens` 0.71, `compiler`, `conform`, `slatex`,
    `maze` 0.80-0.82). The Java call it adds to each Scheme call is small
    beside the call's own work: `tak`, `fib` and `ack`, whose time is
    calls, within their noise in the report's runs (0.98-1.08), and the
    interpreter's later iterations (`bench-check`'s interpreter cases, 8
    interleaved rounds) `fib` 0.99, `tak` 1.04, `deriv` 0.95.
    With the JIT (24 programs at the report's counts, 3 iterations, 3
    rounds) the first iteration 1.00 times and the steady state 1.00.
    M10-07 can read this as what makes the host stop inlining a handler: a cutoff, a `@TruffleBoundary`, a path dominated by
    `transferToInterpreter`, or a call it can't devirtualize (a
    primitive's `PrimitiveNode.invoke` from `Call.doPrimitive` and
    `MakeTailCall.doPrimitive` is never inlined: *"not direct call:
    polymorphic"*, so `SetPair.invoke`'s cost there is its own compiled
    code's); and that anything that changes which instructions a program
    reaches moves its first run by the cost of a compilation or two.
  - *Measured, not kept.*
    - The AOT cache with JDK 25's method profiles (JEP 515), trained on the
      interpreter. JDK 25's cache holds no compiled code (only adapters and
      stubs, behind diagnostic flags); it holds profiles, which HotSpot
      installs as methods are linked and libgraal compiles from (with a
      cache, `continueAt` was compiled at 197 ms into the run instead of
      310, from the training's profile). Caches made for `//:launcher_runtime`'s jars by
      hand, against none (24 programs, 3 rounds): trained on
      `bazel/aot-training.scm`, as `bazel-bin/scheme`'s is, first runs 1.044
      times (0.57-1.45: a profile that isn't the program's makes the
      compiled code trap, or makes it large); trained with
      `-XX:-AOTRecordTraining` (classes only) 1.004; trained on 15 of the
      programs interpreted, 1.09 on 20 (17 of them not in the training: its
      `continueAt` covered every handler the training had used, 320 ms to
      compile, and slower code). With the
      JIT (18 programs, 3 iterations, 3 rounds) the first two 0.99 and 1.01,
      the steady state 1.00: `bazel-bin/scheme` keeps its cache as it is
      (D17, "The AOT cache"), and the benchmarks' runtime gets none.
    - Graal's speculation, as JVM options (44 programs):
      `-Djdk.graal.RemoveNeverExecutedCode=false` (no trap for an unseen
      instruction, but every method compiled with its cold paths) 1.041;
      `-Djdk.graal.DeoptsToDisableOptimisticOptimization=3` (the same after
      3 traps of a method) 1.033.
    - The JIT's warm-up thread (D17) also in the interpreter, to start
      `continueAt`'s profile early: 1.003 (1.035 by the fastest runs).
    - `boolean` boxing elimination off (truffle-guidance.md): fewer
      quickened variants, so fewer traps (36 against 47 over the 12 programs
      above), but over the 54 alone 0.985 and 0.992 (`tak` 1.11, `nboyer`
      1.06), and with the cutoffs above 0.925 against their 0.94, `tak`,
      `ack`, `fib` and `nboyer` 1.08-1.14: what it gives short programs, the
      longest give back, and those are the ones near the report's 60 s.
    - `storeBytecodeIndexInFrame = false`, where D13 keeps it on: 1.006 and
      1.020 over the 54 (1.025 over the 44): no cost to recover.
    - `enableQuickening = false`: the Bytecode DSL keeps quickening when
      `boxingEliminationTypes` needs it (the same jar).
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
- **A loop resumed by a continuation deoptimizes once, at its first exit
  (M11-04).** A procedure whose loop captures on every iteration (a `call/cc`
  in the loop, or any non-tail call under torture) runs each iteration but
  the first in a continuation root, one per resume. That root is compiled
  after 100 resumes, while the loop's exit is still a branch the bytecode's
  profile has never seen taken, so its compiled code has a
  `transferToInterpreterAndInvalidate` there (the Bytecode DSL's branch
  profile with a zero count), and the loop's one exit takes it. The root's
  return-type profile, which had only seen the `ContinuationResult` of a frame
  that suspends again, changes at the same exit (`Profiled Return Type`).
  Both are profiles that stabilize once: the branch profile is the bytecode
  node's, which the procedure and its continuation roots share, and the
  recompiled root keeps the exit. A root compiled before the first exit pays
  it once if it is the one that exits; nothing cycles. It is Truffle's
  behavior for any branch compiled before it is taken (an OSR-compiled loop's
  exit is the same), so nothing changed in the runtime;
  `ResumedLoopRecompilationTest` counts the invalidations, one with the exit
  first taken after compilation and none with it taken before, in all four
  modes.
  - *Not `LoopHead.releaseOSRParent`.* `-Xlog:deoptimization=debug` reports
    the trap (`transfer_to_interpreter`, action `reinterpret`, which is
    `InvalidateReprofile`: a `transferToInterpreterAndInvalidate`) at the last
    frame state before the branch, the loop condition's: `releaseOSRParent`
    at its return (bci 57), which is what #213's follow-up took for the cause.
    With `releaseOSRParent`'s body taken out, the same trap is reported at
    `FrameWithoutBoxing.unsafeClear` (bci 43, `LoopTest` clearing its
    operand). With `(go 3 0)` run before `(go 5000 0)` in
    `SplitRecompilationTest`'s program, there is none, and a second
    `(go 5000 0)` after the first has none either. `releaseOSRParent` has
    nothing to release in a continuation root: in compiled code the Bytecode
    DSL (25.5) runs it on a fresh virtual frame and binds operations' frames
    to the materialized one, whose arguments are the procedure's own.
    `--engine.TraceTransferToInterpreter` shows only the first
    `transferToInterpreter` the interpreter runs after the trap (the generated
    `continueAt`'s "bytecode or tier changed" path), and
    `--engine.TraceDeoptimizeFrame` traces frames deoptimized on request, not
    traps, so neither named it.
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
    costs would only say when to read it. Measured twice (native launcher,
    Apple M3 Max), before and after M9-01, and rejected both times: a frame
    that stays on the Java stack costs a recursion's first run more than one
    that spills, unless the whole recursion fits; only the later runs of a
    recursion that then fits gain. The native launcher mostly runs a program
    once, so the limit stays the costs' 56 MB (about 30 MB of real stack).
    HotSpot has no equivalent of `StackValue` short of a native call, so the
    JVM couldn't do it either.
    - *Before M9-01 (#170):* a recursion's first deep run was compiled while
      it descended, before any call had returned, so the first return
      invalidated the code, and each compiled frame of it still on the stack
      deoptimized when it was returned to (`--engine.TraceCompilation` logs
      an `opt deopt` per frame): 25 to 40 µs a frame, where a spilled frame,
      which resumes into new code, costs about 1 µs. Medians of the
      iterations in one process; the first iteration, then the steady ones:

      | | 56 MB counted (kept) | 224 MB real |
      |---|---:|---:|
      | `ack(3,12)` ×4 | 1.20 s, then 1.16 s; no spill; 92 MB | the same |
      | `divrec`, `primes` (M8-01's counts) | unchanged; no spill | the same |
      | `count` 300,000 deep ×6 | 0.1 to 5.3 s, then 25 to 150 ms; 13, then 2 to 5 spills | 10 s, then 3 ms; no spill |
      | build a list and `map` it, 1,000,000 deep, ×6 | 17.7 s, then 0.26 to 0.57 s; 12 to 19 spills; 1.0 GB | 58 to 60 s, then 0.16 to 0.28 s; 0 to 3 spills; 0.9 to 1.1 GB |
      | `count` 10,000,000 deep ×3 | 15.9 s, then 2.3 to 2.8 s; 65 to 69 spills; 4.3 GB | 72 s, then 3.4 s; 3 to 7 spills; 4.9 GB |

      The list's first iteration, traced: 480,000 deoptimized frames and
      6.3 s of collections (308) with the counted limit, 1,530,000 and
      19.7 s (314) with the real one.
    - *After M9-01:* nothing deoptimizes any more (the list's first
      iteration and the 10M `count`'s run, traced: no `opt deopt` with
      either limit), but every collection scans the whole Java stack, and a
      deep recursion's first run allocates as it descends, so its
      collections run with the stack deep below them. The list's first
      iteration is two thirds collections, and they take longer the deeper
      the stack: 1.1 to 1.5 s with the counted limit, 1.5 to 1.6 s with
      224 MB real, about 0.5 s of the rest either way. The first collection
      in the 300,000-deep `count`'s descent paused 85 to 89 ms instead of 35
      to 37 at the same heap size. Medians of 9 interleaved processes; the
      first iteration, the median of the later ones, the process's wall
      time:

      | | 56 MB counted (kept) | 224 MB real |
      |---|---:|---:|
      | `count` 300,000 deep ×12 | 137 ms, then 34 ms; 0.62 s; 13, 4 to 6, then 1 or 2 spills; 359 MB | 185 ms, then 14 ms; 0.42 s; 1, 0 or 1, then no spill; 448 MB |
      | build a list and `map` it, 1,000,000 deep, ×6 | 1.72 s, then 451 ms; 4.49 s; 86, 17 to 25, then 12 spills; 1.0 GB | 2.10 s, then 215 ms; 3.67 s; 10, 3 or 4, then no spill; 1.0 GB |
      | `count` 10,000,000 deep ×3 | 9.9 s, then 4.0 s; 18.0 s; 435, 66 to 71, then 65 spills; 4.7 GB | 11.0 s, then 4.0 s; 19.1 s; 53, 4 to 6, then 3 spills; 5.3 GB |
      | `ack(3,12)` ×4, `divrec` ×6, `primes` ×5 | 1.14 s, 0.36 s and 0.11 s first; no spill | the same (3 processes) |

      The same 56 MB, but of real depth (the costs count an
      interpreted frame 2.5 KB, which takes 1.37 KB natively, and a compiled
      one about twice what it takes, so a recursion goes about twice as deep
      before it spills), 7 processes each against `main`: first iterations
      1.01 to 1.06 times `main`'s, whole runs 0.80 (`count` 300,000 deep),
      1.02 (the list) and 1.03 times (`count` 10,000,000 deep). A sweep of
      the real limit on the first two: first iterations within 5% of
      `main`'s up to 64 MB, and 1.03 to 1.3 times from 96 MB up. The patch
      is on the branch `experiment/native-real-depth-limit-2`, not merged.
    - *A larger young generation.* A minimum heap of 1 GB starts the
      native launcher's young generation at 384 MB instead of 48, so a
      first run collects about a third as often: with the counted limit,
      the list's first iteration took 0.63 s instead of 1.70, the 10M
      `count`'s 6.1 s instead of 10.7, and the 300,000-deep `count`'s 86 ms
      instead of 133. It isn't the default (D17, "Rejected: a 1 GB minimum
      heap for the native launcher"); a user passes `--vm.Xms1g` to the
      executable for it. These tables are without it, and a deeper limit
      wasn't measured with it.
  - *A first descent isn't compiled (M9-01).* Why a deep recursion's first
    run deoptimized frame by frame: Truffle compiles a procedure once it has
    been called a few hundred times, which a recursion does on its way down,
    before any of its calls has returned. Nothing after the recursive call
    has run then: the base case's branch (the Bytecode DSL's branch profile),
    the operation that takes the call's value (`+`, `cons`: uninitialized
    specializations), the call's return-type profile. The first return
    reaches them in compiled code, which deoptimizes and invalidates it
    (natively the trace's first deoptimization resumes right after the
    prologue's `releaseArguments`, #165's code: that is where the base case's
    frame state is, not the cause, as nothing has a side effect between it
    and the base case's branch; on HotSpot the engine logs an uncommon trap
    and `Profiled Return Type` invalidations), and then each compiled frame
    of it still on the stack deoptimizes as it is returned to: 158,000
    frames of a 300,000-deep `count` natively, 25 to 40 µs each. No profile
    can be initialized ahead: what follows a call is any code. So the code
    isn't compiled until it has seen a return:
    - *A site's first descent.* A non-tail call site (its `Trampoline`)
      records whether a call from it has come back, and until one has, counts
      the interpreted frames waiting in its calls. While 8 or more do, the
      procedure's `prepareForCompilation` declines: Truffle retries on a
      later call, and the descent goes on in the interpreter, whose frames
      don't deoptimize. A value comes back from the base case first, and then
      the code compiles with what follows the call. 8, so that a procedure
      whose first call wraps the whole program (`dynamic-wind`'s,
      `with-exception-handler`'s) still compiles, and so that a recursion
      gets there long before a compilation threshold. Compiled code counts
      nothing: the bookkeeping is the interpreter's, on calls from sites that
      haven't returned, so it costs nothing once they have.
    - *What counts as coming back.* Anything but a spill: a value; another
      capture, whose frames are resumed past the call (in their
      continuations, which share the code) or abandoned (an escape, M9-04); an
      exception. A site whose calls never come back with a value (a
      `call/cc` whose `k` is always invoked, as `fibc`'s) mustn't hold its
      procedure off for good; the price is that a descent that ended in an
      escape leaves the next one unprotected, as before. A spill takes the
      waiting frames off the Java stack, but the descent goes on from the
      frame that spilled, on a fresh one: so a spill of 8 or more holds
      compilation off for up to a second, until the descent is 8 deep again
      (100 ms wasn't enough on a loaded CI executor, with a synchronous
      compilation or a collection in between), or something else comes back
      through the site.
    - *Not on-stack replacement.* A loop at the bottom of a first descent is
      compiled as before: OSR code runs in an interpreted frame and returns
      to it, so it leaves no compiled frame of the procedure on the stack.
      Truffle prepares an OSR compilation's root by preparing the root its
      loop is in, with nothing that tells them apart, so the procedure looks
      for Truffle's `BaseOSRRootNode` on the compiler thread's stack. Without
      that, a 30-million-iteration loop 20 calls deep ran in the interpreter:
      1.4 s instead of 0.14 on the JVM, 2.4 s instead of 0.05 natively.
    - *Measured* (Apple M3 Max shared with other agents, runs with a
      1-minute load above 10 left out; medians; the first iteration in a
      fresh process, and the median of the later ones):

      | 5 processes each | JVM before | JVM after | native before | native after |
      |---|---:|---:|---:|---:|
      | `count` 300,000 deep, first | 2.76 s (1.0-2.9) | 0.40 s (0.39-0.46) | 5.04 s (4.3-5.4) | 0.15 s (0.14-0.16) |
      | then (11 more) | 26 ms | 17 ms | 37 ms | 59 ms |
      | build a list 1,000,000 deep and `map` it, first | 2.59 s (1.2-3.6) | 0.90 s (0.88-1.02) | 8.74 s (3.5-9.8) | 0.87 s (0.81-0.91) |
      | then (5 more) | 139 ms | 137 ms | 343 ms | 261 ms |

      Against `main` after #181 (which made `releaseArguments` return early
      outside a compilation root; the first deoptimization still resumes at
      its end, and 19,902 frames still deoptimized in the test's setting),
      3 processes each at a load of 2 to 5: `count` 1.01 to 0.40 s on the
      JVM, 4.99 to 0.15 s natively; the list 1.39 to 0.90 s, 8.54 to 0.81 s.
      Natively, `count`'s later iterations wait for the second tier, which
      comes later when the first one does: over 40 iterations, both settle at
      10 to 12 ms. With the JIT off, the interpreter alone runs the first
      `count` in 0.3 s on the JVM and 0.12 s natively.

      `DeepRecursionFirstRunTest` counts the deoptimized frames, with the
      compiler at the 100th call: a 20,000-deep recursion deoptimized about
      19,900 of them, and now none. Truffle resubmits the declined compilation
      on each call that finds it not queued: 1,200 (native) to 2,800 (JVM)
      times in a 300,000-deep descent, on the compiler threads.
      Elsewhere nothing changes beyond noise (the report's counts, `-Xmx2g`,
      3 iterations in a fresh process, 3 interleaved rounds): ecraven's `ack`,
      `divrec`, `primes`, `destruc` and `nqueens`, whose recursions are
      shallower or return early, measure 0.985 times `main` first and 0.991
      steady on the JVM, 1.005 and 1.012 natively; M8-03's 24 programs 0.995
      and 1.004 on the JVM.
    - *Rejected: spilling a first descent early instead.* Until a call from
      a site had returned, a compiled call from it counted as a 64th of the
      stack limit, so the descent spilled every 64 compiled frames, and the
      first return found at most 64 on the stack (13 to 44 deoptimized
      frames in the test); the first value through the site deoptimized the
      code. Natively its first runs were a little faster (`count` 0.08 s
      against 0.14 to 0.15; the list 0.65 to 0.68 s against 0.78 to 0.87),
      on the JVM about the same. But a recursion that fits in the stack then
      spills when its first descent is compiled, which `StackBudgetTest`
      forbids (under `CompileImmediately` it spilled 45 times, and once under
      owned torture), and under `CompileImmediately` Graal's deopt-cycle
      detection failed a compilation in `EmbeddingGuideTest` (not looked
      into further).
    - *Rejected: profiles that start "seen".* Every node after the call
      would need one, and only `IsValue`'s branch and the return-type
      profile are this runtime's; the rest are the operations' own
      specializations.
    - *Why the interpreter's `deriv` got faster with it (M10-06).*
      `bench-check`'s `deriv-interpreter` went from 104.2 to 84.5 ms in
      #207's thresholds, and the bisection put it on this change alone
      (107.7 ms at #179, 83.2 at #186), though its bookkeeping only adds
      work to an interpreted call. It is HotSpot's code for the bytecode
      interpreter, not ours. The metric is the median of iterations 3 to 6
      of `deriv`'s count 20,000 in the interpreter, which are bimodal:
      about 0.085 s, or 0.21 s while `continueAt` runs in HotSpot's first
      tier (iteration 4, every time, after an uncommon trap makes the
      compiled `continueAt` not entrant at the end of iteration 3), so the
      median is the fast mode. With the JVM's default 12 host compiler
      threads on 16 cores, which the launcher used until #188, #183's code
      (the commit before) settles at 0.10 to 0.11 s an iteration and stays
      there (12 iterations), and this change's at 0.085 to 0.09 s (6
      interleaved rounds, each commit's own jars): the bisection's result.
      With the launcher's 4 threads since #188, both settle at 0.082 to
      0.087 s (4 interleaved rounds), and so does `main` with this change's
      call-path bookkeeping taken out (4 rounds). (Apple M3 Max, 1-minute
      load 11 to 13 throughout; the differences were the same in every
      round.) The two commits compile
      `continueAt` in the same sequence (the same tiers, the same uncommon
      traps, `-XX:+PrintCompilation`), with the same host inlining
      (`-Djdk.graal.Log=HostInliningPhase`: the trees are the same but for
      the new calls, `inFirstDescent` inlined and `enterDescent` and
      `leaveDescent` cut off), and the methods compiled on their own between
      interpreted calls (`callBoundary`, `profiledPERoot`,
      `executeRootNode`, the root's `execute`) end at the same tiers. So what
      differed was the code HotSpot settled on for the same inlining with
      12 threads, not an inlining decision that could reverse, and with the
      4 threads the thresholds since #207 were measured with, #183's code is
      as fast. Nothing to fix.
  - *Spilling is still per frame.* A frame that doesn't fit is materialized
    when it suspends, and until M8's owned segments was also copied when it
    resumed, about 1 KB allocated and 240 ns per frame (`ack`, M8-01);
    recursion much deeper than the stack pays the materialization for nearly
    every frame. A capture links its segments into the
    list the driver takes as the frames suspend (no array to grow and copy).
    Making the driver's loop a Truffle `LoopNode`, so that the program's root
    prompt (which runs once, so is never compiled) is compiled on the stack,
    gained about 3% on `ack` for compilations of 130 to 240 KB that kept
    being invalidated: not done. That was measured in M8-01, before M9-01
    stopped a first descent's compiled frames from deoptimizing one by one
    (below), which may have been what kept invalidating them; not
    re-measured since.
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
  - *The sandbox counts them too.* A driver reports each pending frame as
    an enter and a return of an internal `RootTag` node, so
    `sandbox.MaxStackFrames` counts it (D10, "Sandboxing").
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
| string | `SString`: Latin-1 `byte[]` until a character above 255 is stored, then UTF-32 `int[]`. Indexing and `string-set!` are O(1). Literals carry an immutable flag. `substring`, `string-append` and `string-copy!` copy between strings of one storage with an array copy (M8-04). A string from Java text (a `String`, or a reader's `char[]`) is built in one pass: Latin-1 bytes until the first unit above 255, then code points, each run of units between surrogates copied by a widening loop of its own (#216 and #220, after M10-05; `codePoints().toArray()` and two more passes until then). A decoder that fills the arrays itself hands them over (`ofLatin1`, `ofCodePoints`, M11-06), copied only to leave out unused room. |
| symbol | `SSymbol`, interned per context (held weakly, so a symbol nothing refers to is collected; M8-02), name is a `TruffleString` |
| pair | `SPair`, mutable `car`/`cdr` fields; literal pairs aren't marked immutable yet (below) |
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
  - **Transcendental functions are fdlibm's (`StrictMath`)**: `exp`, `log`
    (with one argument and two, the quotient of two logs), `sin`, `cos`,
    `tan`, `asin`, `acos`, `atan` (one and two arguments), `expt` of flonums,
    and the complex paths (`hypot`, `sinh`, `cosh`, `make-polar`), and the
    reader's polar literals, so `1@1` reads as the bits `(make-polar 1 1)`
    returns. So a program writes the same digits on every platform, in both
    launchers and at every tier. `sqrt` of a flonum stays `Math.sqrt`, which
    IEEE 754 defines (correctly rounded everywhere), and `inexact` of an exact number
    is our own integer arithmetic, also correctly rounded (`Arithmetic.flo`).
    R7RS doesn't fix the accuracy of transcendental functions (§6.2.6), so
    this is about quality,
    found by the fuzzer (D19, #230's follow-up: `(exp 1)` was
    `2.7182818284590455` here, one ulp above the nearest flonum).
    Reproducibility first, then accuracy, then speed. Measured on GraalVM
    25.5 (November 2026), a sweep of 260,000 arguments (20,000 per function,
    seeded), its results compared bit for bit, and against correctly rounded
    references (Python's `decimal` at 80 to 260 digits) for all of them:
    - **`Math` is not reproducible.** `java.lang.Math` is free to differ
      from fdlibm by an ulp, and HotSpot and Graal replace it with
      platform-specific code. On x86-64 (a BuildBuddy executor's Xeon,
      HotSpot's Intel LIBM stubs) `exp`, `log`, `pow`, `sin`, `cos` and `tan`
      differed from AArch64's (an Apple M3 Max) in 9.1%, 4.4%, 9.5%, 7.7%,
      7.7% and 2.8% of the sweep's arguments; `(exp 1)` was
      `2.718281828459045` on x86-64 and `2.7182818284590455` on AArch64.
      On AArch64, `sin` and `cos` are a HotSpot stub and the rest fdlibm,
      and the native launcher (D17) doesn't use that stub: its `sin` and
      `cos` differed from the JVM launcher's in 6% of the arguments. Within
      one VM, `Math` was the same in HotSpot's interpreter, C1, C2 and Graal
      as the host compiler, and in our interpreter and compiled code.
    - **`StrictMath` is**: Java specifies its results bit for bit (fdlibm's
      algorithms), and the sweep's were identical on both platforms, at every
      tier, and in both launchers.
    - **Accuracy** (share of the sweep not correctly rounded; never more than
      1 ulp off, 2 for `(log z1 z2)`): fdlibm `exp` 9.2%, `log` 4.4%, `sin`
      2.8%, `cos` 2.9%, `tan` 2.8%, `asin` 5.8%, `acos` 7.5%, `atan` 0.9%,
      `atan` of two arguments 18%, `expt` 9.5%. x86-64's `Math`: `exp` 0.3%,
      `log` 0%, `sin` 0.1%, `cos` 0.2%, `tan` 0.3%, `expt` 0.1% (the rest are
      fdlibm's there too). AArch64's `Math` `sin` and `cos`: 7.7% each. So
      `StrictMath` is a little less accurate than x86-64's intrinsics, and
      more than AArch64's. `(exp 1)` and `(log 3)` are an ulp off with it
      (`NumbersTest`). chibi-scheme's C library is no better on every
      argument: on macOS its `(asin 0.5)`, `(acos 0.5)` and `(tan 1)` are an
      ulp off, and fdlibm's are the nearest.
    - **Speed** (Apple M3 Max, our JVM launcher, 5 interleaved rounds,
      nanoseconds per call in a loop of a million, `Math` → `StrictMath`):
      with the JIT, `sin` 15.5 → 21.5, `cos` 16 → 20.5, `atan` 17 → 19.5,
      `asin` 17 → 19, `exp` 23 → 20.5, `tan` 30 → 27, `atan` of two 20.5 →
      21, against 7 for the loop alone; in the interpreter within 11% (115
      for the loop alone). ecraven's `fft`, `mbrot`, `nucleic`, `fibfp`,
      `pnpoly`, `sum1` and `sumfp` (3 interleaved rounds, 6 iterations, 2
      of them warmup) were within 1% of `Math` once compiled, and within
      noise interpreted (at a count of 1, where programs that call no
      transcendental function moved as much). `nucleic`'s check passes
      with both. `log` and `expt` of flonums were slower, 500 and 1,050 ns,
      not for fdlibm (3 and 26 ns a call in a Java loop) but because
      `Arithmetic.sign` compared a flonum with exact 0 as an exact rational
      (through `BigDecimal`).
      Flonums and fixnums now take their sign directly, and `log` is 16 ns,
      `expt` 51 ns (#238's follow-up; 3 interleaved rounds, `sqrt` 9.4 and
      the loop 7.4 in the same runs). Then (#242's follow-up) `sign` became
      exact numbers' only: it had taken -0.0 and a NaN for zeros, so
      `(expt +nan.0 0.5)` was 0.0 and `(magnitude -0.0)` -0.0. Each caller
      now tests a flonum's cases itself, as R7RS §6.2.4 and IEEE 754 have
      them: a NaN operand gives NaN (`(expt 0. +nan.0)` too; but 1 to any
      power is 1, below), any number
      to a zero power is 1 (also a NaN or a negative base: `(expt -1. 0.)`
      is 1.0, not 1.0+0.0i), magnitude is abs, and max and min take 0.0
      above -0.0 (IEEE 754-2019's maximum and minimum).
    - **`expt` and `magnitude` at infinities** (#243's follow-ups). A real
      base to a real power is IEEE 754's pow (R7RS §6.2.3 recommends IEEE
      754-2008's transcendental functions, "particularly with respect to
      infinities and NaNs") whenever the result is real: a non-negative
      base, or an integer-valued flonum power, an infinite one included
      (every flonum beyond 2^53 is an even integer, so pow's |x|^±∞ is the
      limit; pow(±1, ±∞) is 1 as in IEEE 754-2008, where Java's is NaN).
      `(expt -2. 2.)` is 4.0, not 4.0-9.8e-16i from e^(2 log -2). Only a
      negative base to a finite non-integer power is complex: §6.2.6's
      e^(z2 log z1), with the real z2 multiplying each part of log z1 (C99
      Annex G.5.1's real times complex), so `(expt -inf.0 0.5)` is
      +inf.0+inf.0i, the limit of `(expt -1e308 0.5)`, not
      +nan.0+nan.0i from 0·∞. `(* 0 +inf.0)` stays NaN and `(* 0 1.5)`
      0.0: §6.2.2 lets an exact 0 times an inexact number be exact 0
      ("`(* 0 +inf.0)` may return 0, or +nan.0"), and ours is the inexact
      product, the rule of every other operation, IEEE 754's, and the
      typed `*`'s fast path. An exact base to an exact power stays exact.
      The magnitude of a complex number is `StrictMath.hypot`, the same as
      `log`'s real part takes the log of: no overflow of the squares
      (`(magnitude 1e300+1e300i)` was +inf.0), +inf.0 for an infinite part
      even with a NaN one (IEEE 754's hypot), and closer to the nearest
      flonum than `sqrt(x²+y²)` (not correctly rounded in 13% and 17% of a
      sweep of 200,000 arguments). An exact one is exact if it can be,
      `(magnitude 3+4i)` 5, and otherwise the flonum nearest to the root
      of its exact sum of squares (`sqrt`, below), also beyond the
      flonums' range.
    - **A real and a complex number** (M11-01, #244's follow-ups). `+`,
      `-`, `*` and `/` take the real number with each part of the complex
      one on its own, C99 Annex G.5.1's table: x(u+iv) = xu + i(xv),
      (u+iv)/x = u/x + i(v/x), x + (u+iv) = (x+u) + iv, x − (u+iv) =
      (x−u) + i(−v), (u+iv) − x = (u−x) + iv, and x/(u+iv) =
      x(u−iv)/(u²+v²), the real times the conjugate (Annex G leaves this
      one to complex division; ours drops its terms that would multiply
      the real's 0 imaginary part). The real used to become x + 0i, an
      exact 0 that `flo` made 0.0, so 0·∞ made `(* 2. (make-rectangular
      +inf.0 1.))` +inf.0+nan.0i (chibi-scheme and Annex G: +inf.0+2.0i),
      and 0 + -0.0 made `(+ 1. (make-rectangular 1. -0.))` 2.0+0.0i. Each
      part is now one IEEE 754 operation (or, for x/(u+iv), the same ones
      as before, without the zero terms), so its sign of zero is IEEE
      754-2019's: a product or quotient's is the exclusive or of the
      operands' (§6.3), an exactly zero sum of opposite signs (or difference
      of like ones) is +0 (§6.3, round to nearest), and negation flips it
      (§5.5.1). The audit, case by case, against the value before: `+`
      keeps the complex number's imaginary part, -0.0 included; `-` keeps
      it or negates it, so `(- 1. (make-rectangular 1. 0.))` is
      0.0-0.0i, as Annex G has it (0 − 0.0 was 0.0); `*` gives the
      imaginary part x·v's sign, `(* -2. (make-rectangular 1. 0.))`
      -2.0-0.0i (x·v + 0·u was +0.0 whenever x·v was a zero); `/` by a
      real divides each part, without the square of x, so `(/
      (make-rectangular 1. 2.) 1e200)` is 1.0e-200+2.0e-200i, not 0.0+0.0i,
      and a division by ±0.0 gives signed infinities, not NaN; a real
      divided by a complex number has the conjugate's sign, `(/ 1.
      (make-rectangular 1. 0.))` 1.0-0.0i, as 1/z is conj(z)/|z|².
      R7RS §6.2.4 makes -0.0 "distinct (in the sense of eqv?) from 0.0"
      and doesn't fix these signs, nor this accuracy; Annex G and IEEE
      754 do. Unchanged: an exact real with exact parts stays exact (and
      `(* 0 +inf.0+1.i)`'s real part NaN, #243's rule for an exact 0);
      mixed exactness makes both parts inexact; two complex numbers take
      the textbook formulas; and a division by a complex number, of a real
      or a complex one, still squares the divisor's parts, so `(/ 1.
      (make-rectangular +inf.0 1.))` is +nan.0-0.0i, not 0.0-0.0i, and
      `(/ 1. (make-rectangular 1e200 1e200))` 0.0-0.0i (Annex G's
      `_Cdivd` scales the divisor and recovers infinities: a follow-up).
    - **`sqrt` of an exact non-square** is the flonum nearest to its root,
      one rounding of the exact value, as `inexact` of an exact number is
      (above) and as IEEE 754-2019's squareRoot is of its operand (§5.4.1).
      It was `Math.sqrt(flo(z))`: +inf.0 for a z beyond the flonums' range
      whose root is inside it (`(sqrt (* 2 (expt 10 400)))`, now
      1.414213562373095e+200), 0.0 for a tiny one (now
      1.414213562373095e-200 for `(sqrt (/ 2 (expt 10 400)))`, and a
      subnormal where it is one), and an ulp off now and then from the
      double rounding (`(sqrt 719831/227121)` was 1.7802730894245993, the
      nearest is 1.7802730894245995). It is integer arithmetic
      (`Arithmetic.nearestSqrt`): q = ⌊√(n·4^s/d)⌋ of about 64 bits, and
      (q + ½)/2^s rounded once (`Numbers.nearestDouble`), which rounds as
      the irrational root does since no rounding boundary lies between q
      and q+1. A fixnum up to 2^53, exactly a flonum, keeps `Math.sqrt`.
      Checked against Python's integer square root on 3,000 random big
      integers and ratios of up to 2,300 bits: all the same.
    - **`(expt 1. +nan.0)` is 1.0** (M11-01; #243 had made it +nan.0).
      IEEE 754-2019 §9.2.1: "pow(+1, y) is 1 for any y (even a quiet
      NaN)", as C99 F.9.4.4 has it; Java's `pow` gives NaN for any NaN
      power. R7RS §6.2.4 allows either: "An arithmetic operation where one
      operand is NaN returns NaN, unless the implementation can prove that
      the result would be the same if the NaN were replaced by any
      rational number", and 1^q is 1 for every rational q. §6.2.3
      recommends IEEE 754-2008's transcendental functions "particularly
      with respect to infinities and NaNs", and pow(±1, ±∞) is already 1
      here (#244), so a base of 1 (exact or inexact) to any real power is
      1.0. chibi-scheme agrees (C's `pow`). Not pow(−1, NaN), which IEEE
      leaves NaN, nor a NaN base to a non-zero power.
    - Tests: `NumbersTest` (known values, a sweep against `StrictMath`, the
      sweep interpreted and compiled, what the sign of each special value
      gives, NaN and -0.0 against R7RS and IEEE 754, `expt` of negative
      bases and `magnitude` at infinities, a real and a complex number,
      `sqrt` of exact numbers, and polar literals against `make-polar`),
      `tests/diff/numbers-transcendental.scm` (values that
      both fdlibm and chibi-scheme round to the nearest),
      `tests/diff/numbers-special-values.scm` (NaN and -0.0 where chibi-scheme
      agrees with R7RS), `tests/diff/numbers-expt-magnitude.scm` and
      `tests/diff/numbers-complex-edges.scm`.
    - *Rejected:* `Math` (above); `StrictMath` for `exp` and `log` only,
      which wouldn't have fixed the case found, since fdlibm's `(exp 1)` is
      the one an ulp off, and leaves the rest platform-dependent; a correctly
      rounded library (CORE-MATH, CRlibm), which would be a new dependency
      (AGENTS.md), or a port of one to Java, which is a project of its own.
  - **Dispatch.** Arithmetic operations specialize on `long` and `double` as
    fast paths, with overflow checks on the `long` path. `SBigInteger`,
    `SRational` and `SComplex` go through one generic path marked
    `@TruffleBoundary`, so compiled code stays small for the common cases.
- **Literal pairs aren't marked immutable (yet).** R7RS §3.4 (*"literal
  constants ... are immutable ... it is an error to attempt to store a new
  value into a location that is denoted by an immutable object"*) and
  §4.1.2 (*"it is an error to attempt to alter a constant (i.e. the value
  of a literal expression) using a mutation procedure like `set-car!` or
  `string-set!`"*) allow not detecting it, and for now nothing does:
  literal strings, vectors and bytevectors carry an immutable flag and
  their mutators signal, but `(set-car! '(1 2) 9)` succeeds, so
  `(define (g) '(constant-list))` returns a list a `set-car!` changed for
  every later call. The maintainer's decision (November 2026): keep that
  until marking them costs nothing, since catching the bug matters. Two
  representations were built and measured, each with interleaved A/B runs
  against (A), today's final `SPair` with no marking:
  - **(B) A `final boolean immutable` field on `SPair`** (#169, closed).
    - Free on HotSpot: a pair is 24 bytes either way (a 12-byte header and
      two compressed references, padded; the boolean fits in the padding).
    - In the native launcher (D17, 8-byte header) every pair goes from 16
      to 24 bytes: consing 10 million pairs allocated 229.61 MB instead of
      152.92 MB.
    - `destruc`, mostly `set-car!` and `set-cdr!`, was 3–4% slower in the
      JIT, from the check itself (without it the field cost nothing). A
      slow-path hint (`injectBranchProbability`) and a deoptimizing
      seen-a-literal profile didn't help.
  - **(C) A `final class LiteralPair extends SPair`** with no field of its
    own (branch `literal-pair-subclass` at 9c24853, not a PR; 7 variants
    measured, 5 interleaved rounds each). A literal's pairs are
    `LiteralPair`s; `set-car!`, `set-cdr!` and `list-set!` take the fast
    path only for exactly `SPair` (one compare of the class, which is also
    the type check), and `car`, `cdr`, `pair?` and the list primitives'
    loops compare the exact class with each of the two rather than use
    `instanceof`.
    - Memory: none. Natively, 10 million conses are 152.92 MB on both; on
      HotSpot the class histogram is identical.
    - JIT: neutral or faster (`destruc` 0.95×, `mazefun` 0.98×, a `length`
      loop 0.84×).
    - Interpreter: slower on programs that mix literal and run-time pairs:
      `paraffins` 1.056× (every round 1.02–1.06), `browse` 1.089×
      (0.94–1.12), a micro-benchmark mixing literal and run-time pairs
      +24%.
    - The native launcher's first runs: `destruc` 1.044×, `nboyer` 1.046×.
    - The cause is the host compilation of the Bytecode DSL interpreter
      loop (`continueAt`; "The host compiler"), not the checks: JFR spreads
      the extra time across the loop, and one variant stopped HotSpot
      inlining `Call.doPrimitive` and `SetPair.invoke` into it.
    - Variants v2–v7 (generalising `car`/`cdr` with `replaces`,
      `@ForceQuickening`, `getClass() ==` casts) each traded the
      interpreter's cost for the JIT's: +4–10% on the JIT's `set-car!`
      micro-benchmark.
  - (C) is the preferred direction: its cost is in how the host compiles
    the interpreter loop, which M10-02 changes, so it is re-measured after
    M10-02 (plan.md M10-07). (B)'s native memory cost is in the
    representation and won't go away. Also rejected: a weak identity set of
    literal pairs (a hash lookup in every `set-car!`).
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
- **Representations chosen outside loops (M10-06).** A Java loop that
  partial evaluation inlines into compiled code (a primitive that runs in
  place) tests a value's storage once, before the loop, and has a loop per
  storage, rather than calling an accessor that tests it at every element.
  `SString.compare` (`string<?` and the other comparisons) has a loop for
  each pair of storages. It was a loop of `codePointAt`, which reads
  `latin1`, or else `wide`: Graal's speculative guard movement
  (`SpeculativeGuardMovementPhase`) moved the null check of `wide`, which is
  loop-invariant, out of the loop and so above the storage test, where it
  ran for Latin-1 strings too. The first comparison of two Latin-1 strings
  hit it, a `null_check` trap that invalidated the code (`--engine.TraceCompilation`:
  `Invalidated true`, `Reason uncommon trap`; it isn't a transfer to the
  interpreter, so `TraceTransferToInterpreter` doesn't list it). The
  speculation log that then keeps the guard in the loop belongs to the call
  target (`OptimizedCallTarget.speculationLog`), and a split is a call
  target of its own whose log starts empty. In ecraven's `scheme` at its own
  count, 11 splits of `gen-combination-2`'s lambda, which run `string<?` in
  place for the sort's comparison, and `gen-if`'s lambda, which inlined one,
  each deoptimized once (#205 saw 8 splits at a tenth of the count): 34
  deoptimized frames with their callers' on the stack. With a loop per
  storage, none: 12 invalidations → 0, 34 deoptimized frames → 1, 291
  compilations → 242, and the first iteration 5.03 → 4.83 s (0.96×; later
  iterations 3.97 and 3.93 s → 3.94 and 3.91 s; medians of 6 interleaved
  rounds, 3 iterations in a fresh JVM, `-Xmx2g`, the serial collector, an
  Apple M3 Max at a 1-minute load of 4.6 to 6.4; the first iteration was
  faster in 5 of the 6).
  `SplitRecompilationTest` checks that neither code that inlines a
  comparison nor a split that runs one in place is invalidated.
  - *How it was found.* `-XX:+UnlockDiagnosticVMOptions -XX:+LogCompilation`
    logs each `<uncommon_trap>` with its reason, its speculation and its
    frame state (here the state after the last call before the guard,
    `OptimizedCallTarget.doInvoke`, since nothing with a side effect is
    between them). `--vm.Djdk.graal.Dump=Truffle:1
    --vm.Djdk.graal.PrintGraph=Disable --vm.Djdk.graal.PrintBackendCFG=true
    --vm.Djdk.graal.TrackNodeSourcePosition=true` writes each Truffle
    compilation's final schedule as text (a `.cfg` file per compilation,
    named with HotSpot's compile id), whose deoptimizing nodes carry their
    reason, speculation and Java source position: the read with the trap's
    speculation was `codePointAt`'s `wide[index]` (through `StringCompare`
    and `MakeTailCall.doPrimitive`), scheduled right after the call that
    returned the string. No IGV is needed. `--engine.TraceDeoptimizeFrame`
    printed nothing.
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
  `syntax-error`, `quasiquote` (with `unquote` and `unquote-splicing`), the
  auxiliary syntax `else`, `=>`, `...` and `_` (D7), plus the internal form
  `%prompt` (`scheme.compiler.CoreForm`).
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
  (`apply`, `call/cc`, `eval`, `load`, and the continuation internals such
  as `%capture-to-prompt`: the primitives whose `PrimitiveSpec.Result` is
  `TRANSFERS_CONTROL`) does it **only by returning a `TailCall` request** to
  its call site's trampoline.
- Anything that needs a non-tail call (`map`, `for-each`, `vector-map`,
  `string-for-each`, `call-with-values`, `dynamic-wind`, `raise`,
  `with-exception-handler`, parameter converters, `call-with-port`) is
  written in the Scheme prelude on top of `%`-primitives.
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
  An assignment (`set!`) is a store to the cell; only invalidating the
  assumption, the first time, is runtime code (`@TruffleBoundary`), behind an
  `isValid()` test that compiled code reads as a constant: the pattern of
  GraalVM's partial evaluation paper (Würthinger et al., PLDI 2017, §3.3,
  Figure 5). The whole assignment was a boundary until the Truffle guidance
  audit, a runtime call on every `set!` of a global in compiled code: a
  compiled loop of two `set!`s took 1.75 times as long (0.124 s against 0.071
  s an iteration, 3 interleaved JVMs each).
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
  only when every list is circular. An improper list isn't a list either,
  so the same procedures raise the same error when their walk reaches a
  tail that is neither a pair nor `()` (the §6 audit's follow-up, below):
  `memq` and the rest when the object isn't found before it, and `map` and
  `for-each` when they stop, naming the first list whose tail it is (so
  `(map + '(1 2) '(10 20 30 . 40))` stops at the shorter list without
  reaching the improper tail, as chibi-scheme does). The check is the walk's
  last test, once a call, so a proper list costs nothing more. `list-tail`, `list-ref` and `list-set!`
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
    string, vector or bytevector (not yet a literal pair: D1) or
    `symbol->string`'s result (§6.5); `exact` of an infinity or a NaN,
    `exact-integer-sqrt` of anything but a non-negative exact integer, and
    integer division (`floor/`, `truncate/`, `quotient`, `modulo`, ...) by
    any zero; `utf8->string` of invalid UTF-8 (§6.9: *"It is an error for
    bytevector to contain invalid UTF-8 byte sequences"*).
  - Since the audit's follow-up, also `/` with an exact zero divisor and an
    inexact argument (§6.2.6: *"It is an error if any argument of / other
    than the first is an exact zero"*): `(/ 5. 0)` raises `"/: division by
    zero"`, as `(/ 5 0)` did, where it gave IEEE's `+inf.0` (an inexact zero
    still divides as IEEE does: `(/ 5. 0.)` is `+inf.0`). The check is in the
    primitive (`Arithmetic.apply`); an exact zero is always the fixnum 0, so
    the typed `Div` operation's flonum path (M7-03), which takes two
    flonums, never sees one, and a site that divides flonums keeps only that
    specialization (`NumbersTest`). Larceny's `(/ 1.0 0)` and `(/ 0.0 0)`
    are listed exceptions for it. And `memq`, `memv`, `member`, `assq`,
    `assv`, `assoc`, `map` and `for-each` given an improper list (above),
    where they returned `#f` at the tail or stopped there, and took a
    non-list argument for an empty list.
  - Not signalled, on purpose: `(expt 0 z)` with a
    negative real part returns a number when z is inexact (`(expt 0 -1.)` is
    `0.0`; §6.2.6 allows *"either an error is signalled or an unspecified
    number is returned"*). `number->string` of
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
- **Standard output is buffered.** The console output port writes to a
  `BufferedOutputStream` (8 KB) over the Env's `out()`, not straight through:
  through, every `display` was a write to the stream, which the launcher's
  `System.out` passes on as a system call (printing a million short lines
  took 3.2 s to `/dev/null` and 6.3 s to a file, against 0.5 s buffered),
  and another stream could split a line of it (#195). The buffer is written
  out, and the Env's stream flushed (`SchemeContext.flushOutput`, which costs
  a field read when the buffer is empty):
  - when control goes back to the caller: as a program, a REPL input or an
    interop call leaves its root prompt (`PromptRootNode`,
    `InteropCallRootNode`), normally, with an error or with an exit or
    interrupt (so the launcher's error message comes after the output, and
    an embedder finds the output in its stream when `eval` or `execute`
    returns);
  - before a write to the console error port, so the two stay in order
    where they meet (a terminal, or `2>&1`);
  - before the console input port reads the Env's `in()` or asks what is
    available (`char-ready?`): it does only when its own buffer runs out,
    which is when a read may block, so a prompt shows before the program
    waits for its answer, and the REPL's output before its prompt;
  - on `flush-output-port`; on `exit` and `emergency-exit`, before
    `closeExited`. R7RS §6.14 says `emergency-exit` terminates *"without
    running any outstanding dynamic-wind after procedures"*, and says nothing
    of ports: writing out what the program wrote is what C's `exit` does,
    and the program can't tell the difference except by losing output;
  - when the context closes (`finalizeContext`), and when a pre-initialized
    context is patched (D17).

  The `scheme.BufferOutput` option (default true) turns it off: each write
  is then flushed at once, as before. The launcher turns it off when stdin
  and stdout are a terminal (the framework's `isTTY()`), unless the command
  line sets it, so a terminal sees partial lines (progress dots) at once;
  with stdout on a terminal but stdin redirected, output is buffered (Java
  can't tell stdout alone is a terminal without a native call). Standard
  error is never buffered. File output ports are buffered too, and written
  out at the same points (next item).
- **Open file ports are written out with standard output.** Output file
  ports, textual and binary, write to an 8 KB buffer over the
  `TruffleFile`'s stream (binary ones wrote each `write-u8` through until
  then: writing 10 MB with `write-u8` took 18 s, against 0.26 s buffered,
  and in 16-byte `write-bytevector`s 1.3 s, against 0.22 s). Input file
  ports read through an 8 KB buffer too: the textual one through
  `TruffleFile.newBufferedReader`'s `BufferedReader`, the binary one through
  a `BufferedInputStream` over the file's stream, which until then it read
  once per byte (reading 10 MB took 4.3 s with `read-u8` and 4.1 s in
  4096-byte `read-bytevector`s, which read a byte at a time too, against
  0.26 s and 0.09 s buffered). The bulk reads, `read-bytevector`,
  `read-bytevector!` and `read-string`, copy from the buffer
  (`SPort.readBytes` and `readString`, `final` boundaries as in "Ports in
  compiled code"), where they called the port once per byte or character:
  10 MB in 4096-byte `read-bytevector`s takes 0.002 s once compiled, against
  0.038 s, and in 16-byte ones 0.008 s, against 0.040 s; `read-string` of a
  10 MB UTF-8 file in 4096-character chunks 0.044 s, against 0.073 s (#200's
  0.09 s included opening the file and summing every byte). `read-string`
  asks the reader for as many UTF-16 units as characters are still wanted,
  so it never reads past them, and pairs a high surrogate that ends a read
  with the low one after it. `read-line` finds the line ending in the
  buffer too (`SPort.readLine`, #216, after M10-05), where it read a
  character at a time: a file's or the console's `BufferedReader` with its
  own `readLine`, which ends a line where we did, at LF, CR LF or a CR
  alone, and skips the LF after a CR on its next read, so a CR typed at the
  console ends a line without waiting for the next character; a string
  port's reader in chunks of 128 to 8192 units, marked so that it goes back
  to the line's end. Each line goes straight into an `SString` (D1: one
  pass), and so does what `read-string` reads: the reader's units go into
  one `char[]`, counted as characters only when that could make `k` (so a
  whole file's are never counted), and that array becomes the string, with
  no `StringBuilder` or `String` between. A 10 MB file of 200,000 lines
  takes 0.037 s once compiled, against 0.079 s. `read-string` of a whole
  10 MB file takes 0.007 s when it is ASCII and 0.019 s when 10% of its
  lines are wide, so the string is too, against 0.0085 s and 0.031 s with a
  `StringBuilder` and a `String` between (#216, which took them from 0.020 s
  and 0.045 s); most of what is left of the wide one is the JDK's UTF-8
  decoder, which leaves its ASCII fast path at a buffer's first other byte.
  `u8-ready?` on a file is still always `#t`: a read of a file never
  blocks, and at its end, where the stream's `available()` is 0, R7RS §6.13.2 says *"If port is at end of file, then
  u8-ready? returns #t"* (chibi-scheme answers `#f` there). The context
  tracks the file ports it opened, input and output (`OpenFiles`), and
  `flushOutput` writes out what the output ones hold wherever it writes out
  standard output: when control goes back to the caller (so after a program
  ends, normally or with an uncaught error), on `exit` and `emergency-exit`
  (Truffle calls `finalizeContext` after a hard exit too) and when the
  context closes, which also closes them all (`disposeContext`). A program
  that never closes a port loses none of its output, and an embedder or the
  REPL finds it in the file when the call returns; no port, input or
  output, holds its file's descriptor past the context (M10-05 tracks input
  ports too: until then one never closed kept its descriptor until it was
  collected). R7RS doesn't ask for this: §6.13.1 says only that `close-port`
  *"closes the resource associated with port, rendering the port incapable
  of delivering or accepting data"*, nothing of a port never closed, and
  §6.14's `exit` *"runs all outstanding dynamic-wind after procedures,
  terminates the running program, and communicates an exit value to the
  operating system"*, nothing of ports either. It is our choice, what C's
  `exit` does for its open streams; chibi-scheme writes them out at exit,
  after an error and at a program's end too (no differential test can
  show it: they compare standard output). Closing a collected port (below)
  is what §6.13.1 allows `call-with-port`: a port *"must not be closed
  automatically unless it is possible to prove that the port will never
  again be used for a read or write operation"*. An I/O error in these
  implicit flushes is ignored, as C's `exit` ignores one: `flush-output-port`
  and `close-port` raise it.
  - The ports are tracked through weak references, so tracking keeps none
    alive; each reference holds the port's buffered stream strongly
    instead. When the program drops a port without closing it, the
    collector queues its reference, and the next time the context opens a
    file port or flushes them, it writes out (an output port's) and closes
    that stream, on the context's thread. Closing a port stops tracking it. A program that opens
    10,000 files and drops them keeps only the ones not yet collected.
  - *Rejected:* `Env.registerOnDispose`, Truffle's hook for resources that
    live as long as the context: it closes them when the context is
    disposed, but holds them weakly, so a port collected before that loses
    its buffer, and it doesn't flush on return or on exit. A
    `java.lang.ref.Cleaner` that flushes a collected port's stream: it runs
    on the cleaner's thread, outside the context (D10: single-threaded), and
    would write through an embedder's file system from a thread it doesn't
    expect.
- **Textual input file ports decode UTF-8 themselves (M11-06).** The port
  reads the file's bytes into its own 8 KB buffer and decodes them straight
  into what D1 stores: Latin-1 bytes while every character is at most 255,
  code points from the first one above. `read-char` and `peek-char` decode
  one character from the buffer, and `read-string` and `read-line` decode a
  run of them into the string's own array, with no `char[]` or reader
  between. A sequence that a buffer's end cuts is completed from the next
  read. Malformed input is replaced exactly as the JDK's UTF-8 decoder
  replaces it (what the port did until then, through
  `TruffleFile.newBufferedReader`'s `InputStreamReader`): one U+FFFD for each
  malformed sequence, with the decoder's lengths (an encoded surrogate,
  `ED A0 80`, is one; a byte that can't start or continue a sequence is
  one; a sequence the file's end cuts is one). The console port keeps the
  JDK's reader, whose `ready()` is what `char-ready?` asks; a string port
  keeps its `StringReader`. R7RS says nothing of encodings: this keeps
  what files read as and makes it faster. The JDK's decoder took its ASCII
  fast path only at the start of each buffer, so after a buffer's first
  other byte it went a byte at a time, and decoded into UTF-16 that the
  port copied again: decoding was 0.0115 s of `read-string`'s 0.017 s for a
  whole 10 MB file with 10% of its lines wide, against 0.002 s for an ASCII
  one (#220). Decoding that file into the string's arrays ourselves takes
  0.010 s, decoding included (host microbenchmark).
  - *Rejected:* `TruffleString` (`fromByteArray` in UTF-8, then
    `switchEncoding` to UTF-32 or a code point iterator). It replaces
    malformed input differently: three U+FFFD for an encoded surrogate where
    the JDK has one, and its iterator one for each byte of a sequence the
    end cuts; of 100,000 random byte strings of up to 7 bytes, 309 decode
    differently through `switchEncoding` and 9,893 through the iterator. A
    stream still needs its own handling of a sequence cut by a buffer's end,
    and the UTF-32 bytes still need copying into the `int[]`. In runtime
    code, behind the port's boundary, it was also slower: 0.019 s for the
    wide file against 0.010 s, and the same 0.003 s for the ASCII one (host
    microbenchmark, an 8 KB chunk at a time).
- **Ports in compiled code.** The I/O primitives' compiled code sees a port
  it can't know the class of (it comes from a parameter or an argument), so
  nothing it calls on `SPort` is virtual: a port's direction and kind are
  `final` fields, and each operation (`write`, `flush`, `read-char`, ...) is
  a `final` method behind a `@TruffleBoundary`, since it is I/O, which calls
  the kind of port's own implementation as runtime code. Its virtual
  `isInput`, `isBinary` and operations were calls partial evaluation can't
  inline, a performance warning each (30 in ecraven's `scheme`), and a
  runtime call for each check (#208: ecraven's `cat` and `wc` take 0.84 and
  0.83 times as long). *Rejected:* DSL specializations on the four port
  classes: the operation is behind a boundary either way, and the checks
  fold as field reads.
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
- **Sandboxing.** The language declares `SandboxPolicy.CONSTRAINED`
  (`Registration.sandbox`), and so do the options an embedder sets
  (`scheme.LibraryPath`, `scheme.BufferOutput`, `scheme.StackSize`,
  `scheme.PreludeImage`; not `scheme.SpillDepth`, for tests): everything it
  reaches of the host goes through the Env (the rules above), `exit` is
  Truffle's (D9), and Scheme reaches no host object. Until then a
  `CONSTRAINED` context couldn't use it at all. `ISOLATED` and `UNTRUSTED`
  need the language built as a polyglot isolate, which nothing builds.
  Truffle's limits work as GraalVM documents them (statements, CPU time,
  output size, stack frames, interrupt and cancel); `sandbox.MaxASTDepth`
  isn't enforced on Bytecode DSL roots (docs/upstream.md).
  docs/graalvm-configuration.md has the audit; `SandboxTest` the checks.
  - *`sandbox.MaxStackFrames` counts the program's frames, on the Java stack
    or on the heap.* The limit is on the program's stack depth, so a frame
    counts while the program will return to it: the frames on the Java
    stack, and the frames a prompt driver holds pending, those of a spill
    ("Stack-depth spill") or of a continuation the program goes on with. A
    continuation the program only keeps (in a variable) doesn't count until
    it is invoked, and then all its frames do. A tail call adds nothing: its
    caller's frame has returned (AGENTS.md rule 5).
  - *How Truffle counts.* The sandbox instrument counts, per thread, the
    enter and return events of `RootTag` nodes (25.5's
    `SandboxInstrument.FrameCounterNode`, attached with
    `SourceSectionFilter.newBuilder().tagIs(RootTag.class)`, which includes
    internal code). It doesn't override `onYield` and `onResume`, so
    `ExecutionEventNode`'s defaults apply: *"Delegates to
    `onReturnValue`"* and *"Delegates to `onEnter`"*. The Bytecode DSL reports
    a yield and a resume for every tag around each `@Yield`, the implicit
    root tag included (the generated `tag.yieldNull` and `tag.resume`
    instructions, `doEmitTagYieldNull` and `doEmitTagResume` in the
    builder), so a Scheme frame that suspends leaves the count and comes back
    when it is resumed: balanced, with no drift (measured: the deepest
    recursion a limit allows is the same after 1,000 re-entries of a
    continuation). That is Truffle's model of a yield, a generator's frame
    is off the stack until resumed; but it left every spilled frame out, so
    once a recursion spilled it never reached the limit (#228: with
    `scheme.SpillDepth=20`, a limit of 40 let a recursion of any depth run).
  - *What the drivers add.* Each prompt driver has a `PendingFramesNode`:
    tagged `RootTag`, with an internal source section, so the tools that leave
    out internal code (the debugger, the CPU sampler and tracer, coverage)
    never see it. A driver reports an enter of it for each suspended frame
    that joins its pending list (from a capture, a spill, a re-entered
    continuation, a reinstated composable one), and a return for each that
    leaves it: resumed (the frame then reports its own resume), dropped (an
    escape, an abort, a full continuation that replaces the current one, an
    error unwinding through it) or passed on to an outer prompt's driver,
    which enters it there. A suspended frame's yield and the driver's enter
    cancel out, as do the driver's return and the frame's resume, so the
    count is the program's depth. Whatever a driver holds when it returns or
    throws, it drops (a `finally`): an exception that leaves the driver,
    such as an interrupt, takes its frames off the count.
  - *Cost.* The node is wrapped only when an instrument binds `RootTag` nodes
    in internal code (the sandbox's frame and AST depth limits); until then
    the driver's check of its child folds away in compiled code, and is an
    `instanceof` in the interpreter: without the limit, the launcher ran a
    recursion a million deep (five times a round) and a `ctak` with a
    continuation 100 frames deep re-entered 20,000 times as fast as before,
    interleaved, within noise (348 to 354 ms and 76 to 82 ms, against 349
    to 356 and 74 to 79), and `bench-check` passed. Under the limit, each
    frame a recursion spills costs an enter and a return more (that recursion
    took 650 ms instead of 354), re-entering a full continuation a call per
    frame of it, and dropping the current one a call per frame, where without
    it both are O(1).
  - *Considered:* reporting enter and return on the suspended frame's own
    root tag (every instrument would see the procedure entered twice:
    coverage's counts, the debugger's stepping); leaving the yield and resume
    events out for a spill (the Bytecode DSL emits them for every yield, to
    every instrument); and counting Java frames only, as before
    (#228), with `sandbox.MaxHeapMemory` bounding deep recursion instead
    (memory isn't depth: the limit bounded nothing once a recursion spilled,
    and a runaway recursion ran until the heap limit).
- **`ISOLATED` and `UNTRUSTED`: the polyglot isolate library (M11-11).**
  Both policies run the guest in a polyglot isolate, a native image of the
  language with its own heap, collector and JIT, which the host's Truffle
  loads as a shared library (`engine.SpawnIsolate`, preset by both). We
  build that library, and a jar that carries it, as GraalVM builds its own
  languages' `-isolate` artifacts; nothing is published (as for
  `scheme.jar`, docs/embedding.md "The jars").
  - *What the policies ask* (25.5's `SandboxPolicy` Javadoc, the
    [Sandboxing](https://www.graalvm.org/latest/security-guide/sandboxing/)
    guide). `ISOLATED`: everything `CONSTRAINED` asks, the isolate,
    `engine.MaxIsolateMemory` (the isolate's heap, a hard limit),
    `sandbox.MaxCPUTime`, and host access with method scoping
    (`HostAccess.ISOLATED`). `UNTRUSTED` adds a redirected `in`,
    `sandbox.MaxHeapMemory` (what the context retains, a soft limit),
    `sandbox.MaxASTDepth`, `sandbox.MaxThreads`,
    `sandbox.MaxOutputStreamSize` and `sandbox.MaxErrorStreamSize`, and
    `engine.UntrustedCodeMitigation=software`, which it presets: the host
    creates the isolate with constant blinding, Spectre barriers (masked
    memory accesses on x86-64) and randomly offset compiled code
    (`PolyglotIsolateHostSupport.createIsolateArguments`). Each policy also
    needs the language, and every option an embedder sets, declared for it
    (`Registration.sandbox`, `Option.sandbox`).
  - *All of it is in GraalVM CE 25.5, as pinned.* sdk/CHANGELOG.md, 25.1.3:
    *"`SandboxPolicy.ISOLATED` and `SandboxPolicy.UNTRUSTED` are now
    supported in GraalVM Community Edition."* and *"Isolated `Engine` and
    `Context` are now available in GraalVM Community Edition"*. The host
    side is in `truffle-api` (`com.oracle.truffle.polyglot.isolate`, which
    `requires static org.graalvm.nativebridge`, a jar we already pin and
    that `scheme` already requires); the guest side is native-image's
    `com.oracle.svm.truffle.PolyglotIsolateGuestFeature`, in CE's
    `lib/truffle/builder/truffle-runtime-svm.jar` (the `truffle-svm` macro,
    which `truffle-runtime`'s `native-image.properties` enables); the
    mitigations are the open compiler's (`ConstantBlindingPhase`,
    `SpectrePHTBarriers`, `AMD64MemoryMaskingAndFencing`). So M11-11 needs
    no new Maven artifact, no version change and no change to the flake.
    Oracle GraalVM only, and not taken: `-H:+ProtectionKeys`, which mx adds
    to an isolate library only for Oracle GraalVM (hardware memory
    protection, `engine.MemoryProtection` and
    `UntrustedCodeMitigation=hardware`, Linux only), Oracle's compiler
    optimizations, and Oracle's `-isolate` artifacts (of its own languages).
    `--//:native_graalvm=oracle` could build ours with Oracle's
    native-image, as it does //:scheme-native; nothing needs it.
  - *Measured, with a prototype* (this Mac, the language declared
    `UNTRUSTED`, the library given by the experimental
    `engine.IsolateLibrary`, a host with Truffle's jars but not
    `scheme.jar`): the library is 47 MB and took 37 s and 3.1 GB to build.
    An `ISOLATED` context (256 MB, 5 s) ran `(+ 1 2)`, a recursion 100,000
    deep (spilled, design.md "Continuations"), a compiled 10-million
    iteration loop in 20 ms, `call/cc` out of a `dynamic-wind`, and output
    to the host's stream; an endless loop was cancelled at 5,006 ms. The
    first context took 573 ms to build (the isolate, the context and the
    prelude restored from its image, D13), later ones 1 to 2 ms. Under
    `UNTRUSTED` (`MaxThreads=1`, `MaxHeapMemory=64MB`), the same recursion
    and a 50-million iteration loop ran (132 and 134 ms), a list growing without
    end was cancelled at the heap limit, and `MaxASTDepth=10` didn't stop
    40 nested additions until the change below.
  - *How it is built: as mx builds a language's isolate*
    (`truffle/mx.truffle/mx_truffle.py`, `PolyglotIsolateProject` and
    `register_polyglot_isolate_distributions`, at the 25.5 tag), in Bazel
    rules beside //:scheme-native's (`native_library`, D17):
    1. `libpolyglotisolate.{so,dylib}`: native-image `--shared` of the
       `scheme` module, with `org.graalvm.truffle.runtime` and
       `org.graalvm.nativebridge` added, and mx's options:
       `--features=com.oracle.svm.truffle.PolyglotIsolateGuestFeature`,
       `-H:APIFunctionPrefix=truffle_isolate_`, `-H:+CopyLanguageResources`
       and `-H:-InitializeVM` (the host initializes the isolate). Runtime
       options stay parsed: the feature refuses `-H:-ParseRuntimeOptions`,
       since the host passes `MaxIsolateMemory` and the mitigations as
       isolate arguments. The module path is a list of jars: given as a
       directory, native-image reads no jar's `native-image.properties`, so
       the `truffle-svm` macro, and with it the feature, is missing
       (measured: *"User-enabled Feature ... class not found"*).
    2. A jar, the module `scheme.isolate`, that holds the library as an
       optional internal resource of the engine, as mx's
       `polyglot_isolate_resource.template` does: a class implementing
       `InternalResource` with `@InternalResource.Id(value =
       "scheme-isolate-<os>-<arch>", componentId = "engine", optional =
       true)` and `@PolyglotIsolateLanguages(value = "scheme", os = ...,
       cpuArchitecture = ...)`, whose provider the Truffle DSL processor
       generates, and the library under
       `META-INF/resources/engine/scheme-isolate-<os>-<arch>/libvm/` with the
       `sha256` and `files` lists `InternalResource.Env` reads. Truffle finds
       it as an `InternalResourceProvider` service
       (`PolyglotIsolateHostSupport.computeAvailablePolyglotIsolateResources`),
       unpacks it to its resource cache on first use (the user's cache
       directory, or `polyglot.engine.userResourceCache`), and spawns an
       isolate of it for a context that permits `scheme` under `ISOLATED`
       or `UNTRUSTED`, or with `spawnIsolate(true)` under any policy. It is
       built for the target platform only, as an `-isolate-<platform>`
       artifact holds one platform's library.
    3. `bazel-bin/scheme_isolate_jars/`, the host's module path:
       `polyglot`, `truffle-api`, `nativebridge` and the SDK jars they use,
       `truffle-runtime` and `truffle-compiler` (the host's own runtime; the
       guest's is in the library), and that jar, but not `scheme.jar`: the
       language runs only in the isolate. docs/embedding.md shows the
       command.
    - *Rejected:* `engine.IsolateLibrary=<path>` for embedders: it is
      experimental (it needs `allowExperimentalOptions`, and Truffle's own
      message says *"for testing purposes only"*); the tests may use it. A
      language home with `lib/libschemevm` in it (`HomeFinder`'s other
      lookup): a GraalVM home's layout, which we don't ship, and
      `libschemevm` is already the native launcher's library (D17).
      Publishing `scheme-isolate-community` artifacts: publishing is
      deferred, as for `scheme.jar`.
    - *Built only when named*, like //:scheme-native: the library is a
      native-image build of the whole language, redone for every change to
      it (a minute and a few GB on the large executor), and AGENTS.md keeps
      native images out of CI. So the tests that run contexts in the isolate
      are a target of their own, tagged `manual`, run before a PR that
      touches the sandbox or the native-image configuration; CI compiles
      them with the other tests' sources (the lint tests).
    - *Not provided:* `engine.IsolateMode=external` (experimental), which
      needs GraalVM's isolate launcher executable: mx copies it into each
      `-isolate` jar from `NATIVEBRIDGE_LAUNCHER_RESOURCES`, which isn't
      published (`"maven": False` in sdk's suite.py), so we would build it
      from source; an embedder can still name one with
      `engine.IsolateLauncher`. An embedder whose own application is a
      native image (Truffle then reads internal resources at its build time)
      is untested.
  - *The language declares `UNTRUSTED`*, and so do the options an embedder
    sets (`scheme.LibraryPath`, `scheme.BufferOutput`, `scheme.StackSize`,
    `scheme.PreludeImage`): the stricter policies take away nothing the
    language uses. It reaches the host only through the Env (its streams,
    which the host redirects, and its file system, which the embedder
    gives), reaches no host object, and enters the context on one thread
    only; the stack probe (`StackBudget`) is a thread that never enters it,
    and `MaxThreads=1` let a recursion that spills run (measured above).
  - *The limits.* Truffle's sandbox instrument enforces every limit, in the
    isolate as on the host; ours are its inputs: statements (D13's tags),
    stack frames (`PendingFramesNode`, above), and AST depth, below.
    `sandbox.MaxHeapMemory` counts what the context retains, which includes
    the frames a deep recursion has spilled to the heap: a recursion a
    million deep exceeded 64 MB. The tests check each limit `UNTRUSTED`
    requires, under it.
  - *`sandbox.MaxASTDepth` under `UNTRUSTED`.* The sandbox measures a root's
    depth by walking its child nodes, and a Bytecode DSL root holds only
    the tags some instrument asked for, so the limit isn't enforced
    (docs/upstream.md, "MaxASTDepth on Bytecode DSL roots"); `UNTRUSTED`
    requires it set. So when the context's policy is `UNTRUSTED`
    (`Env.getSandboxPolicy()`), the compiler builds its roots with
    `BytecodeConfig.COMPLETE` instead of `WITH_SOURCE`: every tag the
    language provides is materialized as the root is built, the walk sees
    the expression tags nested as the code is, and the limit is enforced
    (measured: 40 nested additions under `MaxASTDepth=10` were refused,
    *"AST depth limit of 10 nodes exceeded. AST depth: 43."*, and 6 ran).
    The depth is per root: a lambda's body is a root of its own. The
    prelude, restored from its image (D13), keeps its configuration: it is
    the language's code, not the guest's. Under the other policies, where
    the limit is optional, it stays unenforced: the language can't tell
    whether it is set (an instrument's options aren't the language's), and
    materializing every tag would cost every sandboxed context. The cost
    under `UNTRUSTED` is measured in M11-11. When `UpstreamAstDepthTest`
    fails after a Truffle upgrade, the bug is fixed and this goes.
- **Out of scope:** Scheme-side access to other languages and the host,
  threads, and SRFIs. Avoid reflection and dynamic class loading: the native
  launcher (D17) is built without any reflection configuration.
- **Deferred from v1, and since done:** escape-only `call/cc` (M5-04),
  Fixing Letrec (M5-05, D2), vector storage strategies (M5-06, D1), and the
  Static Object Model for records (M7-04, D1).

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
  (`toolchains_buildbuddy`). Which machine runs an action is its execution
  platform's choice ("Platforms and C and C++ toolchains", below): a C or C++
  action for a macOS target runs on this Mac. The actions that set their
  own requirement are the AOT cache's training for a non-Linux target and
  the copy of that target's JDK it is trained with (`no-remote`, D17 "The
  AOT cache"), since the cache names the paths it was made with and the
  modification times it found there.
  - *One output directory for both platforms.* Bazel names an output
    directory after `--cpu`, which `--platforms` doesn't change, so a
    build or test for the executors' Linux writes to this Mac's
    `bazel-out/darwin_arm64-fastbuild` too, at the same paths: the Linux
    `bazel/jdk/` or `scheme-native` replaces this Mac's, and with
    `--remote_download_toplevel` (a test's inputs stay remote) leaves
    nothing there. So after `bazel test`, `bazel-bin/scheme` (whose JDK is
    gone) and `bazel-bin/scheme-native` are back with `bazel build
    //:scheme` and `bazel build //:scheme-native`, which take seconds from
    the caches (docs/usage.md). The `bazel-bin` link isn't the cause: it
    names that directory whichever platform the command was for.
  `bazel test //...` is the whole pre-PR check; the repository's other
  commands are `bazel run //:r7rs -- <command>` (`tests/harness/main.py`).
- **Released GraalVM, pinned by Bazel.** The JDK that compiles, runs the
  tools and runs the tests is GraalVM CE 25.5, downloaded at a pinned sha256
  (`bazel/graalvm.bzl`; the archives nixpkgs ships), and its Graal compiler
  is the JIT. Truffle comes from Maven Central (`rules_jvm_external`, locked
  in `maven_install.json`) at **exactly** that version (25.5.5.0.3): one
  `TRUFFLE_VERSION` in `MODULE.bazel` picks both, and `//tests:versions`
  checks the JDK's `release` file against the lock file.
- **Upgrading** is made only by a task that says so (AGENTS.md "Never"):
  add the release to `bazel/graalvm.bzl` (GraalVM CE's archives, Oracle
  GraalVM's for `--//:native_graalvm=oracle`, and the release's source tag
  and its `launcher.cc`'s sha256 for the native launcher, D17), bump
  `TRUFFLE_VERSION`, `bazel run @jvm//:pin`, and bump nixpkgs in
  `flake.lock` (the dev shell's JDK) together. The repository rule fails,
  naming what to add, when any of them is missing for the version.
- **Modules.** `java_module_jar` (`bazel/java_module.bzl`) builds the
  `scheme`, `scheme.launcher` and `scheme.test` modules, and everything runs
  with Truffle on the module path, as embedders run it.
  - *They export nothing to embedders (M11-08).* GraalVM's module migration
    guide (step 9): *"If the language or instrument does not expose any
    API, it is recommended to keep the module as encapsulated as possible
    and avoid exporting any packages"*, and *"In the case of internal APIs
    that are exclusively utilized by known modules, it is advised to use
    qualified exports"*, as the SimpleLanguage's `module-info.java` exports
    its packages `to org.graalvm.sl.test`. Every package of `scheme` is
    exported only `to scheme.test` (the unit tests' modules), and
    `scheme.launcher` only `to org.graalvm.launcher, scheme.test`: the native
    launcher's `AbstractLanguageLauncher.runLauncher` makes `SchemeMain` by
    reflection, and without the export `bazel-bin/scheme-native` stops with
    *"IllegalAccessException: class
    org.graalvm.launcher.AbstractLanguageLauncher (in module
    org.graalvm.launcher) cannot access class scheme.launcher.SchemeMain"*
    (GraalJS's launcher exports `com.oracle.truffle.js.shell to
    org.graalvm.launcher` the same way). Nothing else needs one: the
    language is found as a `TruffleLanguageProvider` service, the JVM
    launcher runs `-m scheme.launcher/...`, and the native image's
    configuration (build-time initialization, D17) has no reflection.
  - *But one class.* The Static Object Model (records, M7-04) defines the
    classes it generates, subclasses of `SRecord` implementing
    `SRecord.Factory`, with `ClassLoader.defineClass` in class loaders of
    its own (`GeneratorClassLoaders`), so they are in those loaders'
    unnamed modules, which no qualified export can name: with
    `scheme.runtime.value` exported only `to scheme.test`, the first
    `define-record-type` failed with *"superclass access check failed: class
    com.oracle.truffle.api.staticobject.GeneratedStaticObject$$1 (in unnamed
    module @...) cannot access class scheme.runtime.value.SRecord (in module
    scheme) because module scheme does not export scheme.runtime.value to
    unnamed module"*. Espresso, the model's main user, exports its static
    objects' package to everyone for the same reason (`suite.py`:
    `"com.oracle.truffle.espresso.runtime.staticobject",  # Workaround
    GR-48132`). So `SRecord` (with its `Factory`) is alone in a package,
    `scheme.runtime.staticobject`, the one exported to everyone: an embedder
    can name that class and nothing else, and can't get a record as one
    (polyglot values are never host objects). Rejected: exporting
    `scheme.runtime.value` to everyone (two dozen value classes as an API);
    `opens` (open packages are accessible at run time anyway, and more);
    exporting to the generated classes' modules with `Module.addExports` at
    run time (the model's class loaders are internal to Truffle, and a class
    must be defined in one before its module can be named).
  - `//tests:module_exports` (a lint test) reads the built jars'
    descriptors and fails on an open module, an unqualified `opens` or an
    unqualified `exports` but `scheme.runtime.staticobject`, and on any
    class in that package but `SRecord`'s.
- **Nix** (`flake.nix`) provides the dev shell: `bazel` (bazelisk), Python,
  `gh`, chibi-scheme and a GraalVM JDK for running and embedding outside
  Bazel. Bazel doesn't need it: it pins its own JDK and Python, and builds
  the differential tests' chibi-scheme from the pinned source of the same
  release (`bazel/chibi.bzl`, with `make` and the C compiler of the
  platform the target's C toolchain runs on), so
  every action can run on another machine. `nix develop .#bench`
  adds the other R7RS implementations for the comparative benchmark report;
  CI's steps use `nix develop .#ci`, which has only `bazel`.
- **Other versions** (the `bazel_dep`s, `.bazelversion`, the Maven
  artifacts other than GraalVM's, chibi-scheme's source, rules_python's
  Python) move to a new release only once it is at least two weeks old, a
  margin against a broken or compromised release (a release already in use
  isn't stepped back from), and updating them is an ordinary change;
  `bazel mod graph` prints no "root module requires" warning.
  Bazel stays on 8 until moving to 9 is decided on its own. The lint tools
  stay at the versions GraalVM's mx uses (Checkstyle 10.21.0, SpotBugs
  4.9.8, ECJ 3.46.0) and the formatter at Eclipse 4.36's, since a newer one
  reformats or re-flags code with nothing to gain; JUnit is 4.13.2, JUnit
  4's last release.
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

- **Platforms and C and C++ toolchains (decided in M10-01).** C and C++ are
  built with Bazel's own rules and toolchains, and which machine runs an
  action is a property of the execution platform it lands on, not of tags on
  rules. (The maintainer's choice: *"let's do Bazel's defaults, and have
  platforms defined so if I use Bazel and select macOS/darwin the build
  happens on my local laptop. Eventually we'll get remote Macs."*)
  - *The platforms* are defined in the repository (`bazel/platforms`), both
    in `--extra_execution_platforms` under `--config=remote`, Linux first:
    `linux_x86_64`, BuildBuddy's executors, with the constraints and
    `exec_properties` of `toolchains_buildbuddy`'s Ubuntu 22.04 platform
    (which it replaces: actions run in the same image; a platform of another
    name, and the new options, change the configurations' output paths, so
    the first build after the change missed the cache); and
    `macos_arm64`, this Mac, whose `exec_properties` are
    `{"no-remote-exec": "1"}`. Bazel adds a platform's `exec_properties`
    with a true-ish value to its actions' execution requirements (katre, in
    bazelbuild/bazel#22363: *"an exec property with a 'true-ish' value (like
    '1' or 'TRUE' or 'yes') has the key added to the exec info and
    requirements"*), so the Mac's actions run here while
    `--remote_executor` is set, and still read and write the remote cache.
    An action runs on the first platform that has the toolchains it needs:
    Java, Python, genrules and the Linux tests on the executors, and a C or
    C++ action for a macOS target, which needs the Xcode toolchain, on the
    Mac. Checked (`--subcommands`): for a macOS target, `launcher.cc`'s
    `CppCompile` and `CppLink` and the `NativeImage` action ran on
    `//bazel/platforms:macos_arm64`, locally, while `Javac` ran remotely on
    `linux_x86_64`; with `--platforms=//bazel/platforms:linux_x86_64` all
    three ran remotely.
  - *The C++ toolchains.* A Linux target uses `toolchains_buildbuddy`'s GCC
    toolchain on the executors (`--extra_toolchains`, as before); under
    `--config=local` on a Linux host, rules_cc's autodetected one. A macOS
    target uses `apple_support`'s Xcode toolchain (a `bazel_dep` above
    `rules_cc`, so it is registered first), with the Xcode pinned by an
    `xcode_config` (`--xcode_version_config`), as BuildBuddy's
    linux-to-mac-rbe-demo does, so a build doesn't depend on what a machine
    detects and remote Macs can share its cache. On a Mac, rules_cc's
    autodetection is off (`BAZEL_DO_NOT_DETECT_CPP_TOOLCHAIN=1`, in the
    `macos` config that `--enable_platform_specific_config` applies there):
    in `nix develop` it found Nix's clang wrapper.
  - *Rules that run the target's C compiler themselves* (native-image, for
    the native launcher's library; chibi-scheme's `make`) declare the C++
    toolchain type, so they land on the platform that has the target's
    toolchain, as the C++ rules do; their `no-remote-exec` tags go.
  - *Tests for a macOS target* run on it: `--platforms=//bazel/platforms:macos_arm64`
    with `--use_target_platform_for_tests`, so the test action's execution
    platform is the target platform, the Mac. Without it a test runs on the
    first execution platform, the executors. (bazelbuild/bazel#22777
    reports test actions reaching remote executors in spite of a
    platform's `no-remote-exec`; with `--use_target_platform_for_tests`,
    the prototype's test ran on the Mac.)
  - *Remote Macs later* are a change to `macos_arm64` alone: its
    `exec_properties` become the executors' (`"OSFamily": "Darwin"`,
    `"Arch": "arm64"`, and a `"Pool"` if they are in one) instead of
    `"no-remote-exec"`, and the `xcode_config` names their Xcode. Bazel 10
    stops forwarding `no-`, `supports-` and `disable-` keys to remote
    executors (bazelbuild/bazel#32010); until then the key is part of the
    Mac's actions' cache keys, which is harmless while only one machine
    runs them.
  - *Rejected:* compiling with the host's `c++` in a custom action (#211's
    first version): it uses no Bazel toolchain, so it can't move to remote
    Macs without its own logic, and its compiler is whatever is on the
    action's PATH. `no-remote-exec` tags chosen per rule by the target's OS
    (what the native image and chibi-scheme did): every rule
    has to know, and remote Macs need each changed. `--strategy=CppCompile=local`
    and other per-mnemonic strategies: they apply to Linux targets too, whose
    compiles must run on the executors. `cc_binary` with only the
    autodetected toolchain: for a macOS target under `--config=remote` its
    compile went to a Linux executor, with Nix's clang wrapper (`clang: not
    found`).
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
  root of an isolated program (D15), of a library (D7), or of code that
  `eval` or `load` compiles into an immutable environment (R7RS §6.12:
  `environment`, `scheme-report-environment`, `null-environment`) records
  that top-level scope and namespace (`SchemeRootNode.getTopLevel`), and
  code evaluated in its frames sees exactly its imports and definitions,
  exported or not; elsewhere, the interaction environment. The prelude
  image saves a standard library's top level with each of its roots (a
  value record of the library's scope and namespace, by index), so a
  restored root has it too. Evaluated code may not `set!` an isolated
  program's own variables: the program was compiled as a closed unit,
  assuming only its own code assigns them (`EscapeAnalysis`). Nor an
  environment's, which are immutable (and all imports). A library isn't
  closed (a macro it exports may assign its variables from another unit),
  so code evaluated in its frames may. A procedure's frames are named by its
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
    `JAVA_TOOL_OPTIONS`), read from `RuntimeMXBean.getInputArguments`, keeps
    evaluation on the launcher's own thread, which has that stack, as before:
    the spec and space tests run the launcher with `-Xss256k`, so that
    their deep recursions still spill (rule 4). `--stack-size=<size>` (or
    the native launcher's `--vm.Xss<size>` or `--vm.XX:StackSize=<size>`,
    which `SchemeMain` reads: the native launcher's isolate, a shared
    library's, reports no input arguments) sets the evaluation thread's
    stack, and `--stack-size=default` evaluates on the launcher's thread
    (the JVM's default stack; the native launcher's is the thread its C part
    created the isolate on: the main thread on Linux, and on macOS a thread
    of its own with the main thread's stack size, typically 8 MB;
    `launcher.cc`'s `setstacksize`). If the system won't reserve the stack (a limit on
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
  counts, and so do its `--vm.` options, "VM options" below). The native
  launcher uses it by default too: it is native-image's
  default (CE has no other but Epsilon), and a build flag chooses another
  ("The native image's collector", below). An embedder chooses its own JVM's.
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
    - *Since the AOT cache (#188), start-up no longer decides it* for
      `bazel-bin/scheme`: a cache holds the JDK's classes too, and one
      trained with the flag maps with it. A hello-world program (the `java`
      command `bazel-bin/scheme` runs, 21 interleaved runs, medians, an
      Apple M3 Max at a load of 5-7, `main` at `74d5b76`): 128 ms with the
      serial collector and the build's cache; 129 ms with compact headers
      and a cache trained the same way with them (`-XX:AOTCacheOutput`, on
      `bazel/aot-training.scm`); 341 ms with compact headers and no cache.
      What is left of the rejection is the steady state, measured at M8-05
      without the AOT cache and with 12 host compiler threads: 5% faster
      overall with the serial collector, slower on `nboyer` and `earley`.
      Not re-measured since. Until the launcher asked the JVM ("The AOT
      cache", "Which JVM options it takes"),
      `--vm.XX:+UseCompactObjectHeaders` on `bazel-bin/scheme` was given the
      build's cache all the same, which the JVM then refused (*"The AOT
      cache's UseCompactObjectHeaders setting (disabled) does not equal the
      current UseCompactObjectHeaders setting (enabled)"*, with `-Xlog:aot`),
      silently: the program started with no class sharing (346 ms in the
      same runs). It now starts without the cache, and no faster: GraalVM CE
      25.5's JDK has no class-sharing archive for compact headers either
      (`lib/server/` has only `classes.jsa`), so with them the JVM shares no
      classes whatever the launcher passes.
- **The launcher's JVM: memory and start-up.** Besides the collector, the
  JVM launcher starts its JVM with 4 host compiler threads and, for
  `bazel-bin/scheme`, an AOT cache (`tests/harness/harness.py`:
  `launcher_vm_args`, `Runtime.launcher`). Each is left out when the JVM
  options (the command's, `JDK_JAVA_OPTIONS`, `JAVA_TOOL_OPTIONS`) set it
  themselves, as for the collector; `//tests:launcher_vm` checks each.
  - *Where the memory went.* A hello-world program on an Apple M3 Max with
    128 GB (16 cores) peaked at about 340 MB of RSS (`ru_maxrss`, which counts
    the shared libraries' pages too) and 327 MB of physical footprint
    (`vmmap --summary`, at its end). Of that, the Java heap was about 65 MB:
    the serial collector's young generation is a third of the initial heap, a
    64th of the machine's memory (682 MB here), so the program runs without a
    collection, and the heap's pages it touches are the ones it allocates,
    65 MB as it starts (with `--engine.Compilation=false` too). That isn't the
    heap's *used* figure, which said 250-260 MB at exit (`-Xlog:gc*`): most of
    that is thread-local allocation buffers handed out and never filled. A
    thread's first buffer is about a 50th of the young generation's eden,
    11 MB here, and every thread that allocates anything takes one, compiler
    threads included. A buffer's pages are touched only as objects fill it, so
    the heap's resident pages (`vmmap -w`, in the heap's address range from
    `-Xlog:gc*`, with compilation off and the program waiting at its end)
    were 62 MB, about what the used figure is with `-XX:-UseTLAB` (64.6 MB). Of the 65 MB (JFR's allocation events, with small fixed
    buffers: `-XX:-ResizeTLAB -XX:TLABSize=16k`), the JDK loading 2,100
    classes from the jars took about half (`ModuleReader.read` reads each
    class into a 16 KB buffer, then copies it), linking lambdas and other
    `invokedynamic` call sites 13 MB (8 MB of it the 310 lambdas of the 283
    primitives' `PrimitiveSpec`s, which the first context linked, in about
    30 ms, until the primitives' registry had none: below), and the engine, the context and the prelude image's restore most
    of the rest. The AOT cache (below) brings those classes and lambdas
    linked: with it the program allocates 8 MB as it starts (the used figure
    with `-XX:-UseTLAB`; 10 MB of the heap is resident, the cache's archived
    objects included), though the used figure says 131 MB. JFR can't measure
    that case: a recording started with the JVM (`-XX:StartFlightRecording`)
    makes it refuse the cache. The JVM's own memory
    (`-XX:NativeMemoryTracking`) was 70 MB: code cache 16, metaspace 16, the
    JDK's CDS archive 14, arenas 8. The Graal compiler's (libgraal, which
    compiles the host's Java as well as Truffle's) is outside it: each JVMCI
    compiler thread has its own libgraal isolate
    (`JVMCIThreadsPerNativeLibraryRuntime=1`), with a 16 MB eden and the free
    chunks it keeps, and the JVM had started 7 of its 12 host compiler threads
    as JVMCI threads before the program ended: with a 32 MB young generation
    (`-Xmn32m`) the heap was 35 MB and the footprint still 290 MB. Thread
    stacks were 2 MB (the evaluation thread's 256 MB is address space). So a
    smaller young generation (rejected below) wouldn't make starting smaller:
    with both levers below, a hello-world program's footprint is 107 MB, 10 MB
    of it the heap (21 interleaved runs, the medians). In a benchmark (`fib`,
    `-Xmx2g`) the young generation (630 MB touched) is most of the 760 MB
    footprint.
  - *Compiler threads.* `-XX:CICompilerCount=4` where the JVM would start
    more (16 processors or more; it starts 3 with 4 to 7, and 4 with 8 to
    15): fewer libgraal isolates, and fewer host compilations competing with
    the program and the Truffle compiler for the cores as it starts.
    On 24 of ecraven's programs at the report's counts (0.1 times their
    own, `-Xmx2g`, 3 iterations in a fresh JVM; 2-3 interleaved rounds, the
    medians; an Apple M3 Max shared with other agents), against the JVM's
    12: the first iteration 0.96 times (geometric mean), the steady state
    1.00, the whole process 0.93, and peak RSS 0.92 (the median 1050 MB
    against 1108). Two programs moved in every round, with 2 and 3 threads
    too: `mperm`'s steady state 0.60 times, `paraffins`' 1.30. With 2
    threads: 1.03, 1.01, 0.97 and 0.90; with 3: 1.00, 1.02, 0.95 and 0.89.
    A hello-world program took 321 ms and 233 MB against 398 ms and 342 MB
    (31 interleaved runs, the medians).
  - **The primitives' registry.** An area's primitives are the constants of
    an enum in its builtins file (`NumberPrimitives.Primitive`, ...): each
    has the primitive's name, its arity and what its calls give
    (`PrimitiveSpec.Result`), and is the factory of its node, which a
    `switch` on the constant makes. A primitive's `PrimitiveRootNode` makes
    its node on the primitive's first call, not when the context makes the
    root. Each name is written once, and `PrimitiveRegistryTest` checks every
    primitive against the registry as it was before (`primitives.txt`).
    - *Why.* The areas' lists had a lambda or a constructor reference per
      primitive as its factory, 310 for 283 primitives, and the first context
      linked every one, a hidden class each, and then made every node. Without
      the AOT cache, `Builtins.primitives()`'s first call in a fresh JVM took
      56-62 ms and allocated 12.2 MB; it takes 5 ms and 1.3 MB. A hello-world
      program (`bazel-bin/scheme`'s `java` command, 21 interleaved runs, the
      medians, load 4-5) took 325 ms against 352 (the fastest runs 306
      against 338), allocated 54 MB against 65 (`-XX:-UseTLAB`), and peaked
      at 230 MB against 244. With the AOT cache, which had them linked, it
      is the same: 141 ms against 143, 8.6 MB allocated against 8.8. The
      native launcher pre-initializes its context: 9.7 ms either way (31
      runs), its executable 80 KB smaller.
    - *A `return` in each case.* The `switch` is a statement in a method
      typed `Object`, not a switch expression: javac joins a switch
      expression's cases as their common class `PrimitiveNode`, and the
      JVM's verifier then loads every node class (151) to check that each
      case's is one, as it verifies the enum. That took 14 ms and 4.1 MB of
      `primitives()`'s first call. Returned as `Object`, a node class is
      loaded only when a node of it is made. The cost: javac doesn't check
      that the cases cover the constants, so the test makes every node.
    - *Rejected:* a `switch` on the name beside the list (each name twice);
      method handles made on a primitive's first call (reflection, which the
      native image restricts: D10); a table generated at build time (the
      Truffle DSL processor makes none, and a processor of our own is more
      machinery than an enum).
  - **The AOT cache.** JDK 25's AOT cache (JEPs 483, 514 and 515, the successor
    of AppCDS): the classes a training run loaded, parsed, verified and
    linked, the module graph, and their profiles, mapped from a file as the
    JVM starts. `scheme_runtime` (`bazel/java_module.bzl`) trains it with a
    small program (`bazel/aot-training.scm`) as a build action, for
    `bazel-bin/scheme`'s module path, and writes beside it a stamp: the
    size, modification time and real path of the JDK's modules image and
    of each jar. The JVM checks those itself and compares the run's module
    path with the real paths it was trained with, so the action passes it
    real paths and the launcher passes them too.
    A hello-world program (31 interleaved runs of the `java` command
    `bazel-bin/scheme` runs, the medians, load 5-15) took 175 ms and 273 MB
    against 398 ms and 342 MB without it, and 133 ms and 169 MB with the
    4 compiler threads too. On the 24 programs above (2 rounds, a cache
    trained the same way by hand): the first iteration 0.95 times, the
    steady state 1.00, the whole process 0.85, RSS 0.99. The cache is
    34 MB and its action takes about 2 s.
    - *Where it is valid.* The cache names the files' real paths, so it is
      valid only where it was made: the action runs here for this machine's
      `bazel-bin/scheme` and neither reads nor writes the remote cache
      (`no-remote`); for a Linux target (the tests, CI) it runs on the
      executors, where the launcher finds it stale. A cache the JVM can't
      use leaves it with no class sharing at all, the JDK's own archive
      included, which starts slower than no cache (a hello-world program
      880 ms against 400), so the launcher compares the stamp with the
      files first (17 `stat`s) and leaves the cache out when they differ
      (Bazel downloaded a jar again, say), as well as when the JVM options
      choose class sharing themselves (`-Xshare`, `-XX:SharedArchiveFile`,
      `-XX:AOTCache`, ...). Bazel's actions are keyed on contents, not
      modification times, so Bazel can make an input again, the same
      bytes at a new time, while the cache's action stays up to date: it
      did with `bazel/jdk/` (#239, M11-02), which came from the remote
      cache at the time of each download, after a Linux build had
      replaced it (D11, "One output directory for both platforms") or a
      switch between `--config=local` and the remote config. So for a
      non-Linux target the JDK is copied here too (`no-remote`), keeping
      the release's times however often it is copied. The JVM itself
      checks the modules image's size, not its time (a cache with a stale
      stamp for it alone was taken, with `-XX:AOTMode=on`); the jars'
      times it checks, but their actions are the same for either platform,
      so a Linux build leaves them as they are (it did).
    - *Which JVM options it takes.* The JVM checks a cache against far
      more than class sharing (JDK 25's `FileMapHeader::validate`,
      `FileMapInfo::validate_aot_class_linking`,
      `AOTClassLocationConfig::validate`, `Modules::ArchivedProperty`,
      `CDSConfig::has_unsupported_runtime_module_options`): the object
      layout (`-XX:+UseCompactObjectHeaders`, `-XX:ObjectAlignmentInBytes`,
      `-XX:-CompactStrings`), compressed pointers (`-XX:-UseCompressedOops`,
      and so a heap over 32 GB: `-Xmx40g`, or `-XX:MaxRAMPercentage=50` on
      a 128 GB machine), the collector (ZGC uses none),
      verification (`-Xverify:all`), the profiles' settings when it has
      profiles (`-XX:TypeProfileLevel`, which `-Xint` and
      `-XX:-UseJVMCICompiler` change), the module options (`--add-opens`,
      `--add-exports`, `--add-modules`, so `-XX:StartFlightRecording`'s
      `jdk.jfr` too; `--limit-modules`, `--patch-module`), a longer boot
      class path, `java.system.class.loader`, `java.security.manager`, a
      JDWP agent and any agent with a class-file hook. Some depend on
      values and on the machine, and each JDK adds some, so no list the
      launcher kept would be right. So it asks the JVM: it passes the cache
      with `-XX:AOTMode=on` (JEP 483: the JVM must use the cache, or it
      stops as it starts with *"Unable to use AOT cache."* and the reason),
      and given JVM options of the user's (the command's, `--vm.`,
      `JDK_JAVA_OPTIONS`, `JAVA_TOOL_OPTIONS`) or of its own beyond the
      cache's (#219's `-XX:-UseJVMCINativeLibrary`), it first runs the same
      `java` command with `--dry-run` (the JVM starts and loads the main
      class, and runs nothing), its output dropped, and leaves the cache out
      if that fails. Before, with `-XX:AOTMode=auto` (the default) and its
      `aot` and `cds` logging off, the JVM refused the cache silently and
      started with no class sharing at all (`MetaspaceShared::
      initialize_runtime_shared_and_meta_spaces` turns `UseSharedSpaces`
      off; it doesn't fall back to the JDK's archive). Options that the
      JVM doesn't check the cache against need no asking: system properties
      other than the JDK's, `-Xss` and `-Xlog:`; nor do the serial, parallel
      and G1 collectors, which `//tests:launcher_vm` checks it takes. The
      `--dry-run` adds about 45 ms where the JVM takes the cache and 25 where
      it refuses it. A hello-world program (the launcher's choice and its
      `java` command, the old harness against the new, 19-23 interleaved
      rounds, the medians, an Apple M3 Max at a load of 5.5-10, rounds above
      10 left out): with no options 132 ms either way, with `-Dx=y` or G1
      133-134; with `-Xmx1g` 178 against 132 (the check); with compact
      headers 360 against 336 and with `-XX:-UseCompressedOops` 358 against
      339 (the check: the JDK has no archive for either, so neither shares
      any class); with `--add-opens` in `JDK_JAVA_OPTIONS` 319 against 341
      (the JDK's archive, which the refused cache had turned off); with no
      cache (`-Xshare:auto`) 289. Leaving the cache out prints nothing: the
      user's option takes effect, and what gives way is the launcher's own
      default, as the serial collector does to `--vm.XX:+UseG1GC`; a note
      would be on the program's stderr on every run (docs/usage.md says
      it). With `-XX:AOTMode=on` and the logging on, a cache the JVM refuses
      after all (one of those collectors, say) is an error that says why.
      *Rejected:* a list of the options the cache records (above);
      `-XX:AOTMode=auto` with the JVM's warning (the run starts with no
      class sharing, where the JDK's archive is often still usable); and
      retrying when the JVM refuses it: the launcher `exec`s the JVM, whose
      stderr is the program's.
    - *Why not on the tests' runtime.* `//:launcher_runtime`, which the
      tests and `bazel run //:r7rs` (the benchmarks) run, has none: every
      test would wait for the action, and none could use it on the
      executors. So the benchmarks' and `bench-check`'s start-up don't
      include it.
    - *Rejected:* a dynamic archive (`-XX:ArchiveClassesAtExit`, JDK 13's
      AppCDS for an application's classes on top of the JDK's archive):
      a hello-world program took about 300 ms against the AOT cache's 190
      (single runs; 15 interleaved on a loaded machine: 384 against 193
      and 716 without), since it shares classes but neither links them nor
      keeps the module graph. Creating the cache as the launcher first
      runs (`-XX:AOTCacheOutput`, or `-XX:+AutoCreateSharedArchive`): the
      first run pays 1.5-4 s, and the launcher would write into the user's
      files.
  - *Rejected.* A smaller young generation (`-XX:NewSize=64m` or `128m`,
    `-Xms64m`, `256m`, `512m`), on 13 allocation-heavy programs (`-Xmx2g`,
    2 rounds): peak RSS 0.70-0.75 times (0.63 on all 24 with `-Xms64m`), but
    the steady state 1.07-1.09 times: `paraffins` 3.3-4.1 times (its
    radicals, hundreds of MB that live for an iteration, are copied and
    promoted instead of dying in a young generation that holds them),
    `earley` 1.2, `gcbench` 1.1. The serial collector sizes its young
    generation only at a full collection, so a small one stays small.
    A default maximum heap: the JVM's (a quarter of the memory) only
    bounds the heap, and what a program touches is the young generation;
    a lower one would fail programs that keep a lot alive (`earley`, 1.1
    GB). `-XX:TieredStopAtLevel=1` (no host compilation by libgraal):
    a hello-world program starts in 300 ms and 167 MB, but the host's
    Java runs C1's code: the steady state 1.45 times (`equal` 8.6, `browse`
    5.5, `fib` 2.6). One libgraal isolate for every compiler thread
    (`-XX:JVMCIThreadsPerNativeLibraryRuntime=0`): RSS 0.70 times, but the
    first iteration 1.41 times and the steady state 1.10 (`compiler` 2.2,
    `parsing` 3.2 first): the compilations wait for each other. A smaller
    libgraal eden (`-Djdk.graal.internal.ExpectedEdenSize=4m`, `8m`): no
    change in RSS, the first iteration up to 6% slower.
    `-XX:ReservedCodeCacheSize`: the code cache's size is reserved address
    space; what it uses (16 MB for a hello-world program) is what it
    compiled.
- **VM options.** Both launchers take the host VM's options as
  `--vm.<option>` among their options (before the program file; after it,
  they are the program's arguments), as GraalVM's language launchers do,
  and with GraalVM's mechanisms. The framework (`AbstractLanguageLauncher`)
  applies none of them in a JVM, which has already started: in GraalVM, the
  launcher that starts the JVM does (its launcher scripts, and the native
  thin launchers' C part, which starts the JVM with them and restarts it if
  the Java side finds it guessed them wrong). In a native image, the thin
  launcher's C part has created the isolate with them, and it sets none; in
  a native executable without one, it sets them in the process
  (`RuntimeOptions`).
  - *The JVM launcher.* `bazel-bin/scheme` is that script
    (`tests/harness/scheme.py`): `Runtime.launcher` takes each `--vm.` option
    before the program file out (`split_vm_options`, which parses as
    `SchemeMain.preprocessArguments` does) and starts the JVM with it as
    `-<option>` (`--vm.cp=` and `--vm.classpath=` as `-cp`, `--vm.@<file>`
    as `@<file>`: "Options", argument files), as GraalVM's
    launcher scripts do (the framework expects them to have taken the
    options out: `Launcher.parsePolyglotOption`). They are JVM options like the command's, so
    the launcher's own (the collector, the compiler threads, the AOT cache)
    give way to them the same way. One that reaches `SchemeMain` in a JVM
    wasn't applied (the launcher started with `java`), and is an error that
    names the JVM option to give `java` instead; before, the framework ignored
    it, so `--vm.XX:+UseG1GC` and even `--vm.Xmx2m` ran as without them.
  - *The native launcher (M10-01).* `bazel-bin/scheme-native` is GraalVM's
    thin launcher, whose C part creates the isolate of the library beside it
    with the `--vm.` options as its VM options, the way GraalVM's language
    standalones start (the decision and how it works: below). So every
    option, the heap's sizes and the collection policy among them, takes
    effect as the isolate starts, and every argument after the program file
    is the program's. `SchemeMain` hands each `--vm.` option back to the
    framework, which checks it found the ones the C part took and sets none
    itself; a `--vm.` argument after the file makes the C part run the
    process again without it. `--vm.Xss` is also the evaluation thread's
    stack ("The evaluation thread"). A class path (`--vm.cp=`) is an error
    from the launcher; a collector (`--vm.XX:+UseG1GC`) is one from the
    isolate's creation, as any option it doesn't know ("The native image's
    collector": it is chosen when the image is built). The executable takes
    no VM option in `java`'s form: `-Xmx2g` before the file is an error that
    names `--vm.Xmx2g`.
  - *Before M10-01 (#203, #206).* The executable was the image itself, and
    two things went wrong with it:
    - *Startup-only options.* The framework's `RuntimeOptions.set` can't set
      the heap's sizes once the process has started (`--vm.Xmx512m` failed
      as "does not specify a valid number", `--vm.XX:MinHeapSize=1g` as
      *immutable*: `RuntimeOptionKey.validateValueBeforeUpdate`, *"is
      immutable and can only be set during startup"*, for the
      `RegisterForIsolateArgumentParser` options), and nothing tells which
      options can. So #203 had `SchemeMain` run the executable again
      (`ProcessProperties.exec`) with its `--vm.X...` and `--vm.XX:...`
      options as its own `-X...` and `-XX:...` options, which native-image
      took as the process started: a second start, about 5 ms, whenever
      such an option was given.
    - *A program's arguments in the VM's forms.* SubstrateVM reads `-X...`,
      `-XX:...` and `-D...` from the whole command line as it creates an
      executable's isolate, before `main` runs (25.5's
      `IsolateArgumentParser.parse`, then
      `RuntimeOptionParser.parseAndConsumeJavaMainOptions`), and `main` gets
      what is left. So `scheme-native prog.scm a -Xmx3g -Dx=y b` ran
      `prog.scm` with `(command-line)` = `("prog.scm" "a" "b")`, a 3 GB
      maximum heap and the property `x`; one it didn't know (`-XX:+Foo`)
      or couldn't read (`-Xmxfoo`) stopped it before the launcher ran
      (`Error: Could not find option 'Foo'`, status 1), and `-Xss1k` made
      it overflow its stack. The executable's `-H:-ParseRuntimeOptions`
      (*"Parse and consume standard options and system properties from the
      command line arguments when the VM is created. Default: + (enabled)"*,
      `native-image --expert-options-all`), which GraalVM builds its own
      launcher executables with (`sdk/mx.sdk/mx_sdk_vm_impl.py`: `if
      isinstance(image_config, mx_sdk.LauncherConfig): build_args +=
      ['-H:-ParseRuntimeOptions']`), gave the program all six, but turned
      off every source of startup options: #203's restart then failed
      (`--vm.Xmx64m` → `Unrecognized argument: '-Xmx64m'`), and SubstrateVM
      reads options from nothing else at startup (no environment variable).
      The executable would have lost `-Xmx` (the comparative report's
      `-Xmx2g`), `-Xms1g` and the collection policies, worth more to its
      users than arguments of those forms are to programs (#206). GraalVM's
      standalones have both through a thin launcher, which M10-01 took.
  - **Decision (M10-01): the native launcher is GraalVM's thin launcher in
    front of the image as a shared library**, as GraalVM's language
    standalones ship (the maintainer's choice of #206's follow-up (b)). It
    replaces the restart above (#203). Sources are 25.5's, at the tag
    `graal-25.5.5.0.3` that the GraalVM CE release names.
    - *The executable* (`bazel-bin/scheme-native`) is
      `sdk/src/org.graalvm.launcher.native/src/launcher.cc`, unmodified,
      fetched at that tag with a pinned sha256 (`@graalvm_launcher_cc`,
      `bazel/graalvm.bzl`, beside the GraalVM it belongs to) and compiled
      as a `cc_binary` (`//:scheme-native`), with the defines
      `NativeLibraryLauncherProject.cflags` gives it
      (`sdk/mx.sdk/mx_sdk_vm_impl.py`): `LAUNCHER_CLASS`
      (`scheme.launcher.SchemeMain`), `LAUNCHER_MAIN_MODULE`,
      `LIBLANG_RELPATH` (the library, beside it), `GRAALVM_VERSION`,
      `DIR_SEP`, `CP_SEP`, and `LIBJVM_RELPATH` and, on macOS, `LIBJLI_RELPATH`,
      which only its JVM mode uses (there is no JVM here: it is the mode it
      falls back to when the library is missing, so they name the library
      too, and its error names the missing file); with `-ObjC++` and
      Foundation on macOS, and the C++ library linked statically on Linux,
      as GraalVM links it (`-static-libstdc++`; GraalVM's own build uses
      LLVM's libc++). Without `LAUNCHER_MODULE_PATH`, `LANGUAGES_DIR` and the
      other JVM-mode defines.
    - *What it does* (`launcher.cc`, `main` and `jvm_main_thread`): it
      takes every `--vm.<option>` on the command line as the VM option
      `-<option>` (`--vm.cp=` and the other path options it keeps for JVM
      mode), loads the library from `LIBLANG_RELPATH` relative to its own
      real path, and creates the isolate with `JNI_CreateJavaVM` and those
      options, on a thread of its own (on macOS always, where the first
      thread runs the Cocoa event loop; elsewhere when `--vm.Xss` exceeds
      the main thread's stack). Then it calls the framework's JNI entry
      point, `AbstractLanguageLauncher.runLauncher(byte[][], byte[][], int,
      long, boolean)` (`launcher-common`, whose `jni-config.json` registers
      it and `RelaunchException.vmArgs`), with the arguments. The framework
      creates the launcher from the build-time property
      `org.graalvm.launcher.class`, and after `preprocessArguments` checks
      (`validateVmArguments`) that the `--vm.` arguments the launcher
      returned are the ones the C part took. If not (a `--vm.` argument
      after the program file is the program's), it throws
      `RelaunchException` with the right ones, and the C part puts them in
      `GRAALVM_LANGUAGE_LAUNCHER_VMARGS` and `_<n>` and `execve`s itself,
      which then takes those instead of the command line's. In the
      process, the framework sets none of them (`maybeExec` with the thin
      launcher): the isolate was created with them.
    - *The library* (`libschemevm`, `lib<language>vm` as GraalVM names it) is
      built as `mx_sdk_vm_impl.py` builds a `LanguageLibraryConfig` with
      launchers (`NativeImageProperties.contents`): `--shared`,
      `-Dorg.graalvm.launcher.class=<the launcher>`, `-H:-InitializeVM` (the
      framework's `maybeExec` runs the startup hooks, `VMRuntime.initialize`,
      once the options are processed), and `-R:+EnableSignalHandling`,
      `-R:+InstallSegfaultHandler` and `-H:+InstallExitHandlers`, which an
      executable has by default and a shared library doesn't. The language
      macro (`--macro:truffle-language-library-community`, which adds polyglot
      isolates' guest feature) and the monitoring features aren't taken:
      neither is in what the executable has today. `//:scheme-native`'s
      options stay (the pre-initialized context, native access for JLine,
      `-march=compatibility`, `--//:native_gc`, `--//:native_graalvm`).
    - *Without `-H:-ParseRuntimeOptions`*, unlike GraalVM's executables: mx
      adds it for a `LauncherConfig` only, and adds `-H:-InitializeVM` to a
      `LanguageLibraryConfig` instead (`mx_sdk_vm_impl.py`, the
      `is_sdk_launcher` branch). The switch gates every source of runtime
      options, `JNI_CreateJavaVM`'s included (`IsolateArgumentParser.shouldParseArguments`
      → `GuestStagingDependencyBridgeImpl.shouldParseRuntimeOptions`):
      a library built with it ignored the thin launcher's options
      (`--vm.Xmx64m` let a program keep 200 MB alive, and `--vm.XX:+VerboseGC`
      printed nothing), while built without it, `--vm.Xmx64m` ended that
      program with *"Garbage-collected heap size exceeded"*, and `--vm.Xms1g
      --vm.XX:InitialCollectionPolicy=BySpaceAndTime` gave *"GC policy: by
      space and time"*, *"Minimum heap size: 1024M"*. It isn't needed
      either: an isolate that `JNI_CreateJavaVM` creates parses only the
      `JavaVMOption`s it is given (`JNIInvocationInterface.JNICreateJavaVMPrologue`,
      with no program name), never the process's command line. So
      `scheme-native prog.scm a -Xmx3g -XX:+Foo -Dx=y b` gives the program
      all six arguments (`//tests:launcher_vm_native`).
    - *The executable's own options are `--vm.` ones only*, as for GraalVM's
      launchers: a `-X...`, `-XX:...` or `-D...` before the program file is
      an error that names the `--vm.` form (`-Xmx2g`: use `--vm.Xmx2g`).
      `bench.py`'s native options and docs/usage.md's commands take that form.
    - *Costs.* A second file: the executable finds the library beside its
      real path, so the two go together. A `--vm.` argument after the
      program file costs a second start (the relaunch). A collector option
      (`--vm.XX:+UseG1GC`) reaches `JNI_CreateJavaVM`, which rejects it as
      any option it doesn't know (*"Could not find option 'UseG1GC'"*, then
      the launcher's *"JNI_CreateJavaVM() failed."*, status 255) before the
      launcher's Java code runs, so the launcher's own message for it (#203)
      can't be shown without changing `launcher.cc`; docs/usage.md says the
      collector is chosen when the executable is built. `--vm.cp=` still
      reaches the launcher, and its error stays.
    - *Building it.* The executable is a `cc_binary` on Bazel's C++
      toolchains, and the library a `native_library` (`bazel/java_module.bzl`)
      that declares the C++ toolchain type, so both build on the execution
      platform of the target's toolchain: GCC on the executors for a Linux
      target, Xcode's clang on this Mac for a macOS one (D11, "Platforms and
      C and C++ toolchains"). The launcher's flags follow GraalVM's per
      platform (`select()`); on Linux, `-include memory`, since `launcher.cc`
      uses `std::unique_ptr` without including `<memory>`, which LLVM's
      libc++ includes for it and GCC's libstdc++ doesn't. The JDK's JNI
      headers are a `cc_library` (`@graalvm//:jni`). The executable and the
      library are both in the package's output directory, so
      `bazel-bin/scheme-native` finds `bazel-bin/libschemevm.*` beside its
      real path, and the library is in its runfiles for the tests.
    - *Measured.* The executable before (`main` after #210) and after, on an
      Apple M3 Max shared with other agents (load 9 to 12), wall time and
      peak RSS. Startup, medians of 21 interleaved runs (two rounds for the
      first two rows):

      | | Before | After |
      |---|---:|---:|
      | `scheme --version` | 9.6, 9.8 ms; 15.0 MB | 10.1, 9.9 ms; 15.6 MB |
      | A hello-world program | 10.1, 11.1 ms; 20.3 MB | 10.4, 10.8 ms; 20.8 MB |
      | The REPL, at end of input | 9.9 ms; 19.3 MB | 10.2 ms; 19.8 MB |
      | `--vm.Xmx512m` and a hello-world program | 15.5 ms (#203's restart) | 10.6 ms |
      | A hello-world program with a `--vm.` argument (a relaunch) | | 17.8 ms |

      ecraven's programs as the comparative report's native column runs them
      (`bench-report --implementations native`, `--vm.Xmx2g`, 3 iterations
      in one process; medians of interleaved rounds, 2 for the first three,
      5 for `divrec` and `primes`, where one round of the 5 ran 1.6 and 3
      times slower with more collections, and the others overlap): the
      first iteration, the steady state, peak RSS:

      | | Before | After |
      |---|---:|---:|
      | `fib` | 1.82 s, 1.81 s; 72 to 106 MB | 1.82 s, 1.82 s; 72 to 76 MB |
      | `nboyer` | 6.3 s, 4.8 s; 1.05 GB | 6.0 s, 4.7 s; 1.03 GB |
      | `earley` | 9.2 s, 9.6 s; 1.55 GB | 9.2 s, 9.8 s; 1.53 GB |
      | `divrec` | 3.35 s, 3.29 s; 164 MB | 3.47 s, 3.35 s; 163 MB |
      | `primes` | 0.82 s, 0.76 s; 187 MB | 0.81 s, 0.76 s; 185 MB |
      | `tests/space/deep-recursion-1m.scm` (7 runs) | 629 ms; 387 MB | 637 ms; 380 MB |
      | `tests/space/deep-apply-1m.scm` (7 runs) | 967 ms; 486 MB | 978 ms; 453 MB |

      The executable was 55.34 MB; the thin launcher is 0.10 MB and the
      library 55.36 MB.
    - *Rejected.* Building the executable with `-H:-ParseRuntimeOptions`
      alone (#206's (a)): it loses the startup-only options (above). Launcher
      logic of our own (#203's restart, or parsing in C): GraalVM's mechanism
      does the job, and is what its standalones are tested with. Compiling
      the launcher with the host's `c++` in an action of its own, with
      `no-remote-exec` for a macOS target (the first version of #211): D11's
      "Platforms and C and C++ toolchains" has why.
  - `//tests:launcher_vm` checks the JVM launcher's, and the local
    `//tests:launcher_vm_native` the native launcher's (the heap's sizes
    and the collection policy from `-XX:+VerboseGC`, a maximum that a
    program exceeds, also after a relaunch, a program's arguments in the
    VM's forms and as `--vm.` ones, the errors).
- **Options (M10-04).** Every option either takes effect or stops the
  launcher with an error that says why: the maintainer's rule, no option is
  ignored. Each class follows GraalVM's launcher conventions (Truffle's
  [Options.md, "Default Language Launcher Options"](https://github.com/oracle/graal/blob/master/truffle/docs/Options.md#default-language-launcher-options);
  [Polyglot Programming](https://www.graalvm.org/latest/reference-manual/polyglot-programming/):
  *"The format is: --<languageID>.<property>=<value>"*), and the framework
  (`launcher-common` 25.5's `Launcher.parsePolyglotOption`) does the
  checking: an option it doesn't know is *"Unrecognized argument"*, status 2
  (`abortUnrecognizedArgument`), a bad value or an experimental option without
  `--experimental-options` status 1 (`abort`). The launcher's own errors about
  those options take the same statuses (they were 64 for `--vm.` ones, #203);
  only its own options (`-I`, `--stack-size`, `--end-status`) keep EX_USAGE.
  docs/usage.md, "Options", has the table, per launcher; what #205, #211 and
  the audit found ignored, and what the launcher does now:
  - *A VM-wide engine option* (`--engine.TraceTransferToInterpreter`): on
    HotSpot the process's first engine fixes it (`HotSpotTruffleRuntime.StaticOptions`;
    a later engine's value gets *"it was previously initialized with
    'false'. The option has therefore no effect"*). That first engine is the
    framework's own, which it creates without the command line's options to
    look their descriptors up (`LanguageLauncherBase.getTempEngine`,
    `useSystemProperties(false)`), so the option never took effect. The
    runtime also reads the system property as that engine is created, so
    `SchemeMain.preprocessArguments`, which runs before it, sets
    `polyglot.engine.TraceTransferToInterpreter=true` (what the warning
    recommends), and clears it once the framework's engine exists, so that
    the warm-up's engine, which allows no experimental options, still
    starts. `engine.Profiling` is the process's too, the last engine's: the
    warm-up's would set it back, so the launcher doesn't warm up with it.
    `engine.TraceDeoptimizeFrame` (#213) is the engine's own (`EngineData`):
    it prints when something reads a compiled frame with
    `FrameInstance#getFrame(READ_WRITE|MATERIALIZE)`
    (`OptimizedFrameInstance.getFrameFrom`), which the runtime never does (it
    walks no Truffle frames), so it has nothing to print.
  - *The compiler's instrumentation* (`--compiler.InstrumentBoundaries`,
    `--compiler.InstrumentBranches`): with libgraal, GraalVM 25.5's compiler
    fails every compilation it instruments, silently (failures aren't printed
    by default), so the profile printed at exit was empty; the native
    image's compiler stops the process (*"Fatal error: guarantee failed"*).
    Graal compiled for HotSpot (`-XX:-UseJVMCINativeLibrary`) instruments
    them, so `bazel-bin/scheme` runs its JVM with that when one is on
    (`tests/harness/harness.py`, `launcher_vm_args`); with libgraal, and in
    the native launcher, the launcher rejects them (docs/upstream.md).
  - *Options the launcher sets*: `scheme.StackSize` (the evaluation thread's,
    "The evaluation thread") replaced the user's; it is an error that names
    `--stack-size`. `scheme.LibraryPath` as a system property was replaced by
    the `-I` directories; it now comes before them, as the option does.
  - *System properties* (`polyglot.<name>`, `--vm.D...`): Truffle reads
    them as the context is built, and throws `IllegalArgumentException` for
    one it rejects, which the framework printed as a stack trace (status
    255); the launcher reports it as an error, status 1.
  - *The native launcher's VM options*: the isolate's creation applies
    `-Xmx`, `-Xms`, `-Xmn`, `-Xss`, `-D` and `-XX:` options, but takes any
    other and ignores it (`-ea`, `-Xint`, `-verbose:gc`, `-Xlog:gc`, `-Xfoo`:
    SubstrateVM's `RuntimeOptionParser` has a list of HotSpot options it
    ignores for compatibility, and `JNI_CreateJavaVM` passes on the rest), and
    the thin launcher keeps `--vm.p=`, `--vm.-module-path=` and
    `--vm.Djava.library.path=` for its JVM mode, which this executable
    doesn't have. `SchemeMain` sees every `--vm.` option and rejects those,
    status 1. An `-XX:` option the image doesn't know, a collector's
    included, still stops `JNI_CreateJavaVM` with SubstrateVM's *"Could not
    find option"* (status 255) before any Java code runs: the thin
    launcher's C part asks the isolate to ignore unknown options only with
    `--jvm` (`vmInitArgs.ignoreUnrecognized`), and has no other hook, so a
    clearer message would need a change to `launcher.cc`, which this project
    takes unmodified. The message names the option, and docs/usage.md says
    the collector is chosen at build time.
  - *Argument files (M11-03)*: `--vm.@<file>` is GraalVM's launchers' form of
    `java`'s argument file, `@<file>`, a file of VM options in `java`'s form
    (`-Xmx1g`), separated by white space, with `#` comments, quotes and, in
    quotes, escapes (the JDK's `libjli/args.c`). The options in one take
    effect or fail as the same options given as `--vm.` ones do, on both
    launchers (`//tests:launcher_vm` and `//tests:launcher_vm_native`,
    `argument_files`).
    - *The JVM launcher* passes it to `java` as `@<file>`, as GraalVM's
      launcher scripts do (`sdk/mx.sdk/vm/launcher_template.sh`,
      `process_vm_arg`), and `java` reads it and applies or rejects its
      options as its own (*"Unrecognized option: -Xfoo"*, or *"could not
      open"* for a file there is none of; status 1). It used to pass
      `-@<file>`, which `java` rejected whatever the file held. The
      launcher's defaults (the collector, the compiler threads, Graal
      compiled for HotSpot, the AOT cache) give way to the file's options
      as to the command's, so `bazel-bin/scheme` reads the file too, for
      that alone (`harness.arg_file_options`), and an argument file in
      `JDK_JAVA_OPTIONS`, which `java` also reads: without that, a file's
      `-XX:+UseG1GC` would meet the launcher's `-XX:+UseSerialGC`, and the
      JVM refuses to start with two (*"Multiple garbage collectors
      selected"*). The launcher started with
      `java` names `@<file>` as the option to give `java`.
    - *The native launcher.* The thin launcher's C part reads the file
      (`launcher.cc`, `expand_vm_arg_file`, with `arg_file_next_token`, its
      port of `libjli`'s reader) and creates the isolate with its options,
      so the isolate applies the heap's, the stack's, `-D` and `-XX:` ones,
      and rejects an `-XX:` one it doesn't know, as it does `--vm.` ones. It
      passes the others on unchecked, and the framework gives the launcher
      only `--vm.@<file>`, so `-ea`, `-Xint` or `-verbose:gc` in a file went
      ignored, and an `-Xss` there set the isolate's first thread's stack
      but not the evaluation thread's (#219's follow-up). `SchemeMain` now
      reads the file as the C part does (`ArgumentFile`: `arg_file_next_token`
      state for state, quirks included: a `#` inside an option comments out
      the rest of its line, and the option goes on on the next), and checks
      each option as it checks its `--vm.` form, with the same errors and
      status (1); an `-Xss` or `-XX:StackSize=` there is the evaluation
      thread's stack too. The class path, module path and
      `-Djava.library.path=` that the C part takes out of a file for its JVM
      mode, and drops in this one, are errors, and so is a word that isn't
      an option (`Xmx1g`, or a nested `@<file>`, which the C part doesn't
      expand), which the isolate would ignore. A file the C part can't open
      is its error (*"could not open"*, status 1), before Java runs.
    - *Rejected: the isolate's strict option parsing*
      (`-H:+StrictRuntimeJavaOptions`, an expert native-image option, off by
      default: *"When enabled, supported Java VM options are parsed,
      recognized but unsupported options are rejected"*, 25.5's
      `SubstrateOptions`). It makes the isolate's creation reject
      `-Xint`, `-verbose:gc`, `-Xlog:gc`, an agent and an option it doesn't
      know, wherever they come from. But it takes `-ea` as an option it
      applies, to classes initialized at run time only (`AssertionsSupport`;
      the language's are initialized when the image is built), and
      `-verbose:class` and others likewise, and it can't give the launcher
      an `-Xss`; so the launcher would still read the file, and the
      command line's errors would become the isolate's (status 255, the
      option in `java`'s form) instead of the launcher's. *Rejected:
      reading the isolate's arguments* from
      `jdk.internal.misc.VM.getRuntimeArguments`, which SubstrateVM
      substitutes with the options the isolate was created with: an
      internal API (an `--add-exports` for javac and native-image), and the
      paths the C part drops from the file never reach it.
  - *The tools*: the native launcher is built without them; their options
    were *"Unrecognized argument"*, and now say the JVM launcher has them
    (`abortUnrecognizedArgument`, the framework's hook).
  - *Help topics*: the framework prints the general help for a
    `--help:<topic>` it has none for; the launcher makes that the error
    *"Unrecognized help topic"*, status 2.
  - Checked by `//tests:launcher_vm`, `LauncherTest.processOptionsTakeEffectOrFailClearly`
    and `//tests:launcher_vm_native` (here and on the executors): an option of
    each class, its effect or its error and status.
- The Chrome inspector, DAP and profiler tools are optional modules
  (`//bazel:tools_modules`), which `bazel-bin/scheme` puts on the module
  path, and CI runs a smoke test of each against a Scheme program
  (`//tests:tools`; D13's tags and source sections are what they consume).
- **Native image (M6-06).** `bazel build //:scheme-native` builds `scheme` as
  a native executable with the pinned GraalVM CE's `native-image` and the
  target's C toolchain (`native_library` in `bazel/java_module.bzl`: on the
  executors for a Linux target, here for this machine), from the launcher's
  module path without the tools; since M10-01, as GraalVM's thin launcher
  and the image as a shared library beside it ("VM options"). Truffle's
  runtime on that path makes native-image add Truffle's runtime
  compilation, so the executable
  has the Graal JIT. The language's jar (`scheme.jar`) carries its own configuration
  (`src/runtime/META-INF/native-image/org.r7rs/scheme`), so any embedder's
  image picks it up: the prelude's resources, and build-time initialization
  of the language's packages, as GraalVM's own languages have (the language
  provider and the DSL-generated library exports live in the image heap).
  docs/graalvm-configuration.md audits this configuration, the language's
  registration and options, and its embedding against GraalVM's guidance.
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
      writes none, and the patch flushes standard output's buffer first, so
      nothing is left buffered), and take the run's `scheme.BufferOutput`
      (D10);
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
  - **Runtime-compiled methods (M11-09).** native-image compiles every
    method partial evaluation can reach a second time, for the runtime
    compiler, and GraalVM's language jars bound how many: each jar's
    `native-image.properties` adds `-H:MaxRuntimeCompileMethods=<n>`
    (Truffle's runtime 2700, the SimpleLanguage 250, TRegex 900, Sulong
    10000, the tools 100 to 300), native-image adds up the values of every
    jar on the module path (25.5's
    `RuntimeCompilationFeature.checkMaxRuntimeCompiledMethods`), and a
    build with `-H:+EnforceMaxRuntimeCompileMethods` fails after its
    analysis when the count is above the sum. The check is off by default
    (*"Enforce checking of maximum number of methods allowed for runtime
    compilation. Useful for checking in the gate that the number of methods
    does not go up without a good reason."*), so an embedder's image only
    adds our share to its bound. The language's jar declares a share of
    300, so `//:scheme-native`'s bound is 3,000, and `native_library`
    (`bazel/java_module.bzl`) enforces the bound in every image it builds.
    - *The count* is native-image's own: its build output's *"2,703
      runtime compiled methods"*, and the list
      `-H:+PrintRuntimeCompileMethods` prints, at `79b93dc` and again at
      `093bb36`: 2,703 on
      Linux x86-64 and 2,694 on macOS arm64 (Linux's 9 more are 7 of
      TruffleString's `TStringOps` string scans, `Math.rint` and
      `Math.copySign`). 732 of them are the language's classes (`scheme.*`,
      288 in `scheme.runtime.value`), the other 1,971 Truffle's (1,875),
      the JDK's and Substrate's.
    - *The margin* is 297 methods over Linux's count (11%), 306 over
      macOS's. Growth by new specializations and primitives, a few methods
      at a time, fits in it; a `@TruffleBoundary` missing in front of a
      Java collection, `BigInteger` arithmetic or string formatting pulls
      in hundreds of methods at once, and fails the build. The share is
      300 rather than the 732 methods of our own classes because the
      shares add up: Truffle's 2,700 is already more than the 1,971 other
      methods, so a share of 732 plus a margin would let the image grow by
      more than 1,000 methods unnoticed. A build that passes the bound
      prints the deepest call path that partial evaluation reaches and
      *"Number of methods for runtime compilation exceeds the allowed
      limit: <count> > 3000"*; raising the share is then the PR's
      decision, with `-H:+PrintRuntimeCompileMethods`'s list as its
      evidence.
    - *A build past the bound fails*: `//tests:compile_bound_native` builds
      the same library with `-H:MaxRuntimeCompileMethods=-1000` added (a
      bound of 2,000: native-image adds negative values too) and passes
      when native-image refuses it with that message, which also shows
      that the language's 300 is in the sum. It stops after the analysis:
      67 s on the executors, 22 s on this Mac (*"native-image refused 2703
      runtime-compiled methods over a bound of 2000"*, and 2694 here).
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
    executable was 55 MB (since M10-01, the library is, and the thin
    launcher 0.1 MB: "VM options"). The JIT compiles in the native launcher as on the
    JVM (`--engine.TraceCompilation` shows the tier-1 compilations), and
    the first `fib` iteration (17.8 s against the JVM's 13.6 s) is the
    only one that is slower.
  - `bazel run //:r7rs -- test --native FILE` runs each conformance section as
    a process of the native launcher, as `ConformanceRunner` runs it, and
    passes what the JVM does (the status block: chibi's suite 1225/1225 and
    Larceny's 2160/2166, its 6 listed exceptions failing), interpreted and
    with `--compile`. The test
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
    target and its tests (`//tests:readme_native`,
    `//tests:conformance_native`, `//tests:space_native`,
    `//tests:launcher_vm_native`, `//tests:compile_bound_native`) are
    `manual`; a PR that changes the native-image configuration runs them.
  - On x86-64 Linux the image targets the baseline CPU
    (`-march=compatibility`): with the default target (AVX2) the image
    uses the JDK's `libsimdsort`, whose libm dependency nixpkgs'
    native-image resolved to glibc's static `libm.a` when this was decided
    (M6-06, built in the Nix shell), and linking that into a dynamically
    linked executable failed. The image is now linked by the executors' GCC
    (`toolchains_buildbuddy`, D11), where the default target hasn't been
    tried. The baseline target is also what nixpkgs' own native-image check
    builds with, and makes the executable run on any x86-64 machine.
  - **The native image's collector.** It is a build-time choice,
    native-image's own `--gc=`, which the flag `--//:native_gc` sets
    (`serial`, the default, `epsilon` or `G1`; `native_setting` in
    `bazel/java_module.bzl`). `--//:native_graalvm=oracle` builds the
    executable with Oracle GraalVM's native-image instead of GraalVM CE's,
    at the pinned version (`bazel/graalvm.bzl` downloads it then, and only
    then; D11's one version holds for both), and G1 needs it: with GraalVM
    CE the build fails at analysis, naming the flag to add. Options that
    only the serial collector takes go in the target's `serial_options`,
    which are passed with `--gc=serial` only (there are none today). The
    collector's run-time options are the executable's `--vm.` options, which
    the thin launcher creates the isolate with ("VM options"): G1's
    `--vm.XX:MaxGCPauseMillis=` and `--vm.Xmx512m` run a G1 executable as
    they should. No collector of our
    own, and no change to what the runtime allocates.
    - *What GraalVM says, and what 25.5 does.* The documentation
      ([Memory Management](https://www.graalvm.org/latest/reference-manual/native-image/optimizations-and-performance/MemoryManagement/),
      "latest", whose version selector lists 25.5 first): *"The Serial GC is
      the default GC in GraalVM Native Image"*; *"Oracle GraalVM also
      provides the Garbage-First (G1) garbage collector, which is based on
      the G1 GC from the Java HotSpot VM"*, *"(Not available in GraalVM
      Community Edition.)"*, *"Currently, G1 Garbage Collector can be used
      with Native Image on the Linux AMD64 and AArch64 architectures"*, and
      *"To enable it, pass the option `--gc=G1` to the `native-image`
      builder"*; *"To enable the Epsilon GC, specify the option
      `--gc=epsilon` at image build time"*. [Native Image Build
      Options](https://www.graalvm.org/latest/reference-manual/native-image/overview/Options/):
      `--gc`: *"select native-image garbage collector implementation.
      Allowed values: 'epsilon', 'serial', 'G1'"*, and *"At run time, the
      default prefix is `-XX:`"*. Checked against 25.5.5.0.3 itself: CE's
      `native-image --help` lists only `'epsilon'` and `'serial'` for
      `--gc=`, and `--gc=G1` fails with *"Error: Invalid option '--gc'.
      'G1' is not an accepted value. Accepted values are 'epsilon',
      'serial'."*; Oracle GraalVM's lists `'G1'` too. Its builder accepts G1
      on more than the documentation says (*"The G1 garbage collector
      ('--gc=G1') is currently only supported on Linux/amd64,
      Linux/aarch64, macOS/aarch64, and Windows/amd64."*, `G1Feature` in
      25.5's `svm.src.zip`), and builds and runs `//:scheme-native` with G1
      on macOS AArch64; on Linux x86-64 (the executors) the native tests
      (`conformance_native`, `readme_native`, `space_native`) pass with it.
      G1 needs no isolated compilation (it turns `SupportCompileInIsolates`
      off itself), and neither of our options conflicts with it.
    - *Oracle GraalVM's terms.* Its archives (the downloads page's
      "script-friendly URLs", no license to accept on download) carry the
      GraalVM Free Terms and Conditions (their `LICENSE.txt`, and
      https://www.oracle.com/downloads/licenses/graal-free-license.html),
      which grant the right to *"internally use the unmodified Programs"*
      and to *"redistribute the unmodified Program ... provided that You do
      not charge Your licensees any fees"*; the [downloads
      page](https://www.graalvm.org/downloads/): *"Oracle GraalVM is free
      to use in production and free to redistribute, at no cost, under the
      GraalVM Free Terms and Conditions."* Oracle publishes no checksum
      beside these archives, so the pinned sha256s are of the files as
      downloaded.
    - *Epsilon* never collects (its use is *"very short running
      applications that only allocate a small amount of memory"*, on the
      Memory Management page): a hello-world program or `fib` run as with the
      serial collector, `primes` peaks at about 1 GB, and with `-Xmx512m`
      it ends with an `OutOfMemoryError`.
    - *The serial collector's policies.* SubstrateVM 25.5's serial
      collector sizes its generations with a policy, chosen at run time by
      `-XX:InitialCollectionPolicy=` (the executable's
      `--vm.XX:InitialCollectionPolicy=`; `-XX:PrintFlags=`: *"The garbage
      collection policy. Default: 'Adaptive2'. Former default: 'Adaptive'
      (deprecated). Serial GC only."*; the documentation names only the
      pre-22 `BySpaceAndTime`). `Adaptive2`, ported from HotSpot's parallel
      collector, starts eden at 48 MB, grows it at most 10% a collection,
      tenures after 7 young collections at first and never fewer, and
      aims at a GC time ratio of 1, *"i.e., 50%"* of the time in collection
      (`GC_TIME_RATIO`, `AdaptiveCollectionPolicy2.java`). With a large live
      set that is where it stays: `earley` (1.2 GB live) and `nboyer` (350
      MB) spent 60 to 77% of their time collecting, `paraffins` and `mperm`
      85 to 90%. The native layout isn't the difference: a pair is 16 bytes
      natively (`-XX:+PrintGCSummary`, a loop that conses a million: 16.0
      bytes each, nothing else), against the JVM's 24.
    - *Measured.* The executable at this commit, serial with its default
      policy, with each other policy (`-XX:InitialCollectionPolicy=`), and
      built with G1 (Oracle GraalVM); no `-Xmx`, as a user runs it; ecraven's
      programs at the report's counts, 3 iterations in one process (as
      `bench-report` runs them), and the deep recursions of "Rejected: a 1
      GB minimum heap" (`count` 300,000 deep ×12, a list built and mapped
      1,000,000 deep ×6). Medians of 3 interleaved processes, an Apple M3
      Max shared with other agents, load 3 to 5: the process's wall time, the
      first iteration, peak RSS:

      | | Serial, `Adaptive2` (default) | `Proportionate` | `BySpaceAndTime` | G1 |
      |---|---:|---:|---:|---:|
      | `earley` | 22.7 s; 8.6 s; 3.7 GB | 16.7 s; 7.2 s; 1.4 GB | 15.6 s; 5.9 s; 1.4 GB | 9.8 s; 4.0 s; 10.8 GB |
      | `nboyer` | 13.7 s; 5.7 s; 1.7 GB | 16.8 s; 6.4 s; 0.7 GB | 15.1 s; 5.2 s; 0.8 GB | 6.5 s; 2.2 s; 5.3 GB |
      | `sboyer` | 10.2 s; 3.3 s; 384 MB | 8.0 s; 2.7 s; 339 MB | 6.8 s; 2.4 s; 701 MB | 6.9 s; 2.3 s; 3.6 GB |
      | `paraffins` | 4.4 s; 1.9 s; 1.3 GB | 3.9 s; 1.7 s; 1.0 GB | 1.9 s; 0.38 s; 1.0 GB | 1.2 s; 0.45 s; 1.6 GB |
      | `mperm` | 6.4 s; 1.9 s; 1.3 GB | 5.1 s; 2.1 s; 1.1 GB | 3.5 s; 0.94 s; 1.2 GB | 3.8 s; 1.1 s; 2.4 GB |
      | `graphs` | 9.4 s; 3.2 s; 308 MB | 9.4 s; 3.2 s; 304 MB | 8.7 s; 3.0 s; 532 MB | 9.1 s; 3.1 s; 6.3 GB |
      | `count` 300,000 deep | 0.68 s; 127 ms; 377 MB | 1.10 s; 140 ms; 355 MB | 0.29 s; 81 ms; 492 MB | 0.54 s; 171 ms; 806 MB |
      | the list, 1,000,000 deep | 4.3 s; 1.7 s; 1.0 GB | 5.7 s; 2.0 s; 0.9 GB | 2.4 s; 0.98 s; 0.7 GB | 3.2 s; 1.3 s; 2.2 GB |
      | `divrec` | 1.04 s; 164 MB | 1.03 s; 148 MB | 1.01 s; 371 MB | 1.02 s; 558 MB |
      | `primes` | 0.27 s; 169 MB | 0.27 s; 130 MB | 0.28 s; 351 MB | 0.25 s; 317 MB |
      | `fib` | 1.09 s; 71 MB | 1.06 s; 71 MB | 1.08 s; 71 MB | 1.08 s; 81 MB |

      (`divrec`, `primes` and `fib`: wall time and RSS; their iterations
      are alike.) With a fixed maximum heap, `-Xmx2g` as the comparative
      report runs it (2 interleaved processes), G1 keeps its speed in
      bounded memory: `earley` 9.8 s and 2.2 GB against the default's 26.8
      s and 1.6 GB (its steady state 2.9 s against 8.6), `nboyer` 6.3 s
      and 1.2 GB against 14.4 s and 1.0 GB, `paraffins` 1.6 s against 4.5,
      `graphs` 9.8 s against 9.0 (1.6 GB against 311 MB), `divrec` 367 MB
      against 166. A hello-world program (41 interleaved runs): 8.4 ms and
      19.2 MB with the serial collector (the same as before `--gc=serial`
      was passed), 8.3 ms and 18.9 MB with Epsilon, 9.6 ms and 25.0 MB with
      G1; with `-Xmx512m` alone each runs as without it.
    - *So the default stays.* `Proportionate` (HotSpot's serial
      collector's sizing: the young generation half the old one, a tenuring
      age that falls when survivors overflow) keeps less memory everywhere,
      but makes deep recursions and `nboyer` slower. `BySpaceAndTime` (a
      fixed young generation, a tenth of the maximum heap up to 256 MB,
      and no survivor spaces) is faster on all but `nboyer`, but a program
      that allocates a few hundred MB peaks 200 MB higher (`divrec` 371 MB
      instead of 164): what #189's minimum heap cost, which the constraint
      on a default rules out. G1 is the fastest on large live sets, the
      native `earley` steady state (2.8 s) as fast as the JIT's on the JVM,
      but by growing the heap (its default maximum is a quarter of memory):
      `earley` peaked at 10.8 GB and `graphs`, whose live heap is 56 MB, at
      6.3 GB. None is a default; each is an option a user can pass or build
      (docs/usage.md, "Choosing a garbage collector"). `-XX:+CollectYoungGenerationSeparately`
      did nothing (`earley` 23.0 s, `nboyer` 15.7 s, one process each), and
      the old `Adaptive` policy was slower (`earley` 26.4 s, `nboyer` 19.2
      s) in less memory.
  - **The native interpreter (M10-02).** The Bytecode DSL generates the
    interpreter with tail call handlers (`enableTailCallHandlers`,
    Truffle's "One Compilation per Bytecode Handler"): in a native image
    (`TruffleOptions.AOT`) each instruction's handler is compiled on its
    own, with the interpreter's state in registers, and jumps to the next
    instruction's handler instead of returning to one large dispatch
    loop. The DSL generates it as a second cached interpreter
    (`CachedBytecodeNodeTailCall`), which only a native image runs: on the
    JVM, HotSpot compiles the usual `continueAt` as before ("Calls and
    tail calls", "The host compiler"), so the JVM's interpreter and JIT
    are unchanged. Measured with the native launcher built both ways
    (Apple M3 Max shared with other agents, load 4-10, interleaved, the
    medians): its interpreter (`--engine.Compilation=false`, 45 of
    ecraven's programs at the report's interpreter scale, 3 rounds) 0.62
    times as long (0.31-0.80, `string`, `pi` and `read1`, which hardly
    interpret, 0.97-1.01); with the JIT, at the report's counts (22
    programs, 3 iterations, 3 rounds), the first iteration 0.97 times
    and the steady state 0.99; `--version` 12.5 ms against 12.8 and a
    hello-world program 13.9 against 13.8 (31 runs each); the shared
    library 384 bytes larger.
  - **Rejected: a 1 GB minimum heap for the native launcher (#189).** The
    image was built for a while with `-R:MinHeapSize=1g`, a default for the
    executable's minimum heap that its own `-Xms` or `-XX:MinHeapSize=`
    replaces, and is built with native-image's defaults again. Native-image's
    serial collector (CE's only one but Epsilon) sizes the young generation
    with its `Adaptive2` policy, which starts it at a third of the initial
    heap: 128 MB, or the minimum heap if that is larger. So eden starts at
    48 MB, and the 1 GB minimum started it at 384 MB. The policy grows it
    later (`nboyer` and `earley` reach about 1 GB either way); a first run
    is what a small one costs.
    - *Why it was reverted.* An `-Xmx` below 1 GB then needed an `-Xms` no
      larger than it: native-image refuses a minimum heap above a maximum
      that was given (`The specified minimum heap size (1g) must not be
      larger than the maximum heap size`), so `scheme-native -Xmx512m
      file.scm` refused to start. (A maximum it chooses itself, from the
      machine's memory, bounds the minimum instead, and nothing fails.)
      And a program that allocates moderately (more than 48 MB, less than
      a few hundred) peaked about three times as high: `divrec` and
      `primes` at 480 MB instead of 165, and the comparative report's
      native median peak RSS was 484 MB instead of 181.
      A user whose programs are deep recursions can pass `--vm.Xms1g` (or
      `--vm.XX:MinHeapSize=1g`) to the executable, which gives the same first
      runs (docs/usage.md, "Native image"). The options can only be set as
      the isolate starts, so a minimum of the launcher's own would be a
      default VM argument of the thin launcher (`LAUNCHER_DEFAULT_VM_ARGS`):
      the same default as #189's, with the same costs.
    - *What it was for.* After M9-01, a deep recursion's first run is mostly
      collections ("Stack-depth spill", *After M9-01*): each one scans the
      whole Java stack, up to the spill limit's 30 MB of frames, and copies
      the frames that the descent keeps live (an interpreted call allocates
      about 230 bytes, and a frame that spills about 100 more: its segment,
      its list cell, and the call that resumes it). Fewer collections do
      less of both.
    - *Measured.* The executable without and with it, built from the same
      commit (`main` at `4ab9edc`), on an Apple M3 Max shared with other
      agents (load 3 to 12); medians of 5 interleaved processes each: the
      first iteration, the median of the later ones, the process's wall
      time, its collections (from `-XX:+PrintGC`) and peak RSS:

      | | Default (eden 48 MB) | 1 GB minimum (eden 384 MB) |
      |---|---:|---:|
      | `count` 300,000 deep ×12 | 133 ms, then 36 ms; 0.61 s; 11 collections, 393 ms; 359 MB | 86 ms, then 6 ms; 0.30 s; 2, 62 ms; 611 MB |
      | build a list and `map` it, 1,000,000 deep, ×6 | 1.70 s, then 506 ms; 4.53 s; 24, 3.45 s; 1.0 GB | 0.63 s, then 422 ms; 2.48 s; 8, 1.38 s; 1.0 GB |
      | `count` 10,000,000 deep ×3 | 10.7 s, then 4.1 s; 19.0 s; 36, 15.5 s; 4.8 GB | 6.1 s, then 3.9 s; 14.0 s; 14, 11.0 s; 5.6 GB |
      | `nboyer` ×3 | 5.78 s, then 4.17 s; 14.2 s; 52, 8.7 s; 1.7 GB | 4.51 s, then 4.04 s; 12.6 s; 23, 6.8 s; 1.9 GB |
      | `earley` ×3 | 8.63 s, then 6.96 s; 22.6 s; 41, 17.3 s; 3.7 GB | 7.22 s, then 7.48 s; 22.2 s; 17, 16.9 s; 4.0 GB |
      | `divrec` ×6 | 367 ms, then 325 ms; 2.03 s; 95, 23 ms; 165 MB | 357 ms, then 312 ms; 1.97 s; 13, 9 ms; 480 MB |
      | `primes` ×5 | 117 ms, then 74 ms; 0.43 s; 19, 11 ms; 169 MB | 125 ms, then 74 ms; 0.44 s; 3, 3 ms; 479 MB |
      | `ack(3,12)` ×4, `fib` ×3 | 1.18 s and 1.79 s first; no collection; 73 and 71 MB | 1.17 s and 1.85 s; the same |
      | A hello-world program (41 runs) | 10.1 ms, 19.1 MB | 9.9 ms, 19.1 MB |

      The spills are the same (`count` 300,000 deep: 13, then 4 to 6, then
      1 or 2; the list: 86, then 17 to 22, then 12). In the 300,000-deep
      `count`, a collection while the stack is deep still pauses 30 to 60
      ms, but with the 1 GB minimum the first iteration has none. A program
      that allocates less than eden's 384 MB in all never collects, and one
      that allocates more peaks with eden full: the deep recursions and
      `nboyer` and `earley` 0.1 to 0.8 GB higher. Programs that allocate
      little are unchanged (a hello-world program 19 MB, `fib` and `ack`
      72 MB). On this machine the JVM launcher's own young generation (the
      serial collector, a third of the JVM's initial heap, 1/64 of memory)
      is larger.
    - *The compilation isolates.* The JIT compiles in isolates of their own
      (`CompileInIsolates`), which take the image's option defaults, not
      the command line's, so a build-time 1 GB minimum gave each one a
      384 MB eden too: `divrec` peaked at 787 MB instead of the 476 MB of
      the same minimum given as `-Xms1g`, with the same collections in the
      program's heap. #189 also passed them `-XX:MinHeapSize=0`
      (`-R:CompilationIsolateOptions`), the default, and the two agreed
      (480 MB). A minimum given on the command line doesn't reach them.
    - *Other sizes.* The same programs with the minimum given
      as `-Xms` (5 interleaved processes each, the first iteration, wall
      time and peak RSS):

      | Minimum heap (eden) | `count` 300,000 deep | the list | `count` 10M | `nboyer` | `earley` | `divrec`, `primes` |
      |---|---:|---:|---:|---:|---:|---:|
      | none (48 MB) | 137 ms; 0.63 s; 372 MB | 1.82 s; 4.42 s; 1.0 GB | 11.9 s; 20.7 s | 6.27 s; 15.3 s | 9.01 s; 23.7 s | 168 MB |
      | 256 MB (96 MB) | 125 ms; 0.50 s; 384 MB | 1.76 s; 4.28 s; 1.1 GB | 8.9 s; 16.7 s | 6.21 s; 15.5 s | 9.52 s; 23.0 s | 190 MB |
      | 512 MB (192 MB) | 88 ms; 0.40 s; 466 MB | 1.12 s; 3.07 s; 1.0 GB | 7.9 s; 15.6 s | 5.32 s; 13.6 s | 9.61 s; 24.7 s | 285 MB |
      | 1 GB (384 MB) | 86 ms; 0.29 s; 567 MB | 0.67 s; 2.34 s; 1.0 GB | 6.5 s; 14.3 s | 4.68 s; 12.8 s | 7.46 s; 22.6 s | 477 MB |
      | 2 GB (768 MB) | 86 ms; 0.29 s; 932 MB | 0.68 s; 1.79 s; 1.2 GB | 7.2 s; 11.8 s | 4.30 s; 11.6 s | 5.95 s; 16.4 s | 868 MB |

      512 MB gets half of what 1 GB does for the list and costs a program
      that allocates 285 MB; 2 GB gains on the long runs but costs 870 MB.
      No survivor spaces (`-H:MaxSurvivorSpaces=0`: what survives a young
      collection goes to the old generation at once, so the descent's
      frames are copied once, not up to the initial tenuring threshold's 7
      times)
      made the list's run 1.3 times as long and `earley`'s first iteration
      2.9 times (one process each), from the full collections of an old
      generation that fills with what a later young collection would have
      freed; one survivor space didn't help either. `-Xmn` and
      `-XX:MaxNewSize` only bound the young generation, and the launcher
      can't set any of these options in the process: they are fixed when
      it starts (the framework's `--vm.XX:MinHeapSize` failed, *immutable*,
      until the thin launcher created the isolate with them, "VM options").
    - *Allocation per spilled frame.* Measured with `-XX:+PrintGCSummary`
      (interpreted only, `scheme.SpillDepth`): an interpreted call of
      `count` allocates 234 bytes, and one that spills 337. A spill copies
      no frame: a suspended interpreted frame is already on the heap, and
      the root prompt resumes its spill's segments in place ("Owned
      segments"); of the 100 bytes, the segment and its list cell are 40,
      the rest the call that resumes it (its arguments, the continuation
      root's frame, the `Resumption`). A compiled frame that spills is
      materialized then (about 200 to 300 bytes). Taking the 40 bytes off
      would make 40% fewer of the spill's own allocations, and about a
      tenth fewer of a deep interpreted descent's, against the third as
      many collections of a 1 GB minimum heap above, so they stay.
  - Limits: the tools (inspector, DAP, profilers) are only on the JVM
    launcher (`bazel-bin/scheme`); the executable is for its target platform
    (this machine's by default, Linux x86-64 on the executors with
    `--platforms=//bazel/platforms:linux_x86_64`, as the tests build it: D11);
    and there is no auxiliary engine cache in the image (only the first
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
| §3.4, §4.1.2 | altering a constant (the value of a literal expression) with a mutation procedure like `set-car!` or `string-set!` | error when the mutation runs on a literal string, vector or bytevector (each flagged immutable; D9); not signalled yet on a literal pair, which `set-car!`, `set-cdr!` and `list-set!` change (D1, plan.md M10-07) |
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

### D19. Differential fuzzing against chibi-scheme

`bazel run //:r7rs -- fuzz` (`tests/fuzz/`, README.md) generates random
programs, runs them under chibi-scheme and under ours in any of the
conformance tests' modes, and minimises and saves those whose stdout or exit
status differ; `//tests/fuzz:fuzz_smoke` runs a fixed seed's 300 on every
commit, so the generator and the driver keep working.

- **A difference must mean a bug.** The generator is type-directed and
  produces only programs R7RS gives one meaning: guarded indices, divisors
  and ranges (no "it is an error", D9, D18), at most one argument of a call
  that leaves non-locally (the order of evaluation is unspecified), no
  side effects that order could reveal, and nothing written whose external
  representation R7RS leaves open (procedures, records, `eof`, error
  objects).
- **R7RS decides, not chibi-scheme** (as for `tests/diff`). Where chibi-scheme
  departs from R7RS, the generator stays clear of it, or, for two printing
  differences every program would hit (`1e+21` without a decimal point, and
  `-1/-4611686018427387904`), the driver undoes them in chibi-scheme's
  stdout; README.md lists each with the R7RS text. Bytevectors' bytes in
  hexadecimal are allowed notation, and undone the same way.
- **Minimised programs** are delta-debugged over the top-level forms and the
  tree. A smaller program counts only if it differs the same way and writes
  nothing the original didn't among what R7RS leaves open (`#<...>`, NaNs,
  complex numbers), so the reduction doesn't drift to another difference.
- *Rejected:* comparing with a tolerance or normalising our output, which
  would hide our bugs; a model interpreter in Python, which would be a third
  implementation to keep right.

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
- **Inline caches for tail calls, and splitting (M5-10).** Each non-tail call
  site (and each prompt) has a trampoline node of its own, `Trampoline`, with
  a 4-entry `DirectCallNode` cache keyed on the tail-called procedure's call
  target, so a procedure reached by a tail call can be inlined into the site
  and its `TailCall` scalar-replaced. The first tail call that comes back and
  the ones after it have separate caches (M7-01), so a chain of tail calls
  (`map` → its loop → `reverse`) is compiled once per site, not twice. A call
  site that goes megamorphic (its own procedure, or the first tail call)
  reports polymorphism, so Truffle splits the procedure it is in per caller;
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
- **The trampoline reports loop counts (M7-02).** The tail calls a site performs
  count as iterations of a loop of the root it is in (`LoopNode.reportLoopCount`),
  as a self tail call's jump does, so a procedure called a few times whose sites
  perform many tail calls is compiled.

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
- **Segments are immutable.** Each resume copies the frame. This is correct
  only because of assignment conversion (D2): the frame holds no mutable
  Scheme variables, only references to shared boxes.
- **Copying a frame:**
  `Truffle.getRuntime().createMaterializedFrame(frame.getArguments().clone(), descriptor)`,
  then `frame.copyTo(0, copy, 0, descriptor.getNumberOfSlots())`. This copies
  the arguments and every frame slot, operand stack included. It's the same
  copy `BytecodeFrame.copy()` makes internally, but `BytecodeFrame` doesn't
  expose its frame, so it can't be used directly. The copy is passed to the
  segment's `ContinuationRootNode` call target as `(copy, value)`, through a
  `DirectCallNode` inline cache keyed on the `ContinuationRootNode`.
- **Never resume an original frame.** Truffle documents a
  `ContinuationResult` as resumable only once. In M0-02, resuming an original
  frame a second time crashed with `FrameSlotTypeException`. Only fresh copies
  are resumed, and that is what makes multi-shot possible.
- **Stack-depth spill.** A depth counter forces a capture past a threshold,
  then execution resumes on a fresh stack. Deep non-tail recursion never
  overflows the Java stack.
- **Escape-only fast path (M5-04).** When `EscapeAnalysis` proves that a
  `call/cc`'s `k` is never `set!`, never referenced by a nested lambda, and is
  only called or passed to a known procedure (a literal lambda, an internal
  definition, or in an isolated program a once-defined global) whose
  parameter qualifies in turn, the call compiles to `%call/ec`: a prompt with
  a fresh tag. Calling `k` throws `EscapeException`, a `ControlFlowException`;
  a `k` tail-called back to its own prompt returns with no throw. An escape
  across a change of dynamic environment goes through the prelude's
  `%escape`, which rewinds first. Captures flatten the prompt into a marker
  like any other, so `k` works from re-entered copies too.
  `unwindExceptions` isn't needed: guard is prompt-based (D4), so there are
  no guest exception handlers for an escape to bypass.
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
| string | `SString`: Latin-1 `byte[]` until a character above 255 is stored, then UTF-32 `int[]`. Indexing and `string-set!` are O(1). Literals carry an immutable flag. |
| symbol | `SSymbol`, interned per context, name is a `TruffleString` |
| pair | `SPair`, mutable `car`/`cdr` fields |
| vector / bytevector | `SVector` (`long[]` while every element is a fixnum, `double[]` while every element is a flonum, otherwise `Object[]`, generalizing for good on the first store that doesn't fit; M5-06) / `SBytevector` (`byte[]`), each with an immutable flag for literals |
| empty list, unspecified, eof, default object | singletons |
| procedure | `SClosure { RootCallTarget, Object[] captured }` |
| multiple values | a single value is returned as itself; zero or 2+ values are a `MultipleValues` (`Object[]`) |
| record | `SRecord { SRecordType }`, a static object of Truffle's Static Object Model whose fields are its record fields (M7-04) |

- `eq?` compares `long` and `SChar` by value and everything else by identity,
  so boxing never leaks into results.
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
  without bound; the type check it would save is one field load).

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
- `exit` runs all outstanding after-thunks through `rewind`, then exits the
  context through Truffle. `emergency-exit` skips the after-thunks.
- `delay`, `delay-force` and `make-promise` use the space-safe reference
  implementation from §7.3.
- Unicode: use the JDK's tables. Character procedures use simple case
  mapping, string procedures use full case mapping (`(string-upcase "ß")` is
  `"SS"`), and everything uses `Locale.ROOT`, never the default locale.
- Floating-point output: shortest round-trip digits (the JDK 21+
  `Double.toString`), rewritten into R7RS syntax (`+inf.0`, `-inf.0`,
  `+nan.0`). Complex numbers print in rectangular form, `a+bi`.

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
    unspecified value is null. Records are objects with their fields as
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
    `error-object`, `environment`, `record-type`, `unspecified` and
    `values`; a record's meta object is its record type. `long`, `double`
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
  adds the other R7RS implementations for the comparative benchmark report.
- **CI runs only what runs on every commit**, on Buildkite
  (`.buildkite/pipeline.yml`, hosted `linux-small` agents, the Nix dev
  shell): the lint tests and the other tests. The required check is the
  build (`buildkite/truffle-scheme`); the pipeline says which steps can
  fail it. Conformance is a test per suite section and mode (interpreted,
  compiled, under torture), which passes when every test of the section
  passes but the suite's listed exceptions, and those fail; docs/plan.md's
  status block is what they check, and the per-section table is a report
  of their results.
  Benchmarks, the threshold check (`bench/thresholds.json`, measured on the
  maintainer's Mac) and the native image are local commands, by the
  maintainer's choice.
- **Measured** when Bazel replaced mx (same commit; Apple M3 Max, 16 cores,
  shared with other builds at load 15–25; GitHub's `ubuntu-26.04` runners):

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
  (thinner open-source JVM support).

### D12. Continuation strategy

- Re-entrant continuations come from Bytecode DSL yields at call sites, the
  segmented stack described above.
- *Rejected:* Loom's `jdk.internal.vm.Continuation` as the base (one-shot,
  can't be cloned, internal API); full CPS conversion (gives up Truffle's frame
  virtualization on every call); escape-only `call/cc` alone (not
  conformant).
- **Status: go** (M0-04). The M0 spike confirmed both open questions. See
  "Spike results" under "Continuations".

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
  pays nothing. Boxes aren't reported: an allocation listener takes only
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
  root of an isolated program (D15) records the program's top-level scope
  and namespace (`SchemeRootNode.getTopLevel`, transient, so not in the
  prelude image), and code evaluated in its frames sees exactly the
  program's imports and definitions; elsewhere, the interaction
  environment. Evaluated code may not `set!` an isolated program's own
  variables: the program was compiled as a closed unit, assuming only its
  own code assigns them (`EscapeAnalysis`). Library frames still use the
  interaction environment. A procedure's frames are named by its
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
    launcher.
- The Chrome inspector, DAP and profiler tools ship as a separate optional
  distribution, and CI runs a smoke test of each against a Scheme program
  (D13's tags and source sections are what they consume).
- **Native image (M6-06).** `bazel run //:r7rs -- native-image` builds `scheme` as a
  native executable with the dev shell's GraalVM CE `native-image`, from the
  launcher's module path without the tools. Truffle's runtime on that path
  makes native-image add Truffle's runtime compilation, so the executable
  has the Graal JIT. The `SCHEME` jar carries its own configuration
  (`src/runtime/META-INF/native-image/org.r7rs/scheme`), so any embedder's
  image picks it up: the prelude's resources, and build-time initialization
  of the language's packages, as GraalVM's own languages have (the language
  provider and the DSL-generated library exports live in the image heap).
  Static state then keeps its build-time value: `-Dscheme.torture` and
  `-Dscheme.preludeImage` (and its `.file`) have no effect in a native image,
  so torture mode needs the JVM. No reflection configuration is needed (D10).
  - The build-time prelude image (D13, M7-05) is read and checked when
    `PreludeImage` is initialized, so at image build time: the checked image,
    its prelude `Source`s included, is in the image heap, the executable
    doesn't read it (nor, when it restores, the prelude files), and the
    resources the executable carries leave it out.
  - Code that partial evaluation reaches must not reach a method
    native-image blocklists for runtime compilation. On HotSpot such a path
    only costs code size, but native-image refuses to build: a call through
    a field of a JDK functional interface (it may reach every implementation
    in the image), a root's `getName`, and string concatenation outside a
    `@TruffleBoundary` all failed the first build.
  - Numbers on an Apple M3 Max (16 cores, macOS), median of 10 runs; the
    JVM launcher is the `java` command `bazel-bin/scheme` runs (M6-06;
    since M7-05 the first context restores the prelude image, which D13
    measures against expanding):

    | | Native | JVM |
    |---|---:|---:|
    | `scheme --version` | 15 ms, 15 MB | 228 ms, 251 MB |
    | A hello-world program | 20 ms, 27 MB | 614 ms, 326 MB |
    | ecraven's `fib` (40, 5 runs), steady iteration | 12.4 s | 13.3 s |
    | ecraven's `tak`, steady iteration | 4.7 s | 6.0 s |
    | Peak RSS over 5 iterations of `fib` / `tak` | 149 / 176 MB | 1505 / 1558 MB |

    The build takes 50–90 s and about 3 GB on that machine; the
    executable is 47 MB. The JIT compiles in the native launcher as on the
    JVM (`--engine.TraceCompilation` shows the tier-1 compilations), and
    the first `fib` iteration (17.8 s against the JVM's 13.6 s) is the
    only one that is slower.
  - `bazel run //:r7rs -- test --native` runs each conformance section as a process of
    the native launcher, as `ConformanceRunner` runs it, and passes what the
    JVM does: chibi's suite 1225/1225 and Larceny's 2162/2166, interpreted
    and with `--compile`. `bazel run //:r7rs -- status --check --native` fails if the
    native launcher passes anything other than the status block.
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
  - CI doesn't build it (D11): the build takes minutes and gigabytes. A PR
    that changes the native-image configuration runs `bazel run //:r7rs --
    readme --native` and that check locally.
  - On x86-64 Linux the image targets the baseline CPU
    (`-march=compatibility`): with the default target (AVX2) the image
    uses the JDK's `libsimdsort`, whose libm dependency nixpkgs'
    native-image resolves to glibc's static `libm.a`, and linking that into
    a dynamically linked executable fails. The baseline target is also what
    nixpkgs' own native-image check builds with, and makes the CI artifact
    run on any x86-64 machine.
  - Limits: the tools (inspector, DAP, profilers) are only on the JVM
    launcher (`bazel-bin/scheme`); the executable is for the platform that built
    it; and there is no auxiliary engine cache or pre-initialized context in
    the image (each context still restores the prelude image and runs the
    library bodies).
- Why: Truffle languages get debugging, profiling and engine tuning from
  the platform; a hand-written launcher would have to re-implement or forgo
  all of it.


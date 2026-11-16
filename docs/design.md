# Design

R7RS-small on GraalVM Truffle, built on the Bytecode DSL. Target: GraalVM CE
25.5 (Truffle 25.5.5.0.3), from nixpkgs (D11).
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
- Self tail calls in named `let` and `do` compile to loops (back-edges, OSR).

### Continuations: a segmented stack

- `call/cc` returns a `Capture` marker. At each non-tail call site,
  `CallSiteEmitter` emits: call → `IsCapture` check → `SuspendFrame`, a custom
  `@Yield` operation (Truffle 25.1+). `SuspendFrame` `@Bind`s its
  `ContinuationRootNode` and `MaterializedFrame`, adds
  `Segment(ContinuationRootNode, MaterializedFrame)` to the capture, and
  returns the marker to its own caller, which does the same.
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
- **Escape-only fast path (M5).** When the compiler can prove a `call/cc`'s
  `k` is only ever called directly inside the body and never captured by a
  closure, it compiles to an escape exception instead of a capture. A
  candidate mechanism is `@GenerateBytecode(unwindExceptions = ...)` (25.5),
  which lets the escape bypass guest exception handlers such as `guard`'s.

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
| vector / bytevector | `SVector` (`Object[]`) / `SBytevector` (`byte[]`), each with an immutable flag for literals |
| empty list, unspecified, eof, default object | singletons |
| procedure | `SClosure { RootCallTarget, Object[] captured }` |
| multiple values | a single value is returned as itself; zero or 2+ values are a `MultipleValues` (`Object[]`) |
| record | `SRecord { SRecordType, Object[] fields }` |

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
- *Rejected:* `MutableTruffleString` for strings (no compaction, conversion
  costs on every operation); Java `String` (UTF-16, immutable); inexact-only
  complex numbers (the chibi suite assumes the full tower); complex numbers
  with mixed-exactness parts (adds cases to every operation for almost no
  real use); Truffle's Static Object Model for records (revisit in M5 if
  profiles show it matters).

### D2. Closures: flat closures with assignment conversion

- The compiler computes each lambda's free variables and copies their values
  into `SClosure.captured`.
- **Any variable that is the target of `set!` lives in a heap `Box`.** The
  frame holds the box, never the value. `letrec*` and internal `define`
  variables are boxed when a closure captures them. "Fixing Letrec" (avoiding
  boxes for groups of mutually recursive lambdas) is an M5 optimization.
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

### D9. Spec traps decided up front

- `equal?` must terminate on circular structures (R7RS §6.1). Use the
  union-find algorithm with a fast path for small trees (Adams & Dybvig).
- `write` uses datum labels for cycles, `write-shared` labels all shared
  structure, and `write-simple` uses no labels. `display` must also terminate
  on cycles.
- `list?` returns `#f` for circular lists (tortoise and hare).
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
- **Interop for embedders (M6):** Scheme values answer Truffle's
  `InteropLibrary` as what they are: numbers as numbers (fitting
  `long`/`double`/`BigInteger`), strings as strings, booleans, `SChar` as a
  one-character string, procedures as executables (calling one runs it
  under a prompt, D6), vectors as arrays, the empty list and unspecified as
  null. Scheme code does not reach into other languages or the host in v1.
- **Out of scope:** Scheme-side access to other languages and the host,
  native image in CI, threads, and SRFIs. Avoid reflection and dynamic class
  loading so a native-image launcher (M6, optional) stays possible.
- **Deferred to M5:** escape-only `call/cc`, Fixing Letrec, vector storage
  strategies, and the Static Object Model for records.

### D11. Build and tooling

- **Released GraalVM, from Nix.** The JDK is GraalVM CE 25.5 from nixpkgs.
  `flake.nix` puts it on `JAVA_HOME`, and its built-in Graal compiler is the
  JIT. Truffle comes from Maven Central as mx libraries in `mx.r7rs/suite.py`
  (`truffle-api`, `truffle-runtime`, `truffle-compiler`,
  `truffle-dsl-processor`, `polyglot` and their SDK dependencies), at
  **exactly** the GraalVM version (25.5.5.0.3). `mx_r7rs.py` refuses to run if
  the two differ.
- **Upgrading** is a human-made change. Bump nixpkgs in `flake.lock` and the
  Truffle library version and digests in `suite.py` together.
- **mx** is still the build tool, the same one GraalPy and TruffleRuby use.
  One toolchain covers building, the annotation processor
  (`TRUFFLE_DSL_PROCESSOR`), `mx unittest`, `mx checks` and `mx tests` (CI and the pre-PR
  check), and `mx benchmark` for M5. Compiled graphs are dumped with
  `-Djdk.graal.Dump` on the GraalVM JDK and viewed in a separately installed
  IGV.
- Nix (`flake.nix`) provides the whole dev shell: GraalVM, `mx` (copied out
  of the store into a writable cache dir, because mx writes into its own
  checkout), Python, `gh` and chibi-scheme. There's nothing to fetch by hand.
- *Rejected:* importing the `truffle` suite from a pinned graal master commit
  with a matching labsjdk (the original D11). It gets Bytecode DSL changes
  before release, but needs a ~1.5 GB graal checkout plus a labsjdk whose
  JVMCI version must track the graal commit, and nixpkgs can't provide that
  JDK. Revisit only if we need an unreleased Truffle fix. Also rejected:
  Bazel (too much build machinery for a project this size); Maven or Gradle
  (no gate, IGV or benchmark integration); Buck2 (thinner open-source JVM
  support).

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
- Why: Truffle already does this well; positions and tags are data we have
  (syntax objects) handed to machinery we don't have to write.
- **Proposed, not yet scheduled:** Bytecode DSL serialization
  (`enableSerialization`) to cache the compiled standard libraries and make
  context startup cheaper (M5), and `unwindExceptions` for the escape-only
  `call/cc` fast path (already under "Continuations").
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
  (D13's incomplete-source signal) and prints each input's values.
- The Chrome inspector, DAP and profiler tools ship as a separate optional
  distribution, and CI runs a smoke test of each against a Scheme program
  (D13's tags and source sections are what they consume).
- Why: Truffle languages get debugging, profiling and engine tuning from
  the platform; a hand-written launcher would have to re-implement or forgo
  all of it.


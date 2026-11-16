# Exploration: interop, embedding and Truffle tools

Exploration, not to be merged as is. It asks how much of GraalVM's Truffle
platform we get for Scheme: other languages and Java embedding Scheme
(design.md D10), Scheme reaching Java and other languages (D10 leaves this
out of v1), and the debugger, Chrome inspector and profiler (D13, D17). It
comes with a working prototype of each part and tests for it. Everything
below was run against Truffle 25.5.5.0.3 on this branch. Code marked
`EXPLORATION` is prototype code.

## Summary

| Area | What it took | Result |
|---|---|---|
| Embedding: Java calls Scheme | `@ExportLibrary(InteropLibrary.class)` on the value classes, an interop-call root, and `getScope` | Works. `context.eval("scheme", "(lambda (x) (* x x))").execute(7).asInt()` returns 49. Strings, characters, booleans, numbers (bignums as `BigInteger`), vectors and bytevectors as arrays, lists as iterators, records as objects, `'()` as null, and the interaction environment through `getBindings("scheme")`. |
| Other languages call Scheme | Nothing beyond embedding | Works with GraalJS (tried from scratch jars, not added to the suite): JS calls Scheme procedures, indexes vectors, iterates lists with `for…of`, and calls back into Scheme from `Array.prototype.map`. |
| Scheme calls Java and other languages | `(truffle interop)`: 14 primitives over `InteropLibrary`, a callback barrier, and error translation | Works: static and instance methods, fields, constructors, Java calling Scheme back (`forEach`, `Comparator`), `call/cc` inside a callback, escaping out through Java frames, errors in both directions, polyglot bindings, `polyglot-eval`. |
| Debugger (DAP, Chrome inspector) | One missing tag (`DebuggerTags.AlwaysHalt`) **crashed both on attach**. With it added, plus local names | Breakpoints, stepping, the stack with file:line:col, locals and parameters by name, and globals all work. Missing: evaluating expressions in a frame, captured variables, names for anonymous lambdas, hiding temporaries, and spilled frames. |
| CPU sampler, `TraceCompilation` | Nothing (system properties work with today's `SchemeMain`) | Works: histogram, call tree and the JUnit API. Time in spilled (resumed) segments is charged to `<prompt>`. |

Most of this comes almost free from Truffle. The real costs are:

- re-entry across foreign frames (a barrier prompt);
- the debugger's frame evaluation (inline parsing);
- a custom debugger scope that unboxes, shows captured variables and hides
  temporaries;
- and, the one item already on the M6 list, showing spilled frames in stack
  traces, the debugger and the profiler.

## 1. Embedding: Scheme values through InteropLibrary (D10)

Prototype: `src/runtime/scheme/runtime/value/*`, `InteropCallRootNode`,
`InteropValues`, `TopScope`, `LanguageView`. Tests: `EmbeddingTest`.

| Scheme value | Interop | Notes |
|---|---|---|
| `long`, `double`, `boolean` | primitives already | nothing to do |
| `SBigInteger` | `isNumber`, `fitsInBigInteger`/`asBigInteger`; `fitsInDouble`/`fitsInFloat` only when exact | the host sees `BigInteger`; JS sees a number when it fits |
| `SRational`, `SComplex` | display only | `fitsInDouble` when exact would be easy; complex has no interop type |
| `SString` | `isString`, `asString` (a copy) | the host sees `java.lang.String`; JS sees a string |
| `SChar` | a one-character string | as D10 says |
| `SSymbol` | display only (not a string) | so `isString` can tell a symbol from a string; open question |
| `SVector` | an array, writable unless it's a literal | the host sees `List`/array parameters; JS sees `Array.isArray(v) === true` |
| `SBytevector` | an array of 0–255 | interop's buffer messages would also fit, at a dozen more exports |
| `SPair` | `hasIterator`: the list's elements, lazily | `Value.getIterator()`, JS `for…of`. Not an array: lists aren't indexable in O(1) |
| `'()`, unspecified | `isNull` | as D10 says. EOF and the default object display only |
| `SRecord` | read-only members, named after the fields | `point.getMember("y")` |
| `MultipleValues` | a read-only array | |
| `SClosure` (any procedure) | `isExecutable`, `execute`, `getExecutableName`, `getSourceLocation` | see below |
| everything else | `hasLanguageId`/`getLanguageId` ("scheme"), `toDisplayString` (what `write` prints) | |

- **Executing a procedure** (`SClosure.execute`) calls one shared call
  target, `InteropCallRootNode`. It converts the arguments (boxed `Integer`,
  `Float`, `Character`, `String`, `TruffleString` become `long`, `double`,
  `SChar`, `SString`; D1 has one representation per value) and runs the
  procedure under a **root prompt of its own**, as a REPL input does (D6). So
  tail calls, `call/cc` (re-entered several times inside one call), spilling
  past 100 000 deep, `dynamic-wind` and errors all work across the boundary.
- **The top scope** (`SchemeLanguage.getScope`) is the interaction
  environment (D15). `getMemberKeys` returns its variables (imports and
  definitions; syntax is left out). `putMember("limit", 10)` defines, as
  `(define limit 10)` would. Writing to an imported binding is refused (D7).
  The debugger shows the same object as its global scope.
- **`getLanguageView`** lets tools show primitives in Scheme syntax (`#t`,
  `+inf.0`) and foreign values as `#<foreign …>`.
- **Cost:** about 25 lines per value class, all in `src/runtime`, and no
  change to the compiler or the operations. Interop isn't on any hot path:
  `execute` is a call through an `IndirectCallNode` (fine for embedding;
  JS→Scheme in a hot loop would want a `DirectCallNode` cache keyed on the
  closure's call target).
- **Deprecated in 25.x:** `hasLanguage`/`getLanguage(Class)` are deprecated in
  favour of `hasLanguageId`/`getLanguageId`. The prototype uses the new ones.
- **Missing:** `getMetaObject` (type objects such as `pair` and `vector`; host
  error messages currently say `type: Unknown`), `SchemeError` answering
  `getLanguageId` (it shows as `#<foreign scheme.runtime.SchemeError …>`), and
  identity (`isIdenticalOrUndefined`/`identityHashCode`, so `eq?` is
  preserved through a host `IdentityHashMap`).

### Another language for free: GraalJS

`org.graalvm.polyglot:js-community` isn't in `suite.py` (AGENTS.md: no new
libraries). I put the jars it pulls in on the module path by hand
(`js-language`, `regex`, `shadowed/icu4j`, `shadowed/xz`, about **51 MB**, of
which 28 MB is js-language and 18.5 MB is icu4j) and ran this in one context:

```
js -> scheme: 145                       square(12) + 1, square a Scheme lambda
js sees vector: true 3 string TWO       Array.isArray(v), v.length, typeof v[1], v[1].toUpperCase()
js iterates list: 6                     for (const x of l) s += x
js sees bignum: number 1267650600228229401496703205376   (expt 2 100), exact as a double
scheme -> js: (9.0 "graal" #(1 2 3) #(10 20 30))   Math.max, a JS object's members, JS map calling a Scheme lambda
js error in scheme guard: "TypeError: from js"
scheme error through js: "car: not a pair: 1"       Scheme error inside a JS callback, caught by a Scheme guard
escape through js: 200                  call/cc's k invoked inside a JS callback escapes through the JS frames
```

The program is under "Trying it" below. JS numbers come back as flonums
(`9.0`): JS doesn't say whether an integer was meant. **Verdict:** polyglot
with JS works with no extra code, but costs 51 MB of test dependencies. For
M6's "tests from another Truffle language if one is available", a separate,
optional `mx` test target that downloads JS (or a tiny test language written
for the purpose) is the right size. It shouldn't go in the default test path.

## 2. Scheme calls Java and other languages: `(truffle interop)`

Prototype: `src/interop/scheme/interop/InteropPrimitives.java` (a new project,
`scheme.interop`) and `src/prelude/scheme/prelude/truffle.interop.sld`.
Tests: `HostInteropTest`.

```scheme
(import (scheme base) (truffle interop))
(define Math (java-type "java.lang.Math"))              ; env.lookupHostSymbol
(java-invoke Math "max" 3 7)                            ; => 7, invokeMember
(java-field (java-type "java.lang.Integer") "MAX_VALUE"); => 2147483647, readMember
(define sb (java-new (java-type "java.lang.StringBuilder") "ab"))  ; instantiate
(java-invoke sb "toString")                             ; => "ab", a Scheme string
(define l (java-new (java-type "java.util.ArrayList")))
(java-invoke l "forEach" (lambda (x) (display x)))      ; a Scheme procedure as a Consumer
(java-invoke (java-type "java.util.Collections") "sort" l (lambda (a b) (- b a)))  ; ... as a Comparator
(foreign->vector l)                                     ; a Java List or array, or a JS array, to a vector
(polyglot-eval "js" "(a, b) => Math.max(a, b)")         ; another language
```

The library exports `java-type`, `java-new`, `java-invoke`, `java-field`,
`java-set-field!`, `foreign?`, `foreign-null?`, `foreign-execute`,
`foreign-length`, `foreign-ref`, `foreign->vector`, `polyglot-import`,
`polyglot-export` and `polyglot-eval`. Apart from `java-type`, each works on
any interop value: a Java object, a JS object, and so on.

- **Conversions.** Outgoing values need none: every Scheme value already
  answers interop as what it is (section 1). So the host maps a vector to a
  `List` or a varargs array, a procedure to any functional interface, and
  `'()` to `null`. Incoming results become Scheme values where one exists
  (`String` becomes a fresh mutable `SString`, `Integer` a `long`, a host
  `BigInteger` an exact integer, `Character` an `SChar`). Anything else stays
  foreign, and prints as `#<foreign [1, 2]>` using its own language's display
  string.
- **Host access is the embedder's choice.** `java-type` needs
  `allowHostClassLookup`. Without it, the result is a raisable Scheme error
  ("not allowed"). `SchemeMain` allows nothing, so programs run by the
  launcher can't reach Java today.
- **Rule 1 (Java never calls Scheme).** These primitives live outside
  `src/builtins`, whose `r7rs-check` forbids `InteropLibrary`. That's because
  an interop message *can* run guest code. The reasoning that makes them safe
  is this:
  1. A call into foreign code is a leaf call from Scheme's point of view.
     The only Java frames are the foreign callee's own.
  2. When foreign code calls a Scheme procedure back, the procedure runs under
     its own root prompt (`InteropCallRootNode`), so no Scheme continuation
     ever needs to capture a Java frame (D5). That is the same structure as a
     REPL input (D6).
  3. The one message that would run a Scheme procedure directly (`execute`
     on an `SClosure`) is never sent: `foreign-execute` returns a `TailCall`
     instead.

  If this lands, `r7rs-check` should check `src/interop` too: allow
  `InteropLibrary`, and forbid call nodes except in `polyglot-eval`.
- **Callbacks: what crosses the Java frames, and how.**

  | Situation | Behaviour | Mechanism |
  |---|---|---|
  | `call/cc` inside a callback, re-entered inside it | works | it captures up to the callback's prompt |
  | an outer continuation invoked inside a callback | **escapes**: the Java frames are abandoned and the rest of the program runs once | `Continuation.owner` records which root-prompt run captured it. An interop-call prompt is a *barrier*: it resumes only its own continuations and rethrows any other `InvokeContinuation` (a `ControlFlowException`, which passes through host frames untouched) |
  | a `dynamic-wind` around the Java call, then that escape | its after-thunk runs once | the callback keeps the caller's winders, so `rewind` sees the right path |
  | re-entering a continuation captured *outside* a callback *after* returning through Java | not possible | Java frames can't be captured, so this is the documented limit |
  | an error in a callback that it doesn't handle | raised again on the Scheme side of the Java call, where a `guard` sees it | the callback starts with no handlers, so the error is uncaught there, leaves as a `SchemeError` through the Java frames (Truffle unwraps it on re-entry), and the primitive raises it again in the caller's frame (D8). A `guard` handler can't run inside the callback: it captures up to its prompt, which is across the Java frames |
  | a Java exception (`NumberFormatException`) | a Scheme error object whose irritant is the exception | any `AbstractTruffleException` from a foreign call |
  | a raised non-condition (`(raise 'boom)`) in a callback | arrives wrapped in an error object | a prototype shortcut: `SchemeError` can only raise `SErrorObject`s. Fix: let it carry any raised object |

- **Not done:** calling a foreign executable directly, `((java-field Math
  "max") 1 2)`. It needs a `Call` specialization for
  `interop.isExecutable(procedure)` and `TailCall` support in the trampoline.
  Also not done: `java-type` for arrays, and host-to-Scheme conversion for
  `null` (it stays a foreign null: `foreign-null?`).

## 3. Truffle tools

Tried from the launcher, with the tool jars on the module path and options
passed as `-Dpolyglot.<option>` system properties (no launcher changes
needed). Also tried from JUnit through the `Debugger` and `CPUSampler` APIs
(`DebuggerProfilerTest`). For this exploration, `suite.py` adds
`profiler-tool`, `dap-tool`, `chromeinspector-tool` and `shadowed/json`
(2.7 MB) as libraries that only the test project uses.

### The finding that matters: both debuggers crashed on attach

Attaching DAP (or the inspector) failed with this internal error:

```
IllegalArgumentException: Invalid tag specified. Tag 'com.oracle.truffle.api.debug.DebuggerTags$AlwaysHalt'
not provided by language 'scheme.runtime.SchemeLanguage'.
  at SchemeRootNodeGen$BytecodeConfigEncoderImpl.getTagMask ... at Breakpoint.install ... at DebuggerSession.install
```

Every debugger session installs an `AlwaysHalt` breakpoint (for JS's
`debugger;` statement). With tag instrumentation, the Bytecode DSL rejects a
tag the language doesn't list in `@ProvidedTags`. The fix is to add
`DebuggerTags.AlwaysHalt.class` to `@ProvidedTags` (this branch does). M3-10's
test used a custom instrument with statement and call tags only, so it
couldn't catch this. **This is the one fix worth landing on its own, now**,
with a test that starts a `Debugger` session.

### Debugger (DAP and Chrome inspector)

Driven by `docs/exploration/dap_client.py` and `inspect_client.py` (raw DAP
over TCP and CDP over a websocket, no dependencies) against `debugme.scm`
(below). This is what DAP showed:

```
setBreakpoints: line 3 -> verified, resolved to 3:3-4:6 (the let form)
stopped (breakpoint)
  frame square       debugme.scm:3:3
  frame loop         debugme.scm:9:30
  frame <program>    debugme.scm:10:10
  scope Block        [x=0]
  scope interaction-environment [lcm=#<procedure lcm>, ...]
  evaluate x -> 0
next  -> square 4:8   scope Local [Return value=#<foreign scheme.runtime.TailCall@...>]
stepIn -> loop 9:40   scope Block [i=0, acc=0, local2=#<procedure loop>, local3=1, local4=#<unassigned>, ...]
continue -> exited
```

The Chrome inspector showed the same:
`Debugger.setBreakpointByUrl`, `Debugger.paused` with three call frames, a
`block` and a `global` scope through `Runtime.getProperties`, and
`Debugger.stepOver`.

| Feature | Status | What it would take |
|---|---|---|
| Breakpoints by line, stepping (over, into), continue | **works** | (statement tags and source sections from M3-09/M3-10) |
| Stack with names and file:line:col | **works** | |
| Parameters and `let`/`define` locals by name | **works** with this branch | `createLocal(name, null)` in `CodeGenerator`: one line. Bytecode DSL's `DefaultBytecodeScope` (from `TagTreeNode`) then names them |
| Values in Scheme syntax | works | `toDisplayString` on values (section 1) and `getLanguageView` for primitives |
| Global scope | works, but shows the interaction environment even for an isolated program (D15) | the program's own top level needs a per-root scope (below), not the language's top scope |
| Boxed (`set!`) variables | shown as their contents | `Box` exports `toDisplayString`. A custom scope should unbox and allow writes |
| Captured (free) variables | **missing**: a closure's frame shows only its own locals | a custom scope: `@GenerateBytecode(tagTreeNodeLibrary = SchemeScopeExports.class)`, which reads `SClosure.captured` (argument 0) with names from the compiler (store the free-variable names on the root) |
| Temporaries | shown as `local2`…`local5` | the same custom scope hides unnamed locals |
| Anonymous lambdas | frame named `null` (`getName()` is null; `let` is a closure call until M5-05) | name anonymous roots, e.g. `lambda@3:5`, or `let` once M5-05 lands |
| A tail-calling frame's return value | shown as `#<foreign scheme.runtime.TailCall@…>` | `TailCall` exports `toDisplayString` ("#<tail call>"); cosmetic |
| Evaluating an expression in a frame (watch, console, `(* x 10)`) | **missing**: the debugger parses it as a program in the interaction environment, where `x` is unbound. A bare identifier works because the debugger falls back to the scope | `TruffleLanguage.parse(InlineParsingRequest)`: expand the expression with the frame's locals as bindings, and compile a root that reads them from the frame (`BytecodeNode.getLocalValue` by name) or from the scope object. Medium-sized: the expander needs a "frame environment" |
| Spilled frames (M2-05) | **missing**: after a spill or a torture capture, frames that are heap segments aren't on the Java stack, so the debugger's stack loses them (under `--torture` the test sees `[null, loop]` without `<program>`) | already an M6 item. Truffle's way: the prompt driver's root node implements `RootNode.findAsynchronousFrames`-style stack walking, or the segments' `ContinuationRootNode`s are reported with `TruffleStackTrace` `addStackFrameInfo`. Needs a spike |
| Stop reason after a step | DAP labels it `debugger_statement` | cosmetic, the tool's choice |

### CPU sampler and compilation tracing

`-Dpolyglot.cpusampler=true` on `fib(27)`:

```
 Name       ||  Total Time   ||  Self Time    || Location
 fib        ||  150ms 100.0% ||  150ms 100.0% || fib.scm~2-5:38-114
 <prompt>   ||  150ms 100.0% ||    0ms   0.0% || fib.scm~1-7:0-144
 <program>  ||  150ms 100.0% ||    0ms   0.0% || fib.scm~1-7:0-144
```

- `cpusampler.Output=calltree` works too, and so does the JUnit API
  (`CPUSampler.find(engine)`, `DebuggerProfilerTest`).
- **Spilled frames again:** on deep recursion (`down` 2000 deep, 300 times),
  61% of self time is charged to `<prompt>`. That's where the driver resumes
  spilled segments, and the resumed `ContinuationRootNode`s aren't
  attributed to `down`. It's the same gap as the debugger's stack.
- The sampler reported many missed samples (30 of 61). Worth checking with
  the M5 benchmarks.
- `-Dpolyglot.engine.TraceCompilation=true` works. One observation for M5:
  `fib` compiles with `Inlined 0Y 5N`. None of its five call sites (`<`, `-`,
  `+`, and the recursive `fib`) inlines, and `<`, `-` and `+` compile as
  separate call targets.
- `--memtracer` (in profiler-tool) wasn't tried.

## Recommended plan

**Free or almost free.** These belong in M6, mostly as they are here:

1. `DebuggerTags.AlwaysHalt` in `@ProvidedTags`, plus a test that starts a
   `Debugger` session. Land now: today the debugger crashes on attach.
2. Named locals (`createLocal(name, null)`).
3. Interop exports on the value classes, the interop-call root (barrier
   prompt included), `getScope` and `getLanguageView`. This is D10's M6
   item, done. Add `getMetaObject`, `SchemeError`'s language id, and an
   inline cache for `execute`.
4. The tools distribution (D17), with `profiler-tool`, `dap-tool`,
   `chromeinspector-tool` and `shadowed/json` at the Truffle version. CI
   smoke tests can be the two Python clients here (DAP: breakpoint, stack,
   scopes, step; CDP: the same), plus `DebuggerProfilerTest`. `SchemeMain`
   already accepts every tool through `-Dpolyglot.*`. D17's polyglot
   launcher would make that `--dap`, `--inspect` and `--cpusampler`.

**Real work.** Each is its own M6 task:

5. A custom debugger scope (`tagTreeNodeLibrary`): unbox, show captured
   variables by name, hide temporaries, and give programs their own global
   scope. It needs the compiler to record free-variable names on each root.
6. Frame evaluation (`parse(InlineParsingRequest)`): needed for watch
   expressions and the inspector console. It depends on 5's name maps.
7. Spilled frames in stack traces, the debugger and the sampler (already
   M6). Needs a spike: the frames exist as segments in the prompt driver,
   and Truffle has to be told about them.

**A decision, not a task:**

8. Scheme reaching Java and other languages (`(truffle interop)`). D10 rules
   it out for v1. The prototype shows it's cheap (one 300-line project), and
   that the barrier prompt makes it consistent with D5 and D6. If it's
   wanted, it needs a design change: amend D10 to allow it, record the
   callback semantics above (an escape through foreign frames works;
   re-entry across them doesn't; errors are re-raised at the call site), and
   extend `r7rs-check` to cover `src/interop`.
9. Polyglot tests with JS: possible today at 51 MB of downloads. Keep them
   out of the default `mx tests`: either an optional target or a minimal
   test language.

## Trying it

```
export MX_ALT_OUTPUT_ROOT=...   # as usual
mx build
mx unittest EmbeddingTest HostInteropTest DebuggerProfilerTest
mx r7rs-unittest --torture EmbeddingTest HostInteropTest DebuggerProfilerTest

# The launcher with the tools on the module path (tool jars from ~/.mx/cache after mx build):
CP=$(mx -p . classpath SCHEME_LAUNCHER | tail -1)
TOOLS=<profiler-tool.jar>:<json.jar>:<dap-tool.jar>:<chromeinspector-tool.jar>
java --enable-native-access=org.graalvm.truffle --sun-misc-unsafe-memory-access=allow \
  --module-path "$CP:$TOOLS" -Dpolyglot.cpusampler=true \
  -m scheme.launcher/scheme.launcher.SchemeMain fib.scm
#   ... -Dpolyglot.dap=127.0.0.1:4711 -Dpolyglot.dap.WaitAttached=true -Dpolyglot.dap.Suspend=false
#       then: python3 docs/exploration/dap_client.py 127.0.0.1 4711 $PWD/debugme.scm 3 3
#   ... -Dpolyglot.inspect=9229 -Dpolyglot.inspect.WaitAttached=true -Dpolyglot.inspect.Suspend=false
#       then: python3 docs/exploration/inspect_client.py 127.0.0.1 9229 $PWD/debugme.scm 3
#       (inspect takes a port only; host:port is rejected)
```

`debugme.scm`:

```scheme
(import (scheme base) (scheme write))
(define (square x)
  (let ((y (* x x)))
    y))
(define (sum-squares n)
  (let loop ((i 0) (acc 0))
    (if (= i n)
        acc
        (loop (+ i 1) (+ acc (square i))))))
(display (sum-squares 5))
(newline)
```

The JS demo, run as a single-file Java program with the GraalJS jars added to
the module path, plus `--add-modules org.graalvm.polyglot,scheme,org.graalvm.js,org.graalvm.truffle.runtime`:

```java
try (Context context = Context.newBuilder("scheme", "js").allowPolyglotAccess(PolyglotAccess.ALL).build()) {
    context.getBindings("js").putMember("square", context.eval("scheme", "(lambda (x) (* x x))"));
    context.eval("js", "square(12) + 1");                                    // 145
    context.getBindings("js").putMember("l", context.eval("scheme", "(list 1 2 3)"));
    context.eval("js", "let s = 0; for (const x of l) s += x; s");          // 6
    context.eval("scheme", """
        (import (scheme base) (truffle interop))
        (define each (polyglot-eval "js" "(xs, f) => { for (const x of xs) f(x); return 'done'; }"))
        (call/cc (lambda (k) (foreign-execute each (vector 1 2 3) (lambda (x) (if (= x 2) (k (* x 100)))))))
        """);                                                                // 200: an escape through JS frames
}
```

## Notes for whoever picks this up

- **Design change needed for item 8:** D10 says "Scheme code does not reach
  into other languages or the host in v1". This branch contradicts that on
  purpose (it's an exploration). Merging `(truffle interop)` needs D10
  changed first, in its own PR.
- **Changes to shared runtime code:** `PromptDriver` gains a `barrier` flag,
  `Continuation` gains an `owner`, and `SchemeContext` a `rootRun`, which
  `PromptRootNode` sets. Ordinary programs see no change in behaviour. A
  continuation invoked inside a later program still resumes there, as REPL
  inputs do.
- **Surprises:**
  - `ControlFlowException` passes through host frames intact, which is what
    makes escapes through Java and JS work.
  - Truffle unwraps a `SchemeError` that left a callback when it re-enters
    the guest, so the original object reaches the primitive.
  - The debugger's frame `eval` falls back to a scope lookup for a bare
    identifier, which hides the missing inline parsing until you try an
    expression.
  - `unittest-compile` caught a first version that created the interop-call
    root lazily from `SClosure.execute`, which Truffle forbids in compiled
    code ("do not create a Node from compiled code"). The root is now created
    in `initializeContext`.
  - One full `mx tests` run reported `larceny lazy: 33 normally, 28 under
    torture`. Run on its own, the section passed 33/33 under torture twice,
    and so did the rerun of the step. It looks load-dependent rather than
    caused by this branch. Worth watching.

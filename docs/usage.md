# Using Truffle Scheme

How to build and run this R7RS-small Scheme: the `scheme` launcher and REPL,
GraalVM's debugger and profilers, the native-image launcher, and how it
compares with other implementations. Embedding it in Java is
[embedding.md](embedding.md). Every command here is run by the readme test
(`//tests:readme`, in CI's `tests` step; the native image's by
`//tests:readme_native`), which compares its output with the block after it.

## Status

Both R7RS conformance suites vendored here pass:

- chibi-scheme's R7RS suite (`tests/r7rs/`): **1225 of 1225** tests.
- Larceny's R7RS suite (`tests/r7rs-larceny/`): **2160 of 2166**. The other
  six expect a particular result where R7RS says "it is an error", and we
  signal the error: [the exceptions table](../tests/r7rs-larceny/README.md#exceptions)
  lists each with the R7RS text.

The per-section numbers are the status block at the top of
[docs/plan.md](plan.md), which is generated from the suites, and a test
checks it on every commit. It is for the interpreter; CI also runs both
suites with every function compiled on first call and under both tortures, and the
native launcher passes the same tests (design.md D17). Proper tail calls run in
constant space, deep non-tail recursion spills to the heap instead of
overflowing the Java stack (once it has used most of the thread's stack,
which in the launcher is a large one, so that it stays on the stack, where it
is faster: [Options](#options)), and a
torture mode captures and re-enters a continuation at every non-tail call;
`tests/spec/` holds those cases.

[docs/plan.md](plan.md) tracks the milestones and what is left.

## Quick start

The Nix dev shell provides Bazel, a GraalVM CE 25.5 JDK, Python and
chibi-scheme; Bazel downloads the same GraalVM release and the Maven jars it
builds with (design.md D11). You need Nix with flakes; from a checkout of this
repository:

<!-- readme: run -->
```sh
nix develop
bazel build //:scheme
```

The commands below run inside that shell, from the repository's root.
`bazel-bin/scheme` is the launcher. Each platform builds into a directory of
its own, and `bazel-bin` is the last command's: the tests build for the
executors' Linux, so on a Mac, after running tests, run `bazel build
//:scheme` again (`bazel-bin/scheme` says so), and `bazel build
//:scheme-native` for the native launcher, which rebuild nothing (design.md
D11, "An output directory per platform").

### Run a program

<!-- readme: run -->
```sh
bazel-bin/scheme docs/examples/hello.scm
bazel-bin/scheme docs/examples/hello.scm Ada "and Grace"
```

<!-- readme: output -->
```text
Hello, world! Arguments: ()
Hello, Ada! Arguments: ("Ada" "and Grace")
```

A file runs as an R7RS program (§5.1): its own top level, with exactly what
its `import` brings in. `(command-line)` is the file followed by the
arguments after it:

<!-- readme: file docs/examples/hello.scm -->
```scheme
(import (scheme base) (scheme write) (scheme process-context))

(define args (cdr (command-line)))
(display "Hello, ")
(display (if (null? args) "world" (car args)))
(display "! Arguments: ")
(write args)
(newline)
```

`-I dir` adds a directory to the library path, where `(import (examples greet))`
finds `examples/greet.sld`:

<!-- readme: run -->
```sh
bazel-bin/scheme -I docs/examples/lib docs/examples/greet.scm || echo "exit status $?"
```

<!-- readme: output -->
```text
Hello from a library, Ada!
exit status 3
```

<!-- readme: file docs/examples/greet.scm -->
```scheme
(import (scheme base) (scheme write) (scheme process-context) (examples greet))

(display (greet "Ada"))
(newline)
(exit 3)
```

<!-- readme: file docs/examples/lib/examples/greet.sld -->
```scheme
(define-library (examples greet)
  (export greet)
  (import (scheme base))
  (begin
    (define (greet name)
      (string-append "Hello from a library, " name "!"))))
```

The launcher's exit status is the one the program passes to `exit` or
`emergency-exit` (0 for none or `#t`, the integer for an exact integer from 0
to 255, and 1 for `#f` or anything else, an integer outside 0–255 among
them), 0 when it ends, 70 after an uncaught error (whose message goes to
stderr), 66 if the file can't be read and 64 for a malformed `-I`.

### The REPL

With no file, `bazel-bin/scheme` starts a REPL in the interaction environment, which
imports every standard library. It reads until an input is complete, so a
definition can span lines, and writes the values of each form of an input
with `write`, one per line, as each form completes (a line can hold several).
An error prints `ERROR: ...` on stderr, and the REPL goes on. Scheme code it
runs reads the same input: `(read-line)` reads the line after it. It ends at
end of input (Ctrl-D) or on `(exit)`.

In a terminal, lines can be edited, and the up and down arrows recall earlier
inputs, from a history kept in `~/.scheme_history` (or the file the
`SCHEME_HISTORY` environment variable names; empty keeps none); an input
typed over several lines is one entry. It prompts with `> `, and with two
spaces inside an unfinished datum; Ctrl-C drops the input typed so far.
Ctrl-C while an input runs interrupts it: the REPL prints `Interrupted` and
prompts again, with the definitions made so far and the parameters as they
were before the input (the `dynamic-wind` after-thunks it skips don't run).
If an interrupt hasn't stopped the input after a second, because it waits in
the operating system (opening a FIFO, say), Ctrl-C again ends the REPL with
status 130 (the input goes in the history all the same). Its standard input can be a file:

<!-- readme: run -->
```sh
bazel-bin/scheme
bazel-bin/scheme < docs/examples/session.scm
```

<!-- readme: output -->
```text
144
1
"two"
#\3
#\A
"Hi, Ada"
3
```

<!-- readme: file docs/examples/session.scm -->
```scheme
(define (square x)
  (* x x))
(square 12)
(car '())
(values 1 "two" #\3)
(char-upcase #\a)
(define name (read-line))
Ada
(string-append "Hi, " name) (string-length name)
```

### Options

The launcher is built on GraalVM's launcher framework (design.md D17), so
besides `-I` it takes the standard options: `--version`, `--help` (and
`--help:engine`, `--help:compiler`, `--help:tools`, ...), and engine,
compiler and logging options such as `--engine.TraceCompilation`.

<!-- readme: run -->
```sh
bazel-bin/scheme --version
bazel-bin/scheme --help
bazel-bin/scheme --help:tools
```

<!-- readme: output -->
```text
R7RS Scheme (GraalVM CE ...)
Usage: scheme [option...] [-I dir]... [--stack-size=<size>] [file.scm [arg...]]
...
```

Every option takes effect or stops the launcher with an error that says why;
none is ignored (design.md D17, "Options"). They follow GraalVM's launcher
conventions: its
[default launcher options](https://github.com/oracle/graal/blob/master/truffle/docs/Options.md#default-language-launcher-options)
(*"`--vm.[option]` : Pass options to the host VM"*, *"`--log.[logger].level`"*,
*"`--help:languages` : Print options for all installed languages"*), a
language's options as *"`--<languageID>.<property>=<value>`"* and a tool's
as *"`--<toolID>.<property>=<value>`"*
([Polyglot Programming](https://www.graalvm.org/latest/reference-manual/polyglot-programming/)),
and *"Most options also require the additional `--experimental-options` flag
set"* ([Optimizing](https://github.com/oracle/graal/blob/master/truffle/docs/Optimizing.md#observing-compilations)).
Options come before the program file; everything after it is the program's.
The statuses are GraalVM's launcher framework's: 2 for an option it doesn't
know, 1 for one that can't be used, and 64 (EX_USAGE) for a malformed `-I`,
`--stack-size` or `--end-status`.

| Options | `bazel-bin/scheme` (JVM) | `bazel-bin/scheme-native` |
|---|---|---|
| `--engine.<name>` (`--engine.TraceCompilation`) | Takes effect. Unknown: *"Unrecognized argument"*, status 2; a bad value, or an experimental one without `--experimental-options`: status 1. | The same. |
| VM-wide engine options (`--engine.TraceTransferToInterpreter`) | Takes effect: HotSpot keeps it for the process, so the launcher sets it before the framework's first engine. | Takes effect. |
| `--compiler.<name>` (`--compiler.TracePerformanceWarnings=all`) | Takes effect. `--compiler.InstrumentBoundaries` and `--compiler.InstrumentBranches` need Graal compiled for HotSpot, which `bazel-bin/scheme` then runs (`-XX:-UseJVMCINativeLibrary`); with libgraal they are an error, status 1 (docs/upstream.md). | Takes effect; the instrumentation is an error, status 1. |
| `--scheme.<name>` (`--scheme.BufferOutput=false`; `--help:scheme`) | Takes effect; `--scheme.LibraryPath` comes before the `-I` directories. `--scheme.StackSize`, which the launcher sets: an error naming `--stack-size`, status 1. | The same. |
| The tools (`--cpusampler`, `--inspect`, `--dap`, `--memtracer`, ...) | Take effect. | An error saying the tools are the JVM launcher's, status 2. |
| `--log.file=<path>`, `--log.level=`, `--log.<id>.level=` | Take effect. | The same. |
| `--experimental-options` | Allows the experimental options on the command line. | The same. |
| `--polyglot`, `--jvm`, `--native` | `--polyglot` and `--jvm` are what it does already; `--native`: an error, status 1. | `--polyglot` and `--native` are; `--jvm`: an error, status 1. |
| `--vm.<option>` (`--vm.Xmx2g`) | The JVM's `-<option>`; the JVM rejects one it doesn't know. The launcher run with `java` has no JVM to start: an error naming the `java` option, status 1. | The isolate takes `--vm.Xmx`, `-Xms`, `-Xmn`, `-Xss`, `-D` and `-XX:`, and refuses an `-XX:` one it doesn't know, a collector included (*"Could not find option"*, status 255). Any other (`--vm.ea`, `--vm.Xint`, `--vm.verbose:gc`), which the isolate would ignore, and a class or module path: an error, status 1. |
| `--vm.@<file>`, a file of VM options in `java`'s form (`-Xmx2g`) | `java`'s argument file, `@<file>`: its options take effect or are `java`'s errors, as on the command line, and the launcher's defaults give way to them. | Each option in it takes effect or is an error, as its `--vm.` form is. A file there is none of: *"could not open"*, status 1. |
| `polyglot.<name>` system properties (`--vm.Dpolyglot.engine.TraceCompilation=true`) | Take effect. One Truffle rejects: an error, status 1. | The same. |
| `--help`, `--help:<topic>`, `--version`, `--show-version` | Print, and exit 0 (`--show-version` goes on). A topic there is none of: an error, status 2. | The same. |
| Anything else starting with `-` | *"Unrecognized argument"*, status 2. | The same. |

The launcher evaluates on a thread of its own with a 256 MB Java stack, so
that deep non-tail recursion stays on the stack, where it is fastest,
instead of spilling to the heap (design.md D17). Only the pages a recursion
reaches take memory; the rest is address space. The launcher tells the
runtime the thread's size (the `scheme.StackSize` option), so the runtime
doesn't have to measure the stack, which would touch up to 64 MB of it.
`--stack-size=<size>` sets the thread's stack (`512k`, `64m`, `1g`), and
`--stack-size=default` evaluates on the launcher's own thread, with the
JVM's default stack (1 or 2 MB). A stack size given to the JVM (`--vm.Xss`,
or `-Xss` through `JDK_JAVA_OPTIONS`) is the evaluation's: the launcher's own thread
has it. The tests that check deep recursion run the launcher with
`-Xss256k`. The native launcher takes the same options; a stack size given
to it as `--vm.Xss` is the evaluation's too, and
`--stack-size=default` evaluates on the process's main thread (8 MB,
`ulimit -s`). On the launcher's own thread, whose size the launcher doesn't
know, the runtime measures the stack the first time a recursion is more than
about 50 calls deep.

Standard output is buffered unless stdin and stdout are a terminal, where each
write shows at once (design.md D10). The buffer is written out when the
program reads standard input (so a prompt shows before the program waits for
its answer), writes to standard error (so an error message comes after the
output before it), calls `flush-output-port`, `exit` or `emergency-exit`, and
when it ends. `--scheme.BufferOutput=false` writes each write at once
elsewhere too, for a program whose progress something reads as it runs
through a pipe; `--scheme.BufferOutput=true` buffers on a terminal too.
On a terminal, Ctrl-D ends only the read that meets it: the next read waits
for more input. Elsewhere (a pipe, a file), once a read has returned the end
of standard input, every later read returns it too, and `char-ready?` is `#t`
(R7RS §6.13.2; design.md D10). `--scheme.InteractiveInput` sets which, if the
launcher can't tell (stdin a terminal but stdout redirected).
Output file ports, textual and binary, are always buffered. What they hold is
written out by `flush-output-port` and `close-port`, and also when the
program ends, exits or stops with an error, even if it never closes them.
Input file ports, textual and binary, read their file a buffer (8 KB) at a
time, so reading one byte or character at a time is cheap, and
`read-bytevector`, `read-bytevector!` and `read-string` copy from the buffer.
Every file port the program leaves open, input or output, is closed when the
context closes, or after the program drops it.

As GraalVM's language launchers do, the launcher takes the JVM's own options
as `--vm.<option>`, among its options (before the program file): `--vm.Xmx2g`,
`--vm.XX:+UseG1GC`, `--vm.Dname=value`, `--vm.cp=<path>` (or
`--vm.classpath=<path>`) for the class path, and `--vm.p=<path>` (or
`--vm.-module-path=<path>`), which the launcher appends to its module path
(another language's jars, [Other languages and Java](#other-languages-and-java)). `bazel-bin/scheme` starts the
JVM with each one as `-<option>` (design.md D17, "VM options"). An argument
file, `--vm.@<file>`, holds VM options as `java` takes them (`-Xmx2g
-XX:+UseG1GC`, with `#` comments), and is `java`'s `@<file>`; the native
launcher takes one too, and checks each option in it as it checks its
`--vm.` form (design.md D17, "Options"). After the
program file, `--vm.` arguments are the program's. The launcher run with
`java` (below) has no JVM to start: it rejects a `--vm.` option, saying to
give it to `java` itself. `JDK_JAVA_OPTIONS` works too.

<!-- readme: run -->
```sh
bazel-bin/scheme --vm.Xmx512m --vm.XX:+UseG1GC docs/examples/hello.scm
```

<!-- readme: output -->
```text
Hello, world! Arguments: ()
```

`bazel-bin/scheme` starts its JVM with the serial collector
(`-XX:+UseSerialGC`, design.md D17), which ran most benchmarks faster than the
JVM's default, G1, in less memory; a collector chosen with `--vm.` or in
`JDK_JAVA_OPTIONS` (`--vm.XX:+UseG1GC`) replaces it. On a machine with 16
processors or more it also starts the JVM with 4 compiler threads for the
host's Java (`-XX:CICompilerCount=4`, where the JVM would start 12 or more),
which start up faster and in less memory; a count given with `--vm.` or in
`JDK_JAVA_OPTIONS` replaces it.

`bazel build //:scheme` also trains an AOT cache for `bazel-bin/scheme`
(JDK 25's `-XX:AOTCache`: the classes the launcher and the language load as
they start, already parsed and linked), with which a hello-world program
starts in about half the time (design.md D17). The cache names the real paths
of the jars in Bazel's output tree, so it is only for the `bazel-bin/scheme`
of the machine that built it, and the launcher leaves it out when those files
have changed since (rebuild to train it again), and when the JVM options
choose class sharing themselves (`-Xshare:off`, `-XX:AOTCache=...`).

The JVM can't use the cache with some options: compact object headers
(`--vm.XX:+UseCompactObjectHeaders`), uncompressed pointers (and so a heap
over 32 GB), ZGC, module options such as `--add-opens`, an agent, and many
more. With JVM options of yours (as `--vm.` options, or in `JDK_JAVA_OPTIONS`
or `JAVA_TOOL_OPTIONS`), the launcher first asks the JVM whether it takes the
cache with them, by starting it once without running anything (about 45 ms
when it does, 25 when it doesn't), and leaves the cache out if it doesn't: the
options take effect, the program starts without the cache (a hello-world
program in about 320-360 ms instead of 130), and nothing is printed about it.
System properties other than the JDK's (`--vm.Dname=value`,
`--vm.Dpolyglot.…`), `--vm.Xss`, `--vm.Xlog:` and the serial, parallel and G1
collectors need no asking (design.md D17, "Which JVM options it takes"). The
launcher passes the cache with `-XX:AOTMode=on`, so were the JVM to refuse it
after all, it would stop with *"Unable to use AOT cache."* and the reason,
rather than start slowly; `--vm.Xshare:auto` starts it without the cache.

`bazel-bin/scheme` runs the launcher in a JVM with the tools below on its
module path. Without them, it is a `java` command on the jars that
`bazel build //:scheme_jars` puts in `bazel-bin/scheme_jars/`:

<!-- readme: run -->
```sh
bazel build //:scheme_jars
java -p bazel-bin/scheme_jars --enable-native-access=org.graalvm.truffle,org.graalvm.shadowed.jline --sun-misc-unsafe-memory-access=allow -m scheme.launcher/scheme.launcher.SchemeMain docs/examples/hello.scm
```

<!-- readme: output -->
```text
Hello, world! Arguments: ()
```

## Other languages and Java

A program that imports `(graalvm polyglot)` can evaluate other GraalVM
languages, share the polyglot bindings with them, call their functions and
Java's methods, and read foreign arrays, objects and maps (design.md D20;
[embedding.md](embedding.md#scheme-calling-other-languages-and-java) lists
the procedures). A program that doesn't import it sees none of it. The
launcher allows all of it, as GraalVM's own launchers do. Java's classes are
there. Of GraalVM's languages, only Scheme is on the launcher's module path:
add another language's jars to it with `--vm.p=<path>` (or
`--vm.-module-path=<path>`), as GraalVM's language launchers take them on
the JVM, and `polyglot-eval` reaches that language too. The native launcher
is Scheme alone, as GraalVM's native standalones are ([Native
image](#native-image); design.md D20, "The launchers"):

<!-- readme: run -->
```sh
bazel-bin/scheme docs/examples/polyglot.scm
```

<!-- readme: output -->
```text
#t #f
#t 2 (1 "two")
7
```

<!-- readme: file docs/examples/polyglot.scm -->
```scheme
(import (scheme base) (scheme write) (graalvm polyglot))

(define (show . items)
  (for-each (lambda (x) (write x) (display " ")) items)
  (newline))

;; Only Scheme is on the launcher's module path.
(show (polyglot-language-available? "scheme") (polyglot-language-available? "js"))

;; A Java class: its constructor, its methods, and what comes back.
(guard (e ((error-object? e) (show 'no-java (error-object-message e))))
  (let ((list (foreign-new (java-type "java.util.ArrayList"))))
    (foreign-invoke list 'add 1)
    (foreign-invoke list 'add "two")
    (show (foreign? list) (foreign-length list) (foreign->list list))
    (show (foreign-invoke (java-type "java.lang.Math") 'max 3 7))))
```

A foreign function is a procedure; a foreign array or list is not a vector
or a list (`foreign->list` copies it); a foreign exception is raised as
itself, which `guard` catches. A continuation can't cross another language's
frames: that is a Scheme error (design.md D20).

## Debugging and profiling

GraalVM's tools work on Scheme programs: breakpoints, stepping, the stack
(including frames that deep recursion has spilled to the heap), Scheme's
variables in each frame's scope, and evaluating an expression in a paused
frame (in a program's frame, with the program's own imports and
definitions). Their jars are optional (`//bazel:tools_modules`), and
`bazel-bin/scheme` puts them on the module path. CI drives each of them
against a Scheme program on every pull request (`tests/harness/tools.py`).

The CPU sampler prints where the time went when the program ends:

<!-- readme: run -->
```sh
bazel-bin/scheme --cpusampler docs/examples/fib.scm
```

<!-- readme: output -->
```text
196418
...Sampling Histogram...
 Name ...|| Location
...
 fib ...|| docs/examples/fib.scm~3-6:...
...
```

`--cpusampler=calltree` prints a call tree instead,
`--cpusampler=flamegraph` writes `flamegraph.svg`, and
`--cpusampler.Output=json` writes JSON. The CPU tracer counts how many times
each procedure was entered (`--cputracer.TraceStatements` and
`--cputracer.TraceCalls` count statements and calls too):

<!-- readme: run -->
```sh
bazel-bin/scheme --cputracer docs/examples/fib.scm
```

<!-- readme: output -->
```text
196418
...Tracing Histogram. Counted a total of 635622 element executions...
 fib ...|        635621 100.0% |...| docs/examples/fib.scm~3-6:39-115
...
```

The coverage tool prints what share of each file's statements, lines and
procedures ran:

<!-- readme: run -->
```sh
bazel-bin/scheme --coverage docs/examples/fib.scm
```

<!-- readme: output -->
```text
196418
...Code coverage histogram...
 Path ...|  Statements |    Lines |    Roots
...
 ...docs/examples/fib.scm |     100.00% |  100.00% |  100.00%
...
```

`--coverage.Output=detailed` also prints each line, marked covered or not,
`--coverage.Output=json` and `--coverage.Output=lcov` write JSON and LCOV
(for editors and coverage services), `--coverage.Count` counts how many
times each statement and procedure ran, and `--coverage.OutputFile` writes
the output to a file instead of standard output.

The memory tracer, which is
experimental, counts the Scheme objects each procedure allocates (pairs,
vectors, strings, closures, records, bignums, ...):

<!-- readme: run -->
```sh
bazel-bin/scheme --experimental-options --memtracer docs/examples/fib.scm
```

<!-- readme: output -->
```text
196418
...Location Histogram with Allocation Counts...
```

The Chrome DevTools inspector waits for a debugger, then pauses at the
program's first line. Open the `devtools://` URL it prints in Chrome
([GraalVM's guide](https://www.graalvm.org/latest/tools/chrome-debugger/)):

<!-- readme: run server -->
```sh
bazel-bin/scheme --inspect --inspect.WaitAttached docs/examples/fib.scm
```

<!-- readme: output -->
```text
Debugger listening on ws://127.0.0.1:9229/...
For help, see: https://www.graalvm.org/tools/chrome-debugger
E.g. in Chrome open: devtools://devtools/bundled/js_app.html?ws=127.0.0.1:9229/...
```

The Debug Adapter Protocol server waits for a client on port 4711; attach an
editor's DAP client to it
([GraalVM's guide](https://www.graalvm.org/latest/tools/dap/) sets up VS Code):

<!-- readme: run server -->
```sh
bazel-bin/scheme --dap docs/examples/fib.scm
```

<!-- readme: output -->
```text
[Graal DAP] Starting server and listening on localhost/127.0.0.1:4711
```

`--inspect=PORT` and `--dap=HOST:PORT` choose another port, as CI's smoke
tests do; `--help:tools` lists every tool option.

### What each tool sees

GraalVM 25.5's tools, run against programs with tail calls, `call/cc`
escapes and re-entries, recursion deep enough to spill frames to the heap,
macros (from the program, a library, and the standard libraries), procedures
of the standard libraries calling back, and `eval` (design.md D13, "Tools",
says how the language tags its code for them). The native launcher has none
of them (#219): they run with `bazel-bin/scheme`.

| Tool | Rating | What it shows, and where it falls short |
|---|---|---|
| Debugger: `--dap`, `--inspect`, the Truffle Debugger API | Works; wrong across spills | Line breakpoints; step into, over and out, stopping at each form of a body, a `let`'s body and each top-level form; the stack, frames spilled to the heap included (as asynchronous frames); each frame's variables; evaluation in a frame. Code from a macro stops at the macro's use; the standard libraries' procedures are internal, so stepping passes through them; `eval`'s code stops at the call of `eval`. A step over a call during which its frame was spilled to the heap stops at the next statement, as for any call, and so does a step over or out across a continuation's escape (calling `k`, which abandons the frames up to its `call/cc`): after an escape-only `k`, at the statement after the `call/cc`; after a full continuation, at the statement of the frame it resumes. Wrong: a breakpoint on a statement whose frame was spilled during it stops again when the frame comes back; a step out of (or over the end of) a frame that came back from the heap stops in `<prompt>`, which resumed it, not in its caller, and from there only a step into goes on in the caller (a step over or out runs to the end). Both are Truffle's (docs/upstream.md). |
| CPU sampler: `--cpusampler` (histogram, call tree, JSON, flame graph) | Works | Procedures by name and location, macro-made lambdas at the macro's use, and every frame of a recursion spilled to the heap. Contified loops (a named `let` that only loops) are their procedure's time. |
| CPU tracer: `--cputracer` | Works; counts resumptions | Exact entries of each procedure, and of each statement and call with `TraceStatements` and `TraceCalls`. A frame that is resumed (after a spill, or each time a continuation re-enters it) counts as another entry of its procedure, and of the statement it was in: a recursion 301 deep, spilled past 50, counts 555 of each. That is the tracer's (docs/upstream.md, "Tools take a resumption for an entry"). A self tail call that loops, and a contified loop, enter once. |
| Memory tracer: `--memtracer` (experimental) | Works | Pairs, vectors, strings, closures, records, bignums and bytevectors by the procedure that allocated them. |
| Heap monitor: `--heapmonitor` (experimental) | Works | No output of its own; it feeds the heap summary other tools read. |
| Coverage: `--coverage` (statement, line and root coverage; histogram, detailed, JSON, LCOV) | Works; counts resumptions | Each file's statements, lines and procedures (roots, the program's own among them), covered or not, in every output. A statement is what the debugger stops at (each form of a body, a `let`'s body, each top-level form, a definition at its name), so a branch of an `if` or a `cond` with one expression is part of the statement around it: its lines are covered when that statement runs, taken or not. The standard libraries' procedures are internal, so only the program's files and libraries show. With `--coverage.Count`, a procedure's count and its statement's include its resumptions, as the CPU tracer's do (docs/upstream.md, "Tools take a resumption for an entry"). |
| Insight: `--insight` | Not shipped; unusable from Scheme | `org.graalvm.tools:insight-tool`. Its scripts are JavaScript, Python, Ruby, or any language that can call the `insight` object's `on` with a function: GraalVM CE has no JavaScript here, and Scheme would need two things it lacks, `insight` bound as a script's argument (Truffle passes it as a named argument of the script's parse, which the language ignores) and calling a foreign object's members (the `(graalvm polyglot)` library, #237). |
| Heap dump: `--heap.dump` | Not shipped | `org.graalvm.tools:insight-heap-tool`, driven by an Insight script's `heap.dump(...)`: what Insight needs, then this. |

### Logging

The language logs what it does rarely through Truffle's logging, at level
`FINE`, so `--log.scheme.level=FINE` shows all of it and
`--log.scheme.<logger>.level=FINE` one logger's:

| Logger | What it logs |
|---|---|
| `scheme.spill` | each time a deep recursion moves frames to the heap, and the stack measurement that sets when it does (design.md "Stack-depth spill") |
| `scheme.continuations` | each continuation captured (`call/cc`, a composable one up to its prompt), re-entered in place of the current one, or reinstated |
| `scheme.prelude` | whether the standard libraries were restored from the prelude image or expanded, and how long it took (design.md D13) |
| `scheme.library` | each library loaded, from which file and how long it took, and a library not found, with where it was looked for |

Each line names its logger (`[scheme::spill] FINE: ...`). Logs go to
standard error, or to the file `--log.file=<path>` names. With no level set,
the loggers cost nothing: the check folds away in compiled code.

<!-- readme: run -->
```sh
bazel-bin/scheme --log.scheme.spill.level=FINE docs/examples/deep.scm 2>&1
```

<!-- readme: output -->
```text
...[scheme::spill] FINE: the stack of thread ... is ... bytes (scheme.StackSize), so recursion on it spills past ... bytes
...[scheme::spill] FINE: spill 1: ... frames moved to the heap (the limit: ... bytes of stack)
...1000000
```

An embedder sets the same options on the context's builder,
`.option("log.scheme.level", "FINE")`, and can give it a `java.util.logging`
`Handler` or a stream for them (`Context.Builder.logHandler`), or a file
(`.option("log.file", path)`).

## Native image

`bazel build //:scheme-native` builds the launcher as a native executable,
with the pinned GraalVM's `native-image` and Bazel's C++ toolchain for this
machine: Xcode's on a Mac, where it builds locally even with remote execution
on (design.md D11, D17). As in GraalVM's language standalones, it is two files:
`bazel-bin/scheme-native`, GraalVM's small native launcher, and beside it
the language as a shared library (`libschemevm.dylib`, or `.so` on Linux),
which the launcher finds next to its real path, so the two go together.
It's built only when named:

<!-- readme: run native -->
```sh
bazel build //:scheme-native
```

The executable keeps the Graal JIT, and starts in milliseconds: its first
context was made when the image was built, with the standard libraries
loaded, and the run takes it over (design.md D17):

<!-- readme: run native -->
```sh
bazel-bin/scheme-native docs/examples/hello.scm
```

<!-- readme: output -->
```text
Hello, world! Arguments: ()
```

The image has Scheme alone, as GraalVM's native standalones do, and no
reflection metadata for Java classes, which a class's constructors, methods
and fields need in a native image. So `(graalvm polyglot)` ([Other languages
and Java](#other-languages-and-java)) finds no other language there, and
`java-type` is an error that says why (design.md D20, "The launchers"); an
embedder's own native image has the classes it gives metadata for:

<!-- readme: run native -->
```sh
bazel-bin/scheme-native docs/examples/polyglot.scm
```

<!-- readme: output -->
```text
#t #f
no-java "java-type: this native image has no reflection metadata for the class (reachability-metadata.json), so it has none of its constructors, methods or fields"
```

On an Apple M3 Max (from design.md D17: native startup is the median of 41 runs,
the rest of 10; the JVM column is the `java` command `bazel-bin/scheme` runs):

| | Native | JVM |
|---|---:|---:|
| `scheme --version` | 10 ms, 15 MB | 228 ms, 251 MB |
| A hello-world program | 11 ms, 19 MB | 614 ms, 326 MB |
| ecraven's `fib`, steady iteration | 12.4 s | 13.3 s |
| ecraven's `tak`, steady iteration | 4.7 s | 6.0 s |

The build takes 50 to 90 s and about 3 GB of memory on that machine; the
launcher is 0.1 MB and the library 55 MB. It has no tools (they need the JVM launcher), and it runs
on the platform it was built for: this machine by default (design.md D11). CI doesn't build it: a change to its
configuration is checked by the tests `//tests:readme_native` (this section's
commands), `//tests:conformance_native` (both conformance suites through
the executable), `//tests:space_native`, `//tests:launcher_vm_native`,
`//tests:compile_bound_native` (its build fails past its bound on
runtime-compiled methods) and `//tests:polyglot_native` (`java-type` in an
image built with reflection metadata), which are run only when named.

The executable sizes its heap as native-image does by default: its young
generation starts at 48 MB, and grows as a program allocates. A program whose
first run is a deep recursion collects often then, and each collection scans
its whole stack (design.md D17). For such programs, a minimum heap of 1 GB
starts the young generation at 384 MB: pass `--vm.Xms1g` (or
`--vm.XX:MinHeapSize=1g`) on the executable's command line, before the program.
A 1,000,000-deep recursion's first run then takes about 0.6 s instead of 1.7,
but a program that allocates more than 384 MB peaks with them in use
(ecraven's `divrec` at 480 MB instead of 165), and a maximum below it
(`--vm.Xmx512m`) needs a minimum no larger than it.

The executable takes the VM's options as `--vm.<option>`, before the program
file, as GraalVM's native launchers do (design.md D17, "VM options"): it
creates the library's VM with them as `-<option>`, so the heap's sizes, the
collector's options and `--vm.D<name>=<value>` all take effect as it starts.
A `-Xmx2g`, `-XX:...` or `-D...` before the program file is an error that
names its `--vm.` form. Every argument after the program file is the
program's, as given: `bazel-bin/scheme-native prog.scm a -Xmx3g -Dx=y b`
gives the program `("prog.scm" "a" "-Xmx3g" "-Dx=y" "b")` as its
`command-line`. A `--vm.` argument there is the program's too, and costs a
second start (the launcher runs itself again without it). Its collector is
chosen when it is built (below), and an option that names one
(`--vm.XX:+UseG1GC`) stops it with `Could not find option 'UseG1GC'`, the
native image's own message, before the launcher runs (design.md D17, "VM
options"); `--vm.cp` is an error too: it has no class path. So is an option
the native image would take and ignore, such as `--vm.ea`, `--vm.Xint` or
`--vm.verbose:gc` (its GC log is `--vm.XX:+PrintGC` or `--vm.XX:+VerboseGC`).

### Choosing a garbage collector

The executable's collector is chosen when it is built, by native-image's
`--gc=` option, which the build flag `--//:native_gc` sets (design.md D17,
"The native image's collector"):

| `--//:native_gc=` | Built with | |
|---|---|---|
| `serial` (the default) | GraalVM CE or Oracle GraalVM | Generational, on one thread. The maximum heap is 80% of the machine's memory unless `--vm.Xmx` says otherwise. |
| `epsilon` | GraalVM CE or Oracle GraalVM | Never collects. For programs that allocate little and end soon: one that allocates more than the maximum heap ends with an `OutOfMemoryError`. |
| `G1` | Oracle GraalVM only | HotSpot's G1, with its own threads. Its default maximum heap is 25% of memory. |

<!-- readme: run native -->
```sh
bazel build --//:native_gc=epsilon //:scheme-native
bazel build --//:native_graalvm=oracle --//:native_gc=G1 //:scheme-native
```

`--//:native_graalvm=oracle` builds the executable with Oracle GraalVM's
native-image, at the same version as the pinned GraalVM CE
(`bazel/graalvm.bzl`), which Bazel downloads the first time it is asked for
(about 370 MB); everything else still builds and runs on GraalVM CE. Oracle
GraalVM is free to use and to redistribute unmodified, at no charge, under the
GraalVM Free Terms and Conditions (its `LICENSE.txt`). GraalVM CE's
native-image has no G1, so `--//:native_gc=G1` without
`--//:native_graalvm=oracle` fails the build, saying so. GraalVM's
documentation lists G1 for Linux on x86-64 and AArch64; the 25.5 builder also
builds it for macOS on AArch64, and the native tests pass with it on Linux
x86-64.

The collector's run-time options are the executable's `--vm.` options,
before the program: `--vm.Xmx512m`, `--vm.Xms`, `--vm.Xmn`,
`--vm.XX:+PrintGC`, and G1's (`--vm.XX:MaxGCPauseMillis=`, ...) in a G1
executable. `--vm.XX:PrintFlags=` lists the ones an executable takes.

<!-- readme: run native -->
```sh
bazel-bin/scheme-native --vm.Xmx512m docs/examples/hello.scm
bazel-bin/scheme-native --vm.Xms1g --vm.XX:InitialCollectionPolicy=BySpaceAndTime docs/examples/hello.scm
```

<!-- readme: output -->
```text
Hello, world! Arguments: ()
Hello, world! Arguments: ()
```

The serial collector's default policy suits programs that keep little alive,
and lets programs that keep hundreds of MB alive spend most of their time
collecting (`earley`, with 1.2 GB live, three quarters). Two other policies,
`--vm.XX:InitialCollectionPolicy=<name>` on the executable's command line, trade
differently (design.md D17 has the measurements):

- `BySpaceAndTime` keeps a fixed young generation (a tenth of the maximum
  heap, at most 256 MB). It is the flag to try for a program that allocates
  and keeps a lot: ecraven's `paraffins` and `mperm` ran in half the time and
  `earley` in two thirds, and once compiled `paraffins` 2.2 times as fast,
  `sboyer` 1.6 times, and `earley` at `--vm.Xmx2g` 2.6 times. But a program
  that allocates a few hundred MB peaks about 200 MB higher, and `nboyer` was
  10 to 35% slower, so it isn't the default.
- `Proportionate` sizes the young generation as HotSpot's serial collector
  does: `earley` and `nboyer` peaked at 1.4 GB and 0.7 GB instead of 3.7 and
  1.7, but deep recursions and `nboyer` were 20 to 60% slower.

For programs with a large live heap, G1 (above) was the fastest: `earley`
in 9.8 s instead of 22.7 (its later iterations as fast as on the JVM), but
it grows the heap freely up to its maximum, so give it one (`--vm.Xmx2g`: 9.8 s
in 2.2 GB).

The JVM launcher, `bazel-bin/scheme`, runs on whatever collector its JVM is
given: it starts the JVM with the serial one unless a `--vm.` option or
`JDK_JAVA_OPTIONS` (or `JAVA_TOOL_OPTIONS`) chooses another, such as
`--vm.XX:+UseG1GC`, `--vm.XX:+UseParallelGC` or `--vm.XX:+UseZGC` (design.md
D17, "The collector").

## Performance

[docs/benchmarks.md](benchmarks.md) compares this implementation, on the JVM
and as the native launcher, with Chez, Racket, Chicken, Guile, Gauche and
chibi on [ecraven's R7RS benchmarks](https://ecraven.github.io/r7rs-benchmarks/),
57 programs, run as ecraven's script runs them. The committed report was
measured on 2026-11-17, on the final M9 code (#197 and the thresholds
re-measured on it), on an Apple M3 Max shared with other agents' builds and
benchmarks (a 1-minute load average of 3.6 at the start, 13 at most, 3.8 at
the end; its own note says "differences under 2x are within the noise"), at a
tenth of each program's count. Its summary, the geometric mean of each
implementation's time relative to the fastest, over the programs it passed:

| Implementation | Passed | Geometric mean (× fastest) | Median peak memory |
|---|--:|--:|--:|
| Chez | 57/57 | 1.20 | 50 MB |
| Ours, JIT, steady state | 57/57 | 1.82 | |
| Racket | 57/57 | 2.08 | 150 MB |
| Ours, native, steady state | 57/57 | 2.28 | |
| Guile | 56/57 | 3.29 | 120 MB |
| Ours, native, first run | 57/57 | 3.38 | 182 MB |
| Ours, native, first run with startup | 57/57 | 3.76 | |
| Chicken | 56/57 | 4.14 | 24 MB |
| Ours, JIT, first run | 57/57 | 4.27 | 955 MB |
| Gauche | 57/57 | 11.77 | 44 MB |
| Ours, interpreter | 54/57 | 46.93 | 708 MB |
| Chibi | 52/57 | 64.26 | 9 MB |

"First run" is the number comparable with the others': one run in a fresh
process, startup excluded, as ecraven's tables report. "Steady state" is the
best later iteration in the same process, once the JIT has compiled the
program. The native launcher ([Native image](#native-image)) keeps the Graal
JIT, so it is measured the same way; "with startup" adds its startup and exit
to its first run, which the others' numbers leave out. Its first run is faster
than the JVM's, since its interpreter was compiled ahead of time where the
JVM's starts out interpreted itself, but its steady state is slower, most of
all on `earley`, which keeps a gigabyte alive (8.6 s against 3.0). The
interpreter's 3 misses are runs stopped at the report's 60 s limit for it
(`takl`, `ntakl`, `cpstak`). Our JVM's peak memory is mostly the JVM's own: the
programs' median live heap is 7 MB (the report's Peak memory table gives each
program's).
[bench/README.md](../bench/README.md) explains how to run the benchmarks and
what is measured, and its "Thresholds" section how `bench-check` checks a
subset of them against `bench/thresholds.json`.

## Embedding in Java

See [embedding.md](embedding.md).

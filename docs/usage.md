# Using Truffle Scheme

How to build and run this R7RS-small Scheme: the `scheme` launcher and REPL,
GraalVM's debugger and profilers, the native-image launcher, and how it
compares with other implementations. Embedding it in Java is
[embedding.md](embedding.md). Every command here is run by CI (the readme
check in the Tests job), which compares its output with the block after it.

## Status

Both R7RS conformance suites vendored here pass:

- chibi-scheme's R7RS suite (`tests/r7rs/`): **1225 of 1225** tests.
- Larceny's R7RS suite (`tests/r7rs-larceny/`): **2162 of 2166**. The other
  four expect a particular result where R7RS says "it is an error", and we
  signal the error: [the exceptions table](../tests/r7rs-larceny/README.md#exceptions)
  lists each with the R7RS text.

The per-section numbers are the status block at the top of
[docs/plan.md](plan.md), which CI regenerates on every pull request and
fails if it is stale. It is for the interpreter; CI also runs both suites
with every function compiled on first call (non-blocking), and the native
launcher passes the same tests (design.md D17). Proper tail calls run in
constant space, deep non-tail recursion spills to the heap instead of
overflowing the Java stack, and a torture mode captures and re-enters a
continuation at every non-tail call; `tests/spec/` holds those cases.

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
`bazel-bin/scheme` is the launcher.

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
`emergency-exit`, 0 when it ends, 70 after an uncaught error (whose message
goes to stderr), 66 if the file can't be read and 64 for a malformed `-I`.

### The REPL

With no file, `bazel-bin/scheme` starts a REPL in the interaction environment, which
imports every standard library. It reads until an input is complete, so a
definition can span lines, and writes the values of each form of an input
with `write`, one per line, as each form completes (a line can hold several).
An error prints `ERROR: ...` on stderr, and the REPL goes on. Scheme code it
runs reads the same input: `(read-line)` reads the line after it. It ends at
end of input (Ctrl-D) or on `(exit)`.

In a terminal, lines can be edited, and the up and down arrows recall earlier
ones, from a history kept in `~/.scheme_history` (or the file the
`SCHEME_HISTORY` environment variable names; empty keeps none). It prompts
with `> `, and with two spaces inside an unfinished datum; Ctrl-C drops the
input typed so far. Its standard input can be a file:

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
Usage: scheme [option...] [-I dir]... [file.scm [arg...]]
...
```

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

`--cpusampler=calltree` prints a call tree instead, and
`--cpusampler.Output=json` writes JSON. The memory tracer, which is
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

## Native image

`bazel build //:scheme-native` builds the launcher as a native executable,
with the pinned GraalVM's `native-image` and the host's C compiler (design.md
D17). It's built only when named:

<!-- readme: run native -->
```sh
bazel build //:scheme-native
```

The executable keeps the Graal JIT, and starts in milliseconds:

<!-- readme: run native -->
```sh
bazel-bin/scheme-native docs/examples/hello.scm
```

<!-- readme: output -->
```text
Hello, world! Arguments: ()
```

On an Apple M3 Max (median of 10 runs, from design.md D17; the JVM column is
the `java` command `bazel-bin/scheme` runs):

| | Native | JVM |
|---|---:|---:|
| `scheme --version` | 15 ms, 15 MB | 228 ms, 251 MB |
| A hello-world program | 20 ms, 27 MB | 614 ms, 326 MB |
| ecraven's `fib`, steady iteration | 12.4 s | 13.3 s |
| ecraven's `tak`, steady iteration | 4.7 s | 6.0 s |

The build takes 50 to 90 s and about 3 GB of memory on that machine, and the
executable is 47 MB. It has no tools (they need the JVM launcher), and it runs
on the platform that built it. CI doesn't build it: a change to its
configuration is checked by the tests `//tests:readme_native` (this section's
commands) and `//tests:conformance_native` (both conformance suites through
the executable), which are run only when named.

## Performance

[docs/benchmarks.md](benchmarks.md) compares this implementation with
Chez, Racket, Chicken, Guile, Gauche and chibi on
[ecraven's R7RS benchmarks](https://ecraven.github.io/r7rs-benchmarks/), 57
programs, run as ecraven's script runs them. The committed report was
measured on 2026-11-16, at commit `33ab4a03b110` (the final M7 code), on an
Apple M3 Max shared with other jobs (its own note: "differences under 2x are
within the noise"), at a tenth of each program's count. Its summary, the
geometric mean of each implementation's time relative to the fastest, over
the programs it passed:

| Implementation | Passed | Geometric mean (× fastest) | Median peak memory |
|---|--:|--:|--:|
| Chez | 57/57 | 1.10 | 50 MB |
| Racket | 57/57 | 1.92 | 150 MB |
| Ours, JIT, steady state | 57/57 | 2.34 | |
| Guile | 56/57 | 3.02 | 120 MB |
| Chicken | 56/57 | 3.82 | 24 MB |
| Ours, JIT, first run | 57/57 | 5.58 | 1628 MB |
| Gauche | 57/57 | 10.66 | 44 MB |
| Ours, interpreter | 52/57 | 49.59 | 1138 MB |
| Chibi | 52/57 | 58.58 | 9 MB |

"First run" is the number comparable with the others': one run in a fresh
JVM, startup excluded, as ecraven's tables report. "Steady state" is the best
later iteration in the same JVM, once the JIT has compiled the program. The
interpreter's 5 misses are runs stopped at the report's 60 s limit for it.
[bench/README.md](../bench/README.md) explains how to run the benchmarks and
what is measured, and its "Thresholds" section how `bench-check` checks a
subset of them against `bench/thresholds.json`.

## Embedding in Java

See [embedding.md](embedding.md).

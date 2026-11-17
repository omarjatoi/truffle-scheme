# Embedding Scheme in Java

The language is a Truffle language with the id `scheme`, so a Java program
runs it through the GraalVM Polyglot API (`org.graalvm.polyglot`), like any
other GraalVM language. Design.md D10 specifies how values cross between Java
and Scheme; this guide shows it from the Java side.

Every Java sample below is a region of
[`tests/scheme/test/EmbeddingGuideTest.java`](../tests/scheme/test/EmbeddingGuideTest.java),
which the unit tests run, and CI's readme check fails if a sample here
and its region differ. [`EmbeddingTest.java`](../tests/scheme/test/EmbeddingTest.java)
covers the same API in more depth.

## The jars

Nothing is published to Maven: `bazel build //:scheme_jars` puts the
language's jar (`scheme.jar`), the launcher's, and Truffle's jars in
`bazel-bin/scheme_jars/`, a directory for the module path. Truffle's jars are
Maven Central's, at exactly the dev shell's GraalVM version, 25.5.5.0.3
(design.md D11): `org.graalvm.polyglot:polyglot`, `org.graalvm.truffle`'s
`truffle-api`, `truffle-runtime` and `truffle-compiler`, and the
`org.graalvm.sdk` jars they use (`maven_install.json` locks each with its
digest). `truffle-runtime` is Truffle's optimizing runtime: without it the
engine only interprets, and says so on stderr. The JIT is the Graal compiler
of the GraalVM JDK the program runs on.

<!-- readme: file docs/examples/Hello.java -->
```java
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;

public class Hello {
    public static void main(String[] args) {
        try (Context context = Context.create("scheme")) {
            context.eval("scheme", "(define (greet name) (string-append \"Hello, \" name \"!\"))");
            Value greet = context.getBindings("scheme").getMember("greet");
            System.out.println(greet.execute("Java").asString());

            Value squares = context.eval("scheme", "(map (lambda (x) (* x x)) '(1 2 3))");
            System.out.println(squares);
        }
    }
}
```

<!-- readme: run -->
```sh
bazel build //:scheme_jars
java -p bazel-bin/scheme_jars --add-modules ALL-MODULE-PATH --enable-native-access=org.graalvm.truffle --sun-misc-unsafe-memory-access=allow docs/examples/Hello.java
```

<!-- readme: output -->
```text
Hello, Java!
(1 4 9)
```

`--add-modules ALL-MODULE-PATH` resolves the language's module (`scheme`),
which nothing `requires`: the polyglot API finds it as a service. It exports no
API: a program talks to Scheme through the polyglot API alone (design.md D11,
"Modules"). The two other
flags are the ones the launcher's JVM runs with (tests/harness/harness.py): Truffle uses
restricted native access and `sun.misc.Unsafe` on JDK 25.

## Contexts and evaluation

`Context.create("scheme")` makes a context; close it when you're done. Text
that doesn't start with `import` runs in the interaction environment, which
imports every standard library and persists across `eval`s, as at the REPL
(design.md D15). `eval` returns the value of the text's last expression.

<!-- readme: file tests/scheme/test/EmbeddingGuideTest.java#eval -->
```java
try (Context context = Context.create("scheme")) {
    Value three = context.eval("scheme", "(+ 1 2)");
    assertEquals(3, three.asInt());

    // Each eval runs in the interaction environment, which persists, as at the REPL.
    context.eval("scheme", "(define (square x) (* x x))");
    Value square = context.eval("scheme", "square");
    assertEquals(144, square.execute(12).asInt());
    assertEquals(6.25, square.execute(2.5).asDouble(), 0);
}
```

A context is single-threaded (D10): use it from one thread at a time.
Scheme's ports write to the context's output and error streams, which are the
process's unless the builder sets them:

<!-- readme: file tests/scheme/test/EmbeddingGuideTest.java#output -->
```java
ByteArrayOutputStream out = new ByteArrayOutputStream();
try (Context context = Context.newBuilder("scheme").out(out).build()) {
    context.eval("scheme", "(display \"hello\") (newline)");
}
assertEquals("hello\n", out.toString(StandardCharsets.UTF_8));
```

## Programs, libraries and exit

A source whose text starts with `import` is an R7RS program: it gets its own
top level with exactly the bindings it imports (D15), as when the launcher runs
a file. Libraries are found on the `scheme.LibraryPath` option (directories
separated by the path separator), which the launcher's `-I` adds to; reading
them, like any file access from Scheme, needs `allowIO`. (The launcher also
inherits the process's environment variables, for `get-environment-variable`,
with `allowEnvironmentAccess(EnvironmentAccess.INHERIT)`.) `exit` surfaces as a
`PolyglotException` whose `isExit()` is true, after the program's
`dynamic-wind` after-thunks have run (D9):

<!-- readme: file tests/scheme/test/EmbeddingGuideTest.java#program -->
```java
ByteArrayOutputStream out = new ByteArrayOutputStream();
Source program = Source.newBuilder("scheme", file.toFile()).build();
Context.Builder builder = Context.newBuilder("scheme").out(out);
builder.allowIO(IOAccess.ALL); // to read the libraries
builder.option("scheme.LibraryPath", libraries.toString()); // what -I adds to
try (Context context = builder.build()) {
    context.eval(program);
    fail("the program calls exit");
} catch (PolyglotException e) {
    assertTrue(e.isExit());
    assertEquals(3, e.getExitStatus());
}
assertEquals("Hello from a library, Ada!\n", out.toString(StandardCharsets.UTF_8));
```

## Options and engines

The language's options, which `Language.getOptions()` describes (and the
launcher's `--help:languages`):

| Option | Default | |
|---|---|---|
| `scheme.LibraryPath` | none | Directories to search for libraries, separated by the path separator. |
| `scheme.BufferOutput` | `true` | Buffer standard output; it is written when Scheme reads standard input, writes to standard error, flushes or exits, and when control returns to Java (design.md D10). |
| `scheme.StackSize` (expert) | `0` | The Java stack size of the thread the context is created on, when you know it (below). |

(`scheme.PreludeImage` and `scheme.SpillDepth` are internal, for tests, and so
are `scheme.Torture`, `scheme.PreludeImageFile` and `scheme.PreludeImageWrite`,
constant options that only `-Dpolyglot.scheme.<name>` on the JVM's command
line sets, for the process: design.md D17.) Give
an option to a context's builder (`Context.Builder.option`), to an explicit
engine's (`Engine.Builder.option`: each context of the engine takes it, unless
its own builder gives another), or as a system property,
`-Dpolyglot.scheme.LibraryPath=...`, which an engine reads when it is created;
the launcher takes them as `--scheme.LibraryPath=...`. A value the option
can't take fails `build()` with an `IllegalArgumentException` that names it.

An explicit engine shares its options, instruments and compiler threads
between its contexts, but not the code: each context parses and compiles
Scheme code itself, a `Source` evaluated in two contexts included. Compiled
code holds the context's global variables as constants, so a context's code
is its own (`ContextPolicy.EXCLUSIVE`, design.md D13), where GraalVM's own
languages share code between the contexts of an engine. Each context starts
with the standard libraries restored from the prebuilt image (D13), not
expanded.

<!-- readme: file tests/scheme/test/EmbeddingGuideTest.java#engine -->
```java
Source program = Source.create("scheme", "(import (scheme base) (examples greet)) (greet \"Ada\")");
// Every context of this engine takes its options, unless the context's builder gives others.
try (Engine engine = Engine.newBuilder("scheme").option("scheme.LibraryPath", libraries.toString()).build()) {
    for (int i = 0; i < 2; i++) {
        try (Context context = Context.newBuilder("scheme").engine(engine).allowIO(IOAccess.ALL).build()) {
            assertEquals("Hello from a library, Ada!", context.eval(program).asString());
        }
    }
}
```

## Sandboxing and resource limits

Scheme reaches files, standard streams and environment variables only
through what the context allows (design.md D10), and Java objects and other
languages only through `(graalvm polyglot)`, with what the context allows
([below](#scheme-calling-other-languages-and-java)). So it runs under GraalVM's
[`SandboxPolicy.CONSTRAINED`](https://www.graalvm.org/latest/security-guide/sandboxing/),
for code you trust but whose access to the host you want to regulate:
`Context.newBuilder("scheme").sandbox(SandboxPolicy.CONSTRAINED)` with
`out` and `err` redirected. Under it, opening a file is a Scheme error that
`file-error?` answers, unless you give the context a file system of your own
(`IOAccess.newBuilder().fileSystem(...)`, such as a zip file's through
`FileSystem.newFileSystem`), from which libraries are then read too;
`get-environment-variable` finds none; `exit` ends the context, not the
JVM; and `java-type` finds the classes your class filter allows, with only
their members annotated `@HostAccess.Export` (`HostAccess.CONSTRAINED`, the
policy's default; design.md D20). `scheme.LibraryPath`, `scheme.BufferOutput` and `scheme.StackSize` can
be set under it, and under the stricter policies below.

With or without a policy, Truffle's limits stop a context that runs too long
or writes too much, and cancel it: `ResourceLimits`' statement limit (each
expression of a body is a statement), and the `sandbox.MaxCPUTime` and
`sandbox.MaxOutputStreamSize` options; so do
`Context.interrupt` and `Context.close(true)` from another thread.
So does `sandbox.MaxStackFrames`, which bounds the depth of Scheme's stack:
it counts the frames the program will return to, those a deep recursion has
moved to the heap (design.md "Continuations") as well as those on the Java
stack, and those of a continuation the program re-enters (design.md D10,
"Sandboxing"). A program's body is a frame, and each procedure call it is
in; a tail call adds none, so a loop runs under any limit.

<!-- readme: file tests/scheme/test/EmbeddingGuideTest.java#limits -->
```java
ResourceLimits limits = ResourceLimits.newBuilder().statementLimit(1_000_000, null).build();
try (Context context = Context.newBuilder("scheme").resourceLimits(limits).build()) {
    assertEquals(55, context.eval("scheme", "(let loop ((i 0) (sum 0)) (if (> i 10) sum (loop (+ i 1) (+ sum i))))").asInt());
    context.eval("scheme", "(let loop () (loop))");
    fail("the loop ended");
} catch (PolyglotException e) {
    // The context is cancelled: it can't be used again.
    assertTrue(e.isResourceExhausted());
}
```

### `ISOLATED` and `UNTRUSTED`

The two stricter policies run the language in a polyglot isolate: a native
image of the language, with its own heap, collector and JIT compiler, which
Truffle loads into your process as a shared library. `ISOLATED` is for
trusted code that processes untrusted input, `UNTRUSTED` for code you don't
trust, which it also hardens against JIT spraying and speculative execution
(design.md D10, "ISOLATED and UNTRUSTED").
`bazel build //:scheme_isolate_jars` builds the isolate library for your
platform (a native-image build, a minute or two and a few GB) and puts it, in
`scheme_isolate.jar`, with Truffle's jars in `bazel-bin/scheme_isolate_jars/`,
your module path for these policies. It has no `scheme.jar`: the language
runs only in the isolate. Truffle unpacks the library to its resource cache
(your user cache directory, or the `polyglot.engine.userResourceCache`
system property's) the first time a context needs it.

Each policy requires its limits: `ISOLATED` the isolate's heap
(`engine.MaxIsolateMemory`) and `sandbox.MaxCPUTime`; `UNTRUSTED` also
standard input redirected, `sandbox.MaxHeapMemory` (what the context keeps,
the frames a deep recursion has moved to the heap among them),
`sandbox.MaxASTDepth`, `sandbox.MaxThreads` (a context uses one) and the
output and error streams' sizes. `sandbox.MaxASTDepth` bounds how deeply a
procedure's expressions nest, each procedure on its own; the standard
libraries don't count. Under `UNTRUSTED`, `scheme.PreludeImage` can't be set.

<!-- readme: file docs/examples/Untrusted.java -->
```java
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.SandboxPolicy;

public class Untrusted {
    public static void main(String[] args) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder("scheme")
                        .sandbox(SandboxPolicy.UNTRUSTED)
                        .in(InputStream.nullInputStream())
                        .out(output)
                        .err(output)
                        .option("engine.MaxIsolateMemory", "512MB")
                        .option("sandbox.MaxHeapMemory", "128MB")
                        .option("sandbox.MaxCPUTime", "2s")
                        .option("sandbox.MaxASTDepth", "50")
                        .option("sandbox.MaxThreads", "1")
                        .option("sandbox.MaxOutputStreamSize", "1MB")
                        .option("sandbox.MaxErrorStreamSize", "1MB")
                        .build()) {
            context.eval("scheme", "(define (fact n) (if (= n 0) 1 (* n (fact (- n 1))))) (display (fact 30))");
            System.out.println(output.toString(StandardCharsets.UTF_8));
            context.eval("scheme", "(let loop () (loop))");
        } catch (PolyglotException e) {
            System.out.println(e.isResourceExhausted() + ": " + e.getMessage());
        }
    }
}
```

<!-- readme: run native -->
```sh
bazel build //:scheme_isolate_jars
java -p bazel-bin/scheme_isolate_jars --add-modules ALL-MODULE-PATH --enable-native-access=org.graalvm.truffle --sun-misc-unsafe-memory-access=allow docs/examples/Untrusted.java
```

<!-- readme: output -->
```text
265252859812191058636308480000000
true: Maximum CPU time limit of 2000ms exceeded. Time executed ...ms.
```

Values from the isolate are handles, as with any polyglot isolate, and a
boolean arrives as Java's: its `toString()` is `true`, not `#t`. `//tests:unit_isolate_SandboxTest` runs Scheme under both policies and
checks each limit (`SandboxTest.Isolated`).

## Scheme values in Java

Scheme values answer Truffle's interop protocol as what they are. Each has the
language `scheme`, its `write` syntax as `toString()`, and a meta object
(`getMetaObject()`) named after its type's predicate without the `?`
(`number`, `string`, `pair`, `procedure`, ...); a record's meta object is its
record type.

| Scheme | In Java (`Value`) |
|---|---|
| fixnum, flonum | `long`, `double` |
| bignum | `fitsInBigInteger()`, never `fitsInLong()`; `fitsInDouble()` only when exact |
| exact rational | a number that fits no integer type; `fitsInDouble()` only when exact (`1/2` yes, `1/3` no) |
| non-real complex number | not an interop number (interop's numbers are real); `toString()` is `1+2i` |
| string | `asString()` (a copy) |
| character | a one-character string |
| symbol | not a string; `toString()` is its name |
| boolean | `asBoolean()` |
| vector, bytevector | an array; bytevector elements are integers 0 to 255; literal ones are read-only |
| list | an iterable (`getIterator()`), walked lazily up to the first non-pair |
| pair of an improper or circular list | also an object whose members are `car` and `cdr`, writable as with `set-car!` (read-only on a literal's pairs, as `set-car!` refuses them), so an improper list's last cdr is its last pair's `cdr`; such a pair is a `Map` of the two as an `Object` (`as(Iterable.class)` still gives its elements). A proper list has no members, so as an `Object` it is an `Iterable` |
| `'()` | null, and an empty iterable |
| unspecified value | null |
| zero or several values | a read-only array |
| record | an object whose fields are read-only members |
| procedure, continuation | an executable (below) |

<!-- readme: file tests/scheme/test/EmbeddingGuideTest.java#scheme-values -->
```java
assertEquals(42L, context.eval("scheme", "42").asLong());
assertEquals(BigInteger.TWO.pow(100), context.eval("scheme", "(expt 2 100)").asBigInteger());
assertEquals(0.5, context.eval("scheme", "1/2").asDouble(), 0);
assertEquals("λ", context.eval("scheme", "#\\λ").asString());
assertTrue(context.eval("scheme", "(< 1 2)").asBoolean());

Value vector = context.eval("scheme", "(vector 1 2 3)");
assertEquals(3, vector.getArraySize());
vector.setArrayElement(0, 10);

List<Integer> items = new ArrayList<>();
Value iterator = context.eval("scheme", "(list 1 2 3)").getIterator();
while (iterator.hasIteratorNextElement()) {
    items.add(iterator.getIteratorNextElement().asInt());
}
assertEquals(List.of(1, 2, 3), items);
Value improper = context.eval("scheme", "'(1 2 . 3)");
assertEquals(3, improper.getMember("cdr").getMember("cdr").asInt());

Value point = context.eval("scheme", """
                (define-record-type point (make-point x y) point? (x point-x) (y point-y))
                (make-point 3 4)
                """);
assertEquals(4, point.getMember("y").asInt());
assertEquals("point", point.getMetaObject().getMetaSimpleName());

// toString is the value's write syntax.
assertEquals("(1 \"two\" #\\3 four)", context.eval("scheme", "(list 1 \"two\" #\\3 'four)").toString());
```

## Java values in Scheme

Arguments, array elements and bindings that come from Java become Scheme
values: integers (including a `BigInteger`) are exact integers, `float` and
`double` flonums, a `char` a character, a `String` a fresh mutable string, a
`boolean` a boolean. A Scheme value passed back in is the same object.

Anything else, Java's `null` included, stays a *foreign value*. Scheme can
store it, return it (Java gets the same object back) and compare it with
`eq?`, `eqv?` and `equal?` (identity); `write` shows it as `#<foreign ...>`.
Every type predicate is false for it but `procedure?`, which is true of an
executable one (a Java lambda, another language's function): Scheme can call
it. A primitive given one raises a Scheme error, and calling one that isn't
executable is a "not a procedure" error. Scheme code reaches into Java and
other languages through `(graalvm polyglot)`
([below](#scheme-calling-other-languages-and-java)).

<!-- readme: file tests/scheme/test/EmbeddingGuideTest.java#java-values -->
```java
Value kind = context.eval("scheme", """
                (lambda (x)
                  (cond ((exact-integer? x) 'exact-integer)
                        ((real? x) 'flonum)
                        ((char? x) 'char)
                        ((string? x) 'string)
                        ((boolean? x) 'boolean)
                        (else 'foreign)))
                """);
assertEquals("exact-integer", kind.execute(42).toString());
assertEquals("exact-integer", kind.execute(BigInteger.TEN.pow(30)).toString());
assertEquals("flonum", kind.execute(2.5f).toString());
assertEquals("char", kind.execute('c').toString());
assertEquals("string", kind.execute("text").toString());
assertEquals("boolean", kind.execute(true).toString());
assertEquals("foreign", kind.execute(new StringBuilder()).toString());
assertEquals("foreign", kind.execute((Object) null).toString());

// A foreign value comes back as the same Java object.
StringBuilder host = new StringBuilder();
Value identity = context.eval("scheme", "(lambda (x) (car (list x)))");
assertSame(host, identity.execute(host).asHostObject());
```

## Calling procedures and global bindings

A procedure is an executable: `execute(args...)` calls it, with any number of
arguments for a variadic procedure or a `case-lambda`. A wrong number of
arguments is a Scheme error, not an interop `ArityException`. Each call runs
under a prompt of its own (D6), as a program does, so tail calls run in
constant space and deep recursion spills to the heap inside it. It spills
once it has used most of the calling thread's Java stack, which the first
deep recursion on each thread measures (design.md "Continuations"): a few
milliseconds, and it touches up to 64 MB of the stack, which stays in
memory.

If you start the thread that creates the context, with a stack size of your
choosing, tell the context that size with the `scheme.StackSize` option
(bytes, or `512k`, `64m`, `1g`): on that thread, the spill limit comes from
the size, and nothing measures the stack. The size is that of the thread
Scheme starts on in the context, so build the context and first use it on
the thread you started. Other threads that enter the context still measure
theirs. The `scheme` launcher does this for its evaluation thread.

<!-- readme: file tests/scheme/test/EmbeddingGuideTest.java#stack-size -->
```java
long stackSize = 64L << 20;
AtomicLong depth = new AtomicLong();
Thread thread = new Thread(null, () -> {
    // Created on this thread, whose stack size the context is told: nothing measures it.
    try (Context context = Context.newBuilder("scheme").option("scheme.StackSize", Long.toString(stackSize)).build()) {
        context.eval("scheme", "(define (depth n) (if (= n 0) 0 (+ 1 (depth (- n 1)))))");
        depth.set(context.eval("scheme", "(depth 20000)").asLong());
    }
}, "scheme", stackSize);
thread.start();
thread.join();
assertEquals(20000, depth.get());
```

`context.getBindings("scheme")` is the interaction environment. Its bound
variables, imported or defined, are its members; syntactic keywords are not.
`putMember` is `(define name value)` there: it defines, redefines, or shadows
an import, as the REPL would. Programs keep their own top level and don't
appear in it.

<!-- readme: file tests/scheme/test/EmbeddingGuideTest.java#bindings -->
```java
Value bindings = context.getBindings("scheme");
bindings.putMember("limit", 10);
assertEquals(11, context.eval("scheme", "(+ limit 1)").asInt());

context.eval("scheme", "(define (twice f x) (f (f x)))");
Value twice = bindings.getMember("twice");
Value cdr = bindings.getMember("cdr");
assertEquals("(3)", twice.execute(cdr, context.eval("scheme", "'(1 2 3)")).toString());
assertTrue(bindings.getMember("string-append").canExecute());
assertFalse(bindings.hasMember("no-such-variable"));
```

## Errors

An error that a call or an `eval` doesn't handle surfaces as a
`PolyglotException` whose `isGuestException()` is true, once the
`dynamic-wind` after-thunks it leaves have run (D8). Its message is the error
object's message and irritants, or `uncaught exception: <obj>` for a raised
object that isn't an error object, and its guest object (`getGuestObject()`)
is the raised object (D10): an error object has its `message` and
`irritants` as members. Afterwards the dynamic environment (parameters,
handlers, `dynamic-wind`) is the caller's again, and the context goes on
working.

<!-- readme: file tests/scheme/test/EmbeddingGuideTest.java#errors -->
```java
try {
    context.eval("scheme", "(error \"bad input:\" 42)");
    fail("error returned");
} catch (PolyglotException e) {
    assertTrue(e.isGuestException());
    assertEquals("bad input: 42", e.getMessage());
    // The guest object is the error object that error raised.
    Value error = e.getGuestObject();
    assertEquals("bad input:", error.getMember("message").asString());
    assertEquals(42, error.getMember("irritants").getIterator().getIteratorNextElement().asInt());
}
Value check = context.eval("scheme", "(lambda (x) (if (negative? x) (raise (list 'negative x)) x))");
try {
    check.execute(-1);
    fail("raise returned");
} catch (PolyglotException e) {
    assertTrue(e.isGuestException());
    assertEquals("uncaught exception: (negative -1)", e.getMessage());
    assertEquals("(negative -1)", e.getGuestObject().toString());
}
// The context goes on working.
assertEquals(5, check.execute(5).asInt());
```

The error is a Truffle exception (design.md D8, "Errors as Truffle
exceptions"). What `PolyglotException` says of it, and what another language
sees through `InteropLibrary`:

| | A Scheme error (`raise` of any object, `error`, a primitive's error) | A syntax error | `exit`, `emergency-exit` | `Context.interrupt` | `close(true)`, a resource limit |
|---|---|---|---|---|---|
| `isGuestException()` | true | true | true | true | true |
| `getMessage()`, `getExceptionMessage` | the error object's message and irritants, or `uncaught exception: <obj>` | the error, with its file, line and column | Truffle's, `Exit was called with exit code 3.` | Truffle's | Truffle's |
| `getSourceLocation()`, `getSourceLocation` | the innermost Scheme frame's expression, never the standard libraries' code | the form | the standard library's `exit` | the running procedure's definition (Truffle's: where it polled) | the same |
| `getPolyglotStackTrace()`, `getExceptionStackTrace` | the Scheme frames left, innermost first: each named after its binding (or `<lambda@file.scm:3:5>`) at the line of its call; with frames a deep recursion moved to the heap and frames of a re-entered continuation; a procedure whose tail call raised has no frame (proper tail calls); the standard libraries' frames are internal (`isInternal` through interop) and left out, except one running when re-entered through a continuation (docs/upstream.md) | none | the Scheme frames | the Scheme frames (the innermost without a line) | the same |
| `getGuestObject()` | the raised object, also an exception: its `toString()` is its `write` syntax, its meta object its type (`error-object` for an error object, with `message` and `irritants` members) | an error object | null | Truffle's interrupt exception | null |
| `getExceptionType` | `RUNTIME_ERROR` | `PARSE_ERROR`, `isIncompleteSource()` when the text ended inside a datum | `EXIT`, `isExit()`, `getExitStatus()` | `isInterrupted()` | `isCancelled()` (and `isResourceExhausted()` for a limit) |
| `getCause()`, `hasExceptionCause` | none | none | none | none | none |
| Scheme handlers and `dynamic-wind` after-thunks | the after-thunks it leaves run first (D8) | not run | the after-thunks run, for `exit` (D9) | neither runs | neither runs |
| The context afterwards | goes on | goes on | closed | goes on | closed |

## Continuations across calls

Continuations are re-entrant and multi-shot, from Java too. A continuation
captured during one call is delimited by that call's prompt: invoked during a
later call (from Java or from Scheme), it runs the rest of the call that
captured it, and the later call returns what that rest returns, as REPL inputs
do. Continuations are immutable once captured, so re-entering one any number of
times leaves both calls intact.

<!-- readme: file tests/scheme/test/EmbeddingGuideTest.java#continuations -->
```java
context.eval("scheme", "(define saved #f)");
Value f = context.eval("scheme", "(lambda () (+ 1 (call/cc (lambda (k) (set! saved k) 1))))");
assertEquals(2, f.execute().asInt());

// The continuation is the rest of that first call: re-enter it, as often as you like.
Value k = context.getBindings("scheme").getMember("saved");
assertEquals(11, k.execute(10).asInt());
assertEquals(21, k.execute(20).asInt());
```

That is enough for a generator driven from Java, one call at a time, each
call resuming the loop that the call before it left:

<!-- readme: file tests/scheme/test/EmbeddingGuideTest.java#generator -->
```java
Value next = context.eval("scheme", """
                (define (make-generator items)
                  (define return #f)
                  (define resume #f)
                  (lambda ()
                    (call/cc
                      (lambda (r)
                        (set! return r)
                        (if resume
                            (resume #f)
                            (begin
                              (for-each (lambda (x)
                                          (call/cc (lambda (k) (set! resume k) (return x))))
                                        items)
                              (return 'done)))))))
                (make-generator '(a b))
                """);
assertEquals("a", next.execute().toString());
assertEquals("b", next.execute().toString());
assertEquals("done", next.execute().toString());
```

## Scheme calling other languages and Java

A program that imports `(graalvm polyglot)` reaches the other languages of
the context, the polyglot bindings and Java classes, with what the context
allows (design.md D20); a program that doesn't import it sees none of it.

| Procedure | |
|---|---|
| `(polyglot-eval language source)`, `(polyglot-eval-file language path)` | Evaluates code of another language of the context: `allowPolyglotAccess`, and the language permitted (`Context.newBuilder("scheme", "js")`). |
| `(polyglot-language-available? language)` | Whether `polyglot-eval` may evaluate that language. |
| `(polyglot-import name [default])`, `(polyglot-export name value)` | The polyglot bindings, `context.getPolyglotBindings()` in Java (`allowPolyglotAccess`); `polyglot-import` answers `default`, or `#f`, for a name nothing exports, as JavaScript's `Polyglot.import` answers `undefined`. |
| `(java-type name)` | A Java class (`allowHostClassLookup` and its filter), with the members your `HostAccess` allows, under any sandbox policy. In a native image of yours, a class needs [reflection metadata](https://www.graalvm.org/latest/reference-manual/embed-languages/#configuring-native-host-reflection) (`reachability-metadata.json`); without it, `java-type` says so. |
| `(foreign? x)`, `(foreign-null? x)` | Whether `x` is a foreign value, and a foreign null (Java's `null`). |
| `(foreign-ref obj key)`, `(foreign-set! obj key value)` | An array element (an exact integer key), a map's value (`HostAccess` with map access), or a member: a field, a method, another language's property (a string or symbol key). |
| `(foreign-length obj)`, `(foreign-keys obj)` | An array's or a map's size; a map's keys, or an object's member names. |
| `(foreign->list obj)`, `(foreign->vector obj)` | An array's or an iterable's elements, copied. |
| `(foreign-invoke obj member arg ...)`, `(foreign-new class arg ...)` | A method call, and `new`. |
| `(foreign-instance? obj type)` | `instanceof`. |

What comes back is converted as an argument from Java is ([Java values in
Scheme](#java-values-in-scheme)): numbers, strings, characters and booleans
become Scheme values, anything else stays a foreign value. A foreign array,
list or map is not a vector or a list: read it with `foreign-ref`, or copy it
with `foreign->list`. A foreign function is a procedure. A foreign exception
is raised in Scheme as itself, so `guard` catches it: `error-object?` is
true of it, with its message as `error-object-message`; one that Scheme
doesn't catch reaches Java as it was thrown.

<!-- readme: file tests/scheme/test/EmbeddingGuideTest.java#polyglot -->
```java
Context.Builder builder = Context.newBuilder("scheme");
builder.allowPolyglotAccess(PolyglotAccess.ALL); // polyglot-eval, polyglot-import and -export
builder.allowHostAccess(HostAccess.ALL); // the members of Java objects
builder.allowHostClassLookup(name -> name.startsWith("java.util.")); // what java-type may find
try (Context context = builder.build()) {
    context.getPolyglotBindings().putMember("greeting", "Hello");
    Value program = context.eval("scheme", """
                    (import (scheme base) (graalvm polyglot))
                    (define names (foreign-new (java-type "java.util.ArrayList")))
                    (foreign-invoke names 'add "Ada")
                    (foreign-invoke names 'add "Grace")
                    (polyglot-export "greet"
                      (lambda (name) (string-append (polyglot-import "greeting") ", " name "!")))
                    (map (lambda (name) (string-length name)) (foreign->list names))
                    """);
    assertEquals("(3 5)", program.toString());
    Value greet = context.getPolyglotBindings().getMember("greet");
    assertEquals("Hello, Ada!", greet.execute("Ada").asString());

    // A Java lambda is a procedure to Scheme, and a Java exception is raised there.
    Value probe = context.eval("scheme", """
                    (import (scheme base) (graalvm polyglot))
                    (lambda (f items)
                      (list (f (foreign-invoke items 'get 0))
                            (guard (e ((error-object? e) 'caught)) (foreign-invoke items 'get 5))))
                    """);
    Function<Object, String> describe = x -> "got " + x;
    assertEquals("(\"got 1\" caught)", probe.execute(describe, List.of(1, 2)).toString());
}
```

When another language calls a Scheme procedure (a callback), the procedure
runs under a prompt of its own, as a call from Java does, and without the
exception handlers of the Scheme code that called that language: an error
the procedure doesn't handle leaves it through the other
language's frames (which can catch it) and is raised again where Scheme
called that language. A continuation can't cross the other language's
frames: invoking, inside a callback, one captured outside it, or, after the
foreign call has returned, one captured in its callback, is a Scheme error
(design.md D20, "Continuations across foreign frames").

A recursion through another language (Scheme calls it, it calls Scheme back,
and so on) keeps every level on the Java stack: Scheme spills its own frames
to the heap, but not the other language's. Too deep, it overflows the Java
stack, which is a Scheme error raised where Scheme called the other
language, and `guard` catches it; `sandbox.MaxStackFrames` counts the
frames of every language of the context, so it stops such a recursion too
(design.md D20, "Stack depth across foreign frames").

Under `ISOLATED` and `UNTRUSTED` ([above](#isolated-and-untrusted)), Scheme
runs in the isolate, which holds no other language: `polyglot-eval` reaches
only Scheme, and a context that permits another language with Scheme can't
be built (*"No native isolate library is available for the requested
language(s)"*). The polyglot bindings, values you hand Scheme (proxies,
Java objects under the policy's `HostAccess`, which has no list, array or map
access by default), the classes `java-type` finds (looked up in your JVM,
with the members the `HostAccess` exports) and functions it calls back work
as on the JVM, through the isolate's handles, and so do errors and the
continuation rules above (design.md D20, "Permissions and the sandbox").
With `engine.SpawnIsolate` under no policy, `java-type` gives a class with
all its members, as your `HostAccess` allows.

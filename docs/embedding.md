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
which nothing `requires`: the polyglot API finds it as a service. The two other
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

(`scheme.PreludeImage` and `scheme.SpillDepth` are internal, for tests.) Give
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
through what the context allows (design.md D10), and can't reach Java
objects or other languages. So it runs under GraalVM's
[`SandboxPolicy.CONSTRAINED`](https://www.graalvm.org/latest/security-guide/sandboxing/),
for code you trust but whose access to the host you want to regulate:
`Context.newBuilder("scheme").sandbox(SandboxPolicy.CONSTRAINED)` with
`out` and `err` redirected. Under it, opening a file is a Scheme error that
`file-error?` answers, unless you give the context a file system of your own
(`IOAccess.newBuilder().fileSystem(...)`, such as a zip file's through
`FileSystem.newFileSystem`), from which libraries are then read too;
`get-environment-variable` finds none; and `exit` ends the context, not the
JVM. `scheme.LibraryPath`, `scheme.BufferOutput` and `scheme.StackSize` can
be set under it. The stricter `ISOLATED` and `UNTRUSTED` policies run the
language in an isolate, which this build doesn't provide.

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
| pair of an improper or circular list | also an object whose members are `car` and `cdr`, writable as with `set-car!`, so an improper list's last cdr is its last pair's `cdr`; such a pair is a `Map` of the two as an `Object` (`as(Iterable.class)` still gives its elements). A proper list has no members, so as an `Object` it is an `Iterable` |
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
Every type predicate is false for it, so a primitive given one raises a
Scheme error, and calling one is a "not a procedure" error. Scheme code can't
call into Java or other languages in this version (D10).

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

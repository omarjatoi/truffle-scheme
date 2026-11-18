# Upstream reports

Bugs in Truffle that this implementation works around, each with a minimal
reproducer that doesn't use Scheme. Filing them at
<https://github.com/oracle/graal/issues> is a human's call: we haven't filed
any. Each section has the issue text, ready to paste, and an **Upstream
status** line: what oracle/graal's issues, pull requests and `master` say
about it, on the date given. The newest release is still 25.5.5.0.3;
`master` is 25.6.0-dev.

All seven are in Truffle 25.5.5.0.3 (Maven Central), on GraalVM CE 25.5.5.0.3
(JDK 25.0.5.0.3, `nix develop`).

## The reproducers are inverted tests

Each reproducer is a JUnit test in `tests/scheme/test/upstream/` (package
`scheme.test.upstream`) that asserts the buggy behavior, so it **passes while
the bug is there**. It runs with every other unit test in `bazel test //...`, and
alone with:

```
bazel test //tests:unit_jit_UpstreamAsyncStacksTest //tests:unit_jit_UpstreamNestedRootSourceTest //tests:unit_jit_UpstreamReportPolymorphismTest //tests:unit_jit_UpstreamInterruptTest //tests:unit_jit_UpstreamPreinitializationDeadlockTest
```

When a Truffle upgrade fixes a bug, its test fails. Then remove the
workaround it names, the test, and the section here. Don't "fix" the test to
expect the bug again.

The tests share a tiny language that has nothing to do with Scheme,
`UpstreamLanguage` (id `upstream-repro`): every program is one statement that
returns 42. Its roots are `UpstreamRoot`, a `@GenerateBytecode` root with tag
instrumentation and one trivial operation (the DSL warns if a root has none).

| Bug | Test | Our workaround |
|---|---|---|
| [Nested root source sections](#nested-root-source-sections) | `UpstreamNestedRootSourceTest` | `CodeGenerator.usable` (`src/compiler/scheme/compiler/CodeGenerator.java`) |
| [Empty asynchronous stacks](#empty-asynchronous-stacks) | `UpstreamAsyncStacksTest` | `DebuggerTest.frameNames`, `SpilledFramesTest.everyFrameOnceAtEveryLevelDownAndBackUp` and `describe` (`tests/scheme/test/`) |
| [ReportPolymorphism on an operation](#reportpolymorphism-on-an-operation) | `UpstreamReportPolymorphismTest` | `SchemeRootNode.Call`'s cache initializer `Trampoline.megamorphic` (`src/runtime/scheme/runtime/`) |
| [ThreadDeath in generated roots](#threaddeath-in-generated-roots) | none (a compiler warning, not behavior) | `BYTECODE_ROOT_JAVACOPTS` (`bazel/java.bzl`) |
| [Interrupting a Bytecode DSL root as it returns](#interrupting-a-bytecode-dsl-root-as-it-returns) | `UpstreamInterruptTest` | `InterruptTest`'s static initializer (`tests/scheme/test/`) |
| [Context pre-initialization with synchronous compilation](#context-pre-initialization-with-synchronous-compilation) | `UpstreamPreinitializationDeadlockTest` | `ContextPreinitializationTest`'s child JVMs (`tests/scheme/test/`) |
| [Instrumented compilations fail in libgraal](#instrumented-compilations-fail-in-libgraal) | none yet (a launcher command, below) | `bazel-bin/scheme` runs Graal compiled for HotSpot for it (`launcher_vm_args`, `tests/harness/harness.py`); `SchemeMain` rejects it with libgraal and in the native launcher |

## Nested root source sections

**Found in** M5-05 (#97). **Test:** `UpstreamNestedRootSourceTest`.

**Upstream status** (checked 2026-11-17): still present on `master`, and
unreported (no issue or pull request found). The search in
[`BuilderElement`](https://github.com/oracle/graal/blob/cd3747b70d9ece13c37de1471a7917af49652822/truffle/src/com.oracle.truffle.dsl.processor/src/com/oracle/truffle/dsl/processor/bytecode/generator/BuilderElement.java#L2083-L2101)
is unchanged since the Bytecode DSL landed: the issue text is ready to file.

A Bytecode DSL root built while another root is being built (in Scheme, every
lambda's root, which is built inside the root that makes its closure) gets its
source sections in the outermost open `Source`, not in the `Source` it opened
itself.

- **Expected:** the nested root's section `[0, 5)` is in `inner.txt`:
  `INNER`. That is what the same root gets when it is built alone (the test's
  control case).
- **Actual:** it is in `outer.txt`: `outer`. When the outer `Source` is
  shorter than the section, the section is out of its bounds, and tools that
  read it fail with "Invalid source section bounds".
- **Cause** (in the generated builder): `beginSourceSectionPrefix` walks the
  operation stack of each root being built, innermost first, looking for a
  `Source` operation. `break loop` leaves the inner `for` loop when it finds
  one, but the `while` loop goes on to the parent root, so the outermost
  `Source` overwrites `foundSourceIndex`.
- **Our workaround:** `CodeGenerator.usable` leaves out any section that the
  builder would put in the wrong `Source`; the code it covers reports the
  enclosing section instead. Building roots flat (not nested) avoids the bug,
  but then a root gets no section of its own, since the builder gives a root
  its section only from an enclosing open section.

### Issue text

````markdown
**Title:** Bytecode DSL: a nested root's source sections are attributed to the outermost open Source

**Version:** Truffle 25.5.5.0.3 (GraalVM CE 25.5.5.0.3, JDK 25.0.5.0.3)

A root built while another root is being built (`beginRoot` inside another root's body)
gets its source sections in the outermost enclosing `Source`, not in the `Source` it
opened itself. Built alone, the same root gets the right `Source`.

Reproducer (`UpstreamLanguage` is any `TruffleLanguage`; the root is passed none):

```java
@GenerateBytecode(languageClass = UpstreamLanguage.class, enableTagInstrumentation = true)
public abstract class UpstreamRoot extends RootNode implements BytecodeRootNode {
    protected UpstreamRoot(UpstreamLanguage language, FrameDescriptor frameDescriptor) {
        super(language, frameDescriptor);
    }

    @Operation
    public static final class Identity {
        @Specialization
        static Object doObject(Object value) {
            return value;
        }
    }
}
```

```java
static final Source OUTER = Source.newBuilder("x", "outer source text", "outer.txt").build();
static final Source INNER = Source.newBuilder("x", "INNER", "inner.txt").build();

/** Builds a root whose only code is in section [0, 5) of INNER: alone, or nested in another root. */
static UpstreamRoot buildInner(boolean nested) {
    UpstreamRoot[] inner = new UpstreamRoot[1];
    UpstreamRootGen.create(null, BytecodeConfig.WITH_SOURCE, b -> {
        if (nested) {
            b.beginRoot();
            b.beginSource(OUTER);
            b.beginSourceSection(0, OUTER.getLength());
            b.beginBlock();
        }

        b.beginRoot();
        b.beginSource(INNER);
        b.beginSourceSection(0, 5);
        b.beginReturn();
        b.emitLoadConstant(1);
        b.endReturn();
        b.endSourceSection();
        b.endSource();
        inner[0] = b.endRoot();

        if (nested) {
            b.beginReturn();
            b.emitLoadConstant(0);
            b.endReturn();
            b.endBlock();
            b.endSourceSection();
            b.endSource();
            b.endRoot();
        }
    });
    return inner[0];
}

static String location(UpstreamRoot root) {
    SourceSection section = root.getBytecodeNode().getSourceLocation(0);
    return section.getSource().getName() + " [" + section.getCharIndex() + ", "
                    + section.getCharEndIndex() + ") " + section.getCharacters();
}

// Built alone: "inner.txt [0, 5) INNER", as expected.
location(buildInner(false));
// Nested: "outer.txt [0, 5) outer". Expected "inner.txt [0, 5) INNER".
location(buildInner(true));
```

If `OUTER` is shorter than the nested section, the section is out of `OUTER`'s bounds, and
tools that read it (the debugger, for instance) fail with "Invalid source section bounds".

Cause: in the generated builder, `beginSourceSectionPrefix` looks for the enclosing `Source`
operation in each root being built, innermost first:

```java
while (currentState != null) {
    loop: for (int i = currentState.operationSp - 1; i >= 0; i--) {
        ...
        case Operations.SOURCE :
            foundSourceIndex = sourceData.getSourceIndex();
            break loop;
    }
    currentState = currentState.parent;
}
```

`break loop` only leaves the `for` loop, so the search goes on into the parent roots, and the
outermost `Source` wins. It should stop at the first `Source` it finds.
````

## Empty asynchronous stacks

**Found in** M6-04 (#98), again in #102. **Test:** `UpstreamAsyncStacksTest`.

**Upstream status** (checked 2026-11-17): still present on `master`, and
unreported (no issue or pull request found).
[`SuspendedEvent.java`](https://github.com/oracle/graal/blob/cd3747b70d9ece13c37de1471a7917af49652822/truffle/src/com.oracle.truffle.api.debug/src/com/oracle/truffle/api/debug/SuspendedEvent.java#L1020-L1024)
is unchanged since 25.5 and still tests `size > 0`, which confirms the cause
above from source: the issue text is ready to file.

When no frame of a suspended program has an asynchronous stack,
`SuspendedEvent.getAsynchronousStacks()` returns a list whose `size()` is 0
but whose iterator throws `IndexOutOfBoundsException` from its first
`hasNext()`. A `for` loop over it fails, though the list says it is empty.

- **Expected:** the loop runs zero times.
- **Actual:** `IndexOutOfBoundsException: Index: -1, Size: 0`, from
  `SuspendedEvent$DebugAsyncStackFrameLists.fillStacks`, which reads
  `stacks.get(stacks.size() - 1)` while `stacks` is empty.
- **Cause** (from the bytecode; we have no sources for 25.5): the constructor
  sets `size = 0` when no frame has an asynchronous stack. `fillStacks(index)`
  returns early only when `size > 0 && index >= size`, so with `size == 0` it
  goes on to look for more stacks after the last one it has, which is
  `stacks.get(-1)`. `size()` returns the 0 without calling `fillStacks`, so
  `size()` and `isEmpty()` work, and `get(i)` and `iterator()` don't.
  `DebugException.getDebugAsynchronousStacks()` builds the same list class
  (not tested here).
- **Our workaround:** in tests only. `DebuggerTest.frameNames` loops by index
  (`for (int i = 0; i < stacks.size(); i++)`), and
  `SpilledFramesTest.everyFrameOnceAtEveryLevelDownAndBackUp` only asks
  `describe` for the asynchronous stack when frames are pending. A debugger
  front end that iterates the list (Chrome inspector, DAP) could hit the bug
  with any language.

### Issue text

````markdown
**Title:** Debugger: iterating an empty `SuspendedEvent.getAsynchronousStacks()` throws IndexOutOfBoundsException

**Version:** Truffle 25.5.5.0.3 (GraalVM CE 25.5.5.0.3, JDK 25.0.5.0.3)

When no frame of a suspended program has an asynchronous stack (the common case:
`RootNode.findAsynchronousFrames` returns null), `SuspendedEvent.getAsynchronousStacks()`
returns a list whose `size()` is 0, but whose iterator throws from its first `hasNext()`:

```
java.lang.IndexOutOfBoundsException: Index: -1, Size: 0
    at java.base/java.util.LinkedList.get(...)
    at com.oracle.truffle.api.debug.SuspendedEvent$DebugAsyncStackFrameLists.fillStacks(...)
    at com.oracle.truffle.api.debug.SuspendedEvent$DebugAsyncStackFrameLists$Itr.hasNext(...)
```

Reproducer, with any language whose programs have a statement (ours, `upstream-repro`, is a
Bytecode DSL root of one `StatementTag` around `LoadConstant 42`):

```java
List<Integer> sizes = new ArrayList<>();
List<Throwable> thrown = new ArrayList<>();
try (Context context = Context.create("upstream-repro")) {
    Debugger debugger = Debugger.find(context.getEngine());
    try (DebuggerSession session = debugger.startSession(event -> {
        List<List<DebugStackTraceElement>> stacks = event.getAsynchronousStacks();
        sizes.add(stacks.size());
        try {
            for (List<DebugStackTraceElement> stack : stacks) {
                sizes.add(stack.size());
            }
        } catch (RuntimeException e) {
            thrown.add(e);
        }
    })) {
        session.suspendNextExecution();
        context.eval("upstream-repro", "x");
    }
}
// sizes == [0], as expected; but thrown == [IndexOutOfBoundsException: Index: -1, Size: 0].
// Expected: no exception (the loop runs zero times).
```

Cause (from the 25.5 bytecode): when no frame has an asynchronous stack, the
`DebugAsyncStackFrameLists` constructor sets `size = 0`. `fillStacks(index)` returns early only
when `size > 0 && index >= size`, so with `size == 0` it goes on to search for more stacks after
the last one it has, `stacks.get(stacks.size() - 1)`, which is `stacks.get(-1)`. `size()` and
`isEmpty()` don't call `fillStacks` when `size >= 0`, so they work; `get(i)` and `iterator()`
don't. Testing `size >= 0` instead of `size > 0` would fix it. `DebugException.getDebugAsynchronousStacks()`
builds the same list class.
````

## ReportPolymorphism on an operation

**Found in** M5-10 (#92). **Test:** `UpstreamReportPolymorphismTest`.

**Upstream status** (checked 2026-11-17): **fixed on `master`**, not yet
released. Someone else reported it as
[oracle/graal#14871](https://github.com/oracle/graal/issues/14871) (a
regression since 25.1: 25.0 compiles it); GR-81207,
[oracle/graal#14872](https://github.com/oracle/graal/pull/14872), fixed it in
[`a9cfb3d`](https://github.com/oracle/graal/commit/a9cfb3d93e9d1e4fb3d910804ecb24089144ff22)
(2026-11-06), with megamorphic-only reporting covered too. It's in the
25.6.0-dev snapshots, not in 25.5.5.0.3, and the CHANGELOG doesn't mention
it. Don't file our issue text. On the upgrade to 25.6, this test fails:
then put `@ReportPolymorphism.Megamorphic` on `Call` and drop the workaround.

`@ReportPolymorphism` on a Bytecode DSL operation makes the DSL generate code
that doesn't compile. In the operation's generated node,
`checkForPolymorphicSpecialize` reads the bytecode array `$bc` and the
bytecode index `$bci` to get the operation's state, but neither is a field or
a parameter there.

The reproducer can't be part of the build, so the test runs `javac` on it, with
the DSL processor (the test runner passes its path as `-Dr7rs.truffle.dslProcessor`,
in `bazel/jvm_test.bzl`) and Truffle's jars from the test JVM's module
path: once as it is, and once without `@ReportPolymorphism`, which compiles.

- **Expected:** it compiles, like the same source without the annotation.
- **Actual:** 2 errors, `cannot find symbol` for `$bc` and for `$bci`, in
  `PolyRootGen.java`.
- **Our workaround:** `SchemeRootNode.Call` reports polymorphism itself: its
  `doIndirect` cache initializer calls `trampoline.megamorphic()`, which calls
  `reportPolymorphicSpecialize()` when the site goes megamorphic (since M7-01 a
  site reports only then, as `@ReportPolymorphism.Megamorphic` would).
  `Trampoline`'s tail-call caches, plain DSL nodes, report the same way.

### Issue text

````markdown
**Title:** Bytecode DSL: `@ReportPolymorphism` on an `@Operation` generates code that doesn't compile (`$bc`, `$bci` not found)

**Version:** Truffle 25.5.5.0.3 (GraalVM CE 25.5.5.0.3, JDK 25.0.5.0.3)

`@ReportPolymorphism` on a Bytecode DSL operation with two specializations generates an
operation node whose `checkForPolymorphicSpecialize` doesn't compile. Without the annotation,
the same source compiles.

Reproducer (compile with `truffle-api` on the class path and `truffle-dsl-processor` on the
processor path):

```java
package repro;

import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.bytecode.BytecodeRootNode;
import com.oracle.truffle.api.bytecode.GenerateBytecode;
import com.oracle.truffle.api.bytecode.Operation;
import com.oracle.truffle.api.dsl.ReportPolymorphism;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.nodes.RootNode;

@GenerateBytecode(languageClass = PolyRoot.Lang.class)
public abstract class PolyRoot extends RootNode implements BytecodeRootNode {

    protected PolyRoot(Lang language, FrameDescriptor frameDescriptor) {
        super(language, frameDescriptor);
    }

    public abstract static class Lang extends TruffleLanguage<Object> {
    }

    @Operation
    @ReportPolymorphism
    public static final class Describe {
        @Specialization
        static String doInt(int value) {
            return "int";
        }

        @Specialization
        static String doString(String value) {
            return "string";
        }
    }
}
```

```
repro/PolyRootGen.java:10487: error: cannot find symbol
            int state_0 = Short.toUnsignedInt(BYTES.getShort($bc, $bci + 2 /* imm state_0 */));
                                                             ^
  symbol:   variable $bc
  location: class Describe_Node
repro/PolyRootGen.java:10487: error: cannot find symbol
            int state_0 = Short.toUnsignedInt(BYTES.getShort($bc, $bci + 2 /* imm state_0 */));
                                                                  ^
  symbol:   variable $bci
  location: class Describe_Node
2 errors
```

The generated code:

```java
            } finally {
                if (oldState_0 != 0) {
                    checkForPolymorphicSpecialize(oldState_0);
                }
            }
        }

        private void checkForPolymorphicSpecialize(int oldState_0) {
            int state_0 = Short.toUnsignedInt(BYTES.getShort($bc, $bci + 2 /* imm state_0 */));
            int newState_0 = state_0;
            if (((oldState_0 ^ newState_0) != 0)) {
                this.reportPolymorphicSpecialize();
            }
        }
```

`executeAndSpecialize` has `$bc` and `$bci` as parameters, but doesn't pass them to
`checkForPolymorphicSpecialize`, which reads the operation's state from the bytecode.

The errors are the same when a specialization has cached state (`@Cached IndirectCallNode call`
added to `doInt`), where the operation's node isn't a shared singleton.
````

## ThreadDeath in generated roots

**Found in** #131. **Test:** none. It's a compiler warning, not behavior, and
the build shows it: drop `bytecode_root = True` from `//src/runtime` and javac
fails on it.

**Upstream status** (checked 2026-11-17): still present on `master`, and
unreported (no issue or pull request found). `master`'s
[`BytecodeNodeElement`](https://github.com/oracle/graal/blob/cd3747b70d9ece13c37de1471a7917af49652822/truffle/src/com.oracle.truffle.dsl.processor/src/com/oracle/truffle/dsl/processor/bytecode/generator/BytecodeNodeElement.java#L2223)
and `AbstractBytecodeNodeElement` still emit `instanceof ThreadDeath`, and
[`FixWarningsVisitor`](https://github.com/oracle/graal/blob/cd3747b70d9ece13c37de1471a7917af49652822/truffle/src/com.oracle.truffle.dsl.processor/src/com/oracle/truffle/dsl/processor/java/transform/FixWarningsVisitor.java#L101-L107),
unchanged since 25.5, still adds only `"deprecation"`: the issue text is
ready to file.

Every class the Bytecode DSL generates for a `@GenerateBytecode` root refers
to `java.lang.ThreadDeath`, which JDK 20 deprecated for removal, so javac
warns (`[removal]`) on generated code. The processor's own
`@SuppressWarnings` on the class has `"deprecation"`, which doesn't cover
removal warnings.

- **Expected:** generated code compiles without warnings under javac's
  defaults, as the DSL's other generated code does.
- **Actual:** `warning: [removal] ThreadDeath in java.lang has been deprecated
  and marked for removal`, once per root without tag instrumentation (the
  handler loop of `handleException`) and twice with it (also
  `resolveThrowable`). With `-Xlint:-removal`, javac prints a note instead
  (`... uses or overrides a deprecated API that is marked for removal`).
- **Cause** (from the processor's sources jar): `BytecodeNodeElement.createHandleException`
  writes `throwable instanceof ThreadDeath` whatever the root's
  configuration, and `createIsUnwindException` and (with tag
  instrumentation) the generated `resolveThrowable` do too. `FixWarningsVisitor`
  adds `"deprecation"` to the class's `@SuppressWarnings` when it sees a
  deprecated element, but never `"removal"` for one deprecated for removal.
- **Our workaround:** the libraries with a Bytecode DSL root (`//src/runtime`,
  and the tests' `UpstreamRoot`) compile with `-Xlint:-removal -XDsuppressNotes`
  (`BYTECODE_ROOT_JAVACOPTS`); every other library keeps removal warnings
  as errors. A generated class can't carry a `@SuppressWarnings` of ours, and
  the processor has no option for it.

### Issue text

````markdown
**Title:** Bytecode DSL: generated roots use `java.lang.ThreadDeath` without suppressing `removal` warnings

**Version:** Truffle 25.5.5.0.3 (GraalVM CE 25.5.5.0.3, JDK 25.0.5.0.3)

Compiling any `@GenerateBytecode` root with javac's default lint settings warns on the
generated code, because it tests `throwable instanceof java.lang.ThreadDeath` (deprecated for
removal since JDK 20). A build with `-Werror` fails; one with `-Xlint:-removal` prints a note per
generated root.

Reproducer (compile with `truffle-api` on the class path and `truffle-dsl-processor` on the
processor path):

```java
package repro;

import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.bytecode.BytecodeRootNode;
import com.oracle.truffle.api.bytecode.GenerateBytecode;
import com.oracle.truffle.api.bytecode.Operation;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.nodes.RootNode;

@GenerateBytecode(languageClass = MinRoot.Lang.class)
public abstract class MinRoot extends RootNode implements BytecodeRootNode {

    protected MinRoot(Lang language, FrameDescriptor frameDescriptor) {
        super(language, frameDescriptor);
    }

    public abstract static class Lang extends TruffleLanguage<Object> {
    }

    @Operation
    public static final class Identity {
        @Specialization
        static Object doObject(Object value) {
            return value;
        }
    }
}
```

```
$ javac -proc:full -processorpath truffle-dsl-processor-25.5.5.0.3.jar -cp truffle-api-25.5.5.0.3.jar:... -d out -s out repro/MinRoot.java
out/repro/MinRootGen.java:2119: warning: [removal] ThreadDeath in java.lang has been deprecated and marked for removal
                if (throwable instanceof java.lang.ThreadDeath) {
                                                  ^
1 warning
```

The generated class is annotated `@SuppressWarnings({"javadoc", "serial", "unused", "deprecation",
"static-method"})`; `"deprecation"` doesn't cover removal warnings. The reference comes from
`BytecodeNodeElement.createHandleException` (unconditionally), `createIsUnwindException`, and, with
`enableTagInstrumentation = true`, the generated `resolveThrowable`. `FixWarningsVisitor` adds
`"deprecation"` when it sees a deprecated element; adding `"removal"` too when the element is
deprecated for removal would fix it.
````

## Interrupting a Bytecode DSL root as it returns

**Found in** M8-06. **Test:** `UpstreamInterruptTest`.

**Upstream status** (checked 2026-11-17): still present on `master` as far
as the source shows, and unreported (no issue or pull request found).
`OptimizedCallTarget` still polls with the root as the location
([L615](https://github.com/oracle/graal/blob/cd3747b70d9ece13c37de1471a7917af49652822/truffle/src/com.oracle.truffle.runtime/src/com/oracle/truffle/runtime/OptimizedCallTarget.java#L615),
[L905](https://github.com/oracle/graal/blob/cd3747b70d9ece13c37de1471a7917af49652822/truffle/src/com.oracle.truffle.runtime/src/com/oracle/truffle/runtime/OptimizedCallTarget.java#L905)),
the interrupt exception still takes the poll's location, and the generated
`findBytecodeIndex` keeps the assertion
([`BytecodeRootNodeElement`](https://github.com/oracle/graal/blob/cd3747b70d9ece13c37de1471a7917af49652822/truffle/src/com.oracle.truffle.dsl.processor/src/com/oracle/truffle/dsl/processor/bytecode/generator/BytecodeRootNodeElement.java#L1031)).
GR-72833 (25.6) lets a Truffle exception carry its own bytecode index, which
`TruffleStackTrace.fillIn` uses before calling `findBytecodeIndex`, but the
interrupt exception doesn't set one. The issue text is ready to file.

`Context.interrupt` throws Truffle's interrupt exception at the next safepoint
the interrupted thread polls, with the poll's node as its location. Truffle
also polls when a root returns (`OptimizedCallTarget.executeRootNode` calls
`TruffleSafepoint.poll(rootNode)` after `rootNode.execute`), so an interrupt
that lands there is located at the root itself. With a Bytecode DSL root and
assertions on, filling in the `PolyglotException`'s stack trace then calls
the generated root's `findBytecodeIndex` with the root as the node, which
asserts it isn't one.

- **Expected:** `Context.eval` throws a `PolyglotException` whose
  `isInterrupted()` is true.
- **Actual,** with `-ea`: `AssertionError: A BytecodeRootNode should not be
  used as a call location.`, from the generated `findBytecodeIndex`, out of
  `PolyglotImpl.guestToHostException`. Without assertions the interrupt is
  right (the generated method reads the bytecode index from the frame).
- **Our workaround:** in tests only. The launcher runs without assertions.
  `InterruptTest` turns assertions off in `scheme.runtime.SchemeRootNodeGen`
  before the class is loaded (each test class runs in its own JVM). Any
  embedder that interrupts with assertions on can hit it: in a Scheme loop of
  calls, most interrupts land at a return.

### Issue text

````markdown
**Title:** Bytecode DSL: `Context.interrupt` landing at a root's return poll fails `findBytecodeIndex`'s assertion

**Version:** Truffle 25.5.5.0.3 (GraalVM CE 25.5.5.0.3, JDK 25.0.5.0.3)

`OptimizedCallTarget.executeRootNode` polls a safepoint after the root returns, with the root
node as the location. When `Context.interrupt` lands at that poll in a `@GenerateBytecode`
root, the interrupt exception's location is the root, and filling in the `PolyglotException`'s
stack trace calls the generated `findBytecodeIndex(node, frame)` with it. With assertions
enabled that fails:

```
java.lang.AssertionError: A BytecodeRootNode should not be used as a call location.
    at ...RootGen.findBytecodeIndex(...)
    at com.oracle.truffle.api.nodes.NodeAccessor$AccessNodes.findBytecodeIndex(NodeAccessor.java:208)
    at com.oracle.truffle.api.TruffleStackTrace.fillIn(TruffleStackTrace.java:282)
    at com.oracle.truffle.polyglot.PolyglotExceptionImpl.<init>(PolyglotExceptionImpl.java:130)
    at com.oracle.truffle.polyglot.PolyglotImpl.guestToHostException(PolyglotImpl.java:1224)
    at com.oracle.truffle.polyglot.PolyglotContextImpl.eval(PolyglotContextImpl.java:1920)
    at org.graalvm.polyglot.Context.eval(Context.java:423)
```

Expected: a `PolyglotException` whose `isInterrupted()` is true, as without assertions.

Reproducer, with a language whose program is a Bytecode DSL root of one `StatementTag` around
`Return(LoadConstant 42)` (ours is `upstream-repro`), run with `-ea`: suspend the program at its
statement with the debugger, interrupt from another thread, and resume; the root returns and the
interrupt lands at the poll after it.

```java
AtomicReference<Throwable> thrown = new AtomicReference<>();
CountDownLatch suspended = new CountDownLatch(1);
CountDownLatch resume = new CountDownLatch(1);
try (Context context = Context.create("upstream-repro")) {
    try (DebuggerSession session = Debugger.find(context.getEngine()).startSession(event -> {
        suspended.countDown();
        try { resume.await(); } catch (InterruptedException e) { }
    })) {
        session.suspendNextExecution();
        Thread evaluation = new Thread(() -> {
            try {
                context.eval("upstream-repro", "x");
            } catch (Throwable t) {
                thrown.set(t);
            }
        });
        evaluation.start();
        suspended.await();
        Thread interrupt = new Thread(() -> {
            try { context.interrupt(Duration.ZERO); } catch (TimeoutException e) { }
        });
        interrupt.start();
        while (interrupt.getState() != Thread.State.WAITING) {
            Thread.sleep(1);
        }
        resume.countDown();
        evaluation.join();
        interrupt.join();
    }
}
// thrown: AssertionError: A BytecodeRootNode should not be used as a call location.
```

The assertion in the generated `findBytecodeIndex` could accept the root itself (the frame
holds the bytecode index), or the poll could pass a location the root's bytecode node owns.
````

## Context pre-initialization with synchronous compilation

**Found in** M8-07 (#135). **Test:** `UpstreamPreinitializationDeadlockTest`.

**Upstream status** (checked 2026-11-17): still present on `master` as far
as the source shows, and unreported (no issue or pull request found).
[`Engine$ImplHolder`'s initializer](https://github.com/oracle/graal/blob/cd3747b70d9ece13c37de1471a7917af49652822/sdk/src/org.graalvm.polyglot/src/org/graalvm/polyglot/Engine.java#L203-L211),
[`createPolyglotThreadScope`](https://github.com/oracle/graal/blob/cd3747b70d9ece13c37de1471a7917af49652822/truffle/src/com.oracle.truffle.polyglot/src/com/oracle/truffle/polyglot/EngineAccessor.java#L2143-L2145)
and `HotSpotTruffleRuntime.ensureInitialized` are unchanged since 25.5. The
open, work-in-progress
[oracle/graal#14788](https://github.com/oracle/graal/pull/14788) (GR-79669,
pre-initialized explicit engines) touches `Engine` but not this path. The
issue text is ready to file.

On HotSpot, Truffle pre-initializes a context in the first use of the
polyglot API when the system property
`polyglot.image-build-time.PreinitializeContexts` is set, as native-image
does at build time: `Engine$ImplHolder`'s class initializer calls
`PolyglotImpl.preInitializeEngine`. With
`-Dpolyglot.engine.BackgroundCompilation=false` (part of our compile mode;
`CompileImmediately` isn't needed), that process deadlocks.

- **Expected:** the process runs as it does with background compilation (the
  test's control case): the first context is the pre-initialized one,
  patched, and its program prints 42.
- **Actual:** `Context.create` never returns. The test gives the child JVM
  20 seconds, then has it dump its threads, and kills it:
  - `main` is in `Engine$ImplHolder.<clinit>` →
    `PolyglotEngineImpl.preInitialize` → `addContext` →
    `ensureRuntimeInitialized`, where
    `HotSpotTruffleRuntime.ensureInitialized` waits for the runtime's first
    compilation (`OptimizedTruffleRuntime.finishCompilation` →
    `CompilationTask.awaitCompletion`).
  - `TruffleCompilerThread-<n>` hasn't started that compilation: its
    thread factory calls `EngineAccessor$EngineImpl.createPolyglotThreadScope`
    → `PolyglotImpl.findInstance` → `Engine.getImpl`, which waits for
    `Engine$ImplHolder`'s class initialization (jstack: "waiting on the Class
    initialization monitor for org.graalvm.polyglot.Engine$ImplHolder").
- **Cause:** the compiler thread needs `Engine$ImplHolder` initialized before
  it can compile anything, and the class initializer waits for a compilation
  when compilation is synchronous. It happens before any language is
  involved: the property's value doesn't matter (an empty value or an
  unknown language hangs the same way), and on every run, since the compiler
  thread is always created inside the class initializer.
- **Our workaround:** in tests only. `ContextPreinitializationTest` runs each
  check in a child JVM that sets the property and doesn't take the test JVM's
  engine options, so its compile-mode run doesn't hang. Native-image doesn't
  compile at build time, so `//:scheme-native`'s pre-initialized context is
  unaffected. An embedder that sets the property on HotSpot with
  `BackgroundCompilation=false` hangs.

### Issue text

````markdown
**Title:** HotSpot: context pre-initialization deadlocks with `engine.BackgroundCompilation=false`

**Version:** Truffle 25.5.5.0.3 (GraalVM CE 25.5.5.0.3, JDK 25.0.5.0.3), on Linux x86_64 and macOS aarch64

On HotSpot, setting `polyglot.image-build-time.PreinitializeContexts` makes the first use of the
polyglot API pre-initialize a context (`Engine$ImplHolder.<clinit>` →
`PolyglotImpl.preInitializeEngine`). With `-Dpolyglot.engine.BackgroundCompilation=false` too, it
deadlocks, every time, before any language code runs:

```java
public static void main(String[] args) {
    try (Context context = Context.create()) { // never returns
        System.out.println("created");
    }
}
```

```
java -Dpolyglot.image-build-time.PreinitializeContexts= -Dpolyglot.engine.BackgroundCompilation=false \
    --module-path <polyglot, truffle-api, truffle-runtime, truffle-compiler, ...> --add-modules ALL-MODULE-PATH Main
```

(The property's value doesn't matter: naming a language that supports pre-initialization hangs the
same way. Without `BackgroundCompilation=false` it prints `created`.)

The main thread waits for the runtime's first compilation inside `Engine$ImplHolder`'s class
initializer:

```
"main" WAITING (parking)
    at java.util.concurrent.FutureTask.get(FutureTask.java:192)
    at com.oracle.truffle.runtime.CompilationTask.awaitCompletion(CompilationTask.java:197)
    at com.oracle.truffle.runtime.OptimizedTruffleRuntime.uninterruptibleWaitForCompilation(OptimizedTruffleRuntime.java:871)
    at com.oracle.truffle.runtime.OptimizedTruffleRuntime.finishCompilation(OptimizedTruffleRuntime.java:847)
    at com.oracle.truffle.runtime.OptimizedCallTarget.maybeWaitForTask(OptimizedCallTarget.java:1036)
    at com.oracle.truffle.runtime.hotspot.HotSpotTruffleRuntime.ensureInitialized(HotSpotTruffleRuntime.java:281)
    at com.oracle.truffle.runtime.hotspot.HotSpotTruffleRuntime.createOptimizedCallTarget(HotSpotTruffleRuntime.java:364)
    at com.oracle.truffle.runtime.OptimizedRuntimeSupport.newCallTarget(OptimizedRuntimeSupport.java:88)
    at com.oracle.truffle.api.nodes.RootNode.initializeTarget(RootNode.java:463)
    at com.oracle.truffle.api.nodes.RootNode.getCallTarget(RootNode.java:451)
    at com.oracle.truffle.polyglot.PolyglotEngineImpl.ensureRuntimeInitialized(PolyglotEngineImpl.java:531)
    at com.oracle.truffle.polyglot.PolyglotEngineImpl.addContext(PolyglotEngineImpl.java:1176)
    at com.oracle.truffle.polyglot.PolyglotContextImpl.preinitialize(PolyglotContextImpl.java:3898)
    at com.oracle.truffle.polyglot.PolyglotEngineImpl.preInitialize(PolyglotEngineImpl.java:1681)
    at com.oracle.truffle.polyglot.PolyglotImpl.preInitializeEngine(PolyglotImpl.java:781)
    at org.graalvm.polyglot.Engine$ImplHolder.debugContextPreInitialization(Engine.java:205)
    at org.graalvm.polyglot.Engine$ImplHolder.<clinit>(Engine.java:210)
    at org.graalvm.polyglot.Engine.getImpl(Engine.java:573)
    at org.graalvm.polyglot.Engine$Builder.build(Engine.java:1027)
    at org.graalvm.polyglot.Context$Builder.build(Context.java:2262)
    at org.graalvm.polyglot.Context.create(Context.java:1055)
```

and the compiler thread that should run that compilation waits for the same class initialization
before it starts:

```
"TruffleCompilerThread-36" RUNNABLE
    at org.graalvm.polyglot.Engine.getImpl(Engine.java:573)
    - waiting on the Class initialization monitor for org.graalvm.polyglot.Engine$ImplHolder
    at java.lang.reflect.Method.invoke(Method.java:565)
    at com.oracle.truffle.polyglot.PolyglotImpl.findImpl(PolyglotImpl.java:166)
    at com.oracle.truffle.polyglot.PolyglotImpl.findInstance(PolyglotImpl.java:177)
    at com.oracle.truffle.polyglot.EngineAccessor$EngineImpl.createPolyglotThreadScope(EngineAccessor.java:2144)
    at com.oracle.truffle.runtime.BackgroundCompileQueue$TruffleCompilerThreadFactory$1.run(BackgroundCompileQueue.java:443)
```

Native-image doesn't compile at image build time, so this only affects the HotSpot path, which is
the one for testing a language's `patchContext` on the JVM. Possible fixes: don't wait for the
runtime's initial compilation (or compile it in the calling thread) during pre-initialization, or
have `createPolyglotThreadScope` find the polyglot implementation without going through
`Engine.getImpl`.
````

## Instrumented compilations fail in libgraal

**Found in** M10-04 (#205 saw `--compiler.InstrumentBoundaries` print nothing). **Test:** none
yet: the reproducer below uses the launcher, and an inverted test needs `UpstreamRoot` to call a
`@TruffleBoundary` method (a follow-up). The bug is in the Graal compiler as built into libgraal and
into a native image, not in Truffle's API.

**Upstream status** (checked 2026-11-17): no issue or pull request found for
`InstrumentBoundaries` with this exception.

With `compiler.InstrumentBoundaries` or `compiler.InstrumentBranches` (or their deprecated
`engine.` names) on, every Truffle compilation that instruments a boundary or a branch fails in
libgraal, GraalVM CE 25.5.5.0.3's default compiler on HotSpot, while lowering a `LoadIndexed`
from the instrumentation's counter table, a `SnippetObjectConstant` that libgraal's constant
reflection can't read:

```
java.lang.ClassCastException: jdk.graal.compiler.hotspot.SnippetObjectConstant cannot be cast to jdk.vm.ci.hotspot.HotSpotObjectConstantImpl
	at jdk.vm.ci.hotspot.HotSpotConstantReflectionProvider.readArrayLength(HotSpotConstantReflectionProvider.java:81)
	at jdk.graal.compiler.nodes.ConstantNode.findLength(ConstantNode.java:546)
	at jdk.graal.compiler.nodes.util.GraphUtil.arrayLength(GraphUtil.java:872)
	at jdk.graal.compiler.nodes.java.ArrayLengthNode.readArrayLength(ArrayLengthNode.java:206)
	at jdk.graal.compiler.replacements.DefaultJavaLoweringProvider.readOrCreateArrayLength(DefaultJavaLoweringProvider.java:1553)
	at jdk.graal.compiler.replacements.DefaultJavaLoweringProvider.getBoundsCheck(DefaultJavaLoweringProvider.java:1544)
	at jdk.graal.compiler.replacements.DefaultJavaLoweringProvider.lowerLoadIndexedNode(DefaultJavaLoweringProvider.java:576)
	...
	at jdk.graal.compiler.truffle.TruffleCompilerImpl.compilePEGraph(TruffleCompilerImpl.java:659)
	at jdk.graal.compiler.libgraal.truffle.LibGraalTruffleEntryPoints.doCompile(LibGraalTruffleEntryPoints.java:207)
```

Compilation failures aren't printed by default (`engine.CompilationFailureAction=Silent`), so the
program runs in the interpreter and the *"Execution profile"* printed at exit is empty: the option
looks ignored. Reproduce in any embedding on libgraal (the launcher rejects the option there now),
with a loop that calls a boundary (`number->string`):

```java
try (Context context = Context.newBuilder("scheme").allowExperimentalOptions(true)
                .option("compiler.InstrumentBoundaries", "true").option("engine.BackgroundCompilation", "false")
                .option("engine.CompilationFailureAction", "Print").build()) {
    context.eval("scheme", """
                    (import (scheme base))
                    (define (h i acc) (if (= i 0) acc (h (- i 1) (+ acc (string-length (number->string i))))))
                    (h 1000000 0)
                    """);
}
```

With
`-XX:-UseJVMCINativeLibrary` (Graal compiled for HotSpot) the same compilations succeed and the
profile lists the boundaries (`NumberPrimitives$NumberToString.invoke`, `SString.of`). The native
launcher's runtime compiler (SubstrateVM) stops the process instead: *"Fatal error: guarantee
failed"*, status 134.

- **Expected:** the instrumented compilations succeed and the profile is printed at exit, as with
  Graal compiled for HotSpot.
- **Actual:** every instrumented compilation fails with the `ClassCastException` above (libgraal),
  or the process dies (native image).

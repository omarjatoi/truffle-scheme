# Upstream reports

Bugs in Truffle that this implementation works around, each with a minimal
reproducer that doesn't use Scheme. Filing them at
<https://github.com/oracle/graal/issues> is a human's call: nothing here has
been filed. Each section has the issue text, ready to paste.

All four are in Truffle 25.5.5.0.3 (Maven Central), on GraalVM CE 25.5.5.0.3
(JDK 25.0.5.0.3, `nix develop`).

## The reproducers are inverted tests

Each reproducer is a JUnit test in `tests/scheme/test/upstream/` (package
`scheme.test.upstream`) that asserts the buggy behavior, so it **passes while
the bug is there**. It runs with every other unit test in `bazel test //...`, and
alone with:

```
bazel test //tests:unit_jit_UpstreamAsyncStacksTest //tests:unit_jit_UpstreamNestedRootSourceTest //tests:unit_jit_UpstreamReportPolymorphismTest
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

## Nested root source sections

**Found in** M5-05 (#97). **Test:** `UpstreamNestedRootSourceTest`.

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

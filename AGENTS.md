# AGENTS.md

R7RS-small Scheme on GraalVM Truffle (Bytecode DSL). Goal: full R7RS-small
conformance, including re-entrant multi-shot continuations and proper tail
calls, with JIT performance.

## Read first

- `docs/design.md`: architecture and the **Decisions** section. Don't
  re-propose an option listed there as rejected without new evidence. If you
  think a decision is wrong, change it in `docs/design.md` in a PR of its
  own that says why, before (not in) the task that depends on it.
- `docs/plan.md`: current milestone, task checklist, and the generated status
  block. Your task is one checklist item there.

## When the user says "start"

Take the next task all the way to a merged PR, then carry on with the next
one. There are no human gates: don't wait for approval, and merge your own
PR once it's green.

1. **Sync.** `git fetch origin && git switch main && git pull --ff-only`.
   If a PR is still open (`gh pr list`), finish it first: address its
   review, fix red checks, or merge it if it's green.
2. **Choose** the first unchecked task in the current milestone (the first
   one in `docs/plan.md` with unchecked tasks) whose dependencies are
   checked. If none qualifies, take the next milestone's *(early)* tasks.
   If the milestone's exit criteria are met, draft the next milestone's
   tasks in the same format, as a PR titled `Plan: Draft the M<n> tasks`.
   Say in one line what you picked.
3. **Work** on a branch `<id>-<short-name>`, following "Working style".
   Tick the task's checkbox in `docs/plan.md` (outside the status block).
   Run `bazel test //...` before opening the PR (AGENTS.md "Commands").
4. **Open the PR** (not a draft), titled `<ID>: <what the change does>`,
   for example `M2-05: Spill deep non-tail recursion to the heap at a depth
   threshold`. The title becomes the squash commit on `main`
   (`<title> (#n)`, empty body), so it describes the change rather than
   repeating the task name.
5. **Merge** once the Buildkite build, `buildkite/truffle-scheme`, is
   green (`gh pr checks <n> --watch`):
   `gh pr merge <n> --squash --delete-branch`. If a check fails, fix it on
   the branch. Never merge red.
6. **Finish** with the PR link and one line of evidence for each "Done
   when" item, then go back to step 1.

## Reviewing

Only when asked. You didn't write the code you're reviewing. Check the PR against the rules in
this file, the decisions in `docs/design.md`, and the task's "Done when"
items in `docs/plan.md`. Post one PR comment that starts with `Agent review`,
listing violations and claims without evidence, most serious first. Don't
push fixes.

## Environment

Run everything inside `nix develop`. It provides `bazel` (bazelisk, at
`.bazelversion`), Python, git, `gh`, chibi-scheme and a GraalVM CE 25.5 JDK
(`JAVA_HOME`). Bazel downloads its own copy of that GraalVM release
(`bazel/graalvm.bzl`): it compiles and runs everything, and its Graal
compiler is the JIT the tests use. It also builds the chibi-scheme the
differential tests compare against, from the same release's source
(`bazel/chibi.bzl`). Truffle comes
from Maven Central at exactly the GraalVM version (`TRUFFLE_VERSION` in
`MODULE.bazel`); the build fails if the two differ (design.md D11).

## Commands

Bazel builds and tests everything (`MODULE.bazel`, design.md D11). Every
build step and test is cached on its inputs, so a run redoes only what a
change affects. The repository's other commands are `bazel run //:r7rs --
<command>` (`tests/harness/main.py`).

```
bazel test //...                          # all checks and tests: run this before opening a PR
bazel test --test_tag_filters=lint //...  # just the static checks (CI: checks)
bazel build //...                         # build everything (javac, warnings are errors)
bazel test //tests:unit_jit               # JUnit in tests/scheme/test, Graal JIT in the background (a test per class)
bazel test //tests:unit_compile           # same, compile immediately; failures are errors
bazel test //tests:unit_torture           # same, capture + resume at every non-tail call
bazel test //tests:unit_jit_ReaderTest    # one class in one mode
bazel test //tests:conformance            # status block in docs/plan.md is current; torture passes as much
bazel test //tests:diff //tests:spec      # tests/diff vs chibi-scheme (and under torture); tests/spec
bazel test //tests:space //tests:tools    # tail loop and deep recursion under -Xss256k; the tools' smoke tests
bazel test //src/runtime:all              # one project's static checks (format, checkstyle, ecj, spotbugs)
bazel build //:scheme                     # the launcher: bazel-bin/scheme [-I dir]... [file.scm [arg]...]
bazel-bin/scheme --dap file.scm           # with GraalVM's tools: --inspect, --dap, --cpusampler, --memtracer
bazel run //:r7rs -- test                 # both conformance suites, interpreter only, a line per section
bazel run //:r7rs -- test -v 6.10         # one section (chibi's 6.10, Larceny's base, ...), listing failing tests
bazel run //:r7rs -- test --compile       # Graal JIT, compile immediately (also --torture, --suite larceny)
bazel run //:r7rs -- status               # regenerate the status block in docs/plan.md
bazel run //:r7rs -- diff path/to/file.scm   # run under chibi and under us; compare stdout + exit status
bazel run //:r7rs -- format               # format Java code (GraalVM's Eclipse formatter settings)
bazel run //:r7rs -- readme               # run docs/usage.md's and docs/embedding.md's commands, check their samples
bazel run //:r7rs -- native-image         # the native-image launcher, native/scheme (design.md D17; minutes, ~3 GB)
bazel run //:r7rs -- test --native native/scheme   # both conformance suites through it
bazel run //:r7rs -- benchmark ecraven:fib         # one benchmark program, JIT (bench/README.md: options, failures)
bazel run //:r7rs -- benchmark larceny:* --mode=*  # every program of a set, JIT and interpreter
bazel run //:r7rs -- bench-check          # benchmark subset vs bench/thresholds.json, fails on a regression
nix develop .#bench -c bazel run //:r7rs -- bench-report   # the comparative report, docs/benchmarks.md
bazel run @jvm//:pin                      # after changing a Maven artifact in MODULE.bazel
```

A failing test's output is printed; every test's is in
`bazel-testlogs/<package>/<test>/test.log`. A new test class
(`tests/scheme/test/**/*Test.java`), `tests/diff` program or `tests/spec`
case is picked up by itself; a new source directory needs a `BUILD.bazel`
like its neighbours'.

Before opening a PR: `bazel test //...`; if you changed docs/usage.md,
docs/embedding.md or what they run, also `bazel run //:r7rs -- readme`. If
you touched `src/runtime` or any Bytecode DSL operation, also run `bazel run
//:r7rs -- test --compile`. If the change may affect performance, run
`bazel run //:r7rs -- bench-check`. A PR that changes performance on purpose
commits new thresholds and says why (bench/README.md "Thresholds"); never
raise one by hand. If it changes the native-image configuration, run `bazel
run //:r7rs -- readme --native`.

CI runs on Buildkite (`.buildkite/pipeline.yml`), for every commit, and only
what runs on every commit: `checks` (the lint tests), `tests` (every other
test and the readme check), and soft-fail `conformance-*` steps (interpreter
and `--compile`) whose annotations show per-section results. The required
check is the build, `buildkite/truffle-scheme`; which steps can fail it is
set in `.buildkite/pipeline.yml`.
Benchmarks, the threshold check and the native image are local commands.

## Rules (each one is enforced by `bazel test //...`)

1. **Java builtins never call Scheme directly.** A builtin that must invoke a
   procedure (`apply`, `call/cc`, `call-with-values`, `eval`) returns a
   `TailCall` request instead. Anything that needs a non-tail call (`map`,
   `for-each`, `dynamic-wind`, `raise`, ...) lives in the Scheme prelude
   (design.md D5). Check: `//tests:rules`.
2. **Every non-tail call site is emitted by `CallSiteEmitter`.** Never emit a
   raw call operation. Check: torture mode.
3. **Continuation segments are immutable.** Resuming always goes through
   `Segment.resume`, which copies the frame. Check: torture mode resumes every
   segment twice.
4. **No Java recursion proportional to Scheme depth.** This includes the
   reader, printer, `equal?`, and the expander: use explicit stacks. Check:
   `tests/spec/deep-*`.
5. **Tail calls use constant space.** Check: `tests/spec/tail-*`, which run
   under a memory limit.

## Never

- Edit anything under `tests/r7rs/` or `tests/r7rs-larceny/` (except the
  `## Exceptions` table in `tests/r7rs-larceny/README.md`, which lists tests
  that expect a result where R7RS says "it is an error"; adding a row needs
  the R7RS text that says so), change or delete an existing case in
  `tests/spec/`, or edit the status block in `docs/plan.md` by hand. `bazel run //:r7rs -- status` regenerates the block, and
  `bazel test //...` fails if it's stale, so commit the regenerated block with your change. If a test looks wrong, explain why in the
  PR and stop.
- Skip, disable, or loosen a test to make it pass. That includes listing a
  `tests/spec/` case in `SPEC_PENDING` (`tests/BUILD.bazel`) once it has
  passed: an entry goes away when the task it names lands, and never comes
  back.
- Merge a PR whose Buildkite build isn't green, or bypass branch
  protection.
- Change the GraalVM or Truffle version (`bazel/graalvm.bzl`,
  `TRUFFLE_VERSION` in `MODULE.bazel` and nixpkgs in `flake.lock` move
  together), change `.bazelversion`, add libraries (Maven artifacts in
  `MODULE.bazel`), or change `flake.nix`/`flake.lock`, unless the task says to.
- Edit the vendored benchmark sets in `bench/r7rs-ecraven/` and
  `bench/r7rs-larceny/` other than their `README.md` (`bazel run //:r7rs --
  vendor-benchmarks` re-vendors them). A program that fails goes in `bench/README.md`'s Failures
  table, with its reason.

## Truffle conventions

- Put `@TruffleBoundary` on anything that shouldn't be partially evaluated:
  Java collections, string formatting, `BigInteger` arithmetic, recursion,
  I/O.
- Express behavior as `@Specialization`s on Bytecode DSL operations.
  Hand-written nodes need a reason in the PR description.
- Before mutating node state on a rare path, call
  `CompilerDirectives.transferToInterpreterAndInvalidate()`. The DSL does
  this for specializations; you do it everywhere else.
- Global bindings use one `Assumption` per cell. Compiled code never reads
  mutable global state without one.
- Fixnum operations overflow into `SBigInteger` and never wrap. Big results
  that fit in a `long` are demoted back.
- Variables targeted by `set!` live in a `Box`, never directly in a frame
  slot (design.md D2). Frames are copied on continuation resume.
- All I/O goes through `TruffleLanguage.Env` (`TruffleFile`, Env streams),
  never `java.io` or `java.nio` directly. Text operations use `Locale.ROOT`.
- Primitive errors throw `SchemeError`, never a raw Java exception
  (design.md D8).
- Format Java with `bazel run //:r7rs -- format` before committing. The lint
  tests fail if the formatter would change anything, and also run Checkstyle,
  ECJ and SpotBugs.
- Only projects that need generated code set `truffle_dsl = True` in their
  `BUILD.bazel`. Keep it that way so edits elsewhere don't re-run the processor.

## Working style

- One task, one PR. Out-of-scope findings go under **Follow-ups** in the PR
  description. Don't fix them in the same PR.
- Write the failing test first, in `tests/scheme/test/`. If you think a case
  belongs in `tests/spec/`, add it in a separate PR whose only change is
  the new case, in the format `tests/spec/README.md` describes.
- If the same failure survives two different fix attempts, stop and write up
  what you tried and what you observed.
- In the PR description, include what changed, which conformance sections
  changed state, any follow-ups, and anything that surprised you.

## Areas

M4 runs one agent per area, in parallel. Each area owns its files, so two
areas never edit the same file. To add a primitive, add its class and its
`PrimitiveSpec` to the area's builtins file. To add a procedure written in
Scheme, add it to the area's prelude file and its name to the area's
declarations file's `export`. Don't edit another area's files, the standard
libraries (`src/prelude/scheme/prelude/scheme.*.sld`, which already export
every R7RS name and pick up new definitions by themselves), `Builtins.java`
or `r7rs.prelude.sld`. Builtins are in `src/builtins/scheme/builtins/`;
prelude and declarations files are in `src/prelude/scheme/prelude/`.

| Area | R7RS | Builtins | Prelude | Declarations |
|---|---|---|---|---|
| Numeric tower | §6.2 | `NumberPrimitives.java` (+ `Arithmetic*.java`) | `numbers.scm` | `numbers.decls.scm` |
| Characters, strings, symbols | §6.5–6.7 | `CharStringPrimitives.java` | `chars-strings.scm` | `chars-strings.decls.scm` |
| Lists and vectors | §6.4, §6.8 | `ListVectorPrimitives.java` (+ `Lists.java`) | `lists-vectors.scm` | `lists-vectors.decls.scm` |
| Bytevectors | §6.9 | `BytevectorPrimitives.java` | `bytevectors.scm` | `bytevectors.decls.scm` |
| Control and exceptions | §6.1, §6.10, §6.11 | `ControlPrimitives.java` | `control.scm` | `control.decls.scm` |
| I/O and ports | §6.13 | `IoPrimitives.java` | `io.scm` | `io.decls.scm` |
| `eval` and environments | §6.12 | `EvalPrimitives.java` | `eval.scm` | `eval.decls.scm` |
| Process context and time | §6.14 | `ProcessTimePrimitives.java` | `process-time.scm` | `process-time.decls.scm` |

- All areas share one namespace, `(r7rs %prelude)`, so they can use each
  other's procedures inside procedure bodies. Top-level code in a prelude file
  runs when the library loads, so it may only call primitives and procedures
  of areas included before it, in `r7rs.prelude.sld`'s order (numbers,
  chars-strings, lists-vectors, bytevectors, control, io, eval,
  process-time). For example, io.scm's `current-output-port` is a
  `make-parameter` from control.
- A primitive's name must be unique across areas; the language refuses to
  start if two areas register the same one.
- An area's tests go in their own files: `tests/scheme/test/<Area>Test.java`
  and `tests/diff/<area>-*.scm`.
- Shared helpers (`TypePredicate.java`, `Lists.java`) are for using, not for
  adding area-specific code to.

## Layout

```
MODULE.bazel               Bazel modules, TRUFFLE_VERSION, Maven artifacts (locked in maven_install.json)
BUILD.bazel                the scheme, scheme.launcher modules (jars), the prelude image, bazel-bin/scheme
bazel/                     the GraalVM toolchain, module jars (java_module.bzl), lint tests (lint/)
flake.nix, flake.lock      dev shell: bazel, Python, gh, chibi-scheme, GraalVM 25.5
src/reader/                reader + datum labels
src/compiler/              expander + core AST -> Bytecode DSL builder calls
src/runtime/               Bytecode DSL root + operations, continuations
                           (Capture, Segment), trampoline, DynEnv
src/builtins/              leaf primitives only (see rule 1)
src/prelude/               standard libraries (.sld) and prelude Scheme code
src/launcher/              host-side embedding: the `scheme` launcher, the conformance runner
tests/r7rs/                chibi suite, vendored, read-only (see its README)
tests/r7rs-larceny/        Larceny's R7RS suite, vendored, read-only (see its README)
bench/r7rs-ecraven/        ecraven's R7RS benchmarks, vendored, read-only (see its README)
bench/r7rs-larceny/        Larceny's R7RS benchmarks, vendored, read-only (see its README)
bench/postlude.scm         our postlude for them; bench/README.md: running, failures
tests/lib/                 test-only libraries: the (chibi test) and (tests scheme test) shims
tests/harness/             test drivers and the `bazel run //:r7rs` commands (main.py)
tests/diff/                programs that must match chibi-scheme; add freely
tests/space/               programs run under small JVM limits (//tests:space)
tests/tools/               programs the tools tests debug and profile (tests/harness/tools.py names their lines)
tests/spec/                tail-call / continuation / depth cases; existing cases read-only
tests/scheme/test/         JUnit unit tests; add freely
docs/examples/             programs and sources that docs/usage.md and docs/embedding.md run (the readme check)
```

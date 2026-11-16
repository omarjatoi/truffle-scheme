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
   Run `mx checks && mx tests` before opening the PR.
4. **Open the PR** (not a draft), titled `<ID>: <what the change does>`,
   for example `M2-05: Spill deep non-tail recursion to the heap at a depth
   threshold`. The title becomes the squash commit on `main`
   (`<title> (#n)`, empty body), so it describes the change rather than
   repeating the task name.
5. **Merge** once `Checks` and `Tests` are green (`gh pr checks <n> --watch`):
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

Run everything inside `nix develop`. It provides GraalVM CE 25.5 (already set
as `JAVA_HOME`; its Graal compiler is the JIT), `mx`, Python, git, `gh` and
chibi-scheme (used for differential testing). There's no JDK to fetch by
hand. Truffle comes from Maven Central
as libraries in `mx.r7rs/suite.py`, at exactly the GraalVM version. mx refuses
to run if the two differ (design.md D11).

## Commands

The suite is `mx.r7rs/`. Custom commands live in
`mx.r7rs/mx_r7rs.py`. A command whose task hasn't landed yet exits nonzero
and names that task.

```
mx checks                                 # build (ECJ + javac, warnings are errors) and static checks
mx checks format spotbugs                 # just those steps (see `mx checks nope` for the list)
mx tests                                  # all tests (unit, conformance status, differential, spec, torture, tools, bench, readme)
mx tests unittest diff                    # just those steps
mx build                                  # build everything
mx unittest                               # JUnit tests in tests/scheme/test (Graal JIT on, background)
mx r7rs-unittest --compile                # same, compile immediately; failures are errors
mx r7rs-test                              # both conformance suites, interpreter only (the fast loop)
mx r7rs-test --suite larceny              # just one suite: chibi (tests/r7rs) or larceny (tests/r7rs-larceny)
mx r7rs-test -v 6.10                      # one section, listing failing tests
mx r7rs-test 6.10                         # one conformance section (chibi's 6.10, or Larceny's base, ...)
mx r7rs-test --torture                    # capture + resume at every non-tail call
mx r7rs-test --compile                    # Graal JIT; compile immediately; failures are errors
mx r7rs-native-image                      # build the native-image `scheme` launcher (design.md D17; minutes, ~3 GB)
mx r7rs-test --native                     # both conformance suites through the native launcher
mx r7rs-spec                              # tests/spec (tail-call space, continuations, depth)
mx scheme [-I dir]... file.scm [arg]...   # the launcher: run a program (--help lists the engine/log options)
mx scheme                                 # no file: a REPL on stdin
mx scheme --dap file.scm                  # with the tools (SCHEME_TOOLS): --inspect, --dap, --cpusampler, --memtracer
mx r7rs-tools                             # smoke tests of those tools against tests/tools/ (part of mx tests)
mx r7rs-diff path/to/file.scm             # run under chibi and under us; compare stdout + exit status
mx r7rs-diff-all                          # every program in tests/diff/ (part of mx tests)
mx r7rs-space                             # tail loop and deep recursion under -Xss256k (part of mx tests)
mx r7rs-status                            # regenerate the status block in docs/plan.md
mx r7rs-check                             # rule 1: Java builtins never call Scheme
mx r7rs-readme                            # run docs/usage.md's and docs/embedding.md's commands, check their samples (part of mx tests)
mx eclipseformat                          # format Java code (GraalVM's Eclipse formatter settings)
mx spotbugs                               # Java static analysis (SpotBugs)
mx benchmark r7rs-ecraven:fib             # one benchmark program, JIT (bench/README.md: options, failures)
mx benchmark r7rs-larceny:* -- --mode=*   # every program of a set, JIT and interpreter
mx r7rs-bench-check                       # benchmark subset vs bench/thresholds.json, fails on a regression (part of mx tests)
mx r7rs-bench-check --update --from F...  # new thresholds from CI measurements (bench/README.md "Thresholds")
nix develop .#bench -c mx r7rs-bench-report   # the comparative report, docs/benchmarks.md
```

Before opening a PR: `mx checks && mx tests`. If you touched `src/runtime` or
any Bytecode DSL operation, also run `mx r7rs-test --compile`. CI runs the same
two commands as the required `Checks` and `Tests` checks, one step per sub-check
(`Tests` passes when the parallel `Tests (...)` jobs that split its steps all pass),
plus non-blocking `Conformance` jobs (interpreter and `--compile`) whose run
summary shows per-section results. Steps whose task hasn't landed yet report
`SKIPPED` and name the task that brings them. This suite doesn't use `mx gate`.
`Tests (benchmarks)` fails when a benchmark is slower than its threshold, measured on CI's
runners: a PR that changes performance on purpose commits new thresholds from the `Benchmark
thresholds` workflow and says why (bench/README.md "Thresholds"); never raise one by hand. The
thresholds are for CI's linux-x86_64 runners: elsewhere `mx tests bench` only warns about them.

## Rules (each one is enforced by `mx checks` or `mx tests`)

1. **Java builtins never call Scheme directly.** A builtin that must invoke a
   procedure (`apply`, `call/cc`, `call-with-values`, `eval`) returns a
   `TailCall` request instead. Anything that needs a non-tail call (`map`,
   `for-each`, `dynamic-wind`, `raise`, ...) lives in the Scheme prelude
   (design.md D5). Check: `mx r7rs-check`.
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
  `tests/spec/`, or edit the status block in `docs/plan.md` by hand. `mx r7rs-status` regenerates the block, and the
  `mx tests` fails if it's stale, so commit the regenerated block with your change. If a test looks wrong, explain why in the
  PR and stop.
- Skip, disable, or loosen a test to make it pass. That includes listing a
  `tests/spec/` case in `_SPEC_PENDING` (`mx.r7rs/mx_r7rs.py`) once it has
  passed: an entry goes away when the task it names lands, and never comes
  back.
- Merge a PR whose `Checks` or `Tests` check isn't green, or bypass branch
  protection.
- Change the GraalVM or Truffle version (nixpkgs in `flake.lock` and the
  Truffle libraries in `suite.py` move together), change `mxversion`, add
  libraries, or change `flake.nix`/`flake.lock`, unless the task says to.
- Edit the vendored benchmark sets in `bench/r7rs-ecraven/` and
  `bench/r7rs-larceny/` other than their `README.md` (`mx r7rs-vendor-benchmarks`
  re-vendors them). A program that fails goes in `bench/README.md`'s Failures
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
- Format Java with `mx eclipseformat` before committing. `mx checks` fails if
  the formatter would change anything, and also runs checkstyle and SpotBugs.
- Only projects that need generated code list `TRUFFLE_DSL_PROCESSOR` in
  `annotationProcessors`. Keep it that way
  so edits elsewhere don't re-run the processor.

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
mx.r7rs/suite.py           projects, distributions, Truffle libraries (pinned to the GraalVM version)
mx.r7rs/mx_r7rs.py         custom mx commands: checks, tests, r7rs-*
mx.r7rs/projects/<name>/   mx project dirs: generated IDE files + checkstyle config
flake.nix, flake.lock      dev shell: GraalVM 25.5, mx, Python, gh, chibi-scheme
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
tests/diff/                programs that must match chibi under mx r7rs-diff; add freely
tests/space/               programs mx r7rs-space runs under small JVM limits
tests/tools/               programs mx r7rs-tools debugs and profiles (its checks name their lines)
tests/spec/                tail-call / continuation / depth cases; existing cases read-only
tests/scheme/test/         JUnit unit tests; add freely
docs/examples/             programs and sources that docs/usage.md and docs/embedding.md run (mx r7rs-readme)
```

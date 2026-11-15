# AGENTS.md

R7RS-small Scheme on GraalVM Truffle (Bytecode DSL). Goal: full R7RS-small
conformance, including re-entrant multi-shot continuations and proper tail
calls, with JIT performance.

## Read first

- `docs/design.md`: architecture and the **Decisions** section. Don't
  re-propose an option listed there as rejected. If you think a decision is
  wrong, say so in the PR description and stop.
- `docs/plan.md`: current milestone, task checklist, and the generated status
  block. Your task is one checklist item there.

## Environment

Run everything inside `nix develop`. It provides `mx`, Python, git and
chibi-scheme (used for differential testing). `mx` manages the JDK: run
`mx fetch-jdk labsjdk-ce-latest` once and set `JAVA_HOME` to the path it
prints. The Truffle version is pinned by the `truffle` suite import in
`mx.r7rs/suite.py`, not by your shell.

## Commands

The suite is `mx.r7rs/`. Custom commands and gate tasks live in
`mx.r7rs/mx_r7rs.py`. These commands exist once M0-01 (suite bootstrap) has
landed.

```
mx build                                  # build everything
mx unittest                               # JUnit tests in tests/r7rs.test
mx r7rs-test                              # chibi suite, interpreter only (the fast loop)
mx r7rs-test 6.10                         # one conformance section
mx r7rs-test --torture                    # capture + resume at every non-tail call
mx --dy /compiler r7rs-test --compile     # Graal JIT built from source; compile immediately; failures are errors
mx r7rs-spec                              # tests/spec (tail-call space, continuations, depth)
mx r7rs-diff path/to/file.scm             # run under chibi and under us, diff the output
mx r7rs-status                            # regenerate the status block in docs/plan.md
mx gate --tags fast                       # build, style, rule checks, unittest, r7rs, spec, torture
```

Before opening a PR: `mx gate --tags fast`. If you touched `r7rs.runtime` or
any Bytecode DSL operation, also run the `--compile` command. CI runs the full
`mx gate`.

## Rules (each one is enforced by a gate task)

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

- Edit anything under `tests/r7rs/` or `tests/spec/`, or the status block in
  `docs/plan.md` (CI rewrites it). If a test looks wrong, explain why in the
  PR and stop.
- Skip, disable, or loosen a test to make it pass.
- Edit `docs/design.md`. Propose changes in the PR description instead.
- Change the `truffle` suite import version or `mxversion` in `suite.py`,
  add libraries, or change `flake.nix`/`flake.lock`, unless the task says to.

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
- Only projects that need generated code list
  `truffle:TRUFFLE_DSL_PROCESSOR` in `annotationProcessors`. Keep it that way
  so edits elsewhere don't re-run the processor.

## Working style

- One task, one PR. Out-of-scope findings go under **Follow-ups** in the PR
  description. Don't fix them in the same PR.
- Write the failing test first, in `tests/r7rs.test/`. If you think a case
  belongs in `tests/spec/`, propose it in the PR. A human adds it.
- If the same failure survives two different fix attempts, stop and write up
  what you tried and what you observed.
- In the PR description, include what changed, which conformance sections
  changed state, any follow-ups, and anything that surprised you.

## Layout

```
mx.r7rs/suite.py           projects, distributions, pinned truffle import
mx.r7rs/mx_r7rs.py         custom mx commands + gate tasks
flake.nix, flake.lock      dev shell: mx, Python, chibi-scheme
src/r7rs.reader/           reader + datum labels
src/r7rs.compiler/         expander + core AST -> Bytecode DSL builder calls
src/r7rs.runtime/          Bytecode DSL root + operations, continuations
                           (Capture, Segment), trampoline, DynEnv
src/r7rs.builtins/         leaf primitives only (see rule 1)
src/r7rs.prelude/          standard libraries (.sld) and prelude Scheme code
tests/r7rs/                chibi suite, vendored, read-only
tests/spec/                tail-call / continuation / depth cases, read-only
tests/r7rs.test/           JUnit unit tests; add freely
```

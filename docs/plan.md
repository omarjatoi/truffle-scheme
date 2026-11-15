# Plan

Milestones run in order. Detailed tasks are written only for the current
milestone. Later milestones list just their exit criteria until they start.

Human-gated milestones (M0, M2): every PR gets human review, and only a human
edits `docs/design.md`.

<!-- status:begin -->
_No data yet. `mx r7rs-status` generates this block once M1-01 lands. Don't
edit it by hand._
<!-- status:end -->

---

## M0 · Suite bootstrap and spike (human gate)

Set up the mx suite, then answer the two questions the design depends on
before anything else is built on top of them.

- [ ] **M0-01 · mx suite bootstrap**
  - `mx.r7rs/suite.py`:
    - `mxversion`.
    - A `truffle` suite import from `https://github.com/oracle/graal` pinned
      to a recent master commit.
    - The projects listed in AGENTS.md "Layout", as empty stubs, with
      `truffle:TRUFFLE_DSL_PROCESSOR` only where generated code will be needed
      (`r7rs.runtime`, `r7rs.builtins`).
    - A JUnit test project, `tests/r7rs.test`.
  - `mx.r7rs/mx_r7rs.py`: every command in AGENTS.md "Commands", registered
    and wired to gate tasks. Commands whose machinery doesn't exist yet print
    a clear "not implemented until <task>" and exit nonzero. `torture` and
    `--compile` are flags the runtime reads, which do nothing until M2.
    `--compile` turns on immediate, synchronous compilation and makes
    compilation failures errors.
  - **Done when:**
    - `mx build` builds a stub `TruffleLanguage` that evaluates an integer
      literal.
    - `mx unittest` runs one JUnit test against it.
    - `mx --dy /compiler unittest` passes with the Graal JIT.
    - Every command in AGENTS.md either works or reports that it isn't
      implemented yet. **If any command in AGENTS.md is wrong for mx, fix
      AGENTS.md in the same PR.**
- [ ] **M0-02 · Spike: frame copy and multi-shot resume** *(needs M0-01)*
  - Write a minimal Bytecode DSL interpreter in a throwaway project,
    `r7rs.spike`, excluded from the gate and deleted at M0-04. Give it a call
    operation, a custom `@Yield SuspendFrame`, and a `Capture` marker, as in
    design.md "Continuations".
  - Capture across at least 3 frames, then resume the same continuation
    **twice**, copying each frame before resuming.
  - **Done when** tests show:
    - The two resumes are independent: locals and the operand stack of one
      don't leak into the other.
    - A variable in a `Box` (design.md D2) is **shared** across both resumes.
    - It works both interpreter-only and under `--dy /compiler`.
  - **Report:** how the frame was copied into a form `ContinuationRootNode`
    accepts, which APIs were used, and any gaps or workarounds.
- [ ] **M0-03 · Spike: cost of the call-site check under the JIT** *(needs M0-02)*
  - Compare `fib(30)` and `tak` with and without the call → `IsCapture` →
    `SuspendFrame` protocol, under `mx --dy /compiler`.
  - **Done when** the report has overhead numbers and evidence (IGV graphs or
    compilation traces) of whether the capture branch is folded away at call
    sites that never see a capture.
- [ ] **M0-04 · Decision (human)**
  - Fill in the "Spike-dependent" box in design.md with the results.
  - Record go/no-go in the Decisions section.
  - Delete `r7rs.spike`.

**Exit:** M0-04 done.

## M1 · Core

Tasks marked *(spike-independent)* can start as soon as M0-01 lands, while
the spike is still running.

- [ ] **M1-01 · Conformance harness** *(spike-independent)*
  - Vendor chibi's `r7rs-tests.scm` into `tests/r7rs/`, split into one file
    per section.
  - Add a `(chibi test)` shim (`test`, `test-begin`, `test-end`,
    `test-error`, ...) that reports results to the runner.
  - `mx r7rs-test [section]` runs each section in one JVM.
  - `mx r7rs-status` regenerates the status block in this file, and a gate
    task fails if that block is stale.
  - **Done when** every section runs (all failing is expected) and the status
    block is generated.
- [ ] **M1-02 · Differential runner** *(spike-independent)*
  - `mx r7rs-diff file.scm` runs the file under chibi and under our
    implementation, then compares stdout and exit status (design.md D8).
- [ ] **M1-03 · Value types and printer** *(spike-independent)*
  - All D1 value types, with arithmetic stubbed to fixnums and flonums only.
  - `write`, `display`, `write-shared` and `write-simple`, with cycle
    handling (D9).
- [ ] **M1-04 · Reader** *(spike-independent; needs M1-03)*
  - The full R7RS datum syntax: every number syntax (parsed into the D1
    types, or rejected clearly while the tower is stubbed), datum labels,
    block comments, `#;` datum comments, and `#!fold-case` /
    `#!no-fold-case`.
  - No Java recursion proportional to nesting depth (rule 4).
- [ ] **M1-05 · Core compiler, call protocol and trampoline** *(needs M0-04 go)*
  - Core forms (D3) → Bytecode DSL, with closure conversion and assignment
    conversion (D2).
  - `CallSiteEmitter` and the `TailCall` trampoline. Self tail calls become
    loops.
  - Global cells with `Assumption`s (D7).
- [ ] **M1-06 · Vertical slice** *(needs M1-02, M1-04, M1-05)*
  - A hand-written program using only core forms defines `fib`, prints
    `(fib 25)`, and matches chibi under `mx r7rs-diff`.
  - A 10M-iteration tail-recursive loop runs in constant space.

**Exit:** all M1 tasks done, `mx gate --tags fast` green, the harness reports
a status for every section, and the vertical slice matches chibi.

## M2 · Continuations (human gate)

**Exit:**
- `call/cc` capture and re-entry, prompts (D4), `dynamic-wind` with
  `rewind` (design.md "Dynamic environment"), and stack-depth spill all work.
- All of `tests/spec/` passes. These tests are written by a human before M2
  starts.
- `mx r7rs-test --torture` is green on every test that passes without it.

## M3 · Expander and prelude

**Exit:**
- The sets-of-scopes expander (D3), `define-library`, `import`, `include`
  and `cond-expand` work.
- The R7RS §7.3 derived syntax is in the prelude, along with `guard` (D4),
  `parameterize` and `define-record-type`.
- Each fan-out area has its own builtins, prelude and registration files, so
  M4 agents don't collide.
- Chibi sections 4.x and 5.x pass.

## M4 · Fan-out (parallel agents)

One agent per area: numeric tower, characters and strings, lists and vectors,
bytevectors, control and exceptions, I/O and ports, `eval` and environments,
process context and time.

**Exit:** 100% of the chibi suite, both normally and with `--torture`.

## M5 · Performance

The escape-only `call/cc` fast path, Fixing Letrec, vector storage strategies,
and benchmark thresholds in CI using `mx benchmark` with ecraven's
`r7rs-benchmarks`.

**Exit:** thresholds set from measured baselines, and CI enforcing them.

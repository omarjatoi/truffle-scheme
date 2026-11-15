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

## When the user says "start"

Pick up the next piece of work and take it all the way to a PR. Several
sessions may be running at once, so claim work before doing it.

1. **Sync.** `git fetch origin && git switch main && git pull --ff-only`
2. **Survey.** `gh pr list --state open --json number,title,baseRefName,reviewDecision,labels`
3. **Choose.** The first of these that applies wins:
   1. **Changes requested.** An open PR whose review decision is
      `CHANGES_REQUESTED` and that isn't labeled `agent:active`. Check it out
      and address the review.
   2. **Review needed.** An open PR not labeled `agent:active`, for a task in a human-gated
      milestone (see `docs/plan.md`) that has no comment starting with
      `Agent review`. Review it as described under "Reviewing".
   3. **Next task.** In the current milestone (the first one in
      `docs/plan.md` with unchecked tasks), the first unchecked task that
      meets all of these:
      - it isn't marked *(human)*;
      - every task it needs is either checked on `main` or done in an open
        PR that isn't labeled `agent:active` (in which case you stack on that PR, see "Stacked
        PRs");
      - no open PR has a title starting with its ID.
   4. **Nothing available.** If the current milestone's exit criteria are
      met, draft the next milestone's tasks in `docs/plan.md`, in the same
      format as the existing tasks, and open a PR titled `Plan: M<n> tasks`
      for human review. Otherwise, report what's blocking (unmerged PRs,
      human tasks) and stop.
4. **Claim.**
   - For an existing PR, add the label `agent:active`.
   - For a new task, push a branch named `<id>-<short-name>` (for example
     `m0-01-suite-bootstrap`), then open a PR (not a draft) titled
     `<ID>: <task name>` with the label `agent:active`. If the task depends
     on an unmerged PR, branch from that PR and stack on it instead (see
     "Stacked PRs").
   - Create the label if it's missing: `gh label create agent:active`.
   - If another open PR for the same ID appeared in the meantime, close
     yours and go back to step 3.
5. **Announce.** Say in one line what you picked and why.
6. **Gate.** If the task is in a human-gated milestone, post a short plan in
   chat and wait for "go". Otherwise, carry on.
7. **Do the work** following "Working style". In the same PR, tick the
   task's checkbox in `docs/plan.md` (outside the status block). When it's
   done, remove `agent:active`. That is the signal that the PR is ready for
   review and can be stacked on. Don't use draft PRs.
8. **Finish** with the PR link, one line of evidence for each "Done when"
   item, and any follow-ups.

## Stacked PRs

Use GitHub's native stacked PRs (the official `gh stack` extension) when a
task depends on work that is done but not merged yet. That way agents don't
sit waiting for merges. Independent tasks still branch from `main`.

- **Starting a stacked task.** Check out the top of the stack you depend on
  (`gh stack checkout <pr-number>`). If that PR isn't in a stack yet,
  check out its branch. Then create your branch on top of it with
  `gh stack add <id>-<short-name>`, push it, and open the PR with
  `gh pr create --base <parent-branch>`. Then link it into the stack with
  `gh stack link <parent-branch> <your-branch>`, listing branches bottom to
  top. `gh stack link` sets and repairs the base of each PR.
- **Always link.** A PR whose base is another PR's branch must be linked with
  `gh stack link` so GitHub shows it as part of a stack. Changing the base
  with `gh pr edit --base` alone isn't enough. Check the result with
  `gh stack view`.
- **One layer per PR.** Each PR's base is the branch below it, so its diff
  and its review cover only its own task. Note the parent PR's number in
  the PR description.
- **Keeping up to date.** Before you work on a stacked branch, and before
  you push, run `git fetch origin` and then `gh stack rebase --downstack`.
  That picks up review fixes from the PRs below yours and replays your
  commits onto the merge target once a PR below has merged. Then push with
  `gh stack push`.
- **Don't rewrite other sessions' branches.** Only rebase or push a branch
  above yours if its PR isn't labeled `agent:active`. If you fix a PR that
  has PRs stacked on it, say so in a comment on each PR above it. Their
  owners restack the next time they work on them.
- **Merging is still human-only.** A human merges stacks bottom-up,
  normally with `gh stack merge`. Agents never run `gh stack merge`.
- **Depth.** Don't stack on a PR whose review decision is
  `CHANGES_REQUESTED`. Wait until its fixes are pushed.

## Reviewing

You didn't write the code you're reviewing. Check the PR against the rules in
this file, the decisions in `docs/design.md`, and the task's "Done when"
items in `docs/plan.md`. Post one PR comment that starts with `Agent review`,
listing violations and claims without evidence, most serious first. Don't
push fixes.

## Environment

Run everything inside `nix develop`. It provides GraalVM CE 25.5 (already set
as `JAVA_HOME`; its Graal compiler is the JIT), `mx`, Python, git, `gh` and
chibi-scheme (used for differential testing). There's no JDK to fetch by
hand. Stacked PRs need the official `gh stack` extension: run
`gh extension install github/gh-stack` once. Truffle comes from Maven Central
as libraries in `mx.r7rs/suite.py`, at exactly the GraalVM version. mx refuses
to run if the two differ (design.md D11).

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
mx r7rs-test --compile                    # Graal JIT; compile immediately; failures are errors
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
- Merge a PR or a stack (`gh stack merge`). Only a human merges.
- Edit `docs/design.md`. Propose changes in the PR description instead.
- Change the GraalVM or Truffle version (nixpkgs in `flake.lock` and the
  Truffle libraries in `suite.py` move together), change `mxversion`, add
  libraries, or change `flake.nix`/`flake.lock`, unless the task says to.

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
- Only projects that need generated code list `TRUFFLE_DSL_PROCESSOR` in
  `annotationProcessors`. Keep it that way
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
mx.r7rs/suite.py           projects, distributions, Truffle libraries (pinned to the GraalVM version)
mx.r7rs/mx_r7rs.py         custom mx commands + gate tasks
flake.nix, flake.lock      dev shell: GraalVM 25.5, mx, Python, gh, chibi-scheme
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

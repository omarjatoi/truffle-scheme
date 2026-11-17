# Spec tests

The cases AGENTS.md's rules 3–5 are checked against: tail calls in constant
space (`tail-*`), continuations (`cont-*`), and depth without Java recursion
(`deep-*`). `bazel test //tests:spec` runs them, a test per case. Read-only for
agents once written: if a case looks wrong, say why in the PR and stop
(AGENTS.md "Never").

## Case format

A case is a program `<name>.scm` and its exact expected stdout `<name>.out`.

The program's leading comment lines may set, one per line:

- `;; limits: <JVM options>`, such as `-Xss256k -Xmx64m`. The case runs in a
  JVM started with these options. A tail call that grows the stack or the heap
  fails under them; so does Java recursion proportional to Scheme depth.
- `;; exit: <status>`, the expected exit status of `scheme.launcher.SchemeMain`.
  It defaults to 0. An uncaught error is 70.

The runner runs every case twice, interpreted only and with the Graal JIT, and
the case passes when both runs give exactly `<name>.out` and the exit status.
stderr is not compared.

Programs are written in R7RS, not in this implementation's core forms. A case
that needs something a later task brings is listed with that task in
`SPEC_PENDING` (tests/BUILD.bazel), which leaves it out, until that task lands
and removes the entry.

## Cases

| Case | Checks |
|---|---|
| `tail-self-loop` | a 10M-iteration self tail loop |
| `tail-mutual` | 10M tail calls between two procedures |
| `tail-positions` | every tail position of the core forms: `if`, `begin`, bodies with definitions, `letrec*`, `((lambda ...))`, procedures passed or returned |
| `tail-callcc` | `call/cc` in tail position; a continuation invoked each iteration; one continuation re-entered 1M times from tail position |
| `tail-dynamic-wind` | 1M iterations through `dynamic-wind` (winders don't accumulate) |
| `tail-derived` | the tail positions of R7RS §3.5 in derived syntax: `cond` (and `=>`), `case`, `and`, `or`, `when`, `unless`, `let`, `let*`, `letrec`, `letrec*`, named `let`, `let-values`, `let*-values`, `do`, `case-lambda` |
| `tail-promise` | R7RS §4.2.5's `delay-force` loop and `stream-filter`, SRFI 45's `stream-ref`, and a `make-promise` chain, each forcing a million promises |
| `tail-apply` | `apply` and the consumer of `call-with-values` in tail position |
| `tail-lazy` | tail loops over a 3M-cell lazily built list under a 32 MB heap: a self tail loop, mutual tail calls and a named let, the last two entered from the body of a let holding the head |
| `cont-escape` | escapes out of non-tail recursion |
| `cont-reentry` | re-entry with pending operands; re-entry of a top-level form carries on through the following forms (design.md D6) |
| `cont-multishot` | one continuation re-entered many times; frames are copies, assigned variables are shared |
| `cont-generator` | generators from two continuations; same-fringe |
| `cont-amb` | backtracking search with `amb` |
| `cont-dynamic-wind` | R7RS §6.10's example; after-thunks on escape and before-thunks on re-entry, in order |
| `cont-deep` | capture 100k frames deep, re-entered twice; escape from 1M deep |
| `cont-guard` | `guard` catching a `raise` from deep inside `dynamic-wind`s; `raise-continuable` returning |
| `cont-exit` | `exit` runs the outstanding after-thunks |
| `deep-recursion` | 1M-deep non-tail recursion, building and walking a 1M-element list non-tail-recursively |
| `deep-error` | an uncaught error 1M frames deep |
| `deep-read` | a 100k-deep nested list in program text |
| `deep-code` | expressions nested 10k deep in program text (the compiler) |
| `deep-write` | `write` and `display` of a 100k-deep nested list |
| `deep-equal` | `equal?` on 1M-deep data, and on circular data |

# Vendored second conformance suite: Larceny's R7RS tests

Read-only (AGENTS.md). The second, independently written R7RS suite (plan.md
M4-11), next to chibi-scheme's in `tests/r7rs/`.

- **Upstream:** Larceny, https://github.com/larcenists/larceny,
  `test/R7RS/Lib/tests/scheme/`, at the commit named in each file's header and
  in `sections.tsv`.
- **Authors:** Will Clinger rewrote Racket's R6RS test suite (mostly by
  Matthew Flatt) for R7RS; there have been other contributors.
- **License:** GNU LGPL, as Larceny's `README.upstream` and
  `README2.upstream` (copied verbatim) state: the suite is derived from
  Racket's LGPL-licensed R6RS tests. `COPYING.LESSER` is the LGPL version 3
  (the version gnu.org/copyleft/lesser.html, which those files point to,
  names) and `COPYING` the GPL version 3 it supplements. These files are test
  inputs: they are redistributed unchanged apart from a two-line provenance
  header, and nothing in the implementation links or includes them.
  `tests/lib/tests/scheme/test.sld` is derived from the suite's own
  `test.sld` and is LGPL too.

## What is here

Only the tests of the R7RS-small libraries: for each `(scheme <name>)`
(`base`, `case-lambda`, `char`, `complex`, `cxr`, `eval`, `file`,
`inexact`, `lazy`, `load`, `read`, `repl`, `time`, `write`,
`process-context`):

- `tests/scheme/<name>.sld`: the library `(tests scheme <name>)`, which
  exports `run-<name>-tests`, and the files it includes or loads
  (`base-test1.scm`, `char.body.scm`, `load-test1.scm`, ...).
- `tests/scheme/run/<name>.sps`: the program that runs them and reports.

Upstream's tests of the R7RS Red Edition libraries (SRFIs such as
`(scheme box)` and `(scheme charset)`) and of `(r6rs *)` emulation libraries
are not R7RS-small and are left out. So is upstream's `(tests scheme test)`:
the portable shim `tests/lib/tests/scheme/test.sld` (not read-only) replaces
it. The shim keeps upstream's macros and comparisons, so each test passes or
fails exactly as under upstream's library, and also reports each test as an
`@@r7rs-test` line, like the `(chibi test)` shim, so the same runner counts
both suites.

`sections.tsv` lists the sections `mx r7rs-test --suite larceny` runs: one per
library, plus the runs of `process-context` with options that upstream's
`testall-chibi` script makes (`--test-getenv` with an environment variable,
and `exit` and `emergency-exit` with and without a status; a run that must
exit passes one more test when its exit status is the expected one). Its
`tests` column is each section's total, and `chibi` the number chibi-scheme
passes.

## Running

Tests load files relative to the working directory
(`(load "tests/scheme/load-test1.scm")`) and write scratch files there, so
`mx` runs every section in a fresh temporary directory whose `tests` links
here, with `-I tests/lib -I tests/r7rs-larceny`.

## Totals

A section's total is the number of tests the suite runs there under
chibi-scheme, with our `(features)` added (`-D`), since some tests are under
`cond-expand` (`exact-complex`, ...): the totals are those of our
configuration. chibi can't finish one test of `(tests scheme base)` (it loops
in a nested `dynamic-wind` whose after thunk escapes; R7RS gives 7, which the
test expects), so the vendoring command has the shim skip that test under
chibi (`R7RS_TEST_SKIP`: counted, not run) and runs the section again. The
`chibi` column of `sections.tsv` shows that, and how many tests chibi passes.

## Exceptions

A few tests expect a particular result where R7RS says "it is an error". We
keep signalling those errors (design.md D8), so these tests fail by design,
and the status block counts them in its `Exceptions` column (plan.md M4-12).
`mx r7rs-test -v` marks them `(listed exception)`. Each row is the library
(its section in `sections.tsv`), the test as the shim reports it, and the
R7RS text that makes it an error. Add a row only with that text; a test that
fails for any other reason is a bug.

| Library | Test | Larceny's result | R7RS |
|---|---|---|---|
| `base` | `(begin (vector-copy! a 2 a 0) (quote unspec))` | copies the 3 elements that fit into the 5-element `a` and ignores the rest | §6.8 `vector-copy!`: "It is also an error if (- (vector-length to) at) is less than (- end start)." (here 3 < 5) |
| `base` | `a` | `#(1 2 1 2 3)`, the vector after that truncated copy (we leave it unchanged) | the same |
| `base` | `(bytevector-u8-ref (make-bytevector 10 -1) 9)` | 255: the fill is taken modulo 256 | §6.9: "A byte is an exact integer in the range from 0 to 255 inclusive", and `make-bytevector`'s fill is a byte; §1.3.3: "it is an error for a procedure to be presented with an argument that it is not specified to handle" |
| `base` | `(bytevector-u8-ref (make-bytevector 10 -128) 9)` | 128, the same way | the same |

## Re-vendoring

```
git clone https://github.com/larcenists/larceny /tmp/larceny
mx r7rs-vendor-larceny /tmp/larceny --commit "$(git -C /tmp/larceny rev-parse HEAD)"
```

It replaces `tests/`, `README.upstream`, `README2.upstream` and
`sections.tsv`. It needs a build (it asks our implementation for its
features) and chibi-scheme, and aborts if chibi still doesn't finish a section
after skipping three tests, or if a section that must exit doesn't.

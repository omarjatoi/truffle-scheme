# Differential fuzzer

Random R7RS-small programs, run under chibi-scheme and under ours; a program
whose stdout or exit status differs is minimised and saved (design.md D19).

```
bazel run //:r7rs -- fuzz --seed 7 --count 2000                    # interpreted
bazel run //:r7rs -- fuzz --seed 7 --count 500 --mode compile,torture,torture-owned
bazel run //:r7rs -- fuzz --seed 7 --start 130 --count 10 --print  # the programs, not run
bazel test //tests/fuzz:fuzz_smoke                                 # a fixed seed's 300 programs
```

- **Programs** (`gen.py`) are typed expression trees over the base library and
  `(scheme case-lambda char cxr eval inexact lazy read write)`: the numeric
  tower at the fixnum and bignum boundaries, characters, strings, symbols,
  lists, vectors, bytevectors, records, closures and `apply`, `map` and
  `for-each`, named `let` and `do`, `call/cc` (escapes and re-entry),
  `dynamic-wind`, `guard`, `raise` and handlers, `values`, `case`, `cond`,
  `quasiquote`, `syntax-rules` (with templates that test hygiene), string
  ports, `parameterize`, promises, `eval`, tail loops and deep recursion.
  Each program `write`s the values of a few expressions, a line each, and
  may end with `exit` or an uncaught `raise`. Program *n* of a seed is always
  the same (`--start n --count 1`).
- **Only defined behaviour**: the generator guards indices, divisors and
  ranges, lets at most one argument of a call leave non-locally (R7RS leaves
  the order of evaluation open), and never writes what R7RS leaves open
  (procedures, records, `eof`) nor relies on what chibi-scheme is known to
  get wrong (below). A difference is a bug in one of the two.
- **Files**: `--batch` programs (default 10) share a file, one JVM. The
  saved files, `<mode>-<seed>-<start>.scm` and the minimised
  `<mode>-<seed>-<start>.min.scm`, go in `--out` (default
  `$TMPDIR/r7rs-fuzz`); `bazel run //:r7rs -- diff FILE` reruns one.
- **Minimising**: delta debugging over the top-level forms, then over the
  tree (a node replaced by one of its children or a small constant, or a
  list element dropped), at most `--budget` runs; a smaller program counts
  only if it differs the same way (exit statuses, whether stdout differs,
  the Java exception ours crashed with) and writes nothing the original
  didn't (`#<...>`, NaNs, complex numbers), which R7RS leaves open.
- **Modes**: `interp`, `compile` (compile at once), `torture` and
  `torture-owned`, as the conformance tests run them. chibi-scheme runs once
  per file, whatever the modes. `--jobs` (default 4) runs files in parallel.

## Triage

Decide each difference by R7RS's text and its errata (https://r7rs.org),
not by what chibi-scheme does. Ours: fix it (a test first; the minimised
program goes in `tests/diff/` when chibi-scheme is right). chibi-scheme's,
or unspecified: make the generator stay clear of it, and list it below.

## chibi-scheme's departures from R7RS

The driver undoes the first two in chibi-scheme's stdout before comparing; the
generator avoids the others.

| What | chibi-scheme | R7RS |
|---|---|---|
| A flonum of one digit and an exponent | `1e+21` | §6.2.7: the result "contains a decimal point" (D9); ours writes `1.0e+21` |
| Bytevectors | `#u8(#x28 #xA4)` | §6.9 doesn't fix the notation of the bytes; ours writes `#u8(40 164)` (not a bug) |
| Reading flonums with an exponent or leading zeros | `1.5e-05` reads as `1.5000000000000002e-05` | §6.2.6: `(string->number (number->string z))` must give z back |
| `rationalize` when the interval holds 0 | `(rationalize 5 10)` is `-5` | §6.2.6: the simplest rational in the interval, which is 0 |

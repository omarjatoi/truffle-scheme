# Differential fuzzer

Random R7RS-small programs, run under chibi-scheme and under ours; a program
whose stdout or exit status differs is minimised and saved (design.md D19).

```
bazel run //:r7rs -- fuzz --seed 7 --count 2000                    # interpreted
bazel run //:r7rs -- fuzz --seed 7 --count 500 --mode compile,torture,torture-owned
bazel build //:scheme-native && bazel run //:r7rs -- fuzz --seed 7 --count 500 --mode native
bazel run //:r7rs -- fuzz --seed 7 --start 130 --count 10 --print  # the programs, not run
bazel test //tests/fuzz:fuzz_smoke                                 # a fixed seed's 300 programs
bazel test //tests/fuzz:fuzz_smoke_native                          # the same, native (Linux, executors)
bazel test --platforms=//bazel/platforms:macos_arm64 //tests/fuzz:fuzz_smoke_native   # native, here
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
- **Files**: `--batch` programs (default 10) share a file, one process. The
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
  `torture-owned`, as the conformance tests run them: all but `interp` with
  Java's and Truffle's detailed assertions (`-ea
  -Dtruffle.DetailedAssertions=true`, `harness.ASSERTION_VM_ARGS`), as the
  Bazel compile and torture tests have them, so a failed one is a crash of
  ours. They are JVM options, which the native launcher has none for: a run
  through it (`harness.vm_args(native=True)`) takes only the mode's launcher
  options. chibi-scheme runs once per file, whatever the modes. `--jobs`
  (default 4) runs files in parallel.
- **`native`**: through the native launcher, as `//tests:conformance_native`
  runs it: interpreted (`--engine.Compilation=false`), with
  `--vm.XX:+AbortVMOnCompilationFailure` (`harness.native_test_options()`),
  and nothing else. The launcher is the checkout's `bazel-bin/scheme-native`,
  or the one `--native FILE` names; build it first (`bazel build
  //:scheme-native`, a minute and about 3 GB), since the command doesn't, and
  without one it stops and says so. `--native` without `--mode native` is an
  error, not ignored. There are no native torture modes: a native image fixes
  `scheme.Torture` when it is built (design.md D17). Its mismatches are
  minimised and saved as the other modes' are, as `native-<seed>-<start>.scm`;
  the native launcher has no `diff` command, so rerun one with
  `bazel-bin/scheme-native FILE` and compare it with chibi-scheme's output.
  `//tests/fuzz:fuzz_smoke_native` runs `fuzz_smoke`'s programs this way,
  only when named, like the other native tests.

## Triage

Decide each difference by R7RS's text and its errata (https://r7rs.org),
not by what chibi-scheme does. Ours: fix it (a test first; the minimised
program goes in `tests/diff/` when chibi-scheme is right). chibi-scheme's,
or unspecified: make the generator stay clear of it, and list it below.

## chibi-scheme's departures from R7RS

The driver undoes the first three in chibi-scheme's stdout before comparing;
the generator stays clear of the others (`gen.py` says where). It makes no
complex numbers at all, so the rows with a complex operand can't arise (#249
found the two of `-`, which `tests/diff/numbers-complex-edges.scm` works
around; M13-02 those of `atan` and of writing a NaN imaginary part, which
`tests/diff/numbers-division-branch-cuts.scm` and
`tests/diff/numbers-complex-special-values.scm` stay clear of).

| What | chibi-scheme | R7RS |
|---|---|---|
| A flonum of one digit and an exponent | `1e+21` | §6.2.7: the result "contains a decimal point" (D9); ours writes `1.0e+21` |
| A negative ratio whose denominator is 2^62 | `-1/-4611686018427387904` | §6.2.7, §2.1: `-1/4611686018427387904` |
| Bytevectors | `#u8(#x28 #xA4)` | §6.9 leaves the notation of the bytes open; ours writes `#u8(40 164)` (not a bug) |
| `/` of negative bignums, with a ratio for quotient | negates them **in place**: `(define g -9223372036854775809) (/ 5 g)` leaves `g` positive, and a literal dividend changes sign each time its code runs | §6.2.6: numbers are values; §3.4: literals are constants |
| `-` of a ratio and a complex number | crashes (SIGSEGV, exit status 139): `(- 1/2 1+2i)`, `(- 1/2 3.+4.i)`; its `sexp_sub` turns the ratio into a flonum and passes that on as a complex number | §6.2.6: the difference, `-1/2-2i` and `-2.5-4.0i` |
| `-` with a complex operand (the second, if both are) | negates **in place** the flonums the complex number was made from: `(define x 7.5) (- 1 (make-rectangular x 1.))` leaves `x` -7.5, and the literal `1.` of `(make-rectangular 1. -0.)` changes sign each time `(- 2. ...)` of it runs (`sexp_complex_copy` gives the original its fresh parts and the copy, which `-` negates, the old ones) | §6.2.6: numbers are values; §3.4: literals are constants |
| `atan` of a complex number whose real part is an exact 0, as `+2.i` reads | crashes (SIGSEGV, exit status 139): `(atan +2.i)`, `(atan +0.5i)`, `(atan (make-rectangular 0 2.))`; `(atan 0.+2.i)` works | §6.2.6: (log(1+iz) − log(1−iz))/2i, `1.5707963267948966+0.5493061443340549i` for `+2.i` |
| `write` of a complex number with a NaN imaginary part | `1.0++nan.0i`, which reads as `#f` | §6.2.7, §7.1.1: `1.0+nan.0i` |
| `floor/`, `floor-quotient`, `floor-remainder` with an operand of magnitude 2^62 or more; `truncate/` at ±2^62 | `(floor/ 100 -1022799909500023965235)` is `0 1022799909500023965335` | §6.2.6: `-1 -1022799909500023965135` |
| `expt` of a non-integer ratio to a negative power | `(expt 2/3 -2)` is `1` | §6.2.6: `9/4` |
| `rationalize` when the interval holds 0 | `(rationalize 5 10)` is `-5` | §6.2.6: the simplest rational in the interval, 0 |
| `gcd` and `lcm` of one negative argument; `lcm` of 0 | `(gcd -20)` is `-20`; `(lcm 0 5)` divides by zero | §6.2.6: "always non-negative"; 0 |
| `abs` of `-0.0` | `-0.0` | §6.2.6: the absolute value, `0.0` |
| `log` of `-0.0` | `-inf.0` | §6.2.6: "`(log -0.0)` returns `-inf.0+πi`" |
| `max` and `min` with a NaN after the first argument | `(max 1 +nan.0)` is `1.0` | §6.2.4: "An arithmetic operation where one operand is NaN returns NaN", unless every rational in its place gives the same result |
| `expt` of a negative flonum to a non-integer power | `(expt -2. 0.5)` is `+nan.0` (C's `pow`) | §6.2.6: e^(z2 log z1), a complex number, `8.7e-17+1.414213562373095i` |
| `integer?` of an infinity | `(integer? -inf.0)` is `#t` | §6.2.6: integers are rationals, and `(rational? -inf.0)` is `#f` |
| Reading flonums with an exponent or leading zeros | `1.5e-05` reads as `1.5000000000000002e-05`, `126.5991` as `126.59909999999999` | §6.2.6: `(string->number (number->string z))` must give z back |
| `inexact` of a bignum | `(inexact 291803963674569850706)` is `2.9180396367456987e+20` | §6.2.6: the closest inexact number, `2.9180396367456983e+20` |
| `string->number` in radix 16 of a ratio with a bignum numerator | `#f` | §6.2.7: the number `number->string` wrote |
| `string->number` of a string with whitespace | `"\n100"` gives `100` | §6.2.7: `#f` for what isn't the notation of a number |
| `char-upcase` of `ß` | `ẞ` | §6.6: Unicode's simple case mapping, which has none for `ß` |
| `string-downcase` of `Σ` before a non-letter | `"Σ#"` gives `"ς#"` | §6.7: Unicode's full lowercasing, whose Final_Sigma needs a cased letter before it |
| `display` of a list with characters or strings, or of a symbol like `\|a b\|` | writes them as `write` does | §6.13.3: as if by `write-char` and `write-string`, symbols not escaped |
| `write` of `(string->symbol ".5")` or `".3."` | `.5`, `.3.`, which don't read back as those symbols | §6.13.3, §2.1: `\|.5\|`, `\|.3.\|` |

Not bugs, but avoided: what `(+ -0.0 0)` is (R7RS doesn't say whether an
exact 0 is an identity there), what an exact 0 times a flonum is (§6.2.2: it
may be exact 0, chibi-scheme's, or the inexact product, ours: `(* 0 1.5)` is
0.0 and `(* 0 +inf.0)` +nan.0; the generator multiplies flonums only by
flonums), the magnitude of a complex number whose squares overflow
(chibi-scheme's `(magnitude 1e300+1e300i)` is `+inf.0`, ours hypot's
`1.4142135623730952e+300`; R7RS doesn't fix the accuracy), which of `-0.0` and `0.0` `max` and `min` of
flonums return (chibi-scheme the first; ours `0.0` and `-0.0`, as IEEE
754-2019's maximum and minimum), and loops of 10^5 calls through `call/cc`,
which take chibi-scheme minutes (it copies its stack on each capture).

Also avoided: the transcendental functions of flonums (`exp`, `log`, `sin`,
..., `expt` of flonums), since R7RS §6.2.6 doesn't fix their accuracy. #230's
follow-up, `(exp 1)` here an ulp above the nearest flonum, which chibi-scheme
gives, was looked into (design.md D1, "Transcendental functions"): ours are
fdlibm's (`StrictMath`), the same digits on every platform, launcher and tier,
and fdlibm's `(exp 1)` is `2.7182818284590455`. Neither side is always the
nearest: on a sample, fdlibm's `exp` isn't in 9% of cases, and chibi-scheme's
`(asin 0.5)` isn't on macOS. So comparing them would only find which library
rounds a given argument better.

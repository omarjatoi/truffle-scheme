# Benchmarks

The two standard R7RS benchmark sets, vendored read-only, and run by
`mx benchmark` (plan.md M5-01, design.md D11):

- `r7rs-ecraven/`: ecraven's r7rs-benchmarks, 57 programs, the set behind
  the published comparison at https://ecraven.github.io/r7rs-benchmarks/.
- `r7rs-larceny/`: Larceny's `test/Benchmarking/R7RS`, which ecraven's set
  was taken from: the same programs with Larceny's inputs, plus 16 for the
  R7RS Red Edition libraries, 73 in all.

Each directory's README gives its upstream, commit, authors, license and the
command that re-vendors it. `postlude.scm` (ours) and the driver in
`mx.r7rs/mx_r7rs.py` (`_R7RSBenchmarkSuite`) run them.

## Running

```
mx build
mx benchmark r7rs-ecraven:fib                    # one program, with the JIT
mx benchmark r7rs-ecraven:*                      # every program, each in a JVM of its own
mx benchmark r7rs-larceny:* -- --mode=interpreter
mx benchmark 'r7rs-ecraven:*[fib,tak]' --results-file results.json -- --mode=* --iterations=10 --warmup=5
```

Options, after `--` (anything else there is a JVM argument):

| Option | Default | |
|---|---|---|
| `--mode=` | `jit` | `jit` (Graal, background compilation), `interpreter` (`engine.Compilation=false`), or `*` for both |
| `--iterations=N` | 3 (`jit`), 1 (`interpreter`) | how many times the JVM runs the suite's harness |
| `--warmup=N` | 1 (`jit`), 0 (`interpreter`) | how many of those iterations are warmup |
| `--count=N` | the program's | runs of the benchmark per iteration (each `.input` file's count) |
| `--timeout=SECONDS` | none | kill the JVM after this long |

`--count=1 --iterations=1` checks every program quickly: the check is the
same, only the timing loop is shorter. With the programs' own counts, one JIT
iteration of ecraven's set takes about half an hour of JVM time (November
2026, on a 16-core Apple M-series machine), so `r7rs-ecraven:*` with the
defaults takes about an hour and a half; the interpreter is about 10-20 times
slower than that, and Larceny's inputs are larger (its `takl` alone takes over
eight minutes per run with the JIT).

## What a run does and measures

A program is built the way each set's `bench` script builds it, with
`postlude.scm` in the place of an implementation's postlude:

- ecraven: `<program>.scm`, `common.scm`, the postlude,
  `common-postlude.scm` (which calls `(run-benchmark)`).
- Larceny: `<program>.scm`, `common.scm` up to its final `(main)`, the
  postlude, then `(main)`. ecraven's `common-postlude.scm` is that same split.

The JVM runs it with `scheme.launcher.SchemeMain` in a fresh working
directory (with `inputs` linked to the set's and an empty `outputs`, which
programs read and write), with `inputs/<program>.input` on standard input, as
`bench` does.

Each program calls the suite's harness, `run-r7rs-benchmark`, once: it times
`count` runs of the benchmark with `current-jiffy`, checks the last result,
and prints `Elapsed time: <seconds>` or `ERROR: returned incorrect result`.
The postlude wraps it so the one JVM calls it `--iterations` times. So:

- the first iteration is the number ecraven's tables report (one call in a
  fresh process, startup excluded), and
- the iterations after `--warmup` measure the code once the JIT has compiled
  it.

Datapoints, in `mx benchmark`'s JSON (`--results-file`, default
`bench-results.json`), each with `host-vm-config` set to the mode:

- `warmup`: every iteration's time (ms), with `metric.iteration`;
- `time`: the same, for the iterations after the warmup;
- `rss`, `max-rss`: the JVM's resident set size, sampled every 0.1 s by
  `mx benchmark`'s default tracker (`rsspercentiles`): peak memory.

A benchmark fails when the JVM exits nonzero, prints `ERROR:` (a failed
check, or an uncaught Scheme error), or prints fewer `Elapsed time` lines than
iterations. `mx benchmark` then names it and exits 1, after running the rest.

## Failures

Programs that don't run or don't pass their own check yet, each to be fixed by
a conformance PR (plan.md M5-01). `mx benchmark` still runs them: a listed
program that fails is logged and produces no datapoints, and one that passes
fails the run until its row is removed. Each row: the set, the program, why.

| Set | Program | Reason |
|---|---|---|
| `larceny` | `dderiv` | imports `(scheme hash-table)`, `(scheme comparator)`: R7RS Red Edition (R7RS-large) libraries, which we don't provide (R7RS-small only) |
| `larceny` | `bibfreq` | imports `(scheme list)`, `(scheme sort)`, `(scheme hash-table)`, `(scheme comparator)`: R7RS Red Edition (R7RS-large) libraries, which we don't provide (R7RS-small only) |
| `larceny` | `bibfreq2` | imports `(scheme list)`, `(scheme sort)`, `(scheme hash-table)`, `(scheme comparator)`: R7RS Red Edition (R7RS-large) libraries, which we don't provide (R7RS-small only) |
| `larceny` | `hashtable0` | imports `(scheme hash-table)`, `(scheme comparator)`: R7RS Red Edition (R7RS-large) libraries, which we don't provide (R7RS-small only) |
| `larceny` | `listsort` | imports `(scheme sort)`: R7RS Red Edition (R7RS-large) library, which we don't provide (R7RS-small only) |
| `larceny` | `vecsort` | imports `(scheme sort)`: R7RS Red Edition (R7RS-large) library, which we don't provide (R7RS-small only) |
| `larceny` | `list` | imports `(scheme list)`: R7RS Red Edition (R7RS-large) library, which we don't provide (R7RS-small only) |
| `larceny` | `ilist` | imports `(scheme ilist)`: R7RS Red Edition (R7RS-large) library, which we don't provide (R7RS-small only) |
| `larceny` | `rlist` | imports `(scheme rlist)`: R7RS Red Edition (R7RS-large) library, which we don't provide (R7RS-small only) |
| `larceny` | `vector` | imports `(scheme vector)`: R7RS Red Edition (R7RS-large) library, which we don't provide (R7RS-small only) |
| `larceny` | `set` | imports `(scheme set)`, `(scheme list)`, `(scheme sort)`, `(scheme comparator)`: R7RS Red Edition (R7RS-large) libraries, which we don't provide (R7RS-small only) |
| `larceny` | `charset` | imports `(scheme charset)`, `(scheme sort)`: R7RS Red Edition (R7RS-large) libraries, which we don't provide (R7RS-small only) |
| `larceny` | `stream` | imports `(scheme stream)`: R7RS Red Edition (R7RS-large) library, which we don't provide (R7RS-small only) |
| `larceny` | `lseq` | imports `(scheme generator)`, `(scheme lseq)`: R7RS Red Edition (R7RS-large) libraries, which we don't provide (R7RS-small only) |
| `larceny` | `generator` | imports `(scheme generator)`: R7RS Red Edition (R7RS-large) library, which we don't provide (R7RS-small only) |
| `larceny` | `text` | imports `(scheme text)`: R7RS Red Edition (R7RS-large) library, which we don't provide (R7RS-small only) |

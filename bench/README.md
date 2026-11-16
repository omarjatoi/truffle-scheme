# Benchmarks

The two standard R7RS benchmark sets, vendored read-only, and run by
`mx benchmark` (plan.md M5-01, design.md D11):

- `r7rs-ecraven/`: ecraven's r7rs-benchmarks, 57 programs, the set behind
  the published comparison at https://ecraven.github.io/r7rs-benchmarks/.
- `r7rs-larceny/`: Larceny's `test/Benchmarking/R7RS`, which ecraven's set
  was taken from: the same programs with Larceny's inputs, plus 16 for the
  R7RS Red Edition libraries, 73 in all. The Red Edition programs are out of
  scope (our target is R7RS-small, as for the conformance suites) and stay
  listed under Failures.

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
defaults takes about an hour and a half. The interpreter is about 10-40 times
slower, and Larceny's inputs are larger: one run of its `takl` takes over
eight minutes with the JIT, and neither its `takl` nor its `ntakl` finishes
one run within an hour in the interpreter (so use `--timeout` there).

## What a run does and measures

A program is built the way each set's upstream `bench` script builds it, with
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

Programs that don't run or don't pass their own check. A row is either out of
scope, or can't run yet and is to be fixed by a conformance PR (plan.md
M5-01). `mx benchmark` still runs them: a listed program that fails is logged
and produces no datapoints, and one that passes fails the run until its row is
removed. Each row: the set, the program, why.

Every R7RS-small program of both sets runs and passes its check; the rows
below are Larceny's 16 Red Edition programs, out of scope.

| Set | Program | Reason |
|---|---|---|
| `larceny` | `dderiv` | out of scope: R7RS-large (Red Edition): imports `(scheme hash-table)`, `(scheme comparator)` |
| `larceny` | `bibfreq` | out of scope: R7RS-large (Red Edition): imports `(scheme list)`, `(scheme sort)`, `(scheme hash-table)`, `(scheme comparator)` |
| `larceny` | `bibfreq2` | out of scope: R7RS-large (Red Edition): imports `(scheme list)`, `(scheme sort)`, `(scheme hash-table)`, `(scheme comparator)` |
| `larceny` | `hashtable0` | out of scope: R7RS-large (Red Edition): imports `(scheme hash-table)`, `(scheme comparator)` |
| `larceny` | `listsort` | out of scope: R7RS-large (Red Edition): imports `(scheme sort)` |
| `larceny` | `vecsort` | out of scope: R7RS-large (Red Edition): imports `(scheme sort)` |
| `larceny` | `list` | out of scope: R7RS-large (Red Edition): imports `(scheme list)` |
| `larceny` | `ilist` | out of scope: R7RS-large (Red Edition): imports `(scheme ilist)` |
| `larceny` | `rlist` | out of scope: R7RS-large (Red Edition): imports `(scheme rlist)` |
| `larceny` | `vector` | out of scope: R7RS-large (Red Edition): imports `(scheme vector)` |
| `larceny` | `set` | out of scope: R7RS-large (Red Edition): imports `(scheme set)`, `(scheme list)`, `(scheme sort)`, `(scheme comparator)` |
| `larceny` | `charset` | out of scope: R7RS-large (Red Edition): imports `(scheme charset)`, `(scheme sort)` |
| `larceny` | `stream` | out of scope: R7RS-large (Red Edition): imports `(scheme stream)` |
| `larceny` | `lseq` | out of scope: R7RS-large (Red Edition): imports `(scheme generator)`, `(scheme lseq)` |
| `larceny` | `generator` | out of scope: R7RS-large (Red Edition): imports `(scheme generator)` |
| `larceny` | `text` | out of scope: R7RS-large (Red Edition): imports `(scheme text)` |

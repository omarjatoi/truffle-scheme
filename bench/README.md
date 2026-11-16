# Benchmarks

The two standard R7RS benchmark sets, vendored read-only, and run by
`bazel run //:r7rs -- benchmark` (plan.md M5-01):

- `r7rs-ecraven/`: ecraven's r7rs-benchmarks, 57 programs, the set behind
  the published comparison at https://ecraven.github.io/r7rs-benchmarks/.
- `r7rs-larceny/`: Larceny's `test/Benchmarking/R7RS`, which ecraven's set
  was taken from: the same programs with Larceny's inputs, plus 16 for the
  R7RS Red Edition libraries, 73 in all. The Red Edition programs are out of
  scope (our target is R7RS-small, as for the conformance suites) and stay
  listed under Failures.

Each directory's README gives its upstream, commit, authors, license and the
command that re-vendors it. `postlude.scm` (ours) and the driver in
`tests/harness/bench.py` run them.

## Running

```
bazel run //:r7rs -- benchmark ecraven:fib       # one program, with the JIT
bazel run //:r7rs -- benchmark 'ecraven:*'       # every program, each in a JVM of its own
bazel run //:r7rs -- benchmark 'larceny:*' --mode=interpreter
bazel run //:r7rs -- benchmark ecraven:fib,tak --json results.json --mode='*' --iterations=10 --warmup=5
```

Options (anything else is a JVM argument):

| Option | Default | |
|---|---|---|
| `--mode=` | `jit` | `jit` (Graal, background compilation), `interpreter` (`engine.Compilation=false`), or `*` for both |
| `--iterations=N` | 3 (`jit`), 1 (`interpreter`) | how many times the JVM runs the suite's harness |
| `--warmup=N` | 1 (`jit`), 0 (`interpreter`) | how many of those iterations are warmup |
| `--count=N` | the program's | the count in the program's `.input` file: runs of the benchmark per iteration, except for `array1`, `equal` and `parsing`, which run the harness once and loop on the count themselves |
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

It prints every iteration's time (ms) and the mean after the warmup; with
`--json FILE`, it also writes them, per program and mode (`times_ms`,
`warmup`). The comparative report below measures peak memory.

A benchmark fails when the JVM exits nonzero, prints `ERROR:` (a failed
check, or an uncaught Scheme error), or prints fewer `Elapsed time` lines than
iterations. The command then names it and exits 1, after running the rest.

## Comparative report

`docs/benchmarks.md` (plan.md M5-02) compares us with the other R7RS
implementations nixpkgs has, on ecraven's set, the way his published tables
do. `nix develop .#bench` is the default shell plus Chez Scheme (with
chez-srfi), Racket, Chicken (with the r7rs egg), Guile and Gauche; chibi is in
both. One command runs everything and writes the page and its data
(`docs/benchmarks.json`):

```
nix develop .#bench --command bazel run //:r7rs -- bench-report --note "quiet machine"
nix develop .#bench --command bazel run //:r7rs -- bench-report --benchmarks fib,tak --scale 0.1   # a quick look
bazel run //:r7rs -- bench-report --render-only   # rewrite the page from the JSON
```

Each other implementation runs as upstream's `bench` script runs it: the
program is `cat <Impl>-prelude.scm <program>.scm common.scm
<Impl>-postlude.scm common-postlude.scm`, compiled with the script's
`<impl>_comp` command and run with its `<impl>_exec` command (the report lists
them), with the `.input` file on standard input, in a fresh process. The
number is the one the harness prints, as in ecraven's tables: startup and
compilation excluded. We don't vendor the preludes and postludes or
anything else the other implementations need (`r7rs-ecraven/README.md`); the
command downloads them, pinned, into `bench/.cache/` (gitignored):

- the preludes and postludes, from ecraven's repository at the commit in
  `r7rs-ecraven/benchmarks.tsv`;
- for Chez, ecraven's R7RS libraries for Chez Scheme
  (`ecraven/chez-scheme-libraries`, which his script names with `--libdirs`).
  They predate Chez 10, which added `vector-copy!` and `vector-append` to
  `(chezscheme)`, so each library is patched to leave out of its
  `(chezscheme)` import what it defines itself;
- for Gauche, SLIB, which its postlude imports;
- for Racket, the `r7rs` package, which its prelude's `#lang r7rs` needs.

Options: `--benchmarks` and `--implementations` choose rows and columns;
`--scale F` runs every program `F` times its count on every implementation
(the Count column says so); our interpreter runs `--interpreter-scale` (0.1)
times that again, its time scaled back up and marked; our JIT's JVM runs the
harness `--jit-iterations` (3) times, the first being the comparable number
and the best of the rest the steady state; `--timeout` (300 s, ecraven's
`CPU_LIMIT`) kills a run, and `--interpreter-timeout` our interpreter's (ten
programs have a count of 1, which no scale reduces). Implementations take
turns per program, in a rotating order, so a machine whose load changes during
the run affects them evenly.

How long it takes: the committed report (`--scale 0.1 --interpreter-timeout
60`, every program) took 105 minutes on a loaded 16-core machine, most of it
chibi's and our interpreter's timeouts and the programs whose count is 1;
twelve programs at the same scale take about 25 minutes. With the programs'
own counts, expect several hours. It runs locally, never in CI (design.md D11).

## Thresholds

`bazel run //:r7rs -- bench-check` (plan.md M5-08) runs a subset of ecraven's set and fails if one of them
is slower than its threshold in `thresholds.json`. It runs locally, not in CI (design.md D11): a
PR that may change performance runs it before it is opened. It is not a `bazel test`: its JVMs
run one after another, with the machine to themselves.

```
bazel run //:r7rs -- bench-check                   # every benchmark of the subset: a table, and exit 1 past a threshold
bazel run //:r7rs -- bench-check fib deriv startup # just those
bazel run //:r7rs -- bench-check --measure --json m.json   # measure only
```

The subset (`_CHECK_CASES` in `tests/harness/bench.py`) has two to four programs per kind of work:
arithmetic (`fib`, `tak`, `fibfp`, `sum`), tail calls (`takl`, `cpstak`), higher-order code
(`mazefun`, `deriv`, `peval`, `browse`), continuations (`ctak`, `fibc`), vectors (`fft`, `pnpoly`,
`triangl`, `puzzle`, `quicksort`), strings, reading and `equal?` (`string`, `parsing`, `equal`),
four of them in the interpreter (`fib`, `tak`, `deriv`, `string`), and `startup`: the launcher
running a hello-world program. Each program gets a workload of a fraction of a second per
iteration: its `.input` file with a smaller count, or, for those whose count is 1, an input of
ours with smaller arguments and their known result (as the "old inputs" in the files' comments).
The program and its check are unchanged.

What is measured: each program runs in a JVM of its own, one after another. A JIT benchmark's JVM
runs the harness 25 times, and its number is the median of the last 10, once the program is
compiled; an interpreter benchmark's runs it 6 times, the median of the last 4. `startup` is the
median wall time of 9 runs of the launcher. A benchmark past its threshold runs again in a fresh
JVM, up to twice (`--retries`), and keeps its best number, so a moment of load or one unlucky
compilation doesn't fail a PR; a regression has to hold over three JVMs.

How thresholds are set: the baselines are measured on the maintainer's Mac (`darwin-arm64`, an
Apple M3 Max), the machine the check runs on, since other machines and other load give other
numbers. Several runs (9 for the committed file), one after another, each give every benchmark a
number. Two thresholds follow, and the check fails past either:

- The threshold on the time: the baseline is the median of the runs, and the threshold the
  largest of 1.4 times the baseline, the baseline plus 4 standard deviations of the runs, and 1.2
  times the slowest run. On one quiet machine the runs agree within a few percent, so it is 1.4
  times the baseline for all but `cpstak` (1.6) and `fib-interpreter`: it catches a slowdown of
  everything, such as a lost inlining.
- The machine-adjusted threshold: a run's machine factor is the median, over its benchmarks, of
  time / baseline (so a few regressed benchmarks don't move it), and a benchmark's adjusted ratio
  its time / baseline / that factor, which takes out load that slows a whole run. The threshold is
  the largest of 1.25 times the runs' median, their median plus 4 standard deviations, and 1.15
  times the largest: 1.23 to 1.29 for most benchmarks, so a slowdown of one benchmark by about a
  quarter is caught. A run needs at least 10 benchmarks for a machine factor, so `bench-check fib`
  checks only the first threshold.

So a slowdown of a few benchmarks is caught from about a quarter, but one of everything alike
raises the machine factor, which load would explain too, and is caught only past 1.4 times.

The machine is shared with other builds, so measure and check while it is quiet: in a run made at
a load average of 22 on its 16 cores, most benchmarks took 2 to 4 times as long. The committed runs
had a load of 3 to 8; three runs above 10 were left out (the file's note says so).

The thresholds apply on the platform they were measured on (`darwin-arm64`, recorded in the
file). Elsewhere, such as on Linux, `bench-check` still runs everything and prints the table, but
only warns about benchmarks past their thresholds; it fails for a program that fails, a benchmark
without a threshold, or a changed workload. `--enforce` makes it fail past a threshold anyway.

`cpstak` has wider thresholds (adjusted 1.57) because two of the nine runs measured it about 1.3
times slower: how far background compilation has got when the measured iterations start varies
between JVMs, as for `peval` and `parsing` on GitHub's 4-CPU runners before (plan.md M7-01; with
`--engine.BackgroundCompilation=false` the same JVMs agree within a few percent). Small slowdowns
are for `benchmark` and the comparative report, A/B on one machine. `thresholds.json` records each
benchmark's runs, baseline, standard deviation, thresholds
and the workload it was measured on (a changed workload fails the check until it is measured
again), and where, when and at which commit the runs were made.

### Updating the thresholds

A PR that changes performance on purpose (a slowdown it accepts, a speedup that leaves a threshold
far too loose, or a changed workload) commits new thresholds, measured on the platform the file
records, and says in its description which ones moved and why:

```
bazel run //:r7rs -- bench-check --measure --json /tmp/runs/m1.json   # several times, while the machine is quiet
bazel run //:r7rs -- bench-check --update --from /tmp/runs/*.json --note "how loaded the machine was"
```

`bench-check --update` without `--from` measures and updates in one go (`--runs`, default 3).
Separate `--measure` runs let you note the load around each.

## Failures

Programs that don't run or don't pass their own check. A row is either out of
scope, or can't run yet and is to be fixed by a conformance PR (plan.md
M5-01). `benchmark` still runs them: a listed program that fails is logged
and gives no times, and one that passes fails the run until its row is
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

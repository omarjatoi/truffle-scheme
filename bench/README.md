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

Our JVMs run with `-Xmx2g` (Heap, below); a JVM argument such as `-Xmx8g`
comes after it, and wins.

`--count=1 --iterations=1` checks every program quickly: the check is the
same, only the timing loop is shorter. With the programs' own counts, one JIT
iteration of ecraven's set takes about half an hour of JVM time (November
2026, on a 16-core Apple M-series machine), so `ecraven:*` with the
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
`bench` does. So with the JIT it also gets the launcher's JIT warm-up on a
thread of its own (design.md D17, plan.md M8-03), as a user's run does.

Each program calls the suite's harness, `run-r7rs-benchmark`, once: it times
`count` runs of the benchmark with `current-jiffy`, checks the last result,
and prints `Elapsed time: <seconds>` or `ERROR: returned incorrect result`.
The postlude wraps it so the one JVM calls it `--iterations` times. So:

- the first iteration is the number ecraven's tables report (one call in a
  fresh process, startup excluded), and
- the iterations after `--warmup` measure the code once the JIT has compiled
  it.

It prints every iteration's time (ms) and the mean after the warmup, and the
JVM's peak RSS and heap after GC (Heap, below); with `--json FILE`, it also
writes them, per program and mode (`times_ms`, `warmup`, `rss_mb`, `heap_mb`,
`committed_mb`, `gcs`, `gc_ms`). `--live-every=SECONDS` also measures the
live heap (`live_mb`), with a full collection that often, so the times of
such a run aren't comparable.

A benchmark fails when the JVM exits nonzero, prints `ERROR:` (a failed
check, or an uncaught Scheme error), or prints fewer `Elapsed time` lines than
iterations. The command then names it and exits 1, after running the rest.

## Heap

Our benchmark JVMs (`benchmark`, `bench-report` and `bench-check`, all but its
`startup`, which runs the launcher as a user does) run with `-Xmx2g`
(`BENCH_HEAP_VM_ARGS` in `tests/harness/bench.py`), and the launcher's
collector, the serial one (below; plan.md M8-02, M8-05).

Why a fixed maximum: without one, the JVM's is a quarter of the machine's
memory (32 GB on the 128 GB machine of the M7 report), and G1 grows the heap
for as long as collections take more than its target share of the time, so an
allocation-heavy program's peak RSS follows the machine and the collector's
ergonomics, not the program. The M7 report's largest (JIT, at its counts, 3
iterations; RSS in MB, M8-02, on a 16-core Apple M3 Max with 128 GB of memory):

| Program | Live heap | Default heap | `-Xmx4g` | `-Xmx2g` | `-Xmx1g` | `-Xmx512m` |
|---|--:|--:|--:|--:|--:|--:|
| `nboyer` | 300 | 13,576 | 3,295 | 2,551 | 1,604 | 1,028 |
| `sboyer` | 112 | 9,303 | 2,197 | 1,974 | 1,414 | 1,002 |
| `paraffins` | 445 | 6,070 | 3,204 | 2,437 | 1,598 | 1,102 |
| `graphs` | 32 | 3,991 | 1,700 | 1,801 | 1,159 | 872 |
| `ack` | 16 | 3,738 | 1,661 | 1,721 | 1,072 | 762 |
| `fib` | 7 | 1,573 | 1,580 | 1,577 | 977 | 674 |

The times were the same from the default down to `-Xmx1g`, within the
run-to-run noise of single runs (about ±15% here), and in four interleaved
pairs of `nboyer` runs, `-Xmx2g` was a little faster (7.5-8.1 s first, 7.0-7.2
s second, against 8.7-9.2 and 7.9-8.4); at `-Xmx512m`, close to its live heap,
`paraffins` took 40-60% longer. What the programs keep alive is
what they compute: `nboyer`'s and `sboyer`'s terms (12.8 and 4.6 million
pairs of 24 bytes at their peak) and `paraffins`' radicals (5.2 million pairs
and as many vectors). Between iterations it falls back to the 7 MB of the
runtime and the standard libraries, so nothing is retained from one to the
next; nor is anything after a deep recursion (`ack`), whose pending frames,
spilled to the heap, are freed as it returns.

The maximum is 2 GB, not 1, because two programs keep about a gigabyte alive:
ecraven's `earley`, 1.1 GB (47 million pairs: its 2.7 million parses), the
most of its set (the committed report's live heap; then `mperm`, 0.6 GB, and
`nboyer`, 0.34 GB; the median is the runtime's 7 MB; `paraffins`, 0.45 GB at
its own count, now ends at the report's before the first 0.5 s sample, so
its row there shows 7 MB live beside 379 MB after GC), and Larceny's `nboyer`,
whose input is larger (size 6), 0.9 GB. `earley` takes as long at `-Xmx2g` as
with the default heap (4.2 s first, 3.0 s steady, two runs each), with an RSS
of 2.6 GB against 9.9 GB. A JVM argument after the command's own
(`benchmark ecraven:nboyer -Xmx8g`) overrides it.

The rest of the RSS is the JVM's: about 0.2-0.6 GB of the GC's data
structures (about 5% of the heap), compiled code, class metadata and the Graal
compiler's own heap (libgraal), and the heap's young generation, which G1
sizes up to 60% of the heap and touches in full between collections, however
little survives: so `fib`, with 7 MB live, has an RSS of 1.6 GB at `-Xmx2g`.
Each of the JVM's JVMCI compiler threads has a libgraal heap of its own, so the
launcher starts 4 host compiler threads instead of the 12 the JVM would on a
16-core machine (design.md D17, "The launcher's JVM"), which took the 24
programs' median peak RSS from 1108 to 1050 MB and their first iteration to
0.96 times. The benchmarks run `//:launcher_runtime`, without the AOT cache
`bazel-bin/scheme` starts with, so neither the report nor `bench-check`'s
`startup` includes it.

The collector is the launcher's, the serial one (`-XX:+UseSerialGC`,
design.md D17 "The collector", plan.md M8-05), so that the report times what a
user runs; a JVM argument that chooses another (`-XX:+UseG1GC`) replaces it.
Until M8-05 it was G1, the JVM's default, which the table above measured:
with the serial collector the young generation is a third of the heap, so
`fib`'s RSS at `-Xmx2g` is about 0.9 GB instead of 1.6. On 24 of ecraven's
programs at the report's counts the serial collector took 0.82 times G1's
time (geometric mean, first iteration and steady state alike) and 0.70 times
its peak RSS; `nboyer` took 1.7 times as long, with 6 s of single-threaded
pauses copying its young survivors (design.md has the numbers).

`bench-check`'s thresholds (Thresholds, below) are measured with `-Xmx2g` and
the serial collector, at M8's exit; those before were G1's with the default
heap, and that change moved several baselines by half (`tak` 0.46 times its
M7-03 baseline, `equal` 0.54, `cpstak` 0.57).

How each number is measured:

- **Peak RSS**: the process's maximum resident set size (`wait4`), as for
  every implementation.
- **Heap after GC**: the most the heap held right after a collection, from
  the JVM's GC log (`-Xlog:gc:file=...`, one line per pause, `before->after(committed)`),
  in the timed run itself, at no cost. It is at least the live heap, and often
  well above it: a young collection leaves the old generation alone, and G1
  looks for the old generation's garbage only once the heap is 45% full, so
  `nboyer`'s heap after GC reaches 1.1-1.3 GB at `-Xmx2g`, for 300 MB live.
- **Live heap**: `jcmd <pid> GC.class_histogram`, which collects the whole heap
  before it counts, so its total is exactly what is reachable; the largest of
  those taken every `--live-every` seconds. Each is a full collection, so this
  is a run of its own, whose times aren't used, and a peak between two samples
  can be missed.

## Comparative report

`docs/benchmarks.md` (plan.md M5-02) compares us with the other R7RS
implementations nixpkgs has, on ecraven's set, the way his published tables
do. `nix develop .#bench` is the default shell plus Chez Scheme (with
chez-srfi), Racket, Chicken (with the r7rs egg), Guile and Gauche; chibi is in
both. Our native launcher is a column too, so build it first, for this
machine (it isn't built remotely). One command then runs everything and
writes the page and its data (`docs/benchmarks.json`):

```
bazel build --config=local //:scheme-native
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

We run three ways, each a column (`--implementations` keys `jit`, `native`
and `interpreter`): the launcher's JVM with the Graal JIT, the native
launcher (`bazel-bin/scheme-native`, docs/usage.md "Native image", or
`--native FILE`), and the JVM with compilation off. Both with a JIT run the
harness `--jit-iterations` times in one process: the first is the comparable
number, the best of the rest the steady state. The native launcher also has
the first iteration with its startup and exit ("native with startup": the
process's wall time, less the later iterations), since it starts in about
10 ms where the JVM takes over half a second; the others' numbers, like
ecraven's, leave startup out. It runs with our JVMs' `-Xmx2g` (Heap, above),
given on its command line, with native-image's serial collector. Without the
executable, the column says it wasn't run.

Our JVMs' memory is measured three ways (Heap, above): the peak RSS, as for
every implementation; the heap after GC of the timed run; and the live heap,
from a second run of each program with the JIT, untimed, whose whole heap is
collected every `--live-every` seconds (0.5; 0 skips that run). The native
launcher's is measured the first two ways: its heap after GC comes from the
lines its `-XX:+PrintGC` writes to standard error (kept apart from the
program's output), and it has no `jcmd` for the live heap.

Options: `--benchmarks` and `--implementations` choose rows and columns;
`--scale F` runs every program `F` times its count on every implementation
(the Count column says so); our interpreter runs `--interpreter-scale` (0.1)
times that again, its time scaled back up and marked; our JIT's JVM and the
native launcher run the harness `--jit-iterations` (3) times, the first being
the comparable number and the best of the rest the steady state; `--native`
names the native launcher; `--timeout` (300 s, ecraven's `CPU_LIMIT`) kills a
run (our JIT's and native launcher's once per iteration), and
`--interpreter-timeout` our interpreter's (ten programs have a count of 1,
which no scale reduces). Implementations take turns per program, in a
rotating order, so a machine whose load changes during the run affects them
evenly; the report also states the machine's 1-minute load average at the
start, the most before any program, and at the end. The machine is shared, so
run it while it is quiet, as for `bench-check` (Thresholds, below), and say
how loaded it was with `--note`.

How long it takes: the committed report (`--scale 0.1 --interpreter-timeout
60`, every program) took 80 minutes on a quiet 16-core machine, most of it
chibi's and our interpreter's timeouts and the programs whose count is 1;
twelve programs at the same scale take about 25 minutes. With the programs'
own counts, expect several hours. The live-heap run doubles our JIT's share of
that (with one iteration, both of our JIT's runs of every program took 8
minutes at `--scale 0.1`). It runs locally, never in CI (design.md D11).

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
numbers. Several runs (12 for the committed file), one after another, each give every benchmark a
number. Two thresholds follow, and the check fails past either:

- The threshold on the time: the baseline is the median of the runs, and the threshold the
  largest of 1.4 times the baseline, the baseline plus 4 standard deviations of the runs, and 1.2
  times the slowest run. On one quiet machine the runs agree within a few percent, so it is 1.4
  times the baseline for every benchmark: it catches a slowdown of everything, such as a lost
  inlining.
- The machine-adjusted threshold: a run's machine factor is the median, over its benchmarks, of
  time / baseline (so a few regressed benchmarks don't move it), and a benchmark's adjusted ratio
  its time / baseline / that factor, which takes out load that slows a whole run. The threshold is
  the largest of 1.25 times the runs' median, their median plus 4 standard deviations, and 1.15
  times the largest: 1.25 to 1.3, so a slowdown of one benchmark by about a quarter is caught. A run needs at least 10 benchmarks for a machine factor, so `bench-check fib`
  checks only the first threshold.

So a slowdown of a few benchmarks is caught from about a quarter, but one of everything alike
raises the machine factor, which load would explain too, and is caught only past 1.4 times.

The machine is shared with other builds, so measure and check while it is quiet: in a run made at
a load average of 22 on its 16 cores, most benchmarks took 2 to 4 times as long. Runs with a
1-minute load above 10 at their start or end are left out; the committed runs (M8's exit) had a load of
1.8 to 4.9, so none was (the file's note says so).

The thresholds apply on the platform they were measured on (`darwin-arm64`, recorded in the
file). Elsewhere, such as on Linux, `bench-check` still runs everything and prints the table, but
only warns about benchmarks past their thresholds; it fails for a program that fails, a benchmark
without a threshold, or a changed workload. `--enforce` makes it fail past a threshold anyway.

A benchmark whose runs disagree gets wider thresholds: before M7-03, two of the nine runs measured
`cpstak` about 1.3 times slower (adjusted threshold 1.57), since how far background compilation has
got when the measured iterations start varies between JVMs, as for `peval` and `parsing` on GitHub's
4-CPU runners before (plan.md M7-01; with `--engine.BackgroundCompilation=false` the same JVMs agree
within a few percent). In the committed runs, `cpstak` measured 0.84 times its median in two of the
twelve (adjusted threshold 1.31), and `fft` 1.10-1.14 times in three (1.31); the rest agreed. Small slowdowns are for `benchmark` and the
comparative report, A/B on one machine. `thresholds.json` records each
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

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
iterations. Its standard error is read apart from its standard output, so
that nothing written there (such as the engine's log) can split one of the
harness's lines. The command then names it and exits 1, after running the rest.

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
most of its set (the committed report's live heap; then `paraffins`, 0.49 GB,
`mperm`, 0.45 GB, and `nboyer`, 0.36 GB; the median is the runtime's 7 MB),
and Larceny's `nboyer`,
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
the serial collector, at M9's exit, as at M8's; those of M7-03 were G1's with
the default heap, and that change moved several baselines by half (`tak` 0.46
times its M7-03 baseline, `equal` 0.54, `cpstak` 0.57).

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
both. Our native launcher is a column too, so build it first, here and for
this machine (design.md D17). One command then runs everything and writes
the page and its data (`docs/benchmarks.json`):

```
bazel build //:scheme-native
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
given as its option `--vm.Xmx2g`, with the collector it was built with:
native-image's serial one by default, or G1 (`--//:native_gc=G1`, design.md
D17). Without the executable, the column says it wasn't run.

Our JVMs' memory is measured three ways (Heap, above): the peak RSS, as for
every implementation; the heap after GC of the timed run; and the live heap,
from a second run of each program with the JIT, untimed, whose whole heap is
collected every `--live-every` seconds (0.5; 0 skips that run). The native
launcher's is measured the first two ways: its heap after GC comes from the
lines its `-XX:+PrintGC` writes to standard error (kept apart from the
program's output; the serial collector's and G1's, which `//tests:bench_gc`
checks), and it has no `jcmd` for the live heap.

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
60`, every program) took 84 minutes on a 16-core machine shared with other
agents (80 on a quiet one, before the native column), most of it chibi's and
our interpreter's timeouts and the programs whose count is 1;
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
1-minute load above 10 at their start or end are left out; the committed runs (M9's exit) had a load of
3.6 to 5.9, so none was (the file's note says so).

Don't build in the same workspace while a benchmark runs. Since Bazel 9 (#276), a build or test
there may remove outputs it doesn't need from `bazel-out` and the runfiles, such as the JDK's
`bin/java` that `bazel-bin/scheme` and the benchmark commands start. A `bazel run` command no
longer holds Bazel's lock once its program runs, so a benchmark then fails with `No such file or
directory`. Seen in M12-04, where launchers started from `bazel-bin` while another build ran.
To compare builds, copy their jars and the native launcher somewhere else first, and run them
with the GraalVM in Bazel's external repository.

The thresholds apply on the platform they were measured on (`darwin-arm64`, recorded in the
file). Elsewhere, such as on Linux, `bench-check` still runs everything and prints the table, but
only warns about benchmarks past their thresholds; it fails for a program that fails, a benchmark
without a threshold, or a changed workload. `--enforce` makes it fail past a threshold anyway.

A benchmark whose runs disagree gets wider thresholds: before M7-03, two of the nine runs measured
`cpstak` about 1.3 times slower (adjusted threshold 1.57), since how far background compilation has
got when the measured iterations start varies between JVMs, as for `peval` and `parsing` on GitHub's
4-CPU runners before (plan.md M7-01; with `--engine.BackgroundCompilation=false` the same JVMs agree
within a few percent). In M11's twelve runs, `cpstak` measured 0.83-0.85 times its median in two
(adjusted threshold 1.29), and the rest agreed within 10%; M12-05 re-measured `cpstak` alone (3 runs,
97.7-98.3 ms) once its loop got faster. Small slowdowns are for `benchmark` and the
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

## Where the slowest programs spend their time

What M11's report had as our JIT's slowest programs against the fastest
implementation (plan.md M12-05): in the steady state `pi` 7.5 times,
`cpstak` 6.7, `ray` 6.0, `compiler` 5.6, `graphs` 5.2, `sboyer` 4.8 and
`simplex` 4.3; in the first iteration `chudnovsky` 23.1, `pi` 20.1,
`compiler` 12.5, `matrix` 10.7 and `dynamic` 9.6. Each was profiled on the
JVM, at the report's counts: Truffle's CPU sampler (`--cpusampler`, with
`--cpusampler.Delay` past the first iterations; it attributes compiled code
at safepoints, so a small procedure between two of them can look hot),
`--engine.TraceCompilation` (compilations, deoptimizations,
invalidations), JFR's allocation and execution samples
(`-XX:StartFlightRecording`; the execution samples see the Java code behind
boundaries, such as `java.math`, but not runtime-compiled Scheme code), the
collector's log, and the hottest compilation's final schedule as text
(`-Djdk.graal.Dump=Truffle:1 -Djdk.graal.PrintGraph=Disable
-Djdk.graal.PrintBackendCFG=true`, design.md D1 "Representations chosen
outside loops", what IGV would show). design.md "Calls and tail calls"
has the two changes and their measurements.

Measured interleaved on the maintainer's Apple M3 Max shared with other
agents, the report's counts, 5 iterations in a process, 2 processes each,
`main` at `27b7dd9` (before M12-05) against `1b67af3` (after it; M12-12's
#294 is in it too), the medians; 1-minute load 2.7 to 7.9. The fastest
is M11's report's, to its two decimals. In seconds, first iteration / steady state:

| Program | Fastest | JIT, before | JIT, after | Native, before | Native, after |
|---|--:|--:|--:|--:|--:|
| `pi` | 0.01 | 0.116 / 0.037 | 0.123 / 0.036 | 0.041 / 0.033 | 0.038 / 0.033 |
| `cpstak` | 1.52 | 11.25 / 9.65 | 10.69 / 8.20 | 11.22 / 9.92 | 10.41 / 8.31 |
| `ray` | 0.14 | 0.457 / 0.047 | 0.478 / 0.048 | 0.228 / 0.052 | 0.234 / 0.054 |
| `compiler` | 0.11 | 1.56 / 0.317 | 1.47 / 0.320 | 1.59 / 0.527 | 1.54 / 0.512 |
| `graphs` | 0.42 | 2.51 / 2.12 | 2.05 / 1.67 | 3.21 / 3.16 | 1.92 / 1.72 |
| `sboyer` | 0.53 | 2.97 / 2.52 | 2.92 / 2.38 | 3.29 / 3.26 | 3.32 / 3.22 |
| `simplex` | 0.12 | 0.543 / 0.091 | 0.429 / 0.084 | 0.349 / 0.121 | 0.300 / 0.101 |
| `chudnovsky` | 0.01 | 0.174 / 0.024 | 0.172 / 0.024 | 0.044 / 0.023 | 0.044 / 0.023 |
| `matrix` | 0.06 | 0.619 / 0.252 | 0.654 / 0.238 | 0.581 / 0.297 | 0.572 / 0.312 |
| `dynamic` | 0.11 | 1.09 / 0.261 | 1.12 / 0.267 | 0.991 / 0.401 | 1.04 / 0.414 |

- `ray` and `simplex`: M12-04's flonum comparisons and `exact` (#289);
  both are now faster than the report's Chez Scheme.
- `cpstak`: one trampoline loop, compiled by OSR, performs its 815 million
  tail calls an iteration among five procedures, all inlined. Each
  allocated a closure, an argument array and, until M12-05, the `TailCall`
  (#295): about 10 GB a second, whose young collections are short (0.1 ms
  each, 0.1 s an iteration), so the time is the allocation itself. What is
  left is the closure and the argument array, which cross the loop's back
  edge; Chez allocates the closure only.
- `graphs`: continuation-passing (`fold-over-perm-tree`'s `deeper` and
  `accross`). Two loops' trampolines saw 9 to 12 procedures after their
  first tail call, past the cache of 8, and called every one indirectly
  with Truffle's argument profiling (design.md D17, "The native launcher's
  steady state"); nothing was split, since that cache doesn't report
  polymorphism. A cache of 12 (#297) calls them directly. What is left is
  allocation: argument arrays (53% of JFR's allocation samples, before),
  `TailCall`s returned from calls that aren't inlined (24%) and closures
  (16%).
- `sboyer`: allocation and the collector. About 9 GB an iteration, in
  JFR's samples argument arrays of calls that aren't inlined (59%),
  `TailCall`s returned across them (25%: `rewrite`'s tail call of
  `rewrite-with-lemmas` and `rewrite-args`' of `scons`, in recursive
  procedures that can't all be inlined), `Long`s (12%: `rewrite-count`, a
  global incremented past the box cache) and pairs (4%); its 16 young
  collections an iteration take 0.45 s of 2.45. *Tried:* one mutable
  `TailCall` per context, filled in place of a new one per tail call: no
  allocation where the callee isn't inlined, but stores to a real object
  where it is, which partial escape analysis can't remove: `deriv` 1.3
  times as long, `graphs` 1.08, `sboyer` and `compiler` unchanged (2
  interleaved rounds). Rejected.
- `pi`: `BigInteger` division. 87% of JFR's execution samples are in
  `java.math`, 67% in `MutableBigInteger.mulsub` and `divideMagnitude`,
  the JDK's schoolbook (Knuth) division, which it uses unless the divisor
  has 80 words and the quotient 40 (Burnikel-Ziegler); our code is 9%.
  Guile, the fastest, divides with GMP. Its first iteration (0.11 s against
  0.036) is the interpreter's until the JIT has compiled the loops; the
  native launcher's is 0.038.
- `chudnovsky`: likewise `java.math` (55% of the samples, half of it
  division), and 36% our arithmetic behind its boundaries
  (`Arithmetic.real` and `apply`, `SBigInteger.normalize`) and the
  interpreter. Its first iteration is 0.17 s against 0.025 in the steady
  state, and the native launcher's 0.044: the JVM's first iteration
  interprets until the arithmetic is compiled, where the native image's
  interpreter is compiled ahead of time.
- `compiler`, `dynamic` and `matrix`, first iterations: the interpreter's,
  until the many procedures they run are compiled. `compiler` makes 748
  compilations over three iterations (665 first-tier), 17 s of the compiler
  threads' time, the largest `gen-call` (98 KB of code, 1.0 s); `dynamic`
  306 (9 s), with 14 invalidations and 15 deoptimizations, mostly
  argument-type profiles and uncommon traps that recompile once; `matrix`
  137 (4.9 s). More compiler threads don't shorten them
  (`--engine.CompilerThreads=8`: `compiler` 1.49 s against 1.45, `dynamic`
  1.07 against 1.06, `matrix` 0.75 against 0.63, one process each): it is
  how soon each procedure is compiled, not the queue, and the thresholds
  that decide that were measured in M8-03 (design.md D17, "The JIT
  warm-up": small gains, from experimental options).
- `compiler`, `dynamic` and `matrix`, steady states: flat profiles.
  `compiler`'s largest procedure in the CPU sampler is
  `declaration-value`, 8% (a walk of an association list); its allocation
  is mostly argument arrays (2.3 of 3.7 GB). `dynamic`'s are `equiv!`
  (18%) and `gen-id` (24%, the sampler's attribution: `gen-id` increments
  a global, and 10 million calls of it take 20-70 ms); `matrix`'s its row
  operations (`_-split-`, `_-*-`, `loop`, 17-21% each).

## Polyglot

`polyglot/foreign.scm` (ours) times foreign access, `(graalvm polyglot)`
(design.md D20): Scheme calling a function of GraalVM's SimpleLanguage (SL)
in a loop, at a call site (`calls`) and as a tail call that a trampoline
makes (`tail-calls`); reading a Java `int[]`'s elements (`elements`) and a
Java object's field (`members`) with `foreign-ref`; and SL calling a Scheme
procedure back (`callbacks`, in SL loops of 1,000). SL is a test-only
dependency (design.md D20), so the program runs on a module path of its own,
`//bench/polyglot:jars` (the launcher's modules and SL), not through
`benchmark`:

```
bazel build //bench/polyglot:jars //bazel:jdk
bazel-bin/bazel/jdk/bin/java -XX:+UseSerialGC -Xmx2g \
  --enable-native-access=org.graalvm.truffle,org.graalvm.shadowed.jline --sun-misc-unsafe-memory-access=allow \
  -p bazel-bin/bench/polyglot/jars --add-modules ALL-MODULE-PATH -m scheme.launcher/scheme.launcher.SchemeMain \
  bench/polyglot/foreign.scm 30 1000000        # ITERATIONS COUNT [CASE ...]
```

It prints a line per case and iteration, `<case> <iteration> <milliseconds>`,
and checks each case's result. For the interpreter, add
`-Dpolyglot.engine.AllowExperimentalOptions=true -Dpolyglot.engine.Compilation=false`
(and a smaller count). design.md D20 ("Foreign access in compiled code") has
the A/B numbers. Neither `bench-check` nor the report runs it.

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

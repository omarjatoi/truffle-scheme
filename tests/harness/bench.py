"""Benchmarks (bench/README.md): ecraven's and Larceny's R7RS sets, run one program per JVM
(`benchmark`), the comparative report against other implementations (`bench-report`, M5-02), and
the subset checked against bench/thresholds.json (`bench-check`, M5-08).

`bazel run //:r7rs -- <command>`; RUNTIME is the launcher's runtime, which main.py sets.
"""

import argparse
import datetime
import functools
import hashlib
import json
import math
import os
import platform
import re
import shlex
import shutil
import signal
import subprocess
import sys
import tarfile
import tempfile
import threading
import time
import urllib.error
import urllib.request
from os.path import exists, join

import harness
from harness import INTERPRETER_VM_ARGS, fail, log, repo_root, tail

RUNTIME = []  # [harness.Runtime] of the launcher


graalvm_version = harness.graalvm_version


_BENCH_DIR = join(repo_root(), 'bench')


class _BenchmarkSet:
    """A vendored benchmark set (bench/r7rs-<name>/, read-only): upstream's src/<program>.scm,
    inputs/<program>.input and the shared src/common.scm harness, run the way upstream's `bench`
    script runs them (bench/README.md)."""

    def __init__(self, name, url, upstream_dir, copied, extra, programs_only=False):
        self.name = name
        self.dir = join(_BENCH_DIR, 'r7rs-' + name)
        self.url = url
        self.upstream_dir = upstream_dir  # in the upstream repository
        self.copied = copied  # files and directories of upstream_dir that are vendored
        self.extra = extra  # {vendored name: path in the upstream repository}
        # Vendor only src/common*.scm and the set's programs from src/, not the rest of src/.
        self.programs_only = programs_only

    def _tsv(self):
        with open(join(self.dir, 'benchmarks.tsv'), encoding='utf-8') as f:
            return f.read().splitlines()

    def benchmarks(self):
        """[(program, group)], in upstream's order (its bench script's ALL_BENCHMARKS)."""
        return [tuple(line.split('\t')) for line in self._tsv() if line and not line.startswith('#')]

    def commit(self):
        m = re.search(r' at ([0-9a-f]{40})', self._tsv()[0])
        return m.group(1) if m else 'unknown'

    def failures(self):
        """{program: reason}: the programs bench/README.md lists as failing, with the reason."""
        return _listed_benchmark_failures().get(self.name, {})

    def _read(self, *path):
        with open(join(self.dir, *path), 'rb') as f:
            return f.read()

    def program(self, name, postlude):
        """The program upstream's bench script builds, with our postlude in the place of an
        implementation's postlude: after common.scm, before the call that runs the benchmark."""
        source, common = self._read('src', name + '.scm'), self._read('src', 'common.scm')
        if self.name == 'ecraven':
            # bench: cat <impl>-prelude.scm <program>.scm common.scm <impl>-postlude.scm common-postlude.scm
            return b'\n'.join([source, common, postlude, self._read('src', 'common-postlude.scm')])
        # Larceny's bench: cat <program>.scm common.scm, where common.scm ends by calling (main).
        # ecraven's split that call into common-postlude.scm; we split it off the same way.
        m = re.search(rb'^\(main\)\s*\Z', common, re.MULTILINE)
        if not m:
            fail(f'{self.dir}/src/common.scm no longer ends with (main): update the driver')
        return b'\n'.join([source, common[:m.start()], postlude, b'(main)\n'])


_BENCH_SETS = [
    # Only what Larceny's permission covers (bench/r7rs-ecraven/README.md): the programs, the shared
    # harness and the inputs; not ecraven's own bench script, README or per-implementation
    # preludes and postludes.
    _BenchmarkSet('ecraven', 'https://github.com/ecraven/r7rs-benchmarks', '', ['inputs'], {}, programs_only=True),
    _BenchmarkSet('larceny', 'https://github.com/larcenists/larceny', join('test', 'Benchmarking', 'R7RS'),
                  ['README', 'bench', 'src', 'inputs'], {'COPYRIGHT': 'COPYRIGHT'}),
]


@functools.cache
def _listed_benchmark_failures():
    """bench/README.md's Failures table: | `set` | `program` | reason |."""
    listed = {}
    with open(join(_BENCH_DIR, 'README.md'), encoding='utf-8') as f:
        in_table = False
        for line in f:
            if line.startswith('## '):
                in_table = line.strip() == '## Failures'
            elif in_table and line.startswith('| `'):
                cells = [c.strip() for c in line.strip().strip('|').split(' | ')]
                listed.setdefault(cells[0].strip('`'), {})[cells[1].strip('`')] = cells[2]
    return listed


def _bench_script_variables(text):
    """The NAME="..." assignments of an upstream bench script, with $VAR references expanded."""
    variables = {}
    for m in re.finditer(r'^([A-Z0-9_]+)="([^"]*)"\s*$', text, re.MULTILINE):
        variables[m.group(1)] = re.sub(r'\$(\w+)', lambda v: variables.get(v.group(1), ''), m.group(2))
    return variables


def r7rs_vendor_benchmarks(args):
    """copy ecraven's or Larceny's R7RS benchmarks into bench/r7rs-<set>/ (M5-01)"""
    parser = argparse.ArgumentParser(prog='bazel run //:r7rs -- vendor-benchmarks')
    parser.add_argument('set', choices=[s.name for s in _BENCH_SETS])
    parser.add_argument('source', help='a checkout of the upstream repository (see bench/README.md)')
    parser.add_argument('--commit', required=True, help='the commit the checkout is at')
    parsed = parser.parse_args(args)
    bench_set = next(s for s in _BENCH_SETS if s.name == parsed.set)
    upstream = join(parsed.source, bench_set.upstream_dir)
    if not re.fullmatch(r'[0-9a-f]{40}', parsed.commit):
        fail('--commit takes a full commit hash')
    # Everything but our README.md is upstream's, copied unchanged.
    os.makedirs(bench_set.dir, exist_ok=True)
    for name in os.listdir(bench_set.dir):
        if name != 'README.md':
            path = join(bench_set.dir, name)
            shutil.rmtree(path) if os.path.isdir(path) else os.remove(path)
    # The programs: the upstream bench script's ALL_BENCHMARKS (`./bench <system> all`), grouped as
    # it groups them.
    with open(join(upstream, 'bench'), encoding='utf-8') as f:
        variables = _bench_script_variables(f.read())
    if 'ALL_BENCHMARKS' not in variables:
        fail('the bench script no longer defines ALL_BENCHMARKS: update the driver')
    groups = [g for g in variables if g != 'ALL_BENCHMARKS' and (g.endswith('_BENCHMARKS') or g.startswith('RED_EDITION_'))]
    copied = list(bench_set.copied)
    if bench_set.programs_only:
        os.makedirs(join(bench_set.dir, 'src'))
        copied += [join('src', name) for name in sorted(os.listdir(join(upstream, 'src'))) if re.fullmatch(r'common.*\.scm', name)]
        copied += [join('src', program + '.scm') for program in variables['ALL_BENCHMARKS'].split()]
    for name in copied:
        source = join(upstream, name)
        if os.path.isdir(source):
            shutil.copytree(source, join(bench_set.dir, name))
        elif exists(source):
            shutil.copyfile(source, join(bench_set.dir, name))
    for name, path in bench_set.extra.items():
        shutil.copyfile(join(parsed.source, path), join(bench_set.dir, name))
    rows = []
    for program in variables['ALL_BENCHMARKS'].split():
        group = next((g for g in groups if program in variables[g].split()), '-')
        for needed in (join('src', program + '.scm'), join('inputs', program + '.input')):
            if not exists(join(bench_set.dir, needed)):
                fail(f'{program}: upstream has no {needed}')
        rows.append(f'{program}\t{group}')
    with open(join(bench_set.dir, 'benchmarks.tsv'), 'w', encoding='utf-8') as f:
        f.write(f'# {bench_set.url} at {parsed.commit}. Generated by `bazel run //:r7rs -- vendor-benchmarks {bench_set.name}`;\n'
                '# columns: program (the bench script\'s ALL_BENCHMARKS, in its order), the bench script\'s group.\n')
        f.write('\n'.join(rows) + '\n')
    sums = []
    for dirpath, dirnames, filenames in os.walk(bench_set.dir):
        dirnames.sort()
        for name in sorted(filenames):
            rel = os.path.relpath(join(dirpath, name), bench_set.dir)
            if rel not in ('README.md', 'SHA256SUMS', 'benchmarks.tsv'):
                with open(join(dirpath, name), 'rb') as f:
                    sums.append(f'{hashlib.sha256(f.read()).hexdigest()}  {rel}')
    with open(join(bench_set.dir, 'SHA256SUMS'), 'w', encoding='utf-8') as f:
        f.write('\n'.join(sums) + '\n')
    log(f'wrote {len(sums)} files and {len(rows)} programs to {os.path.relpath(bench_set.dir, repo_root())}')


# The heap our benchmark JVMs run with (bench/README.md "Heap", plan.md M8-02): a fixed maximum,
# the same on every machine, rather than the JVM's default of a quarter of the machine's memory,
# with which G1 grew the heap to tens of GB for programs that keep a few hundred MB alive. Their
# collector is the launcher's, the serial one, and so are its compiler threads (harness.launcher_vm_args,
# plan.md M8-05, design.md D17).
BENCH_HEAP_VM_ARGS = ['-Xmx2g']

_BENCH_MODES = {
    # mode: (VM arguments, default iterations, default warmup iterations)
    'jit': (BENCH_HEAP_VM_ARGS, 3, 1),
    'interpreter': (BENCH_HEAP_VM_ARGS + INTERPRETER_VM_ARGS, 1, 0),
}

_ELAPSED_LINE = re.compile(r'^Elapsed time: (\S+) seconds \(.*\) for (.*)$', re.MULTILINE)


def _bench_options_parser():
    parser = argparse.ArgumentParser(add_help=False)
    parser.add_argument('--mode', choices=list(_BENCH_MODES) + ['*'], default='jit',
                        help='jit (default), interpreter, or * for both')
    parser.add_argument('--iterations', type=int, metavar='N',
                        help="how many times one JVM runs the suite's harness (each time `count` runs and the check)")
    parser.add_argument('--warmup', type=int, metavar='N', help='how many of those iterations are warmup')
    parser.add_argument('--count', type=int, metavar='N',
                        help="the count in the program's .input file (default: the file's own): runs per iteration for most "
                             "programs, the program's own loop count for those that run the harness once (array1, equal, parsing)")
    parser.add_argument('--timeout', type=int, default=0, metavar='SECONDS', help='kill the JVM after this long (0: never)')
    return parser


def _our_postlude(implementation, iterations):
    """bench/postlude.scm, after the definitions it expects (bench/README.md)."""
    with open(join(_BENCH_DIR, 'postlude.scm'), 'rb') as f:
        return (f'(define %r7rs-bench-implementation "{implementation}")\n'
                f'(define %r7rs-bench-iterations {iterations})\n').encode('utf-8') + f.read()


# ---------------------------------------------------------------------------
# Comparative report (plan.md M5-02): ecraven's set on us and on the other R7RS implementations
# in `nix develop .#bench`, each run the way ecraven's bench script runs it (bench/README.md)
# ---------------------------------------------------------------------------

# Downloads, kept between runs (gitignored).
_REPORT_CACHE = join(_BENCH_DIR, '.cache')

# Third-party files the other implementations need, fetched at run time and never committed
# (bench/r7rs-ecraven/README.md), each pinned:
# - the implementations' preludes and postludes, from ecraven's repository at the vendored commit;
_ECRAVEN_SRC_URL = 'https://raw.githubusercontent.com/ecraven/r7rs-benchmarks/{commit}/src/{file}'
# - for Chez: ecraven's R7RS libraries for Chez Scheme (GPL-3.0), which his bench script loads
#   with `--libdirs` (scheme/*.sls; they import (srfi :9) from chez-srfi);
_CHEZ_R7RS = ('https://codeload.github.com/ecraven/chez-scheme-libraries/tar.gz/50f5f85b91b85ed18bd19c981e1bf15811fb8f73',
              '394939fe34f4cb9c7dc813e86fdaeddea081fd2e0ea509003e4077f2d2010773')
# - for Gauche: SLIB, which Gauche-postlude.scm imports (for scheme-implementation-version);
_SLIB = ('https://groups.csail.mit.edu/mac/ftpdir/scm/slib-3b7.tar.gz',
         'b679e4b8e775ded2111b387589d015d1381c9e2e86a2158d83a5371fba0add83')
# - for Racket: the r7rs package, for Racket-prelude.scm's `#lang r7rs`.
_RACKET_R7RS = 'https://github.com/lexi-lambda/racket-r7rs.git?path=r7rs-lib#dfe5a961eb6f305a84a086af788a6fd0f603e5da'


class _ReportError(Exception):
    pass


def _fetch(url, name, sha256=None, missing_ok=False):
    """The contents of url, downloaded into the cache once (as `name`), checked against sha256 if
    given. With missing_ok, a 404 gives b'' (as the bench script uses /dev/null for a prelude or
    postlude an implementation doesn't have)."""
    path = join(_REPORT_CACHE, 'downloads', name)
    if not exists(path):
        log(f'bench-report: downloading {url}')
        try:
            with urllib.request.urlopen(url, timeout=120) as response:
                data = response.read()
        except urllib.error.HTTPError as e:
            if not (missing_ok and e.code == 404):
                raise _ReportError(f'{url}: {e}') from e
            data = b''
        except OSError as e:
            raise _ReportError(f'{url}: {e}') from e
        if sha256 and hashlib.sha256(data).hexdigest() != sha256:
            raise _ReportError(f'{url}: SHA-256 is {hashlib.sha256(data).hexdigest()}, expected {sha256}')
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path + '.tmp', 'wb') as f:
            f.write(data)
        os.replace(path + '.tmp', path)
    with open(path, 'rb') as f:
        return f.read()


def _extract(url, sha256, name, dest):
    """Downloads a pinned tarball and extracts it into dest (a fresh directory). Returns its one
    top-level directory."""
    _fetch(url, name, sha256)
    with tarfile.open(join(_REPORT_CACHE, 'downloads', name)) as tar:
        tar.extractall(dest, filter='data')
    entries = os.listdir(dest)
    if len(entries) != 1:
        raise _ReportError(f'{url}: expected one top-level directory, found {entries}')
    return join(dest, entries[0])


def _library_definitions(text):
    """The names an R6RS library form's body defines with `define` (not nested definitions)."""
    names, depth, i = set(), 0, 0
    token = re.compile(r'\(define\s+\(?([^\s()]+)')
    while i < len(text):
        c = text[i]
        if c == ';':
            i = text.find('\n', i)
            i = len(text) if i < 0 else i
        elif c == '"':
            i += 1
            while i < len(text) and text[i] != '"':
                i += 2 if text[i] == '\\' else 1
        elif text.startswith('#\\', i):
            i += 2
        elif c == '(':
            m = token.match(text, i)
            if m and depth == 1:
                names.add(m.group(1))
            depth += 1
        elif c == ')':
            depth -= 1
        i += 1
    return names


def _setup_chez(work):
    """ecraven's Chez R7RS libraries, compiled. They predate Chez 10, which added some of the
    procedures they define (vector-copy!, vector-append) to (chezscheme); each library is patched
    to except what it defines from its (chezscheme) import, or Chez rejects the duplicate."""
    libs = _extract(*_CHEZ_R7RS, 'chez-scheme-libraries.tar.gz', join(work, 'chez'))
    query = "(for-each (lambda (s) (display s) (newline)) (library-exports '(chezscheme)))"
    exports = set(subprocess.run(['scheme', '-q'], input=query, capture_output=True, text=True, check=True).stdout.split())
    patched = []
    for name in sorted(os.listdir(join(libs, 'scheme'))):
        path = join(libs, 'scheme', name)
        with open(path, encoding='utf-8') as f:
            text = f.read()
        defined = _library_definitions(text)
        renamed = set(re.findall(r'\(([^\s()]+) %[^\s()]+\)', text))
        clash = sorted((defined & exports) - renamed)
        if clash:
            text = text.replace('(chezscheme)', f'(except (chezscheme) {" ".join(clash)})', 1)
            with open(path, 'w', encoding='utf-8') as f:
                f.write(text)
            patched.append(f'{name}: {" ".join(clash)}')
    libdirs = libs + os.pathsep + os.environ.get('CHEZSCHEMELIBDIRS', '')
    # Compile them now, not in the first benchmark's run.
    imports = ' '.join(f'(scheme {n[:-4]})' for n in sorted(os.listdir(join(libs, 'scheme'))) if n.endswith('.sls')
                       and n not in ('repl.sls', 'load.sls', 'eval.sls'))
    with open(join(work, 'chez-warm.scm'), 'w', encoding='utf-8') as f:
        f.write(f'(import {imports})\n')
    done = subprocess.run(['scheme', '--compile-imported-libraries', '--libdirs', libdirs, '--program', join(work, 'chez-warm.scm')],
                          capture_output=True, text=True, check=False)
    if done.returncode != 0:
        raise _ReportError('compiling the Chez R7RS libraries failed:\n' + tail(done.stdout + done.stderr))
    return {'chezlibs': libdirs}, ['ecraven/chez-scheme-libraries, patched: ' + ('; '.join(patched) or 'nothing')]


def _setup_gauche(work):
    return {'slib': _extract(*_SLIB, 'slib.tar.gz', join(work, 'slib')) + os.sep}, ['SLIB 3b7']


def _setup_racket(work):
    addon = join(_REPORT_CACHE, 'racket')
    marker = join(addon, 'installed')
    if not exists(marker) or open(marker, encoding='utf-8').read() != _RACKET_R7RS:
        shutil.rmtree(addon, ignore_errors=True)
        os.makedirs(addon)
        log(f'bench-report: installing {_RACKET_R7RS}')
        done = subprocess.run(['raco', 'pkg', 'install', '--batch', '--auto', '--scope', 'user', '--no-docs', _RACKET_R7RS],
                              env=dict(os.environ, PLTADDONDIR=addon), capture_output=True, text=True, check=False)
        if done.returncode != 0:
            raise _ReportError('installing the r7rs package failed:\n' + tail(done.stdout + done.stderr))
        with open(marker, 'w', encoding='utf-8') as f:
            f.write(_RACKET_R7RS)
    return {'racket': addon}, ['the r7rs package (lexi-lambda/racket-r7rs, r7rs-lib)']


def _setup_guile(work):
    # guild compile writes, and guile reads, compiled files under $XDG_CACHE_HOME/guile/ccache.
    return {'guilecache': join(work, 'guile-cache')}, []


class _Implementation:
    """One column of the report. Another implementation runs as ecraven's bench script runs it:
    the program is `cat <name>-prelude.scm <program>.scm common.scm <name>-postlude.scm
    common-postlude.scm`, built with its `<name>_comp` command, run with its `<name>_exec` command
    and the program's .input on standard input. In the commands, {src} is the program, {exe} its
    compiled form, and the other words come from setup."""

    def __init__(self, key, title, name=None, executable=None, compile_cmd=None, run_cmd=None, env=None, setup=None):
        self.key = key
        self.title = title
        self.name = name  # the bench script's NAME, which names the prelude and postlude; None for ours
        self.executable = executable
        self.compile_cmd = compile_cmd or []
        self.run_cmd = run_cmd or []
        self.env = env or {}
        self.setup = setup


# Our implementations whose process runs the harness --jit-iterations times: the first is the
# comparable number, the best of the rest the steady state. The native launcher (`native`,
# `bazel build //:scheme-native`) keeps the Graal JIT, so it needs the iterations as much.
_ITERATED = ('jit', 'native')

# In the bench script's order of speed in ecraven's tables, roughly: the column order of the report.
_IMPLEMENTATIONS = [
    _Implementation('jit', 'Ours, JIT'),
    _Implementation('native', 'Ours, native'),
    _Implementation('interpreter', 'Ours, interpreter'),
    _Implementation('chez', 'Chez', 'Chez', 'scheme', run_cmd=[
        'scheme', '--optimize-level', '2', '--compile-imported-libraries', '--libdirs', '{chezlibs}', '--program', '{src}'],
        setup=_setup_chez),
    _Implementation('racket', 'Racket', 'Racket', 'racket', ['raco', 'make', '{src}'], ['racket', '{src}'],
                    env={'PLTADDONDIR': '{racket}'}, setup=_setup_racket),
    _Implementation('chicken', 'Chicken', 'Chicken', 'csc',
                    ['csc', '-R', 'r7rs', '-X', 'r7rs', '-block', '-O3', '-C', '-O3', '-d0', '{src}', '-o', '{exe}'], ['{exe}']),
    _Implementation('guile', 'Guile', 'Guile', 'guile', ['guild', 'compile', '-O3', '{src}'], ['guile', '{src}'],
                    env={'GC_INITIAL_HEAP_SIZE': '100000000', 'XDG_CACHE_HOME': '{guilecache}'}, setup=_setup_guile),
    _Implementation('gauche', 'Gauche', 'Gauche', 'gosh', run_cmd=['gosh', '-r7', '-b', '{src}'],
                    env={'SCHEME_LIBRARY_PATH': '{slib}'}, setup=_setup_gauche),
    _Implementation('chibi', 'Chibi', 'Chibi', 'chibi-scheme', run_cmd=['chibi-scheme', '-m', 'scheme.load', '-e', '(load "{src}")']),
]


class _Measured:
    def __init__(self, rc, output, timed_out, rss_bytes, heap=None, wall_seconds=None):
        self.rc = rc
        self.output = output
        self.timed_out = timed_out
        self.rss_bytes = rss_bytes
        self.heap = heap  # _heap_from_gc_log's, for our JVMs; _heap_from_native_gc_lines', for the native launcher
        self.wall_seconds = wall_seconds  # from starting the process to its exit


def _gc_log_vm_args(path):
    """The JVM options that log each collection to path, a line per pause, for _heap_from_gc_log."""
    return [f'-Xlog:gc:file={path}']


# A pause's line in the log (-Xlog:gc): "GC(3) Pause Young (Normal) (G1 Evacuation Pause) 967M->7M(2064M) 1.577ms":
# the heap's occupancy before and after it, its committed size, and how long it took.
_GC_PAUSE_LINE = re.compile(r'\bPause .*? (\d+)([KMG])->(\d+)([KMG])\((\d+)([KMG])\) (\d+\.\d+)ms$', re.MULTILINE)
_UNIT_MB = {'K': 1 / 1024, 'M': 1, 'G': 1024}


def _heap_from_gc_log(path):
    """What a JVM's GC log says about its heap (bench/README.md "Heap"), or None without a log:
    {heap_mb, committed_mb, gcs, gc_ms}. heap_mb is the most the heap held right after a
    collection: what the program kept alive then, plus the garbage the collection didn't look at (a
    young collection leaves the old generation's), so at least the live heap then, and often well
    above it; committed_mb the most heap the JVM had taken from the OS; gcs and gc_ms the pauses
    and their total time."""
    try:
        with open(path, encoding='utf-8', errors='replace') as f:
            text = f.read()
    except OSError:
        return None
    pauses = [(int(m.group(3)) * _UNIT_MB[m.group(4)], int(m.group(5)) * _UNIT_MB[m.group(6)], float(m.group(7)))
              for m in _GC_PAUSE_LINE.finditer(text)]
    if not pauses:
        return {'heap_mb': None, 'committed_mb': None, 'gcs': 0, 'gc_ms': 0}
    return {'heap_mb': round(max(p[0] for p in pauses), 1), 'committed_mb': round(max(p[1] for p in pauses), 1),
            'gcs': len(pauses), 'gc_ms': round(sum(p[2] for p in pauses))}


# The native launcher's runtime options, as its `--vm.` options (design.md D17, "VM options"): our
# JVMs' heap (BENCH_HEAP_VM_ARGS), and a line per collection on its standard error. Its collector is
# native-image's serial one by default, the only one GraalVM CE has, or G1 (`--//:native_gc=G1`,
# with Oracle GraalVM: design.md D17, "The native image's collector").
_NATIVE_VM_ARGS = ['--vm.' + a[1:] for a in BENCH_HEAP_VM_ARGS + ['-XX:+PrintGC']]
# A pause's line, the serial collector's:
#   "[0.063s] GC(0) Pause Incremental GC (Collect on allocation) 40.00M->30.00M 20.102ms"
# or G1's, which adds the log's level and tag, and the committed size, as the JVM's
# (_GC_PAUSE_LINE) does:
#   "[0.051s][info][gc] GC(0) Pause Young (Normal) (G1 Evacuation Pause) 78M->60M(304M) 8.148ms"
# (both from GraalVM 25.5's native launcher, with -XX:+PrintGC; tests/harness/check_bench_gc.py).
_NATIVE_GC_DECORATION = r'^\[[\d.]+s\](?:\[\w+\]\[gc\])? '
_NATIVE_GC_LINE = re.compile(_NATIVE_GC_DECORATION + r'GC\(\d+\) Pause .*? ([\d.]+)([KMG])->([\d.]+)([KMG])(?:\(([\d.]+)([KMG])\))? '
                             r'([\d.]+)ms\n?', re.MULTILINE)
# Every line -XX:+PrintGC writes, pauses or not, which the program's output leaves out: G1 also
# writes "[0.000s][info][gc] Using G1" and its concurrent cycles' lines, such as
# "[0.120s][info][gc] GC(5) Concurrent Mark Cycle 19.874ms".
_NATIVE_GC_ANY_LINE = re.compile(_NATIVE_GC_DECORATION + r'(?:Using \S+|GC\(\d+\) .*)$\n?', re.MULTILINE)


def _heap_from_native_gc_lines(text):
    """_heap_from_gc_log's numbers from the native launcher's GC lines; committed_mb only from G1's
    (None from the serial collector's, which don't give it)."""
    pauses = [(float(m.group(3)) * _UNIT_MB[m.group(4)],
               float(m.group(5)) * _UNIT_MB[m.group(6)] if m.group(5) else None,
               float(m.group(7)))
              for m in _NATIVE_GC_LINE.finditer(text)]
    if not pauses:
        return {'heap_mb': None, 'committed_mb': None, 'gcs': 0, 'gc_ms': 0}
    committed = [p[1] for p in pauses if p[1] is not None]
    return {'heap_mb': round(max(p[0] for p in pauses), 1), 'committed_mb': round(max(committed), 1) if committed else None,
            'gcs': len(pauses), 'gc_ms': round(sum(p[2] for p in pauses))}


def _without_native_gc_lines(text):
    """The native launcher's standard error without the lines its -XX:+PrintGC wrote."""
    return _NATIVE_GC_ANY_LINE.sub('', text)


_HISTOGRAM_TOTAL = re.compile(r'^Total\s+\d+\s+(\d+)\s*$', re.MULTILINE)


def _live_heap_mb(jcmd, pid):
    """The bytes of live objects in a JVM's heap now, in MB, or None: `jcmd GC.class_histogram`
    collects the whole heap first, so its total is exactly what is reachable."""
    try:
        done = subprocess.run([jcmd, str(pid), 'GC.class_histogram'], capture_output=True, text=True, timeout=60, check=False)
    except (OSError, subprocess.TimeoutExpired):
        return None
    m = _HISTOGRAM_TOTAL.search(done.stdout)
    return int(m.group(1)) / 2**20 if m else None


def _run_measured(argv, cwd, stdin_path, env, timeout, gc_log=None, live_every=None, native_gc=False):
    """Runs argv to completion or until timeout seconds have passed, and measures its wall time and
    peak RSS (the largest of the process and the descendants it waited for, from wait4); with
    gc_log, reads the GC log that a JVM of argv writes there (_gc_log_vm_args); with live_every,
    measures the live heap of that JVM (argv[0] is its java) every live_every seconds
    (_live_heap_mb), each time a full collection, so the times it prints are not to be used; with
    native_gc, reads the lines that the native launcher's -XX:+PrintGC writes to its standard
    error (_NATIVE_VM_ARGS). The output is the standard output followed by the standard error:
    kept apart while it runs, so that nothing written to the standard error (those lines, or the
    engine's log, such as a compiler's performance warning) can split one of the program's
    lines."""
    jcmd = join(os.path.dirname(argv[0]), 'jcmd') if live_every else None
    live = []
    with tempfile.TemporaryFile() as out, tempfile.TemporaryFile() as err, open(stdin_path or os.devnull, 'rb') as stdin:
        start = time.monotonic()
        process = subprocess.Popen(argv, cwd=cwd, stdin=stdin, stdout=out, stderr=err,
                                   env=env, start_new_session=True)
        # A thread waits for the process, so that its wall time is exact (the native launcher
        # starts in about 10 ms) while this one keeps the deadline and takes the samples.
        reaped = {}

        def reap():
            reaped['wait'] = os.wait4(process.pid, 0)
            reaped['end'] = time.monotonic()

        waiter = threading.Thread(target=reap, daemon=True)
        waiter.start()
        deadline = start + timeout
        next_sample = start + (live_every or math.inf)
        timed_out = False
        while waiter.is_alive():
            now = time.monotonic()
            if now > deadline:
                timed_out = True
                try:
                    os.killpg(process.pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass  # it ended just now
                waiter.join()
                break
            if jcmd and now >= next_sample:
                sample = _live_heap_mb(jcmd, process.pid)
                if sample is not None:
                    live.append(sample)
                next_sample = time.monotonic() + live_every
            waiter.join(min(1.0, max(0.0, min(deadline, next_sample) - time.monotonic()) + 0.001))
        _, status, rusage = reaped['wait']
        process.returncode = os.waitstatus_to_exitcode(status)
        out.seek(0)
        output = out.read().decode('utf-8', errors='replace')
        err.seek(0)
        errors = err.read().decode('utf-8', errors='replace')
    # ru_maxrss is in bytes on macOS, in kilobytes on Linux.
    rss = rusage.ru_maxrss * (1 if sys.platform == 'darwin' else 1024)
    heap = _heap_from_gc_log(gc_log) if gc_log else None
    if native_gc:
        heap = _heap_from_native_gc_lines(errors)
        errors = _without_native_gc_lines(errors)
    output += ('\n' if errors and output and not output.endswith('\n') else '') + errors
    if live_every:
        heap = dict(heap or {}, live_mb=round(max(live), 1) if live else None, live_samples=len(live))
    return _Measured(process.returncode, output, timed_out, rss, heap, reaped['end'] - start)


def _outcome(measured, iterations, stage=''):
    """{status, times, rss_mb, detail}, and for our JVMs the heap (_heap_from_gc_log): status is
    ok, or what went wrong."""
    times = [float(m.group(1)) for m in _ELAPSED_LINE.finditer(measured.output)]
    error = re.search(r'^(ERROR|INTERNAL ERROR):.*$', measured.output, re.MULTILINE)
    if measured.timed_out:
        status = f'{stage}timeout'
    elif error and 'incorrect result' in error.group(0):
        status = 'wrong result'
    elif error:
        status = f'{stage}error'
    elif measured.rc != 0:
        status = f'{stage}crash (exit {measured.rc})'
    elif len(times) < iterations:
        status = 'no result'
    else:
        status = 'ok'
    result = {'status': status, 'times': times, 'rss_mb': round(measured.rss_bytes / 2**20, 1)}
    if measured.heap:
        result.update(measured.heap)
    if status != 'ok':
        result['detail'] = '\n'.join(measured.output.rstrip('\n').splitlines()[-8:])
    m = re.search(r'^\+!CSVLINE!\+([^,\n]*),', measured.output, re.MULTILINE)
    if m:
        result['version'] = m.group(1)
    return result


def _with_count(text, count):
    """An .input file with its first datum, the count, replaced."""
    return re.sub(r'\A((?:[ \t]*(?:;[^\n]*)?\n)*[ \t]*)\d+', lambda m: m.group(1) + str(count), text, count=1)


def _input_count(text):
    m = re.match(r'\A(?:[ \t]*(?:;[^\n]*)?\n)*[ \t]*(\d+)', text)
    if not m:
        raise _ReportError('an .input file without a count')
    return int(m.group(1))


def _run_one(impl, words, lude, bench_set, program, count, options, root):
    """Runs one program on one implementation, in a fresh directory. Returns its outcome."""
    workdir = tempfile.mkdtemp(prefix=f'{impl.key}-{program}-', dir=root)
    try:
        os.symlink(join(bench_set.dir, 'inputs'), join(workdir, 'inputs'))
        os.mkdir(join(workdir, 'outputs'))
        with open(join(bench_set.dir, 'inputs', program + '.input'), encoding='utf-8') as f:
            stdin_text = _with_count(f.read(), count)
        stdin_path = join(workdir, 'stdin')
        with open(stdin_path, 'w', encoding='utf-8') as f:
            f.write(stdin_text)
        src = join(workdir, program + '.scm')
        if impl.name is None:
            iterations = options.jit_iterations if impl.key in _ITERATED else 1
            with open(src, 'wb') as f:
                f.write(bench_set.program(program, _our_postlude(f'truffle-scheme-{impl.key}', iterations)))
            timeout = options.timeout * iterations if impl.key in _ITERATED else options.interpreter_timeout
            if impl.key == 'native':
                measured = _run_measured([options.native] + _NATIVE_VM_ARGS + [src], workdir, stdin_path, None, timeout,
                                         native_gc=True)
                result = _outcome(measured, iterations)
                if result['status'] == 'ok':
                    # The first iteration with the process's startup and exit: its wall time, less
                    # the iterations after the first (the native launcher starts in about 10 ms).
                    result['process_seconds'] = measured.wall_seconds - sum(result['times'][1:])
                return result
            gc_log = join(workdir, 'gc.log')
            argv = RUNTIME[0].launcher([src], _BENCH_MODES[impl.key][0] + _gc_log_vm_args(gc_log))
            result = _outcome(_run_measured(argv, workdir, stdin_path, None, timeout, gc_log), iterations)
            if impl.key == 'jit' and options.live_every and result['status'] == 'ok':
                # The live heap, in a JVM of its own: its full collections would change the times.
                argv = RUNTIME[0].launcher([src], _BENCH_MODES[impl.key][0])
                measured = _run_measured(argv, workdir, stdin_path, None, timeout, live_every=options.live_every)
                if _outcome(measured, iterations)['status'] == 'ok':
                    result.update(measured.heap)
            return result
        with open(src, 'wb') as f:
            f.write(b''.join([lude['prelude'], bench_set._read('src', program + '.scm'), bench_set._read('src', 'common.scm'),
                              lude['postlude'], bench_set._read('src', 'common-postlude.scm')]))
        words = dict(words, src=src, exe=join(workdir, program + '.exe'))
        env = dict(os.environ, **{k: v.format(**words) for k, v in impl.env.items()})
        if impl.compile_cmd:
            measured = _run_measured([w.format(**words) for w in impl.compile_cmd], workdir, None, env, options.timeout)
            if measured.timed_out or measured.rc != 0:
                return _outcome(measured, 1, stage='compile ')
        return _outcome(_run_measured([w.format(**words) for w in impl.run_cmd], workdir, stdin_path, env, options.timeout), 1)
    finally:
        shutil.rmtree(workdir, ignore_errors=True)


def _git_commit():
    done = subprocess.run(['git', 'rev-parse', '--short=12', 'HEAD'], cwd=repo_root(), capture_output=True, text=True, check=False)
    return done.stdout.strip() or 'unknown'


def _host_description():
    cpu = platform.processor() or platform.machine()
    if sys.platform == 'darwin':
        brand = subprocess.run(['sysctl', '-n', 'machdep.cpu.brand_string'], capture_output=True, text=True, check=False).stdout.strip()
        cpu = brand or cpu
    elif exists('/proc/cpuinfo'):
        with open('/proc/cpuinfo', encoding='utf-8') as f:
            m = re.search(r'^model name\s*:\s*(.*)$', f.read(), re.MULTILINE)
            cpu = m.group(1) if m else cpu
    memory = 0
    if sys.platform == 'darwin':
        memory = int(subprocess.run(['sysctl', '-n', 'hw.memsize'], capture_output=True, text=True, check=False).stdout.strip() or 0)
    elif exists('/proc/meminfo'):
        with open('/proc/meminfo', encoding='utf-8') as f:
            m = re.search(r'^MemTotal:\s*(\d+) kB', f.read(), re.MULTILINE)
            memory = int(m.group(1)) * 1024 if m else 0
    memory = f', {memory / 2**30:.0f} GB of memory' if memory else ''
    return f'{platform.system()} {platform.release()} ({platform.machine()}), {cpu}, {os.cpu_count()} CPUs{memory}'


def r7rs_bench_report(args):
    """run ecraven's benchmarks on us and on other R7RS implementations, and write docs/benchmarks.md (M5-02)"""
    keys = [impl.key for impl in _IMPLEMENTATIONS]
    bench_set = next(s for s in _BENCH_SETS if s.name == 'ecraven')
    parser = argparse.ArgumentParser(prog='bazel run //:r7rs -- bench-report',
                                     description='Run inside `nix develop .#bench`, which has the other implementations. See bench/README.md.')
    parser.add_argument('--benchmarks', metavar='P,...', help="programs of ecraven's set (default: all, in the bench script's order)")
    parser.add_argument('--implementations', metavar='I,...', default=','.join(keys), help='columns (default: all of ' + ','.join(keys) + ')')
    parser.add_argument('--scale', type=float, default=1.0, metavar='F',
                        help="run each program max(1, round(F * its count)) times, on every implementation (default 1: the program's count, as in ecraven's tables)")
    parser.add_argument('--interpreter-scale', type=float, default=0.1, metavar='F',
                        help='and our interpreter F times that, its time scaled back up to the full count (default 0.1)')
    parser.add_argument('--jit-iterations', type=int, default=3, metavar='N',
                        help='times our JIT JVM runs the harness: the first is the comparable number, the best of the rest the steady state (default 3)')
    parser.add_argument('--timeout', type=int, default=300, metavar='SECONDS',
                        help="kill a run after this long (default 300, as ecraven's CPU_LIMIT; our JIT gets it once per iteration)")
    parser.add_argument('--interpreter-timeout', type=int, metavar='SECONDS',
                        help="the same for our interpreter (default: --timeout); a program whose count is 1 can't be scaled down")
    parser.add_argument('--live-every', type=float, default=0.5, metavar='SECONDS',
                        help="our JIT's live heap: run each program again, collecting the whole heap and measuring "
                             "what is live this often (default 0.5; 0: don't)")
    parser.add_argument('--native', default=join(repo_root(), 'bazel-bin', 'scheme-native'), metavar='FILE',
                        help='the native launcher, for the native column (default: bazel-bin/scheme-native, '
                             'which `bazel build --config=local //:scheme-native` builds)')
    parser.add_argument('--note', default='', help='conditions to state in the report, e.g. how loaded the machine was')
    parser.add_argument('--output', default=join(repo_root(), 'docs', 'benchmarks.md'), metavar='FILE.md')
    parser.add_argument('--json', default=join(repo_root(), 'docs', 'benchmarks.json'), metavar='FILE.json')
    parser.add_argument('--render-only', action='store_true', help='rewrite --output from --json without running anything')
    options = parser.parse_args(args)
    options.interpreter_timeout = options.interpreter_timeout or options.timeout
    options.native = os.path.abspath(options.native)
    if options.render_only:
        with open(options.json, encoding='utf-8') as f:
            report = json.load(f)
        _write_report_markdown(report, options.output)
        return
    selected = options.implementations.split(',')
    unknown = [k for k in selected if k not in keys]
    if unknown:
        fail(f'bench-report: unknown implementation(s) {unknown}; choose from {keys}')
    all_programs = [program for program, _ in bench_set.benchmarks()]
    programs = options.benchmarks.split(',') if options.benchmarks else all_programs
    unknown = [p for p in programs if p not in all_programs]
    if unknown:
        fail(f'bench-report: not in ecraven\'s set: {unknown}')
    if options.scale <= 0 or options.interpreter_scale <= 0 or options.jit_iterations < 1:
        fail('bench-report: --scale and --interpreter-scale must be positive, --jit-iterations at least 1')
    impls = [impl for impl in _IMPLEMENTATIONS if impl.key in selected]

    root = tempfile.mkdtemp(prefix='r7rs-bench-report-')
    report = {
        'generated': datetime.datetime.now(datetime.timezone.utc).strftime('%Y-%m-%d %H:%M UTC'),
        'command': 'bazel run //:r7rs -- bench-report ' + shlex.join(args),
        'host': _host_description(),
        'note': options.note,
        'commit': _git_commit(),
        'ecraven_commit': bench_set.commit(),
        'scale': options.scale,
        'interpreter_scale': options.interpreter_scale,
        'jit_iterations': options.jit_iterations,
        'timeout': options.timeout,
        'interpreter_timeout': options.interpreter_timeout,
        'heap_vm_args': BENCH_HEAP_VM_ARGS,
        'live_every': options.live_every,
        'implementations': {},
        'programs': {},
    }
    try:
        words, ludes = {}, {}
        for impl in impls:
            info = {'title': impl.title, 'notes': []}
            report['implementations'][impl.key] = info
            if impl.key == 'native':
                info['version'] = f'truffle-scheme {report["commit"]}, GraalVM {graalvm_version(RUNTIME[0].release)} Native'
                info['command'] = f'scheme-native {" ".join(_NATIVE_VM_ARGS)} {{src}} < {{input}}'
                info['notes'].append('`bench/postlude.scm` as the postlude')
                if not os.access(options.native, os.X_OK):
                    info['unavailable'] = f'no native launcher at `{options.native}`: `bazel build --config=local //:scheme-native` builds it'
                    log(f'bench-report: {impl.title}: {info["unavailable"]}')
                continue
            if impl.name is None:
                info['version'] = f'truffle-scheme {report["commit"]}, GraalVM {graalvm_version(RUNTIME[0].release)}'
                info['command'] = ('java ' + ' '.join(harness.launcher_vm_args(_BENCH_MODES[impl.key][0]) + _BENCH_MODES[impl.key][0] + _gc_log_vm_args('{gc.log}'))
                                   + (' (Graal JIT, background compilation)' if impl.key == 'jit' else '')
                                   + ' -m scheme.launcher/scheme.launcher.SchemeMain {src} < {input}')
                info['notes'].append('`bench/postlude.scm` as the postlude')
                continue
            info['command'] = ' '.join(impl.compile_cmd + (['&&'] if impl.compile_cmd else []) + impl.run_cmd + ['<', '{input}'])
            if not shutil.which(impl.executable):
                info['unavailable'] = f'`{impl.executable}` is not on PATH: run inside `nix develop .#bench`'
                continue
            try:
                commit = bench_set.commit()
                ludes[impl.key] = {part: _fetch(_ECRAVEN_SRC_URL.format(commit=commit, file=f'{impl.name}-{part}.scm'),
                                                f'ecraven-{commit[:12]}-{impl.name}-{part}.scm', missing_ok=True)
                                   for part in ('prelude', 'postlude')}
                used = [f'`{impl.name}-{part}.scm`' for part, text in ludes[impl.key].items() if text]
                info['notes'].append(' and '.join(used) if used else 'no prelude or postlude')
                if impl.setup:
                    more, notes = impl.setup(root)
                    words.update(more)
                    info['notes'] += notes
            except (_ReportError, subprocess.CalledProcessError, OSError) as e:
                info['unavailable'] = f'setup failed: {e}'
                log(f'bench-report: {impl.title}: {info["unavailable"]}')
        runnable = [impl for impl in impls if 'unavailable' not in report['implementations'][impl.key]]
        start = time.time()
        loads = []
        for i, program in enumerate(programs):
            # The 1-minute load average before each program, and at the end: the report states it.
            loads.append(round(os.getloadavg()[0], 2))
            report['load'] = {'start': loads[0], 'max': max(loads)}
            with open(join(bench_set.dir, 'inputs', program + '.input'), encoding='utf-8') as f:
                full = _input_count(f.read())
            count = max(1, round(full * options.scale))
            entry = {'count': count, 'program_count': full, 'results': {}}
            report['programs'][program] = entry
            # Each program runs every implementation, in an order rotated from one program to the
            # next, so that the machine's load drifting over the run spreads evenly over them.
            order = runnable[i % len(runnable):] + runnable[:i % len(runnable)] if runnable else []
            for impl in order:
                run_count = max(1, round(count * options.interpreter_scale)) if impl.key == 'interpreter' else count
                try:
                    result = _run_one(impl, words, ludes.get(impl.key), bench_set, program, run_count, options, root)
                except _ReportError as e:
                    result = {'status': 'error', 'times': [], 'detail': str(e)}
                result['count'] = run_count
                if result['status'] == 'ok':
                    # The comparable number: the first (or only) run in a fresh process, as in
                    # ecraven's tables; the interpreter's scaled up to the count the rest ran.
                    result['seconds'] = result['times'][0] * count / run_count
                    if impl.key in _ITERATED and len(result['times']) > 1:
                        result['steady_seconds'] = min(result['times'][1:])
                    if 'version' in result:
                        report['implementations'][impl.key].setdefault('version', result['version'])
                entry['results'][impl.key] = result
                shown = f'{result["seconds"]:.3f}s' if result['status'] == 'ok' else result['status']
                log(f'bench-report: [{i + 1}/{len(programs)}, {(time.time() - start) / 60:.0f} min] '
                       f'{program} on {impl.title} (count {run_count}): {shown}')
            _write_json(report, options.json)
        report['minutes'] = round((time.time() - start) / 60, 1)
        loads.append(round(os.getloadavg()[0], 2))
        report['load'] = {'start': loads[0], 'max': max(loads), 'end': loads[-1]}
        _write_json(report, options.json)
        _write_report_markdown(report, options.output)
        log(f'bench-report: wrote {options.output} and {options.json}')
    finally:
        shutil.rmtree(root, ignore_errors=True)


def _write_json(report, path):
    os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
    with open(path + '.tmp', 'w', encoding='utf-8') as f:
        json.dump(report, f, indent=1, sort_keys=False)
        f.write('\n')
    os.replace(path + '.tmp', path)


_SHORT_STATUS = [('timeout', 'timeout'), ('compile', 'compile error'), ('wrong', 'wrong result'), ('crash', 'crash'),
                 ('error', 'error'), ('no result', 'no result')]


def _short_status(status):
    return next((short for prefix, short in _SHORT_STATUS if prefix in status), status)


def _write_report_markdown(report, path):
    impls = report['implementations']
    programs = report['programs']
    keys = list(impls)
    # The columns: each implementation; our JIT's and native launcher's steady state next to their
    # first run, and the native launcher's first run with its startup.
    columns = []
    for key in keys:
        columns.append((key, 'seconds', impls[key]['title']))
        if key in _ITERATED and report['jit_iterations'] > 1:
            columns.append((key, 'steady_seconds', impls[key]['title'] + ' steady'))
        if key == 'native':
            columns.append((key, 'process_seconds', impls[key]['title'] + ' with startup'))

    def time_of(program, key, field):
        result = programs[program]['results'].get(key)
        return result.get(field) if result and result['status'] == 'ok' else None

    # Time relative to the fastest comparable run (steady state excluded: it isn't one).
    fastest = {}
    for program in programs:
        times = [time_of(program, key, 'seconds') for key in keys]
        times = [t for t in times if t is not None]
        fastest[program] = min(times) if times else None

    def ratio(program, key, field):
        t = time_of(program, key, field)
        return t / fastest[program] if t is not None and fastest[program] else None

    # Our JVMs' heap (M8-02), next to their RSS: the live heap, and the heap after GC; a report from
    # before has neither.
    heap_keys = {key for entry in programs.values() for key, result in entry['results'].items() if 'gcs' in result}
    live_keys = {key for entry in programs.values() for key, result in entry['results'].items() if 'live_samples' in result}

    def memory_cell(result, kind):
        result = result or {}
        if kind == 'rss':
            return f'{result["rss_mb"]:.0f}' if 'rss_mb' in result else ''
        if kind == 'live':
            if 'live_samples' not in result:
                return ''
            return f'{result["live_mb"]:.0f}' if result['live_mb'] is not None else 'too short'
        if 'gcs' not in result:
            return ''
        return f'{result["heap_mb"]:.0f}' if result['heap_mb'] is not None else 'no GC'

    def median_of(key, field):
        values = sorted(programs[p]['results'][key][field] for p in programs
                        if programs[p]['results'].get(key, {}).get(field) is not None)
        return f'{values[len(values) // 2]:.0f}' if values else ''

    lines = []
    out = lines.append
    out('# Benchmarks')
    out('')
    out('<!-- Generated by `bazel run //:r7rs -- bench-report`: don\'t edit by hand. -->')
    out('')
    out("ecraven's R7RS benchmarks (`bench/r7rs-ecraven/`, the set behind "
        '<https://ecraven.github.io/r7rs-benchmarks/>) on this implementation and on the other R7RS '
        "implementations in `nix develop .#bench`, each run with its own prelude and postlude and as "
        "ecraven's `bench` script runs it, so the numbers compare with his tables. How to run it, and "
        'what is measured: `bench/README.md`.')
    out('')
    out('## Conditions')
    out('')
    out(f'- Run by `{report["command"]}`, at commit `{report["commit"]}`, from {report["generated"]}, '
        f'for {report.get("minutes", "?")} minutes.')
    out(f'- Host: {report["host"]}.')
    if report['note']:
        out(f'- {report["note"]}')
    scale = report['scale']
    out('- Counts: ' + ("each program's own count (its `.input` file), as in ecraven's tables" if scale == 1
                        else f"{scale:g} times each program's own count (at least 1), on every implementation; "
                        "the Count column gives it") + '.')
    if 'interpreter' in impls:
        out(f'- Ours, interpreter: run {report["interpreter_scale"]:g} times that count (at least 1), its time '
            'then scaled up to the count the others ran, marked †.')
    if 'jit' in impls:
        out(f'- Ours, JIT: one JVM runs the harness {report["jit_iterations"]} times. The first, in a fresh process, '
            "is the comparable number (as for every other implementation, and in ecraven's tables: the harness's "
            'own timing, startup excluded); "JIT steady" is the best of the others, once the JIT has compiled the program.')
    if 'native' in impls:
        out(f'- Ours, native: the native launcher (`bazel build //:scheme-native`, which keeps the Graal JIT) runs the '
            f'harness {report["jit_iterations"]} times in one process, as our JIT\'s JVM does: the first is the comparable '
            'number, "native steady" the best of the others. "Native with startup" is the first with the process\'s '
            'startup and exit: its wall time, less the iterations after the first.')
    interpreter_timeout = report.get('interpreter_timeout', report['timeout'])
    iterated = ' and native launcher\'s' if 'native' in impls else ''
    out(f'- A run is killed after {report["timeout"]} s (ecraven\'s `CPU_LIMIT`), our JIT\'s{iterated} after that '
        'once per iteration' + (f', our interpreter\'s after {interpreter_timeout} s' if interpreter_timeout != report['timeout'] else '')
        + '. Peak memory is the process\'s maximum resident set size. ' + (
            f'Our JVMs{" and native launcher" if "native" in impls else ""} run with `{" ".join(report["heap_vm_args"])}`, '
            'a fixed maximum heap (bench/README.md "Heap"); '
            'the memory tables also give how much of their heap the program used (Peak memory).'
            if 'heap_vm_args' in report else
            'Our JVMs run with the default heap limits (no `-Xmx`), so theirs is as much as the JVM\'s default '
            'ergonomics let the heap grow.'))
    load = report.get('load')
    if load:
        out(f'- The machine\'s 1-minute load average: {load["start"]:g} at the start, at most {load["max"]:g} before a program'
            + (f', {load["end"]:g} at the end' if 'end' in load else '') + '.')
    out('')
    out('## Summary')
    out('')
    heap_summary = [(live_keys, 'live_mb', 'Median live heap (MB)'), (heap_keys, 'heap_mb', 'Median heap after GC (MB)')]
    heap_summary = [h for h in heap_summary if h[0]]
    out('Geometric mean of the time relative to the fastest, over the programs the implementation passed '
        '(failures are not counted, so compare it alongside the failures), and the median peak memory'
        + (' (and for ours, of the heap the programs used: Peak memory)' if heap_summary else '') + '.')
    out('')
    out('| Implementation | Version | Passed | Fastest on | Geometric mean ×fastest | Median peak memory (MB) |'
        + ''.join(f' {title} |' for _, _, title in heap_summary))
    out('|---|---|--:|--:|--:|--:|' + '--:|' * len(heap_summary))
    for key, field, title in columns:
        info = impls[key]
        if 'unavailable' in info:
            if field != 'seconds':
                continue  # said once, on its first row
            out(f'| {title} | not run: {info["unavailable"]} | | | | |' + ' |' * len(heap_summary))
            continue
        ratios = [ratio(p, key, field) for p in programs]
        ratios = [r for r in ratios if r is not None]
        passed = sum(1 for p in programs if time_of(p, key, field) is not None)
        ran = sum(1 for p in programs if key in programs[p]['results'])
        wins = sum(1 for r in ratios if r == 1.0) if field == 'seconds' else '–'
        geomean = f'{math.exp(sum(math.log(r) for r in ratios) / len(ratios)):.2f}' if ratios else '–'
        rss = sorted(programs[p]['results'][key]['rss_mb'] for p in programs
                     if key in programs[p]['results'] and 'rss_mb' in programs[p]['results'][key])
        median = f'{rss[len(rss) // 2]:.0f}' if rss and field == 'seconds' else ''
        heaps = [median_of(key, heap_field) if key in keys_with and field == 'seconds' else ''
                 for keys_with, heap_field, _ in heap_summary]
        out(f'| {title} | {info.get("version", "?")} | {passed}/{ran} | {wins} | {geomean} | {median} |'
            + ''.join(f' {heap} |' for heap in heaps))
    out('')
    out('## Time')
    out('')
    out('Seconds for the count, and in parentheses the time relative to the fastest; the fastest is in bold.')
    out('')
    out('| Program | Count | ' + ' | '.join(title for _, _, title in columns) + ' |')
    out('|---|--:|' + '--:|' * len(columns))
    for program, entry in programs.items():
        cells = []
        for key, field, _ in columns:
            result = entry['results'].get(key)
            if result is None:
                cells.append('')
            elif result['status'] != 'ok':
                cells.append(_short_status(result['status']))
            else:
                t, r = result[field], ratio(program, key, field)
                mark = '†' if key == 'interpreter' and result['count'] != entry['count'] else ''
                text = f'{t:.2f}{mark} ({r:.1f})'
                cells.append(f'**{text}**' if r == 1.0 and field == 'seconds' else text)
        out(f'| {program} | {entry["count"]} | ' + ' | '.join(cells) + ' |')
    out('')
    out('## Peak memory')
    out('')
    out('Maximum resident set size in MB, failed runs included (our JIT\'s'
        + (' and native launcher\'s' if 'native' in impls else '') + ' over all its iterations).')
    if live_keys or heap_keys:
        out('')
        out('For ours, how much of that is heap the program used, in MB (bench/README.md "Heap"):')
        out('')
        if live_keys:
            every = report.get('live_every')
            out(f'- live: the most the program kept alive, measured by collecting the whole heap every {every:g} s '
                'in another run of the program, whose times aren\'t used ("too short": it ended before the first);')
        if heap_keys:
            out('- after GC: the most the heap held right after a collection in the timed run, from the JVM\'s GC log'
                + (' (the native launcher\'s: its `-XX:+PrintGC` lines)' if 'native' in heap_keys else '')
                + ': what was live, and the garbage the collection left in the old generation ("no GC": no collection).')
        if 'native' in heap_keys and 'native' not in live_keys:
            out('- The native launcher has no live heap: native-image has no `jcmd` to collect and count it from outside.')
    out('')
    mem_columns = []
    for key, field, title in columns:
        if field == 'seconds':
            mem_columns.append((key, 'rss', title))
            if key in live_keys:
                mem_columns.append((key, 'live', title + ' live'))
            if key in heap_keys:
                mem_columns.append((key, 'heap', title + ' after GC'))
    out('| Program | ' + ' | '.join(title for _, _, title in mem_columns) + ' |')
    out('|---|' + '--:|' * len(mem_columns))
    for program, entry in programs.items():
        cells = [memory_cell(entry['results'].get(key), kind) for key, kind, _ in mem_columns]
        out(f'| {program} | ' + ' | '.join(cells) + ' |')
    out('')
    out('## Failures')
    out('')
    failures = [(program, key, result) for program, entry in programs.items()
                for key, result in entry['results'].items() if result['status'] != 'ok']
    limits = {'jit': report['timeout'] * report['jit_iterations'], 'native': report['timeout'] * report['jit_iterations'],
              'interpreter': interpreter_timeout}
    if not failures:
        out('None.')
    else:
        out('A timeout is a run killed at its limit (Conditions); our interpreter\'s limit may be shorter '
            'than the others\', so its timeouts say only that it took longer than that.')
        out('')
        out('| Program | Implementation | Status | Last line of output |')
        out('|---|---|---|---|')
        for program, key, result in failures:
            last = (result.get('detail') or '').strip().splitlines()
            last = last[-1].replace('|', '\\|').replace('`', "'")[:120] if last else ''
            status = result['status']
            if status.endswith('timeout'):
                status += f' ({limits.get(key, report["timeout"])} s)'
            out(f'| {program} | {impls[key]["title"]} | {status} | {"`" + last + "`" if last else ""} |')
    out('')
    out('## How each implementation ran')
    out('')
    out('`{src}` is the program as the bench script builds it, `{input}` its `.input` file '
        '(with the count above).')
    out('')
    for key, info in impls.items():
        extra = f' With {"; ".join(info["notes"])}.' if info.get('notes') else ''
        out(f'- {info["title"]}: `{info["command"]}`.{extra}')
    out('')
    os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
    with open(path, 'w', encoding='utf-8') as f:
        f.write('\n'.join(lines))


# ---------------------------------------------------------------------------
# Performance thresholds (plan.md M5-08): a subset of ecraven's set with small workloads, measured
# on the maintainer's machine, and checked there before a PR against bench/thresholds.json
# (bench/README.md)
# ---------------------------------------------------------------------------

_THRESHOLDS = join(_BENCH_DIR, 'thresholds.json')

# How a threshold follows from the baseline runs (bench/README.md "Thresholds").
_THRESHOLD_FLOOR = 1.4  # never closer than 1.4 times the baseline
_THRESHOLD_SIGMAS = 4  # nor than 4 standard deviations of the baseline runs above it
_THRESHOLD_ABOVE_MAX = 1.2  # nor than 1.2 times the slowest baseline run
# The same for the time relative to the baseline divided by the run's machine factor, which takes
# out most of the difference between runners (_machine_factor).
_ADJUSTED_FLOOR = 1.25
_ADJUSTED_ABOVE_MAX = 1.15
_MACHINE_FACTOR_MIN = 10  # benchmarks a run needs for a machine factor


class _CheckCase:
    """One benchmark of `bench-check`: a program of ecraven's set in one mode, with an input
    (the program's .input file with its count replaced, or a whole input of ours, with smaller
    arguments and their known result) that keeps one iteration well under a second; or `startup`,
    the launcher running a hello-world program. Its metric is the median of the iterations after
    `warmup` (JIT: once the program is compiled), in ms; for startup, the median wall time of
    `iterations` processes."""

    def __init__(self, key, mode, group, program=None, count=None, stdin=None, iterations=25, warmup=15):
        self.key = key
        self.mode = mode
        self.group = group
        self.program = program
        self.count = count
        self.stdin = stdin
        self.iterations = iterations
        self.warmup = warmup

    def workload(self):
        """What the baseline was measured on: a baseline only applies to the same workload."""
        work = {'mode': self.mode, 'iterations': self.iterations, 'warmup': self.warmup}
        if self.program:
            work['program'] = self.program
            work['input'] = self.input_text()
        return work

    def input_text(self):
        if self.stdin is not None:
            return self.stdin
        with open(join(_check_bench_set().dir, 'inputs', self.program + '.input'), encoding='utf-8') as f:
            text = f.read()
        return _with_count(text, self.count) if self.count else text


def _check_bench_set():
    return next(s for s in _BENCH_SETS if s.name == 'ecraven')


_JIT, _INTERP = 'jit', 'interpreter'

# The subset: two or three programs per kind of work, in ecraven's groups. Inputs of our own (a
# smaller argument and its result, often the "old inputs" in the .input file's comments) for the
# programs whose count is 1, which a count can't shorten.
_CHECK_CASES = [
    _CheckCase('fib', _JIT, 'arithmetic', 'fib', stdin='8\n30\n832040\n'),
    _CheckCase('tak', _JIT, 'arithmetic', 'tak', stdin='10\n24\n16\n8\n9\n'),
    _CheckCase('fibfp', _JIT, 'arithmetic', 'fibfp', stdin='10\n30.0\n832040.0\n'),
    _CheckCase('sum', _JIT, 'arithmetic', 'sum', count=4000),
    _CheckCase('takl', _JIT, 'tail calls', 'takl', stdin='4\n(24 23 22 21 20 19 18 17 16 15 14 13 12 11 10 9 8 7 6 5 4 3 2 1)\n'
               '(16 15 14 13 12 11 10 9 8 7 6 5 4 3 2 1)\n(8 7 6 5 4 3 2 1)\n9\n'),
    _CheckCase('cpstak', _JIT, 'tail calls', 'cpstak', stdin='4\n24\n16\n8\n9\n'),
    _CheckCase('mazefun', _JIT, 'higher-order', 'mazefun', count=250),
    _CheckCase('deriv', _JIT, 'higher-order', 'deriv', count=500000),
    _CheckCase('peval', _JIT, 'higher-order', 'peval', count=60),
    _CheckCase('browse', _JIT, 'higher-order', 'browse', count=120),
    _CheckCase('ctak', _JIT, 'continuations', 'ctak', stdin='60\n18\n12\n6\n7\n'),
    _CheckCase('fibc', _JIT, 'continuations', 'fibc', stdin='8\n25\n75025\n'),
    _CheckCase('fft', _JIT, 'vectors', 'fft', count=10),
    _CheckCase('pnpoly', _JIT, 'vectors', 'pnpoly', count=30000),
    _CheckCase('triangl', _JIT, 'vectors', 'triangl', count=2),
    _CheckCase('puzzle', _JIT, 'vectors', 'puzzle', count=30),
    _CheckCase('quicksort', _JIT, 'vectors', 'quicksort', count=60),
    _CheckCase('string', _JIT, 'strings and I/O', 'string', count=40),
    _CheckCase('parsing', _JIT, 'strings and I/O', 'parsing', count=50),
    _CheckCase('equal', _JIT, 'strings and I/O', 'equal', count=3),
    _CheckCase('fib-interpreter', _INTERP, 'interpreter', 'fib', stdin='5\n25\n75025\n', iterations=6, warmup=2),
    _CheckCase('tak-interpreter', _INTERP, 'interpreter', 'tak', stdin='10\n18\n12\n6\n7\n', iterations=6, warmup=2),
    _CheckCase('deriv-interpreter', _INTERP, 'interpreter', 'deriv', count=20000, iterations=6, warmup=2),
    _CheckCase('string-interpreter', _INTERP, 'interpreter', 'string', count=30, iterations=6, warmup=2),
    _CheckCase('startup', 'startup', 'startup', iterations=9, warmup=0),
]

_HELLO = '(import (scheme base) (scheme write))\n(display "hello")\n(newline)\n'


def _median(values):
    values = sorted(values)
    n = len(values)
    return values[n // 2] if n % 2 else (values[n // 2 - 1] + values[n // 2]) / 2


def _run_check_case(case, timeout):
    """Runs one case in fresh JVMs. Returns {metric_ms, samples_ms}, or {error}."""
    workdir = tempfile.mkdtemp(prefix=f'r7rs-bench-check-{case.key}-')
    try:
        if case.mode == 'startup':
            file = join(workdir, 'hello.scm')
            with open(file, 'w', encoding='utf-8') as f:
                f.write(_HELLO)
            argv = RUNTIME[0].launcher([file])
            samples = []
            for _ in range(case.iterations):
                start = time.monotonic()
                measured = _run_measured(argv, workdir, None, None, timeout)
                samples.append((time.monotonic() - start) * 1000)
                if measured.timed_out or measured.rc != 0 or measured.output.strip() != 'hello':
                    return {'error': 'hello.scm failed: ' + tail(measured.output, 3)}
            return {'metric_ms': round(_median(samples), 1), 'samples_ms': [round(s, 1) for s in samples]}
        bench_set = _check_bench_set()
        os.symlink(join(bench_set.dir, 'inputs'), join(workdir, 'inputs'))
        os.mkdir(join(workdir, 'outputs'))
        stdin_path = join(workdir, 'stdin')
        with open(stdin_path, 'w', encoding='utf-8') as f:
            f.write(case.input_text())
        src = join(workdir, case.program + '.scm')
        with open(src, 'wb') as f:
            f.write(bench_set.program(case.program, _our_postlude(f'truffle-scheme-{case.mode}', case.iterations)))
        argv = RUNTIME[0].launcher([src], _BENCH_MODES[case.mode][0])
        outcome = _outcome(_run_measured(argv, workdir, stdin_path, None, timeout), case.iterations)
        if outcome['status'] != 'ok':
            return {'error': f'{outcome["status"]}: {outcome.get("detail", "")}'}
        samples = [t * 1000 for t in outcome['times']]
        return {'metric_ms': round(_median(samples[case.warmup:]), 1), 'samples_ms': [round(s, 1) for s in samples]}
    finally:
        shutil.rmtree(workdir, ignore_errors=True)


def _read_thresholds():
    if not exists(_THRESHOLDS):
        return {'benchmarks': {}}
    with open(_THRESHOLDS, encoding='utf-8') as f:
        return json.load(f)


def _threshold_for(samples, floor, slack):
    """(baseline, threshold, sigma) from the values of the baseline runs: the threshold is the largest
    of `floor` times their median, their median plus _THRESHOLD_SIGMAS standard deviations, and
    `slack` times the largest."""
    baseline = _median(samples)
    mean = sum(samples) / len(samples)
    sigma = math.sqrt(sum((s - mean) ** 2 for s in samples) / (len(samples) - 1)) if len(samples) > 1 else 0.0
    return baseline, max(baseline * floor, baseline + _THRESHOLD_SIGMAS * sigma, max(samples) * slack), sigma


def _machine_factor(results, baselines):
    """How fast this run's machine is, relative to the baselines' runs: the median, over the
    benchmarks measured (at least _MACHINE_FACTOR_MIN of them), of measured / baseline. A
    machine's speed and load shift every benchmark alike (GitHub's runners, of several CPU types,
    ranged about 0.7 to 1.4 when the thresholds were measured there, before M7-03). A median, so
    that a few regressed benchmarks don't move it. None if too few benchmarks were measured."""
    ratios = [result['metric_ms'] / baselines[key] for key, result in results.items()
              if 'metric_ms' in result and baselines.get(key)]
    return _median(ratios) if len(ratios) >= _MACHINE_FACTOR_MIN else None


def _bench_platform():
    """The OS and architecture: thresholds measured on one don't apply to another (an arm64 Mac runs
    some benchmarks relatively faster than others, which no machine factor takes out)."""
    return f'{platform.system().lower()}-{platform.machine()}'


def _run_platform(run):
    m = re.match(r'(\S+) .*?\((\S+)\)', run.get('host', ''))
    return run.get('platform') or (f'{m.group(1).lower()}-{m.group(2)}' if m else '?')


def _matching(run, case):
    result = run['results'].get(case.key, {})
    return 'metric_ms' in result and result['workload'] == case.workload()


def _update_thresholds(runs, cases, note):
    """Rewrites bench/thresholds.json's entries for `cases` from the measurement files `runs`."""
    thresholds = _read_thresholds()
    entries = thresholds.get('benchmarks', {})
    for case in cases:
        samples = [run['results'][case.key]['metric_ms'] for run in runs if _matching(run, case)]
        if not samples:
            fail(f'bench-check: no measurement of {case.key} with its current workload in the files given')
        baseline, threshold, sigma = _threshold_for(samples, _THRESHOLD_FLOOR, _THRESHOLD_ABOVE_MAX)
        entries[case.key] = {'group': case.group, 'baseline_ms': round(baseline, 1), 'threshold_ms': round(threshold, 1),
                             'sigma_ms': round(sigma, 1), 'runs_ms': samples, 'workload': case.workload()}
    # The machine-adjusted thresholds, from each run's ratios divided by its machine factor.
    baselines = {key: entry['baseline_ms'] for key, entry in entries.items()}
    factors = [_machine_factor({c.key: run['results'][c.key] for c in _CHECK_CASES if _matching(run, c)}, baselines) for run in runs]
    for case in cases:
        adjusted = [run['results'][case.key]['metric_ms'] / baselines[case.key] / factor
                    for run, factor in zip(runs, factors) if factor and _matching(run, case)]
        entry = entries[case.key]
        for field in ('adjusted_runs', 'adjusted_threshold'):
            entry.pop(field, None)
        if len(adjusted) >= 3:
            _, threshold, _ = _threshold_for(adjusted, _ADJUSTED_FLOOR, _ADJUSTED_ABOVE_MAX)
            entry['adjusted_runs'] = [round(a, 3) for a in adjusted]
            entry['adjusted_threshold'] = round(threshold, 3)
    hosts = sorted({run.get('host', '?') for run in runs})
    platforms = sorted({_run_platform(run) for run in runs})
    if len(platforms) != 1:
        fail(f'bench-check: the runs come from several platforms ({", ".join(platforms)}): thresholds are for one')
    thresholds = {
        'about': 'Generated by `bazel run //:r7rs -- bench-check --update` (plan.md M5-08, bench/README.md "Thresholds"): '
                 "don't edit by hand. Per benchmark: each run's time in ms (runs_ms: the median of a JVM's iterations "
                 'after the warmup, or for startup of the wall times), their median (baseline_ms) and the threshold '
                 f'(threshold_ms: the largest of {_THRESHOLD_FLOOR} x the baseline, the baseline + {_THRESHOLD_SIGMAS} '
                 f'sigma, and {_THRESHOLD_ABOVE_MAX} x the slowest run); and each run\'s time / baseline divided by '
                 "that run's machine factor (adjusted_runs; the machine factor is the median of time / baseline over "
                 'all the benchmarks of the run) and the threshold for that (adjusted_threshold: the largest of '
                 f'{_ADJUSTED_FLOOR} x their median, their median + {_THRESHOLD_SIGMAS} sigma, and {_ADJUSTED_ABOVE_MAX} x '
                 'the largest). '
                 '`bazel run //:r7rs -- bench-check` fails when a benchmark is past either threshold.',
        'measured': {
            'runs': len(runs),
            'note': note,
            'platform': platforms[0],
            'hosts': hosts,
            'commits': sorted({run.get('commit', '?') for run in runs}),
            'dates': sorted({run.get('date', '?')[:10] for run in runs}),
        },
        'benchmarks': {case.key: entries[case.key] for case in _CHECK_CASES if case.key in entries},
    }
    _write_json(thresholds, _THRESHOLDS)
    log(f'bench-check: wrote {os.path.relpath(_THRESHOLDS, repo_root())} from {len(runs)} run(s)')


def r7rs_bench_check(args):
    """run a subset of the benchmarks and fail on a regression past bench/thresholds.json (M5-08)"""
    parser = argparse.ArgumentParser(prog='bazel run //:r7rs -- bench-check', description='See bench/README.md "Thresholds".')
    parser.add_argument('benchmarks', nargs='*', help='which (default: all): ' + ' '.join(c.key for c in _CHECK_CASES))
    parser.add_argument('--retries', type=int, default=2, metavar='N',
                        help='rerun a benchmark past its threshold up to N times, in fresh JVMs, keeping the best (default 2)')
    parser.add_argument('--timeout', type=int, default=300, metavar='SECONDS', help='kill a JVM after this long (default 300)')
    parser.add_argument('--json', metavar='FILE', help='write the measurements here (what --update --from reads)')
    parser.add_argument('--markdown', metavar='FILE', help='append the table here')
    parser.add_argument('--update', action='store_true',
                        help='rewrite the thresholds of the benchmarks chosen, from --from files, or else from --runs runs here')
    parser.add_argument('--from', dest='sources', nargs='+', metavar='FILE', help='with --update: measurement files (--json) of earlier runs')
    parser.add_argument('--runs', type=int, default=3, metavar='N', help='with --update and no --from: runs to measure here (default 3)')
    parser.add_argument('--note', default='', help='with --update: where and how the runs were made')
    parser.add_argument('--measure', action='store_true', help='only measure (with --json): no thresholds, no failure')
    parser.add_argument('--enforce', action='store_true',
                        help='fail past a threshold even on a platform other than the one the thresholds were measured on')
    options = parser.parse_args(args)
    keys = [c.key for c in _CHECK_CASES]
    unknown = [b for b in options.benchmarks if b not in keys]
    if unknown:
        fail(f'bench-check: unknown benchmark(s) {unknown}; choose from {" ".join(keys)}')
    cases = [c for c in _CHECK_CASES if not options.benchmarks or c.key in options.benchmarks]
    if options.update and options.sources:
        runs = []
        for path in options.sources:
            with open(path, encoding='utf-8') as f:
                runs.append(json.load(f))
        _update_thresholds(runs, cases, options.note)
        return 0

    def measure():
        results = {}
        for i, case in enumerate(cases):
            result = _run_check_case(case, options.timeout)
            result['workload'] = case.workload()
            results[case.key] = result
            shown = f'{result["metric_ms"]:.1f} ms' if 'metric_ms' in result else 'ERROR ' + result['error']
            log(f'bench-check: [{i + 1}/{len(cases)}] {case.key}: {shown}')
        return results

    def measurement(results):
        return {'host': _host_description(), 'platform': _bench_platform(), 'commit': _git_commit(),
                'date': datetime.datetime.now(datetime.timezone.utc).strftime('%Y-%m-%d %H:%M UTC'), 'results': results}

    if options.update:
        runs = []
        for run in range(options.runs):
            log(f'bench-check: run {run + 1} of {options.runs}')
            runs.append(measurement(measure()))
        _update_thresholds(runs, cases, options.note or f'{options.runs} runs on {_host_description()}')
        return 0

    results = measure()
    if options.measure:
        if options.json:
            _write_json(measurement(results), options.json)
        return 0
    measured_on = _read_thresholds().get('measured', {}).get('platform')
    enforced = options.enforce or measured_on == _bench_platform()
    thresholds = _read_thresholds().get('benchmarks', {})
    usable = {case.key: thresholds[case.key] for case in cases
              if case.key in thresholds and thresholds[case.key]['workload'] == case.workload() and 'metric_ms' in results[case.key]}
    factor = _machine_factor({key: results[key] for key in usable}, {key: entry['baseline_ms'] for key, entry in usable.items()})

    def verdict(result, entry):
        ratio = result['metric_ms'] / entry['baseline_ms']
        if result['metric_ms'] > entry['threshold_ms']:
            return 'REGRESSION', f'{result["metric_ms"]:.1f} ms, past its threshold of {entry["threshold_ms"]:.1f} ms'
        if factor and 'adjusted_threshold' in entry and ratio / factor > entry['adjusted_threshold']:
            return 'REGRESSION (adjusted)', (f'{ratio:.2f} x its baseline, {ratio / factor:.2f} x adjusted for this machine, '
                                             f'past its adjusted threshold of {entry["adjusted_threshold"]:.2f}')
        return 'ok', ''

    rows, failed, slower = [], [], []
    for case in cases:
        result, entry = results[case.key], thresholds.get(case.key)
        if 'error' in result:
            status = 'ERROR'
        elif entry is None:
            status = 'no threshold'
        elif entry['workload'] != case.workload():
            status = 'workload changed'
        else:
            status, why = verdict(result, entry)
            tries = 0
            while status != 'ok' and enforced and tries < options.retries:
                tries += 1
                log(f'bench-check: {case.key}: {why}: retry {tries} of {options.retries}')
                again = _run_check_case(case, options.timeout)
                result.setdefault('retries_ms', []).append(again.get('metric_ms'))
                if again.get('metric_ms') is not None and again['metric_ms'] < result['metric_ms']:
                    result['metric_ms'], result['samples_ms'] = again['metric_ms'], again['samples_ms']
                status, why = verdict(result, entry)
        if status.startswith('REGRESSION') and not enforced:
            status = status.replace('REGRESSION', 'slower (not enforced here)')
            slower.append(case.key)
        elif status != 'ok':
            failed.append(f'{case.key} ({status})')
        result['status'] = status
        rows.append((case, result, entry, status))
    if options.json:
        _write_json(dict(measurement(results), machine_factor=factor), options.json)
    lines = [f'Thresholds measured on {measured_on}; this is {_bench_platform()}: '
             + ('enforced.' if enforced else 'not enforced (`--enforce` to).'), '',
             f'Machine factor (median of measured / baseline): {f"{factor:.2f}" if factor else "none (too few benchmarks)"}', '',
             '| Benchmark | Group | Baseline (ms) | Threshold (ms) | Measured (ms) | ×baseline | Adjusted ×baseline | Adjusted threshold | Status |',
             '|---|---|--:|--:|--:|--:|--:|--:|---|']
    for case, result, entry, status in rows:
        measured = result.get('metric_ms')
        known = entry is not None and measured is not None
        ratio = f'{measured / entry["baseline_ms"]:.2f}' if known else ''
        adjusted = f'{measured / entry["baseline_ms"] / factor:.2f}' if known and factor else ''
        lines.append(f'| {case.key} | {case.group} | {entry["baseline_ms"] if entry else ""} | {entry["threshold_ms"] if entry else ""} '
                     f'| {measured if measured is not None else ""} | {ratio} | {adjusted} '
                     f'| {entry.get("adjusted_threshold", "") if entry else ""} | {status} |')
    log('\n'.join(lines))
    if options.markdown:
        with open(options.markdown, 'a', encoding='utf-8') as f:
            f.write('## Benchmark thresholds\n\n' + '\n'.join(lines) + '\n\n')
    if failed:
        fail('bench-check: failed: ' + ', '.join(failed) + '. A slowdown that is intended (or a workload change) '
                 'needs new thresholds: bench/README.md "Thresholds"')
    if slower:
        log(f'WARNING: bench-check: past their thresholds, but these were measured on {measured_on} and this is '
                f'{_bench_platform()}, where they don\'t apply (--enforce to fail anyway): {" ".join(slower)}.')
    else:
        log(f'bench-check: all {len(cases)} benchmarks within their thresholds')
    return 0


def benchmark(args):
    """One program of a set per JVM, as the set's harness runs it, printing each iteration's time:
    SET:PROGRAM[,PROGRAM...] or SET:* [--mode jit|interpreter|*] [--iterations N] [--warmup N] [--count N]
    [--timeout S] [--json FILE] [JVM option...]. A program whose check fails fails the command,
    unless bench/README.md lists it as failing; a listed program that passes fails too."""
    parser = argparse.ArgumentParser(prog='bazel run //:r7rs -- benchmark', parents=[_bench_options_parser()])
    parser.add_argument('benchmark', help='SET:PROGRAM or SET:*, SET being ecraven or larceny')
    parser.add_argument('--json', metavar='FILE', help='also write the times (ms per iteration) and the memory here')
    parser.add_argument('--live-every', type=float, metavar='SECONDS',
                        help='also measure the live heap, collecting the whole heap this often (which changes the times)')
    options, jvm_args = parser.parse_known_args(args)
    name, _, which = options.benchmark.partition(':')
    sets = {s.name: s for s in _BENCH_SETS}
    if name.startswith('r7rs-'):
        name = name[len('r7rs-'):]
    if name not in sets or not which:
        fail(f'benchmark: SET:PROGRAM or SET:*, SET one of {" ".join(sets)}')
    bench_set = sets[name]
    programs = [p for p, _ in bench_set.benchmarks()]
    if which != '*':
        unknown = [p for p in which.split(',') if p not in programs]
        if unknown:
            fail(f'benchmark: not in {name}\'s set: {unknown}')
        programs = which.split(',')
    modes = list(_BENCH_MODES) if options.mode == '*' else [options.mode]
    results, failed = [], []
    for mode in modes:
        vm_args, iterations, warmup = _BENCH_MODES[mode]
        iterations = iterations if options.iterations is None else options.iterations
        warmup = min(warmup, iterations - 1) if options.warmup is None else options.warmup
        if iterations < 1 or not 0 <= warmup < iterations:
            fail('--iterations must be at least 1 and --warmup less than --iterations')
        for program in programs:
            problem, times, memory = _run_benchmark(bench_set, program, mode, vm_args + jvm_args, iterations, options)
            reason = bench_set.failures().get(program)
            if problem and reason:
                log(f'{name}:{program} failed, as bench/README.md lists: {reason}')
                continue
            if problem:
                failed.append(f'{name}:{program} ({mode}): {problem}')
            elif reason:
                failed.append(f'{name}:{program} passes but bench/README.md lists it as failing ({reason}): remove it from the list')
            else:
                steady = times[warmup:]
                heap, live = memory.get('heap_mb'), memory.get('live_mb')
                log(f'{name}:{program} ({mode}): ' + ' '.join(f'{t:.0f}' for t in times)
                    + f' ms; after {warmup} warmup, mean {sum(steady) / len(steady):.0f} ms; peak RSS {memory["rss_mb"]:.0f} MB, '
                    + (f'heap after GC {heap:.0f} MB' if heap is not None else 'no GC') + f' ({memory.get("gcs", 0)} GCs)'
                    + (f', live heap {live:.0f} MB ({memory["live_samples"]} samples)' if live is not None else ''))
                results.append(dict({'benchmark': program, 'set': name, 'mode': mode, 'warmup': warmup, 'times_ms': times}, **memory))
    if options.json:
        with open(options.json, 'w', encoding='utf-8') as f:
            json.dump({'commit': _git_commit(), 'results': results}, f, indent=1)
    if failed:
        fail('benchmark: failed:\n  ' + '\n  '.join(failed))


def _run_benchmark(bench_set, program, mode, jvm_args, iterations, options):
    """(problem or None, [ms per iteration]) of one program in one JVM."""
    postlude = _our_postlude(f'truffle-scheme-{mode}', iterations)
    with open(join(bench_set.dir, 'inputs', program + '.input'), encoding='utf-8') as f:
        stdin = f.read()
    if options.count:
        stdin = _with_count(stdin, options.count)
    # Programs read inputs/... and write outputs/... relative to the working directory.
    workdir = tempfile.mkdtemp(prefix=f'r7rs-bench-{program}-')
    try:
        os.symlink(join(bench_set.dir, 'inputs'), join(workdir, 'inputs'))
        os.mkdir(join(workdir, 'outputs'))
        file = join(workdir, program + '.scm')
        with open(file, 'wb') as f:
            f.write(bench_set.program(program, postlude))
        log(f'{bench_set.name}:{program} ({mode}, {iterations} iterations' + (f', count {options.count}' if options.count else '') + ')')
        stdin_path = join(workdir, 'stdin')
        with open(stdin_path, 'w', encoding='utf-8') as f:
            f.write(stdin)
        gc_log = join(workdir, 'gc.log')
        p = _run_measured(RUNTIME[0].launcher([file], jvm_args + _gc_log_vm_args(gc_log)), workdir, stdin_path, None,
                          options.timeout or math.inf, gc_log, options.live_every)
    finally:
        shutil.rmtree(workdir, ignore_errors=True)
    if p.timed_out:
        return f'timeout after {options.timeout} s', [], {}
    if re.search(r'^(ERROR|INTERNAL ERROR):', p.output, re.MULTILINE) or p.rc != 0:
        return f'exit {p.rc}:\n{tail(p.output)}', [], {}
    times = [float(m.group(1)) * 1000 for m in _ELAPSED_LINE.finditer(p.output)]
    if len(times) != iterations:
        return f'{len(times)} "Elapsed time" lines for {iterations} iterations:\n{tail(p.output)}', [], {}
    return None, times, dict({'rss_mb': round(p.rss_bytes / 2**20, 1)}, **(p.heap or {}))

"""The differential fuzzer (tests/fuzz/README.md): random programs (gen.py) under chibi-scheme and
under ours; a program whose stdout or exit status differs is minimised and saved.

  bazel run //:r7rs -- fuzz [--seed N] [--count N] [--mode interp,compile,torture,torture-owned,native]
                            [--native FILE] ...
"""

import concurrent.futures
import hashlib
import os
import re
import subprocess
import sys
import tempfile
import threading
import time
from os.path import join

import gen
import harness

# What each mode passes the JVM (harness.vm_args): the conformance tests' modes. `native` runs the
# native launcher as //tests:conformance_native does: interpreted, with harness.native_test_options()
# and the launcher options of vm_args(native=True), none of them a JVM option (Runner.command).
MODES = {
    'interp': dict(compile=False, torture=False),
    'compile': dict(compile=True, torture=False),
    'torture': dict(compile=False, torture=True),
    'torture-owned': dict(compile=False, torture='owned'),
    'native': dict(compile=False, torture=False, native=True),
}
# The native launcher that `--mode native` runs when --native names none: the checkout's.
DEFAULT_NATIVE = join('bazel-bin', 'scheme-native')

# A run that prints more than this, or takes longer, says nothing about either implementation: the
# generator made a program too big to be useful, and the program is skipped.
MAX_OUTPUT = 1 << 20
CHIBI_TIMEOUT = 30
OURS_TIMEOUT = 120

# chibi-scheme's known departures from R7RS that every program would otherwise hit. Each is
# undone in chibi's stdout before comparing, so it can't hide one of ours.
# - A flonum whose shortest digits are one digit and an exponent: chibi writes `1e+21`, which has
#   no decimal point; R7RS 6.2.7 says the result "contains a decimal point" (design.md D9).
_CHIBI_ONE_DIGIT_EXPONENT = re.compile(r'(?<![\w.#\\|])(-?\d)e(?=[+-]\d)')


# - A negative rational whose denominator is 2^62, where chibi's fixnums end: chibi writes
#   (/ -1 (expt 2 62)) as -1/-4611686018427387904.
_CHIBI_NEGATIVE_DENOMINATOR = re.compile(r'(?<![\w.#\\|])(-\d+)/-(\d+)')
# - Bytevectors: chibi writes their bytes in hexadecimal, `#u8(#x28 #xA4)`; R7RS 6.9 leaves the
#   notation of the numbers to the implementation, and ours writes `#u8(40 164)`.
_CHIBI_BYTEVECTOR = re.compile(r'#u8\(([^()]*)\)')
_HEX_BYTE = re.compile(r'#x([0-9A-Fa-f]+)')


def normalize_chibi(out):
    out = _CHIBI_ONE_DIGIT_EXPONENT.sub(r'\1.0e', out)
    out = _CHIBI_NEGATIVE_DENOMINATOR.sub(r'\1/\2', out)
    return _CHIBI_BYTEVECTOR.sub(lambda m: '#u8(' + _HEX_BYTE.sub(lambda h: str(int(h.group(1), 16)), m.group(1)) + ')',
                                 out)


class Result:
    __slots__ = ('rc', 'out', 'err')

    def __init__(self, rc, out, err):
        self.rc, self.out, self.err = rc, out, err


def _run(command, timeout, env=None):
    try:
        p = subprocess.run(command, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                           timeout=timeout, env=env)
    except subprocess.TimeoutExpired:
        return Result('timeout', '', '')
    out = p.stdout.decode('utf-8', 'replace')
    if len(out) > MAX_OUTPUT:
        return Result('too-much-output', '', '')
    return Result(p.returncode, out, p.stderr.decode('utf-8', 'replace')[-4000:])


class Runner:
    """Runs program files under chibi (cached by content) and under ours, in one mode: on the JVM
    (runtime, a harness.Runtime) or through the native launcher (native, its path)."""

    def __init__(self, chibi, runtime, workdir, native=None):
        self.chibi = chibi
        self.runtime = runtime
        self.native = native
        self.workdir = workdir
        self._chibi_cache = {}
        self._lock = threading.Lock()
        os.makedirs(workdir, exist_ok=True)

    def write(self, text):
        digest = hashlib.sha1(text.encode('utf-8')).hexdigest()[:16]
        path = join(self.workdir, f'{digest}.scm')
        if not os.path.exists(path):
            tmp = f'{path}.{threading.get_ident()}.tmp'  # another thread may write the same file
            with open(tmp, 'w', encoding='utf-8') as f:
                f.write(text)
            os.replace(tmp, path)
        return digest, path

    def run_chibi(self, text):
        digest, path = self.write(text)
        with self._lock:
            if digest in self._chibi_cache:
                return self._chibi_cache[digest]
        r = _run([self.chibi, path], CHIBI_TIMEOUT)
        if isinstance(r.rc, int):
            r.out = normalize_chibi(r.out)
        with self._lock:
            self._chibi_cache[digest] = r
        return r

    def command(self, path, mode):
        """The command line that runs the program file `path` under ours, in `mode`."""
        if MODES[mode].get('native'):
            options = harness.launcher_options(harness.vm_args(**MODES[mode]))
            return [self.native] + harness.native_test_options() + options + [path]
        return self.runtime.launcher([path], harness.vm_args(**MODES[mode]))

    def run_ours(self, text, mode):
        _, path = self.write(text)
        return _run(self.command(path, mode), OURS_TIMEOUT)


_CRASH = re.compile(r'\b(?:[a-z_$][\w$]*\.)+[A-Z][\w$]*(?:Exception|Error)\b')


def signature(chibi, ours):
    """None if the runs agree; else what characterises the difference (the reducer keeps it)."""
    if chibi.rc == ours.rc and chibi.out == ours.out:
        return None
    crash = None
    if ours.rc != chibi.rc:
        m = _CRASH.search(ours.err)
        crash = m.group(0) if m else None
    return (chibi.rc, ours.rc, chibi.out != ours.out, crash)


def first_difference(a, b):
    al, bl = a.splitlines(), b.splitlines()
    for i, (x, y) in enumerate(zip(al, bl)):
        if x != y:
            return f'line {i + 1}:\n  chibi: {x[:300]}\n  ours:  {y[:300]}'
    if len(al) != len(bl):
        i = min(len(al), len(bl))
        return (f'line {i + 1}:\n  chibi: {(al[i] if i < len(al) else "<end>")[:300]}\n'
                f'  ours:  {(bl[i] if i < len(bl) else "<end>")[:300]}')
    return 'stdout is the same'


def first_line(a, b):
    """The number of the first line where a and b differ, or None if they are the same."""
    if a == b:
        return None
    for i, (x, y) in enumerate(zip(a.splitlines(), b.splitlines())):
        if x != y:
            return i + 1
    return min(len(a.splitlines()), len(b.splitlines())) + 1


def describe(chibi, ours):
    lines = [f'exit status: chibi {chibi.rc}, ours {ours.rc}']
    if chibi.out != ours.out:
        lines.append(first_difference(chibi.out, ours.out))
    if ours.rc != chibi.rc:
        lines.append('ours stderr (tail):\n' + harness.tail(ours.err, 8))
        lines.append('chibi stderr (tail):\n' + harness.tail(chibi.err, 4))
    return '\n'.join(lines)


# ---------------------------------------------------------------------------
# Programs
# ---------------------------------------------------------------------------

def batch_forms(seed, start, count, depth, cases):
    """The forms of programs start..start+count-1, one file: only the last may end the program."""
    forms = [gen.IMPORTS]
    for i in range(start, start + count):
        forms += gen.generate(seed, i, depth=depth, cases=cases, exits=i == start + count - 1)
    return forms


def text_of(forms, note=''):
    return gen.program_text(forms, f';; {note}' if note else None)


# ---------------------------------------------------------------------------
# Minimising: delta debugging over the top-level forms, then over the tree
# ---------------------------------------------------------------------------

# Outputs a smaller program may reach that the generator keeps clear of, because R7RS leaves them
# open or chibi-scheme lacks them: #<...> (procedures, eof), NaNs, complex numbers.
_DRIFT = [re.compile(p) for p in (r'#<', r'nan\.0', r'[+-](?:[\d./]*(?:e[+-]?\d+)?|inf\.0|nan\.0)i(?![\w.])')]


class Reducer:
    """Delta debugging: a smaller program is kept if it differs in the same way (`signature`) and
    shows nothing the original didn't (_DRIFT)."""

    def __init__(self, runner, mode, target, jobs, budget, original=''):
        self.runner, self.mode, self.target, self.jobs = runner, mode, target, jobs
        self.budget = budget
        self.tests = 0
        self.allowed = {p.pattern for p in _DRIFT if p.search(original)}

    def drifted(self, out):
        return any(p.pattern not in self.allowed and p.search(out) for p in _DRIFT)

    def interesting_many(self, candidates):
        """For each candidate list of forms, whether it still shows the difference."""
        def test(forms):
            text = text_of(forms)
            chibi = self.runner.run_chibi(text)
            if chibi.rc in ('timeout', 'too-much-output') or self.drifted(chibi.out):
                return False
            ours = self.runner.run_ours(text, self.mode)
            return not self.drifted(ours.out) and signature(chibi, ours) == self.target
        self.tests += len(candidates)
        if len(candidates) == 1 or self.jobs == 1:
            return [test(c) for c in candidates]
        with concurrent.futures.ThreadPoolExecutor(self.jobs) as pool:
            return list(pool.map(test, candidates))

    def first_interesting(self, candidates):
        """The first interesting candidate, testing `jobs` at a time."""
        for i in range(0, len(candidates), self.jobs):
            if self.tests >= self.budget:
                return None
            chunk = candidates[i:i + self.jobs]
            for c, ok in zip(chunk, self.interesting_many(chunk)):
                if ok:
                    return c
        return None

    def reduce(self, forms, line=None):
        """The smallest program found; `line`: stdout's first differing line, if any, to try cutting
        the program after the form that wrote it first."""
        if line:
            newlines = [i for i, f in enumerate(forms) if f == ['newline']]
            if len(newlines) >= line:
                cut = self.first_interesting([forms[:newlines[line - 1] + 1]])
                forms = cut or forms
        header, body = forms[:1], forms[1:]
        body = self.ddmin(header, body)
        forms = header + body
        changed = True
        while changed and self.tests < self.budget:
            changed = False
            for path in list(_paths(forms)):
                if self.tests >= self.budget:
                    break
                node = _get(forms, path)
                if node is None:
                    continue
                candidates = [_set(forms, path, c) for c in _replacements(node, len(path))]
                if len(path) >= 2:
                    parent = _get(forms, path[:-1])
                    if isinstance(parent, list) and len(parent) > 1 and path[-1] > 0:
                        candidates.insert(0, _delete(forms, path))
                candidates = [c for c in candidates if c is not None and c != forms]
                better = self.first_interesting(candidates)
                if better is not None:
                    forms = better
                    changed = True
            if changed:
                body = self.ddmin(header, forms[1:])
                forms = header + body
        return forms

    def ddmin(self, header, body):
        """Zeller's ddmin over the top-level forms after the import."""
        n = 2
        while len(body) >= 2 and self.tests < self.budget:
            size = max(1, len(body) // n)
            chunks = [body[i:i + size] for i in range(0, len(body), size)]
            complements = [[f for j, c in enumerate(chunks) if j != i for f in c] for i in range(len(chunks))]
            better = self.first_interesting([header + c for c in complements])
            if better is not None:
                body = better[1:]
                n = max(n - 1, 2)
                continue
            if n >= len(body):
                break
            n = min(len(body), n * 2)
        return body


_ATOMS = ['0', '1', '#f', "'()", '""', "'a", '#\\a']


def _replacements(node, depth):
    out = []
    if isinstance(node, list) and depth > 1:
        for child in node:
            if child is not node and child not in ('.', '...', '=>', 'else', '_'):
                out.append(child)
    if depth > 1 and node not in _ATOMS:
        out += [a for a in _ATOMS if a != node]
    if isinstance(node, list) and len(node) > 1:
        # Drop the elements after the first, one at a time (for lists, vectors and calls).
        for i in range(len(node) - 1, 0, -1):
            copy = type(node)(node[:i] + node[i + 1:])
            out.append(copy)
    return out


def _paths(tree):
    """Paths of every node, breadth first, from the top-level forms (after the import)."""
    queue = [(i,) for i in range(1, len(tree))]
    while queue:
        path = queue.pop(0)
        yield path
        node = _get(tree, path)
        if isinstance(node, list):
            queue += [path + (i,) for i in range(len(node))]


def _get(tree, path):
    node = tree
    for i in path:
        if not isinstance(node, list) or i >= len(node):
            return None
        node = node[i]
    return node


def _set(tree, path, value):
    if not path:
        return value
    copy = type(tree)(tree)
    copy[path[0]] = _set(tree[path[0]], path[1:], value)
    return copy


def _delete(tree, path):
    parent = _get(tree, path[:-1])
    new = type(parent)(parent[:path[-1]] + parent[path[-1] + 1:])
    return _set(tree, path[:-1], new)


# ---------------------------------------------------------------------------
# The command
# ---------------------------------------------------------------------------

def fuzz(chibi, runtime, seed, count, modes, start=0, batch=10, jobs=4, depth=5, cases=8, out=None,
         minimize=True, budget=400, log=harness.log, native=None):
    """Runs `count` programs from `start` in each mode; returns the mismatches as
    [(mode, first program, file, minimised file or None, description)]. `native`: the native
    launcher's path, for the `native` mode."""
    out = out or join(tempfile.gettempdir(), 'r7rs-fuzz')
    runner = Runner(chibi, runtime, join(out, 'work'), native)
    files = [(s, min(batch, start + count - s)) for s in range(start, start + count, batch)]
    texts, ends = {}, {}
    for s, n in files:
        forms = batch_forms(seed, s, n, depth, cases)
        texts[s] = text_of(forms, f'fuzz --seed {seed} --start {s} --count {n} --batch {n} --depth {depth} '
                           f'--cases {cases}')
        ends[s] = isinstance(forms[-1], list) and forms[-1][0] in gen.ENDINGS
    stats = {m: {'same': 0, 'different': 0, 'skipped': 0, 'chibi-error': 0} for m in modes}
    found = []
    started = time.time()

    def one(mode, s, n):
        text = texts[s]
        chibi = runner.run_chibi(text)
        if chibi.rc in ('timeout', 'too-much-output'):
            return mode, s, n, 'skipped', chibi, None
        ours = runner.run_ours(text, mode)
        if chibi.rc != 0 and not ends[s]:
            # The generator promised a program that runs: chibi's failing it is worth a look.
            return mode, s, n, 'chibi-error', chibi, ours
        if ours.rc == 'timeout' and chibi.rc != 'timeout':
            return mode, s, n, 'different', chibi, ours
        return mode, s, n, ('same' if signature(chibi, ours) is None else 'different'), chibi, ours

    with concurrent.futures.ThreadPoolExecutor(jobs) as pool:
        futures = [pool.submit(one, m, s, n) for m in modes for s, n in files]
        done = 0
        for f in concurrent.futures.as_completed(futures):
            mode, s, n, verdict, chibi, ours = f.result()
            stats[mode][verdict] += n if verdict == 'same' else 1
            done += 1
            if verdict == 'different':
                stats[mode]['same'] += n - 1
                found.append((mode, s, n, chibi, ours))
                log(f'[{mode}] DIFFERENT: programs {s}..{s + n - 1}\n' + describe(chibi, ours))
            elif verdict == 'skipped':
                log(f'[{mode}] skipped programs {s}..{s + n - 1}: chibi {chibi.rc}')
            elif verdict == 'chibi-error':
                stats[mode]['same'] += n - 1
                log(f'[{mode}] chibi failed programs {s}..{s + n - 1} (exit {chibi.rc}):\n' + harness.tail(chibi.err))
                path = join(out, f'chibi-error-{seed}-{s}.scm')
                with open(path, 'w', encoding='utf-8') as fh:
                    fh.write(texts[s])
            if done % 50 == 0:
                log(f'... {done}/{len(futures)} files, {time.time() - started:.0f}s')

    results = []
    for mode, s, n, chibi, ours in sorted(found, key=lambda x: (x[0], x[1])):
        path = join(out, f'{mode}-{seed}-{s}.scm')
        with open(path, 'w', encoding='utf-8') as fh:
            fh.write(texts[s])
        reduced_path = None
        description = describe(chibi, ours)
        if minimize:
            target = signature(chibi, ours)
            reducer = Reducer(runner, mode, target, jobs, budget, chibi.out + ours.out)
            forms = reducer.reduce(batch_forms(seed, s, n, depth, cases), first_line(chibi.out, ours.out))
            reduced_path = join(out, f'{mode}-{seed}-{s}.min.scm')
            text = text_of(forms, f'minimised from {path} ({reducer.tests} runs), {mode}')
            with open(reduced_path, 'w', encoding='utf-8') as fh:
                fh.write(text)
            c, o = runner.run_chibi(text), runner.run_ours(text, mode)
            description = describe(c, o)
            log(f'[{mode}] minimised in {reducer.tests} runs: {reduced_path}\n{text}{description}')
        results.append((mode, s, path, reduced_path, description))

    for mode in modes:
        st = stats[mode]
        log(f'{mode}: {count} programs in {len(files)} files: {st["same"]} same, {st["different"]} files different, '
            f'{st["skipped"]} files skipped (too slow or too big under chibi), {st["chibi-error"]} chibi errors')
    log(f'{time.time() - started:.0f}s; files in {out}')
    return results, stats


def main_args(parser):
    parser.add_argument('--seed', type=int, default=1)
    parser.add_argument('--count', type=int, default=100, help='programs per mode')
    parser.add_argument('--start', type=int, default=0, help='the first program\'s index')
    parser.add_argument('--mode', default='interp',
                        help='comma-separated: ' + ', '.join(MODES))
    parser.add_argument('--native', metavar='FILE',
                        help=f'the native launcher --mode native runs (default: the checkout\'s {DEFAULT_NATIVE})')
    parser.add_argument('--batch', type=int, default=10, help='programs per file (per process)')
    parser.add_argument('--jobs', type=int, default=4)
    parser.add_argument('--depth', type=int, default=5)
    parser.add_argument('--cases', type=int, default=8, help='written expressions per program')
    parser.add_argument('--out', help='where to save programs that differ (default: $TMPDIR/r7rs-fuzz)')
    parser.add_argument('--no-minimize', dest='minimize', action='store_false')
    parser.add_argument('--budget', type=int, default=400, help='runs the minimiser may spend per mismatch')
    parser.add_argument('--print', dest='print_only', action='store_true',
                        help='print the programs instead of running them')


def native_launcher(given):
    """The native launcher for --mode native: the one --native names (`given`), else the checkout's
    bazel-bin/scheme-native."""
    if given:
        path = os.path.abspath(given)
        harness.check(os.path.isfile(path) and os.access(path, os.X_OK), f'--native {given}: no such executable')
        return path
    path = join(harness.workspace(), DEFAULT_NATIVE)
    harness.check(os.path.isfile(path) and os.access(path, os.X_OK),
                  f'--mode native: no native launcher at {path}; build it with `bazel build //:scheme-native` '
                  '(a minute, about 3 GB), or name one with --native FILE')
    return path


def run_command(o, chibi, runtime):
    modes = o.mode.split(',')
    for m in modes:
        harness.check(m in MODES, f'unknown mode {m}; modes: {", ".join(MODES)}')
    harness.check(not o.native or 'native' in modes, f'--native is for --mode native; the modes given are {o.mode}')
    if o.print_only:
        for s in range(o.start, o.start + o.count, o.batch):
            n = min(o.batch, o.start + o.count - s)
            sys.stdout.write(text_of(batch_forms(o.seed, s, n, o.depth, o.cases)))
        return
    native = native_launcher(o.native) if 'native' in modes else None
    if native:
        harness.log(f'native: {native}')
    out =os.path.abspath(o.out) if o.out else None
    results, _ = fuzz(chibi, runtime, o.seed, o.count, modes, start=o.start, batch=o.batch, jobs=o.jobs,
                      depth=o.depth, cases=o.cases, out=out, minimize=o.minimize, budget=o.budget, native=native)
    if results:
        harness.fail(f'{len(results)} files differ: ' + ' '.join(r[3] or r[2] for r in results))

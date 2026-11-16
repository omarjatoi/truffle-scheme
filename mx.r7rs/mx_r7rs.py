"""Custom mx commands for the r7rs suite: checks, tests, and the r7rs-* tools.

Commands whose machinery doesn't exist yet abort with the task that brings
them. When such a task lands, replace the `_not_implemented` call and drop the
matching entry in `_PENDING_GATE_TASKS`.
"""

import argparse
import concurrent.futures
import difflib
import functools
import hashlib
import os
import re
import shutil
import time
from os.path import exists, join

import mx
import mx_unittest

_suite = mx.suite('r7rs')

# Flags the runtime reads (plan.md M0-01).
# --compile: immediate, synchronous compilation, and compilation failures are errors.
COMPILE_VM_ARGS = [
    '-Dpolyglot.engine.AllowExperimentalOptions=true',  # BackgroundCompilation is experimental
    '-Dpolyglot.engine.CompileImmediately=true',
    '-Dpolyglot.engine.BackgroundCompilation=false',
    '-Dpolyglot.engine.CompilationFailureAction=Throw',
]
# Default for the fast loop: interpreter only.
INTERPRETER_VM_ARGS = [
    '-Dpolyglot.engine.AllowExperimentalOptions=true',  # engine.Compilation is experimental
    '-Dpolyglot.engine.Compilation=false',
]
# --torture: capture + resume at every non-tail call. The runtime ignores it until M2.
TORTURE_VM_ARGS = ['-Dscheme.torture=true']

# Truffle on JDK 25 uses restricted native access and sun.misc.Unsafe.
_TRUFFLE_VM_ARGS = [
    '--enable-native-access=org.graalvm.truffle',
    '--sun-misc-unsafe-memory-access=allow',
]



def r7rs_vm_args(compile=False, torture=False):
    args = list(COMPILE_VM_ARGS if compile else INTERPRETER_VM_ARGS)
    if torture:
        args += TORTURE_VM_ARGS
    return args


def _not_implemented(command, task):
    mx.abort(f'mx {command}: not implemented until {task} (see docs/plan.md)')


# ---------------------------------------------------------------------------
# GraalVM / Truffle version check (design.md D11)
# ---------------------------------------------------------------------------

def _truffle_version():
    return mx.library('TRUFFLE_API').maven['version']


def _graalvm_version(java_home):
    release = join(java_home, 'release')
    if not exists(release):
        return None
    with open(release) as f:
        for line in f:
            m = re.match(r'^GRAALVM_VERSION="?([^"\n]*)"?', line)
            if m:
                return m.group(1)
    return None


def _check_graalvm_version():
    java_home = mx.get_jdk(tag='default').home
    graalvm = _graalvm_version(java_home)
    truffle = _truffle_version()
    if graalvm != truffle:
        found = f'GraalVM {graalvm}' if graalvm else 'not a GraalVM JDK'
        mx.abort(
            f'JAVA_HOME ({java_home}) is {found}, but mx.r7rs/suite.py pins Truffle {truffle}.\n'
            'Run mx inside `nix develop`, which sets JAVA_HOME to the matching GraalVM. If you are\n'
            'upgrading, bump nixpkgs in flake.lock and the Truffle library versions and digests in\n'
            'suite.py together (design.md D11).')


def mx_post_parse_cmd_line(opts):
    _check_graalvm_version()


# ---------------------------------------------------------------------------
# Unit tests
# ---------------------------------------------------------------------------

class _R7RSUnittestConfig(mx_unittest.MxUnittestConfig):
    """Applied to SCHEME_TEST (its "unittestConfig"). Plain `mx unittest` runs with the
    Graal JIT in its normal (background) mode; `mx r7rs-unittest` adds mode flags."""

    def __init__(self):
        super().__init__('r7rs')
        self.mode_vm_args = []

    def apply(self, config):
        vm_args, main_class, main_class_args = config
        return (vm_args + _TRUFFLE_VM_ARGS + self.mode_vm_args, main_class, main_class_args)


_unittest_config = _R7RSUnittestConfig()
mx_unittest.register_unittest_config(_unittest_config)


def _default_jobs():
    return os.cpu_count() or 1


def _shard(text, command, items):
    """The items that `--shard I/N` selects: every Nth one, starting with the Ith. CI runs
    shard 1/N, ..., N/N on N machines to split a step whose work saturates one machine."""
    if text is None:
        return items
    m = re.fullmatch(r'(\d+)/(\d+)', text)
    if not m or not 1 <= int(m.group(1)) <= int(m.group(2)):
        mx.abort(f'mx {command}: --shard takes I/N with 1 <= I <= N, not {text}')
    return items[int(m.group(1)) - 1::int(m.group(2))]


def r7rs_unittest(args):
    """run the JUnit tests in a given mode: [--compile] [--torture] [-j N] [--shard I/N] [unittest args...]"""
    parser = argparse.ArgumentParser(prog='mx r7rs-unittest')
    parser.add_argument('--compile', action='store_true', help='compile immediately; compilation failures are errors')
    parser.add_argument('--torture', action='store_true', help='capture + resume at every non-tail call (from M2)')
    parser.add_argument('-j', '--jobs', type=int, default=1, metavar='N',
                        help='split the test classes over N JVMs that run at once (default 1)')
    parser.add_argument('--shard', metavar='I/N', help='run only every Nth test class, starting with the Ith')
    parsed, rest = parser.parse_known_args(args)
    if parsed.jobs > 1 or parsed.shard:
        if rest:
            mx.abort(f'mx r7rs-unittest: --jobs and --shard select the test classes themselves; they take no unittest arguments: {rest}')
        _unittest_in_parallel(parsed.compile, parsed.torture, parsed.jobs, parsed.shard)
        return
    mode = list(COMPILE_VM_ARGS) if parsed.compile else []
    if parsed.torture:
        mode += TORTURE_VM_ARGS
    _unittest_config.mode_vm_args = mode
    try:
        mx_unittest.unittest(rest)
    finally:
        _unittest_config.mode_vm_args = []


_JUNIT_OK = re.compile(r'^OK \((\d+) tests?\)', re.MULTILINE)


def _unittest_in_parallel(compile, torture, jobs, shard):
    """Splits the test classes (those `shard` selects) into `jobs` groups and runs each with a
    nested `mx r7rs-unittest` (a JVM of its own), all at once.

    In CompileImmediately mode one JVM keeps about one core busy, so this is what makes the
    step use the machine. Groups rather than a JVM per class, because every JVM pays for
    startup and compiler warmup again. Results are logged in group order; a failing group's
    full output is logged."""
    # Find the classes (as `mx unittest` does) and build the JUnit harness once here, so the
    # nested runs only read mx's test-class cache and find the harness up to date.
    mx.build(['--no-daemon', '--dependencies', 'JUNIT_TOOL'])
    classes = sorted(mx_unittest.find_test_candidates(['@Theory', '@Test', '@Parameters'], None, mx.get_jdk()))
    classes = _shard(shard, 'r7rs-unittest', classes)
    if not classes:
        mx.abort('mx r7rs-unittest: no test classes found')
    groups = [classes[i::jobs] for i in range(min(jobs, len(classes)))]
    mode = ['--compile'] if compile else []
    if torture:
        mode += ['--torture']

    def run(group):
        out = mx.OutputCapture()
        start = time.time()
        regex = '^(' + '|'.join(re.escape(c) for c in group) + ')$'
        rc = mx.run_mx(['r7rs-unittest'] + mode + ['--regex', regex], suite=_suite, nonZeroIsFatal=False, out=out, err=out)
        return rc, out.data, time.time() - start

    selection = f', shard {shard}' if shard else ''
    mx.log(f'r7rs-unittest: {len(classes)} test classes{selection} in {len(groups)} parallel JVMs ({" ".join(mode) or "default mode"})')
    failed, tests = [], 0
    with concurrent.futures.ThreadPoolExecutor(max_workers=len(groups)) as pool:
        futures = [pool.submit(run, group) for group in groups]
        for i, (group, future) in enumerate(zip(groups, futures), 1):
            rc, output, seconds = future.result()
            ok = _JUNIT_OK.search(output)
            names = ' '.join(c.rsplit('.', 1)[-1] for c in group)
            if rc == 0 and ok:
                tests += int(ok.group(1))
                mx.log(f'  ok      JVM {i}/{len(groups)} ({ok.group(1)} tests, {seconds:.1f}s): {names}')
            else:
                failed.append(str(i))
                mx.log(f'  FAILED  JVM {i}/{len(groups)} (exit {rc}, {seconds:.1f}s): {names}\n' + output.rstrip('\n'))
    if failed:
        mx.abort(f'r7rs-unittest: JVM(s) {" ".join(failed)} of {len(groups)} failed; their output is above')
    mx.log(f'r7rs-unittest: all {len(classes)} test classes passed ({tests} tests)')


# ---------------------------------------------------------------------------
# Conformance, spec, differential, status
# ---------------------------------------------------------------------------

_RESULT_LINE = re.compile(r'^@@r7rs-test (BEGIN|PASS|FAIL|ERROR|END) ?(.*)$')
_SECTION_LINE = re.compile(r'^@@section (\S+) (.*)$')


def _count_results(output):
    """Counts the shim's @@r7rs-test lines (tests/lib/chibi/test.sld)."""
    counts = {'pass': 0, 'fail': 0, 'error': 0, 'complete': False}
    depth = 0
    for line in output.splitlines():
        m = _RESULT_LINE.match(line)
        if not m:
            continue
        kind = m.group(1)
        if kind == 'BEGIN':
            depth += 1
        elif kind == 'END':
            depth -= 1
            counts['complete'] = depth == 0
        else:
            counts[kind.lower()] += 1
    return counts


def _r7rs_dir():
    return join(_suite.dir, 'tests', 'r7rs')


def _sections():
    """[(section id, title, total tests)] in suite order, from tests/r7rs/sections.tsv."""
    rows = []
    with open(join(_r7rs_dir(), 'sections.tsv'), encoding='utf-8') as f:
        for line in f:
            if line.startswith('#') or not line.strip():
                continue
            section_id, title, total = line.rstrip('\n').split('\t')
            rows.append((section_id, title, int(total)))
    return rows


def _run_conformance(section_ids, vm_args):
    """Runs sections under our implementation, all in one JVM (scheme.launcher.ConformanceRunner).

    Returns {section id: {'pass', 'fail', 'error', 'complete', 'message'}}."""
    files = [join(_r7rs_dir(), s + '.scm') for s in section_ids]
    out = mx.OutputCapture()
    jvm_args = mx.get_runtime_jvm_args(['SCHEME_LAUNCHER'])
    rc = mx.run_java(jvm_args + _TRUFFLE_VM_ARGS + vm_args
                     # tests/lib holds the (chibi test) shim the sections import.
                     + ['-m', 'scheme.launcher/scheme.launcher.ConformanceRunner', '-I', join(_suite.dir, 'tests', 'lib')] + files,
                     out=out, err=out, nonZeroIsFatal=False)
    results = {}
    current = None
    for line in out.data.splitlines(keepends=True):
        m = _SECTION_LINE.match(line)
        if m:
            path, message = m.group(1), m.group(2)
            section_id = os.path.basename(path)[:-len('.scm')]
            counts = _count_results(current or '')
            counts['message'] = message
            counts['output'] = current or ''
            results[section_id] = counts
            current = None
        else:
            current = (current or '') + line
    missing = [s for s in section_ids if s not in results]
    if rc != 0 or missing:
        mx.abort(f'ConformanceRunner failed (exit {rc}); no result for {missing}:\n{out.data}')
    return results


def r7rs_test(args):
    """run the chibi conformance suite: [section] [--torture] [--compile]"""
    parser = argparse.ArgumentParser(prog='mx r7rs-test')
    parser.add_argument('section', nargs='?', help='one conformance section, e.g. 6.10')
    parser.add_argument('--torture', action='store_true', help='capture + resume at every non-tail call')
    parser.add_argument('--compile', action='store_true', help='compile immediately; compilation failures are errors')
    parser.add_argument('-v', '--verbose', action='store_true', help='also list failing tests')
    parser.add_argument('--markdown', metavar='FILE', help='append a Markdown results table to FILE (e.g. $GITHUB_STEP_SUMMARY)')
    parsed = parser.parse_args(args)
    sections = _sections()
    if parsed.section:
        sections = [s for s in sections if s[0] == parsed.section]
        if not sections:
            mx.abort(f'unknown section {parsed.section}; sections: ' + ' '.join(s[0] for s in _sections()))
    vm_args = r7rs_vm_args(compile=parsed.compile, torture=parsed.torture)
    results = _run_conformance([s[0] for s in sections], vm_args)
    total_pass = total = 0
    for section_id, title, tests in sections:
        r = results[section_id]
        total_pass += r['pass']
        total += tests
        note = '' if r['complete'] else '  (aborted: ' + r['message'] + ')'
        mx.log(f'{section_id:<5} {r["pass"]:>4}/{tests:<4} {title}{note}')
        if parsed.verbose:
            for line in r['output'].splitlines():
                if line.startswith(('@@r7rs-test FAIL', '@@r7rs-test ERROR')):
                    mx.log('      ' + line[len('@@r7rs-test '):])
    mx.log(f'TOTAL {total_pass:>4}/{total}')
    if parsed.markdown:
        mode = ', '.join(m for m, on in (('--compile', parsed.compile), ('--torture', parsed.torture)) if on) or 'interpreter only'
        rows = [f'### R7RS conformance ({mode}): {total_pass}/{total}', '',
                '| Section | Passing | Tests | Note |', '|---|---:|---:|---|']
        for section_id, title, tests in sections:
            r = results[section_id]
            note = '' if r['complete'] else 'aborted: ' + r['message'].replace('|', '\\|')
            rows.append(f'| {title} | {r["pass"]} | {tests} | {note} |')
        with open(parsed.markdown, 'a', encoding='utf-8') as f:
            f.write('\n'.join(rows) + '\n\n')


_SECTION_BEGIN = re.compile(r'^\(test-begin "([^"]*)"\)\s*$')
_SECTION_END = re.compile(r'^\(test-end\)\s*$')


def _split_chibi_tests(text):
    """Split chibi's r7rs-tests.scm into (import form, [(section id, title, first line, lines)]).

    Sections are the test-begin/test-end groups directly inside the outer "R7RS"
    group. Nested groups ("Read syntax" in 6.13) stay inside their section. Only
    blank lines and comments may sit between sections; anything else would make
    sections depend on each other, so it aborts."""
    lines = text.splitlines(keepends=True)
    start = next(i for i, l in enumerate(lines) if l.startswith('(import'))
    end = next(i for i in range(start, len(lines)) if lines[i].rstrip().endswith(')') and lines[i].strip() == ')')
    import_form = ''.join(lines[start:end + 1])
    sections, depth, current = [], 0, None
    for i, line in enumerate(lines[end + 1:], start=end + 2):
        begin = _SECTION_BEGIN.match(line)
        if begin:
            depth += 1
            if depth == 2:
                title = begin.group(1)
                current = (title.split()[0], title, i, [])
        if current is not None:
            current[3].append(line)
        elif depth == 1 and not begin and not _SECTION_END.match(line) and line.strip() and not line.lstrip().startswith(';'):
            mx.abort(f'r7rs-tests.scm line {i}: code between sections: {line.strip()}')
        if _SECTION_END.match(line):
            depth -= 1
            if depth == 1 and current is not None:
                sections.append(current)
                current = None
    if depth != 0:
        mx.abort('r7rs-tests.scm: unbalanced test-begin/test-end')
    return import_form, sections


def r7rs_vendor_chibi(args):
    """split chibi's tests/r7rs-tests.scm into tests/r7rs/<section>.scm (M1-01)"""
    parser = argparse.ArgumentParser(prog='mx r7rs-vendor-chibi')
    parser.add_argument('source', help="path to chibi-scheme's tests/r7rs-tests.scm")
    parser.add_argument('--version', required=True, help='chibi-scheme version the file comes from')
    parsed = parser.parse_args(args)
    with open(parsed.source, encoding='utf-8') as f:
        text = f.read()
    import_form, sections = _split_chibi_tests(text)
    out_dir = join(_suite.dir, 'tests', 'r7rs')
    os.makedirs(out_dir, exist_ok=True)
    digest = hashlib.sha256(text.encode('utf-8')).hexdigest()
    # chibi-scheme is BSD-3-Clause: redistributed source must keep its notice.
    chibi_license = join(os.path.dirname(os.path.dirname(os.path.abspath(parsed.source))), 'COPYING')
    if not exists(chibi_license):
        mx.abort(f"chibi-scheme's license (COPYING) not found next to tests/: {chibi_license}")
    shutil.copyfile(chibi_license, join(out_dir, 'COPYING'))
    for section_id, title, first_line, body in sections:
        with open(join(out_dir, section_id + '.scm'), 'w', encoding='utf-8') as f:
            f.write(';; -*- coding: utf-8 -*-\n')
            f.write(f';; Vendored from chibi-scheme {parsed.version} tests/r7rs-tests.scm (sha256 {digest}),\n')
            f.write(f';; section "{title}", starting at line {first_line}. Generated by\n')
            f.write(';; `mx r7rs-vendor-chibi`; read-only (AGENTS.md). See tests/r7rs/README.md.\n\n')
            f.write(import_form)
            f.write('\n')
            f.write(''.join(body))
    # Totals come from chibi itself, running each section with our shim
    # (tests/lib/chibi/test.sld). A section where chibi doesn't pass every
    # test, or doesn't reach the end, is a problem with the split or the shim.
    lib_dir = join(_suite.dir, 'tests', 'lib')
    rows = []
    for section_id, title, _, _ in sections:
        out = mx.OutputCapture()
        mx.run(['chibi-scheme', '-I', lib_dir, join(out_dir, section_id + '.scm')], out=out, err=out)
        counts = _count_results(out.data)
        if counts['fail'] or counts['error'] or not counts['complete']:
            mx.abort(f'chibi does not pass section {section_id} cleanly with the shim: {counts}')
        rows.append((section_id, title, counts['pass']))
    with open(join(out_dir, 'sections.tsv'), 'w', encoding='utf-8') as f:
        f.write('# section\ttitle\ttests (from chibi-scheme %s). Generated by `mx r7rs-vendor-chibi`.\n' % parsed.version)
        for row in rows:
            f.write('%s\t%s\t%d\n' % row)
    mx.log(f'wrote {len(sections)} sections ({sum(r[2] for r in rows)} tests) to '
           f'{os.path.relpath(out_dir, _suite.dir)}: ' + ' '.join(r[0] for r in rows))


# tests/spec cases that need something a later task brings: reported as SKIPPED, naming the task.
# Remove an entry when its task lands (tests/spec/README.md).
_SPEC_PENDING = {
    'cont-exit': 'M4 (exit)',
}

_SPEC_HEADER = re.compile(r'^;;\s*(limits|exit):\s*(.*?)\s*$')


def _spec_dir():
    return join(_suite.dir, 'tests', 'spec')


def _spec_case(name):
    """(JVM limits, expected exit status, expected stdout) from tests/spec/<name>.scm's leading comments and <name>.out."""
    limits, status = [], 0
    with open(join(_spec_dir(), name + '.scm'), encoding='utf-8') as f:
        for line in f:
            if not line.startswith(';;'):
                break
            m = _SPEC_HEADER.match(line)
            if m and m.group(1) == 'limits':
                limits = m.group(2).split()
            elif m:
                status = int(m.group(2))
    out = join(_spec_dir(), name + '.out')
    if not exists(out):
        mx.abort(f'tests/spec/{name}.scm has no expected stdout tests/spec/{name}.out')
    with open(out, encoding='utf-8', newline='') as f:
        return limits, status, f.read()


def r7rs_spec(args):
    """run tests/spec (tail-call space, continuations, depth), interpreted and with the JIT: [-j N] [case...]"""
    parser = argparse.ArgumentParser(prog='mx r7rs-spec')
    parser.add_argument('cases', nargs='*', help='case names, e.g. tail-mutual (default: all)')
    parser.add_argument('-j', '--jobs', type=int, default=_default_jobs(), metavar='N',
                        help='JVMs to run at a time (default: the number of CPUs)')
    parser.add_argument('--shard', metavar='I/N', help='run only every Nth case, starting with the Ith')
    parsed = parser.parse_args(args)
    names = sorted(f[:-len('.scm')] for f in os.listdir(_spec_dir()) if f.endswith('.scm'))
    unknown = [c for c in parsed.cases if c not in names]
    if unknown:
        mx.abort(f'mx r7rs-spec: unknown case(s) {unknown}; cases: ' + ' '.join(names))
    stale = [c for c in _SPEC_PENDING if c not in names]
    if stale:
        mx.abort(f'mx r7rs-spec: _SPEC_PENDING names cases that do not exist: {stale}')

    def run(name, vm_args, limits):
        start = time.time()
        rc, out, err = _run_ours(join(_spec_dir(), name + '.scm'), vm_args, limits)
        return rc, out, err, time.time() - start

    # Every case runs twice (interpreter, JIT), each in a JVM of its own under the case's limits.
    # The runs are independent, so they go in parallel; results are logged in case order.
    selected = parsed.cases or names
    selected = _shard(parsed.shard, 'r7rs-spec', selected)
    cases = {name: _spec_case(name) for name in selected if name not in _SPEC_PENDING}
    modes = (('interpreter', r7rs_vm_args()), ('JIT', []))
    _launcher_jvm_args()
    failed, passed, skipped = [], 0, 0
    with concurrent.futures.ThreadPoolExecutor(max_workers=max(1, parsed.jobs)) as pool:
        # All interpreter runs first: they are the slow ones, and starting them early keeps
        # the pool from ending on a long straggler.
        futures = {(name, label): pool.submit(run, name, vm_args, cases[name][0])
                   for label, vm_args in modes for name in cases}
        for name in selected:
            if name in _SPEC_PENDING:
                mx.log(f'SKIPPED {name} (not implemented until {_SPEC_PENDING[name]})')
                skipped += 1
                continue
            limits, status, expected = cases[name]
            problems = []
            for label, _ in modes:
                future = futures[(name, label)]
                rc, out, err, seconds = future.result()
                if rc != status or out != expected:
                    detail = f'exit {rc} (expected {status})' if rc != status else 'stdout differs'
                    if out != expected:
                        diff = list(difflib.unified_diff(expected.splitlines(keepends=True), out.splitlines(keepends=True),
                                                         fromfile='expected', tofile='actual', n=1))
                        detail += '\n' + ''.join(l if len(l) < 200 else l[:200] + '...\n' for l in diff[:20]).rstrip('\n')
                    problems.append(f'{label} ({seconds:.1f}s): {detail}\n  stderr:\n{_tail(err)}')
                else:
                    mx.log(f'  {name}: {label} ok ({seconds:.1f}s)')
            if problems:
                failed.append(name)
                mx.log(f'FAIL    {name} {" ".join(limits)}\n  ' + '\n  '.join(problems))
            else:
                passed += 1
                mx.log(f'PASS    {name} {" ".join(limits)}')
    mx.log(f'r7rs-spec: {passed} passed, {len(failed)} failed, {skipped} skipped')
    if failed:
        mx.abort('r7rs-spec: failed: ' + ' '.join(failed))


@functools.cache
def _launcher_jvm_args():
    """The launcher's module path and friends. Resolved once, before any parallel runs start."""
    return tuple(mx.get_runtime_jvm_args(['SCHEME_LAUNCHER']))


def _run_ours(file, vm_args, jvm_args=()):
    """Runs one program with scheme.launcher.SchemeMain. Returns (exit status, stdout, stderr)."""
    out, err = mx.OutputCapture(), mx.OutputCapture()
    rc = mx.run_java(list(jvm_args) + list(_launcher_jvm_args()) + _TRUFFLE_VM_ARGS + vm_args
                     + ['-m', 'scheme.launcher/scheme.launcher.SchemeMain', file],
                     out=out, err=err, nonZeroIsFatal=False)
    return rc, out.data, err.data


def _run_chibi(file):
    out, err = mx.OutputCapture(), mx.OutputCapture()
    rc = mx.run(['chibi-scheme', file], out=out, err=err, nonZeroIsFatal=False)
    return rc, out.data, err.data


def _tail(text, lines=5):
    return '\n'.join('    ' + l for l in text.rstrip('\n').splitlines()[-lines:]) or '    (empty)'


# (program, expected stdout, JVM limits):
# - M1-06: a 10M-iteration tail-recursive loop, under a stack and heap far too small for anything
#   that grows per iteration.
# - M2-05: 1M-deep non-tail recursion under a 256k Java stack: stack-depth spill keeps the stack
#   constant; the pending frames live on the heap (about 350 bytes each).
# tests/spec/tail-self-loop and deep-recursion (M2-00) check the same things; these stay as the
# Done-when cases of M1-06 and M2-05.
_SPACE_CASES = [
    ('tests/diff/loop-10m.scm', '10000000\n', ['-Xss256k', '-Xmx64m']),
    # Not in tests/diff/: chibi-scheme runs out of stack space on it.
    ('tests/space/deep-recursion-1m.scm', '1000000\n', ['-Xss256k', '-Xmx512m']),
]


def r7rs_space(args):
    """run the M1-06 tail loop and the M2-05 deep recursion under small JVM limits, interpreted and with the JIT"""
    for path, expected, limits in _SPACE_CASES:
        for label, vm_args in (('interpreter', r7rs_vm_args()), ('JIT', [])):
            rc, out, err = _run_ours(join(_suite.dir, path), vm_args, limits)
            if rc != 0 or out != expected:
                mx.abort(f'{path} ({label}, {" ".join(limits)}): exit {rc}, stdout {out!r}\n{_tail(err)}')
            mx.log(f'r7rs-space: {path} ok ({label}, {" ".join(limits)})')


def r7rs_torture(args):
    """torture mode (M2-06): unit tests, tests/diff/, and every conformance case that passes normally"""
    r7rs_unittest(['--torture'])
    r7rs_diff_all(['--torture'])
    sections = [s[0] for s in _sections()]
    normal = _run_conformance(sections, r7rs_vm_args())
    tortured = _run_conformance(sections, r7rs_vm_args(torture=True))
    worse = [f'{s}: {normal[s]["pass"]} normally, {tortured[s]["pass"]} under torture' for s in sections if tortured[s]['pass'] < normal[s]['pass']]
    if worse:
        mx.abort('r7rs-torture: sections that pass less under torture:\n  ' + '\n  '.join(worse))
    total = sum(normal[s]['pass'] for s in sections)
    mx.log(f'r7rs-torture: unit tests, tests/diff/ and conformance ({total} passing normally) all pass under torture')


def r7rs_diff_all(args):
    """run every program in tests/diff/ through r7rs-diff; fail if any differs"""
    diff_dir = join(_suite.dir, 'tests', 'diff')
    files = sorted(f for f in os.listdir(diff_dir) if f.endswith('.scm'))
    different = [f for f in files if r7rs_diff(args + [join(diff_dir, f)]) != 0]
    if different:
        mx.abort(f'r7rs-diff: {len(different)} of {len(files)} programs in tests/diff/ differ from chibi-scheme: {different}')
    mx.log(f'r7rs-diff: all {len(files)} programs in tests/diff/ match chibi-scheme')


def r7rs_diff(args):
    """run a file under chibi-scheme and under our implementation; compare stdout and exit status"""
    parser = argparse.ArgumentParser(prog='mx r7rs-diff')
    parser.add_argument('file', help='Scheme program to run')
    parser.add_argument('--torture', action='store_true', help='capture + resume at every non-tail call (ours)')
    parser.add_argument('--compile', action='store_true', help='compile immediately; compilation failures are errors (ours)')
    parsed = parser.parse_args(args)
    if not exists(parsed.file):
        mx.abort(f'no such file: {parsed.file}')
    chibi_rc, chibi_out, chibi_err = _run_chibi(parsed.file)
    ours_rc, ours_out, ours_err = _run_ours(parsed.file, r7rs_vm_args(compile=parsed.compile, torture=parsed.torture))
    if chibi_rc == ours_rc and chibi_out == ours_out:
        mx.log(f'r7rs-diff: same (exit {ours_rc}, {len(ours_out)} chars of stdout): {parsed.file}')
        return 0
    mx.log(f'r7rs-diff: DIFFERENT: {parsed.file}')
    if chibi_out != ours_out:
        diff = difflib.unified_diff(chibi_out.splitlines(keepends=True), ours_out.splitlines(keepends=True),
                                    fromfile='chibi-scheme stdout', tofile='scheme stdout')
        mx.log(''.join(diff).rstrip('\n'))
        if any(o and not o.endswith('\n') for o in (chibi_out, ours_out)):
            mx.log('(the diff ignores a missing final newline; compare exact stdout below)')
            mx.log(f'  chibi-scheme: {chibi_out!r}')
            mx.log(f'  scheme:       {ours_out!r}')
    mx.log(f'exit status: chibi-scheme {chibi_rc}, scheme {ours_rc}')
    mx.log('chibi-scheme stderr (tail):\n' + _tail(chibi_err))
    mx.log('scheme stderr (tail):\n' + _tail(ours_err))
    return 1


_STATUS_BEGIN = '<!-- status:begin -->'
_STATUS_END = '<!-- status:end -->'


def _status_block(results):
    sections = _sections()
    lines = [_STATUS_BEGIN,
             'Conformance against the vendored chibi suite (`tests/r7rs/`), interpreter',
             'only. Generated by `mx r7rs-status`; `mx tests` fails if it is stale. Don\'t',
             'edit it by hand.',
             '',
             '| Section | Passing | Tests |',
             '|---|---:|---:|']
    total_pass = total = 0
    for section_id, title, tests in sections:
        passing = results[section_id]['pass']
        total_pass += passing
        total += tests
        lines.append(f'| {title} | {passing} | {tests} |')
    lines.append(f'| **Total** | **{total_pass}** | **{total}** |')
    lines.append(_STATUS_END)
    return '\n'.join(lines)


def r7rs_status(args):
    """regenerate the status block in docs/plan.md: [--check]"""
    parser = argparse.ArgumentParser(prog='mx r7rs-status')
    parser.add_argument('--check', action='store_true', help='fail if the block is stale instead of rewriting it')
    parsed = parser.parse_args(args)
    results = _run_conformance([s[0] for s in _sections()], r7rs_vm_args())
    block = _status_block(results)
    plan = join(_suite.dir, 'docs', 'plan.md')
    with open(plan, encoding='utf-8') as f:
        text = f.read()
    begin, end = text.index(_STATUS_BEGIN), text.index(_STATUS_END) + len(_STATUS_END)
    current = text[begin:end]
    if parsed.check:
        if current != block:
            mx.abort('The status block in docs/plan.md is stale. Run `mx r7rs-status` and commit the result.\n'
                     'Expected:\n' + block)
        mx.log('r7rs-status: status block is up to date')
        return
    if current != block:
        with open(plan, 'w', encoding='utf-8') as f:
            f.write(text[:begin] + block + text[end:])
        mx.log('r7rs-status: rewrote the status block in docs/plan.md')
    else:
        mx.log('r7rs-status: status block already up to date')


# ---------------------------------------------------------------------------
# Rule checks (AGENTS.md "Rules")
# ---------------------------------------------------------------------------

# Rule 1: Java builtins never call Scheme. A builtin that needs to invoke a
# procedure returns a TailCall request instead (design.md D5).
_RULE1_FORBIDDEN = [
    (re.compile(r'\b(Direct|Indirect)CallNode\b'), 'call nodes'),
    (re.compile(r'\b\w*CallTarget\b'), 'call targets'),
    (re.compile(r'\bInteropLibrary\b'), 'interop (can execute Scheme procedures)'),
]


def _java_files(root):
    for dirpath, _, filenames in os.walk(root):
        for name in sorted(filenames):
            if name.endswith('.java'):
                yield join(dirpath, name)


def r7rs_check(args):
    """check rule 1: Java builtins never call Scheme (design.md D5)"""
    builtins = join(_suite.dir, 'src', 'builtins')
    violations = []
    for path in _java_files(builtins):
        with open(path) as f:
            for lineno, line in enumerate(f, 1):
                code = line.split('//', 1)[0]
                for pattern, what in _RULE1_FORBIDDEN:
                    if pattern.search(code):
                        violations.append(f'{os.path.relpath(path, _suite.dir)}:{lineno}: uses {what}: {line.strip()}')
    if violations:
        mx.abort('Rule 1 violated: Java builtins never call Scheme; return a TailCall request instead '
                 '(AGENTS.md rule 1, design.md D5).\n' + '\n'.join(violations))
    mx.log('r7rs-check: rule 1 OK')


# ---------------------------------------------------------------------------
# mx checks / mx tests: what CI runs (two required checks), and the pre-PR check.
# They replace `mx gate`, which this suite no longer uses.
# ---------------------------------------------------------------------------

def _command(name, args, failure):
    def run():
        # Some mx commands (build) return None on success and abort on failure.
        if mx.command_function(name)(args) not in (0, None):
            mx.abort(failure)
    return run


def _build_with_ecj():
    """Like the built-in gate's BuildWithEcj: build with ECJ, warnings are errors, then clean."""
    mx.command_function('clean')(['--keep-logs'])
    _command('build', ['-p', '--warning-as-error', '--jdt=builtin'], 'ECJ build failed')()
    mx.command_function('clean')(['--keep-logs'])


# name -> (description, function). Order is the run order.
_CHECKS = {
    'ecj': ('ECJ build, warnings are errors', _build_with_ecj),
    'build': ('javac build, warnings are errors', _command('build', ['-p', '--warning-as-error', '--force-javac'], 'javac build failed')),
    'canonical': ('suite.py is canonical (project dependencies, packages)',
                  _command('canonicalizeprojects', [], 'Rerun `mx canonicalizeprojects` and fix suite.py as suggested')),
    'overlap': ('distributions do not overlap', _command('checkoverlap', [], 'Found overlapping distributions')),
    'sources': ('every Java source is in a project', _command('verifysourceinproject', [], 'Java sources outside a project directory')),
    'checkstyle': ('checkstyle', _command('checkstyle', ['--primary'], 'Checkstyle warnings were found')),
    'format': ('Eclipse formatter (fails if it changes files)',
               _command('eclipseformat', ['--primary'], 'The formatter changed files: review the changes made by `mx eclipseformat` and commit them')),
    'spotbugs': ('SpotBugs', _command('spotbugs', ['--primary'], 'SpotBugs warnings were found')),
    'rules': ('AGENTS.md rule 1: builtins never call Scheme', lambda: r7rs_check([])),
}

# Tests whose machinery doesn't exist yet: reported as SKIPPED, not failed. Remove
# an entry when its task lands.
_PENDING_TESTS = {
}

_TESTS = {
    'unittest': ('JUnit, Graal JIT in background mode', lambda: r7rs_unittest([])),
    'unittest-compile': ('JUnit, compile immediately, test classes split over parallel JVMs',
                         lambda: r7rs_unittest(['--compile', '--jobs', str(_default_jobs())])),
    'conformance': ('status block in docs/plan.md is up to date', lambda: r7rs_status(['--check'])),
    'diff': ('tests/diff/ matches chibi-scheme', lambda: r7rs_diff_all([])),
    'space': ('tail loop and deep recursion under small JVM limits (M1-06, M2-05)', lambda: r7rs_space([])),
    'spec': ('tests/spec: tail calls, continuations, depth (M2-00, M2-07)', lambda: r7rs_spec([])),
    'torture': ('everything that passes normally passes under --torture (M2-06)', lambda: r7rs_torture([])),
}


def _run_steps(command, steps, names, before=None):
    unknown = [n for n in names if n not in steps]
    if unknown:
        mx.abort(f'mx {command}: unknown step(s) {unknown}; steps: ' + ' '.join(steps))
    selected = names or list(steps)
    if before:
        before()
    results = []
    for name in selected:
        description, fn = steps[name]
        mx.log(f'==> mx {command} {name}: {description}')
        start = time.time()
        if fn is None:
            status = 'SKIPPED (not implemented until ' + _PENDING_TESTS[name] + ')'
        else:
            try:
                fn()
                status = 'ok'
            except SystemExit as e:
                status = 'FAILED' if e.code else 'ok'
        results.append((name, status, time.time() - start))
    mx.log(f'mx {command} summary:')
    for name, status, seconds in results:
        mx.log(f'  {name:<17} {status:<8} {seconds:6.1f}s')
    failed = [name for name, status, _ in results if status == 'FAILED']
    if failed:
        mx.abort(f'mx {command}: failed: ' + ' '.join(failed))


def checks(args):
    """build and static checks (CI: the required "Checks" job): [step...]"""
    _run_steps('checks', _CHECKS, args)


def tests(args):
    """all tests (CI: the required "Tests" job): [step...]"""
    _run_steps('tests', _TESTS, args, before=_command('build', [], 'build failed'))


mx.update_commands(_suite, {
    'checks': [checks, '[' + ' | '.join(_CHECKS) + ']...'],
    'tests': [tests, '[' + ' | '.join(_TESTS) + ']...'],
    'r7rs-test': [r7rs_test, '[section] [--torture] [--compile]'],
    'r7rs-spec': [r7rs_spec, '[-j N] [--shard I/N] [case...]'],
    'r7rs-diff': [r7rs_diff, 'file.scm [--compile] [--torture]'],
    'r7rs-diff-all': [r7rs_diff_all, '[--compile] [--torture]'],
    'r7rs-space': [r7rs_space, ''],
    'r7rs-torture': [r7rs_torture, ''],
    'r7rs-status': [r7rs_status, ''],
    'r7rs-check': [r7rs_check, ''],
    'r7rs-vendor-chibi': [r7rs_vendor_chibi, 'path/to/r7rs-tests.scm --version <chibi version>'],
    'r7rs-unittest': [r7rs_unittest, '[--compile] [--torture] [-j N] [--shard I/N] [unittest args...]'],
})

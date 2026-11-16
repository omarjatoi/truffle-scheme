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
import tempfile
import time
from os.path import exists, join

import mx
import mx_benchmark
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

_RESULT_LINE = re.compile(r'^@@r7rs-test (BEGIN|PASS|FAIL|ERROR|SKIP|END) ?(.*)$')
_SECTION_LINE = re.compile(r'^@@section (\S+) (.*)$')


def _count_results(output):
    """Counts the shims' @@r7rs-test lines (tests/lib/chibi/test.sld, tests/lib/tests/scheme/test.sld)."""
    counts = {'pass': 0, 'fail': 0, 'error': 0, 'skip': 0, 'complete': False}
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


class _Section:
    """One conformance section: a program the runner runs in a fresh context, and its total."""

    def __init__(self, suite, section_id, title, file, total, args=(), env=(), exit_status=None, jit=False):
        self.suite = suite
        self.id = section_id
        self.title = title
        self.file = file
        self.total = total
        self.args = list(args)
        self.env = list(env)  # ['NAME=value', ...]
        # None: the program must finish normally and report its end. Otherwise it must exit with this
        # status, which counts as one more passing test (as Larceny's testall scripts count it).
        self.exit_status = exit_status
        # Always compiled (compile immediately): the section's results depend on speed.
        self.jit = jit


class _ConformanceSuite:
    def __init__(self, name, directory, heading, column):
        self.name = name  # for --suite
        self.dir = join(_suite.dir, 'tests', directory)
        self.heading = heading  # Markdown, for the status block and run summaries
        self.column = column  # what one row is

    def sections(self):
        raise NotImplementedError

    def exceptions(self):
        """{section id: [test, ...]}: tests that fail by design (none by default)."""
        return {}

    def runner_args(self, workdir):
        """ConformanceRunner arguments before the files: library path and working directory."""
        raise NotImplementedError

    def _rows(self):
        with open(join(self.dir, 'sections.tsv'), encoding='utf-8') as f:
            return [line.rstrip('\n').split('\t') for line in f if line.strip() and not line.startswith('#')]


class _ChibiSuite(_ConformanceSuite):
    """chibi-scheme's r7rs-tests.scm, split by section (tests/r7rs/, M1-01)."""

    def __init__(self):
        super().__init__('chibi', 'r7rs', "chibi-scheme's R7RS suite (`tests/r7rs/`)", 'Section')

    def sections(self):
        return [_Section(self, section_id, title, join(self.dir, section_id + '.scm'), int(total))
                for section_id, title, total in self._rows()]

    def runner_args(self, workdir):
        # tests/lib holds the (chibi test) shim the sections import.
        return ['-I', join(_suite.dir, 'tests', 'lib')]


class _LarcenySuite(_ConformanceSuite):
    """Larceny's R7RS test libraries, one program per (scheme ...) library (tests/r7rs-larceny/, M4-11)."""

    def __init__(self):
        super().__init__('larceny', 'r7rs-larceny', "Larceny's R7RS test suite (`tests/r7rs-larceny/`)", 'Library')

    def program(self, name):
        return join(self.dir, 'tests', 'scheme', 'run', name + '.sps')

    def sections(self):
        def words(field):
            return [] if field == '-' else field.split(' ')
        return [_Section(self, section_id, title, self.program(program), int(total), words(args), words(env),
                         None if exit_status == '-' else int(exit_status), jit=section_id in _LARCENY_JIT)
                for section_id, title, program, args, env, exit_status, total, _ in self._rows()]

    def exceptions(self):
        """The README's Exceptions table (plan.md M4-12): tests that expect a result where R7RS
        says "it is an error", which we keep signalling (design.md D8). Rows are
        | `section` | `test as the shim reports it` | Larceny's result | R7RS text |."""
        listed = {}
        with open(join(self.dir, 'README.md'), encoding='utf-8') as f:
            in_table = False
            for line in f:
                if line.startswith('## '):
                    in_table = line.strip() == '## Exceptions'
                elif in_table and line.startswith('| `'):
                    cells = [c.strip() for c in line.strip().strip('|').split(' | ')]
                    listed.setdefault(cells[0].strip('`'), []).append(cells[1].strip('`'))
        return listed

    def runner_args(self, workdir):
        # tests/lib holds the (tests scheme test) shim; the vendored libraries come after it.
        return ['-I', join(_suite.dir, 'tests', 'lib'), '-I', self.dir, '-C', workdir]

    @staticmethod
    def make_workdir(workdir, suite_dir):
        """The suite loads tests/scheme/*.scm relative to the working directory and writes scratch
        files there (io-tmp2, tmp-catch-out): a fresh directory whose tests/ is the vendored one."""
        os.symlink(join(suite_dir, 'tests'), join(workdir, 'tests'))


# Sections whose results depend on speed, so they always run compiled (compile immediately), where
# they are deterministic: (scheme time)'s second test checks that jiffies are accurate to a tenth
# of a second by polling the clock between runs of a million-iteration loop, so it assumes such a
# loop takes well under 0.1 s. Interpreted, ours takes about 0.2-0.5 s, and the test passes or
# fails by chance.
_LARCENY_JIT = {'time'}

_CHIBI = _ChibiSuite()
_LARCENY = _LarcenySuite()
_SUITES = [_CHIBI, _LARCENY]


def _count_section(section, output, message):
    counts = _count_results(output)
    counts['message'] = message
    counts['output'] = output
    if section.exit_status is not None:
        counts['complete'] = message == f'exit {section.exit_status}'
        if counts['complete']:
            counts['pass'] += 1
    return counts


def _failing_tests(output):
    """The tests that failed or raised, as the shims report them (the part before ' :: ')."""
    tests = []
    for line in output.splitlines():
        m = _RESULT_LINE.match(line)
        if m and m.group(1) in ('FAIL', 'ERROR'):
            tests.append(m.group(2).split(' :: ', 1)[0])
    return tests


def _listed_exceptions(suite, section, output):
    """Which of the section's failing tests its suite lists as exceptions: [bool] per failing test,
    in order (each listing matches once)."""
    listed = list(suite.exceptions().get(section.id, []))
    marks = []
    for test in _failing_tests(output):
        marks.append(test in listed)
        if marks[-1]:
            listed.remove(test)
    return marks


def _run_conformance(sections, compile=False, torture=False):
    """Runs sections under our implementation, one JVM per suite and mode (scheme.launcher.ConformanceRunner).

    Returns {(suite name, section id): {'pass', 'fail', 'error', 'complete', 'message', 'output'}}."""
    results = {}
    for suite, jit in [(suite, jit) for suite in _SUITES for jit in (False, True)]:
        mine = [s for s in sections if s.suite is suite and s.jit == jit]
        if not mine:
            continue
        vm_args = r7rs_vm_args(compile=compile or jit, torture=torture)
        workdir = tempfile.mkdtemp(prefix=f'r7rs-{suite.name}-')
        try:
            if suite is _LARCENY:
                _LarcenySuite.make_workdir(workdir, suite.dir)
            files = []
            for s in mine:
                files += [a for arg in s.args for a in ('--arg', arg)]
                files += [a for binding in s.env for a in ('--env', binding)]
                files.append(s.file)
            out = mx.OutputCapture()
            jvm_args = mx.get_runtime_jvm_args(['SCHEME_LAUNCHER'])
            rc = mx.run_java(jvm_args + _TRUFFLE_VM_ARGS + vm_args
                             + ['-m', 'scheme.launcher/scheme.launcher.ConformanceRunner'] + suite.runner_args(workdir) + files,
                             out=out, err=out, nonZeroIsFatal=False)
        finally:
            shutil.rmtree(workdir, ignore_errors=True)
        # One @@section line per file, in order.
        done = []
        current = ''
        for line in out.data.splitlines(keepends=True):
            m = _SECTION_LINE.match(line)
            if m and len(done) < len(mine):
                section = mine[len(done)]
                results[(suite.name, section.id)] = _count_section(section, current, m.group(2))
                done.append(section)
                current = ''
            else:
                current += line
        if rc != 0 or len(done) != len(mine):
            missing = [s.id for s in mine[len(done):]]
            mx.abort(f'ConformanceRunner failed (exit {rc}); no result for {missing}:\n{out.data}')
    return results


def _select_suites(name):
    return _SUITES if name == 'all' else [s for s in _SUITES if s.name == name]


def r7rs_test(args):
    """run the conformance suites (chibi's and Larceny's): [section] [--suite S] [--torture] [--compile]"""
    parser = argparse.ArgumentParser(prog='mx r7rs-test')
    parser.add_argument('section', nargs='?', help="one conformance section, e.g. 6.10 (chibi's) or base (Larceny's)")
    parser.add_argument('--suite', choices=['all'] + [s.name for s in _SUITES], default='all', help='which suite (default: all)')
    parser.add_argument('--torture', action='store_true', help='capture + resume at every non-tail call')
    parser.add_argument('--compile', action='store_true', help='compile immediately; compilation failures are errors')
    parser.add_argument('-v', '--verbose', action='store_true', help='also list failing tests')
    parser.add_argument('--markdown', metavar='FILE', help='append a Markdown results table to FILE (e.g. $GITHUB_STEP_SUMMARY)')
    parsed = parser.parse_args(args)
    suites = _select_suites(parsed.suite)
    sections = [s for suite in suites for s in suite.sections()]
    if parsed.section:
        sections = [s for s in sections if s.id == parsed.section]
        if not sections:
            mx.abort(f'unknown section {parsed.section}; sections: ' + ' '.join(s.id for suite in suites for s in suite.sections()))
    results = _run_conformance(sections, compile=parsed.compile, torture=parsed.torture)
    mode = ', '.join(m for m, on in (('--compile', parsed.compile), ('--torture', parsed.torture)) if on) or 'interpreter only'
    markdown = []
    for suite in suites:
        mine = [s for s in sections if s.suite is suite]
        if not mine:
            continue
        mx.log(f'{suite.name}:')
        total_pass = total = 0
        width = max(len(s.id) for s in mine)
        for s in mine:
            r = results[(suite.name, s.id)]
            total_pass += r['pass']
            total += s.total
            note = '' if r['complete'] else '  (aborted: ' + r['message'] + ')'
            note += '  (compiled: depends on speed)' if s.jit and not parsed.compile else ''
            mx.log(f'{s.id:<{width}} {r["pass"]:>4}/{s.total:<4} {s.title}{note}')
            if parsed.verbose:
                marks = iter(_listed_exceptions(suite, s, r['output']))
                for line in r['output'].splitlines():
                    if line.startswith(('@@r7rs-test FAIL', '@@r7rs-test ERROR')):
                        mark = '  (listed exception)' if next(marks) else ''
                        mx.log('      ' + line[len('@@r7rs-test '):] + mark)
        mx.log(f'{"TOTAL":<{width}} {total_pass:>4}/{total}')
        markdown += [f'### {suite.heading} ({mode}): {total_pass}/{total}', '',
                     f'| {suite.column} | Passing | Tests | Note |', '|---|---:|---:|---|']
        for s in mine:
            r = results[(suite.name, s.id)]
            note = '' if r['complete'] else 'aborted: ' + r['message'].replace('|', '\\|')
            exceptions = sum(_listed_exceptions(suite, s, r['output']))
            if exceptions:
                note = (note + '; ' if note else '') + f'{exceptions} listed exceptions'
            if s.jit and not parsed.compile:
                note = (note + '; ' if note else '') + 'compiled: depends on speed'
            markdown.append(f'| {s.title} | {r["pass"]} | {s.total} | {note} |')
        markdown.append('')
    if parsed.markdown:
        with open(parsed.markdown, 'a', encoding='utf-8') as f:
            f.write('\n'.join(markdown) + '\n')


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


# Larceny's R7RS test suite (M4-11): the tests of the R7RS-small libraries, in the order of
# Larceny's test/R7RS/Lib/testall-chibi, then that script's runs of process-context with options.
# The Red Edition libraries (SRFIs: box, charset, comparator, ...) are not R7RS-small and stay out.
_LARCENY_LIBRARIES = ['base', 'case-lambda', 'char', 'complex', 'cxr', 'eval', 'file', 'inexact', 'lazy', 'load',
                      'read', 'repl', 'time', 'write', 'process-context']
# (section id, arguments, environment, exit status): testall-chibi's runtest_with_options.
_LARCENY_VARIANTS = [
    ('process-context-getenv', '--test-getenv TEMPORARY_ENV_VAR 2776e8e', 'TEMPORARY_ENV_VAR=2776e8e', '-'),
    ('process-context-emergency-exit', '--test-emergency-exit', '-', '0'),
    ('process-context-emergency-exit-37', '--test-emergency-exit 37', '-', '37'),
    ('process-context-exit', '--test-exit', '-', '0'),
    ('process-context-exit-46', '--test-exit 46', '-', '46'),
]


def _larceny_file_library(name):
    """The library a file in Larceny's tests/scheme belongs to: base-test1.scm, base.body-closed.scm -> base."""
    return re.sub(r'-test\d+$', '', name.split('.')[0])


def _features(command):
    """The (features) list a Scheme command prints, given a program that writes it."""
    with tempfile.TemporaryDirectory(prefix='r7rs-features-') as d:
        program = join(d, 'features.scm')
        with open(program, 'w', encoding='utf-8') as f:
            f.write('(import (scheme base) (scheme write))\n(write (features))\n(newline)\n')
        rc, out = command(program)
    if rc != 0 or not out.strip().startswith('('):
        mx.abort(f'cannot get the feature list: exit {rc}: {out}')
    return out.strip()[1:-1].split()


def _run_chibi_section(suite_dir, workdir, section, features, skips):
    """Runs one section of the vendored Larceny suite under chibi-scheme with these extra features,
    tracing the tests' start and skipping the tests numbered in skips (tests/lib/tests/scheme/test.sld).

    Returns (counts, exit status, number of the last test that started)."""
    env = dict(os.environ)
    env.update(b.split('=', 1) for b in section.env)
    env['R7RS_TEST_TRACE'] = '1'
    env['R7RS_TEST_SKIP'] = ' '.join(str(n) for n in skips)
    out = mx.OutputCapture()
    rc = mx.run(['chibi-scheme'] + [a for f in features for a in ('-D', f)]
                + ['-I', join(_suite.dir, 'tests', 'lib'), '-I', suite_dir, section.file] + section.args,
                out=out, err=out, cwd=workdir, env=env, nonZeroIsFatal=False, timeout=900)
    started = [int(m.group(1)) for m in re.finditer(r'^@@r7rs-test START (\d+)$', out.data, re.MULTILINE)]
    counts = _count_section(section, out.data, f'exit {rc}' if section.exit_status is not None else 'ok')
    return counts, rc, started[-1] if started else None


def r7rs_vendor_larceny(args):
    """copy Larceny's R7RS-small tests into tests/r7rs-larceny/ and count them (M4-11)"""
    parser = argparse.ArgumentParser(prog='mx r7rs-vendor-larceny')
    parser.add_argument('source', help='a checkout of https://github.com/larcenists/larceny')
    parser.add_argument('--commit', required=True, help='the commit the checkout is at')
    parsed = parser.parse_args(args)
    lib = join(parsed.source, 'test', 'R7RS', 'Lib')
    scheme_dir = join(lib, 'tests', 'scheme')
    out_dir = _LARCENY.dir
    shutil.rmtree(join(out_dir, 'tests'), ignore_errors=True)
    os.makedirs(join(out_dir, 'tests', 'scheme', 'run'))
    # The suite's license statement (LGPL) is in these READMEs; the license texts are committed
    # next to them (COPYING.LESSER, COPYING).
    for name in ('README', 'README2'):
        shutil.copyfile(join(lib, name), join(out_dir, name + '.upstream'))
    for name in ('COPYING', 'COPYING.LESSER'):
        if not exists(join(out_dir, name)):
            mx.abort(f'tests/r7rs-larceny/{name} (the GNU license text) is missing')
    # Each library's .sld, the files it includes or loads, and its driver program. (tests scheme
    # test) is not copied: tests/lib/tests/scheme/test.sld replaces it.
    copied = []
    for name in sorted(os.listdir(scheme_dir)):
        if name.endswith(('.sld', '.scm')) and _larceny_file_library(name) in _LARCENY_LIBRARIES:
            copied.append(join('tests', 'scheme', name))
    copied += [join('tests', 'scheme', 'run', name + '.sps') for name in _LARCENY_LIBRARIES]
    for rel in copied:
        with open(join(lib, rel), encoding='utf-8') as f:
            text = f.read()
        digest = hashlib.sha256(text.encode('utf-8')).hexdigest()
        upstream = 'test/R7RS/Lib/' + rel.replace(os.sep, '/')
        with open(join(out_dir, rel), 'w', encoding='utf-8') as f:
            f.write(f';; Vendored from Larceny {parsed.commit} {upstream} (sha256 {digest}).\n')
            f.write(';; Generated by `mx r7rs-vendor-larceny`; read-only (AGENTS.md). LGPL: see tests/r7rs-larceny/README.md.\n')
            f.write(text)
    # Totals: the number of tests chibi-scheme runs, with our features added (-D), since some tests
    # depend on cond-expand features (exact-complex, ...): the totals are those of our
    # configuration. Where chibi crashes or hangs in a test (it loops in one of base's dynamic-wind
    # tests), the test is skipped (counted, not run) and the section run again.
    ours = _features(lambda program: _run_ours(program, r7rs_vm_args())[:2])
    chibis = _features(lambda program: _run_chibi(program)[:2])
    features = [f for f in ours if f not in chibis]
    rows = [(n, f'(scheme {n})', n, '-', '-', '-') for n in _LARCENY_LIBRARIES]
    rows += [(i, '(scheme process-context) ' + a, 'process-context', a, e, x) for i, a, e, x in _LARCENY_VARIANTS]
    workdir = tempfile.mkdtemp(prefix='r7rs-vendor-larceny-')
    try:
        _LarcenySuite.make_workdir(workdir, out_dir)
        # chibi resolves the include inside an included file (base-test3.scm's "base-test4.scm")
        # against the working directory, not the including file's directory.
        os.symlink(join(out_dir, 'tests', 'scheme', 'base-test4.scm'), join(workdir, 'base-test4.scm'))
        table = []
        for section_id, title, program, arguments, environment, exit_status in rows:
            section = _Section(_LARCENY, section_id, title, _LARCENY.program(program), 0,
                               [] if arguments == '-' else arguments.split(' '),
                               [] if environment == '-' else environment.split(' '),
                               None if exit_status == '-' else int(exit_status))
            skips = []
            while True:
                counts, rc, last = _run_chibi_section(out_dir, workdir, section, features, skips)
                if counts['complete'] or last is None or last in skips or len(skips) == 3:
                    break
                mx.log(f'{section_id}: chibi did not finish test {last} (exit {rc}); skipping it')
                skips.append(last)
            if not counts['complete']:
                mx.abort(f'{section_id}: chibi did not finish (exit {rc}, expected {exit_status}): '
                         f'{ {k: v for k, v in counts.items() if k != "output"} }\n{counts["output"][-3000:]}')
            total = counts['pass'] + counts['fail'] + counts['error'] + counts['skip']
            if section.exit_status is not None and (counts['fail'] or counts['error'] or skips):
                mx.abort(f'{section_id}: chibi exits as expected but does not pass every test: {counts}')
            chibi = str(counts['pass']) + (f' (skips test {", ".join(map(str, skips))})' if skips else '')
            mx.log(f'{section_id}: {total} tests; chibi passes {chibi}')
            table.append((section_id, title, program, arguments, environment, exit_status, str(total), chibi))
    finally:
        shutil.rmtree(workdir, ignore_errors=True)
    with open(join(out_dir, 'sections.tsv'), 'w', encoding='utf-8') as f:
        f.write(f'# Larceny {parsed.commit}. Generated by `mx r7rs-vendor-larceny`; columns: section id, title, program\n'
                '# (tests/scheme/run/<program>.sps), arguments, environment, exit status (- : must finish), tests,\n'
                '# tests chibi-scheme passes (and tests it cannot run). Counted with our features: ' + ' '.join(ours) + '\n')
        for row in table:
            f.write('\t'.join(row) + '\n')
    mx.log(f'wrote {len(copied)} files and {len(table)} sections ({sum(int(r[6]) for r in table)} tests) to tests/r7rs-larceny')


# tests/spec cases that need something a later task brings: reported as SKIPPED, naming the task.
# Remove an entry when its task lands (tests/spec/README.md).
_SPEC_PENDING = {
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
    sections = [s for suite in _SUITES for s in suite.sections()]
    normal = _run_conformance(sections)
    tortured = _run_conformance(sections, torture=True)
    keys = [(s.suite.name, s.id) for s in sections]
    worse = [f'{suite} {s}: {normal[(suite, s)]["pass"]} normally, {tortured[(suite, s)]["pass"]} under torture'
             for suite, s in keys if tortured[(suite, s)]['pass'] < normal[(suite, s)]['pass']]
    if worse:
        mx.abort('r7rs-torture: sections that pass less under torture:\n  ' + '\n  '.join(worse))
    total = sum(normal[k]['pass'] for k in keys)
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
    lines = [_STATUS_BEGIN,
             'Conformance against the vendored suites, interpreter only (except where a row',
             'says compiled). Generated by `mx r7rs-status`; `mx tests` fails if it is stale.',
             'Don\'t edit it by hand.']
    for suite in _SUITES:
        # A suite that lists exceptions (tests that fail by design, M4-12) shows how many fail.
        listing = bool(suite.exceptions())
        lines += ['', suite.heading + ':', '']
        if listing:
            lines += ['Exceptions: failing tests that expect a result where R7RS says "it is an error",',
                      f'listed in `{os.path.relpath(join(suite.dir, "README.md"), _suite.dir)}`.', '']
        lines += [f'| {suite.column} | Passing | Tests |' + (' Exceptions |' if listing else ''),
                  '|---|---:|---:|' + ('---:|' if listing else '')]
        total_pass = total = total_exceptions = 0
        for s in suite.sections():
            result = results[(suite.name, s.id)]
            passing = result['pass']
            total_pass += passing
            total += s.total
            row = f'| {s.title}{" (compiled: depends on speed)" if s.jit else ""} | {passing} | {s.total} |'
            if listing:
                exceptions = sum(_listed_exceptions(suite, s, result['output']))
                total_exceptions += exceptions
                row += f' {exceptions} |'
            lines.append(row)
        lines.append(f'| **Total** | **{total_pass}** | **{total}** |' + (f' **{total_exceptions}** |' if listing else ''))
    lines.append(_STATUS_END)
    return '\n'.join(lines)


def r7rs_status(args):
    """regenerate the status block in docs/plan.md: [--check]"""
    parser = argparse.ArgumentParser(prog='mx r7rs-status')
    parser.add_argument('--check', action='store_true', help='fail if the block is stale instead of rewriting it')
    parsed = parser.parse_args(args)
    results = _run_conformance([s for suite in _SUITES for s in suite.sections()])
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
# Benchmarks (plan.md M5-01): ecraven's and Larceny's R7RS benchmark sets under `mx benchmark`
# ---------------------------------------------------------------------------

_BENCH_DIR = join(_suite.dir, 'bench')


class _BenchmarkSet:
    """A vendored benchmark set (bench/r7rs-<name>/, read-only): upstream's src/<program>.scm,
    inputs/<program>.input and the shared src/common.scm harness, run the way upstream's `bench`
    script runs them (bench/README.md)."""

    def __init__(self, name, url, upstream_dir, copied, extra):
        self.name = name
        self.dir = join(_BENCH_DIR, 'r7rs-' + name)
        self.url = url
        self.upstream_dir = upstream_dir  # in the upstream repository
        self.copied = copied  # files and directories of upstream_dir that are vendored
        self.extra = extra  # {vendored name: path in the upstream repository}

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
            mx.abort(f'{self.dir}/src/common.scm no longer ends with (main): update the driver')
        return b'\n'.join([source, common[:m.start()], postlude, b'(main)\n'])


_BENCH_SETS = [
    _BenchmarkSet('ecraven', 'https://github.com/ecraven/r7rs-benchmarks', '',
                  ['README.org', 'bench', 'src', 'inputs'], {}),
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
    parser = argparse.ArgumentParser(prog='mx r7rs-vendor-benchmarks')
    parser.add_argument('set', choices=[s.name for s in _BENCH_SETS])
    parser.add_argument('source', help='a checkout of the upstream repository (see bench/README.md)')
    parser.add_argument('--commit', required=True, help='the commit the checkout is at')
    parsed = parser.parse_args(args)
    bench_set = next(s for s in _BENCH_SETS if s.name == parsed.set)
    upstream = join(parsed.source, bench_set.upstream_dir)
    if not re.fullmatch(r'[0-9a-f]{40}', parsed.commit):
        mx.abort('--commit takes a full commit hash')
    # Everything but our README.md is upstream's, copied unchanged.
    os.makedirs(bench_set.dir, exist_ok=True)
    for name in os.listdir(bench_set.dir):
        if name != 'README.md':
            path = join(bench_set.dir, name)
            shutil.rmtree(path) if os.path.isdir(path) else os.remove(path)
    for name in bench_set.copied:
        source = join(upstream, name)
        if os.path.isdir(source):
            shutil.copytree(source, join(bench_set.dir, name))
        else:
            shutil.copyfile(source, join(bench_set.dir, name))
    for name, path in bench_set.extra.items():
        shutil.copyfile(join(parsed.source, path), join(bench_set.dir, name))
    # The programs: the bench script's ALL_BENCHMARKS (`./bench <system> all`), grouped as it groups them.
    with open(join(bench_set.dir, 'bench'), encoding='utf-8') as f:
        variables = _bench_script_variables(f.read())
    if 'ALL_BENCHMARKS' not in variables:
        mx.abort('the bench script no longer defines ALL_BENCHMARKS: update the driver')
    groups = [g for g in variables if g != 'ALL_BENCHMARKS' and (g.endswith('_BENCHMARKS') or g.startswith('RED_EDITION_'))]
    rows = []
    for program in variables['ALL_BENCHMARKS'].split():
        group = next((g for g in groups if program in variables[g].split()), '-')
        for needed in (join('src', program + '.scm'), join('inputs', program + '.input')):
            if not exists(join(bench_set.dir, needed)):
                mx.abort(f'{program}: upstream has no {needed}')
        rows.append(f'{program}\t{group}')
    with open(join(bench_set.dir, 'benchmarks.tsv'), 'w', encoding='utf-8') as f:
        f.write(f'# {bench_set.url} at {parsed.commit}. Generated by `mx r7rs-vendor-benchmarks {bench_set.name}`;\n'
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
    mx.log(f'wrote {len(sums)} files and {len(rows)} programs to {os.path.relpath(bench_set.dir, _suite.dir)}')


_BENCH_MODES = {
    # mode: (VM arguments, default iterations, default warmup iterations)
    'jit': ([], 3, 1),
    'interpreter': (INTERPRETER_VM_ARGS, 1, 0),
}

_ELAPSED_LINE = re.compile(r'^Elapsed time: (\S+) seconds \(.*\) for (.*)$', re.MULTILINE)


def _bench_options_parser():
    parser = argparse.ArgumentParser(prog='mx benchmark r7rs-<set>:<program> -- ', add_help=False)
    parser.add_argument('--mode', choices=list(_BENCH_MODES) + ['*'], default='jit',
                        help='jit (default), interpreter, or * for both')
    parser.add_argument('--iterations', type=int, metavar='N',
                        help="how many times one JVM runs the suite's harness (each time `count` runs and the check)")
    parser.add_argument('--warmup', type=int, metavar='N', help='how many of those iterations are warmup')
    parser.add_argument('--count', type=int, metavar='N',
                        help="runs per iteration (default: the program's own count, from its .input file)")
    parser.add_argument('--timeout', type=int, default=0, metavar='SECONDS', help='kill the JVM after this long (0: never)')
    return parser


class _R7RSBenchmarkSuite(mx_benchmark.StdOutBenchmarkSuite):
    """`mx benchmark r7rs-<set>:<program>` (or :* for every program, each in a JVM of its own).

    Options, after `--`: see _bench_options_parser; anything else is a JVM argument. Each iteration's
    "Elapsed time" line (printed by the suite's own harness) becomes a `warmup` datapoint, and, after
    the warmup iterations, a `time` datapoint; the default `rsspercentiles` tracker adds the JVM's
    peak RSS (`max-rss`). A program whose check fails prints ERROR and fails the benchmark, unless
    bench/README.md lists it as failing; a listed program that passes fails too, so the list stays
    current."""

    def __init__(self, bench_set):
        super().__init__()
        self.bench_set = bench_set

    def name(self):
        return 'r7rs-' + self.bench_set.name

    def group(self):
        return 'r7rs'

    def subgroup(self):
        return 'truffle-scheme'

    def benchmarkList(self, bmSuiteArgs):
        return [program for program, _ in self.bench_set.benchmarks()]

    def defaultSuiteVersion(self):
        return self.bench_set.commit()

    def _options(self, bmSuiteArgs):
        options, jvm_args = _bench_options_parser().parse_known_args([a for a in bmSuiteArgs if a != '--'])
        if options.mode != '*':
            vm_args, iterations, warmup = _BENCH_MODES[options.mode]
            options.iterations = iterations if options.iterations is None else options.iterations
            options.warmup = min(warmup, options.iterations - 1) if options.warmup is None else options.warmup
            if options.iterations < 1 or not 0 <= options.warmup < options.iterations:
                mx.abort('--iterations must be at least 1 and --warmup less than --iterations')
            options.vm_args = list(vm_args)
        return options, jvm_args

    def expandBmSuiteArgs(self, benchmarks, bmSuiteArgs):
        if self._options(bmSuiteArgs)[0].mode != '*':
            return [bmSuiteArgs]
        return [[f'--mode={mode}' if a in ('--mode=*', '*') else a for a in bmSuiteArgs if a != '--mode'] for mode in _BENCH_MODES]

    def vmArgs(self, bmSuiteArgs):
        options, jvm_args = self._options(bmSuiteArgs)
        return _TRUFFLE_VM_ARGS + options.vm_args + jvm_args

    def runArgs(self, bmSuiteArgs):
        return []

    def failurePatterns(self):
        # ERROR: the harness's "returned incorrect result", or SchemeMain's uncaught error.
        return [re.compile(r'^(ERROR|INTERNAL ERROR):', re.MULTILINE)]

    def successPatterns(self):
        return [_ELAPSED_LINE]

    def run(self, benchmarks, bmSuiteArgs):
        if not benchmarks or len(benchmarks) != 1:
            mx.abort(f'run one program per JVM: mx benchmark {self.name()}:<program> or {self.name()}:*')
        program = benchmarks[0]
        reason = self.bench_set.failures().get(program)
        try:
            datapoints = super().run(benchmarks, bmSuiteArgs)
        except mx_benchmark.BenchmarkFailureError:
            if reason is None:
                raise
            mx.log(f'{self.name()}:{program} failed, as bench/README.md lists: {reason}')
            return []
        finally:
            # The RSS tracker's samples file, if a failure kept the tracker's rule from removing it.
            samples = getattr(self.tracker(), 'most_recent_text_output', None)
            if samples and exists(samples):
                os.remove(samples)
                self.tracker().most_recent_text_output = None
        if reason is not None:
            raise mx_benchmark.BenchmarkFailureError(
                f'{self.name()}:{program} passes but bench/README.md lists it as failing ({reason}): remove it from the list')
        return datapoints

    def runAndReturnStdOut(self, benchmarks, bmSuiteArgs):
        program = benchmarks[0]
        options, _ = self._options(bmSuiteArgs)
        implementation = f'truffle-scheme-{options.mode}'
        with open(join(_BENCH_DIR, 'postlude.scm'), 'rb') as f:
            postlude = (f'(define %r7rs-bench-implementation "{implementation}")\n'
                        f'(define %r7rs-bench-iterations {options.iterations})\n'
                        f'(define %r7rs-bench-count {options.count or "#f"})\n').encode('utf-8') + f.read()
        with open(join(self.bench_set.dir, 'inputs', program + '.input'), encoding='utf-8') as f:
            stdin = f.read()
        # Programs read inputs/... and write outputs/... relative to the working directory.
        workdir = tempfile.mkdtemp(prefix=f'r7rs-bench-{program}-')
        try:
            os.symlink(join(self.bench_set.dir, 'inputs'), join(workdir, 'inputs'))
            os.mkdir(join(workdir, 'outputs'))
            file = join(workdir, program + '.scm')
            with open(file, 'wb') as f:
                f.write(self.bench_set.program(program, postlude))
            jdk = mx.get_jdk()
            command = jdk.generate_java_command(self.vmArgs(bmSuiteArgs) + list(_launcher_jvm_args())
                                                + ['-m', 'scheme.launcher/scheme.launcher.SchemeMain', file])
            command = mx.apply_command_mapper_hooks(command, [(name, hook, self) for name, hook in self._command_mapper_hooks.items()])
            out = mx.TeeOutputCapture(mx.OutputCapture())
            mx.log(f'{self.name()}:{program} ({options.mode}, {options.iterations} iterations, {options.warmup} warmup'
                   + (f', count {options.count}' if options.count else '') + ')')
            rc = mx.run(command, out=out, err=out, cwd=workdir, stdin=stdin, nonZeroIsFatal=False,
                        timeout=options.timeout or None)
        finally:
            shutil.rmtree(workdir, ignore_errors=True)
        return rc, out.underlying.data, {'host-vm': 'truffle-scheme', 'host-vm-config': options.mode,
                                         'config.name': options.mode}

    def rules(self, output, benchmarks, bmSuiteArgs):
        options, _ = self._options(bmSuiteArgs)
        elapsed = [float(m.group(1)) * 1000 for m in _ELAPSED_LINE.finditer(output)]
        if len(elapsed) != options.iterations:
            raise mx_benchmark.BenchmarkFailureError(
                f'{benchmarks[0]}: {len(elapsed)} "Elapsed time" lines for {options.iterations} iterations')
        datapoints = []
        for i, ms in enumerate(elapsed):
            for metric in ('warmup', 'time') if i >= options.warmup else ('warmup',):
                datapoints.append(mx_benchmark.FixedRule({
                    'benchmark': benchmarks[0],
                    'metric.name': metric,
                    'metric.value': ms,
                    'metric.unit': 'ms',
                    'metric.score-function': 'id',
                    'metric.type': 'numeric',
                    'metric.better': 'lower',
                    'metric.iteration': i,
                }))
        return datapoints


for _bench_set in _BENCH_SETS:
    mx_benchmark.add_bm_suite(_R7RSBenchmarkSuite(_bench_set))


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
    'r7rs-test': [r7rs_test, '[section] [--suite all|chibi|larceny] [--torture] [--compile] [-v]'],
    'r7rs-spec': [r7rs_spec, '[-j N] [--shard I/N] [case...]'],
    'r7rs-diff': [r7rs_diff, 'file.scm [--compile] [--torture]'],
    'r7rs-diff-all': [r7rs_diff_all, '[--compile] [--torture]'],
    'r7rs-space': [r7rs_space, ''],
    'r7rs-torture': [r7rs_torture, ''],
    'r7rs-status': [r7rs_status, ''],
    'r7rs-check': [r7rs_check, ''],
    'r7rs-vendor-chibi': [r7rs_vendor_chibi, 'path/to/r7rs-tests.scm --version <chibi version>'],
    'r7rs-vendor-larceny': [r7rs_vendor_larceny, 'path/to/larceny --commit <hash>'],
    'r7rs-vendor-benchmarks': [r7rs_vendor_benchmarks, 'ecraven|larceny path/to/checkout --commit <hash>'],
    'r7rs-unittest': [r7rs_unittest, '[--compile] [--torture] [-j N] [--shard I/N] [unittest args...]'],
})

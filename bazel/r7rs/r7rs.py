"""Shared code of the Bazel test drivers: the runtime, our runner and chibi's, the conformance suites.

A port of the parts of mx.r7rs/mx_r7rs.py that the tests use; keep the two in step while both
build systems exist. Paths are runfiles paths: the drivers run as Bazel tests (or `bazel run`),
with the repository's files at $RUNFILES_DIR/_main.
"""

import concurrent.futures
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
from os.path import exists, join

# Flags the runtime reads (plan.md M0-01); the same lists as mx_r7rs.py.
# Compile mode: immediate, synchronous compilation, and compilation failures are errors.
COMPILE_VM_ARGS = [
    '-Dpolyglot.engine.AllowExperimentalOptions=true',
    '-Dpolyglot.engine.CompileImmediately=true',
    '-Dpolyglot.engine.BackgroundCompilation=false',
    '-Dpolyglot.engine.CompilationFailureAction=Throw',
]
# Interpreter only.
INTERPRETER_VM_ARGS = [
    '-Dpolyglot.engine.AllowExperimentalOptions=true',
    '-Dpolyglot.engine.Compilation=false',
]
# Torture: capture + resume at every non-tail call.
TORTURE_VM_ARGS = ['-Dscheme.torture=true']
# Truffle on JDK 25 uses restricted native access and sun.misc.Unsafe.
TRUFFLE_VM_ARGS = [
    '--enable-native-access=org.graalvm.truffle',
    '--sun-misc-unsafe-memory-access=allow',
]


def vm_args(compile=False, torture=False):
    """mx_r7rs.r7rs_vm_args: interpreter only unless compile, plus torture."""
    args = list(COMPILE_VM_ARGS if compile else INTERPRETER_VM_ARGS)
    if torture:
        args += TORTURE_VM_ARGS
    return args


def runfiles_dir():
    for name in ('RUNFILES_DIR', 'TEST_SRCDIR'):
        if os.environ.get(name):
            return os.environ[name]
    # `bazel run` of a py_binary sets RUNFILES_DIR; this is a fallback for direct runs.
    return os.path.abspath(sys.argv[0]) + '.runfiles'


def rlocation(path):
    return join(runfiles_dir(), path)


# The source tree: in runfiles for tests and `bazel run`, the execroot for build actions.
_ROOT = []


def set_repo_root(path):
    _ROOT[:] = [path]


def repo_root():
    """The source tree: tests/, docs/, src/ as far as the target's inputs provide them."""
    return _ROOT[0] if _ROOT else rlocation('_main')


def fail(message):
    print(message, file=sys.stderr, flush=True)
    sys.exit(1)


def log(message):
    print(message, flush=True)


class Runtime:
    """The GraalVM `java` and a module path."""

    def __init__(self, java, module_path, release=None):
        self.java = java
        self.module_path = os.pathsep.join(module_path)
        self.release = release

    @staticmethod
    def from_manifest(manifest):
        """From a scheme_runtime's JSON manifest (bazel/java_module.bzl), given as a runfiles path."""
        with open(rlocation(manifest), encoding='utf-8') as f:
            data = json.load(f)
        return Runtime(rlocation(data['java']), [rlocation(p) for p in data['module_path']],
                       rlocation(data['release']) if data['release'] else None)

    def command(self, jvm_args, main):
        """A java command line: JVM options, the module path, and `-m main` (module/class)."""
        tmp = os.environ.get('TEST_TMPDIR') or tempfile.gettempdir()
        return ([self.java, f'-Djava.io.tmpdir={tmp}'] + list(jvm_args) + ['-p', self.module_path]
                + TRUFFLE_VM_ARGS + ['-m', main])

    def run(self, jvm_args, main, args, cwd=None, env=None):
        """Runs java; returns (exit status, stdout, stderr)."""
        p = subprocess.run(self.command(jvm_args, main) + list(args), cwd=cwd, env=env,
                           stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, errors='replace')
        return p.returncode, p.stdout, p.stderr


def run_ours(runtime, file, mode_args, jvm_args=()):
    """mx_r7rs._run_ours: one program with scheme.launcher.SchemeMain."""
    return runtime.run(list(jvm_args) + list(mode_args), 'scheme.launcher/scheme.launcher.SchemeMain', [file])


def run_chibi(chibi, file):
    p = subprocess.run([chibi, file], stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, errors='replace')
    return p.returncode, p.stdout, p.stderr


def tail(text, lines=5):
    return '\n'.join('    ' + l for l in text.rstrip('\n').splitlines()[-lines:]) or '    (empty)'


# ---------------------------------------------------------------------------
# Conformance suites (mx_r7rs.py: _ChibiSuite, _LarcenySuite, _run_conformance, _status_block)
# ---------------------------------------------------------------------------

_RESULT_LINE = re.compile(r'^@@r7rs-test (BEGIN|PASS|FAIL|ERROR|SKIP|END) ?(.*)$')
_SECTION_LINE = re.compile(r'^@@section (\S+) (.*)$')

# Sections whose results depend on speed, so they always run compiled (mx_r7rs._LARCENY_JIT).
LARCENY_JIT = {'time'}


def count_results(output):
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


class Section:
    def __init__(self, suite, section_id, title, file, total, args=(), env=(), exit_status=None, jit=False):
        self.suite = suite
        self.id = section_id
        self.title = title
        self.file = file
        self.total = total
        self.args = list(args)
        self.env = list(env)
        self.exit_status = exit_status
        self.jit = jit


class Suite:
    def __init__(self, name, directory, heading, column):
        self.name = name
        self.rel_dir = 'tests/' + directory
        self.dir = join(repo_root(), 'tests', directory)
        self.heading = heading
        self.column = column

    def _rows(self):
        with open(join(self.dir, 'sections.tsv'), encoding='utf-8') as f:
            return [line.rstrip('\n').split('\t') for line in f if line.strip() and not line.startswith('#')]

    def exceptions(self):
        return {}


class ChibiSuite(Suite):
    def __init__(self):
        super().__init__('chibi', 'r7rs', "chibi-scheme's R7RS suite (`tests/r7rs/`)", 'Section')

    def sections(self):
        return [Section(self, section_id, title, join(self.dir, section_id + '.scm'), int(total))
                for section_id, title, total in self._rows()]

    def runner_args(self, workdir):
        return ['-I', join(repo_root(), 'tests', 'lib')]


class LarcenySuite(Suite):
    def __init__(self):
        super().__init__('larceny', 'r7rs-larceny', "Larceny's R7RS test suite (`tests/r7rs-larceny/`)", 'Library')

    def sections(self):
        def words(field):
            return [] if field == '-' else field.split(' ')
        return [Section(self, section_id, title, join(self.dir, 'tests', 'scheme', 'run', program + '.sps'), int(total),
                        words(args), words(env), None if exit_status == '-' else int(exit_status),
                        jit=section_id in LARCENY_JIT)
                for section_id, title, program, args, env, exit_status, total, _ in self._rows()]

    def exceptions(self):
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
        return ['-I', join(repo_root(), 'tests', 'lib'), '-I', self.dir, '-C', workdir]

    def make_workdir(self, workdir):
        # The suite loads tests/scheme/*.scm relative to its working directory and writes scratch
        # files there: a fresh directory whose tests/ is the vendored one.
        os.symlink(join(self.dir, 'tests'), join(workdir, 'tests'))


def suites():
    return [ChibiSuite(), LarcenySuite()]


def _count_section(section, output, message):
    counts = count_results(output)
    counts['message'] = message
    counts['output'] = output
    if section.exit_status is not None:
        counts['complete'] = message == f'exit {section.exit_status}'
        if counts['complete']:
            counts['pass'] += 1
    return counts


def failing_tests(output):
    tests = []
    for line in output.splitlines():
        m = _RESULT_LINE.match(line)
        if m and m.group(1) in ('FAIL', 'ERROR'):
            tests.append(m.group(2).split(' :: ', 1)[0])
    return tests


def listed_exceptions(suite, section, output):
    listed = list(suite.exceptions().get(section.id, []))
    marks = []
    for test in failing_tests(output):
        marks.append(test in listed)
        if marks[-1]:
            listed.remove(test)
    return marks


def run_conformance(runtime, sections, compile=False, torture=False, jobs=1):
    """Runs sections with scheme.launcher.ConformanceRunner, each in a fresh context.

    With jobs=1, one JVM per suite and mode, as mx runs them. With more, one JVM per section,
    `jobs` at a time, the largest sections first: the same results (every section gets a fresh
    context either way), in the time of the slowest section rather than of all of them.

    Returns {(suite name, section id): {'pass', 'fail', 'error', 'complete', 'message', 'output'}}."""
    groups = []
    if jobs == 1:
        by_suite = {}
        for s in sections:
            by_suite.setdefault(s.suite.name, []).append(s)
        for mine_all in by_suite.values():
            for jit in (False, True):
                mine = [s for s in mine_all if s.jit == jit]
                if mine:
                    groups.append(mine)
    else:
        groups = [[s] for s in sorted(sections, key=lambda s: -s.total)]
    results = {}
    with concurrent.futures.ThreadPoolExecutor(max_workers=jobs) as pool:
        for group_results in pool.map(lambda g: _run_group(runtime, g, compile, torture), groups):
            results.update(group_results)
    return results


def _run_group(runtime, mine, compile, torture):
    """Sections of one suite and mode, in one JVM."""
    suite, jit = mine[0].suite, mine[0].jit
    workdir = tempfile.mkdtemp(prefix=f'r7rs-{suite.name}-', dir=os.environ.get('TEST_TMPDIR'))
    try:
        if isinstance(suite, LarcenySuite):
            suite.make_workdir(workdir)
        files = []
        for s in mine:
            files += [a for arg in s.args for a in ('--arg', arg)]
            files += [a for binding in s.env for a in ('--env', binding)]
            files.append(s.file)
        rc, out, err = runtime.run(vm_args(compile=compile or jit, torture=torture),
                                   'scheme.launcher/scheme.launcher.ConformanceRunner',
                                   suite.runner_args(workdir) + files)
        out += err
    finally:
        shutil.rmtree(workdir, ignore_errors=True)
    results = {}
    done = []
    current = ''
    for line in out.splitlines(keepends=True):
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
        fail(f'ConformanceRunner failed (exit {rc}); no result for {missing}:\n{out}')
    return results


STATUS_BEGIN = '<!-- status:begin -->'
STATUS_END = '<!-- status:end -->'


def suite_block(suite, results):
    """One suite's part of the status block (mx_r7rs._status_block, per suite)."""
    listing = bool(suite.exceptions())
    lines = ['', suite.heading + ':', '']
    if listing:
        lines += ['Exceptions: failing tests that expect a result where R7RS says "it is an error",',
                  f'listed in `{suite.rel_dir}/README.md`.', '']
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
            exceptions = sum(listed_exceptions(suite, s, result['output']))
            total_exceptions += exceptions
            row += f' {exceptions} |'
        lines.append(row)
    lines.append(f'| **Total** | **{total_pass}** | **{total}** |' + (f' **{total_exceptions}** |' if listing else ''))
    return '\n'.join(lines)


def status_block(suite_blocks):
    """The generated block in docs/plan.md, from each suite's part in order (chibi, Larceny)."""
    lines = [STATUS_BEGIN,
             'Conformance against the vendored suites, interpreter only (except where a row',
             'says compiled). Generated by `mx r7rs-status`; `mx tests` fails if it is stale.',
             'Don\'t edit it by hand.']
    return '\n'.join(lines + [b.rstrip('\n') for b in suite_blocks] + [STATUS_END])


def read_status_block(paths):
    """The status block from conformance_results outputs (runfiles paths of their .md files)."""
    blocks = []
    for path in paths:
        with open(rlocation(path), encoding='utf-8') as f:
            blocks.append(f.read())
    return status_block(blocks)


def replace_status_block(text, block):
    begin, end = text.index(STATUS_BEGIN), text.index(STATUS_END) + len(STATUS_END)
    return text[:begin] + block + text[end:], text[begin:end]


def pass_counts(results):
    """{"suite/section": passing}: what a conformance_results target records."""
    return {f'{suite}/{section}': r['pass'] for (suite, section), r in sorted(results.items())}


def summary(results, sections):
    """One line per section: passing/total, and what aborted."""
    lines = []
    for s in sections:
        r = results[(s.suite.name, s.id)]
        note = '' if r['complete'] else '  (aborted: ' + r['message'] + ')'
        lines.append(f'{s.suite.name:<8} {s.id:<34} {r["pass"]:>4}/{s.total:<4} {s.title}{note}')
    return '\n'.join(lines)


def check(condition, message):
    if not condition:
        fail(message)


def require(path):
    check(exists(path), f'missing runfile: {path}')
    return path

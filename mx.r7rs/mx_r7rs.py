"""Custom mx commands and gate tasks for the r7rs suite.

Commands whose machinery doesn't exist yet abort with the task that brings
them. When such a task lands, replace the `_not_implemented` call and drop the
matching entry in `_PENDING_GATE_TASKS`.
"""

import argparse
import hashlib
import os
import re
import shutil
from os.path import exists, join

import mx
import mx_gate
import mx_unittest
from mx_gate import Task

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

_GATE_TAG = 'fast'


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


def r7rs_unittest(args):
    """run the JUnit tests in a given mode: [--compile] [--torture] [unittest args...]"""
    parser = argparse.ArgumentParser(prog='mx r7rs-unittest')
    parser.add_argument('--compile', action='store_true', help='compile immediately; compilation failures are errors')
    parser.add_argument('--torture', action='store_true', help='capture + resume at every non-tail call (from M2)')
    parsed, rest = parser.parse_known_args(args)
    mode = list(COMPILE_VM_ARGS) if parsed.compile else []
    if parsed.torture:
        mode += TORTURE_VM_ARGS
    _unittest_config.mode_vm_args = mode
    try:
        mx_unittest.unittest(rest)
    finally:
        _unittest_config.mode_vm_args = []


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
                     # tests/lib (the (chibi test) shim) is passed once the language has a
                     # library search path (design.md D7, M3).
                     + ['-m', 'scheme.launcher/scheme.launcher.ConformanceRunner'] + files,
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


def r7rs_spec(args):
    """run tests/spec (tail-call space, continuations, depth)"""
    _not_implemented('r7rs-spec', 'M2 (tests/spec is written by a human before M2 starts)')


def r7rs_diff(args):
    """run a file under chibi and under our implementation, then diff stdout and exit status"""
    parser = argparse.ArgumentParser(prog='mx r7rs-diff')
    parser.add_argument('file', help='Scheme file to run')
    parser.parse_args(args)
    _not_implemented('r7rs-diff', 'M1-02 (differential runner)')


_STATUS_BEGIN = '<!-- status:begin -->'
_STATUS_END = '<!-- status:end -->'


def _status_block(results):
    sections = _sections()
    lines = [_STATUS_BEGIN,
             'Conformance against the vendored chibi suite (`tests/r7rs/`), interpreter',
             'only. Generated by `mx r7rs-status`; the gate fails if it is stale. Don\'t',
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
# Gate
# ---------------------------------------------------------------------------

# Gate tasks whose machinery doesn't exist yet. They are logged as skipped, not
# failed, so the gate can be green before the work lands. Remove an entry when
# its task lands.
_PENDING_GATE_TASKS = [
    ('R7RS spec', 'M2'),
    ('R7RS torture', 'M2'),
]


def _gate_runner(args, tasks):
    with Task('R7RS build', tasks, tags=[_GATE_TAG]) as t:
        if t:
            mx.command_function('build')([])
    with Task('R7RS style', tasks, tags=[_GATE_TAG]) as t:
        if t:
            if mx.command_function('checkstyle')([]) != 0:
                t.abort('Checkstyle warnings were found')
    with Task('R7RS rule checks', tasks, tags=[_GATE_TAG]) as t:
        if t:
            r7rs_check([])
    with Task('R7RS conformance status', tasks, tags=[_GATE_TAG]) as t:
        if t:
            r7rs_status(['--check'])
    with Task('R7RS unittest', tasks, tags=[_GATE_TAG]) as t:
        if t:
            r7rs_unittest([])
    with Task('R7RS unittest --compile', tasks, tags=[_GATE_TAG]) as t:
        if t:
            r7rs_unittest(['--compile'])
    for title, task in _PENDING_GATE_TASKS:
        with Task(title, tasks, tags=[_GATE_TAG]) as t:
            if t:
                mx.log(f'SKIPPED: {title} is not implemented until {task}')


mx_gate.add_gate_runner(_suite, _gate_runner)

mx.update_commands(_suite, {
    'r7rs-test': [r7rs_test, '[section] [--torture] [--compile]'],
    'r7rs-spec': [r7rs_spec, ''],
    'r7rs-diff': [r7rs_diff, 'file.scm'],
    'r7rs-status': [r7rs_status, ''],
    'r7rs-check': [r7rs_check, ''],
    'r7rs-vendor-chibi': [r7rs_vendor_chibi, 'path/to/r7rs-tests.scm --version <chibi version>'],
    'r7rs-unittest': [r7rs_unittest, '[--compile] [--torture] [unittest args...]'],
})

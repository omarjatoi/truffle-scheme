"""Custom mx commands and gate tasks for the r7rs suite.

Commands whose machinery doesn't exist yet abort with the task that brings
them. When such a task lands, replace the `_not_implemented` call and drop the
matching entry in `_PENDING_GATE_TASKS`.
"""

import argparse
import os
import re
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
INTERPRETER_VM_ARGS = ['-Dpolyglot.engine.Compilation=false']
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

def r7rs_test(args):
    """run the chibi conformance suite: [section] [--torture] [--compile]"""
    parser = argparse.ArgumentParser(prog='mx r7rs-test')
    parser.add_argument('section', nargs='?', help='one conformance section, e.g. 6.10')
    parser.add_argument('--torture', action='store_true', help='capture + resume at every non-tail call')
    parser.add_argument('--compile', action='store_true', help='compile immediately; compilation failures are errors')
    parser.parse_args(args)
    _not_implemented('r7rs-test', 'M1-01 (conformance harness)')


def r7rs_spec(args):
    """run tests/spec (tail-call space, continuations, depth)"""
    _not_implemented('r7rs-spec', 'M2 (tests/spec is written by a human before M2 starts)')


def r7rs_diff(args):
    """run a file under chibi and under our implementation, then diff stdout and exit status"""
    parser = argparse.ArgumentParser(prog='mx r7rs-diff')
    parser.add_argument('file', help='Scheme file to run')
    parser.parse_args(args)
    _not_implemented('r7rs-diff', 'M1-02 (differential runner)')


def r7rs_status(args):
    """regenerate the status block in docs/plan.md"""
    _not_implemented('r7rs-status', 'M1-01 (conformance harness)')


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
    ('R7RS conformance', 'M1-01'),
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
    'r7rs-unittest': [r7rs_unittest, '[--compile] [--torture] [unittest args...]'],
})

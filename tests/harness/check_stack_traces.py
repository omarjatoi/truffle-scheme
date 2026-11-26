"""Test: tests/traces's cases through a launcher's printed stack trace (design.md D8, "Stack traces",
plan.md M14-04), the JVM launcher's (`//tests:stack_traces`) or the native launcher's
(`//tests:stack_traces_native`).

Each case, with each raiser in place of `RAISE` as StackTraceTest writes it, runs as a program; its
exit status and the lines the launcher prints after the error's message must be
tests/traces/expected/printed.txt's, as they are in the JUnit modes without torture. With
`--modes`, it runs interpreted only and compiled at once too, with harness.native_test_options() for
the native launcher: a runtime compilation that fails ends the run."""

import argparse
import os
import re
import subprocess
import tempfile
from os.path import dirname, join

from harness import check, log, native_test_options, rlocation

# The cases and raisers, in StackTraceTest's order (CASES, RAISERS): the expected file lists them so.
RAISERS = [
    ('error', "(car 'x)", 'car', "'x"),
    ('raise', "(raise 'boom)", 'raise', "'boom"),
    ('exit', '(exit 3)', 'exit', '3'),
    ('emergency-exit', '(emergency-exit 3)', 'emergency-exit', '3'),
]
MODES = {
    'jit': [],
    'interpreter': ['--experimental-options', '--engine.Compilation=false'],
    'compile': ['--experimental-options', '--engine.CompileImmediately=true', '--engine.BackgroundCompilation=false'],
}
TIMEOUT = 120  # seconds per run


def substitute(text, raiser, directory):
    return (text.replace('RAISE', raiser[1]).replace('PRIM', raiser[2]).replace('ARG', raiser[3])
            .replace('DIR', directory))


def write(traces, name, raiser, root):
    """The case's program in a directory of its own, and the launcher options its header asks for."""
    directory = join(root, f'{name}-{raiser[0]}')
    os.makedirs(directory)
    with open(join(traces, name + '.scm'), encoding='utf-8') as f:
        text = f.read()
    options = []
    for line in text.splitlines():
        if line.startswith('; options: '):
            options += [o.replace('DIR', directory) for o in line[len('; options: '):].split(' ')]
        elif line.startswith('; files: '):
            for file in line[len('; files: '):].split(' '):
                target = join(directory, file)
                os.makedirs(dirname(target), exist_ok=True)
                with open(join(traces, name, file), encoding='utf-8') as f:
                    content = f.read()
                with open(target, 'w', encoding='utf-8') as f:
                    f.write(substitute(content, raiser, directory))
    program = join(directory, name + '.scm')
    with open(program, 'w', encoding='utf-8') as f:
        f.write(substitute(text, raiser, directory))
    return program, options


def printed(launcher, extra, program, options):
    command = [launcher] + extra + options + [program]
    try:
        p = subprocess.run(command, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE,
                           timeout=TIMEOUT, check=False)
    except subprocess.TimeoutExpired:
        return f'timeout after {TIMEOUT}s'
    err = p.stderr.decode('utf-8', errors='replace')
    # The message line is M14-07's (design.md D8, "Messages"); the trace is what follows it.
    return '\n'.join([f'status {p.returncode}'] + [line for line in err.splitlines() if line.startswith('    ')])


def stack_traces(o):
    launcher = rlocation(o.launcher)
    expected_file = rlocation(o.expected)
    traces = dirname(dirname(expected_file))
    with open(expected_file, encoding='utf-8') as f:
        expected = f.read()
    names = list(dict.fromkeys(re.findall(r'^(\S+) error: ', expected, re.M)))
    check(len(names) > 20, f'{expected_file}: expected the cases, found {names}')
    extra = native_test_options() if o.native else []
    failures = []
    for mode in o.modes.split(','):
        root = tempfile.mkdtemp(dir=os.environ.get('TEST_TMPDIR'))
        actual = ''.join(f'{name} {raiser[0]}: ' + printed(launcher, extra + MODES[mode], *write(traces, name, raiser, root)) + '\n'
                         for name in names for raiser in RAISERS)
        ok = actual == expected
        log(f'{"ok  " if ok else "FAIL"} {mode}: {len(names)} cases, {len(RAISERS)} raisers')
        if not ok:
            got, want = actual.splitlines(), expected.splitlines()
            first = next((i for i in range(min(len(got), len(want))) if got[i] != want[i]), min(len(got), len(want)))
            failures.append(f'{mode}: line {first + 1} of {o.expected}:\n  expected: {want[first:first + 3]}\n  got:      '
                            f'{got[first:first + 3]}\nThe whole output:\n{actual}')
    check(not failures, 'Printed stack traces differ:\n' + '\n'.join(failures))


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--launcher', required=True)
    parser.add_argument('--native', action='store_true')
    parser.add_argument('--expected', required=True)
    parser.add_argument('--modes', default='jit')
    stack_traces(parser.parse_args())

"""Test: tests/space's deep recursions through the native launcher, on stacks from 256 KB to the
launcher's 256 MB, never overflow the stack (AGENTS.md rule 4, design.md "Stack-depth spill").

Each program runs with the Graal JIT in the background on every stack, and interpreted only and
compiled at once on two of them, with harness.native_test_options(): a runtime compilation that
fails ends the run. A program passes when it prints `--expect` and exits 0."""

import argparse
import subprocess

from harness import check, log, native_test_options, rlocation

# `--stack-size` values (the evaluation thread's stack; `default` evaluates on the native
# executable's main thread, 8 MB), and None for the launcher's default, 256 MB (design.md D17).
STACKS = ['256k', '1m', '8m', '64m', 'default', None]
# Modes: (name, launcher options, stacks it runs on).
MODES = [
    ('jit', [], STACKS),
    ('interpreter', ['--experimental-options', '--engine.Compilation=false'], ['1m', '64m']),
    ('compile', ['--experimental-options', '--engine.CompileImmediately=true', '--engine.BackgroundCompilation=false'],
     ['1m', '64m']),
]
TIMEOUT = 300  # seconds per run


def run(native, program, stack, options):
    command = [native] + native_test_options() + ([f'--stack-size={stack}'] if stack else []) + options + [program]
    try:
        p = subprocess.run(command, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                           timeout=TIMEOUT, check=False)
    except subprocess.TimeoutExpired:
        return None, f'timeout after {TIMEOUT}s'
    return p.returncode, p.stdout.decode('utf-8', errors='replace')


def space_native(o):
    native = rlocation(o.native)
    failures = []
    for program in o.programs:
        path = rlocation(program)
        for mode, options, stacks in MODES:
            for stack in stacks:
                status, output = run(native, path, stack, options)
                ok = status == 0 and output.strip() == o.expect
                name = f'{program.rsplit("/", 1)[-1]} {mode} --stack-size={stack or "(launcher default)"}'
                log(f'{"ok  " if ok else "FAIL"} {name}')
                if not ok:
                    failures.append(f'{name}: exit {status}\n    ' + '\n    '.join(output.strip().splitlines()[-5:]))
    check(not failures, 'Deep recursions failed through the native launcher:\n' + '\n'.join(failures))


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--native', required=True)
    parser.add_argument('--expect', required=True)
    parser.add_argument('programs', nargs='+')
    space_native(parser.parse_args())

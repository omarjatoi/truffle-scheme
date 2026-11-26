"""Test: design.md D8, "Running out of memory": tests/memory's programs under a 64 MB heap, through
the JVM launcher (`--runtime`) or the native launcher (`--native`). Each ends with a message on
stderr and the uncaught error's status, never a Java stack trace: a Scheme error where the size is
checked before allocating (a handler catches it first), and otherwise the launcher's out-of-memory
message, which no handler or after-thunk sees. Each runs again as the REPL's input, which goes on
after it: the context is still usable."""

import argparse
import os
import subprocess

from harness import Runtime, check, log, native_test_options, rlocation

HEAP = '--vm.Xmx64m'
EXIT_UNCAUGHT_ERROR = 70  # SchemeMain.EXIT_UNCAUGHT_ERROR
OUT_OF_MEMORY = 'ERROR: out of memory: the heap is full (--vm.Xmx<size> sets its maximum)'
TIMEOUT = 300  # seconds per run
# Each program's stdout, and what its error line on stderr (`ERROR: ...`) says: for a Scheme
# error, the procedure that raised it (design.md D8's message form puts a location before it and
# the rest after); for the heap, the launcher's whole line.
EXPECTED = {
    'size-vector': ('#t\n', ' make-vector:'),
    'size-string': ('#t\n#t\n#t\n', ' make-bytevector:'),
    'size-bignum': ('(error error error error error error read-error +inf.0)\n', ' expt:'),
    'heap-vector': ('start\n', OUT_OF_MEMORY),
    'heap-list': ('start\n', OUT_OF_MEMORY),
    'heap-deep': ('start\n', OUT_OF_MEMORY),
    'heap-string': ('start\n', OUT_OF_MEMORY),
    'heap-handlers': ('start\n', OUT_OF_MEMORY),
}
# What a Java stack trace, or an error that isn't one of these, would show.
NEVER = ('\tat ', 'INTERNAL ERROR', 'Exception', 'java.lang.')
AFTER = '(display "still here")\n'


def run(command, stdin=''):
    """Runs `command` with `stdin` as its input: its status, stdout and stderr."""
    try:
        p = subprocess.run(command, input=stdin, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
                           encoding='utf-8', errors='replace', timeout=TIMEOUT, check=False)
    except subprocess.TimeoutExpired:
        return None, '', f'timeout after {TIMEOUT}s'
    return p.returncode, p.stdout, p.stderr


def problems(status, out, err, want_status, want_out, want_err, repl):
    """What is wrong with a run, or an empty list."""
    found = []
    if status != want_status:
        found.append(f'exit {status}, not {want_status}')
    if repl:
        if not out.endswith('still here'):
            found.append('the REPL did not go on after the error')
    elif out != want_out:
        found.append(f'stdout {out!r}, not {want_out!r}')
    lines = err.strip().splitlines()
    errors = [line for line in lines if line.startswith('ERROR: ')]
    # A program ends at its error; the REPL goes on with the forms after it, which may fail in turn
    # (heap-vector's display of the vector it couldn't define).
    error = (errors[0] if repl else errors[-1]) if errors else ''
    if not (error == want_err if want_err == OUT_OF_MEMORY else want_err in error):
        found.append(f'no error line with {want_err!r}')
    found += [f'stderr has {never!r}' for never in NEVER if never in err]
    return found


def memory(o):
    if o.native:
        native = rlocation(o.native)
        launcher = lambda args: [native] + native_test_options() + args  # noqa: E731
    else:
        runtime = Runtime.from_manifest(o.runtime)
        launcher = runtime.launcher
    failures = []
    for program in o.programs:
        path = rlocation(program)
        name = os.path.basename(path).removesuffix('.scm')
        check(name in EXPECTED, f'{name}: not in check_memory.py\'s EXPECTED')
        want_out, want_err = EXPECTED[name]
        with open(path, encoding='utf-8') as f:
            text = f.read()
        for repl in (False, True):
            if repl:
                status, out, err = run(launcher([HEAP]), text + AFTER)
            else:
                status, out, err = run(launcher([HEAP, path]))
            found = problems(status, out, err, 0 if repl else EXIT_UNCAUGHT_ERROR, want_out, want_err, repl)
            what = f'{name} {"as REPL input" if repl else "as a program"}'
            log(f'{"FAIL" if found else "ok  "} {what}')
            if found:
                failures.append(f'{what}: ' + '; '.join(found) + '\n    stdout: ' + '\n    '.join(out.strip().splitlines()[-5:]) +
                                '\n    stderr: ' + '\n    '.join(err.strip().splitlines()[-15:]))
    check(not failures, 'Running out of memory went wrong:\n' + '\n'.join(failures))


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--runtime')
    parser.add_argument('--native')
    parser.add_argument('programs', nargs='+')
    memory(parser.parse_args())

"""Test: design.md D17 "VM options": the native launcher's `--vm.<option>`s take effect, as its own
`-<option>`s do. The heap's sizes and the other `-X` and `-XX:` options, which native-image sets only
as the process starts, through a restart with them; system properties and the stack size in the
process. A collector (chosen when the executable is built) and a class path are errors that say
what to do instead."""

import argparse
import os
import subprocess
import tempfile

from harness import check, log, rlocation

# A program that allocates about 130 MB and keeps none of it: it collects, and with
# -XX:+VerboseGC the first collection prints the heap's sizes.
GARBAGE = """(import (scheme base) (scheme write))
(let loop ((i 0)) (when (< i 3000000) (make-bytevector 40 0) (loop (+ i 1))))
(display "done")
"""
# A program that keeps about 200 MB alive.
LIVE = """(import (scheme base) (scheme write))
(define v (make-vector 3000000 #f))
(let loop ((i 0)) (when (< i 3000000) (vector-set! v i (make-bytevector 40 0)) (loop (+ i 1))))
(display (vector-length v))
"""
COMMAND_LINE = """(import (scheme base) (scheme process-context) (scheme write))
(write (cdr (command-line)))
"""


def run(command, status=0):
    """(stdout, stderr) of a command that must exit with `status` (None: any but 0)."""
    p = subprocess.run(command, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
                       encoding='utf-8', errors='replace', timeout=300)
    check(p.returncode != 0 if status is None else p.returncode == status,
          f'{command} exited with {p.returncode}:\n{p.stdout}\n{p.stderr}')
    return p.stdout, p.stderr


def launcher_vm_native(o):
    native = rlocation(o.native)
    tmp = tempfile.mkdtemp(dir=os.environ.get('TEST_TMPDIR'))
    programs = {}
    for name, text in (('garbage', GARBAGE), ('live', LIVE), ('command-line', COMMAND_LINE)):
        programs[name] = os.path.join(tmp, name + '.scm')
        with open(programs[name], 'w', encoding='utf-8') as f:
            f.write(text)

    # The heap's sizes, as -X options and as -XX: options, and with an option of the executable's
    # own, which the restart keeps.
    for options in (['--vm.XX:+VerboseGC', '--vm.Xmx1g', '--vm.Xms512m', '--vm.Xmn100m'],
                    ['-XX:+VerboseGC', '--vm.XX:MaxHeapSize=1g', '--vm.XX:MinHeapSize=512m', '--vm.XX:MaxNewSize=100m']):
        out, err = run([native] + options + [programs['garbage']])
        check(out == 'done', f'{options}: the program should run: {out!r}')
        for size in ('Maximum heap size: 1024M', 'Minimum heap size: 512M', 'Maximum young generation size: 100M'):
            check(size in err, f'{options} should set the heap ({size}):\n{err}')
    # A maximum below what the program keeps alive.
    out, _ = run([native, programs['live']])
    check(out == '3000000', f'the program should run without a maximum heap: {out!r}')
    out, err = run([native, '--vm.Xmx64m', programs['live']], status=None)
    check('heap size exceeded' in err, f'--vm.Xmx64m should bound the heap:\n{out}\n{err}')
    log('--vm.Xmx, -Xms, -Xmn and their -XX: options set the heap')

    # In the process: a system property (Truffle reads polyglot.* ones as options: this one
    # reaches the engine, which rejects it), the stack size.
    _, err = run([native, '--vm.Dpolyglot.scheme.NoSuchOption=1', programs['garbage']], status=None)
    check('scheme.NoSuchOption' in err, f'--vm.Dpolyglot.scheme.NoSuchOption=1 should reach the engine:\n{err}')
    out, _ = run([native, '--vm.Xss2m', '--vm.Xmx1g', programs['garbage']])
    check(out == 'done', f'--vm.Xss2m with a restart: {out!r}')
    # The program's --vm. arguments stay the program's. (Its -X, -XX: and -D arguments are the
    # executable's: native-image takes them from anywhere on its command line. design.md D17,
    # "VM options": why it isn't built with -H:-ParseRuntimeOptions.)
    out, _ = run([native, '--vm.Xmx1g', programs['command-line'], '--vm.Xmx2g', 'a'])
    check(out == '("--vm.Xmx2g" "a")', f'the arguments after the file should be the program\'s: {out!r}')
    log('--vm.D and --vm.Xss in the process; the program\'s arguments left alone')

    # What can't be: a collector, a class path.
    for option, says in (('--vm.XX:+UseG1GC', '--//:native_gc'), ('--vm.XX:+UseSerialGC', '--//:native_gc'),
                         ('--vm.cp=' + tmp, 'no class path')):
        _, err = run([native, option, programs['garbage']], status=64)
        check(says in err and 'bazel-bin/scheme' in err, f'{option} should be an error saying {says!r}:\n{err}')
    log('--vm. collector and class path: errors that say what to do')


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--native', required=True)
    launcher_vm_native(parser.parse_args())

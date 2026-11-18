"""Test: design.md D17 "VM options": the native launcher is GraalVM's thin launcher, which creates
the library's isolate with its `--vm.<option>`s: the heap's sizes, the collection policy, system
properties and the stack size take effect. Every argument after the program file is the program's,
`-X`, `-XX:` and `-D` ones included, and a `--vm.` one there makes the launcher run again without
it. The executable takes VM options only as `--vm.` ones; a collector (chosen when the executable is
built), a class path and an option the isolate would ignore are errors. Every class of option takes
effect or fails clearly (plan.md M10-04, docs/usage.md "Options")."""

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
# A loop that Truffle compiles with OSR as it runs, whose compiled code transfers to the
# interpreter when the loop ends.
OSR = """(import (scheme base) (scheme write))
(define (h i acc) (if (= i 0) acc (h (- i 1) (+ acc (string-length (number->string i))))))
(display (h 1000000 0))
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
    for name, text in (('garbage', GARBAGE), ('live', LIVE), ('command-line', COMMAND_LINE), ('osr', OSR)):
        programs[name] = os.path.join(tmp, name + '.scm')
        with open(programs[name], 'w', encoding='utf-8') as f:
            f.write(text)

    # The heap's sizes, as -X options and as -XX: options.
    for options in (['--vm.XX:+VerboseGC', '--vm.Xmx1g', '--vm.Xms512m', '--vm.Xmn100m'],
                    ['--vm.XX:+VerboseGC', '--vm.XX:MaxHeapSize=1g', '--vm.XX:MinHeapSize=512m', '--vm.XX:MaxNewSize=100m']):
        out, err = run([native] + options + [programs['garbage']])
        check(out == 'done', f'{options}: the program should run: {out!r}')
        for size in ('Maximum heap size: 1024M', 'Minimum heap size: 512M', 'Maximum young generation size: 100M'):
            check(size in err, f'{options} should set the heap ({size}):\n{err}')
    # A maximum below what the program keeps alive.
    out, _ = run([native, programs['live']])
    check(out == '3000000', f'the program should run without a maximum heap: {out!r}')
    out, err = run([native, '--vm.Xmx64m', programs['live']], status=None)
    check('heap size exceeded' in err, f'--vm.Xmx64m should bound the heap:\n{out}\n{err}')
    # A minimum heap and a collection policy, which only the isolate's creation can set (with a
    # young generation small enough for the program to collect).
    _, err = run([native, '--vm.XX:+VerboseGC', '--vm.Xms1g', '--vm.Xmn64m', '--vm.XX:InitialCollectionPolicy=BySpaceAndTime',
                  programs['garbage']])
    for says in ('Minimum heap size: 1024M', 'GC policy: by space and time'):
        check(says in err, f'--vm.Xms1g --vm.XX:InitialCollectionPolicy=BySpaceAndTime should say {says!r}:\n{err}')
    log('--vm.Xmx, -Xms, -Xmn, their -XX: options and the collection policy set the heap')

    # In the process: a system property (Truffle reads polyglot.* ones as options: this one
    # reaches the engine, which rejects it, and the launcher says so: plan.md M10-04), the stack size.
    _, err = run([native, '--vm.Dpolyglot.scheme.NoSuchOption=1', programs['garbage']], status=1)
    check('scheme.NoSuchOption' in err and err.startswith('ERROR: ') and '\tat ' not in err,
          f'--vm.Dpolyglot.scheme.NoSuchOption=1 should reach the engine, and be a clear error:\n{err}')
    out, _ = run([native, '--vm.Xss2m', '--vm.Xmx1g', programs['garbage']])
    check(out == 'done', f'--vm.Xss2m --vm.Xmx1g: {out!r}')
    log('--vm.D and --vm.Xss')

    # Every argument after the file is the program's, as given: the isolate parses only the
    # options the thin launcher gives it, never the process's command line.
    given = ['a', '-Xmx3g', '-XX:+Foo', '-Dx=y', '-Xss1k', 'b']
    out, _ = run([native, programs['command-line']] + given)
    check(out == '(' + ' '.join(f'"{a}"' for a in given) + ')', f'the program should get {given}: {out!r}')
    # A --vm. argument after the file is the program's too: the thin launcher took it, the
    # framework finds it wasn't the launcher's, and the launcher runs again without it.
    out, _ = run([native, '--vm.Xmx1g', programs['command-line'], '--vm.Xmx2g', 'a'])
    check(out == '("--vm.Xmx2g" "a")', f'the arguments after the file should be the program\'s: {out!r}')
    _, err = run([native, '--vm.Xmx64m', programs['live'], '--vm.Xmx2g'], status=None)
    check('heap size exceeded' in err, f'after the relaunch, the launcher\'s --vm.Xmx64m should still bound the heap:\n{err}')
    log('the program\'s arguments left alone, -X, -XX:, -D and --vm. ones included')

    # What can't be: a class path; a collector, which the isolate's creation rejects as an option
    # it doesn't know, before the launcher runs (design.md D17, "VM options": the costs).
    _, err = run([native, '--vm.cp=' + tmp, programs['garbage']], status=1)
    check('no class path' in err and 'bazel-bin/scheme' in err, f'--vm.cp should be an error saying so:\n{err}')
    for option in ('--vm.XX:+UseG1GC', '--vm.XX:+UseSerialGC'):
        _, err = run([native, option, programs['garbage']], status=None)
        check("Could not find option 'Use" in err, f'{option} should be an error:\n{err}')
    # The VM's options in java's form, before the file: an error naming the --vm. form.
    for option in ('-Xmx2g', '-XX:+PrintGC', '-Dx=y'):
        _, err = run([native, option, programs['garbage']], status=2)
        check('--vm.' + option[1:] in err, f'{option} should be an error naming --vm.{option[1:]}:\n{err}')
    # Options the isolate takes and ignores (plan.md M10-04): the launcher's errors.
    for option in ('--vm.ea', '--vm.Xint', '--vm.verbose:gc', '--vm.Xlog:gc', '--vm.Xfoo'):
        _, err = run([native, option, programs['garbage']], status=1)
        check('would ignore it' in err and option in err, f'{option} should be an error saying the native image would ignore it:\n{err}')
    for option in ('--vm.p=' + tmp, '--vm.Djava.library.path=' + tmp):
        _, err = run([native, option, programs['garbage']], status=1)
        check('no module path' in err, f'{option} should be an error:\n{err}')
    log('--vm. class path and collector, options the isolate ignores, and -X, -XX: and -D before the file: errors')


def option_classes(o):
    """plan.md M10-04, docs/usage.md "Options": every class of option through the native launcher
    takes effect or fails clearly."""
    native = rlocation(o.native)
    tmp = tempfile.mkdtemp(dir=os.environ.get('TEST_TMPDIR'))
    program = os.path.join(tmp, 'osr.scm')
    with open(program, 'w', encoding='utf-8') as f:
        f.write(OSR)
    sync = ['--experimental-options', '--engine.BackgroundCompilation=false']

    # --engine.: one that takes effect, a VM-wide one, one there is none of.
    out, err = run([native] + sync + ['--engine.TraceCompilation', program])
    check(out == '5888896' and 'opt done' in err, f'--engine.TraceCompilation should trace the compilations:\n{out}\n{err}')
    _, err = run([native] + sync + ['--engine.TraceTransferToInterpreter', program])
    check('reason: TransferToInterpreter' in err, f'--engine.TraceTransferToInterpreter should trace the OSR loop\'s exit:\n{err}')
    _, err = run([native, '--engine.NoSuchOption', program], status=2)
    check("Unrecognized argument: '--engine.NoSuchOption'" in err, f'--engine.NoSuchOption should be an error:\n{err}')
    # --compiler.: the instrumentation, which the native image's compiler fails with.
    for option in ('--compiler.InstrumentBoundaries', '--engine.InstrumentBranches'):
        _, err = run([native, '--experimental-options', option, program], status=1)
        check('bazel-bin/scheme' in err, f'{option} should be an error naming the JVM launcher:\n{err}')
    # --scheme.: one there is none of, one the launcher sets itself.
    _, err = run([native, '--scheme.NoSuchOption=1', program], status=2)
    check("Unrecognized argument: '--scheme.NoSuchOption=1'" in err, f'--scheme.NoSuchOption should be an error:\n{err}')
    _, err = run([native, '--scheme.StackSize=1m', program], status=1)
    check('--stack-size=' in err, f'--scheme.StackSize, which the launcher sets, should be an error naming --stack-size:\n{err}')
    log('--engine. (TraceTransferToInterpreter too), --compiler., --scheme.')

    # The tools, which the native launcher is built without; logging.
    for option in ('--cpusampler', '--inspect=9229'):
        _, err = run([native, option, program], status=2)
        check('built without GraalVM\'s tools' in err and 'bazel-bin/scheme' in err, f'{option} should be an error naming the JVM launcher:\n{err}')
    log_file = os.path.join(tmp, 'engine.log')
    _, err = run([native, '--log.level=FINE', '--log.file=' + log_file, program])
    with open(log_file, encoding='utf-8') as f:
        logged = f.read()
    check('FINE' in logged and 'FINE' not in err, f'--log.file should get the log, not stderr:\n{logged}\n{err}')
    _, err = run([native, '--jvm', program], status=1)
    check('--jvm' in err, f'--jvm should be an error in the native launcher:\n{err}')
    log('the tools, --log.file and --log.level, --jvm')

    # Help, version, unknown options, and every argument after the file is the program's.
    _, err = run([native, '--help:nosuch'], status=2)
    check("Unrecognized help topic: '--help:nosuch'" in err, f'--help:nosuch should be an error:\n{err}')
    out, _ = run([native, '--version'])
    check(out.startswith('R7RS Scheme ('), f'--version: {out}')
    _, err = run([native, '--no-such-option'], status=2)
    check("Unrecognized argument: '--no-such-option'" in err, f'an unknown option should be an error:\n{err}')
    given = ['--engine.TraceCompilation', '--cpusampler', '--help:nosuch', '--vm.ea']
    command_line = os.path.join(tmp, 'command-line.scm')
    with open(command_line, 'w', encoding='utf-8') as f:
        f.write(COMMAND_LINE)
    out, _ = run([native, command_line] + given)
    check(out == '(' + ' '.join(f'"{a}"' for a in given) + ')', f'the arguments after the file should be the program\'s: {out!r}')
    log('--help:<topic>, --version, an unknown option, the program\'s arguments')


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--native', required=True)
    options = parser.parse_args()
    launcher_vm_native(options)
    option_classes(options)

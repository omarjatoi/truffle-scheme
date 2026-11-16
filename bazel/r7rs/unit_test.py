"""One JUnit test class (tests/scheme/test), in one mode, as a Bazel test.

    unit_test.py --runtime <manifest> --mode jit|compile|torture scheme.test.ReaderTest

The class runs in a JVM of its own with the scheme modules, Truffle and JUnit on the module path,
as `mx unittest` runs it. Modes (mx_r7rs.py): `jit` is the Graal JIT in its normal background
mode (`mx unittest`), `compile` compiles immediately and makes compilation failures errors
(`mx r7rs-unittest --compile`), `torture` captures and resumes at every non-tail call
(`mx r7rs-unittest --torture`).
"""

import argparse
import subprocess
import sys

import r7rs

MODES = {
    'jit': [],
    'compile': r7rs.COMPILE_VM_ARGS,
    'torture': r7rs.TORTURE_VM_ARGS,
}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--runtime', required=True)
    parser.add_argument('--mode', choices=MODES, required=True)
    parser.add_argument('test_class')
    args = parser.parse_args()
    runtime = r7rs.Runtime.from_manifest(args.runtime)
    # -ea -esa as mx unittest. JUnit 4 runs as the automatic module `junit`, which scheme.test
    # exports and opens its package to.
    command = runtime.command(['-ea', '-esa', '--add-modules', 'ALL-MODULE-PATH'] + MODES[args.mode],
                              'junit/org.junit.runner.JUnitCore') + [args.test_class]
    r7rs.log(f'{args.test_class} ({args.mode})')
    sys.exit(subprocess.run(command).returncode)


if __name__ == '__main__':
    main()

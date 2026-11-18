"""Test: design.md D11, "An output directory per platform": `bazel-bin/scheme` runs a program; and where
it can't, run on another platform than its own (`bazel-bin` after `bazel test` on a Mac), without its
JDK or without its runfiles, it exits with 1 and one line that names the command to run, `bazel
build //:scheme`, before any Python starts (bazel/launcher.bzl, plan.md M12-09)."""

import argparse
import os
import shutil
import stat
import subprocess
import tempfile
from os.path import join

from harness import check, log, rlocation, runfiles_dir

_COMMAND = 'run `bazel build //:scheme`'


def run(command, env, status):
    p = subprocess.run(command, env=env, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                       text=True, encoding='utf-8', errors='replace', timeout=120)
    check(p.returncode == status, f'{command} exited with {p.returncode}, not {status}:\n{p.stdout}{p.stderr}')
    return p.stdout, p.stderr


def refused(command, env, reason):
    """The launcher must exit with 1 and one line on stderr, naming the command and the reason."""
    out, err = run(command, env, 1)
    lines = err.splitlines()
    check(out == '' and len(lines) == 1, f'{command}: expected one line on stderr, got:\n{out}{err}')
    check(lines[0].startswith(command[0] + ' ') and lines[0].endswith(_COMMAND) and reason in lines[0],
          f'{command}: the line should say {reason!r} and end with {_COMMAND!r}: {lines[0]}')
    return lines[0]


def write(path, text, mode=0o644):
    with open(path, 'w', encoding='utf-8') as f:
        f.write(text)
    os.chmod(path, mode)


def copy_without(scheme, missing):
    """bazel-bin/ as `bazel build //:scheme` leaves it, but for `missing` (a path in the runfiles, or
    None for all of them): a copy of the script and a tree of symlinks to this test's runfiles, which
    hold the launcher's."""
    bin_dir = tempfile.mkdtemp(dir=os.environ.get('TEST_TMPDIR'))
    copy = join(bin_dir, 'scheme')
    shutil.copyfile(scheme, copy)
    os.chmod(copy, stat.S_IRWXU)
    if missing is None:
        return copy
    root = runfiles_dir()

    def link(rel):
        source, target = join(root, rel), join(copy + '.runfiles', rel)
        if rel == missing:
            return
        if missing.startswith(rel + '/'):
            os.makedirs(target)
            for entry in os.listdir(source):
                link(join(rel, entry) if rel else entry)
        else:
            os.symlink(source, target)

    os.makedirs(copy + '.runfiles')
    for entry in os.listdir(root):
        link(entry)
    return copy


def launcher(o):
    scheme = rlocation(o.scheme)
    tmp = tempfile.mkdtemp(dir=os.environ.get('TEST_TMPDIR'))
    program = join(tmp, 'hello.scm')
    write(program, '(import (scheme base) (scheme write)) (display "hello") (newline)\n')
    env = {k: v for k, v in os.environ.items() if k not in ('RUNFILES_DIR', 'RUNFILES_MANIFEST_FILE', 'JAVA_RUNFILES')}

    out, _ = run([scheme, program], dict(env, RUNFILES_DIR=runfiles_dir()), 0)
    check(out == 'hello\n', f'bazel-bin/scheme should run the program: {out!r}')
    log('runs a program')

    # Another machine: what `uname` prints on none it is built for.
    fake = join(tmp, 'fake-bin')
    os.makedirs(fake)
    write(join(fake, 'uname'), '#!/bin/sh\ncase "$1" in -s) echo Plan9 ;; -m) echo mips ;; *) echo Plan9 ;; esac\n', 0o755)
    line = refused([scheme, program], dict(env, RUNFILES_DIR=runfiles_dir(), PATH=fake + os.pathsep + env.get('PATH', '')),
                   'not this machine')
    log(f'on another platform: {line}')

    # Its JDK gone, as after a Linux test wrote to the same directory, or the output base pruned.
    copy = copy_without(scheme, o.jdk)
    line = refused([copy, program], env, 'has no JDK')
    log(f'without its JDK: {line}')

    copy = copy_without(scheme, None)
    line = refused([copy, program], env, 'has no runfiles')
    log(f'without its runfiles: {line}')


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--scheme', required=True, help='//:scheme, the script')
    parser.add_argument('--jdk', required=True, help="its JDK's runfiles path")
    launcher(parser.parse_args())

"""Test: docs/usage.md's and docs/embedding.md's blocks run as written (readme.py)."""

import argparse
import os
import shutil
from os.path import join

import readme
from harness import Runtime, check, log, rlocation


def _shim(directory, name, verb):
    """`name verb ...` succeeds and does nothing: the readme test depends on what it would build."""
    path = join(directory, name)
    with open(path, 'w', encoding='utf-8') as f:
        f.write(f'#!/bin/sh\n[ "$1" = {verb} ] && exit 0\n'
                f'echo "the readme test runs only \\`{name} {verb}\\`, not: {name} $*" >&2\nexit 1\n')
    os.chmod(path, 0o755)


def readme_test(o):
    """Test: docs/usage.md's and docs/embedding.md's blocks (readme.py), the commands as written, in
    a directory laid out like the checkout: `bazel-bin/` holds the targets this test depends on,
    `bazel build` and `nix develop` do nothing (Bazel built those first), and `java` is the pinned
    GraalVM's. With --native, only the native-image blocks, against that launcher (and the
    polyglot isolate's jars, --isolate-jars)."""
    tmp = os.environ['TEST_TMPDIR']
    main_repo = os.path.dirname(os.path.dirname(rlocation(o.usage)))
    checkout, shims = join(tmp, 'checkout'), join(tmp, 'shims')
    os.makedirs(join(checkout, 'bazel-bin'))
    os.makedirs(shims)
    # Copies, not runfiles symlinks: the profilers print a program's path relative to its real one.
    shutil.copytree(join(main_repo, 'docs'), join(checkout, 'docs'))
    os.symlink(join(main_repo, 'tests'), join(checkout, 'tests'))
    os.symlink(rlocation(o.scheme), join(checkout, 'bazel-bin', 'scheme'))
    os.symlink(os.path.dirname(rlocation(o.jars[0])), join(checkout, 'bazel-bin', 'scheme_jars'))
    if o.native:
        os.symlink(rlocation(o.native), join(checkout, 'bazel-bin', 'scheme-native'))
    if o.isolate_jars:
        os.symlink(os.path.dirname(rlocation(o.isolate_jars[0])), join(checkout, 'bazel-bin', 'scheme_isolate_jars'))
    _shim(shims, 'bazel', 'build')
    _shim(shims, 'nix', 'develop')
    os.symlink(Runtime.from_manifest(o.runtime).java, join(shims, 'java'))
    os.environ['PATH'] = shims + os.pathsep + os.environ.get('PATH', '')
    problems = readme.run(checkout, native=bool(o.native))
    check(not problems, 'readme: failed:\n' + '\n'.join(problems))
    log('readme: ' + ('the native blocks run' if o.native else 'every block is checked, and every command runs as documented'))


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--runtime', required=True)
    parser.add_argument('--usage', required=True)
    parser.add_argument('--scheme', required=True)
    parser.add_argument('--jars', nargs='+', required=True, help="bazel-bin/scheme_jars' jars")
    parser.add_argument('--native', help='only the native-image blocks, against this launcher')
    parser.add_argument('--isolate-jars', nargs='+', help="bazel-bin/scheme_isolate_jars' jars, for the native blocks")
    readme_test(parser.parse_args())

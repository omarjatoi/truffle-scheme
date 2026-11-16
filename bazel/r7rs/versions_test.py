"""design.md D11 as a test: the GraalVM JDK that runs the tests and the Truffle libraries are one release.

    versions_test.py --runtime <manifest> --lock <maven_install.json> --suite <mx.r7rs/suite.py>

Checks the GRAALVM_VERSION in the test JDK's `release` file against every org.graalvm artifact
in the Maven lock file, and (while mx still builds the repo) those against mx.r7rs/suite.py's
libraries, so the two build systems pin the same Truffle. MODULE.bazel already derives both the
JDK and the artifacts from one TRUFFLE_VERSION (bazel/graalvm.bzl fails on a version it has no
release for); this catches a wrong archive or a hand-edited lock file.
"""

import argparse
import json
import re

import r7rs


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--runtime', required=True)
    parser.add_argument('--lock', required=True)
    parser.add_argument('--suite', required=True)
    args = parser.parse_args()
    runtime = r7rs.Runtime.from_manifest(args.runtime)
    r7rs.check(runtime.release is not None, 'the test JDK has no release file')
    with open(runtime.release, encoding='utf-8') as f:
        m = re.search(r'^GRAALVM_VERSION="?([^"\n]*)"?', f.read(), re.MULTILINE)
    graalvm = m.group(1) if m else None
    with open(r7rs.rlocation(args.lock), encoding='utf-8') as f:
        lock = {coordinate: a['version'] for coordinate, a in json.load(f)['artifacts'].items()}
    truffle = lock['org.graalvm.truffle:truffle-api']
    problems = []
    if graalvm != truffle:
        problems.append(f'the test JDK is {"GraalVM " + graalvm if graalvm else "not a GraalVM JDK"}, '
                        f'but the lock file pins Truffle {truffle}')
    problems += [f'{c} is {v} in the lock file, not {truffle}' for c, v in sorted(lock.items())
                 if c.startswith('org.graalvm.') and v != truffle]
    with open(r7rs.rlocation(args.suite), encoding='utf-8') as f:
        suite = f.read()
    pins = re.findall(r'"maven": \{"groupId": "(org\.graalvm[^"]*)", "artifactId": "([^"]+)", "version": "([^"]+)"\}', suite)
    r7rs.check(pins, 'found no org.graalvm libraries in mx.r7rs/suite.py')
    problems += [f'mx.r7rs/suite.py pins {g}:{a} {v}, the lock file {lock.get(g + ":" + a)}'
                 for g, a, v in pins if lock.get(g + ':' + a) != v]
    r7rs.check(not problems, 'GraalVM and Truffle versions differ (design.md D11):\n  ' + '\n  '.join(problems)
               + '\nUpgrade them together: bazel/graalvm.bzl, TRUFFLE_VERSION in MODULE.bazel, '
               '`bazel run @jvm//:pin`, mx.r7rs/suite.py and flake.lock.')
    r7rs.log(f'GraalVM {graalvm} = Truffle {truffle} (lock file, and {len(pins)} libraries in mx.r7rs/suite.py)')


if __name__ == '__main__':
    main()

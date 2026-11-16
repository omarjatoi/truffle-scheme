"""Test: design.md D11, the JDK the tests run on is the GraalVM release of the Truffle libraries."""

import argparse
import json

import harness
from harness import Runtime, check, log, rlocation


def versions(o):
    """design.md D11: the JDK the tests run on is the GraalVM release of the Truffle libraries."""
    runtime = Runtime.from_manifest(o.runtime)
    graalvm = harness.graalvm_version(runtime.release)
    with open(rlocation(o.lock), encoding='utf-8') as f:
        lock = {coordinate: a['version'] for coordinate, a in json.load(f)['artifacts'].items()}
    problems = [f'{c} is {v}, the JDK GraalVM {graalvm}' for c, v in sorted(lock.items())
                if c.startswith('org.graalvm.') and v != graalvm]
    check(not problems, 'GraalVM and Truffle versions differ (design.md D11):\n  ' + '\n  '.join(problems)
          + '\nUpgrade them together: bazel/graalvm.bzl, TRUFFLE_VERSION in MODULE.bazel, `bazel run @jvm//:pin`.')
    log(f'GraalVM {graalvm} = every org.graalvm artifact in the lock file')


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--runtime', required=True)
    parser.add_argument('--lock', required=True)
    versions(parser.parse_args())

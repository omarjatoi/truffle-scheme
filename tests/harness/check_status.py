"""Tests: docs/plan.md's status block says what the conformance tests check (`plan`), and the
native launcher passes it (`native FILE`)."""

import argparse
import sys

import harness
from harness import check, log, rlocation


def conformance_status(o):
    """Test: docs/plan.md's status block says what the conformance tests check."""
    block = harness.expected_status_block()
    with open(rlocation(o.plan), encoding='utf-8') as f:
        _, current = harness.replace_status_block(f.read(), block)
    check(current == block, 'The status block in docs/plan.md is stale. Run `bazel run //:r7rs -- status` and commit '
          'the result.\nExpected:\n' + block)
    log('status block in docs/plan.md is up to date')


def conformance_native(o):
    """Test: both suites through the native launcher pass what docs/plan.md's status block says."""
    all_suites = harness.suites()
    sections = [s for suite in all_suites for s in suite.sections()]
    results = harness.run_conformance(None, sections, native=rlocation(o.native))
    block = harness.status_block([harness.suite_block(suite, results) for suite in all_suites])
    check(block == harness.expected_status_block(), 'Through the native launcher, the suites give a different status block:\n' + block)
    log('the native launcher passes what docs/plan.md says')


def main():
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest='command', required=True)
    sub.add_parser('plan').add_argument('plan')
    sub.add_parser('native').add_argument('native')
    o = parser.parse_args()
    (conformance_status if o.command == 'plan' else conformance_native)(o)
    sys.exit(0)


if __name__ == '__main__':
    main()

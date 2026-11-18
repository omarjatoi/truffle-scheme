"""//tests/fuzz:fuzz_smoke: a few hundred generated programs, from a fixed seed, under chibi-scheme
and under ours (interpreted): the same stdout and exit status, and chibi-scheme runs every one.
It keeps the generator and the driver working; the fuzzing itself is `bazel run //:r7rs -- fuzz`
(README.md)."""

import argparse
import os

import fuzz
import harness


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--runtime', required=True)
    parser.add_argument('--chibi', required=True)
    parser.add_argument('--seed', type=int, required=True)
    parser.add_argument('--count', type=int, required=True)
    parser.add_argument('--batch', type=int, default=25)
    parser.add_argument('--mode', default='interp')
    o = parser.parse_args()
    out = os.environ.get('TEST_UNDECLARED_OUTPUTS_DIR') or os.environ['TEST_TMPDIR']
    results, stats = fuzz.fuzz(harness.rlocation(o.chibi), harness.Runtime.from_manifest(o.runtime), o.seed, o.count,
                               o.mode.split(','), batch=o.batch, jobs=4, out=out, minimize=False)
    problems = [f'{m}: {s["chibi-error"]} files chibi-scheme failed, {s["skipped"]} skipped'
                for m, s in stats.items() if s['chibi-error'] or s['skipped']]
    problems += [f'{mode}: {path} differs:\n{description}' for mode, _, path, _, description in results]
    harness.check(not problems, '\n'.join(problems) + '\n(the programs are in the test\'s outputs: '
                  'bazel-testlogs/tests/fuzz/fuzz_smoke/test.outputs)')
    harness.log(f'{o.count} programs the same under chibi-scheme and ours')


if __name__ == '__main__':
    main()

"""//tests/fuzz:fuzz_smoke: a few hundred generated programs, from a fixed seed, under chibi-scheme
and under ours (interpreted): the same stdout and exit status, and chibi-scheme runs every one.
It keeps the generator and the driver working; the fuzzing itself is `bazel run //:r7rs -- fuzz`
(README.md). //tests/fuzz:fuzz_smoke_native runs them through the native launcher (`--native`,
`--mode native`)."""

import argparse
import os

import fuzz
import harness


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--runtime', help='the JVM modes\' runtime (a scheme_runtime manifest)')
    parser.add_argument('--native', help='the native launcher, for --mode native')
    parser.add_argument('--chibi', required=True)
    parser.add_argument('--seed', type=int, required=True)
    parser.add_argument('--count', type=int, required=True)
    parser.add_argument('--batch', type=int, default=25)
    parser.add_argument('--mode', default='interp')
    o = parser.parse_args()
    modes = o.mode.split(',')
    harness.check(all(m in fuzz.MODES for m in modes), f'unknown mode in {o.mode}; modes: {", ".join(fuzz.MODES)}')
    harness.check(('native' in modes) == bool(o.native), '--native goes with --mode native, and only with it')
    harness.check(any(m != 'native' for m in modes) == bool(o.runtime), '--runtime goes with a JVM mode, and only with it')
    out = os.environ.get('TEST_UNDECLARED_OUTPUTS_DIR') or os.environ['TEST_TMPDIR']
    runtime = harness.Runtime.from_manifest(o.runtime) if o.runtime else None
    native = harness.rlocation(o.native) if o.native else None
    results, stats = fuzz.fuzz(harness.rlocation(o.chibi), runtime, o.seed, o.count, modes, batch=o.batch, jobs=4,
                               out=out, minimize=False, native=native)
    problems = [f'{m}: {s["chibi-error"]} files chibi-scheme failed, {s["skipped"]} skipped'
                for m, s in stats.items() if s['chibi-error'] or s['skipped']]
    problems += [f'{mode}: {path} differs:\n{description}' for mode, _, path, _, description in results]
    harness.check(not problems, '\n'.join(problems) + '\n(the programs are in the test\'s outputs: '
                  'bazel-testlogs/tests/fuzz/<test>/test.outputs)')
    harness.log(f'{o.count} programs the same under chibi-scheme and ours ({o.mode})')


if __name__ == '__main__':
    main()

"""//tests/fuzz:fuzz_smoke: a few hundred generated programs, from a fixed seed, under chibi-scheme
and under ours (interpreted): the same stdout and exit status, and chibi-scheme runs every one.
It keeps the generator and the driver working; the fuzzing itself is `bazel run //:r7rs -- fuzz`
(README.md). //tests/fuzz:fuzz_smoke_native runs them through the native launcher (`--native`,
`--mode native`).

The campaigns (//tests/fuzz:fuzz_campaign, README.md "Campaigns") run it too, sharded: each of the
test's shards (Bazel's TEST_SHARD_INDEX of TEST_TOTAL_SHARDS) runs its part of the --count programs
from --start, and minimises what differs (--minimize)."""

import argparse
import os

import fuzz
import harness


def shard(start, count, batch):
    """This shard's (start, count) of the programs start..start+count-1: whole files of `batch`
    programs, split evenly among Bazel's shards; all of them if the test isn't sharded."""
    total = int(os.environ.get('TEST_TOTAL_SHARDS', '1'))
    index = int(os.environ.get('TEST_SHARD_INDEX', '0'))
    status = os.environ.get('TEST_SHARD_STATUS_FILE')
    if status:
        open(status, 'w').close()  # this test knows about sharding
    files = -(-count // batch)
    harness.check(files >= total, f'{count} programs make {files} files of {batch}, fewer than the {total} shards')
    first, last = files * index // total, files * (index + 1) // total
    begin = start + first * batch
    return begin, min(start + count, start + last * batch) - begin


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--runtime', help='the JVM modes\' runtime (a scheme_runtime manifest)')
    parser.add_argument('--native', help='the native launcher, for --mode native')
    parser.add_argument('--chibi', required=True)
    parser.add_argument('--seed', type=int, required=True)
    parser.add_argument('--start', type=int, default=0)
    parser.add_argument('--count', type=int, required=True)
    parser.add_argument('--batch', type=int, default=25)
    parser.add_argument('--depth', type=int, default=5)
    parser.add_argument('--cases', type=int, default=8)
    parser.add_argument('--mode', default='interp')
    parser.add_argument('--timeout', type=int, default=fuzz.OURS_TIMEOUT, help='seconds ours may take for a file')
    parser.add_argument('--minimize', action='store_true', help='minimise the programs that differ')
    o = parser.parse_args()
    modes = o.mode.split(',')
    harness.check(all(m in fuzz.MODES for m in modes), f'unknown mode in {o.mode}; modes: {", ".join(fuzz.MODES)}')
    harness.check(('native' in modes) == bool(o.native), '--native goes with --mode native, and only with it')
    harness.check(any(m != 'native' for m in modes) == bool(o.runtime), '--runtime goes with a JVM mode, and only with it')
    out = os.environ.get('TEST_UNDECLARED_OUTPUTS_DIR') or os.environ['TEST_TMPDIR']
    runtime = harness.Runtime.from_manifest(o.runtime) if o.runtime else None
    native = harness.rlocation(o.native) if o.native else None
    start, count = shard(o.start, o.count, o.batch)
    options = f'--seed {o.seed} --start {start} --count {count} --batch {o.batch} --depth {o.depth} --cases {o.cases}'
    harness.log(f'fuzz {options} --mode {o.mode}')
    results, stats = fuzz.fuzz(harness.rlocation(o.chibi), runtime, o.seed, count, modes, start=start, batch=o.batch,
                               jobs=4, depth=o.depth, cases=o.cases, out=out, minimize=o.minimize, native=native,
                               timeout=o.timeout)
    problems = [f'{m}: {s["chibi-error"]} files chibi-scheme failed, {s["skipped"]} skipped'
                for m, s in stats.items() if s['chibi-error'] or s['skipped']]
    problems += [f'{mode}: {path} differs{f" (minimised: {reduced})" if reduced else ""}; again with '
                 f'`bazel run //:r7rs -- fuzz --seed {o.seed} --start {s} --count {o.batch} --batch {o.batch} '
                 f'--depth {o.depth} --cases {o.cases} --mode {mode}`:\n{description}'
                 for mode, s, path, reduced, description in results]
    harness.check(not problems, '\n'.join(problems) + '\n(the programs are in the test\'s outputs: '
                  'bazel-testlogs/tests/fuzz/<test>/[shard_n_of_m/]test.outputs, when they are downloaded)')
    harness.log(f'{count} programs the same under chibi-scheme and ours ({o.mode}, {options})')


if __name__ == '__main__':
    main()

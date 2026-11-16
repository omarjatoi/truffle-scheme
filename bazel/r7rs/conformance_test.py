"""The conformance suites (chibi's tests/r7rs and Larceny's tests/r7rs-larceny) as Bazel tests.

    conformance_test.py status <docs/plan.md> <chibi results.md> <larceny results.md>
        The status block in docs/plan.md must be the one the suites give, interpreted only
        (`mx tests conformance`). The suites' parts come from cached conformance_results
        actions, so editing docs/plan.md doesn't run the suites again.
        `bazel run //bazel/r7rs:status` rewrites the block.
    conformance_test.py torture --runtime <manifest> --suite chibi|larceny
        Every section of the suite passes at least as many tests under torture (capture and
        resume at every non-tail call) as it does normally (mx r7rs-torture's conformance part).
"""

import argparse
import concurrent.futures

import r7rs


def main():
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest='kind', required=True)
    s = sub.add_parser('status')
    s.add_argument('plan')
    s.add_argument('blocks', nargs='+')
    t = sub.add_parser('torture')
    t.add_argument('--runtime', required=True)
    t.add_argument('--suite', choices=['chibi', 'larceny'], required=True)
    args = parser.parse_args()

    if args.kind == 'status':
        block = r7rs.read_status_block(args.blocks)
        with open(r7rs.rlocation(args.plan), encoding='utf-8') as f:
            _, current = r7rs.replace_status_block(f.read(), block)
        r7rs.check(current == block, 'The status block in docs/plan.md is stale. Run `bazel run //bazel/r7rs:status` '
                   '(or `mx r7rs-status`) and commit the result.\nExpected:\n' + block)
        r7rs.log('status block in docs/plan.md is up to date')
        return

    runtime = r7rs.Runtime.from_manifest(args.runtime)
    suite = next(s for s in r7rs.suites() if s.name == args.suite)
    sections = suite.sections()
    # Normally and under torture at once, a JVM per section: as long as the slowest section takes.
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        normal_run = pool.submit(r7rs.run_conformance, runtime, sections, jobs=3)
        tortured_run = pool.submit(r7rs.run_conformance, runtime, sections, torture=True, jobs=3)
        normal, tortured = normal_run.result(), tortured_run.result()
    r7rs.log('normally:\n' + r7rs.summary(normal, sections) + '\nunder torture:\n' + r7rs.summary(tortured, sections))
    worse = []
    for s in sections:
        before, after = normal[(suite.name, s.id)]['pass'], tortured[(suite.name, s.id)]['pass']
        if after < before:
            worse.append(f'{suite.name} {s.id}: {before} normally, {after} under torture')
    r7rs.check(not worse, 'sections that pass less under torture:\n  ' + '\n  '.join(worse))
    r7rs.log(f'{args.suite}: every section passes as much under torture as normally')


if __name__ == '__main__':
    main()

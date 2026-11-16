"""Build action: run one conformance suite, interpreted only, and record the results.

    conformance_results.py --suite chibi|larceny --java <java> --module-path <jar>...
                           --json <out.json> --block <out.md>

Writes the suite's part of the docs/plan.md status block (<out.md>) and each section's passing
count (<out.json>). A build action, not a test, so its output is cached on its inputs alone (the
language modules and the suite): checking the block against docs/plan.md, which most PRs edit,
doesn't run the suites again.
"""

import argparse
import json
import os

import r7rs


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--suite', choices=['chibi', 'larceny'], required=True)
    parser.add_argument('--java', required=True)
    parser.add_argument('--module-path', nargs='+', required=True)
    parser.add_argument('--json', required=True)
    parser.add_argument('--block', required=True)
    args = parser.parse_args()
    # Inputs are at their execroot paths: tests/r7rs/..., the jars, the JDK.
    r7rs.set_repo_root(os.getcwd())
    runtime = r7rs.Runtime(os.path.abspath(args.java), [os.path.abspath(p) for p in args.module_path])
    suite = next(s for s in r7rs.suites() if s.name == args.suite)
    sections = suite.sections()
    # A JVM per section, four at a time; a section that times out among them runs again alone
    # (r7rs.run_conformance), which matters here because this result is cached.
    results = r7rs.run_conformance(runtime, sections, jobs=4)
    with open(args.block, 'w', encoding='utf-8') as f:
        f.write(r7rs.suite_block(suite, results) + '\n')
    with open(args.json, 'w', encoding='utf-8') as f:
        json.dump({'passing': r7rs.pass_counts(results), 'summary': r7rs.summary(results, sections).splitlines()}, f, indent=1)
        f.write('\n')


if __name__ == '__main__':
    main()

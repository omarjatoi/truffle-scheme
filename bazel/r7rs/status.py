"""`bazel run //bazel/r7rs:status`: rewrite the status block in docs/plan.md (mx r7rs-status).

    status.py <chibi results.md> <larceny results.md>

The suites' parts come from the cached conformance_results actions (//tests:conformance_*), so
this runs a suite only when the language or that suite changed.
"""

import os
import sys

import r7rs


def main():
    workspace = os.environ.get('BUILD_WORKSPACE_DIRECTORY')
    if not workspace:
        r7rs.fail('run this with `bazel run //bazel/r7rs:status`')
    block = r7rs.read_status_block(sys.argv[1:])
    plan = os.path.join(workspace, 'docs', 'plan.md')
    with open(plan, encoding='utf-8') as f:
        old = f.read()
    new, _ = r7rs.replace_status_block(old, block)
    if new == old:
        r7rs.log('status block already up to date')
        return
    with open(plan, 'w', encoding='utf-8') as f:
        f.write(new)
    r7rs.log('rewrote the status block in docs/plan.md')


if __name__ == '__main__':
    main()

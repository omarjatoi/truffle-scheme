"""The repository's commands: `bazel run //:r7rs -- <command> ...` (AGENTS.md "Commands").

  test [section] [--suite S] [--compile] [--torture|--torture-owned] [--native FILE] [-v] [--markdown FILE]
                     the conformance suites, per section (ad hoc; the checks are the tests)
  conformance-report [--markdown FILE]
                     the conformance tests' last results, a table per suite
  status [--check]   rewrite (or check) the status block in docs/plan.md
  diff FILE [--compile] [--torture|--torture-owned]
                     a program under chibi-scheme and under ours: same stdout and exit status?
  format             the Eclipse formatter over src/ and tests/
  fuzz [--seed N] [--count N] [--mode interp,compile,torture,torture-owned] ...
                     random programs under chibi-scheme and under ours; minimises and saves any that
                     differ (tests/fuzz/README.md)
  tools NAME         one of the tools' smoke tests (tools.py), e.g. terminal
  benchmark, bench-report, bench-check, vendor-benchmarks   bench.py
  vendor-chibi, vendor-larceny                              vendor.py

The tests aren't commands: those in Python have their own entry scripts (check_*.py), and
tests/driver runs the others in Java.

The options before the command name the runfiles of the runtime and tools it uses; the targets that
run this pass them.
"""

import argparse
import difflib
import json
import os
import subprocess
import sys
from os.path import exists, join

import bench
import check_tools
import fuzz
import harness
import tools
import vendor
from harness import Runtime, check, fail, log, rlocation, tail


def resolve(path):
    """A file named on the command line (main() runs in the directory `bazel run` ran in), or a
    runfiles path for a test."""
    if os.path.isabs(path) or 'BUILD_WORKING_DIRECTORY' in os.environ:
        return os.path.abspath(path)
    return rlocation(path)


# ---------------------------------------------------------------------------
# Tests
# ---------------------------------------------------------------------------

def _diff_text(expected, actual, from_name, to_name):
    lines = list(difflib.unified_diff(expected.splitlines(keepends=True), actual.splitlines(keepends=True),
                                      fromfile=from_name, tofile=to_name, n=1))
    return ''.join(l if len(l) < 200 else l[:200] + '...\n' for l in lines[:40]).rstrip('\n')


def diff(o):
    """A program under chibi-scheme and under ours, interpreted (and under torture with --torture or
    --torture-owned): stdout and exit status must be the same. The tests are tests/driver's Diff."""
    file = resolve(o.file)
    check(exists(file), f'no such file: {file}')
    chibi_rc, chibi_out, chibi_err = harness.run_chibi(rlocation(o.chibi), file)
    ours_rc, ours_out, ours_err = harness.run_ours(Runtime.from_manifest(o.runtime), file,
                                                   harness.vm_args(compile=o.compile, torture=o.torture))
    if chibi_rc == ours_rc and chibi_out == ours_out:
        log(f'same (exit {ours_rc}, {len(ours_out)} chars of stdout): {o.file}')
        return
    message = [f'DIFFERENT from chibi-scheme: {o.file}']
    if chibi_out != ours_out:
        message.append(_diff_text(chibi_out, ours_out, 'chibi-scheme stdout', 'scheme stdout'))
        message.append(f'  chibi-scheme: {chibi_out[-500:]!r}\n  scheme:       {ours_out[-500:]!r}')
    message.append(f'exit status: chibi-scheme {chibi_rc}, scheme {ours_rc}')
    message.append('chibi-scheme stderr (tail):\n' + tail(chibi_err))
    message.append('scheme stderr (tail):\n' + tail(ours_err))
    fail('\n'.join(message))


# The conformance tests' modes (tests/harness/defs.bzl conformance_tests).
_MODES = ('interpreter', 'compile', 'torture', 'torture_owned')


def conformance_report(o):
    """The conformance tests' results (their result.json, from bazel-testlogs) as a table per suite:
    a row per section, a column per mode."""
    import zipfile
    testlogs = join(harness.workspace(), 'bazel-testlogs', 'tests')
    results = {}
    for name in sorted(os.listdir(testlogs)) if os.path.isdir(testlogs) else []:
        if not name.startswith('conformance_'):
            continue
        outputs = join(testlogs, name, 'test.outputs')
        data = None
        if os.path.isfile(join(outputs, 'outputs.zip')):
            with zipfile.ZipFile(join(outputs, 'outputs.zip')) as z:
                if 'result.json' in z.namelist():
                    data = json.loads(z.read('result.json'))
        elif os.path.isfile(join(outputs, 'result.json')):
            with open(join(outputs, 'result.json'), encoding='utf-8') as f:
                data = json.load(f)
        if data:
            results[(data['suite'], data['section'], data['mode'])] = data
    lines = []
    for suite in harness.suites():
        sections = suite.sections()
        totals = {mode: sum(results[(suite.name, s.id, mode)]['pass'] for s in sections if (suite.name, s.id, mode) in results)
                  for mode in _MODES}
        lines += [f'### {suite.heading}', '',
                  f'| {suite.column} | Tests | ' + ' | '.join(_MODES) + ' |', '|---|---:|' + '---:|' * len(_MODES)]
        for s in sections:
            cells = []
            for mode in _MODES:
                r = results.get((suite.name, s.id, mode))
                if r is None:
                    cells.append('')
                else:
                    mark = '' if r['pass'] == r['expected'] and r['complete'] else ' ❌'
                    cells.append(f'{r["pass"]}{mark}')
            lines.append(f'| {s.title} | {s.total} | ' + ' | '.join(cells) + ' |')
        lines.append(f'| **Total** | **{sum(s.total for s in sections)}** | ' + ' | '.join(f'**{totals[m]}**' for m in _MODES) + ' |')
        lines.append('')
    text = '\n'.join(lines) + '\n'
    if o.markdown:
        with open(resolve(o.markdown), 'w', encoding='utf-8') as f:
            f.write(text)
    else:
        print(text)


# ---------------------------------------------------------------------------
# Commands
# ---------------------------------------------------------------------------

def test(o):
    """The conformance suites, with a line per section (and its failing tests with -v)."""
    all_suites = harness.suites(o.suite)
    sections = [s for suite in all_suites for s in suite.sections()]
    if o.section:
        sections = [s for s in sections if s.id == o.section]
        check(sections, f'unknown section {o.section}; sections: ' + ' '.join(s.id for suite in all_suites for s in suite.sections()))
    native = resolve(o.native) if o.native else None
    results = harness.run_conformance(Runtime.from_manifest(o.runtime), sections, compile=o.compile, torture=o.torture,
                                      jobs=1 if o.section else 4, native=native)
    mode = ', '.join(m for m, on in (('--compile', o.compile), ('--torture', o.torture is True), ('--torture-owned', o.torture == 'owned'))
                     if on) or 'interpreter only'
    mode += ', native launcher' if native else ''
    markdown = []
    for suite in all_suites:
        mine = [s for s in sections if s.suite is suite]
        if not mine:
            continue
        log(f'{suite.name}:')
        total_pass = total = 0
        width = max(len(s.id) for s in mine)
        for s in mine:
            r = results[(suite.name, s.id)]
            total_pass += r['pass']
            total += s.total
            note = '' if r['complete'] else '  (aborted: ' + r['message'] + ')'
            note += '  (compiled: depends on speed)' if s.jit and not o.compile else ''
            log(f'{s.id:<{width}} {r["pass"]:>4}/{s.total:<4} {s.title}{note}')
            if o.verbose:
                marks = iter(harness.listed_exceptions(suite, s, r['output']))
                for line in r['output'].splitlines():
                    if line.startswith(('@@r7rs-test FAIL', '@@r7rs-test ERROR')):
                        log('      ' + line[len('@@r7rs-test '):] + ('  (listed exception)' if next(marks) else ''))
        log(f'{"TOTAL":<{width}} {total_pass:>4}/{total}')
        markdown += [f'### {suite.heading} ({mode}): {total_pass}/{total}', '',
                     f'| {suite.column} | Passing | Tests | Note |', '|---|---:|---:|---|']
        for s in mine:
            r = results[(suite.name, s.id)]
            notes = [] if r['complete'] else ['aborted: ' + r['message'].replace('|', '\\|')]
            exceptions = sum(harness.listed_exceptions(suite, s, r['output']))
            notes += [f'{exceptions} listed exceptions'] if exceptions else []
            notes += ['compiled: depends on speed'] if s.jit and not o.compile else []
            markdown.append(f'| {s.title} | {r["pass"]} | {s.total} | {"; ".join(notes)} |')
        markdown.append('')
    if o.markdown:
        with open(resolve(o.markdown), 'a', encoding='utf-8') as f:
            f.write('\n'.join(markdown) + '\n')


def status(o):
    """Rewrites the status block in docs/plan.md from what the conformance tests check (the
    suites' totals and listed exceptions); with --check, fails if it differs."""
    plan = join(harness.workspace(), 'docs', 'plan.md')
    with open(plan, encoding='utf-8') as f:
        text = f.read()
    block = harness.expected_status_block()
    new, current = harness.replace_status_block(text, block)
    if o.check:
        check(current == block, 'The status block in docs/plan.md differs from what the suites give:\n' + block)
        log('status block in docs/plan.md is up to date')
    elif new != text:
        with open(plan, 'w', encoding='utf-8') as f:
            f.write(new)
        log('rewrote the status block in docs/plan.md')
    else:
        log('status block already up to date')


def format_sources(o):
    """The Eclipse formatter over every .java file under src/ and tests/, in place."""
    root = harness.workspace()
    files = [join(d, n) for top in ('src', 'tests') for d, _, names in os.walk(join(root, top)) for n in sorted(names)
             if n.endswith('.java')]
    sys.exit(subprocess.run([rlocation(o.formatter), '--fix', rlocation(o.prefs)] + files).returncode)


def main():
    # Runfiles the targets pass, before or after the command name.
    common = argparse.ArgumentParser(add_help=False)
    for option in ('--runtime', '--tools-runtime', '--chibi', '--formatter', '--prefs'):
        common.add_argument(option, default=argparse.SUPPRESS)
    parser = argparse.ArgumentParser(prog='bazel run //:r7rs --', description=__doc__.split('\n\n')[0], parents=[common])
    sub = parser.add_subparsers(dest='command', required=True)
    add = sub.add_parser

    def sub_parser(name, **kwargs):
        return add(name, parents=[common], **kwargs)

    sub.add_parser = sub_parser

    p = sub.add_parser('diff')
    p.add_argument('file')
    p.add_argument('--compile', action='store_true')
    p.add_argument('--torture', action='store_true')
    p.add_argument('--torture-owned', dest='torture', action='store_const', const='owned', default=False)
    p = sub.add_parser('tools')
    p.add_argument('--programs', help='a program in tests/tools (default: the checkout\'s)')
    p.add_argument('name', choices=list(tools.SMOKE_TESTS))

    p = sub.add_parser('test', help='the conformance suites, per section')
    p.add_argument('section', nargs='?', help="one section, e.g. 6.10 (chibi's) or base (Larceny's)")
    p.add_argument('--suite', choices=['all', 'chibi', 'larceny'], default='all')
    p.add_argument('--compile', action='store_true', help='compile immediately; compilation failures are errors')
    p.add_argument('--torture', action='store_true', help='capture + resume at every non-tail call')
    p.add_argument('--torture-owned', dest='torture', action='store_const', const='owned', default=False,
                   help='the same, but the captured frames stay owned, resumed in place (design.md "Owned segments")')
    p.add_argument('--native', metavar='FILE', help='through this native launcher (native-image)')
    p.add_argument('-v', '--verbose', action='store_true', help='also list failing tests')
    p.add_argument('--markdown', metavar='FILE', help='append a Markdown table to FILE')
    p = sub.add_parser('conformance-report', help="the conformance tests' results, a table per suite")
    p.add_argument('--markdown', metavar='FILE', help='write it here')
    p = sub.add_parser('status', help='rewrite the status block in docs/plan.md')
    p.add_argument('--check', action='store_true')
    sub.add_parser('format', help='the Eclipse formatter over src/ and tests/')
    fuzz.main_args(sub.add_parser('fuzz', help='random programs under chibi-scheme and under ours'))
    for name in ('benchmark', 'bench-report', 'bench-check', 'vendor-benchmarks', 'vendor-chibi', 'vendor-larceny'):
        add(name, add_help=False)

    o, rest = parser.parse_known_args()
    for option in ('runtime', 'tools_runtime', 'chibi', 'formatter', 'prefs'):
        setattr(o, option, getattr(o, option, None))  # SUPPRESS'd when not given (parents share actions)
    # Under `bazel run`, file names on the command line are relative to where it ran.
    os.environ['RUNFILES_DIR'] = os.path.abspath(harness.runfiles_dir())
    if 'BUILD_WORKING_DIRECTORY' in os.environ:
        os.chdir(os.environ['BUILD_WORKING_DIRECTORY'])
    if rest and o.command not in ('benchmark', 'bench-report', 'bench-check', 'vendor-benchmarks',
                                  'vendor-chibi', 'vendor-larceny'):
        parser.error(f'unrecognized arguments: {" ".join(rest)}')
    if o.runtime:
        bench.RUNTIME[:] = vendor.RUNTIME[:] = [Runtime.from_manifest(o.runtime)]
    if o.chibi:
        vendor.CHIBI[:] = [rlocation(o.chibi)]
    commands = {
        'diff': diff, 'conformance-report': conformance_report, 'tools': check_tools.tools_test, 'test': test,
        'status': status, 'format': format_sources,
        'fuzz': lambda o: fuzz.run_command(o, rlocation(o.chibi), Runtime.from_manifest(o.tools_runtime)),
    }
    if o.command in commands:
        commands[o.command](o)
    else:
        {'benchmark': bench.benchmark, 'bench-report': bench.r7rs_bench_report, 'bench-check': bench.r7rs_bench_check,
         'vendor-benchmarks': bench.r7rs_vendor_benchmarks, 'vendor-chibi': vendor.r7rs_vendor_chibi,
         'vendor-larceny': vendor.r7rs_vendor_larceny}[o.command](rest)


if __name__ == '__main__':
    main()

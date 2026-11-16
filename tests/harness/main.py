"""The test drivers and the repository's commands, in one program.

As commands, `bazel run //:r7rs -- <command> ...` (AGENTS.md "Commands"):

  test [section] [--suite S] [--compile] [--torture] [--native FILE] [-v] [--markdown FILE]
                     the conformance suites, per section (ad hoc; the checks are the tests)
  conformance-report [--markdown FILE]
                     the conformance tests' last results, a table per suite
  status [--check]   rewrite (or check) the status block in docs/plan.md
  diff FILE [--compile] [--torture]
                     a program under chibi-scheme and under ours: same stdout and exit status?
  format             the Eclipse formatter over src/ and tests/
  tools NAME         one of the tools' smoke tests (tools.py), e.g. terminal
  benchmark, bench-report, bench-check, vendor-benchmarks   bench.py
  vendor-chibi, vendor-larceny                              vendor.py

As Bazel tests (tests/harness/defs.bzl, bazel/lint/defs.bzl): program, conformance,
conformance-status, conformance-native, readme, tools, rules, versions, lint.

The options before the command name the runfiles of the runtime and tools it uses; the targets that
run this pass them.
"""

import argparse
import difflib
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time
import zipfile
from os.path import exists, join

import bench
import harness
import readme
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
    """A program under chibi-scheme and under ours, interpreted (and under torture with --torture):
    stdout and exit status must be the same. The tests take chibi-scheme's from a build action
    (--chibi-out/-err/-status); `bazel run //:r7rs -- diff FILE` runs it."""
    file = resolve(o.file)
    check(exists(file), f'no such file: {file}')
    if o.chibi_out:
        chibi = {}
        for part in ('out', 'err', 'status'):
            with open(rlocation(getattr(o, 'chibi_' + part)), encoding='utf-8', errors='replace') as f:
                chibi[part] = f.read()
        chibi_rc, chibi_out, chibi_err = int(chibi['status']), chibi['out'], chibi['err']
    else:
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


_SPEC_HEADER = re.compile(r'^;;\s*(limits|exit):\s*(.*?)\s*$')


def _expect(runtime, file, limits, status, expected):
    """Runs the program interpreted and with the JIT under the JVM limits; both runs must match."""
    problems = []
    for label, mode in (('interpreter', harness.vm_args()), ('JIT', [])):
        start = time.time()
        rc, out, err = harness.run_ours(runtime, file, mode, limits)
        seconds = time.time() - start
        if rc != status or out != expected:
            detail = f'exit {rc} (expected {status})' if rc != status else 'stdout differs'
            if out != expected:
                detail += '\n' + _diff_text(expected, out, 'expected', 'actual')
            problems.append(f'{label} ({seconds:.1f}s): {detail}\n  stderr:\n{tail(err)}')
        else:
            log(f'{os.path.basename(file)}: {label} ok ({seconds:.1f}s, {" ".join(limits)})')
    check(not problems, f'FAIL {file} {" ".join(limits)}\n  ' + '\n  '.join(problems))


def program(o):
    """spec: a tests/spec case, with its `;; limits:` and `;; exit:` headers and its .out file
    (tests/spec/README.md). space: one line of stdout expected under the given JVM limits."""
    runtime = Runtime.from_manifest(o.runtime)
    file = rlocation(o.file)
    if o.kind == 'space':
        _expect(runtime, file, o.limits.split(), 0, o.expect + '\n')
        return
    limits, status = [], 0
    with open(file, encoding='utf-8') as f:
        for line in f:
            if not line.startswith(';;'):
                break
            m = _SPEC_HEADER.match(line)
            if m and m.group(1) == 'limits':
                limits = m.group(2).split()
            elif m:
                status = int(m.group(2))
    with open(file[:-len('.scm')] + '.out', encoding='utf-8', newline='') as f:
        _expect(runtime, file, limits, status, f.read())


def _expected_results(suite):
    """What each section of a suite gives when its tests pass: its total, but for the tests the
    suite lists as exceptions (tests/r7rs-larceny/README.md), which fail."""
    exceptions = suite.exceptions()
    return {(suite.name, s.id): {'pass': s.total - len(exceptions.get(s.id, [])), 'exceptions': len(exceptions.get(s.id, [])),
                                 'complete': True, 'message': 'ok'}
            for s in suite.sections()}


def expected_status_block():
    """docs/plan.md's status block: what the conformance tests check, when they all pass."""
    return harness.status_block([harness.suite_block(suite, _expected_results(suite)) for suite in harness.suites()])


_MODES = {'interpreter': {}, 'compile': {'compile': True}, 'torture': {'torture': True}}


def conformance_test(o):
    """Test: one section of a suite, interpreted, compiled or under torture, passes every test but
    the ones the suite lists as exceptions, and those fail. Writes its counts to result.json among
    the test's outputs (conformance-report reads them)."""
    runtime = Runtime.from_manifest(o.runtime)
    suite = harness.suites(o.suite)[0]
    sections = [s for s in suite.sections() if s.id == o.section]
    check(sections, f'{o.suite} has no section {o.section}')
    section = sections[0]
    r = harness.run_conformance(runtime, [section], **_MODES[o.mode])[(suite.name, section.id)]
    listed = suite.exceptions().get(section.id, [])
    unexpected = list(harness.failing_tests(r['output']))
    now_passing = []
    for test in listed:
        if test in unexpected:
            unexpected.remove(test)
        else:
            now_passing.append(test)
    expected = section.total - len(listed)
    result = {'suite': suite.name, 'section': section.id, 'title': section.title, 'mode': o.mode, 'pass': r['pass'],
              'total': section.total, 'expected': expected, 'complete': r['complete'], 'message': r['message']}
    outputs = os.environ.get('TEST_UNDECLARED_OUTPUTS_DIR')
    if outputs:
        with open(join(outputs, 'result.json'), 'w', encoding='utf-8') as f:
            json.dump(result, f, indent=1, sort_keys=True)
    log(f'{suite.name} {section.id} ({o.mode}): {r["pass"]}/{section.total}, {expected} expected')
    problems = []
    if not r['complete']:
        problems.append(f'aborted: {r["message"]}')
    if unexpected:
        problems.append('these tests fail, and the suite doesn\'t list them as exceptions:\n    ' + '\n    '.join(unexpected))
    if now_passing:
        problems.append(f'these tests pass now, but {suite.rel_dir}/README.md lists them as exceptions (expected '
                        'failures): remove their rows from its Exceptions table\n    ' + '\n    '.join(now_passing))
    if r['pass'] != expected and not problems:
        problems.append(f'{r["pass"]} tests pass, {expected} expected')
    check(not problems, f'{suite.name} {section.id} ({o.mode}): ' + '\n'.join(problems) + '\n\n' + tail(r['output'], 40))


def conformance_status(o):
    """Test: docs/plan.md's status block says what the conformance tests check."""
    block = expected_status_block()
    with open(rlocation(o.plan), encoding='utf-8') as f:
        _, current = harness.replace_status_block(f.read(), block)
    check(current == block, 'The status block in docs/plan.md is stale. Run `bazel run //:r7rs -- status` and commit '
          'the result.\nExpected:\n' + block)
    log('status block in docs/plan.md is up to date')


def conformance_report(o):
    """The conformance tests' results (their result.json, from bazel-testlogs) as a table per suite:
    a row per section, a column per mode. CI's annotation."""
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


def tools_test(o):
    """A smoke test of GraalVM's tools against the launcher (tools.py), with and without the tools jars."""
    bare, with_tools = Runtime.from_manifest(o.runtime), Runtime.from_manifest(o.tools_runtime)
    commands = tools.Commands(tools=lambda args: with_tools.launcher(args), bare=lambda args: bare.launcher(args))
    programs = os.path.dirname(rlocation(o.programs)) if o.programs else join(harness.repo_root(), 'tests', 'tools')
    failed = tools.run([o.name], programs, commands)
    check(not failed, f'tools smoke test failed: {o.name}')


# AGENTS.md rule 1: Java builtins never call Scheme; they return a TailCall request (design.md D5).
_RULE1_FORBIDDEN = [
    (re.compile(r'\b(Direct|Indirect)CallNode\b'), 'call nodes'),
    (re.compile(r'\b\w*CallTarget\b'), 'call targets'),
    (re.compile(r'\bInteropLibrary\b'), 'interop (can execute Scheme procedures)'),
]


def rules(o):
    violations = []
    for path in o.files:
        with open(rlocation(path), encoding='utf-8') as f:
            for lineno, line in enumerate(f, 1):
                code = line.split('//', 1)[0]
                violations += [f'{path.removeprefix("_main/")}:{lineno}: uses {what}: {line.strip()}'
                               for pattern, what in _RULE1_FORBIDDEN if pattern.search(code)]
    check(o.files and not violations, 'Rule 1 violated: Java builtins never call Scheme; return a TailCall request instead '
          '(AGENTS.md rule 1, design.md D5).\n' + '\n'.join(violations))
    log(f'rule 1 OK ({len(o.files)} files)')


def versions(o):
    """design.md D11: the JDK the tests run on is the GraalVM release of the Truffle libraries."""
    runtime = Runtime.from_manifest(o.runtime)
    graalvm = bench.graalvm_version(runtime.release)
    with open(rlocation(o.lock), encoding='utf-8') as f:
        lock = {coordinate: a['version'] for coordinate, a in json.load(f)['artifacts'].items()}
    problems = [f'{c} is {v}, the JDK GraalVM {graalvm}' for c, v in sorted(lock.items())
                if c.startswith('org.graalvm.') and v != graalvm]
    check(not problems, 'GraalVM and Truffle versions differ (design.md D11):\n  ' + '\n  '.join(problems)
          + '\nUpgrade them together: bazel/graalvm.bzl, TRUFFLE_VERSION in MODULE.bazel, `bazel run @jvm//:pin`.')
    log(f'GraalVM {graalvm} = every org.graalvm artifact in the lock file')


def lint(o):
    """One static check of one java_library (bazel/lint/defs.bzl): format, checkstyle, ecj or spotbugs."""
    with open(rlocation(o.inputs), encoding='utf-8') as f:
        inputs = json.load(f)

    def paths(key):
        return [rlocation(p) for p in inputs[key]]

    tmp = tempfile.mkdtemp(dir=os.environ.get('TEST_TMPDIR'))
    tool = rlocation(o.tool)
    if o.check == 'format':
        command = [tool, '--check', rlocation(o.prefs)] + paths('srcs')
    elif o.check == 'checkstyle':
        command = [tool, '-c', rlocation(o.config)] + paths('srcs')
    elif o.check == 'ecj':
        # GraalVM's JDT settings, -failOnWarning; with the Truffle DSL processor, warnings are
        # ignored (ECJ can't ignore them in generated code only).
        with open(rlocation(o.prefs), encoding='utf-8') as f:
            prefs = f.read()
        processor_path = paths('processor_path')
        if processor_path:
            prefs = prefs.replace('=warning', '=ignore')
        with open(join(tmp, 'jdt.prefs'), 'w', encoding='utf-8') as f:
            f.write(prefs + '\norg.eclipse.jdt.core.compiler.problem.tasks=ignore\n')
        command = [tool, '-g', '-d', join(tmp, 'classes'), '-classpath', os.pathsep.join(paths('classpath')), '-parameters',
                   '--release', '25', '-encoding', 'UTF-8', '-failOnWarning', '-properties', join(tmp, 'jdt.prefs')]
        command += (['-processorpath', os.pathsep.join(processor_path), '-s', join(tmp, 'generated')] if processor_path
                    else ['-proc:none'])
        os.makedirs(join(tmp, 'generated'))
        command += paths('srcs')
    else:
        # SpotBugs as GraalVM runs it: -low -maxRank 15, two known false positives off, and no
        # reports on the classes the Truffle DSL processor generated.
        class_jars = paths('class_jars')
        if not [n for jar in class_jars for n in zipfile.ZipFile(jar).namelist() if n.endswith('.class') and '-info' not in n]:
            log('no classes to analyze')  # scheme.prelude is resources and a package-info
            return
        generated = sorted({n[:-len('.java')].replace('/', '.') for jar in paths('generated_sources')
                            for n in zipfile.ZipFile(jar).namelist() if n.endswith('.java') and '-info' not in n})
        exclude = join(tmp, 'exclude.xml')
        with open(exclude, 'w', encoding='utf-8') as f:
            f.write('<FindBugsFilter>\n'
                    + ''.join(f'  <Match><Bug pattern="{p}"/></Match>\n' for p in
                              ('RCN_REDUNDANT_NULLCHECK_WOULD_HAVE_BEEN_A_NPE', 'ASE_ASSERTION_WITH_SIDE_EFFECT',
                               'ASE_ASSERTION_WITH_SIDE_EFFECT_METHOD'))
                    + ''.join(f'  <Match><Class name="~{c.replace(".", "[.]")}.*"/></Match>\n' for c in generated)
                    + '</FindBugsFilter>\n')
        aux = [p for p in paths('runtime_classpath') if p not in class_jars]
        command = [tool, '-textui', '-low', '-maxRank', '15', '-exclude', exclude, '-auxclasspath', os.pathsep.join(aux),
                   '-exitcode'] + class_jars
    sys.exit(subprocess.run(command).returncode)


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
    mode = ', '.join(m for m, on in (('--compile', o.compile), ('--torture', o.torture)) if on) or 'interpreter only'
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
    block = expected_status_block()
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


def _shim(directory, name, verb):
    """`name verb ...` succeeds and does nothing: the readme test depends on what it would build."""
    path = join(directory, name)
    with open(path, 'w', encoding='utf-8') as f:
        f.write(f'#!/bin/sh\n[ "$1" = {verb} ] && exit 0\n'
                f'echo "the readme test runs only \\`{name} {verb}\\`, not: {name} $*" >&2\nexit 1\n')
    os.chmod(path, 0o755)


def readme_test(o):
    """Test: docs/usage.md's and docs/embedding.md's blocks (readme.py), the commands as written, in
    a directory laid out like the checkout: `bazel-bin/` holds the targets this test depends on,
    `bazel build` and `nix develop` do nothing (Bazel built those first), and `java` is the pinned
    GraalVM's. With --native, only the native-image blocks, against that launcher."""
    tmp = os.environ['TEST_TMPDIR']
    main_repo = os.path.dirname(os.path.dirname(rlocation(o.usage)))
    checkout, shims = join(tmp, 'checkout'), join(tmp, 'shims')
    os.makedirs(join(checkout, 'bazel-bin'))
    os.makedirs(shims)
    # Copies, not runfiles symlinks: the profilers print a program's path relative to its real one.
    shutil.copytree(join(main_repo, 'docs'), join(checkout, 'docs'))
    os.symlink(join(main_repo, 'tests'), join(checkout, 'tests'))
    os.symlink(rlocation(o.scheme), join(checkout, 'bazel-bin', 'scheme'))
    os.symlink(os.path.dirname(rlocation(o.jars[0])), join(checkout, 'bazel-bin', 'scheme_jars'))
    if o.native:
        os.symlink(rlocation(o.native), join(checkout, 'bazel-bin', 'scheme-native'))
    _shim(shims, 'bazel', 'build')
    _shim(shims, 'nix', 'develop')
    os.symlink(Runtime.from_manifest(o.runtime).java, join(shims, 'java'))
    os.environ['PATH'] = shims + os.pathsep + os.environ.get('PATH', '')
    problems = readme.run(checkout, native=bool(o.native))
    check(not problems, 'readme: failed:\n' + '\n'.join(problems))
    log('readme: ' + ('the native blocks run' if o.native else 'every block is checked, and every command runs as documented'))


def conformance_native(o):
    """Test: both suites through the native launcher pass what docs/plan.md's status block says."""
    all_suites = harness.suites()
    sections = [s for suite in all_suites for s in suite.sections()]
    results = harness.run_conformance(None, sections, native=rlocation(o.native))
    block = harness.status_block([harness.suite_block(suite, results) for suite in all_suites])
    check(block == expected_status_block(), 'Through the native launcher, the suites give a different status block:\n' + block)
    log('the native launcher passes what docs/plan.md says')


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
    for part in ('out', 'err', 'status'):
        p.add_argument('--chibi-' + part, help=f"chibi-scheme's {part}, from its build action (tests)")
    p.add_argument('--compile', action='store_true')
    p.add_argument('--torture', action='store_true')
    p = sub.add_parser('program')
    p.add_argument('kind', choices=['spec', 'space'])
    p.add_argument('file')
    p.add_argument('--expect')
    p.add_argument('--limits')
    p = sub.add_parser('conformance')
    p.add_argument('--suite', choices=['chibi', 'larceny'], required=True)
    p.add_argument('--section', required=True)
    p.add_argument('--mode', choices=list(_MODES), required=True)
    p = sub.add_parser('conformance-status')
    p.add_argument('plan')
    p = sub.add_parser('conformance-native')
    p.add_argument('native')
    p = sub.add_parser('readme')
    p.add_argument('--usage', required=True)
    p.add_argument('--scheme', required=True)
    p.add_argument('--jars', nargs='+', required=True, help="bazel-bin/scheme_jars' jars")
    p.add_argument('--native', help='only the native-image blocks, against this launcher')
    p = sub.add_parser('tools')
    p.add_argument('--programs', help='a program in tests/tools (default: the checkout\'s)')
    p.add_argument('name', choices=list(tools.SMOKE_TESTS))
    p = sub.add_parser('rules')
    p.add_argument('files', nargs='*')
    p = sub.add_parser('versions')
    p.add_argument('--lock', required=True)
    p = sub.add_parser('lint')
    p.add_argument('check', choices=['format', 'checkstyle', 'ecj', 'spotbugs'])
    for option in ('--inputs', '--tool', '--config'):
        p.add_argument(option)

    p = sub.add_parser('test', help='the conformance suites, per section')
    p.add_argument('section', nargs='?', help="one section, e.g. 6.10 (chibi's) or base (Larceny's)")
    p.add_argument('--suite', choices=['all', 'chibi', 'larceny'], default='all')
    p.add_argument('--compile', action='store_true', help='compile immediately; compilation failures are errors')
    p.add_argument('--torture', action='store_true', help='capture + resume at every non-tail call')
    p.add_argument('--native', metavar='FILE', help='through this native launcher (native-image)')
    p.add_argument('-v', '--verbose', action='store_true', help='also list failing tests')
    p.add_argument('--markdown', metavar='FILE', help='append a Markdown table to FILE (e.g. a CI summary)')
    p = sub.add_parser('conformance-report', help="the conformance tests' results, a table per suite")
    p.add_argument('--markdown', metavar='FILE', help='write it here (CI: the annotation)')
    p = sub.add_parser('status', help='rewrite the status block in docs/plan.md')
    p.add_argument('--check', action='store_true')
    sub.add_parser('format', help='the Eclipse formatter over src/ and tests/')
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
        'diff': diff, 'program': program, 'conformance': conformance_test,
        'conformance-status': conformance_status, 'conformance-report': conformance_report, 'tools': tools_test, 'rules': rules,
        'versions': versions, 'lint': lint, 'test': test, 'status': status, 'format': format_sources,
        'readme': readme_test, 'conformance-native': conformance_native,
    }
    if o.command in commands:
        commands[o.command](o)
    else:
        {'benchmark': bench.benchmark, 'bench-report': bench.r7rs_bench_report, 'bench-check': bench.r7rs_bench_check,
         'vendor-benchmarks': bench.r7rs_vendor_benchmarks, 'vendor-chibi': vendor.r7rs_vendor_chibi,
         'vendor-larceny': vendor.r7rs_vendor_larceny}[o.command](rest)


if __name__ == '__main__':
    main()

"""The test drivers and the repository's commands, in one program.

As commands, `bazel run //:r7rs -- <command> ...` (AGENTS.md "Commands"):

  test [section] [--suite S] [--compile] [--torture] [--native FILE] [-v] [--markdown FILE]
                     the conformance suites, per section
  status [--check] [--native FILE]
                     rewrite (or check) the status block in docs/plan.md
  diff FILE [--compile] [--torture]
                     a program under chibi-scheme and under ours: same stdout and exit status?
  format             the Eclipse formatter over src/ and tests/
  readme [--native]  run docs/usage.md's and docs/embedding.md's commands, check their samples
  tools NAME         one of the tools' smoke tests (tools.py), e.g. terminal
  native-image [--output FILE] [native-image option...]
                     the launcher as a native executable (minutes, ~3 GB)
  benchmark, bench-report, bench-check, vendor-benchmarks   bench.py
  vendor-chibi, vendor-larceny                              vendor.py

As Bazel tests and build actions (tests/harness/defs.bzl, bazel/lint/defs.bzl): unit, program,
conformance-results, conformance-status, torture, tools, rules, versions, lint.

The options before the command name the runfiles of the runtime and tools it uses; the targets that
run this pass them.
"""

import argparse
import concurrent.futures
import difflib
import json
import os
import platform
import re
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

UNIT_MODES = {
    'jit': [],  # the Graal JIT in its normal background mode
    'compile': harness.COMPILE_VM_ARGS,  # compile immediately; compilation failures are errors
    'torture': harness.TORTURE_VM_ARGS,  # capture + resume at every non-tail call
}


def unit(o):
    """One JUnit class in its own JVM, with the scheme modules, Truffle and JUnit on the module path.
    JUnit 4 runs as the automatic module `junit`, which scheme.test exports and opens its packages to.
    UpstreamReportPolymorphismTest runs javac with the Truffle DSL processor (docs/upstream.md)."""
    runtime = Runtime.from_manifest(o.runtime)
    command = runtime.command(['-ea', '-esa', f'-Dr7rs.truffle.dslProcessor={rlocation(o.processor)}'] + UNIT_MODES[o.mode],
                              'junit/org.junit.runner.JUnitCore') + [o.test_class]
    log(f'{o.test_class} ({o.mode})')
    sys.exit(subprocess.run(command).returncode)


def _diff_text(expected, actual, from_name, to_name):
    lines = list(difflib.unified_diff(expected.splitlines(keepends=True), actual.splitlines(keepends=True),
                                      fromfile=from_name, tofile=to_name, n=1))
    return ''.join(l if len(l) < 200 else l[:200] + '...\n' for l in lines[:40]).rstrip('\n')


def diff(o):
    """A program under chibi-scheme and under ours, interpreted (and under torture with --torture):
    stdout and exit status must be the same."""
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


def conformance_results(o):
    """Build action: one suite, interpreted only, a JVM per section four at a time. Writes the
    suite's part of the status block and the passing counts; cached on the language and the suite."""
    harness.set_repo_root(os.getcwd())  # the execroot
    runtime = Runtime(os.path.abspath(o.java), [os.path.abspath(p) for p in o.module_path])
    suite = harness.suites(o.suite)[0]
    sections = suite.sections()
    results = harness.run_conformance(runtime, sections, jobs=4)
    with open(o.block, 'w', encoding='utf-8') as f:
        f.write(harness.suite_block(suite, results) + '\n')
    with open(o.json, 'w', encoding='utf-8') as f:
        json.dump({'passing': {f'{k[0]}/{k[1]}': r['pass'] for k, r in sorted(results.items())},
                   'summary': harness.summary(results, sections).splitlines()}, f, indent=1)
        f.write('\n')


def _status_from(blocks):
    parts = []
    for path in blocks:
        with open(rlocation(path), encoding='utf-8') as f:
            parts.append(f.read())
    return harness.status_block(parts)


def conformance_status(o):
    """docs/plan.md's status block is the one the suites give."""
    block = _status_from(o.blocks)
    with open(rlocation(o.plan), encoding='utf-8') as f:
        _, current = harness.replace_status_block(f.read(), block)
    check(current == block, 'The status block in docs/plan.md is stale. Run `bazel run //:r7rs -- status` and commit '
          'the result.\nExpected:\n' + block)
    log('status block in docs/plan.md is up to date')


def torture(o):
    """Every section of a suite passes as many tests under torture as normally: the two runs at
    once, a JVM per section, two at a time each."""
    runtime = Runtime.from_manifest(o.runtime)
    sections = harness.suites(o.suite)[0].sections()
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        normal = pool.submit(harness.run_conformance, runtime, sections, jobs=2)
        tortured = pool.submit(harness.run_conformance, runtime, sections, torture=True, jobs=2)
        normal, tortured = normal.result(), tortured.result()
    log('normally:\n' + harness.summary(normal, sections) + '\nunder torture:\n' + harness.summary(tortured, sections))
    worse = [f'{s.id}: {normal[(o.suite, s.id)]["pass"]} normally, {tortured[(o.suite, s.id)]["pass"]} under torture'
             for s in sections if tortured[(o.suite, s.id)]['pass'] < normal[(o.suite, s.id)]['pass']]
    check(not worse, 'sections that pass less under torture:\n  ' + '\n  '.join(worse))


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
    """Rewrites the status block in docs/plan.md from the cached conformance results; with --check,
    fails if it differs; with --native, checks it against a run through the native launcher."""
    plan = join(harness.workspace(), 'docs', 'plan.md')
    with open(plan, encoding='utf-8') as f:
        text = f.read()
    if o.native:
        all_suites = harness.suites()
        sections = [s for suite in all_suites for s in suite.sections()]
        results = harness.run_conformance(None, sections, native=resolve(o.native))
        block = harness.status_block([harness.suite_block(suite, results) for suite in all_suites])
    else:
        check(o.results, '--results: the conformance results, A,B')
        block = _status_from(o.results.split(','))
    new, current = harness.replace_status_block(text, block)
    if o.check or o.native:
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


def readme_command(o):
    problems = readme.run(harness.workspace(), native=o.native)
    check(not problems, 'readme: failed:\n' + '\n'.join(problems))
    log('readme: ' + ('the native blocks run' if o.native else 'every block is checked, and every command runs as documented'))


def native_image(o, options):
    """The launcher as a native executable (design.md D17), with the dev shell's GraalVM (JAVA_HOME):
    the same release as Bazel's, but nixpkgs' native-image is set up to find the shell's C
    toolchain, which the upstream archive's doesn't recognize. The module path is the launcher's
    without the tools; the language's build-time initialization and resources come from the
    scheme jar's META-INF/native-image, and Truffle's runtime on the module path gives the
    executable the Graal JIT. On x86-64 Linux the baseline target (-march=compatibility) keeps
    nixpkgs' static libm out of the dynamically linked executable."""
    runtime = Runtime.from_manifest(o.runtime)
    java_home = os.environ.get('JAVA_HOME', '')
    tool = join(java_home, 'bin', 'native-image')
    check(exists(tool), f'no native-image at {tool}: run this inside `nix develop`, whose JAVA_HOME is GraalVM')
    pinned = bench.graalvm_version(runtime.release)
    check(bench.graalvm_version(join(java_home, 'release')) == pinned, f'JAVA_HOME is not GraalVM {pinned} (design.md D11)')
    output = os.path.abspath(o.output)
    os.makedirs(os.path.dirname(output), exist_ok=True)
    if sys.platform.startswith('linux') and platform.machine() in ('x86_64', 'amd64'):
        options = ['-march=compatibility'] + options
    start = time.time()
    p = subprocess.run([tool, '-p', runtime.module_path, '-m', harness.LAUNCHER_MAIN, '-o', output] + options,
                       cwd=os.path.dirname(output))
    check(p.returncode == 0, f'native-image failed (exit {p.returncode})')
    log(f'native-image: built {output} in {time.time() - start:.0f}s')


def main():
    # Runfiles the targets pass, before or after the command name.
    common = argparse.ArgumentParser(add_help=False)
    for option in ('--runtime', '--tools-runtime', '--chibi', '--formatter', '--prefs'):
        common.add_argument(option, default=argparse.SUPPRESS)
    common.add_argument('--results', default=argparse.SUPPRESS, help="the conformance results' status block parts, A,B")
    parser = argparse.ArgumentParser(prog='bazel run //:r7rs --', description=__doc__.split('\n\n')[0], parents=[common])
    sub = parser.add_subparsers(dest='command', required=True)
    add = sub.add_parser

    def sub_parser(name, **kwargs):
        return add(name, parents=[common], **kwargs)

    sub.add_parser = sub_parser

    p = sub.add_parser('unit')
    p.add_argument('--mode', choices=UNIT_MODES, required=True)
    p.add_argument('--processor', required=True)
    p.add_argument('test_class')
    p = sub.add_parser('diff')
    p.add_argument('file')
    p.add_argument('--compile', action='store_true')
    p.add_argument('--torture', action='store_true')
    p = sub.add_parser('program')
    p.add_argument('kind', choices=['spec', 'space'])
    p.add_argument('file')
    p.add_argument('--expect')
    p.add_argument('--limits')
    p = sub.add_parser('conformance-results')
    for option in ('--suite', '--java', '--json', '--block'):
        p.add_argument(option, required=True)
    p.add_argument('--module-path', nargs='+', required=True)
    p = sub.add_parser('conformance-status')
    p.add_argument('plan')
    p.add_argument('blocks', nargs='+')
    p = sub.add_parser('torture')
    p.add_argument('--suite', choices=['chibi', 'larceny'], required=True)
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
    p = sub.add_parser('status', help='rewrite the status block in docs/plan.md')
    p.add_argument('--check', action='store_true')
    p.add_argument('--native', metavar='FILE', help='check it against a run through this native launcher')
    sub.add_parser('format', help='the Eclipse formatter over src/ and tests/')
    p = sub.add_parser('readme', help="run docs/usage.md's and docs/embedding.md's commands")
    p.add_argument('--native', action='store_true', help='only the native-image blocks')
    p = sub.add_parser('native-image', help='the launcher as a native executable')
    p.add_argument('--output', default='native/scheme')
    for name in ('benchmark', 'bench-report', 'bench-check', 'vendor-benchmarks', 'vendor-chibi', 'vendor-larceny'):
        add(name, add_help=False)

    o, rest = parser.parse_known_args()
    for option in ('runtime', 'tools_runtime', 'chibi', 'formatter', 'prefs', 'results'):
        setattr(o, option, getattr(o, option, None))  # SUPPRESS'd when not given (parents share actions)
    # Under `bazel run`, file names on the command line are relative to where it ran.
    os.environ['RUNFILES_DIR'] = os.path.abspath(harness.runfiles_dir())
    if 'BUILD_WORKING_DIRECTORY' in os.environ:
        os.chdir(os.environ['BUILD_WORKING_DIRECTORY'])
    if rest and o.command not in ('native-image', 'benchmark', 'bench-report', 'bench-check', 'vendor-benchmarks',
                                  'vendor-chibi', 'vendor-larceny'):
        parser.error(f'unrecognized arguments: {" ".join(rest)}')
    if o.runtime:
        bench.RUNTIME[:] = vendor.RUNTIME[:] = [Runtime.from_manifest(o.runtime)]
    if o.chibi:
        vendor.CHIBI[:] = [rlocation(o.chibi)]
    commands = {
        'unit': unit, 'diff': diff, 'program': program, 'conformance-results': conformance_results,
        'conformance-status': conformance_status, 'torture': torture, 'tools': tools_test, 'rules': rules,
        'versions': versions, 'lint': lint, 'test': test, 'status': status, 'format': format_sources,
        'readme': readme_command,
    }
    if o.command in commands:
        commands[o.command](o)
    elif o.command == 'native-image':
        native_image(o, rest)
    else:
        {'benchmark': bench.benchmark, 'bench-report': bench.r7rs_bench_report, 'bench-check': bench.r7rs_bench_check,
         'vendor-benchmarks': bench.r7rs_vendor_benchmarks, 'vendor-chibi': vendor.r7rs_vendor_chibi,
         'vendor-larceny': vendor.r7rs_vendor_larceny}[o.command](rest)


if __name__ == '__main__':
    main()

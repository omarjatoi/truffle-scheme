"""One static check of one java_library, as a Bazel test (bazel/lint/defs.bzl; mx checks).

    lint_test.py format     --inputs <json> --tool <eclipse_format> --prefs <jdt prefs>
    lint_test.py checkstyle --inputs <json> --tool <checkstyle> --config <checks.xml>
    lint_test.py ecj        --inputs <json> --tool <ecj> --prefs <jdt prefs>
    lint_test.py spotbugs   --inputs <json> --tool <spotbugs>

<json> is a _lint_inputs manifest: the library's sources, class jars, generated sources and
classpaths, as runfiles paths. Tools are java_binary launchers in runfiles.
"""

import argparse
import json
import os
import subprocess
import sys
import tempfile
import zipfile

import r7rs


def _paths(inputs, key):
    return [r7rs.rlocation(p) for p in inputs[key]]


def _run(command, cwd=None):
    r7rs.log(' '.join(command[:6]) + (' ...' if len(command) > 6 else ''))
    return subprocess.run(command, cwd=cwd).returncode


def format_check(inputs, args):
    srcs = _paths(inputs, 'srcs')
    return _run([r7rs.rlocation(args.tool), '--check', r7rs.rlocation(args.prefs)] + srcs)


def checkstyle(inputs, args):
    # The config's suppressions match paths with src/launcher or tests in them: runfiles paths
    # (.../_main/src/launcher/...) keep those.
    srcs = _paths(inputs, 'srcs')
    return _run([r7rs.rlocation(args.tool), '-c', r7rs.rlocation(args.config)] + srcs)


def ecj(inputs, args, tmp):
    """mx checks ecj: ECJ with GraalVM's JDT settings, -failOnWarning. In projects that run the
    Truffle DSL processor, warnings are ignored (mx: ECJ can't ignore them in generated code only)."""
    with open(r7rs.rlocation(args.prefs), encoding='utf-8') as f:
        prefs = f.read()
    processor_path = _paths(inputs, 'processor_path')
    if processor_path:
        prefs = prefs.replace('=warning', '=ignore')
    prefs += '\norg.eclipse.jdt.core.compiler.problem.tasks=ignore\n'
    prefs_file = os.path.join(tmp, 'org.eclipse.jdt.core.prefs')
    with open(prefs_file, 'w', encoding='utf-8') as f:
        f.write(prefs)
    command = [r7rs.rlocation(args.tool), '-g', '-d', os.path.join(tmp, 'classes'),
               '-classpath', os.pathsep.join(_paths(inputs, 'classpath')), '-parameters', '--release', '25',
               '-encoding', 'UTF-8', '-failOnWarning', '-properties', prefs_file]
    if processor_path:
        command += ['-processorpath', os.pathsep.join(processor_path), '-s', os.path.join(tmp, 'generated')]
        os.makedirs(os.path.join(tmp, 'generated'))
    else:
        command += ['-proc:none']
    return _run(command + _paths(inputs, 'srcs'))


def spotbugs(inputs, args, tmp):
    """mx checks spotbugs: -textui -low -maxRank 15, mx's exclude filter, and (mx's
    spotbugsIgnoresGenerated) no reports on classes the Truffle DSL processor generated."""
    generated = set()
    for jar in _paths(inputs, 'generated_sources'):
        with zipfile.ZipFile(jar) as z:
            for name in z.namelist():
                if name.endswith('.java') and '-info' not in name:
                    generated.add(name[:-len('.java')].replace('/', '.'))
    matches = ['RCN_REDUNDANT_NULLCHECK_WOULD_HAVE_BEEN_A_NPE', 'ASE_ASSERTION_WITH_SIDE_EFFECT',
               'ASE_ASSERTION_WITH_SIDE_EFFECT_METHOD']
    xml = ['<FindBugsFilter>']
    xml += [f'  <Match><Bug pattern="{p}"/></Match>' for p in matches]
    xml += [f'  <Match><Class name="~{c.replace(".", "[.]")}.*"/></Match>' for c in sorted(generated)]
    xml.append('</FindBugsFilter>')
    exclude = os.path.join(tmp, 'exclude.xml')
    with open(exclude, 'w', encoding='utf-8') as f:
        f.write('\n'.join(xml) + '\n')
    class_jars = _paths(inputs, 'class_jars')
    classes = [n for jar in class_jars for n in zipfile.ZipFile(jar).namelist()
               if n.endswith('.class') and not n.endswith('-info.class')]
    if not classes:
        # scheme.prelude is resources and a package-info: SpotBugs would refuse to run.
        r7rs.log('no classes to analyze')
        return 0
    aux =[p for p in _paths(inputs, 'runtime_classpath') if p not in class_jars]
    return _run([r7rs.rlocation(args.tool), '-textui', '-low', '-maxRank', '15', '-exclude', exclude,
                 '-auxclasspath', os.pathsep.join(aux), '-exitcode'] + class_jars)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('check', choices=['format', 'checkstyle', 'ecj', 'spotbugs'])
    parser.add_argument('--inputs', required=True)
    parser.add_argument('--tool', required=True)
    parser.add_argument('--prefs')
    parser.add_argument('--config')
    args = parser.parse_args()
    with open(r7rs.rlocation(args.inputs), encoding='utf-8') as f:
        inputs = json.load(f)
    tmp = tempfile.mkdtemp(dir=os.environ.get('TEST_TMPDIR'))
    if args.check == 'format':
        rc = format_check(inputs, args)
    elif args.check == 'checkstyle':
        rc = checkstyle(inputs, args)
    elif args.check == 'ecj':
        rc = ecj(inputs, args, tmp)
    else:
        rc = spotbugs(inputs, args, tmp)
    sys.exit(rc)


if __name__ == '__main__':
    main()

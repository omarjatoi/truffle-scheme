"""Test: design.md D17 "The launcher's JVM": the `scheme` launcher's JVM uses the serial collector, 4
compiler threads where the JVM would start more, and the AOT cache where it is valid, unless its
JVM options (on the command line, its `--vm.` options, in JDK_JAVA_OPTIONS or in
JAVA_TOOL_OPTIONS) set those themselves. Its `--vm.<option>`s are the JVM's `-<option>`s ("VM
options"), and the launcher started with java rejects one the JVM wasn't started with."""

import argparse
import os
import re
import subprocess
import tempfile

from harness import LAUNCHER_COMPILER_VM_ARGS, LAUNCHER_MAIN, Runtime, check, launcher_vm_args, log, split_vm_options

_FLAG = re.compile(r'^\s*\S+\s+(\w+)\s+:?=\s+(\S*)\s+\{[^}]*\}\s+\{([^}]*)\}')


def run(runtime, args, jvm_args=(), jdk_java_options=None, status=0):
    """The output of the launcher with these arguments and JVM options, and JDK_JAVA_OPTIONS, the
    command built as bazel-bin/scheme builds it; and the command. It must exit with `status`."""
    env = {k: v for k, v in os.environ.items() if k not in ('JDK_JAVA_OPTIONS', 'JAVA_TOOL_OPTIONS')}
    if jdk_java_options is not None:
        env['JDK_JAVA_OPTIONS'] = jdk_java_options
    command = runtime.launcher(args, jvm_args, env)
    return execute(command, env, status), command


def execute(command, env, status=0):
    """The output (stdout and stderr) of a command that must exit with `status` (None: any but 0)."""
    p = subprocess.run(command, env=env, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                       text=True, encoding='utf-8', errors='replace', timeout=120)
    check(p.returncode != 0 if status is None else p.returncode == status, f'{command} exited with {p.returncode}:\n{p.stdout}')
    return p.stdout


def flags(runtime, jvm_args=(), jdk_java_options=None, args=()):
    """The JVM's flags running `--version` (-XX:+PrintFlagsFinal) after these launcher arguments:
    name -> (value, origin)."""
    out, _ = run(runtime, list(args) + ['--version'], ['-XX:+PrintFlagsFinal'] + list(jvm_args), jdk_java_options)
    found = {m.group(1): (m.group(2), m.group(3)) for m in map(_FLAG.match, out.splitlines()) if m}
    check('UseSerialGC' in found, f'no flags in the output:\n{out}')
    return found


def collector(f):
    return [gc for gc in ('Serial', 'Parallel', 'G1', 'Z', 'Shenandoah', 'Epsilon') if f.get(f'Use{gc}GC', ('',))[0] == 'true']


def launcher_vm(o):
    runtime = Runtime.from_manifest(o.runtime)

    # The collector (M8-05).
    f = flags(runtime)
    check(collector(f) == ['Serial'], f'the launcher should use the serial collector by default, not {collector(f)}')
    check(collector(flags(runtime, ['-XX:+UseG1GC'])) == ['G1'], 'a collector among the JVM options should replace the serial one')
    check(collector(flags(runtime, jdk_java_options='-XX:+UseParallelGC')) == ['Parallel'],
          'a collector in JDK_JAVA_OPTIONS should replace the serial one')

    # The compiler threads: 4 where the JVM would start more, none of ours given a count.
    check(LAUNCHER_COMPILER_VM_ARGS[0] in launcher_vm_args([], {}, cpus=16), '16 processors should get 4 compiler threads')
    check(not any('CICompilerCount' in a for a in launcher_vm_args([], {}, cpus=8)), '8 processors should keep the JVM\'s count')
    check(not any('CICompilerCount' in a for a in launcher_vm_args(['-XX:CICompilerCount=2'], {}, cpus=16)),
          'a count among the JVM options should replace ours')
    if os.cpu_count() >= 16:
        check(f['CICompilerCount'][0] == '4', f'the launcher should start 4 compiler threads here, not {f["CICompilerCount"]}')
    check(flags(runtime, ['-XX:CICompilerCount=2'])['CICompilerCount'][0] == '2', '-XX:CICompilerCount should set the count')
    log('serial collector, compiler threads; the JVM options replace each')

    # The AOT cache: used where it was made (bazel-bin/scheme), and not with ZGC (which maps none) or a
    # stale stamp. Where it was made elsewhere (an executor's action), the launcher leaves it out.
    aot = Runtime.from_manifest(o.aot_runtime)
    check(aot.aot_cache and aot.aot_stamp, f'{o.aot_runtime} should have an AOT cache')
    _, command = run(aot, ['--version'], ['-XX:+UseZGC'])
    check(not any('AOTCache' in a for a in command), f'the AOT cache should be left out with ZGC: {command}')
    with tempfile.NamedTemporaryFile('w', suffix='.stamp', dir=os.environ.get('TEST_TMPDIR')) as stale:
        stale.write('0 0 /nonexistent\n')
        stale.flush()
        check(Runtime(aot.java, aot.module_path.split(os.pathsep), aot.release, aot.aot_cache, stale.name).aot_module_path() is None,
              'a stale stamp should leave the AOT cache out')
    out, command = run(aot, ['--version'], ['-Xlog:class+load=info'])
    if aot.aot_module_path():
        check(any(a.startswith('-XX:AOTCache=') for a in command), f'a valid AOT cache should be used: {command}')
        check(re.search(r'scheme\.launcher\.SchemeMain source: shared objects file', out) and '[error]' not in out,
              f'the launcher\'s classes should come from the AOT cache:\n{out[-3000:]}')
        log('AOT cache: valid here, used')
    else:
        check(not any('AOTCache' in a for a in command), f'a stale AOT cache should be left out: {command}')
        log('AOT cache: made elsewhere, left out')


def vm_options(o):
    """`--vm.<option>`, as GraalVM's launchers take it: the JVM's `-<option>`, among the launcher's
    options (before the program file), and the launcher's defaults give way to it as to the JVM
    options above."""
    # Which arguments are the JVM's: the launcher's options, as SchemeMain parses them.
    check(split_vm_options(['--vm.Xmx1g', '-I', '--vm.Xss1m', '--vm.cp=a:b', '--vm.classpath=c', 'f.scm', '--vm.Xmx2g'])
          == (['-Xmx1g', '-cp', 'a:b', '-cp', 'c'], ['-I', '--vm.Xss1m', 'f.scm', '--vm.Xmx2g']),
          'the --vm. options before the file (and not an -I directory) should be the JVM\'s')
    check(split_vm_options(['--engine.Mode=latency', '--', '--vm.Xmx1g']) == ([], ['--engine.Mode=latency', '--', '--vm.Xmx1g']),
          'a --vm. option after -- should be the program file')
    check(split_vm_options(['--vm.cp', 'x']) == ([], ['--vm.cp', 'x']), 'a bare --vm.cp should be left to the launcher')

    runtime = Runtime.from_manifest(o.runtime)
    # A collector, the heap's maximum, the compiler threads.
    f = flags(runtime, args=['--vm.XX:+UseG1GC', '--vm.Xmx300m', '--vm.XX:CICompilerCount=2'])
    check(collector(f) == ['G1'], f'--vm.XX:+UseG1GC should replace the serial collector, not {collector(f)}')
    check(f['MaxHeapSize'] == (str(300 << 20), 'command line'), f'--vm.Xmx300m should set the maximum heap: {f["MaxHeapSize"]}')
    check(f['CICompilerCount'][0] == '2', f'--vm.XX:CICompilerCount=2 should set the count: {f["CICompilerCount"]}')
    # System properties and the class path.
    tmp = os.environ.get('TEST_TMPDIR') or tempfile.gettempdir()
    out, _ = run(runtime, ['--vm.XshowSettings:properties', '--vm.Dscheme.test.vm=given', f'--vm.cp={tmp}', '--version'])
    check(re.search(r'^\s*scheme\.test\.vm = given$', out, re.M), f'--vm.Dscheme.test.vm=given should set the property:\n{out}')
    check(re.search(rf'^\s*java\.class\.path = {re.escape(tmp)}$', out, re.M), f'--vm.cp should set the class path:\n{out}')
    # Truffle reads polyglot.* properties as options: this one reaches the engine, which rejects it.
    out, _ = run(runtime, ['--vm.Dpolyglot.scheme.NoSuchOption=1', '--version'], status=None)
    check('scheme.NoSuchOption' in out, f'--vm.Dpolyglot.scheme.NoSuchOption=1 should reach the engine:\n{out}')
    # A collector the AOT cache maps none with leaves it out, as among the JVM options.
    _, command = run(Runtime.from_manifest(o.aot_runtime), ['--vm.XX:+UseZGC', '--version'])
    check('-XX:+UseZGC' in command and not any('AOTCache' in a for a in command),
          f'--vm.XX:+UseZGC should leave the AOT cache out: {command}')
    log('--vm.: collector, heap, compiler threads, properties, class path; the AOT cache gives way')

    # The program's arguments stay the program's.
    program = os.path.join(tmp, 'command-line.scm')
    with open(program, 'w', encoding='utf-8') as out:
        out.write('(import (scheme base) (scheme process-context) (scheme write))\n(write (cdr (command-line)))\n')
    out, command = run(runtime, ['--vm.Dscheme.test.vm=given', program, '--vm.Xmx1g', '--vm.Dx=y'])
    check(out.strip() == '("--vm.Xmx1g" "--vm.Dx=y")', f'the --vm. options after the file should be the program\'s: {out}')
    check('-Dscheme.test.vm=given' in command and '-Xmx1g' not in command and '-Dx=y' not in command,
          f'only the --vm. options before the file should be the JVM\'s: {command}')
    # So are arguments in the JVM's own forms: the JVM's options end before its main module.
    program_args = ['a', '-Xmx3g', '-XX:+Foo', '-Dx=y', '-Xss1k', 'b']
    out, command = run(runtime, [program] + program_args)
    check(out.strip() == '(' + ' '.join(f'"{a}"' for a in program_args) + ')',
          f'-X, -XX: and -D arguments after the file should be the program\'s, as given: {out}')
    check(command[command.index(program):] == [program] + program_args,
          f'the arguments after the file should follow it, as given: {command}')

    # The launcher started with java, not with its --vm. options: an error, not ignored.
    env = {k: v for k, v in os.environ.items() if k not in ('JDK_JAVA_OPTIONS', 'JAVA_TOOL_OPTIONS')}
    out = execute(runtime.command([], LAUNCHER_MAIN) + ['--vm.Xmx2m', '--version'], env, status=64)
    check('-Xmx2m' in out and 'bazel-bin/scheme' in out, f'an unapplied --vm.Xmx2m should be an error naming -Xmx2m:\n{out}')
    out, _ = run(runtime, ['--vm.cp', tmp, '--version'], status=64)
    check('--vm.cp=<classpath>' in out, f'a bare --vm.cp should be an error:\n{out}')
    log('--vm.: the program\'s arguments left alone; one the JVM wasn\'t started with is an error')


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--runtime', required=True)
    parser.add_argument('--aot-runtime', required=True)
    options = parser.parse_args()
    launcher_vm(options)
    vm_options(options)

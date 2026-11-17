"""Test: design.md D17 "The launcher's JVM": the `scheme` launcher's JVM uses the serial collector, 4
compiler threads where the JVM would start more, and the AOT cache where it is valid, unless its
JVM options (on the command line, in JDK_JAVA_OPTIONS or in JAVA_TOOL_OPTIONS) set those
themselves."""

import argparse
import os
import re
import subprocess
import tempfile

from harness import LAUNCHER_COMPILER_VM_ARGS, Runtime, check, launcher_vm_args, log

_FLAG = re.compile(r'^\s*\S+\s+(\w+)\s+:?=\s+(\S*)\s+\{[^}]*\}\s+\{([^}]*)\}')


def run(runtime, args, jvm_args=(), jdk_java_options=None):
    """The output of the launcher with these arguments and JVM options, and JDK_JAVA_OPTIONS, the
    command built as bazel-bin/scheme builds it; and the command."""
    env = {k: v for k, v in os.environ.items() if k not in ('JDK_JAVA_OPTIONS', 'JAVA_TOOL_OPTIONS')}
    if jdk_java_options is not None:
        env['JDK_JAVA_OPTIONS'] = jdk_java_options
    command = runtime.launcher(args, jvm_args, env)
    p = subprocess.run(command, env=env, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                       text=True, encoding='utf-8', errors='replace', timeout=120)
    check(p.returncode == 0, f'{command} exited with {p.returncode}:\n{p.stdout}')
    return p.stdout, command


def flags(runtime, jvm_args=(), jdk_java_options=None):
    """The JVM's flags running `--version` (-XX:+PrintFlagsFinal): name -> (value, origin)."""
    out, _ = run(runtime, ['--version'], ['-XX:+PrintFlagsFinal'] + list(jvm_args), jdk_java_options)
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


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--runtime', required=True)
    parser.add_argument('--aot-runtime', required=True)
    launcher_vm(parser.parse_args())

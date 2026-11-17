"""Test: design.md D17 "The collector": the `scheme` launcher's JVM uses the serial collector, unless its
JVM options choose another, on the command line or in JDK_JAVA_OPTIONS (the JVM refuses two)."""

import argparse
import os
import subprocess

from harness import Runtime, check, log


def collector(runtime, jvm_args=(), jdk_java_options=None):
    """The collector the launcher's JVM says it uses (-Xlog:gc) running `--version`, with these
    JVM options and JDK_JAVA_OPTIONS, the command built as bazel-bin/scheme builds it."""
    for name in ('JDK_JAVA_OPTIONS', 'JAVA_TOOL_OPTIONS'):
        os.environ.pop(name, None)
    if jdk_java_options is not None:
        os.environ['JDK_JAVA_OPTIONS'] = jdk_java_options
    command = runtime.launcher(['--version'], ['-Xlog:gc=info'] + list(jvm_args))
    p = subprocess.run(command, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
                       encoding='utf-8', errors='replace', timeout=120)
    check(p.returncode == 0, f'{command} exited with {p.returncode}:\n{p.stdout}')
    for line in p.stdout.splitlines():
        if '[gc]' in line and ' Using ' in line:
            return line.split(' Using ', 1)[1].strip()
    check(False, f'no collector in the output of {command}:\n{p.stdout}')
    return None


def launcher_gc(o):
    runtime = Runtime.from_manifest(o.runtime)
    check(collector(runtime) == 'Serial', 'the launcher should use the serial collector by default')
    check(collector(runtime, ['-XX:+UseG1GC']) == 'G1', 'a collector among the JVM options should replace the serial one')
    check(collector(runtime, jdk_java_options='-XX:+UseParallelGC') == 'Parallel',
          'a collector in JDK_JAVA_OPTIONS should replace the serial one')
    log('serial by default; -XX:+UseG1GC and JDK_JAVA_OPTIONS choose another')


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--runtime', required=True)
    launcher_gc(parser.parse_args())

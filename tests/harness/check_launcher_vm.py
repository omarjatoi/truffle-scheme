"""Test: design.md D17 "The launcher's JVM": the `scheme` launcher's JVM uses the serial collector, 4
compiler threads where the JVM would start more, the AOT cache where it is valid and the JVM takes
it, and its own output on standard error, off unless its JVM options ask for more than sizes and
system properties ("The VM's output"), unless its JVM options (on the command line, its `--vm.`
options, in JDK_JAVA_OPTIONS or in JAVA_TOOL_OPTIONS) set those themselves. Its `--vm.<option>`s
are the JVM's `-<option>`s ("VM options"), and the launcher started with java rejects one the JVM
wasn't started with. Every class of option takes effect or fails clearly (plan.md M10-04,
docs/usage.md "Options"), and so does every option in an argument file, `--vm.@file` (plan.md
M11-03), and every constant option (plan.md M11-07)."""

import argparse
import os
import re
import signal
import subprocess
import tempfile
import time

from harness import (AOT_VM_ARGS, LAUNCHER_COMPILER_VM_ARGS, LAUNCHER_GC_VM_ARGS, LAUNCHER_INSTRUMENT_VM_ARGS, LAUNCHER_MAIN,
                     LAUNCHER_OUTPUT_VM_ARGS, LAUNCHER_QUIET_VM_ARGS, Runtime,
                     check, launcher_vm_args, log, rlocation, split_vm_options)

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


def _clean_env():
    return {k: v for k, v in os.environ.items() if k not in ('JDK_JAVA_OPTIONS', 'JAVA_TOOL_OPTIONS')}


def output_of(runtime, args):
    """The launcher's standard output and standard error, apart, with these arguments; it must exit
    with 0."""
    env = _clean_env()
    command = runtime.launcher(args, (), env)
    p = subprocess.run(command, env=env, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
                       encoding='utf-8', errors='replace', timeout=120)
    check(p.returncode == 0, f'{command} exited with {p.returncode}:\n{p.stdout}\n{p.stderr}')
    return p.stdout, p.stderr


def thread_dump(runtime, args):
    """The REPL's standard output and standard error with these arguments, sent SIGQUIT (which asks
    HotSpot for a thread dump) once it has evaluated a first input."""
    env = _clean_env()
    command = runtime.launcher(args, (), env)
    p = subprocess.Popen(command, env=env, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    try:
        p.stdin.write(b'(display "ready")(newline)(flush-output-port)\n')
        p.stdin.flush()
        first = p.stdout.readline()
        check(first == b'ready\n', f'the REPL should evaluate its first input: {first!r}')
        p.send_signal(signal.SIGQUIT)
        # The dump is printed by the JVM's signal thread; it has 2 s before the input ends.
        time.sleep(2)
        out, err = p.communicate(b'(display "done")(newline)\n', timeout=120)
    finally:
        if p.poll() is None:
            p.kill()
    check(p.returncode == 0 and out == b'done\n', f'{command} should go on after SIGQUIT: {p.returncode} {out!r} {err!r}')
    return out.decode('utf-8', 'replace'), err.decode('utf-8', 'replace')


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

    # The VM's output ("The VM's output"): on standard error, unless the JVM options choose a
    # stream; and off, unless they ask for more than the heap's and stacks' sizes and system
    # properties other than the compiler's.
    def output(jvm_args, env=None, options=()):
        args = launcher_vm_args(jvm_args, env or {}, options=options)
        return LAUNCHER_OUTPUT_VM_ARGS[0] in args, all(a in args for a in LAUNCHER_QUIET_VM_ARGS)

    check(output([]) == (True, True), 'with no JVM options, the VM\'s output should be off, and on standard error')
    check(output(['-Xmx64m', '-Xms8m', '-Xmn4m', '-Xss2m', '-Dname=value', '-Dpolyglot.scheme.BufferOutput=false'], options=['--log.level=FINE']) == (True, True),
          'sizes and system properties should leave the VM\'s output off')
    for asks in (['-XX:+PrintFlagsFinal'], ['-Xmx64m', '-XX:+UseG1GC'], ['-Djdk.graal.PrintCompilation=true'], ['-Djvmci.Compiler=graal'], ['-ea'],
                 ['-Xlog:gc'], ['-Dpolyglot.engine.TraceCompilation=true'], ['-Dpolyglot.compiler.InstrumentBoundaries=true']):
        check(output(asks) == (True, False), f'{asks} should turn the VM\'s output on, on standard error')
    for asks in (['--engine.TraceCompilation'], ['--compiler.InstrumentBoundaries']):
        check(output([], options=asks) == (True, False), f'{asks} should turn the VM\'s output on, on standard error')
    check(output([], {'JDK_JAVA_OPTIONS': '-XX:+PrintCompilation'}) == (True, False), 'an -XX: option in JDK_JAVA_OPTIONS should turn it on')
    check(output([], {'JAVA_TOOL_OPTIONS': '-Xcheck:jni'}) == (True, False), 'an option in JAVA_TOOL_OPTIONS should turn it on')
    for stream in ('-XX:+DisplayVMOutputToStdout', '-XX:-DisplayVMOutputToStderr', '-XX:+DisplayVMOutputToStderr'):
        check(output([stream]) == (False, False), f'{stream} should choose the stream, and turn the output on')
    # In the JVM: -XX:+PrintFlagsFinal prints on standard error; with no options, a thread dump
    # (SIGQUIT) prints nothing, and with one that asks, it prints on standard error.
    out, err = output_of(runtime, ['--vm.XX:+PrintFlagsFinal', '--version'])
    check(' DisplayVMOutputToStderr ' in err and 'UseSerialGC' not in out, f'-XX:+PrintFlagsFinal should print on standard error:\n{out}')
    out, err = thread_dump(runtime, [])
    check('Full thread dump' not in out + err, f'with no JVM options, a thread dump should print nothing:\n{out}\n{err}')
    out, err = thread_dump(runtime, ['--vm.XX:+DisplayVMOutputToStderr'])
    check('Full thread dump' in err and 'Full thread dump' not in out, f'a thread dump should print on standard error:\n{out}\n{err}')
    log('the VM\'s output: on standard error; off unless the JVM options ask; a thread dump, -XX:+PrintFlagsFinal')

    # The AOT cache: used where it was made (bazel-bin/scheme), and not with a stale stamp. Where it
    # was made elsewhere (an executor's action), the launcher leaves it out.
    aot = Runtime.from_manifest(o.aot_runtime)
    check(aot.aot_cache and aot.aot_stamp, f'{o.aot_runtime} should have an AOT cache')
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
        out, command = run(aot, ['--version'])
        check(any(a.startswith('-XX:AOTCache=') for a in command) and '-XX:-DisplayVMOutput' in command,
              f'with no JVM options, the JVM should take the AOT cache with its output off: {command}\n{out}')
        log('AOT cache: valid here, used')
    else:
        check(not any('AOTCache' in a for a in command), f'a stale AOT cache should be left out: {command}')
        log('AOT cache: made elsewhere, left out')


def trained(runtime):
    """runtime with an AOT cache and its stamp made here, as bazel/java_module.bzl's _AOT_COMMAND
    makes bazel-bin/scheme's but trained on `--version`: one the launcher finds valid wherever the
    test runs, the executors included."""
    tmp = tempfile.mkdtemp(dir=os.environ.get('TEST_TMPDIR'))
    cache, stamp = os.path.join(tmp, 'launcher.aot'), os.path.join(tmp, 'launcher.aot.stamp')
    jars = [os.path.realpath(j) for j in runtime.module_path.split(os.pathsep)]
    env = {k: v for k, v in os.environ.items() if k not in ('JDK_JAVA_OPTIONS', 'JAVA_TOOL_OPTIONS')}
    training = LAUNCHER_GC_VM_ARGS + AOT_VM_ARGS + ['-Xlog:aot=off', '-Xlog:cds=off', f'-XX:AOTCacheOutput={cache}']
    execute(runtime.command(training, LAUNCHER_MAIN, os.pathsep.join(jars)) + ['--version'], env)
    with open(stamp, 'w', encoding='utf-8') as f:
        for path in [os.path.join(os.path.dirname(os.path.dirname(runtime.java)), 'lib', 'modules')] + jars:
            st = os.stat(path)
            f.write(f'{st.st_size} {int(st.st_mtime)} {os.path.realpath(path)}\n')
    aot = Runtime(runtime.java, runtime.module_path.split(os.pathsep), runtime.release, cache, stamp)
    check(aot.aot_module_path(), 'the AOT cache trained here should be valid')
    return aot


def flag_values(out):
    """The flags -XX:+PrintFlagsFinal printed: name -> value."""
    return {m.group(1): m.group(2) for m in map(_FLAG.match, out.splitlines()) if m}


def aot_cache(o):
    """design.md D17 "The AOT cache": the launcher passes the cache with -XX:AOTMode=on (so a JVM
    that can't use it is an error, not a start with no class sharing), where the JVM takes it with
    the JVM options given; with any it doesn't (the object layout, the heap's mode, the collector,
    the module options; on the command line, as --vm. options or in JDK_JAVA_OPTIONS), the
    launcher leaves it out, and the JVM says nothing about a cache. With no options, it is used."""
    aot = trained(Runtime.from_manifest(o.runtime))
    out, command = run(aot, ['--version'])
    check(f'-XX:AOTCache={aot.aot_cache}' in command and '-XX:AOTMode=on' in command and out.startswith('R7RS Scheme ('),
          f'with no options, the AOT cache should be used, and required:\n{command}\n{out}')
    # Options the JVM takes it with: system properties and logging, which it isn't checked against,
    # and options it is, for which the launcher asks the JVM first.
    for args in (['--vm.Dscheme.test.vm=given'], ['--vm.ea', '--vm.Xmx300m'], ['--vm.XX:+UseG1GC'], ['--vm.XX:+UseParallelGC']):
        out, command = run(aot, args + ['--vm.Xlog:class+load=info', '--version'])
        check(f'-XX:AOTCache={aot.aot_cache}' in command and re.search(r'scheme\.launcher\.SchemeMain source: shared objects file', out),
              f'{args} should keep the AOT cache, and the launcher\'s classes should come from it:\n{command}\n{out[-3000:]}')
    log('AOT cache: used with no options, with properties, -ea, -Xmx, G1 and the parallel collector')
    # Options it doesn't: the cache is left out, the JVM says nothing about it, and the option
    # takes effect.
    for args, jdk_java_options, flag, value in ((['--vm.XX:+UseCompactObjectHeaders'], None, 'UseCompactObjectHeaders', 'true'),
                                                (['--vm.XX:-UseCompressedOops'], None, 'UseCompressedOops', 'false'),
                                                (['--vm.XX:+UseZGC'], None, 'UseZGC', 'true'),
                                                ([], '-XX:+UseCompactObjectHeaders', 'UseCompactObjectHeaders', 'true')):
        out, command = run(aot, args + ['--vm.XX:+PrintFlagsFinal', '--version'], jdk_java_options=jdk_java_options)
        given = args or [f'JDK_JAVA_OPTIONS={jdk_java_options}']
        check(not any('AOTCache' in a for a in command), f'{given} should leave the AOT cache out: {command}')
        check('AOT cache' not in out and 'shared archive' not in out, f'{given}: the JVM should say nothing about the AOT cache:\n{out[-3000:]}')
        check(flag_values(out).get(flag) == value, f'{given} should set {flag} to {value}: {flag_values(out).get(flag)}')
    # A module option the cache's module graph was made without, in JDK_JAVA_OPTIONS.
    out, command = run(aot, ['--version'], jdk_java_options='--add-opens=java.base/java.lang=ALL-UNNAMED')
    check(not any('AOTCache' in a for a in command) and 'AOT cache' not in out and out.rstrip().endswith(')'),
          f'--add-opens in JDK_JAVA_OPTIONS should leave the AOT cache out, quietly:\n{command}\n{out}')
    log('AOT cache: left out with compact headers, uncompressed oops, ZGC, --add-opens; nothing said')


def aligned_heap(f, size):
    """The maximum heap HotSpot sets for `-Xmx<size>`, given its flags `f`: the size rounded up to
    the heap's alignment (and then reported as ergonomic), which is the card table's (a card's
    bytes times the page size: GCArguments::compute_heap_alignment) or with G1 a region if larger,
    both powers of two. Without large pages, which would add theirs: 2 MB on Linux (4 KB pages), so
    300m stays 300m, and 8 MB on macOS on arm64 (16 KB pages), so 304m."""
    check(f['UseLargePages'][0] == 'false', f'the heap\'s alignment is computed without large pages: {f["UseLargePages"]}')
    alignment = max(int(f['GCCardSizeInBytes'][0]) * os.sysconf('SC_PAGE_SIZE'), int(f['G1HeapRegionSize'][0]))
    return -(-size // alignment) * alignment


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
    check(split_vm_options(['--vm.p=a:b', '--vm.-module-path=c', 'f.scm']) == (['--module-path', 'a:b', '--module-path', 'c'], ['f.scm']),
          '--vm.p and --vm.-module-path should be module paths')

    runtime = Runtime.from_manifest(o.runtime)
    # A collector, the heap's maximum, the compiler threads.
    f = flags(runtime, args=['--vm.XX:+UseG1GC', '--vm.Xmx300m', '--vm.XX:CICompilerCount=2'])
    check(collector(f) == ['G1'], f'--vm.XX:+UseG1GC should replace the serial collector, not {collector(f)}')
    heap = aligned_heap(f, 300 << 20)
    check(f['MaxHeapSize'] == (str(heap), 'command line' if heap == 300 << 20 else 'command line, ergonomic'),
          f'--vm.Xmx300m should set the maximum heap to {heap}: {f["MaxHeapSize"]}')
    check(f['CICompilerCount'][0] == '2', f'--vm.XX:CICompilerCount=2 should set the count: {f["CICompilerCount"]}')
    # System properties and the class path.
    tmp = os.environ.get('TEST_TMPDIR') or tempfile.gettempdir()
    out, _ = run(runtime, ['--vm.XshowSettings:properties', '--vm.Dscheme.test.vm=given', f'--vm.cp={tmp}', '--version'])
    check(re.search(r'^\s*scheme\.test\.vm = given$', out, re.M), f'--vm.Dscheme.test.vm=given should set the property:\n{out}')
    check(re.search(rf'^\s*java\.class\.path = {re.escape(tmp)}$', out, re.M), f'--vm.cp should set the class path:\n{out}')
    # Truffle reads polyglot.* properties as options: this one reaches the engine, which rejects it,
    # and the launcher says so (plan.md M10-04: it was a stack trace, status 255).
    out, _ = run(runtime, ['--vm.Dpolyglot.scheme.NoSuchOption=1', '--version'], status=1)
    check('scheme.NoSuchOption' in out and out.startswith('ERROR: ') and '\tat ' not in out,
          f'--vm.Dpolyglot.scheme.NoSuchOption=1 should reach the engine, and be a clear error:\n{out}')
    log('--vm.: collector, heap, compiler threads, properties, class path')

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
    out = execute(runtime.command([], LAUNCHER_MAIN) + ['--vm.Xmx2m', '--version'], env, status=1)
    check('-Xmx2m' in out and 'bazel-bin/scheme' in out, f'an unapplied --vm.Xmx2m should be an error naming -Xmx2m:\n{out}')
    out, _ = run(runtime, ['--vm.cp', tmp, '--version'], status=1)
    check('--vm.cp=<classpath>' in out, f'a bare --vm.cp should be an error:\n{out}')
    log('--vm.: the program\'s arguments left alone; one the JVM wasn\'t started with is an error')


def argument_files(o):
    """plan.md M11-03: `--vm.@file` is java's argument file `@file`, as GraalVM's launcher scripts
    pass it, so java reads the file and applies or rejects its options as it does its own; the
    launcher's defaults give way to the options in it as to the others (harness.arg_file_options)."""
    tmp = tempfile.mkdtemp(dir=os.environ.get('TEST_TMPDIR'))

    def arguments(name, text):
        path = os.path.join(tmp, name)
        with open(path, 'w', encoding='utf-8') as f:
            f.write(text)
        return path

    check(split_vm_options(['--vm.@a.args', '--vm.Xmx1g', 'f.scm', '--vm.@b.args']) == (['@a.args', '-Xmx1g'], ['f.scm', '--vm.@b.args']),
          'a --vm.@file before the program file should be java\'s @file')
    # 256 MB is a multiple of every heap alignment (aligned_heap), so HotSpot keeps it as given.
    choices = arguments('choices.args','# The collector, the heap, the compiler threads.\n-XX:+UseG1GC "-Xmx256m"\n-XX:CICompilerCount=2\n')
    check(launcher_vm_args(['@' + choices], {}, cpus=16) == LAUNCHER_OUTPUT_VM_ARGS,
          f'the launcher\'s defaults should give way to the options in {choices}, but for the VM\'s output\'s stream')
    runtime = Runtime.from_manifest(o.runtime)
    f = flags(runtime, args=['--vm.@' + choices])
    check(collector(f) == ['G1'], f'-XX:+UseG1GC in an argument file should replace the serial collector, not {collector(f)}')
    check(f['MaxHeapSize'] == (str(256 << 20), 'command line'), f'-Xmx256m in an argument file should set the maximum heap: {f["MaxHeapSize"]}')
    check(f['CICompilerCount'][0] == '2', f'-XX:CICompilerCount=2 in an argument file should set the count: {f["CICompilerCount"]}')
    f = flags(runtime, jdk_java_options='@' + arguments('parallel.args', '-XX:+UseParallelGC\n'))
    check(collector(f) == ['Parallel'], f'a collector in an argument file in JDK_JAVA_OPTIONS should replace the serial one, not {collector(f)}')
    prop = arguments('property.args', '"-Dscheme.test.vm=a b" # a comment\n-ea\n')
    out, _ = run(runtime, ['--vm.@' + prop, '--vm.XshowSettings:properties', '--version'])
    check(re.search(r'^\s*scheme\.test\.vm = a b$', out, re.M), f'a quoted property in an argument file should be set:\n{out}')
    log('--vm.@file: a collector, the heap, compiler threads, a property; in JDK_JAVA_OPTIONS too')

    # Errors: java's, for an option it doesn't know and a file there is none of; the launcher
    # started with java names the @file to give it.
    out, _ = run(runtime, ['--vm.@' + arguments('unknown.args', '-Xfoo\n'), '--version'], status=1)
    check('Unrecognized option: -Xfoo' in out, f'an unknown option in an argument file should be java\'s error:\n{out}')
    missing = os.path.join(tmp, 'missing.args')
    out, _ = run(runtime, ['--vm.@' + missing, '--version'], status=1)
    check(f'could not open `{missing}\'' in out, f'a missing argument file should be java\'s error:\n{out}')
    env = {k: v for k, v in os.environ.items() if k not in ('JDK_JAVA_OPTIONS', 'JAVA_TOOL_OPTIONS')}
    out = execute(runtime.command([], LAUNCHER_MAIN) + ['--vm.@' + prop, '--version'], env, status=1)
    check(f'(@{prop}, before -m)' in out, f'an unapplied --vm.@file should be an error naming @file:\n{out}')
    log('--vm.@file: an unknown option and a missing file, java\'s errors; unapplied, the launcher\'s')


# A loop that Truffle compiles with OSR as it runs (a self tail call), whose compiled code transfers
# to the interpreter when the loop ends; and a boundary (number->string) in it.
OSR_PROGRAM = """(import (scheme base) (scheme write))
(define (h i acc) (if (= i 0) acc (h (- i 1) (+ acc (string-length (number->string i))))))
(display (h 1000000 0))
"""


def option_classes(o):
    """plan.md M10-04, docs/usage.md "Options": every class of option through bazel-bin/scheme takes
    effect or fails clearly, with a clear message and status."""
    runtime = Runtime.from_manifest(o.runtime)
    tmp = tempfile.mkdtemp(dir=os.environ.get('TEST_TMPDIR'))
    program = os.path.join(tmp, 'osr.scm')
    with open(program, 'w', encoding='utf-8') as f:
        f.write(OSR_PROGRAM)
    sync = ['--experimental-options', '--engine.BackgroundCompilation=false']

    # --engine.: one that takes effect, a VM-wide one (the framework's own engine, the process's
    # first, used to fix it off: "previously initialized"), one there is none of.
    out, _ = run(runtime, sync + ['--engine.TraceCompilation', program])
    check('opt done' in out and '5888896' in out, f'--engine.TraceCompilation should trace the compilations:\n{out}')
    out, _ = run(runtime, sync + ['--engine.TraceTransferToInterpreter', program])
    check('transferToInterpreter at' in out and 'previously initialized' not in out,
          f'--engine.TraceTransferToInterpreter should trace the OSR loop\'s exit:\n{out}')
    out, _ = run(runtime, ['--engine.NoSuchOption', program], status=2)
    check("Unrecognized argument: '--engine.NoSuchOption'" in out, f'--engine.NoSuchOption should be an error:\n{out}')
    log('--engine.: TraceCompilation, TraceTransferToInterpreter (VM-wide), an unknown one')

    # --compiler.: the instrumentation, which libgraal fails every compilation with, so the launcher
    # runs the JVM with Graal compiled for HotSpot, unless its JVM options choose.
    check(LAUNCHER_INSTRUMENT_VM_ARGS[0] in launcher_vm_args([], {}, options=['--compiler.InstrumentBoundaries']),
          'the instrumentation should get Graal compiled for HotSpot')
    check(LAUNCHER_INSTRUMENT_VM_ARGS[0] in launcher_vm_args([], {}, options=['--engine.InstrumentBranches=true']),
          'the instrumentation\'s deprecated --engine. name should get Graal compiled for HotSpot')
    check(LAUNCHER_INSTRUMENT_VM_ARGS[0] in launcher_vm_args(['-Dpolyglot.compiler.InstrumentBranches=true'], {}),
          'the instrumentation as a system property should get Graal compiled for HotSpot')
    check(LAUNCHER_INSTRUMENT_VM_ARGS[0] not in launcher_vm_args(['-XX:+UseJVMCINativeLibrary'], {}, options=['--compiler.InstrumentBranches']),
          'a choice among the JVM options should replace ours')
    check(LAUNCHER_INSTRUMENT_VM_ARGS[0] not in launcher_vm_args([], {}, options=['--compiler.InstrumentBoundaries=false']),
          'no instrumentation, no change')
    out, command = run(runtime, ['--experimental-options', '--compiler.InstrumentBoundaries', program])
    check('Execution profile' in out and 'NumberToString' in out, f'--compiler.InstrumentBoundaries should print the boundaries\' profile:\n{out}')
    check(LAUNCHER_INSTRUMENT_VM_ARGS[0] in command, f'the instrumentation should run without libgraal: {command}')
    env = {k: v for k, v in os.environ.items() if k not in ('JDK_JAVA_OPTIONS', 'JAVA_TOOL_OPTIONS')}
    for option in ('--compiler.InstrumentBranches', '--engine.InstrumentBoundaries'):
        out = execute(runtime.command([], LAUNCHER_MAIN) + ['--experimental-options', option, program], env, status=1)
        check('-XX:-UseJVMCINativeLibrary' in out, f'with libgraal, {option} should be an error naming the JVM option:\n{out}')
    log('--compiler.: the instrumentation, without libgraal; with it, an error')

    # --scheme.: an invalid value, one there is none of, one the launcher sets itself.
    out, _ = run(runtime, ['--scheme.BufferOutput=maybe', program], status=1)
    check('--scheme.BufferOutput=maybe' in out, f'an invalid --scheme. value should be an error:\n{out}')
    out, _ = run(runtime, ['--scheme.NoSuchOption=1', program], status=2)
    check("Unrecognized argument: '--scheme.NoSuchOption=1'" in out, f'--scheme.NoSuchOption should be an error:\n{out}')
    out, _ = run(runtime, ['--vm.Dpolyglot.scheme.StackSize=1m', program], status=1)
    check('--stack-size=' in out, f'scheme.StackSize, which the launcher sets, should be an error naming --stack-size:\n{out}')
    log('--scheme.: invalid, unknown, the launcher\'s own')

    # Constant options (design.md D17, "Constant options"): --help:scheme:internal lists them, and
    # the process fixes them with -Dpolyglot.<name>, which the command line may repeat but not
    # change.
    out, _ = run(runtime, ['--help:scheme:internal'])
    for option in ('--scheme.Torture=off|shared|owned', '--scheme.PreludeImageFile=<file>', '--scheme.PreludeImageWrite=<file>'):
        check(option in out, f'--help:scheme:internal should list {option}:\n{out}')
    out, _ = run(runtime, ['--scheme.Torture=shared', program], status=1)
    check(out.startswith("ERROR: Option 'scheme.Torture' is constant and is already fixed to 'off'") and
          '-Dpolyglot.scheme.Torture=<value>' in out and '\tat ' not in out,
          f'--scheme.Torture=shared without the system property should be an error naming it:\n{out}')
    out, _ = run(runtime, ['--vm.Dpolyglot.scheme.Torture=shared', '--scheme.Torture=shared', program])
    check(out == '5888896', f'--vm.Dpolyglot.scheme.Torture=shared should torture the program, and --scheme. may repeat it: {out}')
    out, _ = run(runtime, ['--scheme.Torture=maybe', program], status=1)
    check("'maybe'" in out and "'owned'" in out, f'an invalid --scheme.Torture value should be an error listing the valid ones:\n{out}')
    log('constant options: listed, fixed by -Dpolyglot.<name>, repeated but not changed')

    # The tools (bazel-bin/scheme's runtime has them; //tests:tools drives each), logging, the
    # framework's other options.
    tools = Runtime.from_manifest(o.aot_runtime)
    out, _ = run(tools, ['--cpusampler', program])
    check('Sampling Histogram' in out, f'--cpusampler should print its histogram:\n{out}')
    out, _ = run(tools, ['--memtracer', program], status=1)
    check("'--memtracer' is experimental" in out, f'--memtracer without --experimental-options should be an error:\n{out}')
    log_file = os.path.join(tmp, 'engine.log')
    out, _ = run(runtime, ['--log.level=FINE', '--log.file=' + log_file, program])
    with open(log_file, encoding='utf-8') as f:
        logged = f.read()
    check('FINE' in logged and 'FINE' not in out, f'--log.file should get the log, not stderr:\n{logged}\n{out}')
    out, _ = run(runtime, ['--polyglot', program])
    check(out == '5888896', f'--polyglot (the default) should run the program: {out}')
    out, _ = run(runtime, ['--native', program], status=1)
    check('--native is not supported' in out, f'--native should be an error on the JVM:\n{out}')
    log('--cpusampler, --memtracer, --log.file and --log.level, --polyglot, --native')

    # Help, version, unknown options.
    out, _ = run(runtime, ['--help:nosuch'], status=2)
    check("Unrecognized help topic: '--help:nosuch'" in out, f'--help:nosuch should be an error:\n{out}')
    out, _ = run(tools, ['--help:tools'])
    check('--cpusampler' in out, f'--help:tools should list the tools\' options:\n{out}')
    out, _ = run(runtime, ['--version'])
    check(out.startswith('R7RS Scheme ('), f'--version: {out}')
    out, _ = run(runtime, ['--no-such-option'], status=2)
    check("Unrecognized argument: '--no-such-option'" in out, f'an unknown option should be an error:\n{out}')
    log('--help:<topic>, --version, an unknown option')


def module_path(o):
    """design.md D20, "The launchers": `--vm.p=<path>` (and `--vm.-module-path=<path>`) is appended to
    the launcher's module path, as GraalVM's thin launcher takes it in its JVM mode, so another
    language's jars there make it a language `polyglot-eval` reaches."""
    runtime = Runtime.from_manifest(o.runtime)
    tmp = tempfile.mkdtemp(dir=os.environ.get('TEST_TMPDIR'))
    program = os.path.join(tmp, 'sl.scm')
    with open(program, 'w', encoding='utf-8') as f:
        f.write('(import (scheme base) (scheme write) (graalvm polyglot))\n'
                '(write (list (polyglot-language-available? "sl")\n'
                '             (guard (e ((error-object? e) \'not-available)) (polyglot-eval "sl" "function main() { return 6 * 7; }"))))\n')
    jars = os.pathsep.join(rlocation(j) for j in o.language_jars)
    out, _ = run(runtime, [program])
    check(out.strip() == '(#f not-available)', f'without its jars, SL should not be available:\n{out}')
    for option in ('--vm.p=', '--vm.-module-path='):
        out, command = run(runtime, [option + jars, program])
        check(out.strip() == '(#t 42)', f'{option}<SL\'s jars> should make SL available:\n{out}')
        check(command.count('-p') == 1 and command[command.index('-p') + 1].endswith(os.pathsep + jars),
              f'{option} should be appended to the launcher\'s module path: {command}')
    log('--vm.p and --vm.-module-path: appended to the module path, another language\'s jars')


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--runtime', required=True)
    parser.add_argument('--aot-runtime', required=True)
    parser.add_argument('--language-jars', nargs='+', required=True, help="another Truffle language's jars (SL's)")
    options = parser.parse_args()
    launcher_vm(options)
    aot_cache(options)
    vm_options(options)
    argument_files(options)
    option_classes(options)
    module_path(options)

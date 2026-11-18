"""Test: design.md D17 "The launcher's JVM": the `scheme` launcher's JVM uses the serial collector, 4
compiler threads where the JVM would start more, and the AOT cache where it is valid and the JVM
takes it, unless its JVM options (on the command line, its `--vm.` options, in JDK_JAVA_OPTIONS or
in JAVA_TOOL_OPTIONS) set those themselves. Its `--vm.<option>`s are the JVM's `-<option>`s ("VM
options"), and the launcher started with java rejects one the JVM wasn't started with. Every class of
option takes effect or fails clearly (plan.md M10-04, docs/usage.md "Options")."""

import argparse
import os
import re
import subprocess
import tempfile

from harness import (AOT_VM_ARGS, LAUNCHER_COMPILER_VM_ARGS, LAUNCHER_GC_VM_ARGS, LAUNCHER_INSTRUMENT_VM_ARGS, LAUNCHER_MAIN, Runtime,
                     check, launcher_vm_args, log, split_vm_options)

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


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--runtime', required=True)
    parser.add_argument('--aot-runtime', required=True)
    options = parser.parse_args()
    launcher_vm(options)
    aot_cache(options)
    vm_options(options)
    option_classes(options)

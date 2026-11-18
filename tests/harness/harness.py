"""What the Python tests (check_*.py) and the commands share: the runtime, our runner and chibi's, the
conformance suites.

Paths: tests see the repository's files in their runfiles; `bazel run` commands read and write the
checkout itself ($BUILD_WORKSPACE_DIRECTORY).
"""

import concurrent.futures
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
from os.path import join

# Flags the runtime reads (plan.md M0-01).
# Compile mode: immediate, synchronous compilation, and compilation failures are errors.
COMPILE_VM_ARGS = [
    '-Dpolyglot.engine.AllowExperimentalOptions=true',  # BackgroundCompilation is experimental
    '-Dpolyglot.engine.CompileImmediately=true',
    '-Dpolyglot.engine.BackgroundCompilation=false',
    '-Dpolyglot.engine.CompilationFailureAction=Throw',
]
# Interpreter only.
INTERPRETER_VM_ARGS = [
    '-Dpolyglot.engine.AllowExperimentalOptions=true',  # engine.Compilation is experimental
    '-Dpolyglot.engine.Compilation=false',
]
# Torture: capture + resume at every non-tail call (the constant option scheme.Torture, design.md
# D17 "Constant options").
TORTURE_VM_ARGS = ['-Dpolyglot.scheme.Torture=shared']
# Owned torture: the same, but the captured frames stay owned, so they are resumed in place
# (design.md "Owned segments").
TORTURE_OWNED_VM_ARGS = ['-Dpolyglot.scheme.Torture=owned']
# Truffle on JDK 25 uses restricted native access and sun.misc.Unsafe; so does JLine, for the
# REPL's terminal (M7-07).
TRUFFLE_VM_ARGS = [
    '--enable-native-access=org.graalvm.truffle,org.graalvm.shadowed.jline',
    '--sun-misc-unsafe-memory-access=allow',
]
LAUNCHER_MAIN = 'scheme.launcher/scheme.launcher.SchemeMain'

# The `scheme` launcher's JVM options (design.md D17), each group unless the JVM options (the
# command's, JDK_JAVA_OPTIONS or JAVA_TOOL_OPTIONS) already set what it sets.
# The collector (M8-05): the serial one, unless they choose another (the JVM refuses two).
LAUNCHER_GC_VM_ARGS = ['-XX:+UseSerialGC']
_GC_CHOICE = re.compile(r'^-XX:\+Use\w+GC$')
# The host's JIT compiler threads ("Compiler threads"): 4, where the JVM would start more (with
# 16 processors or more: it starts 3 with 4 to 7, 4 with 8 to 15, 12 with 16).
LAUNCHER_COMPILER_VM_ARGS = ['-XX:CICompilerCount=4']
_COMPILER_CHOICE = re.compile(r'^-XX:(CICompilerCount=|[+-]CICompilerCountPerCPU$)')
# The compiler's instrumentation (`--compiler.InstrumentBoundaries`, `--compiler.InstrumentBranches`):
# GraalVM 25.5's libgraal fails every compilation it instruments (docs/upstream.md), so with one of
# them on (or their deprecated `--engine.` names), the JVM compiles with Graal compiled for HotSpot
# (design.md D17, "Options"), unless the JVM options choose.
LAUNCHER_INSTRUMENT_VM_ARGS = ['-XX:-UseJVMCINativeLibrary']
_INSTRUMENT_OPTION = re.compile(r'^--(compiler|engine)\.Instrument(Boundaries|Branches)(=true)?$')
_INSTRUMENT_PROPERTY = re.compile(r'^-Dpolyglot\.(compiler|engine)\.Instrument(Boundaries|Branches)=true$')
_LIBGRAAL_CHOICE = re.compile(r'^-XX:[+-]UseJVMCINativeLibrary$')
# The AOT cache ("The AOT cache", bazel/java_module.bzl: scheme_runtime): the run-time options it
# was trained with. The launcher passes it with -XX:AOTMode=on, with which a JVM that can't use it
# stops as it starts and says why, rather than starting with no class sharing at all; so only
# where the JVM takes it. It does with the launcher's own options (the cache's), and with the ones
# in _AOT_TAKEN. With any other JVM option (the object layout, the heap's size, the collector, the
# modules, an agent, ...: the JVM checks dozens, some by their values), the launcher asks a JVM
# started with them first (_AOT_PROBE_ARGS), and leaves the cache out if it doesn't. Not with
# options that choose a cache, an archive or class sharing themselves.
AOT_VM_ARGS = ['--add-modules=jdk.internal.vm.ci']
_AOT_REQUIRED_VM_ARGS = ['-XX:AOTMode=on']
# java's --dry-run: the JVM starts and loads the main class, and runs nothing. With -XX:AOTMode=on,
# exit status 0 if it took the cache (about 40 ms); 1, about 10 ms in, if it didn't.
_AOT_PROBE_ARGS = ['--dry-run']
_AOT_PROBE_TIMEOUT = 10
# Options the JVM takes the cache with, which need no asking: system properties other than the
# JDK's (FileMapHeader::validate and FileMapInfo::validate_aot_class_linking check
# java.system.class.loader and java.security.manager, Modules::ArchivedProperty the jdk.module.*
# the module options set), the thread stacks' size and logging, which it doesn't check the cache
# against; and the collectors whose archived objects it maps or loads (HeapShared), which
# //tests:launcher_vm checks it takes. Were one wrong, the run would be the JVM's error.
_AOT_TAKEN = re.compile(r'^(-D(?!(java|jdk|sun)\.)|-Xss|-Xlog:|-XX:\+Use(Serial|Parallel|G1)GC$)')
_AOT_CHOICE = re.compile(r'^(-Xshare:|-XX:(SharedArchiveFile|AOTCache|AOTCacheOutput|AOTMode|AOTConfiguration|'
                         r'ArchiveClassesAtExit|[+-]AutoCreateSharedArchive)\b)')


def _launcher_options(args):
    """The launcher's options among its arguments: those before `--` or the program file, as
    SchemeMain.preprocessArguments takes them (an `-I` directory isn't one)."""
    options = []
    i = 0
    while i < len(args):
        arg = args[i]
        if arg == '-I':
            i += 2
            continue
        if arg == '--' or not arg.startswith('-') or len(arg) == 1:
            break
        options.append(arg)
        i += 1
    return options


def split_vm_options(args):
    """The `scheme` launcher's arguments, its `--vm.<option>`s taken out as JVM options, as GraalVM's
    launcher scripts take them (the framework ignores them in a JVM, which has already started):
    `--vm.cp=<path>` and `--vm.classpath=<path>` are the class path, `--vm.p=<path>` and
    `--vm.-module-path=<path>` a module path, which Runtime.launcher appends to its own as GraalVM's
    thin launcher does in its JVM mode (launcher.cc, VM_P_ARG_PREFIX: another language's jars,
    design.md D20, "The launchers"), `--vm.@<file>` the argument file `@<file>` (java reads it:
    GraalVM's launcher_template.sh does the same), any other the JVM's `-<option>`. Only the launcher's options are: they end where SchemeMain.preprocessArguments
    ends them, at `--` or the program file, and the program's arguments are left alone. Returns
    (the JVM options, the other arguments)."""
    jvm_args, rest = [], []
    i = 0
    while i < len(args):
        arg = args[i]
        if arg == '-I':
            rest += args[i:i + 2]
            i += 2
            continue
        if arg == '--' or not arg.startswith('-') or len(arg) == 1:
            break
        option = arg[len('--vm.'):] if arg.startswith('--vm.') else ''
        # A bare --vm.cp, --vm.classpath, --vm.p or --vm.-module-path stays, for the launcher to
        # reject.
        if option.startswith(('cp=', 'classpath=')):
            jvm_args += ['-cp', option.split('=', 1)[1]]
        elif option.startswith(('p=', '-module-path=')):
            jvm_args += ['--module-path', option.split('=', 1)[1]]
        elif option.startswith('@'):
            jvm_args.append(option)
        elif option and option not in ('cp', 'classpath', 'p', '-module-path'):
            jvm_args.append('-' + option)
        else:
            rest.append(arg)
        i += 1
    return jvm_args, rest + list(args[i:])


def _module_path_options(jvm_args):
    """jvm_args without the `--module-path <path>` pairs that split_vm_options made of `--vm.p=<path>`,
    and those paths: the launcher appends them to its module path rather than replace it."""
    rest, paths, i = [], [], 0
    while i < len(jvm_args):
        if jvm_args[i] == '--module-path' and i + 1 < len(jvm_args):
            paths.append(jvm_args[i + 1])
            i += 2
        else:
            rest.append(jvm_args[i])
            i += 1
    return rest, paths


def _given_vm_args(jvm_args, env):
    """The JVM options a launcher's JVM gets besides its defaults: the environment's, then jvm_args,
    with the options in their argument files (`@file`, which java reads in JDK_JAVA_OPTIONS and
    on the command line) in their place, so that the launcher's defaults give way to those too."""
    given = env.get('JAVA_TOOL_OPTIONS', '').split()
    for arg in env.get('JDK_JAVA_OPTIONS', '').split() + list(jvm_args):
        given += arg_file_options(arg[1:]) if arg.startswith('@') else [arg]
    return given


def arg_file_options(path):
    """The options in a JVM argument file, as java reads them: white space separates options, `#`
    comments to the end of the line, quotes keep white space, and escapes and line continuations
    work inside quotes. It follows GraalVM's launcher.cc's port of the JDK's libjli/args.c, as
    scheme.launcher.ArgumentFile does. Only the launcher's choice of its defaults reads them: java
    reads the file itself, and reports one it can't read (none here)."""
    try:
        with open(path, 'rb') as f:
            data = f.read()
    except OSError:
        return []
    options, token, state, quote, i = [], bytearray(), 'next', None, 0
    escapes = {ord('n'): b'\n', ord('r'): b'\r', ord('t'): b'\t', ord('f'): b'\f'}

    def end():
        options.append(token.decode('utf-8', 'surrogateescape'))
        token.clear()

    while i < len(data):
        ch = data[i:i + 1]
        i += 1
        if state in ('next', 'skip'):
            if ch in b' \n\r\t\f':
                continue
            state = 'token' if state == 'next' else 'quote'
        elif state == 'escape':
            state = 'skip' if ch in b'\n\r' else 'quote'
            if state == 'quote':
                token += escapes.get(ch[0], ch)
            continue
        elif state == 'comment':
            if ch in b'\n\r':
                state = 'next'
            continue
        if ch in b' \t\f' and state == 'quote':
            token += ch
        elif ch in b' \t\f\n\r':
            end()
            state, quote = 'next', None
        elif ch == b'#' and state != 'quote':
            state = 'comment'
        elif ch == b'\\' and state == 'quote':
            state = 'escape'
        elif ch in b'\'"' and not (state == 'quote' and quote != ch):
            state, quote = ('quote', ch) if state == 'token' else ('token', quote)
        else:
            token += ch
    if token:
        end()
    return options


def launcher_gc_vm_args(jvm_args, env=os.environ):
    """LAUNCHER_GC_VM_ARGS, unless jvm_args or the JVM's environment variables already choose a
    collector: the JVM refuses to start with two."""
    return [] if any(_GC_CHOICE.match(a) for a in _given_vm_args(jvm_args, env)) else list(LAUNCHER_GC_VM_ARGS)


def launcher_vm_args(jvm_args, env=os.environ, cpus=None, options=()):
    """The launcher's default JVM options that jvm_args and the environment's leave to it: the
    collector, the compiler threads, and with the compiler's instrumentation among the launcher's
    options (or the JVM's), Graal compiled for HotSpot."""
    given = _given_vm_args(jvm_args, env)
    args = launcher_gc_vm_args(jvm_args, env)
    if (cpus or os.cpu_count() or 1) >= 16 and not any(_COMPILER_CHOICE.match(a) for a in given):
        args += LAUNCHER_COMPILER_VM_ARGS
    if ((any(_INSTRUMENT_OPTION.match(o) for o in options) or any(_INSTRUMENT_PROPERTY.match(a) for a in given))
            and not any(_LIBGRAAL_CHOICE.match(a) for a in given)):
        args += LAUNCHER_INSTRUMENT_VM_ARGS
    return args


def vm_args(compile=False, torture=False):
    """Interpreter only unless compile, plus torture (true, or 'owned' for owned torture)."""
    args = list(COMPILE_VM_ARGS if compile else INTERPRETER_VM_ARGS)
    if torture == 'owned':
        args += TORTURE_OWNED_VM_ARGS
    elif torture:
        args += TORTURE_VM_ARGS
    return args


def log(message):
    print(message, flush=True)


def fail(message):
    print(message, file=sys.stderr, flush=True)
    sys.exit(1)


def check(condition, message):
    if not condition:
        fail(message)


def tail(text, lines=5):
    return '\n'.join('    ' + l for l in text.rstrip('\n').splitlines()[-lines:]) or '    (empty)'


def runfiles_dir():
    for name in ('RUNFILES_DIR', 'TEST_SRCDIR'):
        if os.environ.get(name):
            return os.environ[name]
    # Run directly (bazel-bin/scheme): this file is in the binary's runfiles tree.
    here = os.path.abspath(__file__)
    return here[:here.index('.runfiles' + os.sep) + len('.runfiles')]


def rlocation(path):
    return join(runfiles_dir(), path)


def repo_root():
    """The source tree: the checkout under `bazel run`, else the runfiles."""
    return os.environ.get('BUILD_WORKSPACE_DIRECTORY') or rlocation('_main')


def workspace():
    """The checkout, for commands that write into it."""
    check('BUILD_WORKSPACE_DIRECTORY' in os.environ, 'run this with `bazel run`')
    return os.environ['BUILD_WORKSPACE_DIRECTORY']


class Runtime:
    """The pinned GraalVM's `java` and a module path (bazel/java_module.bzl: scheme_runtime)."""

    def __init__(self, java, module_path, release=None, aot_cache=None, aot_stamp=None):
        self.java = java
        self.module_path = os.pathsep.join(module_path)
        self.release = release
        self.aot_cache = aot_cache
        self.aot_stamp = aot_stamp

    @staticmethod
    def from_manifest(manifest):
        """From a scheme_runtime's JSON manifest, given as a runfiles path."""
        with open(rlocation(manifest), encoding='utf-8') as f:
            data = json.load(f)
        aot = [rlocation(data[k]) if data.get(k) else None for k in ('aot_cache', 'aot_stamp')]
        return Runtime(rlocation(data['java']), [rlocation(p) for p in data['module_path']],
                       rlocation(data['release']) if data['release'] else None, *aot)

    def command(self, jvm_args, main, module_path=None):
        """java, the JVM options, the module path with every module resolved, and `-m main`."""
        tmp = os.environ.get('TEST_TMPDIR')
        return ([self.java] + ([f'-Djava.io.tmpdir={tmp}'] if tmp else []) + list(jvm_args)
                + ['-p', module_path or self.module_path, '--add-modules', 'ALL-MODULE-PATH'] + TRUFFLE_VM_ARGS + ['-m', main])

    def aot_module_path(self):
        """The module path as the AOT cache names it, its jars' real paths, if the cache is the one
        for these files as they are here; else None. The JVM compares the size, modification time
        and real path of the JDK's modules image and of each jar with the cache's, and the module
        path's entries as given with the real paths it was made with; with a cache it can't use,
        it starts with no class sharing at all, slower than with no cache. A cache made elsewhere
        (the executors') or before a file was replaced is stale."""
        if not self.aot_cache or not self.aot_stamp:
            return None
        jars = self.module_path.split(os.pathsep)
        try:
            with open(self.aot_stamp, encoding='utf-8') as f:
                stamp = f.read().splitlines()
            here = []
            for path in [join(os.path.dirname(os.path.dirname(self.java)), 'lib', 'modules')] + jars:
                st = os.stat(path)
                here.append(f'{st.st_size} {int(st.st_mtime)} {os.path.realpath(path)}')
        except OSError:
            return None
        return os.pathsep.join(os.path.realpath(j) for j in jars) if stamp == here else None

    def launcher(self, args, jvm_args=(), env=os.environ):
        """The command line that runs the `scheme` launcher with these arguments, with its JVM
        options (launcher_vm_args), and the AOT cache where it is valid, the JVM options don't
        choose class sharing, and the JVM takes it with them (aot_cache_taken). The arguments'
        `--vm.` options are JVM options (split_vm_options), after jvm_args."""
        vm_options, args = split_vm_options(args)
        vm_options, modules = _module_path_options(vm_options)
        jvm_args = list(jvm_args) + vm_options
        defaults = launcher_vm_args(jvm_args, env, options=_launcher_options(args))
        given = _given_vm_args(jvm_args, env)
        module_path = None
        if modules:
            # Appended to the launcher's, as GraalVM's thin launcher does; the AOT cache is for the
            # launcher's module path alone.
            module_path = os.pathsep.join([self.module_path] + modules)
        elif not any(_AOT_CHOICE.match(a) for a in given):
            module_path = self.aot_module_path()
            if module_path:
                aot = AOT_VM_ARGS + _AOT_REQUIRED_VM_ARGS + [f'-XX:AOTCache={self.aot_cache}']
                # Those the launcher's arguments add (the instrumentation's) aren't the cache's.
                checked = [a for a in given if not _AOT_TAKEN.match(a)]
                if ((checked or defaults != launcher_vm_args(jvm_args, env))
                        and not self.aot_cache_taken(defaults + aot + jvm_args, module_path, env)):
                    module_path = None
                else:
                    defaults += aot
        return self.command(defaults + list(jvm_args), LAUNCHER_MAIN, module_path) + list(args)

    def aot_cache_taken(self, jvm_args, module_path, env):
        """Whether the launcher's JVM, started with these options (-XX:AOTCache and
        -XX:AOTMode=on among them), takes the AOT cache: a start that runs nothing (_AOT_PROBE_ARGS),
        its output dropped. JAVA_TOOL_OPTIONS and JDK_JAVA_OPTIONS in env count, as they do in the
        run itself."""
        try:
            return subprocess.run(self.command(jvm_args + _AOT_PROBE_ARGS, LAUNCHER_MAIN, module_path), env=env,
                                  stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                                  timeout=_AOT_PROBE_TIMEOUT).returncode == 0
        except subprocess.TimeoutExpired:
            return False

    def run(self, jvm_args, main, args, cwd=None, env=None, timeout=None):
        """Runs java; returns (exit status, stdout, stderr)."""
        p = subprocess.run(self.command(jvm_args, main) + list(args), cwd=cwd, env=env, stdin=subprocess.DEVNULL,
                           stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, encoding='utf-8',
                           errors='replace', timeout=timeout)
        return p.returncode, p.stdout, p.stderr


def run_ours(runtime, file, mode_args, jvm_args=()):
    """One program with the launcher. Returns (exit status, stdout, stderr)."""
    return runtime.run(list(jvm_args) + list(mode_args), LAUNCHER_MAIN, [file])


def run_chibi(chibi, file):
    p = subprocess.run([chibi, file], stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, encoding='utf-8',
                       errors='replace')
    return p.returncode, p.stdout, p.stderr


# ---------------------------------------------------------------------------
# The conformance suites: chibi's (tests/r7rs) and Larceny's (tests/r7rs-larceny)
# ---------------------------------------------------------------------------

RESULT_LINE = re.compile(r'^@@r7rs-test (BEGIN|PASS|FAIL|ERROR|SKIP|END) ?(.*)$')
_SECTION_LINE = re.compile(r'^@@section (\S+) (.*)$')

# Sections whose results depend on speed, so they always run compiled (compile immediately), where
# they are deterministic: (scheme time)'s second test checks that jiffies are accurate to a tenth
# of a second by polling the clock between runs of a million-iteration loop.
LARCENY_JIT = {'time'}


def count_results(output):
    """Counts the shims' @@r7rs-test lines (tests/lib/chibi/test.sld, tests/lib/tests/scheme/test.sld)."""
    counts = {'pass': 0, 'fail': 0, 'error': 0, 'skip': 0, 'complete': False}
    depth = 0
    for line in output.splitlines():
        m = RESULT_LINE.match(line)
        if not m:
            continue
        kind = m.group(1)
        if kind == 'BEGIN':
            depth += 1
        elif kind == 'END':
            depth -= 1
            counts['complete'] = depth == 0
        else:
            counts[kind.lower()] += 1
    return counts


class Section:
    """One conformance section: a program the runner runs in a fresh context, and its total."""

    def __init__(self, suite, section_id, title, file, total, args=(), env=(), exit_status=None, jit=False):
        self.suite = suite
        self.id = section_id
        self.title = title
        self.file = file
        self.total = total
        self.args = list(args)
        self.env = list(env)  # ['NAME=value', ...]
        # None: the program must finish normally. Otherwise it must exit with this status, which
        # counts as one more passing test (as Larceny's testall scripts count it).
        self.exit_status = exit_status
        self.jit = jit


class Suite:
    def __init__(self, name, directory, heading, column):
        self.name = name
        self.rel_dir = 'tests/' + directory
        self.dir = join(repo_root(), 'tests', directory)
        self.heading = heading  # Markdown, for the status block and run summaries
        self.column = column  # what one row is

    def _rows(self):
        with open(join(self.dir, 'sections.tsv'), encoding='utf-8') as f:
            return [line.rstrip('\n').split('\t') for line in f if line.strip() and not line.startswith('#')]

    def exceptions(self):
        """{section id: [test, ...]}: tests that fail by design."""
        return {}

    def make_workdir(self, workdir):
        pass


class ChibiSuite(Suite):
    """chibi-scheme's r7rs-tests.scm, split by section (tests/r7rs/, M1-01)."""

    def __init__(self):
        super().__init__('chibi', 'r7rs', "chibi-scheme's R7RS suite (`tests/r7rs/`)", 'Section')

    def sections(self):
        return [Section(self, section_id, title, join(self.dir, section_id + '.scm'), int(total))
                for section_id, title, total in self._rows()]

    def runner_args(self, workdir):
        return ['-I', join(repo_root(), 'tests', 'lib')]


class LarcenySuite(Suite):
    """Larceny's R7RS test libraries, one program per (scheme ...) library (tests/r7rs-larceny/, M4-11)."""

    def __init__(self):
        super().__init__('larceny', 'r7rs-larceny', "Larceny's R7RS test suite (`tests/r7rs-larceny/`)", 'Library')

    def program(self, name):
        return join(self.dir, 'tests', 'scheme', 'run', name + '.sps')

    def sections(self):
        def words(field):
            return [] if field == '-' else field.split(' ')
        return [Section(self, section_id, title, self.program(program), int(total), words(args), words(env),
                        None if exit_status == '-' else int(exit_status), jit=section_id in LARCENY_JIT)
                for section_id, title, program, args, env, exit_status, total, _ in self._rows()]

    def exceptions(self):
        """The README's Exceptions table (plan.md M4-12): | `section` | `test` | Larceny's result | R7RS text |."""
        listed = {}
        with open(join(self.dir, 'README.md'), encoding='utf-8') as f:
            in_table = False
            for line in f:
                if line.startswith('## '):
                    in_table = line.strip() == '## Exceptions'
                elif in_table and line.startswith('| `'):
                    cells = [c.strip() for c in line.strip().strip('|').split(' | ')]
                    listed.setdefault(cells[0].strip('`'), []).append(cells[1].strip('`'))
        return listed

    def runner_args(self, workdir):
        return ['-I', join(repo_root(), 'tests', 'lib'), '-I', self.dir, '-C', workdir]

    def make_workdir(self, workdir):
        # The suite loads tests/scheme/*.scm relative to its working directory and writes scratch
        # files there: a fresh directory whose tests/ is the vendored one.
        os.symlink(join(self.dir, 'tests'), join(workdir, 'tests'))


def suites(name='all'):
    return [s for s in (ChibiSuite(), LarcenySuite()) if name in ('all', s.name)]


def count_section(section, output, message):
    counts = count_results(output)
    counts['message'] = message
    counts['output'] = output
    if section.exit_status is not None:
        counts['complete'] = message == f'exit {section.exit_status}'
        if counts['complete']:
            counts['pass'] += 1
    return counts


def failing_tests(output):
    """The tests that failed or raised, as the shims report them (the part before ' :: ')."""
    tests = []
    for line in output.splitlines():
        m = RESULT_LINE.match(line)
        if m and m.group(1) in ('FAIL', 'ERROR'):
            tests.append(m.group(2).split(' :: ', 1)[0])
    return tests


def listed_exceptions(suite, section, output):
    """For each failing test of the section, in order: whether the suite lists it as an exception."""
    listed = list(suite.exceptions().get(section.id, []))
    marks = []
    for test in failing_tests(output):
        marks.append(test in listed)
        if marks[-1]:
            listed.remove(test)
    return marks


def exception_count(suite, section, result):
    """How many of a section's failing tests the suite lists as exceptions: counted from its
    output, or as a result records it."""
    if 'exceptions' in result:
        return result['exceptions']
    return sum(listed_exceptions(suite, section, result['output']))


def _temp_dir(prefix):
    return tempfile.mkdtemp(prefix=prefix, dir=os.environ.get('TEST_TMPDIR'))


def run_conformance(runtime, sections, compile=False, torture=False, jobs=1, native=None):
    """Runs sections with scheme.launcher.ConformanceRunner, each in a fresh context; or, with
    `native` (a native launcher's path), each in a process of its own.

    jobs=1 runs one JVM per suite and mode. With more, one JVM per section, `jobs` at a time, the
    largest first: the same results, in the time of the slowest section. A section that timed out
    among them (ConformanceRunner gives each 120 s, and (scheme lazy)'s 1M-iteration loops take a
    minute interpreted) runs once more, alone.

    Returns {(suite name, section id): {'pass', 'fail', 'error', 'complete', 'message', 'output'}}."""
    if native:
        check(not torture, '--torture needs the JVM: a native image fixes scheme.Torture, a constant option, at build time (design.md D17)')
        with concurrent.futures.ThreadPoolExecutor(max_workers=os.cpu_count() or 1) as pool:
            counts = list(pool.map(lambda s: _run_native(native, s, compile), sections))
        return {(s.suite.name, s.id): c for s, c in zip(sections, counts)}
    if jobs == 1:
        groups = []
        for suite in {s.suite.name: s.suite for s in sections}.values():
            for jit in (False, True):
                mine = [s for s in sections if s.suite is suite and s.jit == jit]
                if mine:
                    groups.append(mine)
    else:
        groups = [[s] for s in sorted(sections, key=lambda s: -s.total)]
    results = {}
    with concurrent.futures.ThreadPoolExecutor(max_workers=jobs) as pool:
        for group_results in pool.map(lambda g: _run_group(runtime, g, compile, torture), groups):
            results.update(group_results)
    if jobs > 1:
        timed_out = [s for s in sections if results[(s.suite.name, s.id)]['message'].startswith('timeout')]
        if timed_out:
            log('timed out next to other JVMs, running again alone: ' + ' '.join(s.id for s in timed_out))
            for s in timed_out:
                results.update(_run_group(runtime, [s], compile, torture))
    return results


def _run_group(runtime, mine, compile, torture):
    """Sections of one suite and mode, in one JVM."""
    suite = mine[0].suite
    workdir = _temp_dir(f'r7rs-{suite.name}-')
    try:
        suite.make_workdir(workdir)
        files = []
        for s in mine:
            files += [a for arg in s.args for a in ('--arg', arg)]
            files += [a for binding in s.env for a in ('--env', binding)]
            files.append(s.file)
        rc, out, err = runtime.run(vm_args(compile=compile or mine[0].jit, torture=torture),
                                   'scheme.launcher/scheme.launcher.ConformanceRunner', suite.runner_args(workdir) + files)
        out += err
    finally:
        shutil.rmtree(workdir, ignore_errors=True)
    # One @@section line per file, in order.
    results = {}
    done = []
    current = ''
    for line in out.splitlines(keepends=True):
        m = _SECTION_LINE.match(line)
        if m and len(done) < len(mine):
            section = mine[len(done)]
            results[(suite.name, section.id)] = count_section(section, current, m.group(2))
            done.append(section)
            current = ''
        else:
            current += line
    if rc != 0 or len(done) != len(mine):
        fail(f'ConformanceRunner failed (exit {rc}); no result for {[s.id for s in mine[len(done):]]}:\n{out}')
    return results


def launcher_options(jvm_args):
    """The launcher options that set what these -Dpolyglot.* JVM arguments set (design.md D17)."""
    options = []
    for arg in jvm_args:
        m = re.match(r'^-Dpolyglot\.(.*)$', arg)
        check(m, f'no launcher option for {arg}')
        options.append('--experimental-options' if m.group(1) == 'engine.AllowExperimentalOptions=true' else '--' + m.group(1))
    return options


_NATIVE_SECTION_TIMEOUT = 120  # seconds, as ConformanceRunner's watchdog
# The launcher's status for a program that ends without exit (--end-status), so a section that must
# call (exit 0) can't pass by just ending. No section expects it.
_NATIVE_END_STATUS = 99


def _run_native(launcher, section, compile):
    """A section through the native launcher (M6-06), as ConformanceRunner runs it: library path,
    working directory, arguments, environment; stdout and stderr together."""
    options = launcher_options(vm_args(compile=compile or section.jit))
    workdir = _temp_dir(f'r7rs-{section.suite.name}-native-')
    try:
        section.suite.make_workdir(workdir)
        library_path = section.suite.runner_args(workdir)
        if '-C' in library_path:  # the working directory is the process's
            i = library_path.index('-C')
            del library_path[i:i + 2]
        env = dict(os.environ)
        env.update(binding.split('=', 1) for binding in section.env)
        try:
            p = subprocess.run([launcher] + options + library_path + [f'--end-status={_NATIVE_END_STATUS}', section.file] + section.args,
                               cwd=workdir, env=env, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                               stderr=subprocess.STDOUT, timeout=_NATIVE_SECTION_TIMEOUT, check=False)
            output = p.stdout.decode('utf-8', errors='replace')
            message = 'ok' if p.returncode == _NATIVE_END_STATUS else f'exit {p.returncode}'
        except subprocess.TimeoutExpired as e:
            output = (e.stdout or b'').decode('utf-8', errors='replace')
            message = f'timeout after {_NATIVE_SECTION_TIMEOUT}s'
    finally:
        shutil.rmtree(workdir, ignore_errors=True)
    return count_section(section, output, message)


STATUS_BEGIN = '<!-- status:begin -->'
STATUS_END = '<!-- status:end -->'


def suite_block(suite, results):
    """One suite's part of the status block in docs/plan.md."""
    listing = bool(suite.exceptions())
    lines = ['', suite.heading + ':', '']
    if listing:
        lines += ['Exceptions: failing tests that expect a result where R7RS says "it is an error",',
                  f'listed in `{suite.rel_dir}/README.md`.', '']
    lines += [f'| {suite.column} | Passing | Tests |' + (' Exceptions |' if listing else ''),
              '|---|---:|---:|' + ('---:|' if listing else '')]
    total_pass = total = total_exceptions = 0
    for s in suite.sections():
        result = results[(suite.name, s.id)]
        total_pass += result['pass']
        total += s.total
        row = f'| {s.title}{" (compiled: depends on speed)" if s.jit else ""} | {result["pass"]} | {s.total} |'
        if listing:
            exceptions = exception_count(suite, s, result)
            total_exceptions += exceptions
            row += f' {exceptions} |'
        lines.append(row)
    lines.append(f'| **Total** | **{total_pass}** | **{total}** |' + (f' **{total_exceptions}** |' if listing else ''))
    return '\n'.join(lines)


def status_block(suite_blocks):
    """The generated block in docs/plan.md, from each suite's part in order (chibi, Larceny)."""
    lines = [STATUS_BEGIN,
             'Conformance against the vendored suites, interpreter only (except where a row',
             'says compiled). Generated by `bazel run //:r7rs -- status`; `bazel test //...`',
             'fails if it is stale. Don\'t edit it by hand.']
    return '\n'.join(lines + [b.rstrip('\n') for b in suite_blocks] + [STATUS_END])


def replace_status_block(text, block):
    """(text with its status block replaced by block, the block it had)."""
    begin, end = text.index(STATUS_BEGIN), text.index(STATUS_END) + len(STATUS_END)
    return text[:begin] + block + text[end:], text[begin:end]


def _expected_results(suite):
    """What each section of a suite gives when its tests pass: its total, but for the tests the
    suite lists as exceptions (tests/r7rs-larceny/README.md), which fail."""
    exceptions = suite.exceptions()
    return {(suite.name, s.id): {'pass': s.total - len(exceptions.get(s.id, [])), 'exceptions': len(exceptions.get(s.id, [])),
                                 'complete': True, 'message': 'ok'}
            for s in suite.sections()}


def expected_status_block():
    """docs/plan.md's status block: what the conformance tests check, when they all pass."""
    return status_block([suite_block(suite, _expected_results(suite)) for suite in suites()])


def graalvm_version(release):
    """The GraalVM version a JDK's `release` file names."""
    with open(release, encoding='utf-8') as f:
        m = re.search(r'^GRAALVM_VERSION="?([^"\n]*)"?', f.read(), re.MULTILINE)
    return m.group(1) if m else 'unknown'

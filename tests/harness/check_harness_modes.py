"""Test: the local commands' modes (plan.md M14-10). `bazel run //:r7rs -- test --compile`, `--torture`
and `--torture-owned`, and the fuzzer's `compile`, `torture` and `torture-owned`, start the JVM with
Java's and Truffle's detailed assertions (harness.ASSERTION_VM_ARGS), as the Bazel compile and torture
tests do; the interpreter's runs don't, and the native launcher's conformance runs get only the
launcher options of the mode (launcher_options, which has no option for a JVM argument), as do the
fuzzer's `native` mode's (M14-11), whose launcher is named or found, or the command says how to get
one. The command lines are the ones each command builds, with no JVM started: subprocess.run is
replaced."""

import argparse
import contextlib
import io
import tempfile
import unittest
from unittest import mock

import fuzz
import harness
from harness import ASSERTION_VM_ARGS, Runtime

JAVA = '/graalvm/bin/java'
RUNTIME = Runtime(JAVA, ['/modules/scheme.jar'])


class Stop(Exception):
    """Ends a command where it would start its process."""


class Recorder:
    """subprocess.run, recording each command line; with stop, a run raises Stop."""

    def __init__(self, stop=True):
        self.stop = stop
        self.commands = []

    def __call__(self, command, **kwargs):
        self.commands.append(list(command))
        if self.stop:
            raise Stop()
        return mock.Mock(returncode=0, stdout=b'', stderr=b'')


class FakeSuite:
    name = 'chibi'

    def make_workdir(self, workdir):
        pass

    def runner_args(self, workdir):
        return []


def section(jit=False):
    return harness.Section(FakeSuite(), '6.1', 'Equivalence predicates', '/suite/6.1.scm', 1, jit=jit)


def jvm_options(command):
    """A java command line's JVM options: those before its module path."""
    return command[1:command.index('-p')]


def has_assertions(options):
    return all(a in options for a in ASSERTION_VM_ARGS)


class Conformance(unittest.TestCase):
    """`bazel run //:r7rs -- test` and its modes: one ConformanceRunner JVM per group of sections."""

    def jvm_options(self, compile=False, torture=False, jit=False):
        recorder = Recorder()
        with mock.patch('subprocess.run', recorder), self.assertRaises(Stop):
            harness._run_group(RUNTIME, [section(jit)], compile, torture)
        self.assertEqual(len(recorder.commands), 1)
        self.assertEqual(recorder.commands[0][0], JAVA)
        return jvm_options(recorder.commands[0])

    def test_the_interpreter_runs_without_the_assertions(self):
        options = self.jvm_options()
        self.assertIn('-Dpolyglot.engine.Compilation=false', options)
        self.assertNotIn('-ea', options)
        self.assertNotIn('-Dtruffle.DetailedAssertions=true', options)

    def test_compile_runs_with_them(self):
        options = self.jvm_options(compile=True)
        self.assertIn('-Dpolyglot.engine.CompileImmediately=true', options)
        self.assertTrue(has_assertions(options), options)

    def test_a_section_that_needs_the_jit_runs_compiled_with_them(self):
        self.assertTrue(has_assertions(self.jvm_options(jit=True)))

    def test_both_tortures_run_with_them(self):
        for torture, option in ((True, '-Dpolyglot.scheme.Torture=shared'), ('owned', '-Dpolyglot.scheme.Torture=owned')):
            options = self.jvm_options(torture=torture)
            self.assertIn(option, options)
            self.assertIn('-Dpolyglot.engine.Compilation=false', options)
            self.assertTrue(has_assertions(options), options)


class Native(unittest.TestCase):
    """The native launcher's conformance runs (//tests:conformance_native): the mode's launcher
    options, and no JVM option, which the native launcher would refuse."""

    def command(self, compile=False, jit=False):
        recorder = Recorder()
        with mock.patch('subprocess.run', recorder), self.assertRaises(Stop):
            harness._run_native('/bin/scheme-native', section(jit), compile)
        self.assertEqual(len(recorder.commands), 1)
        return recorder.commands[0]

    def test_compile_gives_launcher_options_only(self):
        command = self.command(compile=True)
        self.assertEqual(command[0], '/bin/scheme-native')
        self.assertIn('--engine.CompileImmediately=true', command)
        self.assertIn('--experimental-options', command)
        for arg in command[1:]:
            self.assertFalse(arg.startswith('-D') or arg == '-ea', command)

    def test_the_interpreter_too(self):
        command = self.command()
        self.assertIn('--engine.Compilation=false', command)
        self.assertNotIn('-ea', command)

    def test_a_jvm_option_has_no_launcher_option(self):
        # It isn't dropped: the harness stops, naming it.
        err = io.StringIO()
        with contextlib.redirect_stderr(err), self.assertRaises(SystemExit):
            harness.launcher_options(harness.vm_args(compile=True))
        self.assertIn('no launcher option for -ea', err.getvalue())


class Fuzzer(unittest.TestCase):
    """`bazel run //:r7rs -- fuzz --mode ...`: each file of programs in the launcher's JVM."""

    def jvm_options(self, mode):
        recorder = Recorder(stop=False)
        with tempfile.TemporaryDirectory() as workdir, mock.patch('subprocess.run', recorder):
            fuzz.Runner('/bin/chibi-scheme', RUNTIME, workdir).run_ours('(display 1)', mode)
        self.assertEqual(len(recorder.commands), 1)
        command = recorder.commands[0]
        self.assertEqual(command[0], JAVA)
        self.assertTrue(command[-1].endswith('.scm'), command)
        return jvm_options(command)

    def test_every_mode_but_the_interpreter_runs_with_the_assertions(self):
        self.assertEqual(set(fuzz.MODES), {'interp', 'compile', 'torture', 'torture-owned', 'native'})
        options = self.jvm_options('interp')
        self.assertNotIn('-ea', options)
        self.assertNotIn('-Dtruffle.DetailedAssertions=true', options)
        for mode in ('compile', 'torture', 'torture-owned'):
            options = self.jvm_options(mode)
            self.assertTrue(has_assertions(options), (mode, options))

    def test_native_runs_the_native_launcher_as_conformance_native_does(self):
        # plan.md M14-11: the options _run_native gives a section that doesn't need the JIT.
        recorder = Recorder(stop=False)
        with tempfile.TemporaryDirectory() as workdir, mock.patch('subprocess.run', recorder):
            fuzz.Runner('/bin/chibi-scheme', None, workdir, native='/bin/scheme-native').run_ours('(display 1)', 'native')
        self.assertEqual(len(recorder.commands), 1)
        command = recorder.commands[0]
        self.assertEqual(command[0], '/bin/scheme-native')
        self.assertTrue(command[-1].endswith('.scm'), command)
        options = command[1:-1]
        expected = harness.native_test_options() + harness.launcher_options(harness.vm_args(native=True))
        self.assertEqual(options, expected)
        self.assertIn('--vm.XX:+AbortVMOnCompilationFailure', options)
        self.assertIn('--engine.Compilation=false', options)
        for arg in options:
            self.assertFalse(arg.startswith('-D') or arg == '-ea', command)

    def run_command(self, *args):
        """`fuzz` with these arguments, in a checkout with no bazel-bin: (exit status, stderr)."""
        parser = argparse.ArgumentParser()
        fuzz.main_args(parser)
        o = parser.parse_args(['--count', '1'] + list(args))
        err = io.StringIO()
        with (tempfile.TemporaryDirectory() as checkout, mock.patch.dict('os.environ', BUILD_WORKSPACE_DIRECTORY=checkout),
              mock.patch('subprocess.run', Recorder()), contextlib.redirect_stderr(err),
              self.assertRaises(SystemExit) as e):
            fuzz.run_command(o, '/bin/chibi-scheme', RUNTIME)
        return e.exception.code, err.getvalue()

    def test_native_without_a_launcher_says_how_to_build_one(self):
        status, err = self.run_command('--mode', 'native')
        self.assertEqual(status, 1)
        self.assertIn('bazel-bin/scheme-native', err)
        self.assertIn('bazel build //:scheme-native', err)
        self.assertIn('--native FILE', err)

    def test_native_option_with_a_missing_file_is_an_error(self):
        status, err = self.run_command('--mode', 'native', '--native', '/no/such/scheme-native')
        self.assertEqual(status, 1)
        self.assertIn('--native /no/such/scheme-native: no such executable', err)

    def test_native_option_without_native_mode_is_an_error_not_ignored(self):
        status, err = self.run_command('--mode', 'interp,compile', '--native', '/bin/scheme-native')
        self.assertEqual(status, 1)
        self.assertIn('--native is for --mode native', err)


if __name__ == '__main__':
    unittest.main()

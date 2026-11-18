"""The `scheme` launcher (//:scheme_py, which //:scheme runs once it has checked its platform and its
JDK: bazel/launcher.bzl): `bazel-bin/scheme [option...] [-I dir]... [file.scm [arg...]]`.

Runs scheme.launcher.SchemeMain on the pinned GraalVM with the language and GraalVM's tools on the
module path (--inspect, --dap, --cpusampler and --memtracer work). With no file, a REPL. Its
`--vm.<option>`s start the JVM with `-<option>` (harness.split_vm_options). Under
`bazel run //:scheme --`, paths are relative to where it ran.
"""

import os
import sys

from harness import Runtime

# //:scheme_runtime's manifest, in this binary's runfiles.
MANIFEST = '_main/scheme_runtime.json'

# What the Python launcher sets for itself, which the program shouldn't see in its environment.
_LAUNCHER_ENV = ('RUNFILES_DIR', 'RUNFILES_MANIFEST_FILE', 'JAVA_RUNFILES', 'PYTHONPATH', 'PYTHONSAFEPATH')


def main():
    runtime = Runtime.from_manifest(MANIFEST)
    # Before the command: the argument files it reads (--vm.@file) are relative to it too.
    if 'BUILD_WORKING_DIRECTORY' in os.environ:
        os.chdir(os.environ['BUILD_WORKING_DIRECTORY'])
    command = runtime.launcher(sys.argv[1:])
    env = {k: v for k, v in os.environ.items() if k not in _LAUNCHER_ENV}
    os.execve(command[0], command, env)


if __name__ == '__main__':
    main()

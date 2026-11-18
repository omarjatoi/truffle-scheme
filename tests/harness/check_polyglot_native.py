"""Test: design.md D20, "The launchers": in a native image built with reflection metadata for a Java
class (tests/polyglot/reachability-metadata.json), `java-type` finds the class with its constructors
and methods, as an embedder's own image gives them; the native launcher's image, which has none,
says so instead (//tests:readme_native).

The native launcher's executable runs the library beside its real path, so the executable and
//:schemevm_reflection, renamed as the library it looks for, are copied into a directory together."""

import argparse
import os
import shutil
import subprocess
import tempfile

from harness import check, log, rlocation

# docs/examples/polyglot.scm's output where the image has ArrayList's and Math's metadata.
EXPECTED = '#t #f \n#t 2 (1 "two") \n7 \n'


def polyglot_native(o):
    native = rlocation(o.native)
    library = rlocation(o.library)
    directory = tempfile.mkdtemp(dir=os.environ.get('TEST_TMPDIR'))
    launcher = os.path.join(directory, 'scheme-native')
    shutil.copyfile(native, launcher)
    os.chmod(launcher, 0o755)
    extension = os.path.splitext(library)[1]
    shutil.copyfile(library, os.path.join(directory, 'libschemevm' + extension))
    p = subprocess.run([launcher, rlocation(o.program)], stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                       timeout=300, check=False)
    out = p.stdout.decode('utf-8', errors='replace')
    check(p.returncode == 0 and out == EXPECTED,
          f'java-type should find the classes the image has reflection metadata for (exit {p.returncode}):\n{out}')
    log('java-type in a native image with reflection metadata: the class, its constructor and methods')


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--native', required=True)
    parser.add_argument('--library', required=True)
    parser.add_argument('--program', required=True)
    polyglot_native(parser.parse_args())

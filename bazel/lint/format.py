"""`bazel run //bazel/lint:format`: run the Eclipse formatter over the repository's Java (mx eclipseformat).

    format.py <eclipse_format> <prefs>

Formats every .java file under src/ and tests/ in place (what the `*_format` tests check, and
what mx formats: its projects; the Bazel tools in bazel/ are not).
"""

import os
import subprocess
import sys

import r7rs


def main():
    workspace = os.environ.get('BUILD_WORKSPACE_DIRECTORY')
    if not workspace:
        r7rs.fail('run this with `bazel run //bazel/lint:format`')
    files = []
    for top in ('src', 'tests'):
        for root, _, names in os.walk(os.path.join(workspace, top)):
            files += [os.path.join(root, n) for n in sorted(names) if n.endswith('.java')]
    sys.exit(subprocess.run([r7rs.rlocation(sys.argv[1]), '--fix', r7rs.rlocation(sys.argv[2])] + files).returncode)


if __name__ == '__main__':
    main()

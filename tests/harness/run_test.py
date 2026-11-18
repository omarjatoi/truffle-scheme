"""A py_test's main (defs.bzl harness_test, tests/fuzz's smoke test): `run_test.py MODULE ARG...`
runs the module's file as the script, with the arguments, then writes its result to
$XML_OUTPUT_FILE as JUnit XML, a test case for the target, and exits with the script's status.
Without the file, Bazel 9 runs a second spawn per test to generate it (design.md D11, "Bazel 9")."""

import importlib.util
import os
import sys
import time
import traceback
import types
from xml.sax.saxutils import escape, quoteattr


def main():
    module = sys.argv.pop(1)
    start = time.monotonic()
    status, message, detail = 0, None, ''
    try:
        run(importlib.util.find_spec(module).origin)
    except SystemExit as e:
        if isinstance(e.code, int):
            status = e.code
        elif e.code is not None:
            # sys.exit('message'), as Python's own exit does it.
            print(e.code, file=sys.stderr)
            status, detail = 1, str(e.code)
        if status:
            message = 'exited with status %d' % status
    except BaseException as e:
        traceback.print_exc()
        status = 1
        message = ''.join(traceback.format_exception_only(type(e), e)).strip()
        detail = traceback.format_exc()
    sys.stdout.flush()
    sys.stderr.flush()
    path = os.environ.get('XML_OUTPUT_FILE')
    if path:
        write_xml(path, os.environ.get('TEST_TARGET', module), time.monotonic() - start, message, detail)
    sys.exit(status)


def run(script):
    """Runs a file as `python3 script` would: in a new __main__ module (unittest.main() looks there;
    argparse names the script)."""
    sys.argv[0] = script
    main_module = types.ModuleType('__main__')
    main_module.__file__ = script
    sys.modules['__main__'] = main_module
    with open(script, encoding='utf-8') as f:
        code = compile(f.read(), script, 'exec')
    exec(code, main_module.__dict__)


def write_xml(path, name, seconds, message, detail):
    failure = '' if message is None else '<failure message=%s>%s</failure>' % (quoteattr(clean(message)), escape(clean(detail)))
    with open(path, 'w', encoding='utf-8') as out:
        out.write('<?xml version="1.0" encoding="UTF-8"?>\n<testsuites>\n'
                  '  <testsuite name=%s tests="1" failures="%d" errors="0" time="%.3f">\n'
                  '    <testcase name=%s classname=%s time="%.3f">%s</testcase>\n'
                  '  </testsuite>\n</testsuites>\n'
                  % (quoteattr(name), message is not None, seconds, quoteattr(name), quoteattr(name), seconds, failure))


def clean(text):
    """Text without the characters XML 1.0 can't hold."""
    return ''.join(c if c in '\t\n\r' or ' ' <= c <= '퟿' or '' <= c <= '�' or c >= '\U00010000' else '?' for c in text)


if __name__ == '__main__':
    main()

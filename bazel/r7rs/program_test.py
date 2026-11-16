"""One Scheme program as a Bazel test: a differential test, a spec case, or a space case.

    program_test.py --runtime <manifest> diff <file.scm> --chibi <chibi-scheme> [--torture]
    program_test.py --runtime <manifest> spec <case.scm>
    program_test.py --runtime <manifest> space <file.scm> --expect <stdout line> --limits '<JVM options>'

Files are runfiles paths. The kinds:
  diff   (mx r7rs-diff) stdout and exit status equal chibi-scheme's; ours runs interpreted only,
         and also under torture with --torture (mx r7rs-torture's part for tests/diff).
  spec   (mx r7rs-spec) the case's `;; limits:` and `;; exit:` headers and its .out file
         (tests/spec/README.md); it runs interpreted and with the Graal JIT, each in a JVM of
         its own under the limits.
  space  (mx r7rs-space) the expected stdout under the JVM limits, interpreted and with the JIT.
"""

import argparse
import difflib
import os
import re
import time

import r7rs


def _diff(expected, actual, from_name, to_name):
    lines = list(difflib.unified_diff(expected.splitlines(keepends=True), actual.splitlines(keepends=True),
                                      fromfile=from_name, tofile=to_name, n=1))
    return ''.join(l if len(l) < 200 else l[:200] + '...\n' for l in lines[:40]).rstrip('\n')


def diff(runtime, file, chibi, torture):
    chibi_rc, chibi_out, chibi_err = r7rs.run_chibi(chibi, file)
    ours_rc, ours_out, ours_err = r7rs.run_ours(runtime, file, r7rs.vm_args(torture=torture))
    if chibi_rc == ours_rc and chibi_out == ours_out:
        r7rs.log(f'same (exit {ours_rc}, {len(ours_out)} chars of stdout): {file}')
        return
    message = [f'DIFFERENT from chibi-scheme: {file}']
    if chibi_out != ours_out:
        message.append(_diff(chibi_out, ours_out, 'chibi-scheme stdout', 'scheme stdout'))
        message.append(f'  chibi-scheme: {chibi_out[-500:]!r}\n  scheme:       {ours_out[-500:]!r}')
    message.append(f'exit status: chibi-scheme {chibi_rc}, scheme {ours_rc}')
    message.append('chibi-scheme stderr (tail):\n' + r7rs.tail(chibi_err))
    message.append('scheme stderr (tail):\n' + r7rs.tail(ours_err))
    r7rs.fail('\n'.join(message))


_SPEC_HEADER = re.compile(r'^;;\s*(limits|exit):\s*(.*?)\s*$')


def spec_case(file):
    """(JVM limits, expected exit status, expected stdout): mx_r7rs._spec_case."""
    limits, status = [], 0
    with open(file, encoding='utf-8') as f:
        for line in f:
            if not line.startswith(';;'):
                break
            m = _SPEC_HEADER.match(line)
            if m and m.group(1) == 'limits':
                limits = m.group(2).split()
            elif m:
                status = int(m.group(2))
    out = file[:-len('.scm')] + '.out'
    r7rs.check(os.path.exists(out), f'{file} has no expected stdout {out}')
    with open(out, encoding='utf-8', newline='') as f:
        return limits, status, f.read()


def expect(runtime, file, limits, status, expected):
    """Runs the program interpreted and with the JIT under the limits; both runs must match."""
    problems = []
    for label, mode in (('interpreter', r7rs.vm_args()), ('JIT', [])):
        start = time.time()
        rc, out, err = r7rs.run_ours(runtime, file, mode, limits)
        seconds = time.time() - start
        if rc != status or out != expected:
            detail = f'exit {rc} (expected {status})' if rc != status else 'stdout differs'
            if out != expected:
                detail += '\n' + _diff(expected, out, 'expected', 'actual')
            problems.append(f'{label} ({seconds:.1f}s): {detail}\n  stderr:\n{r7rs.tail(err)}')
        else:
            r7rs.log(f'{os.path.basename(file)}: {label} ok ({seconds:.1f}s, {" ".join(limits)})')
    if problems:
        r7rs.fail(f'FAIL {file} {" ".join(limits)}\n  ' + '\n  '.join(problems))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--runtime', required=True)
    sub = parser.add_subparsers(dest='kind', required=True)
    d = sub.add_parser('diff')
    d.add_argument('file')
    d.add_argument('--chibi', required=True)
    d.add_argument('--torture', action='store_true')
    s = sub.add_parser('spec')
    s.add_argument('file')
    p = sub.add_parser('space')
    p.add_argument('file')
    p.add_argument('--expect', required=True, help='the one line of stdout expected')
    p.add_argument('--limits', required=True)
    args = parser.parse_args()
    runtime = r7rs.Runtime.from_manifest(args.runtime)
    file = r7rs.require(r7rs.rlocation(args.file))
    if args.kind == 'diff':
        diff(runtime, file, r7rs.rlocation(args.chibi), args.torture)
    elif args.kind == 'spec':
        limits, status, expected = spec_case(file)
        expect(runtime, file, limits, status, expected)
    else:
        expect(runtime, file, args.limits.split(), 0, args.expect + '\n')


if __name__ == '__main__':
    main()

"""AGENTS.md rule 1 as a test: Java builtins never call Scheme (design.md D5; mx r7rs-check).

    rules_test.py <src/builtins Java file>...

A builtin that needs to invoke a procedure returns a TailCall request instead.
"""

import re
import sys

import r7rs

_FORBIDDEN = [
    (re.compile(r'\b(Direct|Indirect)CallNode\b'), 'call nodes'),
    (re.compile(r'\b\w*CallTarget\b'), 'call targets'),
    (re.compile(r'\bInteropLibrary\b'), 'interop (can execute Scheme procedures)'),
]


def main():
    files = sys.argv[1:]
    r7rs.check(files, 'no files to check')
    violations = []
    for path in files:
        with open(r7rs.rlocation(path), encoding='utf-8') as f:
            for lineno, line in enumerate(f, 1):
                code = line.split('//', 1)[0]
                for pattern, what in _FORBIDDEN:
                    if pattern.search(code):
                        violations.append(f'{path.removeprefix("_main/")}:{lineno}: uses {what}: {line.strip()}')
    r7rs.check(not violations, 'Rule 1 violated: Java builtins never call Scheme; return a TailCall request instead '
               '(AGENTS.md rule 1, design.md D5).\n' + '\n'.join(violations))
    r7rs.log(f'rule 1 OK ({len(files)} files)')


if __name__ == '__main__':
    main()

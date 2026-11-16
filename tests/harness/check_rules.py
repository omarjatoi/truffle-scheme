"""Test: AGENTS.md rule 1, Java builtins never call Scheme (design.md D5)."""

import re
import sys

from harness import check, log, rlocation


# AGENTS.md rule 1: Java builtins never call Scheme; they return a TailCall request (design.md D5).
_RULE1_FORBIDDEN = [
    (re.compile(r'\b(Direct|Indirect)CallNode\b'), 'call nodes'),
    (re.compile(r'\b\w*CallTarget\b'), 'call targets'),
    (re.compile(r'\bInteropLibrary\b'), 'interop (can execute Scheme procedures)'),
]


def rules(files):
    violations = []
    for path in files:
        with open(rlocation(path), encoding='utf-8') as f:
            for lineno, line in enumerate(f, 1):
                code = line.split('//', 1)[0]
                violations += [f'{path.removeprefix("_main/")}:{lineno}: uses {what}: {line.strip()}'
                               for pattern, what in _RULE1_FORBIDDEN if pattern.search(code)]
    check(files and not violations, 'Rule 1 violated: Java builtins never call Scheme; return a TailCall request instead '
          '(AGENTS.md rule 1, design.md D5).\n' + '\n'.join(violations))
    log(f'rule 1 OK ({len(files)} files)')


if __name__ == '__main__':
    rules(sys.argv[1:])

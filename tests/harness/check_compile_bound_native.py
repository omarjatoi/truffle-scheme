"""Test: the native launcher's library fails to build past its bound on runtime-compiled methods
(design.md D17, "Runtime-compiled methods").

The argument is //:schemevm_over_bound's log: native-image's output and exit status, building
//:schemevm with the bound lowered. It passes when native-image refused the build because the image's
runtime-compiled methods are more than the bound."""

import re
import sys

from harness import check, log, rlocation, tail

REFUSAL = re.compile(r'Number of methods for runtime compilation exceeds the allowed limit: (\d+) > (\d+)')
STATUS = re.compile(r'native-image exit status: (\d+)')


def compile_bound_native(path):
    with open(rlocation(path), encoding='utf-8') as f:
        output = f.read()
    status = STATUS.findall(output)
    refusal = REFUSAL.search(output)
    check(status and status[-1] != '0' and refusal,
          'native-image should have refused the build past its bound on runtime-compiled methods; it ended with:\n' +
          tail(output, 20))
    count, bound = refusal.groups()
    log(f'ok   native-image refused {count} runtime-compiled methods over a bound of {bound} '
        f'(exit status {status[-1]})')


if __name__ == '__main__':
    compile_bound_native(sys.argv[1])

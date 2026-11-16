"""One smoke test of GraalVM's tools against the `scheme` launcher, as a Bazel test (mx r7rs-tools).

    tools_test.py --runtime <launcher manifest> --tools-runtime <launcher + tools manifest>
                  --driver <mx.r7rs/mx_r7rs_tools.py> --programs <tests/tools/debugme.scm> NAME

NAME is one of mx_r7rs_tools.SMOKE_TESTS (help, dap, inspect, cpusampler, memtracer). The tests
themselves, with their DAP and Chrome DevTools clients, are mx's own mx.r7rs/mx_r7rs_tools.py,
loaded here unchanged: it uses nothing of mx but logging, which a small stand-in provides.
"""

import argparse
import importlib.util
import os
import sys
import types

import r7rs


def _mx_stand_in():
    mx = types.ModuleType('mx')
    mx.log = r7rs.log
    mx.logv = r7rs.log
    mx.logvv = lambda message: None
    mx.abort = r7rs.fail
    return mx


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--runtime', required=True)
    parser.add_argument('--tools-runtime', required=True)
    parser.add_argument('--driver', required=True)
    parser.add_argument('--programs', required=True)
    parser.add_argument('name')
    args = parser.parse_args()
    sys.modules['mx'] = _mx_stand_in()
    spec = importlib.util.spec_from_file_location('mx_r7rs_tools', r7rs.rlocation(args.driver))
    tools = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(tools)

    bare = r7rs.Runtime.from_manifest(args.runtime)
    with_tools = r7rs.Runtime.from_manifest(args.tools_runtime)
    main_class = 'scheme.launcher/scheme.launcher.SchemeMain'
    commands = tools.Commands(tools=lambda launcher_args: with_tools.command([], main_class) + list(launcher_args),
                              bare=lambda launcher_args: bare.command([], main_class) + list(launcher_args))
    failed = tools.run([args.name], os.path.dirname(r7rs.rlocation(args.programs)), commands)
    r7rs.check(not failed, f'tools smoke test failed: {args.name}')


if __name__ == '__main__':
    main()

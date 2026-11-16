"""Test: one of GraalVM's tools against the launcher (tools.py), with and without the tools jars."""

import argparse
import os
from os.path import join

import harness
import tools
from harness import Runtime, check, rlocation


def tools_test(o):
    """A smoke test of GraalVM's tools against the launcher (tools.py), with and without the tools jars."""
    bare, with_tools = Runtime.from_manifest(o.runtime), Runtime.from_manifest(o.tools_runtime)
    commands = tools.Commands(tools=lambda args: with_tools.launcher(args), bare=lambda args: bare.launcher(args))
    programs = os.path.dirname(rlocation(o.programs)) if o.programs else join(harness.repo_root(), 'tests', 'tools')
    failed = tools.run([o.name], programs, commands)
    check(not failed, f'tools smoke test failed: {o.name}')


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--runtime', required=True)
    parser.add_argument('--tools-runtime', required=True)
    parser.add_argument('--programs', help='a program in tests/tools (default: the checkout\'s)')
    parser.add_argument('name', choices=list(tools.SMOKE_TESTS))
    tools_test(parser.parse_args())

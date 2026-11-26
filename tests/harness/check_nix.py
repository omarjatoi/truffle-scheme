"""Test: design.md D11, "Nix packages": what the flake builds its packages from without Bazel
(nix/packages.json, which nix/scheme.nix and flake.nix read) is what the Bazel build says. The
GraalVM release is the one bazel/graalvm.bzl pins, at TRUFFLE_VERSION (the same archives, at the
same sha256s, and the same thin launcher's source), and flake.nix's GraalVM is that one; the Truffle DSL's processors, the Maven
artifacts of each module path, the libraries (their sources, resources and processor), the module
descriptors and the native image's options are those of bazel/BUILD.bazel, src/*/BUILD.bazel and
BUILD.bazel. The Starlark files are read as Python syntax, their few expressions evaluated here."""

import argparse
import ast
import json
import re

from harness import check, log, rlocation

# bazel/graalvm.bzl's platform names -> Nix's systems.
_SYSTEMS = {'linux_x64': 'x86_64-linux', 'linux_aarch64': 'aarch64-linux', 'macos_aarch64': 'aarch64-darwin'}


class _Select:
    """A select(): its branches, by condition."""

    def __init__(self, branches):
        self.branches = branches


class _Glob:
    def __init__(self, include, exclude=()):
        self.include, self.exclude = list(include), list(exclude)


class _Starlark:
    """A Starlark file's top-level assignments and calls, with the expressions they use: literals,
    names, `+`, select() and glob()."""

    def __init__(self, path):
        with open(path, encoding='utf-8') as f:
            self.tree = ast.parse(f.read(), path)
        self.names = {}
        for node in self.tree.body:
            if isinstance(node, ast.Assign) and len(node.targets) == 1 and isinstance(node.targets[0], ast.Name):
                try:
                    self.names[node.targets[0].id] = self.value(node.value)
                except ValueError:
                    pass

    def value(self, node):
        if isinstance(node, ast.Constant):
            return node.value
        if isinstance(node, (ast.List, ast.Tuple)):
            return [self.value(e) for e in node.elts]
        if isinstance(node, ast.Dict):
            return {self.value(k): self.value(v) for k, v in zip(node.keys, node.values)}
        if isinstance(node, ast.Name) and node.id in self.names:
            return self.names[node.id]
        if isinstance(node, ast.Name) and node.id in ('True', 'False'):
            return node.id == 'True'
        if isinstance(node, ast.BinOp) and isinstance(node.op, ast.Add):
            left, right = self.value(node.left), self.value(node.right)
            if isinstance(left, _Select) or isinstance(right, _Select):
                selects = [x for x in (left, right) if isinstance(x, _Select)]
                check(len(selects) == 1, 'check_nix: a sum of two select()s')
                common = left if isinstance(right, _Select) else right
                return _Select({k: (common + v if right is selects[0] else v + common)
                                for k, v in selects[0].branches.items()})
            return left + right
        if isinstance(node, ast.Call) and isinstance(node.func, ast.Name):
            if node.func.id == 'select':
                return _Select(self.value(node.args[0]))
            if node.func.id == 'glob':
                kwargs = {k.arg: self.value(k.value) for k in node.keywords}
                return _Glob(self.value(node.args[0]), kwargs.get('exclude', ()))
        raise ValueError(ast.dump(node))

    def calls(self, function):
        """{name: {attribute: value}} of the top-level calls of `function` (attributes it can't evaluate left out)."""
        found = {}
        for node in self.tree.body:
            if isinstance(node, ast.Expr) and isinstance(node.value, ast.Call) and getattr(node.value.func, 'id', None) == function:
                attrs = {}
                for k in node.value.keywords:
                    try:
                        attrs[k.arg] = self.value(k.value)
                    except ValueError:
                        pass
                found[attrs['name']] = attrs
        return found


def _label(coordinate):
    """A Maven artifact's label in rules_jvm_external's repository: `@jvm//:org_graalvm_tools_dap_tool`."""
    return '@jvm//:' + re.sub(r'[.:-]', '_', coordinate)


def nix(o):
    with open(rlocation(o.pins), encoding='utf-8') as f:
        pins = json.load(f)
    with open(rlocation(o.lock), encoding='utf-8') as f:
        lock = json.load(f)['artifacts']
    problems = []

    def same(what, ours, bazels):
        if ours != bazels:
            problems.append(f'{what}: nix/packages.json has {ours!r}, the Bazel build {bazels!r}')

    # The GraalVM release, at TRUFFLE_VERSION.
    with open(rlocation(o.module), encoding='utf-8') as f:
        truffle = re.search(r'^TRUFFLE_VERSION = "([^"]+)"', f.read(), re.M).group(1)
    graalvm = _Starlark(rlocation(o.graalvm))
    version = pins['graalvm']['version']
    same('the GraalVM version (MODULE.bazel, TRUFFLE_VERSION)', version, truffle)
    releases = graalvm.names['_RELEASES']
    if version in releases:
        url_prefix, _, sha256s = releases[version]
        same('the GraalVM archives\' URL prefix (bazel/graalvm.bzl, _RELEASES)', pins['graalvm']['url_prefix'], url_prefix)
        platforms = graalvm.names['_PLATFORMS']
        same('the GraalVM archives (bazel/graalvm.bzl, _RELEASES, _PLATFORMS)', pins['graalvm']['archives'],
             {_SYSTEMS[p]: {'suffix': platforms[p][2], 'sha256': sha256} for p, sha256 in sha256s.items()})
        tag, sha256 = graalvm.names['_LAUNCHER_SOURCES'][version]
        same('the thin launcher\'s source (bazel/graalvm.bzl, _LAUNCHER_SOURCES)', pins['graalvm']['launcher_cc'],
             {'tag': tag, 'sha256': sha256})

    # flake.nix's GraalVM (the dev shell's and the packages') is nix/packages.json's.
    with open(rlocation(o.flake), encoding='utf-8') as f:
        flake = f.read()
    if 'nix/packages.json' not in flake or re.search(r'\b[0-9a-f]{64}\b', flake):
        problems.append('flake.nix: its GraalVM must be nix/packages.json\'s, with no sha256 of its own')

    # The Truffle DSL's processors and the module paths' Maven artifacts.
    bazel = _Starlark(rlocation(o.bazel_build))
    same('the Truffle DSL processors (bazel/BUILD.bazel, _TRUFFLE_PROCESSORS)', pins['maven']['processors'],
         bazel.names['_TRUFFLE_PROCESSORS'])
    libraries = bazel.calls('java_library')
    for name in ('truffle_modules', 'launcher_modules', 'tools_modules'):
        same(f'//bazel:{name}\'s artifacts', sorted(_label(c) for c in pins['maven'][name]),
             sorted(libraries[name]['runtime_deps']))
    processor = _label(pins['maven']['processor'])
    plugins = [n for n in ast.walk(bazel.tree) if isinstance(n, ast.Call) and getattr(n.func, 'id', None) == 'java_plugin']
    if not plugins or any([processor] != [bazel.value(k.value) for k in n.keywords if k.arg == 'deps'][0] for n in plugins):
        problems.append(f'the Truffle DSL processor: bazel/BUILD.bazel\'s java_plugins don\'t depend on {processor}')
    for coordinate in [pins['maven']['processor']] + [c for k in ('truffle_modules', 'launcher_modules', 'tools_modules')
                                                      for c in pins['maven'][k]]:
        if coordinate not in lock:
            problems.append(f'{coordinate}: not in maven_install.json')

    # The libraries and the modules made of them.
    root = _Starlark(rlocation(o.build))
    modules = root.calls('java_module_jar')
    by_module = {m['module_name']: m for m in modules.values()}
    for module, ours in pins['modules'].items():
        theirs = by_module.get(module)
        if theirs is None:
            problems.append(f'module {module}: BUILD.bazel has no java_module_jar of that name')
            continue
        for attr in ('requires', 'exports', 'uses'):
            same(f'module {module}\'s {attr} (BUILD.bazel)', ours[attr], theirs.get(attr, []))
        same(f'module {module}\'s libs (BUILD.bazel)', ours['libs'], [lib.split(':')[0].split('/')[-1] for lib in theirs['libs']])
    builds = {path.split('/')[-2]: path for path in o.library_builds}
    for library in pins['libraries']:
        name = library['name']
        if name not in builds:
            problems.append(f'library {name}: no src/{name}/BUILD.bazel')
            continue
        path = builds[name]
        theirs = _Starlark(rlocation(path)).calls('scheme_java_library').get(name)
        if theirs is None:
            problems.append(f'library {name}: {path} has no scheme_java_library of that name')
            continue
        same(f'library {name}\'s sources (src/{name}/BUILD.bazel)', library['sources'],
             [p.removesuffix('/*.java') for p in theirs['srcs'].include])
        same(f'library {name}\'s Truffle DSL processor (src/{name}/BUILD.bazel)', library['dsl'], theirs.get('truffle_dsl', False))
        resources = theirs.get('resources')
        same(f'library {name}\'s resources (src/{name}/BUILD.bazel)', library['resources'],
             [p.removesuffix('/**').removesuffix('/*') for p in resources.include] if resources else [])
        if resources:
            same(f'library {name}\'s resource_strip_prefix', theirs.get('resource_strip_prefix'), f'src/{name}')
            same(f'library {name}\'s resources\' exclusions', [e for e in resources.exclude if not e.endswith('/*.java')], [])
    same('the libraries (BUILD.bazel: the modules\' libs)', sorted(lib['name'] for lib in pins['libraries']),
         sorted(lib for m in pins['modules'].values() for lib in m['libs']))

    # The native image's options (BUILD.bazel, _SCHEMEVM_OPTIONS).
    options = root.names['_SCHEMEVM_OPTIONS']
    same('the native image\'s options (BUILD.bazel, _SCHEMEVM_OPTIONS)', pins['native_options'],
         options.branches['//conditions:default'])
    same('the native image\'s options on x86-64 Linux (BUILD.bazel, _SCHEMEVM_OPTIONS)',
         pins['native_options'] + pins['native_options_x86_64_linux'], options.branches['//bazel:linux_x86_64'])

    check(not problems, 'nix/packages.json differs from the Bazel build (design.md D11, "Nix packages"):\n  '
          + '\n  '.join(problems) + '\nChange it with the Bazel build, so the flake\'s packages are built as Bazel builds them.')
    log(f'nix/packages.json = the Bazel build: GraalVM {version} and its archives, '
        f'{len(pins["libraries"])} libraries, {len(pins["modules"])} modules, the native image\'s options')


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--pins', required=True, help='nix/packages.json')
    parser.add_argument('--lock', required=True, help='maven_install.json')
    parser.add_argument('--module', required=True, help='MODULE.bazel')
    parser.add_argument('--flake', required=True, help='flake.nix')
    parser.add_argument('--graalvm', required=True, help='bazel/graalvm.bzl')
    parser.add_argument('--build', required=True, help='BUILD.bazel')
    parser.add_argument('--bazel-build', required=True, help='bazel/BUILD.bazel')
    parser.add_argument('--library-builds', nargs='+', required=True,
                        help="src/<library>/BUILD.bazel for each of nix/packages.json's libraries")
    nix(parser.parse_args())

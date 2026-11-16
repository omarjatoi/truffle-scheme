"""Runs the commands of README.md and docs/embedding.md, and checks their samples (plan.md M6-07).

`mx r7rs-readme` (and `mx tests readme`) reads the documents' fenced blocks. Every block has a
marker, an HTML comment on the line before its opening fence (blank lines in between are allowed),
that says how CI checks it; a block without one fails the step:

- `<!-- readme: run -->` before a ```sh block: the block runs as one `sh -e` script, from the
  repository's root with standard input from /dev/null, and must exit 0.
- `<!-- readme: run server -->`: the block starts a server (`--inspect`, `--dap`). It runs until
  its output (stdout and stderr) matches the block's `output` block, then it is killed.
- `<!-- readme: run native -->`: the block needs GraalVM's native-image, which takes minutes and
  gigabytes, so `mx r7rs-readme --native` runs it instead, in the native launcher's workflow
  (.github/workflows/native.yml), and this step checks that the workflow does.
- `<!-- readme: output -->` after a run block: what the block prints on stdout (on stdout and
  stderr for a server). `...` matches any text, newlines included; trailing spaces don't count.
- `<!-- readme: file PATH -->` or `<!-- readme: file PATH#NAME -->`: the block is the file's text,
  or the region of it between `// readme-snippet: NAME` and `// readme-snippet-end`, dedented.

Inline code in the documents that starts like a command (`mx `, `nix `, `java `) must be part of
one of their run blocks, so prose can't mention a command that CI doesn't run.
"""

import os
import re
import signal
import subprocess
import tempfile
import textwrap
import time
from os.path import join

import mx

DOCUMENTS = ['README.md', join('docs', 'embedding.md')]
NATIVE_WORKFLOW = join('.github', 'workflows', 'native.yml')
NATIVE_COMMAND = 'mx r7rs-readme --native'

# Seconds a block may take: a run block of mx commands, the native-image build, a server's banner.
_RUN_TIMEOUT = 900
_NATIVE_TIMEOUT = 2400
_SERVER_TIMEOUT = 180

_FENCE = re.compile(r'^(```+)\s*([\w+-]*)\s*$')
_MARKER = re.compile(r'^<!--\s*readme:\s*(.*?)\s*-->$')
_INLINE_CODE = re.compile(r'(?<!`)`([^`\n]+)`(?!`)')
_COMMAND_PREFIXES = ('mx ', 'nix ', 'java ')
_SNIPPET_BEGIN = re.compile(r'^\s*// readme-snippet: (\S+)\s*$')
_SNIPPET_END = re.compile(r'^\s*// readme-snippet-end\s*$')


class Block:
    def __init__(self, document, line, language, text, marker):
        self.document = document
        self.line = line
        self.language = language
        self.text = text
        self.marker = marker
        self.output = None  # the `output` block after a run block

    def where(self):
        return f'{self.document}:{self.line}'

    def words(self):
        return (self.marker or '').split()


def _parse(root, document):
    """The fenced blocks of a document, and its text outside them."""
    with open(join(root, document), encoding='utf-8') as f:
        lines = f.read().split('\n')
    blocks, prose = [], []
    i = 0
    marker = None
    while i < len(lines):
        line = lines[i]
        fence = _FENCE.match(line)
        if fence:
            start = i
            end = i + 1
            while end < len(lines) and lines[end].strip() != fence.group(1):
                end += 1
            if end == len(lines):
                mx.abort(f'{document}:{start + 1}: unclosed code block')
            blocks.append(Block(document, start + 1, fence.group(2), '\n'.join(lines[start + 1:end]) + '\n', marker))
            marker = None
            i = end + 1
            continue
        m = _MARKER.match(line.strip())
        if m:
            marker = m.group(1)
        elif line.strip():
            marker = None
            prose.append(line)
        i += 1
    return blocks, '\n'.join(prose)


def _snippet(path, name):
    """The region of a source file between its `// readme-snippet: name` and `// readme-snippet-end`."""
    with open(path, encoding='utf-8') as f:
        lines = f.read().split('\n')
    for i, line in enumerate(lines):
        m = _SNIPPET_BEGIN.match(line)
        if m and m.group(1) == name:
            for j in range(i + 1, len(lines)):
                if _SNIPPET_END.match(lines[j]):
                    return textwrap.dedent('\n'.join(lines[i + 1:j]) + '\n')
                if _SNIPPET_BEGIN.match(lines[j]):
                    break
            return None
    return None


def _pattern(expected):
    """A regex for an output block: literal text, `...` matching anything."""
    return '.*?'.join(re.escape(part) for part in _normalize(expected).split('...'))


def _normalize(text):
    return '\n'.join(line.rstrip() for line in text.strip('\n').split('\n'))


def _tail(text, n=30):
    lines = text.rstrip('\n').split('\n')
    return '\n'.join('    ' + line for line in lines[-n:])


def _run(block, root, timeout):
    """Runs a block as a shell script. Returns a problem, or None."""
    with tempfile.NamedTemporaryFile('w', suffix='.sh', delete=False, encoding='utf-8') as script:
        script.write(block.text)
    try:
        try:
            result = subprocess.run(['sh', '-e', script.name], cwd=root, stdin=subprocess.DEVNULL,
                                    capture_output=True, text=True, timeout=timeout)
        except subprocess.TimeoutExpired:
            return f'did not finish within {timeout}s'
    finally:
        os.unlink(script.name)
    if result.returncode != 0:
        return f'exit status {result.returncode}\n  stdout:\n{_tail(result.stdout)}\n  stderr:\n{_tail(result.stderr)}'
    if block.output is not None and not re.fullmatch(_pattern(block.output.text), _normalize(result.stdout), re.DOTALL):
        return (f'stdout does not match the output block at {block.output.where()}\n'
                f'  expected:\n{_tail(block.output.text, 60)}\n  stdout:\n{_tail(result.stdout, 60)}\n  stderr:\n{_tail(result.stderr)}')
    return None


def _run_server(block, root):
    """Starts a block that runs a server, waits for its banner (the output block), then kills it."""
    if block.output is None:
        return 'a server block needs an output block: its banner'
    pattern = re.compile(_pattern(block.output.text), re.DOTALL)
    with tempfile.TemporaryDirectory(prefix='r7rs-readme-') as tmp:
        script, log = join(tmp, 'block.sh'), join(tmp, 'output.txt')
        with open(script, 'w', encoding='utf-8') as f:
            f.write(block.text)
        with open(log, 'wb') as out:
            process = subprocess.Popen(['sh', '-e', script], cwd=root, stdin=subprocess.DEVNULL, stdout=out,
                                       stderr=subprocess.STDOUT, start_new_session=True)
        try:
            deadline = time.time() + _SERVER_TIMEOUT
            while True:
                with open(log, encoding='utf-8', errors='replace') as f:
                    text = f.read()
                if pattern.match(_normalize(text)):
                    return None
                if process.poll() is not None:
                    return f'exited with status {process.returncode} before printing its banner\n  output:\n{_tail(text)}'
                if time.time() > deadline:
                    return f'no banner within {_SERVER_TIMEOUT}s\n  expected:\n{_tail(block.output.text)}\n  output:\n{_tail(text)}'
                time.sleep(0.5)
        finally:
            if process.poll() is None:
                os.killpg(process.pid, signal.SIGKILL)
                process.wait(30)


def _check_file(block, root):
    path, _, name = block.words()[1].partition('#') if len(block.words()) == 2 else (None, None, None)
    if path is None:
        return 'a file marker names one file: `file PATH` or `file PATH#NAME`'
    full = join(root, path)
    if not os.path.isfile(full):
        return f'{path} does not exist'
    if name:
        expected = _snippet(full, name)
        if expected is None:
            return f'{path} has no region `// readme-snippet: {name}` ... `// readme-snippet-end`'
    else:
        with open(full, encoding='utf-8') as f:
            expected = f.read()
    if block.text != expected:
        return f'differs from {block.words()[1]}: copy it into the document again\n  {path}:\n{_tail(expected, 200)}'
    return None


def _blocks(root):
    """Every block of the documents, with run blocks linked to their output blocks; and a list of
    the problems found while linking them."""
    problems, blocks, commands, prose = [], [], [], []
    for document in DOCUMENTS:
        doc_blocks, doc_prose = _parse(root, document)
        prose.append((document, doc_prose))
        previous = None
        for block in doc_blocks:
            kind = block.words()[0] if block.words() else None
            if kind is None:
                problems.append(f'{block.where()}: a {block.language or "plain"} block without a `<!-- readme: ... -->` marker')
            elif kind == 'output':
                if previous is None or previous.words()[:1] != ['run'] or previous.output is not None:
                    problems.append(f'{block.where()}: an output block must follow a run block')
                else:
                    previous.output = block
            elif kind == 'run':
                if block.language != 'sh':
                    problems.append(f'{block.where()}: a run block is a ```sh block')
                if not set(block.words()[1:]) <= {'server', 'native'}:
                    problems.append(f'{block.where()}: unknown run option in `{block.marker}`')
                commands.append(block.text)
            elif kind != 'file':
                problems.append(f'{block.where()}: unknown marker `{block.marker}`')
            blocks.append(block)
            previous = block
    for document, text in prose:
        for code in _INLINE_CODE.findall(text):
            if code.startswith(_COMMAND_PREFIXES) and not any(code in command for command in commands):
                problems.append(f'{document}: `{code}` is not part of any run block, so CI does not run it')
    return blocks, problems


def run(root, native=False):
    """Checks the documents (all but native blocks, or only the native blocks). Returns the problems."""
    blocks, problems = _blocks(root)
    if not native:
        with open(join(root, NATIVE_WORKFLOW), encoding='utf-8') as f:
            if NATIVE_COMMAND not in f.read():
                problems.append(f'{NATIVE_WORKFLOW} does not run `{NATIVE_COMMAND}`, so the native blocks would not run')
    for block in blocks:
        words = block.words()
        if not words or words[0] not in ('run', 'file'):
            continue
        start = time.time()
        if words[0] == 'file':
            if native:
                continue
            problem = _check_file(block, root)
        elif ('native' in words) != native:
            mx.logv(f'  {block.where()}: skipped (' + ('a native block' if not native else 'not a native block') + ')')
            continue
        elif 'server' in words:
            problem = _run_server(block, root)
        else:
            problem = _run(block, root, _NATIVE_TIMEOUT if native else _RUN_TIMEOUT)
        summary = block.text.strip().split('\n')[0] if words[0] == 'run' else ' '.join(words)
        if problem:
            problems.append(f'{block.where()}: {problem}')
            mx.log(f'FAIL    {block.where()}: {summary}')
        else:
            mx.log(f'ok      {block.where()}: {summary} ({time.time() - start:.1f}s)')
    return problems

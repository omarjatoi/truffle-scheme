"""Smoke tests for GraalVM's tools against the `scheme` launcher (plan.md M6-03, design.md D17).

`bazel test //tests:tools` (and `bazel run //:r7rs -- tools NAME`) starts the launcher in a child JVM with the optional tools
(//bazel:tools_modules) on its module path, and drives each tool against a program in
tests/tools/:

- `help`: the tools show in `--help` with the tools jars, and not without it (the language and the
  launcher don't depend on the tools).
- `dap`: the Debug Adapter Protocol (`--dap`): a line breakpoint, the stack, a local by name,
  step over, continue to the next hit, then run to the end.
- `inspect`: the Chrome DevTools Protocol over a websocket (`--inspect`): the same session.
- `cpusampler`: the CPU sampler's histogram and call tree name the program's procedures, and its
  samples of a recursion deeper than the spill depth have every frame (M6-04).
- `memtracer`: the memory tracer's histogram counts the allocations of the program's procedures
  (the language reports them, M7-07).
- `terminal`: the REPL on a pseudo-terminal (M7-07): line editing, the history and its file, the
  continuation prompt, Ctrl-C, and `read-line` reading what is typed next. It runs the launcher
  without the tools, and runs `SCHEME_NATIVE_LAUNCHER` the same way if that variable names an
  executable (the native launcher, `bazel-bin/scheme-native`).

The DAP and CDP clients are dependency-free (the standard library only), ported from the
exploration in #76. Every wait has a timeout, and the child JVM is killed whatever happens, so a
hung tool fails the step instead of hanging CI.
"""

import base64
import collections
import fcntl
import json
import os
import pty
import re
import select
import socket
import struct
import subprocess
import termios
import tempfile
import time
import urllib.request
from os.path import join

from harness import fail, log

# Seconds to wait for a tool's server to accept a connection, for one protocol message, and for the
# program to finish. CI runners are slow; a healthy run takes a few seconds per test.
_CONNECT_TIMEOUT = 120
_MESSAGE_TIMEOUT = 120
_EXIT_TIMEOUT = 120

# tests/tools/debugme.scm: the breakpoint goes on `square`'s body, which runs once per i in 0..4,
# called by `weighted`, a closure that captures sum-squares's n = 5.
_DEBUG_PROGRAM = 'debugme.scm'
_BREAK_LINE = 3
_DEBUG_OUTPUT = '150\n'
# tests/tools/profileme.scm: fib, called from the loop in run-fib.
_PROFILE_PROGRAM = 'profileme.scm'
# tests/tools/spillme.scm: spin, at the bottom of a recursion 500 deep, most of it on the heap.
_SPILL_PROGRAM = 'spillme.scm'
_SPILL_DEPTH = 500


class SmokeFailure(Exception):
    pass


def _check(condition, message):
    if not condition:
        raise SmokeFailure(message)


def _free_port():
    """A TCP port that was free a moment ago. A tool that loses the race fails to bind, and the
    test starts over with another port (_with_free_port)."""
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.bind(('127.0.0.1', 0))
        return s.getsockname()[1]


class _Launcher:
    """The launcher in a child JVM, stdout and stderr in files (pipes could fill and block it).
    A context manager: leaving it kills the JVM if it's still running."""

    def __init__(self, command, workdir):
        self.command = command
        self.workdir = workdir
        self.out_path = join(workdir, 'stdout.txt')
        self.err_path = join(workdir, 'stderr.txt')
        self.process = None

    def __enter__(self):
        log('tools: ' + ' '.join(self.command))
        with open(self.out_path, 'wb') as out, open(self.err_path, 'wb') as err:
            self.process = subprocess.Popen(self.command, cwd=self.workdir, stdin=subprocess.DEVNULL, stdout=out, stderr=err)
        return self

    def __exit__(self, *exc):
        if self.process.poll() is None:
            self.process.kill()
            self.process.wait(30)

    def running(self):
        return self.process.poll() is None

    def wait(self, timeout=_EXIT_TIMEOUT):
        try:
            return self.process.wait(timeout)
        except subprocess.TimeoutExpired:
            raise SmokeFailure(f'the launcher did not exit within {timeout}s\n{self.describe()}') from None

    def stdout(self):
        with open(self.out_path, encoding='utf-8', errors='replace') as f:
            return f.read()

    def stderr(self):
        with open(self.err_path, encoding='utf-8', errors='replace') as f:
            return f.read()

    def describe(self):
        tail = '\n'.join('    ' + line for line in self.stderr().splitlines()[-15:]) or '    (empty)'
        return f'  stdout: {self.stdout()[-500:]!r}\n  stderr (tail):\n{tail}'

    def lost_bind_race(self):
        return not self.running() and re.search(r'(?i)address already in use|BindException', self.stderr()) is not None


def _connect(launcher, port):
    """A socket connected to the tool's server, retried until it listens."""
    deadline = time.time() + _CONNECT_TIMEOUT
    while True:
        if not launcher.running():
            raise SmokeFailure(f'the launcher exited ({launcher.process.returncode}) before its tool accepted a connection\n{launcher.describe()}')
        try:
            s = socket.create_connection(('127.0.0.1', port), timeout=5)
            s.settimeout(_MESSAGE_TIMEOUT)
            return s
        except OSError:
            if time.time() > deadline:
                raise SmokeFailure(f'nothing listening on port {port} after {_CONNECT_TIMEOUT}s\n{launcher.describe()}') from None
            time.sleep(0.2)


# ---------------------------------------------------------------------------
# DAP: Content-Length framed JSON over TCP
# ---------------------------------------------------------------------------

class _DapClient:

    def __init__(self, sock):
        self.sock = sock
        self.file = sock.makefile('rwb')
        self.seq = 0
        self.events = []
        self.output = []

    def close(self):
        try:
            self.file.close()
        finally:
            self.sock.close()

    def _send(self, command, arguments):
        self.seq += 1
        body = json.dumps({'seq': self.seq, 'type': 'request', 'command': command, 'arguments': arguments}).encode()
        self.file.write(b'Content-Length: %d\r\n\r\n' % len(body) + body)
        self.file.flush()
        return self.seq

    def _recv(self):
        length = None
        while True:
            line = self.file.readline()
            if not line:
                raise SmokeFailure('DAP: the server closed the connection')
            line = line.strip()
            if not line:
                break
            if line.lower().startswith(b'content-length:'):
                length = int(line.split(b':')[1])
        _check(length is not None, 'DAP: a message without Content-Length')
        message = json.loads(self.file.read(length))
        if message.get('type') == 'event' and message.get('event') == 'output':
            self.output.append(message.get('body', {}).get('output', ''))
        return message

    def request(self, command, **arguments):
        """Sends a request and returns its response's body; fails unless it succeeded."""
        seq = self._send(command, arguments)
        while True:
            message = self._recv()
            if message.get('type') == 'response' and message.get('request_seq') == seq:
                _check(message.get('success'), f'DAP {command} failed: {message.get("message")}')
                return message.get('body', {})
            if message.get('type') == 'event':
                self.events.append(message)

    def wait_event(self, *names):
        while True:
            while self.events:
                event = self.events.pop(0)
                if event['event'] in names:
                    return event
            self.events.append(self._recv())

    def variables(self, frame_id):
        """Every variable in every scope of a frame, as {name: value}."""
        found = {}
        for scope in self.request('scopes', frameId=frame_id)['scopes']:
            for variable in self.request('variables', variablesReference=scope['variablesReference']).get('variables', []):
                found.setdefault(variable['name'], variable['value'])
        return found


def _dap_stopped(client, path, expected_x):
    """At the breakpoint in `square`: checks the stack and x, and returns the thread."""
    event = client.wait_event('stopped', 'terminated', 'exited')
    _check(event['event'] == 'stopped', f'DAP: expected to stop at the breakpoint, got {event["event"]}')
    _check(event['body'].get('reason') == 'breakpoint', f'DAP: stopped for {event["body"].get("reason")}, not the breakpoint')
    thread = event['body']['threadId']
    frames = client.request('stackTrace', threadId=thread)['stackFrames']
    names = [f['name'] for f in frames]
    top = frames[0]
    _check(top['name'] == 'square' and top['line'] == _BREAK_LINE and top.get('source', {}).get('path') == path,
           f'DAP: expected to stop in square at {path}:{_BREAK_LINE}, got {top["name"]} at {top.get("source", {}).get("path")}:{top["line"]}')
    _check(names[1:2] == ['weighted'], f'DAP: square\'s caller weighted is not next on the stack: {names}')
    _check('sum-squares' in names[2:], f'DAP: weighted\'s caller sum-squares is not on the stack: {names}')
    variables = client.variables(top['id'])
    _check(variables.get('x') == expected_x, f'DAP: expected x = {expected_x} in square, got {variables}')
    # The caller, the closure weighted, has its parameter and the captured n (plan.md M6-05).
    caller = client.variables(frames[1]['id'])
    _check(caller.get('i') == expected_x and caller.get('n') == '5',
           f'DAP: expected i = {expected_x} and the captured n = 5 in weighted, got {caller}')
    log(f'  dap: stopped at the breakpoint in square, stack {names}, x = {variables["x"]}, weighted\'s captured n = {caller["n"]}')
    return thread, top


def _dap(workdir, port, command):
    path = join(workdir, _DEBUG_PROGRAM)
    with _Launcher(command(['--dap=127.0.0.1:' + str(port), '--dap.WaitAttached', '--dap.Suspend=false', path]), workdir) as launcher:
        client = _DapClient(_connect(launcher, port))
        try:
            client.request('initialize', adapterID='scheme', linesStartAt1=True, columnsStartAt1=True, pathFormat='path')
            client.request('attach')
            breakpoints = client.request('setBreakpoints', source={'path': path}, breakpoints=[{'line': _BREAK_LINE}])['breakpoints']
            _check(len(breakpoints) == 1, f'DAP: setBreakpoints answered {breakpoints}')
            client.request('configurationDone')

            # The first hit: square(0). Step over one statement: still in square.
            thread, first = _dap_stopped(client, path, '0')
            client.request('next', threadId=thread)
            event = client.wait_event('stopped', 'terminated', 'exited')
            # GraalVM's adapter reports a finished step as 'debugger_statement'; 'step' is DAP's own word.
            _check(event['event'] == 'stopped' and event['body'].get('reason') in ('step', 'debugger_statement'),
                   f'DAP: expected to stop after the step, got {event["event"]} {event.get("body")}')
            top = client.request('stackTrace', threadId=event['body']['threadId'])['stackFrames'][0]
            _check(top['name'] == 'square' and (top['line'], top['column']) != (first['line'], first['column']),
                   f'DAP: after stepping over, expected a later statement of square, got {top["name"]} at {top["line"]}:{top["column"]}')
            stepped = client.variables(top['id'])
            # A runtime marker (a tail call request) never shows as a foreign object (plan.md M6-05).
            _check(not any('scheme.runtime.' in str(v) for v in stepped.values()), f'DAP: a runtime object leaked into the variables: {stepped}')
            local = {k: v for k, v in stepped.items() if not v.startswith('#<procedure')}
            log(f'  dap: stepped over to {top["name"]} {top["line"]}:{top["column"]}, variables {local}')

            # Continue: the next hit is square(1). Then clear the breakpoint and run to the end.
            client.request('continue', threadId=thread)
            thread, _ = _dap_stopped(client, path, '1')
            client.request('setBreakpoints', source={'path': path}, breakpoints=[])
            client.request('continue', threadId=thread)
            event = client.wait_event('terminated', 'exited')
            log(f'  dap: continued to the end ({event["event"]})')
        finally:
            client.close()
        status = launcher.wait()
        # The adapter writes its banner to stdout before the program runs.
        _check(status == 0 and launcher.stdout().endswith('\n' + _DEBUG_OUTPUT) and '[Graal DAP] Client connected' in launcher.stdout(),
               f'DAP: the program should print {_DEBUG_OUTPUT!r} and exit 0; exit {status}\n{launcher.describe()}')


# ---------------------------------------------------------------------------
# Chrome DevTools Protocol: JSON over a websocket
# ---------------------------------------------------------------------------

class _CdpClient:

    def __init__(self, sock, ws_path, port):
        self.sock = sock
        self.ids = 0
        self.events = []
        key = base64.b64encode(os.urandom(16)).decode()
        sock.sendall((f'GET {ws_path} HTTP/1.1\r\nHost: 127.0.0.1:{port}\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n'
                      f'Sec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n\r\n').encode())
        response = b''
        while b'\r\n\r\n' not in response:
            chunk = sock.recv(1)
            _check(chunk, 'CDP: the server closed the connection during the websocket handshake')
            response += chunk
        status = response.split(b'\r\n')[0].decode()
        _check(' 101 ' in status, f'CDP: websocket handshake answered {status}')

    def close(self):
        self.sock.close()

    def _send(self, obj):
        data = json.dumps(obj).encode()
        mask = os.urandom(4)
        n = len(data)
        if n < 126:
            header = bytes([0x81, 0x80 | n])
        elif n < 65536:
            header = bytes([0x81, 0x80 | 126]) + struct.pack('>H', n)
        else:
            header = bytes([0x81, 0x80 | 127]) + struct.pack('>Q', n)
        self.sock.sendall(header + mask + bytes(b ^ mask[i % 4] for i, b in enumerate(data)))

    def _recv_exact(self, n):
        buf = b''
        while len(buf) < n:
            chunk = self.sock.recv(n - len(buf))
            if not chunk:
                raise SmokeFailure('CDP: the server closed the connection')
            buf += chunk
        return buf

    def _recv(self):
        """The next text message (fragmented messages are reassembled; pings are skipped)."""
        payload = b''
        while True:
            b1, b2 = self._recv_exact(2)
            n = b2 & 0x7f
            if n == 126:
                n = struct.unpack('>H', self._recv_exact(2))[0]
            elif n == 127:
                n = struct.unpack('>Q', self._recv_exact(8))[0]
            data = self._recv_exact(n)
            opcode = b1 & 0x0f
            if opcode == 8:
                raise SmokeFailure('CDP: the server closed the websocket')
            if opcode in (9, 10):
                continue
            payload += data
            if b1 & 0x80:
                message = json.loads(payload)
                return message

    def call(self, method, **params):
        """Sends a command and returns its result; fails if it answers with an error."""
        self.ids += 1
        self._send({'id': self.ids, 'method': method, 'params': params})
        while True:
            message = self._recv()
            if message.get('id') == self.ids:
                _check('error' not in message, f'CDP {method} failed: {message.get("error")}')
                return message.get('result', {})
            self.events.append(message)

    def wait_event(self, method):
        while True:
            while self.events:
                event = self.events.pop(0)
                if event.get('method') == method:
                    return event['params']
            self.events.append(self._recv())

    def scope_variables(self, frame):
        """The variables of a call frame's local scopes (not the global one), as {name: description}."""
        found = {}
        for scope in frame['scopeChain']:
            if scope['type'] == 'global':
                continue
            for p in self.call('Runtime.getProperties', objectId=scope['object']['objectId'], ownProperties=True)['result']:
                value = p.get('value', {})
                found.setdefault(p['name'], value.get('description', value.get('value')))
        return found


def _cdp_paused(client, path, expected_x):
    paused = client.wait_event('Debugger.paused')
    frames = paused['callFrames']
    names = [f['functionName'] for f in frames]
    top = frames[0]
    line = top['location']['lineNumber'] + 1
    _check(top['functionName'] == 'square' and line == _BREAK_LINE and top.get('url', '').endswith(path),
           f'CDP: expected to pause in square at {path}:{_BREAK_LINE}, got {top["functionName"]} at {top.get("url")}:{line}')
    _check(names[1:2] == ['weighted'], f'CDP: square\'s caller weighted is not next on the stack: {names}')
    _check('sum-squares' in names[2:], f'CDP: weighted\'s caller sum-squares is not on the stack: {names}')
    variables = client.scope_variables(top)
    _check(str(variables.get('x')) == expected_x, f'CDP: expected x = {expected_x} in square, got {variables}')
    # The caller, the closure weighted, has its parameter and the captured n (plan.md M6-05).
    caller = client.scope_variables(frames[1])
    _check(str(caller.get('i')) == expected_x and str(caller.get('n')) == '5',
           f'CDP: expected i = {expected_x} and the captured n = 5 in weighted, got {caller}')
    log(f'  inspect: paused at the breakpoint in square ({paused["reason"]}), stack {names}, x = {variables["x"]}, '
        f'weighted\'s captured n = {caller["n"]}')
    return top


def _inspect(workdir, port, command):
    path = join(workdir, _DEBUG_PROGRAM)
    with _Launcher(command(['--inspect=' + str(port), '--inspect.WaitAttached', '--inspect.Suspend=false', path]), workdir) as launcher:
        _connect(launcher, port).close()
        targets = None
        deadline = time.time() + _CONNECT_TIMEOUT
        while targets is None:
            try:
                with urllib.request.urlopen(f'http://127.0.0.1:{port}/json', timeout=10) as response:
                    targets = json.load(response)
            except OSError:
                if time.time() > deadline or not launcher.running():
                    raise SmokeFailure(f'CDP: no target list at http://127.0.0.1:{port}/json\n{launcher.describe()}') from None
                time.sleep(0.2)
        _check(targets and 'webSocketDebuggerUrl' in targets[0], f'CDP: target list {targets}')
        ws_path = '/' + targets[0]['webSocketDebuggerUrl'].split('/', 3)[3]
        client = _CdpClient(_connect(launcher, port), ws_path, port)
        try:
            client.call('Runtime.enable')
            client.call('Debugger.enable')
            breakpoint = client.call('Debugger.setBreakpointByUrl', lineNumber=_BREAK_LINE - 1, url='file://' + path)
            client.call('Runtime.runIfWaitingForDebugger')

            # The first hit: square(0). Step over one statement: still in square.
            first = _cdp_paused(client, path, '0')
            client.call('Debugger.stepOver')
            top = client.wait_event('Debugger.paused')['callFrames'][0]
            location = top['location']
            _check(top['functionName'] == 'square' and (location['lineNumber'], location.get('columnNumber', 0)) !=
                   (first['location']['lineNumber'], first['location'].get('columnNumber', 0)),
                   f'CDP: after stepping over, expected a later statement of square, got {top["functionName"]} at {location}')
            log(f'  inspect: stepped over to {top["functionName"]} {location["lineNumber"] + 1}:{location.get("columnNumber", 0) + 1}')

            # Resume: the next hit is square(1). Then remove the breakpoint and run to the end.
            client.call('Debugger.resume')
            _cdp_paused(client, path, '1')
            client.call('Debugger.removeBreakpoint', breakpointId=breakpoint['breakpointId'])
            client.call('Debugger.resume')
            deadline = time.time() + _EXIT_TIMEOUT
            while not launcher.stdout().endswith(_DEBUG_OUTPUT) and launcher.running() and time.time() < deadline:
                time.sleep(0.1)
        finally:
            # The inspector keeps the JVM alive until its client goes away.
            client.close()
        status = launcher.wait()
        # The inspector writes its banner (the websocket URL) to stdout before the program runs.
        _check(status == 0 and launcher.stdout().endswith('\n' + _DEBUG_OUTPUT) and 'Debugger listening on ws://127.0.0.1:' in launcher.stdout(),
               f'CDP: the program should print {_DEBUG_OUTPUT!r} and exit 0; exit {status}\n{launcher.describe()}')
        log('  inspect: resumed to the end')


# ---------------------------------------------------------------------------
# The profilers
# ---------------------------------------------------------------------------

def _profile(workdir, command, options, expected, program=_PROFILE_PROGRAM):
    """Runs the profile program with these options, given the output file's path; checks that the
    tool's output (the file if it wrote one, else stdout) matches each of `expected`."""
    output = join(workdir, 'profile.txt')
    with _Launcher(command(options(output) + [join(workdir, program)]), workdir) as launcher:
        status = launcher.wait()
        _check(status == 0, f'the program failed: exit {status}\n{launcher.describe()}')
        if os.path.exists(output):
            with open(output, encoding='utf-8') as f:
                text = f.read()
        else:
            text = launcher.stdout()
    for pattern in expected:
        _check(re.search(pattern, text, re.MULTILINE), f'the output does not match {pattern!r}:\n{text[:3000]}')
    return text


def _cpusampler(workdir, commands):
    command = commands.tools
    # Rows read: ` name || total || self || file~lines:chars`. fib must be sampled at its own
    # definition (lines 2-5), and the call tree must have fib under run-fib (whose named let is a
    # loop of its own, plan.md M7-02) under <program>.
    histogram = _profile(workdir, command, lambda out: ['--cpusampler', '--cpusampler.Period=1ms', '--cpusampler.OutputFile=' + out],
                         [r'^Sampling Histogram\.', r'^ fib +\|\|.*\|\| \S*profileme\.scm~2-5:', r'^ run-fib +\|\|.*profileme\.scm~11-15:'])
    samples = re.search(r'Recorded (\d+) samples', histogram)
    _check(samples and int(samples.group(1)) > 0, f'cpusampler: no samples\n{histogram[:2000]}')
    log(f'  cpusampler: histogram names fib and run-fib ({samples.group(1)} samples)')
    _profile(workdir, command, lambda out: ['--cpusampler=calltree', '--cpusampler.Period=1ms', '--cpusampler.OutputFile=' + out],
             [r'^Sampling Call Tree\.', r'^  <program> +\|\|', r'^   run-fib +\|\|.*profileme\.scm~11-15:', r'^    fib +\|\|.*profileme\.scm~2-5:'])
    log('  cpusampler: call tree has <program> > run-fib > fib')
    # Past the spill depth, frames wait on the heap, not the Java stack; the prompt reports them as
    # its asynchronous stack, which the sampler splices in after it (design.md "Spilled frames in
    # tools"). Every sample in spin must have every frame of down (n = 500 down to 1), and no sample
    # may have a frame twice: at most 501 frames of down, the 501st being down(0) before it
    # tail-calls spin. That sample is as long as one in spin, so "the deepest sample" is either.
    tree = json.loads(_profile(workdir, command, lambda out: ['--cpusampler', '--cpusampler.Output=json', '--cpusampler.Period=1ms', '--cpusampler.OutputFile=' + out],
                               [r'"samples"'], program=_SPILL_PROGRAM))
    in_spin = 0
    for thread in tree['contexts'][0]['profile']:
        work = [(root, []) for root in thread['samples']]
        while work:
            node, path = work.pop()
            path = path + [node['root_name']]
            if node['self_hit_count'] > 0:
                frames = path.count('down')
                if path[-1] == 'spin':
                    in_spin += node['self_hit_count']
                    _check(frames == _SPILL_DEPTH,
                           f'cpusampler: a sample in spin has {frames} frames of down, not {_SPILL_DEPTH}: {path[:5]} ... {path[-5:]}')
                _check(frames <= _SPILL_DEPTH + 1,
                       f'cpusampler: a sample has {frames} frames of down, more than the {_SPILL_DEPTH + 1} there are: {path[:5]} ... {path[-5:]}')
            work.extend((child, path) for child in node['children'])
    _check(in_spin > 0, 'cpusampler: no sample in spin')
    log(f'  cpusampler: all {in_spin} samples at the bottom of a recursion {_SPILL_DEPTH} deep have all {_SPILL_DEPTH} frames')


def _memtracer(workdir, commands):
    # The memory tracer writes to stdout (it has no output file option). make-table's loop (lines
    # 7-10, a loop of make-table's own, plan.md M7-02) makes a pair and an 8-element vector per
    # iteration, 100 iterations per call, 10 calls: 2000 allocations that the language reports
    # (M7-07).
    histogram = _profile(workdir, commands.tools, lambda out: ['--experimental-options', '--memtracer'],
                         [r'Location Histogram with Allocation Counts\. Recorded a total of \d+ allocations\.',
                          r'^ make-table +\| +2000 .*\| \S*profileme\.scm~6-10:'])
    total = int(re.search(r'Recorded a total of (\d+) allocations', histogram).group(1))
    _check(total >= 2000, f'memtracer: only {total} allocations\n{histogram[:2000]}')
    log(f'  memtracer: {total} allocations, 2000 of them in make-table\'s loop')


# ---------------------------------------------------------------------------
# The REPL on a terminal
# ---------------------------------------------------------------------------

class _Terminal:
    """The launcher's REPL on a pseudo-terminal: `send` types, `expect` waits for output."""

    def __init__(self, command, workdir, env):
        self.output = b''
        self.seen = 0
        self.pid, self.fd = pty.fork()
        if self.pid == 0:
            try:
                os.chdir(workdir)
                os.execvpe(command[0], command, env)
            finally:
                os._exit(127)
        # A new pseudo-terminal is 0x0, which JLine can't draw a line in.
        fcntl.ioctl(self.fd, termios.TIOCSWINSZ, struct.pack('HHHH', 24, 80, 0, 0))

    def send(self, text):
        os.write(self.fd, text.encode('utf-8'))

    def _read(self, timeout):
        ready, _, _ = select.select([self.fd], [], [], timeout)
        if not ready:
            return False
        try:
            data = os.read(self.fd, 65536)
        except OSError:  # EIO: the child closed the terminal.
            data = b''
        if not data:
            return None
        self.output += data
        return True

    def expect(self, pattern, what, timeout=_MESSAGE_TIMEOUT):
        """Waits for the output after the last match to match `pattern` (on text without ANSI
        escapes, with \r\n as \n)."""
        deadline = time.time() + timeout
        while True:
            text = self.text()
            match = re.compile(pattern).search(text, self.seen)
            if match:
                self.seen = match.end()
                return match
            remaining = deadline - time.time()
            if remaining <= 0 or self._read(remaining) is None:
                raise SmokeFailure(f'terminal: {what}: no {pattern!r} in the output\n{text[self.seen:][-1500:]!r}')

    def text(self):
        """The output so far, without ANSI escapes, with \r\n as \n."""
        raw = self.output.decode('utf-8', 'replace')
        raw = re.sub(r'\x1b\[[0-9;?]*[ -/]*[@-~]|\x1b[()][0-9A-B]|\x1b[=>]|\x07', '', raw)
        return raw.replace('\r\n', '\n')

    def wait(self, timeout=_EXIT_TIMEOUT):
        deadline = time.time() + timeout
        while time.time() < deadline:
            pid, status = os.waitpid(self.pid, os.WNOHANG)
            if pid:
                os.close(self.fd)
                return os.waitstatus_to_exitcode(status)
            self._read(0.2)
        raise SmokeFailure(f'terminal: the REPL did not exit within {timeout}s\n{self.text()[-1500:]!r}')

    def kill(self):
        try:
            os.kill(self.pid, 9)
            os.waitpid(self.pid, 0)
        except OSError:
            pass


def _terminal_session(workdir, command, label):
    history = join(workdir, 'history-' + label)
    env = dict(os.environ, SCHEME_HISTORY=history, TERM='xterm')
    term = _Terminal(command, workdir, env)
    try:
        term.expect(r'> $', 'the first prompt')
        term.send('(define x 4)\r')
        term.expect(r'> $', 'the prompt after a definition')
        term.send('(* x x)\r')
        term.expect(r'\n16\n> $', 'a value')
        # Line editing: backspace over the 3.
        term.send('(+ 1 3\x7f2)\r')
        term.expect(r'\n3\n> $', 'an edited line')
        # The history: up arrow (as a terminal sends it in the keypad mode JLine sets) recalls (+ 1 2).
        term.send('\x1bOA')
        term.expect(r'\(\+ 1 2\)', 'the previous line, recalled')
        term.send('\r')
        term.expect(r'\n3\n> $', 'a recalled line')
        # The continuation prompt inside a datum.
        term.send('(+ 10\r')
        term.expect(r'\n  $', 'the continuation prompt')
        term.send('20)\r')
        term.expect(r'\n30\n> $', 'a datum over two lines')
        # Every form's values, and read-line reading the next line typed.
        term.send('1 (values 2 3)\r')
        term.expect(r'\n1\n2\n3\n> $', 'the values of every form of a line')
        term.send('(read-line)\r')
        term.send('typed text\r')
        term.expect(r'\n"typed text"\n> $', 'read-line reading the next line')
        # Ctrl-C drops the unfinished input: else (+ 1 (+ 2 2)) would be 5.
        term.send('(+ 1\r')
        term.expect(r'\n  $', 'the continuation prompt again')
        term.send('\x03')
        term.expect(r'> $', 'a fresh prompt after Ctrl-C')
        term.send('(+ 2 2)\r')
        term.expect(r'\n4\n> $', 'the input after Ctrl-C')
        # Ctrl-D ends the session.
        term.send('\x04')
        status = term.wait()
    finally:
        term.kill()
    _check(status == 0, f'terminal ({label}): exit {status}\n{term.text()[-1500:]!r}')
    with open(history, encoding='utf-8') as f:
        saved = f.read()
    _check('(* x x)' in saved and '(+ 1 2)' in saved and 'typed text' not in saved,
           f'terminal ({label}): the history file should have the REPL\'s lines and not what read-line read:\n{saved}')
    log(f'  terminal ({label}): editing, history, continuation prompt, Ctrl-C, read-line, Ctrl-D')


def _terminal(workdir, commands):
    _terminal_session(workdir, commands.bare([]), 'JVM')
    native = os.environ.get('SCHEME_NATIVE_LAUNCHER')
    if native:
        _check(os.path.isfile(native), f'SCHEME_NATIVE_LAUNCHER={native} is not a file')
        _terminal_session(workdir, [native], 'native')


def _help(workdir, commands):
    command = commands.tools
    tools = ['--inspect', '--dap', '--cpusampler', '--memtracer']
    with _Launcher(command(['--help:tools']), workdir) as launcher:
        status = launcher.wait()
        text = launcher.stdout()
    _check(status == 0, f'--help:tools: exit {status}\n{launcher.describe()}')
    missing = [t for t in tools if not re.search(r'^\s+' + re.escape(t) + r'\b', text, re.MULTILINE)]
    _check(not missing, f'--help:tools with the tools does not list {missing}:\n{text[:3000]}')
    with _Launcher(command(['--help']), workdir) as launcher:
        launcher.wait()
        listed = launcher.stdout()
    for tool in ('inspect', 'dap', 'cpusampler', 'memtracer'):
        _check(re.search(r'^  ' + tool + r' ', listed, re.MULTILINE), f'--help\'s Tools table does not list {tool}:\n{listed[-2000:]}')
    with _Launcher(commands.bare(['--help:tools']), workdir) as launcher:
        launcher.wait()
        bare = launcher.stdout()
    present = [t for t in tools if re.search(r'^\s+' + re.escape(t) + r'\b', bare, re.MULTILINE)]
    _check(not present, f'without the tools jars, --help:tools should list no tool options, but lists {present}')
    log('  help: the Tools table and --help:tools list inspect, dap, cpusampler and memtracer; without the tools jars, none')


def _with_free_port(test, attempts=3):
    """Runs test(workdir, port, command), with another port if the tool lost the race for the first one."""
    def run(workdir, commands):
        for attempt in range(attempts):
            try:
                return test(workdir, _free_port(), commands.tools)
            except SmokeFailure as failure:
                if attempt + 1 < attempts and 'Address already in use' in str(failure):
                    log(f'  port taken, retrying: {failure}')
                    continue
                raise
    return run


SMOKE_TESTS = {
    'help': ('--help lists the tools only with the tools jars', _help),
    'dap': ('DAP: breakpoint, stack, locals, step over, continue', _with_free_port(_dap)),
    'inspect': ('Chrome inspector: breakpoint, call frames, scope variables, step over, resume', _with_free_port(_inspect)),
    'cpusampler': ('CPU sampler: histogram and call tree name the procedures', _cpusampler),
    'memtracer': ('memory tracer counts the program\'s allocations', _memtracer),
    'terminal': ('the REPL on a terminal: line editing, history, read-line', _terminal),
}


Commands = collections.namedtuple('Commands', ['tools', 'bare'])
Commands.__doc__ = """`tools(args)` is the java command line that runs the launcher with the tools jars on its
module path, `bare(args)` the one without it."""


def run(names, programs_dir, commands):
    """Runs the named smoke tests (all if none) with these Commands. Returns the names that failed."""
    unknown = [n for n in names if n not in SMOKE_TESTS]
    if unknown:
        fail(f'tools: unknown test(s) {unknown}; tests: ' + ' '.join(SMOKE_TESTS))
    failed = []
    for name in names or list(SMOKE_TESTS):
        description, test = SMOKE_TESTS[name]
        start = time.time()
        with tempfile.TemporaryDirectory(prefix='r7rs-tools-') as workdir:
            workdir = os.path.realpath(workdir)
            for program in (_DEBUG_PROGRAM, _PROFILE_PROGRAM, _SPILL_PROGRAM):
                with open(join(programs_dir, program), encoding='utf-8') as src, open(join(workdir, program), 'w', encoding='utf-8') as dst:
                    dst.write(src.read())
            try:
                test(workdir, commands)
                log(f'PASS    {name}: {description} ({time.time() - start:.1f}s)')
            except (SmokeFailure, OSError, ValueError, KeyError, IndexError) as e:
                failed.append(name)
                log(f'FAIL    {name}: {description} ({time.time() - start:.1f}s)\n  {type(e).__name__}: {e}')
    return failed

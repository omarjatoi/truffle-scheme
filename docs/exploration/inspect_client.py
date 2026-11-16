"""Minimal Chrome DevTools Protocol client (no dependencies) for GraalVM's --inspect.

usage: inspect_client.py host port file.scm line
"""
import base64
import json
import os
import socket
import struct
import sys
import time
import urllib.request

host, port, path, line = sys.argv[1], int(sys.argv[2]), sys.argv[3], int(sys.argv[4])

targets = None
for _ in range(150):
    try:
        targets = json.load(urllib.request.urlopen(f"http://{host}:{port}/json"))
        break
    except Exception:
        time.sleep(0.2)
print("targets:", json.dumps(targets)[:300])
ws_url = targets[0]["webSocketDebuggerUrl"]
ws_path = "/" + ws_url.split("/", 3)[3]

s = socket.create_connection((host, port))
key = base64.b64encode(os.urandom(16)).decode()
s.sendall((f"GET {ws_path} HTTP/1.1\r\nHost: {host}:{port}\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
           f"Sec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n\r\n").encode())
resp = b""
while b"\r\n\r\n" not in resp:
    resp += s.recv(1)
print("handshake:", resp.split(b"\r\n")[0].decode())


def send(obj):
    data = json.dumps(obj).encode()
    mask = os.urandom(4)
    header = bytes([0x81])
    n = len(data)
    if n < 126:
        header += bytes([0x80 | n])
    elif n < 65536:
        header += bytes([0x80 | 126]) + struct.pack(">H", n)
    else:
        header += bytes([0x80 | 127]) + struct.pack(">Q", n)
    s.sendall(header + mask + bytes(b ^ mask[i % 4] for i, b in enumerate(data)))


def recv_exact(n):
    buf = b""
    while len(buf) < n:
        chunk = s.recv(n - len(buf))
        if not chunk:
            raise EOFError
        buf += chunk
    return buf


def recv():
    b1, b2 = recv_exact(2)
    n = b2 & 0x7f
    if n == 126:
        n = struct.unpack(">H", recv_exact(2))[0]
    elif n == 127:
        n = struct.unpack(">Q", recv_exact(8))[0]
    data = recv_exact(n)
    if b1 & 0x0f == 8:
        raise EOFError("closed")
    return json.loads(data)


ids = [0]


def call(method, params=None):
    ids[0] += 1
    send({"id": ids[0], "method": method, "params": params or {}})
    while True:
        m = recv()
        if m.get("id") == ids[0]:
            return m
        events.append(m)


events = []


def wait_event(name):
    while True:
        while events:
            e = events.pop(0)
            if e.get("method") == name:
                return e
        events.append(recv())


print("Runtime.enable", "result" in call("Runtime.enable"))
print("Debugger.enable", "result" in call("Debugger.enable"))
bp = call("Debugger.setBreakpointByUrl", {"lineNumber": line - 1, "url": "file://" + path})
print("setBreakpointByUrl:", json.dumps(bp.get("result", bp))[:300])
call("Runtime.runIfWaitingForDebugger")
paused = wait_event("Debugger.paused")
frames = paused["params"]["callFrames"]
print("paused, reason:", paused["params"]["reason"])
for f in frames:
    loc = f["location"]
    print("  frame %-12s line %d col %d" % (f["functionName"], loc["lineNumber"] + 1, loc.get("columnNumber", 0) + 1))
for scope in frames[0]["scopeChain"]:
    props = call("Runtime.getProperties", {"objectId": scope["object"]["objectId"], "ownProperties": True})
    items = [(p["name"], p.get("value", {}).get("description", p.get("value", {}).get("value"))) for p in props["result"]["result"]][:8]
    print("  scope %-8s %s" % (scope["type"], items))
ev = call("Debugger.evaluateOnCallFrame", {"callFrameId": frames[0]["callFrameId"], "expression": "(* x 10)"})
print("evaluateOnCallFrame (* x 10):", json.dumps(ev.get("result", ev))[:200])
call("Debugger.stepOver")
paused = wait_event("Debugger.paused")
f = paused["params"]["callFrames"][0]
print("after stepOver: %s line %d" % (f["functionName"], f["location"]["lineNumber"] + 1))
call("Debugger.removeBreakpoint", {"breakpointId": bp["result"]["breakpointId"]})
call("Debugger.resume")
try:
    while True:
        m = recv()
        if m.get("method"):
            print("  event", m["method"])
except EOFError:
    print("closed")

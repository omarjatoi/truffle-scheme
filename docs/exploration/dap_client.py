"""Minimal DAP client: attach to a GraalVM --dap server, break, inspect, step.

usage: dap_client.py host port file.scm line [steps]
"""
import json
import socket
import sys
import time

host, port, path, line = sys.argv[1], int(sys.argv[2]), sys.argv[3], int(sys.argv[4])
steps = int(sys.argv[5]) if len(sys.argv) > 5 else 2

for _ in range(150):
    try:
        s = socket.create_connection((host, port))
        break
    except OSError:
        time.sleep(0.2)
f = s.makefile('rwb')
seq = 0


def send(cmd, args=None):
    global seq
    seq += 1
    body = json.dumps({"seq": seq, "type": "request", "command": cmd, "arguments": args or {}}).encode()
    f.write(b"Content-Length: %d\r\n\r\n" % len(body) + body)
    f.flush()
    return seq


def recv():
    n = None
    while True:
        h = f.readline()
        if not h:
            raise EOFError("closed")
        h = h.strip()
        if not h:
            break
        if h.lower().startswith(b"content-length:"):
            n = int(h.split(b":")[1])
    return json.loads(f.read(n))


def wait(pred):
    while True:
        m = recv()
        if m.get("type") == "event" and m["event"] == "output":
            print("  [output]", m["body"].get("output", "").rstrip())
            continue
        if pred(m):
            return m
        if m.get("type") == "event":
            print("  [event]", m["event"], json.dumps(m.get("body", {}))[:160])


def response(r):
    return wait(lambda m: m.get("type") == "response" and m["request_seq"] == r)


print("initialize:", response(send("initialize", {"adapterID": "graalvm", "linesStartAt1": True, "columnsStartAt1": True}))["success"])
print("attach:", response(send("attach", {}))["success"])
bp = response(send("setBreakpoints", {"source": {"path": path}, "breakpoints": [{"line": line}]}))
print("setBreakpoints:", json.dumps(bp["body"]))
response(send("configurationDone"))


def stopped():
    ev = wait(lambda m: m.get("type") == "event" and m["event"] in ("stopped", "terminated", "exited"))
    print("EVENT", ev["event"], json.dumps(ev.get("body", {})))
    return ev


def show(thread):
    st = response(send("stackTrace", {"threadId": thread}))
    frames = st["body"]["stackFrames"]
    for fr in frames[:8]:
        print("  frame %-14s %s:%s:%s" % (fr["name"], fr.get("source", {}).get("name"), fr["line"], fr["column"]))
    sc = response(send("scopes", {"frameId": frames[0]["id"]}))
    for scope in sc["body"]["scopes"]:
        vs = response(send("variables", {"variablesReference": scope["variablesReference"]}))
        print("  scope %-10s %s" % (scope["name"], [(v["name"], v["value"]) for v in vs["body"].get("variables", [])][:12]))
    ev = response(send("evaluate", {"expression": "x", "frameId": frames[0]["id"], "context": "watch"}))
    print("  evaluate x:", ev.get("success"), ev.get("body", {}).get("result"), ev.get("message"))


ev = stopped()
for step in range(steps):
    if ev["event"] != "stopped":
        break
    t = ev["body"]["threadId"]
    show(t)
    response(send("next" if step % 2 == 0 else "stepIn", {"threadId": t}))
    ev = stopped()
if ev["event"] == "stopped":
    show(ev["body"]["threadId"])
    response(send("continue", {"threadId": ev["body"]["threadId"]}))
try:
    while True:
        m = recv()
        if m.get("type") == "event":
            print("  [event]", m["event"], json.dumps(m.get("body", {}))[:200])
        if m.get("type") == "event" and m["event"] in ("terminated", "exited"):
            break
except Exception as e:
    print("done:", e)

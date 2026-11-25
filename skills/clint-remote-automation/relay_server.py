#!/usr/bin/env python3
"""relay_server.py - minimal relay for Clint's outbound relay mode.

Clint (phone) connects OUT to   ws://<this-host>:<ws-port>/   (put it behind TLS -> wss:// for internet use)
AI tools call the same HTTP API on http://127.0.0.1:<http-port>/ and the relay forwards each request
over the WebSocket, so clint_remote.py works unchanged:

  pip install websockets          # only dependency
  python3 relay_server.py --ws-port 9340 --http-port 9341
  CLINT_HOST=http://127.0.0.1:9341 CLINT_TOKEN=<TOKEN> python3 clint_remote.py tabs

The relay never stores the token: it passes the caller's Bearer token inside every command and
Clint itself verifies it. Keep the HTTP side bound to localhost (default) or protect it yourself.
"""
import argparse
import asyncio
import base64
import itertools
import json

from websockets.asyncio.server import serve

phone = None            # current Clint connection
pending = {}            # id -> Future
ids = itertools.count(1)


async def ws_handler(conn):
    global phone
    phone = conn
    print("clint connected:", conn.request.headers.get("X-Clint-Client", "?"), flush=True)
    try:
        async for message in conn:
            try:
                msg = json.loads(message)
            except ValueError:
                continue
            fut = pending.pop(str(msg.get("id")), None)
            if fut and not fut.done():
                fut.set_result(msg)
    finally:
        if phone is conn:
            phone = None
        print("clint disconnected", flush=True)


async def forward(method, path, query, body, token, timeout):
    if phone is None:
        return 503, "application/json", json.dumps({"ok": False, "error": "clint is not connected to the relay"}).encode()
    rid = str(next(ids))
    fut = asyncio.get_running_loop().create_future()
    pending[rid] = fut
    await phone.send(json.dumps({"id": rid, "method": method, "path": path, "query": query,
                                 "body": body, "token": token}))
    try:
        msg = await asyncio.wait_for(fut, timeout)
    except asyncio.TimeoutError:
        pending.pop(rid, None)
        return 504, "application/json", json.dumps({"ok": False, "error": "relay timeout"}).encode()
    ctype = msg.get("contentType", "application/json")
    if "bodyBase64" in msg:
        data = base64.b64decode(msg["bodyBase64"])
    else:
        data = json.dumps(msg.get("body"), ensure_ascii=False).encode("utf-8")
    return int(msg.get("status", 200)), ctype, data


async def http_handler(reader, writer, timeout):
    try:
        head = await reader.readuntil(b"\r\n\r\n")
        lines = head.decode("latin-1").split("\r\n")
        method, target = lines[0].split(" ")[:2]
        headers = {}
        for line in lines[1:]:
            if ":" in line:
                k, v = line.split(":", 1)
                headers[k.strip().lower()] = v.strip()
        length = int(headers.get("content-length", "0") or 0)
        raw = await reader.readexactly(length) if length else b""
        from urllib.parse import urlsplit, parse_qsl
        parts = urlsplit(target)
        query = dict(parse_qsl(parts.query))
        token = query.pop("token", "")
        auth = headers.get("authorization", "")
        if auth.lower().startswith("bearer "):
            token = auth[7:].strip()
        body = json.loads(raw.decode("utf-8")) if raw.strip() else {}
        status, ctype, data = await forward(method.upper(), parts.path or "/", query, body, token, timeout)
    except Exception as e:  # noqa: BLE001
        status, ctype, data = 400, "application/json", json.dumps({"ok": False, "error": str(e)}).encode()
    writer.write(f"HTTP/1.1 {status} X\r\nContent-Type: {ctype}\r\nContent-Length: {len(data)}\r\n"
                 f"Connection: close\r\n\r\n".encode("latin-1") + data)
    await writer.drain()
    writer.close()


async def main():
    p = argparse.ArgumentParser()
    p.add_argument("--ws-host", default="0.0.0.0")
    p.add_argument("--ws-port", type=int, default=9340)
    p.add_argument("--http-host", default="127.0.0.1")
    p.add_argument("--http-port", type=int, default=9341)
    p.add_argument("--timeout", type=float, default=60)
    a = p.parse_args()
    async with serve(ws_handler, a.ws_host, a.ws_port, max_size=32 * 1024 * 1024):
        server = await asyncio.start_server(lambda r, w: http_handler(r, w, a.timeout), a.http_host, a.http_port)
        print(f"ws on {a.ws_host}:{a.ws_port}, http on {a.http_host}:{a.http_port}", flush=True)
        async with server:
            await server.serve_forever()


if __name__ == "__main__":
    asyncio.run(main())

#!/usr/bin/env python3
"""clint_remote.py - drive the Clint browser's built-in remote automation API.

Stdlib only. Configure with --host/--token or the CLINT_HOST / CLINT_TOKEN env vars.

  CLINT_HOST=http://<PHONE_IP>:9333 CLINT_TOKEN=<TOKEN> python3 clint_remote.py tabs
  python3 clint_remote.py open https://example.com --new-tab
  python3 clint_remote.py eval "document.title"
  python3 clint_remote.py click "button[type=submit]"
  python3 clint_remote.py type "input[name=q]" "hello" --submit
  python3 clint_remote.py text
  python3 clint_remote.py screenshot -o page.png
  python3 clint_remote.py back | forward | reload | close [--tab ID] | activate --tab ID
  python3 clint_remote.py raw POST /eval '{"js":"1+1"}'
"""
import argparse
import json
import os
import sys
import urllib.error
import urllib.request


def call(host, token, method, path, body=None, query=None, timeout=60, raw=False):
    url = host.rstrip("/") + path
    if query:
        from urllib.parse import urlencode
        url += "?" + urlencode({k: v for k, v in query.items() if v is not None})
    data = None
    headers = {"Authorization": "Bearer " + token}
    if body is not None:
        data = json.dumps(body).encode("utf-8")
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(url, data=data, method=method, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            payload = resp.read()
            ctype = resp.headers.get("Content-Type", "")
            status = resp.status
    except urllib.error.HTTPError as e:
        payload = e.read()
        ctype = e.headers.get("Content-Type", "")
        status = e.code
    if raw or not ctype.startswith("application/json"):
        return status, ctype, payload
    try:
        return status, ctype, json.loads(payload.decode("utf-8"))
    except ValueError:
        return status, ctype, payload.decode("utf-8", "replace")


def main():
    p = argparse.ArgumentParser(description="Control Clint via its remote automation API")
    p.add_argument("--host", default=os.environ.get("CLINT_HOST", "http://127.0.0.1:9333"),
                   help="base URL, e.g. http://<PHONE_IP>:9333 (env CLINT_HOST)")
    p.add_argument("--token", default=os.environ.get("CLINT_TOKEN", ""),
                   help="access token shown in Clint settings (env CLINT_TOKEN)")
    p.add_argument("--timeout", type=float, default=60)
    sub = p.add_subparsers(dest="cmd", required=True)

    sub.add_parser("ping")
    sub.add_parser("status")
    sub.add_parser("tabs")
    s = sub.add_parser("open"); s.add_argument("url"); s.add_argument("--new-tab", action="store_true")
    s = sub.add_parser("eval"); s.add_argument("js"); s.add_argument("--tab")
    s = sub.add_parser("click"); s.add_argument("selector"); s.add_argument("--tab")
    s = sub.add_parser("type"); s.add_argument("selector"); s.add_argument("text")
    s.add_argument("--submit", action="store_true"); s.add_argument("--tab")
    s = sub.add_parser("text"); s.add_argument("--tab"); s.add_argument("--max", type=int, default=0,
                                                                         help="truncate printed text")
    s = sub.add_parser("screenshot"); s.add_argument("-o", "--output", default="clint.png")
    s.add_argument("--scale", type=float); s.add_argument("--tab")
    for name in ("back", "forward", "reload", "close", "activate"):
        s = sub.add_parser(name); s.add_argument("--tab")
    s = sub.add_parser("raw"); s.add_argument("method"); s.add_argument("path"); s.add_argument("body", nargs="?")
    a = p.parse_args()

    if not a.token and a.cmd != "ping":
        sys.exit("missing token: pass --token or set CLINT_TOKEN")

    def go(method, path, body=None, query=None, raw=False):
        return call(a.host, a.token, method, path, body, query, a.timeout, raw)

    if a.cmd in ("ping", "status", "tabs"):
        st, _, out = go("GET", "/" + a.cmd)
    elif a.cmd == "open":
        st, _, out = go("POST", "/open", {"url": a.url, "newTab": a.new_tab})
    elif a.cmd == "eval":
        st, _, out = go("POST", "/eval", {"js": a.js, "tabId": a.tab})
    elif a.cmd == "click":
        st, _, out = go("POST", "/click", {"selector": a.selector, "tabId": a.tab})
    elif a.cmd == "type":
        st, _, out = go("POST", "/type", {"selector": a.selector, "text": a.text, "submit": a.submit, "tabId": a.tab})
    elif a.cmd == "text":
        st, _, out = go("GET", "/text", query={"tabId": a.tab})
        if isinstance(out, dict) and a.max and len(out.get("text", "")) > a.max:
            out["text"] = out["text"][:a.max] + "…"
    elif a.cmd == "screenshot":
        st, ctype, out = go("GET", "/screenshot", query={"scale": a.scale, "tabId": a.tab}, raw=True)
        if st == 200 and ctype.startswith("image/png"):
            with open(a.output, "wb") as f:
                f.write(out)
            print(json.dumps({"ok": True, "file": a.output, "bytes": len(out)}))
            return
        out = out.decode("utf-8", "replace")
    elif a.cmd in ("back", "forward", "reload", "close", "activate"):
        st, _, out = go("POST", "/" + a.cmd, {"tabId": a.tab})
    else:  # raw
        body = json.loads(a.body) if a.body else None
        st, _, out = go(a.method.upper(), a.path, body)
        if isinstance(out, bytes):
            sys.stdout.buffer.write(out); return

    print(json.dumps(out, ensure_ascii=False, indent=2) if not isinstance(out, str) else out)
    if st >= 400:
        sys.exit(1)


if __name__ == "__main__":
    main()

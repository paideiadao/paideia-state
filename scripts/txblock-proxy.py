#!/usr/bin/env python3
"""
Reverse proxy for the Ergo node used by the replay-regression harness.

Forwards every request to UPSTREAM unmodified, EXCEPT it refuses to forward
a POST to exactly "/transactions" — the replica under test must never be
able to broadcast a transaction to the real network. Stdlib only.

Env vars:
  PORT      - port to listen on (default 9053)
  UPSTREAM  - upstream node base URL (default http://192.168.1.137:9053)
"""
import os
import sys
import urllib.request
import urllib.error
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT = int(os.environ.get("PORT", "9053"))
UPSTREAM = os.environ.get("UPSTREAM", "http://192.168.1.137:9053").rstrip("/")
SOCKET_TIMEOUT = 60

BLOCKED_BODY = (
    b'{"error":1,"reason":"blocked-by-replay-harness",'
    b'"detail":"transaction broadcast disabled"}'
)

# Hop-by-hop headers that must not be forwarded verbatim.
HOP_BY_HOP = {
    "connection",
    "keep-alive",
    "proxy-authenticate",
    "proxy-authorization",
    "te",
    "trailers",
    "transfer-encoding",
    "upgrade",
    "host",
    "content-length",
}


class ProxyHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        # Overridden per-request below; suppress default noisy logging.
        pass

    def _handle(self):
        method = self.command
        path = self.path

        content_length = int(self.headers.get("Content-Length", 0) or 0)
        body = self.rfile.read(content_length) if content_length > 0 else b""

        if method == "POST" and path == "/transactions":
            snippet = body[:200].decode("utf-8", errors="replace")
            print(
                "!!! BLOCKED transaction broadcast attempt: "
                "POST /transactions body[:200]=%r" % snippet,
                flush=True,
            )
            self.send_response(400)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(BLOCKED_BODY)))
            self.end_headers()
            self.wfile.write(BLOCKED_BODY)
            print("%s %s -> 400 (blocked)" % (method, path), flush=True)
            return

        url = UPSTREAM + path
        headers = {
            k: v for k, v in self.headers.items() if k.lower() not in HOP_BY_HOP
        }

        req = urllib.request.Request(url, data=body if body else None, headers=headers, method=method)

        try:
            with urllib.request.urlopen(req, timeout=SOCKET_TIMEOUT) as resp:
                status = resp.status
                resp_headers = [
                    (k, v) for k, v in resp.getheaders() if k.lower() not in HOP_BY_HOP
                ]
                resp_body = resp.read()
        except urllib.error.HTTPError as e:
            status = e.code
            resp_body = e.read() if e.fp else b""
            resp_headers = [
                (k, v) for k, v in e.headers.items() if k.lower() not in HOP_BY_HOP
            ] if e.headers else []
        except Exception as e:
            status = 502
            resp_body = ('{"error":502,"reason":"proxy-upstream-error","detail":%r}' % str(e)).encode(
                "utf-8"
            )
            resp_headers = [("Content-Type", "application/json")]

        self.send_response(status)
        sent_content_length = False
        for k, v in resp_headers:
            if k.lower() == "content-length":
                sent_content_length = True
            self.send_header(k, v)
        if not sent_content_length:
            self.send_header("Content-Length", str(len(resp_body)))
        self.end_headers()
        if method != "HEAD":
            self.wfile.write(resp_body)

        print("%s %s -> %s" % (method, path, status), flush=True)

    def do_GET(self):
        self._handle()

    def do_POST(self):
        self._handle()

    def do_PUT(self):
        self._handle()

    def do_DELETE(self):
        self._handle()

    def do_PATCH(self):
        self._handle()

    def do_HEAD(self):
        self._handle()

    def do_OPTIONS(self):
        self._handle()


def main():
    server = ThreadingHTTPServer(("0.0.0.0", PORT), ProxyHandler)
    server.timeout = SOCKET_TIMEOUT
    print(
        "txblock-proxy listening on 0.0.0.0:%d -> %s (blocks POST /transactions)"
        % (PORT, UPSTREAM),
        flush=True,
    )
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()

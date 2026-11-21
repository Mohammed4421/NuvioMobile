#!/usr/bin/env python3
"""A local download fixture for emulator tests: serves one file slowly, with a strong ETag and
byte-range support (Range, If-Range), so a paused download can resume from where it stopped.
It logs every request's range, so a test can tell a resume (bytes=N-) from a restart (no range).
Usage: slow-fixture-server.py <port> <file> <bytes per second> <request log>"""
import http.server
import os
import re
import sys
import time

PORT, PATH, RATE, LOG = int(sys.argv[1]), sys.argv[2], int(sys.argv[3]), sys.argv[4]
SIZE = os.path.getsize(PATH)
ETAG = f'"fixture-{SIZE}-{int(os.path.getmtime(PATH))}"'


class Handler(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *args):
        pass

    def do_HEAD(self):
        self.respond(body=False)

    def do_GET(self):
        self.respond(body=True)

    def respond(self, body):
        start, end, status = 0, SIZE - 1, 200
        wanted = self.headers.get("Range")
        if_range = self.headers.get("If-Range")
        if wanted and (if_range is None or if_range == ETAG):
            m = re.fullmatch(r"bytes=(\d+)-(\d*)", wanted.strip())
            if not m or int(m.group(1)) >= SIZE:
                self.send_response(416)
                self.send_header("Content-Range", f"bytes */{SIZE}")
                self.send_header("Content-Length", "0")
                self.end_headers()
                return
            start = int(m.group(1))
            end = min(int(m.group(2)), SIZE - 1) if m.group(2) else SIZE - 1
            status = 206
        with open(LOG, "a") as log:
            log.write(f"{self.command} range={wanted or '-'} if-range={if_range or '-'} -> {status} {start}-{end}\n")
        self.send_response(status)
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("ETag", ETAG)
        self.send_header("Content-Type", "application/octet-stream")
        self.send_header("Content-Length", str(end - start + 1))
        if status == 206:
            self.send_header("Content-Range", f"bytes {start}-{end}/{SIZE}")
        self.end_headers()
        if not body:
            return
        chunk = max(1, RATE // 10)
        with open(PATH, "rb") as f:
            f.seek(start)
            left = end - start + 1
            try:
                while left > 0:
                    data = f.read(min(chunk, left))
                    self.wfile.write(data)
                    left -= len(data)
                    time.sleep(0.1)
            except (BrokenPipeError, ConnectionResetError):
                pass


http.server.ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()

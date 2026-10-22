#!/usr/bin/env python3
"""Self check for pixelock_laptop.py against a fake ActivityWatch. Run it directly."""
import datetime as dt
import http.server
import json
import threading

import pixelock_laptop as laptop

EVENTS = [
    {"duration": 1800, "data": {"app": "code", "title": "main.py"}},
    {"duration": 620, "data": {"app": "firefox", "title": "docs"}},
    {"duration": 3000, "data": {"app": "firefox", "title": "cats - YouTube"}},
]


class FakeAw(http.server.BaseHTTPRequestHandler):
    seen = None

    def do_POST(self):
        FakeAw.seen = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        body = json.dumps([EVENTS]).encode()
        self.send_response(200)
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args):
        pass


server = http.server.HTTPServer(("127.0.0.1", 0), FakeAw)
threading.Thread(target=server.serve_forever, daemon=True).start()
url = f"http://127.0.0.1:{server.server_port}"
day = dt.date(2026, 10, 30)

assert laptop.focused_minutes(day, "YouTube", url) == 40, "skip filter"
assert laptop.focused_minutes(day, "", url) == 90, "no filter"
assert laptop.focused_minutes(day, "youtube", url) == 40, "filter ignores case"
assert "2026-10-30T00:00:00" in FakeAw.seen["timeperiods"][0], "day start"
assert "2026-10-31T00:00:00" in FakeAw.seen["timeperiods"][0], "day end"
# Same vector as LaptopTokenTest on the phone side.
assert laptop.token(day, 95, "TESTKEY") == "PXL1.2026-10-30.95." + laptop.sign("PXL1.2026-10-30.95", "TESTKEY")
print("ok", laptop.token(day, 95, "TESTKEY"))
server.shutdown()

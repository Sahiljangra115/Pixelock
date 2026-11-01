#!/usr/bin/env python3
"""Self check for the counting logic in pixelock_laptop.py. Run it directly."""
import pixelock_laptop as p

skip = p.DEFAULT_SKIP
secs = {}
p.add_sample(secs, "Code", "main.py", 1800, skip)
p.add_sample(secs, "firefox", "docs", 620, skip)
p.add_sample(secs, "firefox", "cats - YouTube", 3000, skip)
p.add_sample(secs, "chrome", "(3) Instagram", 600, skip)
p.add_sample(secs, "Code", "other.py", 30, skip)

assert p.classify("firefox", "cats - youtube", skip) == ("d", "Youtube"), "case and label"
assert p.classify("Code", "main.py", skip) == ("f", "Code")
assert p.classify("Code", "main.py", "") == ("f", "Code"), "no filter"

focused, distracted, apps = p.summarize(secs)
assert (focused, distracted) == (40, 60), (focused, distracted)  # 2450 s and 3600 s
assert apps[0] == {"app": "Youtube", "cat": "d", "min": 50}, apps  # biggest first
assert {"app": "Code", "cat": "f", "min": 30} in apps
print("ok", focused, distracted, apps)

# End to end against a fake Supabase: sign up, redeem, push.
import http.server, json, threading, tempfile, pathlib

calls = []

class Fake(http.server.BaseHTTPRequestHandler):
    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers["Content-Length"])) or b"{}")
        calls.append((self.path, self.headers.get("Authorization"), self.headers.get("apikey"), body))
        out = b'{"access_token":"A1","refresh_token":"R1","expires_in":3600}' if "/auth/" in self.path else b""
        self.send_response(200 if out else 204)
        self.end_headers()
        self.wfile.write(out)
    def log_message(self, *a): pass

srv = http.server.HTTPServer(("127.0.0.1", 0), Fake)
threading.Thread(target=srv.serve_forever, daemon=True).start()
p.HOME = pathlib.Path(tempfile.mkdtemp())
p.CONFIG = p.HOME / "config.json"
cfg = {"url": f"http://127.0.0.1:{srv.server_port}", "anon_key": "sb_pub"}
p.rpc(cfg, "redeem_pair_code", {"p_code": "ABCD"})
p.push(cfg, "2026-11-01", secs)
assert calls[0][0] == "/auth/v1/signup" and calls[0][2] == "sb_pub"
assert calls[1][:3] == ("/rest/v1/rpc/redeem_pair_code", "Bearer A1", "sb_pub")
assert calls[2][3]["p_focused"] == 40 and calls[2][3]["p_distracted"] == 60 and calls[2][3]["p_date"] == "2026-11-01"
assert oct(p.CONFIG.stat().st_mode & 0o777) == "0o600", "session file private"
srv.shutdown()
print("fake supabase ok")

#!/usr/bin/env python3
"""Shows today's laptop focus time as a code the Pixelock phone app can scan.

Reads ActivityWatch on this machine. Runs on Linux and Windows. Nothing leaves this laptop:
the phone reads the code off your screen.

    python pixelock_laptop.py --pair KEY    once, with the key from Friends > Laptop time
    python pixelock_laptop.py               each day
    python pixelock_laptop.py --date 2026-10-29 --skip "YouTube|Instagram|Netflix"

Install qrcode (pip install qrcode) to get a QR. Without it, type the printed code into the phone.
"""
import argparse
import datetime as dt
import hashlib
import hmac
import json
import os
import re
import sys
import urllib.error
import urllib.request
from pathlib import Path

AW_URL = "http://localhost:5600"
KEY_FILE = Path.home() / ".pixelock_laptop_key"
DEFAULT_SKIP = "YouTube|Instagram|Reddit|Netflix|Facebook|TikTok|Twitter"
MAX_MINUTES = 1440

QUERY = """
afk = flood(query_bucket(find_bucket("aw-watcher-afk_")));
active = filter_keyvals(afk, "status", ["not-afk"]);
windows = flood(query_bucket(find_bucket("aw-watcher-window_")));
focused = filter_period_intersect(windows, active);
RETURN = merge_events_by_keys(focused, ["app", "title"]);
"""


def sign(body, key):
    return hmac.new(key.encode(), body.encode(), hashlib.sha256).hexdigest()[:32]


def token(date, minutes, key):
    body = f"PXL1.{date}.{minutes}"
    return f"{body}.{sign(body, key)}"


def day_period(date):
    """One local day. Offsets come from the machine, so India and DST both read right."""
    start = dt.datetime.combine(date, dt.time()).astimezone()
    end = dt.datetime.combine(date + dt.timedelta(days=1), dt.time()).astimezone()
    return f"{start.isoformat()}/{end.isoformat()}"


def focused_minutes(date, skip, aw_url=AW_URL):
    request = urllib.request.Request(
        f"{aw_url}/api/0/query/",
        data=json.dumps({"timeperiods": [day_period(date)], "query": [QUERY]}).encode(),
        headers={"Content-Type": "application/json"},
    )
    with urllib.request.urlopen(request, timeout=30) as reply:
        events = json.load(reply)[0]
    pattern = re.compile(skip, re.IGNORECASE) if skip else None
    seconds = sum(
        e["duration"]
        for e in events
        if not (pattern and pattern.search(f'{e["data"].get("app", "")} {e["data"].get("title", "")}'))
    )
    return min(int(seconds // 60), MAX_MINUTES)


def show(text):
    print(text)
    try:
        import qrcode
    except ImportError:
        print("\n(pip install qrcode to see a QR. You can type the code above instead.)")
        return
    qr = qrcode.QRCode(border=2)
    qr.add_data(text)
    qr.print_ascii(invert=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--pair", metavar="KEY", help="save the key the phone shows")
    parser.add_argument("--date", type=dt.date.fromisoformat, default=dt.date.today(), help="YYYY-MM-DD, default today")
    parser.add_argument("--skip", default=DEFAULT_SKIP, help="regex of apps and titles that do not count as focus")
    args = parser.parse_args()

    if args.pair:
        # Created private, so the key is never readable by others, even for a moment.
        with os.fdopen(os.open(KEY_FILE, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600), "w") as file:
            file.write(args.pair.strip())
        print(f"Paired. Key saved in {KEY_FILE}")
        return 0
    if not KEY_FILE.exists():
        print("Not paired yet. Open Friends > Laptop time on the phone, then run --pair KEY.", file=sys.stderr)
        return 2
    try:
        minutes = focused_minutes(args.date, args.skip)
    except (urllib.error.URLError, OSError) as error:
        print(f"Could not read ActivityWatch at {AW_URL}: {error}", file=sys.stderr)
        return 1
    print(f"{args.date}: {minutes} focused minutes")
    show(token(args.date, minutes, KEY_FILE.read_text().strip()))
    return 0


if __name__ == "__main__":
    sys.exit(main())

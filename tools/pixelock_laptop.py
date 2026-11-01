#!/usr/bin/env python3
"""Pixelock laptop tracker. Starts at login, counts focused and distracting time per app, syncs to Supabase.

    python pixelock_laptop.py link CODE     once, with the code from phone: Friends > Laptop time
    python pixelock_laptop.py install       start at every login (Windows Task Scheduler, Linux systemd user)
    python pixelock_laptop.py run           what the login job runs
    python pixelock_laptop.py status        today so far

Windows: nothing to install. Linux GNOME Wayland: needs tools/gnome-extension (see its README).
Only app names and minutes leave this laptop. Window titles are used on this laptop to spot
distracting sites and are never stored or sent.
"""
import argparse
import datetime as dt
import json
import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

HOME = Path.home() / ".pixelock_laptop"
CONFIG = HOME / "config.json"  # url, anon key, auth session
TODAY_FILE = HOME / "today.json"  # {"date": ..., "secs": {"f|Code": 123}}
DEFAULT_SKIP = "YouTube|Instagram|Reddit|Netflix|Facebook|TikTok|Twitter|X.com|Discord|WhatsApp|Snapchat"
SAMPLE_S = 5
IDLE_S = 120  # no input for this long counts as away
FLUSH_S = 60  # write to disk
PUSH_S = 300  # send to server
MAX_APPS = 50


# ---------- counting (pure, tested in test_pixelock_laptop.py) ----------

def classify(app, title, skip):
    """Returns (category, label). Distracting time is named after the match, so a browser on YouTube reads 'YouTube'."""
    hit = re.search(skip, f"{app} {title}", re.IGNORECASE) if skip else None
    return ("d", hit.group(0).title()) if hit else ("f", app)


def add_sample(secs, app, title, seconds, skip):
    cat, label = classify(app, title, skip)
    key = f"{cat}|{label}"
    secs[key] = secs.get(key, 0) + seconds


def summarize(secs):
    """Minutes per category plus the biggest apps, shaped for push_laptop_day."""
    rows = sorted(((v, k) for k, v in secs.items() if v >= 60), reverse=True)[:MAX_APPS]
    apps = [{"app": k.split("|", 1)[1][:40], "cat": k[0], "min": v // 60} for v, k in rows]
    total = lambda c: sum(v for k, v in secs.items() if k[0] == c) // 60
    return min(total("f"), 1440), min(total("d"), 1440), apps


# ---------- reading the active window, per OS ----------

def idle_and_window_windows():
    import ctypes
    from ctypes import wintypes
    user32, kernel32 = ctypes.windll.user32, ctypes.windll.kernel32

    class LASTINPUT(ctypes.Structure):
        _fields_ = [("cbSize", wintypes.UINT), ("dwTime", wintypes.DWORD)]

    info = LASTINPUT(ctypes.sizeof(LASTINPUT), 0)
    user32.GetLastInputInfo(ctypes.byref(info))
    idle = (kernel32.GetTickCount() - info.dwTime) / 1000.0

    hwnd = user32.GetForegroundWindow()
    title = ctypes.create_unicode_buffer(256)
    user32.GetWindowTextW(hwnd, title, 256)
    pid = wintypes.DWORD()
    user32.GetWindowThreadProcessId(hwnd, ctypes.byref(pid))
    handle = kernel32.OpenProcess(0x1000, False, pid.value)  # PROCESS_QUERY_LIMITED_INFORMATION
    app = ""
    if handle:
        size = wintypes.DWORD(520)
        path = ctypes.create_unicode_buffer(520)
        if kernel32.QueryFullProcessImageNameW(handle, 0, path, ctypes.byref(size)):
            app = Path(path.value).stem
        kernel32.CloseHandle(handle)
    return idle, app, title.value


def _gdbus(dest, path, method, *args):
    out = subprocess.run(["gdbus", "call", "--session", "--dest", dest, "--object-path", path,
                          "--method", method, *args], capture_output=True, text=True, timeout=5)
    if out.returncode:
        raise RuntimeError(out.stderr.strip())
    return out.stdout


def idle_and_window_linux():
    idle = int(re.search(r"\d+", _gdbus("org.gnome.Mutter.IdleMonitor", "/org/gnome/Mutter/IdleMonitor/Core",
                                        "org.gnome.Mutter.IdleMonitor.GetIdletime")).group()) / 1000.0
    # tools/gnome-extension answers with ('wm_class', 'title')
    match = re.search(r"\('(.*)', '(.*)'\)", _gdbus("dev.pixelock.Focus", "/dev/pixelock/Focus",
                                                   "dev.pixelock.Focus.Get"))
    return idle, match.group(1), match.group(2)


def sampler():
    return idle_and_window_windows if sys.platform == "win32" else idle_and_window_linux


# ---------- Supabase (plain HTTP, same flow as the phone) ----------

def load_config():
    return json.loads(CONFIG.read_text()) if CONFIG.exists() else {}


def save_config(cfg):
    HOME.mkdir(exist_ok=True)
    # Created private so the session is never readable by others, even for a moment.
    with os.fdopen(os.open(CONFIG, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600), "w") as f:
        json.dump(cfg, f)


def call(cfg, method, path, body=None, bearer=None):
    req = urllib.request.Request(cfg["url"] + path, method=method,
                                 data=json.dumps(body).encode() if body is not None else None)
    req.add_header("apikey", cfg["anon_key"])  # sb_ keys go in apikey only, never as a bearer
    req.add_header("Content-Type", "application/json")
    if bearer:
        req.add_header("Authorization", f"Bearer {bearer}")
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            text = r.read().decode()
    except urllib.error.HTTPError as e:
        raise RuntimeError(f"{e.code} {e.read().decode()[:200]}") from None
    return json.loads(text) if text.strip() else None


def store_session(cfg, reply):
    cfg["session"] = {"access": reply["access_token"], "refresh": reply["refresh_token"],
                      "expires": time.time() + reply["expires_in"]}
    save_config(cfg)  # refresh tokens rotate, so save every time
    return reply["access_token"]


def token(cfg):
    s = cfg.get("session")
    if not s:
        return store_session(cfg, call(cfg, "POST", "/auth/v1/signup", {}))
    if s["expires"] - time.time() > 60:
        return s["access"]
    return store_session(cfg, call(cfg, "POST", "/auth/v1/token?grant_type=refresh_token",
                                   {"refresh_token": s["refresh"]}))


def rpc(cfg, name, body):
    return call(cfg, "POST", f"/rest/v1/rpc/{name}", body, token(cfg))


def push(cfg, date, secs):
    focused, distracted, apps = summarize(secs)
    rpc(cfg, "push_laptop_day", {"p_date": date, "p_focused": focused, "p_distracted": distracted, "p_apps": apps})


# ---------- commands ----------

def default_keys():
    """URL and public key from the phone project's local.properties, when run from the repo."""
    props = Path(__file__).resolve().parent.parent / "local.properties"
    found = {}
    if props.exists():
        for line in props.read_text().splitlines():
            k, _, v = line.partition("=")
            found[k.strip()] = v.strip()
    return found.get("SUPABASE_URL", os.environ.get("SUPABASE_URL", "")), \
        found.get("SUPABASE_ANON_KEY", os.environ.get("SUPABASE_ANON_KEY", ""))


def cmd_link(args):
    url, key = default_keys()
    cfg = {"url": (args.url or url).rstrip("/"), "anon_key": args.anon_key or key}
    if not cfg["url"] or not cfg["anon_key"]:
        sys.exit("No Supabase URL or key. Pass --url and --anon-key, or run from the Pixelock folder.")
    rpc(cfg, "redeem_pair_code", {"p_code": args.code})
    print("Linked. Run: python pixelock_laptop.py install")


def cmd_install(_):
    script = Path(__file__).resolve()
    if sys.platform == "win32":
        pythonw = Path(sys.executable).with_name("pythonw.exe")  # no console window
        run = f'"{pythonw}" "{script}" run'
        subprocess.run(["schtasks", "/Create", "/F", "/SC", "ONLOGON", "/TN", "Pixelock", "/TR", run,
                        "/RL", "LIMITED"], check=True)
        subprocess.run(["schtasks", "/Run", "/TN", "Pixelock"], check=False)
    else:
        unit = Path.home() / ".config/systemd/user/pixelock-laptop.service"
        unit.parent.mkdir(parents=True, exist_ok=True)
        unit.write_text(f"[Unit]\nDescription=Pixelock laptop tracker\n\n[Service]\n"
                        f"ExecStart={sys.executable} {script} run\nRestart=always\nRestartSec=30\n\n"
                        f"[Install]\nWantedBy=default.target\n")
        subprocess.run(["systemctl", "--user", "daemon-reload"], check=True)
        subprocess.run(["systemctl", "--user", "enable", "--now", "pixelock-laptop.service"], check=True)
    print("Installed. It starts at every login.")


def load_today(date):
    try:
        data = json.loads(TODAY_FILE.read_text())
        if data["date"] == date:
            return data["secs"]
    except (OSError, ValueError, KeyError):
        pass
    return {}


def save_today(date, secs):
    HOME.mkdir(exist_ok=True)
    tmp = TODAY_FILE.with_suffix(".tmp")
    tmp.write_text(json.dumps({"date": date, "secs": secs}))
    tmp.replace(TODAY_FILE)  # whole file or nothing, so a crash cannot leave half a day


def cmd_run(args):
    cfg = load_config()
    if "url" not in cfg:
        sys.exit("Not linked. Run: python pixelock_laptop.py link CODE")
    read, skip = sampler(), args.skip
    date = dt.date.today().isoformat()
    secs = load_today(date)
    last_flush = last_push = last = time.monotonic()
    dirty = True
    while True:
        time.sleep(SAMPLE_S)
        now = time.monotonic()
        gap, last = now - last, now
        today = dt.date.today().isoformat()
        if today != date:  # midnight: send the finished day, then start fresh
            try:
                push(cfg, date, secs)
            except Exception as e:
                print("push failed:", e, file=sys.stderr)
            date, secs, dirty = today, {}, True
        # A gap far over the sample time means sleep or suspend. Do not count it.
        step = SAMPLE_S if gap < SAMPLE_S * 3 else 0
        try:
            idle, app, title = read()
            if idle < IDLE_S and app and step:
                add_sample(secs, app, title, step, skip)
                dirty = True
        except Exception as e:  # a locked screen or a missing extension must not kill the loop
            print("read failed:", e, file=sys.stderr)
        if dirty and now - last_flush >= FLUSH_S:
            save_today(date, secs)
            last_flush, dirty = now, False
        if now - last_push >= PUSH_S:
            try:
                push(cfg, date, secs)
            except Exception as e:  # offline is normal; the next push sends the whole day again
                print("push failed:", e, file=sys.stderr)
            last_push = now


def cmd_status(_):
    date = dt.date.today().isoformat()
    focused, distracted, apps = summarize(load_today(date))
    print(f"{date}: {focused} focused min, {distracted} distracting min")
    for a in apps:
        print(f"  {'focus' if a['cat'] == 'f' else 'distr'}  {a['min']:>4} min  {a['app']}")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="cmd", required=True)
    link = sub.add_parser("link")
    link.add_argument("code")
    link.add_argument("--url")
    link.add_argument("--anon-key")
    sub.add_parser("install")
    run = sub.add_parser("run")
    run.add_argument("--skip", default=DEFAULT_SKIP, help="regex of apps and titles that count as distracting")
    sub.add_parser("status")
    args = parser.parse_args()
    {"link": cmd_link, "install": cmd_install, "run": cmd_run, "status": cmd_status}[args.cmd](args)


if __name__ == "__main__":
    main()

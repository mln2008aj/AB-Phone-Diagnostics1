#!/usr/bin/env python3
"""
AB's Phone Solutions - PC companion (phase 1)

Receives backups from the AB's Phone Solutions Android app over:
  * USB-C cable  (uses Android's "adb reverse", fastest and most reliable)
  * Same Wi-Fi   (phone finds this PC automatically on the local network)

Every file is written to a temporary ".part" file, its size and SHA-256 are
checked, and only then is it renamed into place. The phone only offers to
remove a file from the phone after this PC has confirmed the copy.

No third-party packages are needed. Run:  python abps_pc.py
"""

import hashlib
import json
import os
import platform
import secrets
import shutil
import socket
import subprocess
import sys
import threading
import time
import webbrowser
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, unquote, urlparse

VERSION = "1.0.0"
PHONE_PORT = 8765      # phone <-> PC data port (LAN and USB)
UI_PORT = 8766         # local control page, only reachable from this PC
DISCOVERY_PORT = 8767  # UDP: phone asks "who is there?"
CHUNK = 1024 * 1024
MAX_WRONG_CODES = 5

CONFIG_DIR = os.path.join(os.path.expanduser("~"), ".abps_pc")
CONFIG_FILE = os.path.join(CONFIG_DIR, "config.json")


def default_root():
    if platform.system() == "Windows":
        for d in ("D:\\", "E:\\"):
            if os.path.isdir(d):
                return os.path.join(d, "Phone Backup")
    return os.path.join(os.path.expanduser("~"), "Phone Backup")


# --------------------------------------------------------------------------
# State
# --------------------------------------------------------------------------
class State:
    def __init__(self):
        self.lock = threading.Lock()
        self.cfg = {"backup_root": default_root(), "devices": {}, "pc_name": socket.gethostname()}
        self.load()
        self.code = self.new_code()
        self.wrong = 0
        self.locked_until = 0.0
        self.log = []            # recent events for the control page
        self.active = {}         # device -> {"files": n, "bytes": n, "started": t, "total_files", "total_bytes"}
        self.usb = {"adb": None, "devices": [], "message": "Looking for adb..."}

    def load(self):
        try:
            with open(CONFIG_FILE, "r", encoding="utf-8") as f:
                self.cfg.update(json.load(f))
        except FileNotFoundError:
            pass
        except Exception as e:  # corrupt config: keep defaults, never crash
            print("Could not read config:", e)

    def save(self):
        os.makedirs(CONFIG_DIR, exist_ok=True)
        tmp = CONFIG_FILE + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(self.cfg, f, indent=2)
        os.replace(tmp, CONFIG_FILE)

    @staticmethod
    def new_code():
        return "%06d" % secrets.randbelow(1000000)

    def add_log(self, text):
        line = time.strftime("%H:%M:%S ") + text
        print(line, flush=True)
        with self.lock:
            self.log.append(line)
            del self.log[:-200]

    def device_for(self, token):
        return self.cfg["devices"].get(token)


STATE = State()


# --------------------------------------------------------------------------
# Helpers
# --------------------------------------------------------------------------
BAD_CHARS = '<>:"|?*' + "".join(chr(i) for i in range(32))


def clean_part(part):
    part = "".join("_" if c in BAD_CHARS else c for c in part).strip().rstrip(".")
    return part


def safe_dest(device, rel):
    """Map a phone-relative path to a path inside the backup folder, or None."""
    parts = [p for p in rel.replace("\\", "/").split("/") if p not in ("", ".")]
    if not parts or any(p == ".." for p in parts):
        return None
    parts = [clean_part(p) for p in parts]
    if any(not p for p in parts):
        return None
    root = os.path.abspath(STATE.cfg["backup_root"])
    dest = os.path.abspath(os.path.join(root, clean_part(device) or "Phone", *parts))
    if not dest.startswith(root + os.sep):
        return None
    return dest


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        while True:
            b = f.read(CHUNK)
            if not b:
                break
            h.update(b)
    return h.hexdigest()


def local_ips():
    ips = set()
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("10.255.255.255", 1))
        ips.add(s.getsockname()[0])
        s.close()
    except OSError:
        pass
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ips.add(info[4][0])
    except OSError:
        pass
    return sorted(ip for ip in ips if not ip.startswith("127."))


# --------------------------------------------------------------------------
# Phone API  (port 8765)
# --------------------------------------------------------------------------
class PhoneHandler(BaseHTTPRequestHandler):
    server_version = "ABPS-PC/" + VERSION
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):  # keep the console readable
        pass

    # -- small helpers
    def send_json(self, code, obj):
        body = json.dumps(obj).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def read_json(self, limit=20 * 1024 * 1024):
        n = int(self.headers.get("Content-Length") or 0)
        if n > limit:
            raise ValueError("request too large")
        return json.loads(self.rfile.read(n) or b"{}")

    def device(self):
        auth = self.headers.get("Authorization", "")
        if auth.startswith("Bearer "):
            return STATE.device_for(auth[7:].strip())
        return None

    # -- routes
    def do_GET(self):
        url = urlparse(self.path)
        if url.path == "/api/info":
            return self.send_json(200, {"app": "ABPS-PC", "version": VERSION,
                                        "pcName": STATE.cfg["pc_name"], "os": platform.system()})
        dev = self.device()
        if not dev:
            return self.send_json(401, {"error": "not paired"})
        if url.path == "/api/verify":
            q = parse_qs(url.query)
            dest = safe_dest(dev, unquote(q.get("path", [""])[0]))
            if not dest or not os.path.isfile(dest):
                return self.send_json(200, {"exists": False})
            return self.send_json(200, {"exists": True, "size": os.path.getsize(dest), "sha256": sha256_file(dest)})
        return self.send_json(404, {"error": "unknown"})

    def do_POST(self):
        url = urlparse(self.path)
        try:
            data = self.read_json()
        except Exception:
            return self.send_json(400, {"error": "bad request"})

        if url.path == "/api/pair":
            now = time.time()
            if now < STATE.locked_until:
                return self.send_json(429, {"error": "Too many wrong codes. Wait a minute and try again."})
            if str(data.get("code", "")).strip() != STATE.code:
                STATE.wrong += 1
                if STATE.wrong >= MAX_WRONG_CODES:
                    STATE.locked_until = now + 60
                    STATE.wrong = 0
                    STATE.code = STATE.new_code()
                    STATE.add_log("Too many wrong pairing codes. Pairing paused for 1 minute; new code issued.")
                return self.send_json(403, {"error": "Wrong code"})
            device = clean_part(str(data.get("device", "Phone")))[:60] or "Phone"
            token = secrets.token_urlsafe(32)
            with STATE.lock:
                STATE.cfg["devices"][token] = device
                STATE.save()
                STATE.code = STATE.new_code()
                STATE.wrong = 0
            STATE.add_log("Paired with %s" % device)
            return self.send_json(200, {"token": token, "pcName": STATE.cfg["pc_name"]})

        dev = self.device()
        if not dev:
            return self.send_json(401, {"error": "not paired"})

        if url.path == "/api/check":
            need = []
            for i, f in enumerate(data.get("files", [])):
                dest = safe_dest(dev, str(f.get("path", "")))
                if not dest:
                    continue
                try:
                    st = os.stat(dest)
                    same = st.st_size == int(f.get("size", -1)) and abs(st.st_mtime * 1000 - int(f.get("mtime", 0))) < 2000
                except FileNotFoundError:
                    same = False
                if not same:
                    need.append(i)
            return self.send_json(200, {"need": need})

        if url.path == "/api/session":
            ev = data.get("event")
            if ev == "start":
                STATE.active[dev] = {"files": 0, "bytes": 0, "started": time.time(),
                                     "total_files": int(data.get("files", 0)), "total_bytes": int(data.get("bytes", 0)),
                                     "mode": data.get("mode", "copy")}
                STATE.add_log("%s started a %s of %d files (%.1f GB)" % (
                    dev, data.get("mode", "copy"), int(data.get("files", 0)), int(data.get("bytes", 0)) / 1e9))
            elif ev == "end":
                a = STATE.active.pop(dev, None)
                if a:
                    secs = max(0.1, time.time() - a["started"])
                    STATE.add_log("%s finished: %d files, %.1f GB in %d s (%.0f MB/s)" % (
                        dev, a["files"], a["bytes"] / 1e9, secs, a["bytes"] / secs / 1e6))
            return self.send_json(200, {"ok": True})

        return self.send_json(404, {"error": "unknown"})

    def do_PUT(self):
        url = urlparse(self.path)
        if url.path != "/api/upload":
            return self.send_json(404, {"error": "unknown"})
        dev = self.device()
        if not dev:
            return self.send_json(401, {"error": "not paired"})
        q = parse_qs(url.query)
        rel = unquote(q.get("path", [""])[0])
        dest = safe_dest(dev, rel)
        length = int(self.headers.get("Content-Length") or -1)
        if not dest or length < 0:
            return self.send_json(400, {"error": "bad path or length"})
        mtime_ms = int(q.get("mtime", ["0"])[0] or 0)

        os.makedirs(os.path.dirname(dest), exist_ok=True)
        part = dest + ".abps-part"
        h = hashlib.sha256()
        got = 0
        try:
            with open(part, "wb") as out:
                remaining = length
                while remaining > 0:
                    b = self.rfile.read(min(CHUNK, remaining))
                    if not b:
                        break
                    out.write(b)
                    h.update(b)
                    got += len(b)
                    remaining -= len(b)
                    a = STATE.active.get(dev)
                    if a:
                        a["bytes"] += len(b)
                out.flush()
                os.fsync(out.fileno())
        except (OSError, ConnectionError) as e:
            try:
                os.remove(part)
            except OSError:
                pass
            if isinstance(e, OSError) and getattr(e, "errno", None) == 28:
                STATE.add_log("PC disk is full. Transfer stopped.")
                return self.send_json(507, {"error": "PC disk is full"})
            return

        if got != length:
            try:
                os.remove(part)
            except OSError:
                pass
            return self.send_json(400, {"error": "incomplete file", "got": got})

        os.replace(part, dest)
        if mtime_ms > 0:
            try:
                os.utime(dest, (mtime_ms / 1000, mtime_ms / 1000))
            except OSError:
                pass
        a = STATE.active.get(dev)
        if a:
            a["files"] += 1
        return self.send_json(200, {"ok": True, "size": got, "sha256": h.hexdigest()})


# --------------------------------------------------------------------------
# USB: keep "adb reverse" set up for every connected phone
# --------------------------------------------------------------------------
def find_adb():
    here = os.path.dirname(os.path.abspath(sys.executable if getattr(sys, "frozen", False) else __file__))
    names = ["adb.exe", "adb"] if platform.system() == "Windows" else ["adb"]
    candidates = []
    for n in names:
        candidates += [os.path.join(here, n), os.path.join(here, "platform-tools", n)]
    local = os.environ.get("LOCALAPPDATA")
    if local:
        candidates.append(os.path.join(local, "Android", "Sdk", "platform-tools", "adb.exe"))
    for c in candidates:
        if os.path.isfile(c):
            return c
    return shutil.which("adb")


def run(cmd, timeout=8):
    flags = 0x08000000 if platform.system() == "Windows" else 0  # CREATE_NO_WINDOW
    r = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout, creationflags=flags)
    return r.stdout + r.stderr


def usb_loop():
    announced = set()
    while True:
        try:
            adb = find_adb()
            if not adb:
                STATE.usb = {"adb": None, "devices": [],
                             "message": "USB needs Google's free 'platform-tools'. Put adb.exe next to this program (see the guide)."}
            else:
                out = run([adb, "devices"])
                devs = []
                for line in out.splitlines()[1:]:
                    parts = line.split()
                    if len(parts) >= 2:
                        devs.append({"serial": parts[0], "state": parts[1]})
                msg = "No phone on USB. Connect the cable and turn on USB debugging." if not devs else ""
                for d in devs:
                    if d["state"] == "device":
                        if f"tcp:{PHONE_PORT}" not in run([adb, "-s", d["serial"], "reverse", "--list"]):
                            run([adb, "-s", d["serial"], "reverse", f"tcp:{PHONE_PORT}", f"tcp:{PHONE_PORT}"])
                        d["ready"] = True
                        if d["serial"] not in announced:
                            announced.add(d["serial"])
                            STATE.add_log("USB link ready for phone %s" % d["serial"])
                    elif d["state"] == "unauthorized":
                        msg = "Tap 'Allow USB debugging' on the phone."
                STATE.usb = {"adb": adb, "devices": devs, "message": msg}
        except Exception as e:
            STATE.usb = {"adb": None, "devices": [], "message": "USB check failed: %s" % e}
        time.sleep(3)


# --------------------------------------------------------------------------
# Wi-Fi discovery (UDP)
# --------------------------------------------------------------------------
def discovery_loop():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    try:
        s.bind(("0.0.0.0", DISCOVERY_PORT))
    except OSError as e:
        STATE.add_log("Wi-Fi discovery unavailable (%s). Phones can still type this PC's address." % e)
        return
    reply = lambda: json.dumps({"app": "ABPS-PC", "pcName": STATE.cfg["pc_name"], "port": PHONE_PORT}).encode()
    while True:
        try:
            data, addr = s.recvfrom(512)
            if data.strip() == b"ABPS_DISCOVER":
                s.sendto(reply(), addr)
        except OSError:
            time.sleep(1)


# --------------------------------------------------------------------------
# Control page (port 8766, this PC only)
# --------------------------------------------------------------------------
PAGE = """<!doctype html><html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>AB's Phone Solutions - PC</title>
<style>
:root{--bg:#f4f1ee;--fg:#1a1918;--muted:#5c5752;--accent:#c42a10;--card:#fff;--line:#d8d2cc}
@media (prefers-color-scheme:dark){:root{--bg:#161514;--fg:#f1ece7;--muted:#b3aca5;--accent:#ff6a4d;--card:#211f1d;--line:#3a3633}}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--fg);font:15px/1.5 system-ui,Segoe UI,sans-serif}
main{max-width:860px;margin:0 auto;padding:24px 16px 48px}
h1{font-size:28px;margin:0}.brand{display:flex;gap:10px;align-items:center;margin-bottom:20px}
.mark{background:var(--accent);color:#fff;font-weight:800;width:34px;height:34px;display:grid;place-items:center}
.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(260px,1fr));gap:12px}
.card{background:var(--card);border:1px solid var(--line);padding:16px}
.label{font-size:11px;letter-spacing:.1em;text-transform:uppercase;color:var(--muted)}
.code{font-size:44px;font-weight:800;letter-spacing:.08em}
button{font:inherit;font-weight:700;border:1px solid var(--fg);background:transparent;color:var(--fg);padding:8px 14px;cursor:pointer}
button.primary{background:var(--accent);border-color:var(--accent);color:#fff}
input{font:inherit;width:100%;padding:8px;border:1px solid var(--line);background:var(--bg);color:var(--fg)}
pre{white-space:pre-wrap;font:13px/1.5 ui-monospace,Consolas,monospace;margin:0;max-height:320px;overflow:auto}
.bar{height:6px;background:var(--line)}.bar>div{height:6px;background:var(--accent);width:0}
.row{display:flex;gap:8px;align-items:center;flex-wrap:wrap}
</style></head><body><main>
<div class="brand"><div class="mark">AB</div><div><h1>AB's Phone Solutions</h1><div class="label">PC companion</div></div></div>
<div class="grid">
 <div class="card"><div class="label">Pairing code</div><div class="code" id="code">------</div>
  <p>In the phone app open <b>Backup</b> &rsaquo; <b>Connect to PC</b> and enter this code.</p>
  <button onclick="post('/ui/newcode')">New code</button></div>
 <div class="card"><div class="label">USB-C cable (fastest)</div><p id="usb">...</p>
  <div class="label">Same Wi-Fi</div><p>This PC: <b id="ips">...</b> &middot; port 8765. The phone finds it by itself. Allow the program if Windows Firewall asks (private networks).</p></div>
</div>
<div class="card" style="margin-top:12px"><div class="label">Backups are saved in</div>
 <div class="row" style="margin-top:6px"><input id="root"><button onclick="saveRoot()">Change</button><button onclick="post('/ui/open')">Open folder</button></div>
 <p class="label" style="margin-top:10px">Each phone gets its own folder. Files already on the PC are skipped.</p></div>
<div class="card" style="margin-top:12px"><div class="label">Transfer</div><p id="tx">Nothing is being sent right now.</p><div class="bar"><div id="bar"></div></div></div>
<div class="card" style="margin-top:12px"><div class="label">Paired phones</div><div id="devs"></div></div>
<div class="card" style="margin-top:12px"><div class="label">Activity</div><pre id="log"></pre></div>
</main><script>
async function post(u,b){await fetch(u,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(b||{})});tick()}
function saveRoot(){post('/ui/root',{path:document.getElementById('root').value})}
let rootSet=false;
async function tick(){try{const s=await (await fetch('/ui/status')).json();
 code.textContent=s.code.slice(0,3)+' '+s.code.slice(3);ips.textContent=s.ips.join(', ')||'not on a network';
 if(!rootSet||document.activeElement!==root){root.value=s.root;rootSet=true}
 usb.textContent=s.usb.devices.filter(d=>d.ready).length?'Phone connected by USB. In the app choose USB cable.':(s.usb.message||'');
 log.textContent=s.log.slice().reverse().join('\\n');
 devs.innerHTML=s.devices.length?s.devices.map(d=>'<div class="row" style="justify-content:space-between;padding:6px 0"><span>'+d.name.replace(/[<&]/g,'')+'</span><button onclick="post(\\'/ui/forget\\',{id:\\''+d.id+'\\'})">Forget</button></div>').join(''):'<p>No phones paired yet.</p>';
 const a=s.active[0];if(a){const pct=a.total_bytes?Math.min(100,a.bytes/a.total_bytes*100):0;
  tx.textContent=a.device+': '+a.files+' of '+a.total_files+' files, '+(a.bytes/1e9).toFixed(2)+' of '+(a.total_bytes/1e9).toFixed(2)+' GB at '+a.rate.toFixed(0)+' MB/s';bar.style.width=pct+'%'}
 else{tx.textContent='Nothing is being sent right now.';bar.style.width='0'}}catch(e){}}
tick();setInterval(tick,1000);
</script></body></html>"""


class UiHandler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        pass

    def send_json(self, obj, code=200):
        body = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def local_only(self):
        host = (self.headers.get("Host") or "").split(":")[0]
        return host in ("127.0.0.1", "localhost")

    def do_GET(self):
        if not self.local_only():
            return self.send_json({"error": "forbidden"}, 403)
        if self.path == "/":
            body = PAGE.encode()
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            return self.wfile.write(body)
        if self.path == "/ui/status":
            active = []
            for dev, a in list(STATE.active.items()):
                secs = max(0.5, time.time() - a["started"])
                active.append({"device": dev, "files": a["files"], "bytes": a["bytes"], "total_files": a["total_files"],
                               "total_bytes": a["total_bytes"], "rate": a["bytes"] / secs / 1e6})
            devices = [{"id": hashlib.sha256(t.encode()).hexdigest()[:12], "name": n} for t, n in STATE.cfg["devices"].items()]
            return self.send_json({"code": STATE.code, "ips": local_ips(), "root": STATE.cfg["backup_root"],
                                   "usb": STATE.usb, "log": STATE.log[-60:], "active": active, "devices": devices})
        self.send_json({"error": "unknown"}, 404)

    def do_POST(self):
        if not self.local_only() or self.headers.get("Content-Type", "").split(";")[0] != "application/json":
            return self.send_json({"error": "forbidden"}, 403)
        n = int(self.headers.get("Content-Length") or 0)
        data = json.loads(self.rfile.read(n) or b"{}")
        if self.path == "/ui/newcode":
            STATE.code = STATE.new_code()
        elif self.path == "/ui/root":
            p = os.path.abspath(os.path.expanduser(str(data.get("path", "")).strip()))
            try:
                os.makedirs(p, exist_ok=True)
                STATE.cfg["backup_root"] = p
                STATE.save()
                STATE.add_log("Backup folder set to %s" % p)
            except OSError as e:
                STATE.add_log("Could not use that folder: %s" % e)
        elif self.path == "/ui/open":
            p = STATE.cfg["backup_root"]
            os.makedirs(p, exist_ok=True)
            if platform.system() == "Windows":
                os.startfile(p)  # noqa
            elif platform.system() == "Darwin":
                subprocess.Popen(["open", p])
            else:
                subprocess.Popen(["xdg-open", p])
        elif self.path == "/ui/forget":
            for t in list(STATE.cfg["devices"]):
                if hashlib.sha256(t.encode()).hexdigest()[:12] == data.get("id"):
                    STATE.add_log("Forgot %s. It must pair again to send files." % STATE.cfg["devices"].pop(t))
            STATE.save()
        self.send_json({"ok": True})


class FastServer(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True

    def handle_error(self, request, client_address):
        # A phone that walks out of Wi-Fi range mid-file is normal: the .part file
        # is already removed and the phone retries. Don't print a traceback.
        exc = sys.exc_info()[1]
        if isinstance(exc, (ConnectionError, TimeoutError)):
            return
        super().handle_error(request, client_address)

    def server_bind(self):
        try:
            self.socket.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 4 * 1024 * 1024)
        except OSError:
            pass
        super().server_bind()


def main():
    no_browser = "--no-browser" in sys.argv
    try:
        phone = FastServer(("0.0.0.0", PHONE_PORT), PhoneHandler)
        ui = FastServer(("127.0.0.1", UI_PORT), UiHandler)
    except OSError as e:
        print("Could not start: %s\nIs AB's Phone Solutions PC already running?" % e)
        input("Press Enter to close.") if not no_browser else None
        return 1
    for target in (phone.serve_forever, ui.serve_forever, usb_loop, discovery_loop):
        threading.Thread(target=target, daemon=True).start()
    STATE.add_log("AB's Phone Solutions PC %s is running. Backups go to %s" % (VERSION, STATE.cfg["backup_root"]))
    STATE.add_log("Control page: http://127.0.0.1:%d  (keep this window open while backing up)" % UI_PORT)
    if not no_browser:
        webbrowser.open("http://127.0.0.1:%d" % UI_PORT)
    try:
        while True:
            time.sleep(3600)
    except KeyboardInterrupt:
        return 0


if __name__ == "__main__":
    sys.exit(main())

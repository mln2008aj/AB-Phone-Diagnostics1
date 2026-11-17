"""Simulates the phone app against the PC companion. Run: python test_pc.py"""
import hashlib, http.client, json, os, socket, subprocess, sys, tempfile, time
from urllib.parse import quote

HERE = os.path.dirname(os.path.abspath(__file__))
home = tempfile.mkdtemp()
root = os.path.join(home, "backup")
os.makedirs(os.path.join(home, ".abps_pc"))
json.dump({"backup_root": root}, open(os.path.join(home, ".abps_pc", "config.json"), "w"))
env = dict(os.environ, HOME=home, USERPROFILE=home)
proc = subprocess.Popen([sys.executable, os.path.join(HERE, "abps_pc.py"), "--no-browser"], env=env,
                        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
time.sleep(1.5)
ok = True


def check(name, cond):
    global ok
    print(("PASS " if cond else "FAIL ") + name)
    ok = ok and cond


def req(method, path, body=None, token=None, port=8765, headers=None, raw=None):
    c = http.client.HTTPConnection("127.0.0.1", port, timeout=30)
    h = dict(headers or {})
    if token:
        h["Authorization"] = "Bearer " + token
    if body is not None:
        raw = json.dumps(body).encode()
        h["Content-Type"] = "application/json"
    c.request(method, path, body=raw, headers=h)
    r = c.getresponse()
    data = r.read()
    try:
        return r.status, json.loads(data)
    except Exception:
        return r.status, data


try:
    s, info = req("GET", "/api/info")
    check("info", s == 200 and info["app"] == "ABPS-PC")

    u = socket.socket(socket.AF_INET, socket.SOCK_DGRAM); u.settimeout(3)
    u.sendto(b"ABPS_DISCOVER", ("127.0.0.1", 8767))
    d, _ = u.recvfrom(512)
    check("wifi discovery reply", json.loads(d)["port"] == 8765)

    s, _ = req("POST", "/api/pair", {"code": "000000x", "device": "Pixel 7"})
    check("wrong code refused", s == 403)
    s, st = req("GET", "/ui/status", port=8766)
    check("control page status", s == 200 and len(st["code"]) == 6)
    s, forbidden = req("GET", "/ui/status", port=8766, headers={"Host": "evil.example"})
    check("control page refuses other hosts", s == 403)
    s, p = req("POST", "/api/pair", {"code": st["code"], "device": "Pixel 7"})
    check("pairing", s == 200 and p.get("token"))
    tok = p["token"]
    s, _ = req("POST", "/api/pair", {"code": st["code"], "device": "Other"})
    check("code cannot be reused", s == 403)

    s, _ = req("POST", "/api/check", {"files": []})
    check("unpaired phone refused", s == 401)

    files = {"Documents/Download/letter.pdf": os.urandom(200_000),
             "Photos/DCIM/Camera/IMG_1.jpg": os.urandom(80 * 1024 * 1024)}
    listing = [{"path": k, "size": len(v), "mtime": 1700000000000} for k, v in files.items()]
    s, r = req("POST", "/api/check", {"files": listing}, token=tok)
    check("all files needed first time", r["need"] == [0, 1])
    req("POST", "/api/session", {"event": "start", "files": 2, "bytes": sum(map(len, files.values())), "mode": "copy"}, token=tok)
    t0 = time.time()
    for k, v in files.items():
        s, r = req("PUT", "/api/upload?path=%s&mtime=1700000000000" % quote(k), token=tok, raw=v,
                   headers={"Content-Type": "application/octet-stream"})
        check("upload " + k, s == 200 and r["sha256"] == hashlib.sha256(v).hexdigest())
    secs = time.time() - t0
    req("POST", "/api/session", {"event": "end"}, token=tok)
    print("     local speed %.0f MB/s" % (sum(map(len, files.values())) / secs / 1e6))
    dest = os.path.join(root, "Pixel 7", "Photos", "DCIM", "Camera", "IMG_1.jpg")
    check("file saved with right content", open(dest, "rb").read() == files["Photos/DCIM/Camera/IMG_1.jpg"])
    check("no leftover .part files", not any(f.endswith(".abps-part") for _, _, fs in os.walk(root) for f in fs))
    s, r = req("POST", "/api/check", {"files": listing}, token=tok)
    check("files already on PC are skipped", r["need"] == [])
    s, r = req("GET", "/api/verify?path=" + quote("Documents/Download/letter.pdf"), token=tok)
    check("verify returns PC hash", r["sha256"] == hashlib.sha256(files["Documents/Download/letter.pdf"]).hexdigest())

    s, r = req("PUT", "/api/upload?path=" + quote("../../evil.txt"), token=tok, raw=b"x")
    check("path escape refused", s == 400 and not os.path.exists(os.path.join(home, "evil.txt")))
    c = http.client.HTTPConnection("127.0.0.1", 8765, timeout=10)
    c.putrequest("PUT", "/api/upload?path=" + quote("Documents/cut.bin"))
    c.putheader("Authorization", "Bearer " + tok); c.putheader("Content-Length", "1000"); c.endheaders()
    c.send(b"x" * 10); c.close(); time.sleep(0.5)
    check("cut-off upload leaves nothing behind", not os.path.exists(os.path.join(root, "Pixel 7", "Documents", "cut.bin")))
finally:
    proc.terminate()
    out = proc.communicate(timeout=5)[0]
print("\nPC log:\n" + out)
print("ALL PASSED" if ok else "SOME FAILED")
sys.exit(0 if ok else 1)

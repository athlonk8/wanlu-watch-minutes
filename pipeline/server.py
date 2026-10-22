# -*- coding: utf-8 -*-
"""腕录控制面板后端 — 标准库实现,无额外依赖(requests 除外,供流水线用)
启动: python server.py  →  http://127.0.0.1:8733
"""
import base64
import hashlib
import hmac
import json
import os
import queue
import subprocess
import threading
import time
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import unquote, urlparse

import requests  # noqa: E402

import minutes  # 同目录流水线模块

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
INBOX = ROOT / "inbox"
OUT = ROOT / "out"
PORT = 8733
WATCH_DIR = "/sdcard/Android/data/com.zcode.watchrec/files"
PHONE_DIR = "/sdcard/Download/腕录纪要"

ADB = str(Path(os.environ.get("LOCALAPPDATA", "")) / "Android" / "Sdk" / "platform-tools" / "adb.exe")
if not Path(ADB).exists():
    ADB = "adb"


def run_adb(args, timeout=20):
    """跑 adb,返回 (ok, stdout+stderr)。args 不含 adb 本身。"""
    try:
        p = subprocess.run(
            [ADB] + args, capture_output=True, text=True,
            encoding="utf-8", errors="replace", timeout=timeout,
        )
        return p.returncode == 0, (p.stdout or "") + (p.stderr or "")
    except Exception as e:
        return False, str(e)


# ---------------------------------------------------------------- 任务队列

JOBS = {}           # id -> dict(name,status,log[],out_dir)
JOB_SEQ = [0]
QUEUE: "queue.Queue[str]" = queue.Queue()


def worker():
    while True:
        jid = QUEUE.get()
        job = JOBS.get(jid)
        if not job:
            continue

        def log(msg, jid=jid):
            job = JOBS.get(jid)
            if job is not None:
                job["log"].append(str(msg))
                job["log"] = job["log"][-50:]
                print(f"[{job['name']}] {msg}", flush=True)

        job["status"] = "running"
        try:
            cfg = minutes.load_config()
            if not cfg["api_key"]:
                raise minutes.MinutesError("未配置百炼 API Key,请先在「百炼设置」里填写")
            out_dir = minutes.process(Path(job["path"]), cfg, OUT, progress=log)
            job["status"] = "done"
            job["out"] = out_dir.name
            log("全部完成 ✔")
        except minutes.MinutesError as e:
            job["status"] = "error"
            log(f"失败: {e}")
        except Exception as e:
            job["status"] = "error"
            log(f"异常: {e}")


threading.Thread(target=worker, daemon=True).start()

# ---------------------------------------------------------------- 手表查询

_watch_cache = {"t": 0, "data": None}


def watch_state(cfg):
    now = time.time()
    if _watch_cache["t"] > now - 2 and _watch_cache["data"]:
        return _watch_cache["data"]

    serial = cfg.get("watch_serial", "")
    devices = []
    ok, out = run_adb(["devices"])
    for line in (out or "").splitlines()[1:]:
        line = line.strip()
        if not line or "*" in line:
            continue
        parts = line.split()
        if len(parts) >= 2 and parts[1] == "device":
            devices.append(parts[0])

    st = {"serial": serial or (devices[0] if devices else ""), "devices": devices,
          "connected": False, "app_installed": False, "recording": False, "files": [], "error": ""}
    if st["serial"] and st["serial"] in devices:
        st["connected"] = True
        ok, out = run_adb(["-s", st["serial"], "shell", "pm", "list", "packages", "com.zcode.watchrec"])
        st["app_installed"] = "com.zcode.watchrec" in (out or "")
        if st["app_installed"]:
            ok, out = run_adb(["-s", st["serial"], "shell", "dumpsys", "power"])
            # 只看“Wake Locks: size=…”活动段(到 Suspend Blockers 为止),历史日志里也有 watchrec:mic 会误判
            live = ""
            if out and "Wake Locks: size" in out:
                live = out.split("Wake Locks: size", 1)[1].split("Suspend Blockers", 1)[0]
            st["recording"] = "watchrec:mic" in live
            ok, out = run_adb(["-s", st["serial"], "shell", "ls", "-l", WATCH_DIR], timeout=15)
            files = []
            for line in (out or "").splitlines():
                tokens = line.split()
                if len(tokens) >= 8 and tokens[-1].startswith("REC_") and tokens[-1].endswith(".m4a"):
                    size = next((int(t) for t in tokens[4:7] if t.isdigit()), 0)
                    files.append({"name": tokens[-1], "size": size})
            st["files"] = sorted(files, key=lambda f: f["name"], reverse=True)
    _watch_cache["t"], _watch_cache["data"] = now, st
    return st


def list_inbox():
    INBOX.mkdir(exist_ok=True)
    out = []
    for p in sorted(INBOX.iterdir(), key=lambda x: x.stat().st_mtime, reverse=True):
        if p.is_file() and p.suffix.lower() in minutes.AUDIO_EXT:
            out.append({"name": p.name, "size": p.stat().st_size})
    return out


def list_results():
    OUT.mkdir(exist_ok=True)
    out = []
    for d in sorted(OUT.iterdir(), key=lambda x: x.stat().st_mtime, reverse=True):
        if d.is_dir() and (d / "转写.md").exists():
            out.append({"name": d.name, "has_minutes": (d / "会议纪要.md").exists()})
    return out


def cfg_public(cfg):
    key = cfg.get("api_key", "")
    mkey = cfg.get("minutes_key", "")
    return {
        "api_key_set": bool(key),
        "api_key_mask": (key[:3] + "***" + key[-4:]) if len(key) > 8 else ("***" if key else ""),
        "minutes_key_separate": bool(mkey) and mkey != key,
        "asr_model": cfg.get("asr_model"),
        "language_hints": cfg.get("language_hints"),
        "diarization": bool(cfg.get("diarization")),
        "minutes_enabled": bool(cfg.get("minutes_enabled")),
        "minutes_base": cfg.get("minutes_base"),
        "minutes_model": cfg.get("minutes_model"),
        "watch_serial": cfg.get("watch_serial", ""),
        "watch_addr": cfg.get("watch_addr", ""),
        "cloud_provider": cfg.get("cloud_provider", ""),
        "cloud_webhook": cfg.get("cloud_webhook", ""),
        "cloud_secret_set": bool(cfg.get("cloud_secret", "")),
        "phone_serial": cfg.get("phone_serial", ""),
        "phone_share": bool(cfg.get("phone_share")),
    }


# ---------------------------------------------------------------- 纪要分发

def result_md_path(name: str):
    """优先取会议纪要.md,无则转写.md"""
    d = OUT / name
    for f in ("会议纪要.md", "转写.md"):
        p = d / f
        if p.exists():
            return p
    return None


def adb_devices():
    _, out = run_adb(["devices"])
    devs = []
    for line in (out or "").splitlines()[1:]:
        parts = line.strip().split()
        if len(parts) >= 2 and parts[1] == "device":
            devs.append(parts[0])
    return devs


def phone_serial(cfg):
    cands = [d for d in adb_devices() if d != cfg.get("watch_serial", "")]
    ps = cfg.get("phone_serial", "")
    if ps in cands:
        return ps
    return cands[0] if cands else ""


def send_to_phone(name: str, cfg: dict, share: bool):
    p = result_md_path(name)
    if not p:
        return False, "该结果没有可发送的内容"
    dev = phone_serial(cfg)
    if not dev:
        return False, "未检测到手机(USB 连接并打开调试)"
    ok, out = run_adb(["-s", dev, "shell", "mkdir", "-p", PHONE_DIR])
    ok, out = run_adb(["-s", dev, "push", str(p), f"{PHONE_DIR}/{name}.md"], timeout=120)
    if not ok:
        return False, f"推送失败: {out.strip()[:200]}"
    if share:
        text = p.read_text(encoding="utf-8")[:6000]
        run_adb(["-s", dev, "shell", "am", "start", "-a", "android.intent.action.SEND",
                 "-t", "text/plain", "--es", "android.intent.extra.TEXT", text])
    return True, f"已发送到手机 {dev} → Download/腕录纪要/{name}.md"


def send_to_cloud(name: str, cfg: dict):
    provider, url, secret = cfg.get("cloud_provider", ""), cfg.get("cloud_webhook", ""), cfg.get("cloud_secret", "")
    if not provider or not url:
        return False, "先在「纪要分发」里选择服务商并填 Webhook"
    p = result_md_path(name)
    content = p.read_text(encoding="utf-8") if p else "(无内容)"
    ts = str(int(time.time() * 1000))
    try:
        if provider == "dingtalk":
            if secret:
                sign = urllib.parse.quote_plus(base64.b64encode(
                    hmac.new(secret.encode(), f"{ts}\n{secret}".encode(), hashlib.sha256).digest()))
                url += f"&timestamp={ts}&sign={sign}"
            payload = {"msgtype": "markdown", "markdown": {"title": name, "text": content}}
        elif provider == "feishu":
            payload = {"msg_type": "text", "content": {"text": f"【{name}】\n{content}"}}
            if secret:
                payload["timestamp"] = ts
                payload["sign"] = hmac.new(secret.encode(), f"{ts}\n{secret}".encode(), hashlib.sha256).hexdigest()
        else:
            payload = {"title": name, "content": content, "source": "watch_minutes"}
        r = requests.post(url, json=payload, timeout=20)
        j = {}
        try:
            j = r.json()
        except Exception:
            pass
        ok = r.status_code == 200 and j.get("errcode", j.get("code", 0)) in (0, "0", None)
        return ok, f"HTTP {r.status_code} {str(j)[:200] if j else r.text[:200]}"
    except Exception as e:
        return False, str(e)[:200]


# ---------------------------------------------------------------- HTTP

class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *a):
        pass

    def _json(self, obj, code=200):
        body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _body(self):
        n = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(n) or b"{}"
        try:
            return json.loads(raw)
        except UnicodeDecodeError:
            return json.loads(raw.decode("gbk", "replace"))

    # ---------- GET ----------
    def do_GET(self):
        path = urlparse(self.path).path
        if path in ("/", "/index.html"):
            html = (HERE / "static" / "index.html").read_bytes()
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(html)))
            self.end_headers()
            self.wfile.write(html)
            return
        if path == "/api/state":
            cfg = minutes.load_config()
            devs = adb_devices()
            self._json({
                "watch": watch_state(cfg),
                "inbox": list_inbox(),
                "results": list_results(),
                "jobs": [{"id": j["id"], "name": j["name"], "status": j["status"],
                          "log": j["log"][-6:]} for j in JOBS.values()],
                "config": cfg_public(cfg),
                "phone": {"serial": phone_serial(cfg), "connected": bool(phone_serial(cfg))},
                "devices": devs,
            })
            return
        if path.startswith("/api/download/"):
            name = unquote(path.split("/", 3)[3])
            p = result_md_path(name)
            if not p:
                self._json({"error": "not found"}, 404)
                return
            body = p.read_bytes()
            self.send_response(200)
            self.send_header("Content-Type", "text/markdown; charset=utf-8")
            self.send_header("Content-Disposition",
                             f"attachment; filename*=UTF-8''{urllib.parse.quote(name + '.md')}")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        if path.startswith("/api/result/"):
            name = unquote(path.split("/", 3)[3])
            d = OUT / name
            if not d.is_dir() or ".." in name:
                self._json({"error": "not found"}, 404)
                return
            data = {}
            for f, key in [("转写.md", "transcript"), ("会议纪要.md", "minutes"), ("raw.json", "raw")]:
                p = d / f
                if p.exists():
                    data[key] = p.read_text(encoding="utf-8")
            self._json(data)
            return
        self._json({"error": "not found"}, 404)

    # ---------- POST ----------
    def do_POST(self):
        path = urlparse(self.path).path
        cfg = minutes.load_config()

        if path == "/api/config":
            body = self._body()
            new = dict(cfg)
            for k in ("asr_model", "language_hints", "minutes_base", "minutes_model",
                      "watch_serial", "watch_addr"):
                if k in body:
                    new[k] = str(body[k]).strip()
            for k in ("diarization", "minutes_enabled"):
                if k in body:
                    new[k] = bool(body[k])
            if "phone_share" in body:
                new["phone_share"] = bool(body["phone_share"])
            for k in ("cloud_provider", "cloud_webhook"):
                if k in body:
                    new[k] = str(body[k]).strip()
            if "phone_serial" in body:
                new["phone_serial"] = str(body["phone_serial"]).strip()
            if str(body.get("cloud_secret") or "").strip():
                new["cloud_secret"] = str(body["cloud_secret"]).strip()
            # 空 key 表示保持不变
            if body.get("api_key"):
                new["api_key"] = str(body["api_key"]).strip()
            if "minutes_key" in body and str(body.get("minutes_key") or "").strip():
                new["minutes_key"] = str(body["minutes_key"]).strip()
            minutes.save_config(new)
            _watch_cache["t"] = 0
            self._json({"ok": True, "config": cfg_public(minutes.load_config())})
            return

        if path == "/api/adb_connect":
            addr = str(self._body().get("addr") or "").strip()
            if not addr:
                self._json({"ok": False, "msg": "请填手表地址,如 192.168.1.8:5555"})
                return
            ok, out = run_adb(["connect", addr], timeout=15)
            _watch_cache["t"] = 0
            self._json({"ok": ok, "msg": out.strip().splitlines()[-1] if out.strip() else ""})
            return

        if path == "/api/rec":
            action = self._body().get("action")
            st = watch_state(cfg)
            if not st["connected"]:
                self._json({"ok": False, "msg": "手表未连接"})
                return
            if not st["app_installed"]:
                self._json({"ok": False, "msg": "手表上还没安装「腕录」"})
                return
            ok, out = run_adb(["-s", st["serial"], "shell", "am", "start",
                               "-n", "com.zcode.watchrec/.RemoteControlActivity",
                               "--es", "action", str(action)])
            time.sleep(0.6)
            _watch_cache["t"] = 0
            st = watch_state(cfg)
            self._json({"ok": ok, "recording": st["recording"],
                        "msg": "" if ok else out.strip()[:200]})
            return

        if path == "/api/pull":
            names = self._body().get("files") or []
            st = watch_state(cfg)
            if not st["connected"]:
                self._json({"ok": False, "msg": "手表未连接"})
                return
            INBOX.mkdir(exist_ok=True)
            pulled, errs = [], []
            for f in st["files"]:
                if names and f["name"] not in names:
                    continue
                ok, out = run_adb(["-s", st["serial"], "pull", f"{WATCH_DIR}/{f['name']}",
                                   str(INBOX / f["name"])], timeout=300)
                (pulled if ok else errs).append(f["name"])
            self._json({"ok": not errs, "pulled": pulled, "errors": errs})
            return

        if path == "/api/submit":
            names = self._body().get("files") or []
            if not names:
                self._json({"ok": False, "msg": "请先勾选要解析的录音"})
                return
            added = []
            for name in names:
                p = INBOX / name
                if not p.exists():
                    continue
                JOB_SEQ[0] += 1
                jid = f"j{JOB_SEQ[0]}"
                JOBS[jid] = {"id": jid, "name": name, "path": str(p),
                             "status": "queued", "log": [], "out": ""}
                QUEUE.put(jid)
                added.append(name)
            self._json({"ok": bool(added), "added": added})
            return

        # ---------- 纪要分发 ----------
        if path == "/api/send_phone":
            body = self._body()
            share = bool(body.get("share", cfg.get("phone_share")))
            ok, msg = send_to_phone(str(body.get("name") or ""), cfg, share)
            self._json({"ok": ok, "msg": msg})
            return

        if path == "/api/cloud_send":
            name = str(self._body().get("name") or "")
            ok, msg = send_to_cloud(name, cfg)
            self._json({"ok": ok, "msg": msg})
            return

        if path == "/api/cloud_test":
            ok, msg = send_to_cloud("腕录测试消息", cfg)
            self._json({"ok": ok, "msg": msg})
            return

        self._json({"error": "not found"}, 404)


def main():
    INBOX.mkdir(exist_ok=True)
    OUT.mkdir(exist_ok=True)
    httpd = ThreadingHTTPServer(("127.0.0.1", PORT), Handler)
    print(f"腕录控制面板 → http://127.0.0.1:{PORT}  (Ctrl+C 退出)")
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()

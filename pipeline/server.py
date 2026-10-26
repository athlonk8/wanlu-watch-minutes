# -*- coding: utf-8 -*-
"""腕录控制面板后端 — 标准库实现,无额外依赖(requests 除外,供流水线用)
启动: python server.py  →  http://127.0.0.1:8733
"""
import base64
import hashlib
import hmac
import json
import os
import secrets
import subprocess
import threading
import time
import urllib.parse
import uuid
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import unquote, urlparse

import requests  # noqa: E402

import minutes  # 同目录流水线模块
import tingwu
from panel_jobs import PanelJobs
from tingwu_jobs import TingwuJobs

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
DATA_ROOT = Path(os.environ.get("WANLU_DATA_DIR", ROOT))
PRIVATE_ROOT = DATA_ROOT / ".local"
INBOX = DATA_ROOT / "inbox"
OUT = DATA_ROOT / "out"
PORT = int(os.environ.get("WANLU_PORT", "8733"))
VERSION = "2.3.0"
WATCH_PACKAGES = ("com.zcode.watchrec.preview", "com.zcode.watchrec")
MAX_AUDIO = 256 * 1024 * 1024
PHONE_DIR = "/sdcard/Download/腕录纪要"

ADB = os.environ.get("WANLU_ADB") or str(Path(os.environ.get("LOCALAPPDATA", "")) / "Android" / "Sdk" / "platform-tools" / "adb.exe")
if not Path(ADB).exists():
    ADB = "adb"


def run_adb(args, timeout=20):
    """跑 adb,返回 (ok, stdout+stderr)。args 不含 adb 本身。"""
    try:
        p = subprocess.run(
            [ADB] + args, capture_output=True, text=True,
            encoding="utf-8", errors="replace", timeout=timeout,
            creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
        )
        return p.returncode == 0, (p.stdout or "") + (p.stderr or "")
    except Exception as e:
        return False, str(e)


# ---------------------------------------------------------------- 任务队列

PANEL_JOBS = None  # Initialized by main(), never start provider work on import.
TINGWU = tingwu.TingwuClient()
DESKTOP_TOKEN = os.environ.get("WANLU_DESKTOP_TOKEN") or secrets.token_urlsafe(32)
DESKTOP_PROCESS = None
TINGWU_JOBS = None


def safe_path(root, name):
    if not isinstance(name, str) or not name or name in (".", ".."):
        return None
    if any(c in name for c in '/\\:<>"|?*') or any(ord(c) < 32 for c in name):
        return None
    if name.endswith((".", " ")):
        return None
    path = root / name
    return path if path.resolve().parent == root.resolve() else None

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

    if not serial:
        for device in devices:
            _, characteristics = run_adb(["-s", device, "shell", "getprop", "ro.build.characteristics"])
            if "watch" in characteristics:
                serial = device
                break
            # Some ColorOS watches report only "nosdcard", not "watch".
            _, packages = run_adb(["-s", device, "shell", "pm", "list", "packages", "com.zcode.watchrec"])
            if any(f"package:{package}" in packages.splitlines() for package in WATCH_PACKAGES):
                serial = device
                break
    st = {"serial": serial or (devices[0] if devices else ""), "devices": devices,
          "package": "", "packages": [],
          "connected": False, "app_installed": False, "recording": False, "files": [], "error": ""}
    if st["serial"] and st["serial"] in devices:
        st["connected"] = True
        ok, out = run_adb(["-s", st["serial"], "shell", "pm", "list", "packages", "com.zcode.watchrec"])
        installed = {line.removeprefix("package:").strip() for line in (out or "").splitlines()}
        st["packages"] = [p for p in WATCH_PACKAGES if p in installed]
        preferred = cfg.get("watch_package", "")
        st["package"] = preferred if preferred in st["packages"] else next(iter(st["packages"]), "")
        st["app_installed"] = bool(st["package"])
        if st["app_installed"]:
            ok, out = run_adb(["-s", st["serial"], "shell", "dumpsys", "power"])
            # 只看“Wake Locks: size=…”活动段(到 Suspend Blockers 为止),历史日志里也有 watchrec:mic 会误判
            live = ""
            if out and "Wake Locks: size" in out:
                live = out.split("Wake Locks: size", 1)[1].split("Suspend Blockers", 1)[0]
            st["recording"] = "watchrec:mic" in live
            watch_dir = f"/sdcard/Android/data/{st['package']}/files"
            ok, out = run_adb(["-s", st["serial"], "shell", "ls", "-l", watch_dir], timeout=15)
            files = []
            for line in (out or "").splitlines():
                tokens = line.split()
                if len(tokens) >= 8 and tokens[-1].startswith("REC_") and tokens[-1].endswith(".m4a"):
                    size = next((int(t) for t in tokens[4:7] if t.isdigit()), 0)
                    files.append({"name": tokens[-1], "size": size})
            st["files"] = sorted(files, key=lambda f: f["name"], reverse=True)
            # 2.2 recordings are private; only inspect its recordings directory.
            ok, private = run_adb(["-s", st["serial"], "shell", "run-as", st["package"], "ls", "-l", "files"], timeout=15)
            if ok:
                by_name = {f["name"]: f for f in st["files"]}
                for line in private.splitlines():
                    tokens = line.split()
                    if len(tokens) >= 8 and tokens[-1].startswith("REC_") and tokens[-1].endswith(".m4a") and tokens[4].isdigit():
                        by_name[tokens[-1]] = {"name": tokens[-1], "size": int(tokens[4]), "private": True}
                st["files"] = sorted(by_name.values(), key=lambda f: f["name"], reverse=True)
    _watch_cache["t"], _watch_cache["data"] = now, st
    return st


def list_inbox():
    INBOX.mkdir(exist_ok=True)
    out = []
    for p in sorted(INBOX.iterdir(), key=lambda x: x.stat().st_mtime, reverse=True):
        if p.is_file() and safe_path(INBOX, p.name) and p.suffix.lower() in minutes.AUDIO_EXT:
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
        "watch_package": cfg.get("watch_package", ""),
        "cloud_provider": cfg.get("cloud_provider", ""),
        "cloud_webhook": cfg.get("cloud_webhook", ""),
        "cloud_secret_set": bool(cfg.get("cloud_secret", "")),
        "phone_serial": cfg.get("phone_serial", ""),
        "phone_share": bool(cfg.get("phone_share")),
    }


# ---------------------------------------------------------------- 纪要分发

def result_md_path(name: str):
    """优先取会议纪要.md,无则转写.md"""
    d = safe_path(OUT, name)
    if d is None:
        return None
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
    watch = watch_state(cfg).get("serial", "")
    cands = [d for d in adb_devices() if d != watch]
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
    def local_request(self):
        host = urlparse("http://" + self.headers.get("Host", ""))
        return (self.client_address[0] in ("127.0.0.1", "::1")
                and host.hostname in ("127.0.0.1", "localhost", "::1")
                and host.port == self.server.server_address[1])

    def log_message(self, fmt, *a):
        pass

    def _json(self, obj, code=200):
        body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def _body(self):
        n = int(self.headers.get("Content-Length") or 0)
        if not 0 <= n <= 1024 * 1024:
            raise ValueError("请求内容过大")
        raw = self.rfile.read(n) or b"{}"
        try:
            data = json.loads(raw)
        except UnicodeDecodeError:
            data = json.loads(raw.decode("gbk", "replace"))
        if not isinstance(data, dict):
            raise ValueError("需要 JSON 对象")
        return data

    def tingwu_body(self):
        body = self._body()
        revision = body.get("session_revision")
        if revision is not None and revision != TINGWU.revision:
            raise tingwu.TingwuError("听悟账号或连接已切换，请刷新后重新确认操作", conflict=True)
        return body

    # ---------- GET ----------
    def do_GET(self):
        try:
            self._get()
        except tingwu.TingwuError as error:
            self._json({"ok": False, "msg": str(error), "login_required": error.login_required}, 401 if error.login_required else 502)
        except requests.RequestException:
            self._json({"ok": False, "msg": "听悟连接失败，请稍后重试"}, 502)
        except (ValueError, TypeError):
            self._json({"ok": False, "msg": "请求参数无效"}, 400)

    def _get(self):
        path = urlparse(self.path).path
        if path == "/api/health":
            return self._json({"service": "wanlu-panel", "version": VERSION})
        if path.startswith("/api/tingwu/"):
            if not self.local_request():
                return self._json({"ok": False, "msg": "听悟账号仅允许本机访问"}, 403)
            if path == "/api/tingwu/status":
                return self._json({"connected": TINGWU.connected, "session_revision": TINGWU.revision,
                                   "uploads": TINGWU_JOBS.snapshot() if TINGWU_JOBS else []})
            if path == "/api/tingwu/tasks":
                query = urllib.parse.parse_qs(urlparse(self.path).query)
                page = max(1, min(int(query.get("page", ["1"])[0]), 10000))
                return self._json(TINGWU.tasks(page, query.get("search", [""])[0][:200]))
            if path.startswith("/api/tingwu/result/"):
                return self._json(TINGWU.result(unquote(path.rsplit("/", 1)[-1])))
            if path == "/api/tingwu/trash":
                query = urllib.parse.parse_qs(urlparse(self.path).query)
                return self._json(TINGWU.trash_list(max(1, int(query.get("page", ["1"])[0]))))
        if path in ("/", "/index.html", "/legacy"):
            html = (HERE / "static" / ("index.html" if path == "/legacy" else "tingwu.html")).read_bytes()
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(html)))
            self.end_headers()
            self.wfile.write(html)
            return
        if path == "/api/state":
            cfg = minutes.load_config()
            devs = adb_devices()
            phone = phone_serial(cfg)
            self._json({
                "watch": watch_state(cfg),
                "inbox": list_inbox(),
                "results": list_results(),
                "jobs": PANEL_JOBS.snapshot() if PANEL_JOBS else [],
                "config": cfg_public(cfg),
                "phone": {"serial": phone, "connected": bool(phone)},
                "devices": devs,
                "lan_addr": f"{lan_ip()}:{PORT}",
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
            d = safe_path(OUT, name)
            if d is None or not d.is_dir():
                self._json({"error": "not found"}, 404)
                return
            data = {}
            for f, key in [("转写.md", "transcript"), ("会议纪要.md", "minutes"), ("raw.json", "raw")]:
                p = d / f
                if p.exists():
                    data[key] = p.read_text(encoding="utf-8")
            data["version"] = max((p.stat().st_mtime_ns for p in d.iterdir() if p.is_file()), default=0)
            self._json(data)
            return
        self._json({"error": "not found"}, 404)

    # ---------- POST ----------
    def do_POST(self):
        try:
            self._post()
        except (ValueError, TypeError, AttributeError):
            self._json({"ok": False, "msg": "请求格式无效，请检查文件名和参数"}, 400)
        except (OSError, minutes.MinutesError):
            self._json({"ok": False, "msg": "操作失败，请检查本地文件权限、磁盘空间和配置"}, 500)
        except tingwu.TingwuError as error:
            self._json({"ok": False, "msg": str(error), "login_required": error.login_required},
                       409 if error.conflict else 401 if error.login_required else 502)
        except requests.RequestException:
            self._json({"ok": False, "msg": "听悟连接失败，请稍后重试"}, 502)

    def _post(self):
        path = urlparse(self.path).path
        cfg = minutes.load_config()

        if path == "/api/desktop/shutdown":
            if not self.local_request() or not hmac.compare_digest(self.headers.get("Authorization", ""), "Bearer " + DESKTOP_TOKEN):
                return self._json({"ok": False}, 403)
            self._json({"ok": True})
            threading.Thread(target=self.server.shutdown, daemon=True).start()
            return

        if path.startswith("/api/tingwu/"):
            origin = self.headers.get("Origin")
            if not self.local_request() or (origin and origin != f"http://{self.headers.get('Host')}"):
                return self._json({"ok": False, "msg": "只允许本机面板访问听悟账号"}, 403)
            if path == "/api/tingwu/session":
                if not hmac.compare_digest(self.headers.get("Authorization", ""), "Bearer " + DESKTOP_TOKEN):
                    return self._json({"ok": False}, 403)
                TINGWU.connect(self.tingwu_body().get("cookie", ""))
                return self._json({"ok": True, "connected": TINGWU.connected})
            if path == "/api/tingwu/login":
                global DESKTOP_PROCESS
                executable = ROOT / "node_modules/electron/dist/electron.exe"
                if not executable.is_file():
                    return self._json({"ok": False, "msg": "请先在项目目录运行 npm install 安装官方登录组件"}, 400)
                environment = dict(os.environ, WANLU_DESKTOP_TOKEN=DESKTOP_TOKEN,
                                   WANLU_PANEL_URL=f"http://127.0.0.1:{PORT}")
                environment.pop("ELECTRON_RUN_AS_NODE", None)
                # An existing companion receives Electron's second-instance event.
                DESKTOP_PROCESS = subprocess.Popen([str(executable), str(ROOT)], cwd=ROOT, env=environment,
                    stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                return self._json({"ok": True, "msg": "已打开听悟官方登录窗口，请用原账号完成登录"})
            if path == "/api/tingwu/upload":
                body = self.tingwu_body()
                names = body.get("files") or []
                if not isinstance(names, list) or not names or any(safe_path(INBOX, n) is None or not (INBOX / n).is_file()
                    or Path(n).suffix.lower() not in minutes.AUDIO_EXT for n in names):
                    return self._json({"ok": False, "msg": "请先选择有效的本地录音"}, 400)
                added = sum(TINGWU_JOBS.enqueue(n, str(body.get("title") or "").strip()[:500])[1] for n in names)
                return self._json({"ok": True, "msg": f"已加入 {added} 条听悟上传任务；相同音频不会重复新建"})
            if path == "/api/tingwu/retry":
                TINGWU_JOBS.retry(str(self.tingwu_body().get("id") or ""))
                return self._json({"ok": True, "msg": "已准备恢复同一条听悟任务"})
            if path == "/api/tingwu/cancel":
                TINGWU_JOBS.cancel(str(self.tingwu_body().get("id") or ""))
                return self._json({"ok": True, "msg": "已请求取消，电脑原件保留"})
            if path == "/api/tingwu/reconcile":
                record = TINGWU_JOBS.reconcile(str(self.tingwu_body().get("id") or ""))
                return self._json({"ok": True, "msg": "已核对云端记录", "trans_id": record})
            if path == "/api/tingwu/rename":
                body = self.tingwu_body()
                TINGWU.rename(body.get("id"), body.get("title"))
                return self._json({"ok": True, "msg": "听悟记录已改名"})
            if path == "/api/tingwu/delete":
                body = self.tingwu_body()
                if body.get("confirm") is not True:
                    return self._json({"ok": False, "msg": "请先确认移入听悟回收站"}, 400)
                record = tingwu.check_id(body.get("id"))
                trash_id = TINGWU.trash(record)
                TINGWU_JOBS.retired(record, trash_id)
                return self._json({"ok": True, "msg": "记录已移入听悟回收站，原录音保留"})
            if path == "/api/tingwu/restore":
                trash_id = self.tingwu_body().get("id")
                TINGWU.restore(trash_id)
                TINGWU_JOBS.restored(trash_id)
                return self._json({"ok": True, "msg": "听悟记录已恢复"})
            if path == "/api/tingwu/edit":
                body = self.tingwu_body()
                if body.get("kind") == "transcript":
                    TINGWU.save_paragraph(body.get("id"), body.get("item_id"), body.get("text"), body.get("version"))
                elif body.get("kind") == "minutes":
                    TINGWU.save_summary(body.get("id"), body.get("key"), body.get("item_id"), body.get("text"), body.get("version"))
                else:
                    raise ValueError("无效编辑类型")
                return self._json({"ok": True, "msg": "修改已保存到听悟"})

        if path == "/api/import":
            return self.import_audio()

        if path == "/api/regenerate":
            body = self._body()
            name = body.get("name")
            target = safe_path(OUT, name)
            if target is None or not (target / "转写.md").is_file():
                return self._json({"ok": False, "msg": "没有可用的转写"}, 404)
            if not cfg.get("minutes_key"):
                return self._json({"ok": False, "msg": "请先保存纪要模型 API Key"}, 400)
            jid = PANEL_JOBS.enqueue(name, cfg, str(body.get("title") or "").strip()[:500], kind="minutes")
            return self._json({"ok": bool(jid), "msg": "已加入纪要队列" if jid else "该结果已有纪要任务，请勿重复提交"})

        if path == "/api/config":
            body = self._body()
            new = dict(cfg)
            for k in ("asr_model", "language_hints", "minutes_base", "minutes_model",
                      "watch_serial", "watch_addr", "watch_package"):
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
                if cfg.get("minutes_key") == cfg.get("api_key"):
                    new["minutes_key"] = new["api_key"]
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
            if action not in ("start", "stop"):
                return self._json({"ok": False, "msg": "无效录音指令"}, 400)
            st = watch_state(cfg)
            if not st["connected"]:
                self._json({"ok": False, "msg": "手表未连接"})
                return
            if not st["app_installed"]:
                self._json({"ok": False, "msg": "手表上还没安装「腕录」"})
                return
            # ColorOS 在息屏状态可能拒绝启动前台服务,先唤醒屏幕再下发指令
            if action == "start":
                run_adb(["-s", st["serial"], "shell", "input", "keyevent", "KEYCODE_WAKEUP"])
                time.sleep(0.5)
            ok, out = run_adb(["-s", st["serial"], "shell", "am", "start",
                               "-n", f"{st['package']}/com.zcode.watchrec.RemoteControlActivity",
                               "--es", "action", str(action)])
            time.sleep(0.6)
            _watch_cache["t"] = 0
            st = watch_state(cfg)
            self._json({"ok": ok, "recording": st["recording"],
                        "msg": "" if ok else out.strip()[:200]})
            return

        if path == "/api/pull":
            names = self._body().get("files") or []
            if not isinstance(names, list) or not names:
                return self._json({"ok": False, "msg": "请先勾选要拉取的录音"}, 400)
            st = watch_state(cfg)
            if not st["connected"]:
                self._json({"ok": False, "msg": "手表未连接"})
                return
            if st["recording"]:
                return self._json({"ok": False, "msg": "请先停止录音，再拉取完整文件"}, 409)
            INBOX.mkdir(exist_ok=True)
            pulled, errs = [], [name for name in names if name not in {f["name"] for f in st["files"]}]
            for f in st["files"]:
                if names and f["name"] not in names:
                    continue
                destination = safe_path(INBOX, f["name"])
                if destination is None:
                    errs.append(f["name"])
                    continue
                watch_dir = f"/sdcard/Android/data/{st['package']}/files"
                temp = INBOX / f".{uuid.uuid4().hex}.part"
                try:
                    if f.get("private"):
                        with temp.open("wb") as output:
                            result = subprocess.run([ADB, "-s", st["serial"], "exec-out", "run-as", st["package"],
                                "cat", f"files/{f['name']}"], stdout=output, stderr=subprocess.PIPE, timeout=300,
                                creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
                        ok = result.returncode == 0
                    else:
                        ok, out = run_adb(["-s", st["serial"], "pull", f"{watch_dir}/{f['name']}",
                                           str(temp)], timeout=300)
                    if ok and temp.is_file() and temp.stat().st_size == f["size"]:
                        destination = save_import(temp, destination)
                        pulled.append(destination.name)
                    else:
                        errs.append(f["name"])
                finally:
                    temp.unlink(missing_ok=True)
            self._json({"ok": bool(pulled) and not errs, "pulled": pulled, "errors": errs})
            return

        if path == "/api/submit":
            body = self._body()
            names = body.get("files") or []
            params = body.get("params") or {}
            if not isinstance(names, list) or not names or not isinstance(params, dict):
                self._json({"ok": False, "msg": "请先勾选要解析的录音"})
                return
            if any(safe_path(INBOX, n) is None or not (INBOX / n).is_file()
                   or Path(n).suffix.lower() not in minutes.AUDIO_EXT for n in names):
                return self._json({"ok": False, "msg": "录音文件不存在或文件名无效"}, 400)
            if not cfg.get("api_key"):
                return self._json({"ok": False, "msg": "请先保存百炼 API Key"}, 400)
            # 每个任务独立保存参数，后续设置更改不影响已排队任务。
            for k in ("diarization", "language_hints", "asr_model",
                      "minutes_enabled", "minutes_model"):
                if k in params and params[k] not in (None, ""):
                    if k in ("diarization", "minutes_enabled"):
                        cfg[k] = bool(params[k])
                    else:
                        cfg[k] = str(params[k])
            title = str(params.get("title") or "").strip()[:500]
            added, skipped = [], []
            for name in names:
                p = INBOX / name
                if not p.exists():
                    continue
                jid = PANEL_JOBS.enqueue(name, cfg, title)
                (added if jid else skipped).append(name)
            self._json({"ok": bool(added), "added": added, "skipped": skipped,
                        "msg": "已加入队列" if added else "所选录音正在排队或处理中，请勿重复提交"})
            return

        if path == "/api/watch_push":
            """手表手动上传录音:只落盘 inbox,绝不自动解析"""
            try:
                ctype = self.headers.get("Content-Type", "")
                length = int(self.headers.get("Content-Length", "0"))
                if not 0 < length <= MAX_AUDIO:
                    return self._json({"ok": False, "msg": "文件为空或超过 256 MB"}, 413)
                body = self.rfile.read(length)
                if len(body) != length:
                    return self._json({"ok": False, "msg": "上传不完整"}, 400)
                parts = parse_multipart(body, ctype)
                fn, data = parts.get("file", (None, b""))
                if not fn or not data:
                    self._json({"ok": False, "msg": "缺少文件"}, 400)
                    return
                if safe_path(INBOX, fn) is None:
                    return self._json({"ok": False, "msg": "文件名无效"}, 400)
                if not fn.endswith(".m4a"):
                    fn += ".m4a"
                INBOX.mkdir(exist_ok=True)
                temp = INBOX / f".{uuid.uuid4().hex}.part"
                try:
                    temp.write_bytes(data)
                    fn = save_import(temp, INBOX / fn).name
                finally:
                    temp.unlink(missing_ok=True)
                print(f"[手表上传] {fn} {len(data)} 字节 → inbox", flush=True)
                self._json({"ok": True, "name": fn, "size": len(data)})
            except Exception as e:
                self._json({"ok": False, "msg": str(e)}, 500)
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

    def import_audio(self):
        name = unquote(self.headers.get("X-Filename", ""))
        target = safe_path(INBOX, name)
        length = int(self.headers.get("Content-Length", "0"))
        if target is None or target.suffix.lower() not in minutes.AUDIO_EXT:
            return self._json({"ok": False, "msg": "请选择支持的音频文件，文件名不能含路径"}, 400)
        if not 0 < length <= MAX_AUDIO:
            return self._json({"ok": False, "msg": "文件为空或超过 256 MB"}, 413)
        INBOX.mkdir(exist_ok=True)
        temp = INBOX / f".{uuid.uuid4().hex}.part"
        self.connection.settimeout(60)
        try:
            remaining = length
            with temp.open("xb") as output:
                while remaining:
                    chunk = self.rfile.read(min(65536, remaining))
                    if not chunk:
                        return self._json({"ok": False, "msg": "上传不完整"}, 400)
                    output.write(chunk)
                    remaining -= len(chunk)
            target = save_import(temp, target)
            self._json({"ok": True, "name": target.name, "size": length})
        finally:
            temp.unlink(missing_ok=True)


def lan_ip() -> str:
    """本机局域网 IP(开个 UDP socket 不发包即可拿到路由表出口)"""
    try:
        import socket as _s
        s = _s.socket(_s.AF_INET, _s.SOCK_DGRAM)
        s.connect(("223.5.5.5", 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except Exception:
        return "127.0.0.1"


_import_lock = threading.Lock()


def save_import(temp, target):
    """Identical re-imports reuse the file; differing contents never overwrite it."""
    with _import_lock:
        if target.exists():
            with target.open("rb") as existing, temp.open("rb") as incoming:
                if hashlib.file_digest(existing, "sha256").digest() == hashlib.file_digest(incoming, "sha256").digest():
                    return target
            target = target.with_name(f"{target.stem[:80]}_{uuid.uuid4().hex[:8]}{target.suffix}")
        temp.replace(target)
        return target


def parse_multipart(body: bytes, ctype: str):
    """MIME parsing preserves trailing CR/LF bytes in binary audio."""
    from email import policy
    from email.parser import BytesParser
    message = BytesParser(policy=policy.default).parsebytes(
        b"Content-Type: " + ctype.encode("ascii") + b"\r\nMIME-Version: 1.0\r\n\r\n" + body)
    out = {}
    for part in message.iter_parts():
        name = part.get_param("name", header="content-disposition")
        if name:
            out[name] = (part.get_filename(), part.get_payload(decode=True))
    return out


def main():
    global PANEL_JOBS, TINGWU_JOBS, DESKTOP_TOKEN
    if sys.stdout is None:
        sys.stdout = open(os.devnull, "w")
    if sys.stderr is None:
        sys.stderr = open(os.devnull, "w")
    INBOX.mkdir(parents=True, exist_ok=True)
    OUT.mkdir(parents=True, exist_ok=True)
    PANEL_JOBS = PanelJobs(INBOX, OUT)
    TINGWU_JOBS = TingwuJobs(PRIVATE_ROOT, INBOX, TINGWU)
    TINGWU.history_root = PRIVATE_ROOT / "edit-history"
    token_file = PRIVATE_ROOT / "desktop-token"
    if token_file.exists() and not os.environ.get("WANLU_DESKTOP_TOKEN"):
        DESKTOP_TOKEN = token_file.read_text(encoding="utf-8").strip()
    else:
        token_file.write_text(DESKTOP_TOKEN, encoding="utf-8")
        token_file.chmod(0o600)
    httpd = ThreadingHTTPServer((os.environ.get("WANLU_BIND", "0.0.0.0"), PORT), Handler)
    httpd.daemon_threads = True
    threading.Thread(target=PANEL_JOBS.worker, daemon=True).start()
    threading.Thread(target=TINGWU_JOBS.worker, daemon=True).start()
    ip = lan_ip()
    print(f"腕录控制面板 → http://127.0.0.1:{PORT}  (局域网 {ip}:{PORT}, 手表上传用)  Ctrl+C 退出")
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        httpd.server_close()


if __name__ == "__main__":
    main()

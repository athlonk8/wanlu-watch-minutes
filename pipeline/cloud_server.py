"""Private WanLu gateway. Run behind HTTPS; never expose the legacy PC panel.

The watch posts an audio stream; the phone reads results. No ADB or PC required.
Only this process holds the provider API key. SQLite persists jobs and deduplicates
audio by SHA-256, including concurrent/retried HTTP requests.
"""
import hashlib
import hmac
import json
import os
import re
import sqlite3
import threading
import time
import uuid
from contextlib import contextmanager
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlsplit

import minutes

MAX_AUDIO = 128 * 1024 * 1024
JOB_ID = re.compile(r"[a-f0-9]{64}\Z")


class Store:
    def __init__(self, root):
        self.root = Path(root)
        (self.root / "audio").mkdir(parents=True, exist_ok=True)
        (self.root / "results").mkdir(exist_ok=True)
        with self.db() as db:
            db.execute("""CREATE TABLE IF NOT EXISTS jobs (
                id TEXT PRIMARY KEY, name TEXT NOT NULL, size INTEGER NOT NULL,
                created REAL NOT NULL, status TEXT NOT NULL, step TEXT NOT NULL,
                output TEXT NOT NULL DEFAULT '')""")
            # A provider call may have finished before a crash. Never auto-bill again.
            db.execute("UPDATE jobs SET status='error', step='服务重启中断处理，请确认后重试' WHERE status='running'")

    @contextmanager
    def db(self):
        db = sqlite3.connect(self.root / "jobs.sqlite3", timeout=30)
        db.row_factory = sqlite3.Row
        try:
            with db:
                yield db
        finally:
            db.close()

    def get(self, jid):
        with self.db() as db:
            row = db.execute("SELECT * FROM jobs WHERE id=?", (jid,)).fetchone()
            return dict(row) if row else None

    def jobs(self):
        with self.db() as db:
            return [dict(r) for r in db.execute("SELECT * FROM jobs ORDER BY created DESC LIMIT 200")]

    def accept(self, jid, name, size, temp):
        with self.db() as db:
            db.execute("BEGIN IMMEDIATE")
            if db.execute("SELECT 1 FROM jobs WHERE id=?", (jid,)).fetchone():
                temp.unlink(missing_ok=True)
                return False
            temp.replace(self.root / "audio" / f"{jid}.m4a")
            db.execute("INSERT INTO jobs(id,name,size,created,status,step) VALUES(?,?,?,?,?,?)",
                       (jid, name, size, time.time(), "queued", "等待转写"))
            return True

    def update(self, jid, status, step, output=""):
        with self.db() as db:
            db.execute("UPDATE jobs SET status=?,step=?,output=? WHERE id=?", (status, step, output, jid))

    def retry(self, jid):
        with self.db() as db:
            return db.execute("UPDATE jobs SET status='queued',step='等待重试' WHERE id=? AND status='error'", (jid,)).rowcount == 1

    def process_next(self, processor=minutes.process):
        with self.db() as db:
            db.execute("BEGIN IMMEDIATE")
            job = db.execute("SELECT * FROM jobs WHERE status='queued' ORDER BY created LIMIT 1").fetchone()
            if not job:
                return False
            db.execute("UPDATE jobs SET status='running',step='正在转写' WHERE id=?", (job["id"],))
        jid = job["id"]
        try:
            cfg = minutes.load_config()
            if not cfg["api_key"]:
                raise minutes.MinutesError("Missing provider configuration")
            output = processor(self.root / "audio" / f"{jid}.m4a", cfg, self.root / "results",
                               progress=lambda _: None, title=job["name"].removesuffix(".m4a"))
            if Path(output).resolve() != (self.root / "results" / jid).resolve():
                raise ValueError("Unexpected output path")
            self.update(jid, "done", "处理完成", jid)
        except Exception:
            # Provider exceptions can include credentials/signed URLs. Don't expose them.
            self.update(jid, "error", "处理失败，请检查服务端配置或额度后重试")
        return True


class CloudServer(ThreadingHTTPServer):
    daemon_threads = True
    def __init__(self, address, store, token):
        if len(token) < 32 or not token.isascii() or any(c.isspace() for c in token):
            raise ValueError("WANLU_TOKEN must contain at least 32 ASCII characters without whitespace")
        self.store, self.token = store, token
        super().__init__(address, Handler)


class Handler(BaseHTTPRequestHandler):
    def setup(self):
        super().setup()
        self.connection.settimeout(120)

    def log_message(self, *_):
        pass  # No URLs, auth headers, or request bodies in access logs.

    def reply(self, code, body, content_type="application/json; charset=utf-8"):
        if not isinstance(body, bytes):
            body = json.dumps(body, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Referrer-Policy", "no-referrer")
        self.send_header("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self'; frame-ancestors 'none'; base-uri 'none'")
        self.end_headers()
        self.wfile.write(body)

    def authorized(self):
        header = self.headers.get("Authorization", "")
        if not hmac.compare_digest(header.encode("utf-8"), f"Bearer {self.server.token}".encode("utf-8")):
            self.reply(401, {"error": "unauthorized"})
            return False
        return True

    def do_GET(self):
        path = urlsplit(self.path).path
        static = {"/": ("index.html", "text/html; charset=utf-8"),
                  "/app.js": ("app.js", "text/javascript; charset=utf-8"),
                  "/style.css": ("style.css", "text/css; charset=utf-8")}
        if path in static:
            name, mime = static[path]
            return self.reply(200, (Path(__file__).parent / "mobile" / name).read_bytes(), mime)
        if not self.authorized():
            return
        if path == "/api/health":
            return self.reply(200, {"service": "wanlu-cloud", "version": 2})
        if path == "/api/jobs":
            return self.reply(200, self.server.store.jobs())
        parts = path.strip("/").split("/")
        if len(parts) in (3, 4) and parts[:2] == ["api", "jobs"] and JOB_ID.fullmatch(parts[2]):
            job = self.server.store.get(parts[2])
            if not job:
                return self.reply(404, {"error": "not_found"})
            if len(parts) == 3:
                return self.reply(200, job)
            filename = {"minutes": "会议纪要.md", "transcript": "转写.md"}.get(parts[3])
            if filename and job["status"] == "done":
                file = self.server.store.root / "results" / job["id"] / filename
                if file.is_file():
                    return self.reply(200, file.read_bytes(), "text/plain; charset=utf-8")
        self.reply(404, {"error": "not_found"})

    def do_POST(self):
        if not self.authorized():
            return
        path = urlsplit(self.path).path
        if path == "/api/recordings":
            return self.upload()
        parts = path.strip("/").split("/")
        if len(parts) == 4 and parts[:2] == ["api", "jobs"] and JOB_ID.fullmatch(parts[2]) and parts[3] == "retry":
            if self.server.store.retry(parts[2]):
                return self.reply(202, {"status": "queued"})
            return self.reply(409, {"error": "only_failed_jobs_can_retry"})
        self.reply(404, {"error": "not_found"})

    def upload(self):
        jid = self.headers.get("X-Audio-SHA256", "")
        name = self.headers.get("X-Filename", "")
        try:
            length = int(self.headers.get("Content-Length", ""))
        except ValueError:
            return self.reply(411, {"error": "length_required"})
        if self.headers.get("Transfer-Encoding"):
            return self.reply(400, {"error": "transfer_encoding_not_supported"})
        if not 0 < length <= MAX_AUDIO:
            return self.reply(413, {"error": "audio_too_large_or_empty"})
        if not JOB_ID.fullmatch(jid) or not re.fullmatch(r"[A-Za-z0-9_-]{1,100}\.m4a", name):
            return self.reply(400, {"error": "invalid_hash_or_filename"})
        if self.headers.get("Content-Type", "").split(";")[0] != "audio/mp4":
            return self.reply(415, {"error": "audio_mp4_required"})
        temp = self.server.store.root / "audio" / f".{uuid.uuid4().hex}.part"
        try:
            digest = hashlib.sha256()
            remaining = length
            with temp.open("xb") as output:
                while remaining:
                    chunk = self.rfile.read(min(64 * 1024, remaining))
                    if not chunk:
                        return self.reply(400, {"error": "incomplete_upload"})
                    output.write(chunk)
                    digest.update(chunk)
                    remaining -= len(chunk)
            if not hmac.compare_digest(digest.hexdigest(), jid):
                return self.reply(422, {"error": "checksum_mismatch"})
            new = self.server.store.accept(jid, name, length, temp)
            self.reply(202 if new else 200, self.server.store.get(jid))
        except (TimeoutError, ConnectionError):
            pass  # Partial uploads never become jobs.
        finally:
            temp.unlink(missing_ok=True)


def main():
    token = os.environ.get("WANLU_TOKEN", "")
    if not minutes.load_config()["api_key"]:
        raise SystemExit("Configure DASHSCOPE_API_KEY or pipeline/config.ini before starting")
    store = Store(os.environ.get("WANLU_DATA", "./cloud-data"))
    server = CloudServer((os.environ.get("WANLU_BIND", "127.0.0.1"), int(os.environ.get("PORT", "8734"))), store, token)
    def process_queue():
        while True:
            if not store.process_next():
                time.sleep(2)
    threading.Thread(target=process_queue, daemon=True).start()
    print("WanLu cloud ready. Configure an HTTPS reverse proxy; legacy panel is not exposed.", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()

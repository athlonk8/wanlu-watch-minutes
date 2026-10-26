import concurrent.futures
import hashlib
import http.client
import json
from pathlib import Path
import socket
import sys
import tempfile
import threading
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from cloud_server import CloudServer, Store, MAX_AUDIO

TOKEN = "test-token-" + "a" * 40
AUDIO = b"synthetic audio for protocol verification" * 100
HASH = hashlib.sha256(AUDIO).hexdigest()


class CloudTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.store = Store(self.temp.name)
        self.server = CloudServer(("127.0.0.1", 0), self.store, TOKEN)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.port = self.server.server_address[1]

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()
        self.temp.cleanup()

    def request(self, method, path, body=None, headers=None, auth=True):
        conn = http.client.HTTPConnection("127.0.0.1", self.port, timeout=5)
        headers = dict(headers or {})
        if auth:
            headers["Authorization"] = f"Bearer {TOKEN}"
        try:
            conn.request(method, path, body=body, headers=headers)
            response = conn.getresponse()
            return response.status, response.read(), dict(response.getheaders())
        finally:
            conn.close()

    def upload(self, **overrides):
        headers = {"Content-Type": "audio/mp4", "X-Filename": "REC_20261023_120000.m4a", "X-Audio-SHA256": HASH}
        headers.update(overrides)
        # Header-only size rejection: sending bytes with Content-Length: 0 is
        # invalid HTTP framing and can provoke a TCP reset on Windows.
        body = b"" if "Content-Length" in overrides else AUDIO
        return self.request("POST", "/api/recordings", body, headers)

    def test_auth_required_for_all_private_routes(self):
        for method, path in [("GET", "/api/health"), ("GET", "/api/jobs"),
                             ("GET", f"/api/jobs/{HASH}/minutes"), ("POST", "/api/recordings"),
                             ("POST", f"/api/jobs/{HASH}/retry")]:
            self.assertEqual(self.request(method, path, auth=False)[0], 401)

    def test_health_and_mobile_are_separate_from_pc_panel(self):
        self.assertEqual(json.loads(self.request("GET", "/api/health")[1])["service"], "wanlu-cloud")
        status, body, headers = self.request("GET", "/", auth=False)
        self.assertEqual(status, 200)
        self.assertIn(b"login-form", body)
        self.assertIn("frame-ancestors 'none'", headers["Content-Security-Policy"])
        for path in ("/api/state", "/api/config", "/../config.ini", "/api/jobs/../minutes"):
            self.assertEqual(self.request("GET", path)[0], 404)

    def test_upload_deduplicates_and_preserves_audio(self):
        self.assertEqual(self.upload()[0], 202)
        self.assertEqual(self.upload()[0], 200)
        self.assertEqual(len(self.store.jobs()), 1)
        self.assertEqual((Path(self.temp.name) / "audio" / f"{HASH}.m4a").read_bytes(), AUDIO)
        self.assertEqual(list((Path(self.temp.name) / "audio").glob("*.part")), [])

    def test_concurrent_uploads_create_one_job(self):
        with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
            statuses = list(pool.map(lambda _: self.upload()[0], range(4)))
        self.assertEqual(statuses.count(202), 1)
        self.assertEqual(statuses.count(200), 3)
        self.assertEqual(len(self.store.jobs()), 1)

    def test_validation_prevents_jobs_for_bad_uploads(self):
        for overrides, expected in [({"X-Filename": "../evil.m4a"}, 400),
                                    ({"X-Audio-SHA256": "0" * 64}, 422),
                                    ({"Content-Type": "text/plain"}, 415),
                                    ({"Content-Length": str(MAX_AUDIO + 1)}, 413),
                                    ({"Content-Length": "0"}, 413)]:
            with self.subTest(overrides=overrides):
                self.assertEqual(self.upload(**overrides)[0], expected)
        self.assertEqual(self.store.jobs(), [])
        self.assertEqual(list((Path(self.temp.name) / "audio").iterdir()), [])

    def test_disconnected_upload_does_not_create_job(self):
        sock = socket.create_connection(("127.0.0.1", self.port))
        request = (f"POST /api/recordings HTTP/1.0\r\nAuthorization: Bearer {TOKEN}\r\n"
                   f"Content-Length: {len(AUDIO)}\r\nContent-Type: audio/mp4\r\n"
                   f"X-Filename: REC_test.m4a\r\nX-Audio-SHA256: {HASH}\r\n\r\n")
        sock.sendall(request.encode() + AUDIO[:50])
        sock.shutdown(socket.SHUT_WR)
        while sock.recv(1024):
            pass
        sock.close()
        self.assertEqual(self.store.jobs(), [])
        self.assertEqual(list((Path(self.temp.name) / "audio").iterdir()), [])

    @patch("cloud_server.minutes.load_config", return_value={"api_key": "fake-test-only"})
    def test_watch_upload_to_phone_result_without_adb_or_provider_calls(self, _):
        calls = []
        def fake_process(path, cfg, root, progress, title):
            calls.append(path)
            out = root / path.stem
            out.mkdir(exist_ok=True)
            (out / "会议纪要.md").write_text("# 测试会议\n待办：完成联调", encoding="utf-8")
            (out / "转写.md").write_text("说话人A：测试转写", encoding="utf-8")
            return out
        self.upload()
        self.assertTrue(self.store.process_next(fake_process))
        self.assertFalse(self.store.process_next(fake_process))
        self.assertEqual(len(calls), 1)
        self.assertEqual(self.store.get(HASH)["status"], "done")
        status, body, _ = self.request("GET", f"/api/jobs/{HASH}/minutes")
        self.assertEqual(status, 200)
        self.assertIn("完成联调", body.decode())
        self.assertEqual(self.request("GET", f"/api/jobs/{HASH}/transcript")[0], 200)
        # Re-sending after completion must not call the billable pipeline again.
        self.assertEqual(self.upload()[0], 200)
        self.assertFalse(self.store.process_next(fake_process))
        self.assertEqual(self.request("POST", f"/api/jobs/{HASH}/retry")[0], 409)

    @patch("cloud_server.minutes.load_config", return_value={"api_key": "fake-test-only"})
    def test_failure_is_not_automatically_retried_and_does_not_leak_secrets(self, _):
        self.upload()
        def fail(*args, **kwargs):
            raise ValueError("secret-key-provider-url")
        self.store.process_next(fail)
        self.assertEqual(self.store.get(HASH)["status"], "error")
        self.assertNotIn("secret-key", json.dumps(self.store.get(HASH)))
        self.assertFalse(self.store.process_next(fail))
        self.assertEqual(self.request("POST", f"/api/jobs/{HASH}/retry")[0], 202)
        self.assertEqual(self.request("POST", f"/api/jobs/{HASH}/retry")[0], 409)

    def test_restart_keeps_queued_but_requires_confirmation_for_interrupted_job(self):
        self.upload()
        self.assertEqual(Store(self.temp.name).get(HASH)["status"], "queued")
        self.store.update(HASH, "running", "working")
        restored = Store(self.temp.name)
        self.assertEqual(restored.get(HASH)["status"], "error")
        self.assertFalse(restored.process_next())

    def test_weak_access_token_is_rejected(self):
        with self.assertRaises(ValueError):
            CloudServer(("127.0.0.1", 0), self.store, "short")


if __name__ == "__main__":
    unittest.main()

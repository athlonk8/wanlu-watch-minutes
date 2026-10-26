import concurrent.futures
import copy
import http.client
import json
from pathlib import Path
import sys
import tempfile
import threading
import unittest
from unittest.mock import patch
from urllib.parse import quote

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import minutes
import server
from panel_jobs import PanelJobs


class PanelTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.inbox, self.out = self.root / "inbox", self.root / "out"
        self.inbox.mkdir(); self.out.mkdir()
        self.jobs = PanelJobs(self.inbox, self.out)
        self.cfg = dict(minutes.DEFAULTS, api_key="synthetic-api-key", minutes_key="synthetic-minutes-key")
        self.patches = [patch.object(server, "INBOX", self.inbox), patch.object(server, "OUT", self.out),
                        patch.object(server, "PANEL_JOBS", self.jobs),
                        patch.object(minutes, "load_config", side_effect=lambda: copy.deepcopy(self.cfg)),
                        patch.object(server, "adb_devices", return_value=[]),
                        patch.object(server, "watch_state", return_value={"serial": "", "connected": False})]
        for item in self.patches: item.start()
        self.httpd = server.ThreadingHTTPServer(("127.0.0.1", 0), server.Handler)
        self.thread = threading.Thread(target=self.httpd.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self):
        self.httpd.shutdown(); self.httpd.server_close(); self.thread.join()
        for item in reversed(self.patches): item.stop()
        self.temp.cleanup()

    def request(self, method, path, body=None, headers=None):
        conn = http.client.HTTPConnection("127.0.0.1", self.httpd.server_port, timeout=5)
        if isinstance(body, dict):
            body = json.dumps(body).encode()
            headers = dict(headers or {}, **{"Content-Type": "application/json"})
        try:
            conn.request(method, path, body, headers or {})
            res = conn.getresponse()
            raw = res.read()
            return res.status, json.loads(raw) if res.getheader("Content-Type", "").startswith("application/json") else raw
        finally:
            conn.close()

    def test_import_is_byte_identical_local_only_and_preserves_name_collisions(self):
        data = b"synthetic-audio\x00\r\n\r\n"
        headers = {"X-Filename": quote('会议 "测试".m4a')}
        # Invalid Windows filename is rejected rather than silently renamed.
        self.assertEqual(self.request("POST", "/api/import", data, headers)[0], 400)
        headers = {"X-Filename": quote("会议测试.m4a")}
        first = self.request("POST", "/api/import", data, headers)[1]
        self.assertTrue(first["ok"])
        self.assertEqual((self.inbox / first["name"]).read_bytes(), data)
        second = self.request("POST", "/api/import", data, headers)[1]
        self.assertEqual(first["name"], second["name"])
        third = self.request("POST", "/api/import", b"different", headers)[1]
        self.assertNotEqual(first["name"], third["name"])
        self.assertEqual(len(self.jobs.snapshot()), 0)
        self.assertEqual(list(self.inbox.glob("*.part")), [])

    def test_import_and_result_paths_cannot_escape_workspace(self):
        for name in ["../escape.m4a", "C:\\escape.m4a", "a/escape.m4a"]:
            self.assertEqual(self.request("POST", "/api/import", b"audio", {"X-Filename": quote(name)})[0], 400)
        for endpoint in ["/api/result/", "/api/download/"]:
            self.assertEqual(self.request("GET", endpoint + quote("../outside", safe=""))[0], 404)
        self.assertEqual(self.request("POST", "/api/submit", {"files": ["../escape.m4a"]})[0], 400)

    def test_legacy_watch_multipart_keeps_binary_crlf(self):
        data = b"\x00test\r\n\r\n"
        body = b'--test-boundary\r\nContent-Disposition: form-data; name="file"; filename="REC_test.m4a"\r\nContent-Type: audio/mp4\r\n\r\n' + data + b'\r\n--test-boundary--\r\n'
        status, result = self.request("POST", "/api/watch_push", body, {"Content-Type": 'multipart/form-data; boundary="test-boundary"'})
        self.assertEqual(status, 200)
        self.assertEqual((self.inbox / result["name"]).read_bytes(), data)
        self.assertEqual(self.jobs.snapshot(), [])

    def test_submit_snapshots_models_and_deduplicates_active_work(self):
        for name in ["a.m4a", "b.m4a"]: (self.inbox / name).write_bytes(b"audio")
        self.assertTrue(self.request("POST", "/api/submit", {"files": ["a.m4a"], "params": {"minutes_model": "model-a", "title": "主题A"}})[1]["ok"])
        self.assertFalse(self.request("POST", "/api/submit", {"files": ["a.m4a"]})[1]["ok"])
        self.assertTrue(self.request("POST", "/api/submit", {"files": ["b.m4a"], "params": {"minutes_model": "model-b"}})[1]["ok"])
        self.cfg["minutes_model"] = "changed-later"
        with patch.object(minutes, "process") as process:
            self.jobs.run_next(); self.jobs.run_next()
            self.assertEqual([c.args[1]["minutes_model"] for c in process.call_args_list], ["model-a", "model-b"])
            self.assertEqual(process.call_args_list[0].kwargs["title"], "主题A")
        journal = self.jobs.file.read_text(encoding="utf-8")
        self.assertNotIn("synthetic-api-key", journal)
        self.assertNotIn("synthetic-minutes-key", journal)
        self.assertNotIn("config", journal)

    def test_concurrent_submit_creates_one_job(self):
        (self.inbox / "a.m4a").write_bytes(b"audio")
        with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
            result = list(pool.map(lambda _: self.request("POST", "/api/submit", {"files": ["a.m4a"]})[1]["ok"], range(4)))
        self.assertEqual(result.count(True), 1)

    def test_restart_keeps_history_but_does_not_automatically_bill(self):
        self.jobs.enqueue("a.m4a", self.cfg)
        restored = PanelJobs(self.inbox, self.out)
        self.assertEqual(restored.snapshot()[0]["status"], "interrupted")
        with patch.object(minutes, "process") as process:
            self.assertFalse(restored.run_next())
            process.assert_not_called()

    def test_regenerate_uses_saved_transcript_only(self):
        target = self.out / "existing"
        target.mkdir(); (target / "转写.md").write_text("会议原文", encoding="utf-8")
        self.assertTrue(self.request("POST", "/api/regenerate", {"name": "existing", "title": "新主题"})[1]["ok"])
        with patch.object(minutes, "make_minutes", return_value="新纪要") as make, patch.object(minutes, "upload_audio") as upload:
            self.jobs.run_next()
            upload.assert_not_called()
            self.assertEqual(make.call_args.kwargs["title"], "新主题")
        self.assertEqual((target / "会议纪要.md").read_text(encoding="utf-8"), "新纪要\n")
        self.assertIn("新纪要", self.request("GET", "/api/result/existing")[1]["minutes"])

    def test_bad_json_and_missing_key_return_actionable_response(self):
        self.assertEqual(self.request("POST", "/api/submit", b"not json")[0], 400)
        self.cfg["api_key"] = ""
        (self.inbox / "a.m4a").write_bytes(b"audio")
        status, result = self.request("POST", "/api/submit", {"files": ["a.m4a"]})
        self.assertEqual(status, 400)
        self.assertIn("Key", result["msg"])

    def test_rotating_shared_key_keeps_minutes_on_shared_key(self):
        self.cfg["minutes_key"] = self.cfg["api_key"]
        with patch.object(minutes, "save_config") as save:
            self.request("POST", "/api/config", {"api_key": "new-synthetic-key"})
            self.assertEqual(save.call_args.args[0]["minutes_key"], "new-synthetic-key")

    def test_regenerate_is_blocked_while_audio_job_still_owns_result(self):
        jid = self.jobs.enqueue("a.m4a", self.cfg)
        result = self.jobs.jobs[jid]["out"]
        self.assertIsNone(self.jobs.enqueue(result, self.cfg, kind="minutes"))

    def test_default_workbench_uses_tingwu_and_legacy_tools_have_separate_route(self):
        status, html = self.request("GET", "/")
        self.assertEqual(status, 200)
        self.assertIn('听悟工作台'.encode(), html)
        self.assertNotIn(b'id="apiKey"', html)
        self.assertIn(b'id="apiKey"', self.request("GET", "/legacy")[1])

    def test_session_exchange_rejects_unauthenticated_and_cross_origin_requests(self):
        with patch.object(server.TINGWU, "connect") as connect:
            self.assertEqual(self.request("POST", "/api/tingwu/session", {"cookie": "synthetic"})[0], 403)
            self.assertEqual(self.request("POST", "/api/tingwu/session", {"cookie": "synthetic"},
                {"Origin": "https://evil.test", "Authorization": "Bearer " + server.DESKTOP_TOKEN})[0], 403)
            connect.assert_not_called()

    def test_rebound_host_cannot_read_the_connected_account(self):
        self.assertEqual(self.request("GET", "/api/tingwu/status", headers={"Host": "attacker.example"})[0], 403)

    def test_cloud_delete_requires_confirmation_before_any_mutation(self):
        with patch.object(server.TINGWU, "trash") as trash:
            self.assertEqual(self.request("POST", "/api/tingwu/delete", {"id": "record1"})[0], 400)
            trash.assert_not_called()

    def test_edit_conflict_returns_409_and_keeps_dialog_payload(self):
        import tingwu
        with patch.object(server.TINGWU, "save_paragraph", side_effect=tingwu.TingwuError("已变化", conflict=True)):
            status, data = self.request("POST", "/api/tingwu/edit", {"id": "record1", "kind": "transcript", "item_id": "p1", "text": "编辑", "version": "old"})
            self.assertEqual(status, 409)
            self.assertIn("已变化", data["msg"])

    def test_health_has_application_identity_and_no_account_data(self):
        status, data = self.request("GET", "/api/health")
        self.assertEqual(status, 200)
        self.assertEqual(data["service"], "wanlu-panel")
        self.assertNotIn("cookie", data)

    def test_stale_account_revision_cannot_edit_a_cloud_record(self):
        with patch.object(server.TINGWU, "rename") as rename:
            status, _ = self.request("POST", "/api/tingwu/rename", {"id": "record1", "title": "新名称", "session_revision": -1})
            self.assertEqual(status, 409)
            rename.assert_not_called()

    def test_retry_results_are_versioned_instead_of_overwriting_previous_run(self):
        first = self.jobs.enqueue("a.m4a", self.cfg)
        with patch.object(minutes, "process"):
            self.jobs.run_next()
        second = self.jobs.enqueue("a.m4a", self.cfg)
        self.assertNotEqual(self.jobs.jobs[first]["out"], self.jobs.jobs[second]["out"])


class WatchTests(unittest.TestCase):
    def test_private_recordings_are_listed_for_tingwu_preview_app(self):
        def adb(args, **kwargs):
            if args == ["devices"]: return True, "List of devices attached\nwatch\tdevice\n"
            if "packages" in args: return True, "package:com.zcode.watchrec.preview\n"
            if "run-as" in args: return True, "-rw-rw---- 1 user user 123 2026-10-26 12:00 REC_private.m4a\n"
            return True, ""
        with patch.object(server, "run_adb", side_effect=adb), patch.object(server, "_watch_cache", {"t": 0, "data": None}):
            state = server.watch_state(dict(minutes.DEFAULTS, watch_serial="watch"))
            self.assertEqual(state["files"], [{"name": "REC_private.m4a", "size": 123, "private": True}])
    def test_coloros_watch_is_detected_after_phone_without_watch_characteristic(self):
        def adb(args, **kwargs):
            if args == ["devices"]: return True, "List of devices attached\nphone\tdevice\nwatch\tdevice\n"
            if "packages" in args and args[1] == "watch": return True, "package:com.zcode.watchrec\n"
            if "getprop" in args: return True, "nosdcard\n"
            return True, ""
        with patch.object(server, "run_adb", side_effect=adb), patch.object(server, "_watch_cache", {"t": 0, "data": None}):
            self.assertEqual(server.watch_state(dict(minutes.DEFAULTS))["serial"], "watch")

    def test_original_and_preview_use_selected_directory_and_do_not_select_watch_as_phone(self):
        def adb(args, **kwargs):
            if args == ["devices"]: return True, "List of devices attached\nwatch\tdevice\nphone\tdevice\n"
            if "packages" in args: return True, "package:com.zcode.watchrec\npackage:com.zcode.watchrec.preview\n"
            if "power" in args: return True, "Wake Locks: size=0\nSuspend Blockers"
            if "run-as" in args: return True, ""
            if "ls" in args:
                self.assertIn("com.zcode.watchrec.preview/files", args[-1])
                return True, "-rw-rw---- 1 owner group 123 2026-10-26 12:00 REC_test.m4a\n"
            return True, ""
        cfg = dict(minutes.DEFAULTS, watch_serial="watch", watch_package="com.zcode.watchrec.preview")
        with patch.object(server, "run_adb", side_effect=adb), patch.object(server, "_watch_cache", {"t": 0, "data": None}):
            state = server.watch_state(cfg)
            self.assertEqual(state["files"], [{"name": "REC_test.m4a", "size": 123}])
            self.assertEqual(state["package"], "com.zcode.watchrec.preview")
            self.assertEqual(server.phone_serial(cfg), "phone")

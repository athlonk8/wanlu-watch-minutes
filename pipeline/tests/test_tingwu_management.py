import copy
import hashlib
import json
from pathlib import Path
import sys
import tempfile
import threading
import unittest
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import tingwu
from tingwu_jobs import TingwuJobs

DOCUMENT = {"pg": [{"pi": "p1", "ui": "speaker", "sc": [
    {"id": 1, "si": 1, "bt": 1000, "et": 1400, "tc": "原文"},
    {"id": 2, "si": 1, "bt": 1400, "et": 2000, "tc": "内容"}]}]}
LAB = {"labCardsMap": {"labSummaryInfo": [{"key": "agendaSummary", "contents": [{"contentValues": [
    {"id": 123, "value": "原摘要", "title": "原章节标题"}]}]}]}}


class ManagementTests(unittest.TestCase):
    def setUp(self):
        self.client = tingwu.TingwuClient()
        self.client.account = "test-account"
        self.client.connected = True

    def test_rename_and_trash_use_current_account_and_never_permanent_delete(self):
        with patch.object(self.client, "api") as api, patch.object(self.client, "trash_list", side_effect=[{"data": []}, {"data": [{"trashId": 1}]}]):
            self.client.rename("record1", " 新标题 ")
            self.assertEqual(api.call_args.args[1]["tag"], {"showName": "新标题"})
            self.client.trash("record1")
            self.assertIs(api.call_args.args[1]["deletePermanently"], False)
            self.assertEqual(api.call_args.kwargs["account"], "test-account")
            with self.assertRaises(ValueError): self.client.rename("../invalid", "name")
            with self.assertRaises(ValueError): self.client.rename("record1", " ")

    def test_paragraph_edit_preserves_identity_speaker_and_time_range(self):
        original = copy.deepcopy(DOCUMENT)
        with patch.object(self.client, "api", return_value={"data": {"result": json.dumps(original)}}) as api:
            self.client.save_paragraph("record1", "p1", "修改后的原文", tingwu.fingerprint(original))
            action, body = api.call_args.args
            self.assertEqual(action, "saveTransDocResult")
            paragraph = json.loads(body["content"])["pg"][0]
            self.assertEqual(paragraph["pi"], "p1")
            self.assertEqual(paragraph["ui"], "speaker")
            self.assertEqual(paragraph["sc"][0]["bt"], 1000)
            self.assertEqual(paragraph["sc"][0]["et"], 2000)
            self.assertEqual(paragraph["sc"][0]["tc"], "修改后的原文")
            self.assertEqual(original, DOCUMENT)

    def test_changed_cloud_document_is_never_overwritten(self):
        with patch.object(self.client, "api", return_value={"data": {"result": json.dumps(DOCUMENT)}}) as api:
            with self.assertRaises(tingwu.TingwuError) as error:
                self.client.save_paragraph("record1", "p1", "text", "stale-version")
            self.assertTrue(error.exception.conflict)
            self.assertEqual(api.call_count, 1)

    def test_summary_edit_preserves_chapter_title_and_checks_version(self):
        item = tingwu.summary_items(LAB["labCardsMap"])[0]
        with patch.object(self.client, "api", return_value={"data": LAB}) as api:
            self.client.save_summary("record1", "agendaSummary", "123", "新摘要", item["version"])
            self.assertEqual(api.call_args.args[0], "modLabResult")
            self.assertEqual(api.call_args.args[1]["title"], "原章节标题")
            self.assertEqual(api.call_args.args[1]["value"], "新摘要")
        with patch.object(self.client, "api", return_value={"data": LAB}) as api:
            with self.assertRaises(tingwu.TingwuError):
                self.client.save_summary("record1", "agendaSummary", "123", "text", "stale")
            self.assertEqual(api.call_count, 1)

    def test_empty_summary_can_be_created_without_calling_a_model(self):
        with patch.object(self.client, "api", return_value={"data": {}}) as api:
            self.client.save_summary("record1", "fullSummary", "new", "人工纪要", tingwu.fingerprint(None))
            self.assertEqual(api.call_args.args[0], "addLabResult")
            self.assertEqual(api.call_args.args[1]["contentValues"], [{"value": "人工纪要"}])

    def test_before_edit_snapshot_stays_in_private_account_directory(self):
        with tempfile.TemporaryDirectory() as temp:
            self.client.history_root = Path(temp)
            with patch.object(self.client, "api", return_value={"data": {"result": DOCUMENT}}):
                self.client.save_paragraph("record1", "p1", "new", tingwu.fingerprint(DOCUMENT))
            saved = list((Path(temp) / "test-account/record1").glob("*.json"))
            self.assertEqual(json.loads(saved[0].read_text(encoding="utf-8")), DOCUMENT)

    @patch("tingwu.requests.get")
    def test_expired_login_clears_local_connection_and_cookie(self, get):
        get.return_value = Mock(status_code=401)
        self.client.cookie = "session=synthetic"
        with self.assertRaises(tingwu.TingwuError): self.client.connect("session=expired")
        self.assertFalse(self.client.connected)
        self.assertEqual(self.client.cookie, "")


class StreamTests(unittest.TestCase):
    @patch("tingwu.http.client.HTTPSConnection")
    def test_large_file_is_sent_in_bounded_chunks_without_reading_whole_file(self, factory):
        with tempfile.TemporaryDirectory() as temp:
            file = Path(temp) / "large.m4a"
            with file.open("wb") as target:
                target.seek(128 * 1024 * 1024 - 1); target.write(b"x")
            digest = hashlib.sha256(); largest = 0; sent = 0
            def receive(chunk):
                nonlocal largest, sent
                largest = max(largest, len(chunk)); sent += len(chunk); digest.update(chunk)
            factory.return_value.send = receive
            factory.return_value.getresponse.return_value = Mock(status=200)
            tingwu.TingwuClient().put(file, "https://bucket.oss-cn-shanghai.aliyuncs.com/large?sig=test")
            with file.open("rb") as source: expected = hashlib.file_digest(source, "sha256").digest()
            self.assertEqual(digest.digest(), expected)
            self.assertEqual(sent, file.stat().st_size)
            self.assertLessEqual(largest, 65536)

    @patch("tingwu.http.client.HTTPSConnection")
    def test_cancel_interrupts_wait_for_upload_acknowledgement(self, factory):
        waiting, release = threading.Event(), threading.Event()
        cancelled = threading.Event()
        errors = []
        connection = factory.return_value
        def response():
            waiting.set(); release.wait(3); raise OSError("synthetic disconnect")
        connection.getresponse.side_effect = response
        connection.sock.shutdown.side_effect = lambda *_: release.set()
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); (root / "REC_test.m4a").write_bytes(b"test")
            client = tingwu.TingwuClient()
            def upload():
                try: client.put(root / "REC_test.m4a", "https://b.aliyuncs.com/a", stopped=cancelled.is_set)
                except Exception as error: errors.append(error)
            thread = threading.Thread(target=upload); thread.start()
            self.assertTrue(waiting.wait(2))
            cancelled.set(); connection.sock.shutdown(2)
            thread.join(2)
            self.assertFalse(thread.is_alive())
            self.assertIsInstance(errors[0], tingwu.UploadCancelled)
            self.assertEqual((root / "REC_test.m4a").read_bytes(), b"test")


class QueueRecoveryTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.root = Path(self.temp.name)
        self.inbox = self.root / "inbox"; self.inbox.mkdir()
        (self.inbox / "REC_test.m4a").write_bytes(b"synthetic audio")
        self.client = Mock(account="account-a", connected=True)
        self.jobs = TingwuJobs(self.root / "state", self.inbox, self.client)

    def tearDown(self): self.temp.cleanup()

    def reply(self, action, payload, **kwargs):
        if action == "generatePutLink": return {"data": {"transId": "record1", "putLink": "https://b.aliyuncs.com/a", "getLink": "https://b.aliyuncs.com/a"}}
        if action == "getTransStatus": return {"data": [{"transId": "record1", "status": 0}]}
        return {"data": {}}

    def test_queued_cancel_and_retry_never_submit_until_user_resumes(self):
        jid = self.jobs.enqueue("REC_test.m4a")[0]
        self.jobs.cancel(jid); self.assertFalse(self.jobs.run_next())
        self.client.api.assert_not_called()
        self.client.api.side_effect = self.reply
        self.jobs.retry(jid); self.jobs.run_next()
        self.assertEqual(self.jobs.jobs[jid]["status"], "accepted")

    def test_network_loss_resumes_same_cloud_record_after_restart(self):
        self.client.api.side_effect = self.reply
        self.client.put.side_effect = [OSError("synthetic network loss"), None]
        jid = self.jobs.enqueue("REC_test.m4a")[0]; self.jobs.run_next()
        self.assertEqual(self.jobs.jobs[jid]["phase"], "uploading")
        restored = TingwuJobs(self.root / "state", self.inbox, self.client)
        restored.retry(jid); restored.run_next()
        self.assertEqual(restored.jobs[jid]["status"], "accepted")
        self.assertEqual([c.args[0] for c in self.client.api.call_args_list].count("generatePutLink"), 1)

    def test_uncertain_creation_reconciles_using_saved_task_uuid(self):
        self.client.api.side_effect = TimeoutError("test")
        jid = self.jobs.enqueue("REC_test.m4a")[0]; self.jobs.run_next()
        task = self.jobs.jobs[jid]["task_id"]
        self.client.api.side_effect = lambda *args, **kwargs: {"data": [{"taskId": task, "transId": "record1", "status": 1}]}
        self.assertEqual(self.jobs.reconcile(jid), "record1")
        self.assertEqual(self.jobs.jobs[jid]["status"], "accepted")
        self.assertEqual(self.client.api.call_args.args[1]["taskIds"], [task])

    def test_expired_link_requires_user_trash_before_new_record(self):
        self.client.api.side_effect = self.reply
        self.client.put.side_effect = tingwu.TingwuError("expired", expired_upload=True)
        jid = self.jobs.enqueue("REC_test.m4a")[0]; self.jobs.run_next()
        with self.assertRaises(tingwu.TingwuError): self.jobs.retry(jid)
        self.jobs.retired("record1", "trash1")
        self.jobs.retry(jid)
        self.assertEqual(self.jobs.jobs[jid]["phase"], "")
        self.assertEqual(self.jobs.jobs[jid]["previous_records"], ["record1"])

    def test_restoring_a_record_cannot_silently_create_a_duplicate(self):
        self.client.api.side_effect = self.reply
        jid = self.jobs.enqueue("REC_test.m4a")[0]; self.jobs.run_next()
        self.jobs.retired("record1", "trash1")
        self.client.trash_contains.return_value = False
        with self.assertRaises(tingwu.TingwuError): self.jobs.retry(jid)
        self.jobs.restored("trash1")
        self.assertEqual(self.jobs.jobs[jid]["status"], "accepted")
        self.assertFalse(self.jobs.jobs[jid]["remote_deleted"])

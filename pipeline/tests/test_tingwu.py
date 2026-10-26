import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import tingwu
from tingwu_jobs import TingwuJobs


def response(data, status=200):
    return Mock(status_code=status, content=json.dumps(data).encode(), json=Mock(return_value=data))


class TingwuClientTests(unittest.TestCase):
    def setUp(self):
        self.client = tingwu.TingwuClient()
        self.client.cookie = "session=synthetic"
        self.client.account = "account-test"
        self.client.connected = True

    @patch("tingwu.requests.get")
    def test_official_session_verified_without_exposing_account_or_cookie(self, get):
        get.return_value = response({"code": "0", "data": {"userId": "synthetic-user"}})
        self.assertTrue(self.client.connect("session=synthetic"))
        self.assertEqual(len(self.client.account), 64)
        self.assertNotEqual(self.client.account, "synthetic-user")
        self.assertFalse(get.call_args.kwargs["allow_redirects"])

    @patch("tingwu.requests.post")
    def test_list_uses_website_filter_and_only_returns_public_fields(self, post):
        post.return_value = response({"code": "0", "data": [{"transId": "123", "tag": {"showName": "周会"}, "status": 0, "privateUrl": "not-for-panel"}], "total": 31})
        data = self.client.tasks(2)
        self.assertEqual(data["total"], 31)
        self.assertEqual(data["tasks"][0]["title"], "周会")
        self.assertNotIn("privateUrl", data["tasks"][0])
        self.assertEqual(post.call_args.kwargs["json"]["pageNo"], 2)
        self.assertEqual(post.call_args.kwargs["json"]["filter"]["showName"], "")

    @patch("tingwu.requests.post")
    def test_title_search_does_not_include_unrelated_full_text_matches(self, post):
        post.return_value = response({"code": "0", "data": [
            {"transId": "1", "tag": {"showName": "周会"}},
            {"transId": "2", "tag": {"showName": "无关课程"}}], "total": 2})
        data = self.client.tasks(1, "周会")
        self.assertEqual(data["total"], 1)
        self.assertEqual(data["tasks"][0]["id"], "1")

    @patch("tingwu.requests.post")
    def test_result_preserves_cloud_transcript_and_summary_without_llm_calls(self, post):
        post.side_effect = [response({"code": "0", "data": {"tag": {"showName": "周会"}, "result": json.dumps({"pg": [{"ui": 1, "sc": [{"bt": 2300, "tc": "下周交付。"}]}]})}}),
                            response({"code": "0", "data": {"labCardsMap": {"labSummaryInfo": [
                                {"key": "fullSummary", "contents": [{"contentValues": [{"value": "讨论下周交付"}]}]}],
                                "labInfo": [{"key": "actionExtractor", "contents": [{"contentValues": [{"time": 2300, "value": "确认交付"}]}]}]}}})]
        result = self.client.result("123")
        self.assertIn("[00:02] 说话人1 下周交付。", result["transcript"])
        self.assertIn("讨论下周交付", result["minutes"])
        self.assertIn("确认交付", result["minutes"])
        self.assertTrue(all("tingwu.aliyun.com/api/" in call.args[0] for call in post.call_args_list))

    @patch("tingwu.requests.post")
    def test_expired_session_and_redirects_never_leak_cookies(self, post):
        post.return_value = response({}, 302)
        with self.assertRaises(tingwu.TingwuError) as error:
            self.client.tasks()
        self.assertTrue(error.exception.login_required)
        self.assertFalse(self.client.connected)
        self.assertFalse(post.call_args.kwargs["allow_redirects"])

    @patch("tingwu.requests.post")
    def test_account_switch_stops_old_upload_before_any_request(self, post):
        with self.assertRaises(tingwu.TingwuError):
            self.client.api("syncPutLink", {"transId": "123"}, account="different-account")
        post.assert_not_called()

    @patch("tingwu.http.client.HTTPSConnection")
    def test_storage_upload_is_exact_and_has_no_account_credentials(self, connection):
        wire = connection.return_value
        wire.getresponse.return_value = Mock(status=200)
        with tempfile.TemporaryDirectory() as temp:
            file = Path(temp) / "REC_test.m4a"; file.write_bytes(b"exact-audio\x00\r\n")
            self.client.put(file, "https://bucket.oss-cn-shanghai.aliyuncs.com/file?signature=synthetic")
            self.assertEqual(b"".join(call.args[0] for call in wire.send.call_args_list), file.read_bytes())
            headers = {call.args[0].lower() for call in wire.putheader.call_args_list}
            self.assertNotIn("cookie", headers)
            self.assertNotIn("authorization", headers)
            wire.close.assert_called_once()
            for url in ["http://bucket.oss.aliyuncs.com/file", "https://evil.test/file", "https://user:pass@b.aliyuncs.com/a", "https://b.oss-cn-shanghai-internal.aliyuncs.com/a"]:
                with self.assertRaises(tingwu.TingwuError): self.client.put(file, url)


class TingwuJobsTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.inbox = self.root / "inbox"; self.inbox.mkdir()
        (self.inbox / "REC_test.m4a").write_bytes(b"synthetic audio")
        self.client = Mock(account="account-test", connected=True)
        self.jobs = TingwuJobs(self.root / "data", self.inbox, self.client)

    def tearDown(self): self.temp.cleanup()

    def enqueue(self): return self.jobs.enqueue("REC_test.m4a", "周会")[0]

    def api(self, action, payload, **kwargs):
        if action == "generatePutLink": return {"data": {"transId": "123", "putLink": "https://b.aliyuncs.com/a?sig=synthetic", "getLink": "https://b.aliyuncs.com/a"}}
        if action == "getTransStatus": return {"data": [{"transId": "123", "status": 0}]}
        return {"data": {}}

    def test_uploaded_record_is_reconciled_without_duplicate_transcription(self):
        self.client.api.side_effect = self.api
        jid = self.enqueue(); self.jobs.run_next()
        self.assertEqual(self.jobs.jobs[jid]["status"], "accepted")
        self.assertNotIn("startTrans", [c.args[0] for c in self.client.api.call_args_list])
        self.assertNotIn("put_link", self.jobs.jobs[jid])
        self.assertEqual(self.jobs.enqueue("REC_test.m4a")[1], False)
        self.assertFalse(self.jobs.run_next())

    def test_uncertain_record_creation_cannot_be_blindly_retried(self):
        self.client.api.side_effect = TimeoutError("signed-secret-url")
        jid = self.enqueue(); self.jobs.run_next()
        self.assertTrue(self.jobs.jobs[jid]["needs_review"])
        with self.assertRaises(tingwu.TingwuError): self.jobs.retry(jid)
        self.assertNotIn("signed-secret-url", self.jobs.jobs[jid]["step"])

    def test_restart_requires_user_action_and_keeps_same_record(self):
        jid = self.enqueue()
        self.jobs.jobs[jid].update(phase="starting", trans_id="123")
        self.jobs.save()
        restored = TingwuJobs(self.root / "data", self.inbox, self.client)
        self.assertEqual(restored.jobs[jid]["status"], "interrupted")
        self.assertFalse(restored.run_next())
        restored.retry(jid)
        self.client.api.side_effect = self.api
        restored.run_next()
        self.assertEqual([c.args[0] for c in self.client.api.call_args_list], ["getTransStatus"])

    def test_changed_source_does_not_create_cloud_record(self):
        self.enqueue(); (self.inbox / "REC_test.m4a").write_bytes(b"changed")
        self.jobs.run_next(); self.client.api.assert_not_called()

    def test_history_and_retries_are_bound_to_the_original_account(self):
        jid = self.enqueue()
        self.client.account = "other-account"
        self.assertEqual(self.jobs.snapshot(), [])
        self.assertFalse(self.jobs.run_next())
        with self.assertRaises(tingwu.TingwuError): self.jobs.retry(jid)

    def test_public_snapshot_excludes_signed_storage_links(self):
        jid = self.enqueue()
        self.jobs.jobs[jid].update(put_link="secret-url", get_link="secret-download")
        self.assertNotIn("secret", json.dumps(self.jobs.snapshot()))

    def test_submission_race_is_reconciled_on_the_same_cloud_record(self):
        statuses = iter([3, 0])
        def api(action, payload, **kwargs):
            if action == "getTransStatus": return {"data": [{"transId": "123", "status": next(statuses)}]}
            if action == "startTrans": raise tingwu.TingwuError("已在处理")
            return self.api(action, payload)
        self.client.api.side_effect = api
        jid = self.enqueue(); self.jobs.run_next()
        self.assertEqual(self.jobs.jobs[jid]["status"], "accepted")
        self.assertEqual([c.args[0] for c in self.client.api.call_args_list].count("generatePutLink"), 1)

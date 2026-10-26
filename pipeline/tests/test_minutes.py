import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import minutes


def response(data, status=200):
    return Mock(status_code=status, json=Mock(return_value=data))


class MinutesTests(unittest.TestCase):
    def setUp(self):
        self.cfg = dict(minutes.DEFAULTS, api_key="synthetic-key", minutes_key="synthetic-key")

    @patch("minutes.requests.post")
    def test_title_and_timestamps_reach_minutes_model(self, post):
        post.return_value = response({"choices": [{"message": {"content": "## 核心要点\n完成测试 [00:01]"}}]})
        self.assertIn("完成测试", minutes.make_minutes("# 转写\n[00:01] 测试", self.cfg, title="周会"))
        prompt = post.call_args.kwargs["json"]["messages"][1]["content"]
        self.assertIn("周会", prompt)
        self.assertIn("[00:01] 测试", prompt)
        self.assertIn("时间戳", prompt)

    @patch("minutes.time.sleep")
    @patch("minutes.requests.get")
    @patch("minutes.requests.post")
    def test_provider_results_array_and_pending_are_supported(self, post, get, sleep):
        post.return_value = response({"output": {"task_id": "task-test"}})
        result = {"transcripts": [{"sentences": [{"begin_time": 0, "text": "测试"}]}]}
        get.side_effect = [response({"output": {"task_status": "PENDING"}}),
                           response({"output": {"task_status": "SUCCEEDED", "results": [
                               {"file_url": "oss://audio", "subtask_status": "SUCCEEDED", "transcription_url": "https://result.example/test"}]}}),
                           response(result)]
        self.assertEqual(minutes.transcribe("oss://audio", self.cfg), result)
        self.assertEqual(post.call_count, 1)
        self.assertEqual(post.call_args.kwargs["headers"]["X-DashScope-OssResourceResolve"], "enable")
        self.assertTrue(get.call_args_list[0].args[0].endswith("/tasks/task-test"))
        self.assertNotIn("headers", get.call_args_list[-1].kwargs)  # No provider key on result download.

    @patch("minutes.time.sleep")
    @patch("minutes.requests.get")
    @patch("minutes.requests.post")
    def test_failed_subtask_is_not_treated_as_success(self, post, get, sleep):
        post.return_value = response({"output": {"task_id": "test"}})
        get.return_value = response({"output": {"task_status": "SUCCEEDED", "results": [
            {"subtask_status": "FAILED", "code": "InvalidFile"}]}})
        with self.assertRaises(minutes.MinutesError):
            minutes.transcribe("oss://audio", self.cfg)
        self.assertEqual(get.call_count, 1)

    @patch("minutes.requests.post")
    def test_empty_or_truncated_minutes_are_rejected(self, post):
        for content, reason in [("", "stop"), ("半份纪要", "length")]:
            post.return_value = response({"choices": [{"message": {"content": content}, "finish_reason": reason}]})
            with self.assertRaises(minutes.MinutesError):
                minutes.make_minutes("测试", self.cfg)

    def test_provider_errors_do_not_leak_credentials(self):
        with self.assertRaises(minutes.MinutesError) as error:
            minutes.response_json(response({"secret": "do-not-log"}, 401), "测试")
        self.assertNotIn("do-not-log", str(error.exception))
        invalid = response(None)
        invalid.json.side_effect = ValueError("invalid")
        with self.assertRaises(minutes.MinutesError):
            minutes.response_json(invalid, "测试")

    def test_more_than_eight_speakers_and_chronological_order(self):
        sentences = [{"begin_time": i * 1000, "text": f"发言{i}", "speaker_id": i} for i in range(12)]
        md = minutes.parse_transcription({"transcripts": [{"sentences": list(reversed(sentences))}]}, "a.m4a")
        self.assertIn("说话人L(11)", md)
        self.assertLess(md.index("[00:00]"), md.index("[00:11]"))

    @patch("minutes.make_minutes", side_effect=minutes.MinutesError("测试失败"))
    @patch("minutes.upload_audio")
    def test_minutes_retry_preserves_existing_text_and_never_uploads_audio(self, upload, generate):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / "转写.md").write_text("原转写", encoding="utf-8")
            (root / "会议纪要.md").write_text("原纪要", encoding="utf-8")
            with self.assertRaises(minutes.MinutesError):
                minutes.regenerate_minutes(root, self.cfg, title="更新主题")
            upload.assert_not_called()
            self.assertEqual((root / "会议纪要.md").read_text(encoding="utf-8"), "原纪要")
            self.assertEqual((root / "转写.md").read_text(encoding="utf-8"), "原转写")

    @patch("minutes.make_minutes", side_effect=minutes.MinutesError("失败"))
    @patch("minutes.transcribe")
    @patch("minutes.upload_audio", return_value="oss://test")
    def test_full_pipeline_keeps_transcript_when_minutes_fail(self, upload, transcribe, generate):
        result = {"transcripts": [{"sentences": [{"begin_time": 0, "text": "会议内容"}]}]}
        transcribe.return_value = result
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            with self.assertRaises(minutes.MinutesError):
                minutes.process(root / "audio.m4a", self.cfg, root / "out")
            self.assertIn("会议内容", (root / "out/audio/转写.md").read_text(encoding="utf-8"))
            self.assertEqual(json.loads((root / "out/audio/raw.json").read_text(encoding="utf-8")), result)

    def test_configuration_roundtrips_percent_in_secrets(self):
        with tempfile.TemporaryDirectory() as temp, patch.object(minutes, "HERE", Path(temp)):
            cfg = dict(self.cfg, cloud_webhook="https://example.test/?v=a%20b", cloud_secret="x%y")
            minutes.save_config(cfg)
            loaded = minutes.load_config()
            self.assertEqual(loaded["cloud_webhook"], cfg["cloud_webhook"])
            self.assertEqual(loaded["cloud_secret"], "x%y")

    @patch("minutes.requests.get")
    @patch("minutes.requests.post")
    def test_upload_uses_selected_model_and_closes_source(self, post, get):
        get.return_value = response({"data": {"upload_dir": "test-dir", "oss_access_key_id": "synthetic",
            "signature": "synthetic", "policy": "synthetic", "upload_host": "https://bucket.example/upload"}})
        post.return_value = response({}, 204)
        with tempfile.TemporaryDirectory() as temp:
            file = Path(temp) / "test.m4a"; file.write_bytes(b"audio")
            url = minutes.upload_audio(file, "synthetic", model="selected-asr")
            self.assertTrue(url.startswith("oss://test-dir/"))
            self.assertEqual(get.call_args.kwargs["params"]["model"], "selected-asr")
            self.assertTrue(post.call_args.kwargs["files"][-1][1][1].closed)

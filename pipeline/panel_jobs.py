"""Durable PC-panel history. Interrupted work always needs a new user action."""
import copy
import json
from pathlib import Path
import threading
import time
import uuid

import minutes


class PanelJobs:
    def __init__(self, inbox: Path, out: Path):
        self.inbox, self.out = inbox, out
        self.file = out / ".panel-jobs.json"
        self.lock = threading.RLock()
        self.jobs = {}
        if self.file.exists():
            for job in json.loads(self.file.read_text(encoding="utf-8")):
                if job["status"] in ("queued", "running"):
                    job["status"] = "interrupted"
                    job["log"].append("面板已重启；请手动重新提交，已有转写可仅生成纪要")
                self.jobs[job["id"]] = job

    def snapshot(self):
        with self.lock:
            return [copy.deepcopy({k: v for k, v in j.items() if k != "config"})
                    for j in sorted(self.jobs.values(), key=lambda j: j["created_at"], reverse=True)]

    def _save(self):
        self.out.mkdir(parents=True, exist_ok=True)
        minutes.write_text_atomic(self.file, json.dumps(self.snapshot(), ensure_ascii=False))

    def enqueue(self, name, cfg, title="", kind="audio"):
        with self.lock:
            for job in self.jobs.values():
                same_audio = kind == "audio" and job["kind"] == "audio" and Path(name).stem == Path(job["name"]).stem
                same_result = kind == "minutes" and name == job["out"]
                if (same_audio or same_result) and job["status"] in ("queued", "running"):
                    return None
            jid = uuid.uuid4().hex
            result = name if kind == "minutes" else f"{Path(name).stem[:80]}_{time.strftime('%Y%m%d_%H%M%S')}_{jid[:6]}"
            self.jobs[jid] = {
                "id": jid, "name": name, "kind": kind, "title": title,
                "status": "queued", "log": [], "out": result,
                "created_at": time.time(), "config": copy.deepcopy(cfg),
            }
            self._save()
            return jid

    def run_next(self):
        with self.lock:
            job = next((j for j in self.jobs.values() if j["status"] == "queued"), None)
            if job is None:
                return False
            job["status"] = "running"
            self._save()

        def log(message):
            with self.lock:
                job["log"] = (job["log"] + [str(message)])[-50:]
                self._save()

        try:
            cfg = job["config"]
            if job["kind"] == "minutes":
                minutes.regenerate_minutes(self.out / job["name"], cfg, log, title=job["title"])
            else:
                if not cfg.get("api_key"):
                    raise minutes.MinutesError("请先配置百炼 API Key")
                minutes.process(self.inbox / job["name"], cfg, self.out, log,
                                title=job["title"], result_name=job["out"])
            with self.lock:
                job["status"] = "done"
            log("全部完成 ✔")
        except Exception as error:
            with self.lock:
                job["status"] = "error"
            # Network exception strings may contain API keys or signed URLs.
            log(str(error) if isinstance(error, minutes.MinutesError)
                else f"处理失败（{type(error).__name__}），请检查网络或配置；已保存的转写可仅重试纪要")
        finally:
            with self.lock:
                job.pop("config", None)
                self._save()
        return True

    def worker(self):
        while True:
            if not self.run_next():
                time.sleep(0.3)

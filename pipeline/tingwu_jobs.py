"""Account-bound upload journal; never create a new cloud record on blind retry."""
import hashlib
import json
import mimetypes
import socket
from pathlib import Path
import threading
import time
import uuid

import minutes
from tingwu import TingwuError, UploadCancelled


class TingwuJobs:
    def __init__(self, root, inbox, client):
        self.file, self.inbox, self.client = Path(root) / "tingwu-jobs.json", Path(inbox), client
        self.lock = threading.RLock()
        self.jobs = {}
        self.active = {}
        self.file.parent.mkdir(parents=True, exist_ok=True)
        if self.file.exists():
            self.jobs = json.loads(self.file.read_text(encoding="utf-8"))
            for job in self.jobs.values():
                if job["status"] in ("queued", "running", "cancelling"):
                    if job.get("cancel_requested"):
                        job.update(status="cancelled", step="上传已取消，录音保留在电脑")
                    else:
                        job.update(status="interrupted", step="上次处理已中断，确认后恢复同一任务")

    def save(self):
        with self.lock:
            minutes.write_text_atomic(self.file, json.dumps(self.jobs, ensure_ascii=False))

    def snapshot(self):
        with self.lock:
            if not self.client.connected:
                return []
            return [{key: job.get(key, "") for key in ("id", "name", "title", "status", "step", "trans_id", "needs_review", "progress", "task_id")}
                    for job in reversed(list(self.jobs.values())) if job["account"] == self.client.account]

    def enqueue(self, name, title=""):
        if not self.client.connected:
            raise TingwuError("请先连接听悟账号", True)
        account = self.client.account
        file = self.inbox / name
        with file.open("rb") as source:
            digest = hashlib.file_digest(source, "sha256").hexdigest()
        if account != self.client.account:
            raise TingwuError("听悟账号已切换，请重新确认上传账号")
        jid = hashlib.sha256((account + digest).encode()).hexdigest()
        with self.lock:
            if jid in self.jobs:
                return jid, False
            self.jobs[jid] = {"id": jid, "name": name, "title": title or file.stem,
                "account": account, "digest": digest, "size": file.stat().st_size,
                "status": "queued", "phase": "", "step": "等待上传听悟", "trans_id": "", "progress": 0}
            self.save()
            return jid, True

    def retry(self, jid):
        with self.lock:
            job = self.jobs.get(jid)
            if not job or job["account"] != self.client.account:
                raise TingwuError("当前听悟账号没有这条上传任务")
            if job["status"] not in ("error", "interrupted", "cancelled"):
                raise TingwuError("任务正在执行或已被听悟接受，无需重复上传")
            if job.get("remote_deleted"):
                if not job.get("trash_id") or not self.client.trash_contains(job["trash_id"]):
                    raise TingwuError("云端记录已恢复或回收站状态尚未确认，请先核对云端记录")
                previous = job.setdefault("previous_records", [])
                previous.append(job.get("trans_id", ""))
                for key in ("put_link", "get_link", "task_id"):
                    job.pop(key, None)
                job.update(phase="", trans_id="", needs_review=False, remote_deleted=False)
            if job.get("needs_review") or job["phase"] == "creating":
                raise TingwuError("上次创建结果未确认，请先在听悟官网检查，避免重复创建记录")
            job.update(status="queued", step="等待恢复同一条听悟记录", cancel_requested=False, progress=0)
            self.save()

    def cancel(self, jid):
        with self.lock:
            job = self.jobs.get(jid)
            if not job or job["account"] != self.client.account:
                raise TingwuError("当前账号没有这条上传任务")
            if job["status"] == "accepted":
                raise TingwuError("录音已被听悟接受；如不再需要，请在云端记录中移入回收站")
            job["cancel_requested"] = True
            running = job["status"] in ("running", "cancelling")
            job.update(status="cancelling" if running else "cancelled",
                       step="正在取消上传，等待当前请求结束" if running else "上传已取消，录音保留在电脑")
            connection = self.active.get(jid)
            self.save()
        if connection:
            try:
                if connection.sock:
                    connection.sock.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
            connection.close()

    def retired(self, trans_id, trash_id=None):
        """Called only after the user successfully moves this cloud record to trash."""
        with self.lock:
            for job in self.jobs.values():
                if job["account"] == self.client.account and job.get("trans_id") == trans_id:
                    job.update(remote_deleted=True, trash_id=trash_id, status="cancelled", cancel_requested=True,
                               step="云端记录已移入回收站，可重新上传本地原件")
            self.save()

    def restored(self, trash_id):
        with self.lock:
            for job in self.jobs.values():
                if job["account"] == self.client.account and str(job.get("trash_id")) == str(trash_id):
                    job.update(remote_deleted=False, needs_review=False, trash_id=None, cancel_requested=False,
                               status="accepted" if job["phase"] == "accepted" else "interrupted",
                               step="云端记录已恢复，可核对原任务状态")
            self.save()

    def reconcile(self, jid):
        with self.lock:
            job = self.jobs.get(jid)
            if not job or job["account"] != self.client.account or job["status"] not in ("error", "interrupted", "cancelled"):
                raise TingwuError("该任务当前不需要核对")
            task_id, trans_id, account = job.get("task_id"), job.get("trans_id"), job["account"]
        payload = {"preview": 1, "userId": ""}
        if trans_id:
            payload["transIds"] = [trans_id]
        elif task_id:
            payload["taskIds"] = [task_id]
        else:
            raise TingwuError("没有可核对的云端任务，可直接恢复上传")
        records = self.client.api("getTransStatus", payload, account=account).get("data") or []
        record = next((r for r in records if (trans_id and str(r.get("transId")) == trans_id)
                       or (not trans_id and str(r.get("taskId")) == task_id)), None)
        if not record:
            raise TingwuError("尚未找到确定匹配的云端记录，请稍后核对；不会自动新建副本")
        with self.lock:
            job["trans_id"] = str(record["transId"])
            if record.get("status") in (0, 1, 2):
                job.update(status="accepted", phase="accepted", needs_review=False, remote_deleted=False, trash_id=None,
                           step="已确认同一条听悟记录，处理状态请看右侧")
                job.pop("put_link", None); job.pop("get_link", None)
            else:
                job.update(needs_review=True, step="已定位云端记录；请在官网继续，或移入回收站后重新上传")
            self.save()
        return job["trans_id"]

    def run_next(self):
        if not self.client.connected:
            return False
        with self.lock:
            job = next((j for j in self.jobs.values() if j["status"] == "queued" and j["account"] == self.client.account), None)
            if not job:
                return False
            job["status"] = "running"
            job["last_progress"] = 0
            self.save()

        def update(step, phase=None):
            with self.lock:
                job["step"] = step
                if phase is not None:
                    job["phase"] = phase
                self.save()

        def api(action, payload):
            ensure_running()
            return self.client.api(action, payload, account=job["account"])

        def stopped():
            return bool(job.get("cancel_requested"))

        def ensure_running():
            if stopped():
                raise UploadCancelled("上传已取消，录音保留在电脑")
            if self.client.account != job["account"]:
                raise TingwuError("账号已切换，连接原听悟账号后可恢复上传")

        def progress(sent, total):
            with self.lock:
                job["progress"] = min(99, int(sent * 100 / total))
                now = time.monotonic()
                if now - job.get("last_progress", 0) > 0.5 or sent == total:
                    job["last_progress"] = now
                    update(f"正在上传 {job['progress']}%")

        def active(connection):
            with self.lock:
                if connection is None:
                    self.active.pop(job["id"], None)
                else:
                    self.active[job["id"]] = connection

        def cloud_status():
            data = api("getTransStatus", {"userId": "", "transIds": [job["trans_id"]], "preview": 1}).get("data") or []
            return next((r.get("status", -1) for r in data if str(r.get("transId")) == job["trans_id"]), -1)

        try:
            ensure_running()
            file = self.inbox / job["name"]
            with file.open("rb") as source:
                digest = hashlib.file_digest(source, "sha256").hexdigest()
            if digest != job["digest"]:
                raise TingwuError("本地录音已变化，已停止处理；请重新导入文件")
            if job["phase"] == "creating":
                raise TingwuError("请先在听悟官网核对上次创建结果")
            if not job["phase"]:
                job["task_id"] = str(uuid.uuid4())
                update("正在建立听悟记录", "creating")
                mime = mimetypes.guess_type(file.name)[0] or "application/octet-stream"
                tag = {"showName": job["title"], "fileFormat": file.suffix.lstrip('.'), "fileType": "local",
                       "lang": "cn", "roleSplitNum": 0, "translateSwitch": "0", "transTargetValue": "",
                       "originalTag": '{"isVideo":false}', "client": "web"}
                data = api("generatePutLink", {"taskId": job["task_id"], "useSts": 0, "fileSize": file.stat().st_size,
                    "dirId": 0, "fileContentType": mime, "tag": tag}).get("data") or {}
                if not all(data.get(k) for k in ("putLink", "getLink", "transId")):
                    raise TingwuError("听悟上传协议发生变化，请先到官网核对记录")
                job.update(put_link=data["putLink"], get_link=data["getLink"], trans_id=str(data["transId"]))
                update("正在上传录音", "uploading")
            if job["phase"] == "uploading":
                ensure_running()
                self.client.put(file, job["put_link"], progress=progress, stopped=stopped, active=active)
                job["progress"] = 100
                update("听悟正在确认录音", "uploaded")
            if job["phase"] == "uploaded":
                api("syncPutLink", {"fileLink": job["get_link"], "fileSize": job["size"], "transId": job["trans_id"]})
                update("正在确认听悟处理状态", "synced")
            if job["phase"] in ("synced", "starting"):
                status = cloud_status()
                if status == 3:
                    update("正在提交听悟转写", "starting")
                    try:
                        api("startTrans", {"userId": "", "transIds": [job["trans_id"]],
                            "tag": {"lang": "cn", "roleSplitNum": 0, "translateSwitch": "0"}})
                    except TingwuError:
                        if cloud_status() not in (0, 1, 2):
                            raise
                elif status not in (0, 1, 2):
                    raise TingwuError("听悟状态尚未确认，请稍后恢复同一任务")
                if job.get("remote_deleted"):
                    raise UploadCancelled("云端记录已移入回收站，上传已停止")
                job.pop("put_link", None); job.pop("get_link", None)
                with self.lock:
                    job["status"] = "accepted"
                update("已上传听悟；转写和纪要进度请看右侧云端任务", "accepted")
        except Exception as error:
            with self.lock:
                cancelled = stopped() or isinstance(error, UploadCancelled)
                job["status"] = "cancelled" if cancelled else "error"
                job["needs_review"] = job["phase"] == "creating" or getattr(error, "expired_upload", False)
            update("上传已取消，录音保留在电脑" if cancelled else str(error) if isinstance(error, TingwuError)
                   else "网络或存储暂不可用，录音保留在电脑，可恢复同一任务")
        finally:
            job.pop("last_progress", None)
            active(None)
        return True

    def worker(self):
        while True:
            if not self.run_next():
                time.sleep(0.5)

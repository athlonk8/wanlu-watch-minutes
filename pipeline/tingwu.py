"""Experimental compatibility with the ordinary Tingwu website, not OpenAPI."""
import hashlib
import copy
import http.client
import json
import mimetypes
import re
import secrets
import threading
import time
from pathlib import Path
from urllib.parse import urlsplit

import requests

ORIGIN = "https://tingwu.aliyun.com"
USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36"
TRANS_ID = re.compile(r"[A-Za-z0-9_-]{1,100}\Z")
ROUTES = {
    "getTransList": "/api/trans/request?getTransList&c=web",
    "getTransStatus": "/api/trans/request?getTransStatus&c=web",
    "getTransResult": "/api/trans/getTransResult?c=web",
    "getAllLabInfo": "/api/lab/getAllLabInfo?c=web",
    "generatePutLink": "/api/trans/request?generatePutLink&c=web",
    "syncPutLink": "/api/trans/request?syncPutLink&c=web",
    "startTrans": "/api/trans/request?startTrans&c=web",
    "syncTransTag": "/api/tag/request?syncTransTag&c=web",
    "delTrans": "/api/trans/request?delTrans&c=web",
    "getTrashList": "/api/trash/request?getTrashList&c=web",
    "revertTrashes": "/api/trash/request?revertTrashes&c=web",
    "saveTransDocResult": "/api/doc/request?saveTransDocResult&c=web",
    "modLabResult": "/api/lab/request?modLabResult&c=web",
    "addLabResult": "/api/lab/request?addLabResult&c=web",
}


class TingwuError(Exception):
    def __init__(self, message, login_required=False, *, expired_upload=False, conflict=False):
        super().__init__(message)
        self.login_required = login_required
        self.expired_upload = expired_upload
        self.conflict = conflict


class UploadCancelled(TingwuError):
    pass


def fingerprint(data):
    return hashlib.sha256(json.dumps(data, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def check_id(value):
    if not isinstance(value, str) or not TRANS_ID.fullmatch(value):
        raise ValueError("Invalid recording ID")
    return value


def decode(response):
    if response.status_code in (301, 302, 303, 307, 308, 401, 403):
        raise TingwuError("听悟登录已过期，请重新打开官方登录窗口", True)
    if response.status_code != 200:
        raise TingwuError(f"听悟暂时不可用（HTTP {response.status_code}）")
    if len(response.content) > 20 * 1024 * 1024:
        raise TingwuError("听悟返回内容过大")
    try:
        data = response.json()
    except ValueError:
        raise TingwuError("听悟响应格式发生变化，请稍后重试") from None
    if not isinstance(data, dict):
        raise TingwuError("听悟响应格式异常")
    code = str(data.get("code", ""))
    if code in ("CMN.NotLogin", "SHA.NotLogin", "TRS.NeedLogin"):
        raise TingwuError("请先登录通义听悟", True)
    if code not in ("0", "200"):
        raise TingwuError("听悟未接受操作，请检查账号额度或到官网核对该记录")
    return data


class TingwuClient:
    def __init__(self):
        self.lock = threading.RLock()
        self.cookie = ""
        self.account = ""
        self.connected = False
        self.revision = secrets.randbits(48)
        self.history_root = None

    def backup(self, trans_id, kind, data, account=None):
        if self.history_root:
            directory = Path(self.history_root) / (account or self.account) / check_id(trans_id)
            directory.mkdir(parents=True, exist_ok=True)
            (directory / f"{time.time_ns()}-{kind}.json").write_text(json.dumps(data, ensure_ascii=False), encoding="utf-8")

    def headers(self, cookie=None):
        with self.lock:
            value = self.cookie if cookie is None else cookie
        if not value:
            raise TingwuError("请先通过官方窗口登录通义听悟", True)
        return {"Cookie": value, "User-Agent": USER_AGENT, "Referer": ORIGIN + "/home",
                "Origin": ORIGIN, "Accept": "application/json"}

    def connect(self, cookie):
        try:
            return self._connect(cookie)
        except TingwuError as error:
            if error.login_required:
                with self.lock:
                    if self.connected:
                        self.revision += 1
                    self.connected = False
                    self.cookie = ""
            raise

    def _connect(self, cookie):
        if not isinstance(cookie, str) or len(cookie) > 64000 or any(c in cookie for c in "\r\n"):
            raise TingwuError("登录会话无效", True)
        response = requests.get(ORIGIN + "/api/tingwu/account/info?isLogin&c=web",
                                headers=self.headers(cookie), timeout=20, allow_redirects=False)
        data = decode(response).get("data") or {}
        user = str(data.get("userId") or "")
        if not user:
            raise TingwuError("请在官方窗口完成扫码登录", True)
        with self.lock:
            self.cookie = cookie
            account = hashlib.sha256(user.encode()).hexdigest()
            if account != self.account:
                self.revision += 1
            self.account = account
            self.connected = True
        return True

    def api(self, action, payload=None, account=None):
        if action not in ROUTES:
            raise ValueError("Unsupported action")
        try:
            with self.lock:
                if account is not None and account != self.account:
                    raise TingwuError("听悟账号已切换，请切回提交任务时的账号")
                headers = self.headers()
            return decode(requests.post(ORIGIN + ROUTES[action],
                headers=headers, json=dict(payload or {}, action=action, version="1.0"),
                timeout=45, allow_redirects=False))
        except TingwuError as error:
            if error.login_required:
                with self.lock:
                    self.connected = False
            raise
        except requests.RequestException:
            raise TingwuError("连接听悟失败，请检查网络后重试") from None

    def tasks(self, page=1, search=""):
        payload = {"userId": "", "filter": {
            "status": [0, 1, 2, 3, 4], "fileTypes": [], "showName": "",
            "beginTime": "", "endTime": "", "read": "", "lang": "", "client": ""},
            "preview": 1, "pageNo": 1 if search else page, "pageSize": 1000 if search else 30}
        data = self.api("getTransList", payload)
        records = data.get("data") or []
        if not isinstance(records, list):
            raise TingwuError("听悟任务列表格式已变化")
        total = int(data.get("total", len(records)))
        if search:
            # Website search can match transcript content; the panel searches record titles.
            while len(records) < total:
                payload["pageNo"] += 1
                more = self.api("getTransList", payload).get("data") or []
                if not more:
                    break
                records.extend(more)
            term = search.casefold()
            records = [r for r in records if term in str((r.get("tag") or {}).get("showName") or r.get("showName") or "").casefold()]
            total = len(records)
            records = records[(page - 1) * 30:page * 30]
        return {"tasks": [{"id": str(r.get("transId", "")),
                           "title": (r.get("tag") or {}).get("showName") or r.get("showName") or "未命名记录",
                           "status": r.get("status"), "duration": r.get("duration"),
                           "time": r.get("showTime", "")} for r in records],
                "total": total, "page": page}

    def result(self, trans_id):
        check_id(trans_id)
        account = self.account
        data = self.api("getTransResult", {"transId": trans_id}, account=account).get("data") or {}
        lab_error = ""
        try:
            lab = self.api("getAllLabInfo", {"transId": trans_id, "content": ["labInfo", "labSummaryInfo"]}, account=account).get("data") or {}
        except TingwuError as error:
            lab, lab_error = {}, str(error)
        document = data.get("result") or {}
        if isinstance(document, str):
            try:
                document = json.loads(document)
            except ValueError:
                document = {}
        if not isinstance(document, dict):
            raise TingwuError("听悟转写格式发生变化，请到官网查看此记录")
        title = (data.get("tag") or {}).get("showName") or "听悟记录"
        transcript = transcript_md(document, title)
        summary = summary_md(lab.get("labCardsMap") or {})
        return {"id": trans_id, "title": title, "transcript": transcript, "minutes": summary,
                "edit": {"document_version": fingerprint(document), "paragraphs": paragraphs(document),
                         "empty_summary_version": fingerprint(None),
                         "summary_items": summary_items(lab.get("labCardsMap") or {})},
                "notice": lab_error, "raw": json.dumps({"document": document, "lab": lab}, ensure_ascii=False, indent=2)}

    def rename(self, trans_id, title):
        check_id(trans_id)
        if not isinstance(title, str) or not title.strip() or len(title) > 200:
            raise ValueError("记录名称需要 1 到 200 个字符")
        self.api("syncTransTag", {"transId": trans_id, "tag": {"showName": title.strip()}}, account=self.account)

    def trash(self, trans_id):
        before = {str(item.get("trashId")) for item in self.trash_list().get("data") or []}
        self.api("delTrans", {"userId": "", "transIds": [check_id(trans_id)], "deletePermanently": False}, account=self.account)
        for _ in range(3):
            created = [item for item in self.trash_list().get("data") or [] if str(item.get("trashId")) not in before]
            if len(created) == 1:
                return str(created[0]["trashId"])
            time.sleep(0.4)
        return None

    def trash_list(self, page=1):
        data = self.api("getTrashList", {"pageNo": page, "pageSize": 30, "filter": {"showNameKeyWord": ""}})
        return data

    def restore(self, trash_id):
        self.api("revertTrashes", {"trashIds": [check_id(trash_id)]}, account=self.account)

    def trash_contains(self, trash_id):
        page = 1
        while True:
            data = self.trash_list(page)
            records = data.get("data") or []
            if any(str(item.get("trashId")) == str(trash_id) for item in records):
                return True
            if not records or page * 30 >= int(data.get("total", len(records))):
                return False
            page += 1

    def save_paragraph(self, trans_id, paragraph_id, text, base_version):
        check_id(trans_id)
        if not isinstance(text, str) or len(text) > 200000:
            raise ValueError("段落内容过长")
        account = self.account
        response = self.api("getTransResult", {"transId": trans_id}, account=account).get("data") or {}
        original = response.get("result") or {"pg": []}
        if isinstance(original, str):
            original = json.loads(original)
        if fingerprint(original) != base_version:
            raise TingwuError("原文已被其他设备修改，请刷新后再编辑", conflict=True)
        document = copy.deepcopy(original)
        matches = [p for p in document.get("pg", []) if str(p.get("pi")) == str(paragraph_id)]
        if paragraph_id == "new" and not document.get("pg"):
            paragraph = {"pi": "1", "ui": "0", "sc": [{"id": 1, "si": 1, "bt": 0,
                "et": max(1000, int(response.get("duration") or 1) * 1000), "tc": text}]}
            document["pg"] = [paragraph]
        elif len(matches) == 1 and matches[0].get("sc"):
            paragraph = matches[0]
            first, last = paragraph["sc"][0], paragraph["sc"][-1]
            word = dict(first, tc=text, et=last.get("et", first.get("et", 0)))
            paragraph["sc"] = [word]
        else:
            raise ValueError("找不到要修改的段落")
        self.backup(trans_id, "transcript", original, account=account)
        self.api("saveTransDocResult", {"transId": trans_id, "content": json.dumps(document, ensure_ascii=False)}, account=account)

    def save_summary(self, trans_id, key, item_id, text, base_version):
        check_id(trans_id)
        if key not in SUMMARY_HEADINGS or not isinstance(text, str) or len(text) > 200000:
            raise ValueError("纪要参数无效")
        account = self.account
        lab = self.api("getAllLabInfo", {"transId": trans_id, "content": ["labInfo", "labSummaryInfo"]}, account=account).get("data") or {}
        items = summary_items(lab.get("labCardsMap") or {})
        item = next((i for i in items if i["key"] == key and i["id"] == str(item_id)), None)
        if item_id == "new" and not any(i["key"] == key for i in items):
            if base_version != fingerprint(None):
                raise TingwuError("纪要状态已变化，请刷新", conflict=True)
            self.api("addLabResult", {"transId": trans_id, "key": key, "contentValues": [{"value": text}]}, account=account)
            return
        if not item or item["version"] != base_version:
            raise TingwuError("该项纪要已被其他设备修改，请刷新后再编辑", conflict=True)
        self.backup(trans_id, "summary", lab, account=account)
        payload = {"transId": trans_id, "key": key, "id": item_id, "value": text}
        if item.get("title") is not None:
            payload["title"] = item["title"]
        self.api("modLabResult", payload, account=account)

    def put(self, path, signed_url, progress=lambda sent, total: None, stopped=lambda: False, active=lambda connection: None):
        target = urlsplit(signed_url)
        if target.scheme != "https" or target.username or target.fragment or target.port not in (None, 443) or not (target.hostname or "").endswith(".aliyuncs.com") or "-internal." in target.hostname:
            raise TingwuError("听悟返回的存储地址无效")
        if stopped():
            raise UploadCancelled("上传已取消，录音保留在电脑")
        size = path.stat().st_size
        if size <= 0:
            raise TingwuError("不能上传空文件")
        connection = http.client.HTTPSConnection(target.hostname, target.port or 443, timeout=60)
        active(connection)
        try:
            destination = target.path or "/"
            if target.query:
                destination += "?" + target.query
            connection.putrequest("PUT", destination)
            connection.putheader("Content-Length", str(size))
            connection.putheader("Content-Type", mimetypes.guess_type(path.name)[0] or "application/octet-stream")
            connection.endheaders()
            sent = 0
            with path.open("rb") as source:
                while sent < size:
                    if stopped():
                        raise UploadCancelled("上传已取消，录音保留在电脑")
                    chunk = source.read(min(65536, size - sent))
                    if not chunk:
                        raise TingwuError("录音文件已变化，停止上传")
                    connection.send(chunk)
                    sent += len(chunk)
                    progress(sent, size)
            response = connection.getresponse()
            if response.status == 403:
                raise TingwuError("上传地址已过期，请核对云端记录后重新获取地址", expired_upload=True)
            if not 200 <= response.status < 300:
                raise TingwuError("听悟存储尚未确认上传，可恢复同一任务")
            if stopped():
                raise UploadCancelled("上传已取消，录音保留在电脑")
        except (OSError, http.client.HTTPException):
            if stopped():
                raise UploadCancelled("上传已取消，录音保留在电脑") from None
            raise TingwuError("上传连接中断，录音保留在电脑，可恢复同一任务") from None
        finally:
            active(None)
            connection.close()


def timestamp(ms):
    seconds = int(float(ms or 0) / 1000)
    return f"{seconds // 60:02d}:{seconds % 60:02d}"


def transcript_md(document, title):
    lines = []
    for paragraph in document.get("pg", []):
        words = paragraph.get("sc") or []
        text = "".join(str(w.get("tc", "")) for w in words)
        if text:
            speaker = paragraph.get("ui", "")
            lines.append(f"[{timestamp(words[0].get('bt'))}] 说话人{speaker} {text}")
    return f"# {title}\n\n" + "\n\n".join(lines) if lines else ""


def paragraphs(document):
    return [{"id": str(p.get("pi")), "speaker": str(p.get("ui", "")),
             "start": timestamp((p.get("sc") or [{}])[0].get("bt")),
             "text": "".join(str(w.get("tc", "")) for w in p.get("sc") or [])}
            for p in document.get("pg", []) if p.get("sc")]


SUMMARY_HEADINGS = {"fullSummary": "全文概要", "agendaSummary": "章节摘要", "roleSummary": "发言总结",
                    "actionExtractor": "待办事项", "keyWordsExtractor": "关键词", "titleGenerator": "章节标题"}


def summary_items(cards):
    items = []
    for group in ("labSummaryInfo", "labInfo"):
        for card in cards.get(group, []):
            key = card.get("key")
            if key not in SUMMARY_HEADINGS:
                continue
            for content in card.get("contents") or []:
                for value in content.get("contentValues") or []:
                    if "id" in value and isinstance(value.get("value"), str):
                        items.append({"id": str(value["id"]), "key": key, "label": SUMMARY_HEADINGS[key],
                                      "title": value.get("title"),
                                      "text": value["value"], "version": fingerprint(value)})
    return items


def summary_md(cards):
    headings = SUMMARY_HEADINGS
    sections = []
    for group in ("labSummaryInfo", "labInfo"):
        for card in cards.get(group, []):
            key = card.get("key")
            if key not in headings:
                continue
            lines = []
            for content in card.get("contents") or []:
                for value in content.get("contentValues") or []:
                    text = value.get("value")
                    if isinstance(text, str) and text.strip():
                        prefix = f"[{timestamp(value['time'])}] " if "time" in value else ""
                        lines.append(prefix + text.strip())
            if lines:
                sections.append("## " + headings[key] + "\n\n" + "\n\n".join(lines))
    return "\n\n".join(sections)

# -*- coding: utf-8 -*-
"""
腕录 → 会议纪要 核心流水线(可被 server.py / CLI 共用)
录音文件 → 阿里云百炼 paraformer-v2 转写(说话人分离) → LLM 生成会议纪要
只需一个 DASHSCOPE_API_KEY(https://bailian.console.aliyun.com → API-KEY)。

CLI 用法:
    python minutes.py                 # 处理 ../inbox 下所有音频
    python minutes.py 文件1.m4a       # 处理指定文件
    python minutes.py --no-minutes    # 只转写不生成纪要
    python minutes.py --mock x.json   # 离线解析转写结果JSON(无需API)
"""
import argparse
import configparser
import json
import os
import sys
import time
import uuid
from pathlib import Path

import requests

HERE = Path(__file__).resolve().parent
DASHSCOPE = "https://dashscope.aliyuncs.com"
AUDIO_EXT = {".m4a", ".mp3", ".wav", ".aac", ".flac", ".ogg", ".opus", ".amr", ".wma", ".mp4"}


class MinutesError(Exception):
    pass


# ---------------------------------------------------------------- 配置

DEFAULTS = {
    "api_key": "",
    "asr_model": "paraformer-v2",
    "language_hints": "zh,en",
    "diarization": True,
    "minutes_enabled": True,
    "minutes_base": "https://dashscope.aliyuncs.com/compatible-mode/v1",
    "minutes_model": "qwen-plus",
    "minutes_key": "",
    "watch_serial": "",
    "watch_addr": "",
    "watch_package": "",
    "cloud_provider": "",          # "" 不启用 | dingtalk | feishu | custom
    "cloud_webhook": "",
    "cloud_secret": "",
    "phone_serial": "",
    "phone_share": False,
}


def load_config() -> dict:
    cfg = configparser.ConfigParser(interpolation=None)
    cfg.read(config_path(), encoding="utf-8")
    out = dict(DEFAULTS)
    env_key = os.environ.get("DASHSCOPE_API_KEY", "")
    if cfg.has_option("api", "dashscope_api_key"):
        out["api_key"] = cfg.get("api", "dashscope_api_key").strip()
    out["api_key"] = out["api_key"] or env_key
    out["asr_model"] = cfg.get("asr", "model", fallback=out["asr_model"])
    out["language_hints"] = cfg.get("asr", "language_hints", fallback=out["language_hints"])
    out["diarization"] = cfg.get("asr", "diarization", fallback="true").lower() != "false"
    out["minutes_enabled"] = cfg.get("minutes", "enabled", fallback="true").lower() != "false"
    out["minutes_base"] = cfg.get("minutes", "base_url", fallback=out["minutes_base"]).rstrip("/")
    out["minutes_model"] = cfg.get("minutes", "model", fallback=out["minutes_model"])
    out["minutes_key"] = cfg.get("minutes", "api_key", fallback="").strip() or out["api_key"]
    if cfg.has_section("gui"):
        out["watch_serial"] = cfg.get("gui", "watch_serial", fallback="")
        out["watch_addr"] = cfg.get("gui", "watch_addr", fallback="")
        out["watch_package"] = cfg.get("gui", "watch_package", fallback="")
    if cfg.has_section("cloud"):
        out["cloud_provider"] = cfg.get("cloud", "provider", fallback="").strip()
        out["cloud_webhook"] = cfg.get("cloud", "webhook", fallback="").strip()
        out["cloud_secret"] = cfg.get("cloud", "secret", fallback="").strip()
    if cfg.has_section("phone"):
        out["phone_serial"] = cfg.get("phone", "serial", fallback="")
        out["phone_share"] = cfg.get("phone", "share", fallback="false").lower() == "true"
    return out


def save_config(cfg: dict):
    cp = configparser.ConfigParser(interpolation=None)
    cp["api"] = {"dashscope_api_key": cfg.get("api_key", "")}
    cp["asr"] = {
        "model": cfg.get("asr_model", "paraformer-v2"),
        "language_hints": cfg.get("language_hints", "zh,en"),
        "diarization": "true" if cfg.get("diarization", True) else "false",
    }
    cp["minutes"] = {
        "enabled": "true" if cfg.get("minutes_enabled", True) else "false",
        "base_url": cfg.get("minutes_base", ""),
        "model": cfg.get("minutes_model", ""),
        "api_key": "" if cfg.get("minutes_key", "") == cfg.get("api_key", "") else cfg.get("minutes_key", ""),
    }
    cp["gui"] = {
        "watch_serial": cfg.get("watch_serial", ""),
        "watch_addr": cfg.get("watch_addr", ""),
        "watch_package": cfg.get("watch_package", ""),
    }
    cp["cloud"] = {
        "provider": cfg.get("cloud_provider", ""),
        "webhook": cfg.get("cloud_webhook", ""),
        "secret": cfg.get("cloud_secret", ""),
    }
    cp["phone"] = {
        "serial": cfg.get("phone_serial", ""),
        "share": "true" if cfg.get("phone_share") else "false",
    }
    from io import StringIO
    content = StringIO()
    cp.write(content)
    target = config_path()
    target.parent.mkdir(parents=True, exist_ok=True)
    write_text_atomic(target, content.getvalue())


def config_path():
    return Path(os.environ["WANLU_DATA_DIR"]) / "config.ini" if os.environ.get("WANLU_DATA_DIR") else HERE / "config.ini"


def die(msg):
    raise MinutesError(msg)


def response_json(response, action):
    """Return provider JSON without putting credentials or signed URLs in logs."""
    if not 200 <= response.status_code < 300:
        die(f"{action}失败 HTTP {response.status_code}，请检查密钥、模型和服务额度")
    try:
        data = response.json()
    except ValueError:
        die(f"{action}返回了无效 JSON")
    if not isinstance(data, dict):
        die(f"{action}返回格式异常")
    return data


def write_text_atomic(path: Path, text: str):
    temp = path.with_name(f".{path.name}.{uuid.uuid4().hex}.tmp")
    try:
        temp.write_text(text, encoding="utf-8")
        temp.replace(path)
    finally:
        temp.unlink(missing_ok=True)

# ---------------------------------------------------------------- 1. 上传本地音频 → oss:// 临时URL(48h)

def upload_audio(path: Path, api_key: str, progress=print, model="paraformer-v2") -> str:
    r = requests.get(
        f"{DASHSCOPE}/api/v1/uploads",
        params={"action": "getPolicy", "model": model},
        headers={"Authorization": f"Bearer {api_key}"},
        timeout=30,
    )
    data = response_json(r, "获取上传凭证").get("data", {})
    try:
        key = data["upload_dir"].rstrip("/") + "/" + uuid.uuid4().hex + path.suffix.lower()
        for field in ("oss_access_key_id", "signature", "policy", "upload_host"):
            if not data.get(field):
                raise KeyError(field)
    except KeyError:
        die("上传凭证缺少必要字段")

    form = [
        ("OSSAccessKeyId", (None, data["oss_access_key_id"])),
        ("Signature", (None, data["signature"])),
        ("policy", (None, data["policy"])),
        ("key", (None, key)),
        ("x-oss-object-acl", (None, data.get("x_oss_object_acl", "public-read"))),
        ("x-oss-forbid-overwrite", (None, data.get("x_oss_forbid_overwrite", "true"))),
    ]
    with path.open("rb") as source:
        r2 = requests.post(data["upload_host"], files=form + [
            ("file", (path.name, source, "application/octet-stream")),
        ], timeout=600)
    if r2.status_code not in (200, 204):
        die(f"上传 OSS 失败 HTTP {r2.status_code}")
    progress("音频已上传至临时存储")
    return f"oss://{key}"

# ---------------------------------------------------------------- 2. 提交转写任务并轮询

def transcribe(oss_url: str, cfg: dict, progress=print) -> dict:
    body = {
        "model": cfg["asr_model"],
        "input": {"file_urls": [oss_url]},
        "parameters": {
            "language_hints": [s.strip() for s in cfg["language_hints"].split(",") if s.strip()],
            "diarization_enabled": bool(cfg["diarization"]),
            "disfluency_removal_enabled": True,
        },
    }
    r = requests.post(
        f"{DASHSCOPE}/api/v1/services/audio/asr/transcription",
        headers={
            "Authorization": f"Bearer {cfg['api_key']}",
            "Content-Type": "application/json",
            "X-DashScope-Async": "enable",
            "X-DashScope-OssResourceResolve": "enable",
        },
        json=body,
        timeout=60,
    )
    j = response_json(r, "提交转写任务")
    if "task_id" not in j.get("output", {}):
        die("提交转写任务失败，服务未返回任务编号")
    task_id = j["output"]["task_id"]
    progress(f"转写任务 {task_id} 已提交")

    deadline = time.time() + 40 * 60
    while time.time() < deadline:
        time.sleep(8)
        q = response_json(requests.get(
            f"{DASHSCOPE}/api/v1/tasks/{task_id}",
            headers={"Authorization": f"Bearer {cfg['api_key']}"},
            timeout=30,
        ), "查询转写任务")
        out = q.get("output", {})
        status = out.get("task_status", "")
        if status == "SUCCEEDED":
            # Paraformer returns per-file results, even for a single recording.
            results = out.get("results") or [out]
            item = next((r for r in results if r.get("file_url") == oss_url), results[0])
            if item.get("subtask_status") == "FAILED":
                die("音频转写失败，请检查音频格式、内容和服务额度")
            url = item.get("transcription_url")
            if not url:
                die("任务成功但没有返回转写下载地址")
            progress("转写完成,下载结果…")
            res = requests.get(url, timeout=120)
            return response_json(res, "下载转写结果")
        if status in ("FAILED", "CANCELED") or out.get("subtask_status") == "FAILED":
            die("转写失败或已取消，请检查音频和服务额度")
        if status not in ("PENDING", "RUNNING"):
            die("转写服务返回了未知任务状态")
        progress(f"状态 {status} …")
    die("转写超时(40分钟)")

# ---------------------------------------------------------------- 3. 解析转写 → Markdown

def ms2ts(ms):
    s = int(ms // 1000)
    return f"{s // 60:02d}:{s % 60:02d}"


def parse_transcription(result: dict, src_name: str) -> str:
    sentences = []
    for t in result.get("transcripts", []):
        sentences.extend(t.get("sentences", []))
    if not sentences:
        die("录音中未识别到有效语音，原始结果已保留")

    sentences.sort(key=lambda s: s.get("begin_time", 0))

    speaker_ids = []
    for s in sentences:
        if s.get("speaker_id") not in speaker_ids:
            speaker_ids.append(s.get("speaker_id"))
    label = {}
    for i, spk in enumerate(speaker_ids):
        tag = chr(65 + i) if i < 26 else str(i + 1)
        label[spk] = f"说话人{tag}({spk})" if len(speaker_ids) > 1 else ""
    lines = []
    for s in sentences:
        who = label[s.get("speaker_id")]
        prefix = f"[{ms2ts(s['begin_time'])}] {who}" if who else f"[{ms2ts(s['begin_time'])}]"
        lines.append(f"{prefix} {s['text'].strip()}")

    dur = result.get("properties", {}).get("original_duration_in_milliseconds", 0)
    head = [f"# 转写: {src_name}", "", f"- 时长: {ms2ts(dur) if dur else '未知'}",
            f"- 句数: {len(sentences)}", f"- 说话人数: {len(speaker_ids)}", "", "---", ""]
    return "\n".join(head + lines) + "\n"

# ---------------------------------------------------------------- 4. LLM 生成会议纪要

SYSTEM_PROMPT = "你是一名专业会议秘书,擅长把会议转写整理成结构清晰、忠于原文的会议纪要。禁止编造未提及的内容。"

USER_TMPL = """请把下面的会议转写(带时间戳和说话人)整理成 Markdown 会议纪要,包含以下章节:
## 会议主题
## 参会人(根据说话人列出)
## 核心要点(分条,尽量具体)
## 决议事项
## 待办事项(表格: 事项 | 负责人 | 期限 | 备注;未提及的填"待定")
## 风险与待跟进

要求:忠于原文;过滤寒暄和无意义内容;保留数字、日期、金额、人名等关键信息。
为核心要点、决议和待办标注原文时间戳，便于回查。不要把说话人标签猜测成真实姓名。
没有明确决议时写“未明确”；负责人、期限或参会人姓名未提及时写“待定”。
{title_line}
会议转写如下:
{transcript}"""


def make_minutes(transcript_md: str, cfg: dict, progress=print, title: str = "") -> str:
    body_text = "\n".join(l for l in transcript_md.splitlines() if not l.startswith("#"))
    progress(f"生成纪要中({cfg['minutes_model']})…")
    r = requests.post(
        f"{cfg['minutes_base']}/chat/completions",
        headers={
            "Authorization": f"Bearer {cfg['minutes_key']}",
            "Content-Type": "application/json",
        },
        json={
            "model": cfg["minutes_model"],
            "temperature": 0.3,
            "messages": [
                {"role": "system", "content": SYSTEM_PROMPT},
                {"role": "user", "content": USER_TMPL.format(
                    transcript=body_text,
                    title_line=f"用户提供的会议主题：{title}" if title else "会议主题请根据原文归纳。",
                )},
            ],
        },
        timeout=300,
    )
    j = response_json(r, "生成纪要")
    try:
        choice = j["choices"][0]
        content = choice["message"]["content"].strip()
        if not content or choice.get("finish_reason") in ("length", "content_filter"):
            die("纪要为空、被截断或被过滤，已保留转写，请调整模型后仅重试纪要")
        return content
    except (KeyError, IndexError, TypeError, AttributeError):
        die("生成纪要返回格式异常，已保留转写，可仅重试纪要")


def regenerate_minutes(result_dir: Path, cfg: dict, progress=print, title: str = "") -> Path:
    """Only send saved text to the LLM; never upload or transcribe audio again."""
    transcript = result_dir / "转写.md"
    if not transcript.is_file():
        die("没有可用的转写，请先完成录音转写")
    if not cfg.get("minutes_key"):
        die("请先配置纪要模型 API Key")
    md = make_minutes(transcript.read_text(encoding="utf-8"), cfg, progress, title=title)
    write_text_atomic(result_dir / "会议纪要.md", md + "\n")
    progress("纪要已保存")
    return result_dir

# ---------------------------------------------------------------- 主流程

def process(path: Path, cfg: dict, out_dir: Path, progress=print, title: str = "", result_name=None) -> Path:
    """处理单个音频,返回输出目录。进度通过 progress(str) 回调。"""
    stem_dir = out_dir / (result_name or path.stem)
    stem_dir.mkdir(parents=True, exist_ok=True)
    try:
        oss_url = upload_audio(path, cfg["api_key"], progress, model=cfg["asr_model"])
        result = transcribe(oss_url, cfg, progress)
    except MinutesError:
        raise
    write_text_atomic(stem_dir / "raw.json", json.dumps(result, ensure_ascii=False, indent=1))
    transcript_md = parse_transcription(result, path.name)
    write_text_atomic(stem_dir / "转写.md", transcript_md)
    progress(f"转写已保存 → {stem_dir / '转写.md'}")

    if cfg.get("minutes_enabled", True):
        regenerate_minutes(stem_dir, cfg, progress, title=title)
    return stem_dir


def main():
    ap = argparse.ArgumentParser(description="腕录 → 会议纪要")
    ap.add_argument("files", nargs="*", help="音频文件;缺省则处理 ../inbox 全部音频")
    ap.add_argument("--no-minutes", action="store_true", help="只转写,不生成纪要")
    ap.add_argument("--mock", help="离线模式:直接解析转写结果JSON,不调API")
    args = ap.parse_args()

    if args.mock:
        result = json.loads(Path(args.mock).read_text(encoding="utf-8"))
        out_dir = HERE.parent / "out" / (Path(args.mock).stem + "_mock")
        out_dir.mkdir(parents=True, exist_ok=True)
        (out_dir / "raw.json").write_text(json.dumps(result, ensure_ascii=False, indent=1), encoding="utf-8")
        t = parse_transcription(result, Path(args.mock).name)
        (out_dir / "转写.md").write_text(t, encoding="utf-8")
        print(f"[离线模式] 转写Markdown已生成 → {out_dir / '转写.md'}")
        return

    cfg = load_config()
    if args.no_minutes:
        cfg["minutes_enabled"] = False
    if not cfg["api_key"]:
        print("[错误] 未配置 API Key:请在 pipeline/config.ini 填 dashscope_api_key,或设环境变量 DASHSCOPE_API_KEY",
              file=sys.stderr)
        sys.exit(1)

    out_dir = HERE.parent / "out"
    out_dir.mkdir(parents=True, exist_ok=True)

    if args.files:
        paths = [Path(f) for f in args.files]
    else:
        inbox = HERE.parent / "inbox"
        if not inbox.exists():
            inbox.mkdir(parents=True)
        paths = sorted(p for p in inbox.iterdir() if p.suffix.lower() in AUDIO_EXT)

    if not paths:
        print("没有待处理的音频。把手表录音放进 watch_minutes/inbox/ 再运行。")
        return

    done, failed = 0, []
    for p in paths:
        if not p.exists():
            print(f"[跳过] 不存在: {p}")
            continue
        print(f"\n=== {p.name} ===")
        try:
            process(p, cfg, out_dir)
            done += 1
        except Exception as e:
            print(f"[失败] {p.name}: {e}")
            failed.append(p.name)

    print(f"\n完成 {done} 个,失败 {len(failed)} 个" + (f": {', '.join(failed)}" if failed else ""))
    print(f"结果在: {out_dir}")


if __name__ == "__main__":
    try:
        main()
    except MinutesError as e:
        print(f"[错误] {e}", file=sys.stderr)
        sys.exit(1)

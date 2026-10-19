# -*- coding: utf-8 -*-
"""Sparkora BYD Dynasty Windows Local Agent.

The Sparkora Server owns authorization, job state, and auditing. This Windows
program owns local-only work: environment checks, BYD image upload, MMKV
draft append, backup, verification, and result reporting.
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import io
import json
import os
import platform
import random
import re
import shutil
import struct
import subprocess
import sys
import tempfile
import time
import zlib
from pathlib import Path
from typing import Any

import requests
from Crypto.Cipher import AES

try:
    from PIL import Image
except ImportError:
    Image = None

AGENT_VERSION = "1.0.0"
APP_ID = "wxa28c31d4ff7ae869"
DRAFT_KEY = "platformDrafts"
PAYLOAD_VERSION = 1
MAX_ERROR_LENGTH = 800
MAX_IMAGE_EDGE = 1920
JPEG_QUALITY = 88
NONCE_CHARS = "ABCDEFGHJKMNPQRSTWXYZabcdefhijkmnprstwxyz2345678"
DEFAULT_WECHAT_PROCESSES = {"wechat.exe", "weixin.exe", "wechatapp.exe", "wechatappex.exe"}
WORK_DIR = Path(__file__).with_name("work")
RUNTIME_STATUS_FILE = WORK_DIR / "agent.status.json"
STOP_REQUEST_FILE = WORK_DIR / "agent.stop"


class AgentError(RuntimeError):
    """Expected local failure with a user-facing Chinese message."""


class AgentConfig:
    """Validated local configuration. Secrets never enter job payload or logs."""

    def __init__(self, raw: dict[str, str | None]):
        self.base_url = _required(raw, "SPARKORA_BASE_URL").rstrip("/")
        self.agent_token = _required(raw, "SPARKORA_AGENT_TOKEN")
        self.agent_id = _required(raw, "SPARKORA_AGENT_ID")
        self.mmkv_dir = _required(raw, "BYD_DYNASTY_MMKV_DIR")
        self.upload_url = _required(raw, "BYD_DYNASTY_UPLOAD_URL")
        self.upload_app_key = _required(raw, "BYD_DYNASTY_UPLOAD_APP_KEY", "clouduser")
        self.upload_sign_key = _required(raw, "BYD_DYNASTY_UPLOAD_SIGN_KEY")
        self.upload_session_id = _required(raw, "BYD_DYNASTY_UPLOAD_SESSION_ID")
        self.club_id = _optional(raw, "BYD_DYNASTY_CLUB_ID")
        self.club_title = _optional(raw, "BYD_DYNASTY_CLUB_TITLE")
        backup_dir = _optional(raw, "BYD_DYNASTY_BACKUP_DIR")
        self.backup_dir = backup_dir or str(Path(self.mmkv_dir).parent / "sparkora-agent-backup")
        self.min_free_disk_mb = int(_optional(raw, "BYD_DYNASTY_MIN_FREE_DISK_MB", "200") or 200)
        self.heartbeat_interval = float(_optional(raw, "BYD_DYNASTY_HEARTBEAT_INTERVAL_SECONDS", "20") or 20)
        self.poll_interval = float(_optional(raw, "BYD_DYNASTY_POLL_INTERVAL_SECONDS", "5") or 5)
        extra = _optional(raw, "BYD_DYNASTY_WECHAT_PROCESSES")
        self.wechat_processes = {*_normalize_process_names(DEFAULT_WECHAT_PROCESSES), *_normalize_process_names(extra)}

    @property
    def main_file(self) -> Path:
        return Path(self.mmkv_dir) / APP_ID

    @property
    def crc_file(self) -> Path:
        return Path(self.mmkv_dir) / f"{APP_ID}.crc"


def _required(source: dict[str, str | None], key: str, default: str | None = None) -> str:
    value = source.get(key) or default
    if value is None or not value.strip():
        raise AgentError(f"本机配置缺失：{key}")
    return value.strip()


def _optional(source: dict[str, str | None], key: str, default: str | None = None) -> str | None:
    value = source.get(key, default)
    return value.strip() if isinstance(value, str) else default


def _normalize_process_names(value: object) -> set[str]:
    if isinstance(value, str):
        return {part.strip().lower() for part in re.split(r"[,;|\s]+", value) if part.strip()}
    if isinstance(value, (list, set, tuple)):
        return {str(item).strip().lower() for item in value if str(item).strip()}
    return set()


def stop_requested() -> bool:
    """Consume a control-panel stop request at a safe scheduling boundary."""
    if not STOP_REQUEST_FILE.exists():
        return False
    try:
        STOP_REQUEST_FILE.unlink(missing_ok=True)
    except OSError:
        pass
    return True


def write_runtime_status(preflight: dict[str, Any]) -> None:
    """Publish local-only liveness/preflight state for the control panel."""
    try:
        WORK_DIR.mkdir(parents=True, exist_ok=True)
        payload = {
            "pid": os.getpid(),
            "updatedAt": time.time(),
            "agentVersion": AGENT_VERSION,
            "preflight": preflight,
        }
        temp = RUNTIME_STATUS_FILE.with_suffix(".tmp")
        temp.write_text(json.dumps(payload, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
        temp.replace(RUNTIME_STATUS_FILE)
    except OSError:
        # Status reporting must never interrupt the Agent scheduling loop.
        pass


def load_environment(config_path: str | None) -> AgentConfig:
    """Load local config from environment plus an optional UTF-8 key=value file."""
    path = Path(config_path) if config_path else Path(__file__).with_name("config.env")
    if path.exists():
        with path.open("r", encoding="utf-8-sig") as fp:
            for line in fp:
                text = line.strip()
                if not text or text.startswith("#") or "=" not in text:
                    continue
                key, value = text.split("=", 1)
                os.environ.setdefault(key.strip(), value.strip().strip('"').strip("'"))
    else:
        print(f"[config] 未找到 {path.name}，仅读取环境变量")
    required_keys = [
        "SPARKORA_BASE_URL",
        "SPARKORA_AGENT_TOKEN",
        "SPARKORA_AGENT_ID",
        "BYD_DYNASTY_MMKV_DIR",
        "BYD_DYNASTY_UPLOAD_URL",
        "BYD_DYNASTY_UPLOAD_SIGN_KEY",
        "BYD_DYNASTY_UPLOAD_SESSION_ID",
    ]
    missing = [key for key in required_keys if not os.environ.get(key)]
    if missing:
        raise AgentError("本机配置缺失：" + "、".join(missing))
    return AgentConfig({key: os.environ.get(key) for key in os.environ})

# ============================== MMKV primitives ==============================

def varint(value: int) -> bytes:
    if value < 0:
        raise AgentError("MMKV 长度不能为负数")
    output = bytearray()
    while True:
        byte = value & 0x7F
        value >>= 7
        if value:
            output.append(byte | 0x80)
        else:
            output.append(byte)
            return bytes(output)


def read_varint(data: bytes, offset: int) -> tuple[int, int]:
    value, shift = 0, 0
    while True:
        if offset >= len(data):
            raise AgentError("MMKV varint 超出边界")
        byte = data[offset]
        offset += 1
        value |= (byte & 0x7F) << shift
        if not byte & 0x80:
            return value, offset
        shift += 7


def derive_aes_key(app_id: str) -> bytes:
    raw = ("w" + app_id[2::2]).encode("ascii")
    return bytes(raw[:16].ljust(16, b"\0"))


class StreamingCFB128:
    """AES-CFB-128 with streaming continuation over the existing MMKV ciphertext."""

    def __init__(self, key16: bytes, iv: bytes):
        self._aes_ecb = AES.new(key16, AES.MODE_ECB)
        self._iv = bytearray(iv)
        self._keystream = self._aes_ecb.encrypt(bytes(self._iv))
        self._pos = 0

    def _refresh(self) -> None:
        self._keystream = self._aes_ecb.encrypt(bytes(self._iv))
        self._pos = 0

    def encrypt(self, data: bytes) -> bytes:
        output = bytearray()
        for byte in data:
            if self._pos == 16:
                self._refresh()
            cipher_byte = byte ^ self._keystream[self._pos]
            self._iv[self._pos] = cipher_byte
            output.append(cipher_byte)
            self._pos += 1
        return bytes(output)

    def decrypt(self, data: bytes) -> bytes:
        output = bytearray()
        for byte in data:
            if self._pos == 16:
                self._refresh()
            plain_byte = byte ^ self._keystream[self._pos]
            self._iv[self._pos] = byte
            output.append(plain_byte)
            self._pos += 1
        return bytes(output)


def make_record(key: str, value: bytes) -> bytes:
    encoded_key = key.encode("utf-8")
    return varint(len(encoded_key)) + encoded_key + varint(len(value)) + value


def wrap_draft_value(draft_array: list[dict[str, Any]]) -> bytes:
    inner = json.dumps(draft_array, ensure_ascii=False, separators=(",", ":"))
    wrapper = json.dumps({"data": inner, "dataType": "Array"}, ensure_ascii=False, separators=(",", ":")).encode()
    return varint(len(wrapper)) + wrapper


def unpack_draft_value(value: bytes) -> list[dict[str, Any]]:
    if not value:
        return []
    _length, offset = read_varint(value, 0)
    wrapper = json.loads(value[offset:].decode("utf-8"))
    if wrapper.get("dataType") != "Array":
        raise AgentError("platformDrafts 包装类型异常")
    drafts = json.loads(wrapper.get("data", "[]"))
    if not isinstance(drafts, list):
        raise AgentError("platformDrafts 内容不是数组")
    return drafts


def parse_mmkv(main: bytes, crc: bytes, app_id: str = APP_ID) -> tuple[list[dict[str, Any]], int]:
    """Parse current platformDrafts without exposing unrelated MMKV values."""
    if len(main) < 4 or len(crc) < 40:
        raise AgentError("MMKV 文件结构不完整")
    actual = struct.unpack("<I", crc[28:32])[0]
    iv = crc[12:28]
    if len(main) < 4 + actual:
        raise AgentError("MMKV 主文件实际长度异常")
    plaintext = StreamingCFB128(derive_aes_key(app_id), iv).decrypt(main[4:4 + actual])
    offset = 4
    draft_value = None
    while offset < len(plaintext):
        key_length, next_offset = read_varint(plaintext, offset)
        if key_length == 0 or next_offset + key_length > len(plaintext):
            break
        key_name = plaintext[next_offset:next_offset + key_length]
        try:
            key_text = key_name.decode("utf-8")
        except UnicodeDecodeError:
            break
        value_length, value_offset = read_varint(plaintext, next_offset + key_length)
        if value_offset + value_length > len(plaintext):
            raise AgentError("MMKV value 越界，文件可能损坏")
        value = plaintext[value_offset:value_offset + value_length]
        if key_text == DRAFT_KEY:
            draft_value = value
        offset = value_offset + value_length
    return unpack_draft_value(draft_value or b""), actual


def append_mmkv(main: bytes, crc: bytes, new_record: bytes) -> tuple[bytes, bytes]:
    if len(crc) < 40:
        raise AgentError("MMKV crc 文件不足 40 字节")
    iv = crc[12:28]
    actual = struct.unpack("<I", crc[28:32])[0]
    cfb = StreamingCFB128(derive_aes_key(APP_ID), iv)
    cfb.decrypt(main[4:4 + actual])
    cipher_tail = cfb.encrypt(new_record)
    new_actual = actual + len(new_record)
    minimum_size = max(16384, 2 * new_actual)
    new_main = struct.pack("<I", new_actual) + main[4:4 + actual] + cipher_tail
    padding = bytes(minimum_size - len(new_main))
    if padding:
        new_main += padding
    checksum = zlib.crc32(main[4:4 + actual] + cipher_tail) & 0xFFFFFFFF
    new_crc = bytearray(crc)
    struct.pack_into("<I", new_crc, 0, checksum)
    struct.pack_into("<I", new_crc, 28, new_actual)
    return new_main, bytes(new_crc)


def backup_files(config: AgentConfig, logs: dict[str, Any] | None = None) -> list[str]:
    backup_dir = Path(config.backup_dir)
    backup_dir.mkdir(parents=True, exist_ok=True)
    timestamp = time.strftime("%Y%m%d-%H%M%S")
    saved: list[str] = []
    for suffix in ("", ".crc"):
        source = config.main_file if suffix == "" else config.crc_file
        target = backup_dir / f"{APP_ID}{suffix}.{timestamp}.bak"
        target.write_bytes(source.read_bytes())
        saved.append(target.name)
    return saved


def restore_files(config: AgentConfig, backup_dir: Path, timestamp: str) -> None:
    for suffix in ("", ".crc"):
        source = backup_dir / f"{APP_ID}{suffix}.{timestamp}.bak"
        target = config.main_file if suffix == "" else config.crc_file
        target.write_bytes(source.read_bytes())

def _verify_bytes(main: bytes, crc: bytes, draft: dict[str, Any]) -> list[dict[str, Any]]:
    drafts, _actual = parse_mmkv(main, crc)
    matching = [item for item in drafts if item.get("draftId") == draft.get("draftId")]
    if not matching:
        raise AgentError("MMKV 写入后校验失败：未找到本次 draftId")
    item = matching[-1]
    expected_messages = draft.get("message", [])
    if item.get("titleText") != draft.get("titleText") or item.get("message") != expected_messages:
        raise AgentError("MMKV 写入后校验失败：标题或消息不一致")
    expected_urls = [m.get("msg_content") for m in expected_messages if m.get("msg_type") == "img"]
    actual_urls = [m.get("msg_content") for m in item.get("message", []) if m.get("msg_type") == "img"]
    if expected_urls != actual_urls:
        raise AgentError("MMKV 写入后校验失败：图片 URL 不一致")
    return drafts


def write_mmkv(config: AgentConfig, draft: dict[str, Any], logs: dict[str, Any]) -> int:
    try:
        main_bytes = config.main_file.read_bytes()
        crc_bytes = config.crc_file.read_bytes()
    except PermissionError as exc:
        raise AgentError("MMKV 文件不可读，请确认文件未被其他程序锁定") from exc
    old_drafts, _actual = parse_mmkv(main_bytes, crc_bytes)
    if len(old_drafts) >= 20:
        raise AgentError("小程序草稿已达 20 篇上限，请先在王朝小程序中清理后重试")

    backup_dir = Path(config.backup_dir)
    timestamp = time.strftime("%Y%m%d-%H%M%S")
    try:
        saved = backup_files(config)
    except Exception as exc:
        raise AgentError(f"MMKV 备份失败，已停止写入：{type(exc).__name__}") from exc
    logs["backupFiles"] = saved

    drafts = [*old_drafts, draft]
    new_record = make_record(DRAFT_KEY, wrap_draft_value(drafts))
    new_main, new_crc = append_mmkv(main_bytes, crc_bytes, new_record)
    _verify_bytes(new_main, new_crc, draft)
    try:
        config.main_file.write_bytes(new_main)
        config.crc_file.write_bytes(new_crc)
    except Exception:
        restore_files(config, backup_dir, timestamp)
        raise AgentError("MMKV 写入失败，已从备份恢复原文件")

    final_main = config.main_file.read_bytes()
    final_crc = config.crc_file.read_bytes()
    final_drafts = _verify_bytes(final_main, final_crc, draft)
    logs["mmkvDraftCountBefore"] = len(old_drafts)
    logs["mmkvDraftCountAfter"] = len(final_drafts)
    return len(final_drafts)


# ============================== BYD image upload ==============================

def upload_headers(config: AgentConfig) -> dict[str, str]:
    nonce = "".join(random.choices(NONCE_CHARS, k=16))
    current_time = str(int(time.time()))
    checksum = hashlib.sha256((config.upload_sign_key + nonce + current_time).encode()).hexdigest()
    return {
        "Appkey": config.upload_app_key,
        "Nonce": nonce,
        "Curtime": current_time,
        "Checksum": checksum.lower(),
        "Algo": "1",
    }


def fetch_image(source_url: str) -> bytes:
    try:
        response = requests.get(source_url, timeout=30, headers={"User-Agent": "Sparkora-BYD-Agent/1.0"})
        response.raise_for_status()
    except requests.RequestException as exc:
        raise AgentError(f"下载任务图片失败：{source_url}") from exc
    return response.content


def normalize_jpeg(raw: bytes, filename: str) -> tuple[bytes, str]:
    if Image is None:
        raise AgentError("本机缺少 Pillow 组件，请先执行 pip install -r requirements.txt")
    try:
        with Image.open(io.BytesIO(raw)) as source:
            source.load()
            if source.mode in {"RGBA", "LA"} or (source.mode == "P" and "transparency" in source.info):
                rgba = source.convert("RGBA")
                image = Image.new("RGB", rgba.size, (255, 255, 255))
                image.paste(rgba, mask=rgba.split()[-1])
            elif source.mode != "RGB":
                image = source.convert("RGB")
            else:
                image = source.copy()
            image.thumbnail((MAX_IMAGE_EDGE, MAX_IMAGE_EDGE), Image.LANCZOS)
            output = io.BytesIO()
            image.save(output, format="JPEG", quality=JPEG_QUALITY, optimize=True)
            return output.getvalue(), "image/jpeg"
    except Exception as exc:
        raise AgentError(f"图片压缩失败：{filename}，{type(exc).__name__}") from exc


def upload_image(config: AgentConfig, raw: bytes, filename: str, mime: str) -> str:
    suffix = Path(filename).suffix or ".jpg"
    temp_path = None
    try:
        with tempfile.NamedTemporaryFile(prefix="sparkora-byd-", suffix=suffix, delete=False) as fp:
            temp_path = Path(fp.name)
            fp.write(raw)
        body = {
            "module": "xxl_platform",
            "mediaType": "image",
            "path": str(temp_path).lower(),
            "session_id": config.upload_session_id,
        }
        response = requests.post(
            config.upload_url,
            headers=upload_headers(config),
            data=body,
            files={"buffer": (filename, raw, mime)},
            timeout=60,
        )
        response.raise_for_status()
        result = response.json()
        if result.get("ret") != 200:
            raise AgentError(f"BYD 图片上传接口返回失败：{result.get('msg') or result.get('ret')}")
        url = str(((result.get("data") or {}).get("attachment") or "")).strip()
        if not url:
            raise AgentError("BYD 图片上传成功但未返回附件 URL")
        return url
    except requests.RequestException as exc:
        raise AgentError(f"BYD 图片上传网络失败：{filename}") from exc
    finally:
        if temp_path and temp_path.exists():
            try:
                temp_path.unlink()
            except OSError:
                pass


def upload_payload_images(config: AgentConfig, images: list[dict[str, Any]], referenced_ids: set[str], logs: dict[str, Any]) -> tuple[dict[str, str], list[str]]:
    sources = {str(item.get("id")): item for item in images if item.get("id")}
    unknown = sorted(referenced_ids - set(sources))
    if unknown:
        raise AgentError("任务 payload 缺少图片源：" + "、".join(unknown))
    urls: dict[str, str] = {}
    uploaded_urls: list[str] = []
    index = 0
    for image in images:
        image_id = str(image.get("id"))
        if image_id not in referenced_ids:
            continue
        index += 1
        if image.get("sourceType") != "URL" or not image.get("url"):
            raise AgentError(f"图片源类型不支持：{image_id}")
        raw = fetch_image(str(image["url"]))
        compressed, mime = normalize_jpeg(raw, f"{image_id}.jpg")
        url = upload_image(config, compressed, f"{image_id}.jpg", mime)
        urls[image_id] = url
        uploaded_urls.append(url)
        logs[f"image_{image_id}_uploaded"] = True
        print(f"[upload] {image_id} -> {url}")
        if index < len(referenced_ids):
            time.sleep(random.uniform(0.8, 2.5))
    return urls, uploaded_urls

def compile_messages(payload: dict[str, Any], uploaded_urls: dict[str, str]) -> list[dict[str, Any]]:
    messages: list[dict[str, Any]] = []
    for raw_message in payload.get("messages") or []:
        message_type = raw_message.get("type")
        if message_type == "text":
            content = raw_message.get("content")
            if content is None or not str(content).strip():
                continue
            messages.append({"msg_type": "text", "msg_content": str(content), "msg_style": {}})
        elif message_type == "img":
            ref = str(raw_message.get("imageRef", ""))
            if ref not in uploaded_urls:
                raise AgentError(f"图片尚未上传成功，无法写入草稿：{ref}")
            image_id = nonce32()
            messages.append({"id": image_id, "msg_type": "img", "msg_content": uploaded_urls[ref]})
            messages.append({"id": nonce32(), "msg_type": "text", "msg_content": "", "msg_style": {"sibling": image_id}})
        else:
            raise AgentError(f"不支持的任务消息类型：{message_type}")
    if not messages:
        raise AgentError("任务正文为空，禁止写入 MMKV")
    return messages


def build_draft(payload: dict[str, Any], messages: list[dict[str, Any]], config: AgentConfig) -> dict[str, Any]:
    club_id = payload.get("clubId") or config.club_id or ""
    club_title = payload.get("clubTitle") or config.club_title or ""
    return {
        "publishType": "article",
        "message": messages,
        "atMember": [],
        "titleText": str(payload.get("title") or "无标题").strip(),
        "tid": "",
        "topicName": "",
        "fid": "",
        "fname": "",
        "clubId": str(club_id),
        "clubTitle": str(club_title),
        "longitude": "",
        "latitude": "",
        "location": "",
        "selectedPostTemplateId": None,
        "selectedExtConfig": {},
        "carInfo": {},
        "source": "",
        "vin": "",
        "reportId": "",
        "timestamp": int(time.time() * 1000),
        "draftId": str(payload.get("draftId") or nonce32()).strip(),
    }


# ============================== Local preflight ==============================

def readable_mmkv(config: AgentConfig) -> tuple[bool, int, str | None]:
    try:
        drafts, _actual = parse_mmkv(config.main_file.read_bytes(), config.crc_file.read_bytes())
        return True, len(drafts), None
    except AgentError as exc:
        return False, 0, str(exc)
    except Exception as exc:
        return False, 0, f"MMKV 解析异常：{type(exc).__name__}"


def wechat_processes_running(config: AgentConfig) -> list[str]:
    if platform.system() != "Windows":
        return ["non-windows"]
    try:
        completed = subprocess.run(["tasklist", "/NH", "/FO", "CSV"], capture_output=True, text=True, timeout=5, check=False)
    except (OSError, subprocess.SubprocessError):
        return ["tasklist-unavailable"]
    running: set[str] = set()
    for row in csv.reader(completed.stdout.splitlines()):
        if not row:
            continue
        name = row[0].strip().strip('"').lower()
        if name in config.wechat_processes:
            running.add(name)
    return sorted(running)


def writable_without_changing(path: Path) -> bool:
    try:
        with path.open("r+b"):
            return True
    except (OSError, PermissionError):
        return False


def directory_writable(path: Path) -> bool:
    try:
        path.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryFile(prefix="sparkora-byd-preflight-", dir=str(path)):
            return True
    except (OSError, PermissionError):
        return False


def build_preflight(config: AgentConfig, probe_server: bool = True) -> dict[str, Any]:
    errors: list[str] = []
    checks: dict[str, Any] = {}
    is_windows = platform.system() == "Windows"
    checks["windows"] = is_windows
    if not is_windows:
        errors.append("必须运行在 Windows 微信所在电脑")

    main_exists = config.main_file.exists() and config.main_file.is_file()
    crc_exists = config.crc_file.exists() and config.crc_file.is_file()
    checks["mmkvMainFileExists"] = main_exists
    checks["mmkvCrcFileExists"] = crc_exists
    if not main_exists or not crc_exists:
        errors.append("BYD 小程序 MMKV 主文件或 crc 文件不存在，请确认目录配置")

    checks["mmkvMainFileReadable"] = main_exists and os.access(config.main_file, os.R_OK)
    checks["mmkvMainFileWritable"] = main_exists and writable_without_changing(config.main_file)
    if not checks["mmkvMainFileWritable"]:
        errors.append("MMKV 主文件不可写，请检查文件权限或杀毒软件拦截")

    parse_ok, draft_count, parse_error = False, 0, "MMKV 文件不存在"
    if main_exists and crc_exists:
        parse_ok, draft_count, parse_error = readable_mmkv(config)
    checks["mmkvPlatformDraftsParsable"] = parse_ok
    checks["mmkvDraftCount"] = draft_count
    if not parse_ok and parse_error:
        errors.append(parse_error)

    processes = wechat_processes_running(config)
    checks["wechatProcessesRunning"] = processes
    if processes:
        errors.append("检测到微信相关进程仍在运行，请完全退出微信（包括系统托盘）后再推送")

    try:
        free_mb = int(shutil.disk_usage(config.mmkv_dir).free / 1024 / 1024)
        enough = free_mb >= config.min_free_disk_mb
    except OSError:
        free_mb, enough = 0, False
    checks["freeDiskMb"] = free_mb
    checks["freeDiskEnough"] = enough
    if not enough:
        errors.append(f"磁盘剩余空间不足 {config.min_free_disk_mb} MB")

    backup_ok = directory_writable(Path(config.backup_dir))
    checks["backupWritable"] = backup_ok
    if not backup_ok:
        errors.append("备份目录不可写，无法安全写入 MMKV")
    checks["uploadConfigured"] = bool(config.upload_url and config.upload_sign_key and config.upload_session_id)
    checks["agentConfigured"] = bool(config.agent_token and config.agent_id and config.base_url)
    checks["payloadVersion"] = PAYLOAD_VERSION

    if probe_server:
        try:
            requests.post(
                f"{config.base_url}/api/agent/byd-dynasty/heartbeat",
                headers={"X-Agent-Token": config.agent_token},
                json={"agentId": config.agent_id, "preflight": {"ready": False}},
                timeout=10,
            )
            server_reachable = True
        except requests.RequestException:
            server_reachable = False
        checks["serverReachable"] = server_reachable
        if not server_reachable:
            errors.append("无法连接 Sparkora Server，请检查 SPARKORA_BASE_URL 与网络")
    return {"ready": not errors, "ok": not errors, "agentVersion": AGENT_VERSION, "checks": checks, "errors": errors}

# ============================== Server client ==============================

def request_server(config: AgentConfig, method: str, path: str, payload: Any, timeout: int = 15) -> dict[str, Any]:
    url = f"{config.base_url}{path}"
    headers = {"X-Agent-Token": config.agent_token, "Content-Type": "application/json"}
    try:
        response = requests.request(method, url, headers=headers, json=payload, timeout=timeout)
        response.raise_for_status()
        body = response.json()
    except requests.HTTPError as exc:
        detail = ""
        try:
            detail = str(exc.response.json().get("msg") or "")
        except Exception:
            detail = exc.response.text[:300] if exc.response is not None else ""
        raise AgentError(f"Server {path} 请求失败：{detail or str(exc)}") from exc
    except requests.RequestException as exc:
        raise AgentError(f"Server {path} 连接失败：{exc}") from exc
    except ValueError as exc:
        raise AgentError(f"Server {path} 返回非法 JSON") from exc
    if body.get("code") != 0:
        raise AgentError(f"Server {path} 返回失败：{body.get('msg') or body.get('code')}")
    return body.get("data") or {}


def identity_body(config: AgentConfig, preflight: dict[str, Any]) -> dict[str, Any]:
    return {
        "agentId": config.agent_id,
        "machineName": platform.node(),
        "osName": f"{platform.system()} {platform.release()}",
        "agentVersion": AGENT_VERSION,
        "preflight": preflight,
    }


def register_agent(config: AgentConfig, preflight: dict[str, Any]) -> dict[str, Any]:
    return request_server(config, "POST", "/api/agent/byd-dynasty/register", identity_body(config, preflight))


def heartbeat(config: AgentConfig, preflight: dict[str, Any]) -> dict[str, Any]:
    return request_server(config, "POST", "/api/agent/byd-dynasty/heartbeat", identity_body(config, preflight))


def claim_job(config: AgentConfig) -> dict[str, Any]:
    return request_server(config, "POST", "/api/agent/byd-dynasty/claim-job", {"agentId": config.agent_id})


def report_result(config: AgentConfig, job_id: str, success: bool, draft_id: str | None, draft_count: int | None, uploaded_image_urls: list[str], preflight: dict[str, Any], logs_summary: dict[str, Any], error: str | None) -> dict[str, Any]:
    return request_server(
        config,
        "POST",
        f"/api/agent/byd-dynasty/jobs/{job_id}/result",
        {
            "agentId": config.agent_id,
            "success": success,
            "draftId": draft_id,
            "draftCount": draft_count,
            "uploadedImageUrls": uploaded_image_urls,
            "preflightSummary": preflight,
            "logsSummary": logs_summary,
            "error": normalize_error(error),
        },
        timeout=30,
    )


# ============================== Job execution ==============================

def execute_job(config: AgentConfig, payload: dict[str, Any]) -> tuple[dict[str, Any], dict[str, Any]]:
    if int(payload.get("payloadVersion") or 0) != PAYLOAD_VERSION:
        raise AgentError("任务 payload 版本不兼容，请升级本机 Agent 后重试")
    logs: dict[str, Any] = {"imageUploadCount": 0}
    messages = payload.get("messages") or []
    images = payload.get("images") or []
    referenced_ids = {str(item.get("imageRef")) for item in messages if item.get("type") == "img" and item.get("imageRef")}
    urls, uploaded_urls = upload_payload_images(config, images, referenced_ids, logs)
    logs["imageUploadCount"] = len(uploaded_urls)
    mmkv_messages = compile_messages(payload, urls)
    draft = build_draft(payload, mmkv_messages, config)
    draft_count = write_mmkv(config, draft, logs)
    logs["verified"] = True
    return {
        "success": True,
        "draftId": draft["draftId"],
        "draftCount": draft_count,
        "uploadedImageUrls": uploaded_urls,
    }, logs


def process_claimed_job(config: AgentConfig, job: dict[str, Any]) -> None:
    job_id = str(job.get("jobId") or "")
    raw_payload = job.get("payloadJson") or job.get("payload")
    preflight = None
    try:
        payload = raw_payload if isinstance(raw_payload, dict) else json.loads(raw_payload or "{}")
    except (TypeError, json.JSONDecodeError) as exc:
        error = f"任务 payload 解析失败，请联系管理员：{type(exc).__name__}"
        _report_failure(config, job_id, error, preflight)
        return
    try:
        preflight = build_preflight(config, probe_server=False)
        if not preflight.get("ready"):
            raise AgentError("本地预检未通过：" + "；".join(preflight.get("errors") or []))
        result, logs = execute_job(config, payload)
        report_result(
            config,
            job_id,
            True,
            result.get("draftId"),
            result.get("draftCount"),
            result.get("uploadedImageUrls", []),
            preflight,
            logs,
            None,
        )
    except AgentError as exc:
        _report_failure(config, job_id, str(exc), preflight)
    except Exception as exc:
        _report_failure(config, job_id, f"本地执行未知异常：{type(exc).__name__}: {exc}", preflight)


def _report_failure(config: AgentConfig, job_id: str, error: str, preflight: dict[str, Any] | None) -> None:
    if preflight is None:
        preflight = build_preflight(config, probe_server=False)
    print(f"[job] {job_id} 失败：{error}")
    try:
        report_result(config, job_id, False, None, None, [], preflight, {}, error)
    except AgentError as exc:
        print(f"[job] 失败结果回传失败：{exc}")


def once(config: AgentConfig) -> None:
    preflight = build_preflight(config, probe_server=True)
    print(f"[preflight] ready={preflight.get('ready')} errors={preflight.get('errors')}")
    try:
        register_agent(config, preflight)
        heartbeat(config, preflight)
    except AgentError as exc:
        print(f"[heartbeat] {exc}")
        return
    if not preflight.get("ready"):
        return
    claim = claim_job(config)
    if not claim.get("claimed"):
        print(f"[job] {claim.get('reason') or '当前没有可领取的任务'}")
        return
    process_claimed_job(config, claim.get("job") or {})


def run_loop(config: AgentConfig) -> None:
    next_heartbeat = 0.0
    while True:
        try:
            if stop_requested():
                print("[agent] 收到控制面板停止请求，正在退出")
                return
            preflight = build_preflight(config, probe_server=False)
            write_runtime_status(preflight)
            if preflight.get("ready"):
                if time.time() >= next_heartbeat:
                    register_agent(config, preflight)
                    heartbeat(config, preflight)
                    next_heartbeat = time.time() + config.heartbeat_interval
                claim = claim_job(config)
                if claim.get("claimed"):
                    process_claimed_job(config, claim.get("job") or {})
                    time.sleep(1)
                    continue
                print(f"[job] {claim.get('reason') or '当前没有可领取的任务'}")
            else:
                print("[preflight] 预检未通过：" + "；".join(preflight.get("errors") or []))
        except AgentError as exc:
            print(f"[agent] {exc}")
        except KeyboardInterrupt:
            raise
        except Exception as exc:
            print(f"[agent] 未知异常：{type(exc).__name__}: {exc}")
        time.sleep(config.poll_interval)


def nonce32() -> str:
    return "".join(random.choices("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789", k=32))


def normalize_error(error: str | None) -> str | None:
    if error is None:
        return None
    value = str(error).strip()
    return (value[:MAX_ERROR_LENGTH] + "…") if len(value) > MAX_ERROR_LENGTH else value


def main() -> int:
    parser = argparse.ArgumentParser(description="Sparkora BYD Dynasty Windows Local Agent")
    parser.add_argument("command", nargs="?", choices=["run", "once", "preflight"], default="run")
    parser.add_argument("--config", help="本地 key=value 配置文件，默认同目录 config.env")
    args = parser.parse_args()
    try:
        config = load_environment(args.config)
        if args.command == "preflight":
            result = build_preflight(config, probe_server=True)
            print(json.dumps(result, ensure_ascii=False, indent=2))
            return 0 if result.get("ready") else 2
        if args.command == "once":
            once(config)
            return 0
        run_loop(config)
        return 0
    except AgentError as exc:
        print(f"[agent] {exc}", file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        print("[agent] 已停止")
        return 0
    except Exception as exc:
        print(f"[agent] 启动失败：{type(exc).__name__}: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())

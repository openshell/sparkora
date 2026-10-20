# -*- coding: utf-8 -*-
from __future__ import annotations

import io
import json
import re
import shutil
import struct
import zlib
from pathlib import Path

import pytest
from Crypto.Cipher import AES

import byd_local_agent as agent


def aes_key() -> bytes:
    return agent.derive_aes_key(agent.APP_ID)


def crypto_cfb(iv: bytes, data: bytes) -> bytes:
    return AES.new(aes_key(), AES.MODE_CFB, iv=iv, segment_size=128).encrypt(data)


def make_mmkv_bytes(plaintext: bytes, actual: int) -> tuple[bytes, bytes]:
    iv = b"0123456789abcdef"
    cipher = crypto_cfb(iv, plaintext)
    main = struct.pack("<I", actual) + cipher
    minimum = max(16384, 2 * len(main))
    main += bytes(minimum - len(main))
    crc = bytearray(struct.pack("<III", 0, 1, 1) + iv + struct.pack("<III", actual, len(main), 0))
    struct.pack_into("<I", crc, 0, zlib.crc32(cipher) & 0xFFFFFFFF)
    return main, bytes(crc)


def make_record_bytes(records: list[tuple[str, bytes]]) -> bytes:
    value = b"".join(
        agent.make_record(key.encode().decode(), blob).removeprefix(b"")
        for key, blob in records
    )
    return value


def build_mmkv(drafts: list[dict] | None = None) -> tuple[bytes, bytes]:
    wrapped = agent.wrap_draft_value(drafts or [])
    record = agent.make_record(agent.DRAFT_KEY, wrapped)
    plaintext = b"\0\0\0\0" + record
    return make_mmkv_bytes(plaintext, len(plaintext))


def mp4_box(box_type: bytes, payload: bytes) -> bytes:
    return struct.pack(">I4s", 8 + len(payload), box_type) + payload


def make_mp4_bytes(duration: float = 12.0, width: int = 1080, height: int = 1920) -> bytes:
    ftyp = mp4_box(b"ftyp", b"isom\0\0\0\0isommp42")
    mvhd = bytearray(20)
    struct.pack_into(">I", mvhd, 0, 0)
    struct.pack_into(">I", mvhd, 12, 1000)
    struct.pack_into(">I", mvhd, 16, int(duration * 1000))
    tkhd = bytearray(84)
    struct.pack_into(">I", tkhd, 0, 0)
    struct.pack_into(">I", tkhd, 76, width << 16)
    struct.pack_into(">I", tkhd, 80, height << 16)
    trak = mp4_box(b"trak", mp4_box(b"tkhd", bytes(tkhd)))
    moov = mp4_box(b"moov", mp4_box(b"mvhd", bytes(mvhd)) + trak)
    return ftyp + mp4_box(b"mdat", b"video-data") + moov


class FakeHttpResponse:
    def __init__(self, content: bytes = b"", json_data: dict | None = None, headers: dict | None = None):
        self.content = content
        self.json_data = json_data or {}
        self.headers = headers or {}
        self.closed = False

    def raise_for_status(self) -> None:
        return None

    def iter_content(self, chunk_size: int):
        for offset in range(0, len(self.content), chunk_size):
            yield self.content[offset:offset + chunk_size]

    def json(self) -> dict:
        return self.json_data

    def close(self) -> None:
        self.closed = True


def test_cfb128_matches_pycryptodome_for_small_stream():
    iv = b"0123456789abcdef"
    data = b"sparkora-byd"
    expected = AES.new(aes_key(), AES.MODE_CFB, iv=iv, segment_size=128).encrypt(data)
    assert agent.StreamingCFB128(aes_key(), iv).encrypt(data) == expected


def test_parse_mmkv_reads_platform_drafts():
    main, crc = build_mmkv([])
    drafts, actual = agent.parse_mmkv(main, crc)
    assert drafts == []
    assert actual > 0


def test_make_mmkv_self_consistent():
    main, crc = build_mmkv([])
    # A well-formed existing MMKV should parse after the local append uses the
    # same streaming primitive.
    drafts, _ = agent.parse_mmkv(main, crc)
    assert drafts == []


def test_compile_messages_adds_sibling_after_image():
    result = agent.compile_messages(
        {
            "messages": [
                {"type": "text", "content": "第一行\n第二行"},
                {"type": "img", "imageRef": "BODY-1"},
            ]
        },
        {"BODY-1": "https://example.test/a.jpg"},
    )
    assert result[0]["msg_content"] == "第一行\n第二行"
    assert result[0]["msg_content"] == "第一行\n第二行"
    assert result[1]["msg_type"] == "img"
    assert result[2]["msg_style"]["sibling"] == result[1]["id"]


def test_build_draft_uses_complete_payload_club_pair(tmp_path: Path):
    config = history_config(tmp_path)
    config.club_id = "LOCAL-1"
    config.club_title = "本地车友会"
    payload = {
        "payloadVersion": 2,
        "title": "成对标签",
        "messages": [{"type": "text", "content": "正文"}],
        "clubId": "220",
        "clubTitle": "四川迪友会",
    }

    draft = agent.build_draft(payload, agent.compile_messages(payload, {}), config)

    assert draft["clubId"] == "220"
    assert draft["clubTitle"] == "四川迪友会"


def test_build_draft_legacy_v1_falls_back_to_complete_local_pair(tmp_path: Path):
    config = history_config(tmp_path)
    config.club_id = "LOCAL-1"
    config.club_title = "本地车友会"
    payload = {
        "payloadVersion": 1,
        "title": "旧任务",
        "messages": [{"type": "text", "content": "正文"}],
    }

    draft = agent.build_draft(payload, agent.compile_messages(payload, {}), config)

    assert draft["clubId"] == "LOCAL-1"
    assert draft["clubTitle"] == "本地车友会"


def test_build_draft_v2_explicit_no_tag_overrides_complete_local_pair(tmp_path: Path):
    config = history_config(tmp_path)
    config.club_id = "LOCAL-1"
    config.club_title = "本地车友会"
    payload = {
        "payloadVersion": 2,
        "title": "不使用标签",
        "messages": [{"type": "text", "content": "正文"}],
        "clubId": None,
        "clubTitle": "",
    }

    draft = agent.build_draft(payload, agent.compile_messages(payload, {}), config)

    assert draft["clubId"] == ""
    assert draft["clubTitle"] == ""


def test_build_draft_rejects_payload_with_only_one_club_key_present(tmp_path: Path):
    config = history_config(tmp_path)
    config.club_id = "LOCAL-1"
    config.club_title = "本地车友会"
    payload = {
        "payloadVersion": 2,
        "title": "缺少标签名字段",
        "messages": [{"type": "text", "content": "正文"}],
        "clubId": "220",
    }

    with pytest.raises(agent.AgentError, match="任务中的车友会标签不完整"):
        agent.build_draft(payload, agent.compile_messages(payload, {}), config)


def test_build_draft_rejects_partial_payload_club_pair(tmp_path: Path):
    config = history_config(tmp_path)
    config.club_id = "LOCAL-1"
    config.club_title = "本地车友会"
    payload = {
        "payloadVersion": 2,
        "title": "半套标签",
        "messages": [{"type": "text", "content": "正文"}],
        "clubId": "220",
        "clubTitle": "",
    }

    with pytest.raises(agent.AgentError, match="任务中的车友会标签不完整"):
        agent.build_draft(payload, agent.compile_messages(payload, {}), config)


def test_build_draft_rejects_partial_local_club_pair_when_fallback_needed(tmp_path: Path):
    config = history_config(tmp_path)
    config.club_id = "LOCAL-1"
    config.club_title = ""
    payload = {
        "payloadVersion": 2,
        "title": "本地半套标签",
        "messages": [{"type": "text", "content": "正文"}],
    }

    with pytest.raises(agent.AgentError, match="本地车友会标签配置不完整"):
        agent.build_draft(payload, agent.compile_messages(payload, {}), config)


def test_partial_club_pair_does_not_write_mmkv(tmp_path: Path, monkeypatch):
    config = history_config(tmp_path)
    payload = {
        "payloadVersion": 2,
        "jobId": "job-partial-club",
        "title": "半套标签",
        "messages": [{"type": "text", "content": "正文"}],
        "clubId": "220",
        "clubTitle": "",
    }
    monkeypatch.setattr(agent, "upload_payload_media", lambda *args: ({}, []))
    write_called = False

    def unexpected_write(*args):
        nonlocal write_called
        write_called = True
        return 1

    monkeypatch.setattr(agent, "write_mmkv", unexpected_write)

    with pytest.raises(agent.AgentError, match="任务中的车友会标签不完整"):
        agent.execute_job(config, payload)
    assert write_called is False


def test_write_mmkv_with_text_payload(tmp_path: Path):
    mmkv_dir = tmp_path / "mmkv"
    backup_dir = tmp_path / "backup"
    mmkv_dir.mkdir()
    main, crc = build_mmkv([])
    (mmkv_dir / agent.APP_ID).write_bytes(main)
    (mmkv_dir / f"{agent.APP_ID}.crc").write_bytes(crc)
    config = agent.AgentConfig(
        {
            "SPARKORA_BASE_URL": "https://sparkora.test",
            "SPARKORA_AGENT_TOKEN": "token",
            "SPARKORA_AGENT_ID": "agent-test",
            "BYD_DYNASTY_MMKV_DIR": str(mmkv_dir),
            "BYD_DYNASTY_UPLOAD_URL": "https://upload.test",
            "BYD_DYNASTY_UPLOAD_SIGN_KEY": "sign",
            "BYD_DYNASTY_UPLOAD_SESSION_ID": "sid",
            "BYD_DYNASTY_BACKUP_DIR": str(backup_dir),
        }
    )
    payload = {"title": "本地测试", "messages": [{"type": "text", "content": "你好"}]}
    draft = agent.build_draft(payload, agent.compile_messages(payload, {}), config)
    logs: dict = {}
    count = agent.write_mmkv(config, draft, logs)
    drafts, _ = agent.parse_mmkv(config.main_file.read_bytes(), config.crc_file.read_bytes())
    assert count == 1
    assert len(drafts) == 1
    assert drafts[0]["draftId"] == draft["draftId"]
    assert logs["mmkvDraftCountAfter"] == 1
    assert len(list(backup_dir.iterdir())) == 2


def history_config(tmp_path: Path) -> agent.AgentConfig:
    return agent.AgentConfig(
        {
            "SPARKORA_BASE_URL": "https://sparkora.test",
            "SPARKORA_AGENT_TOKEN": "super-secret-token",
            "SPARKORA_AGENT_ID": "agent-test",
            "BYD_DYNASTY_MMKV_DIR": str(tmp_path / "private-mmkv"),
            "BYD_DYNASTY_UPLOAD_URL": "https://upload.test",
            "BYD_DYNASTY_UPLOAD_SIGN_KEY": "super-secret-sign",
            "BYD_DYNASTY_UPLOAD_SESSION_ID": "session-secret",
            "BYD_DYNASTY_BACKUP_DIR": str(tmp_path / "private-backup"),
        }
    )


def history_lines(path: Path) -> list[dict]:
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def test_log_uses_local_timestamp_level_and_flushes(capsys):
    agent.log("INFO", "hello")
    output = capsys.readouterr().out.strip()
    assert re.fullmatch(r"\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2} \[INFO\] hello", output)


def test_log_and_safe_error_text_redact_sensitive_paths_and_signatures(capsys):
    sanitized = agent.safe_error_text(
        r"token=super-secret sign=super-signature path=C:\Program Files\WeChat\mmkv"
    )
    assert "super-secret" not in sanitized
    assert "super-signature" not in sanitized
    assert "Program Files" not in sanitized
    assert "WeChat" not in sanitized
    assert "token=<已隐藏>" in sanitized

    agent.log(
        "ERROR",
        r"request https://images.test/private.jpg?sign=secret C:\Program Files\WeChat\mmkv token=secret",
    )
    output = capsys.readouterr().out
    assert "https://images.test" not in output
    assert "Program Files" not in output
    assert "token=secret" not in output


def test_tasklist_uses_replace_decoding(monkeypatch):
    configured = {}

    class Result:
        stdout = '"Weixin.exe","123","Console","1","100 K"\n'

    def fake_run(*args, **kwargs):
        configured.update(kwargs)
        return Result()

    monkeypatch.setattr(agent.platform, "system", lambda: "Windows")
    monkeypatch.setattr(agent.subprocess, "run", fake_run)
    config = agent.AgentConfig(
        {
            "SPARKORA_BASE_URL": "https://sparkora.test",
            "SPARKORA_AGENT_TOKEN": "token",
            "SPARKORA_AGENT_ID": "agent-test",
            "BYD_DYNASTY_MMKV_DIR": "mmkv",
            "BYD_DYNASTY_UPLOAD_URL": "https://upload.test",
            "BYD_DYNASTY_UPLOAD_SIGN_KEY": "sign",
            "BYD_DYNASTY_UPLOAD_SESSION_ID": "sid",
        }
    )
    assert agent.wechat_processes_running(config) == ["weixin.exe"]
    assert configured["errors"] == "replace"
    assert configured["encoding"]


def test_process_claimed_job_records_safe_received_running_success(tmp_path: Path, monkeypatch, capsys):
    monkeypatch.setattr(agent, "WORK_DIR", tmp_path)
    history = tmp_path / "push-history.jsonl"
    monkeypatch.setattr(agent, "PUSH_HISTORY_FILE", history)
    config = history_config(tmp_path)
    payload = {
        "payloadVersion": 1,
        "jobId": "job-success",
        "projectId": 42,
        "versionId": 7,
        "draftId": "draft-123",
        "title": "可发布标题",
        "messages": [{"type": "text", "content": "SECRET BODY"}],
        "images": [{"url": "https://images.test/signed.jpg?token=SECRET", "token": "super-secret-token"}],
    }
    monkeypatch.setattr(agent, "build_preflight", lambda config, probe_server=False: {"ready": True, "errors": [], "checks": {}})

    def execute(config, payload):
        assert history_lines(history)[-1]["status"] == "RUNNING"
        return (
            {"draftId": "draft-123", "draftCount": 3, "uploadedImageUrls": ["https://images.test/out.jpg"]},
            {"imageUploadCount": 0},
        )

    monkeypatch.setattr(agent, "execute_job", execute)
    reported: list[dict] = []
    monkeypatch.setattr(agent, "report_result", lambda *args: reported.append({"success": args[2]}) or {})

    agent.process_claimed_job(config, {"jobId": "job-success", "projectId": 42, "versionId": 7, "payloadJson": json.dumps(payload)})

    events = history_lines(history)
    assert [event["status"] for event in events] == ["RECEIVED", "RUNNING", "SUCCESS"]
    assert events[0]["title"] == "标题不可用"
    assert events[1]["title"] == "可发布标题"
    assert events[0]["projectId"] == "42"
    assert events[-1]["serverReported"] is True
    assert events[-1]["draftCount"] == 3
    expected_fields = {
        "at",
        "atEpoch",
        "status",
        "jobId",
        "projectId",
        "versionId",
        "title",
        "draftId",
        "draftCount",
        "durationMs",
        "error",
        "serverReported",
    }
    assert set(events[0]) == expected_fields
    raw_and_logs = history.read_text(encoding="utf-8") + capsys.readouterr().out
    for sensitive in ("SECRET BODY", "https://images.test", "super-secret-token", str(tmp_path)):
        assert sensitive not in raw_and_logs
    assert reported == [{"success": True}]


def test_process_claimed_job_records_failed_path(tmp_path: Path, monkeypatch, capsys):
    monkeypatch.setattr(agent, "WORK_DIR", tmp_path)
    history = tmp_path / "push-history.jsonl"
    monkeypatch.setattr(agent, "PUSH_HISTORY_FILE", history)
    config = history_config(tmp_path)
    payload = {"payloadVersion": 1, "jobId": "job-fail", "projectId": 8, "versionId": 9, "title": "失败任务"}
    monkeypatch.setattr(agent, "build_preflight", lambda config, probe_server=False: {"ready": True, "errors": [], "checks": {}})

    def fail_execution(config, payload):
        raise agent.AgentError("执行失败 https://images.test/private.jpg?sign=super-secret-sign")

    monkeypatch.setattr(agent, "execute_job", fail_execution)
    monkeypatch.setattr(agent, "report_result", lambda *args: {})

    agent.process_claimed_job(config, {"jobId": "job-fail", "projectId": 8, "versionId": 9, "payloadJson": json.dumps(payload)})

    events = history_lines(history)
    assert [event["status"] for event in events] == ["RECEIVED", "RUNNING", "FAILED"]
    assert events[-1]["serverReported"] is True
    assert "结果回传失败" not in events[-1]["error"]
    assert "https://images.test" not in events[-1]["error"]
    assert "super-secret-sign" not in events[-1]["error"]
    assert "super-secret-sign" not in capsys.readouterr().out


def test_malformed_payload_still_records_received_before_parse(tmp_path: Path, monkeypatch):
    monkeypatch.setattr(agent, "WORK_DIR", tmp_path)
    history = tmp_path / "push-history.jsonl"
    monkeypatch.setattr(agent, "PUSH_HISTORY_FILE", history)
    config = history_config(tmp_path)
    monkeypatch.setattr(agent, "build_preflight", lambda config, probe_server=False: {"ready": True, "errors": [], "checks": {}})
    monkeypatch.setattr(agent, "report_result", lambda *args: {})

    real_loads = json.loads
    parse_calls = 0

    def tracked_loads(value, *args, **kwargs):
        nonlocal parse_calls
        parse_calls += 1
        first = real_loads(history.read_text(encoding="utf-8").splitlines()[0])
        assert first["status"] == "RECEIVED"
        return real_loads(value, *args, **kwargs)

    monkeypatch.setattr(agent.json, "loads", tracked_loads)

    agent.process_claimed_job(config, {"jobId": "job-bad-json", "projectId": 11, "payloadJson": "{not-json"})

    events = history_lines(history)
    assert parse_calls >= 1
    assert [event["status"] for event in events] == ["RECEIVED", "FAILED"]
    assert events[0]["jobId"] == "job-bad-json"
    assert events[0]["projectId"] == "11"
    assert events[-1]["serverReported"] is True


def test_local_success_survives_result_reporting_failure(tmp_path: Path, monkeypatch, capsys):
    monkeypatch.setattr(agent, "WORK_DIR", tmp_path)
    history = tmp_path / "push-history.jsonl"
    monkeypatch.setattr(agent, "PUSH_HISTORY_FILE", history)
    config = history_config(tmp_path)
    payload = {"payloadVersion": 1, "jobId": "job-report-fail", "projectId": 3, "versionId": 4, "title": "回传失败任务"}
    monkeypatch.setattr(agent, "build_preflight", lambda config, probe_server=False: {"ready": True, "errors": [], "checks": {}})
    monkeypatch.setattr(
        agent,
        "execute_job",
        lambda config, payload: ({"draftId": "draft-ok", "draftCount": 1, "uploadedImageUrls": []}, {}),
    )

    def report_failure(*args):
        raise agent.AgentError(
            "Server result failed token=super-secret-token url=https://private.test/x path=C:\\private\\mmkv"
        )

    monkeypatch.setattr(agent, "report_result", report_failure)

    agent.process_claimed_job(config, {"jobId": "job-report-fail", "projectId": 3, "versionId": 4, "payloadJson": json.dumps(payload)})

    events = history_lines(history)
    assert [event["status"] for event in events] == ["RECEIVED", "RUNNING", "SUCCESS"]
    latest = events[-1]
    assert latest["status"] == "SUCCESS"
    assert latest["serverReported"] is False
    assert "结果回传失败" in latest["error"]
    for sensitive in ("super-secret-token", "https://private.test", "C:\\private"):
        assert sensitive not in latest["error"]
    output = capsys.readouterr().out
    assert "super-secret-token" not in output
    assert "https://private.test" not in output
    assert "C:\\private" not in output


def test_failed_result_reporting_failure_has_one_final_event(tmp_path: Path, monkeypatch, capsys):
    monkeypatch.setattr(agent, "WORK_DIR", tmp_path)
    history = tmp_path / "push-history.jsonl"
    monkeypatch.setattr(agent, "PUSH_HISTORY_FILE", history)
    config = history_config(tmp_path)
    payload = {"payloadVersion": 1, "jobId": "job-fail-report", "projectId": 5, "versionId": 6, "title": "失败回传"}
    monkeypatch.setattr(agent, "build_preflight", lambda config, probe_server=False: {"ready": True, "errors": [], "checks": {}})

    def fail_execution(config, payload):
        raise agent.AgentError("本地失败")

    monkeypatch.setattr(agent, "execute_job", fail_execution)

    def report_failure(*args):
        raise agent.AgentError(
            r"report token=super-secret-token url=https://private.test/x path=C:\private\mmkv"
        )

    monkeypatch.setattr(agent, "report_result", report_failure)

    agent.process_claimed_job(config, {"jobId": "job-fail-report", "projectId": 5, "versionId": 6, "payloadJson": json.dumps(payload)})

    events = history_lines(history)
    assert [event["status"] for event in events] == ["RECEIVED", "RUNNING", "FAILED"]
    latest = events[-1]
    assert latest["serverReported"] is False
    assert "本地失败" in latest["error"]
    assert "结果回传失败" in latest["error"]
    output = capsys.readouterr().out
    for sensitive in ("super-secret-token", "https://private.test", r"C:\private"):
        assert sensitive not in latest["error"]
        assert sensitive not in output


def test_record_push_history_tolerates_malformed_optional_counts(tmp_path: Path, monkeypatch):
    monkeypatch.setattr(agent, "WORK_DIR", tmp_path)
    history = tmp_path / "push-history.jsonl"
    monkeypatch.setattr(agent, "PUSH_HISTORY_FILE", history)
    config = history_config(tmp_path)

    agent.record_push_history(
        "SUCCESS",
        {"jobId": "job-bad-count", "projectId": "1", "versionId": "2", "title": "计数异常"},
        config=config,
        draft_count="not-a-number",
        duration_ms=object(),
        server_reported=True,
    )

    event = history_lines(history)[0]
    assert event["draftCount"] is None
    assert event["durationMs"] is None
    assert event["jobId"] == "job-bad-count"


def test_run_loop_does_not_log_each_empty_poll(tmp_path: Path, monkeypatch, capsys):
    config = history_config(tmp_path)
    config.poll_interval = 0
    config.heartbeat_interval = 0
    calls = {"stop": 0}

    def stop_after_two_cycles():
        calls["stop"] += 1
        return calls["stop"] >= 3

    monkeypatch.setattr(agent, "stop_requested", stop_after_two_cycles)
    monkeypatch.setattr(agent, "build_preflight", lambda config, probe_server=False: {"ready": True, "errors": [], "checks": {}})
    monkeypatch.setattr(agent, "write_runtime_status", lambda preflight: None)
    monkeypatch.setattr(agent, "register_agent", lambda *args: {})
    monkeypatch.setattr(agent, "heartbeat", lambda *args: {})
    monkeypatch.setattr(agent, "claim_job", lambda config: {"claimed": False, "reason": "当前没有可领取的任务"})
    monkeypatch.setattr(agent.time, "sleep", lambda seconds: None)

    agent.run_loop(config)

    output = capsys.readouterr().out
    assert output.count("预检状态变化：通过") == 1
    assert output.count("首次心跳成功") == 1
    assert "当前没有可领取的任务" not in output
    assert "收到控制面板停止请求" in output


def test_preflight_reports_payload_v2_upload_and_media_temp(tmp_path: Path, monkeypatch):
    mmkv_dir = tmp_path / "mmkv"
    mmkv_dir.mkdir()
    main, crc = build_mmkv([])
    (mmkv_dir / agent.APP_ID).write_bytes(main)
    (mmkv_dir / f"{agent.APP_ID}.crc").write_bytes(crc)
    config = history_config(tmp_path)
    config.mmkv_dir = str(mmkv_dir)
    config.backup_dir = str(tmp_path / "backup")
    config.media_temp_dir = str(tmp_path / "media")
    monkeypatch.setattr(agent.platform, "system", lambda: "Windows")
    monkeypatch.setattr(agent, "wechat_processes_running", lambda config: [])

    preflight = agent.build_preflight(config, probe_server=False)

    assert preflight["ready"] is True
    assert preflight["checks"]["payloadVersion"] == 2
    assert preflight["checks"]["uploadConfigured"] is True
    assert preflight["checks"]["mediaTempWritable"] is True
    assert preflight["checks"]["mediaLimits"]["maxVideoBytes"] == agent.MAX_VIDEO_BYTES
    assert preflight["checks"]["mediaLimits"]["maxVideos"] == 3


def test_download_media_file_uses_job_scoped_endpoint_and_streams(tmp_path: Path, monkeypatch):
    config = history_config(tmp_path)
    config.media_temp_dir = str(tmp_path / "media")
    response = FakeHttpResponse(b"streamed-media", headers={"Content-Length": "14"})
    captured: dict = {}

    def fake_get(url, **kwargs):
        captured.update({"url": url, **kwargs})
        return response

    monkeypatch.setattr(agent.requests, "get", fake_get)
    media = {
        "id": "MEDIA-1",
        "mediaId": "media-1",
        "sourceType": "STAGED",
        "downloadPath": "/api/agent/byd-dynasty/jobs/job-1/media/media-1",
    }

    downloaded = agent.download_media_file(config, media, "job-1", 1024)

    assert downloaded.read_bytes() == b"streamed-media"
    assert captured["url"] == "https://sparkora.test/api/agent/byd-dynasty/jobs/job-1/media/media-1"
    assert captured["headers"]["X-Agent-Token"] == "super-secret-token"
    assert captured["headers"]["X-Agent-Id"] == config.agent_id
    assert captured["stream"] is True
    assert response.closed is True
    downloaded.unlink()


def test_download_media_file_rejects_unscoped_payload_path(tmp_path: Path, monkeypatch):
    config = history_config(tmp_path)
    config.media_temp_dir = str(tmp_path / "media")

    def unexpected_get(*args, **kwargs):
        raise AssertionError("unsafe media path must not be requested")

    monkeypatch.setattr(agent.requests, "get", unexpected_get)
    media = {
        "id": "MEDIA-1",
        "mediaId": "media-1",
        "sourceType": "STAGED",
        "downloadPath": "https://evil.test/media-1",
    }

    with pytest.raises(agent.AgentError, match="下载路径不合法"):
        agent.download_media_file(config, media, "job-1", 1024)


@pytest.mark.parametrize("duration", [2.0, 12.5, 120.0])
def test_read_mp4_metadata_accepts_valid_boxes(tmp_path: Path, duration: float):
    video = tmp_path / "clip.mp4"
    video.write_bytes(make_mp4_bytes(duration=duration, width=1080, height=1920))

    metadata = agent.read_mp4_metadata(video)

    assert metadata == {"durationSeconds": duration, "width": 1080, "height": 1920}


@pytest.mark.parametrize("duration", [1.0, 121.0])
def test_validate_video_file_rejects_duration_outside_range(tmp_path: Path, duration: float):
    config = history_config(tmp_path)
    video = tmp_path / "clip.mp4"
    video.write_bytes(make_mp4_bytes(duration=duration))

    with pytest.raises(agent.AgentError, match="2–120 秒"):
        agent.validate_video_file(config, video, "clip.mp4")


def test_validate_video_file_rejects_non_mp4_extension_and_signature(tmp_path: Path):
    config = history_config(tmp_path)
    video = tmp_path / "clip.mov"
    video.write_bytes(make_mp4_bytes())
    with pytest.raises(agent.AgentError, match="MP4"):
        agent.validate_video_file(config, video, "clip.mov")

    invalid = tmp_path / "invalid.mp4"
    invalid.write_bytes(b"not-an-mp4")
    with pytest.raises(agent.AgentError):
        agent.validate_video_file(config, invalid, "invalid.mp4")


def test_upload_image_compresses_jpeg_and_sends_image_media_type(tmp_path: Path, monkeypatch):
    config = history_config(tmp_path)
    config.media_temp_dir = str(tmp_path / "media")
    image = agent.Image.new("RGB", (40, 30), (255, 0, 0))
    source = io.BytesIO()
    image.save(source, format="PNG")
    compressed, mime = agent.normalize_jpeg(source.getvalue(), "source.png")
    captured: dict = {}

    def fake_post(url, **kwargs):
        captured.update({"url": url, **kwargs})
        captured["uploaded_bytes"] = kwargs["files"]["buffer"][1].read()
        return FakeHttpResponse(json_data={"ret": 200, "data": {"attachment": "attachment://image"}} )

    monkeypatch.setattr(agent.requests, "post", fake_post)
    attachment = agent.upload_image(config, compressed, "photo.jpg", mime)

    assert attachment == "attachment://image"
    assert captured["data"]["mediaType"] == "image"
    assert captured["files"]["buffer"][0] == "photo.jpg"
    assert captured["files"]["buffer"][2] == "image/jpeg"
    assert captured["uploaded_bytes"].startswith(b"\xff\xd8\xff")


def test_upload_video_sends_video_media_type_and_dimensions(tmp_path: Path, monkeypatch):
    config = history_config(tmp_path)
    video = tmp_path / "clip.mp4"
    video.write_bytes(make_mp4_bytes())
    captured: dict = {}

    def fake_post(url, **kwargs):
        captured.update({"url": url, **kwargs})
        captured["uploaded_bytes"] = kwargs["files"]["buffer"][1].read()
        return FakeHttpResponse(json_data={
            "ret": 200,
            "data": {"attachment": "attachment://video", "mediapreview": "preview://video"},
        })

    monkeypatch.setattr(agent.requests, "post", fake_post)
    result = agent.upload_video_file(config, video, "clip.mp4", 1080, 1920)

    assert result == {"attachment": "attachment://video", "mediapreview": "preview://video"}
    assert captured["data"]["mediaType"] == "video"
    assert captured["data"]["width"] == "1080"
    assert captured["data"]["height"] == "1920"
    assert captured["files"]["buffer"][2] == "video/mp4"
    assert captured["uploaded_bytes"] == video.read_bytes()
    assert captured["timeout"] == 600


def test_validate_video_file_rejects_more_than_300mb(tmp_path: Path):
    config = history_config(tmp_path)
    video = tmp_path / "too-large.mp4"
    with video.open("wb") as fp:
        fp.truncate(agent.MAX_VIDEO_BYTES + 1)

    with pytest.raises(agent.AgentError, match="300MB"):
        agent.validate_video_file(config, video, "too-large.mp4")


def test_compile_v2_messages_keeps_order_siblings_and_video_payload(tmp_path: Path):
    payload = {
        "payloadVersion": 2,
        "title": "图文视频",
        "messages": [
            {"type": "text", "content": "开头"},
            {"type": "img", "mediaRef": "MEDIA-1"},
            {"type": "video", "mediaRef": "MEDIA-2"},
            {"type": "text", "content": "结尾"},
        ],
    }
    uploaded = {
        "MEDIA-1": {"mediaType": "image", "attachment": "attachment://image"},
        "MEDIA-2": {
            "mediaType": "video",
            "attachment": "attachment://video",
            "mediapreview": "preview://video",
            "durationSeconds": 12.5,
        },
    }

    messages = agent.compile_messages(payload, uploaded)

    assert [item["msg_type"] for item in messages] == ["text", "img", "text", "video", "text", "text"]
    assert messages[0] == {"msg_type": "text", "msg_content": "开头", "msg_style": {}}
    assert messages[1]["msg_content"] == "attachment://image"
    assert messages[2] == {
        "msg_type": "text",
        "msg_style": {"sibling": messages[1]["id"]},
        "msg_content": "",
        "id": messages[2]["id"],
    }
    assert messages[3] == {
        "msg_type": "video",
        "msg_content": "attachment://video|preview://video|12.5",
        "src": "attachment://video",
        "cover": "preview://video",
        "id": messages[3]["id"],
        "loadingImg": False,
        "uploadError": False,
    }
    assert messages[4]["msg_style"] == {"sibling": messages[3]["id"]}
    assert messages[5]["msg_content"] == "结尾"

    draft = agent.build_draft(payload, messages, history_config(tmp_path))
    assert draft["publishType"] == "article"
    assert draft["message"] == messages
    assert "materials" not in draft
    assert "surfacePlot" not in draft


def test_execute_job_does_not_write_mmkv_when_media_upload_fails(monkeypatch, tmp_path: Path):
    config = history_config(tmp_path)
    payload = {
        "payloadVersion": 2,
        "jobId": "job-media-fail",
        "messages": [{"type": "img", "mediaRef": "MEDIA-1"}],
        "media": [{"id": "MEDIA-1", "mediaId": "media-1", "type": "image", "sourceType": "STAGED"}],
    }

    monkeypatch.setattr(
        agent,
        "upload_payload_media",
        lambda config, payload, logs: (_ for _ in ()).throw(agent.AgentError("媒体上传失败")),
    )
    write_called = False

    def unexpected_write(*args):
        nonlocal write_called
        write_called = True
        return 1

    monkeypatch.setattr(agent, "write_mmkv", unexpected_write)
    with pytest.raises(agent.AgentError, match="媒体上传失败"):
        agent.execute_job(config, payload)
    assert write_called is False


def test_upload_payload_media_rejects_more_than_three_videos(monkeypatch, tmp_path: Path):
    config = history_config(tmp_path)
    payload = {
        "payloadVersion": 2,
        "jobId": "job-video-limit",
        "messages": [{"type": "video", "mediaRef": f"VIDEO-{index}"} for index in range(4)],
        "media": [],
    }

    monkeypatch.setattr(agent, "download_media_file", lambda *args: pytest.fail("must validate before download"))
    with pytest.raises(agent.AgentError, match="最多支持 3 个视频"):
        agent.upload_payload_media(config, payload, {})


def test_upload_payload_media_maps_staged_image_and_video(monkeypatch, tmp_path: Path):
    config = history_config(tmp_path)
    config.media_temp_dir = str(tmp_path / "media")
    image_path = tmp_path / "downloaded-image"
    video_path = tmp_path / "downloaded-video"
    image_path.write_bytes(b"image-source")
    video_path.write_bytes(b"video-source")
    payload = {
        "payloadVersion": 2,
        "jobId": "job-media-success",
        "messages": [
            {"type": "img", "mediaRef": "MEDIA-1"},
            {"type": "video", "mediaRef": "MEDIA-2"},
        ],
        "media": [
            {
                "id": "MEDIA-1",
                "mediaId": "media-1",
                "type": "image",
                "sourceType": "STAGED",
                "fileName": "photo.png",
            },
            {
                "id": "MEDIA-2",
                "mediaId": "media-2",
                "type": "video",
                "sourceType": "STAGED",
                "fileName": "clip.mp4",
            },
        ],
    }
    paths = iter([image_path, video_path])
    monkeypatch.setattr(agent, "download_media_file", lambda *args: next(paths))
    monkeypatch.setattr(agent, "normalize_jpeg", lambda raw, filename: (b"jpeg", "image/jpeg"))
    monkeypatch.setattr(agent, "upload_image", lambda *args: "attachment://image")
    monkeypatch.setattr(
        agent,
        "validate_video_file",
        lambda *args: {"durationSeconds": 9.5, "width": 1080, "height": 1920},
    )
    uploaded_sizes: list[tuple[int, int]] = []

    def upload_video(config, path, filename, width, height):
        uploaded_sizes.append((width, height))
        return {"attachment": "attachment://video", "mediapreview": "preview://video"}

    monkeypatch.setattr(agent, "upload_video_file", upload_video)
    logs: dict = {}
    uploaded, image_urls = agent.upload_payload_media(config, payload, logs)

    assert uploaded["MEDIA-1"] == {"mediaType": "image", "attachment": "attachment://image"}
    assert uploaded["MEDIA-2"] == {
        "mediaType": "video",
        "attachment": "attachment://video",
        "mediapreview": "preview://video",
        "durationSeconds": 9.5,
    }
    assert image_urls == ["attachment://image"]
    assert uploaded_sizes == [(1080, 1920)]
    assert logs == {"imageUploadCount": 1, "videoUploadCount": 1, "mediaUploadCount": 2}
    assert not image_path.exists()
    assert not video_path.exists()


def test_execute_job_keeps_v1_image_compatibility(monkeypatch, tmp_path: Path):
    config = history_config(tmp_path)
    payload = {
        "payloadVersion": 1,
        "title": "v1 任务",
        "messages": [{"type": "text", "content": "正文"}, {"type": "img", "imageRef": "BODY-1"}],
        "images": [{"id": "BODY-1", "sourceType": "URL", "url": "https://example.test/a.jpg"}],
    }
    uploaded_ids: dict = {}

    def upload_images(config, images, referenced_ids, logs):
        uploaded_ids.update({"images": images, "refs": referenced_ids})
        return {"BODY-1": "attachment://legacy-image"}, ["attachment://legacy-image"]

    def write_draft(config, draft, logs):
        assert draft["message"][1]["msg_content"] == "attachment://legacy-image"
        assert draft["message"][2]["msg_style"]["sibling"] == draft["message"][1]["id"]
        return 7

    monkeypatch.setattr(agent, "upload_payload_images", upload_images)
    monkeypatch.setattr(agent, "write_mmkv", write_draft)
    result, logs = agent.execute_job(config, payload)

    assert uploaded_ids["refs"] == {"BODY-1"}
    assert result["draftCount"] == 7
    assert result["uploadedImageUrls"] == ["attachment://legacy-image"]
    assert logs["payloadVersion"] == 1


def test_verify_bytes_requires_exact_complete_messages(tmp_path: Path):
    payload = {
        "payloadVersion": 2,
        "title": "精确校验",
        "messages": [{"type": "video", "mediaRef": "VIDEO-1"}],
    }
    uploaded = {
        "VIDEO-1": {
            "mediaType": "video",
            "attachment": "attachment://video",
            "mediapreview": "preview://video",
            "durationSeconds": 8,
        }
    }
    config = history_config(tmp_path)
    draft = agent.build_draft(payload, agent.compile_messages(payload, uploaded), config)
    main, crc = build_mmkv([draft])
    assert agent._verify_bytes(main, crc, draft)[0]["draftId"] == draft["draftId"]

    tampered = json.loads(json.dumps(draft))
    del tampered["message"][0]["uploadError"]
    with pytest.raises(agent.AgentError, match="消息不一致"):
        agent._verify_bytes(main, crc, tampered)


def test_write_mmkv_restores_backup_when_post_write_verification_fails(tmp_path: Path, monkeypatch):
    mmkv_dir = tmp_path / "mmkv"
    backup_dir = tmp_path / "backup"
    mmkv_dir.mkdir()
    main, crc = build_mmkv([])
    config = history_config(tmp_path)
    config.mmkv_dir = str(mmkv_dir)
    config.backup_dir = str(backup_dir)
    config.main_file.write_bytes(main)
    config.crc_file.write_bytes(crc)
    draft = agent.build_draft(
        {"title": "回滚测试", "messages": [{"type": "text", "content": "正文"}]},
        agent.compile_messages({"messages": [{"type": "text", "content": "正文"}]}, {}),
        config,
    )
    original_main = config.main_file.read_bytes()
    original_crc = config.crc_file.read_bytes()
    calls = 0

    def verification_fails_after_write(*args):
        nonlocal calls
        calls += 1
        if calls == 1:
            return []
        raise agent.AgentError("MMKV 写入后校验失败：消息不一致")

    monkeypatch.setattr(agent, "_verify_bytes", verification_fails_after_write)

    with pytest.raises(agent.AgentError, match="已从备份恢复"):
        agent.write_mmkv(config, draft, {})

    assert calls == 2
    assert config.main_file.read_bytes() == original_main
    assert config.crc_file.read_bytes() == original_crc
    assert len(list(backup_dir.iterdir())) == 2

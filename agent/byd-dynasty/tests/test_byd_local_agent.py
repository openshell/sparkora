# -*- coding: utf-8 -*-
from __future__ import annotations

import io
import json
import re
import shutil
import struct
import tempfile
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

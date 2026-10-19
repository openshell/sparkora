# -*- coding: utf-8 -*-
from __future__ import annotations

import json
from pathlib import Path

import byd_agent_control as control
import byd_local_agent as agent


def test_stop_request_is_consumed_once(tmp_path: Path, monkeypatch):
    marker = tmp_path / "agent.stop"
    marker.write_text("stop", encoding="utf-8")
    monkeypatch.setattr(agent, "STOP_REQUEST_FILE", marker)
    assert agent.stop_requested() is True
    assert marker.exists() is False
    assert agent.stop_requested() is False


def test_runtime_status_written_atomically(tmp_path: Path, monkeypatch):
    monkeypatch.setattr(agent, "WORK_DIR", tmp_path)
    monkeypatch.setattr(agent, "RUNTIME_STATUS_FILE", tmp_path / "agent.status.json")
    agent.write_runtime_status({"ready": True, "errors": []})
    payload = json.loads((tmp_path / "agent.status.json").read_text(encoding="utf-8"))
    assert payload["pid"] > 0
    assert payload["preflight"]["ready"] is True
    assert payload["updatedAt"] > 0


def test_control_read_pid_rejects_stale_process(tmp_path: Path, monkeypatch):
    pid_file = tmp_path / "agent.pid"
    pid_file.write_text("12345", encoding="utf-8")
    monkeypatch.setattr(control, "PID_FILE", pid_file)
    monkeypatch.setattr(control, "_windows_pid_alive", lambda pid: False)
    assert control.read_pid() is None
    assert not pid_file.exists()


def test_control_read_pid_accepts_live_process(tmp_path: Path, monkeypatch):
    pid_file = tmp_path / "agent.pid"
    pid_file.write_text("12345", encoding="utf-8")
    monkeypatch.setattr(control, "PID_FILE", pid_file)
    monkeypatch.setattr(control, "_windows_pid_alive", lambda pid: pid == 12345)
    assert control.read_pid() == 12345


def test_wechat_process_parser_ignores_other_processes(monkeypatch):
    class Result:
        stdout = '"Weixin.exe","123","Console","1","100 K"\n"notepad.exe","456","Console","1","1 K"\n'

    monkeypatch.setattr(control, "_hidden_process", lambda *args, **kwargs: Result())
    assert control.wechat_processes() == ["weixin.exe"]


def test_publish_page_uses_frontend_port(tmp_path: Path, monkeypatch):
    config = tmp_path / "config.env"
    config.write_text("SPARKORA_BASE_URL=http://192.168.3.108:5661\nSPARKORA_AGENT_TOKEN=secret\n", encoding="utf-8")
    monkeypatch.setattr(control, "CONFIG_FILE", config)
    assert control.publish_page_url() == "http://192.168.3.108:8088/"


def test_tail_text_returns_latest_content(tmp_path: Path):
    log = tmp_path / "agent.log"
    log.write_bytes(b"old\nnew")
    assert control.tail_text(log, max_chars=7) == "old\nnew"

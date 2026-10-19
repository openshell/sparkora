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


def test_load_config_values_retains_only_requested_keys(tmp_path: Path, monkeypatch):
    config = tmp_path / "config.env"
    config.write_text(
        "SPARKORA_BASE_URL=http://127.0.0.1:5661\nSPARKORA_AGENT_TOKEN=super-secret\n",
        encoding="utf-8",
    )
    monkeypatch.setattr(control, "CONFIG_FILE", config)

    values = control.load_config_values({"SPARKORA_BASE_URL"})

    assert values == {"SPARKORA_BASE_URL": "http://127.0.0.1:5661"}


def test_publish_page_uses_frontend_port(tmp_path: Path, monkeypatch):
    config = tmp_path / "config.env"
    config.write_text("SPARKORA_BASE_URL=http://192.168.3.108:5661\nSPARKORA_AGENT_TOKEN=secret\n", encoding="utf-8")
    monkeypatch.setattr(control, "CONFIG_FILE", config)
    assert control.publish_page_url() == "http://192.168.3.108:8088/"


def test_tail_text_returns_latest_content(tmp_path: Path):
    log = tmp_path / "agent.log"
    log.write_bytes(b"old\nnew")
    assert control.tail_text(log, max_chars=7) == "old\nnew"


def history_event(job_id: str, status: str, epoch: float, *, error=None) -> dict:
    return {
        "at": "2026-10-19 12:00:00",
        "atEpoch": epoch,
        "status": status,
        "jobId": job_id,
        "projectId": "42",
        "versionId": "7",
        "title": f"任务 {job_id}",
        "draftId": None,
        "draftCount": 2 if status == "SUCCESS" else None,
        "durationMs": 1200 if status in {"SUCCESS", "FAILED"} else None,
        "error": error,
        "serverReported": True if status in {"SUCCESS", "FAILED"} else None,
    }


def test_history_epoch_rejects_invalid_numbers_and_uses_local_timestamp():
    assert control._history_epoch({"atEpoch": "nan", "at": "2026-10-19 12:00:00"}) > 0
    assert control._history_epoch({"atEpoch": "inf", "at": "2026-10-19 12:00:00"}) > 0
    assert control._history_epoch({"atEpoch": "not-a-number", "at": "bad"}) == 0


def test_read_push_history_dedupes_latest_and_ignores_bad_lines(tmp_path: Path):
    history = tmp_path / "push-history.jsonl"
    rows = [
        history_event("job-a", "RECEIVED", 1),
        history_event("job-b", "RECEIVED", 2),
        history_event("job-a", "SUCCESS", 4),
        history_event("job-b", "RUNNING", 3),
        history_event("job-c", "FAILED", 5, error="失败原因"),
    ]
    history.write_text(
        "not-json\n"
        + "\n".join(json.dumps(row, ensure_ascii=False) for row in rows)
        + "\n{broken\n",
        encoding="utf-8",
    )

    events = control.read_push_history(history)

    assert [event["jobId"] for event in events] == ["job-c", "job-a", "job-b"]
    assert events[1]["status"] == "SUCCESS"
    assert events[2]["status"] == "RUNNING"
    assert events[0]["error"] == "失败原因"


def test_read_push_history_is_bounded(tmp_path: Path):
    history = tmp_path / "push-history.jsonl"
    history.write_text(
        "\n".join(
            json.dumps(history_event(f"job-{index}", "SUCCESS", index), ensure_ascii=False)
            for index in range(1, 6)
        )
        + "\n",
        encoding="utf-8",
    )
    events = control.read_push_history(history, limit=2)
    assert [event["jobId"] for event in events] == ["job-5", "job-4"]


def test_refresh_history_skips_unchanged_rows_and_shows_details(tmp_path: Path, monkeypatch):
    history = tmp_path / "push-history.jsonl"
    history.write_text(
        "\n".join(
            json.dumps(row, ensure_ascii=False)
            for row in [
                history_event("job-a", "RUNNING", 1),
                history_event("job-b", "FAILED", 2, error="可安全展示的失败原因"),
            ]
        )
        + "\n",
        encoding="utf-8",
    )
    monkeypatch.setattr(control, "HISTORY_FILE", history)

    class FakeTree:
        def __init__(self):
            self.rows: dict[str, tuple] = {}
            self.selected: tuple[str, ...] = ()
            self.deletes: list[str] = []
            self.inserts = 0

        def selection(self):
            return self.selected

        def get_children(self):
            return tuple(self.rows)

        def delete(self, item):
            self.deletes.append(item)
            self.rows.pop(item, None)

        def insert(self, parent, index, iid, values, tags):
            self.inserts += 1
            self.rows[iid] = (values, tags)

        def selection_set(self, item):
            self.selected = (item,)

        def focus(self, item):
            pass

    details: list[str] = []
    panel = object.__new__(control.AgentControlPanel)
    panel.history_tree = FakeTree()
    panel._history_events = {}
    panel._history_signature = None
    panel._set_history_detail = lambda value: details.append(value)

    panel.refresh_history()
    first_insert_count = panel.history_tree.inserts
    first_delete_count = len(panel.history_tree.deletes)
    panel.refresh_history()

    assert first_insert_count == 2
    assert panel.history_tree.inserts == first_insert_count
    assert len(panel.history_tree.deletes) == first_delete_count
    assert "可安全展示的失败原因" in details[-1]
    assert panel.history_tree.selected == ("0",)


def test_open_history_opens_the_persistent_jsonl(tmp_path: Path, monkeypatch):
    opened: list[str] = []
    history = tmp_path / "push-history.jsonl"
    monkeypatch.setattr(control, "WORK_DIR", tmp_path)
    monkeypatch.setattr(control, "HISTORY_FILE", history)
    monkeypatch.setattr(control.os, "startfile", lambda path: opened.append(path), raising=False)

    panel = object.__new__(control.AgentControlPanel)
    panel.open_history()

    assert history.exists()
    assert opened == [str(history)]


def test_agent_log_prefers_unified_and_falls_back_to_legacy(tmp_path: Path, monkeypatch):
    unified = tmp_path / "agent.log"
    legacy = tmp_path / "agent.stdout.log"
    stderr = tmp_path / "agent.stderr.log"
    legacy.write_text("legacy output", encoding="utf-8")
    stderr.write_text("legacy error", encoding="utf-8")
    monkeypatch.setattr(control, "AGENT_LOG", unified)
    monkeypatch.setattr(control, "STDOUT_LOG", legacy)
    monkeypatch.setattr(control, "STDERR_LOG", stderr)
    assert "legacy output" in control.agent_log_text()
    unified.write_text("2026-10-19 12:00:00 [INFO] unified", encoding="utf-8")
    assert control.agent_log_text() == "2026-10-19 12:00:00 [INFO] unified"


def test_start_agent_redirects_output_to_unified_log(tmp_path: Path, monkeypatch):
    config = tmp_path / "config.env"
    config.write_text("SPARKORA_BASE_URL=http://127.0.0.1:5661\n", encoding="utf-8")
    unified = tmp_path / "agent.log"
    pid_file = tmp_path / "agent.pid"
    stop_file = tmp_path / "agent.stop"
    monkeypatch.setattr(control, "CONFIG_FILE", config)
    monkeypatch.setattr(control, "WORK_DIR", tmp_path)
    monkeypatch.setattr(control, "AGENT_LOG", unified)
    monkeypatch.setattr(control, "PID_FILE", pid_file)
    monkeypatch.setattr(control, "STOP_FILE", stop_file)
    monkeypatch.setattr(control, "current_agent_pid", lambda discover=False: None)
    monkeypatch.setattr(control, "python_executable", lambda: "python.exe")
    monkeypatch.setattr(control.time, "sleep", lambda seconds: None)
    captured = {}

    class Process:
        pid = 4321

        @staticmethod
        def poll():
            return None

    def fake_popen(args, **kwargs):
        captured.update(kwargs)
        captured["args"] = args
        return Process()

    monkeypatch.setattr(control.subprocess, "Popen", fake_popen)

    pid, message = control.start_agent()

    assert pid == 4321
    assert "已启动" in message
    assert captured["stderr"] is control.subprocess.STDOUT
    assert captured["stdout"].name == str(unified)
    assert "控制面板启动 Agent" in unified.read_text(encoding="utf-8")
    assert pid_file.read_text(encoding="utf-8") == "4321"

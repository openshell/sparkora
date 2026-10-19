# -*- coding: utf-8 -*-
"""Windows control panel for the Sparkora BYD Dynasty Local Agent.

The panel intentionally stays dependency-free (tkinter + stdlib) and manages the
existing byd_local_agent.py process instead of duplicating Agent business logic.
"""

from __future__ import annotations

import ctypes
import json
import os
import subprocess
import sys
import threading
import time
import traceback
import webbrowser
from pathlib import Path
from urllib.parse import urlparse

import tkinter as tk
from tkinter import messagebox, ttk
from tkinter.scrolledtext import ScrolledText

APP_DIR = Path(__file__).resolve().parent
AGENT_SCRIPT = APP_DIR / "byd_local_agent.py"
CONFIG_FILE = APP_DIR / "config.env"
WORK_DIR = APP_DIR / "work"
PID_FILE = WORK_DIR / "agent.pid"
STOP_FILE = WORK_DIR / "agent.stop"
STATUS_FILE = WORK_DIR / "agent.status.json"
STDOUT_LOG = WORK_DIR / "agent.stdout.log"
STDERR_LOG = WORK_DIR / "agent.stderr.log"
CONTROL_LOG = WORK_DIR / "control.log"
CREATE_NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0x08000000)
_MUTEX_HANDLE = None


def acquire_single_instance() -> bool:
    """Prevent multiple control-panel windows from being opened by double-click."""
    global _MUTEX_HANDLE
    if os.name != "nt":
        return True
    kernel32 = ctypes.windll.kernel32
    _MUTEX_HANDLE = kernel32.CreateMutexW(None, False, "Local\\SparkoraBydAgentControlPanel")
    already_exists = 183
    if not _MUTEX_HANDLE:
        return True
    if kernel32.GetLastError() == already_exists:
        kernel32.CloseHandle(_MUTEX_HANDLE)
        _MUTEX_HANDLE = None
        return False
    return True


def python_executable() -> str:
    """Return the console Python executable, even when the UI runs under pythonw."""
    executable = Path(sys.executable)
    if executable.name.lower() == "pythonw.exe":
        candidate = executable.with_name("python.exe")
        if candidate.exists():
            return str(candidate)
    return str(executable)


def _hidden_process(args: list[str], **kwargs):
    return subprocess.run(
        args,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        timeout=kwargs.pop("timeout", 30),
        creationflags=CREATE_NO_WINDOW,
        **kwargs,
    )


def _windows_pid_alive(pid: int) -> bool:
    if pid <= 0:
        return False
    if os.name != "nt":
        try:
            os.kill(pid, 0)
            return True
        except OSError:
            return False
    process_query_limited_information = 0x1000
    still_active = 259
    kernel32 = ctypes.windll.kernel32
    handle = kernel32.OpenProcess(process_query_limited_information, False, pid)
    if not handle:
        return False
    try:
        exit_code = ctypes.c_ulong()
        if not kernel32.GetExitCodeProcess(handle, ctypes.byref(exit_code)):
            return False
        return exit_code.value == still_active
    finally:
        kernel32.CloseHandle(handle)


def read_pid() -> int | None:
    try:
        value = int(PID_FILE.read_text(encoding="utf-8").strip())
    except (OSError, ValueError):
        return None
    if _windows_pid_alive(value):
        return value
    PID_FILE.unlink(missing_ok=True)
    return None


def discover_agent_pid() -> int | None:
    """Find an Agent started outside this panel, e.g. from an older terminal."""
    if os.name != "nt":
        return None
    command = (
        "Get-CimInstance Win32_Process -Filter \"Name='python.exe' or Name='pythonw.exe'\" | "
        "Where-Object { $_.CommandLine -like '*byd_local_agent.py*run*' } | "
        "Select-Object -First 1 -ExpandProperty ProcessId"
    )
    try:
        result = _hidden_process(["powershell", "-NoProfile", "-Command", command], timeout=8)
    except (OSError, subprocess.SubprocessError):
        return None
    for line in result.stdout.splitlines():
        line = line.strip()
        if line.isdigit() and _windows_pid_alive(int(line)):
            return int(line)
    return None


def current_agent_pid(*, discover: bool = False) -> int | None:
    pid = read_pid()
    if pid is not None:
        return pid
    if discover:
        pid = discover_agent_pid()
        if pid is not None:
            WORK_DIR.mkdir(parents=True, exist_ok=True)
            PID_FILE.write_text(str(pid), encoding="utf-8")
        return pid
    return None


def read_runtime_status() -> dict:
    try:
        data = json.loads(STATUS_FILE.read_text(encoding="utf-8"))
        return data if isinstance(data, dict) else {}
    except (OSError, ValueError, TypeError):
        return {}


def wechat_processes() -> list[str]:
    if os.name != "nt":
        return ["non-windows"]
    try:
        result = _hidden_process(["tasklist", "/NH", "/FO", "CSV"], timeout=5)
    except (OSError, subprocess.SubprocessError):
        return ["tasklist-unavailable"]
    import csv

    names: set[str] = set()
    for row in csv.reader(result.stdout.splitlines()):
        if row:
            name = row[0].strip().strip('"').lower()
            if name in {"wechat.exe", "weixin.exe", "wechatapp.exe", "wechatappex.exe"}:
                names.add(name)
    return sorted(names)


def load_config_values() -> dict[str, str]:
    values: dict[str, str] = {}
    try:
        with CONFIG_FILE.open("r", encoding="utf-8-sig") as fp:
            for line in fp:
                text = line.strip()
                if not text or text.startswith("#") or "=" not in text:
                    continue
                key, value = text.split("=", 1)
                values[key.strip()] = value.strip().strip('"').strip("'")
    except OSError:
        pass
    return values


def publish_page_url() -> str:
    base = load_config_values().get("SPARKORA_BASE_URL", "http://192.168.3.108:5661").rstrip("/")
    parsed = urlparse(base)
    if not parsed.hostname:
        return base
    scheme = parsed.scheme or "http"
    frontend_port = 8088
    return f"{scheme}://{parsed.hostname}:{frontend_port}/"


def start_agent() -> tuple[int | None, str]:
    if not CONFIG_FILE.exists():
        return None, f"缺少配置文件：{CONFIG_FILE}"
    running = current_agent_pid(discover=True)
    if running is not None:
        return running, "Agent 已在运行"
    WORK_DIR.mkdir(parents=True, exist_ok=True)
    STOP_FILE.unlink(missing_ok=True)
    env = os.environ.copy()
    env["PYTHONUTF8"] = "1"
    env["PYTHONUNBUFFERED"] = "1"
    try:
        with STDOUT_LOG.open("a", encoding="utf-8") as stdout, STDERR_LOG.open("a", encoding="utf-8") as stderr:
            stdout.write(f"\n=== 控制面板启动 Agent {time.strftime('%Y-%m-%d %H:%M:%S')} ===\n")
            stdout.flush()
            process = subprocess.Popen(
                [python_executable(), "-u", str(AGENT_SCRIPT), "run"],
                cwd=str(APP_DIR),
                env=env,
                stdin=subprocess.DEVNULL,
                stdout=stdout,
                stderr=stderr,
                creationflags=CREATE_NO_WINDOW,
                close_fds=True,
            )
    except OSError as exc:
        return None, f"启动 Agent 失败：{exc}"
    PID_FILE.write_text(str(process.pid), encoding="utf-8")
    time.sleep(0.4)
    if process.poll() is not None:
        return None, "Agent 启动后立即退出，请查看日志"
    return process.pid, "Agent 已启动"


def request_stop_agent(timeout: float = 20.0) -> tuple[bool, str]:
    pid = current_agent_pid(discover=True)
    if pid is None:
        STOP_FILE.unlink(missing_ok=True)
        PID_FILE.unlink(missing_ok=True)
        return True, "Agent 当前未运行"
    WORK_DIR.mkdir(parents=True, exist_ok=True)
    STOP_FILE.write_text(str(time.time()), encoding="utf-8")
    deadline = time.time() + timeout
    while time.time() < deadline:
        if not _windows_pid_alive(pid):
            PID_FILE.unlink(missing_ok=True)
            STOP_FILE.unlink(missing_ok=True)
            return True, "Agent 已安全停止"
        time.sleep(0.25)
    return False, "已发送停止请求；若正在写入草稿，Agent 会在任务完成后退出"


def run_preflight() -> tuple[bool, dict | str]:
    try:
        result = _hidden_process(
            [python_executable(), "-u", str(AGENT_SCRIPT), "preflight"],
            cwd=str(APP_DIR),
            timeout=45,
        )
    except subprocess.TimeoutExpired:
        return False, "预检超时，请查看日志"
    except OSError as exc:
        return False, f"无法执行预检：{exc}"
    text = (result.stdout or result.stderr or "").strip()
    if not text:
        return False, f"预检没有输出（退出码 {result.returncode}）"
    try:
        payload = json.loads(text)
    except ValueError:
        return False, text[-2000:]
    if not isinstance(payload, dict):
        return False, "预检返回格式不正确"
    return bool(payload.get("ready")), payload


def tail_text(path: Path, max_chars: int = 12000) -> str:
    try:
        with path.open("rb") as fp:
            fp.seek(0, os.SEEK_END)
            size = fp.tell()
            fp.seek(max(0, size - max_chars * 2))
            data = fp.read()
        return data.decode("utf-8", errors="replace")[-max_chars:]
    except OSError:
        return ""


class AgentControlPanel:
    def __init__(self, root: tk.Tk):
        self.root = root
        self.root.title("Sparkora · BYD 推送助手")
        self.root.geometry("760x590")
        self.root.minsize(680, 520)
        self.preflight_result: dict | None = None
        self.preflight_message = "正在等待预检…"
        self._preflight_running = False
        self._build_ui()
        self.root.after(120, self.bootstrap)
        self.root.after(1000, self.refresh)

    def _build_ui(self) -> None:
        style = ttk.Style()
        try:
            style.theme_use("vista")
        except tk.TclError:
            pass
        style.configure("Title.TLabel", font=("Microsoft YaHei UI", 18, "bold"))
        style.configure("Subtle.TLabel", foreground="#667085")
        style.configure("Status.TLabel", font=("Microsoft YaHei UI", 10))
        style.configure("Primary.TButton", font=("Microsoft YaHei UI", 10, "bold"))

        outer = ttk.Frame(self.root, padding=18)
        outer.pack(fill="both", expand=True)
        ttk.Label(outer, text="BYD 推送助手", style="Title.TLabel").pack(anchor="w")
        ttk.Label(
            outer,
            text="双击即可启动。Agent 在后台运行，关闭本窗口不会停止服务。",
            style="Subtle.TLabel",
        ).pack(anchor="w", pady=(2, 14))

        status_card = ttk.LabelFrame(outer, text="运行状态", padding=12)
        status_card.pack(fill="x")
        status_card.columnconfigure(1, weight=1)
        self.agent_value = ttk.Label(status_card, text="检测中…", style="Status.TLabel")
        self.wechat_value = ttk.Label(status_card, text="检测中…", style="Status.TLabel")
        self.preflight_value = ttk.Label(status_card, text="检测中…", style="Status.TLabel", wraplength=520)
        ttk.Label(status_card, text="Agent 进程").grid(row=0, column=0, sticky="w", padx=(0, 12), pady=3)
        self.agent_value.grid(row=0, column=1, sticky="w", pady=3)
        ttk.Label(status_card, text="微信进程").grid(row=1, column=0, sticky="w", padx=(0, 12), pady=3)
        self.wechat_value.grid(row=1, column=1, sticky="w", pady=3)
        ttk.Label(status_card, text="预检结果").grid(row=2, column=0, sticky="nw", padx=(0, 12), pady=3)
        self.preflight_value.grid(row=2, column=1, sticky="w", pady=3)

        actions = ttk.Frame(outer)
        actions.pack(fill="x", pady=14)
        self.start_button = ttk.Button(actions, text="启动 Agent", style="Primary.TButton", command=self.start_clicked)
        self.stop_button = ttk.Button(actions, text="安全停止", command=self.stop_clicked)
        self.check_button = ttk.Button(actions, text="重新预检", command=self.preflight_clicked)
        config_button = ttk.Button(actions, text="打开配置", command=self.open_config)
        log_button = ttk.Button(actions, text="打开日志", command=self.open_log)
        publish_button = ttk.Button(actions, text="打开发布页", command=lambda: webbrowser.open(publish_page_url()))
        for button in (self.start_button, self.stop_button, self.check_button, config_button, log_button, publish_button):
            button.pack(side="left", padx=(0, 8))

        log_card = ttk.LabelFrame(outer, text="最近日志", padding=8)
        log_card.pack(fill="both", expand=True)
        self.log_text = ScrolledText(log_card, height=16, wrap="word", state="disabled", font=("Consolas", 9))
        self.log_text.pack(fill="both", expand=True)

        footer = ttk.Frame(outer)
        footer.pack(fill="x", pady=(12, 0))
        ttk.Label(
            footer,
            text="推送前请完全退出微信（包括系统托盘和 WeChatAppEx）。",
            style="Subtle.TLabel",
        ).pack(side="left")
        ttk.Button(footer, text="关闭面板", command=self.root.destroy).pack(side="right")

    def bootstrap(self) -> None:
        if not CONFIG_FILE.exists():
            messagebox.showerror("配置缺失", f"未找到配置文件：\n{CONFIG_FILE}")
            return
        if current_agent_pid(discover=True) is None:
            _, message = start_agent()
            if "失败" in message or "退出" in message:
                messagebox.showerror("启动失败", message)
        self.refresh()
        self.preflight_clicked()

    def refresh(self) -> None:
        pid = current_agent_pid()
        if pid:
            self.agent_value.configure(text=f"运行中（PID {pid}）")
            self.start_button.configure(state="disabled")
            self.stop_button.configure(state="normal")
        else:
            self.agent_value.configure(text="已停止")
            self.start_button.configure(state="normal")
            self.stop_button.configure(state="disabled")

        processes = wechat_processes()
        if processes:
            self.wechat_value.configure(text="运行中：" + ", ".join(processes) + "（请完全退出）")
        else:
            self.wechat_value.configure(text="已退出")

        runtime = read_runtime_status()
        runtime_preflight = runtime.get("preflight") if runtime else None
        runtime_age = time.time() - float(runtime.get("updatedAt") or 0)
        if pid and isinstance(runtime_preflight, dict) and runtime_age <= 30:
            if runtime_preflight.get("ready"):
                self.preflight_message = f"通过（{max(0, int(runtime_age))} 秒前更新），可以进行小程序草稿推送"
            else:
                errors = runtime_preflight.get("errors") or []
                self.preflight_message = "未通过：" + ("；".join(errors) if errors else "等待 Agent 上报")
        elif self.preflight_result is not None:
            # 保留最近一次手动预检结果；Agent 恢复运行后会自动切回实时状态。
            pass
        elif runtime_preflight:
            errors = runtime_preflight.get("errors") or []
            self.preflight_message = "未通过：" + ("；".join(errors) if errors else "等待 Agent 上报")
        self.preflight_value.configure(text=self.preflight_message)

        log = "\n".join(part for part in (tail_text(STDOUT_LOG), tail_text(STDERR_LOG)) if part).strip()
        self.log_text.configure(state="normal")
        self.log_text.delete("1.0", "end")
        self.log_text.insert("1.0", log or "暂无日志")
        self.log_text.see("end")
        self.log_text.configure(state="disabled")
        self.root.after(2000, self.refresh)

    def start_clicked(self) -> None:
        pid, message = start_agent()
        if pid is None:
            messagebox.showerror("启动失败", message)
        self.refresh()

    def stop_clicked(self) -> None:
        if not messagebox.askyesno("停止 Agent", "将请求 Agent 安全停止。若正在写草稿，会等任务完成后再退出。继续吗？"):
            return
        self.stop_button.configure(state="disabled")

        def worker():
            ok, message = request_stop_agent()
            self.root.after(0, lambda: self._finish_stop(ok, message))

        threading.Thread(target=worker, daemon=True).start()

    def _finish_stop(self, ok: bool, message: str) -> None:
        if ok:
            messagebox.showinfo("Agent", message)
        else:
            messagebox.showwarning("Agent", message)
        self.refresh()

    def preflight_clicked(self) -> None:
        if self._preflight_running:
            return
        self._preflight_running = True
        self.preflight_message = "正在预检，请稍候…"
        self.check_button.configure(state="disabled")
        self.preflight_value.configure(text=self.preflight_message)

        def worker():
            ok, result = run_preflight()
            self.root.after(0, lambda: self._finish_preflight(ok, result))

        threading.Thread(target=worker, daemon=True).start()

    def _finish_preflight(self, ok: bool, result: dict | str) -> None:
        self._preflight_running = False
        self.check_button.configure(state="normal")
        if isinstance(result, dict):
            self.preflight_result = result
            if ok:
                self.preflight_message = "通过，可以进行小程序草稿推送"
            else:
                errors = result.get("errors") or []
                self.preflight_message = "未通过：" + ("；".join(errors) if errors else "请查看日志")
        else:
            self.preflight_result = None
            self.preflight_message = f"预检失败：{result}"
        self.preflight_value.configure(text=self.preflight_message)

    def open_config(self) -> None:
        if not CONFIG_FILE.exists():
            messagebox.showerror("配置缺失", str(CONFIG_FILE))
            return
        os.startfile(str(CONFIG_FILE))  # type: ignore[attr-defined]

    def open_log(self) -> None:
        WORK_DIR.mkdir(parents=True, exist_ok=True)
        STDOUT_LOG.touch(exist_ok=True)
        os.startfile(str(STDOUT_LOG))  # type: ignore[attr-defined]


def main() -> int:
    if not acquire_single_instance():
        try:
            messagebox.showinfo("BYD 推送助手", "控制面板已经打开。")
        except Exception:
            pass
        return 0
    WORK_DIR.mkdir(parents=True, exist_ok=True)
    if Path(sys.executable).name.lower() == "pythonw.exe":
        try:
            stream = CONTROL_LOG.open("a", encoding="utf-8", buffering=1)
            sys.stdout = stream
            sys.stderr = stream
        except OSError:
            pass
    try:
        root = tk.Tk()
        AgentControlPanel(root)
        root.mainloop()
        return 0
    except Exception:
        traceback.print_exc()
        try:
            messagebox.showerror("BYD 推送助手", "控制面板启动失败，请查看 work/control.log")
        except Exception:
            pass
        return 1


if __name__ == "__main__":
    raise SystemExit(main())

@echo off
setlocal
cd /d "%~dp0"
where pythonw.exe >nul 2>nul
if errorlevel 1 (
  start "" python.exe "%~dp0byd_agent_control.py"
) else (
  start "" pythonw.exe "%~dp0byd_agent_control.py"
)
endlocal

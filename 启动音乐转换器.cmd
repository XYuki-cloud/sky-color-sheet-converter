@echo off
setlocal

if exist "%~dp0.venv\Scripts\pythonw.exe" (
    start "" "%~dp0.venv\Scripts\pythonw.exe" "%~dp0scripts\music_converter_gui.py"
    exit /b 0
)

where pyw.exe >nul 2>&1
if not errorlevel 1 (
    start "" pyw.exe "%~dp0scripts\music_converter_gui.py"
    exit /b 0
)

where pythonw.exe >nul 2>&1
if not errorlevel 1 (
    start "" pythonw.exe "%~dp0scripts\music_converter_gui.py"
    exit /b 0
)

echo Python 3 not found. Install Python 3 or create .venv\Scripts\pythonw.exe.
exit /b 1

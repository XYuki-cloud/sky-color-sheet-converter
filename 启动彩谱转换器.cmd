@echo off
setlocal
cd /d "%~dp0"

if exist "%~dp0.venv\Scripts\pythonw.exe" (
    start "" "%~dp0.venv\Scripts\pythonw.exe" "%~dp0scripts\sky_converter_gui.py"
    exit /b 0
)

where pyw.exe >nul 2>&1
if errorlevel 1 goto try_pythonw
start "" pyw.exe "%~dp0scripts\sky_converter_gui.py"
exit /b 0

:try_pythonw
where pythonw.exe >nul 2>&1
if errorlevel 1 goto no_python
start "" pythonw.exe "%~dp0scripts\sky_converter_gui.py"
exit /b 0

:no_python
echo Python 3 not found. Install Python 3 with pythonw.exe or pyw.exe on PATH.
exit /b 1

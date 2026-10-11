@echo off
REM ==============================================================================
REM iQOO Telemetry & Hardware Maximizer Launcher (Windows)
REM ==============================================================================
setlocal enabledelayedexpansion

set CMD=%1
if "%CMD%"=="" set CMD=start

if "%CMD%"=="start" (
    echo [*] Starting iQOO Telemetry Maximizer...
    python "%~dp0iqoo_telemetry_maximizer.py" start
) else if "%CMD%"=="stop" (
    echo [*] Stopping iQOO Telemetry Maximizer...
    python "%~dp0iqoo_telemetry_maximizer.py" stop
) else if "%CMD%"=="status" (
    python "%~dp0iqoo_telemetry_maximizer.py" status
) else (
    echo Usage: run_maximizer.bat [start^|stop^|status]
)
pause

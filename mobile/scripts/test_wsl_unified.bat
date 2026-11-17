@echo off
chcp 65001 >nul
title CSQTT Unified WSL Test Runner
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0test_wsl_unified.ps1"
if %errorlevel% neq 0 (
    echo.
    echo [ERROR] Tests failed with exit code %errorlevel%
    pause
    exit /b %errorlevel%
)
echo.
echo [OK] All tests finished successfully.
pause

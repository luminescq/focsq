@echo off
setlocal enabledelayedexpansion

set "VERIFY_ONLY=0"
if "%~1"=="" goto args_ok
if /I "%~1"=="--verify-native-only" (
    set "VERIFY_ONLY=1"
    goto args_ok
)
echo Usage: build_apk.bat [--verify-native-only]
exit /b 2

:args_ok
for %%I in ("%~dp0..") do set "PROJECT_ROOT=%%~fI\"
set "PROVENANCE_SCRIPT=%PROJECT_ROOT%scripts\native_client_provenance.ps1"
set "SERVER_PROVENANCE_SCRIPT=%PROJECT_ROOT%scripts\server_asset_provenance.ps1"
cd /d "%PROJECT_ROOT%"

echo === CSQTT APK Build Script ===
echo === Output: 4 APKs (universal, arm64-v8a, armeabi-v7a, x86_64) ===
echo.

powershell -NoProfile -ExecutionPolicy Bypass -File "%SERVER_PROVENANCE_SCRIPT%" -Mode Verify
if %errorlevel% neq 0 (
    echo.
    echo Run rust-server\build_linux.bat first to rebuild the verified server asset.
    if "%VERIFY_ONLY%"=="0" if not defined CI pause
    exit /b 1
)

powershell -NoProfile -ExecutionPolicy Bypass -File "%PROVENANCE_SCRIPT%" -Mode Verify
if %errorlevel% neq 0 (
    echo.
    echo Run rust-client\build_so.bat first to rebuild verified Rust native libraries.
    if "%VERIFY_ONLY%"=="0" if not defined CI pause
    exit /b 1
)
if "%VERIFY_ONLY%"=="1" (
    echo Native verification completed successfully.
    exit /b 0
)

call "%PROJECT_ROOT%scripts\find_android_sdk.bat"
if %errorlevel% neq 0 (
    if not defined CI pause
    exit /b 1
)
set "ANDROID_HOME=%SDK_PATH%"

echo Incremental build...

echo Building release APKs...
call "%PROJECT_ROOT%gradlew.bat" :app:assembleRelease --no-daemon

if %errorlevel% neq 0 (
    echo.
    echo BUILD FAILED! Please check the errors above.
    if not defined CI pause
    exit /b 1
)

set "APK_DIR=%PROJECT_ROOT%app\build\outputs\apk\release"

if not exist "%PROJECT_ROOT%app\release" mkdir "%PROJECT_ROOT%app\release"

echo.
echo Copying APKs to release folder...

if exist "%APK_DIR%\app-x86-release.apk" del /Q "%APK_DIR%\app-x86-release.apk"
if exist "%PROJECT_ROOT%app\release\CSQTT-x86.apk" del /Q "%PROJECT_ROOT%app\release\CSQTT-x86.apk"

if exist "%APK_DIR%\app-universal-release.apk" (
    copy /Y "%APK_DIR%\app-universal-release.apk" "%PROJECT_ROOT%app\release\CSQTT-universal.apk" >nul
    for %%F in ("%PROJECT_ROOT%app\release\CSQTT-universal.apk") do echo   [OK] CSQTT-universal.apk  [%%~zF bytes]
) else (
    echo   [!!] Universal APK not found
)

if exist "%APK_DIR%\app-arm64-v8a-release.apk" (
    copy /Y "%APK_DIR%\app-arm64-v8a-release.apk" "%PROJECT_ROOT%app\release\CSQTT-arm64-v8a.apk" >nul
    for %%F in ("%PROJECT_ROOT%app\release\CSQTT-arm64-v8a.apk") do echo   [OK] CSQTT-arm64-v8a.apk  [%%~zF bytes]
) else (
    echo   [!!] arm64-v8a APK not found
)

if exist "%APK_DIR%\app-armeabi-v7a-release.apk" (
    copy /Y "%APK_DIR%\app-armeabi-v7a-release.apk" "%PROJECT_ROOT%app\release\CSQTT-armeabi-v7a.apk" >nul
    for %%F in ("%PROJECT_ROOT%app\release\CSQTT-armeabi-v7a.apk") do echo   [OK] CSQTT-armeabi-v7a.apk  [%%~zF bytes]
) else (
    echo   [!!] armeabi-v7a APK not found
)

if exist "%APK_DIR%\app-x86_64-release.apk" (
    copy /Y "%APK_DIR%\app-x86_64-release.apk" "%PROJECT_ROOT%app\release\CSQTT-x86_64.apk" >nul
    for %%F in ("%PROJECT_ROOT%app\release\CSQTT-x86_64.apk") do echo   [OK] CSQTT-x86_64.apk  [%%~zF bytes]
) else (
    echo   [!!] x86_64 APK not found
)

echo.
echo === DONE ===
echo Output directory: app\release\
echo.
echo   CSQTT-universal.apk    - all architectures in one APK
echo   CSQTT-arm64-v8a.apk    - 64-bit ARM only
echo   CSQTT-armeabi-v7a.apk  - 32-bit ARM only
echo   CSQTT-x86_64.apk       - 64-bit x86 only
echo.
if not defined CI pause
exit /b 0

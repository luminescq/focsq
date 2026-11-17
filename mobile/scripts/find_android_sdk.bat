@echo off
setlocal enabledelayedexpansion


set "SDK_PATH="

if defined ANDROID_HOME (
    if exist "%ANDROID_HOME%" (
        set "SDK_PATH=%ANDROID_HOME%"
    )
)

if not defined SDK_PATH (
    if defined ANDROID_SDK_ROOT (
        if exist "%ANDROID_SDK_ROOT%" (
            set "SDK_PATH=%ANDROID_SDK_ROOT%"
        )
    )
)

if not defined SDK_PATH (
    if exist "%PROJECT_ROOT%local.properties" (
        for /f "tokens=1,* delims==" %%A in ('type "%PROJECT_ROOT%local.properties" ^| findstr /R /C:"^sdk\.dir="') do (
            set "SDK_PATH=%%B"
        )
        if defined SDK_PATH (
            set "SDK_PATH=!SDK_PATH:\\=\!"
            set "SDK_PATH=!SDK_PATH:\:=:!"
            if not exist "!SDK_PATH!" (
                set "SDK_PATH="
            )
        )
    )
)

if not defined SDK_PATH (
    if exist "%LOCALAPPDATA%\Android\Sdk" (
        set "SDK_PATH=%LOCALAPPDATA%\Android\Sdk"
    ) else if exist "%ProgramFiles%\Android\android-sdk" (
        set "SDK_PATH=%ProgramFiles%\Android\android-sdk"
    ) else if exist "%ProgramFiles(x86)%\Android\android-sdk" (
        set "SDK_PATH=%ProgramFiles(x86)%\Android\android-sdk"
    )
)

if not defined SDK_PATH (
    echo Error: Android SDK not found.
    echo        Set ANDROID_HOME or ANDROID_SDK_ROOT, define sdk.dir in local.properties,
    echo        or install Android Studio to the default location.
    exit /b 1
)

echo Using SDK: %SDK_PATH%
endlocal & set "SDK_PATH=%SDK_PATH%"
exit /b 0

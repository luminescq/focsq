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

set "NDK_ROOT="

if defined ANDROID_NDK_HOME (
    if exist "%ANDROID_NDK_HOME%" (
        set "NDK_ROOT=%ANDROID_NDK_HOME%"
    )
)

if not defined NDK_ROOT (
    if defined ANDROID_NDK_ROOT (
        if exist "%ANDROID_NDK_ROOT%" (
            set "NDK_ROOT=%ANDROID_NDK_ROOT%"
        )
    )
)

if not defined NDK_ROOT (
    if defined NDK_HOME (
        if exist "%NDK_HOME%" (
            set "NDK_ROOT=%NDK_HOME%"
        )
    )
)

if not defined NDK_ROOT (
    if exist "%SDK_PATH%\ndk" (
        set "NDK_ROOT=%SDK_PATH%\ndk"
    )
)

if not defined NDK_ROOT (
    if exist "%LOCALAPPDATA%\Android\Sdk\ndk" (
        set "NDK_ROOT=%LOCALAPPDATA%\Android\Sdk\ndk"
    ) else if exist "%ProgramFiles%\Android\ndk" (
        set "NDK_ROOT=%ProgramFiles%\Android\ndk"
    )
)

if not defined NDK_ROOT (
    echo Error: NDK folder not found.
    echo        Set ANDROID_NDK_HOME or ANDROID_NDK_ROOT, install NDK via Android Studio,
    echo        or place it next to the detected SDK under %%SDK_PATH%%\ndk.
    exit /b 1
)

set "NDK_VER="
for /f "delims=" %%D in ('dir /b /ad /o-n "%NDK_ROOT%" 2^>nul') do (
    if not defined NDK_VER if exist "%NDK_ROOT%\%%D\toolchains\llvm\prebuilt\windows-x86_64\bin" (
        set "NDK_VER=%%D"
    )
)

if defined NDK_VER (
    echo Using NDK: !NDK_VER!
    endlocal & set "SDK_PATH=%SDK_PATH%" & set "NDK_PATH=%NDK_ROOT%\%NDK_VER%" & set "NDK_VERSION=%NDK_VER%" & set "TOOLCHAIN=%NDK_ROOT%\%NDK_VER%\toolchains\llvm\prebuilt\windows-x86_64\bin"
    exit /b 0
)

if exist "%NDK_ROOT%\toolchains\llvm\prebuilt\windows-x86_64\bin" (
    echo Using NDK: %NDK_ROOT%
    endlocal & set "SDK_PATH=%SDK_PATH%" & set "NDK_PATH=%NDK_ROOT%" & set "NDK_VERSION=" & set "TOOLCHAIN=%NDK_ROOT%\toolchains\llvm\prebuilt\windows-x86_64\bin"
    exit /b 0
)

echo Error: NDK toolchain not found in %NDK_ROOT%.
exit /b 1

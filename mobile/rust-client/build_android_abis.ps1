param(
    [Parameter(Mandatory = $true)][string]$ClientDir,
    [Parameter(Mandatory = $true)][string]$Arm64TargetDir,
    [Parameter(Mandatory = $true)][string]$Armv7TargetDir,
    [Parameter(Mandatory = $true)][string]$X8664TargetDir
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$cargo = (Get-Command cargo.exe -ErrorAction Stop).Source
$builds = @(
    @{ Abi = "arm64-v8a"; TargetDir = $Arm64TargetDir },
    @{ Abi = "armeabi-v7a"; TargetDir = $Armv7TargetDir },
    @{ Abi = "x86_64"; TargetDir = $X8664TargetDir }
)

$jobsPerAbi = [Math]::Max(1, [int][Math]::Floor([Environment]::ProcessorCount / $builds.Count))
$logsRoot = Join-Path (Split-Path -Parent $ClientDir) "build\rust-client-android-logs"
New-Item -ItemType Directory -Force -Path $logsRoot | Out-Null

Write-Host "========================================================================" -ForegroundColor Cyan
Write-Host "  Building 3 Android Client ABIs in Parallel (Single Window)" -ForegroundColor Cyan
Write-Host "  ABIs: arm64-v8a, armeabi-v7a, x86_64 ($jobsPerAbi jobs each)" -ForegroundColor Cyan
Write-Host "========================================================================" -ForegroundColor Cyan

$tasks = @()

foreach ($build in $builds) {
    New-Item -ItemType Directory -Force -Path $build.TargetDir | Out-Null
    $logFile = Join-Path $logsRoot "$($build.Abi).log"
    if (Test-Path -LiteralPath $logFile) {
        Remove-Item -LiteralPath $logFile -Force -ErrorAction SilentlyContinue
    }

    $cmdArgs = "/d /c (`"$cargo`" ndk -t $($build.Abi) -P 26 build --release --jobs $jobsPerAbi --target-dir `"$($build.TargetDir)`") > `"$logFile`" 2>&1"

    $info = New-Object System.Diagnostics.ProcessStartInfo
    $info.FileName = "cmd.exe"
    $info.Arguments = $cmdArgs
    $info.WorkingDirectory = $ClientDir
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    $info.EnvironmentVariables["CMAKE_BUILD_PARALLEL_LEVEL"] = [string]$jobsPerAbi

    $proc = [System.Diagnostics.Process]::Start($info)

    $tasks += [pscustomobject]@{
        Name = $build.Abi
        TargetDir = $build.TargetDir
        LogFile = $logFile
        Process = $proc
        Stream = $null
        Reader = $null
        CurrentCrate = "initializing"
        ExitCode = $null
        Duration = $null
        WarningCount = 0
        ErrorCount = 0
    }
}

$isInteractive = $false
try {
    if ([Environment]::UserInteractive -and -not [Console]::IsOutputRedirected) {
        $null = [Console]::WindowWidth
        $isInteractive = $true
    }
} catch {
    $isInteractive = $false
}

$sw = [System.Diagnostics.Stopwatch]::StartNew()
$lastDisplayUpdate = [DateTime]::MinValue
$lastNonInteractivePrint = [DateTime]::MinValue
$lastPrintedStatus = ""
$maxStatusLen = 0

try {
    while ($true) {
        $anyRunning = $false

        foreach ($item in $tasks) {
            if (-not $item.Process.HasExited) {
                $anyRunning = $true
            } elseif ($null -eq $item.ExitCode) {
                $item.Process.Refresh()
                $item.ExitCode = $item.Process.ExitCode
                $item.Duration = $sw.Elapsed
            }

            if ($null -eq $item.Reader -and (Test-Path -LiteralPath $item.LogFile)) {
                try {
                    $stream = [System.IO.File]::Open($item.LogFile, [System.IO.FileMode]::Open, [System.IO.FileAccess]::Read, [System.IO.FileShare]::ReadWrite)
                    $item.Stream = $stream
                    $item.Reader = [System.IO.StreamReader]::new($stream, [System.Text.Encoding]::UTF8)
                } catch {
                }
            }

            if ($null -ne $item.Reader) {
                while ($true) {
                    $line = $item.Reader.ReadLine()
                    if ($null -eq $line) { break }
                    $trimmed = $line.Trim()
                    if ([string]::IsNullOrWhiteSpace($trimmed)) { continue }

                    if ($trimmed -match '^(?:Compiling|Checking|Building)\s+([^\s]+)') {
                        $item.CurrentCrate = $Matches[1]
                    } elseif ($trimmed -match '^Finished\s+') {
                        $item.CurrentCrate = "Finishing"
                    }

                    if ($trimmed -match '^(?:warning:\s*|CMake Warning|warning\[\w+\]:\s*|[a-zA-Z0-9_-]+@[\d\.]+: warning:)') {
                        $item.WarningCount++
                        if ($isInteractive) {
                            $pad = " " * [Math]::Min(120, [Math]::Max(80, $maxStatusLen))
                            Write-Host ("`r$pad`r") -NoNewline
                        }
                        Write-Host "  [!][$($item.Name)] $trimmed" -ForegroundColor Yellow
                    }
                    elseif ($trimmed -match '^(?:error:\s*|CMake Error|error\[\w+\]:\s*|error occurred in\s*)') {
                        $item.ErrorCount++
                        if ($isInteractive) {
                            $pad = " " * [Math]::Min(120, [Math]::Max(80, $maxStatusLen))
                            Write-Host ("`r$pad`r") -NoNewline
                        }
                        Write-Host "  [x][$($item.Name)] $trimmed" -ForegroundColor Red
                    }
                }
            }

            if ($item.Process.HasExited -and $null -ne $item.Stream) {
                $item.Reader.Dispose()
                $item.Stream.Dispose()
                $item.Reader = $null
                $item.Stream = $null
                $item.CurrentCrate = if ($item.ExitCode -eq 0) { "Done" } else { "Failed" }
            }
        }

        $now = [DateTime]::UtcNow
        if (($now - $lastDisplayUpdate).TotalMilliseconds -ge 300 -or -not $anyRunning) {
            $lastDisplayUpdate = $now
            $elapsed = $sw.Elapsed.ToString("mm\:ss")

            $parts = foreach ($item in $tasks) {
                if ($null -ne $item.ExitCode) {
                    if ($item.ExitCode -eq 0) {
                        "$($item.Name): [OK]"
                    } else {
                        "$($item.Name): [FAILED]"
                    }
                } else {
                    "$($item.Name): $($item.CurrentCrate)"
                }
            }
            $statusText = "[$elapsed] " + ($parts -join " | ")

            if ($isInteractive) {
                $maxLen = 80
                try { $maxLen = [Math]::Max(40, [Console]::WindowWidth - 2) } catch {}
                if ($statusText.Length -gt $maxLen) {
                    $statusText = $statusText.Substring(0, $maxLen - 3) + "..."
                }
                if ($statusText.Length -lt $maxStatusLen) {
                    $statusText = $statusText.PadRight($maxStatusLen)
                } else {
                    $maxStatusLen = $statusText.Length
                }
                Write-Host -NoNewline ("`r" + $statusText)
            } else {
                if ($statusText -ne $lastPrintedStatus -and (($now - $lastNonInteractivePrint).TotalSeconds -ge 4 -or -not $anyRunning)) {
                    $lastNonInteractivePrint = $now
                    $lastPrintedStatus = $statusText
                    Write-Host $statusText
                }
            }
        }

        if (-not $anyRunning) {
            break
        }
        Start-Sleep -Milliseconds 100
    }
} finally {
    foreach ($item in $tasks) {
        if ($null -ne $item.Stream) {
            try { $item.Reader.Dispose() } catch {}
            try { $item.Stream.Dispose() } catch {}
        }
    }
}

if ($isInteractive) {
    $pad = " " * [Math]::Min(120, [Math]::Max(80, $maxStatusLen))
    Write-Host ("`r$pad`r") -NoNewline
}

Write-Host "`n========================================================================" -ForegroundColor Cyan
Write-Host "  Android Native Build Summary ($($sw.Elapsed.ToString('mm\:ss')) total):" -ForegroundColor Cyan
Write-Host "========================================================================" -ForegroundColor Cyan

$hasFailure = $false

foreach ($item in $tasks) {
    $dur = if ($null -ne $item.Duration) { $item.Duration.ToString("mm\:ss") } else { "N/A" }
    $warns = if ($item.WarningCount -gt 0) { " ($($item.WarningCount) warnings)" } else { "" }

    if ($item.ExitCode -eq 0) {
        Write-Host "  [OK]   $($item.Name) ($dur)$warns" -ForegroundColor Green
    } else {
        Write-Host "  [FAIL] $($item.Name) (ExitCode $($item.ExitCode), $dur)" -ForegroundColor Red
        $hasFailure = $true
    }
}

if ($hasFailure) {
    Write-Host "`n========================================================================" -ForegroundColor Red
    Write-Host "  Build Failures - Log Tail (last 40 lines):" -ForegroundColor Red
    Write-Host "========================================================================" -ForegroundColor Red
    foreach ($item in $tasks) {
        if ($item.ExitCode -ne 0) {
            Write-Host "--- Log for $($item.Name) ($($item.LogFile)) ---" -ForegroundColor Red
            if (Test-Path -LiteralPath $item.LogFile) {
                Get-Content -LiteralPath $item.LogFile -Tail 40 | ForEach-Object {
                    Write-Host "  $_" -ForegroundColor DarkRed
                }
            }
        }
    }
    exit 1
}

Write-Host "All Android client ABIs compiled successfully.`n" -ForegroundColor Green
exit 0

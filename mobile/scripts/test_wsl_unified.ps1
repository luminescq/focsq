# test_wsl_unified.ps1
[CmdletBinding()]
param(
    [string[]]$Distros = @("Ubuntu-24.04", "Debian"),
    [string]$ProjectRoot = "F:\csqtt\csqtt-2.5.0"
)

$ErrorActionPreference = "Continue"

Write-Host ""
Write-Host "================================================================================" -ForegroundColor Cyan
Write-Host "         CSQTT 2.5.0 -- UNIFIED WSL VERIFICATION RUNNER" -ForegroundColor Cyan
Write-Host "================================================================================" -ForegroundColor Cyan
Write-Host "Project Root: $ProjectRoot" -ForegroundColor DarkGray
Write-Host "Distros:      $($Distros -join ', ')" -ForegroundColor DarkGray
Write-Host "Timestamp:    $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')" -ForegroundColor DarkGray
Write-Host ""

$installed = @(wsl.exe -l -q | ForEach-Object { $_.Trim().Replace("`0", "") } | Where-Object { $_ -ne "" })
$results = [ordered]@{}

foreach ($distro in $Distros) {
    Write-Host "--------------------------------------------------------------------------------" -ForegroundColor Yellow
    Write-Host ">>> CHECKING DISTRO: $distro" -ForegroundColor Yellow
    Write-Host "--------------------------------------------------------------------------------" -ForegroundColor Yellow

    if ($installed -notcontains $distro) {
        Write-Host "[-] $distro not installed in WSL. Skipping.`n" -ForegroundColor Red
        $results[$distro] = "NOT_INSTALLED"
        continue
    }

    $bashCmd = "bash /mnt/f/csqtt/csqtt-2.5.0/scripts/run_wsl_checks.sh"
    $output = wsl.exe -d $distro -u root bash -c $bashCmd

    $output | ForEach-Object {
        if ($_ -match "OK") {
            Write-Host "  $_" -ForegroundColor Green
        } elseif ($_ -match "SKIPPED") {
            Write-Host "  $_" -ForegroundColor DarkYellow
        } elseif ($_ -match "FAILED|ERROR") {
            Write-Host "  $_" -ForegroundColor Red
        } else {
            Write-Host "  $_" -ForegroundColor Gray
        }
    }

    $isAllPassed = $output | Select-String -Pattern "ALL SYSTEM CHECKS PASSED SUCCESSFULLY" -SimpleMatch
    if ($isAllPassed) {
        $results[$distro] = "READY_FOR_RELEASE"
    } else {
        $results[$distro] = "FAILED"
    }
    Write-Host ""
}

Write-Host "================================================================================" -ForegroundColor Cyan
Write-Host "FINAL RELEASE READINESS MATRIX:" -ForegroundColor Cyan
Write-Host "================================================================================" -ForegroundColor Cyan
foreach ($d in $results.Keys) {
    $status = $results[$d]
    $color = if ($status -eq "READY_FOR_RELEASE") { "Green" } else { "Red" }
    Write-Host ("Distro: {0,-16} | Status: {1}" -f $d, $status) -ForegroundColor $color
}
Write-Host "================================================================================" -ForegroundColor Cyan
Write-Host ""

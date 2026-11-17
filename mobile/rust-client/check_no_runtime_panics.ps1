$ErrorActionPreference = "Stop"
Push-Location $PSScriptRoot
try {
    & cargo clippy --bin client -- `
        -D clippy::unwrap_used `
        -D clippy::expect_used `
        -D clippy::panic `
        -D clippy::unreachable `
        -D clippy::todo `
        -D clippy::unimplemented
    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }
} finally {
    Pop-Location
}

Write-Output "Runtime panic lint passed"

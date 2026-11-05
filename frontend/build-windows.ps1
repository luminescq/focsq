param(
    [string]$FlutterRoot = (Join-Path $env:USERPROFILE 'flutter'),
    [string]$RustToolchain = '1.97.1'
)

$ErrorActionPreference = 'Stop'
$flutterCommand = Join-Path $FlutterRoot 'bin/flutter.bat'
if (-not (Test-Path -LiteralPath $flutterCommand)) {
    throw "Flutter not found at $flutterCommand. Pass -FlutterRoot with the SDK directory."
}

$rustRoot = (& rustup run $RustToolchain rustc --print sysroot | Out-String).Trim()
if ($LASTEXITCODE -ne 0) {
    throw "Install the required toolchain first: rustup toolchain install $RustToolchain"
}

$previousPath = $env:Path
$previousRustc = $env:RUSTC
$previousRustdoc = $env:RUSTDOC
Push-Location $PSScriptRoot
try {
    $env:Path = (Join-Path $FlutterRoot 'bin') + ';' + $previousPath
    # Bundled Cargokit explicitly selects stable Cargo; select the compiler here.
    $env:RUSTC = Join-Path $rustRoot 'bin/rustc.exe'
    $env:RUSTDOC = Join-Path $rustRoot 'bin/rustdoc.exe'

    & $flutterCommand pub get
    if ($LASTEXITCODE -ne 0) { throw 'Flutter dependency resolution failed.' }

    & $flutterCommand build windows --release --no-pub
    if ($LASTEXITCODE -ne 0) { throw 'Windows release build failed.' }

    Write-Host "Release: $(Join-Path (Get-Location).Path 'build/windows/x64/runner/Release')"
}
finally {
    $env:Path = $previousPath
    $env:RUSTC = $previousRustc
    $env:RUSTDOC = $previousRustdoc
    Pop-Location
}

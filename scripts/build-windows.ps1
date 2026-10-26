$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $projectRoot
$buildPython = Join-Path $projectRoot '.local/build-venv/Scripts/python.exe'
if (-not (Test-Path -LiteralPath $buildPython)) {
    python -m venv .local/build-venv
    if ($LASTEXITCODE -ne 0) { throw 'Cannot create build environment' }
}
& $buildPython -m pip install -r scripts/requirements-build.txt
if ($LASTEXITCODE -ne 0) { throw 'Cannot install build dependencies' }
& $buildPython scripts/create-icon.py
$staticPath = Join-Path $projectRoot 'pipeline/static'
$iconPath = Join-Path $projectRoot 'desktop/assets/icon.ico'
& $buildPython -m PyInstaller --noconfirm --onedir --windowed --name wanlu-backend --distpath .local/backend --workpath .local/pyinstaller --specpath .local --add-data "${staticPath}:static" --icon $iconPath pipeline/server.py
if ($LASTEXITCODE -ne 0) { throw 'Backend build failed' }
& $buildPython scripts/collect-runtime-licenses.py
if ($LASTEXITCODE -ne 0) { throw 'Runtime license collection failed' }
$sdkRoot = $env:ANDROID_HOME
if (-not $sdkRoot) { $sdkRoot = Join-Path $env:LOCALAPPDATA 'Android/Sdk' }
$adbSource = Join-Path $sdkRoot 'platform-tools'
$adbTarget = Join-Path $projectRoot '.local/adb'
New-Item -ItemType Directory -Path $adbTarget -Force | Out-Null
foreach ($name in @('adb.exe','AdbWinApi.dll','AdbWinUsbApi.dll','NOTICE.txt')) {
    $source = Join-Path $adbSource $name
    if (-not (Test-Path -LiteralPath $source)) { throw "Missing Android platform-tools file: $name" }
    Copy-Item -LiteralPath $source -Destination (Join-Path $adbTarget $name)
}
npm install --no-audit --no-fund
if ($LASTEXITCODE -ne 0) { throw 'Node dependency install failed' }
npm run install:desktop
if ($LASTEXITCODE -ne 0) { throw 'Electron runtime install failed' }
npm run package:windows
if ($LASTEXITCODE -ne 0) { throw 'Desktop package failed' }
$artifacts = Get-ChildItem -LiteralPath (Join-Path $projectRoot 'dist-desktop') -File | Where-Object Extension -In '.exe','.zip'
$hashes = foreach ($artifact in $artifacts) {
    $stream = [System.IO.File]::OpenRead($artifact.FullName)
    $sha256 = [System.Security.Cryptography.SHA256]::Create()
    try {
        $digest = [System.BitConverter]::ToString($sha256.ComputeHash($stream)).Replace('-', '').ToLowerInvariant()
        "$digest  $($artifact.Name)"
    } finally { $stream.Dispose(); $sha256.Dispose() }
}
$hashes | Set-Content -LiteralPath (Join-Path $projectRoot 'dist-desktop/SHA256SUMS.txt') -Encoding ascii

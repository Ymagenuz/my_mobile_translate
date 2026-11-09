# Tests the app and builds the signed APK and its checksum into dist\.
# Usage: .\scripts\build_release.ps1
$ErrorActionPreference = 'Stop'
$project = Resolve-Path (Join-Path $PSScriptRoot '..')

if (-not (Test-Path (Join-Path $project 'keystore.properties'))) {
    throw 'keystore.properties is missing; see "Signing" in README.md.'
}

& (Join-Path $PSScriptRoot 'gradle.ps1') testDebugUnitTest lintRelease assembleRelease --console=plain
if ($LASTEXITCODE -ne 0) { throw 'The build or its tests failed.' }

$dist = Join-Path $project 'dist'
New-Item -ItemType Directory -Force $dist | Out-Null
$apk = Join-Path $dist 'SnipDoTranslate.apk'
Copy-Item (Join-Path $project 'app\build\outputs\apk\release\app-release.apk') $apk -Force

$hash = (Get-FileHash $apk -Algorithm SHA256).Hash.ToLower()
Set-Content (Join-Path $dist 'SnipDoTranslate.apk.sha256') "$hash  SnipDoTranslate.apk" -Encoding ascii -NoNewline

"{0:N1} MB  {1}" -f ((Get-Item $apk).Length / 1MB), $apk
"SHA-256 $hash"

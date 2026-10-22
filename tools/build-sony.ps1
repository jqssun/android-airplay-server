param([string]$NativeApk)
$ErrorActionPreference = 'Stop'
$projectPath = Split-Path -Parent $PSScriptRoot
Push-Location -LiteralPath $projectPath
try {
    if (!$NativeApk) {
        New-Item -ItemType Directory -Force -Path .local | Out-Null
        $NativeApk = Join-Path $projectPath '.local\upstream-v0.0.36.apk'
        if (!(Test-Path -LiteralPath $NativeApk)) {
            Invoke-WebRequest -Uri 'https://github.com/jqssun/android-airplay-server/releases/download/v0.0.36/app-release.apk' -OutFile $NativeApk
        }
    }
    $NativeApk = (Resolve-Path -LiteralPath $NativeApk).Path
    $expectedHash = 'B8C7C595ED93F91562FEEDCE92C27800F08F9C84DF424F6AC2045E82324CF2A4'
    if ((Get-FileHash -LiteralPath $NativeApk -Algorithm SHA256).Hash -ne $expectedHash) {
        throw 'The APK is not the official v0.0.36 release. Refusing to use its native libraries.'
    }
    & .\gradlew.bat --no-daemon "-PairplayNativeApk=$NativeApk" testSonyTestUnitTest assembleSonyTest assembleSonyTestAndroidTest assembleSonyRelease lintSonyTest lintSonyRelease
    if ($LASTEXITCODE -ne 0) { throw "Gradle failed: $LASTEXITCODE" }
} finally {
    Pop-Location
}

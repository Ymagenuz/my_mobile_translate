# Runs the Gradle wrapper with the portable toolchain, without changing system settings.
# Usage: .\scripts\gradle.ps1 assembleDebug
param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$GradleArgs
)

$androidRoot = if ($env:SNIPDO_ANDROID_ROOT) { $env:SNIPDO_ANDROID_ROOT } else { 'D:\Android' }
$env:JAVA_HOME = Join-Path $androidRoot 'jdk-21'
$env:ANDROID_HOME = Join-Path $androidRoot 'Sdk'
$env:GRADLE_USER_HOME = Join-Path $androidRoot 'gradle-home'

& (Join-Path $PSScriptRoot '..\gradlew.bat') @GradleArgs
exit $LASTEXITCODE

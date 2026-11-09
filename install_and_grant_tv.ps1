param(
    [Parameter(Mandatory=$false)]
    [string]$TvIpAddress = ""
)

$ErrorActionPreference = "Stop"

$SdkAdb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
$AdbPath = if (Test-Path $SdkAdb) {
    $SdkAdb
} elseif (Get-Command adb -ErrorAction SilentlyContinue) {
    (Get-Command adb).Source
} else {
    throw "adb.exe nao encontrado no PATH nem em $SdkAdb"
}

$ApkPath = Join-Path $PSScriptRoot "app\build\outputs\apk\debug\app-debug.apk"
$PackageName = "com.lotus.ambilighttv"

if (-not (Test-Path $ApkPath)) {
    Write-Error "APK nao encontrado em: $ApkPath. Execute .\gradlew.bat assembleDebug ou baixe o APK na aba Releases."
}

if ($TvIpAddress -ne "") {
    $Target = if ($TvIpAddress -match ":") { $TvIpAddress } else { "${TvIpAddress}:5555" }
    Write-Host "Conectando na Android TV em $Target..." -ForegroundColor Cyan
    & $AdbPath connect $Target
}

Write-Host "Dispositivos ADB conectados:" -ForegroundColor Cyan
& $AdbPath devices -l

Write-Host "Instalando BLEDOB Ambilight TV ($ApkPath) com permissoes (-g)..." -ForegroundColor Green
& $AdbPath install -r -g $ApkPath

Write-Host "Aplicando configuracoes do Android TV 14 (sem pop-up de MediaProjection, sem icone no canto superior direito e sem restricao de bateria)..." -ForegroundColor Yellow
& $AdbPath shell appops set $PackageName PROJECT_MEDIA allow
& $AdbPath shell appops set $PackageName RECORD_AUDIO allow
& $AdbPath shell appops set $PackageName SYSTEM_ALERT_WINDOW allow
& $AdbPath shell cmd deviceidle whitelist +$PackageName
& $AdbPath shell cmd device_config set_sync_disabled_for_tests persistent
& $AdbPath shell cmd device_config put privacy media_projection_indicators_enabled false

Write-Host "Abrindo o aplicativo na Android TV..." -ForegroundColor Green
& $AdbPath shell am start -n "$PackageName/.MainActivity"

Write-Host "Concluido!" -ForegroundColor Cyan

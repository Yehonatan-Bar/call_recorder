param(
    [string]$Device = '',
    [switch]$Install,
    [switch]$Restart,
    [switch]$Probe
)
$ErrorActionPreference = 'Stop'
$taskApk = Join-Path $PSScriptRoot 'dist\Kol-Recorder-1.2.apk'
if (-not (Test-Path -LiteralPath $taskApk)) { throw 'Build the APK first: python build.py' }
if (-not $Device) {
    $taskDevices = @(adb devices | Select-String '^\S+\s+device$' | ForEach-Object { ($_.Line -split '\s+')[0] })
    if ($taskDevices.Count -ne 1) { throw 'Specify -Device with the Pixel ADB serial shown by adb devices.' }
    $Device = $taskDevices[0]
}
function Invoke-DeviceAdb {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments)
    $taskOutput = & adb -s $Device @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw "ADB failed ($Arguments): $taskOutput" }
    return ($taskOutput -join "`n")
}
$taskModel = Invoke-DeviceAdb shell getprop ro.product.device
if ($taskModel.Trim() -ne 'husky') { throw "Expected the Pixel 8 Pro (husky), found $taskModel. Specify the correct -Device." }
# An update/restart must never interrupt an existing user recording.
$taskServices = Invoke-DeviceAdb shell dumpsys activity services il.co.kolrecorder
if ($taskServices -match 'ServiceRecord.*il\.co\.kolrecorder/\.RecordingService') {
    throw 'A recording service is active. Stop and save it in the app before updating or restarting.'
}
$taskPidText = Invoke-DeviceAdb shell 'pidof kol_callbridge || true'
$taskPids = @($taskPidText.Trim() -split '\s+' | Where-Object { $_ -match '^\d+$' })
if ($taskPids.Count -gt 0 -and ($Restart -or $Install)) {
    foreach ($taskPid in $taskPids) {
        $taskIdentity = Invoke-DeviceAdb shell "tr '\000' '\n' < /proc/$taskPid/cmdline | head -n 1"
        if ($taskIdentity.Trim() -ne 'kol_callbridge') { throw "Refusing to stop unrecognized process $taskPid" }
        Write-Host (Invoke-DeviceAdb shell kill -TERM $taskPid)
    }
    for ($taskAttempt = 0; $taskAttempt -lt 20; $taskAttempt++) {
        Start-Sleep -Milliseconds 200
        $taskRemaining = Invoke-DeviceAdb shell 'pidof kol_callbridge || true'
        if ([string]::IsNullOrWhiteSpace($taskRemaining)) { break }
    }
    if (-not [string]::IsNullOrWhiteSpace($taskRemaining)) { throw 'The existing helper did not stop. No update was performed.' }
    $taskPids = @()
}
if ($Install) { Write-Host (Invoke-DeviceAdb install -r $taskApk) }
$taskPackage = Invoke-DeviceAdb shell dumpsys package il.co.kolrecorder
if ($taskPackage -notmatch 'versionCode=3\b') { throw 'Install this release with -Install before starting its helper.' }
if ($taskPids.Count -eq 0) {
    Write-Host (Invoke-DeviceAdb push $taskApk /data/local/tmp/kol-callbridge.apk)
    if ($Probe) {
        Write-Host (Invoke-DeviceAdb shell 'CLASSPATH=/data/local/tmp/kol-callbridge.apk app_process /system/bin il.co.kolrecorder.CallBridgeMain --probe')
    }
    Write-Host (Invoke-DeviceAdb shell 'CLASSPATH=/data/local/tmp/kol-callbridge.apk setsid nohup app_process /system/bin --nice-name=kol_callbridge il.co.kolrecorder.CallBridgeMain --serve > /data/local/tmp/kol-callbridge.log 2>&1 < /dev/null &')
} else { Write-Host 'Existing helper retained. Use -Restart to replace it when no recording is active.' }
Write-Host (Invoke-DeviceAdb shell am start -n il.co.kolrecorder/.MainActivity)
$taskReady = $false
for ($taskAttempt = 0; $taskAttempt -lt 15; $taskAttempt++) {
    Start-Sleep -Seconds 1
    $taskStatus = Invoke-DeviceAdb shell content call --uri content://il.co.kolrecorder.bridge --method STATUS
    if ($taskStatus -match 'registered=true') { $taskReady = $true; break }
}
Write-Host (Invoke-DeviceAdb shell cat /data/local/tmp/kol-callbridge.log)
if (-not $taskReady) { throw 'Helper did not register. Review the log above; call recording is unavailable.' }
Write-Host 'Helper registered. Verify the green indicator in Kol. This does not prove that both voices can be captured.'
Write-Host 'Repeat after reboot, ADB daemon restart, or helper termination. No computer connection is needed while the helper survives.'

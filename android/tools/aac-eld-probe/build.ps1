param(
    [Parameter(Mandatory=$true)][string]$OutputDirectory,
    [string]$AndroidSdk = $env:ANDROID_HOME,
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$BuildToolsVersion = '36.0.0',
    [string]$Platform = 'android-36',
    [string]$DebugKeystore
)
$ErrorActionPreference = 'Stop'
if (-not $AndroidSdk) { $AndroidSdk = $env:ANDROID_SDK_ROOT }
if (-not $AndroidSdk -or -not $JavaHome) { throw 'Set ANDROID_HOME and JAVA_HOME, or pass -AndroidSdk and -JavaHome.' }
$toolRoot = $PSScriptRoot
$probeOutput = [IO.Path]::GetFullPath($OutputDirectory)
if ($probeOutput.StartsWith($toolRoot + [IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)) { throw 'Keep generated artifacts outside the source directory.' }
if (Test-Path -LiteralPath $probeOutput) { throw 'Use a new output directory; previous evidence is never overwritten.' }
$androidJar = Join-Path $AndroidSdk "platforms/$Platform/android.jar"
$buildTools = Join-Path $AndroidSdk "build-tools/$BuildToolsVersion"
$exeSuffix = if ($IsWindows -or $env:OS -eq 'Windows_NT') { '.exe' } else { '' }
$javac = Join-Path $JavaHome "bin/javac$exeSuffix"
$java = Join-Path $JavaHome "bin/java$exeSuffix"
$jar = Join-Path $JavaHome "bin/jar$exeSuffix"
$aapt2 = Join-Path $buildTools "aapt2$exeSuffix"
$zipalign = Join-Path $buildTools "zipalign$exeSuffix"
$d8Jar = Join-Path $buildTools 'lib/d8.jar'
$signerJar = Join-Path $buildTools 'lib/apksigner.jar'
foreach ($required in @($androidJar,$javac,$java,$jar,$aapt2,$zipalign,$d8Jar)) {
    if (-not (Test-Path -LiteralPath $required)) { throw "Missing build tool: $required" }
}
if ($DebugKeystore -and -not (Test-Path -LiteralPath $DebugKeystore)) { throw 'Debug keystore does not exist.' }
New-Item -ItemType Directory -Path $probeOutput | Out-Null
$hostClasses = New-Item -ItemType Directory -Path (Join-Path $probeOutput 'host-classes')
$androidClasses = New-Item -ItemType Directory -Path (Join-Path $probeOutput 'android-classes')
$dexDirectory = New-Item -ItemType Directory -Path (Join-Path $probeOutput 'dex')
function Invoke-Checked([string]$Tool, [string[]]$ToolArgs) {
    & $Tool @ToolArgs
    if ($LASTEXITCODE -ne 0) { throw "Build tool exited $LASTEXITCODE : $([IO.Path]::GetFileName($Tool))" }
}
$contract = Join-Path $toolRoot 'src/me/kavishdevar/librepods/eldprobe/ProbeContract.java'
$hostCheck = Join-Path $toolRoot 'host/ProbeContractCheck.java'
Invoke-Checked -Tool $javac -ToolArgs @('--release','8','-d',$hostClasses.FullName,$contract,$hostCheck)
$hostResult = & $java '-cp' $hostClasses.FullName 'me.kavishdevar.librepods.eldprobe.ProbeContractCheck'
if ($LASTEXITCODE -ne 0) { throw 'Offline contract self-check failed.' }
$hostResult | Write-Output
$androidSource = @(Get-ChildItem -LiteralPath (Join-Path $toolRoot 'src') -Filter '*.java' -Recurse | ForEach-Object FullName)
Invoke-Checked -Tool $javac -ToolArgs (@('-source','8','-target','8','-bootclasspath',$androidJar,'-d',$androidClasses.FullName)+$androidSource)
$classesJar = Join-Path $probeOutput 'classes.jar'
Invoke-Checked -Tool $jar -ToolArgs @('--create','--file',$classesJar,'-C',$androidClasses.FullName,'.')
Invoke-Checked -Tool $java -ToolArgs @('-cp',$d8Jar,'com.android.tools.r8.D8','--min-api','29','--lib',$androidJar,'--output',$dexDirectory.FullName,$classesJar)
$baseApk = Join-Path $probeOutput 'probe-unaligned.apk'
Invoke-Checked -Tool $aapt2 -ToolArgs @('link','-I',$androidJar,'--manifest',(Join-Path $toolRoot 'AndroidManifest.xml'),'--min-sdk-version','29','--target-sdk-version','33','-o',$baseApk)
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [IO.Compression.ZipFile]::Open($baseApk,[IO.Compression.ZipArchiveMode]::Update)
try { [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip,(Join-Path $dexDirectory 'classes.dex'),'classes.dex',[IO.Compression.CompressionLevel]::Optimal) | Out-Null }
finally { $zip.Dispose() }
$unsignedApk = Join-Path $probeOutput 'aac-eld-probe-unsigned.apk'
Invoke-Checked -Tool $zipalign -ToolArgs @('-f','4',$baseApk,$unsignedApk)
$finalApk = $unsignedApk
if ($DebugKeystore) {
    # Only the standard Android debug key/password; never use a release signing key here.
    $finalApk = Join-Path $probeOutput 'aac-eld-probe-debug.apk'
    Invoke-Checked -Tool $java -ToolArgs @('-jar',$signerJar,'sign','--ks',$DebugKeystore,'--ks-key-alias','androiddebugkey','--ks-pass','pass:android','--out',$finalApk,$unsignedApk)
    Invoke-Checked -Tool $java -ToolArgs @('-jar',$signerJar,'verify','--verbose',$finalApk)
}
Invoke-Checked -Tool $aapt2 -ToolArgs @('dump','badging',$finalApk)
$sourceFiles = @(Get-ChildItem -LiteralPath $toolRoot -File -Recurse | Where-Object {
    $_.Extension -in '.java','.xml','.ps1','.md'
} | ForEach-Object {
    [ordered]@{path=[IO.Path]::GetRelativePath($toolRoot,$_.FullName).Replace('\','/');sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()}
})
$receipt = [ordered]@{
    schema=1;created_utc=[DateTime]::UtcNow.ToString('o');runtime_executed=$false;hardware_accessed=$false
    package='me.kavishdevar.librepods.eldprobe';version_code=1;version_name='0.1-offline-probe'
    min_sdk=29;target_sdk=33;compile_platform=$Platform;build_tools=$BuildToolsVersion
    host_checks=@($hostResult);android_compile='passed';dex='passed';apk_signature= $(if($DebugKeystore){'standard debug key verified'}else{'unsigned'})
    apk=[IO.Path]::GetFileName($finalApk);apk_bytes=(Get-Item -LiteralPath $finalApk).Length
    apk_sha256=(Get-FileHash -LiteralPath $finalApk -Algorithm SHA256).Hash.ToLowerInvariant()
    sources=$sourceFiles
    limits=@('No device codec was queried or executed.','No encoder capability or Apple transport compatibility is established by this build.')
}
$receipt | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $probeOutput 'receipt.json') -Encoding utf8
Write-Output "Offline build complete: $finalApk"

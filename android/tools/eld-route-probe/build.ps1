param(
    [Parameter(Mandatory=$true)][string]$OutputDirectory,
    [string]$AndroidSdk = $env:ANDROID_HOME,
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$BuildToolsVersion = '36.0.0',
    [string]$Platform = 'android-36'
)
$ErrorActionPreference = 'Stop'
if (-not $AndroidSdk) { $AndroidSdk = $env:ANDROID_SDK_ROOT }
if (-not $AndroidSdk -or -not $JavaHome) { throw 'Pass -AndroidSdk and -JavaHome, or set ANDROID_HOME and JAVA_HOME.' }
$probeOutput = [IO.Path]::GetFullPath($OutputDirectory)
if ($probeOutput.Equals($PSScriptRoot,[StringComparison]::OrdinalIgnoreCase) -or $probeOutput.StartsWith($PSScriptRoot + [IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)) { throw 'Keep artifacts outside source.' }
if (Test-Path -LiteralPath $probeOutput) { throw 'Use a fresh output directory; previous evidence is not overwritten.' }
$androidJar = Join-Path $AndroidSdk "platforms/$Platform/android.jar"
$buildTools = Join-Path $AndroidSdk "build-tools/$BuildToolsVersion"
$exe = if ($IsWindows -or $env:OS -eq 'Windows_NT') { '.exe' } else { '' }
$javac = Join-Path $JavaHome "bin/javac$exe"
$java = Join-Path $JavaHome "bin/java$exe"
$jar = Join-Path $JavaHome "bin/jar$exe"
$aapt2 = Join-Path $buildTools "aapt2$exe"
$zipalign = Join-Path $buildTools "zipalign$exe"
$d8 = Join-Path $buildTools 'lib/d8.jar'
foreach ($required in @($androidJar,$javac,$java,$jar,$aapt2,$zipalign,$d8)) {
    if (-not (Test-Path -LiteralPath $required)) { throw "Missing build tool: $required" }
}
function Invoke-Checked([string]$Tool, [string[]]$ToolArgs) {
    & $Tool @ToolArgs
    if ($LASTEXITCODE -ne 0) { throw "Build tool exited $LASTEXITCODE : $([IO.Path]::GetFileName($Tool))" }
}
New-Item -ItemType Directory -Path $probeOutput | Out-Null
$hostClasses = New-Item -ItemType Directory -Path (Join-Path $probeOutput 'host-classes')
$androidClasses = New-Item -ItemType Directory -Path (Join-Path $probeOutput 'android-classes')
$dexDirectory = New-Item -ItemType Directory -Path (Join-Path $probeOutput 'dex')
$sourceRoot = Join-Path $PSScriptRoot 'src/me/kavishdevar/librepods/eldroute'
Invoke-Checked $javac @('--release','8','-Xlint:all,-options','-Werror','-d',$hostClasses.FullName,
    (Join-Path $sourceRoot 'RouteRequest.java'),(Join-Path $sourceRoot 'CodecCarriers.java'),
    (Join-Path $PSScriptRoot 'host/RouteContractCheck.java'))
$hostResult = & $java '-cp' $hostClasses.FullName 'me.kavishdevar.librepods.eldroute.RouteContractCheck'
if ($LASTEXITCODE -ne 0) { throw 'Offline route contract self-check failed.' }
$hostResult | Write-Output
$sources = @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'src') -Filter '*.java' -Recurse | ForEach-Object FullName)
Invoke-Checked $javac (@('-source','8','-target','8','-bootclasspath',$androidJar,'-d',$androidClasses.FullName)+$sources)
$classes = Join-Path $probeOutput 'classes.jar'
Invoke-Checked $jar @('--create','--file',$classes,'-C',$androidClasses.FullName,'.')
Invoke-Checked $java @('-cp',$d8,'com.android.tools.r8.D8','--min-api','29','--lib',$androidJar,'--output',$dexDirectory.FullName,$classes)
$base = Join-Path $probeOutput 'probe-unaligned.apk'
Invoke-Checked $aapt2 @('link','-I',$androidJar,'--manifest',(Join-Path $PSScriptRoot 'AndroidManifest.xml'),
    '--min-sdk-version','29','--target-sdk-version','33','-o',$base)
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [IO.Compression.ZipFile]::Open($base,[IO.Compression.ZipArchiveMode]::Update)
try { [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive,(Join-Path $dexDirectory 'classes.dex'),'classes.dex',[IO.Compression.CompressionLevel]::Optimal) | Out-Null }
finally { $archive.Dispose() }
$unsigned = Join-Path $probeOutput 'eld-route-probe-unsigned.apk'
Invoke-Checked $zipalign @('-f','4',$base,$unsigned)
Invoke-Checked $aapt2 @('dump','badging',$unsigned)
$sourceFiles = @(Get-ChildItem -LiteralPath $PSScriptRoot -File -Recurse | ForEach-Object {
    [ordered]@{path=[IO.Path]::GetRelativePath($PSScriptRoot,$_.FullName).Replace('\','/');sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()}
})
[ordered]@{
    schema=1;created_utc=[DateTime]::UtcNow.ToString('o');runtime_executed=$false;hardware_accessed=$false
    package='me.kavishdevar.librepods.eldroute';target_package='me.kavishdevar.librepods'
    host_checks=@($hostResult);android_compile='passed';dex='passed';apk_signature='unsigned'
    apk=[IO.Path]::GetFileName($unsigned);apk_bytes=(Get-Item -LiteralPath $unsigned).Length
    apk_sha256=(Get-FileHash -LiteralPath $unsigned -Algorithm SHA256).Hash.ToLowerInvariant()
    compile_platform=$Platform;build_tools=$BuildToolsVersion;sources=$sourceFiles
    limits=@('Build only; no instrumentation, install, Bluetooth or audio operation was performed.',
        'Use the existing target-app signing process; no key is created or read by this build.',
        'The controller must externally bound each runtime command to 25 seconds.')
} | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $probeOutput 'receipt.json') -Encoding utf8
Write-Output "Offline unsigned build complete: $unsigned"

param(
    [Parameter(Mandatory=$true)][string]$OutputDirectory,
    [Parameter(Mandatory=$true)][string]$AndroidSdk,
    [Parameter(Mandatory=$true)][string]$AndroidNdk,
    [Parameter(Mandatory=$true)][string]$JavaHome,
    [string]$Platform = 'android-37.0',
    [string]$BuildToolsVersion = '36.0.0'
)
$ErrorActionPreference = 'Stop'
$probeOutput = [IO.Path]::GetFullPath($OutputDirectory)
if ($probeOutput.Equals($PSScriptRoot,[StringComparison]::OrdinalIgnoreCase) -or
    $probeOutput.StartsWith($PSScriptRoot + [IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)) {
    throw 'Keep generated artifacts outside the source directory.'
}
if (Test-Path -LiteralPath $probeOutput) { throw 'Use a new output directory; previous evidence is never overwritten.' }
$androidJar = Join-Path $AndroidSdk "platforms/$Platform/android.jar"
$javac = Join-Path $JavaHome 'bin/javac.exe'
$java = Join-Path $JavaHome 'bin/java.exe'
$jar = Join-Path $JavaHome 'bin/jar.exe'
$d8 = Join-Path $AndroidSdk "build-tools/$BuildToolsVersion/lib/d8.jar"
$clang = Join-Path $AndroidNdk 'toolchains/llvm/prebuilt/windows-x86_64/bin/aarch64-linux-android33-clang.cmd'
foreach ($required in @($androidJar,$javac,$java,$jar,$d8,$clang)) {
    if (-not (Test-Path -LiteralPath $required)) { throw "Missing build tool: $required" }
}
function Invoke-Checked([string]$Tool, [string[]]$ToolArgs) {
    & $Tool @ToolArgs
    if ($LASTEXITCODE -ne 0) { throw "Build tool exited $LASTEXITCODE : $Tool" }
}
New-Item -ItemType Directory -Path $probeOutput | Out-Null
$hostClasses = New-Item -ItemType Directory -Path (Join-Path $probeOutput 'host-classes')
$classes = New-Item -ItemType Directory -Path (Join-Path $probeOutput 'classes')
$dex = New-Item -ItemType Directory -Path (Join-Path $probeOutput 'dex')
$state = Join-Path $PSScriptRoot 'LeaseState.java'
Invoke-Checked -Tool $javac -ToolArgs @('--release','8','-d',$hostClasses.FullName,$state,
    (Join-Path $PSScriptRoot 'LeaseStateCheck.java'))
Invoke-Checked -Tool $java -ToolArgs @('-cp',$hostClasses.FullName,'me.kavishdevar.librepods.headtrackerlease.LeaseStateCheck')
$linuxSource = (& wsl.exe -e wslpath -a $PSScriptRoot).Trim()
if ($LASTEXITCODE -ne 0) { throw 'Cannot resolve the source directory in WSL.' }
$linuxOutput = (& wsl.exe -e wslpath -a $probeOutput).Trim()
if ($LASTEXITCODE -ne 0) { throw 'Cannot resolve the output directory in WSL.' }
Invoke-Checked -Tool 'wsl.exe' -ToolArgs @('-e','gcc','-std=c11','-Wall','-Wextra','-Werror','-UNDEBUG',
    '-DSIGNAL_GUARD_HOST_TEST',"$linuxSource/signals.c",'-o',"$linuxOutput/signals_check")
Invoke-Checked -Tool 'wsl.exe' -ToolArgs @('-e',"$linuxOutput/signals_check")
Invoke-Checked -Tool $javac -ToolArgs @('--release','8','-classpath',$androidJar,'-d',$classes.FullName,
    $state,(Join-Path $PSScriptRoot 'HeadTrackerLease.java'))
$classesJar = Join-Path $probeOutput 'classes.jar'
Invoke-Checked -Tool $jar -ToolArgs @('--create','--file',$classesJar,'-C',$classes.FullName,'.')
Invoke-Checked -Tool $java -ToolArgs @('-cp',$d8,'com.android.tools.r8.D8','--min-api','33',
    '--lib',$androidJar,'--output',$dex.FullName,$classesJar)
$dexJar = Join-Path $probeOutput 'head-tracker-lease.jar'
Invoke-Checked -Tool $jar -ToolArgs @('--create','--file',$dexJar,'-C',$dex.FullName,'classes.dex')
$library = Join-Path $probeOutput 'libheadtrackerlease.so'
Invoke-Checked -Tool $clang -ToolArgs @('-std=c11','-Wall','-Wextra','-Werror','-O2','-shared','-fPIC',
    (Join-Path $PSScriptRoot 'signals.c'),'-o',$library)
$receipt = [ordered]@{
    created_utc=[DateTime]::UtcNow.ToString('o');host_checks='passed';android_compile='passed';dex='passed'
    device_accessed=$false;framework_methods_invoked=$false;apk_created=$false;minimum_android_api=33
    main_class='me.kavishdevar.librepods.headtrackerlease.HeadTrackerLease';maximum_hold_seconds=25
    artifacts=@(@($dexJar,$library) | ForEach-Object {
        [ordered]@{path=$_;bytes=(Get-Item -LiteralPath $_).Length;sha256=(Get-FileHash -LiteralPath $_ -Algorithm SHA256).Hash.ToLowerInvariant()}
    })
    sources=@(Get-ChildItem -LiteralPath $PSScriptRoot -File | ForEach-Object {
        [ordered]@{path=$_.Name;sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()}
    })
}
$receipt | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $probeOutput 'receipt.json') -Encoding utf8
foreach ($artifact in $receipt.artifacts) { Write-Output "$($artifact.path) SHA256 $($artifact.sha256)" }

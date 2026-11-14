param(
    [Parameter(Mandatory=$true)][string]$OutputDirectory,
    [Parameter(Mandatory=$true)][string]$AndroidSdk,
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
foreach ($required in @($androidJar,$javac,$java,$jar,$d8)) {
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
$contract = Join-Path $PSScriptRoot 'InspectorContract.java'
Invoke-Checked -Tool $javac -ToolArgs @('--release','8','-d',$hostClasses.FullName,$contract,
    (Join-Path $PSScriptRoot 'InspectorContractCheck.java'))
Invoke-Checked -Tool $java -ToolArgs @('-cp',$hostClasses.FullName,
    'me.kavishdevar.librepods.spatialinspector.InspectorContractCheck')
Invoke-Checked -Tool $javac -ToolArgs @('--release','8','-classpath',$androidJar,'-d',$classes.FullName,
    $contract,(Join-Path $PSScriptRoot 'SpatialInspector.java'))
$classesJar = Join-Path $probeOutput 'classes.jar'
Invoke-Checked -Tool $jar -ToolArgs @('--create','--file',$classesJar,'-C',$classes.FullName,'.')
Invoke-Checked -Tool $java -ToolArgs @('-cp',$d8,'com.android.tools.r8.D8','--min-api','33',
    '--lib',$androidJar,'--output',$dex.FullName,$classesJar)
$dexJar = Join-Path $probeOutput 'spatial-inspector.jar'
Invoke-Checked -Tool $jar -ToolArgs @('--create','--file',$dexJar,'-C',$dex.FullName,'classes.dex')
$receipt = [ordered]@{
    created_utc=[DateTime]::UtcNow.ToString('o');host_checks='passed';android_compile='passed';dex='passed'
    device_accessed=$false;framework_methods_invoked=$false;apk_created=$false;minimum_android_api=33
    main_class='me.kavishdevar.librepods.spatialinspector.SpatialInspector'
    artifact=$dexJar;bytes=(Get-Item -LiteralPath $dexJar).Length
    sha256=(Get-FileHash -LiteralPath $dexJar -Algorithm SHA256).Hash.ToLowerInvariant()
    sources=@(Get-ChildItem -LiteralPath $PSScriptRoot -File | ForEach-Object {
        [ordered]@{path=$_.Name;sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()}
    })
}
$receipt | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $probeOutput 'receipt.json') -Encoding utf8
Write-Output "Offline build complete: $dexJar"
Write-Output "SHA256: $($receipt.sha256)"

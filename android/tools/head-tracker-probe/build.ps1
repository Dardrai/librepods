param(
    [Parameter(Mandatory=$true)][string]$OutputDirectory,
    [Parameter(Mandatory=$true)][string]$AndroidNdk
)
$ErrorActionPreference = 'Stop'
$probeOutput = [IO.Path]::GetFullPath($OutputDirectory)
if ($probeOutput.Equals($PSScriptRoot, [StringComparison]::OrdinalIgnoreCase) -or
    $probeOutput.StartsWith($PSScriptRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Keep generated artifacts outside the source directory.'
}
if (Test-Path -LiteralPath $probeOutput) { throw 'Use a new output directory; existing results are never overwritten.' }
$clang = Join-Path $AndroidNdk 'toolchains/llvm/prebuilt/windows-x86_64/bin/aarch64-linux-android33-clang++.cmd'
if (-not (Test-Path -LiteralPath $clang)) { throw "Missing Android NDK compiler: $clang" }
function Invoke-Checked([string]$Tool, [string[]]$ToolArgs) {
    & $Tool @ToolArgs
    if ($LASTEXITCODE -ne 0) { throw "Tool exited $LASTEXITCODE : $Tool" }
}
Invoke-Checked -Tool 'wsl.exe' -ToolArgs @('-e','g++','--version')
New-Item -ItemType Directory -Path $probeOutput | Out-Null
$linuxSource = (& wsl.exe -e wslpath -a $PSScriptRoot).Trim()
if ($LASTEXITCODE -ne 0) { throw 'Cannot resolve the source directory in WSL.' }
$linuxOutput = (& wsl.exe -e wslpath -a $probeOutput).Trim()
if ($LASTEXITCODE -ne 0) { throw 'Cannot resolve the output directory in WSL.' }
Invoke-Checked -Tool 'wsl.exe' -ToolArgs @('-e','g++','-std=c++17','-Wall','-Wextra','-Werror','-UNDEBUG',
    "$linuxSource/protocol_test.cpp",'-o',"$linuxOutput/protocol_test")
Invoke-Checked -Tool 'wsl.exe' -ToolArgs @('-e',"$linuxOutput/protocol_test")
Invoke-Checked -Tool $clang -ToolArgs @('-std=c++17','-Wall','-Wextra','-Werror','-O2','-static-libstdc++',
    (Join-Path $PSScriptRoot 'probe.cpp'),'-o',(Join-Path $probeOutput 'head-tracker-probe'))
$binary = Get-Item -LiteralPath (Join-Path $probeOutput 'head-tracker-probe')
$receipt = [ordered]@{
    created_utc=[DateTime]::UtcNow.ToString('o');host_checks='passed';android_compile='passed'
    device_accessed=$false;live_uhid_created=$false;abi='arm64-v8a';minimum_android_api=33
    binary=$binary.FullName;bytes=$binary.Length
    sha256=(Get-FileHash -LiteralPath $binary.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    sources=@(Get-ChildItem -LiteralPath $PSScriptRoot -File | ForEach-Object {
        [ordered]@{path=$_.Name;sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()}
    })
}
$receipt | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $probeOutput 'receipt.json') -Encoding utf8
Write-Output "Offline build complete: $($binary.FullName)"
Write-Output "SHA256: $($receipt.sha256)"

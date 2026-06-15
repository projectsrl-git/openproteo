param(
    [Parameter(Mandatory=$true)][string]$Source,
    [Parameter(Mandatory=$true)][string]$WorkDir,
    [Parameter(Mandatory=$true)][string]$Target,
    [Parameter(Mandatory=$true)][string]$FeedId,
    [Parameter(Mandatory=$true)][string]$RunId
)
# Demo preparation step: copies the files from landing IN into the step working
# directory, compresses them into a ZIP package named after feedId+runId and
# drops it into landing OUT.

Write-Output "[$FeedId] Preparing package for run $RunId"

$staging = Join-Path $WorkDir $RunId
New-Item -ItemType Directory -Force -Path $staging | Out-Null
Copy-Item -Path (Join-Path $Source '*') -Destination $staging -Force

$zipName = "{0}_{1}.zip" -f $FeedId, $RunId
$zipPath = Join-Path $Target $zipName
if (Test-Path -LiteralPath $zipPath) { Remove-Item -LiteralPath $zipPath -Force }

Add-Type -AssemblyName System.IO.Compression.FileSystem
[System.IO.Compression.ZipFile]::CreateFromDirectory($staging, $zipPath)

$sizeKB = [math]::Round((Get-Item -LiteralPath $zipPath).Length / 1KB, 1)
Write-Output "Created package: $zipPath ($sizeKB KB)"

Write-Output "##VAR packageFile=$zipPath"
Write-Output "##VAR packageSizeKB=$sizeKB"
exit 0

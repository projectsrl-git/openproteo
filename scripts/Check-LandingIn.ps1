param(
    [Parameter(Mandatory=$true)][string]$Path,
    [Parameter(Mandatory=$true)][string]$FeedId
)
# Counts the files currently sitting in the feed landing IN directory.
# Orchestrator convention: output variables are emitted as "##VAR name=value" lines.

Write-Output "[$FeedId] Checking landing: $Path"

if (-not (Test-Path -LiteralPath $Path)) {
    Write-Output "ERROR: directory does not exist"
    exit 2
}

$files = Get-ChildItem -LiteralPath $Path -File
$count = ($files | Measure-Object).Count
Write-Output "Found $count file(s)"
$files | ForEach-Object { Write-Output ("  - " + $_.Name + "  (" + $_.Length + " bytes)") }

Write-Output "##VAR fileCount=$count"
exit 0

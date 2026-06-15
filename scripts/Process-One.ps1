param(
    [Parameter(Mandatory=$true)][string]$File,
    [Parameter(Mandatory=$true)][string]$FeedId
)
# Demo per-item worker used by a parallel for-each step.
Write-Output "[$FeedId] Worker handling: $File"
Start-Sleep -Seconds 1
Write-Output "Done $File"
exit 0

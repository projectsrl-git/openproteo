param(
    [Parameter(Mandatory=$true)][string]$Iteration,
    [Parameter(Mandatory=$true)][string]$FeedId
)
# Demo unit of work for the loop sample.
Write-Output "[$FeedId] Loop iteration $Iteration"
Start-Sleep -Seconds 1
exit 0

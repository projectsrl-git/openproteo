param(
    [Parameter(Mandatory=$true)][string]$Files,
    [Parameter(Mandatory=$true)][string]$FeedId
)
# Demo processing of a set of detected files (passed as a delimited list).
Write-Output "[$FeedId] Processing detected files"
$list = $Files -split ';' | Where-Object { $_ -ne '' }
Write-Output ("Count: " + $list.Count)
foreach ($f in $list) { Write-Output "  processing $f" }
Write-Output "##VAR processed=$($list.Count)"
exit 0

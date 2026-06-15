param(
    [Parameter(Mandatory=$true)][string]$PackageFile,
    [Parameter(Mandatory=$true)][string]$FtpsHost,
    [Parameter(Mandatory=$true)][string]$RemoteFolder,
    [Parameter(Mandatory=$true)][string]$FeedId
)
# Demo FTPS delivery step: plug in the real client here (WinSCP .NET assembly,
# curl.exe, or System.Net.FtpWebRequest with EnableSsl). This skeleton validates
# the package and simulates the upload, propagating the exit code so the
# orchestrator retry logic works.
# Note: do not name a parameter $Host - it is a reserved PowerShell variable.

Write-Output "[$FeedId] Sending $PackageFile to ftps://$FtpsHost$RemoteFolder"

if (-not (Test-Path -LiteralPath $PackageFile)) {
    Write-Output "ERROR: package not found"
    exit 3
}

# --- WinSCP example (uncomment and adapt) ------------------------------------
# Add-Type -Path 'C:\Program Files (x86)\WinSCP\WinSCPnet.dll'
# $opt = New-Object WinSCP.SessionOptions -Property @{
#     Protocol   = [WinSCP.Protocol]::Ftp
#     FtpSecure  = [WinSCP.FtpSecure]::Explicit
#     HostName   = $FtpsHost
#     UserName   = $env:FTPS_USER
#     Password   = $env:FTPS_PASS
# }
# $session = New-Object WinSCP.Session
# try {
#     $session.Open($opt)
#     $res = $session.PutFiles($PackageFile, ($RemoteFolder + '/'))
#     $res.Check()
# } finally { $session.Dispose() }
# ------------------------------------------------------------------------------

Start-Sleep -Seconds 2
Write-Output "Simulated upload completed"
exit 0

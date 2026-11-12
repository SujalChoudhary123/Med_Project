# Puts MedTrack on your server from this PC, and updates it later with the same command.
#
#   .\deploy\deploy-from-pc.ps1 -Ip 1.2.3.4 -Domain medtrack.duckdns.org -Key C:\Users\you\.ssh\medtrack.key
#
# It packs the project, copies it to the server, creates the server's settings from your
# server.env (first time only) and runs deploy/install.sh there.
# -User    the login on the server (Oracle's Ubuntu images use "ubuntu")
# -DryRun  shows what would be sent, and sends nothing

param(
    [Parameter(Mandatory = $true)][string]$Ip,
    [Parameter(Mandatory = $true)][string]$Domain,
    [Parameter(Mandatory = $true)][string]$Key,
    [string]$User = "ubuntu",
    [switch]$DryRun
)

$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent
Set-Location $root

if (-not (Test-Path $Key)) { throw "Key file not found: $Key" }
if (-not (Test-Path "server.env")) { throw "server.env not found. Copy server.env.example and fill it in." }
if ($Domain -notmatch '^[a-z0-9.-]+\.[a-z]{2,}$') { throw "Give the domain only, like medtrack.duckdns.org (no https://, no slash)." }

# ---- settings for the server, taken from server.env ----
$settings = @{}
foreach ($raw in Get-Content "server.env") {
    $line = $raw.Trim()
    if ($line -eq "" -or $line.StartsWith("#")) { continue }
    $index = $line.IndexOf("=")
    if ($index -gt 0) { $settings[$line.Substring(0, $index).Trim()] = $line.Substring($index + 1).Trim().Trim('"') }
}

foreach ($name in "SMTP_EMAIL", "SMTP_APP_PASSWORD", "GOOGLE_CLIENT_ID", "ADMIN_EMAILS") {
    if (-not $settings[$name]) { throw "$name is missing from server.env" }
}

$lines = @("DOMAIN=$Domain")
foreach ($name in "SMTP_EMAIL", "SMTP_APP_PASSWORD", "GOOGLE_CLIENT_ID", "ADMIN_EMAILS", "SUPPORT_EMAIL", "SUPPORT_PHONE") {
    if ($settings[$name]) { $lines += "$name=`"$($settings[$name])`"" }
}
$lines += "PAID_START="

$remote = @(
    "sudo apt-get update -qq",
    "sudo apt-get install -y -qq unzip",
    "sudo mkdir -p /opt/medtrack",
    "sudo unzip -oq /tmp/medtrack-release.zip -d /opt/medtrack",
    "(sudo test -f /opt/medtrack/.env || sudo mv /tmp/medtrack.env /opt/medtrack/.env)",
    "sudo rm -f /tmp/medtrack.env /tmp/medtrack-release.zip",
    "cd /opt/medtrack",
    "sudo bash deploy/install.sh"
) -join " && "

if ($DryRun) {
    "Server:   $User@$Ip"
    "Settings: " + (($lines | ForEach-Object { $_.Split("=")[0] }) -join ", ")
    "Commands: $remote"
    return
}

# OpenSSH refuses a key that other Windows users could read.
icacls $Key /inheritance:r /grant:r "${env:USERNAME}:R" | Out-Null

& "$PSScriptRoot\make-release.ps1"

$envFile = Join-Path $env:TEMP "medtrack.env"
$sshOptions = @("-i", $Key, "-o", "StrictHostKeyChecking=accept-new")

try {
    [IO.File]::WriteAllText($envFile, (($lines -join "`n") + "`n"), (New-Object Text.UTF8Encoding($false)))

    "Copying to the server..."
    scp @sshOptions "$root\medtrack-release.zip" $envFile "${User}@${Ip}:/tmp/"
    if ($LASTEXITCODE -ne 0) { throw "Could not copy to the server. Check the IP, the key, and that port 22 is open." }
}
finally {
    Remove-Item $envFile -Force -ErrorAction SilentlyContinue
}

"Installing (the first time takes several minutes)..."
ssh @sshOptions "${User}@${Ip}" $remote
if ($LASTEXITCODE -ne 0) { throw "The install on the server stopped with an error. See the lines above." }

"Waiting for https://$Domain ..."
for ($i = 0; $i -lt 40; $i++) {
    try {
        $reply = Invoke-WebRequest "https://$Domain/api/health" -UseBasicParsing -TimeoutSec 8
        if ($reply.Content -match '"ok"') {
            "MedTrack is live: https://$Domain"
            return
        }
    } catch {
        Start-Sleep -Seconds 6
    }
}

throw "The server is up, but https://$Domain does not answer yet. Check that the domain points to $Ip and that ports 80 and 443 are open in Oracle's Security List."

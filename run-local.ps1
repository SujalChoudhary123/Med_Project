# Starts the API on this PC using the settings in server.env, so you do not
# have to type the SMTP / Google values into the terminal every time.
#
#   1. Copy server.env.example to server.env and fill it in (once).
#   2. Run:  .\run-local.ps1

$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

if (-not (Test-Path "server.env")) {
    Write-Host "server.env not found. Copy server.env.example to server.env and fill it in." -ForegroundColor Yellow
    exit 1
}

Get-Content "server.env" | ForEach-Object {
    $line = $_.Trim()
    if ($line -eq "" -or $line.StartsWith("#")) { return }

    $index = $line.IndexOf("=")
    if ($index -lt 1) { return }

    $name = $line.Substring(0, $index).Trim()
    $value = $line.Substring($index + 1).Trim().Trim('"')
    Set-Item -Path "Env:$name" -Value $value
}

New-Item -ItemType Directory -Force out | Out-Null

javac -encoding UTF-8 -cp "lib/*" -d out (Get-ChildItem src\*.java).FullName

if ($LASTEXITCODE -ne 0) {
    Write-Host "Compile failed." -ForegroundColor Red
    exit 1
}

java -cp "out;lib/*" ApiServer

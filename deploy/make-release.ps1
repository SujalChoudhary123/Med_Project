# Packs the project into one ZIP for uploading to the server.
# Your database, secrets and build leftovers are left out.
#
#   .\deploy\make-release.ps1
#
# Then copy it to the server:
#   scp .\medtrack-release.zip root@YOUR_SERVER:/opt/

$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent
Set-Location $root

$staging = Join-Path $env:TEMP ("medtrack_release_" + [guid]::NewGuid().ToString("N").Substring(0, 8))
New-Item -ItemType Directory $staging | Out-Null

$files = @(".dockerignore", ".env.example", ".gitignore", "DEPLOY.md", "README.md", "Dockerfile.api", "Dockerfile.web", "docker-compose.yml")

foreach ($file in $files) {
    Copy-Item $file $staging
}

Copy-Item deploy (Join-Path $staging "deploy") -Recurse

New-Item -ItemType Directory (Join-Path $staging "src"), (Join-Path $staging "lib"), (Join-Path $staging "docs") | Out-Null
Copy-Item src\*.java (Join-Path $staging "src")
Copy-Item lib\sqlite-jdbc-*.jar (Join-Path $staging "lib")
Copy-Item docs\*.md (Join-Path $staging "docs")

$web = Join-Path $staging "frontend"
New-Item -ItemType Directory $web | Out-Null
Get-ChildItem frontend -Force | Where-Object { $_.Name -notin "node_modules", "dist", ".env.phone" } |
    ForEach-Object { Copy-Item $_.FullName $web -Recurse }

$zip = Join-Path $root "medtrack-release.zip"
if (Test-Path $zip) { Remove-Item $zip -Force }
# Forward slashes in the ZIP, so that Linux unzips it into proper folders.
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [IO.Compression.ZipFile]::Open($zip, "Create")
Get-ChildItem $staging -Recurse -File -Force | ForEach-Object {
    $name = $_.FullName.Substring($staging.Length + 1).Replace("\", "/")
    [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive, $_.FullName, $name) | Out-Null
}
$archive.Dispose()
Remove-Item $staging -Recurse -Force

$archive = [IO.Compression.ZipFile]::OpenRead($zip)
$bad = $archive.Entries | Where-Object { $_.FullName -match '\.db$|server\.env$|(^|/)\.env$' -and $_.FullName -notmatch 'frontend' }
$archive.Dispose()
if ($bad) { throw "Secrets or data slipped into the ZIP: $($bad.FullName -join ', ')" }

"Created $zip ($([math]::Round((Get-Item $zip).Length / 1MB, 1)) MB)"

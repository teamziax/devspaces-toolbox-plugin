# Install.ps1 - one-step installer for the `dw` module.
#
# Usage (from the folder containing this script):
#   .\Install.ps1
#
# Copies the dw module into your per-user PowerShell module path so that
# `dw` auto-loads in every new terminal - no profile edits, no dot-sourcing.
#
# If PowerShell blocks this script, run once:
#   Set-ExecutionPolicy -Scope CurrentUser RemoteSigned
#   Unblock-File .\Install.ps1

[CmdletBinding()]
param(
    # Optionally pin a namespace into your profile during install.
    [string]$Namespace
)

$ErrorActionPreference = 'Stop'

$src = $PSScriptRoot
if ([string]::IsNullOrEmpty($src)) { $src = (Get-Location).Path }

# Pick the per-user module directory for the current PowerShell edition.
# PS 7+ uses Documents\PowerShell; Windows PowerShell 5.1 uses WindowsPowerShell.
$docs = [Environment]::GetFolderPath('MyDocuments')
$psDir = if ($PSVersionTable.PSVersion.Major -ge 6) { 'PowerShell' } else { 'WindowsPowerShell' }
$destRoot = Join-Path $docs (Join-Path $psDir 'Modules')
$dest = Join-Path $destRoot 'dw'

Write-Host "Installing dw module -> $dest" -ForegroundColor Cyan

New-Item -ItemType Directory -Path $dest -Force | Out-Null
Copy-Item -Path (Join-Path $src 'dw.psm1') -Destination $dest -Force
Copy-Item -Path (Join-Path $src 'dw.psd1') -Destination $dest -Force

# Unblock the copied files in case they came from a download.
Get-ChildItem $dest -Filter *.ps* | Unblock-File -ErrorAction SilentlyContinue

# Optionally persist a namespace override into the user's AllHosts profile.
if (-not [string]::IsNullOrEmpty($Namespace)) {
    $profilePath = $PROFILE.CurrentUserAllHosts
    $profileDir = Split-Path $profilePath -Parent
    New-Item -ItemType Directory -Path $profileDir -Force | Out-Null
    if (-not (Test-Path $profilePath)) { New-Item -ItemType File -Path $profilePath -Force | Out-Null }

    $line = "`$env:DW_NAMESPACE = '$Namespace'"
    $existing = Get-Content $profilePath -ErrorAction SilentlyContinue
    if ($existing -notcontains $line) {
        Add-Content -Path $profilePath -Value $line
        Write-Host "Pinned DW_NAMESPACE='$Namespace' in $profilePath" -ForegroundColor Cyan
    } else {
        Write-Host "DW_NAMESPACE already pinned in profile; skipping." -ForegroundColor DarkGray
    }
}

Write-Host ""
Write-Host "Done. Open a NEW PowerShell window and run:  dw <TAB>" -ForegroundColor Green
Write-Host "(No namespace needed - it auto-detects. Set `$env:DW_NAMESPACE only if you have more than one.)" -ForegroundColor DarkGray

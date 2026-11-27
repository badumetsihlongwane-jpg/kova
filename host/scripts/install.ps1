<#
.SYNOPSIS
    Installs Kova Host Agent on a Windows PC or VPS, creates a Firewall rule, and starts it.
.EXAMPLE
    powershell -ExecutionPolicy Bypass -File .\install.ps1 -PublicHost 100.x.y.z
#>
param(
    [int]$Port = 8765,
    [string]$PublicHost = "",
    [switch]$OpenFirewall,
    [switch]$AutoStart
)

$ErrorActionPreference = "Stop"
$HostDir = Split-Path -Parent $PSScriptRoot
$VenvDir = Join-Path $HostDir ".venv"

Write-Host "=== Kova Host Agent Setup ===" -ForegroundColor Cyan

if (-not (Get-Command python -ErrorAction SilentlyContinue)) {
    throw "Python 3.10+ is required on PATH (or download standalone kova-host.exe from GitHub Releases)."
}

if (-not (Test-Path $VenvDir)) {
    Write-Host "[1/3] Creating virtual environment at $VenvDir..."
    python -m venv $VenvDir
}

$VenvPy = Join-Path $VenvDir "Scripts\python.exe"
Write-Host "[2/3] Installing dependencies..."
& $VenvPy -m pip install --upgrade pip --quiet
& $VenvPy -m pip install -r (Join-Path $HostDir "requirements.txt") --quiet
& $VenvPy -m pip install -e $HostDir --no-deps --quiet

if ($OpenFirewall) {
    Write-Host "[3/3] Opening TCP port $Port in Windows Firewall..."
    try {
        New-NetFirewallRule -DisplayName "Kova Workstation Host ($Port)" `
            -Direction Inbound -Protocol TCP -LocalPort $Port -Action Allow -ErrorAction SilentlyContinue | Out-Null
        Write-Host "Firewall rule ensured for TCP/$Port." -ForegroundColor Green
    } catch {
        Write-Warning "Could not add firewall rule (run as Administrator if you need direct port access)."
    }
}

if ($AutoStart) {
    $StartupBat = Join-Path ([Environment]::GetFolderPath("Startup")) "KovaHost.bat"
    $ExtraArgs = if ($PublicHost) { "--public-host $PublicHost" } else { "" }
    Set-Content -Path $StartupBat -Value "@echo off`r`n`"$VenvPy`" -m kova_host.main --port $Port $ExtraArgs`r`n"
    Write-Host "Added logon startup launcher at $StartupBat" -ForegroundColor Green
}

Write-Host "`nStarting Kova Host Agent..." -ForegroundColor Cyan
$RunArgs = @("-m", "kova_host.main", "--port", "$Port")
if ($PublicHost) { $RunArgs += @("--public-host", $PublicHost) }
& $VenvPy @RunArgs

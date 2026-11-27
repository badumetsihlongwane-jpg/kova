<#
.SYNOPSIS
    Hands your active RDP session cleanly over to the local VPS console via tscon
    so Windows keeps DWM / GUI screen rendering active after RDP disconnects.
    Must be run from an Administrator PowerShell inside your active RDP session.
#>
$ErrorActionPreference = "Stop"

$sessionLine = (query user 2>$null | Select-String -Pattern "^\s*>" | Select-Object -First 1).ToString()
if (-not $sessionLine) {
    Write-Error "Could not identify active RDP session. Run inside an active RDP session as Administrator."
    exit 1
}

$tokens = $sessionLine -split "\s+"
$sessionId = $tokens | Where-Object { $_ -match "^\d+$" } | Select-Object -First 1
if (-not $sessionId) {
    Write-Error "Failed to parse session ID from: $sessionLine"
    exit 1
}

Write-Host "Transferring RDP session $sessionId to local console (your RDP window will close cleanly)..." -ForegroundColor Cyan
tscon $sessionId /dest:console

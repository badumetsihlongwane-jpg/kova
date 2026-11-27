@echo off
echo Opening TCP port 8765 in Windows Firewall for Kova Host...
powershell -NoProfile -ExecutionPolicy Bypass -Command "Start-Process powershell -Verb RunAs -Wait -ArgumentList '-NoProfile -ExecutionPolicy Bypass -Command \"New-NetFirewallRule -DisplayName ''Kova Workstation Host (8765)'' -Direction Inbound -Protocol TCP -LocalPort 8765 -Action Allow -ErrorAction SilentlyContinue; Write-Host ''Firewall rule added for TCP/8765!'' -ForegroundColor Green; Start-Sleep -Seconds 2\"'"
echo Done!
pause

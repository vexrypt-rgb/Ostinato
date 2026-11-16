# Ostinato p2c: feeds the dedicated server's console (its stdin) from console-in.txt, one line per append.
# Server console only: no RCON, no sockets. Append a line with:  Add-Content console-in.txt "fill ..."
param([Parameter(Mandatory=$true)][string]$File)
if (-not (Test-Path $File)) { New-Item -ItemType File $File | Out-Null }
Get-Content -Path $File -Wait -Tail 0

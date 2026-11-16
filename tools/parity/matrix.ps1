# Run a set of scenarios: real twice (noise floor), protocol bot once. Sequential, one bot at a time.
param([string[]]$Scenarios = @("walk","pillar","parkour","ladder","bridge","voidbridge","mine"), [string]$Suffix = "v1", [switch]$ProtoOnly)
$Root = "C:\Users\redfa\Documents\MinecraftDev\parity"
foreach ($s in $Scenarios) {
  $plan = if ($ProtoOnly) { @("proto:1") } else { @("real:1", "real:2", "proto:1") }
  foreach ($e in $plan) {
    $b = $e.Split(":")
    $tag = "$s-$($b[0])-$($b[1])-$Suffix"
    if (Test-Path "$Root\runs\$tag") { continue }
    & powershell -NoProfile -ExecutionPolicy Bypass -File "$Root\run.ps1" -Bot $b[0] -Scenario $s -Tag $tag *> "$Root\runs\$tag.out"
    "$(Get-Date -Format HH:mm:ss) $tag exit=$LASTEXITCODE" | Add-Content "$Root\matrix.log"
  }
}
"$(Get-Date -Format HH:mm:ss) matrix $Suffix done" | Add-Content "$Root\matrix.log"

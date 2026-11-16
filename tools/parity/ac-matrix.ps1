# Anticheat parity matrix: each anticheat alone on Paper 26.3, with and without ProtocolLib, real client then protocol bot.
param([string]$Suffix = "ac1", [string[]]$Scenarios = @("pillar","walk","parkour","ladder","bridge","voidbridge","mine"),
      [string[]]$Configs = @("none","grim","grim+pl","ncp","ncp+pl","spartan","spartan+pl","lac","lac+pl"))
$Root = "C:\Users\redfa\Documents\MinecraftDev\parity"
$jars = @{ none = ""; grim = "grimac-bukkit-2.3.75-5c12799.jar"; ncp = "NoCheatPlus.jar"; spartan = "Spartan-4.0.9.0.jar"; lac = "LightAntiCheat-1.2.9.5.jar" }
foreach ($c in $Configs) {
  $parts = $c.Split("+"); $pl = @()
  if ($jars[$parts[0]]) { $pl += $jars[$parts[0]] }
  if ($parts.Count -gt 1) { $pl += "ProtocolLib-dev.jar" }
  foreach ($s in $Scenarios) {
    foreach ($b in "real", "proto") {
      $tag = "$s-$b-$c-$Suffix"
      if (Test-Path "$Root\runs\$tag") { continue }
      $extra = if ($pl.Count) { @("-Plugins", ($pl -join ",")) } else { @() }
      & powershell -NoProfile -ExecutionPolicy Bypass -File "$Root\run.ps1" -Server paper @extra -Bot $b -Scenario $s -Tag $tag *> "$Root\runs\$tag.out"
      "$(Get-Date -Format HH:mm:ss) $tag exit=$LASTEXITCODE" | Add-Content "$Root\ac-matrix.log"
    }
  }
}
"$(Get-Date -Format HH:mm:ss) ac matrix $Suffix done" | Add-Content "$Root\ac-matrix.log"

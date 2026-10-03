# portablemc bench for Ostinato combat-1.21.11.
# NOT HEADLESS. portablemc 4.4.1 (https://github.com/mindstorm38/portablemc)
# has no flag that runs the Fabric client without a window.
#   portablemc start -h lists --dry, --resolution, --lwjgl, --jvm-args.
#   --dry installs and prints the java command, then does not start the process.
#   --lwjgl VERSION only swaps LWJGL (ARM). It still opens a GLFW window.
#   --resolution sets the window size.
# HeadlessMc (-lwjgl stubs) is a different project and is not used here.
# This script stops before launch unless you pass -AllowWindow, which opens a visible client.
# Do not pass -AllowWindow while another Minecraft client is running.
param([switch]$AllowWindow)

$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent
if (-not (Test-Path (Join-Path $root "gradlew.bat"))) {
    $root = "C:\Users\redfa\Documents\MinecraftDev\Ostinato-combat-1.21.11"
}
$pmc = Join-Path $env:APPDATA "Python\Python314\Scripts\portablemc.exe"
if (-not (Test-Path $pmc)) { throw "portablemc 4.4.1 is not installed. Run: python -m pip install --user portablemc==4.4.1" }
$jvm = "C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot\bin\java.exe"
if (-not (Test-Path $jvm)) { throw "JDK 21 not found at $jvm" }

$dir = Join-Path $root "fabric\run\portablemc-bench"
$mods = Join-Path $dir "mods"
$clientMods = Join-Path $root "fabric\run\client\mods"
New-Item -ItemType Directory -Force -Path $mods | Out-Null

$ostinato = Get-ChildItem (Join-Path $root "fabric\build\libs") -Filter "baritone-fabric-*-dirty.jar" |
    Where-Object { $_.Name -notmatch "dev" } | Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $ostinato) { throw "No production Ostinato jar. From the checkout: gradlew.bat :fabric:remapJar" }

$want = @(
    $ostinato.FullName,
    (Join-Path $clientMods "fabric-api-0.141.6+1.21.11.jar"),
    (Join-Path $clientMods "vexbot-2.2.24+mc1.21.11.jar"),
    (Join-Path $clientMods "grimac-fabric-2.3.74-abb95b6.jar"),
    (Join-Path $clientMods "fiw-anticheat-2.1.0.jar"),
    (Join-Path $clientMods "teha-anticheat-1.2.2+mc1.21.11.jar")
)
Get-ChildItem $mods -Filter "*.jar" -ErrorAction SilentlyContinue | Remove-Item -Force
foreach ($src in $want) {
    if (-not (Test-Path $src)) { throw "Missing mod: $src" }
    if ($src -match "drinium") { throw "Refusing to copy Drinium" }
    Copy-Item $src (Join-Path $mods (Split-Path $src -Leaf)) -Force
}

$saveSrc = Join-Path $root "fabric\run\client\saves\vexflat"
$saveDst = Join-Path $dir "saves\vexflat"
if (-not (Test-Path (Join-Path $saveDst "level.dat"))) {
    if (-not (Test-Path $saveSrc)) { throw "Missing superflat save $saveSrc" }
    Copy-Item $saveSrc $saveDst -Recurse -Force
}

$optSrc = Join-Path $root "fabric\run\client\options.txt"
$optDst = Join-Path $dir "options.txt"
if (Test-Path $optSrc) { Copy-Item $optSrc $optDst -Force } else { New-Item -ItemType File -Path $optDst | Out-Null }
$opt = @(Get-Content $optDst)
function Set-Opt([string]$key, [string]$val) {
    $script:opt = @($script:opt | Where-Object { $_ -notmatch "^$([regex]::Escape($key)):" })
    $script:opt += "${key}:${val}"
}
Set-Opt "pauseOnLostFocus" "false"
foreach ($k in @("master","music","record","weather","block","hostile","neutral","player","ambient","voice")) {
    Set-Opt "soundCategory_$k" "0.0"
}
Set-Content -Path $optDst -Value $opt -Encoding ascii

$jvmArgs = "-Xmx2G -Dostinato.vexbench=1 -Dostinato.vex.kit=spear -Dostinato.vex.diffs=easy -Dostinato.vexbench.exit=true"
$common = @("-vv","--main-dir",$dir,"--work-dir",$dir,"start","--jvm",$jvm,"-u","Dev","--jvm-args=$jvmArgs","fabric:1.21.11:0.19.5")

Write-Host "Installing Fabric 1.21.11 (no game process) into $dir"
& $pmc @("--main-dir",$dir,"--work-dir",$dir,"start","--dry","--jvm",$jvm,"-u","Dev","--jvm-args=$jvmArgs","fabric:1.21.11:0.19.5")
if ($LASTEXITCODE -ne 0) { throw "portablemc --dry failed: $LASTEXITCODE" }

Write-Host ""
Write-Host "NOT HEADLESS. portablemc would open a visible GLFW window."
Write-Host "teha WarningScreen still replaces the title screen, and VexBench only opens vexflat from TitleScreen, so a click would still be required."
Write-Host "Refusing to start the game. Re-run with -AllowWindow only when you accept a visible client and no other client is open."
if (-not $AllowWindow) { exit 2 }

$knots = @(Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object { $_.CommandLine -match "KnotClient|net.minecraft.client.main.Main" })
if ($knots.Count -gt 0) { throw "A Minecraft client is already running. Not starting another." }
Write-Host "STARTING A VISIBLE WINDOW. This is not a headless bench."
& $pmc @common
exit $LASTEXITCODE

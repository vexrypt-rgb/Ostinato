# Parity harness run: one bot (real 26.3 client or protocol bot), one scenario, one server, sequential.
#   -Gen                      build vanilla\world-template (flat, Peaceful, fixed spawn)
#   -Bot real|proto -Scenario NAME -Tag TAG [-Server vanilla|<paper dir>]
# Same username (PBot), same restored world, same console fixture, same command text for both bots.
# PacketLog on both. Server-observed state is read back through the console at the end.
param([switch]$Gen, [ValidateSet("real","proto")][string]$Bot = "real", [string]$Scenario = "pillar", [string]$Tag = "",
      [string]$Server = "vanilla", [int]$HoldSec = 0, [string[]]$Plugins = @(), [switch]$Visible, [switch]$KTrace)
$ErrorActionPreference = "Stop"
$Root = "C:\Users\redfa\Documents\MinecraftDev\parity"
$Srv = Join-Path $Root $Server
$Worlds = if ($Server -eq "paper") { @("world", "world_nether", "world_the_end") } else { @("world") }
function Restore-Worlds { foreach ($w in $Worlds) { $d = Join-Path $Srv $w; if (Test-Path $d) { Remove-Item $d -Recurse -Force }; $t = Join-Path $Srv "$w-template"; if (Test-Path $t) { Copy-Item $t $d -Recurse } } }
$Java = "C:\Program Files\Eclipse Adoptium\jdk-25.0.4.101-hotspot\bin\java.exe"
$si = New-CimInstance -ClassName Win32_ProcessStartup -ClientOnly -Property @{ ShowWindow = [uint16]0 }
function Now { Get-Date -Format "HH:mm:ss" }
function Log($m) { $l = "$(Now) $m"; Write-Host $l; if ($script:RunDir) { try { Add-Content (Join-Path $script:RunDir "harness.log") $l -ErrorAction Stop } catch {} } }
function Console($line) { Add-Content (Join-Path $Root "console-in.txt") $line }
function Child-Java([int]$CmdPid) { $c = @(Get-CimInstance Win32_Process -Filter "ParentProcessId=$CmdPid AND Name='java.exe'"); if ($c.Count) { [int]$c[0].ProcessId } else { 0 } }
function Kill-Tree([int]$TreeRoot) {
  $all = @(Get-CimInstance Win32_Process | Select-Object ProcessId, ParentProcessId, Name, CreationDate)
  $by = @{}; foreach ($q in $all) { $by[[int]$q.ProcessId] = $q }
  if (-not $by.ContainsKey($TreeRoot)) { return }
  $set = @($TreeRoot); $i = 0
  while ($i -lt $set.Count) { $p = $by[[int]$set[$i]]
    $set += @($all | Where-Object { $_.ParentProcessId -eq $p.ProcessId -and $_.CreationDate -ge $p.CreationDate -and $_.Name -ne 'explorer.exe' -and $set -notcontains $_.ProcessId } | ForEach-Object { [int]$_.ProcessId }); $i++ }
  [array]::Reverse($set); foreach ($p in $set) { Stop-Process -Id $p -Force -ErrorAction SilentlyContinue }
  Log "killed tree $TreeRoot ($($set.Count) procs)"
}
function Wait-Out($pattern, [int]$sec) {
  $d = (Get-Date).AddSeconds($sec)
  while ((Get-Date) -lt $d) { if ((Test-Path "$Srv\server.out") -and (Select-String "$Srv\server.out" -Pattern $pattern -Quiet)) { return $true }; Start-Sleep -Milliseconds 500 }
  return $false
}
function Start-Server {
  foreach ($sf in "server.out", "server.err") { $x = Join-Path $Srv $sf; if (Test-Path $x) { Remove-Item $x -Force } }
  $in = Join-Path $Root "console-in.txt"; Set-Content $in "" -NoNewline
  $tail = "powershell -NoProfile -ExecutionPolicy Bypass -File `"$Root\console-tail.ps1`" -File `"$in`""
  $cmd = "cmd.exe /c `"$tail | `"$Java`" -Xmx768M -Xms256M -jar server.jar nogui > server.out 2> server.err`""
  $proc = Invoke-CimMethod -ClassName Win32_Process -MethodName Create -Arguments @{ CommandLine = $cmd; CurrentDirectory = $Srv; ProcessStartupInformation = $si }
  if (-not (Wait-Out "Done \(" 180)) { throw "server not ready" }
  Log "server cmdpid=$($proc.ProcessId) ready"
  return [int]$proc.ProcessId
}
function Stop-Server([int]$CmdPid) { Console "stop"; $jp = Child-Java $CmdPid; for ($i = 0; $i -lt 30; $i++) { Start-Sleep 1; if (-not $jp -or -not (Get-Process -Id $jp -ErrorAction SilentlyContinue)) { break } }; Kill-Tree $CmdPid }

# Scenarios: fixture lines run on the console after the join; the command is identical text for both bots
# (the real client gets it through the command inbox with a leading '#').
$Scen = @{
  pillar = @{ fixture = @("clear PBot", "give PBot dirt 64", "tp PBot 5.5 -60 -3.5 0 0"); command = "goto 5 -54 -4"; wait = 45;
              check = @("data get entity PBot Pos", "data get entity PBot OnGround", "execute if block 5 -55 -4 minecraft:dirt run say CHECK dirt_at_5_-55_-4") }
  walk   = @{ fixture = @("clear PBot", "tp PBot 0.5 -60 0.5 0 0"); command = "goto 20 -60 0"; wait = 25;
              check = @("data get entity PBot Pos") }
  # 2-wide, 3-deep trench across z -40..40: a parkour jump, or a long walk around without parkour.
  parkour = @{ fixture = @("clear PBot", "fill 3 -63 -40 4 -61 40 air", "tp PBot 0.5 -60 0.5 0 0"); command = "goto 8 -60 0"; wait = 40;
              check = @("data get entity PBot Pos") }
  # 6-high stone column with ladders on its west face; goal on top.
  ladder = @{ fixture = @("clear PBot", "fill 4 -60 0 4 -55 0 stone", "fill 3 -60 0 3 -55 0 ladder[facing=west]", "tp PBot 0.5 -60 0.5 0 0"); command = "goto 4 -54 0"; wait = 40;
              check = @("data get entity PBot Pos") }
  # Same ladder, but both start already facing it (yaw -90 = +x): a straight sprint from standstill, no turn.
  ladderfacing = @{ fixture = @("clear PBot", "fill 4 -60 0 4 -55 0 stone", "fill 3 -60 0 3 -55 0 ladder[facing=west]", "tp PBot 0.5 -60 0.5 -90 0"); command = "goto 4 -54 0"; wait = 40;
              check = @("data get entity PBot Pos") }
  # 5-wide, 3-deep trench across z -40..40 with dirt in hand: bridge (or walk around).
  bridge = @{ fixture = @("clear PBot", "give PBot dirt 64", "fill 2 -63 -40 6 -61 40 air", "tp PBot 0.5 -60 0.5 0 0"); command = "goto 9 -60 0"; wait = 60;
              check = @("data get entity PBot Pos", "execute if block 2 -61 0 minecraft:dirt run say CHECK dirt_2_-61_0", "execute if block 4 -61 0 minecraft:dirt run say CHECK dirt_4_-61_0") }
  # Same, cut through bedrock: below is the void.
  voidbridge = @{ fixture = @("clear PBot", "give PBot dirt 64", "fill 2 -64 -40 6 -61 40 air", "tp PBot 0.5 -60 0.5 0 0"); command = "goto 9 -60 0"; wait = 60;
              check = @("data get entity PBot Pos", "execute if block 4 -61 0 minecraft:dirt run say CHECK dirt_4_-61_0") }
  # Four stone blocks to mine with a pickaxe in hand.
  mine   = @{ fixture = @("clear PBot", "give PBot iron_pickaxe 1", "fill 5 -60 3 6 -59 3 stone", "tp PBot 0.5 -60 0.5 0 0"); command = "mine stone"; wait = 45;
              check = @("data get entity PBot Pos", "execute if block 5 -60 3 minecraft:stone run say CHECK stone_left_5_-60_3", "clear PBot cobblestone 0") }
}

if (@(Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object { $_.CommandLine -match "devlaunchinjector|server\.jar|HeadlessBotMain" }).Count) { "ABORT: Minecraft java already running"; exit 2 }

if ($Gen) {
  foreach ($w in $Worlds) { $d = Join-Path $Srv $w; if (Test-Path $d) { Remove-Item $d -Recurse -Force } }
  if (Test-Path "$Srv\plugins") { Remove-Item "$Srv\plugins" -Recurse -Force }; New-Item -ItemType Directory "$Srv\plugins" | Out-Null
  $sp = Start-Server
  foreach ($c in "gamerule advance_time false", "gamerule advance_weather false", "gamerule respawn_radius 0", "time set 6000", "weather clear", "setworldspawn 0 -60 0", "save-all flush") { Console $c; Start-Sleep -Milliseconds 400 }
  Start-Sleep 4; Stop-Server $sp
  foreach ($w in $Worlds) { $t = Join-Path $Srv "$w-template"; if (Test-Path $t) { Remove-Item $t -Recurse -Force }; if (Test-Path (Join-Path $Srv $w)) { Copy-Item (Join-Path $Srv $w) $t -Recurse } }
  "GEN done"; exit 0
}

$sc = $Scen[$Scenario]; if (-not $sc) { "unknown scenario $Scenario"; exit 3 }
if (-not $Tag) { $Tag = "$Scenario-$Bot-" + (Get-Date -Format HHmmss) }
$script:RunDir = Join-Path $Root "runs\$Tag"; New-Item -ItemType Directory -Force $script:RunDir | Out-Null
Log "RUN bot=$Bot scenario=$Scenario server=$Server command='$($sc.command)'"
Restore-Worlds
if ($Server -eq "paper") {
  if (Test-Path "$Srv\plugins") { Remove-Item "$Srv\plugins" -Recurse -Force }; New-Item -ItemType Directory "$Srv\plugins" | Out-Null
  $Plugins = @($Plugins | ForEach-Object { $_ -split "," } | Where-Object { $_ })
  foreach ($pl in $Plugins) { Copy-Item (Join-Path $Root "plugins\$pl") "$Srv\plugins\" }
  Log "plugins: $($Plugins -join ', ')"
}
$srvCmd = Start-Server
$plog = (Join-Path $script:RunDir "packetlog.txt").Replace("\", "/")

$port = (Select-String "$Srv\server.properties" -Pattern "^server-port=(\d+)").Matches[0].Groups[1].Value
if ($Bot -eq "real") {
  $inst = Join-Path $Root "inst\PBot"; if (Test-Path $inst) { Remove-Item $inst -Recurse -Force }
  Copy-Item (Join-Path $Root "inst-template") $inst -Recurse; Copy-Item (Join-Path $Root "launch.cfg.template") (Join-Path $inst "launch.cfg")
  if ($KTrace) { New-Item -ItemType Directory -Force (Join-Path $inst "baritone") | Out-Null; Set-Content (Join-Path $inst "baritone\settings.txt") "kinematicTrace true" }
  $props = "-Dostinato.noMouseGrab=true -Dostinato.windowPos=2600,40 -Dostinato.commandInbox=true -Dostinato.packetLog=$plog -Dostinato.trace=$($plog.Replace('packetlog.txt','trace.txt'))"
  if (-not $Visible) { $props += " -Dostinato.runMode=hidden" }; Log "real client mode: $(if ($Visible) { "visible" } else { "hidden" })"
  $xx = "-XX:+UseG1GC -XX:MaxGCPauseMillis=50 -XX:G1HeapRegionSize=8M -XX:MaxMetaspaceSize=384M -XX:ReservedCodeCacheSize=128M"
  $base = "`"$Java`" -Dfabric.dli.config=$inst\launch.cfg -Dfabric.dli.env=client -Dfabric.dli.main=net.fabricmc.loader.impl.launch.knot.KnotClient @$Root\runClient.args --sun-misc-unsafe-memory-access=allow --enable-native-access=ALL-UNNAMED -Dfile.encoding=UTF-8 -Duser.country=US -Duser.language=en -Duser.variant net.fabricmc.devlaunchinjector.Main"
  $env1 = "set JAVA_TOOL_OPTIONS=-Xmx768M -Xms256M $props $xx&&"
  $proc = Invoke-CimMethod -ClassName Win32_Process -MethodName Create -Arguments @{ CommandLine = "cmd.exe /c `"$env1 $base > `"$script:RunDir\client.log`" 2>&1 && echo PROCESS-EXIT-0 >> `"$script:RunDir\client.log`" || echo PROCESS-EXIT-NONZERO >> `"$script:RunDir\client.log`"`""; CurrentDirectory = $inst; ProcessStartupInformation = $si }
} else {
  $cp = (Get-Content (Join-Path $Root "bot-classpath.txt") -Raw).Trim()
  Set-Content (Join-Path $Root "bot-cp.args") ("-cp `"" + $cp.Replace("\", "/") + "`"")
  $cmdFile = Join-Path $script:RunDir "commands.txt"; Set-Content $cmdFile "" -NoNewline
  $line = "`"$Java`" -Xmx256m --enable-native-access=ALL-UNNAMED -Dostinato.botTrace=true -Dostinato.packetLog=$plog @$Root\bot-cp.args baritone.headless.HeadlessBotMain 127.0.0.1 25573 PBot 240 `"$script:RunDir\evidence.txt`" `"$cmdFile`""
  $proc = Invoke-CimMethod -ClassName Win32_Process -MethodName Create -Arguments @{ CommandLine = "cmd.exe /c `"$line > `"$script:RunDir\client.log`" 2>&1`""; CurrentDirectory = $script:RunDir; ProcessStartupInformation = $si }
}
$botCmd = [int]$proc.ProcessId; Log "bot cmdpid=$botCmd"
if (-not (Wait-Out "PBot joined the game" 180)) { Log "NO JOIN"; $hj = Child-Java $botCmd; if ($hj) { & "C:\Program Files\Eclipse Adoptium\jdk-25.0.4.101-hotspot\bin\jcmd.exe" $hj Thread.print *> (Join-Path $script:RunDir "hang-threads.txt"); Log "thread dump of $hj saved" }; if ($Bot -eq "real") { Get-ChildItem $inst -Recurse -Include "hs_err*","crash-*" -ErrorAction SilentlyContinue | Copy-Item -Destination $script:RunDir; Log "client java alive at no-join: $([bool]$hj)" }; Kill-Tree $botCmd; Stop-Server $srvCmd; exit 4 }
Start-Sleep 8
foreach ($c in $sc.fixture) { Console $c; Start-Sleep -Milliseconds 700 }
Start-Sleep 4
Log "COMMAND $($sc.command)"
if ($Bot -eq "real") {
  $utf8 = New-Object System.Text.UTF8Encoding $false
  [System.IO.File]::WriteAllText((Join-Path $inst "command-inbox.tmp"), "#" + $sc.command + "`n", $utf8)
  Move-Item -Force (Join-Path $inst "command-inbox.tmp") (Join-Path $inst "command-inbox.txt")
} else { Add-Content $cmdFile $sc.command }
$hold = if ($HoldSec -gt 0) { $HoldSec } else { $sc.wait }
Start-Sleep $hold
foreach ($c in $sc.check) { Console $c; Start-Sleep -Milliseconds 700 }
if ($Plugins -match "NoCheatPlus") { Console "ncp info PBot"; Start-Sleep 1 }
Start-Sleep 2
Kill-Tree $botCmd
Start-Sleep 1
Stop-Server $srvCmd
Copy-Item "$Srv\server.out" (Join-Path $script:RunDir "server.out")
if ($Server -eq "paper") { Get-ChildItem "$Srv\plugins" -Directory | ForEach-Object { Copy-Item $_.FullName (Join-Path $script:RunDir "plugin-$($_.Name)") -Recurse -ErrorAction SilentlyContinue } }
if ($Bot -eq "real") { Copy-Item (Join-Path $inst "command-outbox.txt") $script:RunDir -ErrorAction SilentlyContinue }
Log "DONE"

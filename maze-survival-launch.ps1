# Maze demo launcher (TEMP, not committed). Muted, secondary monitor, never activated. Opens saves\vexflat via quickPlay.
param([string]$world = "vexflat", [string]$extra = "")
if (Get-Process java,javaw -EA 0 | ? MainWindowTitle -match Minecraft) { "CLIENT RUNNING"; exit 1 }
Add-Type -AssemblyName System.Windows.Forms
$scr = [System.Windows.Forms.Screen]::AllScreens | ? { -not $_.Primary } | select -First 1
if (-not $scr) { "NO SECOND MONITOR"; exit 1 }
$pos = "$($scr.WorkingArea.X + 40),$($scr.WorkingArea.Y + 40)"
$o = "$PSScriptRoot\fabric\run\client\options.txt"; $t = gc $o
$set = @{maxFps="30";enableVsync="false";renderDistance="6";simulationDistance="5";pauseOnLostFocus="false";inactivityFpsLimit="`"afk`"";gamma="1.0"}
$t = $t | % { $k = ($_ -split ":",2)[0]; if ($k -like "soundCategory_*") { "${k}:0.0" } elseif ($set.ContainsKey($k)) { "${k}:" + $set[$k] } else { $_ } }
Set-Content $o $t
# Ostinato: run33 hit the 60000-tick furnish cap at light 75/133; kit+gather alone uses ~35 min, so allow 90 min.
# Ostinato: lightKit stays off so torches are gathered. lootGate=80 keeps the proof roll (paper) stable.
$props = "-Dostinato.noMouseGrab=true -Dostinato.windowPos=$pos -Dostinato.testHarness=true -Dostinato.mazetest=true -Dostinato.mazetest.furnish=true -Dostinato.mazetest.furnishTimeoutTicks=108000 -Dostinato.mazetest.exit=true -Dostinato.mazetest.timeoutTicks=12000 -Dostinato.mazetest.lootGate=80 -Dostinato.mazetest.lightKit=false $extra"
$si = New-CimInstance -ClassName Win32_ProcessStartup -ClientOnly -Property @{ ShowWindow = [uint16]0 }
# Ostinato: cap the Gradle daemon heap at 1G (native OOM at 16GB RAM with -Xmx4G daemon + client)
$cmd = "cmd.exe /c `"set JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-25.0.4.101-hotspot&& set JAVA_TOOL_OPTIONS=$props&& gradlew.bat -Dorg.gradle.jvmargs=-Xmx1G :fabric:runClient -PclientXmx=1536M --offline --console=plain > proof\complete-mazetest.log 2>&1`""
$r = Invoke-CimMethod -ClassName Win32_Process -MethodName Create -Arguments @{ CommandLine = $cmd; CurrentDirectory = "$PSScriptRoot"; ProcessStartupInformation = $si }
"rv=$($r.ReturnValue) pid=$($r.ProcessId) pos=$pos"
try { (Get-Process -Id $r.ProcessId).PriorityClass = 'BelowNormal' } catch {}
Invoke-CimMethod -ClassName Win32_Process -MethodName Create -Arguments @{ CommandLine = "powershell.exe -NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File `"$PSScriptRoot\mover.ps1`""; CurrentDirectory = "$PSScriptRoot"; ProcessStartupInformation = $si } | Out-Null





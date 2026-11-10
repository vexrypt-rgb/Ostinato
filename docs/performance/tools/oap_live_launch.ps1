# OAP scheduling study: one live planload run (LegatoLiveTest arms=planload) under a chosen CPU environment.
#
#   powershell -NoProfile -ExecutionPolicy Bypass -File docs\performance\tools\oap_live_launch.ps1 -tag e1-pilot -seed 93001 -reps 2 -mask 0x0F -stress 2
#
# -mask    processor affinity (hex) for this launcher process. Gradle and the client JVM are its children and inherit
#          it from creation; -XX:ActiveProcessorCount=<bits in mask> makes them size GC/JIT threads and Ostinato's
#          planner pool from the restricted count too. 0 = leave the affinity alone (all logical processors).
# -stress  busy-loop Python processes, Normal priority, pinned to the same mask (0 = none). They stand for other
#          programs competing for the same processors.
# Runs one client to completion (the harness exits it), samples CPU counters every 5 s, kills the stress processes,
# and moves this run's files to docs\performance\raw\sched-<tag>-*. Client: second monitor, no mouse grab, muted
# (fabric\run\client\options.txt). Refuses to start if a Minecraft client is already running or commit headroom is low.
param([Parameter(Mandatory)][string]$tag, [Parameter(Mandatory)][string]$seed, [int]$reps = 6,
      [string]$mask = "0", [int]$stress = 0, [int]$timeoutMin = 75)
$ErrorActionPreference = "Stop"
$root = (Resolve-Path "$PSScriptRoot\..\..\..").Path
$run = "$root\fabric\run\client"
$raw = "$root\docs\performance\raw"
$out = "$raw\sched-$tag"
if (Test-Path "$out-rows.txt") { "TAG EXISTS $tag"; exit 1 }
if (Get-Process java, javaw -EA 0 | ? MainWindowTitle -match Minecraft) { "CLIENT RUNNING"; exit 1 }
$os = Get-CimInstance Win32_OperatingSystem
$freeMB = [int]($os.FreeVirtualMemory / 1024)
if ($freeMB -lt 3500) { "LOW COMMIT ${freeMB}MB"; exit 1 }
foreach ($f in "legatotest-result.txt", "planload-ticks.csv", "planload-frames.csv", "planload-server.csv") {
    if (Test-Path "$run\$f") { Move-Item "$run\$f" "$run\$f.prev-$(Get-Date -Format yyyyMMddHHmmss)" }
}
$m = [Convert]::ToInt64($mask, 16)
if ($m -ne 0) { [System.Diagnostics.Process]::GetCurrentProcess().ProcessorAffinity = [IntPtr]$m }
$stressP = @()
for ($i = 0; $i -lt $stress; $i++) {
    $p = Start-Process python -ArgumentList '-c', '"while 1: pass"' -WindowStyle Hidden -PassThru
    if ($m -ne 0) { $p.ProcessorAffinity = [IntPtr]$m }
    $p.PriorityClass = 'Normal'
    $stressP += $p
}
$power = (powercfg /getactivescheme) -join ' '
$ac = (Get-CimInstance -Namespace root\wmi -ClassName BatteryStatus -EA 0 | Select-Object -First 1).PowerOnline
$meta = "tag=$tag seed=$seed reps=$reps mask=0x$('{0:X}' -f $m) stress=$stress freeCommitMB=$freeMB ac=$ac power=[$power] start=$(Get-Date -Format s)"
$meta | Set-Content "$out-meta.txt"
$props = "-Dostinato.noMouseGrab=true -Dostinato.windowPos=2600,40 -Dostinato.testHarness=true -Dostinato.tickClock=true " +
         "-Dostinato.legatotest=true -Dostinato.legatotest.exit=true -Dostinato.legatotest.arms=planload " +
         "-Dostinato.legatotest.reps=$reps -Dostinato.seed=$seed"
# JDK 25 on Windows reports every logical processor from availableProcessors() whatever the affinity (seen: affinity
# 0xF, cpus=8), so a restricted run also tells the JVMs the restricted count: GC threads and the planner pool are sized
# as on a machine with that many processors.
if ($m -ne 0) { $props += " -XX:ActiveProcessorCount=$([Convert]::ToString($m, 2).Replace('0', '').Length)" }
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-25.0.4.101-hotspot'
$env:JAVA_TOOL_OPTIONS = $props
$g = Start-Process "$root\gradlew.bat" -ArgumentList '--offline', '--no-daemon', '"-Dorg.gradle.jvmargs=-Xmx1536M"', ':fabric:runClient', '-PclientXmx=1536M' `
    -WorkingDirectory $root -RedirectStandardOutput "$out-client.log" -RedirectStandardError "$out-client.err" -WindowStyle Hidden -PassThru
"launched gradle pid=$($g.Id) $meta"
"t,cpuPct,perfPct,clientCpuPct" | Set-Content "$out-counters.csv"
$deadline = (Get-Date).AddMinutes($timeoutMin)
$cores = [Environment]::ProcessorCount
$prevCpu = @{}
while (-not $g.HasExited -and (Get-Date) -lt $deadline) {
    Start-Sleep -Seconds 5
    try {
        $c = Get-Counter '\Processor(_Total)\% Processor Time', '\Processor Information(_Total)\% Processor Performance' -EA Stop
        $v = $c.CounterSamples | % { [math]::Round($_.CookedValue, 1) }
        $cl = Get-Process java -EA 0 | ? MainWindowTitle -match Minecraft | Select-Object -First 1
        $pct = ""
        if ($cl) {
            $now = $cl.TotalProcessorTime.TotalMilliseconds
            if ($prevCpu.ContainsKey($cl.Id)) { $pct = [math]::Round(($now - $prevCpu[$cl.Id]) / 5000 / $cores * 100, 1) }
            $prevCpu[$cl.Id] = $now
        }
        "$(Get-Date -Format HH:mm:ss),$($v[0]),$($v[1]),$pct" | Add-Content "$out-counters.csv"
    } catch {}
}
if (-not $g.HasExited) { "TIMEOUT: stopping gradle tree"; & taskkill /PID $g.Id /T /F | Out-Null }
$stressP | % { try { $_.Kill() } catch {} }
foreach ($f in @{ "legatotest-result.txt" = "rows.txt"; "planload-ticks.csv" = "ticks.csv"; "planload-frames.csv" = "frames.csv"; "planload-server.csv" = "server.csv" }.GetEnumerator()) {
    if (Test-Path "$run\$($f.Key)") { Move-Item "$run\$($f.Key)" "$out-$($f.Value)" }
}
"end=$(Get-Date -Format s) exit=$($g.ExitCode)" | Add-Content "$out-meta.txt"
"done $tag"

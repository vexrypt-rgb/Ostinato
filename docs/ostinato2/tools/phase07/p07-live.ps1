# Phase 07: live A/B of pathingStateIdCache (LegatoLiveTest arms=stateids, SimEdgeCourse, 96 laps). usage: p07-live.ps1 <seed>
# One client at a time, nothing else running. The window opens on the second monitor, muted, without grabbing the mouse.
param([Parameter(Mandatory = $true)][string]$seed)
$ErrorActionPreference = 'Continue'
Set-Location (Resolve-Path "$PSScriptRoot\..\..\..\..")
$raw = 'docs\ostinato2\raw\phase07\live'
$client = 'fabric\run\client'
New-Item -ItemType Directory -Force $raw, 'build\phase07' | Out-Null
foreach ($v in 'OSTINATO_BENCH','OSTINATO_BENCH_PROFILE','OSTINATO_BENCH_ONLY','OSTINATO_BENCH_FINDER','OSTINATO_BENCH_WARM','OSTINATO_BENCH_STATEIDS','OSTINATO_BENCH_SEED','OSTINATO_LONGROUTE') { Remove-Item "Env:$v" -ErrorAction SilentlyContinue }
# the tick file appends: move an earlier one aside rather than mixing runs
foreach ($f in 'stateids-ticks.csv','legatotest-result.txt') {
  if (Test-Path "$client\$f") { Move-Item "$client\$f" ("$client\$f.before-$seed-" + (Get-Date -Format 'yyyyMMddHHmmss')) }
}
$env:JAVA_TOOL_OPTIONS = "-Dostinato.noMouseGrab=true -Dostinato.windowPos=2600,40 -Dostinato.testHarness=true -Dostinato.tickClock=true -Dostinato.legatotest=true -Dostinato.legatotest.exit=true -Dostinato.legatotest.arms=stateids -Dostinato.legatotest.reps=6 -Dostinato.seed=$seed"
$log = "build\phase07\ab-$seed.log"
$t0 = Get-Date
& .\gradlew.bat --offline --no-daemon "-Dorg.gradle.jvmargs=-Xmx1536M" :fabric:runClient -PclientXmx=1536M *> $log
$code = $LASTEXITCODE
Remove-Item Env:JAVA_TOOL_OPTIONS -ErrorAction SilentlyContinue
Select-String -Path $log -Pattern 'LEGATOTEST|keys still held|keys held right after|keys stuck after' | ForEach-Object { $_.Line } | Set-Content -Encoding utf8 "$raw\ab-$seed-lines.txt"
if (Test-Path "$client\legatotest-result.txt") { Copy-Item "$client\legatotest-result.txt" "$raw\ab-$seed-result.txt" }
if (Test-Path "$client\stateids-ticks.csv") {
  $in = [IO.File]::OpenRead((Resolve-Path "$client\stateids-ticks.csv"))
  $out = [IO.File]::Create((Join-Path (Resolve-Path $raw) "ab-$seed-ticks.csv.gz"))
  $gz = New-Object IO.Compression.GZipStream($out, [IO.Compression.CompressionMode]::Compress)
  $in.CopyTo($gz); $gz.Dispose(); $out.Dispose(); $in.Dispose()
  Move-Item "$client\stateids-ticks.csv" "$client\stateids-ticks.csv.done-$seed"
}
"ab-{0} exit={1} secs={2:n0} head={3} laps={4}" -f $seed, $code, ((Get-Date) - $t0).TotalSeconds, (git rev-parse --short HEAD), (Select-String -Path "$raw\ab-$seed-lines.txt" -Pattern 'LEGATOTEST lap ').Count | Add-Content "$raw\runs.txt"
"DONE ab-$seed" | Add-Content "$raw\runs.txt"

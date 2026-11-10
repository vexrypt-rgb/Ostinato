# Phase 07: PathBench A/B of pathingStateIdCache (wa:2, all lengths). usage: p07-ids-bench.ps1 <prefix> <order: e.g. off,on,on,off> [reps] [held-out OSTINATO_BENCH_SEED]
param([string]$prefix = 'p07-ids-dev', [string]$order = 'off,on,on,off', [string]$reps = '3', [string]$seed = '')
$ErrorActionPreference = 'Continue'
Set-Location (Resolve-Path "$PSScriptRoot\..\..\..\..")
$raw = 'docs\ostinato2\raw\phase07\ids'
New-Item -ItemType Directory -Force $raw | Out-Null
foreach ($v in 'OSTINATO_BENCH_PROFILE','OSTINATO_BENCH_ONLY','OSTINATO_BENCH_WARM','OSTINATO_LONGROUTE','OSTINATO_BENCH_STATEIDS','OSTINATO_BENCH_SEED','JAVA_TOOL_OPTIONS') { Remove-Item "Env:$v" -ErrorAction SilentlyContinue }
if ($seed) { $env:OSTINATO_BENCH_SEED = $seed }
$i = 0
foreach ($arm in $order.Split(',')) {
  $i++
  $label = "$prefix-$arm-$i"
  $env:OSTINATO_BENCH = $label; $env:OSTINATO_BENCH_REPS = $reps; $env:OSTINATO_BENCH_FINDER = 'wa:2'
  if ($arm -eq 'on') { $env:OSTINATO_BENCH_STATEIDS = '1' } else { Remove-Item Env:OSTINATO_BENCH_STATEIDS -ErrorAction SilentlyContinue }
  $t0 = Get-Date
  & .\gradlew.bat --offline --no-daemon --no-build-cache --max-workers=2 "-Dorg.gradle.jvmargs=-Xmx1536M" cleanTest test -x nativeHost -Postinato_bench --tests baritone.pathing.calc.PathBenchTest.benchmark *> "$raw\$label.gradle.log"
  "{0} exit={1} secs={2:n0} head={3} seed={4}" -f $label, $LASTEXITCODE, ((Get-Date) - $t0).TotalSeconds, (git rev-parse --short HEAD), $seed | Add-Content "$raw\runs.txt"
  foreach ($s in @('.md','-raw.csv','-fingerprint.txt')) { Copy-Item "build\pathbench\$label$s" $raw -ErrorAction SilentlyContinue }
}
"DONE $prefix" | Add-Content "$raw\runs.txt"

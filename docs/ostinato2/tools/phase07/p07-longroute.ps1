# Phase 07: long-route corpus (LongRouteSimTest.corpus). usage: p07-longroute.ps1 <label> <set> [seeds] [trips] [arms, e.g. PROD,IDS]
param([string]$label = 'p07-dev', [string]$set = 'dev', [string]$seeds = '', [string]$trips = '2', [string]$arms = '')
$ErrorActionPreference = 'Continue'
Set-Location (Resolve-Path "$PSScriptRoot\..\..\..\..")
$raw = 'docs\ostinato2\raw\phase07\longroute'
New-Item -ItemType Directory -Force $raw | Out-Null
foreach ($v in 'OSTINATO_BENCH','OSTINATO_BENCH_PROFILE','OSTINATO_BENCH_ONLY','OSTINATO_BENCH_FINDER','OSTINATO_BENCH_WARM','OSTINATO_LONGROUTE_FAMILIES','OSTINATO_LONGROUTE_MODES','OSTINATO_LONGROUTE_ARMS','OSTINATO_LONGROUTE_SEEDS') { Remove-Item "Env:$v" -ErrorAction SilentlyContinue }
$env:OSTINATO_LONGROUTE = $label; $env:OSTINATO_LONGROUTE_SET = $set; $env:OSTINATO_LONGROUTE_TRIPS = $trips
if ($seeds) { $env:OSTINATO_LONGROUTE_SEEDS = $seeds }
if ($arms) { $env:OSTINATO_LONGROUTE_ARMS = $arms }
$t0 = Get-Date
& .\gradlew.bat --offline --no-daemon --no-build-cache --max-workers=2 "-Dorg.gradle.jvmargs=-Xmx1536M" cleanTest test -x nativeHost --tests baritone.pathing.calc.LongRouteSimTest.corpus *> "$raw\$label.gradle.log"
"{0} set={1} seeds={2} trips={3} arms={7} exit={4} secs={5:n0} head={6}" -f $label, $set, $seeds, $trips, $LASTEXITCODE, ((Get-Date) - $t0).TotalSeconds, (git rev-parse --short HEAD), $arms | Add-Content "$raw\runs.txt"
foreach ($s in @('-trips.csv','-searches.csv','-progress.txt')) { Copy-Item "build\pathbench\longroute\$label$s" $raw -ErrorAction SilentlyContinue }

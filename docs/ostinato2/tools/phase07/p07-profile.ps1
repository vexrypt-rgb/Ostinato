# Phase 07: JFR CPU/allocation profile of the production planner (wa:2) on the LONG PathBench queries, 1 rep.
$ErrorActionPreference = 'Continue'
Set-Location (Resolve-Path "$PSScriptRoot\..\..\..\..")
$raw = 'docs\ostinato2\raw\phase07\profile'
New-Item -ItemType Directory -Force $raw | Out-Null
$env:OSTINATO_BENCH = 'p07-prof-wa2'; $env:OSTINATO_BENCH_REPS = '1'; $env:OSTINATO_BENCH_FINDER = 'wa:2'
$env:OSTINATO_BENCH_ONLY = 'LONG'; $env:OSTINATO_BENCH_PROFILE = '1'
Remove-Item Env:OSTINATO_BENCH_WARM -ErrorAction SilentlyContinue
Remove-Item Env:OSTINATO_LONGROUTE -ErrorAction SilentlyContinue
$t0 = Get-Date
& .\gradlew.bat --offline --no-daemon --no-build-cache --max-workers=2 "-Dorg.gradle.jvmargs=-Xmx1536M" cleanTest test -x nativeHost -Postinato_bench --tests baritone.pathing.calc.PathBenchTest.benchmark *> "$raw\p07-prof-wa2.gradle.log"
"p07-prof-wa2 exit={0} secs={1:n0}" -f $LASTEXITCODE, ((Get-Date) - $t0).TotalSeconds | Add-Content "$raw\runs.txt"
foreach ($s in @('.md','-raw.csv','-fingerprint.txt','.jfr')) { Copy-Item "build\pathbench\p07-prof-wa2$s" $raw -ErrorAction SilentlyContinue }
"DONE" | Add-Content "$raw\runs.txt"

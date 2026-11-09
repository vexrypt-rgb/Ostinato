# Phase 07 baseline: PathBench all lengths, 4 arms (+ warm hierarchy), sequential.
$ErrorActionPreference = 'Continue'
Set-Location (Resolve-Path "$PSScriptRoot\..\..\..\..")
$raw = 'docs\ostinato2\raw\phase07\pathbench'
New-Item -ItemType Directory -Force $raw | Out-Null
$arms = @(
  @{label='p07-astar'; finder='astar'; warm=$null},
  @{label='p07-wa2'; finder='wa:2'; warm=$null},
  @{label='p07-hpa1bi'; finder='hpa:0:1:1:2:1:bi'; warm=$null},
  @{label='p07-hpa1bi-fw'; finder='hpa:0:1:1:2:1:bi:fw'; warm=$null},
  @{label='p07-hpa1bi-fw-warm'; finder='hpa:0:1:1:2:1:bi:fw'; warm='1'}
)
foreach ($a in $arms) {
  $env:OSTINATO_BENCH = $a.label; $env:OSTINATO_BENCH_REPS = '3'; $env:OSTINATO_BENCH_FINDER = $a.finder
  if ($a.warm) { $env:OSTINATO_BENCH_WARM = '1' } else { Remove-Item Env:OSTINATO_BENCH_WARM -ErrorAction SilentlyContinue }
  Remove-Item Env:OSTINATO_BENCH_ONLY -ErrorAction SilentlyContinue
  $t0 = Get-Date
  & .\gradlew.bat --offline --no-daemon --no-build-cache --max-workers=2 "-Dorg.gradle.jvmargs=-Xmx1536M" cleanTest test -x nativeHost -Postinato_bench --tests baritone.pathing.calc.PathBenchTest.benchmark *> "$raw\$($a.label).gradle.log"
  "{0} exit={1} secs={2:n0}" -f $a.label, $LASTEXITCODE, ((Get-Date) - $t0).TotalSeconds | Add-Content "$raw\runs.txt"
  foreach ($s in @('.md','-raw.csv','-fingerprint.txt')) { Copy-Item "build\pathbench\$($a.label)$s" $raw -ErrorAction SilentlyContinue }
}
"DONE" | Add-Content "$raw\runs.txt"

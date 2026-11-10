# Phase 07 diagnosis: JFR profile of wa:2 on the LONG PathBench queries, 1 rep, with DebugNonSafepoints, state-id cache off then on.
$ErrorActionPreference = 'Continue'
Set-Location (Resolve-Path "$PSScriptRoot\..\..\..\..")
$raw = 'docs\ostinato2\raw\phase07\profile'
New-Item -ItemType Directory -Force $raw | Out-Null
Remove-Item Env:OSTINATO_BENCH_WARM -ErrorAction SilentlyContinue
Remove-Item Env:OSTINATO_LONGROUTE -ErrorAction SilentlyContinue
$env:JAVA_TOOL_OPTIONS = '-XX:+UnlockDiagnosticVMOptions -XX:+DebugNonSafepoints'
foreach ($arm in 'off','on') {
  $label = "p07-prof2-wa2-$arm"
  $env:OSTINATO_BENCH = $label; $env:OSTINATO_BENCH_REPS = '1'; $env:OSTINATO_BENCH_FINDER = 'wa:2'
  $env:OSTINATO_BENCH_ONLY = 'LONG'; $env:OSTINATO_BENCH_PROFILE = '1'
  if ($arm -eq 'on') { $env:OSTINATO_BENCH_STATEIDS = '1' } else { Remove-Item Env:OSTINATO_BENCH_STATEIDS -ErrorAction SilentlyContinue }
  $t0 = Get-Date
  & .\gradlew.bat --offline --no-daemon --no-build-cache --max-workers=2 "-Dorg.gradle.jvmargs=-Xmx1536M" cleanTest test -x nativeHost -Postinato_bench --tests baritone.pathing.calc.PathBenchTest.benchmark *> "$raw\$label.gradle.log"
  "{0} exit={1} secs={2:n0} head={3} jvm=DebugNonSafepoints" -f $label, $LASTEXITCODE, ((Get-Date) - $t0).TotalSeconds, (git rev-parse --short HEAD) | Add-Content "$raw\runs.txt"
  foreach ($s in @('.md','-raw.csv','-fingerprint.txt','.jfr')) { Copy-Item "build\pathbench\$label$s" $raw -ErrorAction SilentlyContinue }
}
Remove-Item Env:JAVA_TOOL_OPTIONS -ErrorAction SilentlyContinue
"DONE prof2" | Add-Content "$raw\runs.txt"

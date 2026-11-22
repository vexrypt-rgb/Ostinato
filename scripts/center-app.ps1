# Ostinato: Phase 3 command-center window, without Minecraft. Usage from the repo root:
#   powershell -File scripts/center-app.ps1 test      all window/feed/channel tests (headless), then lists the PNGs
#   powershell -File scripts/center-app.ps1 demo      the window over a moving fake swarm of 8 bots
#   powershell -File scripts/center-app.ps1 screens   only the screenshot tests, then lists the PNGs
#   powershell -File scripts/center-app.ps1 jar       build/libs/ostinato-center-app.jar
# Runs Gradle on JDK 21, 1.5 GB, --no-daemon (nothing left running, and no daemon holding this script's output
# pipe open). Never runs gradlew --stop.
param([Parameter(Position = 0)][ValidateSet("test", "demo", "screens", "jar")][string]$Action = "test")
$ErrorActionPreference = "Stop"
$RepoDir = Split-Path -Parent $PSScriptRoot
$Jdk21 = "C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot"
if (Test-Path $Jdk21) { $env:JAVA_HOME = $Jdk21 }
$Gradlew = Join-Path $RepoDir "gradlew.bat"
$Common = @("-p", "center-app", "--no-daemon", "-Dorg.gradle.jvmargs=-Xmx1536M", "--console=plain")
Push-Location $RepoDir
try {
  switch ($Action) {
    "test" { & $Gradlew @Common test --rerun }
    "screens" { & $Gradlew @Common test --rerun --tests "*WallViewTest.elevenBotsRenderInEveryCoreTheme" --tests "*DemoSwarmTest*" }
    "demo" { & $Gradlew @Common run --args="--fake 8" }
    "jar" { & $Gradlew @Common jar; Get-ChildItem (Join-Path $RepoDir "center-app\build\libs") | Format-Table Name, Length }
  }
  $code = $LASTEXITCODE
  if ($Action -in "test", "screens") {
    $results = Join-Path $RepoDir "center-app\build\test-results\test"
    if (Test-Path $results) {
      $t = 0; $f = 0
      foreach ($x in Get-ChildItem $results -Filter *.xml) { $s = [xml](Get-Content $x.FullName); $t += [int]$s.testsuite.tests; $f += [int]$s.testsuite.failures + [int]$s.testsuite.errors }
      "tests: $t, failed: $f"
    }
    $screens = Join-Path $RepoDir "center-app\build\screens"
    if (Test-Path $screens) { Get-ChildItem $screens -Filter *.png | ForEach-Object { "screen: $($_.FullName)" } }
  }
  exit $code
} finally {
  Pop-Location
}

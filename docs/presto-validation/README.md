# Presto recovery validation — 2026-10-31

Worktree: `Ostinato-ppe`, branch `feat/predictive-physics`, base `1aed5cc21978e631c2218cbcbd8e80b419b26db8`. All implementation changes remain uncommitted. Minecraft 26.3, Java 25 toolchain; Gradle launched with the local Java 21 installation.

## Deterministic tests

- [Final full suite output](final-full-suite.txt): 195 suites, 1,257 tests, zero failures/errors, 15 skipped. [Final counts](final-test-summary.txt). PrestoTest now has 32 tests, including the two early-miss regressions.
- [Earlier selected suite counts](test-summary.txt): Presto 30/30, cross-model 4/4, existing jump/chaining and actual-Minecraft collision checks. These are preserved historical counts; see final counts above.
- [Earlier targeted run](final-targeted.txt): 34/34 after the harness setup-teleport fix, before the two early-miss regressions. The final full suite above supersedes it.
- [Bookkeeping benchmark](bookkeeping-bench.txt): five warm-up rounds, nine samples of 500,000 iterations, min/median/max 14.9/15.3/22.1 ns per simulated observation tick. Pure Java bookkeeping only; no claim about total client CPU or improvement over disabled mode.

Reproduce from this worktree in PowerShell:

```powershell
.\gradlew.bat test --console=plain
.\gradlew.bat test -Postinato_bench --tests baritone.pathing.kinematic.PrestoBench --console=plain
$env:JAVA_TOOL_OPTIONS='-Dostinato.noMouseGrab=true -Dostinato.windowPos=2600,40 -Dostinato.testHarness=true -Dostinato.prestotest=true -Dostinato.prestotest.exit=true -Dostinato.prestotest.laps=4 -Dostinato.seed=71006'
.\gradlew.bat :fabric:runClient -PclientXmx=1536M --console=plain
Remove-Item Env:JAVA_TOOL_OPTIONS
```

Use a window position suitable for the display. The two harness gates create a fresh disposable world from the title screen. Never launch this to test an existing save. Preserve/rename `fabric/run/client/prestotest-result.txt` before rerunning because the harness appends to it. Restore any pre-existing JAVA_TOOL_OPTIONS value after a manual run rather than removing it.

## Live evidence

[First run, confounded](live-confounded.txt) is diagnostic only. Setup teleports were counted as real corrections while Presto was still enabled between laps. The harness now disables Presto before setup and reenables the lap arm after settling. Intentional mid-lap jolts remain active. Normal and jolt arms each alternate baseline/Presto on the same course. Trust history carries across actual trials; these are not independent statistical replicates. Fixed random seed does not remove camera, scheduling, planner or warm-up variation.

The course includes gaps, elevation changes and a low ceiling. It does not test every attribute, emergency action, combat maneuver, server plugin or network condition. Failure counts and reached-only travel times must be considered together. See the living [handoff](../../PRESTO_HANDOFF.md) for capability classifications and unresolved ownership/physics limitations.

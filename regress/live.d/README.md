# Live-client stages (Ostinato regression suite)

`scripts/regress.sh --live` runs every executable `*.sh` here before the headless stages, with:

| variable | meaning |
|---|---|
| `OSTINATO_REGRESS_METRICS_DIR` | write your StageResult JSON files here (see docs/REGRESSION.md, "Metrics file") |
| `OSTINATO_REGRESS_COMMIT` | the commit under test |
| `OSTINATO_REGRESS_TREE` | its checkout (build the client jar / runClient from here) |

A stage that cannot run should write a file with `"status": "skipped"` and a `note`, so the report says why.
Nothing here is enabled by default: copy an `.example` to `.sh`, `chmod +x` it and fill in the client launch.

- `pktest.sh.example`: a PkTest course in a headless client (Xvfb/HeadlessMC, e.g. the ACS box farm), converted
  with `scripts/regress/pk_results_to_metrics.py`. Needs the headless/real parity check (priorities P0.1) before its
  numbers are trusted.
- `combat.sh.example`: a VexBench match series (default 48 rounds vs the SMP PvP Bots datapack) in a headless client,
  converted with `scripts/regress/vexbench_to_metrics.py` into `combat:vexbench.json` (win rate fails past -0.15,
  damage and hit-rate moves warn); the headless `CombatStage` then leaves it alone instead of recording a skip.
  VexBench is a local bench hook on the combat branches, not on main.

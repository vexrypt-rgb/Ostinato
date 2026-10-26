# Overnight hypothesis races

Opt-in queue of A/B runs on one Minecraft client. The agent drives the bot. The harness only scores, records, and deletes race worlds. `overnightRaces` defaults off. Off, `#overnight` does not create or delete a world, and pathing is unchanged.

This extends the agent session, the race recorder, and `RaceWorldCleanup`. It does not add a second bot stack.

## Switches

| Setting | Default | Role |
| --- | --- | --- |
| `overnightRaces` | off | Master switch. Off, `#overnight` returns immediately. |
| `overnightEveryBlockItem` | off | When on, each scored run also runs the `every_block_item` circuit. Off, the row records the agent outcome and does not read the block or item registry. |
| `overnightDir` | `baritone/overnight` | Queue, state, and `races.jsonl`, relative to the game directory. |
| `overnightTimeoutMs` | `600000` | Per-run limit when a queue line omits `timeoutMs`. |

`overnightEveryBlockItem` does not turn on `#train every_block_item`. That circuit still needs `raceEveryBlockItem`. The collector itself is unchanged: enchant, netherite, and the other interactions stay at 0 except `crafting.use`.

The agent still uses its own switches. `agentApi` and `agentChat` must be on or the turn is skipped and the row says so. `agentDryRun` stays on until you turn it off, so queued commands are printed and not run. The overnight runner does not flip that switch.

Tools the agent may enqueue, when they are on the whitelist: `goto`, `mine`, `follow` (pathing), `pvp` (combat), `gear` and `settings` (gear and other settings), `route`. The harness does not path, fight, or equip.

## Queue file

`{overnightDir}/queue.jsonl`. One JSON object per line. Blank lines and lines that start with `#` are skipped. Order is the file order. Variant B above variant A runs first.

```json
{"hypothesis":"speed","variant":"A","world":"hyp_speed_a","seed":"71006","flat":true,"prompt":"mine stone and fight players","timeoutMs":600000}
{"hypothesis":"speed","variant":"B","world":"hyp_speed_b","seed":"71006","flat":true,"prompt":"mine stone and fight players","timeoutMs":600000}
```

| Field | Required | Meaning |
| --- | --- | --- |
| `hypothesis` | yes | Id. Letters, digits, `_`, `-`, at most 64. Written as `hypothesis` on the result row. |
| `variant` | yes | A/B label, same character rules, at most 32. |
| `world` | yes | Folder name. Must be `race_*`, `hyp_*`, or `agentrun_*`. Anything else is rejected. |
| `seed` | no | World seed, the same rules as the create-world screen (`WorldOptions.parseSeed`). Blank or omitted is a random seed. A number is that long. Any other text is the string's hash. Written on every result row as `seed`. |
| `flat` | no | Defaults true. True creates `WorldPresets.FLAT`. False creates the normal preset. Written on every result row as `flat` (`true` or `false`). |
| `prompt` | no | Text sent through the existing agent API. Empty uses the hypothesis id. |
| `timeoutMs` | no | This run's limit. Zero or omitted uses `overnightTimeoutMs`. |

`#overnight queue` prints the lines. `#overnight start` refuses a bad line and does not start.

## How to run

Put the queue file in place, then in game:

```
#set overnightRaces true
#set agentApi true
#set agentChat true
#set agentDryRun false
#overnight start
#overnight status
#overnight stop
#overnight abort
```

`stop` finishes the current run, including its delete, and does not start the next one. `abort` records `abort`, deletes if this run opened a world, and stops. `start fresh` begins at the first line again.

A second start while a queue is running prints `overnight: busy`. One client session is the existing `AgentSession`: a provider call holds it, and `#agent` waits.

Headless tests do not launch a client. The state machine, the queue, and cleanup run against a folder host.

## Result file

`{overnightDir}/races.jsonl`, one JSON line per run, through `JsonlRaceRecorder`. There is no API-key field. A `runId` already in the file is not written again. `runId` is `hypothesis-variant-world`.

```json
{"runId":"speed-A-hyp_speed_a","hypothesis":"speed","agent":"agent","provider":"groq","model":"openai/gpt-oss-20b","metrics":{"variant":"A","world":"hyp_speed_a","seed":"71006","flat":"true","score":"3/3","timeMs":"1200"},"outcome":"pass"}
```

`outcome` is `pass`, `incomplete`, `error`, `timeout`, `abort`, or `stubbed`. `score` is `n/a` unless `overnightEveryBlockItem` is on, in which case it is the circuit's `collected/total` and the stage fields from [README.md](README.md) are added. `timeMs` is wall time for the run. The circuit's `timeTicks` is added only when that option is on. Every row records `seed` and `flat` from the queue line. A blank seed is stored as an empty string.

## State and resume

`{overnightDir}/state.json` stores the index, phase, and outcome. `#overnight start` resumes an in-progress file. Phases are `CREATE_WORLD`, `RUN_AGENT`, `SCORE`, `RECORD`, `DELETE_WORLD`, `DONE`.

A resume during `RECORD` does not append a second line for the same `runId`. A resume during `DELETE_WORLD` deletes again. A resume during `RUN_AGENT` starts that agent turn again. A resume during `CREATE_WORLD` asks the host again. `start fresh` ignores the state file and starts at the first line. Rows already recorded are still skipped.

Turning `overnightRaces` off pauses the tick. It does not delete the open world. Turn it back on and use `status`, or `abort` while it is on.

## Worlds and cleanup

`#overnight start` writes `ostinato-race-root.txt` into the game's `saves` folder. After that, `RaceWorldCleanup` may delete direct children named `race_*`, `hyp_*`, or `agentrun_*`. It keeps `vexflat` (any name starting with that), `pk_*`, `pkrun_*`, `SigilTest`, and `gs_world`. Other folders, including `New World`, stay.

Cleanup runs after each run, including a stubbed or failed create, but only when no world is loaded. If the run opened a world and leaving it times out (10 seconds), that world is left on disk and the sweep is skipped for that pass. A create that did not open a world does not disconnect the world you were already in.

The marker is what allows deletion. Without it, cleanup deletes nothing. Do not put the marker in a saves folder whose `race_` / `hyp_` / `agentrun_` names you want to keep.

## Live and stubbed

| Step | Status |
| --- | --- |
| Open an existing `race_*`, `hyp_*`, or `agentrun_*` folder with the vanilla world-open flow | Live |
| Leave that world with the client's level disconnect, back toward the title screen, without quitting the process | Live |
| Delete leftover race folders through `RaceWorldCleanup` after the world is left | Live |
| Create a missing race folder with `WorldOpenFlows.createFreshLevel` (survival, the queue seed, flat or normal preset) | Live |

When the queue world is missing and no world is loaded, the host creates that folder. The folder name is the queue `world` with no extra suffix. If the name vanilla would store is different, including `race_x (1)` when `race_x` is already taken, the create fails and the renamed folder is not made. The host then polls until the loaded world folder equals the queue name. A timeout is recorded as `timeout`. A cancel, a rename, or a create that does not start is recorded as `error`. The live host does not record `stubbed` for a missing world. `stubbed` remains an outcome for a host that still reports an unimplemented step.

Seed and flat are applied only on that create. A blank seed is random. `flat` true (the default) uses `WorldPresets.FLAT`. `flat` false uses `WorldPresets.NORMAL`. Game mode is survival. An existing folder is opened as it is. Seed and flat are not written over it.

The plan is in `RaceWorldPlans`: `OPEN_EXISTING`, `CREATE_NEW`, `ALREADY_OPEN`, `BUSY` (some other world is loaded), `REJECT` (the name is not a race world).

## Safety

- Default off. Pathing code does not reference this package.
- One queue, one provider call at a time.
- Dry-run stays on until you turn it off.
- The result line has no API key. Chat text is passed through the same redaction as `#agent`.
- No server join. This is singleplayer only.
- The Pi search heap is unchanged because pathfinding is not on this path.

## Not in this change

- Actually collecting every block and item, the enchant path, or the netherite upgrade. The score hook is opt-in and still only credits `crafting.use`.
- GPU batch sims and tiny models.
- Clicking the Create World screen. New worlds go through `createFreshLevel`, and a folder vanilla would rename is a failed run.

# SCHERZO + BOMBARD — handoff

Living handoff for the Scherzo (predictive combat) + Bombard (TNT combat) work. Updated after every stage or
substantial change. **Read this first, then verify against the tree** — another agent may have moved things.

Last updated: 2026-10-31 00:47 (Claude continuation after Codex; section 8 is newest, then 7; both supersede the
older stage table in section 4).

Evidence labels: **Verified** = inspected source / executed check; **Measured** = reproducible measurement;
**Reported** = docs/prior claims; **Contradicted** = conflicts with evidence; **Unknown**.
Status ladder per capability: implemented → compiling → unit-tested → integration-tested → runtime-validated → live-enabled.

---

## 1. Where the work lives

| Item | Value |
|---|---|
| Repo | `vexrypt-rgb/Ostinato` (worktree) |
| Worktree | `C:\Users\redfa\Documents\MinecraftDev\Ostinato-ppe` |
| Branch / HEAD | `feat/predictive-physics` @ `1aed5cc2` (unchanged by this work) |
| Target MC | **26.3**, Java 25 (`gradle.properties`); unobfuscated jar |
| Game jar used for mechanics | `~/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-merged-deobf/26.3/minecraft-merged-deobf-26.3.jar` sha256 `fca9019b2687fbf7a091fa1344a4ebd716a33ab6466eaec0eb8d976124dad315` |
| Decompiled excerpts | `C:\Users\redfa\Documents\MinecraftDev\_scherzo_audit\out\` (CFR, scratch only) + older `MinecraftDev\_mc263src\` |
| TenorClef | `C:\Users\redfa\Documents\MinecraftDev\TenorClef-main`, `main` @ `983cf317`, **~20 dirty files owned by other agents — do not touch** |

**Not authorised:** commit, push, stash, reset, discard. Nothing has been committed. All work is uncommitted on top of
the already-uncommitted PPE / Threat / Grace Note work in this worktree.

### Protected (pre-existing uncommitted work by other sessions — extend through small hooks only, never rewrite)
- `src/api/java/baritone/api/{physics,threat,gracenote}/**`, `src/main/java/baritone/{physics,threat,gracenote}/**`,
  their tests, `docs/{PREDICTIVE_PHYSICS,THREAT_ASSESSMENT,GRACE_NOTE_CONTINUATION}.md`, `docs/*-validation/`.
- Tracked diffs already present before this work: `IBaritone.java`, `Baritone.java`, `InventoryBehavior.java`,
  `PathingBehavior.java`, `harness/TestHarness.java` (Grace Note / PPE hooks).
- At 14:06 no java process was running; last write in this worktree was 10:16. Several other claude.exe / Codex
  sessions exist on the machine — re-check `git status` and mtimes before editing shared files.

---

## 2. Stage 0 audit — existing combat architecture (Verified by reading source)

- Live PvP path: `#pvp` command → `PvpProcess` (`src/main/java/baritone/process/PvpProcess.java`, 933 lines),
  a `BaritoneProcessHelper` at priority 2. Each tick `onTick` clears keys, picks target (`CombatTargeting`), then a
  fixed priority chain: totem → potions → water bucket → eat → flee → boost → mending → wind clutch → elytra flare →
  shield dive/guard → lava → **crystal/anchor (`CombatExplosives`)** → **explosive hazard flee** → special (mace,
  pearl, wind, elytra, tools) → approach → **spear jab/charge (`CombatSpear`)** → melee.
- Inputs: `baritone.getInputOverrideHandler().setInputForceState`, `options.keyUse.setDown`, hotbar via `select()`,
  look via `CombatAim.look` (bounded human mouse). One writer per tick: PvpProcess.
- PvE: `PveProcess` (478 lines) + `MobProfile`.
- Target velocity: `CombatTargeting.track/velocity` = EMA of per-tick position deltas (remote players report stale
  deltaMovement). No acceleration, no uncertainty, no history. **This is what Scherzo's opponent model extends.**
- ACS: `CombatTuner` (per-window), `CombatFightTuner` (per-fight), `CombatSpsa`, scored by `CombatOutcome`
  (attempt vs landed hits via hurtTime), knobs in `CombatPolicy.Tuned`. Saved to `autotune.txt`.
- Existing TNT handling: only `CombatExplosives.hazard()` (flee from any PrimedTnt within 3.2 blocks). **No TNT
  placement, ignition, fishing rod, or TNT manipulation exists anywhere in Ostinato.**
- PPE (`IBaritone.getPredictivePhysics()`): captures PrimedTnt bodies (fuse synced; blast power NOT synced →
  `TNT_POWER_ASSUMED`), simulates TNT motion (live oracle: zero diff on 3 dry surfaces, 80 ticks), explosions with
  exposure/damage/knockback, HIGH fidelity reports chain-TNT membership (not recursive).
- Threat Assessment: `IBaritone` threat service, publishes `ThreatSnapshot`.
- Grace Note: emergency water clutch; acquires control by process identity + executor hold; default mode OBSERVE.

---

## 3. Mechanics gate — 26.3 (all **Verified** against decompiled 26.3 jar unless noted)

### Primed TNT (`PrimedTnt`)
- tick: gravity 0.04 → move → ×0.98 drag → onGround ×(0.7, −0.5, 0.7) → fuse−1, explode at fuse ≤ 0 (server).
- Default fuse 80; power 4 (not synced). `isPickable()` = true. `hurtServer` **always false** (cannot be damaged).
- Spawn from block: pos (x+.5, y, z+.5), vel (−sin r·0.02, 0.2, −cos r·0.02), r uniform random.

### ROD — fishing rod on primed TNT: **SUPPORTED**
- Hook attaches if `Projectile.canHitEntity` → `canBeHitByProjectile()` = alive && pickable → TNT qualifies.
  Attachment is server-side only (`onHitEntity` → `setHookedEntity`), synced as `DATA_HOOKED_ENTITY` (id+1).
- Reel = use rod while hook exists → `retrieve` → `pullEntity`: `tnt.vel += 0.1 × (owner.pos − hook.pos)`,
  hook pos = (tnt.x, tnt.y + 0.8·0.98, tnt.z); needs `canSimulateMovement()`. Costs **5 durability**.
- Cast: hook spawns at eye, speed ≈ 0.6 + 0.5·|dir| (≈1.1 b/t level cast, tiny triangle noise), gravity 0.03,
  drag 0.92. Hook discarded if owner stops holding a rod (main/off) or is > 32 blocks away; 1200 ticks on ground.
- When TNT is removed the hook unhooks and falls (FLYING).
- Client cannot prove attachment except through `FishingHook.getHookedIn()` synced data — use that as the attach signal.
- Exact launch, per-tick hit sweep, hit margin and use-packet rotation: see section 8 (Verified 2026-10-31).

### SPEAR — spear on primed TNT: **SUPPORTED (jab and charge); Knockback adds to it**
- Sword/normal `Player.attack`: knockback only `if (wasHurt)` → TNT never hurt → **no push**. Only spears work.
- Jab: client `startAttack` with PIERCING_WEAPON → sends `ServerboundPlayerActionPacket.STAB`; **server** runs
  `PiercingWeapon.attack` along its own ray (`AttackRange` spear: min 2.0, max 4.5, hitbox margin 0.125) →
  `Player.stabAttack(..., dealsKnockback=true)` → knockback applied **regardless of damage**:
  `push(−sin(yaw)·0.4, 0.1, cos(yaw)·0.4)` then, if Knockback L>0, `push(−sin(yaw)·L/2, 0.1, cos(yaw)·L/2)`.
  Direction = attacker **yaw**, horizontal; vertical +0.1 (+0.2 with Knockback).
- Requires full attack charge (`MINIMUM_ATTACK_CHARGE` 1.0). Cooldown = attackDuration (wood .65 s, stone .75,
  copper .85, iron/gold .95, diamond 1.05, netherite 1.15). Hits **every** entity along the ray (could hit foe too).
- Knockback enchant: `supported_items #enchantable/melee_weapon` = swords + spears; max level 2;
  `getKnockback = (ATTACK_KNOCKBACK + L)/2`.
- Charge (`KineticWeapon`): stabAttack with `dealsKnockback` when within knockbackTime and attacker look-speed ≥
  5.1 b/s (sprint ≈ 5.6) → same push formula; per-entity contact cooldown 10 ticks.

### CHAIN — explosion-primed TNT: **SUPPORTED**
- Ray cast (16³ shell rays, power ×U(0.7,1.3), step 0.3, −0.225/step, TNT resistance 0 → −0.09) picks blocks;
  `onExplosionHit` → set air → `TntBlock.wasExploded` → new PrimedTnt with fuse `nextInt(20)+10` = **uniform 10..29**.
- Gates: gamerule `TNT_EXPLODES`; block interaction not KEEP/TRIGGER_BLOCK.
- Order in `ServerExplosion.explode`: hurtEntities **before** interactWithBlocks → newly primed TNT does not get
  the triggering blast's knockback; already-primed TNT entities do.

### Melee and spear-jab hit resolution against players (for Scherzo Stage 2)
- Sword/axe: the attack packet names the entity under the **client's** crosshair; `ServerGamePacketListenerImpl.handleAttack`
  only checks `isWithinAttackRange(item, targetBox, 3.0)` (reach + 3 buffer) and rejects piercing weapons on this path.
  So server-side prediction barely matters for melee: the client pick decides.
- Spear jab: `MultiPlayerGameMode.piercingAttack` → `ensureHasSentCarriedItem` → sends `STAB` with **no target and no
  rotation sync**. `Minecraft.tick` order is gameMode.tick → handleKeybinds (the attack) → level.tickEntities (our
  `sendPosition`), so the server casts from our **last sent** eye/look against **its own** position for the target.
  Server also rejects if `cannotAttackWithItem(item, 5)`.
- Jab ray (`ProjectileUtil.getHitEntitiesAlong`): segment from eye + look·effectiveMinRange to
  eye + look·(effectiveMaxRange + max(0, knownMovement·look)), look = `getHeadLookAngle()`; entity hit when the segment
  crosses its box grown by `hitboxMargin` (with a block check toward its centre), or the box contains the start.
  Blocks clip (`ClipContext.Block.COLLIDER`). Spear: min 2.0, max 4.5, margin 0.125.
- Baritone's look target is applied in `sendPosition` PRE, after keybinds, so at attack time the client's current
  rotation is the last sent one (Verified by reading CombatClick/CombatAim + tick order; not measured).

### Still assumptions / Unknown
- Server plugins / anticheat altering STAB or hook behavior (Unknown). Client↔server position disagreement for a
  moving TNT at the moment of the server ray (Unknown; must be verified by observation).
- Ignition: flint-and-steel on TNT block → `TntBlock.useItemOn` → `prime` (read `_mc263src` TntBlock; not re-checked
  line-by-line for the fire-charge branch).

---

## 3b. Stage 1 — Scherzo opponent model (implemented, compiling, unit-tested)

Files (all new unless marked):
- `src/main/java/baritone/scherzo/OpponentModel.java` — game-free. 12-sample ring; horizontal least-squares velocity
  over 5 samples, 2 after a turn (heading change > 60° or |step − fit| > ACCEL); bounded acceleration lead (2 ticks);
  vanilla vertical (`vy=(vy−0.08)·0.98`, jump 0.42 — **Verified** 26.3 LivingEntity); floor via adapter `Ground`
  (else last ground Y, flagged `floor?`); jump detection is position-aware (the synced ground flag runs ahead of the
  played-back position in 26.3 `SteppedInterpolationHandler` — **Verified**); teleport (>4 b/t) and gap (>20 t) reset
  history; stale (>3 t) widens, lost (>20 t) → null. Radii: `spread` (expected error: residual·h + turnRate·ACCEL·h²/2)
  and `bound` (worst case for |Δv| ≤ ACCEL/tick, centred on the newest observed step — property-tested over a 4000-tick
  random walk). Behaviour estimate: EMA turn rate / jump rate / speed + counts (bounded).
- `Prediction.java` (record + flags), `CombatSnapshot.java` (record), `PredictionAudit.java` — game-free: settles each
  prediction at h∈{1,3,5,10} against the later shown position; model vs baseline (the fight's existing EMA
  `CombatTargeting.velocity`) mean/p50/p95/max, in-spread %, in-bound %, vertical. Fixed arrays.
- `Scherzo.java` — MC adapter. Mode `-Dostinato.scherzo=off|observe|live` (default **observe**). Uses level game time,
  resets on level/target change, column floor probe (≤9 block reads), self-timed (`scherzo cost` line).
  Lead to server = `CombatLag.ticks()` + `target.getType().updateInterval()` (PROVISIONAL, calibrate).
- **Modified** `src/main/java/baritone/process/PvpProcess.java` (+13/−4): field `scherzo`; `scherzo.tick(me, target,
  tv(), lag.ticks())` after the lag note in `onTick`; the four `recorder.end(...)` sites → `endFight(...)`, which writes
  the Scherzo lines to the fight log first; `onLostControl` also calls `scherzo.reset()`. OBSERVE changes no decision.
  Pre-edit copy: `C:\Users\redfa\Documents\MinecraftDev\_scherzo_audit\PvpProcess.java.orig`.
- Tests: `src/test/java/baritone/scherzo/OpponentModelTest.java` (19), `PredictionAuditTest.java` (6).

Fight-log lines written at fight end (`pvplogs/*.gz`): `scherzo fight h=… model … | base … | in-spread … in-bound …`,
`scherzo session h=…`, `scherzo mode=… foe turn=… jump=… speed=…`, `scherzo cost ticks=… mean=…us max=…us`.

Rules marked PROVISIONAL (ACCEL 0.2, VMAX 0.7, FIT 5, TURN_COS 0.5, ACCEL_TICKS 2, STALE 3, SETTLE 0.05, lead
interpolation = updateInterval) — tune from the audit lines of real fights. Knockback is not in the model: a hit
opponent breaks `bound` (expected; the audit will show how often).

## 3c. Stage 2 — predictive spear-jab feasibility (implemented, compiling, unit-tested)

Scope: the spear jab only (melee is resolved by the client pick, see §3). Files:
- `src/main/java/baritone/scherzo/AttackFeasibility.java` — game-free. `ray(eye, look, from, to, margin, Prediction,
  width, height)` → `SURE` (segment crosses the box common to every position the `bound` and vertical `up/down` allow),
  `LIKELY` (crosses the predicted box shrunk by `spread`), `POSSIBLE` (crosses the union box), `MISS`, `UNKNOWN` (no
  prediction). `entry(...)` = distance along the look to the predicted box. `look(pitch, yaw)` = vanilla view vector.
- `JabAdvice.java` — interface the fight asks; `JabAdvice.NONE` = the fight exactly as before.
- `JabAudit.java` — game-free. Each sent jab with its full-ray (server) verdict and band verdict; landed = opponent hurt
  flash (hurtTime rises to ≥ hurtDuration−1) 1..12 ticks after; counts `led` (sent though the client ray said no) and
  `held` (once per tick). A flash is an *observed effect*, not proof of cause.
- `Scherzo.java` (extended): `jab()` → NONE when OFF, else inner `Jab`. `jabVerdict` = `AttackFeasibility.ray` from
  `getEyePosition`/`getHeadLookAngle` against `snapshot.now()` (prediction at lead), then a block clip to the entry
  distance (blocked → MISS). `serverJab` = full vanilla range incl. the movement extension.
  OBSERVE: verdicts only audited, fight unchanged. LIVE: aim at the predicted box (not when confidence LOW); hold the jab
  on a full-range MISS (→ −1); send on band SURE/LIKELY even if the client ray misses (→ 1); otherwise the client ray.
- **Modified** `CombatSwing.java`: field `advice`, `lastClientRay`; `spearRayHits` asks `advice.verdict` after computing
  the client ray; new `jabPoint` (advice aim or `aimPoint`). `CombatSpear.jab` and `CombatClick.hit` aim with
  `jabPoint`; `CombatClick.hit` calls `advice.sent(...)` after pressing the jab. `PvpProcess` constructor:
  `swing.advice = scherzo.jab()`. PvE/SparBrain keep `NONE`. Pre-edit copies in `_scherzo_audit\*.orig`.
- Fight-log lines: `scherzo jab fight sent=N ray sure=l/n likely=… possible=… miss=… unknown=… | band … | led=l/n held=k`
  and the same for `session`.
- Tests: `AttackFeasibilityTest` (8), `JabAuditTest` (7).

What decides whether LIVE is worth enabling: in OBSERVE fight logs, `ray miss` jabs should land ≈ never and
`band sure/likely` should land more often than the client ray's baseline. Not yet measured.

## 4. Capability status

| Capability | Status | Notes |
|---|---|---|
| Scherzo snapshot / opponent model | **unit-tested** (25 tests), wired in OBSERVE | not runtime-validated: no live fight run yet |
| Scherzo attack feasibility in live path | **unit-tested** (15 tests), audited in OBSERVE, advice LIVE-gated | spear jab only; not runtime-validated |
| Scherzo maneuver selection | not started | |
| Bombard TNT model / tracker | not started | |
| ROD primitive | not started | mechanics verified |
| SPEAR primitive | not started | mechanics verified |
| CHAIN primitive | not started | mechanics verified |
| Combos A–G | not started | |
| Grace Note yield / TNT hazard handoff | not started | |
| ACS integration | not started | |

## 5. Next concrete step
Stage 4 Bombard foundation (package `baritone.bombard`: `TntMechanics` verified constants, `TntTracker`, PPE-backed
prediction, permission API default deny, `-Dostinato.bombard` default off, TNT hazard feed for Threat/Grace Note —
`ThreatService` currently classes PrimedTnt UNSUPPORTED). Stage 3 (maneuver selection) after it. Runtime validation of
Stages 1–2 needs a live fight (find a disposable fight harness: `ostinato.vexbench` / `ostinato.spar.test` /
`SparBrain`).

## 5b. Test commands (Verified working)
- From the worktree root: `.\gradlew.bat test --tests "baritone.scherzo.*" --max-workers=1 "-Dorg.gradle.jvmargs=-Xmx1536M"`
- Regression: add `--tests "baritone.process.*"` — 257 tests, 0 failures at the Stage 1 checkpoint; 272, 0 failures
  at the Stage 2 checkpoint (14:39). Results: `build/test-results/test/TEST-*.xml`.
- A JDK 21 java.exe at 14:25 was another Gradle daemon (not a client) — leave it alone.

## 6. Change log
- 14:30 — Stage 0 audit + mechanics gate written. No source files changed.
- Stage 1 — Scherzo core + adapter + PvpProcess hook; 25 new unit tests pass; baritone.process.* (232) still pass.
  Found during testing: the ground flag leads the shown position, so jump detection is now position-aware.
- 14:39 — Stage 2 — spear-jab feasibility (`AttackFeasibility`, `JabAdvice`, `JabAudit`), Scherzo `Jab` advice, hooks in
  CombatSwing/CombatSpear/CombatClick/PvpProcess; 15 new unit tests; scherzo + process = 272, 0 failures.

- 2026-10-31 00:47 — (Claude) ROD client path fixed and runtime-validated in fixture (4/4 PASS); see section 8.

## 7. Codex continuation (2026-10-30)

Recovered the original request and interrupted Claude transcript from local Claude project history. The actual tree
already contained Stage 4 tracking, permission, ThreatService hooks and tests that the earlier handoff omitted.
Baseline: 355 selected tests, zero failures. No commit, push, stash, reset or discard performed; TenorClef untouched.

Implemented in this continuation:
- Bombard observer: two-tick cache lifetime, cancelled stale requests, bounded retries after failed forecasts,
  invalidation on terrain/target/world changes, oldest-request-first refresh. These cached answers remain observations,
  not authorization for live actions; live manipulation uses synchronous current-state PPE requests.
- TntTracker: bounded ignition/explosion history and epoch-aware outcome counters so reopening an old track cannot
  subtract another fight's results. First-sighting forecast error remains separate from refreshed answers.
- TntMechanics: corrected drag to `(double)0.98f` and Entity.move's tiny clipped-displacement suppression, verified
  against local decompiled Entity.java and PPE. The previously unrun BombardTntAgreementTest exposed both defects.
- Scherzo.predict now uses current world time, allowing stale observations to age. ManeuverChoice/ManeuverAdvice
  select among hold/forward/back/left/right with a three-step collision/support probe; CombatMovement consumes the
  result only in LIVE mode during ordinary close melee, not chase, critical preparation or w-tap. Limited local
  positioning, not a general cover/dodge planner; no runtime performance claim yet.
- CombatBombard + CombatBombardRod: opt-in manipulation of one existing primed TNT, inside PvpProcess's input
  decision chain. Spear checks item charge and actual ray; rod requires synced getHookedIn identity before reeling.
  Both compare fresh PPE before/after impulses, reject missing mechanics, demand non-increasing self damage within
  the permit and increased predicted target damage. Scherzo supplies the shared opponent estimate. Hard fuse,
  range, screen, stationary-player and multi-TNT gates limit this initial slice. Cancellation/gaps reset actions;
  Bombard and ThreatService keep independently observing the physical hazard.
- ImpulseAttempt reports WAITING / IMPULSE_OBSERVED / UNKNOWN / INTERRUPTED, never a confirmed opponent kill.
  Friction alone cannot satisfy the directional impulse test. Cast/attack/reel commands have distinct log messages.
- PvpProcess excludes fights in which Bombard takes a tick from further ACS/lag outcome learning, avoiding attribution
  of TNT effects to melee settings. No additional learner or adaptive safety thresholds introduced.
- BlastSafety tests cover missing mechanics, lethal/self-budget rejection, unknown damage and required improvement.
  Permit rejects infinite damage budgets; invalid forecast target indices return unknown.
- BombardLiveTest: opt-in fresh-world server oracle for spear impulses, fishing hook hit-handler/retrieve, actual
  explosion priming, and random short-fuse bounds. Hook attachment is induced at the server handler: this explicitly
  does NOT validate client casting, packet timing, movement ownership or complete combat execution.

Current limits (do not mark the overall project complete): placement/ignition executor, strategic resource spending,
CHAIN execution planning, combinations A–G, full PvE integration, end-to-end controller runtime validation and measured
combat improvement remain outstanding. Rod/spear adapters are compiling; game-free policies are unit-tested;
they are not yet runtime-validated. Default Bombard is OFF and permissions DENY. Scherzo defaults OBSERVE.

Reproduction: `gradlew.bat test --tests "baritone.scherzo.*" --tests "baritone.bombard.*" --tests "baritone.process.*"
--tests "baritone.threat.*" --tests "baritone.gracenote.*" --tests "baritone.physics.*" --max-workers=1
"-Dorg.gradle.jvmargs=-Xmx1536M"`. Latest log: bombard-check.log. For the new oracle run :fabric:runClient
-PclientXmx=768M with JAVA_TOOL_OPTIONS containing -XX:ActiveProcessorCount=4 -Dostinato.noMouseGrab=true
-Dostinato.testHarness=true -Dostinato.bombardtest=true -Dostinato.bombardtest.exit=true. Never overlap a build with
the game process.

Validation checkpoint:
- Latest selected suite run: 440 tests, 0 failures/errors, 4 skipped (benchmark opt-ins). This predates the final
  recording-independent fight lifecycle and latest diagnostic changes; rerun before delivery.
- `bombard-live-retry.log`: server oracle PASS. Spear 15 cases (Knockback 0–2, five yaws), maximum velocity error
  0.000044742656 blocks/tick (vanilla float/trigonometric lookup versus planner double approximation). Rod hit-handler
  attachment/retrieve PASS, durability 5, velocity error 1.53e-9. Actual explosion primed one secondary TNT; 1,000
  short-fuse samples stayed in 10–29. Oracle wall time 62.5094 ms is a whole test-suite measurement, not per-tick cost.
- First oracle failed because the synthetic TNT was not registered in the server entity lookup; fixed the fixture
  with addFreshEntity before the hook's synced-id callback. Retained failed log: bombard-live.log.
- `bombard-live-combat.log`: first real PvP spear fixture FAILED (no observed propulsion). It started during the
  LevelLoadingScreen, so cooldown did not recover before the fuse ended. Fixture now waits for screen dismissal,
  item synchronization and full charge. Next run: bombard-live-combat-ready.log. Do not describe the adapter as
  runtime-validated until the explicit CLIENT_SPEAR result passes.
- Recording-independent fight lifecycle now prevents disabled/file-failed PvpRecorder from repeatedly clearing
  Bombard counters and re-enabling ACS learning every tick. Client spear gate uses zero cooldown tolerance; the
  server's five-tick tolerance is not permission for an early client attack.

Further continuation (after user said keep going):
- `bombard-live-combat-window.log`: **CLIENT_SPEAR PASS** through the real PvpProcess and ordinary key input.
  Fixture: stationary player/helmeted no-AI zombie, flat obsidian platform, diamond spear Knockback II, one existing
  TNT. Fight log `fabric/run/client/pvplogs/fight-20261030-215538-Zombie.log.gz`: JAB_SENT at fuse 13, then
  IMPULSE_OBSERVED two fight ticks later; server oracle independently saw forward TNT velocity. No claim for moving
  opponents, remote latency, placement or combinations. This validates the manipulation slice, not all of Bombard.
- Earlier client fixtures were retained as failures. One exposed a short platform (propelled TNT fell off and missed),
  another allowed the ordinary chase path to move the player past the TNT. The successful fixture stays inside the
  existing spear walking radius with a zero movement-speed attribute, and provides adequate floor under the arc.
- AimWindow now requires two settled angular transitions, rejects skipped ticks/retargets, and handles yaw wrapping.
  This prevents sending a targetless STAB while head/body aim is still turning. Spear planning window is fuse 8–30.
- The successful fixture measured Scherzo observer cost mean 186.5 us / maximum 7790.8 us over 69 ticks, and Bombard
  observer mean 195.4 us / maximum 7290.1 us over 120 ticks. These include cold work and exclude CombatBombard's
  inline action predictions; not full combat latency benchmarks or general performance acceptance.
- RodCast models the verified cast mean velocity (pitch-dependent speed, 0.3-block horizontal launch offset),
  gravity `(double)0.03f` and drag 0.92. It searches at most eight intercept times against a HIGH-fidelity PPE TNT
  path, with bounded pitch bisection. The rod adapter clips each hook segment against blocks/fluids, settles aim,
  then still requires actual attachment before reel. Triangular launch noise remains an explicit limitation.
- Latest suites: **447 tests, 0 failures, 4 skipped**, before the newest rod harness/exception guard changes.
  `bombard-live-rod.log` is the current real casting/reeling fixture (obsidian cover and elevated TNT); result pending.
  Add `-Dostinato.bombardtest.combat=true -Dostinato.bombardtest.rod=true -Dostinato.bombard=live
  -Dostinato.scherzo=live` to the oracle launch to reproduce it; omit `.rod=true` for the spear fixture.

## 8. Claude continuation (2026-10-31) — ROD client path

State: worktree `Ostinato-ppe`, branch `feat/predictive-physics` @ `1aed5cc2`, everything still **uncommitted**
(no commit/push/stash authorised). No TenorClef files touched. Protected PPE/Threat/Grace Note files untouched in
this batch.

### Newly Verified 26.3 mechanics (decompiled MultiPlayerGameMode, ServerGamePacketListenerImpl, FishingHook,
Projectile, ProjectileUtil, Entity in `_scherzo_audit/out`)
- **Use-item rotation**: `MultiPlayerGameMode.useItem` sends `ServerboundUseItemPacket(hand, seq, player.getYRot(),
  player.getXRot())`; `handleUseItem` calls `player.absSnapRotationTo(yRot, xRot)` before `gameMode.useItem`. A rod cast
  uses the rotation the client holds at the keypress; no settle/previous-sent-rotation dependency (unlike the spear's
  targetless STAB, which still needs AimWindow). Decisions run at TickEvent PRE before handleKeybinds and LookBehavior
  sets rotation at PlayerUpdate PRE, so `me.getYRot()/getXRot()` at decision time is what the packet carries.
- **Hook launch**: spawn (x - sinY*0.3, eyeY, z + cosY*0.3); v = (-sinY, clamp(-tanP, +-5), cosY) with every component
  scaled by 0.6/|v| + triangle(0.5, 0.0103365) -> mean horizontal 0.6*cos(e) + 0.5, vertical tan(e) * horizontal
  (e = -pitch); noise about +-0.94 % per component.
- **Hook tick (FLYING)**: `++tickCount` (Entity.tick) -> hookedIn -> HOOKED_IN_ENTITY; in water -> BOBBING; else
  `checkCollision()` with the *current* position and *pre-gravity* velocity -> gravity `0.03f` -> move -> stop on
  ground/horizontal collision -> x0.92.
- **Hit test**: `ProjectileUtil.getHitResultOnMoveVector` clips blocks (COLLIDER, Fluid.NONE) pos -> pos + v first, then
  each entity's `getBoundingBox().inflate(computeMargin).clip(from, to)` — a **segment sweep of the hook point**, not a
  box sweep. `computeMargin = max(0, min(0.3, (tickCount - 2)/20))` -> 0 at hook ticks 1-2, 0.05 at 3, 0.3 from 8.
  `AABB.clip` starting inside the box returns no hit (a TNT falling onto the hook can be missed).
- PrimedTnt `isPickable()` is true -> `canBeHitByProjectile`. Inferred (not decompiled): older entities tick first, so
  the TNT has moved before the new hook's first collision check.

### CLIENT_ROD diagnosis (Codex's `bombard-live-rod.log` = **FAIL**, attachment=false, reel=false)
1. AimWindow demanded a settled view; the falling TNT moves the aim point and the human look overshoots, so the cast
   was never sent (AimWindow is unnecessary for the rod; see use-item rotation).
2. Old `RodCast.solve` tested discrete hook points within 0.4 of the TNT centre; vanilla sweeps segments against the box.
3. The solver's aim sat at the *edge* of the pitch hit window (it aimed at the TNT's current position; the hook meets
   it ~4 ticks later), so any overshoot missed. Offline model of this fixture: the window is ~12 deg of pitch wide and
   slides 4-6 deg/tick as the TNT falls (age 0: -43.5..-31.75, age 3: -34.75..-22.5, age 7: -11.75..-7.25, age 8: none).
4. A missed 2 ms PPE deadline at TNT spawn (queue busy with Threat/Bombard observers, cold code) set a 5-tick backoff,
   which is the whole window in this fixture.
5. Many exits were silent, hiding all of the above.

### Changes (Bombard-owned files only; small)
- `bombard/RodCast.java` rewritten: per-tick vanilla hook simulation; `hits(eye, yaw, pitch, path, stride, shrink)` =
  first hook tick that *surely* enters the TNT box (inflated by computeMargin, shrunk by `SHRINK` 0.08 for launch
  noise/PPE drift) for **both** PPE tick k and k+1 (send latency); a start inside the grown box is not sure. `solve`
  seeds pitch by bisection on arc height at the aim point's horizontal distance, verifies with `hits`, then **centres**
  in the contiguous pitch window. `lead(..., 1)` solves for the TNT a tick later. `HORIZON = MAX_TICKS + 2`.
- `process/CombatBombardRod.java`: AimWindow removed from the rod (kept for the spear). Looks at `lead(1)`; fires when
  `RodCast.hits` succeeds for the **actually held** rotation and that arc is clear of blocks (`CLIPPED`) and other
  pickable entities (`ENTITY_IN_PATH`). Aim timeout 10 ticks. Transient forecast failures (EXPIRED / REJECTED /
  PARTIAL+DEADLINE) retry next tick (max 3) instead of backing off. Every exit notes a reason once per TNT:
  `cast=DECLINED|ABANDONED reason=FUSE|RANGE|LOS|PPE_<status>|NO_SOLUTION|AIM_TIMEOUT|CLIPPED|ENTITY_IN_PATH|
  OPPONENT_UNPREDICTABLE...|FORECAST_<status> horizon computeUs|NOT_WORTHWHILE self=a->b foe=c->d`; after a cast
  `reel=ABANDONED reason=TICK_GAP|CONTEXT_CHANGED|NOT_PERMITTED|GRACE_NOTE|AIRBORNE|USING_ITEM|MOVING|SCREEN|NO_ROD|
  ROD_WORN|EXTERNAL|...`, `reel=DECLINED reason=...`, `attachment=UNKNOWN reason=GONE|TIMEOUT|FUSE hooked=...`.
  CAST_SENT notes aimTicks/yaw/pitch/fuse.
- `bombard/BlastSafety.java`: `unusable(r)` reason string (`trajectoryUsable` delegates) and `transientFailure(r)`.
- `process/CombatBombard.java`: `cancel()` calls `rod.drop("EXTERNAL")` so outside cancels are noted.
- Tests: `RodCastTest` 11 (vanilla recurrence, computeMargin, segment crossing between hook points, one-tick-late
  requirement, graze rejection, held-rotation miss, falling TNT over the wall reachable + +-4 deg centring + lead,
  heightAt vs point, rejects); `BlastSafetyTest` +1 (transient vs verdict, reason strings).

### Evidence
- **Selected suite (00:46): 456 tests, 0 failures, 0 errors, 4 skipped** (command in 5b/7).
- **CLIENT_ROD runtime-validated (this fixture only)**, real client, real PvpProcess, ordinary key input:
  `bombard-live-rod7..10.log` -> **4/4 PASS** (`serverAttachment=true serverReelImpulse=true`); fight logs
  `fight-20261031-004224/004323/004405/004445-Zombie.log.gz`: CAST_SENT at fuse 32 after 1-2 aim ticks, pitch
  -31.5..-32.7, attachment observed + REEL_SENT 5 fight ticks later, IMPULSE_OBSERVED 1-2 ticks after.
  `bombard-live-rod6.log` (aim fix, before reason notes): attachment=true but **no reel, cause Unknown** (that exit was
  still silent). Earlier iterations retained: rod2 (NOT_WORTHWHILE without numbers), rod3 (FORECAST_PARTIAL), rod4/5
  (PPE deadline, no cast). Since the aim fix: 5/5 attached, 4/5 reeled.
- Spear regression `bombard-live-spear2.log` / `fight-20261031-004545`: **CLIENT_SPEAR PASS**. It also hit
  `after=EXPIRED ... failure=DEADLINE` and lost ~9 ticks to its backoff; candidate for the same transient retry.
- Measured (fixture runs, cold work included): Bombard observer mean 203-293 us, max 6.4-13.1 ms; Scherzo mean
  152-265 us, max 5.6-8.5 ms. Not a performance acceptance.
- Opponent outcome stays `UNKNOWN`: no claim that the pull improved damage (zombie health/blast not attributed).
- Not validated: moving opponents, remote latency, multiple TNT, rod vs players, Grace Note interplay during a reel.

### Open / next
- The first PPE request on a freshly spawned TNT expires every run (`PPE_EXPIRED_DEADLINE`). Harmless now; measure
  queue contention between Threat/Bombard observers and COMBAT requests before tightening deadlines.
- Apply transient retry to the spear forecast path; investigate rod6's reel loss if it recurs (now logged).
- Remaining roadmap unchanged: placement/ignition executor (`tracker.ignited`), strategic resource spending, CHAIN
  execution planning, combos A-G, Grace Note / ACS coordination checks, moving-opponent + latency validation, PvE
  integration, performance measurement, final 17-section report.

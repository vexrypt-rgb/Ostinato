# Ostinato human mouse

Turns recordings of your own mouse into a profile that drives Ostinato's humanized camera turns
(headSteering, MineController mining look, PvP aim, humanLookEverywhere).

## 1. Record

Open `mouse-capture/index.html` (in Chrome or Edge) and play one-minute rounds. Enter your Minecraft sensitivity,
Windows pointer speed and mouse DPI first. The variety meter shows how much of the movement space you have covered:
40% gives a usable profile, 80%+ a good one. Press **Download JSON** at the end; the session is also kept in the
browser's localStorage (Menu, "Saved sessions") in case you forget.

Put the `mouse-session-*.json` files in one folder, e.g. `%USERPROFILE%\Documents\MinecraftDev\mouse-data\`.

## 2. Fit

Needs only a JDK (11+):

    java tools/human-mouse/HumanMouseFit.java -o human-mouse-profile.json %USERPROFILE%\Documents\MinecraftDev\mouse-data

It segments every hit movement (point-to-point, flicks, precision and Minecraft camera turns; timestamps are used
only to find where each movement starts and ends, no reaction time is modeled), measures movement time, submovements, overshoot, curvature and tremor, fits Fitts' law (`ms = a + b*log2(D/W+1)`,
scaled by urgency) and keeps up to 500 normalized movement templates. Distances are raw mouse counts (as Minecraft
reads them with Raw Input), so the profile is independent of the in-game sensitivity. `--include-desktop` also uses
the non-pointer-lock "Desktop cursor" rounds (Windows acceleration applied; off by default).

## 3. Use

Copy the profile next to the game (e.g. `.minecraft/human-mouse-profile.json`) and set

    #set humanMouseProfile human-mouse-profile.json

Relative paths resolve against the game directory; `example` uses the bundled synthetic profile
(`src/launch/resources/assets/baritone/ostinato/human-mouse-example.json`, regenerate with
`java tools/human-mouse/HumanMouseFit.java --synthetic -o <that path>`). Empty (the default) keeps the built-in curve,
as does a profile that fails to load (a chat message says why). The file is re-read when it changes.

At runtime (`HumanMouseModel.generate`) a move is conditioned on its angle delta (converted to counts at the current
sensitivity), the target's angular width and urgency: a duration is drawn from the Fitts fit, one of the nearest
recorded movements is time-warped to it and scaled to the distance, AR(1) tremor is added, and the landing is kept
within 40% of the target width. Moves start immediately (no reaction delay); a target that moves re-plans from the
current view.

## Surprise reaction delay

`humanReactionDelay` (default on) waits a reaction time only when a new, unexpected look target appears: in
Ostinato that is combat aim (`CombatAim`) when a fight starts or the target jumps more than 60 degrees. Path
following, headSteering, mining, parkour and other planned looks are never delayed; any non-reactive look cancels
a pending hold (`ReactionGate`).

Reaction times come from the profile's `reaction` block (`ReactionModel`), and the same numbers are built in when no
profile is loaded:

- `sets.tired`: measured, 200 214 247 290 317 350 407 ms (200 fastest while tired, 407 distracted while tired),
  log-normal, bounded to [190, 420] ms.
- `sets.peak`: measured, 165 173 177 181 185 190 198 220 ms (165 best ever and rare, 173-185 typical fastest,
  220 slowest at peak), bounded to [165, 225] ms, median about 185.
- An attention state drifts slowly (about a 3-minute timescale) between focused (the fastest samples) and
  distracted (the slowest).
- `humanEnergy` = `tired` (default), `peak`, or `auto` (drifts from peak to tired over `autoFatigueMinutes`, 180).

The capture game's Surprise! round is optional. It isn't counted in coverage, and its times are only listed in the
profile (`surpriseRoundMs`) for reference.

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

The capture game's **Surprise!** round (a block pops up at a random moment and place; turn and click it) is the only
source of the reaction-time fit (log-normal, `reaction` in the profile; needs 5+ hits, otherwise a built-in 230 ms
median is used). `humanReactionDelay` (default on) applies a sampled delay only when a new, unexpected look target
appears: in Ostinato that is combat aim (`CombatAim`) when a fight starts or the target jumps more than 60 degrees
(a new threat, a switch after being hit). Path following, headSteering, mining, parkour and other planned looks are
never delayed: any non-reactive look cancels a pending hold at once (`ReactionGate`).


# Hidden mode

`runMode` chooses how a swarm bot's client window and renderer run. It is off the normal path unless a flag or `#set` turns it on. Pathing, networking, and TenorClef tasks are not switched off by the setting itself.

| Value | What this stage does |
| --- | --- |
| `normal` (default) | The window, rendering, mouse grab, and pathing stay as they were before this setting. |
| `hidden` | The SDL window stays hidden, the GL context stays alive, and rendering is reduced. The render thread still runs. |
| `headless` | True headless. See [Headless mode](headless-mode.md). It does not use this page's 1 fps reducer, and it does not show the window. |

```
#set runMode normal
#set runMode hidden
#set runMode headless
```

Any other token is rejected. The message says the setting expects `normal`, `hidden`, or `headless`.

## JVM property

The window is created before `baritone/settings.txt` loads. The initial window policy reads only:

```
-Dostinato.runMode=normal|hidden|headless
```

A missing or blank property is `normal`. A known token is copied onto the setting when settings finish loading, so the property wins that initial mode. After that, `#set runMode` changes the mode. An unknown property token is rejected, the window stays on the normal policy, and the loaded setting is left as it was.

## Where it lives

`RunMode.current()`, `isHidden()`, and `isHeadless()` are the helper. Before settings are installed they follow the property. Afterwards they follow the setting.

## Window

The 26.3 client creates its window with SDL, not GLFW. Hidden mode OR's `SDL_WINDOW_HIDDEN` into those create flags and does not call `SDL_ShowWindow`. The GPU backend still creates the GL context. The window is not destroyed, and `SDL_RaiseWindow` is not called.

`normal` with no `-Dostinato.windowPos` is unchanged. `normal` with `-Dostinato.windowPos=x,y` still creates the window hidden, moves it, and shows it without activating (`SDL_WINDOW_ACTIVATE_WHEN_SHOWN` and `SDL_WINDOW_ACTIVATE_WHEN_RAISED` are `0`).

While hidden, mouse grab and release do not touch the OS cursor. `#set runMode normal`, or `RenderSwap.requestPresent()`, shows the window at `-Dostinato.windowPos` or at **2600, 40**, restores the size from before it was shrunk, and does not take focus. `requestPresent` does not change the stored mode. It is not a POV switcher.

## Render reduction

The render thread keeps running. Hidden does not cancel `renderFrame` or the client tick.

While hidden, and while the bot is not being presented:

| Cut | Value |
| --- | --- |
| World draws | about 1 per second (`GameRenderer.renderLevel` only; the rest of the frame still runs) |
| Display limiter | at most 20 fps, and never higher than the user's cap. This is not 1. `limitDisplayFPS` sleeps the tick thread. |
| Framebuffer | 320x240, the client's own minimum. 16 was the ask; the window clamps below that. |
| Chunk meshes | async builds and transparency resorts are skipped. A synchronous compile is kept. |
| Textures | atlas stitch uses mip 0; animation uploads stop. The base atlas upload is kept. |
| Sound | volume 0. The sound engine still exists and still ticks. |

`normal` does not apply any of these. `headless` does not use this 1 fps reducer. Its own cuts are in [Headless mode](headless-mode.md).

The gate checklist is `docs/hidden-mode-gate.md`. Decisions that the design doc left open are in `HIDDEN_MODE_NOTES.md` at the repo root. Live memory, CPU, tick health, path quality, and the real hide/show are not run from this unit-test stage. The number to beat, from Phase 0 visible 1.75, is about 2341 MB peak private.

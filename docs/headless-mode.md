# Headless mode

`runMode headless` is the true-headless path. It is off unless `-Dostinato.runMode=headless` or `#set runMode headless` turns it on. `normal` stays the client from before this setting, including `-Dostinato.windowPos`. `hidden` stays the hidden-window path in [Hidden mode](hidden-mode.md): a real hidden SDL window and a real GL context.

```
#set runMode normal
#set runMode hidden
#set runMode headless
```

Any other token is rejected. The JVM property is the mode at window creation. A known token is copied onto the setting once. After that, `#set` wins for drawing. It does not create a GPU device that this process skipped, and it does not destroy a GL context this process already created.

## What headless cuts

The client tick still runs. `runTick` is not skipped. Networking, chunk receiving, inventory, pathing, chat packets, and TenorClef tasks stay on.

| Cut | While this process was started `headless` |
| --- | --- |
| SDL and the GPU device | Not created. No `SDL_Init`, no `SDL_CreateWindow`, no `GpuBackend.loadLibrary`, no `GpuBackend.createDevice`, no `GpuBackend.createWindow`, no GL context. |
| Window object | A `Window` is still constructed so the client tick has one. `createWindow` returns the fake handle `1`. That long is not an SDL window id and is never passed to SDL. |
| Device object | `RenderSystem.initRenderer` receives a stub `GpuDevice`. Its textures, buffers, sampler, command encoder, and surface do nothing. The surface is never acquired, so the frame does not blit or present. |
| Render thread | `Main` does not rename the thread to "Render thread" and does not call `RenderSystem.initRenderThread`. `isOnRenderThread` stays true so a tick assert does not throw. Hidden and normal still record the thread. |
| World draw | `GameRenderer.render` is cancelled, not only `renderLevel`. |
| Display limiter | At most 20, and never higher than the user's cap. Not 1. `limitDisplayFPS` sleeps the tick thread. |
| Chunk meshes | Async builds, transparency resorts, and `compileSync` are skipped. There is no GPU buffer to finish. |
| Textures | Atlas stitch uses mip 0. `TextureAtlas.upload` and animation uploads are skipped. |
| Fonts and shaders | `FontManager.reload` and `ShaderManager.reload` return the reload barrier without building glyphs or compiling shaders. `Minecraft.loadCriticalShaders` is cancelled. |
| Sound | `SoundEngine.reload` is cancelled, so the OpenAL playback device is not opened. Volume is reported as 0. The `SoundEngine` object still exists. Leaving headless reloads it once. |
| Clock and quit | `Blaze3D.getTime` uses `System.nanoTime`. The Alt+F4 shortcut does not read SDL. `Minecraft.close` does not call `SDL_Quit`. |

`normal` and `hidden` do not take these flags. Hidden still uses its own 1 fps reducer, still creates the hidden SDL window, and still keeps the GL context so a later render-swap can show it.

A process started headless keeps `skipGpuWorkNow` on even after `#set runMode normal`, because there is no device to draw with. A process started normal or hidden keeps its device. `#set runMode headless` on that process skips drawing and atlas upload, and does not destroy the window or the context.

## What a headless tick still has

`Minecraft.renderFrame` still runs. It calls `GameRenderer.update` and `GameRenderer.extract`, then `GameRenderer.render`, which returns immediately. The stub surface reports not acquired, so the blit and the present are skipped. `DynamicGpuData` and `SamplerCache` are still constructed against the stub during `initRenderer`. `LevelRenderer.endFrame` still runs. `LevelExtractor` and `CachedWorld` are unchanged.

The `Font` object created before resource reload can still be there. Glyph textures are not uploaded. A tick that measures text may throw. That is a live-run question, not a unit-test result.

`ViewCapability.canRenderSwap` is true only for `hidden`. `ViewCapability.canStreamView` is true only for `headless`. Nothing here opens a socket or draws a stream. A later full stream would send position, view angle, dimension, nearby chunks, entities, health, hunger, and inventory.

The gate checklist is `docs/headless-mode-gate.md`. The conservative readings are in `HEADLESS_MODE_NOTES.md`. Live memory, tick health, path quality, and a real server join were not run. The design-doc estimate of about 0.8–1.1 GB private is not a result.

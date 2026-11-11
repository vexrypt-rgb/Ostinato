/*
 * Ostinato: Main names the main thread "Render thread" and then records it. Headless skips both.
 * Hidden and normal still do. The Minecraft constructor is not skipped.
 */
package baritone.launch.mixins;

import baritone.api.headless.HeadlessPolicy;
import baritone.swarm.center.desktop.CommandCenterAwt;
import baritone.api.run.RunMode;
import baritone.swarm.spawn.SwarmSpawnParentWatcher;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.main.Main;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import joptsimple.ArgumentAcceptingOptionSpec; // Ostinato: offline spawned children have no launcher token.

@Mixin(Main.class)
public class MixinHeadlessMain {

    // Ostinato: MC 26.3 forces AWT headless in Main clinit. Undo that for normal and hidden so #center can open a Swing window.
    @Inject(method = "<clinit>", at = @At("TAIL"))
    private static void ostinato$awtForCommandCenter(CallbackInfo ci) {
        CommandCenterAwt.allowWindow();
    }

    // Ostinato: start the parent-death watcher before the long Minecraft constructor, not only after Baritone loads.
    @Inject(method = "main([Ljava/lang/String;)V", at = @At("HEAD"), remap = false)
    private static void ostinato$watchParent(String[] args, CallbackInfo ci) {
        SwarmSpawnParentWatcher.install();
    }

    // Ostinato: splice a protected auth file into the in-memory args. The OS command line is unchanged.
    @ModifyVariable(method = "main([Ljava/lang/String;)V", at = @At("HEAD"), argsOnly = true, ordinal = 0, remap = false)
    private static String[] ostinato$authFile(String[] args) {
        return baritone.swarm.spawn.SpawnAuth.splice(args);
    }

    // Ostinato: vanilla requires a token even offline. Only our explicitly offline headless children
    // may omit it; empty session credentials cannot authenticate to an online-mode server.
    @Redirect(method = "main", at = @At(value = "INVOKE",
            target = "Ljoptsimple/ArgumentAcceptingOptionSpec;required()Ljoptsimple/ArgumentAcceptingOptionSpec;",
            remap = false))
    private static ArgumentAcceptingOptionSpec<String> ostinato$offlineToken(ArgumentAcceptingOptionSpec<String> option) {
        if (option.options().contains("accessToken") && RunMode.windowCreateMode() == RunMode.Value.HEADLESS
                && System.getProperty("ostinato.swarm.spawnParentPid") != null
                && Boolean.getBoolean("ostinato.swarm.spawnOffline")) {
            return option.defaultsTo("");
        }
        return option.required();
    }

    // Ostinato: only the "Render thread" rename is skipped. Other names still apply.
    @Redirect(
            method = "main",
            at = @At(value = "INVOKE", target = "Ljava/lang/Thread;setName(Ljava/lang/String;)V")
    )
    private static void ostinato$name(Thread thread, String name) {
        if (HeadlessPolicy.skipRenderThread(RunMode.windowCreateMode()) && "Render thread".equals(name)) {
            return;
        }
        thread.setName(name);
    }

    // Ostinato: do not record the render thread. initRenderThread itself also no-ops, this is the call site.
    @Redirect(
            method = "main",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/systems/RenderSystem;initRenderThread()V"
            )
    )
    private static void ostinato$renderThread() {
        if (HeadlessPolicy.skipRenderThread(RunMode.windowCreateMode())) {
            return;
        }
        RenderSystem.initRenderThread();
    }
}

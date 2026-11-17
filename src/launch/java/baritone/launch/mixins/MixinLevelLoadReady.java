/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.launch.mixins;

import baritone.api.headless.HeadlessPolicy;
import baritone.api.hidden.HiddenRenderPolicy;
import baritone.api.hidden.RenderSwap;
import baritone.api.run.RunMode;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.SectionPos;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Ostinato: 26.3 lets the player into the world once the section the player stands in has been compiled into a mesh,
 * or after a 30 s timeout. Hidden (while not presenting) and headless skip mesh builds, so they always waited the full
 * 30 s: no movement packets and no PlayerLoaded until then, where a visible client sends PlayerLoaded about a second
 * after joining (Phase 0 and parity runs, 15-16 Nov 2026). In those modes the player's chunk being present in the
 * client level stands in for the compiled section, which is what a rendering client waits on before compiling it.
 * Everything else in the check, the timeout included, is vanilla.
 */
@Mixin(targets = "net.minecraft.client.multiplayer.LevelLoadTracker$WaitingForPlayerChunk")
public class MixinLevelLoadReady {

    @Shadow
    @Final
    private LocalPlayer player;

    @Shadow
    @Final
    private ClientLevel level;

    @Inject(method = "isReady", at = @At("RETURN"), cancellable = true, require = 0)
    private void ostinato$readyWithoutMeshes(CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ()) {
            return;
        }
        RunMode.Value mode = RunMode.current();
        boolean meshesSkipped = HiddenRenderPolicy.skipChunkMeshBuild(mode, RenderSwap.isPresenting())
                || HeadlessPolicy.skipAsyncChunkMesh(mode);
        if (!meshesSkipped || player == null || level == null || !player.isAlive()) {
            return;
        }
        // Ostinato: ask the chunk source; 26.3's ClientLevel.hasChunk is always true, so it made this check a no-op.
        if (level.getChunkSource().hasChunk(SectionPos.blockToSectionCoord(player.getBlockX()), SectionPos.blockToSectionCoord(player.getBlockZ()))) {
            cir.setReturnValue(true);
        }
    }
}

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

package baritone.utils.player;

import baritone.api.utils.IPlayerController;
import baritone.swarm.lease.LeaseFence;
import baritone.utils.accessor.IPlayerControllerMP;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;


/**
 * Implementation of {@link IPlayerController} that chains to the primary player controller's methods
 *
 * @author Brady
 * @since 12/14/2018
 */
public final class BaritonePlayerController implements IPlayerController {

    private final Minecraft mc;

    public BaritonePlayerController(Minecraft mc) {
        this.mc = mc;
    }

    @Override
    public void syncHeldItem() {
        ((IPlayerControllerMP) mc.gameMode).callSyncCurrentPlayItem();
    }

    @Override
    public boolean hasBrokenBlock() {
        return !((IPlayerControllerMP) mc.gameMode).isHittingBlock();
    }

    @Override
    public boolean onPlayerDamageBlock(BlockPos pos, Direction side) {
        if (LeaseFence.denied()) { // Ostinato: no break while a leased command runs on an invalid lease
            return false;
        }
        return mc.gameMode.continueDestroyBlock(pos, side);
    }

    @Override
    public void resetBlockRemoving() {
        mc.gameMode.stopDestroyBlock();
    }

    @Override
    public void windowClick(int windowId, int slotId, int mouseButton, ContainerInput type, Player player) {
        // Ostinato: shared containers (windowId != 0) are fenced; the player's own inventory (0) never is.
        if (windowId != 0 && LeaseFence.denied()) {
            return;
        }
        mc.gameMode.handleContainerInput(windowId, slotId, mouseButton, type, player);
    }

    @Override
    public GameType getGameType() {
        return mc.gameMode.getPlayerMode();
    }

    @Override
    public InteractionResult processRightClickBlock(LocalPlayer player, Level world, InteractionHand hand, BlockHitResult result) {
        // primaryplayercontroller is always in a ClientWorld so this is ok
        if (LeaseFence.denied()) { // Ostinato: no place/use-on-block on an invalid lease
            return InteractionResult.PASS;
        }
        return mc.gameMode.useItemOn(player, hand, result);
    }

    @Override
    public InteractionResult processRightClick(LocalPlayer player, Level world, InteractionHand hand) {
        return mc.gameMode.useItem(player, hand);
    }

    @Override
    public boolean clickBlock(BlockPos loc, Direction face) {
        if (LeaseFence.denied()) { // Ostinato: no break start on an invalid lease
            return false;
        }
        return mc.gameMode.startDestroyBlock(loc, face);
    }

    @Override
    public void setHittingBlock(boolean hittingBlock) {
        ((IPlayerControllerMP) mc.gameMode).setIsHittingBlock(hittingBlock);
    }
}
